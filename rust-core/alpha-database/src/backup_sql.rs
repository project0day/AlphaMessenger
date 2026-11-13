//! Replaying a backup dump without trusting it.
//!
//! A backup is our own SQL dump, but the file can come from anyone: someone
//! hands over "your backup" and its password. While the dump runs, SQLite's
//! authorizer allows only what our exporter writes — rows inserted into the
//! tables our own schema already has, their `CREATE … IF NOT EXISTS` no-ops,
//! `PRAGMA foreign_keys` — and refuses the rest: new tables, `ATTACH` (files
//! outside the database), triggers, views, drops, updates, and the bookkeeping
//! tables (a forged migrations row would switch future migrations off).
//!
//! A dump is data only. No trigger fires while it runs, and the search index
//! (a virtual table and its shadow tables) is never taken from it: both are
//! derived from the messages and rebuilt once the rows are in.

use rusqlite::hooks::{AuthAction, AuthContext, Authorization};
use rusqlite::Connection;
use std::collections::HashSet;

/// The only table whose rows the dump may replace (avatar bytes, keyed by path).
const BLOBS_TABLE: &str = "backup_blobs";

/// Tables a dump never writes, even though they exist.
const NEVER_RESTORED: &[&str] = &["schema_migrations", "sync_checkpoints"];

fn allowed(ctx: AuthContext<'_>, tables: &HashSet<String>, derived: &HashSet<String>) -> Authorization {
    // Older dumps carry the search index too: skipped without failing the restore.
    let target = match ctx.action {
        AuthAction::CreateTable { table_name }
        | AuthAction::CreateIndex { table_name, .. }
        | AuthAction::CreateVtable { table_name, .. }
        | AuthAction::Insert { table_name } => Some(table_name),
        _ => None,
    };
    if target.is_some_and(|t| derived.contains(&t.to_ascii_lowercase())) {
        return Authorization::Ignore;
    }
    let known = |t: &str| {
        let t = t.to_ascii_lowercase();
        (tables.contains(&t) || t == BLOBS_TABLE) && !NEVER_RESTORED.contains(&t.as_str())
    };
    let ok = match ctx.action {
        AuthAction::CreateTable { table_name } => known(table_name),
        AuthAction::CreateIndex { table_name, .. } => known(table_name),
        // A permitted CREATE writes its own schema row (a statement cannot:
        // that needs writable_schema, refused below).
        AuthAction::Insert { table_name } => known(table_name) || table_name.eq_ignore_ascii_case("sqlite_master"),
        // SQLite fills a new index with it.
        AuthAction::Reindex { .. }
        // Uniqueness checks and IF NOT EXISTS read the schema.
        | AuthAction::Read { .. }
        // A multi-row VALUES list compiles as a SELECT.
        | AuthAction::Select => true,
        AuthAction::Pragma { pragma_name, .. } => pragma_name.eq_ignore_ascii_case("foreign_keys"),
        // INSERT OR REPLACE into the blobs table removes the old row first.
        AuthAction::Delete { table_name } => table_name.eq_ignore_ascii_case(BLOBS_TABLE),
        // CREATE writes its own schema row; a statement cannot touch it
        // directly (that needs writable_schema, refused above).
        AuthAction::Update { table_name, .. } => table_name.eq_ignore_ascii_case("sqlite_master"),
        _ => false,
    };
    if ok { Authorization::Allow } else { Authorization::Deny }
}

/// Run `sql` (a backup dump) on `conn` under the dump-only authorizer.
/// Anything outside it fails the whole dump, and nothing of it is left behind.
pub fn apply(conn: &Connection, sql: &str) -> rusqlite::Result<()> {
    conn.execute_batch("SAVEPOINT backup_apply;")?;
    match replay(conn, sql) {
        Ok(()) => conn.execute_batch("RELEASE backup_apply;"),
        Err(e) => {
            let _ = conn.execute_batch("ROLLBACK TO backup_apply; RELEASE backup_apply;");
            Err(e)
        }
    }
}

