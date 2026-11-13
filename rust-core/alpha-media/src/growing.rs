//! An MP4 still being written by the encoder: which of its bytes are final.
//!
//! The send encoder only ever appends samples to one `mdat` and keeps rewriting
//! its `moov` index — in a reserved slot in front while it fits, behind the
//! samples once it does not. Either way that index describes every sample it
//! has written by then, so the end of its last sample is the end of the final
//! bytes. Everything in front of the `mdat` payload (the header region) changes
//! at the finish and is never sent early.
//!
//! The finished file is sent as written — index in front or behind — and the
//! player reads it by range, so bytes sent early never move.

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use crate::crypto::{StreamCrypto, STREAM_CHUNK_SIZE};
use crate::media_ref::TransportObjectId;
use crate::resume::ConfirmedChunk;

struct TopBox {
    kind: [u8; 4],
    start: u64,
    header: u64,
    /// None: the box runs to the end of the file (size 0).
    size: Option<u64>,
}

fn top_boxes(path: &Path) -> std::io::Result<(Vec<TopBox>, u64)> {
    let mut f = std::fs::File::open(path)?;
    let len = f.metadata()?.len();
    let mut out = Vec::new();
    let mut pos = 0u64;
    while pos + 8 <= len && out.len() < 64 {
        f.seek(SeekFrom::Start(pos))?;
        let mut h = [0u8; 8];
        f.read_exact(&mut h)?;
        let small = u32::from_be_bytes([h[0], h[1], h[2], h[3]]) as u64;
        let kind = [h[4], h[5], h[6], h[7]];
        let (header, size) = match small {
            0 => (8, None),
            1 => {
                if pos + 16 > len { break; }
                let mut l = [0u8; 8];
                f.read_exact(&mut l)?;
                (16, Some(u64::from_be_bytes(l)))
            }
            s => (8, Some(s)),
        };
        out.push(TopBox { kind, start: pos, header, size });
        match size {
            Some(s) if s >= header => pos += s,
            _ => break,
        }
    }
    Ok((out, len))
}

/// The index is a few hundred KB even for an hour of video; anything far larger is not one.
const MAX_MOOV: u64 = 32 * 1024 * 1024;

fn read_at(path: &Path, start: u64, n: u64) -> Option<Vec<u8>> {
    let mut f = std::fs::File::open(path).ok()?;
    f.seek(SeekFrom::Start(start)).ok()?;
    let mut buf = vec![0u8; n as usize];
    f.read_exact(&mut buf).ok()?;
    Some(buf)
}

/// `(payload_start, settled_end)` of the `mdat`: bytes in that range are final.
/// None while there is no complete, stable index to read it from.
pub fn settled_range(path: &Path) -> Option<(u64, u64)> {
    let (boxes, len) = top_boxes(path).ok()?;
    let mdat = boxes.iter().find(|b| &b.kind == b"mdat")?;
    let moov = boxes.iter().find(|b| &b.kind == b"moov")?;
    let size = moov.size?;
    if size > MAX_MOOV || moov.start + size > len {
        return None;
    }
    // The encoder rewrites the index in place while we read: two equal reads
    // mean neither caught it half-written.
    let a = read_at(path, moov.start, size)?;
    let b = read_at(path, moov.start, size)?;
    if a != b {
        return None;
    }
    let start = mdat.start + mdat.header;
    let end = samples_end(&a[moov.header as usize..])?.min(len);
    (end > start).then_some((start, end))
}

/// Child boxes of a container payload: (kind, payload).
fn children(buf: &[u8]) -> impl Iterator<Item = ([u8; 4], &[u8])> {
    let mut pos = 0usize;
    std::iter::from_fn(move || {
        if pos + 8 > buf.len() { return None; }
        let small = u32::from_be_bytes(buf[pos..pos + 4].try_into().ok()?) as usize;
        let kind: [u8; 4] = buf[pos + 4..pos + 8].try_into().ok()?;
        let (header, size) = match small {
            1 => {
                if pos + 16 > buf.len() { return None; }
                (16, u64::from_be_bytes(buf[pos + 8..pos + 16].try_into().ok()?) as usize)
            }
            0 => (8, buf.len() - pos),
            s => (8, s),
        };
        if size < header || pos + size > buf.len() { return None; }
        let payload = &buf[pos + header..pos + size];
        pos += size;
        Some((kind, payload))
    })
}

