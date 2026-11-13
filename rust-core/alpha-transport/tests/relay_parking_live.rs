//! A relay that refuses every connection is parked: its lanes stop redialling
//! it and only a probe now and then knocks again.

use alpha_transport::NostrRelayClient;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "takes 40 s: run with --ignored after touching relay parking"]
async fn a_dead_relay_stops_being_redialled() {
    // Accepts TCP and hangs up at once: every WebSocket handshake fails.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", listener.local_addr().unwrap());
    let knocks = Arc::new(AtomicUsize::new(0));
    let k = Arc::clone(&knocks);
    tokio::spawn(async move {
        while let Ok((sock, _)) = listener.accept().await {
            k.fetch_add(1, Ordering::SeqCst);
            drop(sock);
        }
    });

    let client = NostrRelayClient::new(vec![url.clone()]);
    client.connect().await.unwrap();

    // Two lanes (identity, groups) retry every 3 s until parked.
    let mut parked_at = None;
    for s in 0..40 {
        tokio::time::sleep(Duration::from_secs(1)).await;
        if client.parked_relays().iter().any(|u| url.starts_with(u.as_str())) {
            parked_at = Some(s);
            break;
        }
    }
    let parked_at = parked_at.expect("the dead relay was never parked");
    let before = knocks.load(Ordering::SeqCst);
    // Parked: nothing knocks until the first probe (22-37 s later).
    tokio::time::sleep(Duration::from_secs(20)).await;
    let after = knocks.load(Ordering::SeqCst);
    println!("parked after {parked_at}s, {before} knocks before, {} while parked", after - before);
    assert!(after - before <= 1, "a parked relay is still dialled: {} knocks in 20 s", after - before);
}
