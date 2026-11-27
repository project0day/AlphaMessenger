//! Offline→online catch-up logic.
//!
//! When the app comes back online, `CatchUpManager` queries each relay
//! for events that arrived while we were away (using the Nostr
//! `since` filter) and returns the raw MLS events for downstream
//! processing.
//!
//! # GiftWrap Support (kind 1059)
//!
//! The catch-up query fetches **both** kind-443 (MLS envelope) and
//! kind-1059 (gift-wrapped) events. Gift-wrapped events carry
//! Welcome messages for new chats — without them, catch-up would
//! miss new chat invitations that arrived while offline.
//!
//! # Parallel Relay Queries
//!
//! Queries to multiple relays run concurrently. A pass does not wait for the
//! slowest relay: once one relay has answered, the others get a short grace
//! period; a relay that misses it keeps its watermark and is asked again next
//! pass, so nothing is lost — every event goes to several relays anyway.

use alpha_database::DatabasePool;
use alpha_mls::MlsSessionManager;
use alpha_mls::message::ReceivedMessage;
use alpha_transport::{NostrRelayClient, RawMlsEvent};
use nostr::{Keys, PublicKey};
use std::collections::HashMap;
use std::sync::Arc;

use crate::error::{Result, SyncError};
use crate::event_processor::{EventProcessor, ProcessedEvent};
use crate::sync_state::SyncState;

/// The Nostr kind for MLS envelope events.
const KIND_MLS_ENVELOPE: u16 = 443;
/// The Nostr kind for gift-wrapped events (NIP-59).
const KIND_GIFT_WRAP: u16 = 1059;

/// Manages the offline→online catch-up process.
pub struct CatchUpManager {
    /// Nostr relay client for querying events.
    transport: NostrRelayClient,
    /// Per-relay sync state (checkpoints).
    sync_state: SyncState,
    /// Database pool.
    #[allow(dead_code)]
    db: Arc<DatabasePool>,
    /// Event processor for turning raw events into app-level events.
    processor: EventProcessor,
    /// Our own Nostr pubkey, required for the `#p` filter on kind-1059
    /// gift-wraps. Without it, relays return zero gift-wraps (NIP-59).
    self_pubkey: PublicKey,
}

impl CatchUpManager {
    /// Create a new catch-up manager.
    ///
    /// `contact_avatars_dir` is forwarded to [`EventProcessor`] so
    /// incoming `ProfileUpdate` avatars get written to the app-files
    /// area instead of being lost.
    pub fn new(
        transport: NostrRelayClient,
        sync_state: SyncState,
        db: Arc<DatabasePool>,
        self_pubkey: PublicKey,
        contact_avatars_dir: std::path::PathBuf,
        group_avatars_dir: std::path::PathBuf,
    ) -> Self {
        let processor = EventProcessor::new(
            Arc::clone(&db), contact_avatars_dir, group_avatars_dir,
        );
        Self {
            transport,
            sync_state,
            db,
            processor,
            self_pubkey,
        }
    }

    /// Perform catch-up across all relays (parallel).
    ///
    /// 1. For each relay, get the stored checkpoint.
    /// 2. Build a `since` filter for both kind-443 and kind-1059.
    /// 3. Query all relays **in parallel** for events since the last sync.
    /// 4. Merge and deduplicate results.
    /// 5. Update checkpoints to the latest event seen.
    /// 6. Return all raw MLS events for processing.
    pub async fn catch_up(&self) -> Result<Vec<RawMlsEvent>> {
        let (transport, relay_since, self_pk) = self.prepare_catch_up().await;
        Self::fetch_events(&transport, relay_since, self_pk).await
    }

    /// FETCH-OUTSIDE-LOCK part 1: build the per-relay (url, since) list + clone
    /// the transport/pubkey the network fetch needs. Reads checkpoints ONLY (the
    /// watermark is advanced later in `process_missed_events`, never here), and
    /// does NO network I/O, so the caller can hold the sync_manager lock for just
    /// this cheap step and RELEASE it before the actual round-trip — that's what
    /// stops a slow relay from blocking the realtime listener (the old deadlock).
    pub async fn prepare_catch_up(&self) -> (NostrRelayClient, Vec<(String, u64)>, PublicKey) {
        // An onion relay is unreachable without Tor: asking it only waits out the timeout.
        let tor = alpha_transport::net_switch::tor_route().is_some();
        // Our group's relays we only read are caught up on too (group events only).
        let relay_urls: Vec<String> = self.transport.enabled_relay_urls().await
            .into_iter()
            .chain(self.transport.listen_extra())
            .filter(|u| tor || !is_onion(u))
            .collect();
        // 90s overlap: re-pull a small window past the checkpoint so events
        // published during a brief subscription gap aren't skipped. Dedup +
        // forward-only checkpoint keep the re-pull harmless.
        const CATCH_UP_OVERLAP_SECS: u64 = 90;
        let relay_since: Vec<(String, u64)> = relay_urls
            .iter()
            .map(|url| {
                let since = self
                    .sync_state
                    .checkpoint(url)
                    .map(|cp| (cp.last_timestamp as u64).saturating_sub(CATCH_UP_OVERLAP_SECS))
                    .unwrap_or(0);
                if since == 0 {
                    tracing::info!("No checkpoint for {} — performing full sync", url);
                } else {
                    tracing::info!("Catching up on {} since timestamp {}", url, since);
                }
                (url.clone(), since)
            })
            .collect();
        (self.transport.clone(), relay_since, self.self_pubkey)
    }

    /// FETCH-OUTSIDE-LOCK part 2: the network round-trip. Takes NO `&self`, so it
    /// runs WITHOUT the sync_manager lock held. Returns the deduped raw events for
    /// the caller to process (which re-acquires the lock).
    pub async fn fetch_events(
        transport: &NostrRelayClient,
        relay_since: Vec<(String, u64)>,
        self_pubkey: PublicKey,
    ) -> Result<Vec<RawMlsEvent>> {
        if relay_since.is_empty() {
            tracing::warn!("No relay URLs configured — nothing to catch up on");
            return Ok(Vec::new());
        }

        // Query all relays in PARALLEL
        let mut futures = Vec::new();
        for (relay_url, since) in &relay_since {
            let url = relay_url.clone();
            let s = *since;
            let transport = transport.clone();
            let self_pk = self_pubkey;
            futures.push(async move {
                let result = transport.query_events_since(&url, s, &self_pk).await;
                (url, result)
            });
        }

        let results = gather_until_stragglers(futures).await;

        // Collect all events and update checkpoints
        let mut all_events = Vec::new();

        for (relay_url, query_result) in results {
            match query_result {
                Ok(events) => {
                    if events.is_empty() {
                        tracing::info!("No new events on {}", relay_url);
                        continue;
                    }

                    // NOTE: the checkpoint is deliberately NOT advanced here.
                    // On the wire every event is a kind-443 MLS envelope — text
                    // messages AND ephemeral typing/chat_state look identical and
                    // can't be told apart until decrypted. Advancing on the raw
                    // batch would let frequent typing events shove the watermark
                    // past a real message delivered slightly out of order, which
                    // would then be skipped forever (silent message loss). The
                    // checkpoint is advanced in `process_missed_events`, AFTER
                    // decryption, and ONLY for durable (persisted) events.

                    let kind_443_count = events.iter().filter(|e| e.kind == KIND_MLS_ENVELOPE).count();
                    let kind_1059_count = events.iter().filter(|e| e.kind == KIND_GIFT_WRAP).count();

                    tracing::info!(
                        "Fetched {} events from {} (443: {}, 1059: {})",
                        events.len(),
                        relay_url,
                        kind_443_count,
                        kind_1059_count,
                    );

                    all_events.extend(events);
                }
                Err(e) => {
                    tracing::warn!(
                        "Failed to query events from {}: {} — continuing with other relays",
                        relay_url,
                        e,
                    );
                    // Don't fail the entire catch-up if one relay is down
                }
            }
        }

        // Deduplicate by event_id (same event can arrive from multiple relays).
        all_events.sort_by(|a, b| a.event_id.cmp(&b.event_id));
        all_events.dedup_by(|a, b| a.event_id == b.event_id);

        // Sort by timestamp so we process in order.
        all_events.sort_by_key(|e| e.created_at);

        tracing::info!(
            "Catch-up total: {} events after dedup (443: {}, 1059: {})",
            all_events.len(),
            all_events.iter().filter(|e| e.kind == KIND_MLS_ENVELOPE).count(),
            all_events.iter().filter(|e| e.kind == KIND_GIFT_WRAP).count(),
        );

        Ok(all_events)
    }

