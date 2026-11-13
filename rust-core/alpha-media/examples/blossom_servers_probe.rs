//! Which public Blossom servers store our blobs? For every candidate: upload a
//! random blob signed by a one-time key (as the app does), download it back and
//! compare bytes, for each content type we may use.
//! Run: `cargo run --release -p alpha-media --example blossom_servers_probe`.

use alpha_media::blossom::{BlossomClient, MediaSigner, ReplicationPolicy};
use rand::RngCore;

const CANDIDATES: &[&str] = &[
    "https://blossom.primal.net",
    "https://blossom.band",
    "https://nostr.download",
    "https://cdn.satellite.earth",
    "https://blossom.nostr.build",
    "https://files.v0l.io",
    "https://cdn.nostrcheck.me",
    "https://nostrcheck.me",
    "https://blossom.oxtr.dev",
    "https://blossom.f7z.io",
    "https://cdn.hzrd149.com",
    "https://blossom.yakihonne.com",
    "https://blossom.sovereignengineering.io",
    "https://blossom.data.haus",
    "https://blossom.poster.place",
    "https://nosto.re",
    "https://blossom.azzamo.media",
    "https://24242.io",
];

#[tokio::main]
async fn main() {
    let mut data = vec![0u8; 512 * 1024];
    rand::thread_rng().fill_bytes(&mut data);
    let json = format!("{{\"v\":1,\"d\":\"{}\"}}", base64::Engine::encode(&base64::engine::general_purpose::STANDARD, &data[..65536]));
    let cases: [(&str, Vec<u8>); 2] = [("application/octet-stream", data.clone()), ("application/json", json.into_bytes())];

    // Servers given on the command line replace the built-in candidates.
    let given: Vec<String> = std::env::args().skip(1).collect();
    let list: Vec<String> = if given.is_empty() { CANDIDATES.iter().map(|s| s.to_string()).collect() } else { given };
    println!("{:<42} {:<26} result", "server", "content-type");
    for server in &list {
        for (ct, body) in &cases {
            let client = BlossomClient::with_servers(&[server.to_string()]);
            let signer = MediaSigner::generate();
            let started = std::time::Instant::now();
            let up = tokio::time::timeout(
                std::time::Duration::from_secs(30),
                client.upload_mirrored(body, &signer, ct, ReplicationPolicy::primary()),
            )
            .await;
            let verdict = match up {
                Err(_) => "upload timeout".to_string(),
                Ok(Err(e)) => format!("upload refused: {}", e.to_string().chars().take(70).collect::<String>()),
                Ok(Ok(locs)) => {
                    let url = locs[0].url.clone();
                    match tokio::time::timeout(std::time::Duration::from_secs(30), client.download(&url)).await {
                        Err(_) => format!("download timeout ({url})"),
                        Ok(Err(e)) => format!("download failed: {}", e.to_string().chars().take(60).collect::<String>()),
                        Ok(Ok(got)) if &got == body => format!("OK {} ms  {}", started.elapsed().as_millis(), url),
                        Ok(Ok(got)) => format!("CORRUPTED (got {} bytes)", got.len()),
                    }
                }
            };
            println!("{:<42} {:<26} {}", server, ct, verdict);
        }
    }
}
