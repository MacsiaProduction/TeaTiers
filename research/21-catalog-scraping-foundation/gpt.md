## Executive correction

Run 20’s source policy can stand, but its import foundation must be replaced. The correct model is **not “scrape row → compute dedup_key → upsert tea.”** The buildable model is:

**source observation → parsed source record → normalized candidate → deterministic match proposal → human-approved match/create decision → canonical upsert with field-level provenance → versioned serving snapshot.**

The central correction is to separate **source-record identity** from **canonical tea identity**. Re-import idempotency is keyed by `(source, external_id)` or `(source, canonical_url_norm)`. Canonical identity is resolved by aliases, cross-script keys, source references, field compatibility, and review decisions. The existing `dedup_key` remains a weak internal hint, not a canonical identity proof.

No named commercial source page was verified in this answer; the source choices from #131 are treated as settled inputs. What I verified live here is the build substrate: PostgreSQL `ON CONFLICT`, `pg_trgm`, robots parsing references, legal/database-risk anchors, and current OSS package versions/licenses. PostgreSQL’s `ON CONFLICT DO UPDATE` is the real atomic upsert primitive; it must be implemented explicitly, not assumed to exist in the server. ([PostgreSQL][1])

---

## 1. Identity and matching: how to unify cross-script names

### 1.1 Canonical entity policy

TeaTiers needs two separate identities:

**Source record identity**

A source record is one observed record from one source: a product page, catalog card, Wikidata entity, curated seed row, or future enrichment result.

Its stable key is:

```text
source_id + external_id
```

where `external_id` is preferred if the source has a stable SKU, product id, slug, Wikidata QID, or canonical catalog id.

If the source has no stable id, use:

```text
source_id + canonical_url_norm
```

where `canonical_url_norm` is a normalized URL with tracking parameters removed, host lowercased, path normalized, fragment removed, and redirects resolved only if the redirect stays within the source allowlist.

This key drives **re-import idempotency**.

**Canonical tea identity**

A canonical tea is the public catalog entity served to Android. It can be either:

1. a generic tea style or named tea variety, such as Da Hong Pao, Long Jing, Tie Guan Yin;
2. a branded/vendor product, if brand/vendor materially changes identity;
3. a curated or Wikidata entity;
4. a source-observed unverified entry awaiting future curation.

A source record does not automatically become a canonical tea. It first creates observations and a match proposal.

Important rule: **brand/vendor is not just provenance.** If a page says “Vendor X Da Hong Pao,” that should not automatically overwrite or merge into a generic “Da Hong Pao” entry. The safe first-cut rule is:

```text
If brand/vendor is present and differs from an existing candidate's brand/vendor,
route to review unless the reviewer explicitly attaches it as an alias/observation
to a generic tea style.
```

That prevents collapsing every vendor’s Da Hong Pao into one row while still allowing cross-script unification for generic names.

---

### 1.2 Keep current `name_norm`, add matching keys

The existing server normal form is:

```sql
lower(f_unaccent(name))
```

and `tea_name.name_norm` already has a `pg_trgm` GIN index. Keep that. It is the compatibility base for search and candidate generation.

Add a separate key table, because one generated `name_norm` is not enough for cross-script identity:

```sql
CREATE TABLE tea_name_match_key (
    id                  BIGSERIAL PRIMARY KEY,
    tea_name_id          BIGINT REFERENCES tea_name(id) ON DELETE CASCADE,
    tea_id               BIGINT NOT NULL REFERENCES tea(id),
    locale               TEXT NOT NULL,
    raw_name             TEXT NOT NULL,
    name_norm            TEXT NOT NULL,
    script               TEXT NOT NULL CHECK (script IN ('latin','cyrillic','hanzi','mixed','unknown')),
    token_key            TEXT NOT NULL,
    pinyin_key           TEXT,
    hanzi_key            TEXT,
    ru_palladius_key     TEXT,
    alias_seed_id        BIGINT,
    generated_by         TEXT NOT NULL,
    generated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX tea_name_match_key_name_trgm_idx
    ON tea_name_match_key USING gin (name_norm gin_trgm_ops);

CREATE INDEX tea_name_match_key_pinyin_idx
    ON tea_name_match_key (pinyin_key)
    WHERE pinyin_key IS NOT NULL;

CREATE INDEX tea_name_match_key_hanzi_idx
    ON tea_name_match_key (hanzi_key)
    WHERE hanzi_key IS NOT NULL;

CREATE INDEX tea_name_match_key_ru_palladius_idx
    ON tea_name_match_key (ru_palladius_key)
    WHERE ru_palladius_key IS NOT NULL;
```

`pg_trgm` is appropriate for candidate generation because PostgreSQL’s extension supports similarity functions and GIN/GiST operator classes for fast similar-string search, including `LIKE`, `ILIKE`, regex, equality, and similarity operators. ([PostgreSQL][2])

---

### 1.3 Cross-script canonicalization rules

For every observed name, produce a `NameKeys` object:

```text
raw_name
locale
script
name_norm          = lower(f_unaccent(raw_name))
token_key          = normalized tokens, stopwords removed, punctuation collapsed
pinyin_key         = normalized pinyin if derivable
hanzi_key          = normalized Hanzi if present
ru_palladius_key   = normalized pinyin candidate from curated RU↔pinyin mapping
```

#### Hanzi → pinyin

Use `pypinyin` only for Chinese input. Pin `pypinyin==0.55.0` under MIT. Its documented purpose is converting Chinese characters to pinyin/phonetic forms, with support for simplified/traditional characters, heteronyms, and multiple styles. ([PyPI][3])

For TeaTiers:

```text
大红袍 → da hong pao
龙井   → long jing
铁观音 → tie guan yin
```

Rules:

