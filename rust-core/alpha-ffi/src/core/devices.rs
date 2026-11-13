//! Linked devices: our account on several phones (the list lives in
//! `alpha_database::devices`).
//!
//! The account is the Nostr key, shared by the phones; each has its own MLS
//! leaf, bound to that key, so a member can tell "one of hers" from a peer.
//!
//! Linking a new phone N from a linked phone L:
//! 1. N shows a QR: a one-time key and a secret.
//! 2. L scans it and sends the account, sealed to that one-time key ("offer").
//! 3. N takes the account and answers to our own key ("hello") with a
//!    KeyPackage kept after use — one per channel we own hidden, too.
//! 4. L adds N where it may commit itself (direct chats, groups it owns) and
//!    asks each other group's owner to (only the owner commits there), then
//!    hands N the history.
//!
//! The phones then tell each other what the chats alone don't carry (read,
//! mute, pin, archive, deletions, notes to self) in notes sealed to our key.

use super::*;
use alpha_database::devices::{self as registry, Device};

const QR_PREFIX: &str = "alphalink:1:";
/// Linking new phones is frozen: requests from members about their phones are
/// not acted on (what is linked can still be unlinked).
const LINKING_ENABLED: bool = false;
/// What the linking phone keeps of the link it runs: `{secret, eph, stage, total, done, asked, error}`.
const KV_LINK_RUN: &str = "link_run_v1";
const KV_DEVSYNC_IN: &str = "devsync_in:";
/// A gift wrap carries at most ~64 KB; a note stays well below.
const MAX_NOTE: usize = 40 * 1024;
/// Join-request tokens between an account and a group's owner (no invite link).
const TOKEN_DEVICE: &str = "device:";
const TOKEN_UNLINK: &str = "unlink:";
/// Gift wraps are dated up to two days back (NIP-59).
const WRAP_BACKDATE_SECS: u64 = 3 * 24 * 3600;

/// The new phone while it waits for an offer.
struct LinkWait {
    keys: nostr::Keys,
    secret: String,
    since: u64,
    offer: Option<serde_json::Value>,
}

thread_local! {
    /// Set while a note of our other phone is applied: what it writes is
    /// theirs already and must not be noted back.
    static APPLYING: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

pub(super) fn applying_note() -> bool {
    APPLYING.with(|a| a.get())
}

fn link_wait() -> &'static std::sync::Mutex<Option<LinkWait>> {
    static W: std::sync::OnceLock<std::sync::Mutex<Option<LinkWait>>> = std::sync::OnceLock::new();
    W.get_or_init(|| std::sync::Mutex::new(None))
}

fn now_secs() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn b64(bytes: &[u8]) -> String {
    base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes)
}

fn unb64(s: &str) -> Option<Vec<u8>> {
    base64::Engine::decode(&base64::engine::general_purpose::STANDARD, s).ok()
}

fn parse_qr(qr: &str) -> Option<(nostr::PublicKey, String)> {
    let rest = qr.trim().strip_prefix(QR_PREFIX)?;
    let (pk, secret) = rest.split_once(':')?;
    let pk = nostr::PublicKey::from_hex(pk).ok()?;
    (secret.len() == 32 && secret.bytes().all(|b| b.is_ascii_hexdigit())).then(|| (pk, secret.to_string()))
}

fn short_hash(bytes: &[u8]) -> String {
    use sha2::Digest;
    hex::encode(&sha2::Sha256::digest(bytes)[..8])
}

impl AlphaMessengerCore {
    fn account_keys(&self) -> Result<nostr::Keys, MessengerError> {
        let guard = self.identity.lock().map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        Ok(guard.as_ref()
            .ok_or_else(|| MessengerError::InternalError { description: "identity not initialized".to_string() })?
            .nostr().keypair().keys().clone())
    }

    pub(super) fn account_hex(&self) -> Result<String, MessengerError> {
        Ok(self.account_keys()?.public_key().to_hex())
    }

    fn account_signer_hex(&self) -> String {
        self.identity.lock().ok()
            .and_then(|g| g.as_ref().map(|id| hex::encode(id.mls().signer().public())))
            .unwrap_or_default()
    }

    fn this_device(&self, name: &str) -> Device {
        // Identity before the database (the order every path takes them in).
        let sig = self.account_signer_hex();
        Device {
            id: registry::this_device_id(&self.db.connection()),
            name: name.to_string(),
            sig,
            added_at: chrono::Utc::now().timestamp(),
            persona_sigs: Vec::new(),
        }
    }

    /// The list of our phones holds this one (its first entry once a second comes).
    fn ensure_registered(&self, name: &str) {
        let mut me = self.this_device(name);
        let conn = self.db.connection();
        me.persona_sigs = alpha_database::personas::all(&conn).into_iter()
            .filter(|p| p.joined && !p.sig.is_empty() && !self.is_secret_chat_in(&conn, &p.chat_id))
            .map(|p| p.sig).collect();
        let mut all = registry::list(&conn);
        match all.iter_mut().find(|d| d.id == me.id) {
            Some(d) => {
                d.sig = me.sig;
                d.persona_sigs = me.persona_sigs;
                if !name.is_empty() { d.name = me.name; }
            }
            None => all.insert(0, me),
        }
        registry::save(&conn, &all);
    }

