# Catalog scraping plan review

Date: 2026-06-21  
Repository baseline: `main` at `9ff8ed2`  
Scope: research run 20, decision #131, the live catalog schema/import path, source behavior,
open-source reuse, rights/provenance boundaries, matching, rollout, and failure recovery.

## 1. Verdict

The source and operating-model choices are mostly correct:

- begin with `artoftea.ru`;
- exclude `tea.ru` because its live robots policy blocks the catalog/product paths;
- run extraction locally, not in the API request path and not as an always-on production service;
- ship factual fields only, not vendor descriptions, reviews, or photos;
- retain evidence and require human review before canonical publication;
- prioritize work from aggregate catalog misses instead of copying every reachable product.

The locked implementation description is **not safe to build unchanged**. Its central identity and import
assumptions do not match the code:

1. `dedup_key` is not cross-script canonical. It is a useful uniqueness guard for one chosen primary
   name/pinyin/type tuple, but it does not prove that `Да Хун Пао`, `Da Hong Pao`, and `大红袍` are the same
   entity.
2. Re-import idempotency must be keyed by the source record (`source + external_id/canonical_url`), not by
   the canonical tea key. Source-record identity and canonical entity resolution are different problems.
3. `CatalogSeeder` is insert-or-skip, not an upsert. It cannot apply corrections, new aliases,
   per-field provenance, or conflict decisions to an existing row.
4. The current provenance schema cannot accurately represent a canonical tea assembled from multiple
   source records. It also cannot put `scrape:artoftea` in `tea.source` because the database check permits
   only `wikidata|curated|ai|user`.
5. The public API exposes database-generated `BIGINT` IDs and Android persists them. Large catalog imports
   therefore need a stable-public-identity and recovery policy before seed regeneration or database rebuilds
   can be considered safe.
6. Persisting raw vendor prose in the server is unnecessary for the first cut and increases copyright,
   leakage, retention, and access-control risk. Structured facts are enough for the initial importer and
   for grounded description generation.

**Recommendation:** keep the source selection and facts-only policy, but replace “scrape directly into the
existing seed/upsert path” with a staged observation-to-canonical pipeline. Use Scrapy's maintained crawl
machinery for real sitemap/category traversal; reserve `httpx + selectolax` for a fixed-URL parser spike.
Do not publish bulk scraped records until the identity, provenance, explicit import, and stable-ID issues
below are resolved.

No new multi-model research run is needed. Run 20 has been judged; the remaining work is repository-specific
design correction, a bounded source pilot, and measured matching/parser validation.

## 2. What was reviewed

### Repository sources

- `research/20-catalog-scraping/prompt.md`
- every answer and `research/20-catalog-scraping/RATING.md`
- `context/decisions.md` #129, #130, #131, and #132
- `context/plan.md`, `task.md`, and `architecture.md`
- `context/design/tea-sample-split-v7.md`
- `server/.../service/DedupKeys.kt`
- `server/.../service/CatalogSeeder.kt` and `SeedModels.kt`
- Flyway V1/V4/V5 catalog, search, and miss-log schemas
- catalog search, resolve, Wikidata import, and Android catalog-ID consumers
- `scripts/catalog-curation-runbook.md`
- the committed catalog seed

### Live/primary-source verification performed for this review

