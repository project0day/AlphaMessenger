//! Push notification dispatch.
//!
//! Sends minimal "wake" notifications via ntfy. The recipient app
//! wakes up, connects to Nostr relays, and fetches the actual
//! messages. ZERO plaintext is ever included in push notifications.

use crate::error::Result;
use crate::ntfy_client::{NtfyClient, NtfyMessage};

/// Dispatches push notifications to devices.
#[derive(Clone)]
pub struct PushDispatcher {
    /// The ntfy client used to send notifications.
    ntfy: NtfyClient,
}

impl PushDispatcher {
    /// Create a new push dispatcher.
    pub fn new(ntfy: NtfyClient) -> Self {
        Self { ntfy }
    }

    /// Send a minimal "wake" notification to a specific topic.
    ///
    /// The recipient's app will:
    /// 1. Receive the push notification
    /// 2. Wake up in the background
    /// 3. Connect to Nostr relays
    /// 4. Fetch actual messages via catch-up sync
    ///
    /// The notification payload contains NO message content,
    /// NO sender information, and NO chat identifiers.
    pub async fn notify_device(&self, topic: &str) -> Result<()> {
        let message = NtfyMessage::wake();
        tracing::info!("Sending wake notification to topic: {}", topic);

        // Create a temporary client for the target topic.
        // This is necessary because the dispatcher's own ntfy client
        // is configured with the *local* device's topic.
        let target_client = NtfyClient::new(self.ntfy.server_url(), topic);
        target_client.publish(&message).await?;

        Ok(())
    }

    /// Derive an ntfy topic from a Nostr public key using BLAKE3.
    ///
    /// The topic is computed as:
    /// ```text
    /// topic = hex(blake3(pubkey)[..16])
    /// ```
    ///
    /// Using only 16 bytes (128 bits) provides more than enough
    /// uniqueness while keeping the topic string short.  The hash
    /// ensures the topic doesn't directly reveal the user's identity.
    pub fn generate_topic(pubkey: &[u8]) -> String {
        let hash = blake3::hash(pubkey);
        hex::encode(&hash.as_bytes()[..16])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_topic_deterministic() {
        let pubkey = [0xAB_u8; 32];
        let topic1 = PushDispatcher::generate_topic(&pubkey);
        let topic2 = PushDispatcher::generate_topic(&pubkey);
        assert_eq!(topic1, topic2);
    }

    #[test]
    fn test_generate_topic_different_keys() {
        let pubkey1 = [0x01_u8; 32];
        let pubkey2 = [0x02_u8; 32];
        let topic1 = PushDispatcher::generate_topic(&pubkey1);
        let topic2 = PushDispatcher::generate_topic(&pubkey2);
        assert_ne!(topic1, topic2);
    }

    #[test]
    fn test_generate_topic_length() {
        let pubkey = [0x00_u8; 32];
        let topic = PushDispatcher::generate_topic(&pubkey);
        // 16 bytes → 32 hex chars
        assert_eq!(topic.len(), 32);
    }
}
