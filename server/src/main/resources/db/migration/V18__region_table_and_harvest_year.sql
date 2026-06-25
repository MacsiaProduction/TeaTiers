-- "Proper" region (decision #138) + harvest year (artoftea.ru exposes «регион сбора» and «год сбора»).
-- Additive + nullable, so it applies cleanly to live prod with no backfill: existing free-text tea.region is
-- KEPT as the as-authored value, and region_id is the canonical resolution, populated later by curation / the
-- scraper review flow. The raw-text -> region matcher is deliberately NOT built here (SRV-P1-2): scraped
-- region keeps landing as free text until an operator/QID step resolves it. harvest_year is a new scalar fact
-- that flows through the claim model exactly like cultivar.

-- Canonical region: a Wikidata-QID identity + localized names (the catalog is ru/en/zh from the start). No
-- parent hierarchy yet (YAGNI) -- add a parent_region_id when a Wuyi>Fujian rollup is actually needed.
CREATE TABLE region (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wikidata_qid  TEXT,
    country_code  TEXT,                  -- ISO-3166 alpha-2 of the containing country
    name_ru       TEXT,
    name_en       TEXT,
    name_zh       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One row per Wikidata entity when known; QID-less regions are allowed but not deduped by the DB.
CREATE UNIQUE INDEX region_wikidata_qid_uk ON region (wikidata_qid) WHERE wikidata_qid IS NOT NULL;

ALTER TABLE tea
    -- Canonical region (nullable until resolved); tea.region keeps the raw/as-authored text meanwhile.
    ADD COLUMN region_id    BIGINT REFERENCES region (id),
    -- Gregorian harvest year of the picked leaf; a fact, recorded as a claim like origin/cultivar.
    ADD COLUMN harvest_year SMALLINT CHECK (harvest_year BETWEEN 1900 AND 2100);

CREATE INDEX tea_region_id_idx ON tea (region_id);
