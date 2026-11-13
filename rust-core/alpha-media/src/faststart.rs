//! MP4 "faststart" transform (a.k.a. qt-faststart).
//!
//! Many MP4 files (especially straight from a phone camera or a generic
//! encoder) place the `moov` atom — the index that maps presentation time to
//! byte offsets — at the *end* of the file, after the `mdat` media payload.
//! A streaming player can't render a single frame until it has the `moov`,
//! so with the index at the end it must download nearly the whole file first.
//! That is the single biggest reason streamed video "takes a while before it
//! plays".
//!
//! This module rewrites such files so `moov` comes *before* `mdat` (right
//! after `ftyp`). The byte offsets stored inside `moov` (`stco`/`co64` chunk
//! offset tables) point at absolute positions in the file, so when `moov`
//! moves in front of `mdat` every entry is shifted by the size of the moved
//! `moov` atom and must be patched.
//!
//! The transform is a no-op (returns `None`) when:
//! - the data isn't a parseable MP4,
//! - `moov` already precedes `mdat` (already fast-start),
//! - any structural assumption fails (we never corrupt — we just bail).

/// Attempt to rewrite `data` so the `moov` atom precedes `mdat`.
///
/// Returns `Some(rewritten)` when a faststart copy was produced, or `None`
/// when no change is needed/possible (caller should use the original bytes).
pub fn to_faststart(data: &[u8]) -> Option<Vec<u8>> {
    let atoms = parse_top_level(data)?;

    let ftyp = atoms.iter().find(|a| &a.kind == b"ftyp");
    let moov = atoms.iter().find(|a| &a.kind == b"moov")?;
    let mdat = atoms.iter().find(|a| &a.kind == b"mdat")?;

    // Already fast-start: moov sits before mdat → nothing to do.
    if moov.start < mdat.start {
        return None;
    }

    // Extract and patch a private copy of the moov atom.
    let mut moov_buf = data[moov.start..moov.end()].to_vec();
    let moov_header = moov.header_len;
    // Every absolute chunk offset shifts forward by the whole moov atom size.
    let delta = moov_buf.len() as u64;
    patch_chunk_offsets(&mut moov_buf[moov_header..], delta);

    // Rebuild: ftyp (if any) → moov → everything else in original order
    // (skipping the old moov, which we've relocated).
    let mut out = Vec::with_capacity(data.len());
    if let Some(f) = ftyp {
        out.extend_from_slice(&data[f.start..f.end()]);
    }
    out.extend_from_slice(&moov_buf);
    for a in &atoms {
        if &a.kind == b"moov" || (ftyp.is_some() && &a.kind == b"ftyp") {
            continue;
        }
        out.extend_from_slice(&data[a.start..a.end()]);
    }
    Some(out)
}

/// Streaming faststart: rewrite `src` → `dst` with `moov` moved in front of
/// `mdat`, **without ever holding the file in memory**.
///
/// Peak memory is O(moov) + a 1 MiB copy buffer — `moov` is the small index
/// atom (a few MB at most), while `mdat` (the bulk) is stream-copied. This is
/// the file-based counterpart of [`to_faststart`], which needs the whole file
/// as a slice and therefore costs O(file).
///
/// Returns `Ok(true)` when `dst` was written, `Ok(false)` when no rewrite was
/// needed or possible (caller keeps using `src` — we never corrupt).
pub fn to_faststart_file(
    src: &std::path::Path,
    dst: &std::path::Path,
) -> std::io::Result<bool> {
    use std::io::{Read, Seek, SeekFrom, Write};

    let mut f = std::fs::File::open(src)?;
    let file_len = f.metadata()?.len();

    // 1. Walk the top-level atom list reading ONLY headers (8/16 bytes each),
    //    seeking over the payloads. O(1) memory.
    let atoms = match read_top_level(&mut f, file_len)? {
        Some(a) => a,
        None => return Ok(false),
    };
    let ftyp = atoms.iter().find(|a| &a.kind == b"ftyp").cloned();
    let (moov, mdat) = match (
        atoms.iter().find(|a| &a.kind == b"moov").cloned(),
        atoms.iter().find(|a| &a.kind == b"mdat").cloned(),
    ) {
        (Some(m), Some(d)) => (m, d),
        _ => return Ok(false),
    };
    // Already fast-start → nothing to do.
    if moov.start < mdat.start {
        return Ok(false);
    }
    // Guard: a pathological moov must not blow up memory.
    const MAX_MOOV: u64 = 64 * 1024 * 1024;
    if moov.size as u64 > MAX_MOOV {
        return Ok(false);
    }

    // 2. Pull ONLY the moov atom into RAM and patch its absolute chunk offsets:
    //    every entry shifts forward by the size of the atom we're relocating.
    let mut moov_buf = vec![0u8; moov.size];
    f.seek(SeekFrom::Start(moov.start as u64))?;
    f.read_exact(&mut moov_buf)?;
    patch_chunk_offsets(&mut moov_buf[moov.header_len..], moov.size as u64);

    // 3. Write ftyp → moov → everything else, stream-copying the payloads.
    let mut out = std::io::BufWriter::new(std::fs::File::create(dst)?);
    if let Some(a) = &ftyp {
        copy_range(&mut f, &mut out, a.start as u64, a.size as u64)?;
    }
    out.write_all(&moov_buf)?;
    drop(moov_buf); // release the index before the big copy
    for a in &atoms {
        if &a.kind == b"moov" || (ftyp.is_some() && &a.kind == b"ftyp") {
            continue;
        }
        copy_range(&mut f, &mut out, a.start as u64, a.size as u64)?;
    }
    out.flush()?;
    Ok(true)
}

