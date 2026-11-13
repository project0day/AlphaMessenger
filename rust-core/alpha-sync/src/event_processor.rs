//! Event processing pipeline.
//!
//! Turns decrypted MLS application messages into application-level events
//! that the UI can act on.
//!
//! # Ephemeral messages
//!
//! When persisting ephemeral messages:
//! - **AfterSend**: `expires_at` is computed at send time and persisted.
//! - **AfterRead**: `expires_at` is `NULL` until the recipient reads the
//!   message. When read, `read_at` is recorded, `expires_at` is computed,
//!   and a `ReadSignal` is sent back to the sender.
//! - **ViewOnce**: `content` is **never** written to disk (memory-only).
//!   The message row is created with `content = NULL` and
//!   `expiry_policy = 'view_once'`. The plaintext is held in an
//!   [`EphemeralPlaintext`] and zeroized after first view.

use alpha_database::{DatabasePool, MessageRepo, ChatRepo, ContactRepo, new_message, new_ephemeral_message, new_contact};
use alpha_messages::{AlphaMessage, deserialize, ExpiryPolicy};
use alpha_database::models::{ExpiryPolicyType, MessageType as DbMessageType};
use std::sync::Arc;

use crate::error::{Result, SyncError};

/// An application-level event produced from a raw MLS message.
#[derive(Debug, Clone)]
pub enum ProcessedEvent {
    /// A new message arrived in a chat.
    NewMessage {
        chat_id: String,
        message: AlphaMessage,
        /// Written on another phone of ours: no receipt goes back.
        own_device: bool,
    },
    /// A read signal was received (the recipient opened an AfterRead message).
    /// A member says these messages of ours reached them.
    DeliveredReceived {
        chat_id: String,
        /// The member (MLS leaf key, hex) who received them.
        sender_id: String,
        message_ids: Vec<String>,
    },
    ReadSignalReceived {
        chat_id: String,
        target_id: String,
        read_at: String,
    },
    /// A transient typing / recording signal from a peer. Not persisted —
    /// the UI shows an indicator and auto-clears it.
    ChatStateReceived {
        chat_id: String,
        /// Sender's stable id (MLS leaf signature key hex).
        sender_id: String,
        /// "typing" | "recording" | "paused".
        state: String,
        /// The post whose discussion it is in ("" = the chat itself).
        thread: String,
        /// How long ago the sender published it.
        age_ms: u64,
    },
    /// Group transport directory.
    ///
    /// Here it is only PARSED and passed on. The FFI core applies it:
    /// validation needs the group membership and the MLS epoch, and the
    /// MLS session lives there. Dragging membership knowledge into the
    /// sync layer would create a second place deciding who is in the group.
    DirectoryReceived {
        chat_id: String,
        /// The sender's signing key — authorizations are checked against it.
        sender_id: String,
        version: u32,
        revision: u64,
        entries: Vec<alpha_messages::message_type::GroupDirectoryEntry>,
    },
    /// A step of setting up a secret chat; the core acts on it (it needs the
    /// MLS session and the personas). Never a bubble.
    SecretChatSignal {
        /// The ordinary chat it came through.
        chat_id: String,
        sender_id: String,
        invite_id: String,
        step: String,
        data: String,
    },
    /// A new chat (group) was created.
    NewChat {
        group_id: String,
    },
    /// A member was added to a chat.
    MemberAdded {
        chat_id: String,
        member: String,
    },
    /// A member was removed from a chat.
    MemberRemoved {
        chat_id: String,
        member: String,
    },
    /// A peer's profile (display_name / bio / avatar) was updated in
    /// `contacts` table. Receivers should refresh any UI bound to this
    /// peer's identity (chat top bar, bubble headers, chat list row).
    ProfileUpdated {
        /// Sender's stable id (MLS leaf signature key hex).
        sender_id: String,
        /// Path to the cached avatar file, if one was attached.
        avatar_path: String,
        /// MediaRef JSON to background-download for the avatar ("" = nothing to
        /// fetch — either unchanged, cleared, or legacy inline already written).
        avatar_ref: String,
        /// The avatar's new content hash (committed after download succeeds).
        avatar_dl_hash: String,
        /// MediaRef JSON to background-download for the cover ("" = nothing).
        cover_ref: String,
        /// The cover's new content hash (committed after download succeeds).
        cover_dl_hash: String,
        /// MediaRef JSON of a video avatar to fetch ("" = nothing to fetch).
        video_ref: String,
        /// The video avatar's content hash (committed after download succeeds).
        video_dl_hash: String,
    },
    /// Peer asked us to send our current profile (first-encounter pull).
    /// FFI layer should respond by re-broadcasting `broadcast_my_profile`.
    ProfileRequested {
        sender_id: String,
        chat_id: String,
    },
    /// A `GroupMetadataUpdate` was applied to the `chats` row. UI should
    /// refresh chat list / top bar so the new name/avatar/description
    /// shows.
    ChatMetadataUpdated {
        chat_id: String,
    },
    /// The MLS epoch advanced (e.g. after a commit).
    EpochAdvanced {
        chat_id: String,
        new_epoch: u64,
    },
    /// A member asked for the current role state (pull-based role sync). The
    /// FFI layer routes this to the OWNER, who replies once with a snapshot.
    RoleSyncRequested {
        chat_id: String,
    },
    /// Someone wants to join via an invite link. The FFI layer (OWNER/
    /// ADMIN) validates the token + ban list and performs the real Add+Welcome.
    JoinRequested {
        chat_id: String,
        requester_npub: String,
        token: String,
    },
    /// Leave: a member announced they're leaving (audit "leave"). An
    /// OWNER/ADMIN that still sees them in the group performs the real MLS
    /// Remove+Commit so they actually drop out for everyone.
    MemberLeaveRequested {
        chat_id: String,
        leaver_pubkey: String,
    },
    /// A member was banned (sender's authority checked). The core wipes the
    /// chat if the member is us.
    MemberBanned {
        chat_id: String,
        target_pubkey: String,
        reason: String,
    },
    /// The owner deleted the group or channel (sender checked). The core
    /// wipes the chat, as for a ban.
    ChatDisbanded {
        chat_id: String,
    },
    /// The other side of a 1:1 deleted it for both of us. The core wipes it.
    ChatDeletedByPeer {
        chat_id: String,
    },
    /// An MLS Commit could not be applied and the failure
    /// was classified as a genuine epoch fork or an unrecoverable gap (NOT a
    /// stale duplicate). The group can no longer decrypt new messages on this
    /// branch. The FFI layer should: if we are the OWNER, auto-trigger
    /// `recover_group_from_fork(chat_id)` to re-key the group via fresh
    /// Welcomes; otherwise, request recovery from the owner.
    ForkDetected {
        chat_id: String,
        current_epoch: u64,
        incoming_epoch: u64,
        /// Nostr pubkey (hex) of the commit's author — used for diagnostics
        /// and to decide whether this is our own losing commit.
        author: String,
    },
    /// A `DeliveryAck` was received. `sender_id` is
    /// the pubkey whose seqs are being acked (the original content sender).
    /// The FFI compares `sender_id` to its OWN sig key — if they match, the
    /// ACK is for ITS messages and it advances the per-recipient ACK
    /// watermark (`delivery_acks`), then recomputes the delivery outbox. If
    /// they don't match, the ACK is for another member and is ignored.
    /// `acker_id` is the pubkey of the member who sent the ACK (the recipient).
    DeliveryAckReceived {
        chat_id: String,
        /// Whose seqs are acked (original content sender's sig key hex).
        sender_id: String,
        /// Who sent the ACK (recipient's sig key hex).
        acker_id: String,
        upto_seq: u64,
    },
    /// A `ResendRequest` was received. The FFI
    /// compares `sender_id` to its OWN sig key — if they match, IT must
    /// re-publish the messages in [from_seq, to_seq] from its delivery outbox.
    /// `requester_id` is who asked (the recipient with the gap).
    ResendRequested {
        chat_id: String,
        /// Whose messages to resend (original content sender's sig key hex).
        sender_id: String,
        /// Who asked for the resend (recipient's sig key hex).
        requester_id: String,
        from_seq: u64,
        to_seq: u64,
    },
    /// Our other phones wrote to us: their notes wait in `devsync_in:` for the core.
    DeviceSync,
    /// A sequenced message arrived that we've already
    /// received (seq <= highest_received_seq). This is a redelivery (from a
    /// resend or relay echo) — the message was NOT persisted again. The FFI
    /// ignores this event (no UI update). This is what makes redelivery
    /// idempotent: a resent message that we already have is silently dropped.
    DuplicateReceived {
        chat_id: String,
    },
}

/// Processes decrypted MLS messages into `ProcessedEvent`s.
///
/// This is the bridge between the transport/crypto layer and the
/// application layer.  It:
///
/// 1. Parses the plaintext as an [`AlphaMessage`] via [`deserialize`].
/// 2. Detects the message type.
/// 3. Constructs the appropriate database model and persists it.
/// 4. Returns the high-level `ProcessedEvent` for the UI.
pub struct EventProcessor {
    /// Database for persisting messages.
    db: Arc<DatabasePool>,
    /// Where to write incoming peer avatars
    /// (`<app_files>/contact_avatars/{sender_id}.jpg`).
    contact_avatars_dir: std::path::PathBuf,
    /// Where to write incoming group avatars
    /// (`<app_files>/group_avatars/{chat_id}.jpg`).
    group_avatars_dir: std::path::PathBuf,
}

impl EventProcessor {
    /// Create a new event processor.
    ///
    /// `contact_avatars_dir` is where incoming `ProfileUpdate` avatars get
    /// cached. The FFI layer passes `<app_files>/contact_avatars`.
    pub fn new(
        db: Arc<DatabasePool>,
        contact_avatars_dir: std::path::PathBuf,
        group_avatars_dir: std::path::PathBuf,
    ) -> Self {
        Self { db, contact_avatars_dir, group_avatars_dir }
    }