    /// Our phones, this one marked: `[{id, name, added_at, this}]`.
    pub fn devices_json(&self) -> String {
        let conn = self.db.connection();
        let me = registry::this_device_id(&conn);
        serde_json::Value::Array(registry::list(&conn).into_iter().map(|d| serde_json::json!({
            "id": d.id, "name": d.name, "added_at": d.added_at, "this": d.id == me,
        })).collect()).to_string()
    }

    // --- Notes between our phones -------------------------------------------------

    /// A note to our other phones; nothing while this is the only one.
    pub(super) fn device_note(&self, mut op: serde_json::Value) {
        if !registry::linked() { return; }
        let Ok(keys) = self.account_keys() else { return };
        op["from"] = serde_json::Value::String(registry::this_device_id(&self.db.connection()));
        op["at"] = serde_json::Value::from(chrono::Utc::now().timestamp_millis());
        self.seal_note(&keys, keys.public_key(), &op, false);
    }

    /// Seal [body] with [keys] to [to]. `wait`: return only once a relay took it.
    fn seal_note(&self, keys: &nostr::Keys, to: nostr::PublicKey, body: &serde_json::Value, wait: bool) -> bool {
        let payload = body.to_string().into_bytes();
        if payload.len() > MAX_NOTE {
            tracing::warn!(target: "ACCOUNT", "device note too large ({} bytes) — not sent", payload.len());
            return false;
        }
        let env = MlsEnvelope::new(MlsMessageType::DeviceSync, Vec::new(), 0, payload);
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let keys = keys.clone();
        let send = async move {
            for attempt in 1..=3u32 {
                match bridge.send_mls_message(&keys, &env, Some(&to), None).await {
                    Ok(_) => return true,
                    Err(e) => {
                        tracing::warn!(target: "ACCOUNT", "device note attempt {attempt}: {e}");
                        tokio::time::sleep(std::time::Duration::from_secs(2 * attempt as u64)).await;
                    }
                }
            }
            false
        };
        if wait { runtime().block_on(send) } else { runtime().spawn(send); true }
    }

    /// The signed list of our phones, for whoever adds us to a chat later.
    fn publish_device_list(&self) {
        let ids: Vec<String> = registry::list(&self.db.connection()).into_iter().map(|d| d.id).collect();
        let Ok(keys) = self.account_keys() else { return };
        let Ok(ev) = NostrMlsBridge::device_list_event(&keys, &ids) else { return };
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        runtime().spawn(async move {
            if let Err(e) = bridge.relay_client().publish(ev.clone()).await {
                tracing::warn!(target: "ACCOUNT", "device list publish: {e}");
            }
            let _ = bridge.mirror_key_packages(vec![ev]).await;
        });
    }

    /// Notes waiting from our other phones, and device requests to us as a
    /// group's owner. Returns how many were handled.
    pub fn process_device_inbox(&self) -> u32 {
        let (notes, requests) = {
            let conn = self.db.connection();
            let notes = KeyValueRepo::list_prefix(&conn, KV_DEVSYNC_IN).unwrap_or_default();
            let requests: Vec<(String, String)> = KeyValueRepo::list_prefix(&conn, "join_req:").unwrap_or_default()
                .into_iter()
                .filter(|(_, v)| {
                    let t = serde_json::from_str::<serde_json::Value>(v).ok()
                        .and_then(|j| j.get("token").and_then(|x| x.as_str()).map(str::to_string)).unwrap_or_default();
                    t.starts_with(TOKEN_DEVICE) || t.starts_with(TOKEN_UNLINK)
                })
                .collect();
            for (k, _) in notes.iter().chain(requests.iter()) {
                let _ = KeyValueRepo::delete(&conn, k);
            }
            (notes, requests)
        };
        let me = registry::this_device_id(&self.db.connection());
        let mut handled = 0u32;
        // In the order they were written (pin, then unpin, stays unpinned).
        let mut notes: Vec<serde_json::Value> = notes.into_iter().filter_map(|(_, b)| serde_json::from_str(&b).ok()).collect();
        notes.sort_by_key(|op| op.get("at").and_then(|x| x.as_i64()).unwrap_or(0));
        for op in notes {
            if op.get("from").and_then(|x| x.as_str()) == Some(me.as_str()) { continue; }
            APPLYING.with(|a| a.set(true));
            self.apply_note(&op);
            APPLYING.with(|a| a.set(false));
            handled += 1;
        }
        for (key, held) in requests {
            let Ok(v) = serde_json::from_str::<serde_json::Value>(&held) else { continue };
            // join_req:<chat>:<requester>
            let mut parts = key.splitn(3, ':').skip(1);
            let (Some(chat), Some(requester)) = (parts.next(), parts.next()) else { continue };
            self.answer_device_request(chat, requester, &v);
            handled += 1;
        }
        handled
    }