```text
lowercase
strip tones
strip apostrophes unless syllable boundary is meaningful
collapse whitespace
keep syllable spaces internally for phrase comparison
also store compact form: dahongpao
```

Use a project custom phrase dictionary for tea names and polyphones. Do not trust raw character-by-character output for names with known phrase readings.

#### Pinyin / Latin → pinyin key

For Latin names:

```text
Da Hong Pao
Da-Hong-Pao
dahongpao
da hong pao
```

normalize to:

```text
da hong pao
compact: dahongpao
```

Rules:

```text
lowercase
unaccent
remove tone marks and tone numbers
normalize ü/u:/v consistently
split camel-like and hyphenated forms where possible
remove generic product words: tea, chinese tea, oolong, red robe only as weak aliases, not primary keys
```

#### Cyrillic → pinyin key

Do **not** use a generic Cyrillic transliterator as the primary bridge. A generic Russian transliteration produces Latin approximations of Russian spelling, not Chinese pinyin. For example, a normal Cyrillic transliterator may turn a Chinese Palladius-like Russian spelling into something that looks Latin but is not pinyin.

Use a small, explicit, reviewed `ru_palladius_alias_seed` table plus a reverse mapping helper. The reverse-Palladius problem is real: Russian writers use a traditional Chinese-to-Cyrillic mapping, and reversing it requires rules, syllable boundaries, and human correction; the paper on reversing Palladius specifically describes rule-based reversal plus human selection and a growing dictionary. ([aclanthology.org][4])

For MVP, do **not** try to solve all Russian Chinese transliteration. Build the top-miss alias seed:

```sql
CREATE TABLE tea_alias_seed (
    id              BIGSERIAL PRIMARY KEY,
    canonical_group TEXT NOT NULL,
    locale          TEXT NOT NULL CHECK (locale IN ('en','ru','zh-Hans','pinyin')),
    name            TEXT NOT NULL,
    name_norm       TEXT NOT NULL,
    pinyin_key      TEXT,
    hanzi_key       TEXT,
    source          TEXT NOT NULL CHECK (source IN ('curated','wikidata','reviewed_scrape')),
    confidence      NUMERIC(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    notes           TEXT,
    UNIQUE(locale, name_norm)
);
```

Example group:

```text
canonical_group: da_hong_pao
zh-Hans: 大红袍
pinyin: da hong pao
en: Da Hong Pao
ru: Да Хун Пао
ru variants: Дахунпао, Да Хунпао
```

The first deliverable should include 50–150 curated cross-script aliases from the existing seed, Wikidata rows, and top `catalog_miss` terms. That is much more reliable than pretending a library can derive every Cyrillic tea title.

---

### 1.4 Candidate tiers and thresholds

Use deterministic tiers. The importer should produce a proposed decision, not silently mutate canonical rows.

#### Tier A: exact source reference

Auto-match if a source record already has an approved link:

```text
tea_external_ref(source_record_id) → tea_id
```

Confidence: `1.000`.

Action: apply source-record reimport diff; update observations; do not rematch.

#### Tier B: exact alias/cross-script key

Auto-match only if all are true:

```text
observed pinyin_key equals existing pinyin_key
OR observed hanzi_key equals existing hanzi_key
OR observed name_norm equals existing name_norm
OR observed alias_seed canonical_group equals existing alias_seed canonical_group
```

and:

```text
type is same or one side is null
brand/vendor is same, both null, or reviewer has marked source as generic
origin/region does not conflict
```

Confidence:

```text
0.99 exact Hanzi or alias_seed group
0.97 exact pinyin_key with compatible type
0.95 exact same-locale name_norm with compatible type
```

Action: can auto-propose attach; for the first pilot, still require one-click approval before canonical upsert.

#### Tier C: pg_trgm candidate generation

Generate candidates from existing `tea_name.name_norm` and `tea_name_match_key.name_norm`.

Query shape:

```sql
SET LOCAL pg_trgm.similarity_threshold = 0.25;

SELECT
    tn.tea_id,
    tn.name,
    tn.locale,
    similarity(tn.name_norm, :q_norm) AS sim,
    word_similarity(:q_norm, tn.name_norm) AS word_sim
FROM tea_name tn
WHERE tn.name_norm % :q_norm
ORDER BY GREATEST(
    similarity(tn.name_norm, :q_norm),
    word_similarity(:q_norm, tn.name_norm)
) DESC
LIMIT 30;
```

Then score outside SQL.

Proposed score formula:

```text
name_score =
  max(
    similarity(name_norm),
    word_similarity(query_norm, candidate_norm),
    exact_pinyin ? 0.97 : 0,
    exact_hanzi ? 0.99 : 0,
    exact_alias_group ? 0.99 : 0
  )

type_score:
  +0.05 same type
  +0.02 one side missing
  reject if strong mismatch, e.g. green vs puerh, unless reviewer override

brand_score:
  +0.04 same brand/vendor
   0.00 both missing
  review if one side generic and one side branded
  reject/review if conflicting brands

origin_region_score:
  +0.03 same origin_country
  +0.03 same region
  review if high-confidence conflict

final_score =
  min(1.0, name_score + type_score + brand_score + origin_region_score)
```

Thresholds:

```text
Auto-attach candidate:
  final_score >= 0.95
  and no brand/type/origin conflict
  and at least one exact cross-script key or same-locale exact name_norm

High-confidence review:
  0.88 <= final_score < 0.95
  or exact pinyin_key but scalar conflict
  or Cyrillic reverse-Palladius result without alias_seed confirmation

Normal review:
  0.72 <= final_score < 0.88
  or multiple candidates within 0.05 of each other

Propose new canonical:
  no candidate >= 0.72
  and no exact alias/source ref
  and source record passes ToS/robots/facts-only gates

Reject candidate:
  final_score < 0.72
  or hard type mismatch
  or conflicting brand/vendor for branded product identity
```

