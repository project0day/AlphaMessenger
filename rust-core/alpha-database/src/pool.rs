//! Connection pool for SQLCipher-encrypted SQLite database.
//!
//! [`DatabasePool`] wraps a single `rusqlite::Connection` behind a `Mutex`
//! so that it can be shared across threads. The database file is encrypted
//! at rest using SQLCipher with a passphrase supplied at open time.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension};
use tracing::info;

use crate::error::{DatabaseError, Result};
use crate::migrations;

/// The connection, held. Lets go of the lock — and of its "who holds it"
/// note — when dropped.
pub struct DbGuard<'a> {
    guard: std::sync::MutexGuard<'a, Connection>,
}

impl std::ops::Deref for DbGuard<'_> {
    type Target = Connection;
    fn deref(&self) -> &Connection { &self.guard }
}

impl std::ops::DerefMut for DbGuard<'_> {
    fn deref_mut(&mut self) -> &mut Connection { &mut self.guard }
}

impl Drop for DbGuard<'_> {
    fn drop(&mut self) { hold_watch().released(); }
}

/// Who holds the database lock right now, and a watcher that names a holder
/// keeping it too long. There is one database connection per process, so one
/// note is enough. Costs a few nanoseconds per lock; the watcher wakes every
/// few seconds.
struct HoldWatch {
    holder: Mutex<Option<(&'static std::panic::Location<'static>, String, std::time::Instant)>>,
    holder_thread: Mutex<Option<std::thread::ThreadId>>,
}

impl HoldWatch {
    fn taken(&self, at: &'static std::panic::Location<'static>) {
        let thread = std::thread::current().name().unwrap_or("?").to_string();
        if let Ok(mut h) = self.holder.lock() {
            *h = Some((at, thread, std::time::Instant::now()));
        }
        if let Ok(mut t) = self.holder_thread.lock() {
            *t = Some(std::thread::current().id());
        }
    }
    fn released(&self) {
        if let Ok(mut h) = self.holder.lock() {
            *h = None;
        }
        if let Ok(mut t) = self.holder_thread.lock() {
            *t = None;
        }
    }
    /// This thread already holds the lock: the coming lock never returns.
    fn warn_if_reentry(&self, at: &'static std::panic::Location<'static>) {
        let mine = self.holder_thread.lock().ok().and_then(|t| *t) == Some(std::thread::current().id());
        if mine {
            let held_at = self.holder.lock().ok().and_then(|h| h.as_ref().map(|(l, _, _)| format!("{}:{}", l.file(), l.line())));
            tracing::error!(target: "TRANSPORT", "LOCK_REENTRY db at {}:{} while held at {}", at.file(), at.line(), held_at.unwrap_or_default());
        }
    }
}

fn hold_watch() -> &'static HoldWatch {
    static W: std::sync::OnceLock<HoldWatch> = std::sync::OnceLock::new();
    W.get_or_init(|| {
        let _ = std::thread::Builder::new().name("db-hold-watch".into()).spawn(|| loop {
            std::thread::sleep(std::time::Duration::from_secs(5));
            let held = hold_watch().holder.lock().ok().and_then(|h| h.as_ref().map(|(at, t, since)| (*at, t.clone(), since.elapsed())));
            if let Some((at, thread, long)) = held {
                if long >= std::time::Duration::from_secs(15) {
                    tracing::warn!(
                        target: "TRANSPORT",
                        "DB_LOCK_HELD {}s by {}:{} (thread {})",
                        long.as_secs(), at.file(), at.line(), thread,
                    );
                }
            }
        });
        HoldWatch { holder: Mutex::new(None), holder_thread: Mutex::new(None) }
    })
}

/// Current holder of the database lock as "Ns by file:line thread=T", if any.
pub fn db_holder() -> Option<String> {
    let h = hold_watch().holder.lock().ok()?;
    h.as_ref().map(|(at, t, since)| format!("{}s by {}:{} thread={}", since.elapsed().as_secs(), at.file(), at.line(), t))
}

/// Thread-safe handle to an encrypted SQLite database.
pub struct DatabasePool {
    conn: Mutex<Connection>,
}

