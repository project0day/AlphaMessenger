//! Secret chats: a two-person MLS group between two personas, set up through
//! the ordinary chat with that contact and carried only on each persona's own
//! Tor-only connection. Neither account appears in it, and no relay sees a
//! link between it and anything else of ours.
//!
//! Setup, all inside the ordinary chat's MLS ciphertext:
//! `invite` (A, naming the secret group) → `accept` with B's persona
//! KeyPackage → `welcome` (A adds it) → B joins as its persona. `decline` ends
//! an offer. B sees the invitation as the secret chat itself, waiting for an
//! answer; the ordinary chat shows nothing of it.

use super::*;

pub(super) type Mls = Arc<TrackedMutex<MlsSessionManager<openmls_rust_crypto::OpenMlsRustCrypto>>>;
pub(super) type Identity = Arc<TrackedMutex<Option<DualIdentity>>>;

/// Every key of this module starts so (kept out of backups).
pub(super) const KV_SECRET_PREFIX: &str = "secret_";
/// Secret chat id -> the ordinary chat it was set up from (local only).
pub(super) const KV_SECRET_CHAT: &str = alpha_database::secret_media::SECRET_CHAT_PREFIX;
/// Invite id -> what we sent: `{chat, gid, welcome}`.
const KV_SECRET_OUT: &str = "secret_out:";
/// Invite id -> what we accepted: `{chat, persona, nostr_sk}`.
const KV_SECRET_IN: &str = "secret_in:";
/// Invite id -> an offer waiting for our answer: `{chat, gid, ts}`.
const KV_SECRET_OFFER: &str = "secret_offer:";
/// Secret chat id -> the invite id while we have not answered or joined yet.
const KV_SECRET_PENDING: &str = "secret_pending:";
/// Secret chat id -> the relays both phones use for it (JSON array).
const KV_SECRET_RELAYS: &str = "secret_relays:";
/// How many relays carry one secret chat: enough to survive one going down,
/// few enough that each idle chat costs little battery.
const SECRET_RELAY_COUNT: usize = 3;
/// Unanswered invitations one contact may have waiting.
const MAX_WAITING_OFFERS: usize = 3;
/// Relays seen to serve Tor connections well, tried first.
const TOR_FRIENDLY: &[&str] = &["wss://relay.primal.net", "wss://relay.nostr.net"];
/// Relays seen to refuse Tor exits: never picked.
const TOR_REFUSING: &[&str] = &["wss://nos.lol", "wss://nostr.mom"];

fn norm(url: &str) -> String {
    url.trim().trim_end_matches('/').to_ascii_lowercase()
}

/// The relays for a new secret chat: our working ones that take Tor, the
/// known good ones first, filled up from those when we have too few.
fn pick_relays(enabled: Vec<String>) -> Vec<String> {
    let refusing = |u: &str| TOR_REFUSING.iter().any(|r| norm(r) == norm(u));
    let friendly = |u: &str| TOR_FRIENDLY.iter().any(|r| norm(r) == norm(u));
    let mut ours: Vec<String> = enabled.into_iter()
        .filter(|u| u.starts_with("wss://") && !refusing(u))
        .collect();
    ours.sort_by_key(|u| !friendly(u));
    let mut picked: Vec<String> = Vec::new();
    for u in ours.into_iter().chain(TOR_FRIENDLY.iter().map(|u| u.to_string())) {
        if picked.len() == SECRET_RELAY_COUNT { break; }
        if !picked.iter().any(|p| norm(p) == norm(&u)) { picked.push(u); }
    }
    picked
}

fn relays_of(conn: &rusqlite::Connection, chat_id: &str) -> Vec<String> {
    KeyValueRepo::get(conn, &format!("{KV_SECRET_RELAYS}{chat_id}")).ok().flatten()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

/// Unix secs of the chat's last message (its creation when it has none).
fn last_activity(conn: &rusqlite::Connection, chat_id: &str) -> u64 {
    let at: Option<String> = conn.query_row(
        "SELECT COALESCE((SELECT MAX(created_at) FROM messages WHERE chat_id = ?1), (SELECT created_at FROM chats WHERE id = ?1))",
        rusqlite::params![chat_id],
        |r| r.get(0),
    ).ok().flatten();
    at.and_then(|t| chrono::DateTime::parse_from_rfc3339(&t).ok()).map(|t| t.timestamp().max(0) as u64).unwrap_or(0)
}
/// An invitation shown in the ordinary chat: this mark, then the invite id.
/// The app draws it as a card with Accept / Decline.
const INVITE_MARK: &str = "\u{2}SEC\u{2}";

/// The invitation as a row of the ordinary chat, so it reaches the chat list
/// and the notification like any message.
fn show_invite(db: &Arc<DatabasePool>, callback: &CallbackDispatcher, chat_id: &str, sender_id: &str, invite_id: &str) {
    let row = alpha_database::new_message(
        chat_id.to_string(), sender_id.to_string(), alpha_database::models::MessageType::Text,
        Some(format!("{INVITE_MARK}{invite_id}")), None, None, None, None,
    );
    if alpha_database::MessageRepo::insert(&db.connection(), &row).is_ok() {
        callback.on_message_received(chat_id, &serde_json::json!({ "type": "text", "id": row.id }).to_string());
    }
}

pub(super) struct Ctx<'a> {
    pub db: &'a Arc<DatabasePool>,
    pub mls: &'a Mls,
    pub identity: &'a Identity,
    pub transport: &'a NostrRelayClient,
    pub callback: &'a CallbackDispatcher,
}

