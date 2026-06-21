# TeaTiers scraper implementation-readiness review

Date: 2026-06-21

Status: separate focused review of the scraper/ingestion workstream after decision #136 and commits
through `21fda80`.

Audited baseline: clean tracked `main` at `21fda80`. The concurrent untracked general review
`2026-06-21-post-scrape-foundation-architecture-review.md` was treated as analysis input, not as
implemented behavior.

Scope boundary: this review covers source policy, fetch/preflight, parser contract, staging, identity
matching, human review, canonical apply, provenance, public-content gates, lifecycle, tests, and operating
model. It does not repeat the full Android/release/infra review except where scraper output creates a
client-facing identity or deployment risk.

## 1. Verdict

There is currently **no scraper implementation** in the repository.

What exists is a substantial server-side **scrape ingestion foundation**:

- Flyway V7-V9 catalog identity, staging, review, provenance, and alias tables;
- services for source registration, staging observations, matching, review, and canonical writes;
- integration tests for several happy paths and defensive cases;
- judged research and a detailed target design for the first Art of Tea source.

What does not exist:

- no `scraper/` module or CLI;
- no HTTP fetcher, redirect/host policy, robots preflight, cache, or request budget;
- no Art of Tea parser;
- no HTML fixtures or parser golden tests;
- no source registry data for Art of Tea;
- no deterministic JSONL/patch format;
- no operator command that connects parser output to the Spring services safely;
- no dependency lock/license gate for the scraper stack.

Readiness is therefore split:

| Activity | Verdict |
|---|---|
| Build an isolated 30-50 fixed-page Art of Tea fetch/parser spike that emits local JSONL only | **Conditional GO** after implementing the fetch-safety checklist in this review |
| Stage parser output into the server database | **NO-GO** until run/robots/dry-run/revision invariants are fixed |
| Approve merge/create into the public catalog | **NO-GO** until stable identity, provenance, review auth, and lifecycle blockers are fixed |
| Traverse the full sitemap | **NO-GO** until the fixed-page pilot is measured and then moved to Scrapy controls |
| Add a second source | **NO-GO** until one-source idempotency, conflict handling, rollback, and review capacity are proven |

The correct next deliverable is not a crawler. It is a deterministic fixed-URL parser plus a corrected,
non-public import state machine.

## 2. Source decisions that remain valid

The following run-20/run-21 conclusions remain appropriate:

- first source: `artoftea.ru`;
- `tea.ru` remains excluded because its catalog/product paths are disallowed;
- public output is facts-only: names, mapped type, country/region, cultivar, bounded oxidation, and a
  carefully reviewed brand field;
- vendor prose, reviews, preparation instructions, marketing text, prices, availability, and images do
  not enter the public catalog;
- the first parser is local/operator-run, not an API request path and not a production daemon;
- HTTPX + selectolax + Protego is proportionate for fixed approved pages;
- Scrapy should replace hand-built traversal controls when sitemap traversal begins;
- `pypinyin` and the Palladius bridge generate candidates only; they never prove canonical identity;
- `dedup_key` is a uniqueness guard, not a source identity or cross-script equivalence proof;
- fuzzy/transliteration matches stay review-only during the pilot;
- source observations, canonical tea identity, and field provenance are separate concerns;
- a human decision is required before a scraped record creates or modifies a canonical tea.

No new source-selection research is needed before the first parser spike.

## 3. Readiness by pipeline stage

| Stage | Current implementation | Readiness | Main gap |
|---|---|---:|---|
| Source policy | Decisions #129/#131/#136 + run ratings | 70% | Policy is not encoded into an executable registry/preflight |
| Dependency/tooling | Version decisions only | 10% | No module, lockfile, hashes, or license check |
| URL frontier | None | 0% | No fixed allowlist, sitemap filter, or canonicalization |
| Robots/ToS preflight | Columns + ToS flag | 25% | Robots evidence is never required to start/ingest/apply |
| Fetch safety | None | 0% | No redirect, host, byte, MIME, timeout, retry, or budget enforcement |
| HTML parser | None | 0% | No selectors, label mapping, fixtures, rejects, or drift detection |
| Facts schema | Kotlin DTO + guard | 60% | No portable schema/version; incomplete validation |
| Evidence/staging | V8 tables + importer | 55% | `raw_evidence` unused; run association and revisions broken |
| Identity matching | Tiered matcher + tests | 55% | Ambiguity hidden; approved revisions do not requeue |
| Human review | Service + optional controller | 40% | Self-reported actor, unauthenticated HTTP, incomplete evidence view |
| Canonical apply | Service + tests | 45% | Merge provenance and confirmed aliases incorrect |
| Stable lifecycle | UUID/status/legacy tables | 30% | Seed/client/numeric compatibility and search visibility incomplete |
| Public-content gate | DTO shape + tests | 45% | No final export scan; DB/API-wide expression/media gate incomplete |
| Rollback/recovery | Schema intent only | 15% | No retract/merge/apply rollback workflow or rebuild proof |
| Metrics/abort policy | Columns only | 10% | Counters unused; no thresholds or run report |