    /// Process missed events through the MLS session manager.
    ///
    /// Handles two types of events:
    ///
    /// **Kind 443 (MLS envelope):** Decoded from base64 and passed directly
    /// to the MLS session for decryption.
    ///
    /// **Kind 1059 (gift-wrapped):** Must be unwrapped using the recipient's
    /// Nostr keys (NIP-59) before the inner MLS envelope can be extracted
    /// and decrypted.
    ///
    /// Because [`MlsSessionManager`] is generic over its provider,
    /// this method takes a mutable reference and calls `receive` on it.
    /// The caller is responsible for constructing the session manager
    /// with the appropriate provider.
    pub fn process_missed_events<P: openmls_traits::OpenMlsProvider>(
        &mut self,
        events: Vec<RawMlsEvent>,
        mls_session: &mut MlsSessionManager<P>,
        nostr_keys: &Keys,
    ) -> Result<Vec<ProcessedEvent>> {
        let mut processed = Vec::new();

        // Per-relay durable watermark candidates: (created_at, event_id). The
        // sync checkpoint is advanced ONLY for events that produced a durable
        // (persisted) result — never for ephemeral typing (ChatStateReceived)
        // and never for events that failed to decrypt (they yield no
        // ProcessedEvent at all). This is the single source of truth for
        // catch-up position, shared by both the realtime and catch-up paths.
        let mut watermarks: HashMap<String, (u64, String)> = HashMap::new();

        // Skip events we've already handled (decrypted, or that SecretReuse-failed
        // = already decrypted earlier). Computed once under a single DB lock to
        // avoid re-decrypting the 300s backfill every cycle (the SecretReuse flood
        // that drowns real delivery when a group is forked).
        let already_processed: std::collections::HashSet<String> = {
            let conn = self.db.connection();
            events.iter()
                .filter(|e| alpha_database::ProcessedEventsRepo::is_processed(&conn, &e.event_id))
                .map(|e| e.event_id.clone())
                .collect()
        };

        for raw_event in &events {
            if already_processed.contains(&raw_event.event_id) {
                // Already handled in a prior cycle — don't re-decrypt. BUT we
                // MUST still advance the per-relay watermark past it: a backlog
                // of already-marked events must not pin the checkpoint at the
                // oldest point, or every catch-up re-fetches the same old events
                // forever, hammering the MLS lock in waves. Advancing over
                // handled events lets `since` move toward now so the backlog
                // drops out of the window.
                let entry = watermarks
                    .entry(raw_event.relay_url.clone())
                    .or_insert((0, String::new()));
                if raw_event.created_at >= entry.0 {
                    *entry = (raw_event.created_at, raw_event.event_id.clone());
                }
                continue; // already handled — don't re-decrypt (no SecretReuse)
            }
            tracing::debug!(
                "Processing missed event {} from {} (kind={})",
                raw_event.event_id,
                raw_event.relay_url,
                raw_event.kind,
            );

            let before = processed.len();
            match raw_event.kind {
                KIND_MLS_ENVELOPE => {
                    // Kind 443: direct MLS envelope — decode and decrypt
                    self.process_mls_envelope_event(raw_event, mls_session, &mut processed);
                }
                KIND_GIFT_WRAP => {
                    // Kind 1059: gift-wrapped — unwrap with NIP-59, then process
                    self.process_gift_wrapped_event(raw_event, mls_session, nostr_keys, &mut processed);
                }
                _ => {
                    tracing::warn!(
                        "Unknown event kind {} in catch-up — skipping",
                        raw_event.kind,
                    );
                }
            }

            // Advance the watermark for this relay if this event yielded a
            // durable outcome OR was skipped (own-message / already-processed).
            // ANY event we "handled" (including skips) advances the
            // checkpoint so the query window shrinks; returning early on
            // own-messages and skips without advancing would re-fetch the
            // same overlap every cycle. Failed-decrypt events (genuine
            // errors) still DON'T advance — a real message behind them
            // might be recoverable later.
            let durable = processed[before..].iter().any(Self::is_durable_event);
            let was_skipped = processed.len() == before; // no new events = skipped
            if durable || was_skipped {
                let entry = watermarks
                    .entry(raw_event.relay_url.clone())
                    .or_insert((0, String::new()));
                if raw_event.created_at >= entry.0 {
                    *entry = (raw_event.created_at, raw_event.event_id.clone());
                }
            }
        }

        // Persist the per-relay durable watermarks. `update_checkpoint` only ever
        // advances forward, so this is safe to call from both paths and is a no-op
        // when realtime already moved the checkpoint past this batch.
        for (relay_url, (ts, event_id)) in &watermarks {
            let old_ts = self.sync_state.checkpoint(relay_url)
                .map(|c| c.last_timestamp as u64).unwrap_or(0);
            if let Err(e) = self.sync_state.update_checkpoint(relay_url, event_id, *ts) {
                tracing::warn!("Failed to advance checkpoint for {}: {}", relay_url, e);
            } else {
                // DIAG: prove the watermark advances (it's NOT pinned) + show
                // how far the catch-up window reached. If advanced_by stays ~0
                // the checkpoint is stuck; if it grows, the overlap is just
                // re-reading foreign kind-443 noise on the relay.
                tracing::info!(
                    target: "TRANSPORT",
                    "CATCHUP_WM relay={} old_wm={} new_wm={} advanced_by={}s",
                    relay_url, old_ts, ts, (*ts).saturating_sub(old_ts),
                );
            }
        }

        // Keep the processed-events table bounded (only on the larger catch-up
        // batches, not per realtime message).
        if events.len() > 5 {
            let conn = self.db.connection();
            alpha_database::ProcessedEventsRepo::prune(&conn, 20_000);
        }

        tracing::info!("Processed {} missed events (total {})", processed.len(), events.len());
        Ok(processed)
    }

    /// A `ProcessedEvent` is "durable" if it represents a persisted/handled
    /// outcome whose position should advance the sync checkpoint. Ephemeral
    /// typing/recording signals (`ChatStateReceived`) are explicitly excluded —
    /// they fire constantly and must never move the message watermark.
    fn is_durable_event(e: &ProcessedEvent) -> bool {
        !matches!(e, ProcessedEvent::ChatStateReceived { .. })
    }

    /// Process a kind-443 MLS envelope event.
    fn process_mls_envelope_event<P: openmls_traits::OpenMlsProvider>(
        &self,
        raw_event: &RawMlsEvent,
        mls_session: &mut MlsSessionManager<P>,
        processed: &mut Vec<ProcessedEvent>,
    ) {
        use alpha_transport::{MlsEnvelope, MlsMessageType};

        // Kind-443 event content is a base64-encoded MlsEnvelope wrapper —
        // NOT a raw MLS ciphertext. Feeding the wrapper directly into
        // `MlsSessionManager::receive` always fails because OpenMLS sees
        // garbage instead of an MLSMessage. Parse the envelope first to
        // get the real ciphertext + group_id.
        let envelope = match MlsEnvelope::from_base64(&raw_event.payload_base64) {
            Ok(env) => env,
            Err(_) => {
                // Stranger kind-443 events on shared relays are usually other
                // people's KeyPackages or noise — silently skip them.
                tracing::trace!(
                    "Event {} content is not a parseable MlsEnvelope (likely foreign KeyPackage/noise)",
                    raw_event.event_id,
                );
                return;
            }
        };

        // The envelope already carries the target group_id — no need to
        // brute-force across active_groups().
        let group_id = &envelope.group_id;

        // Early skip: if we don't have this group loaded, attempting receive()
        // burns time + locks for every foreign event on shared relays. Welcome
        // messages (which we DO want to process even without a group) come via
        // gift-wrap (kind 1059), not direct MLS envelopes (kind 443), so it's
        // safe to skip kind-443 with unknown group.
        if mls_session.group_epoch(group_id).is_none() {
            // Shared relays dump a large backlog of kind-443 events for
            // groups we're NOT in — each processing to nothing but taking
            // the MLS lock + CPU on EVERY catch-up/resubscribe, which
            // starves realtime delivery in waves. A kind-443 for a group we
            // never joined can never be decrypted (MLS only decrypts from
            // our join epoch forward), so once such an event is OLD it is
            // terminal — mark it processed so it is filtered before
            // re-processing. Guard by age: a FRESH unknown-group event
            // might be for one of OUR groups whose MLS state hasn't
            // finished loading from storage yet, so leave it unmarked to be
            // retried once the group is live.
            const UNKNOWN_GROUP_TERMINAL_SECS: u64 = 300;
            let now_secs = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs()).unwrap_or(0);
            if now_secs.saturating_sub(raw_event.created_at) > UNKNOWN_GROUP_TERMINAL_SECS {
                let conn = self.db.connection();
                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
            }
            tracing::trace!(
                "Skip event {} for unknown group {} (not joined)",
                raw_event.event_id,
                hex::encode(group_id),
            );
            return;
        }