    fn apply_note(&self, op: &serde_json::Value) {
        let s = |k: &str| op.get(k).and_then(|x| x.as_str()).unwrap_or_default().to_string();
        let chat = s("chat");
        match s("op").as_str() {
            "hello" => self.on_hello(op),
            "devices" => {
                let list: Vec<Device> = serde_json::from_value(op.get("devices").cloned().unwrap_or_default()).unwrap_or_default();
                let conn = self.db.connection();
                let me = registry::this_device_id(&conn);
                if list.iter().any(|d| d.id == me) {
                    registry::save(&conn, &list);
                    drop(conn);
                    self.callback.on_devices_event("devices");
                }
            }
            "unlinked" => {
                if s("id") == registry::this_device_id(&self.db.connection()) {
                    tracing::warn!(target: "ACCOUNT", "this phone was unlinked from the account");
                    self.callback.on_devices_event("unlinked");
                }
            }
            "history" => self.apply_history(op),
            "read" => {
                let through = s("through");
                let conn = self.db.connection();
                let _ = MessageRepo::mark_chat_read(&conn, &chat, (!through.is_empty()).then_some(through.as_str()));
                let _ = ChatRepo::set_unread_override(&conn, &chat, false);
                drop(conn);
                self.callback.on_chat_metadata_updated(&chat);
            }
            "unread" => {
                let _ = ChatRepo::set_unread_override(&self.db.connection(), &chat, true);
                self.callback.on_chat_metadata_updated(&chat);
            }
            "pin" => {
                let _ = ChatRepo::set_pinned(&self.db.connection(), &chat, op["on"].as_bool().unwrap_or(false));
                self.callback.on_chat_metadata_updated(&chat);
            }
            "mute" => {
                let until = op["until"].as_i64().filter(|u| *u > 0);
                let _ = ChatRepo::set_muted(&self.db.connection(), &chat, until);
                self.callback.on_chat_metadata_updated(&chat);
            }
            "archive" => {
                let _ = ChatRepo::set_archived(&self.db.connection(), &chat, op["on"].as_bool().unwrap_or(false));
                self.callback.on_chat_metadata_updated(&chat);
            }
            "deleted" => {
                let exists = ChatRepo::get(&self.db.connection(), &chat).ok().flatten().is_some();
                if exists {
                    let _ = self.delete_chat_here(&chat);
                    self.callback.on_chat_metadata_updated(&chat);
                }
            }
            "saved" => {
                if let Some(msg) = op.get("msg").and_then(|m| serde_json::from_value::<AlphaMessage>(m.clone()).ok()) {
                    let known = MessageRepo::get(&self.db.connection(), msg.id()).ok().flatten().is_some();
                    if !known && self.persist_alpha_message(SAVED_MESSAGES_CHAT_ID, &msg, "self").is_ok() {
                        self.callback.on_message_received(SAVED_MESSAGES_CHAT_ID, &serde_json::to_string(&msg).unwrap_or_default());
                    }
                }
            }
            "profile" => self.apply_profile_note(op),
            other => tracing::info!(target: "ACCOUNT", "device note '{other}' not understood — ignored"),
        }
    }

    /// Our profile as our other phone changed it: name, about, and the photo
    /// it already uploaded (fetched once; nothing is sent from here).
    fn apply_profile_note(&self, op: &serde_json::Value) {
        let s = |k: &str| op.get(k).and_then(|x| x.as_str()).unwrap_or_default().to_string();
        {
            let conn = self.db.connection();
            let _ = KeyValueRepo::set(&conn, PROFILE_KV_NAME, &s("name"));
            let _ = KeyValueRepo::set(&conn, PROFILE_KV_BIO, &s("bio"));
        }
        let (r, h) = (s("avatar_ref"), s("avatar_hash"));
        let have = KeyValueRepo::get(&self.db.connection(), PROFILE_KV_AVATAR_MHASH).ok().flatten().unwrap_or_default();
        if !r.is_empty() && h != have {
            if let Some(dir) = self.contact_avatars_dir.parent() {
                let path = dir.join(format!("my_avatar_{}.jpg", &h[..h.len().min(12)]));
                let path = path.to_string_lossy().into_owned();
                if self.download_media(r.clone(), path.clone()).is_ok() {
                    let conn = self.db.connection();
                    let _ = KeyValueRepo::set(&conn, PROFILE_KV_AVATAR, &path);
                    let _ = KeyValueRepo::set(&conn, PROFILE_KV_AVATAR_REF, &r);
                    let _ = KeyValueRepo::set(&conn, PROFILE_KV_AVATAR_MHASH, &h);
                }
            }
        }
        self.callback.on_devices_event("profile");
    }

    // --- The new phone ---------------------------------------------------------------

    /// Start waiting to be linked: the text of the QR to show.
    pub fn link_begin(&self) -> String {
        let keys = nostr::Keys::generate();
        let secret = hex::encode(&uuid::Uuid::new_v4().as_bytes()[..16]);
        let qr = format!("{QR_PREFIX}{}:{}", keys.public_key().to_hex(), secret);
        if let Ok(mut w) = link_wait().lock() {
            *w = Some(LinkWait { keys, secret, since: now_secs().saturating_sub(WRAP_BACKDATE_SECS), offer: None });
        }
        qr
    }

