//! One phone per MLS leaf. A backup carries our place in every group (the
//! leaf and its signer), so after a restore two phones would hold the same
//! leaf: the first commit or message of one forks the other, silently.
//! The restored phone claims the leaf: a replaceable event, sealed and signed
//! by keys derived from the account, says which install holds this signer
//! since when. An older install of the same signer that finds it stops its
//! network for good and asks to restore again. Phones linked as phones of
//! ours have their own leaves and never match.

use super::*;

const KV_INSTALL_ID: &str = "install_id_v1";
const KV_CLAIM_AT: &str = "device_claim_at_v1";
const KV_REPLACED_AT: &str = "device_replaced_at_v1";
/// Facts of this install only: never in a backup, never taken over.
pub(super) const DEVICE_ONLY_KV: &[&str] = &[KV_INSTALL_ID, KV_CLAIM_AT, KV_REPLACED_AT];
const CLAIM_AAD: alpha_transport::sealed::Tag16 = *b"alpha-device-v1!";
const CLAIM_D_TAG: &str = "alpha-device";

impl AlphaMessengerCore {
    fn install_id(&self) -> String {
        let conn = self.db.connection();
        if let Some(id) = KeyValueRepo::get(&conn, KV_INSTALL_ID).ok().flatten().filter(|s| !s.is_empty()) {
            return id;
        }
        let id = hex::encode(alpha_transport::sealed::random_secret()[..16].to_vec());
        let _ = KeyValueRepo::set(&conn, KV_INSTALL_ID, &id);
        id
    }

    /// The account's keys for the claim, and this phone's MLS signer (the leaf it holds).
    fn claim_material(&self) -> Result<(nostr::Keys, alpha_transport::sealed::Key32, String), MessengerError> {
        let (secret, signer) = {
            let guard = self.identity.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let id = guard.as_ref()
                .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?;
            (id.nostr().keypair().keys().secret_key().to_secret_bytes(), hex::encode(id.mls().signer().public()))
        };
        let (sign, key) = alpha_transport::sealed::device_claim(&secret);
        let keys = nostr::SecretKey::from_slice(&sign)
            .map(nostr::Keys::new)
            .map_err(|e| MessengerError::CryptoError { description: format!("device claim key: {e}") })?;
        Ok((keys, key, signer))
    }

    /// After a restore: this phone now holds the account's leaf; any other install of it stops.
    pub fn claim_this_device(&self) -> Result<(), MessengerError> {
        let (keys, key, sig) = self.claim_material()?;
        let install = self.install_id();
        let at = chrono::Utc::now().timestamp();
        {
            let conn = self.db.connection();
            let _ = KeyValueRepo::set(&conn, KV_CLAIM_AT, &at.to_string());
            let _ = KeyValueRepo::delete(&conn, KV_REPLACED_AT);
            // A fresh pool at the next start, and with it the sweep of the replaced install's KeyPackages.
            let _ = conn.execute("DELETE FROM key_value_store WHERE key LIKE 'last_published_keypackage_at:%'", []);
        }
        let body = serde_json::json!({ "sig": sig, "install": install, "at": at }).to_string();
        let content = alpha_transport::sealed::seal(&key, &CLAIM_AAD, body.as_bytes())
            .ok_or_else(|| MessengerError::CryptoError { description: "device claim seal failed".to_string() })?;
        let event = nostr::EventBuilder::new(nostr::Kind::Custom(30078), content)
            .tag(nostr::Tag::identifier(CLAIM_D_TAG))
            .sign_with_keys(&keys)
            .map_err(|e| MessengerError::CryptoError { description: format!("device claim sign: {e}") })?;
        let rt = runtime();
        let ours = rt.block_on(self.transport.publish(event.clone()));
        let copies = rt.block_on(alpha_transport::NostrRelayClient::publish_detached(
            Self::backup_lookup_relays(Vec::new()), vec![event], std::time::Duration::from_secs(10)));
        if ours.is_err() && copies == 0 {
            return Err(MessengerError::NetworkError { description: "device claim not published".to_string() });
        }
        tracing::info!(target: "ACCOUNT", "device claim published (copies: {copies})");
        Ok(())
    }

    /// Unix time since which another phone holds this account's leaf, or 0.
    /// Once seen it stays: the network of this install is off from then on.
    pub fn device_replaced_at(&self) -> i64 {
        if let Some(at) = self.replaced_at_kv() { return at; }
        let Ok((keys, key, sig)) = self.claim_material() else { return 0 };
        let install = self.install_id();
        let mine_at: i64 = {
            let conn = self.db.connection();
            KeyValueRepo::get(&conn, KV_CLAIM_AT).ok().flatten().and_then(|s| s.parse().ok()).unwrap_or(0)
        };
        let filter = nostr::Filter::new()
            .author(keys.public_key())
            .kind(nostr::Kind::Custom(30078))
            .identifier(CLAIM_D_TAG);
        let Ok(events) = self.fetch_backup_locator(filter) else { return 0 };
        let Some(claim) = events.into_iter()
            .filter_map(|e| alpha_transport::sealed::unseal(&key, &CLAIM_AAD, &e.content))
            .filter_map(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
            .max_by_key(|v| v["at"].as_i64().unwrap_or(0))
        else { return 0 };
        let at = claim["at"].as_i64().unwrap_or(0);
        if claim["sig"].as_str() != Some(sig.as_str()) || claim["install"].as_str() == Some(install.as_str()) || at <= mine_at {
            return 0;
        }
        tracing::warn!(target: "ACCOUNT", "this phone's leaf is held by another install since {at}: network off");
        {
            let conn = self.db.connection();
            let _ = KeyValueRepo::set(&conn, KV_REPLACED_AT, &at.to_string());
        }
        let _ = self.stop_sync();
        at
    }

    /// KeyPackages under our key that this phone cannot open (consumed, or left by an
    /// install that was erased or moved) are withdrawn: whoever picks one sends a
    /// Welcome nobody can read. Runs on the read-back that follows each pool publish,
    /// so it costs no query of its own. Returns how many we hold.
    pub(super) fn sweep_own_key_packages(&self, keys: &nostr::Keys, events: &[nostr::Event]) -> usize {
        let (held, dead): (usize, Vec<nostr::EventId>) = {
            let Ok(mls) = self.mls_session.lock() else { return 0 };
            let mut held = 0usize;
            let mut dead = Vec::new();
            for e in events {
                let Ok(bytes) = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, e.content.trim()) else { continue };
                match mls.holds_key_package(&bytes) {
                    Some(true) => held += 1,
                    Some(false) => dead.push(e.id),
                    None => {}
                }
            }
            (held, dead)
        };
        // Phones of ours linked to each other publish under the same key: theirs are not ours to withdraw.
        if !dead.is_empty() && !alpha_database::devices::linked() {
            let bridge = NostrMlsBridge::new((*self.transport).clone());
            let sent = runtime().block_on(bridge.withdraw_key_packages(keys, &dead));
            tracing::info!(target: "ACCOUNT", "KeyPackages under our key: {held} held, {} withdrawn (relays: {sent})", dead.len());
        }
        held
    }

    pub(super) fn replaced_at_kv(&self) -> Option<i64> {
        let conn = self.db.connection();
        KeyValueRepo::get(&conn, KV_REPLACED_AT).ok().flatten().and_then(|s| s.parse().ok()).filter(|at: &i64| *at > 0)
    }
}