pub(super) fn is_secret(conn: &rusqlite::Connection, chat_id: &str) -> bool {
    KeyValueRepo::get(conn, &format!("{KV_SECRET_CHAT}{chat_id}")).ok().flatten().is_some()
}

/// A secret chat never reaches the wake server: that request goes outside Tor and
/// would tie the send's time to the peer's wake topic.
pub(super) fn wakes_peers(conn: &rusqlite::Connection, chat_id: &str) -> bool {
    !is_secret(conn, chat_id)
}

fn get_json(conn: &rusqlite::Connection, key: &str) -> Option<serde_json::Value> {
    KeyValueRepo::get(conn, key).ok().flatten().and_then(|s| serde_json::from_str(&s).ok())
}

fn b64(bytes: &[u8]) -> String {
    base64::Engine::encode(&base64::engine::general_purpose::STANDARD, bytes)
}

fn unb64(s: &str) -> Option<Vec<u8>> {
    base64::Engine::decode(&base64::engine::general_purpose::STANDARD, s).ok()
}

pub(super) fn signal(invite_id: &str, step: &str, data: String) -> AlphaMessage {
    AlphaMessage::SecretChat {
        invite_id: invite_id.to_string(),
        step: step.to_string(),
        data,
        ts: chrono::Utc::now().to_rfc3339(),
    }
}

/// Which groups we speak in as which persona, and which personas use Tor only
/// (those of secret chats).
pub(super) fn persona_routes(
    conn: &rusqlite::Connection,
) -> (std::collections::HashMap<Vec<u8>, nostr::PublicKey>, std::collections::HashSet<nostr::PublicKey>) {
    let mut groups = std::collections::HashMap::new();
    let mut tor_only = std::collections::HashSet::new();
    for r in alpha_database::personas::all(conn) {
        let (Ok(gid), Ok(pk)) = (hex::decode(&r.chat_id), nostr::PublicKey::from_hex(&r.nostr_pk)) else { continue };
        if !r.joined { continue; }
        if is_secret(conn, &r.chat_id) { tor_only.insert(pk); }
        groups.insert(gid, pk);
    }
    (groups, tor_only)
}

/// How each secret chat's connection runs: its own relays, and its last
/// message (a long-quiet chat sleeps between short visits).
pub(super) fn persona_plans(conn: &rusqlite::Connection) -> std::collections::HashMap<nostr::PublicKey, alpha_transport::PersonaPlan> {
    alpha_database::personas::all(conn).into_iter().filter_map(|r| {
        if !is_secret(conn, &r.chat_id) { return None; }
        let pk = nostr::PublicKey::from_hex(&r.nostr_pk).ok()?;
        Some((pk, alpha_transport::PersonaPlan {
            relays: relays_of(conn, &r.chat_id),
            active_at: last_activity(conn, &r.chat_id),
            // Until it has joined it waits for the Welcome: no sleeping then.
            may_sleep: r.joined,
        }))
    }).collect()
}

/// Hand the transport the current persona inboxes, routes and plans;
/// resubscribe when anything changed.
pub(super) fn refresh_transport(db: &Arc<DatabasePool>, identity: &Identity, transport: &NostrRelayClient) {
    let (groups, tor_only, plans) = {
        let conn = db.connection();
        let (groups, tor_only) = persona_routes(&conn);
        (groups, tor_only, persona_plans(&conn))
    };
    let inboxes_changed = transport.set_persona_inboxes(crate::personas::inboxes());
    let routes_changed = transport.set_persona_routes(groups, tor_only);
    let plans_changed = transport.set_persona_plans(plans);
    if inboxes_changed || routes_changed || plans_changed {
        resubscribe(identity, transport);
    }
}

fn resubscribe(identity: &Identity, transport: &NostrRelayClient) {
    let Some(pk) = identity.lock().ok().and_then(|g| g.as_ref().map(|id| id.nostr().public_key())) else { return };
    let transport = transport.clone();
    runtime().spawn(async move {
        if let Err(e) = transport.subscribe_mls_and_giftwrap(&pk).await {
            tracing::debug!("resubscribe skipped: {}", e);
        }
    });
}

fn save_mls(db: &Arc<DatabasePool>, snapshot: Vec<(Vec<u8>, Vec<u8>)>) {
    if !snapshot.is_empty() {
        let _ = alpha_database::MlsStorageRepo::save_snapshot(&db.connection(), &snapshot);
    }
}

