//! Memory benchmark for streaming crypto.
//! Compares the peak RSS of the old path (upload_streaming: the whole file and
//! its ciphertext in RAM) with the new one (upload_streaming_file: O(chunk)).
//! No network: only memory and CPU of read → encrypt → envelope.
//! Run: `cargo run --release -p alpha-media --example blossom_probe`.

use alpha_media::blob_transport::{active_codec, BlobMetrics};
use alpha_media::crypto::{generate_key, StreamCrypto, STREAM_CHUNK_SIZE};
use std::io::{Read, Write};

/// Current resident set size (Linux) in bytes.
fn rss_bytes() -> u64 {
    let s = std::fs::read_to_string("/proc/self/statm").unwrap_or_default();
    let pages: u64 = s.split_whitespace().nth(1).and_then(|v| v.parse().ok()).unwrap_or(0);
    pages * 4096
}

fn make_file(path: &std::path::Path, size: usize) {
    let mut f = std::io::BufWriter::new(std::fs::File::create(path).unwrap());
    let block = vec![0xABu8; 1 << 20];
    let mut left = size;
    while left > 0 {
        let n = left.min(block.len());
        f.write_all(&block[..n]).unwrap();
        left -= n;
    }
    f.flush().unwrap();
}

/// OLD shape: read whole file → encrypt whole file → all chunks resident.
fn old_path(path: &std::path::Path) -> (u64, usize) {
    let key = generate_key();
    let data = std::fs::read(path).unwrap(); // 1× file
    let chunks = StreamCrypto::encrypt_stream(&data, &key).unwrap(); // + full ciphertext
    let codec = active_codec();
    // Envelope-encode them all (as the old flow effectively held them).
    let blobs: Vec<Vec<u8>> = chunks
        .iter()
        .enumerate()
        .map(|(i, c)| codec.encode_chunk(i as u32, chunks.len() as u32, &c.ciphertext).unwrap())
        .collect();
    let peak = rss_bytes();
    let n = blobs.len();
    drop(blobs);
    drop(chunks);
    drop(data);
    (peak, n)
}

/// NEW shape: read → encrypt → envelope → drop, one chunk at a time.
fn new_path(path: &std::path::Path) -> (u64, usize) {
    let key = generate_key();
    let codec = active_codec();
    let mut f = std::io::BufReader::with_capacity(STREAM_CHUNK_SIZE, std::fs::File::open(path).unwrap());
    let mut hasher = blake3::Hasher::new();
    let mut idx = 0u32;
    let mut peak = 0u64;
    loop {
        let mut buf = vec![0u8; STREAM_CHUNK_SIZE];
        let mut filled = 0usize;
        while filled < buf.len() {
            match f.read(&mut buf[filled..]) {
                Ok(0) => break,
                Ok(n) => filled += n,
                Err(e) => panic!("{e}"),
            }
        }
        if filled == 0 {
            break;
        }
        buf.truncate(filled);
        hasher.update(&buf);
        let enc = StreamCrypto::encrypt_chunk(&buf, &key, idx).unwrap();
        drop(buf);
        let blob = codec.encode_chunk(idx, u32::MAX, &enc.ciphertext).unwrap();
        std::hint::black_box(&blob);
        drop(blob); // ← released immediately (this is the whole point)
        peak = peak.max(rss_bytes());
        idx += 1;
    }
    let _ = hasher.finalize();
    (peak, idx as usize)
}

fn mb(b: u64) -> f64 {
    b as f64 / 1_048_576.0
}

fn main() {
    let dir = std::env::temp_dir();
    for size_mb in [50usize, 200] {
        let path = dir.join(format!("am_bench_{size_mb}.bin"));
        make_file(&path, size_mb * 1_048_576);
        let base = rss_bytes();

        println!("\n================ file {size_mb} MB ================");
        println!("  baseline RSS         : {:>8.1} MB", mb(base));

        BlobMetrics::reset();
        let t = std::time::Instant::now();
        let (peak_new, chunks_new) = new_path(&path);
        let ms_new = t.elapsed().as_millis();
        println!(
            "  NEW streaming (O(chunk)) : peak {:>8.1} MB  (+{:>7.1} MB over baseline)  {chunks_new} chunks  {ms_new}ms",
            mb(peak_new), mb(peak_new.saturating_sub(base)),
        );

        let t = std::time::Instant::now();
        let (peak_old, chunks_old) = old_path(&path);
        let ms_old = t.elapsed().as_millis();
        println!(
            "  OLD whole-file (O(file)) : peak {:>8.1} MB  (+{:>7.1} MB over baseline)  {chunks_old} chunks  {ms_old}ms",
            mb(peak_old), mb(peak_old.saturating_sub(base)),
        );

        let m = BlobMetrics::snapshot();
        println!(
            "  metrics: overhead x{:.3}  encode {:.0}µs/chunk  decode {:.0}µs/chunk",
            m.overhead_ratio(), m.avg_encode_micros(), m.avg_decode_micros(),
        );
        let _ = std::fs::remove_file(&path);
    }
}
