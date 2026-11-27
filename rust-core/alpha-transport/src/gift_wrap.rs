//! NIP-59 gift-wrap for MLS Welcome messages.
//!
//! Gift-wrapping (NIP-59) ensures that the relay — and any passive observer —
//! cannot determine *who* sent a message to *whom*. The flow is:
//!
//! 1. **Rumor** — unsigned event (kind 443) containing the MLS envelope
//! 2. **Seal** — kind-13 event, signed by the sender, NIP-44-encrypted to the
//!    recipient
//! 3. **Gift wrap** — kind-1059 event, signed by an *ephemeral* key, NIP-44-
//!    encrypted to the recipient
//!
//! Only the intended recipient can decrypt the gift wrap, extract the seal,
//! verify the sender, and read the rumor.
//!
//! # NIP-40 Expiration
//!
//! When sending ephemeral messages, the gift-wrap can include a NIP-40
//! `expiration` tag. Supporting relays will drop the event after the
//! specified timestamp, providing best-effort ciphertext cleanup.

use nostr::nips::nip59::UnwrappedGift;
use nostr::{Event, EventBuilder, Keys, Kind, PublicKey, Tag, Timestamp};

use crate::envelope::MlsEnvelope;
use crate::error::{Result, TransportError};

// ---------------------------------------------------------------------------
// GiftWrapBuilder
// ---------------------------------------------------------------------------

/// Builds NIP-59 gift-wrapped events containing MLS envelopes.
pub struct GiftWrapBuilder;

impl GiftWrapBuilder {
    /// Wrap an MLS envelope in a NIP-59 gift-wrap for a specific recipient.
    ///
    /// The resulting [`Event`] has kind [`Kind::GiftWrap`] (1059). The relay
    /// sees only the ephemeral pubkey and opaque ciphertext.
    ///
    /// # Arguments
    /// * `sender`    — The sender's Nostr keypair (used for the inner seal).
    /// * `recipient` — The recipient's Nostr public key.
    /// * `envelope`  — The MLS envelope to deliver.
    pub async fn wrap(
        sender: &Keys,
        recipient: &PublicKey,
        envelope: &MlsEnvelope,
    ) -> Result<Event> {
        Self::wrap_with_ttl(sender, recipient, envelope, None).await
    }

    /// Wrap an MLS envelope in a NIP-59 gift-wrap with an optional TTL.
    ///
    /// If `ttl_secs` is provided, a NIP-40 expiration tag is added to the
    /// gift-wrap event. Supporting relays will drop the event after the TTL.
    ///
    /// The expiration tag is passed directly to `EventBuilder::gift_wrap()`,
    /// so the NIP-44 ciphertext is created with the correct ephemeral key
    /// in a single step. No re-signing is performed.
    pub async fn wrap_with_ttl(
        sender: &Keys,
        recipient: &PublicKey,
        envelope: &MlsEnvelope,
        ttl_secs: Option<u32>,
    ) -> Result<Event> {
        // Serialise the envelope to base64 for the Nostr event content field.
        let content = envelope.to_base64();

        // Build the rumor — an unsigned event with kind 443 (MLS envelope).
        let rumor = EventBuilder::new(Kind::MlsKeyPackage, content).build(sender.public_key());

        // Build the tags array. If TTL is specified, include the NIP-40
        // expiration tag so that supporting relays will drop the event
        // after the TTL elapses.
        let tags: Vec<Tag> = if let Some(ttl) = ttl_secs {
            let expiration_ts = Timestamp::from(
                (std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs()
                    + u64::from(ttl)) as u64,
            );
            tracing::debug!(
                "Adding NIP-40 expiration to gift-wrap: ttl={}s, expires_at={}",
                ttl,
                expiration_ts
            );
            vec![Tag::expiration(expiration_ts)]
        } else {
            vec![]
        };

        // Gift-wrap: creates seal (kind 13, NIP-44 encrypted) then
        // wraps in gift-wrap (kind 1059, ephemeral key, NIP-44 encrypted).
        // The expiration tag (if any) is included in the outer event.
        let gift_wrap_event = EventBuilder::gift_wrap(sender, recipient, rumor, tags)
            .await
            .map_err(|e| TransportError::GiftWrap(format!("gift_wrap failed: {e}")))?;

        tracing::debug!(
            "Gift-wrapped MLS envelope for recipient {}: event_id={}",
            recipient,
            gift_wrap_event.id
        );

        Ok(gift_wrap_event)
    }
}

