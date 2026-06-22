-- Tighten the import-run state machine + per-run robots evidence (decision #137-C4 / #139-R3). No prod
-- import_run rows exist (the table was added in V8 and never driven), so these changes are safe.

-- Decorative, never-maintained counters: remove them (the review: "decorative audit fields are worse than
-- absent fields"). Reconciliation will compute counts from source_record/match_decision when needed.
ALTER TABLE import_run DROP COLUMN fetched_count;
ALTER TABLE import_run DROP COLUMN parsed_count;
ALTER TABLE import_run DROP COLUMN queued_count;
ALTER TABLE import_run DROP COLUMN rejected_count;

-- Complete the per-run robots snapshot: the exact robots URL and the user agent the decision was made for.
ALTER TABLE import_run ADD COLUMN robots_url        TEXT;
ALTER TABLE import_run ADD COLUMN robots_user_agent TEXT;

-- 'dry_run' was never a real status value (dry-ness is the dry_run BOOLEAN); drop it from the allowed set
-- so the status column is a clean lifecycle enum: running -> succeeded | failed | blocked.
ALTER TABLE import_run DROP CONSTRAINT import_run_status_check;
ALTER TABLE import_run ADD CONSTRAINT import_run_status_check
    CHECK (status IN ('running', 'succeeded', 'failed', 'blocked'));

-- At most ONE non-terminal (running) run per source at a time, so concurrent runs can't race into the same
-- source_record / review state through separate run locks.
CREATE UNIQUE INDEX import_run_one_active_uk ON import_run (source_site_id) WHERE status = 'running';
