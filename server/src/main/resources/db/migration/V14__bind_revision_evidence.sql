-- Make the immutable fetch-evidence chain a hard invariant (decision #141, PR-2; refresh ING-P0-2). From
-- now on every observation revision is bound to a raw_evidence row at ingest, and canonical apply fails
-- closed without it. source_record_revision is empty before this deploy (prod is pre-V7; the V10 backfill
-- inserted 0 rows on an empty source_record), so SET NOT NULL is a safe no-op on real data.

-- Every revision must carry its fetch envelope (the FK to raw_evidence already exists from V10).
ALTER TABLE source_record_revision ALTER COLUMN raw_evidence_id SET NOT NULL;

-- A fetch envelope must record a SUCCESSFUL fetch: when an http status is present it must be 2xx. (The
-- importer also enforces this at ingest; this is the DB backstop.)
ALTER TABLE raw_evidence ADD CONSTRAINT raw_evidence_http_2xx
    CHECK (http_status IS NULL OR (http_status BETWEEN 200 AND 299));
