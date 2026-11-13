//! Notification processing.
//!
//! Handles incoming push notifications and outgoing wake signals.

use crate::error::Result;
use crate::ntfy_client::NtfyNotification;
use crate::push_dispatcher::PushDispatcher;

/// What the app should do when a push notification arrives.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NotificationAction {
    /// Connect to Nostr relays and fetch new messages.
    FetchMessages,
    // Future variants could include NoOp, ShowSystemNotification, etc.
}

/// Processes incoming push notifications and dispatches outgoing
/// wake signals to other devices.
pub struct NotificationHandler {
    /// Dispatcher for sending wake notifications.
    push_dispatcher: PushDispatcher,
}

impl NotificationHandler {
    /// Create a new notification handler.
    pub fn new(push_dispatcher: PushDispatcher) -> Self {
        Self { push_dispatcher }
    }

    /// Called when we receive an MLS message and need to wake the
    /// sender's other devices.
    ///
    /// The `sender_pubkey` is the Nostr public key of the *local*
    /// user (so we can derive the topic for *our* other devices).
    pub async fn on_message_received(&self, sender_pubkey: &[u8]) -> Result<()> {
        let topic = PushDispatcher::generate_topic(sender_pubkey);
        tracing::debug!(
            "Received MLS message; waking our other devices on topic: {}",
            topic,
        );
        self.push_dispatcher.notify_device(&topic).await
    }

    /// Called when we receive a push notification from ntfy.
    ///
    /// Returns the action the app should take.  Since push notifications
    /// contain ZERO plaintext (only "wake"), the only action is to
    /// fetch messages from the Nostr relays.
    ///
    /// This **never** includes any message content in the returned
    /// action — the app must fetch and decrypt messages itself.
    pub fn on_notification(&self, _notification: &NtfyNotification) -> NotificationAction {
        tracing::debug!(
            "Received push notification (id={}); fetching messages",
            _notification.id,
        );
        NotificationAction::FetchMessages
    }

    /// Get a reference to the underlying push dispatcher.
    pub fn push_dispatcher(&self) -> &PushDispatcher {
        &self.push_dispatcher
    }
}
