-- H2 (decision #141 harsh review): make the canonical dedup_key unique only among ACTIVE teas. The matcher
-- is active-only (PR-5), so it proposes create_new for a name that only matches a RETRACTED/MERGED tea; the
-- old status-blind global unique then made that create_new collide on dedup_key -- and merging into a
-- tombstone is forbidden -- a deadlock (can neither create nor merge the identity). A partial unique with a
-- SAME-TABLE predicate is auto-maintained: when a tea goes retracted/merged it drops out of the index and
-- frees its dedup_key for a fresh active identity. Uniqueness among active teas (incl. the whole seed) is
-- unchanged. The collision pre-check in CanonicalUpsertService is made active-scoped to match.
DROP INDEX tea_dedup_key_uk;
CREATE UNIQUE INDEX tea_dedup_key_active_uk ON tea (dedup_key) WHERE status = 'active';