These thresholds are deliberately conservative. They should be calibrated with a fixed fixture set before any bulk run.

Minimum fixture set:

```text
Да Хун Пао / Da Hong Pao / 大红袍
Те Гуань Инь / Tie Guan Yin / 铁观音
Лун Цзин / Long Jing / 龙井
Пуэр Шу / Shu Pu-erh / 熟普洱
Пуэр Шэн / Sheng Pu-erh / 生普洱
Молочный улун / Milk Oolong / 奶香乌龙
```

---

## 2. Source-record staging and idempotency pipeline

### 2.1 Pipeline states

The corrected pipeline should be:

```text
1. import_run starts
2. source gate checks ToS approval snapshot
3. robots.txt fetched and checked for every run
4. pages fetched from allowlisted domains only
5. evidence record written: hashes + factual extracted fields, not prose
6. source_record_version inserted
7. source_record current row upserted by source identity
8. normalized candidate generated
9. deterministic matching proposes attach/create/ignore/conflict
10. reviewer approves or rejects
11. approved decision creates canonical upsert command
12. canonical tea, names, scalar fields, provenance updated transactionally
13. clean snapshot exported
```

Re-importing the same record is idempotent because `(source_id, external_id)` or `(source_id, canonical_url_norm)` is unique. If the parsed facts hash is unchanged, the run is a no-op except for run audit. If the facts changed, a new version is recorded and only changed observations enter the review/conflict queue.

---

### 2.2 Tables

#### Source registry

```sql
CREATE TABLE catalog_source (
    id                    BIGSERIAL PRIMARY KEY,
    code                  TEXT NOT NULL UNIQUE,
    display_name          TEXT NOT NULL,
    source_kind           TEXT NOT NULL CHECK (source_kind IN ('wikidata','curated','vendor','community','ai')),
    base_url              TEXT,
    allowed_hostnames     TEXT[] NOT NULL DEFAULT '{}',
    terms_url             TEXT,
    terms_hash_sha256     TEXT,
    terms_checked_at      TIMESTAMPTZ,
    terms_decision        TEXT NOT NULL DEFAULT 'blocked'
        CHECK (terms_decision IN ('blocked','approved_facts_only','approved_manual_only')),
    terms_signed_off_by   TEXT,
    terms_signed_off_at   TIMESTAMPTZ,
    robots_url            TEXT,
    enabled               BOOLEAN NOT NULL DEFAULT FALSE,
    notes                 TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

A source cannot run unless:

```text
enabled = true
terms_decision in ('approved_facts_only','approved_manual_only')
terms_checked_at is recent enough, e.g. <= 30 days for pilot
terms_hash_sha256 matches the latest fetched terms hash, or owner re-signs
```

#### Import run

```sql
CREATE TABLE catalog_import_run (
    id                    UUID PRIMARY KEY,
    source_id             BIGINT NOT NULL REFERENCES catalog_source(id),
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at           TIMESTAMPTZ,
    status                TEXT NOT NULL CHECK (status IN ('running','succeeded','failed','blocked','dry_run')),
    initiated_by          TEXT NOT NULL,
    tool_name             TEXT NOT NULL,
    tool_version          TEXT NOT NULL,
    git_sha               TEXT,
    parser_version        TEXT NOT NULL,
    user_agent            TEXT NOT NULL,
    seed_terms            TEXT[] NOT NULL DEFAULT '{}',
    dry_run               BOOLEAN NOT NULL DEFAULT TRUE,

    terms_url             TEXT,
    terms_hash_sha256     TEXT,
    terms_checked_at      TIMESTAMPTZ,
    terms_decision        TEXT,

    robots_url            TEXT,
    robots_fetched_at     TIMESTAMPTZ,
    robots_http_status    INT,
    robots_hash_sha256    TEXT,
    robots_decision       TEXT CHECK (robots_decision IN ('allow','disallow','fail_closed','not_checked')),

    fetched_count         INT NOT NULL DEFAULT 0,
    parsed_count          INT NOT NULL DEFAULT 0,
    proposed_count        INT NOT NULL DEFAULT 0,
    approved_count        INT NOT NULL DEFAULT 0,
    notes                 TEXT
);
```

Robots is a compliance gate, not a legal authorization substitute. RFC 9309 explicitly specifies a robots protocol for crawlers, and also states that these rules are not an access authorization mechanism. ([datatracker.ietf.org][5])

#### Immutable source evidence

Do not persist raw HTML or vendor prose in production. The “raw evidence” table stores the immutable factual extraction envelope, hashes, and source metadata.

```sql
CREATE TABLE source_evidence (
    id                    UUID PRIMARY KEY,
    import_run_id         UUID NOT NULL REFERENCES catalog_import_run(id),
    source_id             BIGINT NOT NULL REFERENCES catalog_source(id),
    external_id           TEXT,
    canonical_url         TEXT NOT NULL,
    canonical_url_norm    TEXT NOT NULL,
    retrieved_at          TIMESTAMPTZ NOT NULL,
    http_status           INT NOT NULL,
    content_type          TEXT,
    content_hash_sha256   TEXT,
    parser_version        TEXT NOT NULL,

    raw_facts_json        JSONB NOT NULL,
    raw_facts_hash_sha256 TEXT NOT NULL,

    -- No raw_html, no description body, no reviews, no photos.
    contains_prose        BOOLEAN NOT NULL DEFAULT FALSE,
    contains_image_url    BOOLEAN NOT NULL DEFAULT FALSE,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK (contains_prose = FALSE)
);
```

`raw_facts_json` may contain:

```json
{
  "names": [
    {"locale": "ru", "value": "...", "role": "title"},
    {"locale": "zh-Hans", "value": "...", "role": "observed_alias"},
    {"locale": "pinyin", "value": "...", "role": "derived"}
  ],
  "type": "oolong",
  "origin_country": "China",
  "region": "Wuyi",
  "cultivar": null,
  "oxidation": null,
  "brand": "...",
  "vendor": "...",
  "source_product_id": "...",
  "canonical_url": "..."
}
```

It must not contain:

```text
description
review
body
html
image_url
photo_url
marketing_text
brewing_story
taste_notes_verbatim
```

For debugging, a local one-off run may keep raw HTML on the operator workstation with short retention, but it must never be loaded into the production catalog DB or public API.

#### Source record current state and versions

```sql
CREATE TABLE source_record (
    id                    UUID PRIMARY KEY,
    source_id             BIGINT NOT NULL REFERENCES catalog_source(id),
    external_id           TEXT,
    canonical_url         TEXT NOT NULL,
    canonical_url_norm    TEXT NOT NULL,
    current_evidence_id   UUID NOT NULL REFERENCES source_evidence(id),
    first_seen_at         TIMESTAMPTZ NOT NULL,
    last_seen_at          TIMESTAMPTZ NOT NULL,
    latest_facts_hash     TEXT NOT NULL,
    status                TEXT NOT NULL CHECK (status IN ('active','gone','blocked','ignored','retracted')),
    UNIQUE (source_id, external_id),
    UNIQUE (source_id, canonical_url_norm)
);
```

If `external_id` is null, the unique constraint needs a partial unique index instead:

```sql
CREATE UNIQUE INDEX source_record_source_external_uq
    ON source_record(source_id, external_id)
    WHERE external_id IS NOT NULL;

