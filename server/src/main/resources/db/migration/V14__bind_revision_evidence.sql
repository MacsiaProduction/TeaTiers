-- Make the immutable fetch-evidence chain a hard invariant (decision #141, PR-2; refresh ING-P0-2). From
-- now on every observation revision is bound to a raw_evidence row at ingest, and canonical apply fails
-- closed without it. source_record_revision is empty before this deploy (prod is pre-V7; the V10 backfill
-- inserted 0 rows on an empty source_record), so the work below is a no-op on real data -- but it is
-- self-healing rather than asserting emptiness, so a long-lived dev/staging DB that ran the pre-PR-2
-- importer (which left raw_evidence_id NULL) upgrades cleanly instead of aborting on SET NOT NULL.

-- 1) Backfill a synthetic envelope for any pre-PR-2 revision that has none. The unique placeholder hash
--    lets us bind it back in step 2; http_status stays NULL (allowed by the 2xx CHECK added below).
INSERT INTO raw_evidence (import_run_id, source_site_id, canonical_url, retrieved_at, content_hash, parser_version)
SELECT r.import_run_id, sr.source_site_id, sr.canonical_url, r.retrieved_at, 'backfill-rev-' || r.id, r.parser_version
FROM source_record_revision r
JOIN source_record sr ON sr.id = r.source_record_id
WHERE r.raw_evidence_id IS NULL;

UPDATE source_record_revision r
SET raw_evidence_id = e.id
FROM raw_evidence e
WHERE r.raw_evidence_id IS NULL AND e.content_hash = 'backfill-rev-' || r.id;

-- 2) Now every revision carries its fetch envelope (the FK to raw_evidence already exists from V10).
ALTER TABLE source_record_revision ALTER COLUMN raw_evidence_id SET NOT NULL;

-- 3) A fetch envelope must record a SUCCESSFUL fetch: when an http status is present it must be 2xx. (The
--    importer also enforces this at ingest; this is the DB backstop. Backfilled rows have NULL status.)
ALTER TABLE raw_evidence ADD CONSTRAINT raw_evidence_http_2xx
    CHECK (http_status IS NULL OR (http_status BETWEEN 200 AND 299));
