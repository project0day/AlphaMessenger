//! How far back each relay still serves events (catch-up after a long offline gap).
use nostr_sdk::prelude::*;
use std::time::Duration;

#[tokio::main]
async fn main() {
    let mut set = tokio::task::JoinSet::new();
    for url in std::env::args().skip(1) {
        set.spawn(async move {
            let client = Client::builder().opts(ClientOptions::default().automatic_authentication(false)).build();
            let _ = client.add_relay(url.as_str()).await;
            client.try_connect(Duration::from_secs(10)).await;
            let mut out = Vec::new();
            for days in [1u64, 3, 7, 30] {
                let until = Timestamp::now() - Duration::from_secs(days * 86400);
                let f = Filter::new().kinds([Kind::from(445), Kind::GiftWrap, Kind::TextNote]).until(until).limit(1);
                let n = client.fetch_events(f, Duration::from_secs(8)).await.map(|e| e.len()).unwrap_or(0);
                out.push(format!("{days}d={}", if n > 0 { "yes" } else { "no" }));
            }
            client.shutdown().await;
            format!("{url:<34} {}", out.join(" "))
        });
    }
    let mut lines = Vec::new();
    while let Some(Ok(l)) = set.join_next().await { lines.push(l); }
    lines.sort();
    for l in lines { println!("{l}"); }
}
