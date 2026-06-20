# TeaTiers full architecture and design review

Date: 2026-06-21

Status: current-state review; supersedes stale conclusions in earlier reviews, but does not replace their historical record

Audited baseline: clean `main` at `a76b888`, before the concurrent `feat/delete-board` worktree changes

Authority order: `context/decisions.md` and `context/plan.md`, then live code; `task.md` and `architecture.md` are legacy sketches

## 1. Executive verdict

TeaTiers has an appropriate small-system architecture: a local-first Android app, Room for personal data, a narrow Spring/PostgreSQL reference-catalog API, an opt-in private OCR sidecar, and one Docker Compose host. The project should not add Kubernetes, Kafka, a separate search engine, or accounts/cloud sync at this stage. The strongest existing decisions are the local-first boundary, review-before-save OCR, PostgreSQL plus `pg_trgm`, feature-gated LLM enrichment, first-party diagnostics, and a deliberately small operational footprint.

The project is nevertheless no longer in a pre-release state. The public `v0.1.0` APK changes several risk decisions. Three items are now release blockers rather than deferred hardening:

1. Room still permits destructive migration and does not export schemas. A schema bump can erase a user's complete local collection.
2. The product's privacy explanation is not an accurate description of current and feature-gated server behavior. Typed names are retained in the miss log, and the LLM path can promote a typed name into the shared catalog.
3. The only demonstrated release-key recovery path is one local keystore plus GitHub secrets. The external local copy is mode `0644`; loss of that key ends the existing Android update lineage.

The most important product-model correction is to separate a canonical tea reference from a user's concrete tea sample/product. The current one-to-one relationship prevents the same kind of tea from being represented across vendors, harvests, batches, ages, and purchases with different notes or rankings. Fixing this before a large installed base is substantially cheaper than migrating it later.

The best next architecture is evolutionary, not a rewrite:

- preserve the app/server/sidecar/deployment topology;
- establish Room v6 as the public migration baseline;
- split catalog references from personal samples;
- replace Russian-required local names with an at-least-one-localized-name rule;
- build scraping as an offline, provenance-preserving ingestion pipeline, never as a production request path;
- harden the release updater, backup streaming, OCR timeout isolation, rate-limit storage, and single-host operations.

## 2. Review scope and method

This pass reviewed:

- product and architecture sources: `task.md`, `architecture.md`, `context/plan.md`, and all locked decisions through #129;
- all earlier files under `context/review/`, treating them as dated evidence rather than current truth;
- all research prompts, ratings, and leaderboards through run 19, plus the active run 20 prompt and available unjudged answers;
- Android persistence, repositories, ViewModels, backup, OCR, diagnostics, updater, UI, build configuration, and tests;
- server API, persistence, resolve/enrichment, miss logging, rate limiting, Flyway, and tests;
- OCR sidecar execution and resource guards;
- Compose, cloud-init, Caddy, backup, CI, security, image publication, and release workflows;
- external operator material in `~/context/git/TeaTiers`, without reading secret contents;
- current official Android and GitHub release guidance and maintained upstream OSS documentation.

Validation performed during the review:

- the server Gradle `check` passed;
- the app `check` reached compilation but was invalidated by concurrent uncommitted `feat/delete-board` edits: `FakeTeaDao` temporarily lacked the new `deleteBoardRow` method. This is not recorded as a baseline defect;
- OCR Python tests could not run in the host interpreter because `pytest` is not installed. CI installs the declared test tooling, but the sidecar should still be retested in its container;
- no secret value or keystore content was inspected.

## 3. What is already sound

These decisions should be retained:

