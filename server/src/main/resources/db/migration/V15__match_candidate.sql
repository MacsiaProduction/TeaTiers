-- Ranked match candidates for a review decision (decision #141, PR-5 / FND-P1-1). The matcher no longer
-- discards everything but the single best hit: it records the full ranked candidate set (within the winning
-- tier) so the operator can see the runners-up -- essential when the proposal is a multi-owner 'conflict'
-- and the operator must pass an explicit merge target. The CHOSEN/best candidate stays denormalized on
-- match_decision (candidate_tea_id/match_tier/match_score); this table is additive review context only.
CREATE TABLE match_candidate (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    match_decision_id BIGINT NOT NULL REFERENCES match_decision (id) ON DELETE CASCADE,
    tea_id            BIGINT NOT NULL REFERENCES tea (id),
    match_tier        TEXT NOT NULL,
    match_score       NUMERIC(5, 4),
    rank              INT NOT NULL,            -- 0 = best; ranked within the winning tier
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A decision is re-pointed in place across revisions (its candidate rows are deleted + reinserted), so
    -- one rank per decision at any time.
    CONSTRAINT match_candidate_rank_uk UNIQUE (match_decision_id, rank)
);
CREATE INDEX match_candidate_decision_idx ON match_candidate (match_decision_id);
