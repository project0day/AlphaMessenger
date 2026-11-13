//! MLS envelope format for Nostr transport.
//!
//! The [`MlsEnvelope`] wraps an opaque MLS message with just enough metadata
//! for the transport layer to route it to the correct group, without exposing
//! any plaintext to the relay.
//!
//! Wire format (binary, all multi-byte integers are big-endian):
//! ```text
//! [1B version][1B msg_type][4B group_id_len][group_id][8B epoch][4B payload_len][payload]
//! ```

use crate::error::{TransportError, Result};

/// Current envelope version.
/// - v1: original.
/// - v2: + `chat_kind` byte after `message_type`.
/// - v3: + optional `name` (UTF-8) at the end so a group Welcome carries
///   the chat name and the receiver doesn't fall back to "Group".
const ENVELOPE_VERSION: u8 = 3;

/// MLS message type discriminator.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum MlsMessageType {
    /// MLS Welcome message — used to invite a new member to a group.
    Welcome = 0,
    /// MLS Application message — encrypted group chat content.
    Application = 1,
    /// MLS Proposal — propose adding/removing members, etc.
    Proposal = 2,
    /// MLS Commit — finalise a proposal.
    Commit = 3,
    /// Join-by-link request (NOT an MLS message): gift-wrapped from a
    /// would-be member to an OWNER/ADMIN. `payload` is a JSON
    /// `{token, requester_npub}`; `group_id` is the target group. The admin
    /// validates the invite + ban list, then performs the real Add+Welcome.
    JoinRequest = 4,
    /// "Become an admin of this channel" (NOT an MLS message): gift-wrapped
    /// from the persona owning a channel that hides its admins to one of its
    /// subscribers. `payload` is JSON `{token, role}`. The subscriber answers
    /// with a [`Self::JoinRequest`] from a persona of their own, carrying the token.
    AdminInvite = 5,
    /// Between the phones of one account (NOT an MLS message): gift-wrapped
    /// to our own key, sealed by it. `payload` is JSON `{from, ops}`. Also
    /// the link offer to a new phone, sealed the same way to its one-time key.
    DeviceSync = 6,
}

/// Chat-kind discriminator carried in the envelope so the receiver can build
/// the right `Chat` row when joining via a Welcome (MLS group state alone
/// cannot tell a 2-person group apart from a DM — both look identical).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum ChatKind {
    /// 1:1 direct chat.
    Direct = 0,
    /// Group chat (3+ members or explicitly created as a group).
    Group = 1,
}

impl ChatKind {
    pub fn from_u8(v: u8) -> Self {
        match v {
            1 => Self::Group,
            _ => Self::Direct,
        }
    }
    pub fn as_u8(self) -> u8 { self as u8 }
}

impl MlsMessageType {
    /// Convert from the raw `u8` discriminator.
    pub fn from_u8(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::Welcome),
            1 => Ok(Self::Application),
            2 => Ok(Self::Proposal),
            3 => Ok(Self::Commit),
            4 => Ok(Self::JoinRequest),
            5 => Ok(Self::AdminInvite),
            6 => Ok(Self::DeviceSync),
            _ => Err(TransportError::InvalidEnvelope(format!(
                "unknown message type discriminator: {value}"
            ))),
        }
    }

    /// Convert to the raw `u8` discriminator.
    pub fn as_u8(self) -> u8 {
        self as u8
    }
}

impl std::fmt::Display for MlsMessageType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Welcome => write!(f, "Welcome"),
            Self::Application => write!(f, "Application"),
            Self::Proposal => write!(f, "Proposal"),
            Self::Commit => write!(f, "Commit"),
            Self::JoinRequest => write!(f, "JoinRequest"),
            Self::AdminInvite => write!(f, "AdminInvite"),
            Self::DeviceSync => write!(f, "DeviceSync"),
        }
    }
}