| Area | Current choice | Assessment |
|---|---|---|
| Personal data | Local-first Room database; no account required | Correct product boundary and privacy default. |
| Android UI | Compose, UDF/MVVM, immutable state, repository boundary | Appropriate; no need for an architectural framework replacement. |
| Backend | Spring WebMVC, PostgreSQL, Flyway | Appropriate for the load and team size. |
| Search | PostgreSQL `pg_trgm` over multilingual names | Correct; a separate search service has no justified operating cost. |
| OCR | Explicit opt-in, preview/edit, then `sourceText` | Correct human-in-the-loop design. |
| OCR model | Mobile detector at 960 on the current 4 GB host | Current measurements support keeping it until a larger corpus disproves it. |
| LLM enrichment | Disabled by default and gated by cost budget | Correct while recovery, provenance, and quality controls remain incomplete. |
| Diagnostics | Opt-in ACRA plus local wipe sentinel | Proportionate and first-party. |
| Deployment | One VM and Docker Compose | Correct current topology. |
| Supply chain | OSV, CycloneDX, image signing and attestations | Strong baseline; deployment verification is the missing half. |
| Catalog growth | Demand-driven miss counts plus curated promotion | Useful prioritization signal once retention and disclosure are fixed. |

Material improvements since the previous broad review should not be reopened as missing work: the Wikidata failure path, OCR request and pixel caps, corrected-text propagation, Caddy edge limits, atomic database backup upload, bounded enrichment executor, first-party diagnostics, dependency scanning, container signing/attestation, and initial signed APK release have all landed.

## 4. Priority findings

### P0-1. Public user data can still be destructively migrated

Evidence:

- `app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDatabase.kt` declares Room version 6 with `exportSchema = false`.
- `app/src/main/kotlin/com/macsia/teatiers/di/AppModule.kt` unconditionally calls `fallbackToDestructiveMigration(dropAllTables = true)`.
- there is no committed Room schema history, migration implementation, or `MigrationTestHelper` suite.
- decision #128 records a public signed v0.1.0 release. The old “prelaunch” justification is therefore false.

Impact: any incompatible future schema version can silently destroy boards, rankings, notes, purchases, and photos. Because the product is local-first, this can be the user's only copy.

Required change:

1. Treat the shipped Room v6 schema as the immutable public baseline.
2. Enable schema export through the Room Gradle plugin and commit schemas.
3. Remove the production destructive fallback. If debug builds need it, scope it explicitly to debug-only construction.
4. Add explicit migrations for every version after 6.
5. Add `MigrationTestHelper` coverage from v6 to latest plus a real repository read/write smoke test after migration.
6. Prevent release CI from publishing when schema export or migration verification fails.

Acceptance criteria: a database created by v0.1.0 upgrades to the next release with every user-visible record and photo reference preserved; the test uses a checked-in v6 schema.

### P0-2. Privacy statements do not match data flows

Evidence:

- Android copy says typed names and packaging descriptions are only used to clarify a tea and are not saved in the common catalog.
- `ResolveService.unresolved()` sends every unresolved typed name to `MissLogService.record()`.
- `catalog_miss` stores the normalized query, count, first date, and last date with no retention or deletion policy.
- when LLM enrichment is enabled, `EnrichmentStubService.createOrGetStub()` stores the user-provided name as a shared catalog `TeaName(source = "user")`.
- confirmed OCR text becomes `sourceText` and is sent to the server/enrichment path. The image itself is not retained, which is a good boundary, but the text processing still requires disclosure.
- the public release has inline settings copy but no versioned privacy document, operator contact, retention statement, or deletion process.

Impact: users cannot give informed consent based on the current copy. “No PII” in source comments is too strong: a free-form query can itself contain personal or sensitive text even if no account, IP, or device identifier is stored with it.

Required change:

1. Publish a short versioned privacy policy and link it before the first server/OCR use and from Settings.
2. State exactly what is sent: typed tea name, optional confirmed OCR/pasted text, locale, diagnostics when enabled, and ordinary transport/server logs.
3. State what is retained, why, and for how long. Do not claim arbitrary free text can never be personal data.
4. Add miss-log retention, for example deleting rows whose `last_seen` is older than 90 days and whose demand is below a documented promotion threshold. Preserve only an aggregate after promotion if needed.
5. Decide explicitly whether an LLM-created catalog stub may preserve a user's exact typed name. Prefer a reviewed/normalized candidate queue over immediate shared-catalog persistence.
6. Add an operator contact and deletion/request process proportionate to the project.

