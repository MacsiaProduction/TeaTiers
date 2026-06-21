# Corrected scrape→catalog foundation: buildable plan

This plan supersedes the unbuildable parts of run 20 decision #131. Source choices (Wikidata + curated seed + demand-driven miss-log) are settled. What follows is the **buildable** identity/import pipeline, schema migrations, stable public ID, facts-only boundary, pinned OSS stack, and PR-sized sequencing.

---

## 1. Identity & matching: cross-script canonical unification

The current `dedup_key` (`name|slug|type`) cannot bridge `Да Хун Пао` / `Da Hong Pao` / `大红袍`. The fix is a **three-tier matching pipeline** that leverages the existing `name_norm` (`lower(f_unaccent(name))`) + `pg_trgm` GIN index, plus a **transliteration pre-processing layer**.

### Transliteration layer

| Script | Direction | Tool |
|--------|-----------|------|
| Chinese (Hanzi) → Pinyin | zh → pinyin | `pypinyin` (MIT) |
| Pinyin → Cyrillic (Palladius) | pinyin → ru | curated mapping table (seed) |
| Cyrillic → Pinyin (reverse Palladius) | ru → pinyin | curated mapping table (seed) |

`pypinyin` cannot bridge Cyrillic, so the Palladius mapping is provided as a **static lookup table** (JSON/CSV) seeded from the standard Palladius system. This table is **curated and versioned** in the repo, not scraped.

For a new candidate name:
1. If Hanzi → generate Pinyin variants via `pypinyin`.
2. If Cyrillic → reverse-lookup to Pinyin via Palladius table.
3. If Pinyin → use as-is.
4. Produce a **normalized search key** = `lower(f_unaccent(candidate))` after transliteration.

### Matching tiers (against existing `tea` rows)

| Tier | Method | Threshold | Action |
|------|--------|-----------|--------|
| **Exact** | `name_norm` equality on any `tea_name` row | 100% | Auto-match → same canonical tea |
| **Trigram** | `pg_trgm` `similarity(name_norm, candidate_norm) >= 0.7` | ≥ 0.7 | Candidate list → queue for human review |
| **Human review** | Manual confirmation in admin UI | N/A | Approve/reject/merge |

The existing `pg_trgm` + `unaccent` extensions are already in place. No new index is required.

### Canonical entity resolution

When a match is found, the incoming source record is **attached** to the existing canonical `tea` row (see per-field provenance below). When no match is found, a **new canonical `tea` row** is created with `verification_status = 'unverified'` (or `'pending'`) — never `'verified'` from scrape.

> **Key principle**: Source-record identity (`source + external_id`) and canonical-entity resolution are separate problems. The former guarantees idempotency; the latter resolves synonyms.

---

## 2. Source-record staging & idempotency

### Immutable raw evidence table

```sql
CREATE TABLE scrape_raw (
    id BIGINT IDENTITY PRIMARY KEY,
    source VARCHAR(32) NOT NULL,          -- e.g., 'wikidata', 'teaspot', 'moychay'
    external_id VARCHAR(256) NOT NULL,    -- canonical URL or Wikidata QID
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    content_hash VARCHAR(64) NOT NULL,    -- SHA-256 of raw response body
    raw_body TEXT,                        -- raw HTML/JSON (short retention, access-controlled)
    parser_version VARCHAR(32) NOT NULL,
    UNIQUE(source, external_id)
);
```

### Parsed source record table

```sql
CREATE TABLE scrape_parsed (
    id BIGINT IDENTITY PRIMARY KEY,
    raw_id BIGINT NOT NULL REFERENCES scrape_raw(id),
    source VARCHAR(32) NOT NULL,
    external_id VARCHAR(256) NOT NULL,
    parsed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- factual fields (nullable)
    name_ru VARCHAR(256),
    name_zh VARCHAR(256),
    name_pinyin VARCHAR(256),
    tea_type VARCHAR(64),
    origin_country VARCHAR(64),
    region VARCHAR(128),
    cultivar VARCHAR(128),
    oxidation VARCHAR(32),
    brand VARCHAR(128),
    -- field-level provenance
    field_provenance JSONB,               -- { "name_ru": {"source_url": "...", "license": "...", "confidence": 0.9} }
    match_decision VARCHAR(32),           -- 'auto_match', 'human_match', 'new', 'rejected'
    matched_tea_id BIGINT REFERENCES tea(id),
    reviewer_id VARCHAR(64),
    review_status VARCHAR(32),            -- 'pending', 'approved', 'rejected'
    import_run_id UUID,
    UNIQUE(source, external_id)
);
```

### Idempotency

- Re-import of the same `(source, external_id)` updates `scrape_raw` (new `fetched_at`, `content_hash`, `raw_body`) and inserts a new `scrape_parsed` row **with a new `parsed_at`**.
- The **latest** `scrape_parsed` row for a given `(source, external_id)` is the active one.
- Matching is re-run on each import; if the match changes, the `matched_tea_id` is updated and a **review flag** is set if the change is non-trivial.

### Flow into canonical `tea`

```
scrape_raw → scrape_parsed → match/canonicalize → tea (upsert) + tea_name (upsert)
```