impl DatabasePool {
    /// Open (or create) an encrypted database at `path` using `passphrase`.
    ///
    /// This:
    /// 1. Opens the SQLite file.
    /// 2. Sets the SQLCipher PRAGMA key using hex notation (avoids SQL injection).
    /// 3. Runs any pending migrations.
    /// 4. Enables foreign keys.
    pub fn open(path: &Path, passphrase: &str) -> Result<Self> {
        let conn = Connection::open(path).map_err(|e| DatabaseError::OpenFailed {
            path: path.display().to_string(),
            source: e,
        })?;

        // Set the SQLCipher encryption key.
        // Use hex-encoded passphrase with x'...' notation to avoid SQL injection.
        // This prevents any quotes, semicolons, or other special characters in the
        // passphrase from being interpreted as SQL.
        let hex_key = hex::encode(passphrase.as_bytes());
        conn.execute_batch(&format!("PRAGMA key = \"x'{hex_key}'\";"))
            .map_err(|_| DatabaseError::DecryptionFailed)?;

        Self::verify_configure_migrate(conn, path)
    }

    /// Open (or create) an encrypted database at `path` using a **raw 32-byte
    /// key** supplied as 64 lowercase hex characters.
    ///
    /// Unlike [`Self::open`], the key is used verbatim as the SQLCipher raw key
    /// (`PRAGMA key = "x'<64hex>'"`), which bypasses SQLCipher's own PBKDF2 —
    /// appropriate because we have already run Argon2id ourselves to derive this
    /// `DatabaseKey`. This is the path the app will use once a database has been
    /// migrated to the production key.
    pub fn open_with_raw_key(path: &Path, raw_key_hex: &str) -> Result<Self> {
        let key = Self::validate_raw_key_hex(raw_key_hex)?;
        let conn = Connection::open(path).map_err(|e| DatabaseError::OpenFailed {
            path: path.display().to_string(),
            source: e,
        })?;
        conn.execute_batch(&format!("PRAGMA key = \"x'{key}'\";"))
            .map_err(|_| DatabaseError::DecryptionFailed)?;
        Self::verify_configure_migrate(conn, path)
    }

    /// Re-key the live database to a **raw 32-byte key** (64 lowercase hex chars)
    /// via SQLCipher `PRAGMA rekey`.
    ///
    /// `PRAGMA rekey` re-encrypts every page in place with the new key while the
    /// connection stays open; on success the same open `Connection` continues to
    /// work with the new key. After re-keying we force a page read to verify the
    /// new key actually took effect.
    ///
    /// **Safety:** this mutates the on-disk database irreversibly. Callers (the
    /// rekey orchestrator) MUST take a file-level backup via
    /// [`crate`]-external `alpha_keys::db_rekey::backup_db_file`] *before* calling
    /// this, and restore it if this returns an error. This method itself does not
    /// touch the filesystem beyond what SQLCipher does internally. It is NOT
    /// touch the filesystem beyond what SQLCipher does internally.
    pub fn rekey_to_raw_key(&self, new_raw_key_hex: &str) -> Result<()> {
        let conn = self.connection();
        Self::rekey_conn(&conn, new_raw_key_hex)
    }

    /// Re-key on a caller-supplied (already-locked) connection. Used by the
    /// rekey orchestrator, which holds the single pool lock across the WHOLE
    /// critical section (checkpoint → backup → rekey → verify) so no other
    /// in-process writer can interleave. `rekey_to_raw_key` is the convenience
    /// wrapper that locks for you.
    pub fn rekey_conn(conn: &Connection, new_raw_key_hex: &str) -> Result<()> {
        let key = Self::validate_raw_key_hex(new_raw_key_hex)?;
        conn.execute_batch(&format!("PRAGMA rekey = \"x'{key}'\";"))
            .map_err(|_| DatabaseError::DecryptionFailed)?;
        // Verify the new key works by forcing SQLCipher to read a page.
        conn.execute_batch("SELECT count(*) FROM sqlite_master;")
            .map_err(|_| DatabaseError::DecryptionFailed)?;
        info!("Database successfully re-keyed to raw production key");
        Ok(())
    }

