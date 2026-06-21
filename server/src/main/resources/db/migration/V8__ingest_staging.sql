-- Scrape ingestion foundation, part 2 of 3 (decision #136, research run 21).
-- The ingest staging layer, kept in its own namespace, separate from the public tea* tables. Nothing
-- here is ever served to a client. The pipeline is:
--
--   source_site (registry + ToS sign-off + robots gate)
--     -> import_run (one local one-off run; per-run robots snapshot)
--       -> raw_evidence (immutable fetch envelope; hashes only -- NO vendor prose by default)
--         -> source_record (parsed FACTS; the idempotency key (source_site, external_id/canonical_url))
--           -> normalized_candidate (cross-script normalized projection for matching)
--             -> match_decision (the human review/conflict queue; nothing auto-merges in the pilot)
--
-- The two keys #131 conflated are now separate: source-record identity drives re-import idempotency;
-- canonical-entity identity is a human-confirmed match_decision; per-field provenance is V9.

-- Per-source registry + the hard preflight gates (ToS owner sign-off, robots). A run may not start for a
-- site unless terms_signed_off_at is set AND active = true; the scraper re-checks robots EVERY run.
CREATE TABLE source_site (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  TEXT NOT NULL UNIQUE,             -- stable slug, e.g. 'artoftea'
    display_name          TEXT NOT NULL,
    base_url              TEXT NOT NULL,
    allowed_hosts         TEXT[] NOT NULL DEFAULT '{}',     -- fetch allowlist (no SSRF off this set)
    license_default       TEXT,
    -- ToS gate: the enforceable lever (Ryanair v PR Aviation C-30/14). Owner sign-off is blocking.
    terms_url             TEXT,
    terms_checked_at      TIMESTAMPTZ,
    terms_signed_off_by   TEXT,
    terms_signed_off_at   TIMESTAMPTZ,
    -- Snapshot of the last robots check (each run also records its own fresh snapshot on import_run).
    robots_url            TEXT,
    robots_checked_at     TIMESTAMPTZ,
    active                BOOLEAN NOT NULL DEFAULT FALSE,
    notes                 TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One local one-off import run. Carries the per-run robots snapshot (NOT a one-time check) and counts.
CREATE TABLE import_run (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_site_id        BIGINT NOT NULL REFERENCES source_site (id),
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at           TIMESTAMPTZ,
    status                TEXT NOT NULL DEFAULT 'running'
        CHECK (status IN ('running', 'succeeded', 'failed', 'blocked', 'dry_run')),
    operator              TEXT NOT NULL,            -- who ran it (a person, not a daemon)
    tool_version          TEXT NOT NULL,
    parser_version        TEXT NOT NULL,
    dry_run               BOOLEAN NOT NULL DEFAULT TRUE,
    robots_fetched_at     TIMESTAMPTZ,
    robots_http_status    INT,
    robots_hash           TEXT,
    robots_decision       TEXT CHECK (robots_decision IN ('allow', 'disallow', 'fail_closed', 'not_checked')),
    fetched_count         INT NOT NULL DEFAULT 0,
    parsed_count          INT NOT NULL DEFAULT 0,
    queued_count          INT NOT NULL DEFAULT 0,
    rejected_count        INT NOT NULL DEFAULT 0,
    notes                 TEXT
);
CREATE INDEX import_run_site_idx ON import_run (source_site_id);

-- Immutable fetch envelope. Append-only. content_hash is the sha256 of the fetched body; raw_blob_ref is
-- an object-store key used ONLY if a raw body is retained at all (access-controlled, short retention,
-- never shipped, never on the API path) -- NULL in the facts-only first cut.
CREATE TABLE raw_evidence (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_run_id         BIGINT NOT NULL REFERENCES import_run (id),
    source_site_id        BIGINT NOT NULL REFERENCES source_site (id),
    canonical_url         TEXT NOT NULL,
    http_status           INT,
    retrieved_at          TIMESTAMPTZ NOT NULL,
    content_hash          TEXT NOT NULL,           -- sha256 hex (64 chars)
    content_type          TEXT,
    parser_version        TEXT NOT NULL,
    raw_blob_ref          TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX raw_evidence_run_idx ON raw_evidence (import_run_id);

-- The parsed source record: the unit of idempotency. Re-fetching the same URL/external id upserts THIS
-- row (ON CONFLICT on the unique indexes below) -- NOT the canonical tea. raw_facts is FACTS ONLY
-- (ru/zh/pinyin/en names, type, origin/region, cultivar, oxidation, brand, vendor) -- never vendor prose.
CREATE TABLE source_record (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_site_id        BIGINT NOT NULL REFERENCES source_site (id),
    external_id           TEXT,                    -- vendor SKU / slug / Wikidata QID, when present
    canonical_url         TEXT NOT NULL,           -- normalized product URL
    import_run_id         BIGINT NOT NULL REFERENCES import_run (id),
    raw_evidence_id       BIGINT REFERENCES raw_evidence (id),
    content_hash          TEXT NOT NULL,           -- sha256 hex of the parsed facts (drives reparse detection)
    parser_version        TEXT NOT NULL,
    retrieved_at          TIMESTAMPTZ NOT NULL,
    raw_facts             JSONB NOT NULL,
    status                TEXT NOT NULL DEFAULT 'parsed'
        CHECK (status IN ('parsed', 'reparse_pending', 'linked', 'rejected')),
    first_seen_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Canonical link; filled ONLY after an approved match_decision. NULL = not yet resolved.
    tea_id                BIGINT REFERENCES tea (id)
);
-- Idempotency keys. A site usually has a stable external_id; if not, the canonical_url is the key.
CREATE UNIQUE INDEX source_record_url_uk ON source_record (source_site_id, canonical_url);
CREATE UNIQUE INDEX source_record_extid_uk ON source_record (source_site_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX source_record_tea_idx ON source_record (tea_id);
CREATE INDEX source_record_status_idx ON source_record (status) WHERE status IN ('parsed', 'reparse_pending');

-- Cross-script normalized projection of a source_record, ready to match against the catalog. The *_norm
-- columns MUST be built with the server convention lower(f_unaccent(...)) so they line up with
-- tea_name.name_norm + the existing pg_trgm GIN (the shared-normalizer invariant, decision #136).
CREATE TABLE normalized_candidate (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_record_id      BIGINT NOT NULL REFERENCES source_record (id) ON DELETE CASCADE,
    name_ru               TEXT,
    name_en               TEXT,
    name_zh               TEXT,
    name_pinyin           TEXT,
    name_ru_norm          TEXT,
    name_pinyin_norm      TEXT,
    -- candidate cross-script keys: pypinyin(Hanzi)->pinyin, and a curated Palladius<->pinyin bridge.
    pinyin_from_hanzi     TEXT,
    palladius_bridge      TEXT,
    type                  TEXT,
    origin_country        TEXT,
    region                TEXT,
    cultivar              TEXT,
    brand                 TEXT,
    vendor                TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX normalized_candidate_record_idx ON normalized_candidate (source_record_id);

-- The human review/conflict queue. In the pilot NOTHING auto-merges or auto-creates: every cross-script /
-- fuzzy candidate lands here as 'pending' and an operator decides. match_tier records WHY it was proposed.
CREATE TABLE match_decision (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_record_id      BIGINT NOT NULL REFERENCES source_record (id) ON DELETE CASCADE,
    normalized_candidate_id BIGINT REFERENCES normalized_candidate (id),
    candidate_tea_id      BIGINT REFERENCES tea (id),   -- proposed match; NULL for a create_new proposal
    match_tier            TEXT NOT NULL
        CHECK (match_tier IN ('authoritative', 'exact', 'trigram', 'transliteration', 'none')),
    match_score           NUMERIC(5, 4),
    proposed_kind         TEXT NOT NULL
        CHECK (proposed_kind IN ('attach', 'create_new', 'conflict', 'ignore')),
    decision              TEXT NOT NULL DEFAULT 'pending'
        CHECK (decision IN ('pending', 'approved_merge', 'approved_new', 'rejected')),
    reviewer              TEXT,
    decided_at            TIMESTAMPTZ,
    import_run_id         BIGINT REFERENCES import_run (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX match_decision_record_idx ON match_decision (source_record_id);
CREATE INDEX match_decision_pending_idx ON match_decision (decision) WHERE decision = 'pending';