fn child<'a>(buf: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    children(buf).find(|(k, _)| k == kind).map(|(_, p)| p)
}

fn be32(b: &[u8], at: usize) -> Option<u64> {
    Some(u32::from_be_bytes(b.get(at..at + 4)?.try_into().ok()?) as u64)
}

fn be64(b: &[u8], at: usize) -> Option<u64> {
    Some(u64::from_be_bytes(b.get(at..at + 8)?.try_into().ok()?))
}

/// End of the last sample any track's tables place in the file.
fn samples_end(moov: &[u8]) -> Option<u64> {
    let mut end: Option<u64> = None;
    for (kind, trak) in children(moov) {
        if &kind != b"trak" { continue; }
        let stbl = child(trak, b"mdia").and_then(|m| child(m, b"minf")).and_then(|m| child(m, b"stbl"))?;
        if let Some(e) = track_end(stbl)? {
            end = Some(end.map_or(e, |x| x.max(e)));
        }
    }
    end
}

/// Some(None): a track with no samples yet. None: tables that do not add up.
fn track_end(stbl: &[u8]) -> Option<Option<u64>> {
    let stsz = child(stbl, b"stsz")?;
    let stsc = child(stbl, b"stsc")?;
    let (offsets, wide) = match child(stbl, b"stco") {
        Some(b) => (b, false),
        None => (child(stbl, b"co64")?, true),
    };
    let chunks = be32(offsets, 4)?;
    if chunks == 0 { return Some(None); }
    let last_off = if wide { be64(offsets, 8 + 8 * (chunks as usize - 1))? } else { be32(offsets, 8 + 4 * (chunks as usize - 1))? };

    // Samples per chunk for the last chunk: the last run starting at or before it (1-based).
    let runs = be32(stsc, 4)? as usize;
    let mut per_chunk = None;
    for i in 0..runs {
        let first = be32(stsc, 8 + 12 * i)?;
        if first <= chunks { per_chunk = Some(be32(stsc, 8 + 12 * i + 4)?); }
    }
    let n = per_chunk?;

    let fixed = be32(stsz, 4)?;
    let count = be32(stsz, 8)?;
    if n == 0 || n > count { return None; }
    let bytes = if fixed > 0 {
        fixed * n
    } else {
        let mut sum = 0u64;
        for s in (count - n)..count {
            sum += be32(stsz, 12 + 4 * s as usize)?;
        }
        sum
    };
    Some(Some(last_off + bytes))
}

/// Read chunk [index] of [path] whole; None if the file is shorter.
pub fn read_chunk(path: &Path, index: u32) -> Option<Vec<u8>> {
    let mut f = std::fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    let start = index as u64 * STREAM_CHUNK_SIZE as u64;
    if start >= len { return None; }
    let n = (len - start).min(STREAM_CHUNK_SIZE as u64) as usize;
    f.seek(SeekFrom::Start(start)).ok()?;
    let mut buf = vec![0u8; n];
    f.read_exact(&mut buf).ok()?;
    Some(buf)
}