    /// Validate that `raw_key_hex` is exactly 64 lowercase hex chars (32 bytes)
    /// and return it. Rejects anything else so a malformed key can never be
    /// interpolated into a PRAGMA.
    fn validate_raw_key_hex(raw_key_hex: &str) -> Result<String> {
        let ok = raw_key_hex.len() == 64
            && raw_key_hex.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase());
        if !ok {
            return Err(DatabaseError::DecryptionFailed);
        }
        Ok(raw_key_hex.to_string())
    }

    /// Shared tail of every open path: verify the key works, enable FK + WAL,
    /// run migrations, and wrap the connection in the pool.
    fn verify_configure_migrate(conn: Connection, path: &Path) -> Result<Self> {
        // Verify the key by forcing SQLCipher to derive/check it now.
        // If the key is wrong this fails.
        conn.execute_batch("SELECT count(*) FROM sqlite_master;")
            .map_err(|_| DatabaseError::DecryptionFailed)?;

        // Enable foreign-key enforcement.
        conn.execute_batch("PRAGMA foreign_keys = ON;")?;

        // Enable WAL mode for better concurrent read performance.
        conn.execute_batch("PRAGMA journal_mode = WAL;")?;

        // Run migrations.
        migrations::run_migrations(&conn)?;

        // Claims do not survive the process that took them.
        //
        // A queue row marked as being sent belongs to a live sender. After
        // the process dies that sender no longer exists, and waiting out the
        // lease would mean an hour of not retrying for no reason. DB open is
        // the one moment when it is certain that no previous owner is left.
        let freed = conn.execute(
            "UPDATE pending_media SET status = 'queued', claimed_at = NULL WHERE status = 'uploading'",
            [],
        ).unwrap_or(0);
        if freed > 0 {
            info!("CLAIM_RECOVER claims of the previous run released: {freed}");
        }

        info!("Database opened successfully at {}", path.display());

        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    /// Acquire the connection lock and return a guard.
    ///
    /// The guard dereferences to `rusqlite::Connection`, allowing you to
    /// run queries directly. Who holds it (the calling line, the thread, since
    /// when) is noted on the way: a lock that is never let go is then named
    /// in the log by [`HoldWatch`] instead of silently freezing the app.
    #[track_caller]
    pub fn connection(&self) -> DbGuard<'_> {
        let at = std::panic::Location::caller();
        hold_watch().warn_if_reentry(at);
        let guard = self.conn.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        hold_watch().taken(at);
        DbGuard { guard }
    }

    /// Force a full WAL checkpoint, folding `<db>-wal` back into the main file
    /// the WAL.
    ///
    /// This MUST run before taking a file-level backup of the DB: in WAL mode
    /// recent writes live in the `-wal` sidecar, so a plain `std::fs::copy` of
    /// the main file alone would produce an INCONSISTENT backup missing the
    /// latest rows. It is also run after a re-key so a second connection reads
    /// a fully-materialised main file.
    pub fn checkpoint_truncate(&self) -> Result<()> {
        let conn = self.connection();
        Self::checkpoint_conn(&conn)
    }

    /// Checkpoint on a caller-supplied (already-locked) connection — see
    /// [`Self::checkpoint_truncate`]. Used inside the orchestrator's held-lock
    /// critical section.
    pub fn checkpoint_conn(conn: &Connection) -> Result<()> {
        conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")?;
        Ok(())
    }

    /// Core application tables that must be present and decryptable for a
    /// migrated database to be considered healthy (rekey verify).
    const VERIFY_CORE_TABLES: &'static [&'static str] = &[
        "contacts",
        "chats",
        "chat_members",
        "messages",
        "mls_groups",
        "mls_key_packages",
        "key_value_store",
        "outbox",
        "pending_sends",
        "pending_media",
    ];

    /// Extended post-rekey integrity verification.
    ///
    /// Proves the database is fully healthy AND genuinely re-encrypted under the
    /// new key, in two passes:
    ///
    /// 1. On the LIVE (already-rekeyed) connection: checkpoint, then run the
    ///    integrity battery.
    /// 2. On a SECOND, INDEPENDENT connection freshly opened from the file with
    ///    the new raw key: run the same battery. This is the crucial proof —
    ///    a rekey can leave the in-memory connection working while the file on
    ///    disk fails to re-open under the new key; only a fresh open catches it.
    ///
    /// The battery: `cipher_integrity_check` (per-page HMAC over the whole DB),
    /// `integrity_check` (b-tree structure), a `sqlite_master` count, a readable
    /// `SELECT COUNT(*)` on every core table, and the `identity_kv_key` row must
    /// exist and parse as JSON (proves the identity record decrypts intact).
    ///
    /// Returns `Ok(())` only if BOTH passes succeed.
    pub fn verify_migrated(
        &self,
        db_path: &Path,
        raw_key_hex: &str,
        identity_kv_key: &str,
    ) -> Result<()> {
        // Pass 1 — live connection (checkpoint first so the main file is whole).
        {
            let conn = self.connection();
            Self::checkpoint_conn(&conn)?;
            Self::run_integrity_battery(&conn, identity_kv_key)?;
        }
        // Pass 2 — fresh independent connection from the file with the new key.
        Self::open_and_verify(db_path, raw_key_hex, identity_kv_key)?;
        info!("Post-rekey verify passed on both live and fresh connections");
        Ok(())
    }

    /// Open a SECOND independent connection to `db_path` with `raw_key_hex` and
    /// run the integrity battery on it (verify pass 2).
    ///
    /// This is the crucial proof that the on-disk file genuinely re-opens under
    /// the new key — a rekey can leave the in-memory connection working while a
    /// fresh open fails. Used by both `verify_migrated` and the orchestrator's
    /// held-lock path.
    pub fn open_and_verify(db_path: &Path, raw_key_hex: &str, identity_kv_key: &str) -> Result<()> {
        let key = Self::validate_raw_key_hex(raw_key_hex)?;
        let conn2 = Connection::open(db_path).map_err(|e| DatabaseError::OpenFailed {
            path: db_path.display().to_string(),
            source: e,
        })?;
        conn2
            .execute_batch(&format!("PRAGMA key = \"x'{key}'\";"))
            .map_err(|_| DatabaseError::DecryptionFailed)?;
        Self::run_integrity_battery(&conn2, identity_kv_key)?;
        conn2.close().map_err(|(_c, e)| DatabaseError::Sqlite(e))?;
        Ok(())
    }

    /// The shared integrity battery used by [`Self::verify_migrated`] on each
    /// connection.
    ///
    /// Cost note: `cipher_integrity_check` and `integrity_check`
    /// are O(database size) BY NECESSITY — they page through the whole file to
    /// prove every page re-encrypted/decodes correctly; that is the entire point
    /// of a post-rekey verify, and it runs ONCE per migration. Everything else is
    /// O(1)/O(#tables): we never `COUNT(*)` or read all rows. Per-table probes
    /// use `SELECT 1 ... LIMIT 1` (touches the table's root page only) and the
    /// identity lookup is an indexed primary-key fetch.
    pub fn run_integrity_battery(conn: &Connection, identity_kv_key: &str) -> Result<()> {
        // 1. SQLCipher per-page HMAC check — any returned row is a problem.
        {
            let mut stmt = conn.prepare("PRAGMA cipher_integrity_check;")?;
            let problems: Vec<String> = stmt
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<std::result::Result<_, _>>()?;
            if !problems.is_empty() {
                return Err(DatabaseError::IntegrityCheckFailed(format!(
                    "cipher_integrity_check reported {} problem(s)",
                    problems.len()
                )));
            }
        }

        // 2. SQLite structural integrity — expect exactly "ok".
        let ic: String = conn.query_row("PRAGMA integrity_check;", [], |r| r.get(0))?;
        if ic != "ok" {
            return Err(DatabaseError::IntegrityCheckFailed(format!(
                "integrity_check returned '{ic}'"
            )));
        }

        // 3. sqlite_master is readable under the new key.
        let _master_objects: i64 =
            conn.query_row("SELECT COUNT(*) FROM sqlite_master;", [], |r| r.get(0))?;

        // 4. Every core table decrypts and is queryable. `LIMIT 1` touches only
        //    the table's root page (O(1)) — we deliberately do NOT COUNT(*) or
        //    scan rows, so this stays cheap on a DB with tens of thousands of
        //    messages.
        for t in Self::VERIFY_CORE_TABLES {
            let mut stmt = conn
                .prepare(&format!("SELECT 1 FROM \"{t}\" LIMIT 1;"))
                .map_err(|e| {
                    DatabaseError::IntegrityCheckFailed(format!("core table '{t}' unreadable: {e}"))
                })?;
            let mut rows = stmt.query([]).map_err(|e| {
                DatabaseError::IntegrityCheckFailed(format!("core table '{t}' query failed: {e}"))
            })?;
            // Drive the plan once (decrypts the root page); zero rows is fine.
            rows.next().map_err(|e| {
                DatabaseError::IntegrityCheckFailed(format!("core table '{t}' read failed: {e}"))
            })?;
        }

        // 5. The identity record exists, decrypts, and is intact JSON. We never
        //    expose the secret — only prove the row is present and parseable.
        let identity_val: Option<String> = conn
            .query_row(
                "SELECT value FROM key_value_store WHERE key = ?1",
                [identity_kv_key],
                |r| r.get(0),
            )
            .optional()?;
        let identity_val = identity_val.ok_or_else(|| {
            DatabaseError::IntegrityCheckFailed(format!(
                "identity row '{identity_kv_key}' missing after rekey"
            ))
        })?;
        serde_json::from_str::<serde_json::Value>(&identity_val).map_err(|e| {
            DatabaseError::IntegrityCheckFailed(format!("identity row corrupt (not JSON): {e}"))
        })?;

        Ok(())
    }

    /// Close the database, consuming the pool.
    ///
    /// After this call the pool can no longer be used.
    pub fn close(self) -> Result<()> {
        let conn = match self.conn.into_inner() {
            Ok(c) => c,
            Err(e) => e.into_inner(),
        };
        conn.close().map_err(|(_conn, e)| DatabaseError::Sqlite(e))?;
        info!("Database closed");
        Ok(())
    }
}