- [Art of Tea robots.txt](https://artoftea.ru/robots.txt)
- [Art of Tea product sample](https://artoftea.ru/oolong/dahongpao-high-baking)
- [Art of Tea sitemap index](https://artoftea.ru/index.php?route=extension/feed/yandex_sitemap)
- [tea.ru robots.txt](https://tea.ru/robots.txt)
- [H2O Company robots.txt](https://h2ocompany.ru/robots.txt)
- [RFC 9309: Robots Exclusion Protocol](https://www.rfc-editor.org/rfc/rfc9309.txt)
- [Python `urllib.robotparser`](https://docs.python.org/3/library/urllib.robotparser.html)
- [Scrapy settings](https://docs.scrapy.org/en/latest/topics/settings.html),
  [downloader middleware](https://docs.scrapy.org/en/latest/topics/downloader-middleware.html),
  [AutoThrottle](https://docs.scrapy.org/en/latest/topics/autothrottle.html),
  [jobs](https://docs.scrapy.org/en/latest/topics/jobs.html), and
  [feed exports](https://docs.scrapy.org/en/latest/topics/feed-exports.html)
- current PyPI metadata for Scrapy, HTTPX, selectolax, and pypinyin

Live pages are volatile. The crawler must repeat the source/robots checks at run time; this review's snapshot
does not authorize future runs.

## 3. Correct parts of decision #131

### 3.1 `artoftea.ru` is the right pilot source

The live site remains server-rendered and exposes useful explicit fields. The sampled product contains:

- canonical URL;
- an OpenCart `product_id` (`504` in the sample);
- product title;
- category/type (`улун`);
- collection region (`горный хребет Уишань, провинция Фуцзянь`);
- harvest year (`2025`);
- producing country (`Китай`);
- vendor-specific variants, price, taste prose, and reviews.

The current `robots.txt` permits the sampled category/product paths for `User-agent: *` while disallowing
account, affiliate, checkout, product-search, sort, tracking, and filter URLs. This is a workable technical
posture for a conservative pilot.

The sitemap is useful as a frontier but is **not a tea-only dataset**. Sampled shards include tea products,
teaware, accessories, images, and other commerce pages. The importer must filter to an explicit tea-category
allowlist and still validate the page's product type. It must not assume every sitemap URL is a tea.

### 3.2 `tea.ru` must remain excluded

Its current `User-agent: *` block disallows `/catalog/`, `/product/`, `/products/`, and `/search/`.
This is an explicit machine-readable exclusion of the paths the plan would need.

### 3.3 H2O is only a candidate second source

The current H2O robots file mainly blocks Bitrix/service/search/personal/query paths and does not globally
block ordinary product paths. That makes it technically investigable, not approved. Before use it still
needs:

- a terms/manual rights check;
- a fixed-page parser pilot;
- source taxonomy mapping;
- a product/external-ID strategy;
- measured overlap and incremental value beyond Art of Tea;
- the same provenance and facts-only gates.

Do not build two adapters in the first milestone. Prove the end-to-end review/import process with one source.

### 3.4 The facts/expression boundary is directionally right

Public records may carry independently reviewed facts such as names, type, country, region, cultivar, and a
source link. The following stay out of public catalog artifacts unless an independent redistribution license
is recorded and reviewed:

- product and editorial descriptions;
- customer reviews;
- vendor photographs and sitemap image URLs;
- marketing slogans and tasting narratives;
- page HTML;
- prices, availability, package variants, and stock state unless a separate ephemeral product-observation
  feature is intentionally designed.

Robots compliance is not permission or authorization; RFC 9309 says so explicitly. A source registry and
manual owner approval are still required.

## 4. Blocking findings

| Priority | Finding | Consequence | Required correction |
|---|---|---|---|
| P0 | `dedup_key` is not cross-script canonical | Same tea can be inserted once per primary-name script or type | Separate source identity, canonical identity, aliases, and match decisions |
| P0 | Source-record idempotency is missing | Changed URLs/titles or different canonical matches produce duplicates or overwrite ambiguity | `UNIQUE(source_id, external_id)` plus canonical URL/history and content hash |
| P0 | `CatalogSeeder` is insert-or-skip | Corrections, aliases, provenance, and verification changes never apply | Dedicated explicit importer with dry-run and transaction; keep startup seeder small |
| P0 | Multi-source provenance is not representable | Canonical values lose which page supported them and why one conflict won | Store source observations and field-level claim/decision evidence |
| P0 | Stable public catalog identity is unresolved | Fresh rebuild/reseed can change IDs already stored by released Android clients | Add immutable public ID or a recovery path that reproduces IDs exactly |
| P1 | Vendor product/lot and canonical tea are conflated | Grade/year/roast/SKU variations explode into false canonical teas | Model source products as observations; attach many observations to one canonical tea |
| P1 | Raw prose is proposed as server staging by default | Creates avoidable retention, access, leakage, and expression-copy risk | Do not retain prose in v1; if later needed, local short-lived quarantine only |
| P1 | `httpx` plan reimplements crawler controls | Bespoke frontier, cache, retries, throttle, stats, and restart logic accumulate | Use Scrapy for traversal; keep tiny stack only for a fixed URL spike |
| P1 | Normalization layers are conflated | Python and PostgreSQL comparisons drift; destructive cleanup loses evidence | Preserve raw values and separate DB normalization from match cleanup |
| P1 | Existing seed defaults scraped records to curated/verified | Automated source data can silently claim human verification | No defaults for import DTOs; explicit source/status required; auto data is unverified |
| P1 | Current CI gate is too narrow | Expression or unlicensed images can leak through non-description fields/artifacts | Gate the complete public export, not one SQL source prefix |
| P1 | No import-run/rollback design | Bad merge or parser drift is difficult to identify and reverse | Immutable run manifest, before/after plan, audit rows, and tested rollback |
| P2 | Sitemap/page parsing can silently mix accessories and reviews | Non-tea products and copyrighted review text enter staging | Category/page-type allowlist, label-based parser, forbidden-selector tests |
| P2 | Catalog-miss input is not a crawl instruction | Noise, private text, and arbitrary strings could drive unsafe discovery | Aggregate prioritization only; manual queue maps misses to approved source URLs |

## 5. Identity: the plan's most serious error

### 5.1 What the code actually does

`DedupKeys.of(primaryName, pinyin, type)` returns:

```text
normalize(primaryName) | alphanumeric-pinyin-slug | TYPE
```

The seed chooses the English primary name when present, otherwise the first primary name. Therefore:

```text
DedupKeys.of("Da Hong Pao", "Da Hong Pao", OOLONG)
  = da hong pao|dahongpao|OOLONG

DedupKeys.of("Да Хун Пао", "Da Hong Pao", OOLONG)
  = да хун пао|dahongpao|OOLONG
```

Those keys are different. A Hanzi-primary record is different again. A correction from `OTHER` to `OOLONG`
also changes the key.

The pinyin component improves collision resistance, but it does not replace the script-dependent primary
name. Calling this key “pinyin-canonical” overstates what it guarantees.

There is also an input mismatch: `pypinyin` derives pinyin from Hanzi. The useful Art of Tea title in the
sample is Cyrillic (`Да Хун Пао`), not a Hanzi name. `pypinyin` cannot turn that source value into
`da hong pao`; a trusted existing alias/Wikidata match or an explicit reviewed mapping must bridge it.

### 5.2 `pg_trgm` does not repair cross-script identity

`tea_name.name_norm` is generated by PostgreSQL as `lower(f_unaccent(name))`. It removes Latin diacritics
but passes Cyrillic and CJK through. Production fuzzy search is intentionally useful for same-script typos;
it cannot infer that Cyrillic, Latin pinyin, and Hanzi strings denote the same tea.

The correct role split is:

- **source key:** stable identity of one vendor product page;
- **canonical public ID:** stable identity of one TeaTiers catalog entity;
- **alias:** one attested or derived name attached to that canonical entity;
- **dedup key:** last-resort database uniqueness guard, not proof of entity equivalence;
- **match decision:** auditable evidence connecting a source record to a canonical entity.

### 5.3 Safe match order

Use this conservative order:

1. Existing mapping for the same `(source, external_id)` -> reuse the prior canonical ID.
2. Exact trusted external identity, such as an already-reviewed Wikidata QID -> candidate match.
3. Exact unique alias match after source-title cleanup -> candidate match.
4. Same-script `name_norm`/`pg_trgm` candidates -> review evidence, not automatic identity.
5. Derived pinyin/Hanzi/Cyrillic comparisons plus type/region/cultivar corroboration -> review evidence.
6. No safe match -> create an **unpublished candidate**, not a canonical row.

For the first production run, auto-attach only case 1 and unambiguous reviewed external IDs. Everything else
should be reviewed. A labeled corpus can later justify additional auto-match rules.

### 5.4 Do not invent a score threshold

Run 20 correctly rejected several fabricated thresholds, but its weighted scoring proposals are still
untuned. Do not ship `0.65`, `0.85`, or any other confidence boundary until it is measured on a hand-labeled
set containing at least:

- exact aliases;
- Cyrillic spacing variants (`Да Хун Пао`, `Дахунпао`, `ДХП`);
- pinyin spacing/hyphen/tone variants;
- traditional and simplified Hanzi;
- vendor grade/year/roast suffixes;
- same-family but distinct teas;
- products whose vendor taxonomy disagrees with TeaTiers;
- blends and branded products;
- deliberately difficult negative pairs.

Report precision and recall separately. False merges are more damaging than duplicate candidates, so an
auto-merge threshold must prioritize measured precision and leave uncertainty in review.

## 6. Vendor products are observations, not canonical teas

The sampled page demonstrates why one product cannot map 1:1 to `tea`:

- `product_id=504` identifies the vendor's listing;
- “высший сорт” is a vendor grade;
- harvest year 2025 is lot-specific;
- pack sizes and prices vary;
- taste prose and reviews are vendor/user expression;
- region/type may support the canonical Da Hong Pao entity.

The server's canonical catalog and the Android v7 sample design already point to the right separation:

```text
vendor source product / observation
              N
              |
              | reviewed match
              v
        canonical catalog tea
              1
              |
              | user chooses/refers to
              v
        personal TeaSample (N)
```

Keep in the source observation:

- source external ID and canonical URL;
- vendor title and parsed candidate names;
- product category and source taxonomy;
- grade, harvest year, roast, batch, SKU, package/price if collected at all;
- retrieval/evidence metadata;
- raw factual key/value pairs;
- match status and reviewer decision.

Promote to canonical tea only stable reviewed facts. Never add a canonical tea solely because the vendor
created a new harvest-year page.

## 7. Recommended pipeline

```text
approved source registry
  -> robots/terms preflight + immutable run manifest
  -> polite, allowlisted fetch
  -> response evidence/cache
  -> source-specific parse into source records + factual claims
  -> schema and parser-drift validation
  -> candidate normalization (raw preserved)
  -> exact/fuzzy candidate generation against current catalog
  -> human match/conflict review
  -> deterministic dry-run catalog patch
  -> public-content/provenance/identity gates
  -> explicit transactional import
  -> post-import verification + second-run no-op check
```

### 7.1 Keep staging local for the first cut

There is no need to add raw crawl tables or vendor prose to the production server for the pilot. A local
SQLite database can hold:

```text
source
  id, name, base_url, approval_state, robots_url, allowed_paths,
  terms_url, owner_decision, checked_at

ingestion_run
  run_id, source_id, started_at, finished_at, crawler_version,
  parser_version, config_hash, robots_hash, status, stats_json

fetch
  run_id, requested_url, final_url, external_id, status, content_type,
  retrieved_at, etag, last_modified, body_sha256, body_path, error

source_record
  source_id, external_id, canonical_url, first_seen_at, last_seen_at,
  body_sha256, parser_version, parsed_json, record_status

source_claim
  source_id, external_id, field_path, raw_value, normalized_value,
  extraction_rule, confidence, rights_class

match_decision
  source_id, external_id, catalog_public_id, decision, matcher_version,
  evidence_json, reviewer, reviewed_at
```

Key constraints:

```text
UNIQUE(source_id, external_id)
UNIQUE(source_id, canonical_url) where canonical_url is not null
UNIQUE(run_id, requested_url)
```

The response body cache must be ignored by Git. Commit only fixtures whose content has been deliberately
minimized/redacted for parser testing, plus approved structured catalog patches and their evidence manifest.

### 7.2 Field-level provenance must survive review

The current single `tea.source/source_url/license` tuple is not enough when one source supplies a Russian
alias, another supplies region, and a curator resolves a taxonomy conflict.

Minimum viable approach:

- keep full claim evidence in the committed approved-import manifest;
- add an internal server `tea_source_claim`/`catalog_claim` table before multi-source imports;
- reference canonical tea, field path/value, source URL/record, retrieval time, import run, and review
  decision;
- expose only the provenance needed by the UI, but retain enough internal evidence to reproduce and reverse
  decisions.

Do not use `license=null` to mean “facts are unprotected.” License, source attribution, robots compliance,
and the project's redistribution decision are different concepts. Use explicit fields such as
`rights_class=fact|expression|image` and `public_export_allowed=true|false`, while preserving a real license
identifier only when one actually applies.

### 7.3 Production import must be explicit

Do not let the crawler connect to PostgreSQL. Do not run bulk content mutation during normal server startup.

Build a dedicated importer that:

1. validates a versioned approved patch;
2. checks the expected catalog baseline/hash;
3. prints additions, aliases, changed facts, conflicts, and rejected operations;
4. requires an explicit apply command/profile;
5. applies one transaction;
6. writes `catalog_import_run` and per-operation audit rows;
7. never overwrites a verified curated value without an explicit reviewed conflict decision;
8. preserves canonical IDs;
9. can produce or execute a tested inverse/rollback plan;
10. returns a no-op on a second identical application.

The importer should use the server's Kotlin domain/schema contract. A standalone Python scraper should not
duplicate production mutation rules or carry production database credentials.

## 8. Why `CatalogSeeder` cannot be the importer

Current behavior:

- computes a key from one primary name, pinyin, and type;
- queries by that key;
- skips the complete seed record if the row exists;
- inserts otherwise;
- defaults source to `curated`;
- defaults verification status to `verified`;
- assigns every seed name the tea-level source;
- has no per-name source URL/license/retrieval time;
- has no import-run, match-decision, conflict, or rollback record;
- ignores unknown JSON fields because Jackson is configured with
  `FAIL_ON_UNKNOWN_PROPERTIES=false`.

The runbook says the seed accepts `retrieved_at`, but `SeedTea` has no such property. Adding it to JSON would
therefore be silently ignored. The runbook also calls the loader an upsert, but existing rows are skipped.

These mismatches are dangerous for generated content. Required corrections before any scrape-derived patch:

- keep `CatalogSeeder` for the small trusted bootstrap only;
- make generated/import DTOs separate from `SeedTea`;
- require explicit `source`, `source_url`, `retrieved_at`, review state, and stable identity;
- fail on unknown fields for import artifacts;
- never default an imported record to `verified` or `curated`;
- validate public-output policy on the serialized final artifact and resulting database state.

## 9. Stable public identity and disaster recovery

`tea.id` is `BIGINT GENERATED ALWAYS AS IDENTITY`. The API returns it, Android caches it, personal records
persist it as `catalogTeaId`, and the v7 design continues using it as `catalog_refs.id`.

This is safe while one PostgreSQL database and its backups remain authoritative. It is not sufficient for a
catalog that may be reconstructed from an evolving seed/import history: insertion order or missing history
can assign a different integer to the same tea.

Before bulk growth, choose and test one of these contracts:

1. **Preferred:** add an immutable random UUID/ULID `public_id` to canonical tea, make it the long-term API
   identity, and migrate clients through a compatibility period while retaining the numeric internal PK.
2. Preserve and import explicit canonical IDs in every disaster-recovery artifact, manage the identity
   sequence, and prove a blank-database restore reproduces all IDs exactly.
3. Declare PostgreSQL backup/restore, not seed replay, the only recovery mechanism; then test backups and keep
   import history consistent with that operational contract. This is weaker and makes repo snapshots
   insufficient on their own.

The scraper does not need to block a 30-record local pilot on a UUID migration, but it must not publish a
large canonical update until the recovery identity is decided.

## 10. Open-source tooling review

### 10.1 Current package verification

At review time, current PyPI metadata confirms:

| Package | Version | License | Use |
|---|---:|---|---|
| Scrapy | 2.16.0 | BSD-3-Clause | crawl scheduler, robots middleware, retry, throttle, HTTP cache, persistent jobs, exports, stats |
| HTTPX | 0.28.1 | BSD-3-Clause | fixed-URL/preflight HTTP client |
| selectolax | 0.4.10 | MIT | fast HTML parser for a tiny standalone spike |
| pypinyin | 0.55.0 | MIT | derived pinyin candidate aliases for actual Hanzi input |

Pin with hashes in a lock file generated from a minimal `pyproject.toml`. Do not rely only on exact version
strings in prose.

### 10.2 Recommended tool choice

#### Fixed-URL parser pilot

Use the locked small stack for 30-50 hand-selected URLs:

```text
HTTPX + selectolax + SQLite + pypinyin
```

This is appropriate when the URL list is fixed and the purpose is to learn page shapes and validate fields.

#### Real source traversal

Use:

```text
Scrapy 2.16 + built-in selectors/Parsel + default Protego robots parser
+ built-in RetryMiddleware, RobotsTxtMiddleware, AutoThrottle,
  HttpCacheMiddleware, JOBDIR restartability, FEEDS JSONL, and stats
+ SQLite staging/match-review layer
+ pypinyin only in normalization/candidate generation
```

This removes duplicate custom work for the exact controls a polite crawl needs. Do not add selectolax to the
Scrapy implementation unless measured parser performance demonstrates a real need; Scrapy's existing
selector stack is enough at this scale.

Core settings should express a conservative project policy, for example:

```python
ROBOTSTXT_OBEY = True
CONCURRENT_REQUESTS_PER_DOMAIN = 1
DOWNLOAD_DELAY = 2.0
RANDOMIZE_DOWNLOAD_DELAY = True
AUTOTHROTTLE_ENABLED = True
AUTOTHROTTLE_START_DELAY = 2.0
AUTOTHROTTLE_MAX_DELAY = 60.0
HTTPCACHE_ENABLED = True
```

The values are project limits, not claims about what the site permits. Add an identifying user agent with a
purpose/contact URL. Do not mimic a browser or use stealth tooling.

### 10.3 What not to add

- Playwright/headless Chromium: the selected source is server-rendered.
- anti-bot/stealth plugins: incompatible with the compliance posture.
- Scrapy clusters, Kafka, Airflow, Celery, or Kubernetes: unjustified for a local bounded job.
- a search service or vector database for matching: PostgreSQL aliases and `pg_trgm` are sufficient.
- generic article extraction: it increases the chance of collecting vendor prose/reviews that should be
  excluded.
- ML record-linkage packages before a labeled set exists: deterministic reviewable rules are safer here.
- marketplace APIs/frontends in the first two milestones: unstable, noisy, high-churn, and low-value for
  canonical tea facts.

## 11. Robots, source approval, and polite fetching

### 11.1 Mandatory per-run preflight

Before fetching product pages:

1. fetch `robots.txt` using the same identifying user agent;
2. record status, final URL, bytes hash, retrieval time, and applicable product token;
3. validate every frontier URL with the chosen parser;
4. save the robots snapshot in the run evidence;
5. abort on parser errors, redirects off host, ambiguity, or any newly disallowed path;
6. apply a stricter project policy: unexpected non-200 robots responses require manual review rather than
   silently allowing the crawl;
7. re-check source approval/terms metadata before apply, not just before fetch.

`urllib.robotparser` can answer `can_fetch`, delay/rate, and sitemap questions, but using its blocking
`read()` directly would bypass the plan's HTTPX timeout/UA/telemetry policy. If the fixed-URL spike keeps it,
fetch the file through the controlled HTTP client and pass the lines to `parse()`. For real traversal,
Scrapy's robots middleware and maintained default Protego parser are preferable.

### 11.2 URL and response safety

Enforce:

- HTTPS only;
- exact approved host allowlist;
- redirects remain on an approved host;
- URL schemes other than HTTPS are rejected;
- query parameters are stripped/allowlisted; never crawl disallowed sort/filter/search/tracking variants;
- maximum response and decompressed sizes;
- HTML/XML content types only where expected;
- bounded connect/read/total timeouts;
- bounded retries for transient statuses only, honoring `Retry-After`;
- no authentication, cart, account, checkout, review submission, or session cookies;
- no JavaScript execution;
- no vendor image downloads;
- a global request budget and per-run kill switch.

### 11.3 Source registry

Keep a reviewed registry with at least:

```yaml
id: artoftea
base_url: https://artoftea.ru
status: pilot-approved
robots_url: https://artoftea.ru/robots.txt
robots_checked_at: ...
robots_sha256: ...
terms_url: ...
terms_checked_at: ...
owner_decision: ...
allowed_path_prefixes: [...]
forbidden_path_prefixes: [...]
request_policy: { concurrency: 1, delay_seconds: 2 }
public_fields: [names, type, country, region, cultivar]
forbidden_public_fields: [description, reviews, images, price, availability]
```

Robots and terms can change; hashes are evidence, not permanent permission.

## 12. Art of Tea parser risks and required design

The sampled HTML contains duplicated product attributes in visible cards and hidden specification blocks,
as well as customer reviews later in the same document. Some label/value structures include unrelated
values such as weights or numeric attributes. Generic `.text()` extraction over broad containers is unsafe.

Requirements:

- parse explicit label/value pairs by normalized label, not element position;
- maintain a source-label mapping such as `Тип`, `Регион сбора чая`, `Год сбора`, and
  `Страна производства товара`;
- treat unknown labels as evidence, not silently map them;
- never parse `.review-list__comment` or generic description containers;
- use `product_id` as the preferred source external ID and canonical URL as a secondary key;
- separate title cleanup from the raw title;
- reject a page with no product ID, no title, or no approved tea type;
- filter sitemap paths to approved tea roots and reject accessory/category ambiguity;
- record selector/parser version on every record;
- fail the run if required-field success rates drop or page fingerprints change materially.

Do not trust one fixture. Create minimized fixtures for:

- each tea category/type;
- product with and without harvest year;
- product with variants;
- out-of-stock/archived product;
- redirected/renamed slug;
- missing/duplicated specification blocks;
- non-tea sitemap product;
- page containing reviews;
- malformed/partial HTML;
- 404/429/5xx and wrong content type.

The parser should produce explicit rejects with reason codes, not partially valid canonical rows.

## 13. Normalization contract

Use three representations, not one destructive “normalized name”:

1. `raw_value`: exact source field for private evidence.
2. `db_name_norm`: PostgreSQL's actual `lower(f_unaccent(value))` result used for catalog candidate queries.
3. `match_norm`: a versioned, reviewable transformation that may remove packaging, year, vendor-grade, and
   punctuation noise for candidate generation.

Do not attempt to reproduce PostgreSQL `unaccent` approximately in Python and assume equality. Let the
import/matching query compute the production normalization, or run shared golden vectors against both
implementations.

Golden vectors must cover:

- NFC/NFD and combining tone marks;
- NBSP and Unicode whitespace;
- hyphens, apostrophes, Chinese punctuation, and brackets;
- `ё/е`, `й`, and case folding;
- joined/spaced Palladius forms;
- tone-numbered and tone-marked pinyin;
- simplified/traditional Hanzi where actually present;
- years, weights, grades, roast levels, and vendor phrases;
- negative cases where cleanup would collapse different teas.

`pypinyin` rules:

- run it only on actual Hanzi;
- do not expect it to normalize Art of Tea's Cyrillic transliterations into Latin pinyin;
- generated names are derived candidates, never source-attested facts;
- record library/version/style and mark the alias non-primary/unverified;
- do not treat generic transliteration (`anyascii`) as Palladius/pinyin entity resolution;
- keep curated exceptions as versioned data with tests, not scattered code conditionals.

## 14. Conflict policy

Use field-specific decisions, not a single source ranking that overwrites whole rows.

| Field | Policy |
|---|---|
| Canonical names | Existing human-verified primary wins; add reviewed aliases without replacing it |
| Type | Map source taxonomy to TeaTiers vocabulary; disagreement requires review; type is not stable identity |
| Country | Accept explicit factual value; conflict requires review |
| Region | Preserve source granularity; do not concatenate incompatible hierarchies blindly |
| Cultivar | Import only when explicitly stated and clearly distinguished from product/marketing name |
| Oxidation | Do not infer exact percentages from broad tea type |
| Brand/vendor | Do not put the shop name in `brand` for unbranded loose tea; keep seller on observation |
| Harvest/year/grade/roast | Observation/sample-level by default, not canonical identity |
| Flavor | Source prose/tags are enrichment hints only; never direct-map free text to fixed 0-5 dimensions |
| Description | Own-authored or generated output only, with overlap guard; no vendor/review text |
| Images | Absent unless independently licensed, reviewed, downloaded/validated, and served first-party |

“Curated beats scraped” is a useful default, but every retained source claim should remain visible to the
reviewer. Silent fill-null or overwrite behavior hides contradictions.

## 15. Public-content and provenance gates

The decision #131 guard is necessary but incomplete. Checking only
`tea_description.source LIKE 'scrape:%'` misses copied text in names, blurbs with a mislabeled source,
embedded source records, fixtures, generated JSON, or image URLs.

Gate the final public export and resulting catalog:

### Structural

- approved schema version;
- deterministic sort/order and canonical JSON serialization;
- no unknown fields;
- no HTML or raw response bodies;
- every source-derived claim has source URL, retrieval time, source record ID, and import run;
- every new canonical entity has a stable public identity and at least one reviewed name;
- all aliases have correct locale and source/derivation metadata.

### Expression/media

- no vendor product descriptions, reviews, instructions, marketing paragraphs, or HTML;
- no Art of Tea image/sitemap URLs in public `tea_image`;
- no remote vendor hotlinks;
- every public image has an allowlisted license, source URL, content hash, and first-party immutable URL;
- generated description passes the existing source-overlap/repetition checks against any transient input.

### Verification and conflicts

- automated records are `unverified`;
- only explicit reviewer action promotes `verified`;
- existing verified values cannot be replaced without a recorded conflict decision;
- fuzzy matches cannot auto-merge during the pilot;
- every rejected/ambiguous record has a reason.

### Idempotency and reproducibility

- same source snapshot + same parser/config -> byte-identical parsed output;
- applying the same approved patch twice -> zero canonical changes on the second application;
- re-fetch with unchanged content hash -> no reparse unless parser version changed;
- parser version change -> reproducible before/after diff;
- blank database restore -> stable catalog identities according to the chosen recovery contract.

## 16. Raw prose policy

The first cut should **not store raw vendor prose in the server at all**.

Preferred v1:

- extract explicit factual fields;
- discard reviews and images without retaining them;
- optionally retain response bodies only in a local ignored cache for the active run;
- generate any TeaTiers description from structured facts, not copied prose;
- keep current overlap guards as defense in depth.

If later evaluation proves raw prose materially improves generated descriptions:

- treat it as quarantined transformation input, not catalog data;
- keep it local or in access-controlled private storage;
- record source, hash, purpose, and deletion deadline;
- define short automatic retention;
- never expose it through API, logs, diagnostics, backups, or public artifacts;
- exclude customer reviews entirely;
- run overlap checks against the exact input before publishing output;
- delete the input after the reviewed result and audit evidence no longer require it.

A new permanent `source_text` column in the production `tea` aggregate is the wrong default.

## 17. Catalog-miss integration

The miss log is a prioritization signal, not an automated crawl frontier.

Safe workflow:

1. export aggregate high-frequency recent misses;
2. manually classify typo, alias, spam/private text, existing tea, real missing tea, or product-specific query;
3. resolve typos/aliases without scraping where possible;
4. map approved missing teas to one or more allowed source product URLs;
5. enqueue those source URLs/IDs in the pilot;
6. after import, confirm the original miss resolves and record the outcome.

Never put arbitrary miss text into a site-search URL, crawler URL, shell command, or filename. Keep the
existing retention/privacy policy aligned with the separate privacy review.

## 18. Import rollback and audit

Every applied import needs:

- immutable `run_id` and approved-patch hash;
- source/robots/config/parser hashes;
- expected pre-import catalog revision;
- exact insert/update/alias/claim operations;
- reviewer identity/time and match decisions;
- resulting canonical IDs;
- post-import catalog revision;
- reversible operations or a tested point-in-time/database restore procedure.

Rollback must account for client references. Deleting or reusing a canonical ID can orphan or misdirect an
Android `catalogTeaId`. Preferred rollback behavior is usually:

- deactivate/retract a bad canonical record or alias;
- preserve its stable identity and audit history;
- detach wrong source matches;
- restore prior field decisions;
- avoid ID reuse and hard deletion once a record may have been returned to clients.

Test rollback on a database containing Android-visible IDs before the first bulk apply.

## 19. Security and operational controls

- crawler runs without production credentials;
- only approved hosts can be requested, including after redirects;
- no browser, JavaScript, authentication, cookies, checkout, or write requests;
- GET/HEAD only;
- bounded URL count, response size, decompression, timeouts, retries, and run duration;
- cache files have restrictive local permissions and are Git-ignored;
- logs exclude response bodies, descriptions, reviews, and credentials;
- JSONL is preferred to CSV; if CSV is produced for manual review, neutralize spreadsheet formula prefixes;
- dependency lock/hashes and vulnerability scan apply to the tool;
- run stats and rejects are retained, but proprietary bodies follow the short-retention policy;
- cancellation leaves a resumable/consistent staging state and never a partial canonical import.

## 20. Test strategy

### Unit

- label/value extraction and required-field rejection;
- title cleanup with positive and negative vectors;
- external-ID/canonical-URL extraction;
- tea-category allowlist;
- forbidden review/description/image extraction;
- source taxonomy mapping;
- claim rights classification;
- pypinyin-derived alias metadata;
- public-export gate.

### Contract/golden

- captured minimized HTML fixtures for every supported page shape;
- robots fixtures, including changed/disallowed/unreachable cases;
- sitemap index/shard fixtures containing tea and non-tea products;
- Python/PostgreSQL normalization golden vectors;
- deterministic JSONL/patch serialization;
- parser-version reparse diffs.

### Integration

- local fake HTTP server for redirect, timeout, 429, `Retry-After`, 5xx, large body, wrong MIME, and
  off-host redirect;
- current-catalog candidate generation using PostgreSQL `f_unaccent` and `pg_trgm`;
- dedicated importer dry-run/apply/no-op-second-run;
- conflict protection for verified curated rows;
- transaction rollback on a mid-import failure;
- source record updated with changed content but stable external ID;
- same tea observed from two source records without canonical duplication.

### Evaluation corpus

Label at least 100 candidate pairs before enabling any fuzzy auto-attach. Keep hard negatives. Report:

- exact/source-ID match precision;
- fuzzy candidate recall at top 1/5/20;
- auto-attach precision, if any;
- duplicate-candidate rate;
- false-merge count;
- reviewer time per accepted record.

For the pilot, the acceptable false-merge count is zero; ambiguous matches stay unpublished.

## 21. Observability and abort thresholds

Each run should report:

- frontier URLs discovered/allowed/disallowed/rejected;
- fetch statuses, retries, bytes, cache hits, and request rate;
- pages parsed/rejected by reason;
- required-field completeness by field and category;
- unique/stable external IDs and URL changes;
- new/existing/ambiguous/rejected match counts;
- canonical additions, alias additions, conflicts, and no-ops;
- public-gate failures;
- source/provenance completeness;
- duration and parser version.

Fail the run, rather than degrade silently, when:

- robots/source approval changed;
- off-host redirects appear;
- product-ID/title/type extraction falls below the pilot baseline;
- page fingerprint changes materially;
- duplicate external IDs map to different canonical URLs unexpectedly;
- forbidden fields appear in public output;
- the matcher proposes an unexpected surge of new canonical teas;
- any verified curated value would be overwritten without review.

Thresholds must be established from the fixed-URL pilot; do not invent them in advance.

## 22. Phased implementation plan

### Phase 0 — correct prerequisites

1. Amend the implementation interpretation of decision #131: `dedup_key` is a guard, not entity resolution.
2. Decide stable public catalog identity/recovery.
3. Define source-record, claim, match-decision, and import-run schemas.
4. Build the dedicated strict importer and public export gate before the crawler.
5. Fix documentation drift: crawling is no longer categorically banned, and `CatalogSeeder` is not an
   update-capable upsert.

Exit: a hand-authored source record can move through review/dry-run/apply/rollback without scraping.

### Phase 1 — fixed-URL parser pilot

1. Register/approve Art of Tea.
2. Select 30-50 pages covering every category/page shape plus non-tea controls.
3. Use HTTPX + selectolax with local evidence cache.
4. Create minimized fixtures and extraction tests.
5. Produce source records/claims only; do not mutate canonical data.

Exit: deterministic parse, zero review/image leakage, complete evidence, measured reject/completeness rates.

### Phase 2 — matching/review pilot

1. Generate candidates against a catalog snapshot using production PostgreSQL normalization/search.
2. Hand-label at least 100 pairs, including hard negatives.
3. Record decisions and assess canonical-vs-product distinctions.
4. Apply a small reviewed patch, preferably demand-driven misses.
5. Prove second-run no-op and rollback.

Exit: zero false merges in applied records, 100% provenance completeness, stable IDs preserved.

### Phase 3 — polite Art of Tea traversal

1. Move to Scrapy core facilities.
2. Start from the live sitemap, filtered to explicit tea-category allowlists.
3. Enforce per-run robots/source preflight, budgets, throttle, cache, restartability, and stats.
4. Publish only reviewed patches in bounded batches.

Exit: repeatable bounded source run with parser-drift alarms and sustainable review load.

### Phase 4 — second source only if justified

Evaluate H2O after measuring what Art of Tea and Wikidata still miss. Add it only when it contributes
material canonical facts/aliases and the same controls are implemented. Multi-source field provenance must
already exist.

Marketplaces remain out of scope unless a concrete high-demand gap cannot be filled by approved specialist
or open sources.

## 23. Acceptance checklist before first public bulk import

- [ ] Source registry entry manually approved and current.
- [ ] Robots snapshot/hash and every requested URL validated.
- [ ] Crawler identifying UA, one-domain concurrency, delay, budgets, timeout, retry, and kill switch tested.
- [ ] Source external ID and canonical URL strategy proven across redirects/renames.
- [ ] Sitemap filter excludes accessories and service/query paths.
- [ ] Parser fixtures cover all supported shapes and forbidden review/prose containers.
- [ ] Raw response/title/claim/match normalizations are distinct and versioned.
- [ ] `dedup_key` is not used as source identity or automatic cross-script proof.
- [ ] Fuzzy matching is review-only until labeled precision supports automation.
- [ ] Vendor year/grade/roast/pack/SKU remain observations, not canonical identity.
- [ ] Field-level provenance and conflict decisions survive import.
- [ ] Import DTOs require explicit source/status and reject unknown fields.
- [ ] Startup seeder is not the bulk mutation path.
- [ ] Stable public IDs and blank-database/disaster recovery behavior are tested.
- [ ] Automated records cannot become `verified` without reviewer action.
- [ ] Public artifact contains no vendor prose, reviews, HTML, or unlicensed/hotlinked images.
- [ ] Same patch applied twice is a no-op.
- [ ] Rollback/retraction preserves client identity references.
- [ ] Metrics, rejects, and abort conditions are visible.
- [ ] Android search/detail and original miss queries resolve correctly after import.

## 24. Concrete decisions to amend or add

Because `context/decisions.md` is append-only, do not rewrite #131. Add a later correction when implementation
is authorized:

1. **Identity correction:** existing `dedup_key` remains a uniqueness guard; source-record ID plus reviewed
   alias/external-ID matching determines canonical association.
2. **Ingestion boundary:** local observation staging and an explicit strict importer replace direct seed
   import; the API service never crawls.
3. **Tool correction:** HTTPX/selectolax for the fixed-page pilot; Scrapy core for traversal.
4. **Provenance:** field-level source claims and match/import audit are mandatory before multi-source apply.
5. **Raw text:** no server-side raw vendor prose in v1; facts-only extraction and local short-lived cache.
6. **Verification:** imported source observations and new candidates default unverified; human promotion only.
7. **Public identity:** canonical IDs must survive catalog rebuild/recovery and existing Android references.

## 25. Final recommendation

Proceed with Art of Tea, but first build the **review/import safety spine**, not the crawler. The smallest
correct deliverable is:

```text
strict source-record schema
+ source registry and robots preflight
+ 30-50 fixed Art of Tea fixtures/pages
+ local evidence cache
+ production-Postgres candidate generation
+ explicit human match decisions
+ deterministic dry-run patch
+ strict transactional importer
+ full public-content/provenance gate
+ stable-ID and rollback test
```

Only after that should the project traverse the sitemap with Scrapy. This keeps the system small while
reusing mature open-source crawl controls and prevents the expensive failure modes: cross-script duplicates,
vendor-lot explosion, silent curated-data overwrites, provenance loss, expression leakage, and broken client
catalog references.