The canonical upsert is **explicit**: a new `CatalogUpsertService` with a DTO that **does not** accept `source` or `verification_status` defaults. The service:
1. Finds or creates the canonical `tea` row via the matching pipeline.
2. Upserts `tea_name` rows for each locale (ru, zh-Hans, pinyin, en).
3. Updates `tea` factual fields (type, origin, region, cultivar, oxidation, brand) **with per-field provenance** (see below).
4. Never sets `verification_status = 'verified'` — scraped data starts as `'unverified'` or `'pending'`.

---

## 3. Schema changes

### 3.1 `tea.source` CHECK migration

The current `CHECK` (`'wikidata','curated','ai','user'`) rejects `'scrape'`【V1__catalog_schema.sql:28】.

**Migration**:

```sql
ALTER TABLE tea DROP CONSTRAINT tea_source_check;
ALTER TABLE tea ADD CONSTRAINT tea_source_check CHECK (source IN ('wikidata','curated','ai','user','scrape'));
```

But `tea.source` is a **single** value per tea. For a tea assembled from multiple sources, this is insufficient. Therefore:

### 3.2 Per-field provenance

Add a `provenance` JSONB column to `tea`:

```sql
ALTER TABLE tea ADD COLUMN provenance JSONB;
-- Example: {
--   "type": {"source": "wikidata", "source_url": "https://wikidata.org/...", "license": "CC0", "confidence": 0.95},
--   "region": {"source": "teaspot", "source_url": "https://teaspot.com/...", "license": "ToS-granted", "confidence": 0.8},
--   "name_ru": {"source": "curated", "source_url": null, "license": "Apache-2.0", "confidence": 1.0}
-- }
```

This allows **per-field attribution** without losing the original `tea.source` (which can remain the **primary** source or `'scrape'` for the row overall). The `tea.source` CHECK is extended to include `'scrape'`; the `provenance` column carries the detail.

### 3.3 Real UPSERT path

The server currently has **no upsert** — `CatalogSeeder` is insert-or-skip, and Wikidata import is create-or-skip.

**New service**: `CatalogUpsertService.upsertCanonical(dto: CanonicalTeaUpsertDTO)`:

```kotlin
fun upsertCanonical(dto: CanonicalTeaUpsertDTO): Tea {
    // 1. Find existing via matching pipeline (or create new)
    // 2. Update tea row (type, origin, region, cultivar, oxidation, brand, provenance)
    // 3. Upsert tea_name rows (locale, name, is_primary, source, license)
    // 4. Return tea with id
}
```

The DTO **forbids** setting `source` or `verification_status` directly — these are set by the service based on the import context. The service is **idempotent**: re-running with the same DTO updates rather than duplicates.

---

## 4. Stable public ID + rollback

### Problem

The API exposes DB `BIGINT` `tea.id`. A bulk import / reseed can re-number teas → orphaned client `catalogTeaId`s【#131 problem 6】.

### Solution: `public_id` + soft delete

```sql
ALTER TABLE tea ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE tea ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;
CREATE UNIQUE INDEX idx_tea_public_id ON tea(public_id);
```

- `public_id` is **stable forever**. Once a tea is created, its `public_id` never changes.
- The API endpoint `/catalog/detail` accepts **either** `id` (internal, deprecated) or `public_id` (new primary key for clients).
- The Android app v7 `CatalogTeaRef` stores `public_id` instead of `catalogTeaId`.
- **Soft rollback**: `active = false` retracts a tea without reusing its `public_id`. The tea remains in the DB; clients see a 404 or "tea unavailable" message.
- **Never hard-delete** a canonical tea once it has been returned to clients.

### Interaction with v7 `CatalogTeaRef`