Acceptance criteria: every server-bound field has a disclosed purpose and retention rule; the UI wording and implementation agree; automated retention is tested.

### P0-3. Release signing continuity has a single-point-of-failure risk

Evidence:

- decision #128 establishes a stable signing certificate for all future updates.
- external operator material contains one `release.jks`; it was not opened, but its filesystem mode was `0644`.
- GitHub Actions secrets can build releases but are not a usable recovery export of the keystore.

Impact: loss or corruption of the keystore/password prevents updates to installed v0.1.0 copies. Overly broad local read permission also increases exposure.

Required change:

1. Change the local file to mode `0600` and keep it outside synchronized/public paths.
2. Create at least two encrypted offline backups in separate failure domains.
3. Put aliases, passwords, certificate fingerprint, backup locations, and recovery procedure in a password manager/operator runbook; never commit secrets.
4. Perform a recovery drill: reconstruct the keystore on an isolated machine, sign a test artifact, and verify the expected certificate fingerprint.
5. Consider Android's managed signing options only if distribution later moves to a compatible store; do not rotate casually after public sideload release.

Acceptance criteria: a documented, tested recovery can reproduce an APK signed by the v0.1.0 certificate without relying on the original workstation.

### P1-1. Canonical tea and personal tea sample are conflated

Evidence:

- `TeaEntity` has a unique `catalogTeaId`.
- `TeaBoardRepository` deduplicates a personal tea by catalog ID or normalized name.
- flavors, notes, photos, purchase records, and board placements all hang from that single personal tea row.

This cannot represent these valid cases:

- the same Longjing from two vendors;
- two harvest years or grades with different flavor and rank;
- multiple cakes/batches of the same puer with different storage and aging;
- one canonical reference linked to several purchased samples.

Target model:

```text
server catalog tea reference (optional)
              |
              | 0..1 link
              v
user tea sample/product 1 ---- * purchase
          |      |  \
          |      |   * photo
          |      * personal flavor/note
          * board placement ---- 1 board
```

Recommended local entities:

- `CatalogTeaRef`: remote ID plus cached immutable-ish reference facts;
- `TeaSample`: user-owned item with optional catalog link, vendor/product, harvest/year, batch/grade, personal names and notes;
- `Purchase`: seller, URL/location, date, price/quantity when added later;
- `BoardPlacement`: references a `TeaSample`, with an explicit product decision on whether one sample may appear once per board or across several boards.

Do not copy personal ratings, purchase facts, or user descriptions into the shared catalog. Catalog refreshes must not overwrite personal data.

Acceptance criteria: two samples linked to one catalog tea can coexist with independent notes, photos, purchases, flavors, and ranks; existing v6 rows migrate losslessly into one sample each.

### P1-2. The Android multilingual model requires Russian even when the tea has no Russian name

Evidence:

- `AddTeaForm.isValid` requires `nameRu`.
- `TeaEntity.nameRu` is non-null and is treated as the display title.
- the product requirement allows Russian, English, and Chinese names.

Impact: users with only Chinese or English packaging must invent a Russian value. It also makes dedup and display behavior inconsistent with the server's normalized multilingual `tea_name` model.

Required change:

- require at least one non-blank name, not a Russian name;
- model personal names as child rows `(locale/script, value, isPrimary, source)` or, as an interim migration, make structured fields nullable with a display-name resolver;
- choose display name by user preference, app locale, primary flag, then deterministic fallback;
- perform dedup only as a user-visible suggestion. Do not automatically merge distinct samples because names normalize alike.

