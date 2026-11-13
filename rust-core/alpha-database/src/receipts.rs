//! Delivery receipts: which members have received our messages (migration 026).

use rusqlite::{params, Connection};

use crate::error::Result;

/// Record that [member] received [message_id]; returns how many distinct
/// members have received it so far.
pub fn record(conn: &Connection, message_id: &str, member: &str, at: &str) -> Result<u32> {
    conn.execute(
        "INSERT OR IGNORE INTO receipts (message_id, member, at) VALUES (?1, ?2, ?3)",
        params![message_id, member, at],
    )?;
    let n: i64 = conn.query_row(
        "SELECT COUNT(*) FROM receipts WHERE message_id = ?1",
        params![message_id],
        |r| r.get(0),
    )?;
    Ok(n as u32)
}

/// Forget receipts of messages that are delivered or gone (nothing waits on them).
pub fn prune(conn: &Connection) -> Result<usize> {
    Ok(conn.execute(
        "DELETE FROM receipts WHERE message_id NOT IN
           (SELECT id FROM messages WHERE delivered_at IS NULL AND read_at IS NULL)",
        [],
    )?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_distinct_members() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(include_str!("migrations/026_receipts.sql")).unwrap();
        assert_eq!(record(&conn, "m1", "a", "t").unwrap(), 1);
        assert_eq!(record(&conn, "m1", "a", "t").unwrap(), 1);
        assert_eq!(record(&conn, "m1", "b", "t").unwrap(), 2);
        assert_eq!(record(&conn, "m2", "a", "t").unwrap(), 1);
    }
}
