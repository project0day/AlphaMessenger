//! Bridge between MLS messages and Nostr transport.
//!
//! [`NostrMlsBridge`] provides the main API for sending and receiving MLS
//! messages over Nostr relays. It decides, based on the presence of a
//! `recipient`, whether to gift-wrap the message (for 1:1 delivery such as
//! Welcome messages) or publish it as a public kind-443 event (for group
//! application messages).
//!
//! # NIP-40 Expiration Tags (STAGE D)
//!
//! When sending ephemeral messages, the bridge adds a NIP-40 `expiration`
//! tag to the Nostr event. This tells supporting relays to drop the
//! ciphertext after the TTL expires, providing best-effort cleanup at the
//! relay level. Note: this only removes the *ciphertext* from relays —
//! local deletion on the device is handled by the [`ExpirySweeper`].

use std::time::Duration;

use base64::Engine;
use nostr::{EventBuilder, EventId, Filter, Keys, Kind, PublicKey, Tag, TagKind, Timestamp};

use crate::envelope::MlsEnvelope;
use crate::error::{Result, TransportError};
use crate::gift_wrap::{GiftWrapBuilder, GiftWrapReader};
use crate::nostr_relay_client::NostrRelayClient;

/// One entry per relay (trailing slash and case ignored), first spelling kept.
fn dedup_relays(urls: &mut Vec<String>) {
    let mut seen = std::collections::HashSet::new();
    urls.retain(|u| seen.insert(u.trim_end_matches('/').to_ascii_lowercase()));
}
use crate::sealed;

/// Relay lifetime of events in "don't keep on servers" chats.
const NO_STORE_EVENT_TTL_SECS: u32 = 7 * 24 * 3600;

/// Tag key used to identify KeyPackage events within kind 443.
const TAG_KEY_PACKAGE: &str = "t";

/// Tag value for KeyPackage events.
const TAG_VALUE_KEY_PACKAGE: &str = "key_package";

/// Tag key for the MLS group ID (for routing).
const TAG_GROUP_ID: &str = "g";

/// Names the phone a KeyPackage belongs to (one account, several phones).
pub const TAG_DEVICE: &str = "device";
/// Application data (NIP-78): the account's list of its phones lives here.
const KIND_APP_DATA: u16 = 30078;
/// The `d` of that list.
pub const DEVICE_LIST_D: &str = "alpha:devices";

/// Default timeout for event queries.
const DEFAULT_QUERY_TIMEOUT: Duration = Duration::from_secs(10);

// ---------------------------------------------------------------------------
// NostrMlsBridge
// ---------------------------------------------------------------------------

/// High-level bridge between the MLS protocol layer and the Nostr transport
/// layer.
///
/// All public methods are thread-safe. The bridge holds a reference to a
/// [`NostrRelayClient`] and delegates relay communication to it.
#[derive(Debug, Clone)]
pub struct NostrMlsBridge {
    /// The relay client used for all transport operations.
    relay_client: NostrRelayClient,
}

impl NostrMlsBridge {
    /// Create a new bridge backed by the given relay client.
    pub fn new(relay_client: NostrRelayClient) -> Self {
        Self { relay_client }
    }

    /// Get a reference to the underlying relay client.
    pub fn relay_client(&self) -> &NostrRelayClient {
        &self.relay_client
    }

    // -----------------------------------------------------------------------
    // Sending
    // -----------------------------------------------------------------------

    /// Send an MLS message via the Nostr transport.
    ///
    /// * If `recipient` is `Some` → the envelope is **gift-wrapped** (NIP-59)
    ///   so only the recipient can read it. Used for Welcome messages and
    ///   1-to-1 delivery.
    /// * If `recipient` is `None` → the envelope is published as a **public**
    ///   kind-443 event. Used for group application messages that all group
    ///   members need to read.
    ///
    /// * If `ttl_secs` is `Some` → a NIP-40 `expiration` tag is added so
    ///   supporting relays drop the event after the TTL. Best-effort only.
    pub async fn send_mls_message(
        &self,
        sender_keys: &Keys,
        envelope: &MlsEnvelope,
        recipient: Option<&PublicKey>,
        ttl_secs: Option<u32>,
    ) -> Result<EventId> {
        self.send_mls_message_at(sender_keys, envelope, recipient, ttl_secs, None).await
    }