## 4. Blocking findings before database staging

### SCR-P0-1. The repository has no executable scraper boundary

Evidence:

- no scraper package/module, `pyproject.toml`, lockfile, or parser source exists;
- HTTPX is present only as an OCR-sidecar test dependency;
- no code fetches `robots.txt`, Art of Tea HTML, or a sitemap;
- no source registry row is created by migrations/config/operator tooling;
- no JSON Schema or versioned interchange contract connects Python output to `SourceObservation`.

Impact: references to “the scraper” currently mean a design, not a runnable artifact. Attempting to wire
ad-hoc scripts directly to production tables would bypass the reviewable contract the foundation is meant
to provide.

Required first boundary:

```text
approved fixed URL list
  -> preflight and bounded fetch
  -> immutable local evidence envelope
  -> source-specific parser
  -> versioned facts-only JSONL
  -> offline validation/report
```

No database credentials or production API token belong in this module.

### SCR-P0-2. Robots is documented as a hard gate but is optional metadata

Evidence:

- `source_site` and `import_run` contain robots fields;
- `SourceSite.importAllowed()` checks only `active && termsSignedOffAt != null`;
- `CatalogImportService.startRun()` accepts no robots snapshot or decision;
- `ingest()` does not require `robotsDecision='allow'`;
- tests create eligible sites without any robots evidence and expect import to succeed.

Impact: a run can be labeled legitimate even when robots was never fetched, was stale, failed closed, or
disallowed the exact URL.

Required:

1. Fetch robots before any product request.
2. Store URL, fetched time, HTTP status, body hash, parser/version, user agent, and decision.
3. Bind the decision to the exact source host and run.
4. Reject start/ingest when robots is absent, stale beyond the run, unavailable, or disallows a URL.
5. Re-evaluate redirects and every requested URL, not only the initial frontier.
6. Test allow, disallow, wildcard, longest-match, unreachable, redirect, and changed-policy cases.

### SCR-P0-3. Run identity and `dryRun` do not control behavior

Evidence:

- `ingest(importRunId, observation)` never loads the run;
- it does not verify run state, source-site equality, parser version, or completion time;
- an Art of Tea observation can reference another source's valid run ID;
- `dryRun` is stored but never read by matcher, reviewer, or canonical writer;
- canonical approval can publish records staged by a dry run;
- `finishRun()` silently ignores a missing ID and accepts arbitrary status until the database rejects it.

Impact: audit fields are not authorization/state invariants. A dry-run label provides no safety.

Required:

- load and lock the run on every transition;
- require `run.sourceSiteId == observation.sourceSiteId`;
- require a valid state transition and exact parser/tool version;
- separate `dry_run` report generation from `apply_allowed` explicitly;
- make canonical apply reject records originating from dry/blocked/failed runs;
- use enums/state transition methods and checked database constraints;
- make missing/mismatched runs fail loudly.

### SCR-P0-4. Source corrections cannot pass through review after an approval

Evidence:

- a changed facts hash sets `source_record.status='reparse_pending'`;
- `IdentityMatchService.proposeFor()` returns an earlier approved decision instead of creating a fresh
  decision for the changed content;
- decisions are linked to a mutable `source_record`, not an immutable observation revision/content hash;
- no test covers `approve -> facts change -> new review -> apply correction`.

Impact: parser bug fixes and changed source facts stop at `reparse_pending`. Audit history cannot show which
exact content the operator approved.

Required:

- make source observations immutable revisions;
- bind normalized candidates and match decisions to revision ID/content hash;
- allow one decided history plus one pending decision for a later revision;
- reject stale approval if the candidate hash differs from the reviewed hash;
- preserve unchanged re-import as a no-op.