/// Copy `len` bytes from `src` at `start` into `out` in bounded blocks.
fn copy_range<W: std::io::Write>(
    src: &mut std::fs::File,
    out: &mut W,
    start: u64,
    len: u64,
) -> std::io::Result<()> {
    use std::io::{Read, Seek, SeekFrom};
    src.seek(SeekFrom::Start(start))?;
    let mut remaining = len;
    let mut buf = vec![0u8; 1024 * 1024];
    while remaining > 0 {
        let want = remaining.min(buf.len() as u64) as usize;
        src.read_exact(&mut buf[..want])?;
        out.write_all(&buf[..want])?;
        remaining -= want as u64;
    }
    Ok(())
}

/// Top-level atom list read straight from the file — headers only, payloads
/// skipped by seeking. Mirrors [`parse_top_level`] but costs O(1) memory.
fn read_top_level(f: &mut std::fs::File, file_len: u64) -> std::io::Result<Option<Vec<Atom>>> {
    use std::io::{Read, Seek, SeekFrom};
    let mut atoms = Vec::new();
    let mut pos: u64 = 0;
    while pos + 8 <= file_len {
        f.seek(SeekFrom::Start(pos))?;
        let mut hdr = [0u8; 16];
        f.read_exact(&mut hdr[..8])?;
        let size32 = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        let kind = [hdr[4], hdr[5], hdr[6], hdr[7]];
        let (header_len, size) = if size32 == 1 {
            if pos + 16 > file_len {
                return Ok(None);
            }
            f.read_exact(&mut hdr[8..16])?;
            (16u64, u64::from_be_bytes(hdr[8..16].try_into().unwrap()))
        } else if size32 == 0 {
            (8u64, file_len - pos)
        } else {
            (8u64, size32)
        };
        if size < header_len || pos + size > file_len {
            return Ok(None);
        }
        atoms.push(Atom {
            kind,
            start: pos as usize,
            size: size as usize,
            header_len: header_len as usize,
        });
        pos += size;
    }
    Ok(if atoms.is_empty() { None } else { Some(atoms) })
}

#[derive(Clone)]
struct Atom {
    kind: [u8; 4],
    start: usize,
    size: usize,
    header_len: usize,
}

impl Atom {
    fn end(&self) -> usize {
        self.start + self.size
    }
}

/// Parse the top-level atom list. Returns `None` on any malformed structure.
fn parse_top_level(data: &[u8]) -> Option<Vec<Atom>> {
    let mut atoms = Vec::new();
    let mut pos = 0usize;
    while pos + 8 <= data.len() {
        let size32 = be_u32(data, pos)? as usize;
        let kind = [data[pos + 4], data[pos + 5], data[pos + 6], data[pos + 7]];
        let (header_len, size) = if size32 == 1 {
            // 64-bit extended size in the next 8 bytes.
            if pos + 16 > data.len() {
                return None;
            }
            (16usize, be_u64(data, pos + 8)? as usize)
        } else if size32 == 0 {
            // Extends to end of file.
            (8usize, data.len() - pos)
        } else {
            (8usize, size32)
        };
        if size < header_len || pos + size > data.len() {
            return None;
        }
        atoms.push(Atom { kind, start: pos, size, header_len });
        pos += size;
    }
    if atoms.is_empty() {
        None
    } else {
        Some(atoms)
    }
}

/// Recursively walk container boxes inside `moov` and add `delta` to every
/// `stco` (32-bit) / `co64` (64-bit) chunk-offset entry.
fn patch_chunk_offsets(buf: &mut [u8], delta: u64) {
    let mut pos = 0usize;
    while pos + 8 <= buf.len() {
        let size32 = match be_u32(buf, pos) {
            Some(v) => v as usize,
            None => break,
        };
        let kind = [buf[pos + 4], buf[pos + 5], buf[pos + 6], buf[pos + 7]];
        let (header_len, size) = if size32 == 1 {
            if pos + 16 > buf.len() {
                break;
            }
            (16usize, be_u64(buf, pos + 8).unwrap_or(0) as usize)
        } else if size32 == 0 {
            (8usize, buf.len() - pos)
        } else {
            (8usize, size32)
        };
        if size < header_len || pos + size > buf.len() {
            break;
        }
        let body = pos + header_len..pos + size;
        match &kind {
            b"stco" => patch_table(&mut buf[body], delta, false),
            b"co64" => patch_table(&mut buf[body], delta, true),
            // Container boxes that may hold the sample tables.
            b"trak" | b"mdia" | b"minf" | b"stbl" | b"edts" | b"udta" | b"moov" => {
                patch_chunk_offsets(&mut buf[body], delta)
            }
            _ => {}
        }
        pos += size;
    }
}