    /// Like [`send_mls_message`] but lets the caller pin the event's
    /// `created_at` (Unix seconds). The flush of offline-composed messages uses
    /// the original COMPOSE time so the receiver (which orders by created_at)
    /// shows them in send order even though they're all published at reconnect.
    pub async fn send_mls_message_at(
        &self,
        sender_keys: &Keys,
        envelope: &MlsEnvelope,
        recipient: Option<&PublicKey>,
        ttl_secs: Option<u32>,
        created_at_secs: Option<u64>,
    ) -> Result<EventId> {
        match recipient {
            Some(recipient_pk) => {
                self.send_gift_wrapped(sender_keys, recipient_pk, envelope, ttl_secs)
                    .await
            }
            None => self.send_public(sender_keys, envelope, ttl_secs, created_at_secs).await,
        }
    }

    /// Publish a group MLS envelope as a sealed kind-445 event.
    ///
    /// The envelope is encrypted with the transport key of the MLS epoch it
    /// belongs to and routed by that epoch's opaque tag; the event is signed by
    /// a one-time key. Relays see neither the sender, the group nor the size.
    /// `_sender_keys` (the identity) is deliberately NOT used.
    ///
    /// If `ttl_secs` is provided, a NIP-40 expiration tag is added.
    async fn send_public(
        &self,
        _sender_keys: &Keys,
        envelope: &MlsEnvelope,
        ttl_secs: Option<u32>,
        created_at_secs: Option<u64>,
    ) -> Result<EventId> {
        let ring = self.relay_client.key_ring();
        let key = ring
            .key_for_message(&envelope.group_id, &envelope.payload, envelope.epoch)
            .ok_or_else(|| TransportError::PublishFailed("no transport key for this group yet".into()))?;
        let plaintext = envelope.to_bytes();
        let content = sealed::seal(&key.key, &key.tag, &plaintext)
            .ok_or_else(|| TransportError::PublishFailed("seal failed".into()))?;

        let mut tags: Vec<Tag> = vec![Tag::custom(TagKind::custom(sealed::TAG_ROUTE), [hex::encode(key.tag)])];
        // "Don't keep on servers" chats: every event expires on relays (NIP-40).
        let ttl_secs = ttl_secs.or_else(|| {
            self.relay_client.is_no_store(&envelope.group_id).then_some(NO_STORE_EVENT_TTL_SECS)
        });

        // NIP-40 expiration tag for ephemeral messages.
        // Relays that support NIP-40 will drop the event after this timestamp.
        if let Some(ttl) = ttl_secs {
            let expiration_ts = Timestamp::from(
                (std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs()
                    + u64::from(ttl)) as u64,
            );
            tags.push(Tag::expiration(expiration_ts));
            tracing::debug!(
                "Adding NIP-40 expiration tag: ttl={}s, expires_at={}",
                ttl,
                expiration_ts
            );
        }

        let mut builder = EventBuilder::new(Kind::from(sealed::KIND_SEALED_GROUP), content).tags(tags);
        if let Some(ts) = created_at_secs {
            builder = builder.custom_created_at(Timestamp::from(ts));
        }

        let event = builder
            .sign_with_keys(&sealed::event_signer(&key.key, &plaintext))
            .map_err(|e| TransportError::PublishFailed(format!("signing failed: {e}")))?;
        self.relay_client.mark_own_event(&event.id.to_hex());

        // Also where the other members listen: no relay has to be shared.
        let route = self.relay_client.group_route(&envelope.group_id);
        let event_id = self.relay_client.publish_routed(event, route).await?;

        tracing::info!(
            "Published sealed group event: event_id={}, type={}, epoch={}, ttl={:?}",
            event_id,
            envelope.message_type,
            key.epoch,
            ttl_secs,
        );

        Ok(event_id)
    }