### SCR-P0-5. Source identity changes are not reconciled correctly

The current lookup prefers `(site, externalId)`, falling back to `(site, canonicalUrl)`, but it does not
update both identity fields consistently:

- same external ID at a renamed URL finds the record but retains the old canonical URL;
- same canonical URL with a newly discovered external ID retains the old/null external ID;
- two records created before the source exposes a stable ID have no merge/reconciliation flow;
- redirects/canonical link changes are not recorded as identity history.

Required:

- model source identity as a stable source key with URL aliases/history;
- update canonical URL only after allowed-host validation and record the old URL;
- attach a newly discovered external ID through an explicit collision check;
- surface identity collisions for operator review, never overwrite silently;
- test slug rename, redirect, external-ID addition/change, and duplicate historical URLs.

## 5. Blocking findings before canonical publication

### SCR-P0-6. Approved merge writes incomplete provenance

`applyApprovedMerge()` may fill `originCountry`, `region`, `cultivar`, `brand`, or oxidation, then calls
`writeProvenanceAndAliases(... includeScalarFields=false)`. That suppresses every scalar provenance row,
including values just added by the merge.

The current provenance table also stores only `field_name` and source links, not the claimed value,
selection/rejection decision, reviewer, or supersession state. Multiple rows are unconstrained.

Required:

- record each source claim with its typed value and observation revision;
- record accepted/rejected/conflicting decisions;
- write the selected claim in the same transaction as the canonical field;
- never create a canonical value without a selected claim;
- add uniqueness/current-selection constraints and conflict tests.

### SCR-P0-7. Human-approved aliases remain unverified library output

Every scraped name is written as `origin='library_derived', verified=false`, even after a human approves
the identity decision. `IdentityAliasService.addAuthoritative()` exists but is not called by review.
The integration test explicitly asserts all approved scraped aliases remain unverified.

Impact: later runs cannot reuse the human-confirmed cross-script identity as Tier 0, so the review system
repeats work and may propose inconsistent matches.

Required:

- distinguish source-attested name, generated transliteration, and identity-confirmed alias;
- promote only the aliases explicitly confirmed by the operator to `human_confirmed, verified=true`;
- keep pypinyin/Palladius generated candidates derived/unverified;
- record reviewer, decision, and source observation for the promotion.

### SCR-P0-8. Stable client identity is not yet safe for imported rows

The server added `public_id`, lifecycle status, and a legacy map, but:

- curated seed records do not contain stable UUIDs and receive new random UUIDs on blank rebuild;
- Android ignores `publicId` and still stores/calls numeric IDs;
- numeric detail bypasses the legacy map;
- merged/retracted rows remain searchable;
- the planned Room v7 catalog ref still uses the numeric server ID.

Any public scraped import increases the number of client-visible identities affected by this incomplete
contract. Fix the end-to-end UUID and lifecycle path before publication.

### SCR-P0-9. Operator review is not an authenticated audit boundary

The controller is disabled by default, but when enabled every list/approve/merge/reject route is
unauthenticated. `reviewer` is caller-supplied query text. There is no reason, source evidence, revision
hash, or authenticated actor in the decision.

Prefer a local operator CLI/profile. If HTTP remains, use a separate private listener plus strong auth,
structured request bodies, authenticated actor identity, and production tests proving it cannot appear on
the public listener.

## 6. Matching and canonical-model findings

### SCR-P1-1. Matching hides multiple plausible identities

Authoritative and exact queries return lists, but the matcher selects the first row. Trigram matches retain
only one maximum. Multiple exact owners, equal candidates, and near ties are not surfaced.

Return a candidate set containing:

- tea public UUID and lifecycle;
- matched name/alias and locale;
- tier and score;
- type/origin/brand differences;
- source/reviewer status of the alias;
- conflict flags.

Multiple authoritative owners must be a data-integrity error. Fuzzy/transliteration results remain review
suggestions regardless of score.

### SCR-P1-2. Type handling silently converts mapping failures to `OTHER`

`FactsOnlyGuard` does not validate `facts.type`, and canonical creation maps unknown text to `TeaType.OTHER`.
This hides parser/taxonomy drift and can mint incorrect catalog rows.