        match envelope.message_type {
            MlsMessageType::Application => {
                // CRITICAL: skip our OWN messages. When we publish a kind-443
                // Application message, every relay echoes it back to all
                // subscribers — including us. MLS rejects our own messages with
                // `ValidationError(CannotDecryptOwnMessage)` because we already
                // have the plaintext (we sent it). Without this skip, every
                // catch-up cycle re-attempts to decrypt dozens of our own
                // messages, each taking the MLS lock + CPU → flooding the logs
                // + blocking the DB + freezing the UI.
                // With a second phone our key also signs ITS events: then MLS
                // tells ours apart (CannotDecryptOwnMessage below).
                let own_pk_hex = self.self_pubkey.to_hex();
                if !raw_event.author_pubkey_hex.is_empty()
                    && raw_event.author_pubkey_hex == own_pk_hex
                    && !alpha_database::devices::linked()
                {
                    // Mark as processed so the 300s catch-up overlap doesn't
                    // re-fetch + re-skip it every cycle.
                    let conn = self.db.connection();
                    alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                    tracing::trace!(
                        "Skipping own message {} (echo from relay) — CannotDecryptOwnMessage prevented",
                        raw_event.event_id,
                    );
                    return;
                }
                match mls_session.receive(group_id, &envelope.payload) {
                    Ok(ReceivedMessage::Application { data, sender }) => {
                        let chat_id = hex::encode(group_id);
                        // Variant-1 "reappear on a new message" is a 1:1-ONLY
 // behaviour (other messengers DMs): if the user deleted a
                        // DIRECT chat but the MLS group is still alive, re-create
                        // the row so the dialog reappears. A GROUP / channel is
                        // NEVER auto-recreated — leaving or deleting it is
                        // terminal. chat_kind comes from the envelope, never
                        // guessed from member count.
                        let is_group_envelope = matches!(
                            envelope.chat_kind,
                            alpha_transport::ChatKind::Group,
                        );
                        {
                            let conn = self.db.connection();
                            let row_missing = alpha_database::ChatRepo::get(&conn, &chat_id)
                                .ok().flatten().is_none();
                            if row_missing {
                                if is_group_envelope {
                                    // We left/deleted this group — keep it gone.
                                    tracing::info!(
                                        "Group message for {} with no local chat row — NOT recreating (group leave/delete is terminal). Dropped.",
                                        chat_id,
                                    );
                                    alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                                    return;
                                }
                                // DIRECT (1:1): re-create the dialog on this new message.
                                let ah = &raw_event.author_pubkey_hex;
                                let nm = (ah.len() >= 8).then(|| format!("npub…{}", &ah[ah.len() - 8..]));
                                let epoch = mls_session.group_epoch(group_id).unwrap_or(0) as i64;
                                let chat = alpha_database::new_chat(
                                    chat_id.clone(),
                                    alpha_database::models::ChatType::Direct,
                                    nm,
                                    epoch,
                                );
                                let _ = alpha_database::ChatRepo::insert(&conn, &chat);
                            }
                        }
                        // Use the MLS leaf signature key as the stable contact id —
                        // it's bound to the sender's MLS credential, not spoofable
                        // by the relay. Hex-encoded so it fits the TEXT primary key.
                        let sender_id = hex::encode(&sender.signature_key);
                        // Map MLS leaf signature key → nostr pubkey so the UI
                        // so the UI can DM/add group members by their real
                        // npub. The nostr event author IS the sender for
                        // public kind-443 group application messages.
                        // A sealed group event is signed by a throwaway key: its
                        // author names no one.
                        if !raw_event.author_pubkey_hex.is_empty()
                            && raw_event.kind != alpha_transport::sealed::KIND_SEALED_GROUP
                        {
                            let conn = self.db.connection();
                            let kv_key = format!("member_npub:{}", sender_id);
                            let _ = alpha_database::KeyValueRepo::set(
                                &conn, &kv_key, &raw_event.author_pubkey_hex,
                            );
                        }
                        match self.processor.process_application_message(&chat_id, &data, &sender_id, raw_event.created_at) {
                            Ok(event) => {
                                tracing::info!(
                                    "Decrypted application message in chat {} from {} (event {})",
                                    chat_id, sender_id, raw_event.event_id,
                                );
                                processed.push(event);
                            }
                            Err(e) => tracing::warn!("Failed to process application message: {}", e),
                        }
                        // Decrypt succeeded → ratchet advanced → re-decrypt would
                        // SecretReuse. Remember it so catch-up never retries it.
                        {
                            let conn = self.db.connection();
                            alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                        }
                    }
                    Ok(other) => {
                        tracing::warn!(
                            "Envelope said Application but MLS returned {:?}",
                            other,
                        );
                    }
                    Err(e) => {
                        let err_str = e.to_string();
                        if err_str.contains("SecretReuse") {
                            // Already decrypted earlier (realtime or a prior
                            // catch-up) — the ratchet moved past it. Mark it so
                            // we stop re-fetching + re-failing it every cycle.
                            let conn = self.db.connection();
                            alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                            tracing::debug!(
                                "Event {} already decrypted (SecretReuse) — marked processed",
                                raw_event.event_id,
                            );
                        } else if err_str.contains("CannotDecryptOwnMessage") {
                            // CannotDecryptOwnMessage is TERMINAL — it will
                            // never succeed on retry. It happens for (a) our own
                            // published Application messages echoed back by
                            // relays, and (b) peer messages in a FORKED group
                            // whose leaf indices collide with ours. In BOTH
                            // cases re-decrypting is impossible, so mark the
                            // event processed — otherwise every catch-up cycle
                            // re-fetches the same window of own/forked messages,
                            // each taking the MLS lock + CPU until the UI
                            // freezes. MLS_DIAG logs author vs self so a genuine
                            // fork (author != self) can be told from a relay echo.
                            let conn = self.db.connection();
                            alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                            tracing::info!(
                                target: "MLS_DIAG",
                                "OWN_MSG_SKIP event={} author={} self={} group={} — CannotDecryptOwnMessage, marked processed",
                                raw_event.event_id, raw_event.author_pubkey_hex,
                                self.self_pubkey.to_hex(), hex::encode(group_id),
                            );
                        } else if err_str.contains("GroupNotFound") {
                            tracing::debug!(
                                "Event {} for unknown group {} — not joined yet",
                                raw_event.event_id, hex::encode(group_id),
                            );
                        } else if err_str.contains("TooDistantInThePast") {
                            // The per-message ratchet key for this generation
                            // has been discarded — the message arrived far
                            // enough out of order that it fell outside the
                            // retained window (out_of_order_tolerance). It can
                            // NEVER decrypt, so this is genuine, unrecoverable
                            // loss. Mark it processed so catch-up stops
                            // re-fetching + re-failing it every cycle.
                            let conn = self.db.connection();
                            alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                            tracing::warn!(
                                target: "MLS_DIAG",
                                "LOST event={} group={} — TooDistantInThePast (out-of-order beyond window), marked processed",
                                raw_event.event_id, hex::encode(group_id),
                            );
                        } else if err_str.contains("epoch") {
                            // RECOVERABLE — do NOT mark. A FUTURE-epoch
                            // application message (the sender committed ahead
                            // and we have not applied that commit yet) cannot
                            // decrypt until we reach its epoch. Leaving it
                            // UNmarked lets a later catch-up decrypt it once the
                            // commit lands — marking it here would drop a real
                            // message permanently. If the commit never arrives
                            // it harmlessly ages out of the catch-up window.
                            tracing::warn!(
                                "Epoch gap in event {} — will retry after its commit lands: {}",
                                raw_event.event_id, err_str,
                            );
                        } else {
                            tracing::warn!(
                                "MLS receive failed for event {}: {}",
                                raw_event.event_id, err_str,
                            );
                        }
                    }
                }
            }
            MlsMessageType::Commit => {
                // Snapshot membership BEFORE receive so we can tell if THIS commit
                // evicted us (active → gone). A Commit that removes us may make
                // `receive` return Err or a non-Commit result, so we must NOT gate
                // the self-removal cleanup on `Ok(Commit)` — check membership after
                // the attempt regardless of its result.
                let was_member = mls_session.group_info(group_id)
                    .map(|i| i.is_active).unwrap_or(false);
                // MLS_DIAG: full state on every commit — this is what catches an
                // epoch fork. A fork shows as incoming_epoch == current_epoch (two
                // commits competing for the same epoch) or as a persistent
                // current < incoming gap (we missed the winning commit).
                let cur_epoch = mls_session.group_info(group_id).map(|i| i.epoch).unwrap_or(0);
                tracing::info!(
                    target: "MLS_DIAG",
                    "COMMIT_RECV group={} current_epoch={} incoming_epoch={} event={} relay={} author={}",
                    hex::encode(group_id), cur_epoch, envelope.epoch,
                    raw_event.event_id, raw_event.relay_url, raw_event.author_pubkey_hex,
                );
                match mls_session.receive(group_id, &envelope.payload) {
                    Ok(ReceivedMessage::Commit) => {
                        let new_epoch = mls_session.group_info(group_id).map(|i| i.epoch).unwrap_or(0);
                        tracing::info!(
                            target: "MLS_DIAG",
                            "COMMIT_OK group={} epoch {} -> {} event={}",
                            hex::encode(group_id), cur_epoch, new_epoch, raw_event.event_id,
                        );
                        // Record who advanced us to the current epoch so a later
                        // competing commit (same target epoch, different author)
                        // can be classified as a FORK rather than a stale dup.
                        let conn = self.db.connection();
                        let _ = alpha_database::KeyValueRepo::set(
                            &conn,
                            &format!("last_commit_author:{}", hex::encode(group_id)),
                            &raw_event.author_pubkey_hex,
                        );
                        if let Some(i) = mls_session.group_info(group_id) {
                            alpha_database::policy::record_proven(
                                &conn, &hex::encode(group_id), &alpha_mls::leaf_binding::proven_owners(&i.members));
                        }
                                                // An applied commit is terminal — mark it so the
                        // relay backlog dump doesn't re-feed it every cycle (it
                        // would re-fail as WrongEpoch/stale and churn the MLS lock).
                        alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                    }
                    Ok(other) => tracing::debug!("Commit envelope produced {:?}", other),
                    Err(e) => {
                        // --- Precise conflict classification ---
                        // Not every failed commit is interesting. Under the
                        // catch-up overlap the same already-applied commit is
                        // re-fetched every cycle and re-fails with WrongEpoch →
                        // log cascades and lock churn. A genuine epoch fork (two
                        // members committing at once) must be distinguished from
                        // a harmless duplicate so recovery actually triggers.
                        //
                        // `envelope.epoch` is the post-commit epoch the sender
                        // published. Classify against our `cur_epoch`:
                        //   • envelope.epoch <  cur_epoch            → STALE (old)
                        //   • envelope.epoch == cur_epoch, same author → STALE dup
                        //   • envelope.epoch == cur_epoch, diff author → FORK
                        //   • envelope.epoch == cur_epoch + 1 (failed) → FORK (branch)
                        //   • envelope.epoch >  cur_epoch + 1          → GAP (recovery)
                        let chat_id_hex = hex::encode(group_id);
                        let last_author = {
                            let conn = self.db.connection();
                            alpha_database::KeyValueRepo::get(
                                &conn,
                                &format!("last_commit_author:{}", chat_id_hex),
                            ).ok().flatten().unwrap_or_default()
                        };
                        let same_author = !raw_event.author_pubkey_hex.is_empty()
                            && raw_event.author_pubkey_hex == last_author;
                        // A commit whose epoch is <= our current epoch can
                        // NEVER be applied — it is ALWAYS already-applied or
                        // superseded, NOT a fork. The dominant false
                        // positive: joining via a Welcome lands us at epoch
                        // 1, while the creator ALSO broadcasts the Add commit
                        // (epoch 0→1) publicly; fetching it fails WrongEpoch
                        // because we are already at epoch 1, and a Welcome
                        // join records no last_commit_author — so every such
                        // echo would be classified as a FORK, one recovery
                        // churn per group. Treating epoch <= cur as stale
                        // (regardless of author) kills the false-fork storm.
                        // A genuine divergent branch still surfaces as epoch
                        // > cur (a commit we are MISSING), which the GAP/FORK arm handles.
                        let _ = same_author; // retained for diagnostics only
                        let is_stale = envelope.epoch <= cur_epoch;
                        if is_stale {
                            // Already applied (or an older commit we superseded).
                            // Mark processed so the 300s catch-up overlap stops
                            // re-fetching + re-failing on this event forever.
                            let conn = self.db.connection();
                            alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                            tracing::debug!(
                                target: "MLS_DIAG",
                                "COMMIT_STALE group={} cur={} incoming={} author={} event={} — already applied, marked processed",
                                chat_id_hex, cur_epoch, envelope.epoch,
                                raw_event.author_pubkey_hex, raw_event.event_id,
                            );
                        } else {
                            // A real fork or an unrecoverable gap. The group
                            // can no longer decrypt new messages on this branch.
                            // Surface it so the FFI layer (owner) can trigger
                            // `recover_group_from_fork`.
                            tracing::warn!(
                                target: "MLS_DIAG",
                                "FORK_DETECTED group={} cur_epoch={} incoming_epoch={} event={} err={}",
                                chat_id_hex, cur_epoch, envelope.epoch,
                                raw_event.event_id, e,
                            );
                            // Mark the un-appliable fork commit processed.
                            // Surfacing ForkDetected ONCE lets the owner attempt
                            // recovery, but WITHOUT this mark the same WrongEpoch
                            // commit is re-fetched every cycle, re-pushing
                            // ForkDetected → re-triggering recover_group_from_fork
                            // forever, which itself emits new commits → MORE forks
                            // — a self-amplifying loop. The competing commit can
                            // never be applied (we already left the old epoch via
                            // our own commit), so marking is safe — recovery
                            // happens via fresh gift-wrapped Welcomes.
                            {
                                let conn = self.db.connection();
                                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                            }
                            processed.push(ProcessedEvent::ForkDetected {
                                chat_id: chat_id_hex,
                                current_epoch: cur_epoch,
                                incoming_epoch: envelope.epoch,
                                author: raw_event.author_pubkey_hex.clone(),
                            });
                        }
                    }
                }
                // Self-removal detection: if we WERE an active member and now the
                // group is gone/inactive, this commit evicted us.
                let still_active = mls_session.group_info(group_id)
                    .map(|i| i.is_active).unwrap_or(false);
                // Membership hardening: the OWNER can never be removed from
                // their own group. If a (buggy/foreign) commit briefly reads
                // us as inactive, do NOT delete state or set the removed flag
                // for a group we own.
                let chat_id_owner_check = hex::encode(group_id);
                let we_are_owner = {
                    let conn = self.db.connection();
                    alpha_database::group_roles::owner_of(&conn, &chat_id_owner_check)
                        .as_deref() == Some(self.self_pubkey.to_hex().as_str())
                };
                if was_member && !still_active && !we_are_owner {
                    let chat_id = hex::encode(group_id);
                    tracing::info!("We were removed from {} — cleaning local MLS state + setting removed flag", chat_id);
                    // Clean only the MLS group state so a later re-add Welcome can
                    // rejoin cleanly. KEEP the chat row + messages (history + the
                    // admin's "banned: reason" notice).
                    let _ = mls_session.delete_group(group_id);
                    // Persistent "removed" flag (survives restart) so the banned
                    // user ALWAYS sees the read-only notice — independent of live
                    // MLS state. Cleared only on a genuine re-add (Welcome).
                    {
                        let conn = self.db.connection();
                        let _ = alpha_database::KeyValueRepo::set(
                            &conn, &format!("removed_group:{}", chat_id), "1",
                        );
                    }
                    // Emit an event so the caller persists the MLS snapshot and
                    // the UI refreshes into the read-only state.
                    processed.push(ProcessedEvent::ChatMetadataUpdated { chat_id });
                }
            }
            MlsMessageType::Proposal => {
                let _ = mls_session.receive(group_id, &envelope.payload);
                tracing::debug!("Proposal received, queued for later commit");
            }
            MlsMessageType::Welcome => {
                tracing::debug!("Welcome message received in kind-443 (unusual — Welcomes come gift-wrapped)");
            }
            MlsMessageType::JoinRequest | MlsMessageType::AdminInvite | MlsMessageType::DeviceSync => {
                // These only travel gift-wrapped (kind-1059), never as a
                // public kind-443 envelope — ignore here.
            }
        }
    }

    /// Whether a re-key Welcome for a chat we are in is genuine, and if so the
    /// staged join and the re-keyer's leaf key. Nothing changes here.
    ///
    /// Who may re-key: in a 1:1 the other member; in a group its owner or an
    /// admin (the one who brought us in where no owner is recorded) — always
    /// someone already in the chat, never blocked. The Welcome must name the
    /// same group at a newer epoch than ours, and be made by that very person.
    fn authorize_rekey<P: openmls_traits::OpenMlsProvider>(
        &self,
        mls_session: &MlsSessionManager<P>,
        envelope: &alpha_transport::MlsEnvelope,
        sender_pk: &PublicKey,
        cur_epoch: u64,
    ) -> std::result::Result<(alpha_mls::group::StagedJoin, Vec<u8>), String> {
        use alpha_mls::leaf_binding::{self, LeafVerdict};
        let chat_id = hex::encode(&envelope.group_id);
        let sender = sender_pk.to_hex();
        let old = mls_session.group_info(&envelope.group_id).ok_or("no such group")?;
        {
            let conn = self.db.connection();
            if alpha_database::privacy::is_blocked(&conn, &sender) {
                return Err("sender is blocked".into());
            }
            // The owner of a leaf: its proof, else what our directory says.
            let owner = |cred: &[u8], sig: &[u8]| match leaf_binding::check(cred, sig) {
                LeafVerdict::Bound(pk) => Some(hex::encode(pk)),
                LeafVerdict::Legacy => alpha_database::KeyValueRepo::get(&conn, &format!("dir:{chat_id}:{}", hex::encode(sig)))
                    .ok().flatten(),
                LeafVerdict::Forged => None,
            };
            if !old.members.iter().any(|m| owner(&m.credential_bytes, &m.signature_key).as_deref() == Some(sender.as_str())) {
                return Err("sender is not in the chat".into());
            }
            // The chat's kind as we hold it, not as the envelope claims.
            let group = alpha_database::ChatRepo::get(&conn, &chat_id).ok().flatten()
                .map(|c| matches!(c.chat_type, alpha_database::models::ChatType::Group))
                .unwrap_or(old.members.len() > 2);
            if group {
                let role = alpha_database::group_roles::role_of(&conn, &chat_id, &sender);
                let inviter = alpha_database::KeyValueRepo::get(&conn, &format!("dir_bootstrap_auth:{chat_id}")).ok().flatten();
                let allowed = role == alpha_database::group_roles::ROLE_OWNER
                    || role == alpha_database::group_roles::ROLE_ADMIN
                    || (alpha_database::group_roles::owner_of(&conn, &chat_id).is_none() && inviter.as_deref() == Some(sender.as_str()));
                if !allowed {
                    return Err("sender may not re-key this group".into());
                }
            }
        }
        let staged = mls_session.stage_rejoin(&envelope.payload).map_err(|e| format!("unreadable: {e}"))?;
        if staged.group_id() != envelope.group_id {
            return Err("names another group".into());
        }
        if staged.epoch() <= cur_epoch {
            return Err(format!("epoch {} not newer than {}", staged.epoch(), cur_epoch));
        }
        let (cred, sig) = staged.sender().ok_or("no sender leaf")?;
        let made_by_sender = match leaf_binding::check(&cred, &sig) {
            LeafVerdict::Bound(pk) => hex::encode(pk) == sender,
            LeafVerdict::Legacy => {
                let conn = self.db.connection();
                alpha_database::KeyValueRepo::get(&conn, &format!("dir:{chat_id}:{}", hex::encode(&sig)))
                    .ok().flatten().as_deref() == Some(sender.as_str())
            }
            LeafVerdict::Forged => false,
        };
        if !made_by_sender {
            return Err("made by someone else than its sender".into());
        }
        Ok((staged, sig))
    }

    /// Process a kind-1059 gift-wrapped event.
    ///
    /// Gift-wrapped events contain an inner MLS envelope that was
    /// encrypted with NIP-44 for a specific recipient. We need to
    /// unwrap using our Nostr keys to extract the inner envelope,
    /// then process it through MLS.
    fn process_gift_wrapped_event<P: openmls_traits::OpenMlsProvider>(
        &self,
        raw_event: &RawMlsEvent,
        mls_session: &mut MlsSessionManager<P>,
        nostr_keys: &Keys,
        processed: &mut Vec<ProcessedEvent>,
    ) {
        use alpha_database::{ChatRepo, KeyValueRepo, new_chat};
        use alpha_database::models::ChatType;
        use alpha_transport::{GiftWrapReader, MlsMessageType};

        let json = match raw_event.original_event_json.as_deref() {
            Some(j) => j,
            None => {
                tracing::warn!(
                    "Gift-wrap {} arrived without original_event_json — cannot unwrap",
                    raw_event.event_id,
                );
                return;
            }
        };

        let event: nostr::Event = match serde_json::from_str(json) {
            Ok(e) => e,
            Err(e) => {
                tracing::warn!(
                    "Failed to deserialize gift-wrap event {}: {}",
                    raw_event.event_id, e,
                );
                return;
            }
        };

        // Addressed to one of our personas (anonymous channel)? Then only the
        // persona's key opens it — our identity's key never touches it.
        let persona_row = event
            .tags
            .public_keys()
            .next()
            .filter(|pk| **pk != nostr_keys.public_key())
            .and_then(|pk| {
                let conn = self.db.connection();
                alpha_database::personas::by_inbox(&conn, &pk.to_hex())
            });
        let persona_keys: Option<Keys> = persona_row.as_ref().and_then(|p| Keys::parse(&p.nostr_sk).ok());
        let open_with = persona_keys.as_ref().unwrap_or(nostr_keys);

        // NIP-44 decrypt + unwrap. Pure-CPU async fn — safe to drive with
        // futures_executor::block_on even when called from a tokio worker.
        let (envelope, sender_pk, written_at) = match futures_executor::block_on(
            GiftWrapReader::unwrap_with_time(open_with, &event),
        ) {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(
                    "Gift-wrap unwrap failed for {}: {}",
                    raw_event.event_id, e,
                );
                return;
            }
        };

        // Only our own phones hold the key that sealed it: our account's, or
        // this chat's persona's.
        let from_own_device = sender_pk == nostr_keys.public_key()
            || persona_row.as_ref().is_some_and(|p| p.nostr_pk == sender_pk.to_hex());

        tracing::info!(
            "Unwrapped gift-wrap {} from {}: type={:?}, group_id={}B, payload={}B",
            raw_event.event_id,
            sender_pk,
            envelope.message_type,
            envelope.group_id.len(),
            envelope.payload.len(),
        );

        match envelope.message_type {
            MlsMessageType::Welcome => {
                // Skip ONLY a true duplicate Welcome: one for a group we're already
                // in AND whose epoch is not ahead of ours. The same Welcome is
                // re-fetched on every catch-up while it lingers on relays, and
                // re-running join_chat on it fails with NoMatchingKeyPackage (the
                // one-time KeyPackage was consumed) — that wall of log spam + wasted
                // CPU is what this guard suppresses.
                //
                // CRITICAL: a Welcome with a HIGHER epoch than our current group is
                // a re-key / re-add (owner removed+re-added us to recover a desynced
                // group). We MUST process it — skipping it leaves the group
                // permanently broken.
                let mut rekey: Option<(alpha_mls::group::StagedJoin, Vec<u8>)> = None;
                if !envelope.group_id.is_empty() {
                    if let Some(cur_epoch) = mls_session.group_epoch(&envelope.group_id) {
                        tracing::warn!(target: "TRANSPORT", "GW_GROUPINFO cur_epoch={} in_epoch={}", cur_epoch, envelope.epoch);
                        if envelope.epoch <= cur_epoch {
                            // Mark this duplicate Welcome processed. NIP-59
                            // gift-wraps carry a RANDOMIZED past timestamp, so
                            // the catch-up window re-fetches every lingering
                            // Welcome every cycle; without this mark each one is
                            // NIP-44 + MLS unwrapped again every cycle
                            // (expensive). With N groups that is N× heavy
                            // re-decrypts per cycle. epoch <= ours means we
                            // already hold this (or newer) group state, so it is
                            // terminal — safe to mark. (A higher-epoch re-key
                            // Welcome does NOT hit this branch — it falls through
                            // to re-join — so recovery is never suppressed.)
                            tracing::warn!(target: "TRANSPORT", "GW_DBCONN_START (dup mark)");
                            let conn = self.db.connection();
                            tracing::warn!(target: "TRANSPORT", "GW_DBCONN_GOT");
                            alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                            tracing::warn!(target: "TRANSPORT", "GW_MARKED");
                            tracing::debug!(
                                "Welcome {} from {} for group {} is a duplicate (epoch {} <= {}) — marked processed",
                                raw_event.event_id, sender_pk, hex::encode(&envelope.group_id),
                                envelope.epoch, cur_epoch,
                            );
                            return;
                        }
                        // A re-key replaces a chat we are in. The envelope's
                        // group id and epoch are the sender's word only: the
                        // Welcome is read and checked first, and the chat we
                        // have is touched only once it proves to be a real re-key
                        // by someone allowed to make one.
                        match self.authorize_rekey(mls_session, &envelope, &sender_pk, cur_epoch) {
                            Ok(ok) => rekey = Some(ok),
                            Err(why) => {
                                let conn = self.db.connection();
                                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                                tracing::warn!(target: "ACCOUNT", "re-key welcome refused: {why}");
                                return;
                            }
                        }
                    }
                }
                let is_rekey = rekey.is_some();
                // v2 envelopes carry an explicit ChatKind so a 2-member group
                // Welcome can be told apart from a 1:1 DM Welcome (MLS itself
                // doesn't distinguish them — both look like 2 members joining).
                // v1 senders default to Direct via envelope deserialisation.
                let is_group = matches!(envelope.chat_kind, alpha_transport::ChatKind::Group);
                let chat_type = if is_group { ChatType::Group } else { ChatType::Direct };
                let sender_pk_hex = sender_pk.to_hex();
                let kv_key = format!("direct_chat_for:{}", sender_pk_hex);
                let incoming_chat_id = hex::encode(&envelope.group_id);

                // --- Canonical single group per pair (1:1 glare) ---
                // When BOTH sides scan each other's QR and call add_contact_by_npub
                // concurrently, each creates its own MLS group → two chat rows for
                // the same contact ("glare"). Deterministically elect ONE
                // canonical group per (self, peer) pair using a total order on
                // Nostr pubkeys, then converge both sides to it.
                //
                // Rule (applied ONLY for direct chats, when the incoming
                // Welcome's group_id differs from any existing direct chat with
                // the same peer):
                //   • own_pk < sender_pk  → OUR existing group wins. Skip the
                //     incoming Welcome entirely (don't join, don't create a row).
                //     The peer converges symmetrically: their pk > our pk, so
                //     when they receive OUR Welcome they adopt ours.
                //   • sender_pk < own_pk  → INCOMING group wins. Join it, then
                //     DELETE our old duplicate group (MLS state + chat row + KV)
                //     so only the canonical one remains. Messages sent into the
                //     old group are dropped — acceptable because at glare time
                //     (mutual QR scan) no messages have been exchanged yet.
                //
                // This is server-free, deterministic, and converges: both
                // devices end with exactly one chat row for the pair, pointing
                // at the same MLS group_id. No UI workaround needed.
                // --- Who may bring us in (alpha_database::privacy) ---
                // Decided on this phone: a blocked inviter, or an invitation the
                // user's rules refuse, is dropped without a trace; a stranger's
                // lands in Requests. A re-key of a chat we are in is no invitation.
                // We asked to join this very group (an invite link we tapped):
                // that tap was our consent. Whether it is really that group is
                // checked again after the join, against the group MLS gave us.
                let asked_to_join = {
                    let conn = self.db.connection();
                    alpha_database::KeyValueRepo::get(&conn, &format!("join_wanted:{}", hex::encode(&envelope.group_id)))
                        .ok().flatten().is_some()
                };
                let admission = if is_rekey || from_own_device {
                    alpha_database::privacy::Admission::Join
                } else {
                    let conn = self.db.connection();
                    match alpha_database::privacy::admission(&conn, &sender_pk_hex, is_group) {
                        // A blocked inviter stays blocked; any other refusal is
                        // overridden by our own request — confirmed below.
                        alpha_database::privacy::Admission::Ignore
                            if asked_to_join && !alpha_database::privacy::is_blocked(&conn, &sender_pk_hex) =>
                            alpha_database::privacy::Admission::Request,
                        a => a,
                    }
                };
                tracing::info!(target: "ACCOUNT", "welcome ({}): {:?}{}",
                    if is_group { "group" } else { "direct" }, admission,
                    if is_rekey { " (re-key)" } else { "" });
                if admission == alpha_database::privacy::Admission::Ignore {
                    let conn = self.db.connection();
                    alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                    tracing::info!("Welcome {} from {} refused by privacy rules", raw_event.event_id, sender_pk);
                    return;
                }

                let mut old_chat_to_delete: Option<String> = None;
                // The old chat's conversation moves into the new group's chat.
                let mut carry_history = false;
                // The glare/canonical-resolution block applies ONLY when this
                // is NOT a re-key. A re-key (same group_id, higher epoch) has
                // no glare — we deleted the stale state above and just re-join.
                // Running the canonical-pubkey rule on a re-key would be wrong
                // (it could skip the re-join after we already deleted the group).
                if !is_group && !is_rekey && !from_own_device && !envelope.group_id.is_empty() {
                    let conn = self.db.connection();
                    match KeyValueRepo::get(&conn, &kv_key) {
                        Ok(Some(existing_chat_id)) => {
                            if existing_chat_id == incoming_chat_id {
                                // Exact duplicate (same group_id, same/lower
                                // epoch) — already handled by the epoch guard
                                // above, but keep this as a belt-and-suspenders
                                // skip. (A re-key never reaches here: the outer
                                // `!is_rekey` gate bypasses this whole block.)
                                // `conn` is already ours: locking again would
                                // wait for ourselves forever (not reentrant).
                                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                                tracing::info!(
                                    "Welcome {} from {} is a duplicate of existing chat {} — marked processed",
                                    raw_event.event_id, sender_pk, existing_chat_id,
                                );
                                return;
                            }
                            // A 1:1 the user deleted keeps its group and this link
                            // (a message from the peer brings it back), but it is
                            // no glare partner: the peer inviting again wants this
                            // new group. Join it and clear what is left of the old.
                            let row_exists = ChatRepo::get(&conn, &existing_chat_id).ok().flatten().is_some();
                            if !row_exists {
                                tracing::info!(target: "ACCOUNT", "welcome: replaces a deleted chat");
                                old_chat_to_delete = Some(existing_chat_id);
                            } else {
                            // Glare is two EMPTY chats from a mutual scan. A chat
                            // with history means the peer is inviting us again
                            // (they lost or left the old group): the latest
                            // invitation wins and the conversation moves with us,
                            // so there is never a second chat with the same person.
                            let has_history: bool = conn.query_row(
                                "SELECT EXISTS(SELECT 1 FROM messages WHERE chat_id = ?1 AND is_deleted = 0)",
                                [&existing_chat_id],
                                |r| r.get(0),
                            ).unwrap_or(true);
                            // Only a NEWER invitation moves the chat: an old one that
                            // reaches us late (it waited on a relay) would carry the
                            // conversation back into a group the peer left long ago.
                            let chat_since = ChatRepo::get(&conn, &existing_chat_id).ok().flatten()
                                .and_then(|c| chrono::DateTime::parse_from_rfc3339(&c.created_at).ok())
                                .map(|t| t.timestamp().max(0) as u64)
                                .unwrap_or(0);
                            if has_history && written_at > 0 && chat_since > 0 && written_at + 60 < chat_since {
                                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                                tracing::info!(target: "ACCOUNT", "welcome: older than our chat with them ({}s), ignored", chat_since - written_at);
                                return;
                            }
                            if has_history {
                                tracing::info!(target: "ACCOUNT", "welcome: the new group takes over a chat with its history");
                                old_chat_to_delete = Some(existing_chat_id);
                                carry_history = true;
                            } else {
                                let own_pk_hex = self.self_pubkey.to_hex();
                                let our_group_wins = own_pk_hex < sender_pk_hex;
                                tracing::info!(
                                    target: "MLS_GLARE",
                                    "1:1 glare detected: existing={} incoming={} from={} (own={} sender={}) — canonical={}",
                                    existing_chat_id, incoming_chat_id, sender_pk,
                                    own_pk_hex, sender_pk_hex,
                                    if our_group_wins { "existing(ours)" } else { "incoming(peer)" },
                                );
                                if our_group_wins {
                                    // OUR group is canonical. Do NOT join the
                                    // incoming Welcome — leave our existing group
                                    // as the single source of truth. The sender's
                                    // symmetric rule will adopt ours.
                                    alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                                    tracing::info!(target: "ACCOUNT", "welcome: mutual add, our chat stays");
                                    tracing::info!(
                                        "Skipping incoming Welcome {} — keeping our canonical group {} for peer {} (marked processed)",
                                        raw_event.event_id, existing_chat_id, sender_pk,
                                    );
                                    return;
                                }
                                // INCOMING is canonical. We'll join it below and
                                // then delete our old duplicate group so only one
                                // chat row remains.
                                old_chat_to_delete = Some(existing_chat_id);
                            }
                            }
                        }
                        Ok(None) => { /* first direct chat with this peer — proceed */ }
                        Err(e) => tracing::warn!("KV lookup failed: {}", e),
                    }
                }

                let rekey_sender_sig = rekey.as_ref().map(|(_, sig)| sig.clone());
                let joined = match rekey {
                    Some((staged, _)) => mls_session.commit_rejoin(staged),
                    None => mls_session.join_chat(&envelope.payload),
                };
                let group_id = match joined {
                    Ok(gid) => gid,
                    Err(e) => {
                        // A Welcome's KeyPackage is one-time — once consumed,
                        // re-running join_chat on the SAME Welcome always
                        // fails (NoMatchingKeyPackage). NIP-59's randomized
                        // past timestamp keeps re-fetching it every catch-up
                        // cycle, so mark it processed. Terminal for THIS event
                        // — a genuine re-add arrives as a NEW Welcome (new
                        // event_id), so marking is safe.
                        let conn2 = self.db.connection();
                        alpha_database::ProcessedEventsRepo::mark(&conn2, &raw_event.event_id);
                        // In the release log: an invitation made for a KeyPackage this phone
                        // never held (one left by an erased install) is otherwise invisible.
                        tracing::warn!(
                            target: "ACCOUNT",
                            "welcome could not be opened (no matching KeyPackage on this phone?) — marked processed: {}",
                            e,
                        );
                        return;
                    }
                };

                let chat_id = hex::encode(&group_id);
                // Brought in as our persona of this channel (our other phone owns
                // it hidden): from now on we sign there with that persona's leaf.
                if let Some(p) = persona_row.as_ref().filter(|p| p.chat_id == chat_id) {
                    match alpha_mls::Persona::from_json(&p.mls) {
                        Ok(persona) => {
                            mls_session.set_persona(&group_id, persona);
                            let _ = alpha_database::personas::mark_joined(&self.db.connection(), &chat_id);
                        }
                        Err(e) => tracing::warn!(target: "ACCOUNT", "persona of {} unreadable: {}", chat_id, e),
                    }
                }
                // The group we asked for, and MLS confirms it is that one: we're in.
                let joined_by_our_request = {
                    let conn = self.db.connection();
                    let key = format!("join_wanted:{}", chat_id);
                    let wanted = alpha_database::KeyValueRepo::get(&conn, &key).ok().flatten().is_some();
                    if wanted { let _ = alpha_database::KeyValueRepo::delete(&conn, &key); }
                    wanted
                };
                if admission == alpha_database::privacy::Admission::Request && !joined_by_our_request {
                    let conn = self.db.connection();
                    alpha_database::privacy::mark_request(
                        &conn, &chat_id, &sender_pk_hex, if is_group { "group" } else { "direct" },
                        chrono::Utc::now().timestamp(),
                    );
                }
                let info = mls_session.group_info(&group_id);
                let epoch = info.as_ref().map(|i| i.epoch).unwrap_or(0);
                let member_count = info.as_ref().map(|i| i.members.len()).unwrap_or(2);

                // --- Whose directory to trust on join ---
                //
                // `sender_pk` comes NOT from the payload but from the Seal
                // signature of the very Welcome we just joined by — a
                // cryptographic proof of the membership transition, and the
                // only authorization a newcomer can verify on their own: they
                // hold no provenance records of their own and have added no one.
                //
                // The FFI core reads the key (`dir_bootstrap_key`); the format
                // must match — written here, checked there.
                if let Some(sig) = rekey_sender_sig.as_ref() {
                    // A re-key keeps who brought us in; only the re-keyer's
                    // (checked) leaf is bound, and the proven ones recorded.
                    let conn = self.db.connection();
                    let _ = alpha_database::KeyValueRepo::set(
                        &conn, &format!("dir:{chat_id}:{}", hex::encode(sig)), &sender_pk.to_hex());
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("dir_epoch:{chat_id}"), &epoch.to_string());
                    if let Some(i) = info.as_ref() {
                        alpha_database::policy::record_proven(
                            &conn, &chat_id, &alpha_mls::leaf_binding::proven_owners(&i.members));
                    }
                } else {
                    let conn = self.db.connection();
                    let inviter_hex = sender_pk.to_hex();
                    let _ = alpha_database::KeyValueRepo::set(
                        &conn,
                        &format!("dir_bootstrap_auth:{chat_id}"),
                        &inviter_hex,
                    );
                    // Direct chat: exactly one remote member, the inviter.
                    // Bind immediately — the newcomer can then reply without
                    // waiting for a directory at all.
                    // (Brought in by our own phone, the inviter's key is ours:
                    // the leaves' own proofs below say who is who.)
                    if member_count == 2 && !from_own_device {
                        if let Some(i) = info.as_ref() {
                            let own = hex::encode(mls_session.signer_public());
                            for m in &i.members {
                                let sig = hex::encode(&m.signature_key);
                                if sig != own {
                                    let _ = alpha_database::KeyValueRepo::set(
                                        &conn, &format!("dir:{chat_id}:{sig}"), &inviter_hex);
                                    let _ = alpha_database::KeyValueRepo::set(
                                        &conn, &format!("member_npub:{sig}"), &inviter_hex);
                                    let _ = alpha_database::KeyValueRepo::set(
                                        &conn, &format!("dir_epoch:{chat_id}"), &epoch.to_string());
                                }
                            }
                        }
                    }
                    tracing::info!("DIR_BOOTSTRAP stored for chat {chat_id}");
                    if let Some(i) = info.as_ref() {
                        alpha_database::policy::record_proven(
                            &conn, &chat_id, &alpha_mls::leaf_binding::proven_owners(&i.members));
                    }
                }

                // Post-join cleanup (1:1 glare). If we adopted the peer's
                // canonical group, delete our old duplicate group's MLS state +
                // chat row + stale KV so the user sees exactly ONE conversation
                                // with this contact. Messages sent into the old
                // group are orphaned (the peer never joined it); dropping the
                // row is the correct convergence.
                if let Some(old_chat_id) = old_chat_to_delete.take() {
                    if old_chat_id != chat_id {
                        let conn = self.db.connection();
                        if carry_history {
                            // The new row first (messages point at it); the full
                            // insert below then only refreshes its name.
                            let _ = ChatRepo::insert(&conn, &new_chat(chat_id.clone(), chat_type.clone(), None, epoch as i64));
                            for table in ["messages", "whispers", "poll_votes", "poll_closed"] {
                                if let Err(e) = conn.execute(
                                    &format!("UPDATE {table} SET chat_id = ?1 WHERE chat_id = ?2"),
                                    [chat_id.as_str(), old_chat_id.as_str()],
                                ) {
                                    tracing::warn!("history move {}: {}", table, e);
                                }
                            }
                        }
                        // Delete the old MLS group state so it doesn't linger
                        // in the session cache / storage and trigger
                        // SecretReuse floods on later catch-ups.
                        let old_gid = hex::decode(&old_chat_id).unwrap_or_default();
                        if !old_gid.is_empty() {
                            let _ = mls_session.delete_group(&old_gid);
                        }
                        // Drop the old chat row (and via ON DELETE CASCADE, its
                        // messages). This is the row that produced the
                        // "duplicate dialog" in the UI.
                        if let Err(e) = ChatRepo::delete(&conn, &old_chat_id) {
                            tracing::warn!(
                                "glare cleanup: failed to delete old chat {}: {}",
                                old_chat_id, e,
                            );
                        }
                        // Clear stale per-chat KV for the old group.
                        let _ = KeyValueRepo::delete(
                            &conn, &format!("peer_npub_for_chat:{}", old_chat_id),
                        );
                        let _ = KeyValueRepo::delete(
                            &conn, &format!("removed_group:{}", old_chat_id),
                        );
                        tracing::info!(
                            target: "MLS_GLARE",
                            "Adopted canonical group {} from peer {}; deleted duplicate {}",
                            chat_id, sender_pk, old_chat_id,
                        );
                        // Tell the UI to refresh so the old row vanishes.
                        processed.push(ProcessedEvent::ChatMetadataUpdated { chat_id: old_chat_id });
                    }
                }

                // Our own phone sealed it: the other side of a direct chat is
                // whoever else's leaf proves an owner, never that key (ours).
                let sender_pk_hex = if from_own_device {
                    let me = sender_pk.to_hex();
                    info.as_ref()
                        .and_then(|i| alpha_mls::leaf_binding::proven_owners(&i.members).into_iter()
                            .map(|(_, pk)| pk).find(|pk| *pk != me && *pk != self.self_pubkey.to_hex()))
                        .unwrap_or_default()
                } else { sender_pk_hex };
                let kv_key = format!("direct_chat_for:{}", sender_pk_hex);

                // The name the creator shipped in the envelope. An unnamed group
                // stays unnamed: the app shows its own localized title.
                let conn = self.db.connection();
                let display_name = match envelope.name.as_ref().filter(|n| !n.trim().is_empty()) {
                    Some(n) => Some(n.clone()),
                    None if is_group => None,
                    None => Some(format!("npub…{}", &sender_pk_hex[sender_pk_hex.len().saturating_sub(8)..])),
                };

                let chat = new_chat(
                    chat_id.clone(),
                    chat_type,
                    display_name,
                    epoch as i64,
                );
                if let Err(e) = ChatRepo::insert(&conn, &chat) {
                    // Row already exists (e.g. re-add after a previous removal):
                    // the MLS join above already succeeded, so DON'T abort —
                    // just refresh name/epoch and continue. Aborting here
                    // would leave the re-added user in the admin's list but
                    // never actually rejoined on their own device.
                    tracing::info!(
                        "Chat {} exists on Welcome (re-add) — updating instead of insert: {}",
                        chat_id, e,
                    );
                    // Our own phone's history already named it better than a Welcome can.
                    let name = if from_own_device { None } else { chat.name.as_deref() };
                    let _ = ChatRepo::update(&conn, &chat.id, name, Some(epoch as i64));
                }
                // MlsGroupRepo lifecycle: track group as active so leave guard works.
                if is_group {
                    let _ = alpha_database::MlsGroupRepo::insert(
                        &conn,
                        &alpha_database::new_mls_group_row(chat_id.clone(), epoch as i64, 0, true),
                    );
                    // Back in a group we once left: its old row (kept, inactive)
                    // must come alive again, or the chat reads "unavailable".
                    let _ = alpha_database::MlsGroupRepo::update(&conn, &chat_id, None, Some(true));
                }
                // KV mapping is direct-only; for groups skip it.
                if !is_group && !sender_pk_hex.is_empty() {
                    if let Err(e) = KeyValueRepo::set(&conn, &kv_key, &chat_id) {
                        tracing::warn!("KV save for a direct chat mapping failed: {}", e);
                    }
                }
                // Cache the inviter's Nostr pubkey so the UI can
                // render the real npub bech32 in the avatar preview.
                let pk_kv_key = format!("peer_npub_for_chat:{}", chat_id);
                if sender_pk_hex.is_empty() {
                    // Not known from the leaves: our phone's notes bring it.
                } else if let Err(e) = KeyValueRepo::set(&conn, &pk_kv_key, &sender_pk_hex) {
                    tracing::warn!("KV save for {} failed: {}", pk_kv_key, e);
                }

                // Genuine re-add: clear the persistent "removed" flag so the
                // read-only ban notice disappears and the composer returns.
                let _ = KeyValueRepo::delete(&conn, &format!("removed_group:{}", chat_id));

                // Mark the Welcome processed now that we have joined. The
                // same gift-wrap lingers on relays with a randomized past
                // timestamp and would otherwise be re-fetched + NIP-44/MLS
                // re-unwrapped every catch-up cycle (only to become a
                // duplicate skip). Marking here bounds each Welcome to a
                // SINGLE unwrap instead of one per cycle forever.
                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                tracing::info!(
                    "Joined new chat {} via Welcome from {}",
                    chat_id, sender_pk,
                );
                processed.push(ProcessedEvent::NewChat { group_id: chat_id });
            }
            MlsMessageType::DeviceSync => {
                let conn = self.db.connection();
                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                if sender_pk != nostr_keys.public_key() {
                    tracing::warn!(target: "ACCOUNT", "device note not sealed by our key: dropped");
                    return;
                }
                let body = String::from_utf8_lossy(&envelope.payload).to_string();
                let _ = KeyValueRepo::set(&conn, &format!("devsync_in:{}", raw_event.event_id), &body);
                processed.push(ProcessedEvent::DeviceSync);
            }
            MlsMessageType::JoinRequest => {
                // Someone wants to join via an invite link. Surface it so
                // the FFI layer (which has add_member_by_npub + the invite KV)
                // can validate the token + ban list and perform the real add.
                let chat_id = hex::encode(&envelope.group_id);
                let body = serde_json::from_slice::<serde_json::Value>(&envelope.payload).unwrap_or_default();
                let token = body.get("token").and_then(|t| t.as_str()).unwrap_or_default().to_string();
                // Who asks and how to add them: their name and photo (what they
                // chose to show), and a fresh KeyPackage made for this request —
                // no hunt for a published one, which may all be used up.
                {
                    let s = |k: &str, max: usize| body.get(k).and_then(|x| x.as_str())
                        .filter(|v| v.len() <= max).unwrap_or_default().to_string();
                    let held = serde_json::json!({
                        "token": token,
                        "kp": s("kp", 16 * 1024),
                        // The requester's other phones (a few at most).
                        "more": body.get("more").and_then(|m| m.as_array()).map(|a| a.iter()
                            .filter_map(|x| x.as_str().filter(|v| v.len() <= 16 * 1024).map(str::to_string))
                            .take(4).collect::<Vec<_>>()).unwrap_or_default(),
                        "name": s("name", 256).chars().take(64).collect::<String>(),
                        "avatar": s("avatar", 24 * 1024),
                    }).to_string();
                    let conn = self.db.connection();
                    let _ = alpha_database::KeyValueRepo::set(
                        &conn, &format!("join_req:{}:{}", chat_id, sender_pk.to_hex()), &held,
                    );
                }
                let requester_npub = {
                    use nostr::nips::nip19::ToBech32;
                    sender_pk.to_bech32().unwrap_or_else(|_| sender_pk.to_hex())
                };
                // Handled once: catch-up re-reads gift wraps for days, and the
                // same request must not be surfaced (and auto-accepted) again
                // on every pass.
                {
                    let conn = self.db.connection();
                    alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
                }
                tracing::info!("JoinRequest for chat {} from {}", chat_id, sender_pk);
                processed.push(ProcessedEvent::JoinRequested {
                    chat_id,
                    requester_npub,
                    token,
                });
            }
            MlsMessageType::AdminInvite => {
                // Retired: admins are ordinary members now. Drop old invitations.
                let conn = self.db.connection();
                alpha_database::ProcessedEventsRepo::mark(&conn, &raw_event.event_id);
            }
            MlsMessageType::Application => {
                // A group message sent privately (the group's directory goes
                // this way): the very same MLS envelope a group event carries,
                // so it takes the very same path.
                let inner = RawMlsEvent {
                    payload_base64: envelope.to_base64(),
                    kind: KIND_MLS_ENVELOPE,
                    original_event_json: None,
                    author_pubkey_hex: sender_pk.to_hex(),
                    ..raw_event.clone()
                };
                self.process_mls_envelope_event(&inner, mls_session, processed);
            }
            other => {
                tracing::warn!(
                    "Gift-wrapped envelope type {:?} not handled in catch-up",
                    other,
                );
            }
        }
    }

    /// Process gift-wrapped events using the full NostrMlsBridge.
    ///
    /// This is the recommended way to process kind-1059 events during
    /// catch-up, as it has access to the complete Nostr event data
    /// needed for NIP-59 unwrapping.
    pub async fn catch_up_gift_wrapped(
        &self,
        recipient_keys: &Keys,
        since: u64,
    ) -> Result<Vec<alpha_transport::MlsEnvelope>> {
        use alpha_transport::NostrMlsBridge;

        let bridge = NostrMlsBridge::new(self.transport.clone());
        let results = bridge
            .receive_gift_wrapped(recipient_keys, Some(since))
            .await
            .map_err(|e| SyncError::CatchUpFailed(e.to_string()))?;

        let envelopes: Vec<alpha_transport::MlsEnvelope> = results
            .into_iter()
            .map(|(envelope, _sender)| envelope)
            .collect();

        tracing::info!(
            "Unwrapped {} gift-wrapped MLS envelope(s) since {}",
            envelopes.len(),
            since,
        );

        Ok(envelopes)
    }
}