    /// Whether the account came: `{"state": "waiting"}` or
    /// `{"state": "offer", "name", "npub"}` (then [`Self::link_accept`]).
    pub fn link_poll(&self) -> String {
        use nostr::nips::nip19::{FromBech32, ToBech32};
        let waiting = || serde_json::json!({ "state": "waiting" }).to_string();
        let Some((keys, secret, since, offer)) = link_wait().lock().ok().and_then(|w| {
            w.as_ref().map(|w| (w.keys.clone(), w.secret.clone(), w.since, w.offer.clone()))
        }) else { return serde_json::json!({ "state": "idle" }).to_string() };
        let found = offer.or_else(|| {
            let bridge = NostrMlsBridge::new((*self.transport).clone());
            let events = runtime().block_on(bridge.fetch_gift_wraps_for(&keys.public_key(), since));
            events.iter().find_map(|ev| {
                let (env, sender) = runtime().block_on(alpha_transport::GiftWrapReader::unwrap(&keys, ev)).ok()?;
                if env.message_type != MlsMessageType::DeviceSync { return None; }
                let mut v: serde_json::Value = serde_json::from_slice(&env.payload).ok()?;
                if v["op"].as_str() != Some("offer") || v["secret"].as_str() != Some(secret.as_str()) { return None; }
                // The account handed over is the one whose key sealed it.
                let sk = nostr::SecretKey::from_bech32(v["nsec"].as_str()?).ok()?;
                if nostr::Keys::new(sk).public_key() != sender { return None; }
                v["npub"] = serde_json::Value::String(sender.to_bech32().ok()?);
                Some(v)
            })
        });
        let Some(offer) = found else { return waiting() };
        if let Ok(mut w) = link_wait().lock() {
            if let Some(w) = w.as_mut() { w.offer = Some(offer.clone()); }
        }
        serde_json::json!({
            "state": "offer",
            "name": offer["profile"]["name"].as_str().unwrap_or_default(),
            "npub": offer["npub"].as_str().unwrap_or_default(),
        }).to_string()
    }

    /// Take the account offered to this phone and tell the linking phone we
    /// are here. [device_name]: how our other phones will list this one.
    pub fn link_accept(&self, device_name: String) -> Result<(), MessengerError> {
        let bad = |d: &str| MessengerError::InvalidArgument { description: format!("link: {d}") };
        let (offer, secret) = link_wait().lock().ok()
            .and_then(|w| w.as_ref().and_then(|w| w.offer.clone().map(|o| (o, w.secret.clone()))))
            .ok_or_else(|| bad("no offer yet"))?;
        let s = |v: &serde_json::Value, k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or_default().to_string();

        self.import_identity(s(&offer, "nsec"))?;
        let words = s(&offer, "mnemonic");
        if !words.is_empty() { let _ = self.store_mnemonic(words); }

        // Our profile, as the other phones show it.
        let profile = &offer["profile"];
        let avatar = unb64(&s(profile, "avatar")).filter(|b| !b.is_empty()).and_then(|bytes| {
            let path = self.contact_avatars_dir.parent()?.join("linked_avatar.jpg");
            std::fs::write(&path, bytes).ok()?;
            Some(path.to_string_lossy().into_owned())
        });
        let _ = self.set_my_profile(Some(s(profile, "name")), Some(s(profile, "bio")), avatar);

        // Their relays too: our events must meet on common ones.
        if let Some(relays) = offer["relays"].as_str().and_then(|j| serde_json::from_str::<Vec<PersistedRelay>>(j).ok()) {
            for r in relays.into_iter().filter(|r| r.enabled) {
                let _ = self.add_relay(r.url, r.read, r.write);
            }
        }

        let own_hex = self.account_keys()?.public_key().to_hex();
        let me = self.this_device(&device_name);
        {
            let conn = self.db.connection();
            registry::set_own_account(&conn, &own_hex);
            for (field, key) in [("presence", KV_OWN_PRESENCE_SECRET), ("wake", KV_OWN_WAKE_SECRET)] {
                let v = s(&offer["secrets"], field);
                if v.len() == 64 && v.bytes().all(|b| b.is_ascii_hexdigit()) { let _ = KeyValueRepo::set(&conn, key, &v); }
            }
            let mut list: Vec<Device> = serde_json::from_value(offer["devices"].clone()).unwrap_or_default();
            list.retain(|d| d.id != me.id);
            list.push(me.clone());
            registry::save(&conn, &list);
            // Their Welcomes are expected — the groups' owners' too.
            for chat in offer["chats"].as_array().into_iter().flatten().filter_map(|c| c.as_str()) {
                let _ = KeyValueRepo::set(&conn, &format!("join_wanted:{chat}"), "1");
            }
        }

        // The channels we own hidden: the same persona keys, a leaf of this phone's own.
        let mut persona_kps = serde_json::Map::new();
        let mut persona_sigs = Vec::new();
        for p in offer["personas"].as_array().into_iter().flatten() {
            let (chat, sk) = (s(p, "chat"), s(p, "sk"));
            let Ok(keys) = nostr::Keys::parse(&sk) else { continue };
            let persona = alpha_mls::Persona::generate_bound(&keys)
                .map_err(|e| MessengerError::CryptoError { description: format!("persona: {e}") })?;
            let kp = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?
                .generate_last_resort_key_package(Some(&persona))
                .and_then(|k| k.key_package_bytes())
                .map_err(|e| MessengerError::CryptoError { description: format!("persona key package: {e}") })?;
            let sig = hex::encode(persona.public_key());
            let row = alpha_database::personas::PersonaRow {
                chat_id: chat.clone(),
                nostr_sk: keys.secret_key().to_secret_hex(),
                nostr_pk: keys.public_key().to_hex(),
                mls: persona.to_json().map_err(|e| MessengerError::CryptoError { description: format!("persona: {e}") })?,
                sig: sig.clone(),
                joined: false,
            };
            alpha_database::personas::put(&self.db.connection(), &row)
                .map_err(|e| MessengerError::DatabaseError { description: format!("persona: {e}") })?;
            persona_kps.insert(chat, serde_json::Value::String(b64(&kp)));
            persona_sigs.push(sig);
        }
        self.refresh_persona_inboxes();

        let kp = self.mls_session.lock()
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?
            .generate_last_resort_key_package(None)
            .and_then(|k| k.key_package_bytes())
            .map_err(|e| MessengerError::CryptoError { description: format!("key package: {e}") })?;
        self.persist_mls_storage();

        let mut device = me;
        device.persona_sigs = persona_sigs;
        let hello = serde_json::json!({
            "op": "hello", "from": device.id, "secret": secret,
            "device": device, "kp": b64(&kp), "personas": persona_kps,
        });
        let keys = self.account_keys()?;
        if !self.seal_note(&keys, keys.public_key(), &hello, true) {
            return Err(MessengerError::NetworkError { description: "link: no relay took our answer".to_string() });
        }
        if let Ok(mut w) = link_wait().lock() { *w = None; }
        tracing::info!(target: "ACCOUNT", "linked to the account; waiting to be brought into its chats");
        Ok(())
    }

