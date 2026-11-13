//! Subscription management for MLS events.
//!
//! Provides [`MlsSubscription`] to track individual subscription state and
//! [`SubscriptionManager`] to manage multiple subscriptions across relays,
//! including re-establishing them after a disconnect.

use std::collections::HashMap;
use std::sync::Arc;

use nostr::{Filter, SubscriptionId};
use tokio::sync::RwLock;

use crate::error::{Result, TransportError};

/// Tracks the state of a single active subscription.
#[derive(Debug, Clone)]
pub struct MlsSubscription {
    /// Unique subscription identifier.
    pub id: SubscriptionId,
    /// The relay URL this subscription is associated with.
    pub relay_url: String,
    /// The filters used for this subscription.
    pub filters: Vec<Filter>,
    /// Whether this subscription is currently active.
    pub active: bool,
}

impl MlsSubscription {
    /// Create a new subscription descriptor.
    pub fn new(id: SubscriptionId, relay_url: String, filters: Vec<Filter>) -> Self {
        Self {
            id,
            relay_url,
            filters,
            active: true,
        }
    }

    /// Mark the subscription as inactive (e.g. after disconnect).
    pub fn deactivate(&mut self) {
        self.active = false;
    }

    /// Mark the subscription as active (e.g. after reconnect).
    pub fn activate(&mut self) {
        self.active = true;
    }
}

/// Manages multiple MLS event subscriptions across relays.
///
/// Thread-safe: the internal state is protected by a [`RwLock`] so it can be
/// shared between the relay client and notification handlers.
#[derive(Debug, Clone)]
pub struct SubscriptionManager {
    subscriptions: Arc<RwLock<HashMap<String, MlsSubscription>>>,
}

impl SubscriptionManager {
    /// Create an empty subscription manager.
    pub fn new() -> Self {
        Self {
            subscriptions: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Add a new subscription.
    ///
    /// Returns the `SubscriptionId` for tracking. If a subscription for the
    /// given relay URL already exists it will be replaced.
    pub async fn add(&self, relay_url: &str, filters: Vec<Filter>) -> SubscriptionId {
        let id = SubscriptionId::generate();
        let sub = MlsSubscription::new(id.clone(), relay_url.to_string(), filters);

        let mut subs = self.subscriptions.write().await;
        subs.insert(relay_url.to_string(), sub);

        id
    }

    /// Add a subscription with a pre-existing `SubscriptionId`.
    pub async fn add_with_id(
        &self,
        relay_url: &str,
        id: SubscriptionId,
        filters: Vec<Filter>,
    ) {
        let sub = MlsSubscription::new(id, relay_url.to_string(), filters);
        let mut subs = self.subscriptions.write().await;
        subs.insert(relay_url.to_string(), sub);
    }

    /// Remove a subscription by its ID.
    ///
    /// Returns `true` if a subscription was found and removed.
    pub async fn remove(&self, subscription_id: &SubscriptionId) -> bool {
        let mut subs = self.subscriptions.write().await;
        let key = subs.iter().find(|(_, v)| &v.id == subscription_id).map(|(k, _)| k.clone());
        match key {
            Some(k) => {
                subs.remove(&k);
                true
            }
            None => false,
        }
    }

    /// Remove a subscription by relay URL.
    pub async fn remove_by_relay(&self, relay_url: &str) -> bool {
        let mut subs = self.subscriptions.write().await;
        subs.remove(relay_url).is_some()
    }

    /// Mark all subscriptions as inactive (e.g. after disconnect).
    pub async fn deactivate_all(&self) {
        let mut subs = self.subscriptions.write().await;
        for sub in subs.values_mut() {
            sub.deactivate();
        }
    }

    /// Get all subscriptions that need to be re-established.
    ///
    /// Returns subscriptions that are currently inactive (i.e. were created
    /// but lost due to a disconnect).
    pub async fn inactive_subscriptions(&self) -> Vec<MlsSubscription> {
        let subs = self.subscriptions.read().await;
        subs.values()
            .filter(|s| !s.active)
            .cloned()
            .collect()
    }

    /// Get all active subscriptions.
    pub async fn active_subscriptions(&self) -> Vec<MlsSubscription> {
        let subs = self.subscriptions.read().await;
        subs.values()
            .filter(|s| s.active)
            .cloned()
            .collect()
    }

    /// Get all subscriptions (active and inactive).
    pub async fn all_subscriptions(&self) -> Vec<MlsSubscription> {
        let subs = self.subscriptions.read().await;
        subs.values().cloned().collect()
    }

    /// Mark a subscription as active after successfully re-subscribing.
    pub async fn mark_active(&self, subscription_id: &SubscriptionId) -> Result<()> {
        let mut subs = self.subscriptions.write().await;
        let sub = subs
            .iter_mut()
            .find(|(_, v)| &v.id == subscription_id)
            .map(|(_, v)| v);
        match sub {
            Some(s) => {
                s.activate();
                Ok(())
            }
            None => Err(TransportError::SubscriptionFailed(format!(
                "subscription {} not found",
                subscription_id
            ))),
        }
    }

    /// Number of subscriptions (active and inactive).
    pub async fn len(&self) -> usize {
        let subs = self.subscriptions.read().await;
        subs.len()
    }

    /// Whether there are no subscriptions.
    pub async fn is_empty(&self) -> bool {
        let subs = self.subscriptions.read().await;
        subs.is_empty()
    }
}

impl Default for SubscriptionManager {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use nostr::Kind;

    #[tokio::test]
    async fn add_subscription() {
        let mgr = SubscriptionManager::new();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);

        let id = mgr.add("wss://relay1.example", vec![filter]).await;

        assert_eq!(mgr.len().await, 1);
        assert!(!mgr.is_empty().await);

        // The subscription should be active by default
        let active = mgr.active_subscriptions().await;
        assert_eq!(active.len(), 1);
        assert_eq!(active[0].id, id);
        assert_eq!(active[0].relay_url, "wss://relay1.example");
        assert!(active[0].active);
    }

    #[tokio::test]
    async fn remove_subscription_by_id() {
        let mgr = SubscriptionManager::new();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);

        let id = mgr.add("wss://relay1.example", vec![filter]).await;
        assert_eq!(mgr.len().await, 1);

        let removed = mgr.remove(&id).await;
        assert!(removed);
        assert_eq!(mgr.len().await, 0);
        assert!(mgr.is_empty().await);
    }