/// How long the other relays may still answer once the first one has.
const STRAGGLER_GRACE: std::time::Duration = std::time::Duration::from_millis(1500);

/// Results in arrival order; after the first success the rest have
/// [STRAGGLER_GRACE] to finish, then the pass goes on without them.
async fn gather_until_stragglers<F, T, E>(futures: Vec<F>) -> Vec<(String, std::result::Result<T, E>)>
where
    F: std::future::Future<Output = (String, std::result::Result<T, E>)>,
{
    use futures_util::stream::{FuturesUnordered, StreamExt};
    let total = futures.len();
    let mut pending: FuturesUnordered<F> = futures.into_iter().collect();
    let mut results = Vec::with_capacity(total);
    let mut deadline: Option<tokio::time::Instant> = None;
    loop {
        let next = match deadline {
            None => pending.next().await,
            Some(d) => match tokio::time::timeout_at(d, pending.next()).await {
                Ok(next) => next,
                Err(_) => {
                    tracing::warn!(target: "TRANSPORT", "CATCHUP stragglers left for the next pass: {}", total - results.len());
                    break;
                }
            },
        };
        let Some((url, result)) = next else { break };
        if result.is_ok() && deadline.is_none() {
            deadline = Some(tokio::time::Instant::now() + STRAGGLER_GRACE);
        }
        results.push((url, result));
    }
    results
}

fn is_onion(url: &str) -> bool {
    let rest = url.split_once("://").map(|(_, r)| r).unwrap_or(url);
    let host = rest.split(['/', ':', '?']).next().unwrap_or("");
    host.to_ascii_lowercase().ends_with(".onion")
}