    /// Send a gift-wrapped MLS envelope to a specific recipient.
    ///
    /// If `ttl_secs` is provided, the inner rumor gets a NIP-40 expiration tag
    /// so that supporting relays will drop the gift-wrap after the TTL.
    async fn send_gift_wrapped(
        &self,
        sender_keys: &Keys,
        recipient: &PublicKey,
        envelope: &MlsEnvelope,
        ttl_secs: Option<u32>,
    ) -> Result<EventId> {
        // The gift-wrap event itself doesn't carry the expiration directly
        // (NIP-59 structure). However, we can set an expiration on the
        // outer gift-wrap event (kind 1059) so relays drop it after TTL.
        // The inner rumor is ephemeral by NIP-59 design.
        let gift_wrap_event = GiftWrapBuilder::wrap_with_ttl(sender_keys, recipient, envelope, ttl_secs).await?;

        // Never on our own connection: it carries our npub, and a wrap arriving
        // over it names who wrote to the `#p` recipient. A separate short-lived
        // connection with a random key takes it to our relays and to the
        // well-known ones (the recipient may share none of ours).
        let mut urls = self.relay_client.enabled_relay_urls().await;
        urls.extend(self.relay_client.well_known_extra().await);
        urls.extend(self.relay_client.inbox_route(recipient));
        dedup_relays(&mut urls);
        let event_id = gift_wrap_event.id;
        if NostrRelayClient::publish_detached(urls, vec![gift_wrap_event], Duration::from_secs(10)).await == 0 {
            return Err(TransportError::PublishFailed("gift wrap: no relay accepted it".into()));
        }

        tracing::info!(
            "Published gift-wrapped MLS envelope: event_id={}, type={}, recipient={}, ttl={:?}",
            event_id,
            envelope.message_type,
            recipient,
            ttl_secs,
        );

        Ok(event_id)
    }

    // -----------------------------------------------------------------------
    // Receiving
    // -----------------------------------------------------------------------

    /// Receive MLS envelope events from relays.
    ///
    /// Subscribes to kind-443 events (public MLS envelopes) and parses them
    /// into [`MlsEnvelope`] instances. If `since` is provided, only events
    /// after that timestamp are fetched (catch-up mode).
    ///
    /// **Note:** This only fetches *public* (non-gift-wrapped) MLS envelopes.
    /// Gift-wrapped messages (kind 1059) must be handled separately via
    /// [`GiftWrapReader`].
    pub async fn receive_mls_events(
        &self,
        since: Option<u64>,
    ) -> Result<Vec<MlsEnvelope>> {
        let mut filter = Filter::new().kind(Kind::MlsKeyPackage);

        if let Some(ts) = since {
            filter = filter.since(Timestamp::from(ts));
        }

        let events = self
            .relay_client
            .get_events_of(filter, DEFAULT_QUERY_TIMEOUT)
            .await?;

        let mut envelopes = Vec::with_capacity(events.len());
        for event in events {
            match MlsEnvelope::from_base64(&event.content) {
                Ok(envelope) => envelopes.push(envelope),
                Err(e) => {
                    // Skip malformed envelopes but log the error.
                    tracing::warn!(
                        "Failed to parse MLS envelope from event {}: {e}",
                        event.id
                    );
                }
            }
        }

        tracing::debug!("Received {} MLS envelope(s)", envelopes.len());
        Ok(envelopes)
    }

    /// Receive MLS envelope events from a specific author.
    pub async fn receive_mls_events_from(
        &self,
        author: &PublicKey,
        since: Option<u64>,
    ) -> Result<Vec<MlsEnvelope>> {
        let mut filter = Filter::new()
            .kind(Kind::MlsKeyPackage)
            .author(*author);

        if let Some(ts) = since {
            filter = filter.since(Timestamp::from(ts));
        }

        let events = self
            .relay_client
            .get_events_of(filter, DEFAULT_QUERY_TIMEOUT)
            .await?;

        let mut envelopes = Vec::with_capacity(events.len());
        for event in events {
            match MlsEnvelope::from_base64(&event.content) {
                Ok(envelope) => envelopes.push(envelope),
                Err(e) => {
                    tracing::warn!(
                        "Failed to parse MLS envelope from event {}: {e}",
                        event.id
                    );
                }
            }
        }

        Ok(envelopes)
    }