    #[tokio::test]
    async fn remove_nonexistent_subscription() {
        let mgr = SubscriptionManager::new();
        let fake_id = SubscriptionId::generate();
        let removed = mgr.remove(&fake_id).await;
        assert!(!removed);
    }

    #[tokio::test]
    async fn remove_subscription_by_relay() {
        let mgr = SubscriptionManager::new();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);

        mgr.add("wss://relay1.example", vec![filter.clone()]).await;
        mgr.add("wss://relay2.example", vec![filter]).await;
        assert_eq!(mgr.len().await, 2);

        let removed = mgr.remove_by_relay("wss://relay1.example").await;
        assert!(removed);
        assert_eq!(mgr.len().await, 1);

        // The remaining subscription should be for relay2
        let all = mgr.all_subscriptions().await;
        assert_eq!(all[0].relay_url, "wss://relay2.example");
    }

    #[tokio::test]
    async fn deactivate_all() {
        let mgr = SubscriptionManager::new();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);

        mgr.add("wss://relay1.example", vec![filter.clone()]).await;
        mgr.add("wss://relay2.example", vec![filter]).await;

        // All should be active
        assert_eq!(mgr.active_subscriptions().await.len(), 2);
        assert_eq!(mgr.inactive_subscriptions().await.len(), 0);

        mgr.deactivate_all().await;

        // All should now be inactive
        assert_eq!(mgr.active_subscriptions().await.len(), 0);
        assert_eq!(mgr.inactive_subscriptions().await.len(), 2);
    }

    #[tokio::test]
    async fn inactive_subscriptions_after_deactivate() {
        let mgr = SubscriptionManager::new();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);

        let _id1 = mgr.add("wss://relay1.example", vec![filter.clone()]).await;
        let _id2 = mgr.add("wss://relay2.example", vec![filter]).await;

        mgr.deactivate_all().await;

        let inactive = mgr.inactive_subscriptions().await;
        assert_eq!(inactive.len(), 2);

        // Check relay URLs are present in the inactive list
        let urls: Vec<&str> = inactive.iter().map(|s| s.relay_url.as_str()).collect();
        assert!(urls.contains(&"wss://relay1.example"));
        assert!(urls.contains(&"wss://relay2.example"));
    }

    #[tokio::test]
    async fn mark_active_after_deactivate() {
        let mgr = SubscriptionManager::new();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);

        let id = mgr.add("wss://relay1.example", vec![filter]).await;

        mgr.deactivate_all().await;
        assert_eq!(mgr.active_subscriptions().await.len(), 0);

        // Mark the specific subscription as active again
        mgr.mark_active(&id).await.unwrap();
        assert_eq!(mgr.active_subscriptions().await.len(), 1);
        assert_eq!(mgr.inactive_subscriptions().await.len(), 0);
    }

    #[tokio::test]
    async fn mark_active_nonexistent_returns_error() {
        let mgr = SubscriptionManager::new();
        let fake_id = SubscriptionId::generate();
        let result = mgr.mark_active(&fake_id).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn add_with_id() {
        let mgr = SubscriptionManager::new();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);
        let custom_id = SubscriptionId::generate();

        mgr.add_with_id("wss://relay1.example", custom_id.clone(), vec![filter]).await;

        assert_eq!(mgr.len().await, 1);
        let all = mgr.all_subscriptions().await;
        assert_eq!(all[0].id, custom_id);
    }

    #[tokio::test]
    async fn replace_existing_relay_subscription() {
        let mgr = SubscriptionManager::new();
        let filter1 = Filter::new().kind(Kind::MlsKeyPackage);
        let filter2 = Filter::new().kind(Kind::GiftWrap);

        let id1 = mgr.add("wss://relay1.example", vec![filter1]).await;
        let id2 = mgr.add("wss://relay1.example", vec![filter2]).await;

        // Should still be 1 subscription (replaced)
        assert_eq!(mgr.len().await, 1);

        // The new subscription should have the new ID
        let all = mgr.all_subscriptions().await;
        assert_eq!(all[0].id, id2);
        assert_ne!(id1, id2);
    }

    #[tokio::test]
    async fn default_is_empty() {
        let mgr = SubscriptionManager::default();
        assert!(mgr.is_empty().await);
        assert_eq!(mgr.len().await, 0);
    }

    #[tokio::test]
    async fn concurrent_add_remove() {
        let mgr = std::sync::Arc::new(SubscriptionManager::new());
        let filter = Filter::new().kind(Kind::MlsKeyPackage);

        let mut handles = Vec::new();

        // Concurrently add subscriptions for 10 relays
        for i in 0..10 {
            let mgr_clone = mgr.clone();
            let filter_clone = filter.clone();
            handles.push(tokio::spawn(async move {
                mgr_clone
                    .add(&format!("wss://relay{}.example", i), vec![filter_clone])
                    .await
            }));
        }

        // Wait for all adds to complete
        for handle in handles {
            let _ = handle.await.unwrap();
        }

        assert_eq!(mgr.len().await, 10);

        // Concurrently remove 5 subscriptions
        let all = mgr.all_subscriptions().await;
        let mut remove_handles = Vec::new();
        for sub in all.iter().take(5) {
            let mgr_clone = mgr.clone();
            let id = sub.id.clone();
            remove_handles.push(tokio::spawn(async move {
                mgr_clone.remove(&id).await
            }));
        }

        for handle in remove_handles {
            assert!(handle.await.unwrap());
        }

        assert_eq!(mgr.len().await, 5);
    }

    // ── MlsSubscription unit tests ──

    #[test]
    fn mls_subscription_new_is_active() {
        let id = SubscriptionId::generate();
        let sub = MlsSubscription::new(id, "wss://relay.example".to_string(), vec![]);
        assert!(sub.active);
    }

    #[test]
    fn mls_subscription_deactivate() {
        let id = SubscriptionId::generate();
        let mut sub = MlsSubscription::new(id, "wss://relay.example".to_string(), vec![]);
        assert!(sub.active);
        sub.deactivate();
        assert!(!sub.active);
    }

    #[test]
    fn mls_subscription_activate() {
        let id = SubscriptionId::generate();
        let mut sub = MlsSubscription::new(id, "wss://relay.example".to_string(), vec![]);
        sub.deactivate();
        assert!(!sub.active);
        sub.activate();
        assert!(sub.active);
    }

    #[test]
    fn mls_subscription_stores_filters() {
        let id = SubscriptionId::generate();
        let filter = Filter::new().kind(Kind::MlsKeyPackage);
        let sub = MlsSubscription::new(id, "wss://relay.example".to_string(), vec![filter.clone()]);
        assert_eq!(sub.filters.len(), 1);
        assert!(sub.filters[0].kinds.as_ref().map_or(false, |s| s.contains(&Kind::MlsKeyPackage)));
    }
}