/// Send a setup step into the ordinary chat from outside a core method.
fn send_in(ctx: &Ctx<'_>, chat_id: &str, msg: &AlphaMessage) {
    let Ok(gid) = hex::decode(chat_id) else { return };
    let Ok(payload) = alpha_messages::serialize(msg) else { return };
    let sent = ctx.mls.lock().ok().and_then(|mut m| {
        let ct = m.send(&gid, &payload).ok()?;
        let epoch = m.group_info(&gid).map(|i| i.epoch).unwrap_or(0);
        Some((ct, epoch, m.snapshot_storage()))
    });
    let Some((ct, epoch, snapshot)) = sent else { return };
    save_mls(ctx.db, snapshot);
    let keys = ctx.identity.lock().ok().and_then(|g| g.as_ref().map(|id| id.nostr().keypair().keys().clone()));
    let Some(keys) = keys else { return };
    let env = MlsEnvelope::new(MlsMessageType::Application, gid, epoch, ct);
    let bridge = NostrMlsBridge::new(ctx.transport.clone());
    runtime().spawn(async move {
        if let Err(e) = bridge.send_mls_message(&keys, &env, None, None).await {
            tracing::warn!(target: "ACCOUNT", "secret chat step not sent: {e}");
        }
    });
}

/// A 1:1 chat: the only kind a secret chat is set up from.
fn is_direct(mls: &Mls, chat_id: &str) -> bool {
    let Ok(gid) = hex::decode(chat_id) else { return false };
    mls.lock().ok().and_then(|m| m.group_info(&gid).map(|i| i.members.len() == 2)).unwrap_or(false)
}

/// A setup step arrived in the ordinary chat `chat_id` (or `end` in the
/// secret chat itself).
pub(super) fn on_signal(ctx: &Ctx<'_>, chat_id: &str, sender_id: &str, invite_id: &str, step: &str, data: &str) {
    if step == "end" {
        if is_secret(&ctx.db.connection(), chat_id) {
            wipe(ctx.db, ctx.mls, ctx.identity, ctx.transport, chat_id);
            tracing::info!(target: "ACCOUNT", "secret chat ended by the other side");
            ctx.callback.on_chat_metadata_updated(chat_id);
        }
        return;
    }
    if !is_direct(ctx.mls, chat_id) {
        tracing::warn!(target: "ACCOUNT", "secret chat step outside a 1:1 chat ignored");
        return;
    }
    match step {
        "invite" => on_invite(ctx, chat_id, sender_id, invite_id, data),
        "accept" => on_accept(ctx, chat_id, invite_id, data),
        "welcome" => on_welcome(ctx, chat_id, invite_id, data),
        "decline" => on_decline(ctx, chat_id, invite_id),
        _ => {}
    }
}

fn on_invite(ctx: &Ctx<'_>, chat_id: &str, sender_id: &str, invite_id: &str, data: &str) {
    // `{gid, relays}`; an inviter without relay choice sends the bare group id.
    let (gid, relays): (String, Vec<String>) = match serde_json::from_str::<serde_json::Value>(data) {
        Ok(v) if v.is_object() => (
            v["gid"].as_str().unwrap_or_default().to_string(),
            v["relays"].as_array().map(|a| {
                a.iter().filter_map(|u| u.as_str())
                    .filter(|u| u.starts_with("wss://") && u.len() < 200)
                    .take(SECRET_RELAY_COUNT).map(String::from).collect()
            }).unwrap_or_default(),
        ),
        _ => (data.to_string(), Vec::new()),
    };
    let gid = gid.as_str();
    let conn = ctx.db.connection();
    let key = format!("{KV_SECRET_OFFER}{invite_id}");
    if KeyValueRepo::get(&conn, &key).ok().flatten().is_some()
        || KeyValueRepo::get(&conn, &format!("{KV_SECRET_IN}{invite_id}")).ok().flatten().is_some()
    {
        return;
    }
    // A contact cannot flood us with secret chats: a few unanswered at most.
    let waiting = KeyValueRepo::list_prefix(&conn, KV_SECRET_OFFER).unwrap_or_default().iter()
        .filter(|(_, v)| serde_json::from_str::<serde_json::Value>(v).ok()
            .is_some_and(|o| o["chat"].as_str() == Some(chat_id)))
        .count();
    if waiting >= MAX_WAITING_OFFERS {
        tracing::warn!(target: "ACCOUNT", "secret chat invitation ignored: {waiting} already waiting from this contact");
        return;
    }
    // The invitation is the secret chat itself, waiting for our answer. A
    // group id we already have (or a malformed one) is never taken over.
    let valid = gid.len() >= 32 && gid.len() <= 128 && gid.bytes().all(|b| b.is_ascii_hexdigit());
    if !valid || ChatRepo::get(&conn, gid).ok().flatten().is_some() {
        tracing::warn!(target: "ACCOUNT", "secret chat invitation with an unusable group id ignored");
        return;
    }
    let offer = serde_json::json!({ "chat": chat_id, "gid": gid, "ts": chrono::Utc::now().timestamp() });
    let _ = KeyValueRepo::set(&conn, &key, &offer.to_string());
    let _ = ChatRepo::insert(&conn, &new_chat(gid.to_string(), alpha_database::models::ChatType::Direct, None, 0));
    let _ = KeyValueRepo::set(&conn, &format!("{KV_SECRET_CHAT}{gid}"), chat_id);
    let _ = KeyValueRepo::set(&conn, &format!("{KV_SECRET_PENDING}{gid}"), invite_id);
    if !relays.is_empty() {
        let _ = KeyValueRepo::set(&conn, &format!("{KV_SECRET_RELAYS}{gid}"), &serde_json::to_string(&relays).unwrap_or_default());
    }
    drop(conn);
    tracing::info!(target: "ACCOUNT", "secret chat offered in {chat_id}");
    ctx.callback.on_chat_created(gid, "secret");
    show_invite(ctx.db, ctx.callback, gid, sender_id, invite_id);
}