/// Patch an `stco`/`co64` full-box body: `[version:1][flags:3][count:4][entries…]`.
fn patch_table(body: &mut [u8], delta: u64, is_64: bool) {
    if body.len() < 8 {
        return;
    }
    let count = u32::from_be_bytes([body[4], body[5], body[6], body[7]]) as usize;
    let entry_size = if is_64 { 8 } else { 4 };
    let mut off = 8usize;
    for _ in 0..count {
        if off + entry_size > body.len() {
            break;
        }
        if is_64 {
            let v = u64::from_be_bytes([
                body[off], body[off + 1], body[off + 2], body[off + 3],
                body[off + 4], body[off + 5], body[off + 6], body[off + 7],
            ])
            .wrapping_add(delta);
            body[off..off + 8].copy_from_slice(&v.to_be_bytes());
        } else {
            let v = u32::from_be_bytes([body[off], body[off + 1], body[off + 2], body[off + 3]])
                .wrapping_add(delta as u32);
            body[off..off + 4].copy_from_slice(&v.to_be_bytes());
        }
        off += entry_size;
    }
}

fn be_u32(data: &[u8], pos: usize) -> Option<u32> {
    if pos + 4 > data.len() {
        return None;
    }
    Some(u32::from_be_bytes([data[pos], data[pos + 1], data[pos + 2], data[pos + 3]]))
}

fn be_u64(data: &[u8], pos: usize) -> Option<u64> {
    if pos + 8 > data.len() {
        return None;
    }
    Some(u64::from_be_bytes([
        data[pos], data[pos + 1], data[pos + 2], data[pos + 3],
        data[pos + 4], data[pos + 5], data[pos + 6], data[pos + 7],
    ]))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a minimal box: [size:4][type:4][body].
    fn boxed(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
        let size = (8 + body.len()) as u32;
        let mut v = size.to_be_bytes().to_vec();
        v.extend_from_slice(kind);
        v.extend_from_slice(body);
        v
    }

    #[test]
    fn already_faststart_returns_none() {
        let mut data = boxed(b"ftyp", b"isom");
        data.extend(boxed(b"moov", b"\x00\x00\x00\x00")); // moov before mdat
        data.extend(boxed(b"mdat", b"rawmediabytes"));
        assert!(to_faststart(&data).is_none());
    }

    #[test]
    fn non_mp4_returns_none() {
        assert!(to_faststart(b"not an mp4 at all").is_none());
    }

    #[test]
    fn moves_moov_and_patches_stco() {
        // stco with one entry pointing at the mdat body offset.
        // Layout (moov LAST): ftyp(12) + mdat(8+4) + moov(...)
        let ftyp = boxed(b"ftyp", b"isom"); // 12 bytes
        let mdat = boxed(b"mdat", b"\xAA\xBB\xCC\xDD"); // 12 bytes; body starts at offset 20

        // stco: version/flags(4) + count(4)=1 + entry(4) = offset of mdat body = 20
        let mut stco_body = vec![0u8, 0, 0, 0];
        stco_body.extend_from_slice(&1u32.to_be_bytes());
        stco_body.extend_from_slice(&20u32.to_be_bytes());
        let stco = boxed(b"stco", &stco_body);
        let stbl = boxed(b"stbl", &stco);
        let minf = boxed(b"minf", &stbl);
        let mdia = boxed(b"mdia", &minf);
        let trak = boxed(b"trak", &mdia);
        let moov = boxed(b"moov", &trak);

        let mut data = ftyp.clone();
        data.extend_from_slice(&mdat);
        data.extend_from_slice(&moov);

        let out = to_faststart(&data).expect("should rewrite");

        // New order: ftyp, moov, mdat.
        let atoms = parse_top_level(&out).unwrap();
        assert_eq!(&atoms[0].kind, b"ftyp");
        assert_eq!(&atoms[1].kind, b"moov");
        assert_eq!(&atoms[2].kind, b"mdat");

        // The single stco entry must have shifted by moov.len().
        let moov_len = moov.len() as u32;
        // Find the entry in the rewritten moov: ftyp(12) + moov header(8)+trak header(8)
        // +mdia(8)+minf(8)+stbl(8)+stco header(8)+vf(4)+count(4) = 12+8*6+8 = 68
        let entry_pos = 12 + 8 * 6 + 8;
        let patched = u32::from_be_bytes([
            out[entry_pos], out[entry_pos + 1], out[entry_pos + 2], out[entry_pos + 3],
        ]);
        assert_eq!(patched, 20 + moov_len);
    }
}
