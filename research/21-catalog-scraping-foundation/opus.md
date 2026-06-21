# TeaTiers Run 21 — Buildable Scrape→Catalog Foundation (Superseding the Unbuildable Parts of #131)

## TL;DR
- Build the **foundation, not the crawler**: a source-record staging layer with correct idempotency, a real upsert path, per-field provenance, a stable public UUID with soft-rollback, and a hybrid human-confirmed cross-script identity model — all of which #131 lacks or specifies incorrectly.
- The single most important correction: **identity, idempotency, and provenance are three different keys** — re-import must be idempotent on the *source record* `(source_site, canonical_url/external_id)`, canonical-entity resolution must be human-confirmed (fuzzy/transliteration never auto-merges in the pilot), and a canonical tea assembled from multiple sources must carry **per-field** provenance, not one per-table `source` string.
- The pilot is tiny (tens of demand-driven records, Art of Tea first, local operator execution): **httpx 0.28.1 + selectolax 0.4.10 + Protego 0.6.1 + pypinyin 0.55.0** is the right pinned stack; reserve Scrapy 2.16.0 for later sitemap traversal; publish facts only and let the existing #22 LLM tier write our own prose.

## Key Findings
1. **`tea.source` physically rejects `'scrape'`.** The CHECK constraint in `V1__catalog_schema.sql:28` is `source IN ('wikidata','curated','ai','user')`. #131's guard targeted `tea_description.source` (free-text, unconstrained) — the wrong column. A `'scrape'` row cannot be inserted today. Fix with an explicit CHECK migration plus a per-field provenance table.
2. **No upsert exists.** CatalogSeeder is insert-or-skip; Wikidata import and the enrichment stub are create-or-skip. An importer "upserting on dedup_key" silently no-ops on existing rows. We must build a *new* explicit importer + DTO with a real UPDATE path.
3. **`dedup_key` is not cross-script canonical.** It is `name|slug|TYPE` for one chosen primary name/pinyin/type (`DedupKeys.kt`); it does not prove that "Да Хун Пао", "Da Hong Pao" and "大红袍" are one entity. pypinyin bridges 大红袍→"da hong pao" but cannot transliterate the Cyrillic "Да Хун Пао".
4. **Idempotency vs identity are conflated in #131.** They are separate keys and need separate machinery.
5. **The public BIGINT `tea.id` is unstable across reseed/rebuild.** The APK v0.1.0 caches it as `catalogTeaId`. A bulk import + seed regeneration re-numbers rows → cached clients 404 or point at the wrong tea. No soft-rollback exists.
6. **OSS versions verified live on PyPI as of 2026-06-21** (see Section F). No fabricated pins; `anyascii` exists (ISC, candidate-generator only, maintenance "Inactive").
7. **Legal lever confirmed:** for a RU-on-RU scrape, RU ГК РФ Art. 1334 (database maker's right) is operative; Ryanair v PR Aviation (C-30/14) is the principle that ToS can bind even where copyright/database right does not. Facts are not copyrightable. The goal is a clean public APK, not zero risk.
8. **Live recon of artoftea.ru** (the RU site; NOT artoftea.com): it is an OpenCart store with clean `/category/subcategory/slug` product URLs (e.g. `/puer/sheng-puer/da-syue-shan-bolshaya-snezhnaya-gora-2007`) and Open Graph product facts in-page; a single legal page at `/privacy` doubles as "Пользовательское соглашение". Its `robots.txt` and `/privacy` text could **not** be fetched in recon and must be verified manually at run time — which is exactly why the design treats source/robots/ToS as a per-run preflight, never a one-time check. (JSON-LD/microdata presence on product pages is unconfirmed; Open Graph is confirmed.)

---

## (A) Cross-Script Identity + Tiered Matching Pipeline

### The problem, concretely
Three surface forms must resolve to one canonical tea:
- `大红袍` (locale `zh-Hans`)
- `Da Hong Pao` / `Dà Hóng Páo` (locale `pinyin`)
- `Да Хун Пао` (locale `ru`, Palladius Cyrillization)

`name_norm = lower(f_unaccent(name))` collapses diacritics within a script (so `Dà Hóng Páo`→`da hong pao`) but does nothing across scripts: it cannot connect Hanzi or Cyrillic to Latin pinyin. The existing `pg_trgm` GIN index on `tea_name.name_norm` only helps *within* a script's character set.

### Hybrid model (per locked rules)
**Authoritative layer (establishes identity):**
- Curated, entity-linked aliases (operator-seeded) — e.g. a seed row linking `Да Хун Пао`/`Da Hong Pao`/`大红袍` to one canonical tea.
- Human-confirmed match decisions from the review queue.

**Candidate layer (proposes only, never establishes identity):**
- `pypinyin` 0.55.0: Hanzi→pinyin (`大红袍`→`da hong pao`). Bridges `zh-Hans`→`pinyin` candidate keys only.
- A curated **Palladius↔pinyin syllable table** (operator-maintained reference seed) to propose links between Cyrillic names and pinyin/Hanzi entities. pypinyin cannot do this; there is no maintained Python Palladius library (only Raku and PHP implementations exist publicly), so this bridge is a curated lookup, not a library.
- `anyascii` (generic transliteration) may generate a coarse ASCII candidate key for the queue but **must never establish identity**.
- All library-generated aliases remain `verified = false` (derived/unverified) until a human approves them.

### Tiers and how they relate
- **Tier 0 — Authoritative.** Look up the candidate's normalized names against `tea_identity_alias` (curated + human-confirmed). A hit here *is* identity. Direct merge into the existing canonical tea.
- **Tier 1 — Exact normalized (same locale).** Match `normalized_candidate.<locale>_norm` against `tea_name.name_norm` using `lower(f_unaccent(...))` for the same locale. A strong-confidence candidate — but **enqueued for review, not auto-merged**, during the pilot.
- **Tier 2 — Trigram candidate.** Use the existing `pg_trgm` GIN index (`similarity()` / `%`) to surface near-matches within a script. → review queue only.
- **Tier 3 — Transliteration candidate.** Generate pinyin from Hanzi (pypinyin) and Palladius↔pinyin bridge candidates from Cyrillic; match against pinyin-locale aliases. anyascii produces a last-resort coarse key. → review queue only.

**Thresholds are NOT fixed here.** Per the PostgreSQL 18 official docs (F.35 pg_trgm), `pg_trgm.similarity_threshold` "Sets the current similarity threshold that is used by the % operator. The threshold must be between 0 and 1 (default is 0.3)." (The docs also define `word_similarity_threshold` default 0.6 and `strict_word_similarity_threshold` default 0.5.) These defaults are *starting points only*; the operative cutoffs are calibrated later on a labeled corpus. During the pilot, **no fuzzy or transliteration match ever auto-merges** — every Tier 1–3 result lands in the operator review queue.

### Curated alias seed + human-confirmed queue
- Seed `tea_identity_alias` with the small set of high-traffic teas (Da Hong Pao, Tie Guan Yin, Dian Hong, etc.) across `ru`/`pinyin`/`zh-Hans`, marked `origin='curated'`, `verified=true`.
- Every unresolved scrape candidate produces a `match_decision` row (`status='pending'`). The operator approves `merge` (into an existing tea), `new` (create a canonical tea), or `reject`. Approved decisions become authoritative and feed back into Tier 0.

---

## (B) Source-Record Staging Schema + Idempotency

A six-stage pipeline, each stage a named table. New schema namespace `ingest_*` (kept separate from the public `tea*` tables).

**1. `source_site` — source registry + ToS/robots gate (preflight anchor)**
- `id BIGINT PK`
- `name TEXT`, `base_url TEXT`
- `terms_url TEXT`, `terms_checked_at TIMESTAMPTZ`, `terms_owner_signoff BOOLEAN NOT NULL DEFAULT false`
- `robots_last_checked_at TIMESTAMPTZ`
- `license_default TEXT`
- `active BOOLEAN NOT NULL DEFAULT false`
- A site cannot be imported unless `terms_owner_signoff = true` AND `terms_checked_at` is recent AND `active = true`.

**2. `import_run` — per-run provenance + robots snapshot**
- `id BIGINT PK`, `source_site_id FK`
- `started_at`, `finished_at`, `operator TEXT`, `tool_version TEXT`, `status TEXT`
- `robots_snapshot TEXT`, `robots_checked_at TIMESTAMPTZ` (fetched fresh **every run**, not once)
- counts (`fetched`, `parsed`, `queued`, `rejected`)

**3. `raw_evidence` — immutable raw record**
- `id BIGINT PK`, `import_run_id FK`, `source_site_id FK`
- `canonical_url TEXT`, `http_status INT`, `retrieved_at TIMESTAMPTZ`
- `content_hash CHAR(64)` (sha256), `content_type TEXT`
- `raw_blob_ref TEXT` (object-store key; access-controlled, short retention; **never shipped**, never on the API path)
- `parser_version TEXT`
- Append-only; never updated.

**4. `source_record` — parsed source record (idempotency key)**
- `id BIGINT PK`, `source_site_id FK`, `external_id TEXT NULL`, `canonical_url TEXT NOT NULL`
- `content_hash CHAR(64)`, `parser_version TEXT`, `retrieved_at TIMESTAMPTZ`
- `import_run_id FK`, `raw_evidence_id FK`
- `raw_facts JSONB` (parsed factual fields only: ru/zh/pinyin names, type, origin/region, cultivar, vendor)
- `status TEXT` (`parsed`,`reparse_pending`,`linked`)
- **Idempotency indexes:**
  - `CREATE UNIQUE INDEX uq_source_record_url ON source_record (source_site_id, canonical_url);`
  - `CREATE UNIQUE INDEX uq_source_record_extid ON source_record (source_site_id, external_id) WHERE external_id IS NOT NULL;`

**5. `normalized_candidate` — cross-script normalized form**
- `id BIGINT PK`, `source_record_id FK`
- `name_ru`, `name_zh`, `name_pinyin`, `name_en`
- `name_ru_norm`, `name_pinyin_norm` (generated `lower(f_unaccent(...))` to match server convention)
- `type`, `origin_country`, `region`, `cultivar`, `vendor`
- `pinyin_from_hanzi TEXT` (pypinyin-derived candidate key), `palladius_bridge TEXT` (curated-table candidate key)

**6. `match_decision` — review/conflict queue (operator-side)**
- `id BIGINT PK`, `normalized_candidate_id FK`, `candidate_tea_id BIGINT NULL` (proposed match)
- `match_tier TEXT` (`authoritative`/`exact`/`trigram`/`transliteration`)
- `match_score NUMERIC NULL`
- `decision TEXT` (`pending`/`approved_merge`/`approved_new`/`rejected`)
- `reviewer TEXT`, `decided_at TIMESTAMPTZ`, `import_run_id FK`

**7. Versioned clean snapshot.** A `catalog_snapshot` (snapshot id, created_at, git_sha, public_id list + hashes) produced from the approved canonical rows — the artifact the APK build consumes.

### Idempotency, corrections, and aliases flow
- **Re-import is keyed by the source record, not the canonical key.** Re-fetching the same URL upserts the same `source_record` row via `ON CONFLICT (source_site_id, canonical_url)`. If `content_hash` is unchanged → no downstream work. If changed → `status='reparse_pending'` re-queues review. This is what #131's "upsert on dedup_key" got wrong (#131 keyed on the canonical entity, so corrections/new aliases silently no-op'd).
- **Corrections** flow when an approved `match_decision` with `decision='approved_merge'` triggers a real UPDATE of the existing canonical tea + insertion of new `tea_identity_alias` rows + new `tea_field_provenance` rows. No silent skip.

---

## (C) Concrete Schema Migrations — Upsert Path + DTO, `tea.source`, Per-Field Provenance

Flyway versioned migrations (`V<n>__...sql`). Use the next free integer above the current max in `db/migration` — shown here as `V20+` placeholders; the exact integers are assigned at PR time.

**`V20__tea_source_add_scrape.sql`**
```sql
ALTER TABLE tea DROP CONSTRAINT tea_source_check;
ALTER TABLE tea ADD CONSTRAINT tea_source_check
  CHECK (source IN ('wikidata','curated','ai','user','scrape'));
```

**`V21__tea_public_id_and_lifecycle.sql`** (stable public id + soft-rollback)
```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
ALTER TABLE tea
  ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
  ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
      CHECK (status IN ('active','retracted','merged')),
  ADD COLUMN retracted_at TIMESTAMPTZ,
  ADD COLUMN merged_into_public_id UUID;
ALTER TABLE tea ADD CONSTRAINT uq_tea_public_id UNIQUE (public_id);

CREATE TABLE tea_legacy_id_map (
  legacy_id BIGINT PRIMARY KEY,     -- the BIGINT the APK v0.1.0 cached
  public_id UUID NOT NULL REFERENCES tea(public_id)
);
```

**`V22__tea_field_provenance.sql`** (per-field provenance — fixes #5)
```sql
CREATE TABLE tea_field_provenance (
  id BIGSERIAL PRIMARY KEY,
  tea_id BIGINT NOT NULL REFERENCES tea(id),
  field_name TEXT NOT NULL,          -- e.g. 'region','cultivar','name:ru'
  source_record_id BIGINT REFERENCES source_record(id),
  source_site_id BIGINT REFERENCES source_site(id),
  source_url TEXT,
  license TEXT,
  confidence NUMERIC,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_tfp_tea ON tea_field_provenance (tea_id, field_name);
```
This lets one canonical tea record per-field `source/url/license/confidence`, so a tea assembled from multiple source records is representable. `tea.source` remains the row's creation origin; field truth lives here.

**`V23__tea_identity_alias.sql`** (cross-script authoritative aliases)
```sql
CREATE TABLE tea_identity_alias (
  id BIGSERIAL PRIMARY KEY,
  tea_id BIGINT NOT NULL REFERENCES tea(id),
  locale TEXT NOT NULL,              -- en, ru, zh-Hans, pinyin
  alias TEXT NOT NULL,
  alias_norm TEXT GENERATED ALWAYS AS (lower(f_unaccent(alias))) STORED,
  romanization_system TEXT,          -- pinyin, palladius, wade-giles, none
  origin TEXT NOT NULL,              -- curated, human_confirmed, library_derived
  verified BOOLEAN NOT NULL DEFAULT false,
  source TEXT
);
CREATE INDEX ix_tia_norm_trgm ON tea_identity_alias USING gin (alias_norm gin_trgm_ops);
```
Library-derived aliases stay `verified=false` until approved.

(Plus `V24__ingest_tables.sql` creating `source_site`, `import_run`, `raw_evidence`, `source_record` with its two unique indexes, `normalized_candidate`, `match_decision`, `catalog_snapshot`.)

### The real UPSERT path
**Source-record upsert (idempotent, keyed by source record):**
```sql
INSERT INTO source_record
  (source_site_id, canonical_url, external_id, content_hash,
   parser_version, retrieved_at, import_run_id, raw_evidence_id, raw_facts, status)
VALUES (:siteId, :url, :extId, :hash, :pv, :ts, :runId, :rawId, :facts::jsonb, 'parsed')
ON CONFLICT (source_site_id, canonical_url) DO UPDATE SET
  content_hash   = EXCLUDED.content_hash,
  parser_version = EXCLUDED.parser_version,
  retrieved_at   = EXCLUDED.retrieved_at,
  import_run_id  = EXCLUDED.import_run_id,
  raw_evidence_id= EXCLUDED.raw_evidence_id,
  raw_facts      = EXCLUDED.raw_facts,
  status = CASE WHEN source_record.content_hash IS DISTINCT FROM EXCLUDED.content_hash
                THEN 'reparse_pending' ELSE source_record.status END
RETURNING id;
```
(For the `external_id` arbiter, the partial unique index requires the matching `WHERE external_id IS NOT NULL` predicate be repeated in the `ON CONFLICT` clause — a documented PostgreSQL partial-index requirement; otherwise you get "there is no unique or exclusion constraint matching the ON CONFLICT specification".)

**Approved canonical upsert/update (only after human approval):**
- `approved_new` → `INSERT INTO tea (... , source, verification_status, status) VALUES (..., 'scrape', :nonVerified, 'active')`, then insert names, aliases, and `tea_field_provenance`.
- `approved_merge` → `UPDATE tea SET ...` for changed fields + `INSERT` new `tea_identity_alias` + new `tea_field_provenance`. This is the correction path #131 lacked.

### Importer DTO (Kotlin) — no defaults, rejects 'verified'
```kotlin
data class TeaFactImportDTO(
  val sourceSiteId: Long,
  val canonicalUrl: String,
  val externalId: String?,
  val retrievedAt: Instant,
  val parserVersion: String,
  val contentHash: String,
  val facts: TeaFacts          // structured facts ONLY — no prose field
  // intentionally NO source default, NO verification_status default
)
data class TeaFacts(
  val nameRu: String?, val nameZh: String?, val namePinyin: String?, val nameEn: String?,
  val type: String?, val originCountry: String?, val region: String?,
  val cultivar: String?, val vendor: String?
)
```
Importer rules: it sets `tea.source = 'scrape'` explicitly at materialization; sets `verification_status` to a non-verified value; and **rejects** any attempt to import `verification_status = 'verified'` (`require(dto.verificationStatus != VERIFIED)`), since a scrape can never self-certify as verified.

---

## (D) Stable Public ID + Soft-Rollback + CatalogTeaRef Interaction

**Policy (lockable):**
1. **Introduce `tea.public_id UUID UNIQUE`** (above). The API exposes `public_id`, never the internal BIGINT `tea.id`. `catalog.detail(...)` keys on `public_id`. (This follows the standard guidance that publicly exposed identifiers should be stable surrogate keys that do not change over time and do not leak the underlying DB sequence, while the BIGINT remains the internal FK/PK.)
2. **A public_id, once returned to clients, is never reused and never re-numbered.** Seeds carry explicit committed `public_id` values, so a DB rebuild/reseed reproduces the same UUIDs (no re-numbering). This is the core fix for #6.
3. **Soft-rollback, never hard-delete.** Retire a tea by `status='retracted'` (preserve the id) or `status='merged'` with `merged_into_public_id` pointing at the survivor. The API resolves `merged`→survivor with redirect semantics so a client holding the old `public_id` still resolves. Hard `DELETE` and id reuse are forbidden.
4. **CatalogTeaRef interaction (decision #132 is a fixed boundary — designed UP TO, not redesigned).** The v7 `CatalogTeaRef` should cache `public_id` (UUID) as its stable key. **Legacy-ID compatibility period:** because APK v0.1.0 already cached the BIGINT as `catalogTeaId`, the `tea_legacy_id_map` table maps `legacy_id → public_id`; the API resolves the old numeric id during a defined compatibility window and clients migrate `CatalogTeaRef` to `public_id` on next sync. After the window, numeric resolution can be retired. The TeaSample side of the #132 split is untouched.

---

## (E) Facts-Only Boundary + ToS/Robots Gates + CI Guard + Injection Safety

**Facts-only boundary (hard line from #129/#131):** scrape only ru/zh/pinyin names, type, origin/region, cultivar, vendor into the public catalog. **Never** persist raw vendor descriptions, reviews, or photos server-side as catalog content. Raw prose, if retained at all, lives only in `raw_evidence.raw_blob_ref` (access-controlled, short retention, never shipped) and is used solely as **structured-facts input** to the existing #22 enrichment tier, which writes our **own** blurb (decision #22, fixed — we define its input/output gates only, not its providers/models/prompts/enablement):
- **Input gate:** the #22 tier receives structured facts (+ optionally short-retention raw text as grounding) — never publishes vendor prose.
- **Output gate:** generated descriptions are stored with `source='ai'`, never `'scrape'`.

**CI guard (build-time, fails the build):**
- Assert no `tea_description` row with `source='scrape'` exists.
- Assert no `tea_image` row whose `source_url` is a scraped vendor domain or whose `license` is null/unlicensed is included in the public snapshot.
- Assert the public API DTO never serializes `source_record.raw_facts` free-text or `raw_evidence` content.

**ToS gate (hard preflight):** no `import_run` may start for a `source_site` unless `terms_url` is set, `terms_checked_at` is recent, and `terms_owner_signoff = true`. This operationalizes the Ryanair principle. In Ryanair Ltd v PR Aviation BV (Case C‑30/14, Judgment of the Court (Second Chamber), 15 January 2015), the CJEU held that Directive 96/9/EC "is not applicable to a database which is not protected either by copyright or by the sui generis right under that directive, so that Articles 6(1), 8 and 15 of that directive do not preclude the author of such a database from laying down contractual limitations on its use by third parties." In plain terms: ToS can bind even where copyright/database right does not — so we treat sign-off as a blocking gate, not a footnote.

**Per-run robots re-check (not one-time):** every `import_run` fetches `robots.txt` fresh, stores `robots_snapshot` + `robots_checked_at`, and evaluates `can_fetch` per URL with **Protego** (RFC-9309-aware). The recon confirmed artoftea.ru's robots.txt could not be read remotely and must be checked at run time — reinforcing that this is a live, per-run gate.

**Injection safety:** `catalog_miss` text (and any scraped string) is treated as an opaque search term only. It must **never** be interpolated into a fetch URL, a shell argument, or SQL. Fetch targets come exclusively from an allowlist of `source_site.base_url` + parsed catalog links; all DB writes use parameterized statements; no subprocess ever receives miss-log text.

---

## (F) Pinned OSS Stack (Latest Stable vs Recommended Pin) + Operating Model

All versions verified live on PyPI on 2026-06-21. **Latest stable = recommended pin** for every library below unless noted.

| Library | Latest stable (PyPI, 2026-06-21) | Recommended pin | License | Python compat | Maintenance | Role |
|---|---|---|---|---|---|---|
| httpx | 0.28.1 (released 2024-12-06) | `0.28.1` | BSD-3-Clause (confirmed on pypi.org/project/httpx/) | "Requires: Python >=3.8" (confirmed on PyPI) | Active; classifier still "4 - Beta" | Pilot HTTP client |
| selectolax | 0.4.10 (2026-05-26) | `0.4.10` | MIT (wheel bundles Lexbor engine = Apache-2.0; Modest engine = LGPL-2.1, unused if you use Lexbor) | ≥3.9,<3.15 | Active, Production/Stable | HTML parse (use Lexbor backend) |
| Protego | 0.6.1 (2026-06-11) | `0.6.1` | BSD-3-Clause | ≥3.10 | Active (Scrapy org) | robots.txt (RFC-9309-aware) |
| pypinyin | 0.55.0 (2025-07-20) | `0.55.0` | MIT (bundled CC-CEDICT data is CC-BY-SA 3.0) | ≥2.6 (excl. 3.0–3.2) | Active, Production/Stable | Hanzi→pinyin candidates ONLY |
| Scrapy | 2.16.0 (2026-05-19) | defer; `2.16.0` when needed | BSD-3-Clause | ≥3.10 | Active, Production/Stable | Full sitemap traversal (later) |
| anyascii | 0.3.3 (2025-06-29) | optional; `0.3.3` | ISC | ≥3.3 | "Inactive" per Snyk | Coarse transliteration candidate ONLY — never identity |

**On `urllib.robotparser` (stdlib):** suitable *only* marginally. The stdlib parser is **first-match-wins within a group and does not implement RFC 9309's longest-match / `*`,`$` wildcard semantics** (there is an open CPython issue to add RFC 9309 support; it can disagree with Googlebot when a more-specific `Allow` should override a broader `Disallow`). Because **Protego is BSD-3-Clause, pure-Python, small, and RFC-9309-aware**, the recommendation is to **use Protego for the robots gate from the pilot** rather than stdlib — a correction to #131's assumption. stdlib remains an acceptable zero-dependency fallback only for trivially simple robots files.

**Lock file with hashes (required at implementation):** generate a fully pinned, hashed lock (`uv lock` / `pip-compile --generate-hashes`) committed to the repo. The decision-log versions are not an implementation lock file; the hashed lock is the install contract. Run a license report (`pip-licenses`) in CI and confirm no copyleft (LGPL/GPL) creeps into a distributed artifact — note the Modest engine is LGPL-2.1, so the build must use selectolax's Lexbor (Apache-2.0) backend.

**Where each earns its keep / operating model:**
- The pilot is demand-driven (top `catalog_miss` terms, decision #116) and tens of records. **httpx + selectolax + Protego** is sufficient and minimal; no Scrapy overhead.
- **Scrapy + Protego** are reserved for later full sitemap traversal where scheduling, dedup, and politeness middleware pay off.
- **pypinyin** generates pinyin from Hanzi for candidate keys; it cannot bridge Cyrillic Palladius (that is the curated table + human confirmation).
- **anyascii** only ever produces a coarse review-queue candidate; it never writes an alias that establishes identity.
- **Where scraping runs:** a **local operator one-off CLI** — NOT the API path, NOT the prod Yandex Cloud VM. The CLI emits JSON facts that are fed to the importer endpoint/CLI. It coexists with the curated seed, the miss-log, the #22 enrichment tier, and the v7 work without touching the request path. The match-review queue is an **operator-side ingestion tool, not Android UI**.
- **Fixed boundaries honored:** Art of Tea first, tea.ru excluded, local operator execution, facts-only publication.

---

## (G) Ordered, PR-Sized First Deliverable + What to Defer

**Build first (the foundation):**
- **PR1 — `tea.source` + identity scaffolding migration.** `V20` (add `'scrape'`), `V21` (public_id + lifecycle + legacy map). Smallest correct unblock: a `'scrape'` row becomes insertable and a stable public id exists.
- **PR2 — Source registry + gates.** `source_site` (ToS gate columns) + `import_run` (per-run robots snapshot). No fetching yet — just the gate tables and a preflight check service.
- **PR3 — Staging tables.** `raw_evidence`, `source_record` (+ the two idempotency unique indexes), `normalized_candidate`, `match_decision`, `tea_field_provenance`, `tea_identity_alias`, `catalog_snapshot`.
- **PR4 — Importer service + DTO + upsert SQL.** The explicit importer with the source-record `ON CONFLICT` upsert and the approved-canonical insert/update path; DTO with no defaults, rejecting `'verified'`.
- **PR5 — Cross-script identity + tiered matcher + operator review-queue CLI.** Curated alias seed; Tier 0–3 logic; pypinyin + Palladius-table candidate generation; thresholds left for later calibration; no auto-merge.
- **PR6 — Facts-only CI guard + API switch to public_id + legacy resolution.**

**Defer:** the actual fetcher/crawler implementation; Scrapy + full sitemap traversal; threshold calibration on a labeled corpus; AI enrichment wiring (define I/O only, do not build); any persistence of vendor prose beyond short-retention `raw_evidence`.

---

## Recommendations
1. **Lock PR1–PR3 immediately** — they are pure schema/scaffolding, low-risk, and unblock everything. Benchmark to advance: migrations applied cleanly on a copy of prod; a `'scrape'` row inserts; `public_id` populated and seeds reproduce identical UUIDs on rebuild.
2. **Gate PR4 on a passing idempotency test**: re-running the same source URL twice produces one `source_record` row and zero duplicate teas; a changed `content_hash` re-queues review.
3. **Do not calibrate thresholds until you have a labeled corpus**; until then every fuzzy/transliteration match is review-only. Threshold-tuning is the trigger to revisit Tier 1–3 auto-merge — and only after the pilot.
4. **Verify artoftea.ru robots.txt and `/privacy` text manually before the first run**, record `terms_owner_signoff`, and re-fetch robots every run.
5. **Adopt Protego over stdlib `urllib.robotparser`** from the pilot.

## Caveats
- artoftea.ru's `robots.txt` and ToS/`/privacy` text were not machine-readable in recon; treat all source/robots/ToS state as run-time-verified, never assumed. Product-page JSON-LD/microdata is unconfirmed (Open Graph confirmed) — confirm by inspecting raw HTML before writing parsers.
- httpx remains classifier "4 - Beta" and anyascii is "Inactive" per Snyk — both acceptable for a local operator tool, but pin exactly and revisit at scale.
- The Palladius↔pinyin bridge is a curated reference table, not a maintained Python library (only Raku/PHP implementations exist publicly); its coverage must be expanded as new Cyrillic tea names appear.
- The exact Flyway version integers depend on the current max in `db/migration`; assign the next free integers at PR time.
- Legal section is practical, not advice. Per ГК РФ ст. 1334 п.1 (Гражданский кодекс РФ, часть четвертая, ред. от 23.07.2025, действ. с 04.01.2026): "При отсутствии доказательств иного базой данных, создание которой требует существенных затрат, признается база данных, содержащая не менее десяти тысяч самостоятельных информационных элементов (материалов)…" — i.e. the 10,000-element presumption is *rebuttable* ("при отсутствии доказательств иного"). A tens-of-records pilot is far below that presumption, but the maker may still attempt to prove substantial investment for a smaller set; facts are not copyrightable; ToS (per Ryanair C‑30/14) is the real enforceable lever, which is why owner sign-off is a hard gate.

## One-Paragraph Lockable Recommendation (supersedes the unbuildable parts of #131)
Supersede #131's import/identity machinery with this foundation-first design: add `'scrape'` to the `tea.source` CHECK and introduce a `tea_field_provenance` table so a canonical tea can carry per-field source/url/license/confidence; build a *new* explicit importer + facts-only DTO (no `source`/`verification_status` defaults, rejecting `'verified'`) with a real upsert path whose idempotency is keyed on the **source record** `(source_site, canonical_url/external_id)` — not on `dedup_key` — and whose corrections/aliases flow through an approved-merge UPDATE; resolve cross-script identity with a hybrid model where curated entity-linked aliases and human-confirmed decisions are authoritative while pypinyin (Hanzi→pinyin), a curated Palladius↔pinyin bridge, and anyascii generate review-queue candidates only (no auto-merge in the pilot, thresholds calibrated later on a labeled corpus); make the public identifier a stable `public_id UUID` with soft-rollback (retract/merge, never hard-delete or re-number) and a `legacy_numeric_id` compatibility window feeding `CatalogTeaRef`; enforce facts-only publication with a build-time CI guard, a per-source ToS owner-sign-off gate (Ryanair C‑30/14), and a per-run Protego robots re-check; and pin httpx 0.28.1 + selectolax 0.4.10 (Lexbor backend) + Protego 0.6.1 + pypinyin 0.55.0 (Scrapy 2.16.0 deferred) behind a hashed lock file, run locally by the operator off the prod path. Ship it as PR1–PR6 in that order; the crawler comes after the foundation.