Define a source-specific taxonomy map. Unknown labels are explicit rejects or review tasks. The parser
fixture corpus must include every supported category and negative accessory/non-tea examples.

### SCR-P1-3. Brand/vendor semantics remain confused

The matcher claims brand/vendor conflict protection but checks only `brand`. The writer ignores `vendor`
and may copy `brand` onto the canonical tea. For specialist shops, the shop is usually a seller, not the tea
manufacturer or canonical entity.

Keep seller, SKU, harvest, year, grade, roast, pack size, price, and availability on the observation/product
record. Promote brand only through an explicit mapping/decision. None of these fields should establish
canonical tea identity automatically.

### SCR-P1-4. One canonical row cannot express unresolved field conflicts

Fill-null merge is safe from overwriting but insufficient for multi-source curation. If source A says
Fujian and source B says Guangdong, silently retaining the first value loses the conflict. A value-bearing
claim table plus selection decision is required before adding the second source.

### SCR-P1-5. Stored normalization hints are diagnostic, not reproducible production keys

`CrossScriptKeys.normalizeHint()` correctly documents that it is not PostgreSQL `f_unaccent`. The matcher
uses database normalization for actual lookups, which is good. The future Python parser must not recreate
or trust its own approximate `name_norm` as production identity.

Emit raw normalized candidates and derivation metadata; let PostgreSQL calculate match normalization. Add
golden vectors spanning Unicode composition, tone marks, Cyrillic, Hanzi, punctuation, whitespace, years,
weights, and hard negative tea pairs.

## 7. Parser/fetch design review

### 7.1 Recommended module boundary

Create one isolated Python module when implementation begins:

```text
scraper/
  pyproject.toml
  uv.lock
  src/teatiers_scraper/
    cli.py
    policy.py
    fetch.py
    evidence.py
    schema.py
    sources/artoftea.py
  tests/
    fixtures/artoftea/*.html
    test_policy.py
    test_fetch.py
    test_artoftea.py
    test_determinism.py
```

Initial dependencies, reverified and locked in decision #136:

- `httpx==0.28.1`;
- `selectolax==0.4.10`, Lexbor backend only;
- `Protego==0.6.1`;
- `pypinyin==0.55.0` for derived Hanzi candidates only;
- pytest and a local fake HTTP server for tests;
- hash-locked transitive dependencies and a license report in CI.

Do not add Playwright, Selenium, a browser, or Scrapy to the fixed-page spike. Art of Tea is
server-rendered. Adopt Scrapy when bounded sitemap traversal begins so robots middleware, AutoThrottle,
retry/cache/stats, and restartability are reused rather than rebuilt.

### 7.2 Fetch policy

The fetcher must enforce before opening a response body:

- HTTPS only;
- exact approved host allowlist, including redirects;
- GET/HEAD only;
- no cookies, authentication, account/cart/checkout/review endpoints, or JavaScript;
- allowed path prefixes plus forbidden service/query patterns;
- query-parameter allowlist/stripping;
- identifying contactable user agent;
- concurrency 1 for the pilot and at least 1-2 seconds between requests;
- bounded connect/read/total timeouts;
- transient retry only, honoring `Retry-After`;
- maximum compressed and decompressed bytes;
- expected HTML/XML MIME types;
- per-run request/byte/time budget and kill switch;
- fail closed when robots cannot be fetched or parsed.

Use a local fake server to test off-host redirects, redirect loops, 429, retry-after, timeouts, 5xx, wrong
MIME, oversized/chunked/decompression-bomb responses, and cancellation.

### 7.3 Evidence envelope

For every request, record:

- source code and run ID;
- requested and final canonical URL;
- redirect chain;
- retrieval time;
- HTTP status and content type;
- response/content hash;
- robots snapshot hash/decision;
- parser/tool/config versions;
- cache hit/miss;
- parse outcome or reject reason.

Raw HTML may live in a local ignored short-retention cache for parser debugging. It must not enter Git,
the public API, application logs, Android backups, or permanent production storage by default.

### 7.4 Art of Tea parser contract

Parse explicit normalized label/value pairs, not broad container text or element positions. Maintain an
allowlisted mapping for labels such as type, region, harvest year, and country. Unknown labels are captured
as evidence/rejects, not guessed into schema fields.

The parser must explicitly exclude:

- description/marketing containers;
- customer reviews;
- preparation instructions;
- price/availability;
- image URLs;
- weight/pack variants from canonical identity;
- accessories and non-tea sitemap products.