// ---------------------------------------------------------------------------
// GiftWrapReader
// ---------------------------------------------------------------------------

/// Reads (unwraps) NIP-59 gift-wrapped MLS envelopes.
pub struct GiftWrapReader;

impl GiftWrapReader {
    /// Unwrap a gift-wrapped event and extract the MLS envelope.
    ///
    /// # Arguments
    /// * `recipient` — The recipient's Nostr keypair (needed for NIP-44
    ///   decryption).
    /// * `event`     — The kind-1059 gift-wrap event.
    ///
    /// # Returns
    /// The [`MlsEnvelope`] contained in the rumor, along with the sender's
    /// public key.
    pub async fn unwrap(
        recipient: &Keys,
        event: &Event,
    ) -> Result<(MlsEnvelope, PublicKey)> {
        Self::unwrap_with_time(recipient, event).await.map(|(e, s, _)| (e, s))
    }

    /// Like [`Self::unwrap`], plus when the sender wrote it (the rumor's time:
    /// only the outer layers carry a randomized one).
    pub async fn unwrap_with_time(
        recipient: &Keys,
        event: &Event,
    ) -> Result<(MlsEnvelope, PublicKey, u64)> {
        // Verify this is actually a gift-wrap event.
        if event.kind != Kind::GiftWrap {
            return Err(TransportError::GiftWrap(format!(
                "expected kind {} (GiftWrap), got kind {}",
                Kind::GiftWrap,
                event.kind
            )));
        }

        // Decrypt and unwrap the gift wrap → seal → rumor.
        let unwrapped = UnwrappedGift::from_gift_wrap(recipient, event)
            .await
            .map_err(|e| TransportError::GiftWrap(format!("unwrap failed: {e}")))?;

        // The rumor's content should be a base64-encoded MlsEnvelope.
        let envelope = MlsEnvelope::from_base64(&unwrapped.rumor.content)?;

        tracing::debug!(
            "Unwrapped gift-wrap from sender {}: envelope={}",
            unwrapped.sender,
            envelope
        );

        Ok((envelope, unwrapped.sender, unwrapped.rumor.created_at.as_secs()))
    }