The v7 split (`CatalogTeaRef` cached ref vs user `TeaSample`, decision #132) is compatible:
- `CatalogTeaRef` stores `public_id` (UUID) as the stable key.
- The detail fetch uses `public_id` to resolve.
- A soft-deleted tea returns `active=false`; the client can choose to hide or show a tombstone.

---

## 5. Facts-only boundary + ToS/robots gate

### Facts-only

- Scrape **only**: ru/zh/pinyin names, tea type, origin/region, cultivar, oxidation, brand.
- **Never** persist raw vendor prose (descriptions, reviews, photos) server-side.
- If raw prose is temporarily held for enrichment input: **short-retention (≤ 24h)**, access-controlled, never shipped to clients.
- The existing LLM tier generates our own descriptions (decision #22).

### Build-time/CI guard

A CI check (`./gradlew checkNoScrapedProse` or a Python script) scans the repo and the public catalog export for:
- Any `tea_description` rows with `source = 'scrape'` (should be none — descriptions are LLM-generated).
- Any `tea_image` rows with `license NOT IN ('CC0','Apache-2.0','...')` and `source_url` pointing to a scraped vendor.

### ToS gate (hard preflight)

```sql
CREATE TABLE scrape_tos_gate (
    id BIGINT IDENTITY PRIMARY KEY,
    source_domain VARCHAR(256) NOT NULL UNIQUE,
    terms_url VARCHAR(512) NOT NULL,
    terms_checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    owner_sign_off VARCHAR(64) NOT NULL,   -- who approved
    allowed BOOLEAN NOT NULL,
    notes TEXT
);
```

- **Per-source** ToS check: before any scrape run, verify the domain's `robots.txt` and ToS.
- **Per-run** robots re-check: `urllib.robotparser` or `Protego` is called **each run**, not just once.
- If `robots.txt` disallows the user-agent or ToS explicitly forbids scraping, the run **aborts** for that source.

### Miss-log safety

`catalog_miss` logs **only** the miss text (the user's query). It must **never** become a fetch URL or shell argument — this is enforced by the importer accepting only pre-approved source URLs from a configuration file, not from the miss log.

---

## 6. OSS reuse + operating model

### Pinned stack (pilot)

| Library | Version | License | Role |
|---------|---------|---------|------|
| `httpx` | 0.28.1 | BSD-3-Clause | HTTP client (sync/async) |
| `selectolax` | 0.4.9 | MIT | HTML5 parser with CSS selectors |
| `urllib.robotparser` | stdlib | Python PSF | robots.txt parsing (pilot) |
| `pypinyin` | 0.55.0 | MIT | Hanzi → Pinyin |
| `psycopg2` | latest | LGPL | PostgreSQL driver |
| `python-dotenv` | latest | BSD | env config |

**Scrapy + Protego** are **reserved** for full sitemap traversal later:
- `Scrapy` earns its keep when we need **scheduled, multi-domain, recursive crawling** with middleware, pipelines, and extensible spiders.
- `Protego` earns its keep when we need **modern robots.txt extensions** (sitemaps, crawl-delay, etc.) beyond `urllib.robotparser`.

For the pilot (tens of records, demand-driven), `httpx + selectolax + stdlib robotparser` is sufficient and lighter.

### Where scraping runs

- **Local one-off** or **CI/CD pipeline**, **not** on the prod VM, **not** on the API path.
- The importer is a **command-line script** (`./scrape_import.py --source teaspot --query "Да Хун Пао"`) that writes to the dev/staging DB.
- Enrichment (LLM description generation) runs **after** import, also offline.
- The prod catalog is updated via a **controlled deployment** (migration + seed refresh), not by live scraping.

### Coexistence with curated seed + miss-log + v7

- Curated seed (`source = 'curated'`) is the **trusted baseline**.
- Scraped data (`source = 'scrape'`) supplements it, but never overrides curated fields without human review.
- Miss-log drives the **demand queue**: the importer processes the top N miss terms each run.
- v7 work (client-side refactor) is **parallel** — the stable `public_id` is the only dependency.

---

## 7. Recommendation + sequencing

### PR-sized steps (ordered)

| Step | PR | What | Why |
|------|-----|------|-----|
| **1** | `schema/scrape-foundation` | Migrations: `tea.source` CHECK add `'scrape'`, `provenance` JSONB, `public_id` UUID, `active` BOOLEAN, `scrape_raw`, `scrape_parsed`, `scrape_tos_gate`. | Foundation — no code changes yet. |
| **2** | `core/upsert-service` | `CatalogUpsertService` with real upsert (no insert-or-skip). DTO with no `source`/`verification_status` defaults. | Unblocks everything else. |
| **3** | `core/public-id` | API endpoints accept `public_id`; Android v7 `CatalogTeaRef` migrates to `public_id`. | Client stability. |
| **4** | `core/matching` | Transliteration layer (Palladius table + `pypinyin`) + pg_trgm exact/trigram matching + human review queue stub. | Identity resolution. |
| **5** | `scraper/pilot` | `httpx` + `selectolax` + `robotparser` pilot for one source (e.g., teaspot.com). ToS gate + per-run robots check. | Working scraper. |
| **6** | `scraper/import-pipeline` | Full pipeline: `scrape_raw` → `scrape_parsed` → match → `CatalogUpsertService`. Idempotent re-import. | End-to-end. |
| **7** | `ci/guards` | CI check for no scraped prose in public catalog. | Safety. |

### One-paragraph lockable recommendation

**Lock this plan as the corrected foundation for TeaTiers catalog scraping (superseding the unbuildable parts of #131).** The identity pipeline uses a three-tier match (exact → trigram → human review) with a Palladius transliteration table to bridge Cyrillic↔Pinyin↔Hanzi. The import pipeline is built on immutable raw evidence (`scrape_raw`) and parsed records (`scrape_parsed`) keyed on `(source, external_id)`, with a real `CatalogUpsertService` that upserts canonical teas and per-field provenance. The `tea.source` CHECK is extended to `'scrape'`; `public_id` (UUID) + `active` BOOLEAN provide stable client-facing IDs and soft rollback. The pilot uses `httpx 0.28.1` + `selectolax 0.4.9` + stdlib `robotparser`, with Scrapy+Protego reserved for later. Scraping runs offline (local/CI), not on the prod VM; ToS and robots are checked per-source and per-run; facts-only is enforced by schema and CI. Deliver in 7 PR-sized steps, starting with schema migrations and the upsert service.