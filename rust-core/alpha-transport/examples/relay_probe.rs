//! Probes public relays the way the app uses them: connect, NIP-11, each of
//! our event kinds published with a throwaway key (expiring in 10 min) and read
//! back with the filter the app subscribes with, plus one ~100 KB event.
//!
//! `cargo run -q -p alpha-transport --example relay_probe -- wss://a wss://b ...`

use nostr_sdk::prelude::*;
use std::time::{Duration, Instant};

const KINDS: &[(u16, &str)] = &[(445, "group"), (1059, "giftwrap"), (443, "keypackage"), (30078, "app-data")];

#[tokio::main]
async fn main() {
    let urls: Vec<String> = std::env::args().skip(1).collect();
    let mut set = tokio::task::JoinSet::new();
    for url in urls {
        set.spawn(async move { probe(&url).await });
    }
    let mut lines = Vec::new();
    while let Some(Ok(line)) = set.join_next().await {
        lines.push(line);
    }
    lines.sort();
    for l in lines {
        println!("{l}");
    }
}

async fn nip11(url: &str) -> String {
    let http = url.replacen("wss://", "https://", 1);
    let client = reqwest::Client::builder().timeout(Duration::from_secs(8)).build().unwrap();
    match client.get(&http).header("Accept", "application/nostr+json").send().await {
        Ok(r) => match r.json::<serde_json::Value>().await {
            Ok(v) => {
                let l = &v["limitation"];
                let mut f: Vec<String> = ["auth_required", "payment_required", "restricted_writes"].iter()
                    .filter(|k| l[**k].as_bool().unwrap_or(false))
                    .map(|k| k.trim_end_matches("_required").to_string())
                    .collect();
                if let Some(m) = l["max_message_length"].as_u64() { f.push(format!("max_msg={}K", m / 1024)); }
                format!("nip11[{}] sw={}", f.join(","), v["software"].as_str().unwrap_or("?").rsplit('/').next().unwrap_or("?"))
            }
            Err(_) => "nip11[bad json]".into(),
        },
        Err(_) => "nip11[none]".into(),
    }
}

fn noise(n: usize) -> String {
    base64::Engine::encode(&base64::engine::general_purpose::STANDARD, (0..n).map(|_| rand::random::<u8>()).collect::<Vec<u8>>())
}

fn short(s: impl ToString, n: usize) -> String {
    s.to_string().chars().take(n).collect()
}

async fn probe(url: &str) -> String {
    let info = nip11(url).await;
    let keys = Keys::generate();
    let client = Client::builder().signer(keys.clone()).opts(ClientOptions::default().automatic_authentication(false)).build();
    if client.add_relay(url).await.is_err() {
        return format!("{url:<36} BAD URL");
    }
    let t0 = Instant::now();
    if client.try_connect(Duration::from_secs(10)).await.success.is_empty() {
        return format!("{url:<36} DOWN (no connect in 10s)  {info}");
    }
    let connect_ms = t0.elapsed().as_millis();
    let expires = Timestamp::now() + Duration::from_secs(600);
    let rcpt = Keys::generate().public_key();
    let h_tag = SingleLetterTag::lowercase(Alphabet::H);
    let mut res = Vec::new();
    for (kind, name) in KINDS {
        let mut tags = vec![Tag::expiration(expires)];
        let h = hex::encode(rand::random::<[u8; 16]>());
        let d = hex::encode(rand::random::<[u8; 8]>());
        let read = match kind {
            445 => {
                tags.push(Tag::custom(TagKind::SingleLetter(h_tag), [h.clone()]));
                Filter::new().kind(Kind::from(445)).custom_tag(h_tag, h.clone())
            }
            1059 => {
                tags.push(Tag::public_key(rcpt));
                Filter::new().kind(Kind::GiftWrap).pubkey(rcpt)
            }
            30078 => {
                tags.push(Tag::identifier(d.clone()));
                Filter::new().kind(Kind::from(30078)).author(keys.public_key()).identifier(d.clone())
            }
            _ => Filter::new().kind(Kind::from(*kind)).author(keys.public_key()),
        };
        let ev = EventBuilder::new(Kind::from(*kind), noise(96)).tags(tags).sign_with_keys(&keys).unwrap();
        let t = Instant::now();
        let sent = match client.send_event(&ev).await {
            Ok(out) if !out.success.is_empty() => format!("ok{}ms", t.elapsed().as_millis()),
            Ok(out) => format!("NO({})", out.failed.values().next().map(|s| short(s, 40)).unwrap_or_default()),
            Err(e) => format!("ERR({})", short(e, 30)),
        };
        let back = if sent.starts_with("ok") { read_back(&client, read, ev.id).await } else { "-".into() };
        res.push(format!("{name}={sent}/{back}"));
    }
    // A profile with its picture travels as one ~100 KB group event.
    let big = {
        let ev = EventBuilder::new(Kind::from(445), noise(75_000)).tags([Tag::expiration(expires)]).sign_with_keys(&keys).unwrap();
        match client.send_event(&ev).await {
            Ok(out) if !out.success.is_empty() => "big100K=ok".to_string(),
            Ok(out) => format!("big100K=NO({})", out.failed.values().next().map(|s| short(s, 30)).unwrap_or_default()),
            Err(e) => format!("big100K=ERR({})", short(e, 30)),
        }
    };
    client.shutdown().await;
    format!("{url:<36} UP {connect_ms}ms  {}  {big}  {info}", res.join(" "))
}

/// Reads [filter] as the app's live subscription does: "ok" when [id] comes
/// back, "closed(..)" when the relay refuses the filter, "empty" when it kept nothing.
async fn read_back(client: &Client, filter: Filter, id: EventId) -> String {
    let mut rx = client.notifications();
    let sub = match client.subscribe(filter, None).await {
        Ok(o) => o.val,
        Err(e) => return format!("ERR({})", short(e, 30)),
    };
    let deadline = tokio::time::sleep(Duration::from_secs(6));
    tokio::pin!(deadline);
    let out = loop {
        tokio::select! {
            _ = &mut deadline => break "timeout".to_string(),
            n = rx.recv() => match n {
                // Raw relay message: the pool's own Event notification skips an event it already saw (ours).
                Ok(RelayPoolNotification::Message { message: RelayMessage::Event { subscription_id, event }, .. }) if *subscription_id == sub && event.id == id => break "read".into(),
                Ok(RelayPoolNotification::Message { message: RelayMessage::Closed { subscription_id, message }, .. }) if *subscription_id == sub =>
                    break format!("closed({})", short(message, 30)),
                Ok(RelayPoolNotification::Message { message: RelayMessage::EndOfStoredEvents(s), .. }) if *s == sub => break "NOT-STORED".into(),
                Err(_) => break "lag".into(),
                _ => {}
            },
        }
    };
    client.unsubscribe(&sub).await;
    out
}
