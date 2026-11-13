//! Message type definitions for AlphaMessenger.
//!
//! This is the app-layer message format that goes INSIDE the MLS payload.
//! After MLS decryption, we get raw bytes — this module defines the
//! structured format for those bytes. Pure product code, NO crypto.
//!
//! # Ephemeral messages
//!
//! Text and Media messages can carry an optional [`ExpiryPolicy`] that
//! controls when the message should be automatically deleted:
//!
//! - **AfterSend**: timer starts at send time, both sides delete at the same time.
//! - **AfterRead**: timer starts when the recipient opens the message;
//!   a `ReadSignal` is sent back so the sender also deletes.
//! - **ViewOnce**: render once, then immediately zeroize and delete.
//!   Plaintext is **never** written to disk — memory only.
//!
//! The old `Disappearing` variant is retained for backward compatibility;
//! it is equivalent to `Text { expiry: Some(AfterSend { ttl_secs }) }`.


/// Helper for `skip_serializing_if`: omit `seq` from the wire when 0, so
/// legacy-shaped JSON is produced for unsequenced messages (byte-identical
/// to pre-delivery-layer clients -> perfect backward compat).
fn is_zero_u64(v: &u64) -> bool { *v == 0 }

fn is_zero_seq(v: &u64) -> bool {
    *v == 0
}

fn is_false(v: &bool) -> bool { !*v }

use serde::{Deserialize, Serialize};

use crate::expiry::ExpiryPolicy;

/// The top-level message type for AlphaMessenger.
///
/// Each variant represents a different kind of user-facing message.
/// All messages are JSON-serialized before being passed to MLS for encryption.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type")]
pub enum AlphaMessage {
    /// A plain text message.
    #[serde(rename = "text")]
    Text {
        /// Unique message identifier (UUID v4).
        id: String,
        /// The message body text.
        body: String,
        /// Optional message ID being replied to.
        #[serde(skip_serializing_if = "Option::is_none")]
        reply_to: Option<String>,
        /// Optional expiry policy for ephemeral messages.
        /// When `None`, the message is permanent.
        #[serde(skip_serializing_if = "Option::is_none")]
        expiry: Option<ExpiryPolicy>,
        /// Delivery-layer sequence number (per chat+sender, monotonic). 0 = unsequenced.
        #[serde(default, skip_serializing_if = "is_zero_seq")]
        seq: u64,
        /// Sender's send time in ms (orders messages sent within one second). 0 = absent.
        #[serde(default, rename = "ms", skip_serializing_if = "is_zero_u64")]
        sent_ms: u64,
        /// Sent without sound: the receiver notifies quietly.
        #[serde(default, skip_serializing_if = "is_false")]
        silent: bool,
        /// Comment thread: the id of the channel post this message discusses.
        /// `None` = an ordinary message of the chat.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        thread: Option<String>,
    },

    /// An edit to a previously sent message.
    #[serde(rename = "edit")]
    Edit {
        /// Same ID as the original message.
        id: String,
        /// The new text content.
        body: String,
        /// RFC3339 timestamp of when the edit occurred.
        edited_at: String,
        /// Delivery-layer sequence number (per chat+sender, monotonic). 0 = unsequenced.
        #[serde(default, skip_serializing_if = "is_zero_seq")]
        seq: u64,
    },

    /// A reaction (emoji) to a message.
    #[serde(rename = "reaction")]
    Reaction {
        /// Unique identifier for this reaction (UUID v4).
        id: String,
        /// The message ID being reacted to.
        target_id: String,
        /// The reaction emoji.
        emoji: String,
        /// If `true`, this reaction should be removed.
        #[serde(skip_serializing_if = "Option::is_none")]
        remove: Option<bool>,
        /// Delivery-layer sequence number (per chat+sender, monotonic). 0 = unsequenced.
        #[serde(default, skip_serializing_if = "is_zero_seq")]
        seq: u64,
    },

    /// A reply to a specific message.
    #[serde(rename = "reply")]
    Reply {
        /// Unique message identifier (UUID v4).
        id: String,
        /// The reply body text.
        body: String,
        /// The message ID being replied to.
        reply_to: String,
        /// Inline snippet of the quoted message's text. Carried with the
        /// reply so the recipient can render the quote even if they never
        /// received the original (relay loss / joined later). `serde(default)`
        /// keeps old replies (without this field) deserialising.
        #[serde(default)]
        quoted_text: String,
        /// Delivery-layer sequence number (per chat+sender, monotonic). 0 = unsequenced.
        #[serde(default, skip_serializing_if = "is_zero_seq")]
        seq: u64,
        /// Sender's send time in ms (orders messages sent within one second). 0 = absent.
        #[serde(default, rename = "ms", skip_serializing_if = "is_zero_u64")]
        sent_ms: u64,
        /// Sent without sound: the receiver notifies quietly.
        #[serde(default, skip_serializing_if = "is_false")]
        silent: bool,
        /// Comment thread: the id of the channel post this message discusses.
        /// `None` = an ordinary message of the chat.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        thread: Option<String>,
    },

    /// A deletion of a previously sent message.
    #[serde(rename = "delete")]
    Delete {
        /// Same ID as the original message (for tombstone tracking).
        id: String,
        /// The message ID being deleted.
        target_id: String,
        /// Delivery-layer sequence number (per chat+sender, monotonic). 0 = unsequenced.
        #[serde(default, skip_serializing_if = "is_zero_seq")]
        seq: u64,
    },

    /// A disappearing (ephemeral) message.
    ///
    /// **Legacy** — retained for backward compatibility with messages
    /// already in the wild. New code should use `Text { expiry:
    /// Some(AfterSend { ttl_secs }) }` instead.
    #[serde(rename = "disappearing")]
    Disappearing {
        /// Unique message identifier (UUID v4).
        id: String,
        /// The message body text.
        body: String,
        /// Time-to-live in seconds.
        ttl_seconds: u32,
        /// Delivery-layer sequence number (per chat+sender, monotonic). 0 = unsequenced.
        #[serde(default, skip_serializing_if = "is_zero_seq")]
        seq: u64,
        /// Sender's send time in ms (orders messages sent within one second). 0 = absent.
        #[serde(default, rename = "ms", skip_serializing_if = "is_zero_u64")]
        sent_ms: u64,
        /// Sent without sound: the receiver notifies quietly.
        #[serde(default, skip_serializing_if = "is_false")]
        silent: bool,
    },