    /// The history our linking phone sent: its chats, messages and contacts,
    /// written under ours (the rows our Welcomes made give way to them).
    fn apply_history(&self, op: &serde_json::Value) {
        let Some(media_ref) = op.get("ref").and_then(|x| x.as_str()) else { return };
        let Some(dir) = self.contact_avatars_dir.parent().map(|d| d.to_path_buf()) else { return };
        let path = dir.join(format!("link_history_{}.sql", now_secs()));
        if let Err(e) = self.download_media(media_ref.to_string(), path.to_string_lossy().into_owned()) {
            tracing::warn!(target: "ACCOUNT", "history download failed: {e}");
            return;
        }
        let sql = std::fs::read_to_string(&path).unwrap_or_default();
        let _ = std::fs::remove_file(&path);
        if sql.is_empty() { return; }
        let applied = {
            let conn = self.db.connection();
            // Foreign keys off outside the transaction: a replaced chat row must
            // not take its messages (ours already) with it.
            let _ = conn.execute_batch("PRAGMA foreign_keys=OFF;");
            let r = conn.execute_batch("BEGIN;")
                .and_then(|_| alpha_database::backup_sql::apply(&conn, &sql))
                .and_then(|_| conn.execute_batch("COMMIT;"));
            if r.is_err() { let _ = conn.execute_batch("ROLLBACK;"); }
            let _ = conn.execute_batch("PRAGMA foreign_keys=ON;");
            r
        };
        match applied {
            Ok(()) => {
                self.restore_avatar_blobs();
                tracing::info!(target: "ACCOUNT", "history from our other phone applied ({} bytes)", sql.len());
                self.callback.on_sync_complete(0);
                self.callback.on_devices_event("history");
            }
            Err(e) => tracing::warn!(target: "ACCOUNT", "history apply failed: {e}"),
        }
    }

    // --- The linking phone -------------------------------------------------------

    /// Send our account to the phone that shows [qr]. [this_name]: how this
    /// phone is listed. Then [`Self::link_status_json`] follows the link.
    pub fn link_device(&self, qr: String, this_name: String) -> Result<(), MessengerError> {
        if !LINKING_ENABLED {
            return Err(MessengerError::InvalidArgument { description: "linking is not available".to_string() });
        }
        let (eph, secret) = parse_qr(&qr)
            .ok_or_else(|| MessengerError::InvalidArgument { description: "not a link code".to_string() })?;
        let keys = self.account_keys()?;
        self.ensure_registered(&this_name);
        {
            let conn = self.db.connection();
            registry::set_own_account(&conn, &keys.public_key().to_hex());
        }
        let nsec = self.export_nsec()?;
        let profile = self.get_my_profile()?;
        let (relays, devices, chats, personas, secrets) = {
            let conn = self.db.connection();
            let relays = KeyValueRepo::get(&conn, KV_RELAYS).ok().flatten().unwrap_or_default();
            // One account, one presence and one wake topic, whichever phone is on.
            let secrets = serde_json::json!({
                "presence": hex::encode(own_secret(&conn, KV_OWN_PRESENCE_SECRET)),
                "wake": hex::encode(own_secret(&conn, KV_OWN_WAKE_SECRET)),
            });
            let devices = registry::list(&conn);
            let chats: Vec<String> = ChatRepo::list(&conn).unwrap_or_default().into_iter()
                .map(|c| c.id)
                .filter(|id| !is_local_chat(id) && !self.is_secret_chat_in(&conn, id))
                .collect();
            let personas: Vec<serde_json::Value> = alpha_database::personas::all(&conn).into_iter()
                .filter(|p| p.joined && chats.contains(&p.chat_id))
                .map(|p| serde_json::json!({ "chat": p.chat_id, "sk": p.nostr_sk }))
                .collect();
            (relays, devices, chats, personas, secrets)
        };
        let avatar = std::fs::read(&profile.avatar_path).ok().filter(|b| b.len() <= 24 * 1024).map(|b| b64(&b)).unwrap_or_default();
        let mut offer = serde_json::json!({
            "op": "offer", "secret": secret, "nsec": nsec, "mnemonic": self.stored_mnemonic().unwrap_or_default(),
            "profile": { "name": profile.display_name, "bio": profile.bio, "avatar": avatar },
            "relays": relays, "devices": devices, "chats": chats, "personas": personas, "secrets": secrets,
            "from": registry::this_device_id(&self.db.connection()),
        });
        // Too much for one wrap: the photo and the chat list are the first to go
        // (the chats then come as plain Welcomes, the photo with the profile).
        for drop in ["avatar", "chats"] {
            if offer.to_string().len() <= MAX_NOTE { break; }
            if drop == "avatar" { offer["profile"]["avatar"] = "".into(); } else { offer["chats"] = serde_json::json!([]); }
        }
        if !self.seal_note(&keys, eph, &offer, true) {
            return Err(MessengerError::NetworkError { description: "link: no relay took the offer".to_string() });
        }
        self.set_link_run(serde_json::json!({
            "secret": secret, "stage": "offered", "at": now_secs(), "total": 0, "done": 0, "asked": 0,
        }));
        tracing::info!(target: "ACCOUNT", "link offer sent");
        Ok(())
    }

