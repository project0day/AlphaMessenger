//! Our personas in anonymous channels, as the core uses them.
//!
//! In a channel that hides its admins we act under a persona: its own Nostr
//! key and its own MLS leaf. The rows live in the encrypted database with an
//! in-memory copy (`alpha_database::personas`), so "who am I in this chat" is
//! answered without touching the database or the MLS session — safe to ask
//! from inside any lock.

#[derive(Clone)]
pub struct Persona {
    /// The persona's Nostr keys: inbox, directory address, envelope sender.
    pub keys: nostr::Keys,
    /// The persona's MLS leaf key, hex.
    pub sig_hex: String,
}

/// Our persona in `chat_id`, once it is in the channel.
pub fn get(chat_id: &str) -> Option<Persona> {
    let row = alpha_database::personas::cached(chat_id).filter(|r| r.joined && !r.sig.is_empty())?;
    Some(Persona { keys: nostr::Keys::parse(&row.nostr_sk).ok()?, sig_hex: row.sig })
}

/// Every persona inbox, waiting ones too — gift wraps addressed to them are ours.
pub fn inboxes() -> Vec<nostr::PublicKey> {
    alpha_database::personas::cached_all()
        .into_iter()
        .filter_map(|r| nostr::PublicKey::from_hex(&r.nostr_pk).ok())
        .collect()
}