    /// A media attachment message.
    #[serde(rename = "media")]
    Media {
        /// Unique message identifier (UUID v4).
        id: String,
        /// Reference to the encrypted media attachment.
        media_ref: String,
        /// Media type: "image", "video", "audio", "file".
        media_type: String,
        /// Original filename.
        filename: String,
        /// Original file size in bytes.
        size_bytes: u64,
        /// Optional caption for the media.
        #[serde(skip_serializing_if = "Option::is_none")]
        caption: Option<String>,
        /// Optional message ID being replied to.
        #[serde(skip_serializing_if = "Option::is_none")]
        reply_to: Option<String>,
        /// Optional expiry policy for ephemeral media.
        #[serde(skip_serializing_if = "Option::is_none")]
        expiry: Option<ExpiryPolicy>,
        /// Delivery-layer sequence number (per chat+sender, monotonic). 0 = unsequenced.
        #[serde(default, skip_serializing_if = "is_zero_seq")]
        seq: u64,
        /// Sender's send time in ms (orders messages sent within one second). 0 = absent.
        #[serde(default, rename = "ms", skip_serializing_if = "is_zero_u64")]
        sent_ms: u64,
        /// Sent without sound: the receiver notifies quietly.
        #[serde(default, skip_serializing_if = "is_false")]
        silent: bool,
        /// Comment thread: the id of the channel post this message discusses.
        /// `None` = an ordinary message of the chat.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        thread: Option<String>,
    },

    /// A read-signal sent by the recipient when they open an `AfterRead`
    /// ephemeral message. This tells the sender to start their expiry
    /// timer as well, so both sides delete at approximately the same time.
    ///
    /// This is an app-level control message sent **inside** the MLS
    /// encrypted channel — the relay never sees it in cleartext.
    #[serde(rename = "read_signal")]
    ReadSignal {
        /// The ID of the message that was read.
        target_id: String,
        /// The chat (group) ID the message belongs to.
        chat_id: String,
        /// RFC3339 timestamp when the message was read.
        read_at: String,
    },

    /// "These messages reached me": sent by a recipient once per batch of
    /// received messages, so their sender shows them as delivered (two grey
    /// checks). A control message inside MLS — never persisted, never shown.
    #[serde(rename = "delivered_signal")]
    DeliveredSignal {
        /// The chat (group) the messages belong to.
        chat_id: String,
        /// The ids of the messages received.
        message_ids: Vec<String>,
    },

    /// Transient presence / activity signal: "typing…" or "recording a
    /// voice message". Like [`ReadSignal`], this is an app-level control
    /// message sent **inside** MLS — never persisted, never shown as a
    /// bubble. The recipient renders a transient indicator and auto-clears
    /// it after a few seconds if no refresh arrives. The sender re-emits
    /// while the activity continues and sends `state = "paused"` when it
    /// stops (or when the message is actually sent).
    #[serde(rename = "chat_state")]
    ChatState {
        /// The chat (group) ID this activity is happening in.
        chat_id: String,
        /// One of: "typing", "recording", "paused".
        state: String,
        /// Unix epoch millis when emitted — receivers use it to expire
        /// stale signals.
        ts: u64,
        /// The channel post whose discussion this happens in (`None` = the chat itself).
        #[serde(default, skip_serializing_if = "Option::is_none")]
        thread: Option<String>,
    },

    /// Profile broadcast: sender's current display_name + bio + avatar.
    ///
    /// Sent through MLS into every chat the sender is a member of whenever
    /// `set_my_profile` is called and a value actually changed. Recipients
    /// update their local `contacts` table keyed by sender npub and refresh
    /// chat UI (bubble headers, chat list rows, chat top bar).
    ///
    /// **Idempotency / replay protection**: `version` is a monotonically
    /// increasing counter per sender. Receivers MUST drop the update if
    /// `version <= contacts.profile_version` already stored. This prevents
    /// stale relays from rolling back the user's name to a previous one.
    ///
    /// **Avatar size cap**: Senders should resize/recompress to ≤200KB so
    /// a single MLS application message can carry the whole payload. Future
    /// iteration may add chunked transfer for larger blobs.
    #[serde(rename = "profile_update")]
    ProfileUpdate {
        /// Monotonic counter — receivers drop if not strictly newer.
        version: u64,
        /// Display name. Empty string clears.
        display_name: String,
        /// Bio / status text. Empty string clears.
        bio: String,
        /// JPEG bytes, base64-encoded for transport. Empty string = no avatar.
        avatar_jpeg_base64: String,
        /// SHA256 hex of the raw JPEG bytes — receivers use this for
        /// dedup / cache-key. Empty if no avatar.
        avatar_hash: String,
        /// Cover/banner JPEG bytes, base64-encoded. Empty = no cover.
        /// `#[serde(default)]` keeps compatibility with pre-cover peers.
        /// DEPRECATED for sending — kept only as a fallback for old peers; new
        /// clients ship images via `avatar_ref`/`cover_ref` (blob pipeline).
        #[serde(default)]
        cover_jpeg_base64: String,
        /// SHA256/BLAKE3 hex of the cover image (dedup/cache-key).
        #[serde(default)]
        cover_hash: String,
        /// MediaRef JSON for the avatar image on the blob/chunk pipeline
        /// ("" = none). Replaces inline base64 — keeps ProfileUpdate tiny.
        #[serde(default)]
        avatar_ref: String,
        /// MediaRef JSON for the cover image on the blob/chunk pipeline.
        #[serde(default)]
        cover_ref: String,
        /// Monotonic per-image versions (receiver ignores non-newer).
        #[serde(default)]
        avatar_version: u64,
        #[serde(default)]
        cover_version: u64,
        /// Hex 32-byte secret of the sender's sealed presence channel ("" = none).
        /// Shared only with contacts over MLS; relays never see it.
        #[serde(default)]
        presence_secret: String,
        /// Private ntfy topic that wakes the sender ("" = none).
        #[serde(default)]
        wake_topic: String,
        /// RFC3339 send timestamp.
        ts: String,
        /// Who this copy is for: "contact" (a direct chat with an accepted
        /// contact) or "member" (anyone else). The sender's privacy settings
        /// shape each copy; of one broadcast the "contact" copy wins over the
        /// "member" one at a receiver that gets both. `None` = older senders.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        scope: Option<String>,
        /// The sender's custom status text and its colour (0xRRGGBBAA) — for
        /// this receiver, by the sender's "status" setting. `None` = not shown.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        status: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        status_color: Option<u32>,
        /// May THIS receiver call the sender (voice / video)? The sender's
        /// phone enforces it either way; this only spares a button that would
        /// not ring. `None` = older senders (offer the call).
        #[serde(default, skip_serializing_if = "Option::is_none")]
        can_call: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        can_video: Option<bool>,
        /// MediaRef JSON of a short looping video avatar ("" = none). Shown
        /// on the profile; lists keep the still avatar. Follows the photo rule.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        video_ref: String,
        /// Monotonic version of the video avatar (a clear bumps it too).
        #[serde(default, skip_serializing_if = "is_zero_u64")]
        video_version: u64,
        /// Relays the sender listens on. Its contacts write there too, so two
        /// people reach each other without sharing a relay. Never published.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        relays: Vec<String>,
    },

    /// Ask the peer to send us their current ProfileUpdate.
    ///
    /// Sent on first encounter (first message from previously-unknown
    /// sender) to pull their profile data even though we missed the
    /// broadcast they sent when joining. The peer responds with a fresh
    /// `ProfileUpdate` into the same chat.
    #[serde(rename = "profile_request")]
    ProfileRequest {
        /// RFC3339 send timestamp.
        ts: String,
    },

