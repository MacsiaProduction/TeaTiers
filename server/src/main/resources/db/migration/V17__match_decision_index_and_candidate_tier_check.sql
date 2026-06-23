-- Two code-level second-pass review fixes (2026-06-23).

-- SRV-P1-5: ReviewService.applyRun filters approved decisions by import_run_id
-- (MatchDecisionRepository.findByImportRunIdAndDecisionIn), but V8 only indexed source_record_id and the
-- pending-decision partial-unique -- so the apply phase seq-scans match_decision, which grows with every
-- run. Index the FK.
CREATE INDEX match_decision_import_run_idx ON match_decision (import_run_id);

-- SRV-P2-5: match_candidate.match_tier (V15) was a bare TEXT NOT NULL while its twin match_decision.match_tier
-- (V8) carries a CHECK. Add the same CHECK so the two columns can't drift to different vocabularies.
ALTER TABLE match_candidate ADD CONSTRAINT match_candidate_match_tier_check
    CHECK (match_tier IN ('authoritative', 'exact', 'trigram', 'transliteration', 'none'));
