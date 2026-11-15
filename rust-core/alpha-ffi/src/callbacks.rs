//! Callback trait and management for FFI events.
//!
//! Defines the `AlphaMessengerCallback` trait that matches the UniFFI
//! UDL callback interface, and the `CallbackDispatcher` that routes
//! events to registered callbacks in a thread-safe manner.

use std::sync::Arc;
use std::sync::Mutex;

use crate::events::CoreEvent;

/// Callback interface for receiving events from the Rust core.
///
/// This trait matches the `AlphaMessengerCallback` interface defined
/// in the UDL file.  Kotlin/Swift implementations will be generated
/// by UniFFI.
pub trait AlphaMessengerCallback: Send + Sync {
    /// A new message was received in a chat.
    fn on_message_received(&self, chat_id: String, message_json: String);

    /// A message's delivery status was updated.
    fn on_delivery_update(&self, message_id: String, status: String);

    /// A sync catch-up completed with `new_message_count` new messages.
    fn on_sync_complete(&self, new_message_count: u64);

    /// A new chat was created.
    fn on_chat_created(&self, chat_id: String, chat_type: String);

    /// Our phones: "inbox" (notes or requests wait: call `process_device_inbox`),
    /// "devices" (the list changed), "unlinked" (this phone was unlinked).
    fn on_devices_event(&self, kind: String);

    /// A member was added to a chat.
    fn on_member_added(&self, chat_id: String, member_id: String);

    /// A member was removed from a chat.
    fn on_member_removed(&self, chat_id: String, member_id: String);

    /// A push notification was received (wake signal).
    fn on_notification_received(&self);

    /// A peer's profile was updated (display_name / bio / avatar changed).
    /// `sender_id` is the MLS leaf-signature hex id we use as the contact
    /// row's primary key. UI listeners should re-read the affected
    /// `PeerProfile` via `get_peer_profile(sender_id)` and refresh bubbles,
    /// chat list rows, chat top bar etc.
    fn on_peer_profile_updated(&self, sender_id: String);

    /// A chat's metadata (group name / description / avatar) was changed
    /// by an admin and applied locally. UI listeners should reload the
    /// chat row from the DB to pick up the new values.
    fn on_chat_metadata_updated(&self, chat_id: String);

    /// A peer started/stopped typing or recording a voice message in a
    /// chat. `state` is "typing", "recording" or "paused". Transient — UI
    /// shows a live indicator and auto-clears it after a short timeout.
    /// `thread`: the channel post whose discussion it is in ("" = the chat itself).
    fn on_chat_state(&self, chat_id: String, sender_id: String, state: String, thread: String, age_ms: u64);

    /// Media download progress. `op_id` is the caller token (message id),
    /// `downloaded` / `total` are byte counts.
    fn on_media_progress(&self, op_id: String, downloaded: u64, total: u64);

    /// G-7: someone wants to join `chat_id` via an invite link. `requester_npub`
    /// is their bech32 pubkey, `token` the invite token. The Kotlin side checks
    /// the local ban list and, if allowed, calls `approve_join_request`.
    fn on_join_requested(&self, chat_id: String, requester_npub: String, token: String);

    /// A member announced they're leaving. An OWNER/ADMIN should perform the
    /// real MLS removal so they drop out for everyone. `leaver_npub` is bech32.
    fn on_member_leave_requested(&self, chat_id: String, leaver_npub: String);

    /// Relay connection status changed. `relay_url` is the relay URL,
    /// `status` is "connected" or "disconnected". UI should update
    /// relay status indicators accordingly (green = connected, yellow = disconnected).
    fn on_relay_status_change(&self, relay_url: String, status: String);

    /// How far the receiving side is: `connected` live relays out of the
    /// `relays` enabled ones, and whether a catch-up is running.
    fn on_sync_state(&self, connected: u32, relays: u32, catching_up: bool, link: String);

    /// BLOCK K — Unified Event Dispatcher. A COALESCED BATCH of high-frequency
    /// events (media progress, typing/recording, delivery updates) delivered in
    /// ONE FFI call instead of hundreds. Kotlin just fans each out to the same
    /// SharedFlows it already has — it must NOT deduplicate (Rust already did).
    fn on_events_batch(&self, events: Vec<CoreEvent>);
}

/// Thread-safe dispatcher that routes events to the currently
/// registered callback.
///
/// Only one callback can be active at a time.  Setting a new
/// callback replaces the previous one.
pub struct CallbackDispatcher {
    /// The currently registered callback, if any.
    callback: Arc<Mutex<Option<Arc<Box<dyn AlphaMessengerCallback>>>>>,
}

