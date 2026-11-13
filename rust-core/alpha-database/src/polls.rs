//! Polls (migration 027): who chose what, and which polls are closed.
//!
//! A poll itself is an ordinary media message (`"poll": true` in its ref);
//! votes and closing are control messages applied here. Every voter has one
//! current choice: a newer vote replaces it, an empty one withdraws it.

use rusqlite::{params, Connection, OptionalExtension};

use crate::error::Result;

/// What a poll asks, as its message carries it.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PollSpec {
    pub options: u32,
    pub multi: bool,
    pub quiz: bool,
    pub anon: bool,
    /// Unix seconds after which no vote counts (0 = never).
    pub close_at: i64,
}

impl PollSpec {
    /// Read a poll from its message's media ref (None: not a poll).
    pub fn from_ref(media_ref: &str) -> Option<PollSpec> {
        let v: serde_json::Value = serde_json::from_str(media_ref).ok()?;
        if !v.get("poll").and_then(|x| x.as_bool()).unwrap_or(false) { return None; }
        let options = v.get("o").and_then(|x| x.as_array()).map(|a| a.len() as u32).unwrap_or(0);
        if !(2..=10).contains(&options) { return None; }
        Some(PollSpec {
            options,
            multi: v.get("multi").and_then(|x| x.as_bool()).unwrap_or(false),
            quiz: v.get("quiz").and_then(|x| x.as_bool()).unwrap_or(false),
            anon: v.get("anon").and_then(|x| x.as_bool()).unwrap_or(true),
            close_at: v.get("close_at").and_then(|x| x.as_i64()).unwrap_or(0),
        })
    }

    /// Is [choice] a valid vote for this poll (empty = withdraw)?
    pub fn accepts(&self, choice: &[u32]) -> bool {
        if choice.is_empty() { return !self.quiz; }
        if !self.multi && choice.len() > 1 { return false; }
        let mut seen = std::collections::HashSet::new();
        choice.iter().all(|&o| o < self.options && seen.insert(o))
    }
}

fn encode(choice: &[u32]) -> String {
    choice.iter().map(|o| o.to_string()).collect::<Vec<_>>().join(",")
}

fn decode(s: &str) -> Vec<u32> {
    s.split(',').filter_map(|x| x.trim().parse().ok()).collect()
}

pub fn is_closed(conn: &Connection, chat_id: &str, poll_id: &str) -> bool {
    conn.query_row(
        "SELECT 1 FROM poll_closed WHERE chat_id = ?1 AND poll_id = ?2",
        params![chat_id, poll_id], |_| Ok(()),
    ).optional().ok().flatten().is_some()
}

/// The voter's current choice, if any.
pub fn choice_of(conn: &Connection, chat_id: &str, poll_id: &str, voter: &str) -> Option<Vec<u32>> {
    conn.query_row(
        "SELECT options FROM poll_votes WHERE chat_id = ?1 AND poll_id = ?2 AND voter = ?3",
        params![chat_id, poll_id, voter], |r| r.get::<_, String>(0),
    ).optional().ok().flatten().map(|s| decode(&s))
}

/// Apply a vote written at [at]; an older one than the voter's current is
/// ignored (relays deliver out of order). A quiz answer is final. Returns
/// whether anything changed.
pub fn vote(conn: &Connection, chat_id: &str, poll_id: &str, spec: &PollSpec, voter: &str, choice: &[u32], at: i64) -> Result<bool> {
    if is_closed(conn, chat_id, poll_id) || !spec.accepts(choice) { return Ok(false); }
    if spec.close_at > 0 && at > spec.close_at { return Ok(false); }
    let current: Option<(String, i64)> = conn.query_row(
        "SELECT options, at FROM poll_votes WHERE chat_id = ?1 AND poll_id = ?2 AND voter = ?3",
        params![chat_id, poll_id, voter], |r| Ok((r.get(0)?, r.get(1)?)),
    ).optional()?;
    if let Some((_, was)) = &current {
        if spec.quiz || *was > at { return Ok(false); }
    }
    if choice.is_empty() {
        conn.execute("DELETE FROM poll_votes WHERE chat_id = ?1 AND poll_id = ?2 AND voter = ?3", params![chat_id, poll_id, voter])?;
    } else {
        conn.execute(
            "INSERT OR REPLACE INTO poll_votes (chat_id, poll_id, voter, options, at) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![chat_id, poll_id, voter, encode(choice), at],
        )?;
    }
    Ok(true)
}

/// Close a poll; [tally] is its author's final count per option.
pub fn close(conn: &Connection, chat_id: &str, poll_id: &str, at: i64, tally: &[u32]) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO poll_closed (chat_id, poll_id, at, tally) VALUES (?1, ?2, ?3, ?4)",
        params![chat_id, poll_id, at, encode(tally)],
    )?;
    Ok(())
}