/// B sent its persona's KeyPackage: add it to our secret group and send the
/// Welcome back. Repeated: the same Welcome again.
fn on_accept(ctx: &Ctx<'_>, chat_id: &str, invite_id: &str, data: &str) {
    let key = format!("{KV_SECRET_OUT}{invite_id}");
    let Some(mut out) = get_json(&ctx.db.connection(), &key) else { return };
    if out["chat"].as_str() != Some(chat_id) {
        tracing::warn!(target: "ACCOUNT", "secret chat accept from another chat ignored");
        return;
    }
    if let Some(w) = out["welcome"].as_str().filter(|w| !w.is_empty()) {
        send_in(ctx, chat_id, &signal(invite_id, "welcome", w.to_string()));
        return;
    }
    let (Some(gid), Some(kp)) = (out["gid"].as_str().and_then(|g| hex::decode(g).ok()), unb64(data)) else { return };
    // The invitee's leaf must prove its persona key: an unsigned or forged one
    // is not let into a secret chat.
    if !matches!(alpha_mls::leaf_binding::key_package_owner(&kp), Some(alpha_mls::LeafVerdict::Bound(_))) {
        tracing::warn!(target: "ACCOUNT", "secret chat accept with an unproven key refused");
        return;
    }
    let added = ctx.mls.lock().ok().and_then(|mut m| {
        let r = m.add_member_to_group(&gid, &kp).map_err(|e| tracing::warn!(target: "ACCOUNT", "secret chat add: {e}")).ok()?;
        let proven = m.group_info(&gid).map(|i| alpha_mls::leaf_binding::proven_owners(&i.members)).unwrap_or_default();
        Some((r.1, proven, m.snapshot_storage()))
    });
    let Some((welcome, proven, snapshot)) = added else { return };
    save_mls(ctx.db, snapshot);
    let welcome = b64(&welcome);
    {
        let conn = ctx.db.connection();
        out["welcome"] = serde_json::Value::String(welcome.clone());
        let _ = KeyValueRepo::set(&conn, &key, &out.to_string());
        alpha_database::policy::record_proven(&conn, &hex::encode(&gid), &proven);
    }
    send_in(ctx, chat_id, &signal(invite_id, "welcome", welcome));
    tracing::info!(target: "ACCOUNT", "secret chat {}: invitee added", hex::encode(&gid));
    ctx.callback.on_chat_metadata_updated(&hex::encode(&gid));
}

/// A's Welcome into the secret group: join as the persona we accepted with.
fn on_welcome(ctx: &Ctx<'_>, chat_id: &str, invite_id: &str, data: &str) {
    let key = format!("{KV_SECRET_IN}{invite_id}");
    let Some(pending) = get_json(&ctx.db.connection(), &key) else { return };
    if pending["chat"].as_str() != Some(chat_id) {
        tracing::warn!(target: "ACCOUNT", "secret chat welcome from another chat ignored");
        return;
    }
    let (Some(persona_json), Some(nostr_sk), Some(welcome)) = (
        pending["persona"].as_str(), pending["nostr_sk"].as_str(), unb64(data),
    ) else { return };
    let (Ok(persona), Ok(keys)) = (alpha_mls::Persona::from_json(persona_json), nostr::Keys::parse(nostr_sk)) else { return };
    let sig_hex = hex::encode(persona.public_key());

    let joined = ctx.mls.lock().ok().and_then(|mut m| {
        let gid = m.join_chat(&welcome).map_err(|e| tracing::warn!(target: "ACCOUNT", "secret chat join: {e}")).ok()?;
        let info = m.group_info(&gid)?;
        // Exactly the two of us, and the leaf we accepted with among them.
        let ours = info.members.iter().any(|x| hex::encode(&x.signature_key) == sig_hex);
        if info.members.len() != 2 || !ours {
            tracing::warn!(target: "ACCOUNT", "secret chat welcome into an unexpected group refused");
            let _ = m.delete_group(&gid);
            return None;
        }
        let proven = alpha_mls::leaf_binding::proven_owners(&info.members);
        m.set_persona(&gid, persona);
        Some((gid, info.epoch, proven, m.snapshot_storage()))
    });
    let Some((gid, epoch, proven, snapshot)) = joined else { return };
    save_mls(ctx.db, snapshot);
    let secret_id = hex::encode(&gid);
    {
        let conn = ctx.db.connection();
        let _ = alpha_database::personas::put(&conn, &alpha_database::personas::PersonaRow {
            chat_id: secret_id.clone(),
            nostr_sk: keys.secret_key().to_secret_hex(),
            nostr_pk: keys.public_key().to_hex(),
            mls: persona_json.to_string(),
            sig: sig_hex,
            joined: true,
        });
        // The chat row stands since the invitation; its invitation row goes.
        if ChatRepo::get(&conn, &secret_id).ok().flatten().is_none() {
            let _ = ChatRepo::insert(&conn, &new_chat(secret_id.clone(), alpha_database::models::ChatType::Direct, None, epoch as i64));
        }
        let _ = alpha_database::MessageRepo::delete_for_chat(&conn, &secret_id);
        let _ = MlsGroupRepo::insert(&conn, &new_mls_group_row(secret_id.clone(), epoch as i64, 0, true));
        let _ = KeyValueRepo::set(&conn, &format!("{KV_SECRET_CHAT}{secret_id}"), chat_id);
        let _ = KeyValueRepo::set(&conn, &format!("{KV_NO_STORE_PREFIX}{secret_id}"), "1");
        let _ = KeyValueRepo::delete(&conn, &format!("{KV_SECRET_PENDING}{secret_id}"));
        let _ = KeyValueRepo::delete(&conn, &key);
        alpha_database::policy::record_proven(&conn, &secret_id, &proven);
    }
    ctx.transport.set_no_store(&gid, true);
    refresh_transport(ctx.db, ctx.identity, ctx.transport);
    tracing::info!(target: "ACCOUNT", "secret chat {secret_id} joined");
    ctx.callback.on_chat_metadata_updated(&secret_id);
}