    fn set_link_run(&self, v: serde_json::Value) {
        let _ = KeyValueRepo::set(&self.db.connection(), KV_LINK_RUN, &v.to_string());
        self.callback.on_devices_event("link");
    }

    fn link_run(&self) -> serde_json::Value {
        KeyValueRepo::get(&self.db.connection(), KV_LINK_RUN).ok().flatten()
            .and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_else(|| serde_json::json!({}))
    }

    /// The link this phone runs: `{stage, total, done, asked}`; stage
    /// "offered" → "adding" → "history" → "done" (or "" when none).
    pub fn link_status_json(&self) -> String {
        let mut v = self.link_run();
        if let Some(o) = v.as_object_mut() { o.remove("secret"); }
        v.to_string()
    }

    pub(super) fn is_secret_chat_in(&self, conn: &rusqlite::Connection, chat_id: &str) -> bool {
        KeyValueRepo::get(conn, &format!("{}{chat_id}", secret_chat::KV_SECRET_CHAT)).ok().flatten().is_some()
    }

    /// The new phone answered: list it, then bring it into our chats.
    fn on_hello(&self, op: &serde_json::Value) {
        let mut run = self.link_run();
        if run["secret"].as_str().is_none() || op["secret"].as_str() != run["secret"].as_str() {
            tracing::warn!(target: "ACCOUNT", "hello for a link we do not run — ignored");
            return;
        }
        // The same answer may come over several relays: one link runs once.
        if run["stage"].as_str() != Some("offered") {
            return;
        }
        let Ok(device) = serde_json::from_value::<Device>(op["device"].clone()) else { return };
        let Some(kp) = op["kp"].as_str().and_then(unb64) else { return };
        if alpha_mls::leaf_binding::key_package_owner(&kp).and_then(|v| v.npub_hex())
            != self.account_keys().ok().map(|k| k.public_key().to_hex())
        {
            tracing::warn!(target: "ACCOUNT", "hello whose KeyPackage is not ours — ignored");
            return;
        }
        let mut device = device;
        device.sig = alpha_mls::leaf_binding::key_package_signature_key(&kp).map(hex::encode).unwrap_or(device.sig);
        {
            let conn = self.db.connection();
            registry::upsert(&conn, device.clone());
        }
        self.publish_device_list();
        let list = registry::list(&self.db.connection());
        self.device_note(serde_json::json!({ "op": "devices", "devices": list }));
        self.callback.on_devices_event("devices");

        // Our pool again, each package naming this phone: published before the
        // update they named none, and a peer must tell our phones apart now.
        if let Ok(me) = self.account_hex() {
            let _ = KeyValueRepo::delete(&self.db.connection(), &format!("last_published_keypackage_at:{me}"));
        }
        if let Err(e) = self.publish_own_key_package() {
            tracing::warn!(target: "ACCOUNT", "linking: our KeyPackages not republished: {e}");
        }

        let persona_kps = op["personas"].as_object().cloned().unwrap_or_default();
        let chats: Vec<String> = {
            let conn = self.db.connection();
            ChatRepo::list(&conn).unwrap_or_default().into_iter()
                .map(|c| c.id)
                .filter(|id| !is_local_chat(id) && !self.is_secret_chat_in(&conn, id))
                .collect()
        };
        run["stage"] = "adding".into();
        run["total"] = chats.len().into();
        self.set_link_run(run.clone());
        let (mut done, mut asked) = (0u32, 0u32);
        for chat in &chats {
            let kp_here = match crate::personas::get(chat) {
                Some(_) => persona_kps.get(chat).and_then(|v| v.as_str()).and_then(unb64),
                None => Some(kp.clone()),
            };
            match kp_here.map(|k| self.bring_into(chat, k)) {
                Some(Ok(true)) => done += 1,
                Some(Ok(false)) => asked += 1,
                Some(Err(e)) => tracing::warn!(target: "ACCOUNT", "linking: chat …{} skipped: {e}", &chat[chat.len().saturating_sub(6)..]),
                None => {}
            }
            run["done"] = (done + asked).into();
            run["asked"] = asked.into();
            self.set_link_run(run.clone());
        }

        run["stage"] = "history".into();
        self.set_link_run(run.clone());
        if let Err(e) = self.send_history() {
            tracing::warn!(target: "ACCOUNT", "linking: history not sent: {e}");
        }
        run["stage"] = "done".into();
        self.set_link_run(run);
        tracing::info!(target: "ACCOUNT", "linked a phone: {done} chat(s) joined, {asked} asked of their owners");
    }

