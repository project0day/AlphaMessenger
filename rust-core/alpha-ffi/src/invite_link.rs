//! Invite links: `alpha://channel/<code>` and `alpha://group/<code>`.
//!
//! The code is base64url (no padding) of a small binary record:
//!
//! ```text
//! [0]      version = 1
//! [1]      flags: bit 0 = channel, bit 1 = joining needs the owner's approval
//! [2]      group id length N
//! [3..]    group id (N bytes)
//!          token (16 bytes)
//!          inviter's Nostr key, x-only (32 bytes) — a persona's in an
//!          anonymous channel
//!          name, UTF-8 (the rest, at most 64 bytes)
//! ```
//!
//! The Android side reads the same record to draw the invite card (kind and
//! name) — keep `InviteLink.kt` in step with this file.
//!
//! The first format, `alpha://group/invite/<base64 JSON {g,t,i,n,d}>`, is
//! still read, so links already sent keep working.

use base64::Engine;

const VERSION: u8 = 1;
const NAME_MAX: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Invite {
    pub channel: bool,
    /// Joining waits for the owner's approval (else the link lets one in).
    pub by_request: bool,
    /// Group id, hex.
    pub group_hex: String,
    /// Invite token, hex.
    pub token: String,
    /// Who handles join requests (x-only key, hex).
    pub inviter_hex: String,
    pub name: String,
}

pub fn encode(inv: &Invite) -> Option<String> {
    let gid = hex::decode(&inv.group_hex).ok()?;
    let token = hex::decode(&inv.token).ok()?;
    let inviter = hex::decode(&inv.inviter_hex).ok()?;
    if gid.is_empty() || gid.len() > 255 || token.len() != 16 || inviter.len() != 32 {
        return None;
    }
    let mut name = inv.name.as_str();
    while name.len() > NAME_MAX {
        let mut cut = NAME_MAX.min(name.len() - 1);
        while !name.is_char_boundary(cut) { cut -= 1; }
        name = &name[..cut];
    }
    let mut b = Vec::with_capacity(3 + gid.len() + 48 + name.len());
    b.push(VERSION);
    b.push(inv.channel as u8 | (inv.by_request as u8) << 1);
    b.push(gid.len() as u8);
    b.extend_from_slice(&gid);
    b.extend_from_slice(&token);
    b.extend_from_slice(&inviter);
    b.extend_from_slice(name.as_bytes());
    let code = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b);
    Some(format!("alpha://{}/{}", if inv.channel { "channel" } else { "group" }, code))
}

pub fn decode(link: &str) -> Option<Invite> {
    let link = link.trim();
    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    if let Some(code) = link.strip_prefix("alpha://group/invite/") {
        // The first format: base64 JSON.
        let v: serde_json::Value = serde_json::from_slice(&b64.decode(code.as_bytes()).ok()?).ok()?;
        let s = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or_default().to_string();
        let inviter = s("i");
        let inviter_hex = {
            use nostr::nips::nip19::FromBech32;
            nostr::PublicKey::from_bech32(&inviter)
                .or_else(|_| nostr::PublicKey::from_hex(&inviter))
                .ok()?
                .to_hex()
        };
        let inv = Invite { channel: false, by_request: true, group_hex: s("g"), token: s("t"), inviter_hex, name: s("n") };
        return (!inv.group_hex.is_empty() && !inv.token.is_empty()).then_some(inv);
    }
    let code = link.strip_prefix("alpha://channel/").or_else(|| link.strip_prefix("alpha://group/"))?;
    let b = b64.decode(code.as_bytes()).ok()?;
    if b.len() < 3 || b[0] != VERSION {
        return None;
    }
    let n = b[2] as usize;
    let head = 3 + n + 16 + 32;
    if n == 0 || b.len() < head {
        return None;
    }
    let gid = &b[3..3 + n];
    let token = &b[3 + n..3 + n + 16];
    let inviter = &b[3 + n + 16..head];
    Some(Invite {
        channel: b[1] & 1 != 0,
        by_request: b[1] & 2 != 0,
        group_hex: hex::encode(gid),
        token: hex::encode(token),
        inviter_hex: hex::encode(inviter),
        name: String::from_utf8_lossy(&b[head..]).into_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(channel: bool, name: &str) -> Invite {
        Invite {
            channel,
            by_request: !channel,
            group_hex: "ab".repeat(16),
            token: "cd".repeat(16),
            inviter_hex: nostr::Keys::generate().public_key().to_hex(),
            name: name.to_string(),
        }
    }

    #[test]
    fn a_link_comes_back_as_it_went() {
        for channel in [true, false] {
            let inv = sample(channel, "Vent du Nord — été");
            let link = encode(&inv).unwrap();
            assert!(link.starts_with(if channel { "alpha://channel/" } else { "alpha://group/" }));
            assert!(link.len() < 170, "{} chars: {link}", link.len());
            assert_eq!(decode(&link).unwrap(), inv);
        }
    }

    #[test]
    fn a_long_name_is_cut_on_a_character() {
        let inv = sample(true, &"é".repeat(100));
        let back = decode(&encode(&inv).unwrap()).unwrap();
        assert!(back.name.len() <= NAME_MAX);
        assert!(back.name.chars().all(|c| c == 'é'));
    }

    #[test]
    fn the_first_format_is_still_read() {
        let keys = nostr::Keys::generate();
        let npub = { use nostr::nips::nip19::ToBech32; keys.public_key().to_bech32().unwrap() };
        let json = serde_json::json!({ "g": "ab".repeat(16), "t": "cd".repeat(16), "i": npub, "n": "Old" }).to_string();
        let link = format!("alpha://group/invite/{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json));
        let inv = decode(&link).unwrap();
        assert_eq!(inv.inviter_hex, keys.public_key().to_hex());
        assert_eq!(inv.name, "Old");
        assert!(!inv.channel);
    }

    #[test]
    fn junk_is_not_a_link() {
        assert!(decode("alpha://channel/AAAA").is_none());
        assert!(decode("https://example.com").is_none());
    }
}
