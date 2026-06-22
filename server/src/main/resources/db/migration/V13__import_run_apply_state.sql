-- Turn the flat import-run status into a reviewed/apply-authorized state machine (decision #137-C4 /
-- #139-R3 / 2026-06-22 refresh ING-P0-1). The old enum (running -> succeeded|failed|blocked) let canonical
-- apply run from a never-reviewed 'running' run or a generic 'succeeded' marker that only meant "the runner
-- finished". The new lifecycle gates apply on an explicit 'reviewed'/'applying' state reached ONLY after
-- ingestion is sealed and every decision is resolved:
--
--   created -> preflight_allowed -> ingesting -> awaiting_review -> reviewed -> applying -> applied
--                              \________________ blocked / failed (terminal) ________________/
--
-- No prod data exists yet (prod is pre-V7); the UPDATE below is defensive for any dev/test row.

-- The active-run partial-unique (V12) referenced status = 'running'; drop it before the values change.
DROP INDEX IF EXISTS import_run_one_active_uk;

-- Drop the old CHECK so the migration can rewrite legacy status values (IF EXISTS for upgrade robustness).
ALTER TABLE import_run DROP CONSTRAINT IF EXISTS import_run_status_check;

-- Map any legacy rows: a 'running' run can't be resumed safely under the new machine, and a generic
-- 'succeeded' marker never proved review/apply completeness -- fail both so neither can masquerade as
-- apply-authorized. (No-op in practice: import_run is empty pre-V7 and in fresh test databases.)
UPDATE import_run SET status = 'failed' WHERE status IN ('running', 'succeeded');

-- New default + lifecycle enum.
ALTER TABLE import_run ALTER COLUMN status SET DEFAULT 'preflight_allowed';
ALTER TABLE import_run ADD CONSTRAINT import_run_status_check
    CHECK (status IN (
        'created', 'preflight_allowed', 'ingesting', 'awaiting_review',
        'reviewed', 'applying', 'applied', 'failed', 'blocked'
    ));

-- At most one ACTIVE (non-terminal) run per source. Active = anything not yet applied/failed/blocked.
CREATE UNIQUE INDEX import_run_one_active_uk ON import_run (source_site_id)
    WHERE status NOT IN ('applied', 'failed', 'blocked');