    /// Receive and unwrap gift-wrapped MLS envelopes intended for `recipient`.
    ///
    /// Subscribes to kind-1059 (GiftWrap) events and attempts to unwrap each
    /// one using the recipient's keys.
    pub async fn receive_gift_wrapped(
        &self,
        recipient: &Keys,
        since: Option<u64>,
    ) -> Result<Vec<(MlsEnvelope, PublicKey)>> {
        let mut filter = Filter::new().kind(Kind::GiftWrap);

        if let Some(ts) = since {
            filter = filter.since(Timestamp::from(ts));
        }

        let events = self
            .relay_client
            .get_events_of(filter, DEFAULT_QUERY_TIMEOUT)
            .await?;

        let mut results = Vec::with_capacity(events.len());
        for event in events {
            match GiftWrapReader::unwrap(recipient, &event).await {
                Ok((envelope, sender)) => results.push((envelope, sender)),
                Err(e) => {
                    // Not all gift-wrap events may be intended for us, or they
                    // may use a different format — skip gracefully.
                    tracing::debug!(
                        "Skipping gift-wrap event {}: {e}",
                        event.id
                    );
                }
            }
        }

        tracing::debug!(
            "Unwrapped {} gift-wrapped MLS envelope(s)",
            results.len()
        );
        Ok(results)
    }

    // -----------------------------------------------------------------------
    // Key Packages
    // -----------------------------------------------------------------------

    /// Publish an MLS KeyPackage as a kind-443 event.
    ///
    /// KeyPackages are **public** so that other users can include them when
    /// creating Welcome messages for new group members. The event is tagged
    /// with `"t":"key_package"` for discoverability.
    pub async fn publish_key_package(
        &self,
        keys: &Keys,
        key_package_bytes: Vec<u8>,
    ) -> Result<EventId> {
        let event = Self::key_package_event(keys, &key_package_bytes, None)?;
        let event_id = self.relay_client.publish(event).await?;

        tracing::info!(
            "Published KeyPackage: event_id={}, author={}",
            event_id,
            keys.public_key()
        );

        Ok(event_id)
    }

    /// The signed kind-443 event carrying a KeyPackage (tagged `t=key_package`).
    pub fn key_package_event(keys: &Keys, key_package_bytes: &[u8], device: Option<&str>) -> Result<nostr::Event> {
        let content = base64::engine::general_purpose::STANDARD.encode(key_package_bytes);
        let mut tags = vec![Tag::custom(
            TagKind::custom(TAG_KEY_PACKAGE),
            [TAG_VALUE_KEY_PACKAGE],
        )];
        if let Some(d) = device.filter(|d| !d.is_empty()) {
            tags.push(Tag::custom(TagKind::custom(TAG_DEVICE), [d]));
        }
        EventBuilder::new(Kind::MlsKeyPackage, content).tags(tags)
            .sign_with_keys(keys)
            .map_err(|e| TransportError::PublishFailed(format!("signing failed: {e}")))
    }

    /// Copy KeyPackage events to the well-known relays we don't use ourselves,
    /// on a separate connection: whoever adds us finds them even when our own
    /// relays are down, ban us, or are not theirs. `None` = every well-known
    /// relay is already ours (nothing to copy); else how many got through.
    pub async fn mirror_key_packages(&self, events: Vec<nostr::Event>) -> Option<usize> {
        let extra = self.relay_client.well_known_extra().await;
        if extra.is_empty() { return None; }
        Some(NostrRelayClient::publish_detached(extra, events, Duration::from_secs(6)).await)
    }

    /// Fetch KeyPackages published by a given public key, newest first.
    pub async fn fetch_key_packages(
        &self,
        pubkey: &PublicKey,
    ) -> Result<Vec<Vec<u8>>> {
        Ok(self.fetch_device_key_packages(pubkey).await.into_iter().map(|(_, kp)| kp).collect())
    }