#[cfg(test)]
mod rekey_tests {
    use super::*;

    // A valid 64-hex (32-byte) raw key for tests.
    const KEY_A: &str = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    const KEY_B: &str = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    fn tmp_db(name: &str) -> std::path::PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!("alpha_pool_rekey_{name}.db"));
        let _ = std::fs::remove_file(&p);
        let _ = std::fs::remove_file(format!("{}-wal", p.display()));
        let _ = std::fs::remove_file(format!("{}-shm", p.display()));
        p
    }

    #[test]
    fn rekey_from_passphrase_to_raw_key_roundtrips_data() {
        let path = tmp_db("roundtrip");

        // Open with a legacy passphrase and write a marker row.
        let pool = DatabasePool::open(&path, "legacy-pw").unwrap();
        {
            let conn = pool.connection();
            conn.execute_batch("CREATE TABLE t (x TEXT); INSERT INTO t (x) VALUES ('hello');")
                .unwrap();
        }
        // Re-key the live connection to the raw production key, then close.
        pool.rekey_to_raw_key(KEY_A).unwrap();
        pool.close().unwrap();

        // Re-open with the raw key — the marker row must still be there.
        let pool2 = DatabasePool::open_with_raw_key(&path, KEY_A).unwrap();
        {
            let conn = pool2.connection();
            let got: String = conn
                .query_row("SELECT x FROM t LIMIT 1", [], |r| r.get(0))
                .unwrap();
            assert_eq!(got, "hello");
        }
        pool2.close().unwrap();

        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn old_passphrase_fails_after_rekey() {
        let path = tmp_db("oldfails");
        let pool = DatabasePool::open(&path, "legacy-pw").unwrap();
        pool.rekey_to_raw_key(KEY_A).unwrap();
        pool.close().unwrap();

        // The old passphrase must NOT open the re-keyed DB.
        let res = DatabasePool::open(&path, "legacy-pw");
        assert!(matches!(res, Err(DatabaseError::DecryptionFailed)));

        // A wrong raw key must also fail.
        let res2 = DatabasePool::open_with_raw_key(&path, KEY_B);
        assert!(matches!(res2, Err(DatabaseError::DecryptionFailed)));

        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn verify_migrated_passes_after_full_rekey_cycle() {
        let path = tmp_db("verify_ok");
        let pool = DatabasePool::open(&path, "legacy-pw").unwrap();
        {
            let conn = pool.connection();
            // Seed an identity row + some user data so the battery has real rows.
            conn.execute(
                "INSERT INTO key_value_store (key, value, updated_at) VALUES ('identity_v1', '{\"secret\":\"x\"}', '');",
                [],
            )
            .unwrap();
        }
        pool.rekey_to_raw_key(KEY_A).unwrap();
        // Full extended verify: live pass + second independent connection.
        pool.verify_migrated(&path, KEY_A, "identity_v1").unwrap();
        pool.close().unwrap();
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn verify_migrated_fails_when_identity_missing() {
        let path = tmp_db("verify_no_identity");
        let pool = DatabasePool::open(&path, "legacy-pw").unwrap();
        pool.rekey_to_raw_key(KEY_A).unwrap();
        // No identity row was inserted → the battery must reject it.
        let res = pool.verify_migrated(&path, KEY_A, "identity_v1");
        assert!(matches!(res, Err(DatabaseError::IntegrityCheckFailed(_))));
        pool.close().unwrap();
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn malformed_raw_key_is_rejected() {
        // Too short, uppercase, and non-hex must all be rejected before any
        // PRAGMA interpolation.
        assert!(DatabasePool::validate_raw_key_hex("abc").is_err());
        assert!(DatabasePool::validate_raw_key_hex(&"AB".repeat(32)).is_err());
        assert!(DatabasePool::validate_raw_key_hex(&"zz".repeat(32)).is_err());
        assert!(DatabasePool::validate_raw_key_hex(KEY_A).is_ok());
    }
}