    /// Group metadata broadcast: name + description + avatar.
    ///
    /// Sent into a group MLS chat whenever an admin updates the group's
    /// presentation. Every member's realtime listener applies it to their
    /// local `chats` row and refreshes the chat list / top bar UI.
    ///
    /// Same replay-protection model as `ProfileUpdate`: `version` is a
    /// monotonic counter per chat, receivers MUST drop updates with
    /// `version <= chats.metadata_version` already stored.
    #[serde(rename = "group_metadata_update")]
    GroupMetadataUpdate {
        /// Monotonic per-chat counter.
        version: u64,
        /// New group name. Empty string clears.
        name: String,
        /// Optional description / topic.
        description: String,
        /// JPEG bytes of the group avatar, base64-encoded. Empty = no avatar.
        avatar_jpeg_base64: String,
        /// SHA256 hex of the raw avatar bytes — dedup key.
        avatar_hash: String,
        /// The chat's look set by its admins (JSON: theme code, background,
        /// background photo ref). "" = not carried (an older sender) —
        /// receivers keep what they have; "{}" = no look.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        look: String,
        /// Join/leave lines in the chat: "on" | "off"; "" = not carried.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        notices: String,
        /// RFC3339 send timestamp.
        ts: String,
    },

        /// Group role change: an OWNER/ADMIN assigns a new role to a member
    /// (or transfers ownership). App-layer control message — never shown as a
    /// chat bubble. Receivers validate the *sender's* role before applying and
    /// drop stale updates via the monotonic per-chat `version`.
    #[serde(rename = "group_role_update")]
    GroupRoleUpdate {
        /// Monotonic per-chat counter — receivers drop if not strictly newer.
        version: u64,
        /// The affected member's Nostr public key (hex).
        target_pubkey: String,
        /// New role: "owner" | "admin" | "moderator" | "member".
        role: String,
        /// RFC3339 send timestamp.
        ts: String,
    },

        /// Full group role snapshot: the OWNER/ADMIN publishes the complete
    /// role map so members — especially a freshly added one — learn WHO the
    /// owner is and everyone's role. Without this, a joined member doesn't know
    /// the owner and (wrongly) believes the group is owner-less. Sent on member
    /// add. Receivers bootstrap from it if they have no owner yet, else require
    /// the sender to be owner/admin.
    #[serde(rename = "group_role_snapshot")]
    GroupRoleSnapshot {
        /// Monotonic per-chat counter — receivers drop if not strictly newer.
        version: u64,
        /// The owner's Nostr public key (hex).
        owner_pubkey: String,
        /// JSON object {pubkey_hex: "admin"|"moderator"} for non-owner roles.
        roles_json: String,
        /// RFC3339 send timestamp.
        ts: String,
    },

        /// Group mute: an OWNER/ADMIN/MODERATOR mutes (or unmutes) a member.
    /// `until_ts` is a unix-seconds expiry (0 = unmute). App-layer control —
    /// never a chat bubble. Receivers validate the sender's authority and drop
    /// stale updates via the monotonic per-chat `version`.
    #[serde(rename = "group_mute_update")]
    /// A member is banned for good. Sent while they are still in the group,
    /// so they learn why; their phone then keeps nothing of the chat.
    GroupBan {
        /// The banned member's Nostr public key (hex).
        target_pubkey: String,
        /// Shown to the banned member.
        #[serde(default)]
        reason: String,
        /// RFC3339 send timestamp.
        ts: String,
    },

    GroupMuteUpdate {
        /// Monotonic per-chat counter — receivers drop if not strictly newer.
        version: u64,
        /// The affected member's Nostr public key (hex).
        target_pubkey: String,
        /// Unix-seconds expiry; 0 = unmute.
        until_ts: i64,
        /// RFC3339 send timestamp.
        ts: String,
    },

        /// Pull-based role sync: a member that doesn't know the group owner
    /// (just joined / lost state) asks for the current role state. Only the
    /// OWNER answers — once — with a [GroupRoleSnapshot]. This is the ONLY way
    /// a snapshot is ever sent: explicit request → explicit reply. No periodic
    /// or event-driven snapshots (that caused a control-message storm).
    #[serde(rename = "group_role_sync_request")]
    GroupRoleSyncRequest {
        /// RFC3339 send timestamp.
        ts: String,
    },

        /// Group audit-log entry: a record of a moderation action, broadcast
        /// so every member's "Recent actions" log stays in sync. Append-only,
    /// dedup by `id`. Never becomes a chat bubble.
    #[serde(rename = "group_audit_entry")]
    GroupAuditEntry {
        /// Stable unique id (dedup across relays/devices).
        id: String,
        /// Actor's Nostr pubkey (hex) — who performed the action.
        actor_pubkey: String,
        /// Actor's display-name snapshot at action time (so the log shows real
        /// nicks for every admin/owner without any pubkey→name resolution).
        #[serde(default)]
        actor_name: String,
        /// Action kind: "ban"|"unban"|"mute"|"unmute"|"role_admin"|
        /// "role_moderator"|"role_member"|"join"|"leave"|"perms"|"metadata".
        action: String,
        /// Affected member's Nostr pubkey (hex), if any.
        target_pubkey: String,
        /// Human-readable target name snapshot (best-effort).
        target_name: String,
        /// RFC3339 timestamp.
        ts: String,
    },

    /// Group disbanded: the OWNER deleted the whole group. Broadcast by the owner
    /// as their final act (before tearing down their own MLS state). Receivers
        /// tear down their local group, show a "Group deleted by owner" system
        /// notice and lock the composer ("Group unavailable"). Never a chat bubble.
    #[serde(rename = "pin_message")]
    /// Pin / unpin a message of the chat. Shared: every member keeps the same
    /// pinned list. In a group with roles only those allowed to edit the
    /// group's info may pin (checked by every receiver). Never a chat bubble.
    PinMessage {
        /// Id of the pinned (or unpinned) message.
        target_id: String,
        /// true = unpin.
        #[serde(default)]
        unpin: bool,
        /// RFC3339 send timestamp.
        ts: String,
    },

    /// A story for this contact, sent in the 1:1 chat with them (so it is
    /// end-to-end encrypted and only the chosen audience ever receives it).
    /// Never a chat bubble: kept in the stories store until `expires_at`.
    #[serde(rename = "story_post")]
    StoryPost {
        story_id: String,
        /// "image" | "video" | "text".
        kind: String,
        /// Slim media ref (blob + key) for image/video; empty for text.
        #[serde(default)]
        media_ref: String,
        /// Text of a text story.
        #[serde(default)]
        text: String,
        /// Background of a text story ("#RRGGBB,#RRGGBB").
        #[serde(default)]
        bg: String,
        #[serde(default)]
        caption: String,
        /// Unix seconds.
        created_at: i64,
        expires_at: i64,
        #[serde(default)]
        allow_replies: bool,
        #[serde(default)]
        allow_screenshots: bool,
        /// Emoji stickers laid over the story: JSON [{"e","x","y","s"}]
        /// (position as fractions of the screen, s = size factor).
        #[serde(default, skip_serializing_if = "String::is_empty")]
        stickers: String,
        ts: String,
    },

