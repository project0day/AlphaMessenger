//! A resumed send must yield the SAME ref as an uninterrupted one.
//!
//! That is the core property of resume and the only reason it can ship at
//! all. Traffic savings are meaningless without it: a recipient handed a ref
//! with skipped or incomplete chunks cannot assemble the file — and finds
//! out after the send, when nothing can be fixed.
//!
//! Verified on a live HTTP testbench, not pipeline stubs: only then do real
//! encryption, envelope, identity computation and ref assembly enter the
//! check. A stub would only prove that the stub agrees with itself.

use crate::blossom::BlossomClient;
use crate::media_ref::UploadResult;
use crate::pipeline::MediaPipeline;
use crate::resume::{ChunkCheckpoint, ConfirmedChunk};
use crate::thumbnail::MediaType;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

/// Storage that accepts uploads and counts them. The counter IS the proof:
/// a skipped chunk shows up as a missing request, not as code testifying
/// about itself.
struct UploadStore {
    addr: String,
    puts: Arc<AtomicUsize>,
}

async fn spawn_upload_store() -> UploadStore {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = format!("http://{}", listener.local_addr().unwrap());
    let puts = Arc::new(AtomicUsize::new(0));
    let (a, p) = (addr.clone(), puts.clone());
    tokio::spawn(async move {
        loop {
            let Ok((mut sock, _)) = listener.accept().await else { return };
            let (a, p) = (a.clone(), p.clone());
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut raw = Vec::new();
                let mut buf = [0u8; 16384];
                // Headers, then exactly as much body as declared.
                let mut head_end: Option<usize> = None;
                let mut want = 0usize;
                loop {
                    let n = match sock.read(&mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
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
    UploadStore { addr, puts }
}

/// An in-memory checkpoint — the same role as the core's table.
#[derive(Default)]
struct MemCheckpoint(Mutex<Vec<ConfirmedChunk>>);

impl ChunkCheckpoint for MemCheckpoint {
    fn load(&self) -> Vec<ConfirmedChunk> {
        self.0.lock().unwrap().clone()
    }
    fn commit(&self, batch: &[ConfirmedChunk]) -> Result<(), String> {
        let mut g = self.0.lock().unwrap();
        for c in batch {
            g.retain(|x| x.index != c.index);
            g.push(c.clone());
        }
        Ok(())
    }
}

fn temp_file(chunks: usize) -> std::path::PathBuf {
    let p = std::env::temp_dir().join(format!("alpha_t4_{}.bin", uuid::Uuid::new_v4()));
    let n = chunks * crate::crypto::STREAM_CHUNK_SIZE;
    // Non-repeating content: identical chunks would hide swapped numbers
    // and one chunk's copy substituted for another.
    let data: Vec<u8> = (0..n).map(|i| (i as u32).wrapping_mul(2654435761) as u8).collect();
    std::fs::write(&p, data).unwrap();
    p
}

/// A ref fingerprint INDEPENDENT of addresses: chunk content and their order.
/// Different testbenches have different addresses, but the chunks must match byte for byte.
fn manifest(r: &UploadResult) -> Vec<(u64, u64, String, String)> {
    r.stream_chunks
        .as_ref()
        .expect("потоковая отправка обязана дать список кусков")
        .iter()
        .map(|c| {
            (
                c.original_size,
                c.ciphertext_size,
                c.hash_hex.clone(),
                c.transport_object_id.as_ref().map(|i| i.to_string()).unwrap_or_default(),
            )
        })
        .collect()
}

#[tokio::test]
async fn возобновлённая_отправка_даёт_ту_же_ссылку_и_не_шлёт_принятое() {
    // The file is deliberately longer than one wave of in-flight chunks: on
    // a fast disconnect, chunks that were in flight are cancelled with the
    // stream, and at six chunks nothing would have time to confirm — the
    // check would measure wave width, not resume.
    const CHUNKS: usize = 3 * crate::pipeline::UPLOAD_CONCURRENCY;
    let file = temp_file(CHUNKS);
    let key = [7u8; 32];
    let signer = crate::blossom::MediaSigner::generate();
    let noop = |_: u64, _: u64| {};

    // 1. Uninterrupted send — the reference to compare against.
    let store_a = spawn_upload_store().await;
    let pipe_a = MediaPipeline::new(BlossomClient::with_servers(&[store_a.addr.clone()]));
    let normal = pipe_a
        .upload_streaming_file(&file, "f.bin", MediaType::File, &signer, key, &noop, &|| false, None)
        .await
        .expect("непрерывная отправка обязана пройти");
    assert_eq!(store_a.puts.load(Ordering::Relaxed), CHUNKS);

    // 2. Disconnect mid-way. Confirmations of accepted chunks must survive
    //    the error — that is why the write happens BEFORE parsing the result.
    let store_b = spawn_upload_store().await;
    let pipe_b = MediaPipeline::new(BlossomClient::with_servers(&[store_b.addr.clone()]));
    let ckpt = MemCheckpoint::default();
    let seen = AtomicUsize::new(0);
    let interrupted = pipe_b
        .upload_streaming_file(
            &file,
            "f.bin",
            MediaType::File,
            &signer,
            key,
            &noop,
            &|| seen.fetch_add(1, Ordering::Relaxed) >= CHUNKS / 2,
            Some(&ckpt),
        )
        .await;
    assert!(interrupted.is_err(), "обрыв обязан завершиться ошибкой, а не тихим успехом");
    let confirmed = ckpt.load().len();
    assert!(confirmed > 0, "часть кусков обязана быть подтверждена до обрыва");
    assert!(confirmed < CHUNKS, "обрыв обязан оставить незавершённые куски");
    let sent_before = store_b.puts.swap(0, Ordering::Relaxed);

    // 3. Resume on the same storage.
    let resumed = pipe_b
        .upload_streaming_file(
            &file, "f.bin", MediaType::File, &signer, key, &noop, &|| false, Some(&ckpt),
        )
        .await
        .expect("возобновление обязано пройти");
    let sent_after = store_b.puts.load(Ordering::Relaxed);

    // What is confirmed does not go to the network again.
    assert_eq!(
        sent_after,
        CHUNKS - confirmed,
        "заново ушло {sent_after} кусков при {confirmed} подтверждённых из {CHUNKS}",
    );
    assert!(
        sent_before + sent_after < CHUNKS * 2,
        "возобновление обязано стоить дешевле полной повторной отправки",
    );

    // And the key point: the ref is indistinguishable from the uninterrupted one.
    assert_eq!(manifest(&resumed), manifest(&normal), "манифест кусков разошёлся");
    assert_eq!(resumed.media_hash, normal.media_hash, "хэш файла разошёлся");
    assert_eq!(resumed.original_size, normal.original_size);
    assert_eq!(resumed.stream_chunks.as_ref().unwrap().len(), CHUNKS);
    assert!(
        resumed.stream_chunks.as_ref().unwrap().iter().all(|c| !c.locations.is_empty()),
        "у пропущенных кусков обязаны остаться адреса — иначе получатель не соберёт файл",
    );

    let _ = std::fs::remove_file(&file);
}

/// Records from another revision grant no skip: the content differs, so does
/// the identity. Chunks go out again, the ref comes out right.
///
/// The guard is not that we compared revision numbers but that bytes must be
/// compared: even a foreign record slipped in gives no skip.
#[tokio::test]
async fn чужая_контрольная_точка_не_подменяет_куски() {
    let file = temp_file(3);
    let signer = crate::blossom::MediaSigner::generate();
    let noop = |_: u64, _: u64| {};
    let store = spawn_upload_store().await;
    let pipe = MediaPipeline::new(BlossomClient::with_servers(&[store.addr.clone()]));

    // Confirmations from ANOTHER send: same file, different key — different
    // bytes in storage, different identity.
    let alien = MemCheckpoint::default();
    pipe.upload_streaming_file(
        &file, "f.bin", MediaType::File, &signer, [1u8; 32], &noop, &|| false, Some(&alien),
    )
    .await
    .unwrap();
    store.puts.store(0, Ordering::Relaxed);

    let mine = pipe
        .upload_streaming_file(
            &file, "f.bin", MediaType::File, &signer, [2u8; 32], &noop, &|| false, Some(&alien),
        )
        .await
        .expect("отправка обязана пройти, не поверив чужим записям");

    assert_eq!(
        store.puts.load(Ordering::Relaxed),
        3,
        "ни один кусок не имел права быть пропущенным по чужой записи",
    );
    assert_eq!(mine.stream_chunks.as_ref().unwrap().len(), 3);
    let _ = std::fs::remove_file(&file);
}

/// Storage that accepts exactly `ok_puts` uploads, then refuses. That is how
/// a disconnect is reproduced: some chunks accepted, the rest fail.
async fn spawn_failing_store(ok_puts: usize) -> UploadStore {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = format!("http://{}", listener.local_addr().unwrap());
    let puts = Arc::new(AtomicUsize::new(0));
    let (a, p) = (addr.clone(), puts.clone());
    tokio::spawn(async move {
        loop {
            let Ok((mut sock, _)) = listener.accept().await else { return };
            let (a, p) = (a.clone(), p.clone());
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut raw = Vec::new();
                let mut buf = [0u8; 16384];
                let mut head_end: Option<usize> = None;
                let mut want = 0usize;
                loop {
                    let n = match sock.read(&mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
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
                        if raw.len() >= h + want { break; }
                    }
                }
                let Some(h) = head_end else { return };
                let body = &raw[h..];
                let n = p.fetch_add(1, Ordering::Relaxed);
                let resp = if n < ok_puts {
                    let sha = { use sha2::Digest; hex::encode(sha2::Sha256::digest(body)) };
                    let json = format!(
                        "{{\"url\":\"{a}/{sha}\",\"sha256\":\"{sha}\",\"size\":{},\"uploaded\":0}}",
                        body.len()
                    );
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{json}",
                        json.len()
                    )
                } else {
                    "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_string()
                };
                let _ = sock.write_all(resp.as_bytes()).await;
                let _ = sock.shutdown().await;
            });
        }
    });
    UploadStore { addr, puts }
}

/// One permanent chunk failure must abort the WHOLE attempt — immediately.
///
/// A defect once observed on device: chunk results were collected into a
/// vector and the error examined after it, so the stream kept reading and
/// sending new chunks after the attempt was already doomed. In the log it
/// looked like a hole mid-file, a read-through to the last chunk and
/// eternal silence — no crash, hence no retry.
///
/// Checked by three independent properties, each forgeable alone but not
/// together: the attempt ends in an error; it ends FAST without walking the
/// rest of the file; send permits are returned in full, otherwise the next
/// attempt would hang on an empty semaphore.
#[tokio::test]
async fn одна_неудача_куска_обрывает_попытку_не_дочитывая_файл() {
    const CHUNKS: usize = 40;
    const OK: usize = 6;
    let file = temp_file(CHUNKS);
    let signer = crate::blossom::MediaSigner::generate();
    let noop = |_: u64, _: u64| {};

    let store = spawn_failing_store(OK).await;
    let pipe = MediaPipeline::new(BlossomClient::with_servers(&[store.addr.clone()]));
    let ckpt = MemCheckpoint::default();

    let t0 = std::time::Instant::now();
    let res = pipe
        .upload_streaming_file(
            &file, "f.bin", MediaType::File, &signer, [9u8; 32], &noop, &|| false, Some(&ckpt),
        )
        .await;
    let elapsed = t0.elapsed();

    assert!(res.is_err(), "неудача куска обязана дать ошибку, а не тихий успех");

    // Reading a doomed attempt to the end would cost four attempts per each
    // of the remaining 34 chunks. The threshold separates "aborted at
    // once" from "walked the whole file" with a >4× margin.
    let puts = store.puts.load(Ordering::Relaxed);
    assert!(
        puts < OK + 4 * crate::pipeline::GLOBAL_UPLOAD_SLOTS * 2,
        "попытка продолжала слать куски после окончательной неудачи: запросов {puts}",
    );

    assert!(elapsed.as_secs() < 60, "обрыв обязан быть быстрым, а не по сроку ожидания: {elapsed:?}");

    // What was accepted before the break must survive the error — otherwise
    // resume has nothing to skip and the whole gain is lost on the key case.
    assert!(!ckpt.load().is_empty(), "подтверждения принятых кусков обязаны сохраниться");

    // In-flight chunks are cancelled with the stream; their permits must be
    // returned, otherwise a second send in the same process hangs forever.
    //
    // Counting free permits is not allowed: the semaphore is one per process
    // and neighboring tests hold them at that same moment — the check would
    // measure someone else's work. Taking all of them tests the same property
    // independently of neighbors: a leak would make it forever impossible,
        // mere busy-ness would only delay it.
    let all = crate::pipeline::GLOBAL_UPLOAD_SLOTS as u32;
    tokio::time::timeout(
        std::time::Duration::from_secs(30),
        crate::pipeline::upload_slots().acquire_many(all),
    )
    .await
    .expect("разрешения на отправку не вернулись после обрыва")
    .expect("семафор отправки закрыт");

    let _ = std::fs::remove_file(&file);
}