/// Remove a secret chat from this phone: its MLS group, persona, messages,
/// keys and connection.
fn wipe(db: &Arc<DatabasePool>, mls: &Mls, identity: &Identity, transport: &NostrRelayClient, chat_id: &str) {
    if let Ok(gid) = hex::decode(chat_id) {
        let snapshot = mls.lock().ok().map(|mut m| {
            let _ = m.delete_group(&gid);
            m.snapshot_storage()
        });
        if let Some(s) = snapshot { save_mls(db, s); }
    }
    {
        let conn = db.connection();
        let _ = alpha_database::MessageRepo::delete_for_chat(&conn, chat_id);
        let _ = ChatRepo::delete(&conn, chat_id);
        let _ = alpha_database::MlsGroupRepo::update(&conn, chat_id, None, Some(false));
        let _ = alpha_database::personas::delete(&conn, chat_id);
        for key in KeyValueRepo::list_prefix(&conn, "").unwrap_or_default().into_iter().map(|(k, _)| k) {
            if key.contains(chat_id) { let _ = KeyValueRepo::delete(&conn, &key); }
        }
    }
    refresh_transport(db, identity, transport);
}

fn on_decline(ctx: &Ctx<'_>, chat_id: &str, invite_id: &str) {
    let conn = ctx.db.connection();
    let key = format!("{KV_SECRET_OUT}{invite_id}");
    let Some(out) = get_json(&conn, &key) else { return };
    if out["chat"].as_str() != Some(chat_id) { return; }
    let _ = KeyValueRepo::set(&conn, &key, &serde_json::json!({ "chat": chat_id, "gid": out["gid"], "declined": true }).to_string());
    drop(conn);
    tracing::info!(target: "ACCOUNT", "secret chat declined");
    if let Some(gid) = out["gid"].as_str() { ctx.callback.on_chat_metadata_updated(gid); }
}

