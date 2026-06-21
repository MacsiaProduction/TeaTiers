-- Scrape ingestion foundation, part 4 (decision #137 C5/C6). Closes FND-P0-3/SCR-P0-4 (corrections stop
-- after the first approval), SCR-P0-5 (source-identity not reconciled), and FND-P0-4/SCR-P0-6,7 (merge
-- writes no value-bearing provenance; approved aliases never promoted). Also closes part of FND-P1-3
-- (normalized-candidate cardinality + one-pending-per-record).

-- C5: immutable observation revisions. Each distinct content_hash of a source_record's parsed facts is an
-- append-only revision. A match decision binds to the revision it reviewed, so a corrected re-import
-- re-enters review (instead of sitting at 'reparse_pending' forever) and a stale approval -- content
-- changed since the operator looked -- is rejected rather than publishing the old value.
CREATE TABLE source_record_revision (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_record_id  BIGINT NOT NULL REFERENCES source_record (id) ON DELETE CASCADE,
    content_hash      TEXT NOT NULL,           -- sha256 hex of the parsed facts
    parser_version    TEXT NOT NULL,
    retrieved_at      TIMESTAMPTZ NOT NULL,
    raw_facts         JSONB NOT NULL,
    import_run_id     BIGINT NOT NULL REFERENCES import_run (id),
    raw_evidence_id   BIGINT REFERENCES raw_evidence (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Re-importing identical facts maps to the SAME revision (idempotent); a real change is a new revision.
    CONSTRAINT source_record_revision_uk UNIQUE (source_record_id, content_hash)
);
CREATE INDEX source_record_revision_record_idx ON source_record_revision (source_record_id);

-- The current (latest) revision pointer on the stable source identity.
ALTER TABLE source_record ADD COLUMN current_revision_id BIGINT REFERENCES source_record_revision (id);

-- Backfill: one revision per existing record from its current facts, set as the current revision.
INSERT INTO source_record_revision
    (source_record_id, content_hash, parser_version, retrieved_at, raw_facts, import_run_id, raw_evidence_id, created_at)
SELECT id, content_hash, parser_version, retrieved_at, raw_facts, import_run_id, raw_evidence_id, first_seen_at
FROM source_record;
UPDATE source_record sr
SET current_revision_id = rev.id
FROM source_record_revision rev
WHERE rev.source_record_id = sr.id;

-- C5.6 (SCR-P0-5): canonical-URL history. A slug rename updates source_record.canonical_url and records
-- the old URL here, so identity is reconciled with an audit trail instead of silently losing the old URL.
CREATE TABLE source_record_url_history (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_record_id  BIGINT NOT NULL REFERENCES source_record (id) ON DELETE CASCADE,
    canonical_url     TEXT NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX source_record_url_history_record_idx ON source_record_url_history (source_record_id);

-- Bind each match decision to the exact revision it reviewed (C5.2/C5.4).
ALTER TABLE match_decision ADD COLUMN source_record_revision_id BIGINT REFERENCES source_record_revision (id);
UPDATE match_decision md
SET source_record_revision_id = sr.current_revision_id
FROM source_record sr
WHERE sr.id = md.source_record_id;

-- FND-P1-3: the repository assumes one normalized_candidate per source_record -- make it a constraint.
CREATE UNIQUE INDEX normalized_candidate_record_uk ON normalized_candidate (source_record_id);
-- At most one OPEN (pending) decision per source record at a time (a later revision re-points it in place).
CREATE UNIQUE INDEX match_decision_one_pending_uk ON match_decision (source_record_id) WHERE decision = 'pending';

-- C6 (FND-P0-4/SCR-P0-6,7): turn per-field provenance into a value-bearing CLAIM model. Each claim records
-- the claimed value, the source revision + decision + reviewer that produced it, and whether it is the
-- SELECTED value for that scalar field. A conflict (existing value wins) is kept as a non-selected claim
-- rather than silently dropped. Names are multi-valued provenance, excluded from the single-selection rule.
ALTER TABLE tea_field_provenance
    ADD COLUMN claimed_value             TEXT,
    ADD COLUMN source_record_revision_id BIGINT REFERENCES source_record_revision (id),
    ADD COLUMN match_decision_id         BIGINT REFERENCES match_decision (id),
    ADD COLUMN reviewer                  TEXT,
    ADD COLUMN selected                  BOOLEAN NOT NULL DEFAULT TRUE;
-- Exactly one SELECTED scalar claim per (tea, field); 'name:*' rows are multi-valued and excluded.
CREATE UNIQUE INDEX tea_field_claim_selected_uk ON tea_field_provenance (tea_id, field_name)
    WHERE selected AND field_name NOT LIKE 'name:%';