Reject a page without a stable external product ID, canonical URL, nonblank title, or mapped tea category.
Do not emit partial canonical facts after a required-field failure.

Fixtures must cover:

- every supported tea category;
- year present/absent;
- variants and duplicated hidden/visible attributes;
- out-of-stock/archived product;
- renamed/redirected slug;
- missing/duplicated specification blocks;
- review content on the page;
- accessory/non-tea product;
- malformed/partial HTML;
- unknown labels;
- title containing vendor, weight, grade, year, and punctuation noise.

### 7.5 Deterministic output

The fixed-page tool emits versioned UTF-8 JSONL, one observation per line, sorted deterministically. The
same source snapshot + parser/config version must produce byte-identical output. Include schema version,
source identity, raw factual values, derivation metadata, and evidence hash; do not include prose or images.

Validate the output against a portable schema before it reaches Kotlin. Kotlin must reject unknown fields
rather than silently ignoring schema drift at the import boundary.

## 8. Test review

Current tests prove useful but narrow behavior:

- ToS/active gating;
- basic facts-only length/markup checks;
- identical source-record idempotency;
- changed facts mark `reparse_pending`;
- several exact/trigram/transliteration proposals;
- human approval required for canonical write;
- unverified scrape status;
- oxidation bound hardening;
- basic facts-only public API checks.

Missing release-blocking tests:

- robots allow/disallow/fail-closed and run binding;
- dry-run cannot apply;
- mismatched source/run and finished/failed run rejection;
- approved revision followed by changed observation and new approval;
- stale-decision hash rejection;
- renamed URL/external-ID reconciliation;
- concurrent ingest/propose/approve races;
- multiple exact/authoritative candidates and ties;
- retracted/merged target rejection;
- scalar provenance on merge;
- rejected conflict claim retention;
- human-confirmed alias promotion;
- deterministic second apply no-op;
- transactional rollback after a mid-apply failure;
- blank DB rebuild retaining public UUIDs and legacy resolution;
- final public artifact scan for prose/reviews/HTML/vendor URLs/images;
- parser fixtures, network safety, drift fingerprints, and run abort metrics.

Several current tests encode incorrect expectations and must change, especially “approved scraped aliases
are all unverified” and “ToS/active alone is enough to start a run.”

## 9. Operating model

Use two deliberately separate executables:

1. **Fetcher/parser CLI**: no production DB credentials; emits evidence + facts JSONL.
2. **Importer/reviewer CLI**: validates signed/hashed JSONL, stages it, generates candidate reports, records
   authenticated decisions, and applies a deterministic patch.

For the pilot:

- operator manually supplies the fixed approved URL list;
- catalog misses are prioritization text only, never interpolated into URLs or shell commands;
- fetch output is reviewed before staging;
- matching is performed against a recorded catalog revision;
- application requires an expected pre-import revision and patch hash;
- every apply produces a machine-readable report and rollback/retraction plan;
- no scheduler or production daemon exists;
- no public Android contribution flow exists.

The optional Spring review controller is not necessary for this operating model. A CLI over a private
connection is simpler and safer.

## 10. Required metrics and abort conditions

Every run report should include:

- URLs requested/allowed/disallowed/rejected;
- redirects and off-host rejects;
- statuses, retries, bytes, timings, and cache hits;
- parser success/reject counts by reason and category;
- field completeness and unknown labels;
- external-ID and canonical-URL uniqueness;
- unchanged/changed/new source revisions;
- exact/fuzzy/transliteration candidate counts;
- ambiguous/conflict/new/rejected decisions;
- canonical inserts, field selections, aliases, and no-ops;
- provenance completeness;
- public-gate failures;
- parser/config/source-policy hashes.

Abort rather than degrade silently when:

- robots/ToS/source approval is missing or changed;
- an off-host redirect occurs;
- required-field extraction drops materially from the pilot baseline;
- page fingerprints or duplicate external-ID behavior change;
- an unknown category/label surge appears;
- a verified canonical value would be overwritten;
- a source correction lacks a new review;
- any vendor prose/review/image URL reaches public output;
- public ID or catalog revision expectations do not match;
- the new-canonical count exceeds the reviewed patch.

Threshold values should be derived from the fixed-page corpus, not invented before it exists.

## 11. Implementation order