    /// KeyPackages of `pubkey` with the phone each belongs to (`None`: an
    /// older build that does not say), newest first.
    pub async fn fetch_device_key_packages(&self, pubkey: &PublicKey) -> Vec<(Option<String>, Vec<u8>)> {
        // A fresh connection: allow for the handshakes (through Tor too).
        const LOOKUP_TIMEOUT: Duration = Duration::from_secs(8);

        // We publish ALL MLS-envelope events under Kind::MlsKeyPackage (443):
        // KeyPackages, Application messages, Commits, public Welcomes. Without
        // a server-side filter on the `t=key_package` tag, a relay returning
        // the last N events for this author hands us mostly application
        // messages — and `limit(10)` then misses every real KP. Filter by tag
        // at the relay (NIP-12 generic tag query) and raise the limit so the
        // pool of 10 KPs fits comfortably even with chatter in between.
        use nostr::{Alphabet, SingleLetterTag};
        let filter = Filter::new()
            .kind(Kind::MlsKeyPackage)
            .author(*pubkey)
            .custom_tag(SingleLetterTag::lowercase(Alphabet::T), TAG_VALUE_KEY_PACKAGE)
            .limit(50);

        // Never on our own connection: it carries our npub, and this lookup
        // says whom we are about to add. One anonymous query to our relays
        // and the well-known ones at once — KeyPackages are public, and
        // whether two people can reach each other must not depend on both
        // choosing the same relay.
        let mut urls = self.relay_client.enabled_relay_urls().await;
        urls.extend(self.relay_client.well_known_extra().await);
        urls.extend(self.relay_client.inbox_route(pubkey));
        dedup_relays(&mut urls);
        let mut events = NostrRelayClient::fetch_anonymous(urls, filter, LOOKUP_TIMEOUT)
            .await
            .unwrap_or_else(|e| {
                tracing::warn!("fetch_key_packages: lookup failed: {e}");
                Vec::new()
            });
        let total = events.len();

        // Sort newest first. A peer may have several KPs sitting on
        // the relay (e.g. from a previous install whose private key
        // is gone). The most recent one is the one whose private
        // material the peer still has — without this ordering we
        // randomly hand the caller a dead KP and join_chat fails
        // with `NoMatchingKeyPackage`.
        events.sort_by_key(|e| std::cmp::Reverse(e.created_at));

        let mut packages = Vec::with_capacity(events.len());
        let mut tag_skipped = 0usize;
        let mut decode_failed = 0usize;
        for event in events {
            let mut is_key_package = false;
            let mut device = None;
            for tag in event.tags.iter() {
                let slice = tag.as_slice();
                if slice.len() >= 2 && slice[0] == TAG_KEY_PACKAGE && slice[1] == TAG_VALUE_KEY_PACKAGE {
                    is_key_package = true;
                }
                if slice.len() >= 2 && slice[0] == TAG_DEVICE && !slice[1].is_empty() {
                    device = Some(slice[1].to_string());
                }
            }
            if !is_key_package {
                tag_skipped += 1;
                continue;
            }
            match base64::engine::general_purpose::STANDARD.decode(&event.content) {
                Ok(bytes) => packages.push((device, bytes)),
                Err(e) => {
                    decode_failed += 1;
                    tracing::warn!("Failed to base64-decode KeyPackage from event {}: {e}", event.id);
                }
            }
        }
        tracing::info!(
            "Fetched {} KeyPackage(s) for pubkey={} (raw={}, tag_skipped={}, decode_failed={}); newest first",
            packages.len(), pubkey, total, tag_skipped, decode_failed,
        );
        packages
    }