/// MLS envelope carried inside a Nostr kind-443 event.
///
/// The relay sees **only** the opaque `payload` ciphertext. All other fields
/// are for transport-level routing (group ID) and metadata (epoch, type) that
/// are not secret — the relay could infer group membership from traffic
/// analysis regardless, but the *content* remains private.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MlsEnvelope {
    /// Envelope format version (currently 3).
    pub version: u8,
    /// Discriminator for the MLS message type.
    pub message_type: MlsMessageType,
    /// Direct vs Group — v2+. v1 envelopes are read as `Direct`.
    pub chat_kind: ChatKind,
    /// MLS GroupId bytes — used for routing to the correct local group state.
    pub group_id: Vec<u8>,
    /// MLS epoch number.
    pub epoch: u64,
    /// TLS-serialised MLS message (opaque ciphertext).
    pub payload: Vec<u8>,
    /// Optional human-readable chat name — v3+. Set on Welcome envelopes for
    /// groups so the receiver doesn't fall back to "Group (N)".
    pub name: Option<String>,
}

impl MlsEnvelope {
    /// Create a new direct-chat envelope with the current version.
    /// For groups use [`Self::new_group`].
    pub fn new(
        message_type: MlsMessageType,
        group_id: Vec<u8>,
        epoch: u64,
        payload: Vec<u8>,
    ) -> Self {
        Self {
            version: ENVELOPE_VERSION,
            message_type,
            chat_kind: ChatKind::Direct,
            group_id,
            epoch,
            payload,
            name: None,
        }
    }

    /// Build a group-flavoured envelope. Used by `add_member_by_npub` and
    /// `create_group_with_members` so the receiver creates a group chat row
    /// instead of a DM when it joins.
    pub fn new_group(
        message_type: MlsMessageType,
        group_id: Vec<u8>,
        epoch: u64,
        payload: Vec<u8>,
    ) -> Self {
        Self {
            version: ENVELOPE_VERSION,
            message_type,
            chat_kind: ChatKind::Group,
            group_id,
            epoch,
            payload,
            name: None,
        }
    }

    /// Attach a chat name to this envelope (typically the group title).
    pub fn with_name(mut self, name: impl Into<String>) -> Self {
        self.name = Some(name.into());
        self
    }

    /// Serialise to the binary wire format.
    ///
    /// Layout:
    /// ```text
    /// [1B version][1B msg_type][4B group_id_len][group_id][8B epoch][4B payload_len][payload]
    /// ```
    pub fn to_bytes(&self) -> Vec<u8> {
        let name_bytes: &[u8] = self.name.as_deref().map(str::as_bytes).unwrap_or(&[]);
        let mut buf = Vec::new();

        buf.push(self.version);
        buf.push(self.message_type.as_u8());
        if self.version >= 2 {
            buf.push(self.chat_kind.as_u8());
        }
        buf.extend_from_slice(&(self.group_id.len() as u32).to_be_bytes());
        buf.extend_from_slice(&self.group_id);
        buf.extend_from_slice(&self.epoch.to_be_bytes());
        buf.extend_from_slice(&(self.payload.len() as u32).to_be_bytes());
        buf.extend_from_slice(&self.payload);
        if self.version >= 3 {
            // u16 length capped at 65535 — chat names won't get bigger than that.
            let nlen = name_bytes.len().min(u16::MAX as usize) as u16;
            buf.extend_from_slice(&nlen.to_be_bytes());
            buf.extend_from_slice(&name_bytes[..nlen as usize]);
        }

        buf
    }

