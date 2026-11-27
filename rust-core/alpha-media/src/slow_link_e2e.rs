//! A weak link must slow a send down, never make it impossible.
//!
//! The storage here reads ALL its connections through one narrow pipe, the way a
//! poor cellular uplink is shared by every chunk in flight. Each chunk alone is
//! honest and keeps moving; only their sum is slow.

use crate::blossom::BlossomClient;
use crate::pipeline::MediaPipeline;
use crate::thumbnail::MediaType;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

/// Bytes per second the whole storage accepts, across all connections.
const LINK_RATE: usize = 120 * 1024;
const NARROW_RATE: usize = 30 * 1024;
const SLICE: usize = 4096;

struct SlowStore {
    addr: String,
    puts: Arc<AtomicUsize>,
    /// Uploads in flight at the moment each one had its body in.
    crowd: Arc<Mutex<Vec<usize>>>,
}

/// A storage that never stalls and never refuses, but reads at `rate` bytes/s in all.
/// `answer` false: it reads the request and never replies (a dead server).
async fn spawn_slow_store(answer: bool, rate: usize) -> SlowStore {
    let socket = tokio::net::TcpSocket::new_v4().unwrap();
    // A small receive window, or loopback would swallow whole chunks at once.
    let _ = socket.set_recv_buffer_size(16 * 1024);
    socket.bind("127.0.0.1:0".parse().unwrap()).unwrap();
    let listener = socket.listen(64).unwrap();
    let addr = format!("http://{}", listener.local_addr().unwrap());
    let puts = Arc::new(AtomicUsize::new(0));
    let crowd: Arc<Mutex<Vec<usize>>> = Default::default();
    let active = Arc::new(AtomicUsize::new(0));
    let pipe = Arc::new(tokio::sync::Mutex::new(()));
    let (a, p, c) = (addr.clone(), puts.clone(), crowd.clone());
    tokio::spawn(async move {
        loop {
            let Ok((mut sock, _)) = listener.accept().await else { return };
            let (a, p, c, pipe, active) = (a.clone(), p.clone(), c.clone(), pipe.clone(), active.clone());
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                active.fetch_add(1, Ordering::Relaxed);
                struct Leave(Arc<AtomicUsize>);
                impl Drop for Leave {
                    fn drop(&mut self) {
                        self.0.fetch_sub(1, Ordering::Relaxed);
                    }
                }
                let _leave = Leave(active.clone());
                let mut raw = Vec::new();
                let mut buf = [0u8; SLICE];
                let mut head_end: Option<usize> = None;
                let mut want = 0usize;
                loop {
                    // One slice at a time through the shared pipe.
                    let n = {
                        let _turn = pipe.lock().await;
                        let n = match sock.read(&mut buf).await {
                            Ok(0) | Err(_) => break,
                            Ok(n) => n,
                        };
                        tokio::time::sleep(std::time::Duration::from_secs_f64(n as f64 / rate as f64)).await;
                        n
                    };
                    raw.extend_from_slice(&buf[..n]);
                    if head_end.is_none() {
                        if let Some(i) = raw.windows(4).position(|w| w == b"\r\n\r\n") {
                            head_end = Some(i + 4);
                            let head = String::from_utf8_lossy(&raw[..i]).to_lowercase();
                            want = head
                                .lines()
                                .find_map(|l| l.strip_prefix("content-length:"))
                                .and_then(|v| v.trim().parse().ok())
                                .unwrap_or(0);
                        }
                    }
                    if let Some(h) = head_end {
                        if raw.len() >= h + want {
                            break;
                        }
                    }
                }
                let Some(h) = head_end else { return };
                // The client gave the upload up before its body was in.
                if raw.len() < h + want {
                    return;
                }
                c.lock().unwrap().push(active.load(Ordering::Relaxed));
                if !answer {
                    tokio::time::sleep(std::time::Duration::from_secs(3600)).await;
                    return;
                }
                let body = &raw[h..];
                p.fetch_add(1, Ordering::Relaxed);
                let sha = {
                    use sha2::Digest;
                    hex::encode(sha2::Sha256::digest(body))
                };
                let json = format!(
                    "{{\"url\":\"{a}/{sha}\",\"sha256\":\"{sha}\",\"size\":{},\"uploaded\":0}}",
                    body.len()
                );
                let resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{json}",
                    json.len()
                );
                let _ = sock.write_all(resp.as_bytes()).await;
                let _ = sock.shutdown().await;
            });
        }
    });
    SlowStore { addr, puts, crowd }
}

