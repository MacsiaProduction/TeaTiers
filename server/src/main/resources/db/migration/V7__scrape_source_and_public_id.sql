-- Scrape ingestion foundation, part 1 of 3 (decision #136, research run 21 winner opus).
-- The unblock + the stable public id. Two independent fixes the corrected design needs FIRST,
-- before any staging/importer table exists:
--
--   1. tea.source physically rejects a scrape row. The V1 CHECK is ('wikidata','curated','ai','user')
--      (V1__catalog_schema.sql:28); a 'scrape' INSERT fails today. Add 'scrape' (row first created from
--      approved scraped facts) and 'mixed' (canonical row later assembled from several source kinds).
--
--   2. The API returns the raw BIGINT tea.id (TeaSummaryDto.id / TeaDetailDto.id) and the shipped APK
--      v0.1.0 persists it as catalogTeaId. A bulk import + seed regeneration / DB rebuild can RE-NUMBER
--      the identity column -> every installed client's cached id 404s or points at the wrong tea, with no
--      soft-rollback. Introduce a stable public_id UUID that the API exposes instead, a legacy-id map so a
--      client holding the old numeric id still resolves after a rebuild, and a soft-rollback lifecycle
--      (retract / merge, NEVER hard-delete or reuse an id once returned to clients).

-- 1. Allow scrape-origin rows. 'mixed' = a canonical row whose facts come from more than one source kind.
ALTER TABLE tea DROP CONSTRAINT IF EXISTS tea_source_check;
ALTER TABLE tea ADD CONSTRAINT tea_source_check
    CHECK (source IN ('wikidata', 'curated', 'ai', 'user', 'scrape', 'mixed'));

-- 2a. Stable public identity + soft-rollback lifecycle.
-- gen_random_uuid() is core in PostgreSQL 13+ (the box is postgres:16-alpine) -- NO pgcrypto extension and
-- NOT the PostgreSQL-18-only uuidv7(). Existing rows get a random UUID once, here, and keep it forever.
ALTER TABLE tea
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    -- 'active' served normally; 'retracted' = soft-deleted tombstone (id preserved, never reused);
    -- 'merged' = folded into merged_into_public_id (the API redirects holders of the old id to the survivor).
    ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'retracted', 'merged')),
    ADD COLUMN retracted_at TIMESTAMPTZ,
    ADD COLUMN merged_into_public_id UUID;

ALTER TABLE tea ADD CONSTRAINT tea_public_id_uk UNIQUE (public_id);
-- A merge target must be another existing tea, and only a 'merged' row may carry one.
ALTER TABLE tea ADD CONSTRAINT tea_merged_into_fk
    FOREIGN KEY (merged_into_public_id) REFERENCES tea (public_id);
ALTER TABLE tea ADD CONSTRAINT tea_merged_into_chk
    CHECK ((status = 'merged') = (merged_into_public_id IS NOT NULL));

CREATE INDEX tea_status_idx ON tea (status);

-- 2b. Legacy-id map: the durable decoupling of the (volatile across a DB rebuild) BIGINT id from the
-- (stable forever) public_id. Every numeric id EVER returned to a client is recorded here, so even a
-- reseed that renumbers the identity column leaves old clients resolvable. Backfill the current rows;
-- the importer + seeder add a row for each tea they create from now on.
CREATE TABLE tea_legacy_id_map (
    legacy_id  BIGINT PRIMARY KEY,
    public_id  UUID NOT NULL REFERENCES tea (public_id)
);
CREATE INDEX tea_legacy_id_map_public_id_idx ON tea_legacy_id_map (public_id);

INSERT INTO tea_legacy_id_map (legacy_id, public_id)
SELECT id, public_id FROM tea;