/// Every chunk sent early is byte-for-byte the finished file's chunk.
///
/// Checked locally before anything else goes out under the same key: chunk
/// nonces depend on (key, index) alone, so a changed chunk re-sent under it
/// would reuse the keystream.
pub fn verify_confirmed(path: &Path, key: &[u8; 32], confirmed: &[ConfirmedChunk]) -> bool {
    confirmed.iter().all(|c| {
        let Some(pt) = read_chunk(path, c.index) else { return false };
        if pt.len() as u64 != c.plaintext_bytes { return false; }
        let Ok(enc) = StreamCrypto::encrypt_chunk(&pt, key, c.index) else { return false };
        let Ok(blob) = crate::blob_transport::wrap_chunk(key, c.index, &enc.ciphertext) else { return false };
        TransportObjectId::of_bytes(&blob) == c.transport_object_id && hex::encode(enc.hash) == c.chunk_hash
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn boxed(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
        let mut v = ((body.len() + 8) as u32).to_be_bytes().to_vec();
        v.extend_from_slice(kind);
        v.extend_from_slice(body);
        v
    }

    fn full(kind: &[u8; 4], words: &[u32]) -> Vec<u8> {
        let mut body = vec![0u8; 4];
        for w in words { body.extend_from_slice(&w.to_be_bytes()); }
        boxed(kind, &body)
    }

    /// One track: chunks at [offsets], [per_chunk] samples each, sizes [sizes].
    fn moov(offsets: &[u32], per_chunk: u32, sizes: &[u32]) -> Vec<u8> {
        let mut stco = vec![offsets.len() as u32];
        stco.extend_from_slice(offsets);
        let mut stsz = vec![0, sizes.len() as u32];
        stsz.extend_from_slice(sizes);
        let stbl = [full(b"stsz", &stsz), full(b"stsc", &[1, 1, per_chunk, 1]), full(b"stco", &stco)].concat();
        let trak = boxed(b"trak", &boxed(b"mdia", &boxed(b"minf", &boxed(b"stbl", &stbl))));
        boxed(b"moov", &trak)
    }

    fn write(name: &str, parts: &[Vec<u8>]) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("am_growing_{name}_{}.mp4", uuid::Uuid::new_v4()));
        let mut f = std::fs::File::create(&p).unwrap();
        for part in parts { f.write_all(part).unwrap(); }
        p
    }

    fn mdat_large(declared_payload: u64, payload: &[u8]) -> Vec<u8> {
        let mut v = 1u32.to_be_bytes().to_vec();
        v.extend_from_slice(b"mdat");
        v.extend_from_slice(&(declared_payload + 16).to_be_bytes());
        v.extend_from_slice(payload);
        v
    }

    #[test]
    fn index_in_front_settles_up_to_its_last_sample() {
        let ftyp = boxed(b"ftyp", &[0; 16]);
        // Sizes are fixed by the index's own length, so place it first, then fill offsets.
        let probe = moov(&[0, 0], 2, &[100, 100, 50, 70]);
        let free = boxed(b"free", &[0; 100]);
        let payload = (ftyp.len() + probe.len() + free.len() + 16) as u32;
        let index = moov(&[payload, payload + 200], 2, &[100, 100, 50, 70]);
        // 900 bytes written, the index knows of 320: the rest is not final yet.
        let p = write("f", &[ftyp, index, free, mdat_large(900, &[7; 900])]);
        assert_eq!(settled_range(&p), Some((payload as u64, payload as u64 + 320)));
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn index_behind_the_samples_settles_the_same_way() {
        let ftyp = boxed(b"ftyp", &[0; 16]);
        let free = boxed(b"free", &[0; 100]);
        let payload = (ftyp.len() + free.len() + 16) as u32;
        // The muxer's extension mode: room past the samples, the index after it.
        let index = moov(&[payload], 3, &[10, 20, 30]);
        let p = write("b", &[ftyp, free, mdat_large(500, &[7; 500]), index]);
        assert_eq!(settled_range(&p), Some((payload as u64, payload as u64 + 60)));
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn no_index_means_nothing_is_settled() {
        let p = write("e", &[boxed(b"ftyp", &[0; 16]), boxed(b"free", &[0; 64]), mdat_large(500, &[7; 500])]);
        assert_eq!(settled_range(&p), None);
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn a_changed_chunk_fails_verification() {
        let key = [9u8; 32];
        let data = vec![5u8; STREAM_CHUNK_SIZE * 2];
        let p = write("v", &[data.clone()]);
        let pt = read_chunk(&p, 1).unwrap();
        let enc = StreamCrypto::encrypt_chunk(&pt, &key, 1).unwrap();
        let blob = crate::blob_transport::wrap_chunk(&key, 1, &enc.ciphertext).unwrap();
        let c = ConfirmedChunk {
            index: 1, total: 0,
            transport_object_id: TransportObjectId::of_bytes(&blob),
            plaintext_bytes: pt.len() as u64,
            ciphertext_bytes: enc.ciphertext.len() as u64,
            chunk_hash: hex::encode(enc.hash),
            locations: vec![],
        };
        assert!(verify_confirmed(&p, &key, std::slice::from_ref(&c)));
        let mut changed = data;
        changed[STREAM_CHUNK_SIZE + 3] = 6;
        std::fs::write(&p, changed).unwrap();
        assert!(!verify_confirmed(&p, &key, &[c]));
        let _ = std::fs::remove_file(p);
    }
}