    /// The author removed a story.
    #[serde(rename = "story_delete")]
    StoryDelete { story_id: String, ts: String },

    /// A viewer saw (or reacted to) a story — sent back to its author only.
    #[serde(rename = "story_view")]
    StoryView {
        story_id: String,
        /// Empty = just seen; else the emoji ("-" takes it back).
        #[serde(default)]
        reaction: String,
        /// The viewer took a screenshot (or recorded the screen) of it.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        screenshot: bool,
        ts: String,
    },

    /// The other side deleted our one-to-one chat for both of us: history and all.
    #[serde(rename = "chat_deleted")]
    ChatDeleted {
        /// RFC3339 send timestamp.
        ts: String,
    },

    #[serde(rename = "group_disbanded")]
    GroupDisbanded {
        /// Owner's Nostr pubkey (hex) — who disbanded the group.
        actor_pubkey: String,
        /// RFC3339 send timestamp.
        ts: String,
    },

        /// Group-level permissions: owner-configured policy for who may write,
    /// invite, and edit group info. Client-enforced (serverless), synced like
    /// roles via a monotonic per-chat `version`. Each field is "all" | "admins".
    #[serde(rename = "group_permissions_update")]
    GroupPermissionsUpdate {
        /// Monotonic per-chat counter — receivers drop if not strictly newer.
        version: u64,
        /// Who may send messages: "all" | "admins".
        who_can_write: String,
        /// Who may create invites / add members: "all" | "admins".
        who_can_invite: String,
        /// Who may edit group name/avatar/description: "all" | "admins".
        who_can_edit: String,
            /// Unix-seconds until which the chat is temporarily closed
        /// (only admins can write). 0 = open. Carried alongside permissions
        /// so it reuses the same versioned sync path.
        #[serde(default)]
        closed_until: i64,
        /// Allowed reactions: "all" | "some" | "none"; "" = not carried
        /// (an older sender) — receivers keep what they have.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        reactions_mode: String,
        /// The allowed emoji when `reactions_mode` is "some".
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        reactions: Vec<String>,
        /// Channel settings, JSON `{"on","comments","protect","join"}`;
        /// "" = not carried (an older sender) — receivers keep what they have.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        channel_cfg: String,
        /// RFC3339 send timestamp.
        ts: String,
    },

    /// A channel subscriber has seen these posts (one batch per opening).
    /// Counted by every member as a view; never a chat bubble.
    /// The recipient burned our whisper (see `ExpiryPolicy::Whisper`): how
    /// long they read it, whether they tried to capture the screen, or that it
    /// burned unread. A control message inside MLS — never a bubble.
    #[serde(rename = "poll_vote")]
    /// A vote in a poll (a control message, never a bubble): the voter's whole
    /// current choice; empty = the vote withdrawn. One per voter counts.
    PollVote {
        /// The poll's message id.
        poll_id: String,
        /// Chosen options (indexes).
        #[serde(default)]
        options: Vec<u32>,
        ts: String,
    },

    /// The poll's author (or an admin) closes it; `tally` = their count per
    /// option, so members who joined later see the full result.
    #[serde(rename = "poll_close")]
    PollClose {
        poll_id: String,
        #[serde(default)]
        tally: Vec<u32>,
        ts: String,
    },

    #[serde(rename = "whisper_burned")]
    WhisperBurned {
        /// Our message id (the whisper we sent).
        target_id: String,
        /// Seconds it was read.
        #[serde(default)]
        used_secs: u32,
        /// A screenshot / screen recording was attempted.
        #[serde(default)]
        shot: bool,
        /// Never opened in time.
        #[serde(default)]
        expired: bool,
    },

    /// Earlier posts of a channel for a subscriber who just joined: MLS gives
    /// a newcomer nothing from before. Each item is a post exactly as it was
    /// sent (same id — those who have it drop it as a duplicate) with its
    /// original time. Only the channel's owner or admins may send it.
    #[serde(rename = "channel_history")]
    ChannelHistory {
        items: Vec<HistoryItem>,
        ts: String,
    },

    #[serde(rename = "channel_seen")]
    ChannelSeen {
        post_ids: Vec<String>,
        /// Posts the subscriber forwarded somewhere (counted, never where to).
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        forwarded: Vec<String>,
        ts: String,
    },

        /// E2E delivery acknowledgment (E2E Delivery Layer).
    ///
    /// Sent by a recipient AFTER it successfully decrypted + persisted
    /// message(s) from `sender_id` in `chat_id`. `upto_seq` confirms receipt
    /// of **all** seqs `1..=upto_seq` from that sender (batched ACK — one
    /// event confirms a whole contiguous range, minimising traffic).
    ///
    /// The original sender (whose MLS sig key hex == `sender_id`) uses this to
    /// advance its per-recipient ACK watermark and remove fully-ACKed rows
    /// from its delivery outbox. Other group members ignore it. This control
    /// message does NOT carry its own `seq` — acks are not acked (that would
    /// recurse forever). A lost ACK is subsumed by the recipient's next
    /// batched ACK covering a higher `upto_seq`.
    #[serde(rename = "delivery_ack")]
    DeliveryAck {
        /// The chat (group) ID the ACK applies to.
        chat_id: String,
        /// The MLS signature key hex of the sender whose seqs are being
        /// acknowledged (the original content sender). Receivers compare this
        /// to their own sig key to decide whether the ACK is for them.
        sender_id: String,
        /// Highest contiguous seq received + persisted from `sender_id`.
        /// Confirms 1..=upto_seq.
        upto_seq: u64,
    },

        /// E2E gap-recovery request (E2E Delivery Layer).
    ///
    /// Sent by a recipient that detected a hole in a sender's seq stream
    /// (e.g. received seq 101, 102, 104 → missing 103). The original sender
    /// (whose sig key hex == `sender_id`) pulls `from_seq..=to_seq` from its
    /// delivery outbox and re-publishes them. This makes the log
    /// **self-healing**: even if a relay permanently dropped a message, the
    /// sender re-delivers it on demand. Over-requesting is harmless —
    /// redelivered seqs the recipient already has (<= highest_received) are
    /// deduped and skipped. Does NOT carry its own `seq`.
    #[serde(rename = "resend_request")]
    ResendRequest {
        /// The chat (group) ID the request applies to.
        chat_id: String,
        /// The MLS signature key hex of the sender whose messages to resend.
        sender_id: String,
        /// First missing seq (inclusive).
        from_seq: u64,
        /// Last missing seq (inclusive). `to_seq >= from_seq`.
        to_seq: u64,
    },