    /// The account's own list of its phones (signed by its key), with the time
    /// it was written; `None` while the account has never listed any.
    pub async fn fetch_device_list(&self, pubkey: &PublicKey) -> Option<(Vec<String>, u64)> {
        let filter = Filter::new()
            .kind(Kind::Custom(KIND_APP_DATA))
            .author(*pubkey)
            .identifier(DEVICE_LIST_D)
            .limit(5);
        let mut urls = self.relay_client.enabled_relay_urls().await;
        urls.extend(self.relay_client.well_known_extra().await);
        let events = NostrRelayClient::fetch_anonymous(urls, filter, Duration::from_secs(8)).await.ok()?;
        let newest = events.into_iter().filter(|e| e.verify().is_ok()).max_by_key(|e| e.created_at)?;
        let v: serde_json::Value = serde_json::from_str(&newest.content).ok()?;
        let ids = v.get("devices")?.as_array()?.iter().filter_map(|x| x.as_str().map(str::to_string)).collect();
        Some((ids, newest.created_at.as_secs()))
    }

    /// The signed list of our phones, for whoever adds us to a chat.
    pub fn device_list_event(keys: &Keys, ids: &[String]) -> Result<nostr::Event> {
        let content = serde_json::json!({ "v": 1, "devices": ids }).to_string();
        EventBuilder::new(Kind::Custom(KIND_APP_DATA), content)
            .tags([Tag::identifier(DEVICE_LIST_D)])
            .sign_with_keys(keys)
            .map_err(|e| TransportError::PublishFailed(format!("signing failed: {e}")))
    }

    /// Gift wraps addressed to `pubkey` since `since` (unix seconds), read on a
    /// one-off connection: a phone being linked has no account of its own yet.
    pub async fn fetch_gift_wraps_for(&self, pubkey: &PublicKey, since: u64) -> Vec<nostr::Event> {
        let filter = Filter::new().kind(Kind::GiftWrap).pubkey(*pubkey).since(Timestamp::from(since));
        let mut urls = self.relay_client.enabled_relay_urls().await;
        urls.extend(self.relay_client.well_known_extra().await);
        NostrRelayClient::fetch_anonymous(urls, filter, Duration::from_secs(8)).await.unwrap_or_default()
    }
}