CREATE UNIQUE INDEX source_record_source_url_uq
    ON source_record(source_id, canonical_url_norm)
    WHERE external_id IS NULL;
```

```sql
CREATE TABLE source_record_version (
    id                    UUID PRIMARY KEY,
    source_record_id      UUID NOT NULL REFERENCES source_record(id),
    evidence_id           UUID NOT NULL REFERENCES source_evidence(id),
    import_run_id         UUID NOT NULL REFERENCES catalog_import_run(id),
    observed_at           TIMESTAMPTZ NOT NULL,
    facts_hash_sha256     TEXT NOT NULL,
    diff_json             JSONB,
    parser_version        TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_record_id, facts_hash_sha256)
);
```

#### Candidate and match decision

```sql
CREATE TABLE catalog_match_candidate (
    id                    UUID PRIMARY KEY,
    source_record_id      UUID NOT NULL REFERENCES source_record(id),
    import_run_id         UUID NOT NULL REFERENCES catalog_import_run(id),
    candidate_tea_id      BIGINT REFERENCES tea(id),
    decision_kind         TEXT NOT NULL CHECK (decision_kind IN ('attach','create_new','ignore','conflict')),
    score                 NUMERIC(5,4) NOT NULL,
    score_breakdown       JSONB NOT NULL,
    matcher_version       TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'proposed'
        CHECK (status IN ('proposed','approved','rejected','superseded')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

```sql
CREATE TABLE catalog_match_decision (
    id                    UUID PRIMARY KEY,
    source_record_id      UUID NOT NULL REFERENCES source_record(id),
    candidate_id          UUID REFERENCES catalog_match_candidate(id),
    target_tea_id         BIGINT REFERENCES tea(id),
    decision_kind         TEXT NOT NULL CHECK (decision_kind IN ('attach','create_new','ignore','split','merge','retract')),
    status                TEXT NOT NULL CHECK (status IN ('approved','rejected','revoked')),
    reviewer              TEXT NOT NULL,
    reason                TEXT NOT NULL,
    decided_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at            TIMESTAMPTZ,
    revoked_by            TEXT,
    revoked_reason        TEXT
);
```

#### Field observations and canonical assignments

```sql
CREATE TABLE tea_field_observation (
    id                    UUID PRIMARY KEY,
    source_record_id      UUID NOT NULL REFERENCES source_record(id),
    source_evidence_id    UUID NOT NULL REFERENCES source_evidence(id),
    field_path            TEXT NOT NULL,
    value_json            JSONB NOT NULL,
    value_norm            TEXT,
    confidence            NUMERIC(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    source_url            TEXT NOT NULL,
    license               TEXT NOT NULL DEFAULT 'facts-only',
    observed_at           TIMESTAMPTZ NOT NULL,
    active                BOOLEAN NOT NULL DEFAULT TRUE
);
```

```sql
CREATE TABLE tea_field_assignment (
    id                    UUID PRIMARY KEY,
    tea_id                BIGINT NOT NULL REFERENCES tea(id),
    field_path            TEXT NOT NULL,
    value_json            JSONB NOT NULL,
    selected_observation_id UUID REFERENCES tea_field_observation(id),
    selected_by           TEXT NOT NULL,
    selected_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    status                TEXT NOT NULL CHECK (status IN ('active','superseded','retracted')),
    UNIQUE (tea_id, field_path, status) DEFERRABLE INITIALLY IMMEDIATE
);
```

Because partial unique constraints with `status='active'` are often simpler than deferrable uniqueness here, a practical version is:

```sql
CREATE UNIQUE INDEX tea_field_assignment_active_uq
    ON tea_field_assignment(tea_id, field_path)
    WHERE status = 'active';
```

#### Source refs

```sql
CREATE TABLE tea_external_ref (
    id                    UUID PRIMARY KEY,
    tea_id                BIGINT NOT NULL REFERENCES tea(id),
    source_record_id      UUID NOT NULL REFERENCES source_record(id),
    source_id             BIGINT NOT NULL REFERENCES catalog_source(id),
    external_id           TEXT,
    canonical_url_norm    TEXT NOT NULL,
    relationship          TEXT NOT NULL CHECK (relationship IN ('same_as','product_page','alias_source','rejected_match')),
    approved_decision_id  UUID REFERENCES catalog_match_decision(id),
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_record_id, relationship)
);
```

---

## 3. Schema changes and real upsert path

### 3.1 `tea.source` migration

The existing `tea.source CHECK ('wikidata','curated','ai','user')` physically rejects scrape-created rows. That must be fixed before any importer lands.

Recommended migration:

```sql
ALTER TABLE tea DROP CONSTRAINT IF EXISTS tea_source_check;

ALTER TABLE tea ADD CONSTRAINT tea_source_check
CHECK (source IN ('wikidata','curated','ai','user','scrape','mixed'));
```

But treat `tea.source` as a **legacy row-origin summary**, not real provenance.

Rules:

```text
source = 'wikidata'   only if row is purely Wikidata-derived
source = 'curated'    curated seed/manual row
source = 'ai'         AI-created row, still unverified
source = 'user'       if ever used for user-origin local/public submission
source = 'scrape'     row first created from approved scraped facts
source = 'mixed'      canonical row assembled from multiple source types
```

The real source of truth becomes `tea_field_observation`, `tea_field_assignment`, `tea_external_ref`, and `tea_name.source_record_id`.

Extend `tea_name`:

```sql
ALTER TABLE tea_name
    ADD COLUMN source_record_id UUID REFERENCES source_record(id),
    ADD COLUMN source_url TEXT,
    ADD COLUMN confidence NUMERIC(4,3) CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1);

CREATE UNIQUE INDEX tea_name_tea_locale_name_norm_uq
    ON tea_name(tea_id, locale, name_norm);
```

Then all alias insertion uses:

```sql
INSERT INTO tea_name (...)
VALUES (...)
ON CONFLICT (tea_id, locale, name_norm)
DO UPDATE SET
    is_primary = tea_name.is_primary OR EXCLUDED.is_primary,
    source = CASE
        WHEN tea_name.source = EXCLUDED.source THEN tea_name.source
        ELSE 'mixed'
    END,
    source_record_id = COALESCE(tea_name.source_record_id, EXCLUDED.source_record_id),
    source_url = COALESCE(tea_name.source_url, EXCLUDED.source_url),
    confidence = GREATEST(COALESCE(tea_name.confidence, 0), COALESCE(EXCLUDED.confidence, 0));
```

PostgreSQL requires an explicit conflict target for `ON CONFLICT DO UPDATE`; it can atomically insert or update the conflicting row, which is exactly what the importer needs for aliases and source records. ([PostgreSQL][1])

---

### 3.2 Canonical upsert service

Add a new server-side service, not a seeder modification:

```text
CatalogImportService
CatalogCanonicalUpsertService
CatalogMatchService
CatalogReviewService
```

The upsert DTO must not have hidden defaults for `source` or `verification_status`.

Shape:

```kotlin
data class CatalogCanonicalUpsertCommand(
    val decisionId: UUID,
    val targetTeaId: Long?,          // required for attach; null only for approved create_new
    val sourceRecordId: UUID,
    val rowSource: TeaSource,        // SCRAPE or MIXED, explicit
    val verificationStatus: VerificationStatus, // explicit, must not be VERIFIED
    val lifecycleStatus: LifecycleStatus,
    val scalarAssignments: List<FieldAssignmentCommand>,
    val names: List<NameUpsertCommand>,
    val externalRefs: List<ExternalRefCommand>
)
```

Validation:

```text
Reject if verificationStatus == VERIFIED.
Reject if decision status is not approved.
Reject if sourceRecord has failed ToS/robots gate.
Reject if source evidence contains prose or image URL.
Reject if rowSource is absent.
Reject if targetTeaId is null and decision_kind != create_new.
Reject if targetTeaId is present and decision_kind != attach/merge/split correction.
Reject if scalar conflict exists and no reviewer-approved field assignment exists.
```

Canonical scalar merge policy:

```text
Names:
  additive; never remove old aliases on reimport;
  primary-name changes require review.

type/origin_country/region/cultivar/oxidation/brand:
  if canonical field is null and observation is high-confidence, may fill after approved decision;
  if non-null and same normalized value, keep;
  if non-null and conflicting, create conflict; do not overwrite automatically.

description:
  never import vendor prose;
  existing LLM tier may generate our own blurb after facts are approved.

flavor:
  not scraped from vendor prose in first cut;
  existing LLM tier can generate profile from structured facts only.

image:
  no scraped images in first cut.
```

---

### 3.3 Canonical row upsert

Canonical `tea` upsert should be by **stable id**, not `dedup_key`.

For attach:

```sql
UPDATE tea
SET
    type = COALESCE(:approved_type, type),
    origin_country = COALESCE(:approved_origin_country, origin_country),
    region = COALESCE(:approved_region, region),
    cultivar = COALESCE(:approved_cultivar, cultivar),
    oxidation = COALESCE(:approved_oxidation, oxidation),
    brand = COALESCE(:approved_brand, brand),
    source = CASE
        WHEN source = :row_source THEN source
        ELSE 'mixed'
    END,
    verification_status = CASE
        WHEN verification_status = 'verified' THEN verification_status
        ELSE :verification_status
    END,
    updated_at = now()
WHERE id = :target_tea_id
  AND lifecycle_status IN ('active','source_observed');
```

For approved create:

```sql
INSERT INTO tea (
    public_id,
    type,
    origin_country,
    region,
    cultivar,
    oxidation,
    brand,
    source,
    verification_status,
    dedup_key,
    lifecycle_status,
    created_at,
    updated_at
)
VALUES (
    :public_id,
    :type,
    :origin_country,
    :region,
    :cultivar,
    :oxidation,
    :brand,
    :row_source,
    :verification_status,
    :dedup_key,
    'source_observed',
    now(),
    now()
)
RETURNING id;
```

`dedup_key` can still be populated for backward compatibility and search hints, but not used as canonical identity.

---

## 4. Stable public id and rollback

### 4.1 The hard requirement

The app already persists `catalogTeaId` as the server’s `BIGINT tea.id`. Therefore `tea.id` is already a public id, whether or not the schema intended it. From now on:

```text
Never hard-delete a tea row once returned by the API.
Never reuse a tea.id.
Never reseed in a way that changes existing ids.
Never use TRUNCATE + reload for production catalog.
Never let a bad merge destroy the old id.
```

Add a new stable public id for v7 and future clients, but preserve numeric ids forever.

```sql
ALTER TABLE tea
    ADD COLUMN public_id UUID;

-- Backfill in application migration or SQL migration.
-- Then:
ALTER TABLE tea
    ALTER COLUMN public_id SET NOT NULL;

CREATE UNIQUE INDEX tea_public_id_uq ON tea(public_id);

ALTER TABLE tea
    ADD COLUMN lifecycle_status TEXT NOT NULL DEFAULT 'active'
        CHECK (lifecycle_status IN ('active','source_observed','merged','retracted','superseded')),
    ADD COLUMN merged_into_tea_id BIGINT REFERENCES tea(id),
    ADD COLUMN retracted_at TIMESTAMPTZ,
    ADD COLUMN retraction_reason TEXT,
    ADD COLUMN first_public_at TIMESTAMPTZ,
    ADD COLUMN last_public_at TIMESTAMPTZ;
```

For DB rebuilds:

```text
The clean snapshot must include tea.id and tea.public_id.
Restore must insert explicit ids from the snapshot.
After restore, reset the identity sequence to max(id)+1.
```

If the identity column is `GENERATED ALWAYS`, PostgreSQL supports overriding system-generated identity values with `OVERRIDING SYSTEM VALUE`, which is relevant for deterministic snapshot restore. ([PostgreSQL][1])

---

### 4.2 Alias and redirect table

```sql
CREATE TABLE tea_id_alias (
    id                    BIGSERIAL PRIMARY KEY,
    old_tea_id             BIGINT NOT NULL REFERENCES tea(id),
    new_tea_id             BIGINT NOT NULL REFERENCES tea(id),
    reason                 TEXT NOT NULL CHECK (reason IN ('merge','duplicate','canonical_rename','rollback')),
    decision_id            UUID REFERENCES catalog_match_decision(id),
    active                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at             TIMESTAMPTZ,
    UNIQUE (old_tea_id, active)
);
```

API behavior:

```text
GET /api/v1/catalog/teas/{id}
  active: return normal detail
  merged: return 200 with canonical detail plus supersededByCatalogTeaId,
          or 308-like API status if client supports it
  retracted: return tombstone with old id, display name, status=retracted,
             and no misleading replacement unless merged_into_tea_id exists
  missing: only for ids never issued
```

For current Android clients, safest is to return a compact tombstone instead of 404 for previously public ids.

---

### 4.3 v7 `CatalogTeaRef` interaction

v7 should store:

```kotlin
data class CatalogTeaRef(
    val legacyCatalogTeaId: Long?,
    val catalogPublicId: String?,
    val cachedPrimaryName: String,
    val cachedType: TeaType?,
    val cachedBrand: String?,
    val cachedOrigin: String?,
    val lastResolvedStatus: CatalogRefStatus,
    val lastResolvedAt: Instant?
)
```

Rules:

```text
Existing installed clients:
  continue resolving legacy BIGINT id.

New clients:
  store public_id as primary;
  keep legacy id for backward API compatibility.

Local user TeaSample:
  must remain usable if catalog row is retracted or unreachable;
  use cached name/type as local display fallback.
```

Soft rollback is then easy:

```text
Bad merge:
  old tea row was lifecycle_status='merged', not deleted.
  rollback sets old row active again, revokes tea_id_alias, and creates a rollback decision.

Bad field import:
  old field assignment was superseded, not deleted.
  rollback reactivates previous assignment and supersedes the bad one.

Bad source:
  source_record.status='retracted';
  canonical row may be retracted or field assignments revoked;
  public ids remain reserved.
```

---

## 5. Facts-only boundary and gates

### 5.1 Public catalog boundary

Allowed scraped facts:

```text
names: ru, en, zh-Hans, pinyin
tea type
origin country
region
cultivar
oxidation
brand/vendor
source product id
canonical URL as provenance
retrieval timestamp
parser/source metadata
```

Not allowed in public catalog:

```text
vendor descriptions
marketing prose
reviews
ratings
brewing instructions copied from vendor
taste notes copied from vendor
photos
unlicensed image URLs
raw HTML
screenshots
large extracted text blocks
```

The existing LLM enrichment tier should generate TeaTiers’ own short blurb from structured facts, not from stored vendor prose. If a future grounded enrichment uses vendor prose, keep it outside the public catalog with short retention, access control, and no API exposure.

Add CI checks:

```sql
-- No scraped descriptions.
SELECT COUNT(*)
FROM tea_description
WHERE source = 'scrape';

-- No obvious vendor prose in descriptions.
SELECT COUNT(*)
FROM tea_description
WHERE source_url IS NOT NULL
  AND source NOT IN ('wikidata','curated','ai');

-- No scraped images in first cut.
SELECT COUNT(*)
FROM tea_image
WHERE source_url IS NOT NULL
  AND license NOT IN ('cc0','cc-by','public-domain','own');

-- No evidence rows marked as prose.
SELECT COUNT(*)
FROM source_evidence
WHERE contains_prose = TRUE OR contains_image_url = TRUE;
```

Also add JSON-schema validation for `raw_facts_json`:

```text
reject keys matching description|review|body|html|image|photo|text
reject string values > 240 chars except URL fields
reject arrays of long strings
reject HTML-looking values
```

---

### 5.2 Legal posture

The practical legal posture is: **facts-only does not mean zero risk**, but it keeps the public APK clean. The main risk is database extraction/substantial-part reuse and site terms, not copyright in isolated tea facts.

For Russia-first RU-hosted scraping, Russian database-maker rights are operative: Article 1334 grants the maker of a database the exclusive right to extract materials and reuse them, and treats transfer of all or a substantial part of database contents as extraction. ([КонсультантПлюс][6])

The ToS gate is not optional. The Ryanair v PR Aviation line is useful as a warning: even where a database is not protected by EU copyright/sui-generis database rights, contractual limitations may still restrict use depending on applicable national law. ([eur-lex.europa.eu][7])

Therefore the build rule is:

```text
No source enters catalog_source.enabled=true until an owner signs off:
  terms_url
  terms_checked_at
  terms_hash_sha256
  allowed use: facts-only/manual-only/blocked
  notes on disallowed crawling, scraping, republication, API use
```

---

### 5.3 Robots and request gate

Per run:

```text
fetch robots.txt
store status/hash/fetched_at
evaluate every candidate URL with the configured user-agent
respect disallow
respect crawl-delay/request-rate where available
fail closed on 401/403/5xx/network ambiguity for pilot
block redirects outside allowed hostnames
```

Python’s standard `urllib.robotparser.RobotFileParser` answers whether a user agent can fetch a URL from a site’s robots rules and references RFC 9309 in current docs. ([Python documentation][8])

For the pilot, use `urllib.robotparser`. For full traversal later, use Protego with Scrapy because Protego exposes `can_fetch`, `crawl_delay`, `request_rate`, sitemap, and modern-convention support. ([PyPI][9])

---

### 5.4 Miss-log safety

`catalog_miss` text must never become:

```text
a fetch URL
a shell argument
a subprocess command
an unvalidated path
a SQL fragment
```

Use it only as a search term:

```text
miss term → normalized query string → allowlisted source search URL builder → encoded query param
```

Safety rules:

```text
allowlisted source hosts only
http/https only
no localhost/private/reserved IPs after DNS resolution
no redirects outside allowlist
max URL length
max pages per term
no shell execution
no browser automation in first cut
rate limits per source
```

---

## 6. OSS reuse and operating model

### 6.1 Pilot stack

Use a local Python CLI for the first tens of records:

```text
Python: 3.12 or 3.13
httpx==0.28.1
selectolax==0.4.10
pypinyin==0.55.0
stdlib urllib.robotparser
```

`httpx 0.28.1` is BSD-3-Clause and supports sync/async HTTP, HTTP/1.1 and HTTP/2, strict timeouts, sessions, cookies, proxies, and decompression. ([PyPI][10])

`selectolax 0.4.10` is MIT, fast, CSS-selector based, and uses Modest/Lexbor engines; note the bundled-engine license caveat: selectolax is MIT, Lexbor is Apache-2.0, and Modest is LGPL-2.1 according to the project metadata. ([PyPI][11])

`pypinyin 0.55.0` is MIT and should be limited to Hanzi-derived pinyin keys, not treated as a Cyrillic bridge. ([PyPI][3])

This is the right pilot stack because:

```text
tens of records
known source choices
manual review
no sitemap traversal
no JS rendering
no persistent crawler service
no production VM load
easy deterministic NDJSON bundles
```

### 6.2 Deferred full-crawl stack

Reserve:

```text
Scrapy==2.16.0
Protego==0.6.1
```

Scrapy 2.16.0 is BSD-3-Clause, Python >=3.10, and is explicitly a high-level web crawling/scraping framework for structured extraction. ([PyPI][12])

Protego 0.6.1 is BSD-3-Clause and useful once sitemap traversal, request-rate parsing, wildcard/precedence behavior, and crawler-scale robots logic matter. ([PyPI][9])

Scrapy earns its keep only when you need:

```text
sitemap traversal
broad pagination
retry/backoff middleware
download throttling
per-domain concurrency
persistent crawl state
structured item pipelines
large source-specific spiders
```

Do not introduce Scrapy for the first 20–100 records.

---

### 6.3 Where scraping runs

Scraping should run:

```text
on the operator workstation or a disposable local/dev machine
as an explicit one-off command
with checked-in parser code and lockfile
with dry-run as default
producing an import bundle
```

It should not run:

```text
inside the public Android app
inside the API request path
as a daemon on the production Yandex VM
as an automatic background job
from user-submitted URLs
```

Suggested artifact:

```text
import-bundles/
  2026-06-21-source-code-run-id/
    manifest.json
    source_records.ndjson
    observations.ndjson
    match_candidates.ndjson
    proposed_decisions.ndjson
    parser_version.txt
    robots_snapshot.txt
    terms_snapshot_hash.txt
```

Only approved structured facts enter the production DB.

---

## 7. Clean snapshot and rollback

Add:

```sql
CREATE TABLE catalog_snapshot (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            TEXT NOT NULL,
    schema_version         TEXT NOT NULL,
    app_catalog_version    TEXT NOT NULL,
    source_run_ids         UUID[] NOT NULL,
    git_sha               TEXT,
    artifact_sha256        TEXT NOT NULL,
    tea_count              INT NOT NULL,
    active_tea_count       INT NOT NULL,
    retracted_tea_count    INT NOT NULL,
    notes                 TEXT
);
```

Snapshot contents:

```text
tea rows with explicit id and public_id
tea_name rows
active scalar field assignments
public descriptions generated by TeaTiers only
flavor profiles
external refs
lifecycle statuses
id aliases
```

Snapshot excludes:

```text
raw HTML
vendor prose
review queue internals not needed for serving
local debug captures
```

Rollback is a **new diff**, not a database rewind:

```text
retract source record
supersede bad field assignment
reactivate previous assignment
set row lifecycle_status
revoke id alias
emit new snapshot
```

This avoids renumbering and keeps installed clients safe.

---

## 8. Smallest correct first deliverable

The first deliverable is the **foundation**, not the crawler.

### PR 1 — Stable identity and lifecycle migration

Add:

```text
tea.public_id
tea.lifecycle_status
tea.merged_into_tea_id
tea.retracted_at
tea.retraction_reason
tea_id_alias
source CHECK expansion to include scrape/mixed
```

Add tests:

```text
existing ids remain unchanged
retracted rows still resolve
merged rows preserve old ids
snapshot restore preserves ids
source='scrape' no longer violates CHECK
```

### PR 2 — Source/provenance schema

Add:

```text
catalog_source
catalog_import_run
source_evidence
source_record
source_record_version
tea_external_ref
tea_field_observation
tea_field_assignment
tea_name.source_record_id/source_url/confidence
```

Add DB constraints:

```text
source_record unique source identity
no evidence contains prose
active field assignment unique per tea+field
```

### PR 3 — Canonical upsert service

Build `CatalogCanonicalUpsertService`.

No scraper yet.

Inputs are test fixtures and hand-authored import bundles.

Validation:

```text
no verified status from importer
no implicit source default
no overwrite of conflicting scalar fields without approved decision
names are additive and idempotent
source refs are idempotent
```

### PR 4 — Cross-script identity library

Build:

```text
NameNormalizer
PinyinKeyGenerator
AliasSeedLoader
CandidateMatcher
MatchScore
```

Fixture tests:

```text
Да Хун Пао / Da Hong Pao / 大红袍
Tie Guan Yin variants
Long Jing variants
Shu vs Sheng Pu-erh non-merge
brand conflict review
type conflict reject
```

Use current `name_norm` as the baseline and create `tea_name_match_key`.

### PR 5 — Review queue

Build a CLI or admin-only endpoint:

```text
list candidates
show source facts
show candidate tea
approve attach
approve create_new
reject
mark conflict
revoke decision
```

No public UI needed.

### PR 6 — Gates and CI guard

Build:

```text
catalog_source terms signoff fields
per-run robots snapshot
allowlisted host URL builder
miss-log sanitizer
facts JSON schema
CI checks for no scraped prose/images/descriptions
```

The scraper still does not matter until this passes.

### PR 7 — Local pilot scraper

Only now add:

```text
httpx + selectolax local CLI
one settled source parser
top catalog_miss terms
dry-run default
NDJSON bundle output
no raw prose persistence
manual review required
```

Target: 20–50 records.

### PR 8 — Clean snapshot and restore drill

Build:

```text
snapshot export
restore into empty DB preserving tea.id/public_id
sequence reset
API smoke test for old ids
rollback drill for bad merge
```

### PR 9 — v7 API compatibility

Add to API:

```text
publicId
lifecycleStatus
supersededByCatalogTeaId
supersededByPublicId
retracted tombstone behavior
```

Then align Android `CatalogTeaRef`.

---

## What to defer

Defer all of this until the foundation works:

```text
Scrapy spiders
sitemap traversal
bulk import
automatic create without review
automatic verified status
photo/image import
vendor prose retention
browser automation
running scraper on prod VM
source-wide crawling
LLM grounded on stored vendor descriptions
fully automatic Cyrillic→pinyin matching
```

---

## Lockable recommendation

Supersede the unbuildable parts of decision #131 with this: TeaTiers will implement a **facts-only, review-gated source-observation pipeline** before any crawler. Re-import idempotency is keyed by source record identity, not `dedup_key`; canonical identity is resolved through source refs, cross-script alias keys, pg_trgm candidates, and approved match decisions. The schema will add stable public identity, lifecycle/tombstone rollback, source-record tables, field-level provenance, and an explicit canonical upsert service that cannot mark scraped rows verified or silently overwrite conflicts. Scraping remains a local one-off pilot using pinned `httpx + selectolax + urllib.robotparser + pypinyin`; Scrapy/Protego are deferred until real traversal is needed. The public catalog will contain structured facts only, never vendor prose, reviews, or unlicensed images, with ToS owner sign-off and per-run robots checks as hard preflights.

[1]: https://www.postgresql.org/docs/current/sql-insert.html "PostgreSQL: Documentation: 18: INSERT"
[2]: https://www.postgresql.org/docs/current/pgtrgm.html "PostgreSQL: Documentation: 18: F.35. pg_trgm — support for similarity of text using trigram matching"
[3]: https://pypi.org/project/pypinyin/ "pypinyin · PyPI"
[4]: https://aclanthology.org/2012.amta-government.13.pdf "Reversing the Palladius mapping of Chinese names in Russian text"
[5]: https://datatracker.ietf.org/doc/html/rfc9309 "
            
                RFC 9309 - Robots Exclusion Protocol
            
        "
[6]: https://www.consultant.ru/document/cons_doc_LAW_64629/c8b26358cbae2a98f328bd8cb495a08f7e11caff/ "ГК РФ Статья 1334. Исключительное право изготовителя базы данных \ КонсультантПлюс"
[7]: https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=celex%3A62014CJ0030 "EUR-Lex - 62014CJ0030 - EN - EUR-Lex"
[8]: https://docs.python.org/3/library/urllib.robotparser.html "urllib.robotparser — Parser for robots.txt — Python 3.14.6 documentation"
[9]: https://pypi.org/project/Protego/ "Protego · PyPI"
[10]: https://pypi.org/project/httpx/ "httpx · PyPI"
[11]: https://pypi.org/project/selectolax/ "selectolax · PyPI"
[12]: https://pypi.org/project/Scrapy/ "Scrapy · PyPI"