impl AlphaMessengerCore {
    /// Start a secret chat with the contact of the 1:1 chat `chat_id`: a new
    /// group owned by a fresh persona, and an invitation inside `chat_id`.
    /// Returns the secret chat's id; it opens for them once they accept.
    pub fn secret_chat_start(&self, chat_id: String) -> Result<String, MessengerError> {
        if !is_direct(&self.mls_session, &chat_id) {
            return Err(MessengerError::InvalidArgument { description: "a secret chat starts from a 1:1 chat".to_string() });
        }
        let keys = nostr::Keys::generate();
        let persona = alpha_mls::Persona::generate_bound(&keys)
            .map_err(|e| MessengerError::CryptoError { description: format!("persona: {}", e) })?;
        let persona_json = persona.to_json()
            .map_err(|e| MessengerError::CryptoError { description: format!("persona: {}", e) })?;
        let sig_hex = hex::encode(persona.public_key());
        let (gid, epoch) = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let gid = mls.create_group_as(persona)
                .map_err(|e| MessengerError::CryptoError { description: format!("secret group: {}", e) })?;
            let epoch = mls.group_info(&gid).map(|i| i.epoch).unwrap_or(0);
            (gid, epoch)
        };
        self.persist_mls_storage();
        let secret_id = hex::encode(&gid);
        let invite_id = uuid::Uuid::new_v4().to_string();
        let transport = (*self.transport).clone();
        let relays = pick_relays(runtime().block_on(async move { transport.enabled_relay_urls().await }));
        {
            let conn = self.db.connection();
            alpha_database::personas::put(&conn, &alpha_database::personas::PersonaRow {
                chat_id: secret_id.clone(),
                nostr_sk: keys.secret_key().to_secret_hex(),
                nostr_pk: keys.public_key().to_hex(),
                mls: persona_json,
                sig: sig_hex.clone(),
                joined: true,
            }).map_err(|e| MessengerError::DatabaseError { description: format!("persona: {}", e) })?;
            ChatRepo::insert(&conn, &new_chat(secret_id.clone(), alpha_database::models::ChatType::Direct, None, epoch as i64))
                .map_err(|e| MessengerError::DatabaseError { description: format!("database error: {}", e) })?;
            MlsGroupRepo::insert(&conn, &new_mls_group_row(secret_id.clone(), epoch as i64, 0, true))
                .map_err(|e| MessengerError::DatabaseError { description: format!("mls_groups insert: {}", e) })?;
            let _ = KeyValueRepo::set(&conn, &Self::dir_key(&secret_id, &sig_hex), &keys.public_key().to_hex());
            let _ = KeyValueRepo::set(&conn, &format!("{KV_SECRET_CHAT}{secret_id}"), &chat_id);
            // Nothing of it is kept on relays longer than delivery needs.
            let _ = KeyValueRepo::set(&conn, &format!("{KV_NO_STORE_PREFIX}{secret_id}"), "1");
            let out = serde_json::json!({ "chat": chat_id, "gid": secret_id });
            let _ = KeyValueRepo::set(&conn, &format!("{KV_SECRET_OUT}{invite_id}"), &out.to_string());
            let _ = KeyValueRepo::set(&conn, &format!("{KV_SECRET_RELAYS}{secret_id}"), &serde_json::to_string(&relays).unwrap_or_default());
        }
        self.transport.set_no_store(&gid, true);
        refresh_transport(&self.db, &self.identity, &self.transport);
        let invite = serde_json::json!({ "gid": secret_id, "relays": relays }).to_string();
        let payload = alpha_messages::serialize(&signal(&invite_id, "invite", invite))
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("secret-{invite_id}"))?;
        tracing::info!(target: "ACCOUNT", "secret chat {secret_id} started");
        self.callback.on_chat_created(&secret_id, "secret");
        Ok(secret_id)
    }

    /// Offers waiting for our answer, as JSON `[{invite_id, chat_id, ts}]`.
    pub fn secret_chat_offers(&self) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        let rows = KeyValueRepo::list_prefix(&conn, KV_SECRET_OFFER).unwrap_or_default();
        let list: Vec<serde_json::Value> = rows.into_iter().filter_map(|(k, v)| {
            let o: serde_json::Value = serde_json::from_str(&v).ok()?;
            Some(serde_json::json!({
                "invite_id": k.strip_prefix(KV_SECRET_OFFER)?,
                "chat_id": o["chat"],
                "ts": o["ts"],
            }))
        }).collect();
        Ok(serde_json::Value::Array(list).to_string())
    }

    /// Accept an offer: a fresh persona and its KeyPackage go back to the
    /// inviter; the secret chat opens when their Welcome arrives.
    pub fn secret_chat_accept(&self, invite_id: String) -> Result<(), MessengerError> {
        let offer_key = format!("{KV_SECRET_OFFER}{invite_id}");
        let offer = get_json(&self.db.connection(), &offer_key)
            .ok_or_else(|| MessengerError::InvalidArgument { description: "no such offer".to_string() })?;
        let chat_id = offer["chat"].as_str().unwrap_or_default().to_string();
        let keys = nostr::Keys::generate();
        let persona = alpha_mls::Persona::generate_bound(&keys)
            .map_err(|e| MessengerError::CryptoError { description: format!("persona: {}", e) })?;
        let persona_json = persona.to_json()
            .map_err(|e| MessengerError::CryptoError { description: format!("persona: {}", e) })?;
        let kp = {
            let mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            mls.generate_key_package_as(&persona)
                .and_then(|k| k.key_package_bytes())
                .map_err(|e| MessengerError::CryptoError { description: format!("key package: {}", e) })?
        };
        // The KeyPackage's private part lives in the MLS storage: kept before
        // anything goes out, or the Welcome could not be opened after a restart.
        self.persist_mls_storage();
        {
            let conn = self.db.connection();
            let pending = serde_json::json!({
                "chat": chat_id,
                "persona": persona_json,
                "nostr_sk": keys.secret_key().to_secret_hex(),
            });
            KeyValueRepo::set(&conn, &format!("{KV_SECRET_IN}{invite_id}"), &pending.to_string())
                .map_err(|e| MessengerError::DatabaseError { description: format!("secret chat: {}", e) })?;
            let _ = KeyValueRepo::delete(&conn, &offer_key);
        }
        let payload = alpha_messages::serialize(&signal(&invite_id, "accept", b64(&kp)))
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("secret-acc-{invite_id}"))
    }

    /// Turn an offer down; the inviter is told.
    pub fn secret_chat_decline(&self, invite_id: String) -> Result<(), MessengerError> {
        let offer_key = format!("{KV_SECRET_OFFER}{invite_id}");
        let Some(offer) = get_json(&self.db.connection(), &offer_key) else { return Ok(()) };
        let _ = KeyValueRepo::delete(&self.db.connection(), &offer_key);
        let chat_id = offer["chat"].as_str().unwrap_or_default().to_string();
        if let Some(gid) = offer["gid"].as_str() {
            wipe(&self.db, &self.mls_session, &self.identity, &self.transport, gid);
        }
        let payload = alpha_messages::serialize(&signal(&invite_id, "decline", String::new()))
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        self.queue_and_send(&chat_id, &payload, &format!("secret-dec-{invite_id}"))
    }

    /// End a secret chat on both phones: the other side is told inside it,
    /// then it is gone here whether or not that reached them.
    pub fn secret_chat_end(&self, chat_id: String) -> Result<(), MessengerError> {
        if !is_secret(&self.db.connection(), &chat_id) {
            return Err(MessengerError::InvalidArgument { description: "not a secret chat".to_string() });
        }
        let gid = hex::decode(&chat_id).map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
        let payload = alpha_messages::serialize(&signal(&uuid::Uuid::new_v4().to_string(), "end", String::new()))
            .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
        let sent = {
            let mut mls = self.mls_session.lock()
                .map_err(|e| MessengerError::InternalError { description: e.to_string() })?;
            let ct = mls.send(&gid, &payload).ok();
            ct.map(|c| (c, mls.group_info(&gid).map(|i| i.epoch).unwrap_or(0)))
        };
        if let Some((ct, epoch)) = sent {
            self.persist_mls_storage();
            if let Err(e) = self.publish_application_message_confirmed(&gid, epoch, ct) {
                tracing::warn!(target: "ACCOUNT", "secret chat end not delivered: {e}");
            }
        }
        wipe(&self.db, &self.mls_session, &self.identity, &self.transport, &chat_id);
        tracing::info!(target: "ACCOUNT", "secret chat ended here");
        Ok(())
    }

    /// Every secret chat on this phone, as a JSON array of ids.
    pub fn secret_chat_ids(&self) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        let ids: Vec<String> = KeyValueRepo::list_prefix(&conn, KV_SECRET_CHAT).unwrap_or_default()
            .into_iter().filter_map(|(k, _)| k.strip_prefix(KV_SECRET_CHAT).map(str::to_string)).collect();
        Ok(serde_json::to_string(&ids).unwrap_or_else(|_| "[]".into()))
    }

    /// Where a secret chat stands, as JSON `{state, invite_id}`: `invited` (we
    /// have not answered), `accepted` (waiting for their Welcome), `waiting`
    /// (they have not answered), `declined`, `active`; `none` for other chats.
    pub fn secret_chat_status(&self, chat_id: String) -> Result<String, MessengerError> {
        let conn = self.db.connection();
        let status = |state: &str, invite: &str| serde_json::json!({ "state": state, "invite_id": invite }).to_string();
        if !is_secret(&conn, &chat_id) {
            return Ok(status("none", ""));
        }
        if let Some(invite) = KeyValueRepo::get(&conn, &format!("{KV_SECRET_PENDING}{chat_id}")).ok().flatten() {
            let accepted = KeyValueRepo::get(&conn, &format!("{KV_SECRET_IN}{invite}")).ok().flatten().is_some();
            return Ok(status(if accepted { "accepted" } else { "invited" }, &invite));
        }
        let ours = KeyValueRepo::list_prefix(&conn, KV_SECRET_OUT).unwrap_or_default().into_iter()
            .filter_map(|(_, v)| serde_json::from_str::<serde_json::Value>(&v).ok())
            .find(|o| o["gid"].as_str() == Some(chat_id.as_str()));
        drop(conn);
        if ours.as_ref().map_or(false, |o| o["declined"].as_bool() == Some(true)) {
            return Ok(status("declined", ""));
        }
        let members = hex::decode(&chat_id).ok()
            .and_then(|gid| self.mls_session.lock().ok().and_then(|m| m.group_info(&gid).map(|i| i.members.len())))
            .unwrap_or(0);
        Ok(status(if members < 2 { "waiting" } else { "active" }, ""))
    }

    /// The ordinary chat a secret chat was set up from ("" for any other chat).
    pub fn secret_chat_origin(&self, chat_id: String) -> Result<String, MessengerError> {
        Ok(KeyValueRepo::get(&self.db.connection(), &format!("{KV_SECRET_CHAT}{chat_id}")).ok().flatten().unwrap_or_default())
    }
}