/// Compile-time assertion that `NostrMlsBridge` is `Send + Sync`.
const _: () = {
    fn _assert() {
        fn is_send_sync<T: Send + Sync>() {}
        is_send_sync::<NostrMlsBridge>();
    }
};

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
            MlsMessageType::Application,
            vec![0x01, 0x02, 0x03, 0x04],
            42,
            vec![0xAA, 0xBB, 0xCC],
        )
    }

    // ── send_public produces correct kind-443 event with group_id tag ──
    //
    // We test the event *construction* logic by building the event manually
    // (mirroring what send_public does) and checking the wire format.
    // We cannot call send_public() directly because it requires a relay
    // connection to publish.

    #[test]
    fn send_public_event_construction_kind_443() {
        let envelope = test_envelope();
        let keys = Keys::generate();

        // Replicate the event construction logic from send_public
        let content = envelope.to_base64();
        let mut tags: Vec<Tag> = Vec::new();
        if !envelope.group_id.is_empty() {
            tags.push(Tag::custom(
                TagKind::custom(TAG_GROUP_ID),
                [hex::encode(&envelope.group_id)],
            ));
        }

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        // Verify kind is 443
        assert_eq!(event.kind, Kind::MlsKeyPackage);
        assert_eq!(event.kind.as_u16(), 443);

        // Verify group_id tag is present
        let group_tag = event.tags.iter().find(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == TAG_GROUP_ID
        });
        assert!(group_tag.is_some(), "Event should have a 'g' (group_id) tag");

        let tag_slice = group_tag.unwrap().as_slice();
        assert_eq!(tag_slice[1], hex::encode(&envelope.group_id));
    }

    #[test]
    fn send_public_event_content_is_base64_envelope() {
        let envelope = test_envelope();
        let keys = Keys::generate();

        let content = envelope.to_base64();
        let mut tags: Vec<Tag> = Vec::new();
        if !envelope.group_id.is_empty() {
            tags.push(Tag::custom(
                TagKind::custom(TAG_GROUP_ID),
                [hex::encode(&envelope.group_id)],
            ));
        }

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        // Content should be a valid base64-encoded MlsEnvelope
        let parsed = MlsEnvelope::from_base64(&event.content).unwrap();
        assert_eq!(parsed, envelope);
    }

    #[test]
    fn send_public_event_no_group_id_tag_when_empty() {
        let envelope = MlsEnvelope::new(
            MlsMessageType::Application,
            vec![], // empty group_id
            1,
            vec![0x01],
        );
        let keys = Keys::generate();

        let content = envelope.to_base64();
        let mut tags: Vec<Tag> = Vec::new();
        if !envelope.group_id.is_empty() {
            tags.push(Tag::custom(
                TagKind::custom(TAG_GROUP_ID),
                [hex::encode(&envelope.group_id)],
            ));
        }

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        // Should NOT have a 'g' tag when group_id is empty
        let has_group_tag = event.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 1 && slice[0] == TAG_GROUP_ID
        });
        assert!(!has_group_tag, "Empty group_id should not produce a 'g' tag");
    }

    #[test]
    fn send_public_event_with_ttl_has_expiration_tag() {
        let envelope = test_envelope();
        let keys = Keys::generate();

        let content = envelope.to_base64();
        let mut tags: Vec<Tag> = Vec::new();
        if !envelope.group_id.is_empty() {
            tags.push(Tag::custom(
                TagKind::custom(TAG_GROUP_ID),
                [hex::encode(&envelope.group_id)],
            ));
        }

        // Add NIP-40 expiration tag (same logic as send_public)
        let ttl_secs: u32 = 3600;
        let expiration_ts = Timestamp::from(
            (std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs()
                + u64::from(ttl_secs)) as u64,
        );
        tags.push(Tag::expiration(expiration_ts));

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        // Verify expiration tag is present
        let has_expiration = event.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == "expiration"
        });
        assert!(has_expiration, "Event with TTL should have an expiration tag");
    }

    #[test]
    fn send_public_event_without_ttl_no_expiration_tag() {
        let envelope = test_envelope();
        let keys = Keys::generate();

        let content = envelope.to_base64();
        let mut tags: Vec<Tag> = Vec::new();
        if !envelope.group_id.is_empty() {
            tags.push(Tag::custom(
                TagKind::custom(TAG_GROUP_ID),
                [hex::encode(&envelope.group_id)],
            ));
        }
        // No TTL → no expiration tag

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        let has_expiration = event.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == "expiration"
        });
        assert!(!has_expiration, "Event without TTL should NOT have an expiration tag");
    }

    // ── receive_mls_events: parsing logic ──

    #[test]
    fn receive_mls_events_parses_kind_443_content() {
        // Simulate what receive_mls_events does: parse base64 content from events
        let envelope = test_envelope();
        let content = envelope.to_base64();

        let parsed = MlsEnvelope::from_base64(&content).unwrap();
        assert_eq!(parsed, envelope);
    }

    #[test]
    fn receive_mls_events_skips_malformed_content() {
        // Malformed base64 should be skipped gracefully
        let result = MlsEnvelope::from_base64("not-valid-base64!!!");
        assert!(result.is_err());
    }

    // ── publish_key_package: produces event with "t":"key_package" tag ──

    #[test]
    fn publish_key_package_event_construction() {
        let keys = Keys::generate();
        let key_package_bytes = vec![0x01, 0x02, 0x03, 0x04, 0x05];

        // Replicate the event construction logic from publish_key_package
        let content = base64::engine::general_purpose::STANDARD.encode(&key_package_bytes);

        let tags = vec![Tag::custom(
            TagKind::custom(TAG_KEY_PACKAGE),
            [TAG_VALUE_KEY_PACKAGE],
        )];

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        // Verify kind is 443
        assert_eq!(event.kind, Kind::MlsKeyPackage);

        // Verify "t":"key_package" tag
        let key_package_tag = event.tags.iter().find(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == TAG_KEY_PACKAGE && slice[1] == TAG_VALUE_KEY_PACKAGE
        });
        assert!(key_package_tag.is_some(), "Event should have a 't':'key_package' tag");

        // Verify content is base64-decodable to the original bytes
        let decoded = base64::engine::general_purpose::STANDARD.decode(&event.content).unwrap();
        assert_eq!(decoded, key_package_bytes);
    }

    // ── fetch_key_packages: filter construction ──

    #[test]
    fn fetch_key_packages_filter_construction() {
        let pubkey = Keys::generate().public_key();

        // Replicate the filter construction logic from fetch_key_packages
        let filter = Filter::new()
            .kind(Kind::MlsKeyPackage)
            .author(pubkey)
            .limit(10);

        assert!(filter.kinds.as_ref().map_or(false, |s| s.contains(&Kind::MlsKeyPackage)));
        assert!(filter.authors.as_ref().map_or(false, |s| s.contains(&pubkey)));
        assert_eq!(filter.limit, Some(10));
    }

    #[test]
    fn fetch_key_packages_filter_json_format() {
        let pubkey = Keys::generate().public_key();

        let filter = Filter::new()
            .kind(Kind::MlsKeyPackage)
            .author(pubkey)
            .limit(10);

        let json = serde_json::to_value(&filter).unwrap();

        // Verify "kinds" contains 443
        let kinds = json.get("kinds").expect("filter should have 'kinds'");
        assert!(kinds.as_array().unwrap().iter().any(|k| k.as_u64() == Some(443)));

        // Verify "authors" contains the pubkey
        let authors = json.get("authors").expect("filter should have 'authors'");
        assert!(authors.as_array().unwrap().iter().any(|a| a.as_str() == Some(pubkey.to_hex().as_str())));

        // Verify "limit"
        assert_eq!(json.get("limit").and_then(|l| l.as_u64()), Some(10));
    }

    #[test]
    fn fetch_key_packages_tag_parsing() {
        let keys = Keys::generate();
        let key_package_bytes = vec![0x01, 0x02, 0x03];

        let content = base64::engine::general_purpose::STANDARD.encode(&key_package_bytes);
        let tags = vec![Tag::custom(
            TagKind::custom(TAG_KEY_PACKAGE),
            [TAG_VALUE_KEY_PACKAGE],
        )];

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        // Replicate the tag checking logic from fetch_key_packages
        let is_key_package = event.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2
                && slice[0] == TAG_KEY_PACKAGE
                && slice[1] == TAG_VALUE_KEY_PACKAGE
        });
        assert!(is_key_package, "Event should be identified as a key_package");

        // Verify base64 content decoding
        let decoded = base64::engine::general_purpose::STANDARD.decode(&event.content).unwrap();
        assert_eq!(decoded, key_package_bytes);
    }

    #[test]
    fn fetch_key_packages_ignores_non_key_package_tags() {
        let keys = Keys::generate();
        let content = base64::engine::general_purpose::STANDARD.encode(b"some-data");

        // Create an event with a different tag (not "t":"key_package")
        let tags = vec![Tag::custom(
            TagKind::custom("x"),
            ["something_else"],
        )];

        let event = EventBuilder::new(Kind::MlsKeyPackage, content)
            .tags(tags)
            .sign_with_keys(&keys)
            .unwrap();

        // Should NOT be identified as a key_package
        let is_key_package = event.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2
                && slice[0] == TAG_KEY_PACKAGE
                && slice[1] == TAG_VALUE_KEY_PACKAGE
        });
        assert!(!is_key_package, "Event without key_package tag should not be identified as one");
    }

    // ── Constants verification ──

    #[test]
    fn tag_constants_are_correct() {
        assert_eq!(TAG_KEY_PACKAGE, "t");
        assert_eq!(TAG_VALUE_KEY_PACKAGE, "key_package");
        assert_eq!(TAG_GROUP_ID, "g");
    }

    #[test]
    fn nostr_kind_mls_envelope_is_443() {
        assert_eq!(crate::NOSTR_KIND_MLS_ENVELOPE, 443);
    }

    // ── NostrMlsBridge construction ──

    #[test]
    fn bridge_new_stores_relay_client() {
        let client = NostrRelayClient::new(vec!["wss://relay.example".to_string()]);
        let bridge = NostrMlsBridge::new(client);

        // Verify the bridge holds the relay client reference
        let relay = bridge.relay_client();
        let _ = relay.inner();
    }
}
