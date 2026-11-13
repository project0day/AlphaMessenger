//! Group roles & permission rules (app-layer, G-3).
//!
//! MLS itself has **no** concept of roles — any member can technically issue a
//! Commit. Roles here are an application-layer convention enforced by clients:
//! we hide actions a user has no right to, and we IGNORE incoming role/ban/mute
//! control messages whose sender lacks the right. A malicious forked client can
//! bypass this — that is the documented limit of serverless + MLS. Real
//! eviction protection still comes from the MLS Remove+Commit ratchet.
//!
//! This module is **pure logic, no crypto, no I/O** — it only decides whether a
//! given actor may change a target's role. Storage (KV) and transport (control
//! messages) live in the FFI / sync crates.

/// The four group roles, highest-privilege first.
pub const ROLE_OWNER: &str = "owner";
pub const ROLE_ADMIN: &str = "admin";
pub const ROLE_MODERATOR: &str = "moderator";
pub const ROLE_MEMBER: &str = "member";

/// Numeric rank for a role string (unknown → member).
pub fn role_rank(role: &str) -> u8 {
    match role {
        ROLE_OWNER => 3,
        ROLE_ADMIN => 2,
        ROLE_MODERATOR => 1,
        _ => 0, // member / unknown
    }
}

/// Normalise an arbitrary string to one of the four canonical roles.
pub fn normalize_role(role: &str) -> &'static str {
    match role {
        ROLE_OWNER => ROLE_OWNER,
        ROLE_ADMIN => ROLE_ADMIN,
        ROLE_MODERATOR => ROLE_MODERATOR,
        _ => ROLE_MEMBER,
    }
}

/// Can `actor_role` change `target_current_role` to `new_role`?
///
/// Rules (mirrors the table in RoadmapGroups.md):
/// - Only the OWNER can grant the OWNER role (= ownership transfer).
/// - OWNER may set anyone else (not another owner) to admin/moderator/member.
/// - ADMIN may only manage MODERATOR/MEMBER (never touch admins or the owner,
///   and never grant admin).
/// - MODERATOR / MEMBER may not change roles at all.
pub fn can_change_role(actor_role: &str, target_current_role: &str, new_role: &str) -> bool {
    let actor = normalize_role(actor_role);
    let target = normalize_role(target_current_role);
    let new = normalize_role(new_role);

    // Granting ownership is a transfer — owner only.
    if new == ROLE_OWNER {
        return actor == ROLE_OWNER && target != ROLE_OWNER;
    }

    match actor {
        ROLE_OWNER => target != ROLE_OWNER, // can re-rank anyone but a (the) owner
        ROLE_ADMIN => {
            // Admins manage moderators/members only.
            role_rank(target) < role_rank(ROLE_ADMIN) && role_rank(new) < role_rank(ROLE_ADMIN)
        }
        _ => false,
    }
}

/// Can `actor_role` remove (kick/ban) a member currently holding `target_role`?
/// - OWNER: anyone but self/owner.
/// - ADMIN: moderators & members.
/// - MODERATOR: members only.
pub fn can_remove(actor_role: &str, target_role: &str) -> bool {
    let actor = role_rank(actor_role);
    let target = role_rank(target_role);
    if normalize_role(target_role) == ROLE_OWNER {
        return false; // the owner can't be kicked
    }
    match normalize_role(actor_role) {
        ROLE_OWNER => true,
        ROLE_ADMIN => target < role_rank(ROLE_ADMIN),
        ROLE_MODERATOR => target < role_rank(ROLE_MODERATOR),
        _ => false,
    }
    .then_some(actor > target)
    .unwrap_or(false)
}

/// May this role invite / change group metadata? (OWNER or ADMIN)
pub fn can_manage_group(actor_role: &str) -> bool {
    matches!(normalize_role(actor_role), ROLE_OWNER | ROLE_ADMIN)
}

/// May this role mute others? (OWNER / ADMIN / MODERATOR)
pub fn can_moderate(actor_role: &str) -> bool {
    role_rank(actor_role) >= role_rank(ROLE_MODERATOR)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn owner_can_transfer_and_demote() {
        assert!(can_change_role(ROLE_OWNER, ROLE_ADMIN, ROLE_OWNER)); // transfer
        assert!(can_change_role(ROLE_OWNER, ROLE_MEMBER, ROLE_ADMIN));
        assert!(can_change_role(ROLE_OWNER, ROLE_ADMIN, ROLE_MEMBER));
        assert!(!can_change_role(ROLE_OWNER, ROLE_OWNER, ROLE_ADMIN)); // not another owner
    }

    #[test]
    fn admin_limited_to_moderator() {
        assert!(can_change_role(ROLE_ADMIN, ROLE_MEMBER, ROLE_MODERATOR));
        assert!(can_change_role(ROLE_ADMIN, ROLE_MODERATOR, ROLE_MEMBER));
        assert!(!can_change_role(ROLE_ADMIN, ROLE_MEMBER, ROLE_ADMIN)); // can't grant admin
        assert!(!can_change_role(ROLE_ADMIN, ROLE_ADMIN, ROLE_MEMBER)); // can't touch admin
        assert!(!can_change_role(ROLE_ADMIN, ROLE_MEMBER, ROLE_OWNER)); // can't transfer
    }

    #[test]
    fn member_and_moderator_cannot_change_roles() {
        assert!(!can_change_role(ROLE_MEMBER, ROLE_MEMBER, ROLE_MODERATOR));
        assert!(!can_change_role(ROLE_MODERATOR, ROLE_MEMBER, ROLE_MODERATOR));
    }

    #[test]
    fn remove_rules() {
        assert!(can_remove(ROLE_OWNER, ROLE_ADMIN));
        assert!(can_remove(ROLE_ADMIN, ROLE_MODERATOR));
        assert!(can_remove(ROLE_ADMIN, ROLE_MEMBER));
        assert!(!can_remove(ROLE_ADMIN, ROLE_ADMIN));
        assert!(can_remove(ROLE_MODERATOR, ROLE_MEMBER));
        assert!(!can_remove(ROLE_MODERATOR, ROLE_MODERATOR));
        assert!(!can_remove(ROLE_MEMBER, ROLE_MEMBER));
        assert!(!can_remove(ROLE_OWNER, ROLE_OWNER));
    }
}