        /// # Group transport directory
    ///
        /// The mapping "MLS member → their transport address". Needed for
        /// exactly one thing: addressing a separate NIP-59 envelope to each
        /// member instead of one public event for the whole group.
    ///
        /// **Why it must ride in the payload.** Group membership and transport
        /// addressing live in different planes: MLS knows signing keys and
        /// UUID credentials, addresses are none of its business. A member who
        /// joined via Welcome learns ONLY the inviter's address from the Seal
        /// signature; the others are nowhere to be found, and their very
        /// first send to the group would fail.
    ///
        /// **Where it lives.** Exclusively inside the MLS ciphertext. Not a
        /// single record reaches the outer 1059 event — no signing key, no
        /// address, not even the fact that a directory exists.
    ///
        /// **Who to trust.** Records are accepted only from whoever added the
        /// member and therefore knows the mapping first-hand: they fetched
        /// the KeyPackage by that address. The recipient must cross-check the
        /// directory against the CURRENT group membership: a record for a key
        /// not among members is dropped; two addresses for one key or two
        /// keys for one address — refuse wholesale, no silent skipping.
    ///
        /// Fanned out on membership changes, not with every message.
    /// Setting up a secret chat, inside the ordinary chat with that contact:
    /// `invite` → `accept` (the invitee's persona KeyPackage) → `welcome`
    /// (the MLS Welcome into the secret group), or `decline`; `end`, inside
    /// the secret chat itself, removes it on both phones. Nothing of it
    /// leaves the MLS ciphertext of the ordinary chat.
    #[serde(rename = "secret_chat")]
    SecretChat {
        invite_id: String,
        step: String,
        /// The KeyPackage or Welcome, base64; empty for `invite` / `decline`.
        #[serde(default)]
        data: String,
        ts: String,
    },

    #[serde(rename = "group_directory")]
    GroupDirectory {
                /// Directory schema version. Currently 1; the field exists so a
                /// future format change needs no guessing from the field set.
        version: u32,
                /// Monotonic per-chat counter — receivers drop non-newer ones.
        revision: u64,
                /// Records "signing key (hex) → transport address (hex)".
        entries: Vec<GroupDirectoryEntry>,
                /// RFC3339 send timestamp.
        ts: String,
    },
}

/// One post inside [`AlphaMessage::ChannelHistory`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryItem {
    /// When the post was published (unix seconds).
    pub at: u64,
    /// The post as it was sent: the serialized message, hex.
    pub msg: String,
    /// A comment's author (their sender id) when it is not the sender of the
    /// history itself. Only comments carry it: posts speak as the channel.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from: Option<String>,
}

/// One transport-directory record — see [`AlphaMessage::GroupDirectory`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GroupDirectoryEntry {
        /// The MLS member's signing key, hex. The same identifier used for
        /// the member everywhere else in the code (`sender_id`).
    pub sig_key: String,
        /// The member's transport address, a hex Nostr key.
    pub npub_hex: String,
}

impl AlphaMessage {
    /// Returns the message ID regardless of variant.
    ///
    /// For `ReadSignal`, returns an empty string (it has no ID of its own).
    pub fn id(&self) -> &str {
        match self {
            AlphaMessage::Text { id, .. } => id,
            AlphaMessage::Edit { id, .. } => id,
            AlphaMessage::Reaction { id, .. } => id,
            AlphaMessage::Reply { id, .. } => id,
            AlphaMessage::Delete { id, .. } => id,
            AlphaMessage::Disappearing { id, .. } => id,
            AlphaMessage::Media { id, .. } => id,
            AlphaMessage::ReadSignal { .. } => "",
            AlphaMessage::DeliveredSignal { .. } => "",
            AlphaMessage::ChatState { .. } => "",
            AlphaMessage::ProfileUpdate { .. } => "",
            AlphaMessage::ProfileRequest { .. } => "",
            AlphaMessage::GroupMetadataUpdate { .. } => "",
            AlphaMessage::GroupRoleUpdate { .. } => "",
            AlphaMessage::GroupRoleSnapshot { .. } => "",
            AlphaMessage::GroupDirectory { .. } => "",
            AlphaMessage::SecretChat { .. } => "",
            AlphaMessage::GroupRoleSyncRequest { .. } => "",
            AlphaMessage::GroupMuteUpdate { .. } => "",
            AlphaMessage::GroupBan { .. } => "",
            AlphaMessage::GroupPermissionsUpdate { .. } => "",
            AlphaMessage::GroupAuditEntry { .. } => "",
            AlphaMessage::GroupDisbanded { .. } => "",
            AlphaMessage::ChatDeleted { .. } => "",
            AlphaMessage::PinMessage { .. } => "",
            AlphaMessage::StoryPost { .. } => "",
            AlphaMessage::StoryDelete { .. } => "",
            AlphaMessage::StoryView { .. } => "",
            AlphaMessage::ChannelSeen { .. } => "",
            AlphaMessage::ChannelHistory { .. } => "",
            AlphaMessage::DeliveryAck { .. } => "",
            AlphaMessage::ResendRequest { .. } => "",
            AlphaMessage::WhisperBurned { .. } => "",
            AlphaMessage::PollVote { .. } => "",
            AlphaMessage::PollClose { .. } => "",
        }
    }

