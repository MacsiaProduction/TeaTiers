-- TeaTiers shared catalog schema (plan.md section 4a, research 01 opus).
-- Locale-aware from the start; per-row provenance is mandatory for CC-BY-SA / ODbL
-- share-alike. Logical enums are stored as text + CHECK (not native PG ENUM) because the
-- flavor vocabulary is explicitly extensible and text maps cleanly to JPA @Enumerated(STRING).

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE tea (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Cross-source identity key; nullable for curated/AI/user rows without a Wikidata match.
    wikidata_qid      TEXT,
    type              TEXT NOT NULL
        CHECK (type IN ('GREEN','WHITE','YELLOW','OOLONG','BLACK','DARK',
                        'PUER','HERBAL','BLENDED','OTHER')),
    origin_country    TEXT,
    region            TEXT,
    cultivar          TEXT,
    oxidation_min     SMALLINT CHECK (oxidation_min BETWEEN 0 AND 100),
    oxidation_max     SMALLINT CHECK (oxidation_max BETWEEN 0 AND 100),
    brand             TEXT,
    -- One optional reference image, stored in object storage (never hotlinked, decision #24).
    image_url         TEXT,
    image_license     TEXT,
    image_source_url  TEXT,
    -- Provenance (mandatory for share-alike licenses).
    source            TEXT NOT NULL
        CHECK (source IN ('wikidata','curated','ai','user')),
    source_url        TEXT,
    license           TEXT,
    retrieved_at      TIMESTAMPTZ,
    -- Enrichment provenance (section 6); a later curation pass promotes rows to verified.
    verification_status TEXT NOT NULL DEFAULT 'unverified'
        CHECK (verification_status IN ('verified','unverified')),
    confidence        REAL CHECK (confidence BETWEEN 0 AND 1),
    enriched_by       TEXT,
    enriched_at       TIMESTAMPTZ,
    -- Normalized dedup key (unaccented-lower primary name + pinyin slug + type), maintained
    -- by the app; backs the section 6 enrich-on-miss upsert so concurrent resolves of the
    -- same new tea cannot create duplicate rows.
    dedup_key         TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tea_oxidation_range CHECK (
        oxidation_min IS NULL OR oxidation_max IS NULL OR oxidation_min <= oxidation_max
    )
);

CREATE UNIQUE INDEX tea_dedup_key_uk ON tea (dedup_key);
CREATE UNIQUE INDEX tea_wikidata_qid_uk ON tea (wikidata_qid) WHERE wikidata_qid IS NOT NULL;
CREATE INDEX tea_type_idx ON tea (type);
CREATE INDEX tea_origin_country_idx ON tea (origin_country);

CREATE TABLE tea_name (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tea_id      BIGINT NOT NULL REFERENCES tea (id) ON DELETE CASCADE,
    locale      TEXT NOT NULL CHECK (locale IN ('en','ru','zh-Hans','pinyin')),
    name        TEXT NOT NULL,
    is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
    source      TEXT,
    license     TEXT,
    -- Multi-row-per-locale so aliases fit; the same alias cannot repeat within a locale.
    CONSTRAINT tea_name_unique UNIQUE (tea_id, locale, name)
);

-- At most one primary name per (tea, locale).
CREATE UNIQUE INDEX tea_name_primary_uk
    ON tea_name (tea_id, locale) WHERE is_primary;
-- Trigram GIN for prefix/substring search across Cyrillic + CJK (section 4a).
CREATE INDEX tea_name_trgm_idx
    ON tea_name USING gin (name gin_trgm_ops);

CREATE TABLE tea_description (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tea_id      BIGINT NOT NULL REFERENCES tea (id) ON DELETE CASCADE,
    locale      TEXT NOT NULL CHECK (locale IN ('ru','en','zh')),
    -- 1-2 sentence taste blurb for the card (own/AI to keep cards clean, decision #22).
    short_text  TEXT,
    -- Behind "read full"; a Wikipedia full extract is CC-BY-SA -> attribute + link.
    full_text   TEXT,
    source      TEXT,
    license     TEXT,
    CONSTRAINT tea_description_locale_unique UNIQUE (tea_id, locale)
);

CREATE TABLE tea_flavor (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tea_id      BIGINT NOT NULL REFERENCES tea (id) ON DELETE CASCADE,
    dimension   TEXT NOT NULL
        CHECK (dimension IN ('BITTERNESS','SWEETNESS','ASTRINGENCY','FRUITINESS','FLORAL',
                             'GRASSY','SPICY','SMOKY','EARTHY_NUTTY','UMAMI','ROASTED')),
    -- 0-5; an absent row means "unknown" for that dimension (0 means none, not unknown).
    intensity   SMALLINT NOT NULL CHECK (intensity BETWEEN 0 AND 5),
    CONSTRAINT tea_flavor_dimension_unique UNIQUE (tea_id, dimension)
);