Acceptance criteria: a tea can be added with only `大红袍`, only `Da Hong Pao`, or only `Да Хун Пао`, and is displayed predictably in every locale.

### P1-3. The in-app updater does not yet provide the promised mandatory-update control

Evidence:

- update checks are initiated only from the Settings action; no app-start or app-resume integration exists.
- `minSupportedVersionCode` and `mandatory` cannot protect old clients if the user never opens Settings.
- the UI states that the developer signature is verified before the APK has been downloaded and verified.
- the expected signer fingerprint is supplied by the same remotely fetched manifest as the APK URL and hash. This is integrity metadata, not an independent trust anchor.
- downloader code has no explicit maximum streamed APK size and writes to the final filename.
- on failure, the fallback opens a URL supplied by that same manifest while presenting it as an official release path.

Required change:

1. Check on app open/resume with a DataStore throttle; show an application-level prompt. WorkManager is unnecessary for this first cut.
2. Compare the downloaded APK signer to the currently installed app signer or an embedded certificate fingerprint. Keep Android's same-signer install enforcement as the final control.
3. Add a detached Ed25519-signed manifest if the custom channel remains public.
4. Download to `.partial`, enforce HTTPS and an allowlist, bound content length and streamed bytes, fsync/rename only after hash and signer verification.
5. Make fallback a compile-time official repository release page, not an arbitrary manifest URL.
6. Change copy to “will be verified before installation” until verification actually succeeds.