impl AlphaMessengerCore {
    /// The pipeline for a file: a secret chat's files go through Tor only.
    pub(super) fn media_for(&self, tor: bool) -> &Arc<MediaPipeline> {
        if tor { &self.secret_media } else { &self.media_pipeline }
    }

    pub(super) fn blossom_for(&self, tor: bool) -> &Arc<BlossomClient> {
        if tor { &self.secret_blossom } else { &self.blossom }
    }

    /// An upload's message (op id = message id, `#n` for an album item) is in
    /// a secret chat.
    /// The way a received file is fetched is ours to decide, never the
    /// sender's: a file of a secret chat goes through Tor whatever its ref says.
    pub(super) fn tor_policy(&self, media_ref: &mut MediaRef, op_id: Option<&str>) {
        if media_ref.tor { return; }
        if op_id.is_some_and(|op| self.op_is_secret(op)) || self.media_in_secret_chat(&media_ref.hash) {
            media_ref.tor = true;
        }
    }

    fn media_in_secret_chat(&self, hash: &str) -> bool {
        alpha_database::secret_media::received_in_secret_chat(&self.db.connection(), hash)
    }

    pub(super) fn op_is_secret(&self, op_id: &str) -> bool {
        let msg_id = op_id.split('#').next().unwrap_or(op_id);
        let conn = self.db.connection();
        alpha_database::MessageRepo::get(&conn, msg_id).ok().flatten()
            .map(|m| is_secret(&conn, &m.chat_id))
            .unwrap_or(false)
    }
}

