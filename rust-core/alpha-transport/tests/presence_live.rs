//! Live check against real relays: a sealed presence heartbeat published by one
//! client reaches another subscribed to that contact's channel. Needs network.
//! `cargo test -p alpha-transport --test presence_live -- --ignored --nocapture`

use alpha_transport::sealed::{current_hour, presence_key, presence_tag};
use alpha_transport::{NostrRelayClient, RelayPoolNotification, DEFAULT_RELAYS};
use std::time::Duration;

#[tokio::test(flavor = "multi_thread")]
#[ignore]
async fn a_heartbeat_reaches_a_subscribed_contact() {
    let urls: Vec<String> = DEFAULT_RELAYS.iter().map(|s| s.to_string()).collect();
    let secret = [7u8; 32];
    let tag = presence_tag(&secret, current_hour());
    let key = presence_key(&secret);

    let receiver = NostrRelayClient::new(urls.clone());
    receiver.connect().await.unwrap();
    receiver.set_presence_tags(vec![hex::encode(tag)]);
    let me = nostr::Keys::generate().public_key();
    let mut rx = receiver.notifications();
    receiver.subscribe_mls_and_giftwrap(&me).await.unwrap();
    tokio::time::sleep(Duration::from_secs(4)).await;

    let sender = NostrRelayClient::new(urls);
    sender.connect().await.unwrap();
    tokio::time::sleep(Duration::from_secs(3)).await;
    let id = sender.publish_sealed_presence(&tag, &key, b"{\"v\":1}").await;
    println!("published: {:?}", id);

    let got = tokio::time::timeout(Duration::from_secs(15), async {
        loop {
            match rx.recv().await {
                Ok(RelayPoolNotification::Event { relay_url, subscription_id, event }) => {
                    println!("event kind={} sub={} from {}", event.kind.as_u16(), subscription_id, relay_url);
                    if let Some((_, p)) = receiver.open_presence(&event, |t| (*t == tag).then_some(key)) {
                        return p;
                    }
                }
                Ok(RelayPoolNotification::Message { relay_url, message }) => println!("msg {} {:?}", relay_url, message),
                Ok(_) => {}
                Err(e) => println!("recv: {e}"),
            }
        }
    })
    .await;
    assert_eq!(got.expect("no heartbeat arrived"), b"{\"v\":1}".to_vec());
}
