//! UnifiedPush fallback integration.
//!
//! On Android, ntfy acts as a UnifiedPush distributor. This module
//! provides the registration/unregistration interface following the
//! standard UnifiedPush protocol.
//!
//! UnifiedPush is an open standard for push notifications that avoids
//! dependency on Google Play Services.  The ntfy Android app registers
//! as a UnifiedPush distributor, and apps can register endpoints
//! through it.

use crate::error::{NotificationError, Result};
use reqwest::Client;

/// Client for interacting with the UnifiedPush protocol via ntfy.
///
/// On Android, the ntfy app serves as both:
/// - A UnifiedPush *distributor* (receives push messages from other apps)
/// - A UnifiedPush *endpoint* (for this app's own notifications)
///
/// The standard flow is:
/// 1. Register with the distributor → get an endpoint URL
/// 2. Send the endpoint URL to the application server
/// 3. The server pushes to that endpoint when needed
/// 4. The distributor delivers the push to the app
pub struct UnifiedPushClient {
    /// HTTP client.
    http: Client,
    /// The current registration endpoint, if registered.
    endpoint: Option<String>,
}

impl UnifiedPushClient {
    /// Create a new UnifiedPush client.
    pub fn new() -> Self {
        Self {
            http: crate::proxied_client(crate::with_http_proxy(Client::builder())),
            endpoint: None,
        }
    }

    /// Register with the UnifiedPush distributor.
    ///
    /// `endpoint`: the push endpoint URL provided by the distributor
    /// (on Android, this comes from the ntfy app's UnifiedPush
    /// integration).
    pub fn register(&mut self, endpoint: &str) -> Result<()> {
        if endpoint.is_empty() {
            return Err(NotificationError::UnifiedPushRegistration(
                "endpoint URL is empty".to_string(),
            ));
        }

        // Validate the endpoint URL.
        let url = reqwest::Url::parse(endpoint).map_err(|e| {
            NotificationError::UnifiedPushRegistration(format!("invalid endpoint URL: {}", e))
        })?;

        if url.scheme() != "https" && url.scheme() != "http" {
            return Err(NotificationError::UnifiedPushRegistration(format!(
                "invalid endpoint scheme: {}",
                url.scheme()
            )));
        }

        tracing::info!("Registered with UnifiedPush endpoint: {}", endpoint);
        self.endpoint = Some(endpoint.to_string());
        Ok(())
    }

    /// Unregister from the UnifiedPush distributor.
    ///
    /// After calling this, the app will no longer receive push
    /// notifications through UnifiedPush.  The app should fall
    /// back to direct ntfy WebSocket subscription.
    pub fn unregister(&mut self) -> Result<()> {
        if self.endpoint.is_none() {
            tracing::debug!("Not registered with UnifiedPush; nothing to unregister");
            return Ok(());
        }

        let endpoint = self.endpoint.take().unwrap();
        tracing::info!("Unregistered from UnifiedPush endpoint: {}", endpoint);
        Ok(())
    }

    /// Whether we are currently registered.
    pub fn is_registered(&self) -> bool {
        self.endpoint.is_some()
    }

    /// Get the current endpoint URL, if registered.
    pub fn endpoint(&self) -> Option<&str> {
        self.endpoint.as_deref()
    }

    /// Send a push message to the registered endpoint.
    ///
    /// This is typically called by the *sender's* app server,
    /// not the recipient's app.  The body should be a minimal
    /// wake signal with zero plaintext.
    pub async fn push(&self, body: &str) -> Result<()> {
        let endpoint = self
            .endpoint
            .as_ref()
            .ok_or_else(|| NotificationError::UnifiedPushRegistration(
                "not registered".to_string(),
            ))?;

        let response = self
            .http
            .post(endpoint)
            .header("Content-Type", "text/plain")
            .body(body.to_string())
            .send()
            .await
            .map_err(|e| {
                NotificationError::UnifiedPushRegistration(format!("push failed: {}", e))
            })?;

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let body = response.text().await.unwrap_or_default();
            return Err(NotificationError::ServerError { status, body });
        }

        tracing::debug!("Push sent via UnifiedPush to {}", endpoint);
        Ok(())
    }
}

impl Default for UnifiedPushClient {
    fn default() -> Self {
        Self::new()
    }
}
