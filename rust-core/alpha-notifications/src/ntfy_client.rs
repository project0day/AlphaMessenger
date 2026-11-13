//! ntfy.sh client for push notifications.
//!
//! Uses the ntfy HTTP API:
//! - POST `/{topic}` to publish a notification
//! - GET `/{topic}/json` to subscribe via long-polling
//!
//! All notifications contain ZERO plaintext — only a "wake" signal
//! that tells the recipient to connect to Nostr relays and fetch
//! actual messages.

use futures_util::stream::StreamExt;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::pin::Pin;

use crate::error::{NotificationError, Result};

/// A notification message to send via ntfy.
///
/// **Privacy**: The `message` field is always `"wake"` (or empty).
/// It never contains any chat info, sender name, or message content.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NtfyMessage {
    /// Title — always empty or "AlphaMessenger".
    pub title: String,
    /// Message body — always `"wake"` or empty.
    pub message: String,
    /// Priority — `"min"` means no sound/vibration.
    pub priority: String,
}

impl Default for NtfyMessage {
    fn default() -> Self {
        Self {
            title: "AlphaMessenger".to_string(),
            message: "wake".to_string(),
            priority: "min".to_string(),
        }
    }
}

impl NtfyMessage {
    /// Create a minimal wake notification.
    pub fn wake() -> Self {
        Self::default()
    }
}

/// A notification received from ntfy.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NtfyNotification {
    /// Unique notification ID assigned by ntfy.
    pub id: String,
    /// Unix timestamp of the notification.
    pub time: u64,
    /// The message body (should be "wake").
    pub message: String,
}

/// An item in the ntfy JSON stream (can be a notification or an event).
#[derive(Debug, Clone, Serialize, Deserialize)]
struct NtfyStreamItem {
    /// "message" for a real notification, "open"/"ping"/etc. for events.
    event: Option<String>,
    /// Notification ID.
    id: Option<String>,
    /// Unix timestamp.
    time: Option<u64>,
    /// Message body.
    message: Option<String>,
}

/// Client for communicating with a ntfy server.
#[derive(Clone)]
pub struct NtfyClient {
    /// The ntfy server URL (e.g. `https://ntfy.sh`).
    server_url: String,
    /// The topic (derived from the user's Nostr pubkey hash).
    topic: String,
    /// HTTP client for short requests (publish). Has a 30s total timeout.
    http: Client,
    /// HTTP client for the long-poll subscribe stream. Must NOT have a total
    /// request timeout — the `/json` endpoint holds the connection open
    /// indefinitely waiting for messages, so a total timeout would cut the
    /// stream and cause an endless reconnect loop ("error decoding response
    /// body"). Forced to HTTP/1.1 because reqwest's HTTP/2 long-poll handling
    /// resets these idle streams. ntfy sends periodic keepalive frames, so the
    /// TCP keepalive + read behaviour keeps it healthy without a hard timeout.
    http_stream: Client,
}

impl NtfyClient {
    /// Create a new ntfy client.
    ///
    /// `server_url`: the ntfy server (default `https://ntfy.sh`,
    /// can be self-hosted).
    /// `topic`: the topic string (derived via [`crate::push_dispatcher::generate_topic`]).
    pub fn new(server_url: &str, topic: &str) -> Self {
        let http = crate::proxied_client(crate::with_http_proxy(Client::builder()
            .timeout(std::time::Duration::from_secs(30))));
        // Streaming client: no total timeout (long-poll), HTTP/1.1, TCP
        // keepalive so a silently dropped socket is detected without cutting a
        // healthy idle stream.
        let http_stream = crate::proxied_client(crate::with_http_proxy(Client::builder()
            .http1_only()
            .connect_timeout(std::time::Duration::from_secs(15))
            .tcp_keepalive(std::time::Duration::from_secs(60))
            .pool_idle_timeout(None)));
        Self {
            server_url: server_url.trim_end_matches('/').to_string(),
            topic: topic.to_string(),
            http,
            http_stream,
        }
    }