OSS alternative: for a small tester population, document [Obtainium](https://github.com/ImranR98/Obtainium) as an external GitHub-release updater and remove most custom update code. Keep Ackpine/custom UX only if seamless in-app updates justify owning this security-sensitive path.

### P1-4. Release CI can publish an APK without the project's full quality gate

Evidence:

- `.github/workflows/release.yml` builds `assembleRelease` but does not run the complete Android `check`, device smoke tests, or Room migration tests.
- tag matching is permissive and does not prove that the tag, `versionName`, and monotonic `versionCode` agree.
- container images are attested; the downloadable APK is not.

Required change:

- make release depend on the same unit/lint/static-analysis gates as PRs;
- add migration and install/upgrade emulator smoke tests;
- validate exact semver, tag equals `versionName`, and versionCode is greater than the prior release;
- verify signature and certificate fingerprint after signing;
- generate an SBOM/provenance record and use GitHub artifact attestation for the APK;
- publish only immutable outputs produced by the successful gated job.

Also schedule Android developer verification work now. Current Android guidance says certified-device installation requirements begin rolling out in September 2026 in selected countries before broader expansion; register the developer, package name `com.macsia.teatiers`, and signing certificate before it becomes a release emergency.

### P1-5. Backup archives are bounded but not streaming

Evidence:

- export calls `File.readBytes()` for every photo and builds an in-memory map before writing the ZIP;
- import keeps decompressed photo byte arrays in memory up to the aggregate limit, then copies them again;
- missing declared photos can be silently omitted during entity reconstruction.

Impact: a formally bounded 256 MB archive can still cause several hundred MB of peak allocations and an Android OOM. A “successful” partial import can lose photo evidence without a clear failure.

Required change:

- stream one photo at a time into the ZIP;
- import to a staging directory using per-entry and aggregate compressed/uncompressed limits;
- validate manifest, paths, counts, CRC/hash, IDs, and all required files before changing Room;
- reject missing or duplicate declared entries;
- apply the database transaction only after the staged archive is complete; atomically promote photo files or clean up on failure;
- add round-trip tests with a large synthetic archive and an instrumented low-memory test.

Add a first-run explanation and a backup-freshness reminder based on the last successful export timestamp. Local-first without sync is acceptable only if users understand device loss and are prompted to protect the sole copy.

### P1-6. Catalog ingestion is not designed for scraped multi-source data

Current seed behavior is suitable for a small hand-curated bootstrap but not the newly allowed scraping program:

- the committed seed contains exactly 100 teas, not the approximately 300 claimed by stale documentation;
- all 100 seed entries omit a top-level source URL and default to `source=curated` and `verificationStatus=verified`; the seed currently contains zero images;
- the seeder inserts missing dedup keys but cannot reliably propagate corrections, aliases, provenance, or licenses into existing rows;
- startup performs row-by-row checks/inserts and should not become a thousand-record import engine;
- the `tea.source` constraint and tea-level provenance cannot represent facts reconciled from several source pages;
- direct promotion into canonical rows makes conflicts and rollback hard to audit.

Required ingestion architecture:

```text
polite local fetch
  -> immutable raw metadata/evidence record
  -> parsed source record
  -> normalized candidate + aliases
  -> deterministic exact/trigram candidate matching
  -> conflict/review queue
  -> approved canonical upsert
  -> versioned clean catalog snapshot
```

Store at minimum: source ID, canonical URL, retrieval time, HTTP/content hash, parser version, raw factual fields, normalized fields, field-level provenance, match decision, reviewer/status, and import-run ID. Raw vendor prose, if retained for transformation, stays access-controlled server-side with a short retention and is never served or shipped. Vendor photos are not redistributed unless their license independently permits it.

Run scraping locally or as a disposable operator job, not in the API service and not on the production VM. Start with top miss-log terms, honor site terms/robots and conservative rate limits, cache responses, and make re-import idempotent. Promote only reviewed facts. Do not describe scraped records as verified by default.

Research run 20 already covers source selection, tools, rights, dedup, and operating model. Its answers are still unjudged. Do not start a duplicate run: collect the remaining expected answers, write `RATING.md`, update `research/LEADERBOARD.md`, verify any chosen source and library version against primary sources, then write a locked implementation decision.

### P1-7. OCR timeout “recycling” does not terminate the timed-out inference

Evidence: `ocr-sidecar/app.py` uses `asyncio.wait_for(run_in_executor(...))`. On timeout it creates a new executor and calls `shutdown(wait=False)` on the old one. Python cannot kill the already-running thread.

Impact: repeated stuck inferences can accumulate abandoned model threads consuming CPU and memory until the 4 GB host is killed. The next request gets a fresh executor, but the resource leak remains.

Required change: isolate inference in a killable process. The simplest robust options are one worker subprocess supervised by the sidecar, or terminating the sidecar process on a hard deadline and letting Docker restart it. Preserve readiness semantics so traffic is not accepted before model warm-up. Add a test with an intentionally hung recognizer and prove process/thread count returns to baseline.

The Android picker has a related client-side risk: it reads the selected content fully before imposing a raw byte bound. Inspect size where available and copy through a bounded stream/file descriptor before decode.

### P1-8. Deployment provenance exists but is not enforced at deploy time

Evidence:

- CI signs and attests images.
- deployment material still permits mutable `:latest` references.
- no deploy step verifies a cosign signature/attestation before starting the digest.

Impact: signing without consumer-side verification provides audit evidence but does not prevent an unintended or replaced image from running.

Required change:

1. Resolve release images to immutable digests.
2. Verify signature and expected GitHub identity/attestation before deployment.
3. record the exact server and sidecar digests as one release manifest;
4. deploy those digests together and retain the prior manifest for rollback.

Do this with the existing Compose topology. ArgoCD implies Kubernetes and is not an appropriate tool for moving to the pelican host. If repeatability becomes painful, use a small Ansible playbook or OpenTofu for host/cloud resources plus immutable Compose releases.

### P1-9. Rate-limiter key storage is not strictly bounded in the active window

The custom fixed-window map purges stale entries but can still grow with arbitrarily many new forwarded IPs during one active window. Caddy limits requests, but this is still attacker-controlled heap state.

Use a bounded [Caffeine](https://github.com/ben-manes/caffeine/wiki/Eviction) cache with maximum size plus expiry and preserve the current counter semantics. [Bucket4j](https://bucket4j.com/8.17.0/toc.html) is reasonable only if token-bucket behavior is actually required. A distributed rate limiter is unnecessary on one instance.

### P1-10. Single-host operations need failure-domain controls, not orchestration expansion

Remaining gaps:

- Docker JSON logs have no explicit size/count rotation on a 30 GB disk;
- the backup service account is folder-wide `storage.admin`, broader than upload-only backup needs;
- backup integrity is tested, but object deletion/overwrite resistance and backup-freshness alerting are weak;
- SSH is open to `0.0.0.0/0` even though keys are required;
- application health checks originate on the same host/failure domain;
- there is no clear low-disk or stale-backup external alert.

Improvements:

- configure `max-size`/`max-file`, disk thresholds, and safe image cleanup;
- scope the service account to the backup bucket/prefix and, where supported, make existing backups non-deletable/immutable for the retention window;
- alert externally on uptime, disk pressure, and age of newest successful backup;
- restrict SSH to operator IP/VPN/bastion where practical and disable unused serial-console access;
- continue periodic restore drills and record restore time and recovered snapshot date.

Daily atomic `pg_dump` is proportionate for the current reference catalog. Add WAL/PITR tooling such as pgBackRest only when a real RPO requirement or write volume justifies it.

## 5. Secondary findings

### P2-1. Future catalog images must not be arbitrary third-party hotlinks

The current seed has no image rows, so this is dormant. `CatalogDetailSheet` nevertheless loads `image.url` directly with Coil. If populated with vendor or arbitrary external URLs, the client discloses its IP and request metadata to third parties and inherits mutable/malicious content.

Ingest only independently licensed images, validate HTTPS/MIME/dimensions/size, mirror them into first-party object storage, retain source and license evidence, and serve a first-party immutable URL. Decision #129 allows scraping facts; it does not grant permission to redistribute vendor photos.

### P2-2. AI enrichment remains intentionally dormant, and its recovery controls are incomplete

Before enabling it:

- retry only transient failures, with exponential backoff and jitter; do not retry ordinary 4xx errors;
- define whether the daily budget caps logical jobs or paid attempts and make a hard cost ceiling durable across restarts if that promise is kept;
- gracefully drain or mark queued work on shutdown;
- sweep stale `PENDING` records and make recovery idempotent;
- keep input/output provenance and human review for generated facts.

Do not start another general LLM research run now. Follow the existing decision to rerun focused research immediately before enabling the feature.

### P2-3. API compatibility depends on duplicated DTOs and convention

The app and server manually duplicate response contracts. The recent optional `corrected` OCR field was coordinated successfully, but there is no contract fixture or compatibility gate.

Keep `/api/v1` additive. Add `X-App-Version-Code` and platform headers, golden JSON compatibility tests shared as fixtures, and server-side visibility of unsupported-client use. Adopt OpenAPI generation only if it reduces duplication without forcing poor Kotlin/Compose models; it is not required for a single first-party client.

### P2-4. Remote catalog detail is network-only

Search has an offline cache, but reference detail/flavor can disappear when offline. If the product promise includes offline reference browsing, cache catalog detail by catalog ID with fetched-at/schema version and a bounded TTL. Personal notes and flavors remain independent and authoritative.

### P2-5. Critical Android flows lack device-level tests

There are unit tests, but no meaningful `androidTest` coverage for Room upgrade, drag/drop ranking, Storage Access Framework backup/restore, URI grants, APK installation/update handoff, and process-death restoration. Add a small emulator matrix for these boundaries rather than broad screenshot testing.

### P2-6. Documentation has crossed decision epochs

Current contradictions include:

- plan sections still describe the first public APK as pending;
- plan/runbook text still bans crawling after decision #129 reversed that rule;
- seed size is documented as roughly 300 while the committed seed is about 100;
- Room comments still rely on a prelaunch assumption;
- the OCR README documents only `text` although the live contract also returns optional `corrected`.

Make one documentation-consistency change after this review. Update current plan/runbooks/comments without rewriting append-only decision history.

### P3. Lower-priority hardening

- narrow GitHub Actions permissions to job level where practical;
- validate forwarded headers only from the trusted proxy path;
- add catalog ingestion metrics: candidates, auto-matches, review queue, conflicts, rejects, and provenance completeness;
- define deletion semantics for photos and temporary update/backup files after crashes;
- measure rather than assume OCR changes: n >= 30 labeled real packages, CER plus latency/RSS, before the pelican/server-detector decision.

## 6. Target architecture

The target keeps the number of online components unchanged:

```text
Android app
  Room: boards, samples, purchases, notes, photos, cached catalog refs
  DataStore: consent, diagnostics, update/backup freshness
        |
        | versioned HTTPS API; explicit opt-in for OCR/resolve
        v
Caddy -> Spring API -> PostgreSQL canonical catalog
                    -> private OCR sidecar

Offline operator pipeline
  polite fetch -> staging/evidence -> normalize/match -> human review
               -> canonical upsert/snapshot -> PostgreSQL/Flyway seed import
```

Boundary rules:

1. Personal sample data stays local unless the user explicitly invokes a documented server feature.
2. Shared catalog data is reference information with field-level provenance, never a sink for personal notes or ratings.
3. OCR images are ephemeral; confirmed text follows the disclosed resolve/enrichment policy.
4. Scraping produces candidates, not verified canonical records.
5. Production serves approved snapshots; it does not crawl, run browser automation, or perform bulk reconciliation.
6. Every release is a set of immutable APK/image/database-schema identities with an executable rollback/recovery path.

## 7. Open-source reuse decisions

| Tool/solution | Decision | Why |
|---|---|---|
| Room migrations, Room Gradle plugin, `room-testing` | Adopt now | Native solution to the highest data-loss risk. |
| Obtainium | Offer/evaluate now | Can replace most custom GitHub-release updater risk for testers. |
| Ackpine | Keep only if needed | Useful installer UX, but it does not solve manifest trust or release discovery. |
| GitHub artifact attestations | Add for APK | Existing project pattern already covers containers. |
| Caffeine | Adopt for bounded limiter state | Small, maintained, and adequate for one JVM. |
| Bucket4j | Conditional | Use only if token-bucket semantics are needed. |
| Scrapy | Conditional after run 20 | Appropriate for a real multi-page crawl; plain `httpx` plus parser is simpler for a few stable sources. |
| Playwright | Last resort | High resource/maintenance cost; use only for verified JS-only pages. |
| Ansible | Optional | Good fit if repeatable one-host provisioning/deploy becomes manual. |
| pgBackRest/WAL-G | Defer | Current write rate and RPO do not yet justify PITR complexity. |
| Meilisearch/OpenSearch | Reject now | PostgreSQL search is sufficient. |
| Kubernetes/ArgoCD | Reject now | Adds a cluster and control plane to solve a one-host deployment problem. |
| Kafka/RabbitMQ | Reject now | The workload does not require a broker. |
| GlitchTip/Sentry self-host | Reject now | First-party ACRA is sufficient and materially cheaper to operate. |
| Cloud sync/accounts | Defer by product choice | Conflicts with the local-first scope and creates authentication/privacy obligations. |

## 8. Recommended sequence

### Before another public APK

1. Establish Room v6 schema export, explicit migrations, and upgrade tests; remove production destructive fallback.
2. Correct privacy/consent copy, publish the policy, and add miss-log retention.
3. Secure and recovery-test the release keystore.
4. Gate release on Android checks, migration/install upgrade smoke tests, tag/version validation, signature verification, and APK attestation.
5. Fix updater trust/copy/size handling or document Obtainium and defer the custom channel.
6. Add backup streaming and reject partial archives.

### Next product-model milestone

1. Lock the catalog-reference versus personal-sample decision.
2. Design and test the v6-to-new-model migration.
3. Make local names locale-neutral with at least one required value.
4. Add backup freshness UX and the few critical emulator tests.

### Catalog-growth milestone

1. Finish and judge research run 20; verify the selected sites and tool versions live.
2. Build the offline staging/provenance/import-run model.
3. Start with one or two sources and the highest-value miss-log queries.
4. Require human promotion and generate a clean versioned snapshot.
5. Update the stale curation runbook and seed-size claims.

### Reliability milestone

1. Replace OCR thread timeout recycling with killable process isolation.
2. Bound Android image input before allocation.
3. Deploy verified image digests and add log rotation/external alerts.
4. Bound rate-limit state with Caffeine.
5. Add API compatibility fixtures and app-version observability.

### Measure before changing

- keep the current 4 GB VM until OCR and traffic measurements justify moving;
- re-run labeled OCR evaluation on the pelican hardware before enabling the server detector;
- do not add Kubernetes/ArgoCD for that move;
- do not enable LLM enrichment until its focused enable-day review;
- do not add a separate search service, broker, or managed database without measured pressure.

## 9. Release gate checklist

A release should be blocked unless all applicable items are true:

- [ ] Room schema is exported and every shipped-to-current migration passes.
- [ ] Upgrade from the last public APK preserves a representative database and photos.
- [ ] Privacy policy and in-app disclosure match the exact server-bound and retained fields.
- [ ] Backup export/import round-trip passes, including a large archive and missing-entry rejection.
- [ ] `check`, lint/static analysis, unit tests, and critical emulator smoke tests pass.
- [ ] Tag, `versionName`, and increasing `versionCode` match.
- [ ] APK signature is the expected release certificate; hash/SBOM/attestation are published.
- [ ] Update manifest and installer path reject wrong host, oversize, wrong hash, and wrong signer.
- [ ] Release keystore recovery has been tested and backups remain accessible.
- [ ] Server/sidecar image digests are verified and recorded together.
- [ ] Latest database backup age, disk space, external health, and rollback image are known.
- [ ] User-visible ru/en/zh copy is complete for changed flows.
- [ ] Current plan/runbooks describe the released behavior rather than a pre-release state.

## 10. Research disposition

No additional research folder should be created from this review.

- Run 20 is the correct active research for scraping and ingestion. It already has multiple answers but no rating; finish that workflow first.
- Sample/catalog identity is a product-domain decision that the current code settles well enough to design directly. Validate with a few realistic user journeys; a multi-model research run would add little.
- Room, signing, updater, backup, rate limiting, and OCR timeout issues are implementation/verification work with authoritative upstream guidance, not open research questions.
- Keep the existing deferred focused research immediately before enabling the LLM tier.

## 11. External references

- [Android Room migration guidance](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Android app signing](https://developer.android.com/studio/publish/app-signing)
- [Android same-signer update requirement](https://developer.android.com/google/play/app-updates)
- [Android developer verification guide](https://developer.android.com/developer-verification/guides/pdf-guides/adc-guide.pdf?hl=en)
- [GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)
- [Obtainium](https://github.com/ImranR98/Obtainium)
- [Caffeine eviction and bounds](https://github.com/ben-manes/caffeine/wiki/Eviction)
- [Bucket4j documentation](https://bucket4j.com/8.17.0/toc.html)

## 12. Final recommendation

Do not redesign TeaTiers into a larger distributed system. Freeze wider release work long enough to make the existing local-first promise true under upgrade, backup, and privacy failure modes. Then separate personal samples from canonical references and build the new scraping capability as an offline, reviewed, provenance-preserving import pipeline. These changes address the project's real risks—irrecoverable local data loss, misleading privacy expectations, signing continuity, and an underspecified domain model—while preserving the architecture that is already working.