### Step 0 — fix foundation invariants

1. Stable seeded public UUID and Android/client lifecycle contract.
2. Enforced run/robots/source/dry-run state machine.
3. Immutable source-observation revisions and decision hashes.
4. Value-bearing field claims and complete merge provenance.
5. Human-confirmed alias promotion.
6. Candidate-set ambiguity and concurrency constraints.
7. Private authenticated operator interface.

### Step 1 — isolated parser spike

1. Add locked scraper module and CI.
2. Add source registry document/data and owner sign-off.
3. Implement policy/fetch/evidence on a local fake server.
4. Capture 30-50 approved pages and minimized fixtures.
5. Implement facts-only Art of Tea parser and deterministic JSONL.
6. Publish a run report; do not touch canonical DB tables.

### Step 2 — staging and review proof

1. Import the frozen JSONL into a disposable database.
2. Generate ranked candidate sets against a recorded catalog revision.
3. Label at least 100 candidate pairs including hard negatives.
4. Exercise revision, conflict, no-op, stale-decision, and concurrency tests.
5. Approve a tiny patch in the disposable database.

### Step 3 — controlled production pilot

1. Back up and record the pre-import catalog revision.
2. Verify exact patch hash, source policy, parser version, and review identities.
3. Apply a small demand-driven batch.
4. Verify API/search/Android resolution and public-content gate.
5. Reapply to prove no-op and rehearse retraction/restore.

### Step 4 — traversal only after measured success

1. Adopt Scrapy core facilities.
2. Filter the live sitemap to approved tea paths.
3. Preserve the same parser/evidence/facts schema.
4. Add AutoThrottle, robots middleware, cache, job persistence, stats, and bounded budgets.
5. Keep human-reviewed patch batches.

## 12. Go/no-go checklist

### Fixed-page parser spike

- [ ] scraper module and hash-locked dependencies;
- [ ] source owner sign-off and current robots snapshot;
- [ ] exact host/path/query allowlist;
- [ ] bounded fetch behavior and fake-server tests;
- [ ] no browser, cookies, auth, or production credentials;
- [ ] minimized fixtures and explicit label mapping;
- [ ] deterministic facts-only JSONL schema;
- [ ] raw HTML ignored and short-retention;
- [ ] run report with rejects and policy hashes.

### Database staging

- [ ] run/source/parser/robots binding enforced;
- [ ] dry-run state enforced;
- [ ] immutable observation revisions;
- [ ] source URL/external-ID history and collisions handled;
- [ ] raw evidence linked or explicitly local-only by policy;
- [ ] unknown schema fields/types rejected;
- [ ] concurrency cardinalities constrained.

### Canonical publication

- [ ] stable public UUID end-to-end and rebuild-tested;
- [ ] public lifecycle/visibility predicate enforced;
- [ ] candidate ambiguity visible;
- [ ] decision bound to exact revision and catalog snapshot;
- [ ] selected field claims and provenance complete;
- [ ] confirmed aliases promoted correctly;
- [ ] authenticated operator and reason recorded;
- [ ] same patch twice is a no-op;
- [ ] rollback/retraction keeps client identity;
- [ ] final API/export contains no vendor expression or media.

## 13. Research disposition

Do not create another broad scraper research run now.

- Run 20 settled source choice, public-content policy, and pilot tools.
- Run 21 settled source/canonical/provenance separation and human review.
- This review finds implementation gaps that should be fixed and tested directly.

Run new research only if the fixed-page spike exposes a genuinely unsettled question: materially different
HTML/source policy, a maintained cross-script identity dataset, or a second source that cannot fit the same
observation/claim model. Live robots, ToS, selectors, and dependency versions are implementation preflight
evidence, not model-comparison topics.

## 14. Final recommendation

Start with the isolated parser only after its network policy and deterministic output contract exist. Do
not connect it to production staging or canonical tables until the P0 foundation findings are closed.

The fastest safe path is:

```text
fix run/revision/identity/provenance invariants
-> fixed approved URLs
-> bounded local fetch
-> fixture-backed Art of Tea parser
-> deterministic facts-only JSONL
-> disposable DB review/apply/no-op/rollback proof
-> tiny production pilot
-> Scrapy traversal later
```

This preserves the project's small-system architecture while preventing the scraper from amplifying
incorrect identities, unaudited field changes, or copyrighted content into the public catalog.