fn temp_file(chunks: usize) -> std::path::PathBuf {
    let p = std::env::temp_dir().join(format!("alpha_slow_{}.bin", uuid::Uuid::new_v4()));
    let n = chunks * crate::crypto::STREAM_CHUNK_SIZE;
    let data: Vec<u8> = (0..n).map(|i| (i as u32).wrapping_mul(2654435761) as u8).collect();
    std::fs::write(&p, data).unwrap();
    p
}

/// One full wave of chunks over a link that carries all of them in ~25 s.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "slow, shares the process-wide upload pool: cargo test -p alpha-media slow_link_e2e -- --ignored --test-threads=1"]
async fn weak_link_slows_upload_but_completes() {
    const CHUNKS: usize = 8;
    crate::link::network_changed();
    let file = temp_file(CHUNKS);
    let store = spawn_slow_store(true, LINK_RATE).await;
    let pipe = MediaPipeline::new(BlossomClient::with_servers(&[store.addr.clone()]));
    let signer = crate::blossom::MediaSigner::generate();
    let noop = |_: u64, _: u64| {};
    let started = std::time::Instant::now();
    let sent = tokio::time::timeout(
        std::time::Duration::from_secs(240),
        pipe.upload_streaming_file(&file, "f.bin", MediaType::File, &signer, [3u8; 32], &noop, &|| false, None),
    )
    .await;
    let _ = std::fs::remove_file(&file);
    let took = started.elapsed().as_secs();
    let sent = sent.unwrap_or_else(|_| panic!("upload hung for over {took} s"));
    let result = sent.unwrap_or_else(|e| panic!("weak link broke the upload after {took} s: {e}"));
    assert_eq!(result.stream_chunks.as_ref().unwrap().len(), CHUNKS);
    assert!(store.puts.load(Ordering::Relaxed) >= CHUNKS);
}

/// A link too narrow for several chunks at once: the first wave is not cut, and once
/// the window has learned the link it carries a chunk or two at a time.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "slow, shares the process-wide upload pool: cargo test -p alpha-media slow_link_e2e -- --ignored --test-threads=1"]
async fn a_narrow_link_is_carried_a_chunk_or_two_at_a_time() {
    const CHUNKS: usize = 8;
    crate::link::network_changed();
    let file = temp_file(CHUNKS);
    let store = spawn_slow_store(true, NARROW_RATE).await;
    let pipe = MediaPipeline::new(BlossomClient::with_servers(&[store.addr.clone()]));
    let signer = crate::blossom::MediaSigner::generate();
    let noop = |_: u64, _: u64| {};
    let started = std::time::Instant::now();
    let sent = tokio::time::timeout(
        std::time::Duration::from_secs(400),
        pipe.upload_streaming_file(&file, "f.bin", MediaType::File, &signer, [6u8; 32], &noop, &|| false, None),
    )
    .await;
    let _ = std::fs::remove_file(&file);
    let took = started.elapsed().as_secs();
    let sent = sent.unwrap_or_else(|_| panic!("upload hung for over {took} s"));
    sent.unwrap_or_else(|e| panic!("narrow link broke the upload after {took} s: {e}"));
    let crowd = store.crowd.lock().unwrap().clone();
    assert_eq!(store.puts.load(Ordering::Relaxed), CHUNKS, "a chunk was sent twice: {crowd:?}");
    assert!(crowd[CHUNKS - 3..].iter().all(|&n| n <= 2), "still crowded at the end: {crowd:?}");
}

/// A server that takes the bytes and never answers must not hold the send forever.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "slow, shares the process-wide upload pool: cargo test -p alpha-media slow_link_e2e -- --ignored --test-threads=1"]
async fn silent_server_does_not_hold_upload_forever() {
    crate::link::network_changed();
    let file = temp_file(1);
    let store = spawn_slow_store(false, LINK_RATE).await;
    let pipe = MediaPipeline::new(BlossomClient::with_servers(&[store.addr.clone()]));
    let signer = crate::blossom::MediaSigner::generate();
    let noop = |_: u64, _: u64| {};
    let sent = tokio::time::timeout(
        std::time::Duration::from_secs(600),
        pipe.upload_streaming_file(&file, "f.bin", MediaType::File, &signer, [4u8; 32], &noop, &|| false, None),
    )
    .await;
    let _ = std::fs::remove_file(&file);
    assert!(matches!(sent, Ok(Err(_))), "a silent server must end in an error within bounded time");
}