impl AlphaMessengerCore {
    /// Whether the secret chat's own Tor connection is up yet.
    /// Asked while the chat is on screen: it is kept awake meanwhile.
    pub fn secret_chat_ready(&self, chat_id: String) -> bool {
        let Ok(gid) = hex::decode(&chat_id) else { return false };
        if self.transport.wake_group(&gid) {
            resubscribe(&self.identity, &self.transport);
        }
        let transport = (*self.transport).clone();
        runtime().block_on(async move {
            tokio::time::timeout(std::time::Duration::from_secs(3), transport.persona_link_ready(&gid)).await.unwrap_or(false)
        })
    }
}

impl AlphaMessengerCore {
    /// New Tor circuits for this secret chat alone: its connection asks Tor
    /// for fresh ones and reconnects.
    pub fn secret_chat_new_circuit(&self, chat_id: String) -> Result<(), MessengerError> {
        let gid = hex::decode(&chat_id).map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
        let transport = (*self.transport).clone();
        let done = runtime().block_on(async move { transport.renew_persona_circuits(&gid).await });
        if done { Ok(()) } else {
            Err(MessengerError::InvalidArgument { description: "no Tor connection for this chat yet".to_string() })
        }
    }

    /// Ask the Tor check service, through this chat's own route, whether the
    /// request arrives from Tor: JSON `{tor, ip, country, country_code}`.
    pub fn secret_chat_check(&self, chat_id: String) -> Result<String, MessengerError> {
        let gid = hex::decode(&chat_id).map_err(|e| MessengerError::InvalidArgument { description: e.to_string() })?;
        let proxy = self.transport.persona_proxy(&gid)
            .ok_or_else(|| MessengerError::InvalidArgument { description: "no Tor connection for this chat yet".to_string() })?;
        let net = |e: reqwest::Error| MessengerError::NetworkError { description: e.to_string() };
        runtime().block_on(async move {
            let client = reqwest::Client::builder()
                .proxy(reqwest::Proxy::all(format!("socks5h://{proxy}")).map_err(net)?)
                .timeout(std::time::Duration::from_secs(40))
                .build().map_err(net)?;
            let check: serde_json::Value = client.get("https://check.torproject.org/api/ip")
                .send().await.map_err(net)?.json().await.map_err(net)?;
            let ip = check["IP"].as_str().unwrap_or_default().to_string();
            // The exit's country, asked through the same route: best effort.
            let place: serde_json::Value = match client.get(format!("https://ipwho.is/{ip}?fields=country,country_code")).send().await {
                Ok(r) => r.json().await.unwrap_or_default(),
                Err(_) => serde_json::Value::Null,
            };
            Ok(serde_json::json!({
                "tor": check["IsTor"].as_bool().unwrap_or(false),
                "ip": ip,
                "country": place["country"].as_str().unwrap_or_default(),
                "country_code": place["country_code"].as_str().unwrap_or_default(),
            }).to_string())
        })
    }
}

/// Heartbeat: re-plan the secret chats' connections (sleep, visits, wakes);
/// resubscribe when one must open or close.
pub(super) fn plan_tick(db: &Arc<DatabasePool>, transport: &NostrRelayClient, me: &nostr::PublicKey) -> bool {
    let plans = persona_plans(&db.connection());
    if !transport.set_persona_plans(plans) { return false; }
    let transport = transport.clone();
    let me = *me;
    runtime().spawn(async move {
        let _ = transport.subscribe_mls_and_giftwrap(&me).await;
    });
    true
}

#[cfg(test)]
mod relay_pick_tests {
    use super::*;

    #[test]
    fn a_secret_chat_takes_three_relays_that_serve_tor() {
        let enabled = vec![
            "wss://nos.lol".to_string(), "wss://nostr.oxtr.dev".to_string(),
            "wss://relay.nostr.net/".to_string(), "wss://nostr.mom".to_string(),
            "wss://relay.snort.social".to_string(),
        ];
        let picked = pick_relays(enabled);
        assert_eq!(picked.len(), SECRET_RELAY_COUNT);
        assert_eq!(norm(&picked[0]), "wss://relay.nostr.net");
        assert!(!picked.iter().any(|u| u.contains("nos.lol") || u.contains("nostr.mom")));
    }

    #[test]
    fn too_few_of_our_own_are_filled_from_the_known_good_ones() {
        let picked = pick_relays(vec!["wss://nos.lol".to_string()]);
        assert_eq!(picked.len(), 2);
        assert!(picked.iter().all(|u| TOR_FRIENDLY.contains(&u.as_str())));
    }
}

#[cfg(test)]
mod wake_tests {
    use super::*;

    #[test]
    fn a_secret_chat_wakes_no_one_and_an_ordinary_chat_still_does() {
        let path = std::env::temp_dir().join(format!("secret-wake-{}.db", uuid::Uuid::new_v4()));
        let pool = alpha_database::DatabasePool::open(&path, "k").unwrap();
        let conn = pool.connection();
        KeyValueRepo::set(&conn, &format!("{KV_SECRET_CHAT}s1"), "o1").unwrap();
        assert!(!wakes_peers(&conn, "s1"));
        assert!(wakes_peers(&conn, "o1"));
        drop(conn);
        drop(pool);
        let _ = std::fs::remove_file(path);
    }
}