impl CallbackDispatcher {
    /// Create a new dispatcher with no registered callback.
    pub fn new() -> Self {
        Self {
            callback: Arc::new(Mutex::new(None)),
        }
    }

    /// Register (or replace) the callback.
    pub fn set_callback(&self, cb: Box<dyn AlphaMessengerCallback>) {
        let mut guard = self.callback.lock().unwrap_or_else(|p| p.into_inner());
        *guard = Some(Arc::new(cb));
    }

    /// The registered callback, taken out of the lock: the call into the app
    /// runs with no lock held, so it may call back into the core, and a panic
    /// in it cannot poison the dispatcher for every later event.
    fn current(&self) -> Option<Arc<Box<dyn AlphaMessengerCallback>>> {
        self.callback.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    /// Fire `on_message_received` on the registered callback.
    pub fn on_message_received(&self, chat_id: &str, message_json: &str) {
        if let Some(cb) = self.current() {
            cb.on_message_received(chat_id.to_string(), message_json.to_string());
        }
    }

    /// Fire `on_delivery_update` on the registered callback.
    pub fn on_delivery_update(&self, message_id: &str, status: &str) {
        if let Some(cb) = self.current() {
            cb.on_delivery_update(message_id.to_string(), status.to_string());
        }
    }

    /// Fire `on_sync_complete` on the registered callback.
    pub fn on_sync_complete(&self, new_message_count: u64) {
        if let Some(cb) = self.current() {
            cb.on_sync_complete(new_message_count);
        }
    }

    pub fn on_devices_event(&self, kind: &str) {
        if let Some(cb) = self.current() {
            cb.on_devices_event(kind.to_string());
        }
    }

    /// Fire `on_chat_created` on the registered callback.
    pub fn on_chat_created(&self, chat_id: &str, chat_type: &str) {
        if let Some(cb) = self.current() {
            cb.on_chat_created(chat_id.to_string(), chat_type.to_string());
        }
    }

    /// Fire `on_member_added` on the registered callback.
    pub fn on_member_added(&self, chat_id: &str, member_id: &str) {
        if let Some(cb) = self.current() {
            cb.on_member_added(chat_id.to_string(), member_id.to_string());
        }
    }

    /// Fire `on_member_removed` on the registered callback.
    pub fn on_member_removed(&self, chat_id: &str, member_id: &str) {
        if let Some(cb) = self.current() {
            cb.on_member_removed(chat_id.to_string(), member_id.to_string());
        }
    }

    /// Fire `on_notification_received` on the registered callback.
    pub fn on_notification_received(&self) {
        if let Some(cb) = self.current() {
            cb.on_notification_received();
        }
    }

    /// Fire `on_peer_profile_updated` on the registered callback.
    pub fn on_peer_profile_updated(&self, sender_id: &str) {
        if let Some(cb) = self.current() {
            cb.on_peer_profile_updated(sender_id.to_string());
        }
    }

    /// Fire `on_chat_metadata_updated` on the registered callback.
    pub fn on_chat_metadata_updated(&self, chat_id: &str) {
        if let Some(cb) = self.current() {
            cb.on_chat_metadata_updated(chat_id.to_string());
        }
    }

    /// Fire `on_chat_state` on the registered callback.
    pub fn on_chat_state(&self, chat_id: &str, sender_id: &str, state: &str, thread: &str, age_ms: u64) {
        if let Some(cb) = self.current() {
            cb.on_chat_state(chat_id.to_string(), sender_id.to_string(), state.to_string(), thread.to_string(), age_ms);
        }
    }

    /// Fire `on_media_progress` on the registered callback.
    pub fn on_media_progress(&self, op_id: &str, downloaded: u64, total: u64) {
        if let Some(cb) = self.current() {
            cb.on_media_progress(op_id.to_string(), downloaded, total);
        }
    }

    /// Fire `on_join_requested` on the registered callback.
    pub fn on_join_requested(&self, chat_id: &str, requester_npub: &str, token: &str) {
        if let Some(cb) = self.current() {
            cb.on_join_requested(chat_id.to_string(), requester_npub.to_string(), token.to_string());
        }
    }

    /// Fire `on_member_leave_requested` on the registered callback.
    pub fn on_member_leave_requested(&self, chat_id: &str, leaver_npub: &str) {
        if let Some(cb) = self.current() {
            cb.on_member_leave_requested(chat_id.to_string(), leaver_npub.to_string());
        }
    }

    /// Fire `on_relay_status_change` on the registered callback.
    pub fn on_relay_status_change(&self, relay_url: &str, status: &str) {
        if let Some(cb) = self.current() {
            cb.on_relay_status_change(relay_url.to_string(), status.to_string());
        }
    }

    /// Fire `on_sync_state` on the registered callback.
    pub fn on_sync_state(&self, connected: u32, relays: u32, catching_up: bool, link: String) {
        if let Some(cb) = self.current() {
            cb.on_sync_state(connected, relays, catching_up, link);
        }
    }

    /// BLOCK K — deliver one coalesced batch of events. Called by the
    /// [`crate::event_dispatcher::UnifiedEventDispatcher`] flush; never per-event.
    pub fn on_events_batch(&self, events: Vec<CoreEvent>) {
        if let Some(cb) = self.current() {
            cb.on_events_batch(events);
        }
    }

}

impl Default for CallbackDispatcher {
    fn default() -> Self {
        Self::new()
    }
}

impl Clone for CallbackDispatcher {
    fn clone(&self) -> Self {
        Self {
            callback: Arc::clone(&self.callback),
        }
    }
}

// ── Tests ──────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// A test callback that counts invocations of each method.
    struct CountingCallback {
        messages: AtomicUsize,
        deliveries: AtomicUsize,
        sync_completions: AtomicUsize,
        chats_created: AtomicUsize,
        members_added: AtomicUsize,
        members_removed: AtomicUsize,
        notifications: AtomicUsize,
        relay_status_changes: AtomicUsize,
    }

