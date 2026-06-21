-- Scrape ingestion foundation, part 3 of 3 (decision #136, research run 21).
-- Per-field provenance + cross-script identity aliases. These hang off the public tea row but are
-- written only by the importer / the operator review queue.

-- Per-field provenance: a canonical tea can be assembled from several source records, so the single
-- tea.source column cannot say WHERE each fact came from. One row per (tea, field) records the source
-- record / site / url / license / confidence for that field. tea.source stays the row-origin summary
-- ('scrape', 'mixed', ...); field truth lives here.
CREATE TABLE tea_field_provenance (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tea_id                BIGINT NOT NULL REFERENCES tea (id) ON DELETE CASCADE,
    field_name            TEXT NOT NULL,            -- 'type','origin_country','region','cultivar','name:ru', ...
    source_record_id      BIGINT REFERENCES source_record (id),
    source_site_id        BIGINT REFERENCES source_site (id),
    source_url            TEXT,
    license               TEXT,
    confidence            NUMERIC(4, 3) CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    recorded_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tea_field_provenance_tea_idx ON tea_field_provenance (tea_id, field_name);

-- Cross-script identity aliases: the AUTHORITATIVE layer that unifies Да Хун Пао / Da Hong Pao / 大红袍.
-- Curated + human-confirmed rows establish identity (a Tier-0 lookup); library-derived rows (pypinyin /
-- the Palladius bridge / anyascii) stay verified=false and only propose review candidates. alias_norm
-- reuses the server's f_unaccent (defined IMMUTABLE in V4) so it lines up with tea_name.name_norm.
CREATE TABLE tea_identity_alias (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tea_id                BIGINT NOT NULL REFERENCES tea (id) ON DELETE CASCADE,
    locale                TEXT NOT NULL CHECK (locale IN ('en', 'ru', 'zh-Hans', 'pinyin')),
    alias                 TEXT NOT NULL,
    alias_norm            TEXT GENERATED ALWAYS AS (lower(f_unaccent(alias))) STORED,
    romanization_system   TEXT,                     -- pinyin, palladius, wade-giles, none
    origin                TEXT NOT NULL
        CHECK (origin IN ('curated', 'human_confirmed', 'library_derived')),
    -- Library-derived aliases stay unverified until a human approves them; only curated/human-confirmed
    -- aliases are authoritative for identity. Enforced by the importer/matcher, not the DB.
    verified              BOOLEAN NOT NULL DEFAULT FALSE,
    source                TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tea_identity_alias_uk UNIQUE (tea_id, locale, alias)
);
CREATE INDEX tea_identity_alias_tea_idx ON tea_identity_alias (tea_id);
CREATE INDEX tea_identity_alias_norm_trgm ON tea_identity_alias USING gin (alias_norm gin_trgm_ops);