    /// Try to unwrap a gift-wrapped event. Returns `None` if the event is not
    /// a gift-wrap or if decryption fails (e.g. we are not the intended
    /// recipient).
    pub async fn try_unwrap(
        recipient: &Keys,
        event: &Event,
    ) -> Option<(MlsEnvelope, PublicKey)> {
        if event.kind != Kind::GiftWrap {
            return None;
        }
        Self::unwrap(recipient, event).await.ok()
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::MlsMessageType;

    /// Helper: create a test envelope.
    fn test_envelope() -> MlsEnvelope {
        MlsEnvelope::new(
            MlsMessageType::Welcome,
            vec![0x01, 0x02, 0x03, 0x04],
            42,
            vec![0xAA, 0xBB, 0xCC],
        )
    }

    /// The outer layers lie about the time (NIP-59); the rumor says when it was written.
    #[tokio::test]
    async fn the_rumor_keeps_the_real_time() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &test_envelope()).await.unwrap();
        let (_, _, at) = GiftWrapReader::unwrap_with_time(&recipient, &wrapped).await.unwrap();
        let now = Timestamp::now().as_secs();
        assert!(at + 5 >= now && at <= now + 5, "rumor time {at}, now {now}");
    }

    // --- Gift-wrap then unwrap should produce the same MlsEnvelope ---

    #[tokio::test]
    async fn wrap_unwrap_roundtrip() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();

        // Verify it's a gift-wrap kind
        assert_eq!(wrapped.kind, Kind::GiftWrap);

        // Unwrap should recover the same envelope
        let (unwrapped, sender_pk) = GiftWrapReader::unwrap(&recipient, &wrapped)
            .await
            .unwrap();

        assert_eq!(unwrapped, envelope);
        assert_eq!(sender_pk, sender.public_key());
    }

    // --- Unwrap with wrong recipient keys should fail ---

    #[tokio::test]
    async fn unwrap_wrong_recipient_fails() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let wrong_recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();

        // Trying to unwrap with the wrong keys should fail
        let result = GiftWrapReader::unwrap(&wrong_recipient, &wrapped).await;
        assert!(result.is_err(), "Unwrap with wrong keys should fail");
    }

    // --- Unwrap non-gift-wrap event (wrong kind) should error ---

    #[tokio::test]
    async fn unwrap_wrong_kind_errors() {
        let keys = Keys::generate();
        // Create a regular kind-1 (text note) event, not a gift-wrap
        let event = EventBuilder::new(Kind::TextNote, "hello world")
            .sign_with_keys(&keys)
            .unwrap();

        let result = GiftWrapReader::unwrap(&keys, &event).await;
        assert!(result.is_err());
        let err_msg = result.unwrap_err().to_string();
        assert!(
            err_msg.contains("expected kind") && err_msg.contains("GiftWrap"),
            "Expected 'wrong kind' error, got: {err_msg}"
        );
    }

    // --- Gift wrap with TTL produces event with expiration tag ---

    #[tokio::test]
    async fn wrap_with_ttl_has_expiration_tag() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap_with_ttl(
            &sender,
            &recipient.public_key(),
            &envelope,
            Some(3600), // 1 hour TTL
        )
        .await
        .unwrap();

        // Check that an "expiration" tag is present
        let has_expiration = wrapped.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == "expiration"
        });
        assert!(has_expiration, "Gift wrap with TTL should have an expiration tag");

        // Verify the gift-wrap can still be unwrapped (the expiration tag is
        // passed directly to gift_wrap() instead of re-signing with a new
        // ephemeral key, so NIP-44 decryption works).
        let (unwrapped, sender_pk) = GiftWrapReader::unwrap(&recipient, &wrapped)
            .await
            .expect("TTL gift-wrap should unwrap successfully");
        assert_eq!(unwrapped, envelope, "Unwrapped envelope must match original");
        assert_eq!(sender_pk, sender.public_key(), "Sender must match");
    }

    // --- Gift wrap without TTL has no expiration tag ---

    #[tokio::test]
    async fn wrap_without_ttl_has_no_expiration_tag() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();

        // Check that no "expiration" tag is present
        let has_expiration = wrapped.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == "expiration"
        });
        assert!(!has_expiration, "Gift wrap without TTL should NOT have an expiration tag");
    }

    // --- wrap_with_ttl(None) same as wrap() ---

    #[tokio::test]
    async fn wrap_with_ttl_none_same_as_wrap() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap_with_ttl(
            &sender,
            &recipient.public_key(),
            &envelope,
            None,
        )
        .await
        .unwrap();

        // No expiration tag
        let has_expiration = wrapped.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == "expiration"
        });
        assert!(!has_expiration);

        // Can still unwrap
        let (unwrapped, _) = GiftWrapReader::unwrap(&recipient, &wrapped)
            .await
            .unwrap();
        assert_eq!(unwrapped, envelope);
    }

    // --- Gift-wrap event kind is 1059 ---

    #[tokio::test]
    async fn gift_wrap_event_kind_is_1059() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();

        assert_eq!(wrapped.kind, Kind::GiftWrap);
        assert_eq!(wrapped.kind.as_u16(), 1059);
    }

    // --- Gift-wrap sender pubkey is ephemeral (not the real sender) ---

    #[tokio::test]
    async fn gift_wrap_pubkey_is_ephemeral() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();

        // The gift-wrap event's pubkey should NOT be the sender's pubkey
        // (it should be an ephemeral key)
        assert_ne!(
            wrapped.pubkey, sender.public_key(),
            "Gift wrap should be signed by an ephemeral key, not the sender"
        );
    }

    // --- try_unwrap returns None for wrong kind ---

    #[tokio::test]
    async fn try_unwrap_wrong_kind_returns_none() {
        let keys = Keys::generate();
        let event = EventBuilder::new(Kind::TextNote, "hello")
            .sign_with_keys(&keys)
            .unwrap();

        let result = GiftWrapReader::try_unwrap(&keys, &event).await;
        assert!(result.is_none());
    }

    // --- try_unwrap returns None for wrong recipient ---

    #[tokio::test]
    async fn try_unwrap_wrong_recipient_returns_none() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let wrong_recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();

        let result = GiftWrapReader::try_unwrap(&wrong_recipient, &wrapped).await;
        assert!(result.is_none());
    }

    // --- try_unwrap returns Some for correct recipient ---

    #[tokio::test]
    async fn try_unwrap_correct_recipient_returns_some() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        let envelope = test_envelope();

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();

        let result = GiftWrapReader::try_unwrap(&recipient, &wrapped).await;
        assert!(result.is_some());
        let (unwrapped, sender_pk) = result.unwrap();
        assert_eq!(unwrapped, envelope);
        assert_eq!(sender_pk, sender.public_key());
    }

    // --- Gift-wrap different message types ---

    #[tokio::test]
    async fn wrap_unwrap_all_message_types() {
        for mt in [
            MlsMessageType::Welcome,
            MlsMessageType::Application,
            MlsMessageType::Proposal,
            MlsMessageType::Commit,
        ] {
            let sender = Keys::generate();
            let recipient = Keys::generate();
            let envelope = MlsEnvelope::new(mt, b"gid".to_vec(), 10, b"payload".to_vec());

            let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
                .await
                .unwrap();
            let (unwrapped, _) = GiftWrapReader::unwrap(&recipient, &wrapped)
                .await
                .unwrap();

            assert_eq!(unwrapped.message_type, mt, "Failed for message type {:?}", mt);
            assert_eq!(unwrapped, envelope, "Failed for message type {:?}", mt);
        }
    }

    //  What is ACTUALLY visible from the outside
    //
    // The audit left one point unresolved: how `EventBuilder::gift_wrap`
    // behaves inside nostr-sdk — time randomization, the set of outer
    // tags. Reading a foreign crate by eye is unreliable: we check what
    // goes to the network, i.e. the SERIALIZED event.
    //
    // This test is not a description but a boundary. It fails if a
    // group id, the sender's persistent key, or any payload part ever
    // starts leaking to the outside.

    /// The outer event contains NOTHING tying it to the conversation.
    #[tokio::test]
    async fn outer_event_reveals_no_conversation_metadata() {
        let sender = Keys::generate();
        let recipient = Keys::generate();
        // Distinctive bytes: if they appear in the outer JSON, we will find them for sure.
        let group_id = vec![0xDE, 0xAD, 0xBE, 0xEF, 0xCA, 0xFE];
        let envelope = MlsEnvelope::new(
            MlsMessageType::Application,
            group_id.clone(),
            7,
            vec![0x11, 0x22, 0x33, 0x44],
        );

        let wrapped = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
            .await
            .unwrap();
        let json = {
            use nostr::util::JsonUtil;
            wrapped.try_as_json().expect("the outer event serializes")
        };

        // 1. The group id is absent outside, in any form.
        let gid_hex = hex::encode(&group_id);
        assert!(
            !json.to_lowercase().contains(&gid_hex),
            "the group id leaked into the outer event",
        );

        // 2. The sender's persistent key is absent outside.
        assert!(
            !json.contains(&sender.public_key().to_hex()),
            "the sender's long-term key is visible outside",
        );
        assert_ne!(wrapped.pubkey, sender.public_key());

        // 3. The payload is not visible.
        assert!(!json.contains("11223344"), "the MLS ciphertext is visible outside");

        // 4. The outer tags are recipient addressing only. Anything else
        //    would mean a new leak.
        for tag in wrapped.tags.iter() {
            let s = tag.as_slice();
            assert!(
                s.first().map(|k| k == "p").unwrap_or(false),
                "unexpected outer tag: {s:?}",
            );
        }

        // 5. The recipient is addressed openly — that is a property of
        //    NIP-59, not our oversight. Pinned as known, accepted visibility.
        assert!(
            json.contains(&recipient.public_key().to_hex()),
            "the recipient address must be there for delivery",
        );
    }

    /// The outer event's timestamp is shifted into the past, not "now".
    ///
    /// Otherwise a batch of envelopes from one group send would cluster
    /// at the same second. The SDK's actual behavior is checked, not its
    /// promise: identical timestamps on two consecutive envelopes is already a signal.
    #[tokio::test]
    async fn outer_created_at_is_randomized() {
        let sender = Keys::generate();
        let envelope = test_envelope();
        let now = Timestamp::now().as_u64();

        let mut stamps = Vec::new();
        for _ in 0..8 {
            let recipient = Keys::generate();
            let w = GiftWrapBuilder::wrap(&sender, &recipient.public_key(), &envelope)
                .await
                .unwrap();
            stamps.push(w.created_at.as_u64());
        }

        // No timestamp may end up in the future.
        assert!(stamps.iter().all(|t| *t <= now + 5), "a timestamp in the future: {stamps:?}");
        // And they must not all be identical — otherwise there is no randomization.
        let all_same = stamps.windows(2).all(|w| w[0] == w[1]);
        assert!(
            !all_same,
            "the outer event time is not randomized: {stamps:?}; envelopes of one send link by the second",
        );
    }
}