    impl CountingCallback {
        fn new() -> Self {
            Self {
                messages: AtomicUsize::new(0),
                deliveries: AtomicUsize::new(0),
                sync_completions: AtomicUsize::new(0),
                chats_created: AtomicUsize::new(0),
                members_added: AtomicUsize::new(0),
                members_removed: AtomicUsize::new(0),
                notifications: AtomicUsize::new(0),
                relay_status_changes: AtomicUsize::new(0),
            }
        }
    }

    impl AlphaMessengerCallback for CountingCallback {
        fn on_message_received(&self, _chat_id: String, _message_json: String) {
            self.messages.fetch_add(1, Ordering::SeqCst);
        }
        fn on_delivery_update(&self, _message_id: String, _status: String) {
            self.deliveries.fetch_add(1, Ordering::SeqCst);
        }
        fn on_sync_complete(&self, _new_message_count: u64) {
            self.sync_completions.fetch_add(1, Ordering::SeqCst);
        }
        fn on_devices_event(&self, _kind: String) {}
        fn on_chat_created(&self, _chat_id: String, _chat_type: String) {
            self.chats_created.fetch_add(1, Ordering::SeqCst);
        }
        fn on_member_added(&self, _chat_id: String, _member_id: String) {
            self.members_added.fetch_add(1, Ordering::SeqCst);
        }
        fn on_member_removed(&self, _chat_id: String, _member_id: String) {
            self.members_removed.fetch_add(1, Ordering::SeqCst);
        }
        fn on_notification_received(&self) {
            self.notifications.fetch_add(1, Ordering::SeqCst);
        }
        fn on_peer_profile_updated(&self, _sender_id: String) {
            // Test stub.
        }
        fn on_chat_metadata_updated(&self, _chat_id: String) {
            // Test stub.
        }
        fn on_chat_state(&self, _chat_id: String, _sender_id: String, _state: String, _thread: String, _age_ms: u64) {
            // Test stub.
        }
        fn on_media_progress(&self, _op_id: String, _downloaded: u64, _total: u64) {
            // Test stub.
        }
        fn on_join_requested(&self, _chat_id: String, _requester_npub: String, _token: String) {
            // Test stub.
        }
        fn on_member_leave_requested(&self, _chat_id: String, _leaver_npub: String) {
            // Test stub.
        }
        fn on_sync_state(&self, _connected: u32, _relays: u32, _catching_up: bool, _link: String) {}
        fn on_relay_status_change(&self, _relay_url: String, _status: String) {
            self.relay_status_changes.fetch_add(1, Ordering::SeqCst);
        }
        fn on_events_batch(&self, _events: Vec<CoreEvent>) {
            // Test stub.
        }
    }