    /// Deserialise from the binary wire format.
    pub fn from_bytes(data: &[u8]) -> Result<Self> {
        let mut cursor = 0;

        // Version (1 byte)
        let version = *data.get(cursor).ok_or_else(|| {
            TransportError::InvalidEnvelope("missing version byte".into())
        })?;
        cursor += 1;

        // Accept v1, v2, v3. v1 has no chat_kind (default Direct);
        // v3 trails an optional UTF-8 chat name after the payload.
        if !(1..=3).contains(&version) {
            return Err(TransportError::InvalidEnvelope(format!(
                "unsupported envelope version: {version} (expected 1..=3)"
            )));
        }

        // Message type (1 byte)
        let msg_type_raw = *data.get(cursor).ok_or_else(|| {
            TransportError::InvalidEnvelope("missing message type byte".into())
        })?;
        cursor += 1;
        let message_type = MlsMessageType::from_u8(msg_type_raw)?;

        // chat_kind (1 byte, v2+ only)
        let chat_kind = if version >= 2 {
            let raw = *data.get(cursor).ok_or_else(|| {
                TransportError::InvalidEnvelope("missing chat_kind byte".into())
            })?;
            cursor += 1;
            ChatKind::from_u8(raw)
        } else {
            ChatKind::Direct
        };

        // Group ID length (4 bytes, big-endian u32)
        let group_id_len = read_u32_be(data, &mut cursor, "group_id_len")? as usize;

        // Group ID
        let group_id = read_bytes(data, &mut cursor, group_id_len, "group_id")?;

        // Epoch (8 bytes, big-endian u64)
        let epoch = read_u64_be(data, &mut cursor, "epoch")?;

        // Payload length (4 bytes, big-endian u32)
        let payload_len = read_u32_be(data, &mut cursor, "payload_len")? as usize;

        // Payload
        let payload = read_bytes(data, &mut cursor, payload_len, "payload")?;

        // v3+: optional name trailer.
        let name = if version >= 3 && data.len() > cursor + 2 {
            let nlen = u16::from_be_bytes([data[cursor], data[cursor + 1]]) as usize;
            cursor += 2;
            if nlen == 0 {
                None
            } else if data.len() >= cursor + nlen {
                let s = String::from_utf8_lossy(&data[cursor..cursor + nlen]).into_owned();
                Some(s)
            } else {
                None
            }
        } else {
            None
        };

        Ok(Self {
            version,
            message_type,
            chat_kind,
            group_id,
            epoch,
            payload,
            name,
        })
    }

    /// Encode the envelope to a base64 string (for embedding in Nostr event content).
    pub fn to_base64(&self) -> String {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(self.to_bytes())
    }

    /// Decode an envelope from a base64 string.
    pub fn from_base64(s: &str) -> Result<Self> {
        use base64::Engine;
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(s)
            .map_err(|e| TransportError::Deserialization(format!("base64 decode: {e}")))?;
        Self::from_bytes(&bytes)
    }
}

impl std::fmt::Display for MlsEnvelope {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "MlsEnvelope(v{}, {}, group_id={}bytes, epoch={}, payload={}bytes)",
            self.version,
            self.message_type,
            self.group_id.len(),
            self.epoch,
            self.payload.len(),
        )
    }
}

// ---------------------------------------------------------------------------
// Helper functions for binary parsing
// ---------------------------------------------------------------------------

fn read_u32_be(data: &[u8], cursor: &mut usize, field: &str) -> Result<u32> {
    if data.len() < *cursor + 4 {
        return Err(TransportError::InvalidEnvelope(format!(
            "not enough bytes for {field}: expected 4, got {}",
            data.len().saturating_sub(*cursor)
        )));
    }
    let value = u32::from_be_bytes([
        data[*cursor],
        data[*cursor + 1],
        data[*cursor + 2],
        data[*cursor + 3],
    ]);
    *cursor += 4;
    Ok(value)
}

fn read_u64_be(data: &[u8], cursor: &mut usize, field: &str) -> Result<u64> {
    if data.len() < *cursor + 8 {
        return Err(TransportError::InvalidEnvelope(format!(
            "not enough bytes for {field}: expected 8, got {}",
            data.len().saturating_sub(*cursor)
        )));
    }
    let value = u64::from_be_bytes([
        data[*cursor],
        data[*cursor + 1],
        data[*cursor + 2],
        data[*cursor + 3],
        data[*cursor + 4],
        data[*cursor + 5],
        data[*cursor + 6],
        data[*cursor + 7],
    ]);
    *cursor += 8;
    Ok(value)
}