    /// Subscribe to notifications on the configured topic.
    ///
    /// Uses the ntfy JSON streaming endpoint (long-polling).
    /// Returns a stream of [`NtfyNotification`] items.
    ///
    /// `since`: if set, asks ntfy to replay any cached messages published at or
    /// after this unix timestamp. This is what lets a phone that was offline
    /// (airplane mode) receive the wake pushes that arrived while it was down —
    /// without it, a reconnecting subscription only sees brand-new messages and
    /// the missed wakes are lost, so the phone wouldn't catch up until a slow
    /// timer fired.
    pub async fn subscribe(
        &self,
        since: Option<u64>,
    ) -> Result<Pin<Box<dyn futures_util::Stream<Item = NtfyNotification> + Send>>> {
        // ntfy's JSON streaming endpoint is `/{topic}/json` (a path segment),
        // NOT `/{topic}.json`. The dotted form 404s ("page not found"), which is
        // why wake-up push silently never worked. Use the correct `/json` path.
        let url = match since {
            Some(ts) => format!("{}/{}/json?since={}", self.server_url, self.topic, ts),
            None => format!("{}/{}/json", self.server_url, self.topic),
        };
        tracing::info!("Subscribing to ntfy topic: {} (since={:?})", self.topic, since);

        let response = self
            .http_stream
            .get(&url)
            .header("Accept", "application/json")
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let body = response.text().await.unwrap_or_default();
            return Err(NotificationError::ServerError { status, body });
        }

        // Read the streaming response line by line.
        // Each line is a JSON object (either a notification or an event like "open").
        let stream = ntfy_stream(response).boxed();

        Ok(stream)
    }

    /// Publish a notification to the configured topic.
    ///
    /// Used by the *sender's* device to wake the recipient's device.
    /// The message contains ONLY the word `"wake"` — no chat info,
    /// no sender, no content.
    pub async fn publish(&self, message: &NtfyMessage) -> Result<()> {
        let url = format!("{}/{}", self.server_url, self.topic);
        tracing::debug!("Publishing wake notification to topic: {}", self.topic);

        let response = self
            .http
            .post(&url)
            .header("Title", &message.title)
            .header("Priority", &message.priority)
            .body(message.message.clone())
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let body = response.text().await.unwrap_or_default();
            return Err(NotificationError::ServerError { status, body });
        }

        tracing::debug!("Wake notification published successfully");
        Ok(())
    }

    /// Get the server URL.
    pub fn server_url(&self) -> &str {
        &self.server_url
    }

    /// Get the topic.
    pub fn topic(&self) -> &str {
        &self.topic
    }
}

/// Converts an HTTP response into a stream of ntfy notifications.
///
/// Reads the response body line by line, parses each line as JSON,
/// and filters for actual message events (ignoring "open", "ping", etc.).
/// ntfy sends a keepalive every 45 s: this long without a byte means the
/// socket died without telling us (Wi-Fi up, internet gone).
const STREAM_IDLE: std::time::Duration = std::time::Duration::from_secs(120);

fn ntfy_stream(
    response: reqwest::Response,
) -> impl futures_util::Stream<Item = NtfyNotification> + Send {
    use futures_util::StreamExt;

    let stream = async_stream::stream! {
        let mut stream = response.bytes_stream();

        let mut buffer = String::new();

        loop {
            let chunk_result = match tokio::time::timeout(STREAM_IDLE, stream.next()).await {
                Ok(Some(r)) => r,
                Ok(None) => break,
                Err(_) => {
                    tracing::warn!("ntfy stream silent for {}s: reconnecting", STREAM_IDLE.as_secs());
                    break;
                }
            };
            let chunk = match chunk_result {
                Ok(c) => c,
                Err(e) => {
                    tracing::warn!("Error reading ntfy stream: {}", e);
                    break;
                }
            };

            let text = match std::str::from_utf8(&chunk) {
                Ok(t) => t,
                Err(_) => continue,
            };

            buffer.push_str(text);

            // Process complete lines
            while let Some(newline_pos) = buffer.find('\n') {
                let line = buffer[..newline_pos].to_string();
                buffer = buffer[newline_pos + 1..].to_string();

                let line = line.trim().to_string();
                if line.is_empty() {
                    continue;
                }

                let item: NtfyStreamItem = match serde_json::from_str(&line) {
                    Ok(v) => v,
                    Err(_) => continue,
                };

                // Only yield actual message notifications, not "open"/"ping" events.
                if item.event.as_deref() == Some("message") {
                    if let (Some(id), Some(time), Some(message)) =
                        (item.id, item.time, item.message)
                    {
                        yield NtfyNotification {
                            id,
                            time,
                            message,
                        };
                    }
                }
            }
        }
    };

    stream
}