    #[test]
    fn test_dispatcher_no_callback_no_panic() {
        let dispatcher = CallbackDispatcher::new();
        // None of these should panic even without a callback set
        dispatcher.on_message_received("chat1", "{}");
        dispatcher.on_delivery_update("msg1", "sent");
        dispatcher.on_sync_complete(5);
        dispatcher.on_chat_created("chat1", "direct");
        dispatcher.on_member_added("chat1", "member1");
        dispatcher.on_member_removed("chat1", "member1");
        dispatcher.on_notification_received();
    }

    #[test]
    fn test_dispatcher_on_message_received() {
        let dispatcher = CallbackDispatcher::new();
        let cb = Box::new(CountingCallback::new());
        let cb_ref = cb.as_ref() as *const CountingCallback;

        dispatcher.set_callback(cb);

        dispatcher.on_message_received("chat1", r#"{"body":"hello"}"#);
        dispatcher.on_message_received("chat2", r#"{"body":"world"}"#);

        // Safety: cb_ref is valid as long as dispatcher holds the callback
        let count = unsafe { &*cb_ref }.messages.load(Ordering::SeqCst);
        assert_eq!(count, 2, "on_message_received should be called twice");
    }

    #[test]
    fn test_dispatcher_on_chat_created() {
        let dispatcher = CallbackDispatcher::new();
        let cb = Box::new(CountingCallback::new());
        let cb_ref = cb.as_ref() as *const CountingCallback;

        dispatcher.set_callback(cb);

        dispatcher.on_chat_created("chat1", "group");
        dispatcher.on_chat_created("chat2", "direct");
        dispatcher.on_chat_created("chat3", "group");

        let count = unsafe { &*cb_ref }.chats_created.load(Ordering::SeqCst);
        assert_eq!(count, 3, "on_chat_created should be called 3 times");
    }

    #[test]
    fn test_dispatcher_replace_callback() {
        let dispatcher = CallbackDispatcher::new();

        let cb1 = Box::new(CountingCallback::new());
        let cb1_ref = cb1.as_ref() as *const CountingCallback;
        dispatcher.set_callback(cb1);

        dispatcher.on_chat_created("chat1", "group");

        let cb2 = Box::new(CountingCallback::new());
        let cb2_ref = cb2.as_ref() as *const CountingCallback;
        dispatcher.set_callback(cb2);

        dispatcher.on_chat_created("chat2", "direct");

        let count1 = unsafe { &*cb1_ref }.chats_created.load(Ordering::SeqCst);
        let count2 = unsafe { &*cb2_ref }.chats_created.load(Ordering::SeqCst);

        assert_eq!(count1, 1, "first callback should have 1 event");
        assert_eq!(count2, 1, "second callback should have 1 event");
    }

    #[test]
    fn test_dispatcher_all_callbacks() {
        let dispatcher = CallbackDispatcher::new();
        let cb = Box::new(CountingCallback::new());
        let cb_ref = cb.as_ref() as *const CountingCallback;

        dispatcher.set_callback(cb);

        dispatcher.on_message_received("c1", "{}");
        dispatcher.on_delivery_update("m1", "delivered");
        dispatcher.on_sync_complete(3);
        dispatcher.on_chat_created("c1", "group");
        dispatcher.on_member_added("c1", "u1");
        dispatcher.on_member_removed("c1", "u2");
        dispatcher.on_notification_received();

        let counter = unsafe { &*cb_ref };
        assert_eq!(counter.messages.load(Ordering::SeqCst), 1);
        assert_eq!(counter.deliveries.load(Ordering::SeqCst), 1);
        assert_eq!(counter.sync_completions.load(Ordering::SeqCst), 1);
        assert_eq!(counter.chats_created.load(Ordering::SeqCst), 1);
        assert_eq!(counter.members_added.load(Ordering::SeqCst), 1);
        assert_eq!(counter.members_removed.load(Ordering::SeqCst), 1);
        assert_eq!(counter.notifications.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn test_dispatcher_clone_shares_state() {
        let dispatcher = CallbackDispatcher::new();
        let clone = dispatcher.clone();

        let cb = Box::new(CountingCallback::new());
        let cb_ref = cb.as_ref() as *const CountingCallback;

        dispatcher.set_callback(cb);

        // Fire via clone — should reach the same callback
        clone.on_chat_created("c1", "group");

        let count = unsafe { &*cb_ref }.chats_created.load(Ordering::SeqCst);
        assert_eq!(count, 1, "clone should share the same callback state");
    }
}
