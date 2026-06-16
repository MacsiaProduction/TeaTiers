-- Async LLM enrichment tier (plan.md section 6 step 3). On a Wikidata miss, /resolve now creates a
-- minimal stub row and enriches it in the background (decision: async, miss-only), so the request
-- returns immediately and the app polls the detail endpoint for the result.

ALTER TABLE tea
    -- NULL = no async enrichment in flight (Wikidata/curated/user rows). PENDING/DONE/FAILED track an
    -- LLM enrichment job so the client can show a spinner / retry affordance.
    ADD COLUMN enrichment_state TEXT
        CHECK (enrichment_state IN ('PENDING', 'DONE', 'FAILED')),
    -- Short failure reason for FAILED rows (timeout, invalid JSON, guard rejection); never user text.
    ADD COLUMN enrichment_error TEXT;

-- Partial index so a restart-recovery sweep of stuck PENDING rows stays cheap.
CREATE INDEX tea_enrichment_pending_idx ON tea (enrichment_state) WHERE enrichment_state = 'PENDING';