fn replay(conn: &Connection, sql: &str) -> rusqlite::Result<()> {
    // The tables our migrations made, read before anything of the dump runs.
    let tables: HashSet<String> = conn
        .prepare("SELECT lower(name) FROM sqlite_master WHERE type = 'table'")?
        .query_map([], |r| r.get::<_, String>(0))?
        .collect::<rusqlite::Result<_>>()?;
    let derived = derived_tables(conn)?;
    let triggers: Vec<(String, String)> = conn
        .prepare("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND sql IS NOT NULL")?
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<rusqlite::Result<_>>()?;
    for (name, _) in &triggers {
        conn.execute_batch(&format!("DROP TRIGGER \"{}\";", name.replace('"', "\"\"")))?;
    }
    conn.authorizer(Some(move |ctx: AuthContext<'_>| allowed(ctx, &tables, &derived)));
    let result = conn.execute_batch(sql);
    conn.authorizer(None::<fn(AuthContext<'_>) -> Authorization>);
    result?;
    for (_, ddl) in &triggers {
        conn.execute_batch(ddl)?;
    }
    rebuild_search(conn)
}

/// Virtual tables and their shadow tables: data derived from other tables.
pub fn derived_tables(conn: &Connection) -> rusqlite::Result<HashSet<String>> {
    conn.prepare("SELECT lower(name) FROM pragma_table_list WHERE schema = 'main' AND type IN ('virtual', 'shadow')")?
        .query_map([], |r| r.get::<_, String>(0))?
        .collect()
}

/// The index is emptied, then the search trigger files every message again,
/// so it holds exactly what live inserts would have put there.
fn rebuild_search(conn: &Connection) -> rusqlite::Result<()> {
    let has: i64 = conn.query_row(
        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'message_fts'", [], |r| r.get(0))?;
    if has == 0 { return Ok(()); }
    conn.execute_batch("INSERT INTO message_fts(message_fts) VALUES ('delete-all');
        UPDATE messages SET content = content;")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch("CREATE TABLE messages(id TEXT PRIMARY KEY, body TEXT);
            CREATE TABLE schema_migrations(version INTEGER, name TEXT);").unwrap();
        c
    }

    #[test]
    fn our_dump_restores() {
        let c = db();
        let dump = "PRAGMA foreign_keys=OFF;\n\
            CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY, body TEXT);\n\
            CREATE INDEX IF NOT EXISTS i_body ON messages(body);\n\
            INSERT OR IGNORE INTO \"messages\" (\"id\", \"body\") VALUES\n('a', 'hi'),\n('b', 'x');\n\
            CREATE TABLE IF NOT EXISTS backup_blobs(path TEXT PRIMARY KEY, data BLOB);\n\
            INSERT OR REPLACE INTO backup_blobs(path, data) VALUES('p', x'00');\n\
            INSERT OR REPLACE INTO backup_blobs(path, data) VALUES('p', x'01');\n\
            PRAGMA foreign_keys=ON;\n";
        apply(&c, dump).unwrap();
        let n: i64 = c.query_row("SELECT count(*) FROM messages", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 2);
    }

    #[test]
    fn a_row_of_our_dump_may_take_the_place_of_one_already_there() {
        let c = db();
        c.execute_batch("INSERT INTO messages VALUES ('a', 'old');").unwrap();
        apply(&c, "INSERT OR REPLACE INTO \"messages\" (\"id\", \"body\") VALUES ('a', 'new');").unwrap();
        let body: String = c.query_row("SELECT body FROM messages WHERE id = 'a'", [], |r| r.get(0)).unwrap();
        assert_eq!(body, "new");
    }

    #[test]
    fn hostile_statements_are_refused() {
        for evil in [
            "ATTACH DATABASE '/tmp/x.db' AS x;",
            "CREATE TRIGGER t AFTER INSERT ON messages BEGIN DELETE FROM messages; END;",
            "CREATE VIEW v AS SELECT * FROM messages;",
            "DROP TABLE messages;",
            "DELETE FROM messages;",
            "UPDATE messages SET body = 'x';",
            "PRAGMA writable_schema = ON;",
            "CREATE TABLE IF NOT EXISTS evil(x);",
            "INSERT INTO schema_migrations VALUES(999, 'x');",
        ] {
            let c = db();
            assert!(apply(&c, evil).is_err(), "accepted: {evil}");
        }
    }

    #[test]
    fn triggers_rest_while_the_dump_runs_and_come_back() {
        let c = db();
        c.execute_batch("CREATE TABLE log(x TEXT);
            CREATE TRIGGER t AFTER INSERT ON messages BEGIN INSERT INTO log VALUES (upper(new.body)); END;").unwrap();
        apply(&c, "INSERT OR IGNORE INTO messages VALUES('a','b');").unwrap();
        let logged: i64 = c.query_row("SELECT count(*) FROM log", [], |r| r.get(0)).unwrap();
        assert_eq!(logged, 0);
        c.execute_batch("INSERT INTO messages VALUES('c','d');").unwrap();
        let logged: i64 = c.query_row("SELECT count(*) FROM log", [], |r| r.get(0)).unwrap();
        assert_eq!(logged, 1);
    }

    #[test]
    fn a_failed_dump_leaves_nothing() {
        let c = db();
        c.execute_batch("CREATE TRIGGER t AFTER INSERT ON messages BEGIN SELECT 1; END;").unwrap();
        assert!(apply(&c, "INSERT INTO messages VALUES('a','b'); DROP TABLE messages;").is_err());
        let n: i64 = c.query_row("SELECT count(*) FROM messages", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 0);
        let t: i64 = c.query_row("SELECT count(*) FROM sqlite_master WHERE type='trigger'", [], |r| r.get(0)).unwrap();
        assert_eq!(t, 1);
    }

    #[test]
    fn the_authorizer_does_not_outlive_the_restore() {
        let c = db();
        apply(&c, "INSERT OR IGNORE INTO messages VALUES('a','b');").unwrap();
        c.execute_batch("DELETE FROM messages;").unwrap();
    }
}