    /// Put our new phone's leaf into [chat_id]. `Ok(true)`: added here;
    /// `Ok(false)`: asked of the group's owner (only the owner commits there).
    fn bring_into(&self, chat_id: &str, kp: Vec<u8>) -> Result<bool, MessengerError> {
        let gid = hex::decode(chat_id).map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
        let active = self.mls_session.lock().ok().and_then(|m| m.group_info(&gid).map(|i| i.is_active)).unwrap_or(false);
        if !active {
            return Err(MessengerError::InvalidArgument { description: "not a member any more".to_string() });
        }
        let me = self.own_hex_in(chat_id);
        let owner = alpha_database::group_roles::owner_of(&self.db.connection(), chat_id);
        match owner {
            Some(o) if o != me => {
                let owner_pk = nostr::PublicKey::from_hex(&o).map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
                self.ask_owner(chat_id, &owner_pk, &format!("{TOKEN_DEVICE}{}", short_hash(&kp)), &b64(&kp))?;
                Ok(false)
            }
            _ => {
                self.add_member_with(chat_id.to_string(), me, vec![kp], true)?;
                Ok(true)
            }
        }
    }

    /// A request to [owner] about a leaf of ours in [chat_id] (add or remove).
    fn ask_owner(&self, chat_id: &str, owner: &nostr::PublicKey, token: &str, kp_b64: &str) -> Result<(), MessengerError> {
        let gid = hex::decode(chat_id).map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
        let keys = self.keys_in(chat_id)?;
        let payload = serde_json::json!({ "token": token, "kp": kp_b64 }).to_string().into_bytes();
        let env = MlsEnvelope::new(MlsMessageType::JoinRequest, gid, 0, payload);
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let to = *owner;
        runtime().block_on(async move { bridge.send_mls_message(&keys, &env, Some(&to), None).await })
            .map_err(|e| MessengerError::NetworkError { description: format!("ask owner: {e}") })?;
        Ok(())
    }

    /// As a group's owner: a member asks to add or remove a leaf of their own
    /// (another phone of theirs). Done only when the leaf proves that member.
    fn answer_device_request(&self, chat_id: &str, requester: &str, held: &serde_json::Value) {
        if !LINKING_ENABLED { return; }
        let token = held["token"].as_str().unwrap_or_default();
        let me = self.own_hex_in(chat_id);
        if alpha_database::group_roles::owner_of(&self.db.connection(), chat_id).as_deref() != Some(me.as_str()) {
            return;
        }
        let Ok(gid) = hex::decode(chat_id) else { return };
        let members = self.mls_session.lock().ok().and_then(|m| m.group_info(&gid)).map(|i| i.members).unwrap_or_default();
        let proven = alpha_mls::leaf_binding::proven_owners(&members);
        let is_member = proven.iter().any(|(_, pk)| pk == requester)
            || members.iter().any(|m| alpha_database::policy::sender_pubkey(&self.db.connection(), chat_id, &hex::encode(&m.signature_key)).as_deref() == Some(requester));
        if !is_member {
            tracing::warn!(target: "ACCOUNT", "device request from a non-member — ignored");
            return;
        }
        if let Some(sig) = token.strip_prefix(TOKEN_UNLINK) {
            let Some(m) = members.iter().find(|m| hex::encode(&m.signature_key) == sig) else { return };
            if proven.iter().any(|(s, pk)| s == sig && pk == requester) {
                match self.remove_leaves(chat_id, &[m.leaf_index]) {
                    Ok(_) => tracing::info!(target: "ACCOUNT", "removed a member's unlinked phone"),
                    Err(e) => tracing::warn!(target: "ACCOUNT", "removing a member's phone failed: {e}"),
                }
            }
            return;
        }
        let Some(kp) = held["kp"].as_str().and_then(unb64) else { return };
        let owner_ok = alpha_mls::leaf_binding::key_package_owner(&kp).and_then(|v| v.npub_hex()).as_deref() == Some(requester);
        let already = alpha_mls::leaf_binding::key_package_signature_key(&kp)
            .is_some_and(|sig| members.iter().any(|m| m.signature_key == sig));
        if !owner_ok || already { return; }
        match self.add_member_with(chat_id.to_string(), requester.to_string(), vec![kp], false) {
            Ok(()) => tracing::info!(target: "ACCOUNT", "added a member's new phone"),
            Err(e) => tracing::warn!(target: "ACCOUNT", "adding a member's phone failed: {e}"),
        }
    }

    /// The history for our new phone, uploaded as one encrypted file.
    fn send_history(&self) -> Result<(), MessengerError> {
        let sql = {
            let conn = self.db.connection();
            self.dump_link_history_sql(&conn)
                .map_err(|e| MessengerError::DatabaseError { description: format!("history dump: {e}") })?
        };
        let dir = self.contact_avatars_dir.parent()
            .ok_or_else(|| MessengerError::InternalError { description: "no files dir".to_string() })?;
        let path = dir.join(format!("link_history_out_{}.sql", now_secs()));
        std::fs::write(&path, sql.as_bytes()).map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let up = self.upload_blob_ref_only(
            path.to_string_lossy().into_owned(), "file".to_string(), None, format!("link-history-{}", now_secs()), None,
        );
        let _ = std::fs::remove_file(&path);
        let ref_json = up?;
        let mr: MediaRef = serde_json::from_str(&ref_json)
            .map_err(|e| MessengerError::InternalError { description: format!("history ref: {e}") })?;
        let slim = self.externalize_ref_if_large(&mr)?;
        let wire = strip_local_path(&serde_json::to_value(&slim).map_err(|e| MessengerError::InternalError { description: e.to_string() })?);
        self.device_note(serde_json::json!({ "op": "history", "ref": wire, "size": sql.len() }));
        Ok(())
    }