    /// Process an MLS application message (plaintext bytes) into an
    /// app-level event.
    pub fn process_application_message(
        &self,
        chat_id: &str,
        plaintext: &[u8],
        sender_id: &str,
        event_ts: u64,
    ) -> Result<ProcessedEvent> {
        // Deserialize the AlphaMessage from the plaintext.
        let mut alpha_msg = deserialize(plaintext)
            .map_err(|e| SyncError::MessageProcessing(e.to_string()))?;
        // A peer decides what is inside: nothing oversized is stored or looped over.
        alpha_messages::validate_inbound(&alpha_msg)
            .map_err(|e| SyncError::MessageProcessing(format!("refused inbound message: {e}")))?;
        // A file path in a ref is the sender's own: on this phone it would name
        // OUR file. Never stored for a received message.
        if let AlphaMessage::Media { media_ref, .. } = &mut alpha_msg {
            *media_ref = strip_local_paths(media_ref);
        }

        // One of our other phones wrote it: it is ours, not a peer's. Receipts,
        // delivery bookkeeping, profile pulls, typing, secret chats, stories
        // and views concern the peers; between our own phones they mean nothing.
        let own_device = alpha_database::devices::is_own_leaf(&self.db.connection(), chat_id, sender_id);
        if own_device {
            // A call our other phone takes part in: the app here only learns
            // that it was answered or ended there (and never rings for it).
            if let AlphaMessage::ChatState { state, .. } = &mut alpha_msg {
                if let Some(rest) = state.strip_prefix("call:") {
                    *state = format!("call-elsewhere:{rest}");
                }
            }
            let noop = || Ok(ProcessedEvent::DuplicateReceived { chat_id: chat_id.to_string() });
            match &alpha_msg {
                m if m.is_delivery_control() || m.is_story_control() => return noop(),
                // Our profile travels between our phones in their own notes.
                AlphaMessage::DeliveredSignal { .. }
                | AlphaMessage::ProfileRequest { .. }
                | AlphaMessage::ProfileUpdate { .. }
                | AlphaMessage::SecretChat { .. }
                | AlphaMessage::ChannelSeen { .. } => return noop(),
                AlphaMessage::ChatState { state, .. }
                    if !(state.starts_with("call-elsewhere:") || state.starts_with("to:") || state.starts_with("room:")) => return noop(),
                // Read there: read here too.
                AlphaMessage::ReadSignal { target_id, .. } => {
                    let conn = self.db.connection();
                    let _ = MessageRepo::mark_chat_read(&conn, chat_id, Some(target_id));
                    let _ = ChatRepo::set_unread_override(&conn, chat_id, false);
                    return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
                }
                _ => {}
            }
        }
        // Stored as ours; delivery bookkeeping keeps the real leaf (each phone counts its own).
        let author = if own_device { "self" } else { sender_id };

        // --- Delivery-layer control messages ---
        // These bypass the normal persistence pipeline entirely — they mutate
        // delivery-layer state (outbox / watermarks / gaps), never becoming
        // chat bubbles. Surface them as events for the FFI to act on.
        if alpha_msg.is_delivery_control() {
            return self.process_delivery_control(chat_id, &alpha_msg, sender_id);
        }

        // "Delivered" — a control message, not persisted: surfaced for the sender.
        if let AlphaMessage::DeliveredSignal { message_ids, .. } = &alpha_msg {
            return Ok(ProcessedEvent::DeliveredReceived {
                chat_id: chat_id.to_string(),
                sender_id: sender_id.to_string(),
                message_ids: message_ids.clone(),
            });
        }

        // Handle ReadSignal separately — it's a control message, not persisted.
        if alpha_msg.is_read_signal() {
            return self.process_read_signal(chat_id, &alpha_msg);
        }

        // Transient typing/recording signal — not persisted, just surfaced.
        if let AlphaMessage::ChatState { chat_id: cs_chat, state, ts, thread } = &alpha_msg {
            // Drop STALE typing signals: a "typing" that was published earlier
            // (e.g. before the sender went offline) gets re-delivered by
            // catch-up alongside the deferred message — we must NOT show
            // "typing…" for it. Anything older than 10s is reported as paused.
            let now_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            let fresh = *ts > 0 && now_ms.saturating_sub(*ts) <= 10_000;
            let age_ms = if *ts > 0 { now_ms.saturating_sub(*ts) } else { 0 };
            // A call signal keeps what it says however late it comes: a late
            // offer is a missed call, and the app decides that from its age.
            let call = state.starts_with("call:");
            tracing::info!(
                target: "DELIVERY_TRACE",
                "CHAT_STATE_RECV chat_id={} sender_id={} state={} fresh={} age_ms={}",
                chat_id, sender_id, state, fresh, age_ms
            );
            return Ok(ProcessedEvent::ChatStateReceived {
                chat_id: if cs_chat.is_empty() { chat_id.to_string() } else { cs_chat.clone() },
                sender_id: sender_id.to_string(),
                state: if fresh || call { state.clone() } else { "paused".to_string() },
                thread: thread.clone().unwrap_or_default(),
                age_ms,
            });
        }

        // Profile-control messages. These mutate ContactRepo
        // but don't become chat rows.
        if alpha_msg.is_profile_control() {
            return self.process_profile_message(chat_id, &alpha_msg, sender_id);
        }

        // Transport directory. Only parse it and pass it up: the one who
        // must validate it knows the group membership and the MLS epoch —
        // the FFI core. It never becomes a bubble, whatever the outcome.
        if let AlphaMessage::SecretChat { invite_id, step, data, .. } = &alpha_msg {
            return Ok(ProcessedEvent::SecretChatSignal {
                chat_id: chat_id.to_string(),
                sender_id: sender_id.to_string(),
                invite_id: invite_id.clone(),
                step: step.clone(),
                data: data.clone(),
            });
        }

        if let AlphaMessage::GroupDirectory { version, revision, entries, .. } = &alpha_msg {
            return Ok(ProcessedEvent::DirectoryReceived {
                chat_id: chat_id.to_string(),
                sender_id: sender_id.to_string(),
                version: *version,
                revision: *revision,
                entries: entries.clone(),
            });
        }

        // Group role-control messages. Mutate the role KV after
        // validating the sender's authority; never become chat rows.
        if alpha_msg.is_role_control() {
            return self.process_role_message(chat_id, &alpha_msg, sender_id);
        }

        // Group mute-control messages. Mutate the mute KV after validating
        // the sender's authority; never become chat rows.
        if alpha_msg.is_mute_control() {
            return self.process_mute_message(chat_id, &alpha_msg, sender_id);
        }

        // A ban: checked like a mute, applied by the core.
        if alpha_msg.is_ban_control() {
            return self.process_ban_message(chat_id, &alpha_msg, sender_id);
        }

        // Group permissions-control messages. Owner-set policy; never rows.
        if alpha_msg.is_permissions_control() {
            return self.process_permissions_message(chat_id, &alpha_msg, sender_id);
        }

        // Group audit-log entries. Append to the local log; never rows.
        if alpha_msg.is_audit_entry() {
            if let AlphaMessage::GroupAuditEntry { id, actor_pubkey, actor_name, action, target_pubkey, target_name, ts } = &alpha_msg {
                let conn = self.db.connection();
                // The entry speaks for its sender only: a member may not write
                // "X left" or "X banned Y" in someone else's name (a forged
                // "left" would make the owner's app remove that person).
                let sender_pk = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
                if sender_pk.is_empty() || sender_pk != *actor_pubkey {
                    tracing::warn!("GroupAuditEntry dropped in {}: actor is not the sender", chat_id);
                    return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
                }
                alpha_database::group_roles::append_audit(
                    &conn, chat_id, id, actor_pubkey, actor_name, action, target_pubkey, target_name, ts,
                );
 // A "leave" audit means the actor is dropping out. other messengers the FFI
                // layer so an OWNER/ADMIN can perform the real MLS removal (their
                // own self-leave is only a proposal nobody commits otherwise).
                if action == "leave" && !actor_pubkey.is_empty() {
                    return Ok(ProcessedEvent::MemberLeaveRequested {
                        chat_id: chat_id.to_string(),
                        leaver_pubkey: actor_pubkey.clone(),
                    });
                }
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // Group disbanded: the OWNER deleted the whole group. Mark our local
        // group inactive so the composer locks ("Group unavailable") and drop a
        // one-time system notice bubble. Never a normal chat message itself.
        if alpha_msg.is_disband_control() {
            return self.process_disband(chat_id, sender_id);
        }
        // Our 1:1 deleted for both by the other side. Only in a direct chat,
        // only from the other person (never from a group member).
        if alpha_msg.is_chat_deleted_control() {
            let conn = self.db.connection();
            let direct = ChatRepo::get(&conn, chat_id).ok().flatten()
                .is_some_and(|c| c.chat_type.as_str() != "group");
            if direct && sender_id != "self" {
                tracing::info!(target: "ACCOUNT", "chat {} deleted for both by the other side", chat_id);
                return Ok(ProcessedEvent::ChatDeletedByPeer { chat_id: chat_id.to_string() });
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // Stories: kept in the stories store, never chat rows. Only meaningful
        // between two people (a direct chat): the sender is the author.
        if alpha_msg.is_story_control() {
            let conn = self.db.connection();
            let now = chrono::Utc::now().timestamp();
            let author_npub = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
            match &alpha_msg {
                AlphaMessage::StoryPost { story_id, kind, media_ref, text, bg, caption, created_at, expires_at, allow_replies, allow_screenshots, stickers, .. } => {
                    if *expires_at > now {
                        let seen = alpha_database::stories::get_story(&conn, story_id)
                            .and_then(|v| v.get("seen").and_then(|x| x.as_bool())).unwrap_or(false);
                        alpha_database::stories::put_story(&conn, story_id, &serde_json::json!({
                            "id": story_id, "chat_id": chat_id, "author_sid": sender_id, "author_npub": author_npub,
                            "kind": kind, "media_ref": media_ref, "text": text, "bg": bg, "caption": caption,
                            "created_at": created_at, "expires_at": expires_at,
                            "allow_replies": allow_replies, "allow_screenshots": allow_screenshots, "seen": seen,
                            "stickers": stickers,
                        }));
                    }
                }
                AlphaMessage::StoryDelete { story_id, .. } => {
                    // Only its author (the same chat and sender) may remove it.
                    if let Some(v) = alpha_database::stories::get_story(&conn, story_id) {
                        if v.get("chat_id").and_then(|x| x.as_str()) == Some(chat_id)
                            && v.get("author_sid").and_then(|x| x.as_str()) == Some(sender_id) {
                            alpha_database::stories::delete_story(&conn, story_id);
                        }
                    }
                }
                AlphaMessage::StoryView { story_id, reaction, screenshot, .. } => {
                    // A viewer of OUR story: only from a chat it was sent to.
                    if let Some(mut v) = alpha_database::stories::get_mine(&conn, story_id) {
                        let sent_here = v.get("chats").and_then(|x| x.as_array())
                            .map(|a| a.iter().any(|c| c.as_str() == Some(chat_id))).unwrap_or(false);
                        if sent_here {
                            let mut viewers: Vec<serde_json::Value> = v.get("viewers").and_then(|x| x.as_array()).cloned().unwrap_or_default();
                            let prev = viewers.iter().find(|w| w.get("sid").and_then(|x| x.as_str()) == Some(sender_id)).cloned();
                            let prev_reaction = prev.as_ref().and_then(|w| w.get("reaction").and_then(|x| x.as_str()).map(str::to_string)).unwrap_or_default();
                            let shot = *screenshot || prev.as_ref().and_then(|w| w.get("shot").and_then(|x| x.as_bool())).unwrap_or(false);
                            viewers.retain(|w| w.get("sid").and_then(|x| x.as_str()) != Some(sender_id));
                            // "" = just seen (keep any earlier reaction), "-" = reaction taken back.
                            let reaction = match reaction.as_str() { "" => prev_reaction, "-" => String::new(), r => r.to_string() };
                            viewers.insert(0, serde_json::json!({
                                "sid": sender_id, "npub": author_npub, "chat_id": chat_id, "at": now, "reaction": reaction,
                                "shot": shot,
                            }));
                            v["viewers"] = serde_json::Value::Array(viewers);
                            alpha_database::stories::put_mine(&conn, story_id, &v);
                        }
                    }
                }
                _ => {}
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // A vote in a poll of this chat: the voter's current choice (the rules —
        // one per voter, single/multiple, quiz final, closed — in alpha_database::polls).
        if let AlphaMessage::PollVote { poll_id, options, .. } = &alpha_msg {
            let conn = self.db.connection();
            if let Some(poll) = MessageRepo::get(&conn, poll_id).ok().flatten().filter(|m| m.chat_id == chat_id) {
                if let Some(spec) = poll.media_ref.as_deref().and_then(alpha_database::polls::PollSpec::from_ref) {
                    let _ = alpha_database::polls::vote(&conn, chat_id, poll_id, &spec, author, options, event_ts as i64);
                }
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        // Closing a poll: by its author, or a group's admins.
        if let AlphaMessage::PollClose { poll_id, tally, .. } = &alpha_msg {
            let conn = self.db.connection();
            if let Some(poll) = MessageRepo::get(&conn, poll_id).ok().flatten().filter(|m| m.chat_id == chat_id) {
                let admin = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id)
                    .map(|pk| alpha_messages::can_manage_group(&alpha_database::group_roles::role_of(&conn, chat_id, &pk)))
                    .unwrap_or(false);
                if poll.sender_id == sender_id || admin {
                    let _ = alpha_database::polls::close(&conn, chat_id, poll_id, event_ts as i64, tally);
                }
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // The recipient burned a whisper we sent: how long it was read.
        if let AlphaMessage::WhisperBurned { target_id, used_secs, shot, expired } = &alpha_msg {
            let conn = self.db.connection();
            let _ = alpha_database::whispers::burned_by_peer(
                &conn, chat_id, target_id, *used_secs, *shot, *expired, &chrono::Utc::now().to_rfc3339(),
            );
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // A channel subscriber saw some posts: count the views.
        if let AlphaMessage::ChannelSeen { post_ids, forwarded, .. } = &alpha_msg {
            let conn = self.db.connection();
            if alpha_database::group_roles::is_channel(&conn, chat_id) {
                alpha_database::group_roles::add_views(&conn, chat_id, post_ids, sender_id);
                alpha_database::group_roles::add_forwards(&conn, chat_id, forwarded, sender_id);
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // Earlier posts of a channel we just joined. Only its owner or admins
        // may send them; each post then goes the ordinary way (rights, dedup,
        // its original time) — those who already have it drop it as a duplicate.
        if let AlphaMessage::ChannelHistory { items, .. } = &alpha_msg {
            let allowed = {
                let conn = self.db.connection();
                // It may come before we know this is a channel and who runs it
                // (all of it arrives at once): then it waits for the settings.
                if !alpha_database::group_roles::history_on(&conn, chat_id)
                    || alpha_database::group_roles::owner_of(&conn, chat_id).is_none()
                {
                    let held = serde_json::json!({ "sid": sender_id, "msg": hex::encode(plaintext) }).to_string();
                    // Keyed by content: the same batch arriving twice is held once.
                    let digest = {
                        use std::hash::{Hash, Hasher};
                        let mut h = std::collections::hash_map::DefaultHasher::new();
                        plaintext.hash(&mut h);
                        h.finish()
                    };
                    let key = format!("pending_hist:{}:{:016x}", chat_id, digest);
                    // A real channel sends one or two batches to a newcomer; a
                    // member must not be able to grow this store without end.
                    let held_already = alpha_database::KeyValueRepo::list_prefix(&conn, &format!("pending_hist:{}:", chat_id))
                        .map(|v| v.len()).unwrap_or(0);
                    if held_already >= 32 {
                        tracing::warn!("ChannelHistory dropped in {}: too many held batches", chat_id);
                        return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
                    }
                    let _ = alpha_database::KeyValueRepo::set(&conn, &key, &held);
                    tracing::warn!("ChannelHistory held in {}: channel not known yet", chat_id);
                    return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
                }
                let pk = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
                alpha_messages::can_manage_group(&alpha_database::group_roles::role_of(&conn, chat_id, &pk))
            };
            if !allowed {
                tracing::warn!("ChannelHistory dropped in {}: sender is not a channel manager", chat_id);
                return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
            }
            let is_channel_chat = alpha_database::group_roles::is_channel(&self.db.connection(), chat_id);
            let mut added = 0usize;
            for item in items.iter().take(100) {
                let Ok(bytes) = hex::decode(&item.msg) else { continue };
                let Ok(msg) = deserialize(&bytes) else { continue };
                if alpha_messages::validate_inbound(&msg).is_err() { continue; }
                // Only a plain post or comment: no expiry, no delivery seq, no control.
                let plain = matches!(msg,
                    AlphaMessage::Text { expiry: None, seq: 0, .. }
                    | AlphaMessage::Media { expiry: None, seq: 0, .. }
                    | AlphaMessage::Reply { seq: 0, .. });
                if !plain { continue; }
                match item.from.as_deref().filter(|f| *f != sender_id) {
                    // A subscriber's comment, vouched for by the channel's manager:
                    // stored under its real author, filed under its post.
                    Some(author) if msg.thread().is_some() || !is_channel_chat => {
                        // A comment, or a group member's message: stored under its real author.
                        if self.store_history_comment(chat_id, &msg, author, item.at).is_ok() { added += 1; }
                    }
                    Some(_) => continue,
                    None => {
                        if self.process_application_message(chat_id, &bytes, sender_id, item.at).is_ok() {
                            added += 1;
                        }
                    }
                }
            }
            tracing::warn!("ChannelHistory in {}: {} of {} post(s) taken", chat_id, added, items.len());
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // Who may write here is checked by every receiver, not only by the
        // sender's composer: a modified client posting into an admins-only
        // group or channel, or a muted member, is dropped.
        if !self.write_allowed(chat_id, &alpha_msg, sender_id, event_ts as i64) {
            tracing::warn!("message dropped in {}: sender not allowed to write", chat_id);
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // Pin / unpin: the shared pinned list, by the sender's right to pin.
        if let AlphaMessage::PinMessage { target_id, unpin, .. } = &alpha_msg {
            let conn = self.db.connection();
            let sender_pubkey = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
            if alpha_database::group_roles::may_pin(&conn, chat_id, &sender_pubkey) {
                alpha_database::group_roles::set_pinned(&conn, chat_id, target_id, !*unpin);
            } else {
                tracing::warn!("PinMessage ignored: sender may not pin in {}", chat_id);
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // Upsert the contact row so the FK on messages.sender_id holds.
        // Without this, every incoming message blows up the entire chat with
        // "FOREIGN KEY constraint failed" the moment we try to persist it.
        {
            let conn = self.db.connection();
            if ContactRepo::get(&conn, sender_id)
                .map_err(|e| SyncError::MessageProcessing(format!("contact lookup: {}", e)))?
                .is_none()
            {
                let short = if sender_id.len() > 8 { &sender_id[sender_id.len() - 8..] } else { sender_id };
                let display = format!("peer…{}", short);
                let contact = new_contact(
                    sender_id.to_string(),
                    Some(display),
                    Vec::new(),
                    None,
                );
                if let Err(e) = ContactRepo::insert(&conn, &contact) {
                    // Race with a parallel insert is fine — the row exists either way.
                    tracing::debug!("ContactRepo::insert race for {}: {}", sender_id, e);
                }
            }
        }

        // --- Delivery-layer seq dedup ---
        // Why this is correct: `highest_received` advances ONLY contiguously
        // (seq == highest+1), and an out-of-order-AHEAD message (seq >
        // highest+1) is PERSISTED and tracked in `pending`, never dropped.
        // So a message is dropped ONLY when its seq is one we already hold
        // (true redelivery). The delivery layer re-sends pending items
        // (flush_pending_media), and relays echo our own kind-443 back —
        // those redeliveries carry the SAME seq and MUST be dropped,
        // otherwise the recipient (and the sender's own echo) shows the media twice.
        if let Some(seq) = alpha_msg.seq() {
            let conn = self.db.connection();
            let (highest_received, _highest_acked) =
                alpha_database::delivery::DeliveryRepo::get_watermarks(&conn, chat_id, sender_id)?;
            if seq <= highest_received {
                // Already hold this contiguous seq → redelivery (resend / echo).
                tracing::info!(
                    target: "DELIVERY",
                    "DUP_DROP chat={} sender={} seq={} highest_received={} — redelivery dropped",
                    chat_id, sender_id, seq, highest_received,
                );
                return Ok(ProcessedEvent::DuplicateReceived { chat_id: chat_id.to_string() });
            }
            if seq == highest_received + 1 {
                // In-order: advance the contiguous watermark + drain pending.
                let mut new_high = seq;
                let pending = alpha_database::delivery::DeliveryRepo::pending_drain_from(
                    &conn, chat_id, sender_id, seq + 1,
                )?;
                for p in pending {
                    if p == new_high + 1 { new_high = p; }
                    else {
                        let _ = alpha_database::delivery::DeliveryRepo::pending_insert(
                            &conn, chat_id, sender_id, p,
                        );
                    }
                }
                let _ = alpha_database::delivery::DeliveryRepo::advance_received(
                    &conn, chat_id, sender_id, new_high,
                );
                let _ = alpha_database::delivery::DeliveryRepo::gap_close_covered(
                    &conn, chat_id, sender_id, new_high,
                );
            } else {
                // Out-of-order AHEAD (seq > highest+1): persist it (it's real,
                // just early) + track in pending so a later redelivery of this
                // same seq is recognised as a duplicate. Do NOT advance the
                // contiguous watermark. (Gap rows are harmless now — the resend
                // timer is disabled — they just record the hole.)
                let _ = alpha_database::delivery::DeliveryRepo::gap_record(
                    &conn, chat_id, sender_id, highest_received + 1, seq - 1,
                );
                let _ = alpha_database::delivery::DeliveryRepo::pending_insert(
                    &conn, chat_id, sender_id, seq,
                );
            }
        }

        // Persist the message to the database (with the sender's ORIGINAL send
        // time so backlog delivered newest-first by relays still shows in the
        // correct order).
        let t_db_start_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis()).unwrap_or(0);
        tracing::info!(
            target: "DELIVERY_TRACE",
            "DB_WRITE_START chat_id={} sender_id={} t_ms={}",
            chat_id, sender_id, t_db_start_ms
        );
        self.persist_alpha_message(chat_id, &alpha_msg, author, event_ts)?;
        if alpha_msg.silent() {
            let _ = MessageRepo::mark_silent(&self.db.connection(), alpha_msg.id());
        }
        file_comment(&self.db.connection(), chat_id, &alpha_msg);
        let t_db_done_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis()).unwrap_or(0);
        tracing::info!(
            target: "DELIVERY_TRACE",
            "DB_WRITE_DONE chat_id={} dt_ms={}",
            chat_id, t_db_done_ms.saturating_sub(t_db_start_ms)
        );

        Ok(ProcessedEvent::NewMessage {
            chat_id: chat_id.to_string(),
            message: alpha_msg,
            own_device,
        })
    }

    /// A comment from a channel's history, under its real author. The history's
    /// sender (the channel's owner or admin) vouches for it; the comment rules
    /// of today don't apply to what was written back then.
    fn store_history_comment(&self, chat_id: &str, msg: &AlphaMessage, author: &str, at: u64) -> Result<()> {
        {
            let conn = self.db.connection();
            if MessageRepo::get(&conn, msg.id()).ok().flatten().is_some() {
                return Ok(());
            }
            if ContactRepo::get(&conn, author).ok().flatten().is_none() {
                let short = if author.len() > 8 { &author[author.len() - 8..] } else { author };
                let _ = ContactRepo::insert(&conn, &new_contact(author.to_string(), Some(format!("peer…{}", short)), Vec::new(), None));
            }
        }
        self.persist_alpha_message(chat_id, msg, author, at)?;
        file_comment(&self.db.connection(), chat_id, msg);
        Ok(())
    }

    /// Process a ReadSignal control message.
    ///
    /// When we receive a ReadSignal, it means the recipient has opened
    /// an AfterRead message we sent. We need to:
    /// 1. Record `read_at` on our copy of the message.
    /// 2. Compute `expires_at = read_at + ttl`.
    /// 3. Schedule local deletion.
    fn process_read_signal(
        &self,
        _chat_id: &str,
        alpha_msg: &AlphaMessage,
    ) -> Result<ProcessedEvent> {
        match alpha_msg {
            AlphaMessage::ReadSignal { target_id, chat_id, read_at } => {
                let conn = self.db.connection();

                // Our own clock, from the moment the receipt came: a disappearing
                // message must not run to the reader's clock (phones differ).
                let seen_at = chrono::Utc::now().to_rfc3339();
                // Mark the message as read — this also computes expires_at.
                let found = MessageRepo::mark_read(&conn, target_id, &seen_at)?;
                // Senders now batch: one receipt for the newest message read.
                let marked = MessageRepo::mark_outgoing_read_up_to(&conn, chat_id, target_id, &seen_at)
                    .map(|v| v.len()).unwrap_or(0);
                tracing::warn!(
                    "RECEIPT_RECV chat={} target_found={} marked={}",
                    &chat_id[..chat_id.len().min(8)], found, marked,
                );

                tracing::info!(
                    "ReadSignal received for message {} in chat {}: read_at={}",
                    target_id,
                    chat_id,
                    read_at
                );

                Ok(ProcessedEvent::ReadSignalReceived {
                    chat_id: chat_id.clone(),
                    target_id: target_id.clone(),
                    read_at: read_at.clone(),
                })
            }
            _ => Err(SyncError::MessageProcessing(
                "process_read_signal called on non-ReadSignal message".to_string(),
            )),
        }
    }

    /// Handle a `DeliveryAck` or `ResendRequest`
    /// control message. These don't touch the messages table — they surface
    /// as `ProcessedEvent::DeliveryAckReceived` / `ResendRequested` so the FFI
    /// (which owns the identity + MLS session + transport) can act:
    ///
    /// - `DeliveryAck { sender_id, upto_seq }`: if `sender_id` == our own sig
    ///   key, the ACK is for OUR messages → the FFI records it in
    ///   `delivery_acks` and recomputes the outbox. The `acker_id` (who sent
    ///   the ACK) is `sender_id` of the MLS message (the recipient).
    /// - `ResendRequest { sender_id, from_seq, to_seq }`: if `sender_id` ==
    ///   our own sig key, WE must re-publish those seqs from our outbox. The
    ///   `requester_id` is the MLS sender of the request.
    ///
    /// Messages where `sender_id` != our sig key are for another member and
    /// are ignored (the FFI decides by comparing to its own key).
    fn process_delivery_control(
        &self,
        chat_id: &str,
        alpha_msg: &AlphaMessage,
        // MLS-layer sender of this control message (the recipient/requester).
        mls_sender_id: &str,
    ) -> Result<ProcessedEvent> {
        match alpha_msg {
            AlphaMessage::DeliveryAck { chat_id: ack_chat, sender_id, upto_seq } => {
                let cid = if ack_chat.is_empty() { chat_id } else { ack_chat };
                tracing::info!(
                    target: "DELIVERY",
                    "ACK_RECV chat={} sender_id={} acker={} upto_seq={}",
                    cid, sender_id, mls_sender_id, upto_seq,
                );
                Ok(ProcessedEvent::DeliveryAckReceived {
                    chat_id: cid.to_string(),
                    sender_id: sender_id.clone(),
                    acker_id: mls_sender_id.to_string(),
                    upto_seq: *upto_seq,
                })
            }
            AlphaMessage::ResendRequest { chat_id: rr_chat, sender_id, from_seq, to_seq } => {
                let cid = if rr_chat.is_empty() { chat_id } else { rr_chat };
                tracing::info!(
                    target: "DELIVERY",
                    "RESEND_REQ chat={} sender_id={} requester={} range=[{}..{}]",
                    cid, sender_id, mls_sender_id, from_seq, to_seq,
                );
                Ok(ProcessedEvent::ResendRequested {
                    chat_id: cid.to_string(),
                    sender_id: sender_id.clone(),
                    requester_id: mls_sender_id.to_string(),
                    from_seq: *from_seq,
                    to_seq: *to_seq,
                })
            }
            _ => Err(SyncError::MessageProcessing(
                "process_delivery_control called on non-delivery message".to_string(),
            )),
        }
    }

    /// Apply an incoming `ProfileUpdate` or `ProfileRequest` to the local
    /// contacts table. Drops updates whose `version` is not strictly
    /// greater than the stored `profile_version` (replay protection).
    ///
    /// Avatars are written to `app_files/contact_avatars/{sender_id}.jpg`
    /// (the directory is created on the FFI side via `get_avatars_dir`).
    /// Bytes are skipped if the hash matches the cached `avatar_hash` —
    /// no point re-writing the same file twice.
    fn process_profile_message(
        &self,
        chat_id: &str,
        alpha_msg: &AlphaMessage,
        sender_id: &str,
    ) -> Result<ProcessedEvent> {
        use base64::Engine;

        match alpha_msg {
            AlphaMessage::ProfileUpdate {
                version,
                display_name,
                bio,
                avatar_jpeg_base64,
                avatar_hash,
                cover_jpeg_base64,
                cover_hash,
                avatar_ref,
                cover_ref,
                avatar_version: _,
                cover_version: _,
                presence_secret,
                wake_topic,
                ts: _,
                scope,
                status,
                status_color,
                can_call,
                can_video,
                video_ref,
                video_version: _,
                relays,
            } => {
                // Where this contact listens: we write there too.
                alpha_database::peer_relays::set(&self.db.connection(), sender_id, relays);
                // One broadcast reaches us as a "contact" copy (our direct chat)
                // and/or "member" copies (shared groups), shaped by the sender's
                // privacy settings. Ordering key: the fuller copy of the same
                // broadcast wins, any newer broadcast wins over an older one.
                let rank: i64 = if scope.as_deref() == Some("member") { 0 } else { 1 };
                let effective_version = (*version as i64).saturating_mul(2).saturating_add(rank);
                let valid_secret = presence_secret.len() == 64 && presence_secret.chars().all(|c| c.is_ascii_hexdigit());
                let valid_topic = wake_topic.len() == 34 && wake_topic.starts_with("am")
                    && wake_topic[2..].chars().all(|c| c.is_ascii_hexdigit());
                tracing::info!(
                    target: "ACCOUNT",
                    "ProfileUpdate received: v{} scope={:?} presence={} avatar_hash={} via={}",
                    version, scope, presence_secret.len() == 64,
                    if avatar_hash.is_empty() { "none" } else { &avatar_hash[..avatar_hash.len().min(16)] },
                    if !avatar_ref.is_empty() { "blob" } else if !avatar_jpeg_base64.is_empty() { "inline" } else { "none" },
                );

                let (cached_hash, cached_path): (String, String) = {
                    let conn = self.db.connection();
                    ContactRepo::get(&conn, sender_id).ok().flatten()
                        .map(|c| (c.avatar_hash, c.avatar_path)).unwrap_or_default()
                };

                // Decide how to apply the avatar. Blob path = keep OLD file +
                // signal a background download (committed on success); inline =
                // legacy fallback (decode now); none = clear.
                let mut avatar_dl_ref = String::new();
                let mut avatar_dl_hash = String::new();
                let (write_avatar_path, write_avatar_hash): (String, String) =
                    if !avatar_ref.is_empty() && !avatar_hash.is_empty() {
                        if cached_hash != *avatar_hash {
                            avatar_dl_ref = avatar_ref.clone();
                            avatar_dl_hash = avatar_hash.clone();
                        }
                        (cached_path.clone(), cached_hash.clone()) // keep old until downloaded
                    } else if !avatar_jpeg_base64.is_empty() && !avatar_hash.is_empty() {
                        if cached_hash == *avatar_hash {
                            (cached_path.clone(), cached_hash.clone())
                        } else {
                            match base64::engine::general_purpose::STANDARD.decode(avatar_jpeg_base64) {
                                Ok(bytes) => match self.write_contact_avatar(sender_id, &bytes) {
                                    Ok(p) => (p, avatar_hash.clone()),
                                    Err(e) => { tracing::warn!("write avatar {}: {}", sender_id, e); (cached_path.clone(), cached_hash.clone()) }
                                },
                                Err(e) => { tracing::warn!("avatar b64 decode: {}", e); (cached_path.clone(), cached_hash.clone()) }
                            }
                        }
                    } else {
                        (String::new(), String::new())
                    };

                let pubkey_bytes: Vec<u8> = hex::decode(sender_id).unwrap_or_default();
                let applied = {
                    let conn = self.db.connection();
                    ContactRepo::upsert_profile(
                        &conn, sender_id, &pubkey_bytes, display_name, bio,
                        &write_avatar_path, &write_avatar_hash, effective_version,
                    ).map_err(|e| SyncError::MessageProcessing(format!("upsert profile: {}", e)))?
                };
                if !applied {
                    tracing::info!(target: "ACCOUNT", "ProfileUpdate not applied: v{} ({:?}) not newer", version, scope);
                }
                // Contact secrets for private presence / wake-ups — from the copy
                // that applied only (a thinner "member" copy must not wipe what a
                // contact copy gave us). Validated so a malicious member can't
                // inject anything into URLs or filters.
                if applied {
                    let conn = self.db.connection();
                    // The secrets come only in a contact copy; a member copy (sent
                    // into another chat with us) carries none and must not erase
                    // them. Whoever drops us as a contact rotates theirs anyway.
                    if rank == 1 {
                        let json = serde_json::json!({
                            "p": if valid_secret { presence_secret.to_lowercase() } else { String::new() },
                            "w": if valid_topic { wake_topic.to_lowercase() } else { String::new() },
                        }).to_string();
                        let _ = alpha_database::KeyValueRepo::set(&conn, &format!("contact_secrets:{}", sender_id), &json);
                    }
                    // Status text (what the sender lets us see; nothing = none)
                    // and whether we may call them — a profile fact like the name.
                    let text: String = status.clone().unwrap_or_default().chars().take(140).collect();
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("peer_status:{}", sender_id),
                        &serde_json::json!({"t": text, "c": status_color.unwrap_or(0)}).to_string());
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("peer_calls:{}", sender_id),
                        &serde_json::json!({"v": can_call.unwrap_or(true), "x": can_video.unwrap_or(true)}).to_string());
                }

                // Cover (KV-backed). Same model: blob → signal download (keep
                // old); inline → decode now; none → clear. Only when applied.
                let mut cover_dl_ref = String::new();
                let mut cover_dl_hash = String::new();
                if applied {
                    let cover_key_path = format!("peer_cover_path:{}", sender_id);
                    let cover_key_hash = format!("peer_cover_hash:{}", sender_id);
                    let cached_cover_hash: String = {
                        let conn = self.db.connection();
                        alpha_database::KeyValueRepo::get(&conn, &cover_key_hash).ok().flatten().unwrap_or_default()
                    };
                    if !cover_ref.is_empty() && !cover_hash.is_empty() {
                        if cached_cover_hash != *cover_hash {
                            cover_dl_ref = cover_ref.clone();
                            cover_dl_hash = cover_hash.clone();
                        }
                    } else if !cover_jpeg_base64.is_empty() && !cover_hash.is_empty() {
                        if cached_cover_hash != *cover_hash {
                            if let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(cover_jpeg_base64) {
                                if let Ok(path) = self.write_contact_cover(sender_id, &bytes) {
                                    let conn = self.db.connection();
                                    let _ = alpha_database::KeyValueRepo::set(&conn, &cover_key_path, &path);
                                    let _ = alpha_database::KeyValueRepo::set(&conn, &cover_key_hash, cover_hash);
                                }
                            }
                        }
                    } else if cover_ref.is_empty() && cover_jpeg_base64.is_empty() && cover_hash.is_empty() {
                        let conn = self.db.connection();
                        let _ = alpha_database::KeyValueRepo::set(&conn, &cover_key_path, "");
                        let _ = alpha_database::KeyValueRepo::set(&conn, &cover_key_hash, "");
                    }
                }

                // Video avatar: fetch a changed one in the background; none → clear.
                let mut video_dl_ref = String::new();
                let mut video_dl_hash = String::new();
                if applied {
                    let key_path = format!("peer_video_path:{}", sender_id);
                    let key_hash = format!("peer_video_hash:{}", sender_id);
                    let conn = self.db.connection();
                    let cached = alpha_database::KeyValueRepo::get(&conn, &key_hash).ok().flatten().unwrap_or_default();
                    if video_ref.is_empty() {
                        if !cached.is_empty() {
                            let _ = alpha_database::KeyValueRepo::set(&conn, &key_path, "");
                            let _ = alpha_database::KeyValueRepo::set(&conn, &key_hash, "");
                        }
                    } else {
                        let hash = serde_json::from_str::<serde_json::Value>(video_ref).ok()
                            .and_then(|v| v.get("hash").and_then(|h| h.as_str()).map(str::to_string))
                            .unwrap_or_default();
                        if !hash.is_empty() && hash != cached {
                            video_dl_ref = video_ref.clone();
                            video_dl_hash = hash;
                        }
                    }
                }

                // Downloads only when this update actually applied.
                if !applied {
                    avatar_dl_ref.clear(); avatar_dl_hash.clear();
                    cover_dl_ref.clear(); cover_dl_hash.clear();
                }

                Ok(ProcessedEvent::ProfileUpdated {
                    sender_id: sender_id.to_string(),
                    avatar_path: write_avatar_path,
                    avatar_ref: avatar_dl_ref,
                    avatar_dl_hash,
                    cover_ref: cover_dl_ref,
                    cover_dl_hash,
                    video_ref: video_dl_ref,
                    video_dl_hash,
                })
            }
            AlphaMessage::ProfileRequest { .. } => {
                tracing::info!("ProfileRequest received from {}, will respond", sender_id);
                Ok(ProcessedEvent::ProfileRequested {
                    sender_id: sender_id.to_string(),
                    chat_id: chat_id.to_string(),
                })
            }
            AlphaMessage::GroupMetadataUpdate {
                version,
                name,
                description,
                avatar_jpeg_base64,
                avatar_hash,
                look,
                notices,
                ts: _,
            } => {
                tracing::info!(
                    "GroupMetadataUpdate received: chat={} v{} name='{}' avatar_hash={}",
                    chat_id, version, name,
                    if avatar_hash.is_empty() { "none" } else { &avatar_hash[..avatar_hash.len().min(16)] },
                );

                // Only the owner or an admin changes a group's name, photo and
                // description. Where no owner is on record the one who brought
                // us in stands for it — a plain member never does.
                {
                    let conn = self.db.connection();
                    let is_group = alpha_database::ChatRepo::get(&conn, chat_id).ok().flatten()
                        .map(|c| matches!(c.chat_type, alpha_database::models::ChatType::Group))
                        .unwrap_or(false);
                    let sender_pk = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
                    let allowed = if alpha_database::group_roles::owner_of(&conn, chat_id).is_some() {
                        alpha_messages::can_manage_group(&alpha_database::group_roles::role_of(&conn, chat_id, &sender_pk))
                    } else if is_group {
                        match alpha_database::group_roles::effective_owner(&conn, chat_id) {
                            Some(owner) => !sender_pk.is_empty() && sender_pk == owner,
                            None => true,
                        }
                    } else {
                        true
                    };
                    if !allowed {
                        tracing::warn!("GroupMetadataUpdate ignored: sender lacks manage rights");
                        return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
                    }
                }

                // Write avatar to disk if a new hash arrived.
                let avatar_path = if !avatar_jpeg_base64.is_empty() && !avatar_hash.is_empty() {
                    let cached_hash: String = {
                        let conn = self.db.connection();
                        alpha_database::ChatRepo::get(&conn, chat_id)
                            .ok().flatten()
                            .map(|c| c.avatar_hash)
                            .unwrap_or_default()
                    };
                    if cached_hash == *avatar_hash {
                        let conn = self.db.connection();
                        alpha_database::ChatRepo::get(&conn, chat_id)
                            .ok().flatten()
                            .map(|c| c.avatar_path)
                            .unwrap_or_default()
                    } else {
                        match base64::engine::general_purpose::STANDARD.decode(avatar_jpeg_base64) {
                            Ok(bytes) => match store_group_avatar(&self.group_avatars_dir, chat_id, avatar_hash, &bytes) {
                                Ok(path) => path,
                                Err(e) => {
                                    tracing::warn!("Failed to write group avatar for {}: {}", chat_id, e);
                                    String::new()
                                }
                            },
                            Err(e) => {
                                tracing::warn!("Group avatar base64 decode failed: {}", e);
                                String::new()
                            }
                        }
                    }
                } else {
                    String::new()
                };

                let name_opt = if name.is_empty() { None } else { Some(name.as_str()) };
                let applied = {
                    let conn = self.db.connection();
                    alpha_database::ChatRepo::apply_metadata(
                        &conn,
                        chat_id,
                        name_opt,
                        Some(description),
                        Some(&avatar_path),
                        Some(avatar_hash),
                        *version as i64,
                    ).map_err(|e| SyncError::MessageProcessing(format!("apply chat metadata: {}", e)))?
                };
                if !applied {
                    tracing::debug!("GroupMetadataUpdate dropped: v{} not newer than cached", version);
                } else if !look.is_empty() {
                    // The admins' look for this chat ("{}" = none); same version
                    // and rights as the rest of the metadata.
                    let conn = self.db.connection();
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("chat_look:{}", chat_id), look);
                }
                if applied && (notices == "on" || notices == "off") {
                    let conn = self.db.connection();
                    let _ = alpha_database::KeyValueRepo::set(&conn, &format!("group_notices:{}", chat_id), notices);
                }
                Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() })
            }
            _ => Err(SyncError::MessageProcessing(
                "process_profile_message called on non-profile message".to_string(),
            )),
        }
    }

    /// Apply an incoming `GroupRoleUpdate` to the local role KV.
    ///
    /// Honesty model: MLS does not authenticate *roles*, only the leaf
    /// signature key (`sender_id`). We map `sender_id → pubkey` via the
    /// `member_npub` KV and look up that pubkey's role, then enforce
    /// [`alpha_messages::can_change_role`]. An update from someone without the
    /// right is **ignored** (dropped). Replay/stale updates are dropped via the
    /// monotonic per-chat `group_roles_ver` counter.
    fn process_role_message(
        &self,
        chat_id: &str,
        alpha_msg: &AlphaMessage,
        sender_id: &str,
    ) -> Result<ProcessedEvent> {
        use alpha_database::group_roles;
        let conn = self.db.connection();

        // Pull-based sync: a member asks for role state. Surface it so the FFI
        // layer can have the OWNER reply once with a snapshot. (No DB change.)
        if matches!(alpha_msg, AlphaMessage::GroupRoleSyncRequest { .. }) {
            return Ok(ProcessedEvent::RoleSyncRequested { chat_id: chat_id.to_string() });
        }

        // Resolve the sender's pubkey from their leaf id.
        let sender_pubkey = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
        if sender_pubkey.is_empty() {
            tracing::warn!("role-control from unknown sender {} dropped", sender_id);
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // --- Full snapshot: learn owner + everyone's role (esp. on join) ---
        if let AlphaMessage::GroupRoleSnapshot { version, owner_pubkey, roles_json, .. } = alpha_msg {
            if *version <= group_roles::roles_version(&conn, chat_id) {
                tracing::debug!("GroupRoleSnapshot dropped: v{} not newer", version);
                return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
            }
            // Authorisation. Only the owner sends snapshots (handing ownership
            // over included), so a known owner is the only accepted sender: an
            // admin must not be able to name a new owner. Before we know the
            // owner, only the member who invited us is trusted (their signature
            // is on our Welcome): a member naming themselves owner would take
            // the group over on every phone that has no owner on record.
            let known_owner = group_roles::owner_of(&conn, chat_id);
            let allowed = match known_owner.as_deref() {
                Some(owner) => !sender_pubkey.is_empty() && sender_pubkey == owner,
                None => {
                    let inviter = alpha_database::KeyValueRepo::get(&conn, &format!("dir_bootstrap_auth:{chat_id}"))
                        .ok().flatten().unwrap_or_default();
                    !sender_pubkey.is_empty() && sender_pubkey == inviter
                }
            };
            if !allowed {
                tracing::warn!("GroupRoleSnapshot ignored in {}: sender {} may not set the roles", chat_id, sender_id);
                return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
            }
            // Ownership goes only to someone in the group.
            if *owner_pubkey != sender_pubkey && !group_roles::is_known_member(&conn, chat_id, owner_pubkey) {
                tracing::warn!("GroupRoleSnapshot ignored in {}: the named owner is not a member", chat_id);
                return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
            }
            // Clear existing explicit roles for this chat, then apply snapshot.
            if let Ok(entries) = alpha_database::KeyValueRepo::list(&conn) {
                let prefix = format!("group_role:{}:", chat_id);
                for e in entries {
                    if e.key.starts_with(&prefix) {
                        let _ = alpha_database::KeyValueRepo::delete(&conn, &e.key);
                    }
                }
            }
            group_roles::set_owner(&conn, chat_id, owner_pubkey);
            if let Ok(map) = serde_json::from_str::<std::collections::HashMap<String, String>>(roles_json) {
                for (pk, r) in map {
                    let nr = alpha_messages::normalize_role(&r);
                    if nr == alpha_messages::ROLE_ADMIN || nr == alpha_messages::ROLE_MODERATOR {
                        let _ = alpha_database::KeyValueRepo::set(&conn, &format!("group_role:{}:{}", chat_id, pk), nr);
                    }
                }
            }
            group_roles::set_roles_version(&conn, chat_id, *version);
            tracing::info!("GroupRoleSnapshot applied: chat={} owner={}", chat_id, owner_pubkey);
            // Permissions that arrived before we knew the owner: judge them now.
            let held_key = format!("pending_perms:{}", chat_id);
            let held = alpha_database::KeyValueRepo::get(&conn, &held_key).ok().flatten();
            if held.is_some() {
                let _ = alpha_database::KeyValueRepo::delete(&conn, &held_key);
            }
            drop(conn);
            if let Some((sid, msg)) = held
                .and_then(|h| serde_json::from_str::<serde_json::Value>(&h).ok())
                .and_then(|v| {
                    let sid = v.get("sid")?.as_str()?.to_string();
                    let bytes = hex::decode(v.get("msg")?.as_str()?).ok()?;
                    Some((sid, alpha_messages::deserialize(&bytes).ok()
                        .filter(|m| alpha_messages::validate_inbound(m).is_ok())?))
                })
            {
                let _ = self.process_permissions_message(chat_id, &msg, &sid);
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // --- Incremental update ---
        let (version, target_pubkey, role) = match alpha_msg {
            AlphaMessage::GroupRoleUpdate { version, target_pubkey, role, .. } => {
                (*version, target_pubkey.clone(), alpha_messages::normalize_role(role).to_string())
            }
            _ => return Err(SyncError::MessageProcessing(
                "process_role_message called on non-role message".to_string(),
            )),
        };

        let owner = group_roles::owner_of(&conn, chat_id).unwrap_or_default();
        let actor_role = group_roles::role_of(&conn, chat_id, &sender_pubkey);
        let target_role = group_roles::role_of(&conn, chat_id, &target_pubkey);

        if !alpha_messages::can_change_role(&actor_role, &target_role, &role) {
            tracing::warn!(
                "GroupRoleUpdate ignored: {} (role {}) may not set {} → {}",
                sender_id, actor_role, target_pubkey, role,
            );
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        // Replay protection: drop if not strictly newer.
        if version <= group_roles::roles_version(&conn, chat_id) {
            tracing::debug!("GroupRoleUpdate dropped: v{} not newer", version);
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }

        group_roles::apply_role_change(&conn, chat_id, &owner, &target_pubkey, &role);
        group_roles::set_roles_version(&conn, chat_id, version);
        tracing::info!("GroupRoleUpdate applied: chat={} {} → {}", chat_id, target_pubkey, role);

        Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() })
    }

    /// Apply an incoming `GroupDisbanded`: the owner deleted the group. Only the
    /// owner may; the core then wipes the chat. Idempotent.
    fn process_disband(&self, chat_id: &str, sender_id: &str) -> Result<ProcessedEvent> {
        let conn = self.db.connection();
        // Only the owner closes the group for everyone.
        let sender_pk = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
        let owner = alpha_database::group_roles::owner_of(&conn, chat_id).unwrap_or_default();
        if sender_pk.is_empty() || sender_pk != owner {
            tracing::warn!("GroupDisbanded ignored in {}: not from the owner", chat_id);
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        // Lock the group now; the core then wipes it (it owns the MLS state).
        let _ = alpha_database::MlsGroupRepo::update(&conn, chat_id, None, Some(false));
        tracing::info!("process_disband: group {} disbanded by owner", chat_id);
        Ok(ProcessedEvent::ChatDisbanded { chat_id: chat_id.to_string() })
    }

    /// Apply an incoming `GroupMuteUpdate`. Same trust model as roles:
    /// resolve the sender's pubkey + role, require authority over the target
    /// (`can_remove` hierarchy), drop stale updates via the per-chat version.
    fn process_mute_message(
        &self,
        chat_id: &str,
        alpha_msg: &AlphaMessage,
        sender_id: &str,
    ) -> Result<ProcessedEvent> {
        use alpha_database::group_roles;
        let (version, target_pubkey, until_ts) = match alpha_msg {
            AlphaMessage::GroupMuteUpdate { version, target_pubkey, until_ts, .. } =>
                (*version, target_pubkey.clone(), *until_ts),
            _ => return Err(SyncError::MessageProcessing("non-mute message".to_string())),
        };
        let conn = self.db.connection();
        let sender_pubkey = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
        if sender_pubkey.is_empty() {
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        let actor_role = group_roles::role_of(&conn, chat_id, &sender_pubkey);
        let target_role = group_roles::role_of(&conn, chat_id, &target_pubkey);
        if !alpha_messages::can_remove(&actor_role, &target_role) {
            tracing::warn!("GroupMuteUpdate ignored: sender (role {}) may not mute that member", actor_role);
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        if version <= group_roles::mutes_version(&conn, chat_id) {
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        group_roles::set_mute(&conn, chat_id, &target_pubkey, until_ts);
        group_roles::set_mutes_version(&conn, chat_id, version);
        tracing::info!("GroupMuteUpdate applied: chat={} {} until={}", chat_id, target_pubkey, until_ts);
        Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() })
    }

    /// Check an incoming `GroupBan`: the sender must outrank the target.
    fn process_ban_message(&self, chat_id: &str, alpha_msg: &AlphaMessage, sender_id: &str) -> Result<ProcessedEvent> {
        use alpha_database::group_roles;
        let AlphaMessage::GroupBan { target_pubkey, reason, .. } = alpha_msg else {
            return Err(SyncError::MessageProcessing("non-ban message".to_string()));
        };
        let conn = self.db.connection();
        let sender_pubkey = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
        let actor_role = group_roles::role_of(&conn, chat_id, &sender_pubkey);
        let target_role = group_roles::role_of(&conn, chat_id, target_pubkey);
        if sender_pubkey.is_empty() || !alpha_messages::can_remove(&actor_role, &target_role) {
            tracing::warn!("GroupBan ignored in {}: {} (role {}) may not ban", chat_id, sender_id, actor_role);
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        Ok(ProcessedEvent::MemberBanned {
            chat_id: chat_id.to_string(),
            target_pubkey: target_pubkey.clone(),
            reason: reason.chars().take(300).collect(),
        })
    }

    /// May `sender_id` put this message into the chat? Only content (text,
    /// media, replies) is checked. Owner/admins always may; a muted member may
    /// not; with "admins only" (or a temporarily closed chat) others may not —
    /// except a comment under a channel post when comments are on. A sender
    /// whose identity is not known yet cannot be judged and is let through.
    fn write_allowed(&self, chat_id: &str, msg: &AlphaMessage, sender_id: &str, event_ts: i64) -> bool {
        use alpha_database::group_roles;
        // Delete / edit touch an existing message: only its author may edit it;
        // its author or the group's admins (moderators in a group) may delete it.
        // Everywhere — a modified client must not erase or rewrite others' words.
        match msg {
            AlphaMessage::Edit { id, .. } => {
                let conn = self.db.connection();
                return MessageRepo::get(&conn, id).ok().flatten().map(|m| m.sender_id == sender_id).unwrap_or(false);
            }
            AlphaMessage::Delete { target_id, .. } => {
                let conn = self.db.connection();
                let Some(target) = MessageRepo::get(&conn, target_id).ok().flatten() else { return false };
                if target.chat_id != chat_id { return false; }
                if target.sender_id == sender_id { return true; }
                if group_roles::owner_of(&conn, chat_id).is_none() { return false; }
                let Some(pk) = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id) else { return false };
                let role = group_roles::role_of(&conn, chat_id, &pk);
                // In a channel the posts belong to its admins; a moderator looks
                // after the discussions (comments) only.
                let is_post = group_roles::is_channel(&conn, chat_id)
                    && alpha_database::comments::post_of(&conn, target_id).is_none();
                return if is_post { alpha_messages::can_manage_group(&role) } else { alpha_messages::can_moderate(&role) };
            }
            _ => {}
        }
        // Content: ONE rule set (alpha_database::policy) — the same our own core
        // applies before sending and the screen shows as the reason.
        let Some(intent) = write_intent(msg) else { return true };
        let conn = self.db.connection();
        let pk = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id);
        let now = chrono::Utc::now().timestamp();
        match alpha_database::policy::may_write(&conn, chat_id, pk.as_deref(), sender_id, &intent, event_ts, now) {
            Ok(()) => {
                if matches!(intent, alpha_database::policy::Intent::Comment { .. }) && pk.is_some() {
                    alpha_database::policy::note_comment(&conn, chat_id, sender_id, event_ts);
                }
                true
            }
            Err(d) => {
                tracing::info!("write denied in {}: {} from {}", chat_id, d.code(), sender_id);
                false
            }
        }
    }

    /// Apply an incoming `GroupPermissionsUpdate`. Only the OWNER may set
    /// group permissions; drop stale updates via the per-chat version.
    fn process_permissions_message(
        &self,
        chat_id: &str,
        alpha_msg: &AlphaMessage,
        sender_id: &str,
    ) -> Result<ProcessedEvent> {
        use alpha_database::group_roles;
        let (version, write, invite, edit, closed_until, react_mode, react_list) = match alpha_msg {
            AlphaMessage::GroupPermissionsUpdate { version, who_can_write, who_can_invite, who_can_edit, closed_until, reactions_mode, reactions, .. } =>
                (*version, who_can_write.clone(), who_can_invite.clone(), who_can_edit.clone(), *closed_until, reactions_mode.clone(), reactions.clone()),
            _ => return Err(SyncError::MessageProcessing("non-permissions message".to_string())),
        };
        let conn = self.db.connection();
        let sender_pubkey = alpha_database::policy::sender_pubkey(&conn, chat_id, sender_id).unwrap_or_default();
        // A newcomer gets the role snapshot and the permissions at the same
        // moment, and they may be processed in either order. Until we know
        // who the owner is (or who the sender is), we can't judge the update —
        // so it waits, and the snapshot that names the owner applies it.
        if sender_pubkey.is_empty() || group_roles::owner_of(&conn, chat_id).is_none() {
            if let Ok(bytes) = alpha_messages::serialize(alpha_msg) {
                let held = serde_json::json!({ "sid": sender_id, "msg": hex::encode(bytes) }).to_string();
                let _ = alpha_database::KeyValueRepo::set(&conn, &format!("pending_perms:{}", chat_id), &held);
                tracing::warn!("GroupPermissionsUpdate held in {}: owner not known yet", chat_id);
            }
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        // Only the recorded owner may change group permissions.
        if group_roles::owner_of(&conn, chat_id).as_deref() != Some(sender_pubkey.as_str()) {
            tracing::warn!("GroupPermissionsUpdate ignored: {} is not the owner", sender_id);
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        if version <= group_roles::perms_version(&conn, chat_id) {
            return Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() });
        }
        group_roles::set_permissions(&conn, chat_id, &write, &invite, &edit);
        group_roles::set_closed_until(&conn, chat_id, closed_until);
        if !react_mode.is_empty() {
            group_roles::set_reactions(&conn, chat_id, &react_mode, &react_list);
        }
        if let AlphaMessage::GroupPermissionsUpdate { channel_cfg, .. } = alpha_msg {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(channel_cfg) {
                group_roles::set_channel_cfg(&conn, chat_id, &v);
            }
        }
        group_roles::set_perms_version(&conn, chat_id, version);
        tracing::info!("GroupPermissionsUpdate applied: chat={} write={} invite={} edit={} closed_until={}", chat_id, write, invite, edit, closed_until);
        // Channel history that came before the settings: take it now.
        let prefix = format!("pending_hist:{}:", chat_id);
        let held: Vec<(String, String)> = alpha_database::KeyValueRepo::list_prefix(&conn, &prefix).unwrap_or_default();
        for (k, _) in &held {
            let _ = alpha_database::KeyValueRepo::delete(&conn, k);
        }
        drop(conn);
        for (_, v) in held {
            let Some((sid, bytes)) = serde_json::from_str::<serde_json::Value>(&v).ok().and_then(|v| {
                Some((v.get("sid")?.as_str()?.to_string(), hex::decode(v.get("msg")?.as_str()?).ok()?))
            }) else { continue };
            let _ = self.process_application_message(chat_id, &bytes, &sid, 0);
        }
        Ok(ProcessedEvent::ChatMetadataUpdated { chat_id: chat_id.to_string() })
    }

    /// Write a group avatar JPEG to the local filesystem at a stable path.

    /// Write a peer's avatar JPEG to the local filesystem at a stable path.
    /// Returns the absolute path. The directory is created if missing.
    fn write_contact_avatar(&self, sender_id: &str, bytes: &[u8]) -> std::io::Result<String> {
        let dir = self.contact_avatars_dir.clone();
        std::fs::create_dir_all(&dir)?;
        let path = dir.join(format!("{}.jpg", sender_id));
        std::fs::write(&path, bytes)?;
        Ok(path.to_string_lossy().into_owned())
    }

    /// Write a peer's cover/banner JPEG next to their avatar (same dir, `_cover`
    /// suffix). Returns the absolute path.
    fn write_contact_cover(&self, sender_id: &str, bytes: &[u8]) -> std::io::Result<String> {
        let dir = self.contact_avatars_dir.clone();
        std::fs::create_dir_all(&dir)?;
        let path = dir.join(format!("{}_cover.jpg", sender_id));
        std::fs::write(&path, bytes)?;
        Ok(path.to_string_lossy().into_owned())
    }

    /// Map an [`AlphaMessage`] to a database [`alpha_database::Message`]
    /// model and persist it.
    ///
    /// For ephemeral messages:
    /// - AfterSend: `expires_at` is computed at persist time.
    /// - AfterRead: `expires_at` is NULL until read.
    /// - ViewOnce: `content` is NULL (never written to disk).
    fn persist_alpha_message(&self, chat_id: &str, msg: &AlphaMessage, sender_id: &str, event_ts: u64) -> Result<()> {
        // The sender's send time (Nostr event created_at, Unix seconds). Used as
        // the row's created_at so ordering follows send order regardless of the
        // arrival order relays deliver in.
        // The payload's millisecond time refines it (same-second order) when it
        // agrees with the event time; anything further off is not trusted.
        let ms = msg.sent_ms();
        let sent_at_rfc3339: Option<String> = if ms != 0 && (ms / 1000).abs_diff(event_ts) <= 300 {
            chrono::DateTime::<chrono::Utc>::from_timestamp_millis(ms as i64)
        } else {
            chrono::DateTime::<chrono::Utc>::from_timestamp(event_ts as i64, 0)
        }
        .map(|dt| dt.to_rfc3339());
        // Reaction toggle: a Reaction with remove=true deletes the matching
        // reaction row instead of inserting a new one.
        if let AlphaMessage::Reaction { target_id, emoji, remove: Some(true), .. } = msg {
            let conn = self.db.connection();
            let _ = MessageRepo::delete_reaction(&conn, chat_id, sender_id, target_id, emoji);
            return Ok(());
        }
        // The group allows only some reactions (or none): others are not shown.
        if let AlphaMessage::Reaction { emoji, .. } = msg {
            let conn = self.db.connection();
            if !alpha_database::group_roles::reaction_allowed(&conn, chat_id, emoji) {
                return Ok(());
            }
        }
        let (msg_type, content, reply_to, media_ref, ttl_seconds) = match msg {
            AlphaMessage::Text { id: wire_id, body, reply_to, expiry, .. } => {
                let (ttl, expiry_type) = match expiry {
                    // A whisper: a one-view row whose ttl holds the reading time;
                    // its secret goes to the whispers table, never to `messages`.
                    Some(ExpiryPolicy::Whisper { secs, .. }) => {
                        (Some(*secs as i64), Some(ExpiryPolicyType::ViewOnce))
                    }
                    Some(ExpiryPolicy::AfterSend { ttl_secs }) => {
                        (Some(*ttl_secs as i64), Some(ExpiryPolicyType::AfterSend))
                    }
                    Some(ExpiryPolicy::AfterRead { ttl_secs }) => {
                        (Some(*ttl_secs as i64), Some(ExpiryPolicyType::AfterRead))
                    }
                    Some(ExpiryPolicy::ViewOnce) => {
                        (None, Some(ExpiryPolicyType::ViewOnce))
                    }
                    Some(ExpiryPolicy::None) | None => (None, None),
                };
                let safe_content = match expiry {
                    Some(ExpiryPolicy::ViewOnce) | Some(ExpiryPolicy::Whisper { .. }) => None, // D3: never in messages
                    _ => Some(body.clone()),
                };
                // Use ephemeral helper if we have an expiry policy
                if let Some(ep_type) = expiry_type {
                    let expires_at = match expiry {
                        Some(ExpiryPolicy::AfterSend { ttl_secs }) => {
                            let created = chrono::Utc::now();
                            Some((created + chrono::Duration::seconds(i64::from(*ttl_secs))).to_rfc3339())
                        }
                        _ => None,
                    };
                    let mut db_msg = new_ephemeral_message(
                        chat_id.to_string(),
                        sender_id.to_string(),
                        DbMessageType::Text,
                        safe_content,
                        reply_to.clone(),
                        None,
                        ttl,
                        expires_at,
                        ep_type,
                    );
                    // The sender's id, as on the untimed path: receipts, deletes and
                    // reactions name it; a copy from another relay is the same row.
                    if !wire_id.is_empty() { db_msg.id = wire_id.clone(); }
                    if let Some(ts) = &sent_at_rfc3339 { db_msg.created_at = ts.clone(); }
                    let conn = self.db.connection();
                    if MessageRepo::get(&conn, &db_msg.id).ok().flatten().is_some() {
                        return Ok(());
                    }
                    MessageRepo::insert(&conn, &db_msg)?;
                    if let Some(ExpiryPolicy::Whisper { secs, glance }) = expiry {
                        alpha_database::whispers::put_incoming(
                            &conn, &db_msg.id, chat_id, wire_id, "text", Some(body), None, *secs, *glance, &db_msg.created_at,
                        )?;
                    }
                    tracing::debug!("Persisted ephemeral message {} in chat {}", db_msg.id, chat_id);
                    return Ok(());
                }
                (DbMessageType::Text, safe_content, reply_to.clone(), None, ttl)
            }
            AlphaMessage::Edit { id, body, .. } => (
                DbMessageType::Edit,
                Some(body.clone()),
                // Carry the edited message's id in reply_to so the UI can
                // fold the edit onto its target (the row gets a fresh UUID,
                // losing the original id otherwise).
                Some(id.clone()),
                None,
                None,
            ),
            AlphaMessage::Reaction { target_id, emoji, .. } => (
                DbMessageType::Reaction,
                Some(emoji.clone()),
                Some(target_id.clone()),
                None,
                None,
            ),
            AlphaMessage::Reply { body, reply_to, quoted_text, .. } => (
                DbMessageType::Reply,
                Some(body.clone()),
                Some(reply_to.clone()),
                // Inline quote snippet in media_ref (see sender-side note).
                if quoted_text.is_empty() { None } else { Some(quoted_text.clone()) },
                None,
            ),
            AlphaMessage::Delete { target_id, .. } => (
                DbMessageType::Delete,
                None,
                Some(target_id.clone()),
                None,
                None,
            ),
            AlphaMessage::Disappearing { body, ttl_seconds, .. } => (
                DbMessageType::Disappearing,
                Some(body.clone()),
                None,
                None,
                Some(*ttl_seconds as i64),
            ),
            AlphaMessage::Media { id: wire_id, media_ref, media_type, caption, reply_to, expiry, .. } => {
                let (ttl, expiry_type) = match expiry {
                    Some(ExpiryPolicy::Whisper { secs, .. }) => {
                        (Some(*secs as i64), Some(ExpiryPolicyType::ViewOnce))
                    }
                    Some(ExpiryPolicy::AfterSend { ttl_secs }) => {
                        (Some(*ttl_secs as i64), Some(ExpiryPolicyType::AfterSend))
                    }
                    Some(ExpiryPolicy::AfterRead { ttl_secs }) => {
                        (Some(*ttl_secs as i64), Some(ExpiryPolicyType::AfterRead))
                    }
                    Some(ExpiryPolicy::ViewOnce) => {
                        (None, Some(ExpiryPolicyType::ViewOnce))
                    }
                    Some(ExpiryPolicy::None) | None => (None, None),
                };
                let safe_caption = match expiry {
                    Some(ExpiryPolicy::ViewOnce) | Some(ExpiryPolicy::Whisper { .. }) => None, // D3: never in messages
                    _ => caption.clone(),
                };
                let safe_media_ref = match expiry {
                    Some(ExpiryPolicy::ViewOnce) | Some(ExpiryPolicy::Whisper { .. }) => None, // D3: media ref not stored for view-once
                    _ => Some(media_ref.clone()),
                };
                if let Some(ep_type) = expiry_type {
                    let expires_at = match expiry {
                        Some(ExpiryPolicy::AfterSend { ttl_secs }) => {
                            let created = chrono::Utc::now();
                            Some((created + chrono::Duration::seconds(i64::from(*ttl_secs))).to_rfc3339())
                        }
                        _ => None,
                    };
                    let mut db_msg = new_ephemeral_message(
                        chat_id.to_string(),
                        sender_id.to_string(),
                        DbMessageType::Media,
                        safe_caption,
                        None,
                        safe_media_ref,
                        ttl,
                        expires_at,
                        ep_type,
                    );
                    // The sender's id, as on the untimed path: receipts, deletes and
                    // reactions name it; a copy from another relay is the same row.
                    if !wire_id.is_empty() { db_msg.id = wire_id.clone(); }
                    db_msg.reply_to = reply_to.clone();
                    if let Some(ts) = &sent_at_rfc3339 { db_msg.created_at = ts.clone(); }
                    let conn = self.db.connection();
                    if MessageRepo::get(&conn, &db_msg.id).ok().flatten().is_some() {
                        return Ok(());
                    }
                    MessageRepo::insert(&conn, &db_msg)?;
                    if let Some(ExpiryPolicy::Whisper { secs, glance }) = expiry {
                        alpha_database::whispers::put_incoming(
                            &conn, &db_msg.id, chat_id, wire_id, media_type, None, Some(media_ref), *secs, *glance, &db_msg.created_at,
                        )?;
                    }
                    tracing::debug!("Persisted ephemeral media {} in chat {}", db_msg.id, chat_id);
                    return Ok(());
                }
                (DbMessageType::Media, caption.clone(), reply_to.clone(), Some(media_ref.clone()), ttl)
            }
            AlphaMessage::ReadSignal { .. } | AlphaMessage::DeliveredSignal { .. } => {
                // Receipts are handled before persistence, never stored.
                return Ok(());
            }
            AlphaMessage::ChatState { .. } => {
                // Transient presence — intercepted earlier, never persisted.
                return Ok(());
            }
            AlphaMessage::ProfileUpdate { .. }
            | AlphaMessage::ProfileRequest { .. }
            | AlphaMessage::GroupMetadataUpdate { .. }
            | AlphaMessage::GroupRoleUpdate { .. }
            | AlphaMessage::GroupRoleSnapshot { .. }
            | AlphaMessage::GroupRoleSyncRequest { .. }
            // Transport directory — a service message, not a replica:
            // applied to the address map, never a bubble.
            | AlphaMessage::GroupDirectory { .. }
            | AlphaMessage::SecretChat { .. }
            | AlphaMessage::GroupMuteUpdate { .. }
            | AlphaMessage::GroupBan { .. }
            | AlphaMessage::GroupPermissionsUpdate { .. }
            | AlphaMessage::GroupAuditEntry { .. }
            | AlphaMessage::GroupDisbanded { .. }
            | AlphaMessage::ChatDeleted { .. }
            | AlphaMessage::PinMessage { .. }
            | AlphaMessage::StoryPost { .. }
            | AlphaMessage::StoryDelete { .. }
            | AlphaMessage::StoryView { .. }
            | AlphaMessage::ChannelSeen { .. }
            | AlphaMessage::ChannelHistory { .. }
            | AlphaMessage::DeliveryAck { .. }
            | AlphaMessage::ResendRequest { .. }
            | AlphaMessage::PollVote { .. }
            | AlphaMessage::PollClose { .. }
            | AlphaMessage::WhisperBurned { .. } => {
                // Profile / group-metadata / role / mute control messages AND
                // delivery-layer control messages (DeliveryAck / ResendRequest)
                // are handled separately — they are NOT persisted as chat
                // messages. Delivery control is intercepted earlier in
                // process_application_message; reaching here is a safety net.
                return Ok(());
            }
        };

        // Non-ephemeral path (original logic). A chat-timer message (the only
        // one here with a ttl) starts counting when it is read: `mark_read`
        // sets its expiry then.
        let after_read = matches!(msg, AlphaMessage::Disappearing { .. });
        let expires_at = if after_read { None } else {
            ttl_seconds.map(|ttl| {
                let created = chrono::Utc::now();
                (created + chrono::Duration::seconds(ttl)).to_rfc3339()
            })
        };

        let mut db_msg = new_message(
            chat_id.to_string(),
            sender_id.to_string(), // sender_id filled in by caller
            msg_type,
            content,
            reply_to,
            media_ref,
            ttl_seconds,
            expires_at,
        );
        if after_read {
            db_msg.expiry_policy = Some(ExpiryPolicyType::AfterRead);
        }
        // CRITICAL: use the AlphaMessage's stable id as the row id so the
        // SAME message has the SAME id on every device. Otherwise reactions
        // / replies / edits / deletes (which target this id) never match on
        // the receiver, where new_message would mint a fresh random UUID.
        // EDIT is special: it carries the ORIGINAL id (matched via reply_to in
        // the fold) but needs its own row id or it collides with the base row.
        let stable_id = match msg {
            AlphaMessage::Edit { id, edited_at, .. } => format!("{}#edit#{}", id, edited_at),
            _ => msg.id().to_string(),
        };
        if !stable_id.is_empty() {
            db_msg.id = stable_id;
        }
        if let Some(ts) = &sent_at_rfc3339 { db_msg.created_at = ts.clone(); }

        let conn = self.db.connection();
        // Idempotency: the same event can arrive from several relays. Use an
        // upsert-ignore so a duplicate stable id doesn't error out.
        if MessageRepo::get(&conn, &db_msg.id).ok().flatten().is_some() {
            tracing::trace!("Duplicate message {} ignored", db_msg.id);
            return Ok(());
        }
        MessageRepo::insert(&conn, &db_msg)?;

        // For delete-for-everyone, also soft-delete the target row so the
        // chat-list preview / latest_in_chat skips it and falls back to the
        // previous real message (otherwise the deleted message would still
        // surface as the chat preview).
        if let AlphaMessage::Delete { target_id, .. } = msg {
            let _ = MessageRepo::mark_deleted(&conn, target_id);
        }
        // For an edit, also rewrite the original row's content so the
        // chat-list preview reflects the new text.
        if let AlphaMessage::Edit { id, body, edited_at, .. } = msg {
            let _ = MessageRepo::apply_edit(&conn, id, body, edited_at);
        }

        tracing::debug!("Persisted message {} in chat {}", db_msg.id, chat_id);
        Ok(())
    }

    /// Process a membership change event (add or remove).
    ///
    /// This is called by `CatchUpManager` when an MLS commit
    /// is processed that adds or removes a member.
    pub fn process_member_added(&self, chat_id: &str, member: &str) -> Result<ProcessedEvent> {
        let conn = self.db.connection();
        let member_row = alpha_database::new_chat_member(
            chat_id.to_string(),
            member.to_string(),
            None,
        );
        ChatRepo::add_member(&conn, &member_row)?;

        tracing::info!("Member {} added to chat {}", member, chat_id);
        Ok(ProcessedEvent::MemberAdded {
            chat_id: chat_id.to_string(),
            member: member.to_string(),
        })
    }

    /// Process a member removal event.
    pub fn process_member_removed(&self, chat_id: &str, member: &str) -> Result<ProcessedEvent> {
        let conn = self.db.connection();
        ChatRepo::remove_member(&conn, chat_id, member)?;

        tracing::info!("Member {} removed from chat {}", member, chat_id);
        Ok(ProcessedEvent::MemberRemoved {
            chat_id: chat_id.to_string(),
            member: member.to_string(),
        })
    }

    /// Process an epoch advancement event.
    pub fn process_epoch_advanced(
        &self,
        chat_id: &str,
        new_epoch: u64,
    ) -> Result<ProcessedEvent> {
        let conn = self.db.connection();
        ChatRepo::update(&conn, chat_id, None, Some(new_epoch as i64))?;

        tracing::info!("Chat {} advanced to epoch {}", chat_id, new_epoch);
        Ok(ProcessedEvent::EpochAdvanced {
            chat_id: chat_id.to_string(),
            new_epoch,
        })
    }
}

/// Store a group's avatar under a name that follows its CONTENT
/// (`<chat>_<hash>.jpg`): a new photo is a new path, so every screen and image
/// cache shows it at once instead of the old picture kept for the same path.
/// Older versions of this chat's avatar are removed.
pub fn store_group_avatar(
    dir: &std::path::Path,
    chat_id: &str,
    hash_hex: &str,
    bytes: &[u8],
) -> std::io::Result<String> {
    std::fs::create_dir_all(dir)?;
    let tag: String = hash_hex.chars().filter(|c| c.is_ascii_hexdigit()).take(12).collect();
    let name = if tag.is_empty() { format!("{}.jpg", chat_id) } else { format!("{}_{}.jpg", chat_id, tag) };
    let path = dir.join(&name);
    std::fs::write(&path, bytes)?;
    if let Ok(entries) = std::fs::read_dir(dir) {
        for e in entries.flatten() {
            let n = e.file_name().to_string_lossy().into_owned();
            let ours = n == format!("{}.jpg", chat_id) || (n.starts_with(&format!("{}_", chat_id)) && n.ends_with(".jpg"));
            if ours && n != name {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
    Ok(path.to_string_lossy().into_owned())
}

/// What a message wants to put into a chat, for [`alpha_database::policy`]:
/// content only (text, media, replies, disappearing); everything else (control
/// messages, reactions, receipts) has its own checks — `None`.
pub fn write_intent(msg: &AlphaMessage) -> Option<alpha_database::policy::Intent> {
    use alpha_database::policy::Intent;
    if !matches!(msg, AlphaMessage::Text { .. } | AlphaMessage::Media { .. } | AlphaMessage::Reply { .. } | AlphaMessage::Disappearing { .. }) {
        return None;
    }
    Some(if msg.is_channel_comment() {
        Intent::Comment { post_id: msg.comment_post_id().unwrap_or_default(), kind: msg.comment_kind() }
    } else {
        Intent::Message
    })
}

/// File a message that has just been stored under the post it comments on,
/// so the feed leaves it out and the post's discussion shows it. A comment
/// written in the old text format also loses its header here: from now on it
/// is stored like any other comment.
pub fn file_comment(conn: &alpha_database::Connection, chat_id: &str, msg: &AlphaMessage) {
    let Some(post) = msg.comment_post_id() else { return };
    if alpha_database::comments::link(conn, msg.id(), chat_id, &post).is_err() { return; }
    if msg.thread().is_none() {
        strip_legacy_header(conn, msg.id());
    }
}

/// Once, after the update that introduced typed comments: file every comment
/// stored in the old text format. Returns how many were filed.
pub fn adopt_legacy_comments(conn: &alpha_database::Connection) -> usize {
    let rows: Vec<(String, String, String)> = match conn.prepare(
        "SELECT id, chat_id, content FROM messages
         WHERE content IS NOT NULL AND substr(content, 1, 5) = char(2) || 'CMT' || char(2)",
    ) {
        Ok(mut stmt) => stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
            .map(|it| it.filter_map(|r| r.ok()).collect())
            .unwrap_or_default(),
        Err(_) => return 0,
    };
    let mut n = 0;
    for (id, chat_id, content) in rows {
        let Some((post, _)) = alpha_messages::legacy_comment(&content) else { continue };
        if alpha_database::comments::link(conn, &id, &chat_id, &post).is_ok() {
            strip_legacy_header(conn, &id);
            n += 1;
        }
    }
    n
}

fn strip_legacy_header(conn: &alpha_database::Connection, message_id: &str) {
    let Some(row) = MessageRepo::get(conn, message_id).ok().flatten() else { return };
    let Some(content) = row.content.as_deref() else { return };
    if let Some((_, body)) = alpha_messages::legacy_comment(content) {
        let _ = alpha_database::comments::set_content(conn, message_id, (!body.is_empty()).then_some(body));
    }
}

/// The ref without any `local_path` (also inside album items).
fn strip_local_paths(media_ref: &str) -> String {
    if !media_ref.contains("local_path") {
        return media_ref.to_string();
    }
    fn strip(v: &mut serde_json::Value) {
        match v {
            serde_json::Value::Object(m) => {
                m.remove("local_path");
                m.values_mut().for_each(strip);
            }
            serde_json::Value::Array(a) => a.iter_mut().for_each(strip),
            _ => {}
        }
    }
    match serde_json::from_str::<serde_json::Value>(media_ref) {
        Ok(mut v) => { strip(&mut v); v.to_string() }
        Err(_) => media_ref.to_string(),
    }
}

#[cfg(test)]
mod local_path_tests {
    #[test]
    fn a_received_ref_loses_every_local_path() {
        let r = super::strip_local_paths(r#"{"hash":"h","local_path":"/data/x","items":[{"local_path":"/y","hash":"i"}]}"#);
        assert!(!r.contains("local_path") && r.contains("\"hash\":\"h\"") && r.contains("\"hash\":\"i\""));
    }
}
