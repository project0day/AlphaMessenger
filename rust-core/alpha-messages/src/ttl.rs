//! TTL / disappearing message management.
//!
//! Provides utilities for computing expiry timestamps and checking
//! whether disappearing messages have expired.

use chrono::{DateTime, Duration, Utc};

/// Manager for TTL (time-to-live) / disappearing message computations.
///
/// This is a stateless utility — all methods are associated functions
/// that operate on provided timestamps.
pub struct TtlManager;

impl TtlManager {
    /// Compute the expiry timestamp for a disappearing message.
    ///
    /// # Arguments
    ///
    /// * `ttl_seconds` - The time-to-live in seconds
    /// * `created_at` - The timestamp when the message was created
    ///
    /// # Returns
    ///
    /// The `DateTime<Utc>` at which the message expires.
    pub fn compute_expiry(ttl_seconds: u32, created_at: &DateTime<Utc>) -> DateTime<Utc> {
        *created_at + Duration::seconds(i64::from(ttl_seconds))
    }

    /// Check whether a message has expired.
    ///
    /// Returns `true` if the current time is at or past the expiry timestamp.
    pub fn is_expired(expires_at: &DateTime<Utc>) -> bool {
        Utc::now() >= *expires_at
    }

    /// Compute the number of seconds remaining before a message expires.
    ///
    /// Returns a negative value if the message has already expired.
    pub fn seconds_remaining(expires_at: &DateTime<Utc>) -> i64 {
        (*expires_at - Utc::now()).num_seconds()
    }
}

/// Compute the expiry timestamp for a disappearing message.
///
/// Convenience function that delegates to [`TtlManager::compute_expiry`].
pub fn compute_expiry(ttl_seconds: u32, created_at: &DateTime<Utc>) -> DateTime<Utc> {
    TtlManager::compute_expiry(ttl_seconds, created_at)
}

/// Check whether a message has expired.
///
/// Convenience function that delegates to [`TtlManager::is_expired`].
pub fn is_expired(expires_at: &DateTime<Utc>) -> bool {
    TtlManager::is_expired(expires_at)
}

/// Compute the number of seconds remaining before expiry.
///
/// Convenience function that delegates to [`TtlManager::seconds_remaining`].
pub fn seconds_remaining(expires_at: &DateTime<Utc>) -> i64 {
    TtlManager::seconds_remaining(expires_at)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compute_expiry() {
        let created = Utc::now();
        let expires = TtlManager::compute_expiry(300, &created);
        let diff = expires - created;
        assert_eq!(diff.num_seconds(), 300);
    }

    #[test]
    fn test_compute_expiry_zero_ttl() {
        let created = Utc::now();
        let expires = TtlManager::compute_expiry(0, &created);
        assert_eq!(expires, created);
    }

    #[test]
    fn test_compute_expiry_large_ttl() {
        let created = Utc::now();
        let expires = TtlManager::compute_expiry(86_400, &created);
        let diff = expires - created;
        assert_eq!(diff.num_seconds(), 86_400);
    }

    #[test]
    fn test_is_expired_past() {
        let past = Utc::now() - Duration::seconds(60);
        assert!(TtlManager::is_expired(&past));
    }

    #[test]
    fn test_is_expired_future() {
        let future = Utc::now() + Duration::seconds(600);
        assert!(!TtlManager::is_expired(&future));
    }

    #[test]
    fn test_seconds_remaining_future() {
        let future = Utc::now() + Duration::seconds(300);
        let remaining = TtlManager::seconds_remaining(&future);
        // Should be close to 300, allowing for small timing differences
        assert!(remaining > 295 && remaining <= 300);
    }

    #[test]
    fn test_seconds_remaining_past() {
        let past = Utc::now() - Duration::seconds(60);
        let remaining = TtlManager::seconds_remaining(&past);
        assert!(remaining < 0);
    }

    #[test]
    fn test_convenience_functions() {
        let created = Utc::now();
        let expires = compute_expiry(60, &created);
        assert_eq!(expires, created + Duration::seconds(60));

        let future = Utc::now() + Duration::seconds(600);
        assert!(!is_expired(&future));

        let remaining = seconds_remaining(&future);
        assert!(remaining > 0);
    }
}