    // --- Unlinking ------------------------------------------------------------------

    /// Take [device_id] off the account: out of every chat we may commit in,
    /// asked of the other chats' owners, and told to wipe itself.
    pub fn unlink_device(&self, device_id: String) -> Result<(), MessengerError> {
        let device = {
            let conn = self.db.connection();
            if device_id == registry::this_device_id(&conn) {
                return Err(MessengerError::InvalidArgument { description: "unlink another phone, not this one".to_string() });
            }
            registry::list(&conn).into_iter().find(|d| d.id == device_id)
                .ok_or_else(|| MessengerError::InvalidArgument { description: "no such phone".to_string() })?
        };
        let leaves: Vec<String> = std::iter::once(device.sig.clone()).chain(device.persona_sigs.clone())
            .filter(|s| !s.is_empty()).collect();
        let chats: Vec<String> = ChatRepo::list(&self.db.connection()).unwrap_or_default().into_iter().map(|c| c.id)
            .filter(|id| !is_local_chat(id)).collect();
        for chat in chats {
            let Ok(gid) = hex::decode(&chat) else { continue };
            let members = self.mls_session.lock().ok().and_then(|m| m.group_info(&gid)).map(|i| i.members).unwrap_or_default();
            let me = self.own_hex_in(&chat);
            let owner = alpha_database::group_roles::owner_of(&self.db.connection(), &chat);
            for m in members.iter().filter(|m| leaves.contains(&hex::encode(&m.signature_key))) {
                let r = match owner.as_deref() {
                    Some(o) if o != me => nostr::PublicKey::from_hex(o)
                        .map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })
                        .and_then(|pk| self.ask_owner(&chat, &pk, &format!("{TOKEN_UNLINK}{}", hex::encode(&m.signature_key)), "")),
                    _ => self.remove_leaves(&chat, &[m.leaf_index]).map(|_| ()),
                };
                if let Err(e) = r {
                    tracing::warn!(target: "ACCOUNT", "unlink: chat …{}: {e}", &chat[chat.len().saturating_sub(6)..]);
                }
            }
        }
        // Told before it leaves the list: it then wipes itself.
        self.device_note(serde_json::json!({ "op": "unlinked", "id": device.id }));
        {
            let conn = self.db.connection();
            registry::remove(&conn, &device.id);
        }
        self.publish_device_list();
        let list = registry::list(&self.db.connection());
        if list.len() > 1 {
            self.device_note(serde_json::json!({ "op": "devices", "devices": list }));
        }
        self.callback.on_devices_event("devices");
        Ok(())
    }

    // --- Adding a person: all their phones ----------------------------------------

    /// KeyPackages for adding [pk]: one per phone their own list names, or the
    /// newest one while they list none (a single phone, or an older app).
    pub(super) fn member_key_packages(&self, pk: &nostr::PublicKey) -> Result<Vec<Vec<u8>>, MessengerError> {
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let rt = runtime();
        let (tagged, listed) = rt.block_on(async {
            tokio::join!(bridge.fetch_device_key_packages(pk), bridge.fetch_device_list(pk))
        });
        // Frozen: a person is one phone. A list left from an old link would add
        // a leaf for a phone that no longer holds its key.
        let listed = listed.map(|(ids, _)| ids).filter(|ids| LINKING_ENABLED && ids.len() > 1);
        let hex = pk.to_hex();
        let Some(ids) = listed else {
            return Ok(vec![self.pick_distinct_kp(&hex, tagged.into_iter().map(|(_, kp)| kp).collect())?]);
        };
        let mut out = Vec::new();
        for id in &ids {
            let pool: Vec<Vec<u8>> = tagged.iter().filter(|(d, _)| d.as_deref() == Some(id.as_str())).map(|(_, kp)| kp.clone()).collect();
            match self.pick_distinct_kp(&hex, pool) {
                Ok(kp) => out.push(kp),
                Err(e) => tracing::warn!(target: "ACCOUNT", "a phone of this person has no KeyPackage now: {e}"),
            }
        }
        if out.is_empty() {
            out.push(self.pick_distinct_kp(&hex, tagged.into_iter().map(|(_, kp)| kp).collect())?);
        }
        Ok(out)
    }

    /// KeyPackages of our other phones, for a chat this one creates.
    pub(super) fn own_other_device_kps(&self) -> Vec<Vec<u8>> {
        if !registry::linked() { return Vec::new(); }
        let Ok(keys) = self.account_keys() else { return Vec::new() };
        let others: Vec<String> = {
            let conn = self.db.connection();
            let me = registry::this_device_id(&conn);
            registry::list(&conn).into_iter().map(|d| d.id).filter(|id| *id != me).collect()
        };
        let bridge = NostrMlsBridge::new((*self.transport).clone());
        let tagged = runtime().block_on(bridge.fetch_device_key_packages(&keys.public_key()));
        let hex = keys.public_key().to_hex();
        others.iter().filter_map(|id| {
            let pool: Vec<Vec<u8>> = tagged.iter().filter(|(d, _)| d.as_deref() == Some(id.as_str())).map(|(_, kp)| kp.clone()).collect();
            self.pick_distinct_kp(&hex, pool).map_err(|e| tracing::warn!(target: "ACCOUNT", "our other phone has no KeyPackage now: {e}")).ok()
        }).collect()
    }
}