fn read_bytes(data: &[u8], cursor: &mut usize, len: usize, field: &str) -> Result<Vec<u8>> {
    if data.len() < *cursor + len {
        return Err(TransportError::InvalidEnvelope(format!(
            "not enough bytes for {field}: expected {len}, got {}",
            data.len().saturating_sub(*cursor)
        )));
    }
    let bytes = data[*cursor..*cursor + len].to_vec();
    *cursor += len;
    Ok(bytes)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: create a sample envelope with configurable fields.
    fn sample_envelope(
        message_type: MlsMessageType,
        group_id: Vec<u8>,
        epoch: u64,
        payload: Vec<u8>,
    ) -> MlsEnvelope {
        MlsEnvelope::new(message_type, group_id, epoch, payload)
    }

    // --- Round-trip: to_bytes() → from_bytes() for all MlsMessageType variants ---

    #[test]
    fn roundtrip_welcome() {
        let env = sample_envelope(
            MlsMessageType::Welcome,
            vec![0xAA, 0xBB, 0xCC],
            42,
            vec![1, 2, 3, 4, 5],
        );
        let bytes = env.to_bytes();
        let parsed = MlsEnvelope::from_bytes(&bytes).unwrap();
        assert_eq!(parsed, env);
    }

    #[test]
    fn roundtrip_application() {
        let env = sample_envelope(
            MlsMessageType::Application,
            vec![0x01; 16],
            999,
            vec![0xFF; 64],
        );
        let bytes = env.to_bytes();
        let parsed = MlsEnvelope::from_bytes(&bytes).unwrap();
        assert_eq!(parsed, env);
    }

    #[test]
    fn roundtrip_proposal() {
        let env = sample_envelope(
            MlsMessageType::Proposal,
            b"group-1".to_vec(),
            7,
            b"proposal-data".to_vec(),
        );
        let bytes = env.to_bytes();
        let parsed = MlsEnvelope::from_bytes(&bytes).unwrap();
        assert_eq!(parsed, env);
    }

    #[test]
    fn roundtrip_commit() {
        let env = sample_envelope(
            MlsMessageType::Commit,
            b"group-2".to_vec(),
            100,
            b"commit-data".to_vec(),
        );
        let bytes = env.to_bytes();
        let parsed = MlsEnvelope::from_bytes(&bytes).unwrap();
        assert_eq!(parsed, env);
    }

    // --- Base64 round-trip ---

    #[test]
    fn base64_roundtrip() {
        let env = sample_envelope(
            MlsMessageType::Application,
            vec![0xDE, 0xAD, 0xBE, 0xEF],
            12345,
            vec![10, 20, 30, 40, 50],
        );
        let b64 = env.to_base64();
        let parsed = MlsEnvelope::from_base64(&b64).unwrap();
        assert_eq!(parsed, env);
    }

    #[test]
    fn base64_roundtrip_all_types() {
        for mt in [
            MlsMessageType::Welcome,
            MlsMessageType::Application,
            MlsMessageType::Proposal,
            MlsMessageType::Commit,
        ] {
            let env = sample_envelope(mt, b"g".to_vec(), 0, b"p".to_vec());
            let b64 = env.to_base64();
            let parsed = MlsEnvelope::from_base64(&b64).unwrap();
            assert_eq!(parsed, env, "Failed for message type {:?}", mt);
        }
    }

    #[test]
    fn base64_invalid_string() {
        let result = MlsEnvelope::from_base64("!!!not-base64!!!");
        assert!(result.is_err());
    }

    // --- Version validation ---

    #[test]
    fn wrong_version_returns_error() {
        let env = sample_envelope(MlsMessageType::Application, b"g".to_vec(), 1, b"p".to_vec());
        let mut bytes = env.to_bytes();
        // Corrupt the version byte
        bytes[0] = 99;
        let result = MlsEnvelope::from_bytes(&bytes);
        assert!(result.is_err());
        let err_msg = result.unwrap_err().to_string();
        assert!(
            err_msg.contains("unsupported envelope version"),
            "Expected version error, got: {err_msg}"
        );
    }

    // --- Truncated input ---

    #[test]
    fn truncated_missing_version() {
        let result = MlsEnvelope::from_bytes(&[]);
        assert!(result.is_err());
        assert!(
            result.unwrap_err().to_string().contains("missing version"),
        );
    }

    #[test]
    fn truncated_missing_message_type() {
        // Only version byte present
        let result = MlsEnvelope::from_bytes(&[1]);
        assert!(result.is_err());
        assert!(
            result.unwrap_err().to_string().contains("missing message type"),
        );
    }

    #[test]
    fn truncated_missing_group_id_len() {
        // Version + message type only
        let result = MlsEnvelope::from_bytes(&[1, 0]);
        assert!(result.is_err());
    }

    #[test]
    fn truncated_missing_epoch() {
        // Version(1) + msg_type(1) + group_id_len(4) + group_id(0) = 6 bytes
        // Missing epoch (8 bytes)
        let result = MlsEnvelope::from_bytes(&[1, 0, 0, 0, 0, 0]);
        assert!(result.is_err());
    }

    #[test]
    fn truncated_missing_payload() {
        // Version(1) + msg_type(1) + gid_len(4)=0 + epoch(8) + payload_len(4)=5 = 18 bytes
        // But no payload bytes following
        let mut bytes = vec![1, 0]; // version + type
        bytes.extend_from_slice(&0u32.to_be_bytes()); // group_id_len = 0
        bytes.extend_from_slice(&1u64.to_be_bytes()); // epoch = 1
        bytes.extend_from_slice(&5u32.to_be_bytes()); // payload_len = 5
        let result = MlsEnvelope::from_bytes(&bytes);
        assert!(result.is_err());
    }

    // --- Invalid message type discriminator ---

    #[test]
    fn invalid_message_type_discriminator() {
        let mut bytes = vec![1]; // version
        bytes.push(99); // invalid message type
        bytes.extend_from_slice(&0u32.to_be_bytes()); // group_id_len
        bytes.extend_from_slice(&0u64.to_be_bytes()); // epoch
        bytes.extend_from_slice(&0u32.to_be_bytes()); // payload_len
        let result = MlsEnvelope::from_bytes(&bytes);
        assert!(result.is_err());
        assert!(
            result.unwrap_err().to_string().contains("unknown message type discriminator"),
        );
    }

    // --- Zero-length group_id and payload ---

    #[test]
    fn zero_length_group_id_and_payload() {
        let env = sample_envelope(
            MlsMessageType::Welcome,
            vec![], // zero-length group_id
            0,
            vec![], // zero-length payload
        );
        let bytes = env.to_bytes();
        // v3 minimum size: 1(ver) + 1(type) + 1(chat_kind) + 4(gid_len) + 0(gid)
        //              + 8(epoch) + 4(pay_len) + 0(pay) + 2(name_len) = 21 bytes
        assert_eq!(bytes.len(), 21);
        let parsed = MlsEnvelope::from_bytes(&bytes).unwrap();
        assert_eq!(parsed, env);
        assert!(parsed.group_id.is_empty());
        assert!(parsed.payload.is_empty());
    }

    // --- Maximum field values ---

    #[test]
    fn max_epoch_value() {
        let env = sample_envelope(
            MlsMessageType::Commit,
            b"g".to_vec(),
            u64::MAX,
            b"p".to_vec(),
        );
        let bytes = env.to_bytes();
        let parsed = MlsEnvelope::from_bytes(&bytes).unwrap();
        assert_eq!(parsed.epoch, u64::MAX);
        assert_eq!(parsed, env);
    }

    #[test]
    fn large_payload() {
        let large_payload = vec![0xAB; 100_000];
        let env = sample_envelope(
            MlsMessageType::Application,
            vec![0x01; 32],
            1000,
            large_payload.clone(),
        );
        let bytes = env.to_bytes();
        let parsed = MlsEnvelope::from_bytes(&bytes).unwrap();
        assert_eq!(parsed.payload, large_payload);
    }

    // --- MlsMessageType conversion tests ---

    #[test]
    fn mls_message_type_from_u8_valid() {
        assert_eq!(MlsMessageType::from_u8(0).unwrap(), MlsMessageType::Welcome);
        assert_eq!(MlsMessageType::from_u8(1).unwrap(), MlsMessageType::Application);
        assert_eq!(MlsMessageType::from_u8(2).unwrap(), MlsMessageType::Proposal);
        assert_eq!(MlsMessageType::from_u8(3).unwrap(), MlsMessageType::Commit);
    }

    #[test]
    fn mls_message_type_from_u8_invalid() {
        // 0..=6 are valid (..., JoinRequest, AdminInvite, DeviceSync).
        assert!(MlsMessageType::from_u8(6).is_ok());
        assert!(MlsMessageType::from_u8(7).is_err());
        assert!(MlsMessageType::from_u8(255).is_err());
    }

    #[test]
    fn mls_message_type_as_u8_roundtrip() {
        for (expected, mt) in [
            (0u8, MlsMessageType::Welcome),
            (1u8, MlsMessageType::Application),
            (2u8, MlsMessageType::Proposal),
            (3u8, MlsMessageType::Commit),
        ] {
            assert_eq!(mt.as_u8(), expected);
            assert_eq!(MlsMessageType::from_u8(mt.as_u8()).unwrap(), mt);
        }
    }

    // --- Display impl ---

    #[test]
    fn display_mls_message_type() {
        assert_eq!(format!("{}", MlsMessageType::Welcome), "Welcome");
        assert_eq!(format!("{}", MlsMessageType::Application), "Application");
        assert_eq!(format!("{}", MlsMessageType::Proposal), "Proposal");
        assert_eq!(format!("{}", MlsMessageType::Commit), "Commit");
    }

    #[test]
    fn display_mls_envelope() {
        let env = sample_envelope(
            MlsMessageType::Application,
            vec![1, 2, 3],
            42,
            vec![4, 5],
        );
        let s = format!("{}", env);
        // Envelope is at wire version 3 (v1 + chat_kind + name trailer).
        assert!(s.contains("v3"));
        assert!(s.contains("Application"));
        assert!(s.contains("3bytes")); // group_id length
        assert!(s.contains("epoch=42"));
        assert!(s.contains("2bytes")); // payload length
    }

    // --- Version field is the current wire version (v3) ---

    #[test]
    fn new_envelope_version_is_current() {
        // `MlsEnvelope::new` builds a v3 envelope (current wire format:
        // v1 base + v2 chat_kind + v3 name trailer).
        let env = MlsEnvelope::new(MlsMessageType::Welcome, vec![], 0, vec![]);
        assert_eq!(env.version, ENVELOPE_VERSION);
        assert_eq!(env.version, 3);
    }

    // --- Wire format byte layout verification ---

    #[test]
    fn wire_format_layout() {
        let env = MlsEnvelope::new(
            MlsMessageType::Application,
            vec![0xDE, 0xAD], // 2-byte group_id
            0x0102030405060708, // epoch
            vec![0xCA, 0xFE], // 2-byte payload
        );
        let bytes = env.to_bytes();

        // v3 layout: [1B version=3][1B type=1][1B chat_kind=0(Direct)]
        //          [4B gid_len=2][gid=0xDEAD][8B epoch][4B pay_len=2][pay=0xCAFE]
        //          [2B name_len=0]
        assert_eq!(bytes[0], 3); // version (v3)
        assert_eq!(bytes[1], 1); // Application
        assert_eq!(bytes[2], 0); // chat_kind = Direct (0)
        // group_id_len at bytes 3..7
        assert_eq!(u32::from_be_bytes([bytes[3], bytes[4], bytes[5], bytes[6]]), 2); // group_id_len
        assert_eq!(bytes[7], 0xDE);
        assert_eq!(bytes[8], 0xAD);
        // epoch at bytes 9..17
        let epoch_val = u64::from_be_bytes([
            bytes[9], bytes[10], bytes[11], bytes[12],
            bytes[13], bytes[14], bytes[15], bytes[16],
        ]);
        assert_eq!(epoch_val, 0x0102030405060708);
        // payload_len at bytes 17..21
        assert_eq!(u32::from_be_bytes([bytes[17], bytes[18], bytes[19], bytes[20]]), 2);
        assert_eq!(bytes[21], 0xCA);
        assert_eq!(bytes[22], 0xFE);
        // v3 name trailer: 2-byte length (0) at bytes 23..25
        assert_eq!(u16::from_be_bytes([bytes[23], bytes[24]]), 0);
    }
}