/// Count per option, voters, and (for open polls) who chose what. A closed
/// poll's author tally wins where it is higher (votes cast before we joined).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Results {
    pub counts: Vec<u32>,
    pub voters: u32,
    pub closed: bool,
    pub by: Vec<(String, Vec<u32>)>,
}

pub fn results(conn: &Connection, chat_id: &str, poll_id: &str, spec: &PollSpec) -> Result<Results> {
    let mut counts = vec![0u32; spec.options as usize];
    let mut by = Vec::new();
    let mut stmt = conn.prepare("SELECT voter, options FROM poll_votes WHERE chat_id = ?1 AND poll_id = ?2 ORDER BY at")?;
    let rows = stmt.query_map(params![chat_id, poll_id], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
    for row in rows {
        let (voter, opts) = row?;
        let choice = decode(&opts);
        for &o in &choice {
            if let Some(c) = counts.get_mut(o as usize) { *c += 1; }
        }
        by.push((voter, choice));
    }
    let mut voters = by.len() as u32;
    let closed: Option<String> = conn.query_row(
        "SELECT tally FROM poll_closed WHERE chat_id = ?1 AND poll_id = ?2",
        params![chat_id, poll_id], |r| r.get(0),
    ).optional()?;
    if let Some(t) = &closed {
        let tally = decode(t);
        for (i, c) in counts.iter_mut().enumerate() {
            if let Some(&a) = tally.get(i) { *c = (*c).max(a); }
        }
        if !spec.multi { voters = voters.max(counts.iter().sum()); }
    }
    if spec.anon { by.clear(); }
    Ok(Results { counts, voters, closed: closed.is_some(), by })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(include_str!("migrations/027_polls.sql")).unwrap();
        c
    }
    fn spec(multi: bool, quiz: bool) -> PollSpec { PollSpec { options: 3, multi, quiz, anon: false, close_at: 0 } }

    #[test]
    fn one_current_vote_per_voter() {
        let c = db();
        let s = spec(false, false);
        assert!(vote(&c, "c", "p", &s, "a", &[0], 10).unwrap());
        assert!(vote(&c, "c", "p", &s, "a", &[2], 20).unwrap());
        assert!(!vote(&c, "c", "p", &s, "a", &[1], 15).unwrap(), "older vote arriving late is ignored");
        assert!(vote(&c, "c", "p", &s, "b", &[2], 12).unwrap());
        let r = results(&c, "c", "p", &s).unwrap();
        assert_eq!(r.counts, vec![0, 0, 2]);
        assert_eq!(r.voters, 2);
        assert!(vote(&c, "c", "p", &s, "a", &[], 30).unwrap(), "withdraw");
        assert_eq!(results(&c, "c", "p", &s).unwrap().counts, vec![0, 0, 1]);
    }

    #[test]
    fn rules_single_multi_quiz_closed() {
        let c = db();
        assert!(!vote(&c, "c", "p", &spec(false, false), "a", &[0, 1], 1).unwrap(), "single choice");
        assert!(!vote(&c, "c", "p", &spec(true, false), "a", &[0, 0], 1).unwrap(), "duplicates");
        assert!(!vote(&c, "c", "p", &spec(true, false), "a", &[5], 1).unwrap(), "out of range");
        assert!(vote(&c, "c", "q", &spec(false, true), "a", &[1], 1).unwrap());
        assert!(!vote(&c, "c", "q", &spec(false, true), "a", &[2], 2).unwrap(), "a quiz answer is final");
        close(&c, "c", "q", 3, &[4, 1, 0]).unwrap();
        assert!(!vote(&c, "c", "q", &spec(false, true), "b", &[0], 4).unwrap(), "closed");
        let r = results(&c, "c", "q", &spec(false, true)).unwrap();
        assert!(r.closed);
        assert_eq!(r.counts, vec![4, 1, 0], "the author's tally fills in earlier votes");
        let timed = PollSpec { close_at: 100, ..spec(false, false) };
        assert!(!vote(&c, "c", "t", &timed, "a", &[0], 101).unwrap(), "after its time");
    }

    #[test]
    fn anonymous_results_name_nobody() {
        let c = db();
        let s = PollSpec { anon: true, ..spec(false, false) };
        vote(&c, "c", "p", &s, "a", &[1], 1).unwrap();
        let r = results(&c, "c", "p", &s).unwrap();
        assert_eq!(r.counts, vec![0, 1, 0]);
        assert!(r.by.is_empty());
    }

    #[test]
    fn spec_from_ref() {
        let s = PollSpec::from_ref(r#"{"poll":true,"q":"?","o":["a","b"],"multi":true,"anon":false}"#).unwrap();
        assert_eq!(s.options, 2);
        assert!(s.multi && !s.anon);
        assert!(PollSpec::from_ref(r#"{"poll":true,"o":["a"]}"#).is_none(), "one option is not a poll");
        assert!(PollSpec::from_ref(r#"{"link":true}"#).is_none());
    }
}