/// A storage that takes uploads at once and serves them through one narrow pipe.
async fn spawn_slow_reader_store() -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = format!("http://{}", listener.local_addr().unwrap());
    let blobs: Arc<std::sync::Mutex<std::collections::HashMap<String, Vec<u8>>>> = Default::default();
    let pipe = Arc::new(tokio::sync::Mutex::new(()));
    let a = addr.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut sock, _)) = listener.accept().await else { return };
            let _ = sock.set_nodelay(true);
            let (a, blobs, pipe) = (a.clone(), blobs.clone(), pipe.clone());
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut raw = Vec::new();
                let mut buf = [0u8; 16384];
                let mut head_end: Option<usize> = None;
                let mut want = 0usize;
                let mut first = String::new();
                loop {
                    let n = match sock.read(&mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
                    };
                    raw.extend_from_slice(&buf[..n]);
                    if head_end.is_none() {
                        if let Some(i) = raw.windows(4).position(|w| w == b"\r\n\r\n") {
                            head_end = Some(i + 4);
                            let head = String::from_utf8_lossy(&raw[..i]).to_string();
                            first = head.lines().next().unwrap_or("").to_string();
                            want = head
                                .to_lowercase()
                                .lines()
                                .find_map(|l| l.strip_prefix("content-length:"))
                                .and_then(|v| v.trim().parse().ok())
                                .unwrap_or(0);
                        }
                    }
                    if let Some(h) = head_end {
                        if raw.len() >= h + want {
                            break;
                        }
                    }
                }
                let Some(h) = head_end else { return };
                if first.starts_with("PUT") {
                    let body = raw[h..].to_vec();
                    let sha = {
                        use sha2::Digest;
                        hex::encode(sha2::Sha256::digest(&body))
                    };
                    let json = format!(
                        "{{\"url\":\"{a}/{sha}\",\"sha256\":\"{sha}\",\"size\":{},\"uploaded\":0}}",
                        body.len()
                    );
                    blobs.lock().unwrap().insert(sha, body);
                    let resp = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{json}",
                        json.len()
                    );
                    let _ = sock.write_all(resp.as_bytes()).await;
                } else {
                    let sha = first.split_whitespace().nth(1).unwrap_or("").trim_start_matches('/').to_string();
                    let Some(body) = blobs.lock().unwrap().get(&sha).cloned() else {
                        let _ = sock.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await;
                        return;
                    };
                    let head = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = sock.write_all(head.as_bytes()).await;
                    for part in body.chunks(SLICE) {
                        let _turn = pipe.lock().await;
                        if sock.write_all(part).await.is_err() {
                            return;
                        }
                        tokio::time::sleep(std::time::Duration::from_secs_f64(part.len() as f64 / LINK_RATE as f64)).await;
                    }
                }
                let _ = sock.shutdown().await;
            });
        }
    });
    addr
}

/// The same weak link on the way in: a file arrives slowly, never not at all.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "slow, shares the process-wide upload pool: cargo test -p alpha-media slow_link_e2e -- --ignored --test-threads=1"]
async fn weak_link_slows_download_but_completes() {
    const CHUNKS: usize = 16;
    crate::link::network_changed();
    let file = temp_file(CHUNKS);
    let addr = spawn_slow_reader_store().await;
    let pipe = MediaPipeline::new(BlossomClient::with_servers(&[addr]));
    let signer = crate::blossom::MediaSigner::generate();
    let noop = |_: u64, _: u64| {};
    let up = pipe
        .upload_streaming_file(&file, "f.bin", MediaType::File, &signer, [5u8; 32], &noop, &|| false, None)
        .await
        .expect("upload to a fast intake must succeed");
    let out = std::env::temp_dir().join(format!("alpha_slow_dl_{}.bin", uuid::Uuid::new_v4()));
    let started = std::time::Instant::now();
    let got = tokio::time::timeout(
        std::time::Duration::from_secs(300),
        pipe.download_streaming_to_file(
            &up.media_key, &up.media_hash, up.stream_chunks.as_ref().unwrap(), &out, &noop, None,
        ),
    )
    .await;
    let took = started.elapsed().as_secs();
    let same = std::fs::read(&out).ok() == std::fs::read(&file).ok();
    let _ = std::fs::remove_file(&file);
    let _ = std::fs::remove_file(&out);
    let got = got.unwrap_or_else(|_| panic!("download hung for over {took} s"));
    got.unwrap_or_else(|e| panic!("weak link broke the download after {took} s: {e}"));
    assert!(same, "downloaded file differs from the uploaded one");
}