    /// Returns a human-readable name for the message type.
    pub fn type_name(&self) -> &'static str {
        match self {
            AlphaMessage::Text { .. } => "text",
            AlphaMessage::Edit { .. } => "edit",
            AlphaMessage::Reaction { .. } => "reaction",
            AlphaMessage::Reply { .. } => "reply",
            AlphaMessage::Delete { .. } => "delete",
            AlphaMessage::Disappearing { .. } => "disappearing",
            AlphaMessage::Media { .. } => "media",
            AlphaMessage::ReadSignal { .. } => "read_signal",
            AlphaMessage::DeliveredSignal { .. } => "delivered_signal",
            AlphaMessage::ChatState { .. } => "chat_state",
            AlphaMessage::ProfileUpdate { .. } => "profile_update",
            AlphaMessage::ProfileRequest { .. } => "profile_request",
            AlphaMessage::GroupMetadataUpdate { .. } => "group_metadata_update",
            AlphaMessage::GroupRoleUpdate { .. } => "group_role_update",
            AlphaMessage::GroupRoleSnapshot { .. } => "group_role_snapshot",
            AlphaMessage::GroupDirectory { .. } => "group_directory",
            AlphaMessage::SecretChat { .. } => "secret_chat",
            AlphaMessage::GroupRoleSyncRequest { .. } => "group_role_sync_request",
            AlphaMessage::GroupMuteUpdate { .. } => "group_mute_update",
            AlphaMessage::GroupBan { .. } => "group_ban",
            AlphaMessage::GroupPermissionsUpdate { .. } => "group_permissions_update",
            AlphaMessage::GroupAuditEntry { .. } => "group_audit_entry",
            AlphaMessage::GroupDisbanded { .. } => "group_disbanded",
            AlphaMessage::ChatDeleted { .. } => "chat_deleted",
            AlphaMessage::PinMessage { .. } => "pin_message",
            AlphaMessage::StoryPost { .. } => "story_post",
            AlphaMessage::StoryDelete { .. } => "story_delete",
            AlphaMessage::StoryView { .. } => "story_view",
            AlphaMessage::ChannelSeen { .. } => "channel_seen",
            AlphaMessage::ChannelHistory { .. } => "channel_history",
            AlphaMessage::DeliveryAck { .. } => "delivery_ack",
            AlphaMessage::ResendRequest { .. } => "resend_request",
            AlphaMessage::WhisperBurned { .. } => "whisper_burned",
            AlphaMessage::PollVote { .. } => "poll_vote",
            AlphaMessage::PollClose { .. } => "poll_close",
        }
    }

    /// Returns `true` if this is a profile/metadata-control message.
    /// Used by event processors to bypass the message-persistence pipeline
    /// — these aren't chat messages, they're metadata signals.
    pub fn is_profile_control(&self) -> bool {
        matches!(
            self,
            AlphaMessage::ProfileUpdate { .. }
                | AlphaMessage::ProfileRequest { .. }
                | AlphaMessage::GroupMetadataUpdate { .. }
        )
    }

    /// Returns the expiry policy, if any, for this message.
    ///
    /// Only `Text` and `Media` messages can carry an expiry policy.
    /// The legacy `Disappearing` variant is mapped to `AfterSend`.
    pub fn expiry(&self) -> Option<ExpiryPolicy> {
        match self {
            AlphaMessage::Text { expiry, .. } => expiry.clone(),
            AlphaMessage::Media { expiry, .. } => expiry.clone(),
            // The chat timer counts from reading, on both sides: the reader's
            // receipt starts the sender's copy too.
            AlphaMessage::Disappearing { ttl_seconds, .. } => {
                Some(ExpiryPolicy::AfterRead {
                    ttl_secs: *ttl_seconds,
                })
            }
            _ => None,
        }
    }

    /// Returns `true` if this message has an ephemeral expiry policy.
    pub fn is_ephemeral(&self) -> bool {
        self.expiry().map_or(false, |p| p.is_ephemeral())
    }

    /// Returns `true` if this message is a ViewOnce message.
    pub fn is_view_once(&self) -> bool {
        matches!(
            self.expiry(),
            Some(ExpiryPolicy::ViewOnce)
        )
    }

    /// Returns `true` if this is a ReadSignal control message.
    pub fn is_read_signal(&self) -> bool {
        matches!(self, AlphaMessage::ReadSignal { .. })
    }

    /// Returns `true` if this is a ChatState (typing/recording) signal.
    pub fn is_chat_state(&self) -> bool {
        matches!(self, AlphaMessage::ChatState { .. })
    }

        /// Returns `true` if this is a group role-control message. Like the
    /// profile-control messages it mutates local state (the role KV) but never
    /// becomes a chat bubble.
    pub fn is_role_control(&self) -> bool {
        matches!(
            self,
            AlphaMessage::GroupRoleUpdate { .. }
                | AlphaMessage::GroupRoleSnapshot { .. }
                | AlphaMessage::GroupRoleSyncRequest { .. }
        )
    }

        /// Returns `true` if this is a group mute-control message.
    pub fn is_mute_control(&self) -> bool {
        matches!(self, AlphaMessage::GroupMuteUpdate { .. })
    }

    /// Returns `true` if this is a ban of a member.
    pub fn is_ban_control(&self) -> bool {
        matches!(self, AlphaMessage::GroupBan { .. })
    }

        /// Returns `true` if this is a group-permissions control message.
    pub fn is_permissions_control(&self) -> bool {
        matches!(self, AlphaMessage::GroupPermissionsUpdate { .. })
    }

        /// Returns `true` if this is a group audit-log entry.
    pub fn is_audit_entry(&self) -> bool {
        matches!(self, AlphaMessage::GroupAuditEntry { .. })
    }

    /// Returns `true` if this is a group-disbanded control message (owner
    /// deleted the group). Handled specially: tear down local group + notice.
    pub fn is_disband_control(&self) -> bool {
        matches!(self, AlphaMessage::GroupDisbanded { .. })
    }

    /// The peer deleted our 1:1 for both sides.
    pub fn is_chat_deleted_control(&self) -> bool {
        matches!(self, AlphaMessage::ChatDeleted { .. })
    }

    /// Returns `true` for story traffic (post / delete / view).
    pub fn is_story_control(&self) -> bool {
        matches!(self, AlphaMessage::StoryPost { .. } | AlphaMessage::StoryDelete { .. } | AlphaMessage::StoryView { .. })
    }

    /// Returns `true` for a batch of earlier channel posts.
    pub fn is_channel_history(&self) -> bool {
        matches!(self, AlphaMessage::ChannelHistory { .. })
    }

    /// Returns `true` for a channel view batch.
    pub fn is_channel_seen(&self) -> bool {
        matches!(self, AlphaMessage::ChannelSeen { .. })
    }

    /// A comment under a channel post.
    pub fn is_channel_comment(&self) -> bool {
        self.comment_post_id().is_some()
    }

    /// The comment thread this message belongs to (`thread` field).
    pub fn thread(&self) -> Option<&str> {
        match self {
            AlphaMessage::Text { thread, .. }
            | AlphaMessage::Reply { thread, .. }
            | AlphaMessage::Media { thread, .. } => thread.as_deref().filter(|t| !t.is_empty()),
            _ => None,
        }
    }

    /// The same message placed in comment thread `thread` (only text, reply
    /// and media can be comments; anything else is returned unchanged).
    pub fn in_thread(mut self, thread: Option<String>) -> Self {
        let t = thread.filter(|t| !t.is_empty());
        match &mut self {
            AlphaMessage::Text { thread, .. }
            | AlphaMessage::Reply { thread, .. }
            | AlphaMessage::Media { thread, .. } => *thread = t,
            _ => {}
        }
        self
    }

    /// Id of the post this comment is under: the typed `thread`, or the
    /// header of a comment written by an older version ([COMMENT_MARK]).
    pub fn comment_post_id(&self) -> Option<String> {
        if let Some(t) = self.thread() { return Some(t.to_string()); }
        legacy_comment(self.text_part()?).map(|(post, _)| post)
    }

    /// The words of a message (text / reply body, media caption).
    fn text_part(&self) -> Option<&str> {
        match self {
            AlphaMessage::Text { body, .. } | AlphaMessage::Reply { body, .. } => Some(body),
            AlphaMessage::Media { caption, .. } => caption.as_deref(),
            _ => None,
        }
    }

    /// What a comment carries, for the channel's "allowed in comments" rule:
    /// "text" | "link" | "photo" | "video" | "voice" | "file".
    pub fn comment_kind(&self) -> &'static str {
        match self {
            AlphaMessage::Media { media_type, filename, .. } => match media_type.as_str() {
                "image" | "photo" | "album" => "photo",
                "video" => "video",
                // A recorded voice note travels as "audio" named "voice_…";
                // other audio (music) is a file.
                "voice" => "voice",
                "audio" if filename.starts_with("voice") => "voice",
                "link" => "link",
                _ => "file",
            },
            _ => {
                let body = self.text_part().map(strip_legacy_comment).unwrap_or("");
                if body.contains("http://") || body.contains("https://") || body.contains("www.") { "link" } else { "text" }
            }
        }
    }

    /// Returns `true` if this pins/unpins a message (shared pinned list).
    pub fn is_pin_control(&self) -> bool {
        matches!(self, AlphaMessage::PinMessage { .. })
    }

        // --- Delivery Layer (E2E Delivery) ---

    /// Returns `true` if this is a delivery-layer control message
    /// (`DeliveryAck` or `ResendRequest`). These bypass the normal
    /// message-persistence pipeline.
    pub fn is_delivery_control(&self) -> bool {
        matches!(
            self,
            AlphaMessage::DeliveryAck { .. } | AlphaMessage::ResendRequest { .. }
        )
    }

    /// Returns the delivery-layer `seq` for content-bearing messages, or
    /// `None` for control/unsequenced messages. `seq == 0` is treated as
    /// `None` so legacy messages are skipped by the delivery layer.
    pub fn seq(&self) -> Option<u64> {
        let s = match self {
            AlphaMessage::Text { seq, .. }
            | AlphaMessage::Edit { seq, .. }
            | AlphaMessage::Reaction { seq, .. }
            | AlphaMessage::Reply { seq, .. }
            | AlphaMessage::Delete { seq, .. }
            | AlphaMessage::Disappearing { seq, .. }
            | AlphaMessage::Media { seq, .. } => *seq,
            _ => return None,
        };
        if s == 0 { None } else { Some(s) }
    }

    /// Replace the user-visible text (body, or the caption of media). Used to
    /// edit a scheduled message before it is sent. Returns false when the
    /// message kind has no text.
    pub fn set_user_text(&mut self, text: &str) -> bool {
        match self {
            AlphaMessage::Text { body, .. }
            | AlphaMessage::Reply { body, .. }
            | AlphaMessage::Disappearing { body, .. } => { *body = text.to_string(); true }
            AlphaMessage::Media { caption, .. } => {
                *caption = if text.is_empty() { None } else { Some(text.to_string()) };
                true
            }
            _ => false,
        }
    }

    /// A message the user writes (not an edit/reaction/delete/control, and
    /// not a service notice carried as text — timer change, call log: bodies
    /// that start with the \u{2} mark).
    pub fn is_user_content(&self) -> bool {
        match self {
            AlphaMessage::Text { body, .. } | AlphaMessage::Disappearing { body, .. } => !body.starts_with('\u{2}'),
            AlphaMessage::Reply { .. } | AlphaMessage::Media { .. } => true,
            _ => false,
        }
    }

    /// Sender's send time in ms, 0 when absent or not a content message.
    pub fn sent_ms(&self) -> u64 {
        match self {
            AlphaMessage::Text { sent_ms, .. }
            | AlphaMessage::Reply { sent_ms, .. }
            | AlphaMessage::Disappearing { sent_ms, .. }
            | AlphaMessage::Media { sent_ms, .. } => *sent_ms,
            _ => 0,
        }
    }

    pub fn set_sent_ms(&mut self, ms: u64) {
        match self {
            AlphaMessage::Text { sent_ms, .. }
            | AlphaMessage::Reply { sent_ms, .. }
            | AlphaMessage::Disappearing { sent_ms, .. }
            | AlphaMessage::Media { sent_ms, .. } => *sent_ms = ms,
            _ => {}
        }
    }

    /// Sent without sound (content messages only).
    pub fn silent(&self) -> bool {
        match self {
            AlphaMessage::Text { silent, .. }
            | AlphaMessage::Reply { silent, .. }
            | AlphaMessage::Disappearing { silent, .. }
            | AlphaMessage::Media { silent, .. } => *silent,
            _ => false,
        }
    }

    pub fn set_silent(&mut self, on: bool) {
        match self {
            AlphaMessage::Text { silent, .. }
            | AlphaMessage::Reply { silent, .. }
            | AlphaMessage::Disappearing { silent, .. }
            | AlphaMessage::Media { silent, .. } => *silent = on,
            _ => {}
        }
    }

    /// Set the delivery-layer `seq` on a content-bearing message. No-op for
    /// control messages.
    pub fn set_seq(&mut self, new_seq: u64) {
        match self {
            AlphaMessage::Text { seq, .. }
            | AlphaMessage::Edit { seq, .. }
            | AlphaMessage::Reaction { seq, .. }
            | AlphaMessage::Reply { seq, .. }
            | AlphaMessage::Delete { seq, .. }
            | AlphaMessage::Disappearing { seq, .. }
            | AlphaMessage::Media { seq, .. } => *seq = new_seq,
            _ => {}
        }
    }

    /// Returns `true` if this message is content-bearing (eligible for the
    /// delivery layer: seq allocation, outbox, ACK tracking).
    pub fn is_delivery_eligible(&self) -> bool {
        matches!(
            self,
            AlphaMessage::Text { .. }
                | AlphaMessage::Edit { .. }
                | AlphaMessage::Reaction { .. }
                | AlphaMessage::Reply { .. }
                | AlphaMessage::Delete { .. }
                | AlphaMessage::Disappearing { .. }
                | AlphaMessage::Media { .. }
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn silent_is_optional_on_the_wire() {
        let old = r#"{"type":"media","id":"a","media_ref":"r","media_type":"image","filename":"f","size_bytes":1}"#;
        let mut msg: AlphaMessage = serde_json::from_str(old).expect("old shape still reads");
        assert!(!msg.silent());
        assert!(!serde_json::to_string(&msg).unwrap().contains("silent"));
        msg.set_silent(true);
        let wire = serde_json::to_string(&msg).unwrap();
        assert!(wire.contains("\"silent\":true"));
        let back: AlphaMessage = serde_json::from_str(&wire).unwrap();
        assert!(back.silent());
    }

    #[test]
    fn sent_ms_is_optional_on_the_wire() {
        let old = r#"{"type":"text","id":"a","body":"hi"}"#;
        let mut msg: AlphaMessage = serde_json::from_str(old).expect("old shape still reads");
        assert_eq!(msg.sent_ms(), 0);
        assert!(!serde_json::to_string(&msg).unwrap().contains("\"ms\""));

        msg.set_sent_ms(1_760_000_000_123);
        let back: AlphaMessage = serde_json::from_str(&serde_json::to_string(&msg).unwrap()).unwrap();
        assert_eq!(back.sent_ms(), 1_760_000_000_123);
    }

    #[test]
    fn test_text_message_serialization() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            body: "Hello, world!".to_string(),
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"type\":\"text\""));
        assert!(json.contains("\"body\":\"Hello, world!\""));
        assert!(!json.contains("reply_to")); // skip_serializing_if
        assert!(!json.contains("expiry")); // skip_serializing_if

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_text_message_with_reply_to() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            body: "Replying...".to_string(),
            reply_to: Some("660e8400-e29b-41d4-a716-446655440001".to_string()),
            expiry: None,
            seq: 0,
            thread: None,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("reply_to"));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_text_message_with_expiry_after_send() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            body: "This will vanish!".to_string(),
            reply_to: None,
            expiry: Some(ExpiryPolicy::AfterSend { ttl_secs: 300 }),
            seq: 0,
            thread: None,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"expiry\""));
        assert!(json.contains("\"mode\":\"after_send\""));
        assert!(json.contains("\"ttl_secs\":300"));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
        assert!(deserialized.is_ephemeral());
    }

    #[test]
    fn test_text_message_with_expiry_after_read() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            body: "Read then gone".to_string(),
            reply_to: None,
            expiry: Some(ExpiryPolicy::AfterRead { ttl_secs: 60 }),
            seq: 0,
            thread: None,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"mode\":\"after_read\""));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
        assert!(deserialized.is_ephemeral());
    }

    #[test]
    fn test_text_message_view_once() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            body: "One time only".to_string(),
            reply_to: None,
            expiry: Some(ExpiryPolicy::ViewOnce),
            seq: 0,
            thread: None,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        // Round-trip test — ViewOnce is a unit variant in internally-tagged enum
        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
        assert!(deserialized.is_view_once());
    }

    #[test]
    fn test_media_message_with_expiry() {
        let msg = AlphaMessage::Media { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            media_ref: "sha256:abc123".to_string(),
            media_type: "image".to_string(),
            filename: "photo.png".to_string(),
            size_bytes: 1024,
            caption: None,
            reply_to: None,
            expiry: Some(ExpiryPolicy::ViewOnce),
            seq: 0,
            thread: None,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        // Round-trip test
        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert!(deserialized.is_view_once());
    }

    #[test]
    fn test_read_signal_serialization() {
        let msg = AlphaMessage::ReadSignal {
            target_id: "660e8400-e29b-41d4-a716-446655440001".to_string(),
            chat_id: "aabbccdd".to_string(),
            read_at: "2025-01-15T12:30:00Z".to_string(),
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"type\":\"read_signal\""));
        assert!(json.contains("\"target_id\""));
        assert!(json.contains("\"read_at\""));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
        assert!(deserialized.is_read_signal());
    }

    #[test]
    fn test_reaction_message() {
        let msg = AlphaMessage::Reaction {
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            target_id: "660e8400-e29b-41d4-a716-446655440001".to_string(),
            emoji: "👍".to_string(),
            remove: None,
            seq: 0,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"type\":\"reaction\""));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_reaction_remove() {
        let msg = AlphaMessage::Reaction {
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            target_id: "660e8400-e29b-41d4-a716-446655440001".to_string(),
            emoji: "👍".to_string(),
            remove: Some(true),
            seq: 0,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"remove\":true"));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_disappearing_message() {
        let msg = AlphaMessage::Disappearing { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            body: "This will vanish!".to_string(),
            ttl_seconds: 300,
            seq: 0,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"ttl_seconds\":300"));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
        // The chat timer counts from reading.
        assert!(matches!(
            deserialized.expiry(),
            Some(ExpiryPolicy::AfterRead { ttl_secs: 300 })
        ));
    }

    #[test]
    fn test_media_message() {
        let msg = AlphaMessage::Media { sent_ms: 0, silent: false,
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            media_ref: "sha256:abc123".to_string(),
            media_type: "image".to_string(),
            filename: "photo.png".to_string(),
            size_bytes: 1024,
            caption: Some("A nice photo".to_string()),
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"type\":\"media\""));
        assert!(json.contains("caption"));
        assert!(!json.contains("reply_to"));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_delete_message() {
        let msg = AlphaMessage::Delete {
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            target_id: "660e8400-e29b-41d4-a716-446655440001".to_string(),
            seq: 0,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"type\":\"delete\""));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_edit_message() {
        let msg = AlphaMessage::Edit {
            id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
            body: "Edited text".to_string(),
            edited_at: "2025-01-15T12:00:00Z".to_string(),
            seq: 0,
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(json.contains("\"type\":\"edit\""));

        let deserialized: AlphaMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, deserialized);
    }

    #[test]
    fn test_type_name() {
        assert_eq!(
            AlphaMessage::Text { sent_ms: 0, silent: false,
                id: "a".into(),
                body: "b".into(),
                reply_to: None,
                expiry: None,
                seq: 0,
                thread: None,
            }
            .type_name(),
            "text"
        );
        assert_eq!(
            AlphaMessage::Edit {
                id: "a".into(),
                body: "b".into(),
                edited_at: "c".into(),
                seq: 0,
            }
            .type_name(),
            "edit"
        );
        assert_eq!(
            AlphaMessage::Reaction {
                id: "a".into(),
                target_id: "b".into(),
                emoji: "c".into(),
                remove: None,
                seq: 0,
            }
            .type_name(),
            "reaction"
        );
        assert_eq!(
            AlphaMessage::ReadSignal {
                target_id: "a".into(),
                chat_id: "b".into(),
                read_at: "c".into()
            }
            .type_name(),
            "read_signal"
        );
    }

    #[test]
    fn a_voice_note_is_a_voice_comment_and_music_a_file() {
        let voice = crate::MessageBuilder::media("{}", "audio", "voice_1700000000.ogg", 10).build();
        let music = crate::MessageBuilder::media("{}", "audio", "song.mp3", 10).build();
        assert_eq!(voice.comment_kind(), "voice");
        assert_eq!(music.comment_kind(), "file");
    }

    #[test]
    fn test_id_accessor() {
        let msg = AlphaMessage::Text { sent_ms: 0, silent: false,
            id: "test-id-123".to_string(),
            body: "hello".to_string(),
            reply_to: None,
            expiry: None,
            seq: 0,
            thread: None,
        };
        assert_eq!(msg.id(), "test-id-123");
    }

    #[test]
    fn test_read_signal_has_empty_id() {
        let msg = AlphaMessage::ReadSignal {
            target_id: "abc".into(),
            chat_id: "def".into(),
            read_at: "2025-01-01T00:00:00Z".into(),
        };
        assert_eq!(msg.id(), "");
    }
}

/// Prefix of a comment written by versions before the typed `thread` field:
/// the mark, a JSON header line `{"p": post id, "n": author name}`, then the
/// comment text. Read only — new comments carry `thread`.
pub const COMMENT_MARK: &str = "\u{2}CMT\u{2}";

/// `(post id, words)` of a text written in the old comment format.
pub fn legacy_comment(text: &str) -> Option<(String, &str)> {
    let rest = text.strip_prefix(COMMENT_MARK)?;
    // A photo comment without words has no line after the header.
    let (header, body) = match rest.find('\n') {
        Some(i) => (&rest[..i], &rest[i + 1..]),
        None => (rest, ""),
    };
    let post = serde_json::from_str::<serde_json::Value>(header).ok()?
        .get("p")?.as_str().filter(|p| !p.is_empty())?.to_string();
    Some((post, body))
}

/// `text` without an old-format comment header (unchanged otherwise).
pub fn strip_legacy_comment(text: &str) -> &str {
    legacy_comment(text).map(|(_, body)| body).unwrap_or(text)
}
