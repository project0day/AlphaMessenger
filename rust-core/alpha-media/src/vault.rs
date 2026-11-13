//! The media vault: received media kept on disk encrypted.
//!
//! A file is `AMV1` + a random 32-byte salt, then the content under
//! AES-256-CTR (counter from zero) with the key HMAC-SHA256(vault key, salt).
//! CTR keeps the length and lets any byte range be read alone, so a player can
//! seek without decrypting the whole file. The vault key lives in the
//! encrypted database: without the unlock there is nothing to decrypt with.
//! The app reads the same format (MediaVault.kt).

use aes::cipher::{KeyIvInit, StreamCipher, StreamCipherSeek};
use hmac::{Hmac, Mac};
use rand::RngCore;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::Path;
use zeroize::Zeroize;

type Ctr = ctr::Ctr128BE<aes::Aes256>;

pub const MAGIC: &[u8; 4] = b"AMV1";
const SALT_LEN: usize = 32;
/// Bytes before the content.
pub const HEADER_LEN: u64 = (MAGIC.len() + SALT_LEN) as u64;

/// The key every vault file's own key is derived from.
#[derive(Clone)]
pub struct VaultKey([u8; 32]);

impl VaultKey {
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    fn file_key(&self, salt: &[u8]) -> [u8; 32] {
        let mut mac = <Hmac<sha2::Sha256> as Mac>::new_from_slice(&self.0).expect("any key length");
        mac.update(salt);
        mac.finalize().into_bytes().into()
    }
}

impl Drop for VaultKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

/// Whether the file at [path] is a vault file.
pub fn is_sealed(path: &Path) -> bool {
    let mut head = [0u8; 4];
    File::open(path).and_then(|mut f| f.read_exact(&mut head)).is_ok() && &head == MAGIC
}

/// A file being written and read back by content offset: sealed when a vault
/// key is given, plain otherwise.
pub struct MediaFile {
    file: File,
    key: Option<[u8; 32]>,
    header: u64,
}

impl MediaFile {
    /// Open [path] to continue it, or start it. A file in the other form (plain
    /// where a sealed one is wanted, or the reverse) is started over.
    pub fn open(path: &Path, vault: Option<&VaultKey>) -> io::Result<Self> {
        let mut file = std::fs::OpenOptions::new().read(true).write(true).create(true).open(path)?;
        let Some(vault) = vault else {
            if file.metadata()?.len() >= HEADER_LEN && is_sealed(path) { file.set_len(0)?; }
            return Ok(Self { file, key: None, header: 0 });
        };
        let mut head = [0u8; HEADER_LEN as usize];
        let existing = file.metadata()?.len() >= HEADER_LEN
            && file.read_exact(&mut head).is_ok()
            && &head[..4] == MAGIC;
        if !existing {
            rand::thread_rng().fill_bytes(&mut head[4..]);
            head[..4].copy_from_slice(MAGIC);
            file.set_len(0)?;
            file.seek(SeekFrom::Start(0))?;
            file.write_all(&head)?;
        }
        let key = vault.file_key(&head[4..]);
        Ok(Self { file, key: Some(key), header: HEADER_LEN })
    }

    /// Content length.
    pub fn len(&self) -> io::Result<u64> {
        Ok(self.file.metadata()?.len().saturating_sub(self.header))
    }

    pub fn truncate(&self, len: u64) -> io::Result<()> {
        self.file.set_len(self.header + len)
    }

    fn apply(&self, offset: u64, buf: &mut [u8]) {
        if let Some(key) = &self.key {
            let mut c = Ctr::new(key.into(), &[0u8; 16].into());
            c.seek(offset);
            c.apply_keystream(buf);
        }
    }

    pub fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<()> {
        self.file.seek(SeekFrom::Start(self.header + offset))?;
        self.file.read_exact(buf)?;
        self.apply(offset, buf);
        Ok(())
    }

    pub fn write_at(&mut self, offset: u64, data: &[u8]) -> io::Result<()> {
        self.file.seek(SeekFrom::Start(self.header + offset))?;
        if self.key.is_none() {
            return self.file.write_all(data);
        }
        let mut sealed = data.to_vec();
        self.apply(offset, &mut sealed);
        self.file.write_all(&sealed)
    }

    pub fn sync(&self) -> io::Result<()> {
        self.file.sync_all()
    }
}

impl Drop for MediaFile {
    fn drop(&mut self) {
        if let Some(k) = self.key.as_mut() { k.zeroize(); }
    }
}

/// Write [data] to [path] whole (sealed when a vault key is given).
pub fn write_file(path: &Path, vault: Option<&VaultKey>, data: &[u8]) -> io::Result<()> {
    let _ = std::fs::remove_file(path);
    let mut f = MediaFile::open(path, vault)?;
    f.write_at(0, data)?;
    f.sync()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> VaultKey {
        VaultKey::from_bytes([7u8; 32])
    }

    #[test]
    fn sealed_media_reads_back_from_any_offset_and_hides_its_content() {
        let dir = std::env::temp_dir().join(format!("vault-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("a");
        let data: Vec<u8> = (0..100_000u32).map(|i| (i * 31 % 251) as u8).collect();
        write_file(&path, Some(&key()), &data).unwrap();

        let raw = std::fs::read(&path).unwrap();
        assert!(is_sealed(&path));
        assert_eq!(raw.len() as u64, data.len() as u64 + HEADER_LEN);
        assert!(!raw.windows(64).any(|w| w == &data[5000..5064]));

        let mut f = MediaFile::open(&path, Some(&key())).unwrap();
        assert_eq!(f.len().unwrap(), data.len() as u64);
        let mut buf = vec![0u8; 777];
        f.read_at(12_345, &mut buf).unwrap();
        assert_eq!(buf, data[12_345..12_345 + 777]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn a_sealed_part_resumes_where_it_stopped() {
        let dir = std::env::temp_dir().join(format!("vault-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("b.part");
        let data: Vec<u8> = (0..50_000u32).map(|i| (i % 253) as u8).collect();
        {
            let mut f = MediaFile::open(&path, Some(&key())).unwrap();
            f.write_at(0, &data[..20_000]).unwrap();
        }
        let mut f = MediaFile::open(&path, Some(&key())).unwrap();
        assert_eq!(f.len().unwrap(), 20_000);
        f.write_at(20_000, &data[20_000..]).unwrap();
        let mut all = vec![0u8; data.len()];
        f.read_at(0, &mut all).unwrap();
        assert_eq!(all, data);

        // A plain leftover is not trusted as sealed: it starts over.
        let plain = dir.join("c.part");
        std::fs::write(&plain, b"old plain bytes").unwrap();
        assert_eq!(MediaFile::open(&plain, Some(&key())).unwrap().len().unwrap(), 0);
        std::fs::remove_dir_all(&dir).ok();
    }
}
