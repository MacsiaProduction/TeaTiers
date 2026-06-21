# TeaTiers post-fix architecture and design review

Date: 2026-06-21

Status: current-state refresh after PRs #108-#113; supersedes the open-item list in
`2026-06-21-full-architecture-design-review.md` where this file marks an item resolved
or changes its diagnosis. The earlier file remains the full pre-fix review and historical record.

Audited baseline: clean `main` at `9ff8ed2`

Authority order: `context/decisions.md` and `context/plan.md`, then live code. In practice,
`context/plan.md` contains material stale sections, so a claim is treated as current only when the
decision log and implementation agree.

## 1. Executive verdict

The topology remains correct:

- local-first Android app for all personal data;
- Room and DataStore on-device;
- one narrow Spring/PostgreSQL shared-catalog service;
- one private, opt-in OCR sidecar;
- one Docker Compose host;
- offline/operator-only catalog ingestion.

Do not introduce Kubernetes, Kafka, a separate search engine, accounts, or cloud sync. The current
risks are boundary correctness and operational closure, not insufficient distribution.

The June 21 fix wave closed four important implementation defects:

1. release builds no longer use destructive Room migration and v6 is exported;
2. miss-log retention and more accurate in-app privacy copy landed;
3. backup photos are streamed and the archive is checked before the Room replacement;
4. server limiter state is bounded and timed-out OCR work is moved to a killable process.

Those changes materially improve the release posture, but they do not close the release program.
Before another public APK, the highest-risk work is now:

1. protect the v0.1.0 signing lineage with tested offline recovery;
2. repair the custom updater trust model before enabling its manifest;
3. finish privacy/diagnostics operational truth rather than only copy and retention;
4. stop the app from recreating sample data after the user deletes the last board;
5. implement the already-locked v7 sample/reference migration with device-level upgrade tests;
6. make the OCR worker supervisor single-flight and self-healing;
7. deploy immutable, verified image identities and add off-host failure detection.

## 2. Verification performed

Reviewed in this refresh:

- source-of-truth docs, focused design docs, every existing review, and decisions through #132;
- judged research runs through run 20;
- the post-review commits from `fcc2c23` through `9ff8ed2`;
- Android persistence, sample seeding, backup, updater, diagnostics, networking, and release wiring;
- server catalog, resolve/enrichment, rate limiting, diagnostics, retention, and migrations;
- OCR process isolation and deployment concurrency;
- Compose, Caddy, OpenTofu, backups, image publication, and CI workflows;
- external operator material at `~/context/git/TeaTiers` without reading the keystore;
- current upstream project material for TUF, Pebble, Trivy, restic, and db-scheduler.

Local validation:

- `server/gradlew --no-daemon check`: passed;
- `app/gradlew --no-daemon check assembleDebug`: passed;
- Room schema regeneration produced no tracked diff;
- OCR host tests did not run because host Python 3.14 has no `pytest`. The image workflow remains the
  runtime verification path; this is a verification limitation, not evidence of a failing test.

## 3. Disposition of the previous review

| Previous item | Current state | Evidence / remaining work |
|---|---|---|
| P0-1 destructive Room migration | Resolved for v6 | `TeaDatabase` exports v6; release omits destructive fallback; `6.json` is committed. Future v7 still requires a real 6->7 migration test. |
| P0-2 privacy/data-flow mismatch | Partial | Copy and miss retention landed. No versioned policy/contact/deletion process exists, diagnostics is not operationally wired, and the dormant LLM path still persists a typed name into the shared catalog. |
| P0-3 signing continuity | Open | The only observed operator keystore is still mode `0644` and no tested recovery evidence is recorded. |
| P1-1 canonical tea vs sample | Designed, not built | Decision #132 and `context/design/tea-sample-split-v7.md` are strong and adversarially reviewed. Implementation is correctly deferred until the migration gate is ready. |
| P1-2 Russian-required local name | Designed, not built | The v7 `tea_sample_names` design fixes it; current v6 still requires `nameRu`. |
| P1-3 updater | Open, severity increased | No automatic check, no independent trust anchor, no package-name check, no byte bound, and production config is not injectable through current Compose. |
| P1-4 release CI | Partial | Release now runs Android `check` and PR CI has an emulator job. Tag/version monotonicity, last-public upgrade, APK attestation, and release-time emulator gating are still absent. |
| P1-5 backup memory/partial restore | Core resolved, residual gaps | Photos stream and missing declared files reject before Room mutation. Export can still create an unusable archive when a referenced local photo file is missing; failed imports can leave copied orphan files. |
| P1-6 scraped-data ingestion | Research resolved, build deferred | Run 20 is judged and decision #131 locks a proportionate first cut. Staging/provenance/import-run implementation remains deferred. |
| P1-7 OCR timeout leak | Mechanism improved, supervisor incomplete | A timed-out process is killable now. Concurrent requests and dead-worker recovery still have correctness gaps. |
| P1-8 deploy provenance | Open | Images are signed/attested, but live deployment still permits `:latest` and verification is a manual README instruction. |
| P1-9 unbounded limiter map | Memory bound resolved, rate semantics partial | Caffeine caps memory, but eviction can reset an active client window; comments claim the opposite. |
| P1-10 single-host controls | Open | Log rotation, least-privilege backup credentials, external health/disk/backup alerts, SSH restriction, and recorded restore drills remain. |
| P2-1 image hotlink boundary | Open/dormant | Seed still has zero images. Preserve the first-party mirror/license rule before adding any. |
| P2-2 AI recovery | Open/dormant | AI remains effectively off in production. Stale `PENDING` rows still cannot recover after restart. |
| P2-3 API compatibility | Open | DTOs remain duplicated without golden compatibility fixtures. |
| P2-4 offline detail | Open | Search summaries cache; detail remains network-only. |
| P2-5 device-level tests | Partial | Room baseline emulator infrastructure landed; upgrade data preservation and other Android boundary flows are still untested on-device. |
| P2-6 documentation epochs | Open, worse | More fixes landed without updating authoritative current-state prose. |

## 4. Current release blockers

### P0-1. Release-key recovery is still a single point of failure

Evidence:

- v0.1.0 established a permanent update lineage in decision #128;
- `~/context/git/TeaTiers/release.jks` exists but is mode `0644`;
- GitHub Secrets can build but are not a recoverable, independently tested keystore backup;
- no recovery drill result is recorded in the repository or operator material.

Impact: loss or corruption of this key permanently prevents an in-place update for every installed
v0.1.0 copy. The risk increases with every additional install.

Required:

1. Change the operator copy to `0600`.
2. Create two encrypted offline copies in separate failure domains.
3. Record the certificate fingerprint, alias, recovery locations, and recovery procedure in a
   password manager/operator runbook, never in Git.
4. Reconstruct the key on an isolated machine, sign a test APK, and verify the exact v0.1.0
   certificate fingerprint.
5. Re-run the drill periodically and after moving operator hardware.

Acceptance: an isolated recovery starts from a backup, produces a signed APK, and verifies
`a4835edc0addb0c8ed0d3e49d7c700d61ed07636f1520f29e5ad66960b21a43c` without the original file.

### P0-2. The updater has no independent trust anchor and does not verify package identity

Evidence:

- `AppUpdateProperties` says an offline Ed25519 manifest signature must land before public launch,
  but v0.1.0 is already public and the field/signature do not exist
  (`server/.../AppUpdateProperties.kt:12-13`);
- `ApkVerifier` compares the APK signer to `manifest.signingCertSha256`
  (`app/.../ApkVerifier.kt:44-62`). The server that selects the APK also selects the supposedly
  trusted certificate, so this is consistency checking, not pinning;
- `ApkVerifier` never verifies `PackageInfo.packageName == context.packageName`;
- Android same-signer enforcement protects replacement of the existing package, but a different
  package can instead be offered for side-by-side installation if package identity is not rejected;
- update checks exist only inside Settings. `mandatory` and `minSupportedVersionCode` cannot protect
  a user who never opens Settings;
- the download writes directly to `update.apk` with no declared or streamed byte cap, no `.partial`
  promotion, no HTTPS/host allowlist, and no fsync;
- the UI says the developer signature is already verified before download/verification occurs;
- fallback opens the manifest-provided APK URL rather than a compile-time official release page.

This path is currently dormant because the deployed manifest defaults to 204. It becomes a release
blocker the moment update metadata is enabled.

Required first cut:

1. Embed the expected package name and current release-certificate fingerprint in the app.
2. Reject a downloaded archive unless package name, version code, signer, SHA-256, and minimum SDK
   all match expected policy.
3. Add a detached signature over byte-stable manifest content with an offline key whose public key
   is embedded in the app. TUF is the stronger general design, but a single-target signed manifest
   is proportionate now if rollback/freeze fields and canonical bytes are explicit.
4. Check on app start/resume with a DataStore throttle. Keep an offline escape behavior explicit;
   a mandatory update must not create an unrecoverable blank screen during an outage.
5. Stream into a bounded `.partial` file; accept only HTTPS and allowlisted release hosts; fsync,
   verify, then atomically rename.
6. Use a compile-time official GitHub release page as manual fallback.
7. Change copy to “signature will be checked before installation,” then show “verified” only after
   `ApkVerifier.Ok`.

Alternative for the tester phase: publish Obtainium setup instructions and remove/disable the custom
channel until the signed-manifest path is complete.

Acceptance: tests reject a different package signed by the manifest-selected certificate, a correct
package signed by the wrong certificate, a downgraded APK, oversized/chunked downloads, non-HTTPS
URLs, stale/expired metadata, and a tampered manifest.

### P0-3. Privacy copy improved, but consent and diagnostics are not operationally truthful yet

Resolved:

- Settings now discloses miss logging and OCR text flow;
- `MissLogService.purgeStale()` deletes low-demand stale query text daily;
- tests cover the retention query.

Still open:

- there is no versioned privacy policy, operator contact, retention table, or request/deletion
  process linked from Settings or before first server/OCR use;
- diagnostics UI says the app can send reports, but stock builds compile
  `DIAGNOSTICS_TOKEN=""` unless the release job injects it. `release.yml` does not inject it;
- the server defaults diagnostics off, and production Compose passes neither
  `TEATIERS_DIAGNOSTICS_ENABLED` nor `TEATIERS_DIAGNOSTICS_TOKEN`;
- enabling the UI toggle in that state installs ACRA on the next process start but sends nothing.
  The user has consented to a feature that is silently non-functional;
- if the LLM tier is later enabled, `EnrichmentStubService.createOrGetStub()` stores the exact typed
  name as a shared `TeaName(source="user")`. The current text describes aggregate miss retention but
  does not clearly describe durable shared-catalog promotion;
- the published policy must not claim arbitrary free text can never contain personal data.

Required:

1. Publish and link a short versioned policy before enabling telemetry or AI enrichment.
2. Expose diagnostics capability state: hide/disable the toggle when the build cannot send, or ship
   a correctly configured release and server together.
3. Pass server feature config via a dedicated `env_file`/Compose secrets or explicit environment
   entries; do not assume Compose's interpolation `.env` becomes container environment.
4. Prefer a reviewed candidate queue over immediately publishing a typed user name when AI is enabled.
5. Add an operator contact and exact retention/deletion rules for miss and diagnostics rows.

Acceptance: a release-level integration test proves opt-in report delivery and opt-out no-delivery;
every server-bound field and stored row has disclosed purpose and retention.

## 5. Android data and domain model

### P1-1. Deleting the last board is undone on the next process start

This is a new regression exposed by the delete-board feature.

Evidence:

- `TeaBoardRepository.deleteBoard()` deletes the selected board;
- repository initialization treats `dao.boardCount() == 0` as a fresh install and inserts the sample
  data (`TeaBoardRepository.kt:87-100`);
- there is no durable “sample seeded/onboarding complete” marker;
- the current unit test verifies the board is gone in the same repository instance but never creates
  a second repository over the same DAO (`TeaBoardRepositoryTest.kt:75-89`).

Impact:

- a user cannot persist a deliberately empty board list;
- deleting the last board causes the sample board to reappear after process death/relaunch;
- importing a valid zero-board backup has the same next-launch behavior;
- user teas survive, so this is not direct data loss, but it violates explicit deletion and makes
  first-run state indistinguishable from user-created empty state.

Required:

- persist an onboarding/sample-seeded marker outside the board count, ideally in DataStore;
- seed only when the marker is absent and the database is genuinely new;
- set the marker atomically enough that a crash cannot duplicate sample insertion;
- define import behavior explicitly: importing an empty archive must mark onboarding complete;
- add a restart test: seed -> delete last board -> construct a new repository -> still zero boards.

Do not solve this by forbidding deletion of the last board; the UI already has a valid empty state.

### P1-2. Build the locked v7 sample/reference split next, without redesigning it again

Decision #132 and `context/design/tea-sample-split-v7.md` correctly address the largest domain
constraint:

- a catalog reference is not a purchased sample;
- several samples may link to one catalog reference;
- personal names require at least one value, not specifically Russian;
- placements still share one sample across boards;
- v6 IDs and child IDs are preserved;
- backup format v2 and a 6->7 converter are part of the change;
- explicit column lists, stub preconditions, foreign-key checking, and validate-before-wipe are
  already specified.

Do not reopen the model unless implementation reveals a concrete invariant failure. The work is now
migration engineering:

1. keep v6 public baseline immutable;
2. add a real representative v6 fixture with boards, shared placements, flavors, purchases, photos,
   pending enrichment, blank-edge names, and catalog links;
3. run `Migration(6,7)` on an emulator;
4. assert every ID, file reference, relation, and user-visible value after upgrade;
5. run a repository write/read smoke on the migrated DB;
6. test backup v1 -> v2 and failed-import rollback;
7. publish v7 only after install-over-v0.1.0 succeeds on a device/emulator.

### P1-3. Backup streaming is fixed, but export validity and file cleanup need closure

Resolved:

- no whole-photo-corpus buffering;
- uncompressed size and entry count are bounded;
- declared-but-missing archive photos reject before Room replacement;
- Room replacement is transactional.

Residual defects:

- export builds JSON from every photo row, but adds a ZIP `PhotoSource` only when the referenced file
  exists (`BackupManager.kt:158-169`). One orphaned DB path therefore creates a “successful” archive
  that import later rejects as missing a declared photo;
- photos copied into the live store before `dao.replaceAll` are not cleaned if a later copy or the DB
  transaction fails. The next app-open reconcile eventually removes old-enough orphans, but restore
  itself is not filesystem-atomic;
- duplicate IDs or duplicate bundled names are not rejected explicitly;
- relational and enum validation is largely delegated to the Room transaction rather than reported
  as a clean `InvalidFile` before mutation;
- no last-successful-backup timestamp/reminder exists despite local data being the only copy.

Required:

- fail export before reporting success if any declared photo cannot be opened, or reconcile and build
  JSON only from the proven snapshot according to an explicit policy;
- import into a transaction-specific staging directory under files storage and promote only after
  all content plus DB constraints validate; delete every newly copied file on failure;
- reject duplicate record IDs, duplicate ZIP entry names, invalid enums, missing parents, and invalid
  positions with a typed validation report;
- add large instrumented round-trip, corrupt-file, duplicate-entry, and low-free-space tests;
- persist last successful export time and show a non-coercive freshness reminder.

### P2-1. Offline behavior remains asymmetric

- server search is typo-tolerant through `pg_trgm`;
- app cache search remains literal substring `LIKE` over fetched summaries;
- catalog browse caches summary pages, but detail/descriptions/flavors remain network-only;
- the app's personal data remains fully offline, so this is not a local-first violation.

Improve only if usage proves it matters: cache detail by catalog ID with fetched-at/schema version,
and use a small normalized token index for cached names. Do not embed a second search service.

## 6. OCR reliability and resource isolation

### P1-4. The new killable worker has a concurrency-generation race

The process move fixed the old unkillable-thread defect. The remaining supervisor is not safe under
the deployed concurrency settings.

Evidence:

- the sidecar owns one `ProcessPoolExecutor(max_workers=1)`;
- the server allows four concurrent OCR requests (`teatiers.ocr.max-concurrent: 4`);
- every sidecar request submits to the current executor and starts its own 15-second wait;
- on timeout the handler reads the current global `_executor`, replaces it, and kills that value
  (`ocr-sidecar/app.py:239-254`), not necessarily the executor generation to which that request was
  submitted;
- queued concurrent requests can therefore time out after another request has replaced the pool and
  kill the fresh worker while leaving the original timed-out generation unmanaged;
- `_kill_executor` reaches into the private `ProcessPoolExecutor._processes` implementation detail;
- a worker that exits/OOMs outside the timeout path raises on requests, but generic exception handling
  returns 500 without recreating the broken pool;
- parent `_ready` remains true after worker death, so `/health` stays green and Docker will not restart
  a permanently broken sidecar.

Required minimum:

1. Make OCR strictly single-flight at the server and/or sidecar; set the server semaphore to the
   actual worker count (1), fast-failing excess work before it queues.
2. Bind each submitted future to an immutable executor generation and only recycle that generation.
3. Serialize recycle operations with a lock.
4. Detect `BrokenProcessPool`/worker exit, mark readiness false, rebuild and warm a worker, then return
   503 until ready.
5. Make health prove a live worker, not only that startup once succeeded.
6. Add a test with two overlapping requests where one hangs; prove only the owning generation dies
   and the next request succeeds.

OSS option: Pebble's `ProcessPool` explicitly supports per-task timeouts and worker restart. Run a
small container proof against RapidOCR before adopting it. If the proof is sound, it is preferable to
maintaining private `concurrent.futures` process internals.

### P2-2. Test and scan the same Python runtime that is deployed

- OCR Docker runtime is Python 3.14;
- pytest and OSV dependency-resolution workflows use Python 3.12;
- the built-image smoke catches startup incompatibility, but the dependency graph selected by Python
  markers and request tests can differ.

Use Python 3.14 for the workflow that resolves/scans runtime dependencies, and run request tests
inside the built image or a matching 3.14 environment. Keep the lightweight pre-build test only as
an additional fast lane.

## 7. Server correctness

### P2-3. AI jobs can remain permanently PENDING after a server restart

The tier is effectively off in production today, so this is an enable-day blocker, not an active
incident.

Evidence:

- `/resolve` creates a `PENDING` row and dispatches an in-memory `@Async` task;
- migration V2 created an index explicitly for restart recovery;
- no startup/scheduled recovery sweep exists;
- cache hits on `PENDING` return `ENRICHING` without redispatch;
- after a process death, the client polls, times out locally, retries, and receives the same stuck
  `PENDING` row indefinitely.

Required before enabling AI:

- simplest: on startup mark stale PENDING rows FAILED, letting explicit retry re-arm them;
- stronger: persist a job identity/attempt state and idempotently resubmit stale work;
- use exponential backoff, jitter, max attempts, graceful shutdown, and durable daily spend accounting.

OSS decision: do not add a broker. If durable job semantics become a real requirement, db-scheduler is
an embeddable Java 17+/PostgreSQL solution using one table and supports one-time jobs, retry/backoff,
and transactionally staged work. For the current off-by-default low volume, a small stale-row sweep is
enough.

### P2-4. Bounded limiter memory weakens the advertised per-client guarantee

Caffeine is appropriate for bounding heap. The implementation comment is wrong: size eviction can
remove an active client entry; its next request starts a fresh window and therefore receives more
budget. Under a high-cardinality flood, strict per-client enforcement is not preserved.

This is acceptable only if documented as approximate overload protection. The global LLM cap still
bounds spend, and OCR concurrency bounds simultaneous work. Options:

- keep Caffeine and document approximate semantics;
- add a global request/cost ceiling that does not depend on client keys;
- enforce coarse edge limits in Caddy if one IP-rotation attack becomes real;
- use Bucket4j only if true token-bucket semantics are required.

Also correct “absolute” wording around in-memory daily budgets: a server restart resets them.

### P2-5. API contracts need executable compatibility, not only careful duplication

Keep hand-written app/domain models, but add:

- checked-in golden JSON request/response fixtures for search, detail, resolve, OCR, diagnostics, and
  app update;
- server serialization tests and app deserialization tests over the same fixtures;
- additive-only checks for `/api/v1`;
- `X-App-Version-Code`/platform headers and server metrics for unsupported clients;
- a compatibility matrix when v7 changes backup/local data but not the wire model.

OpenAPI generation is optional. Adopt it only if it removes duplication without leaking generated
wire types into Compose/domain code.

## 8. Deployment, supply chain, and operations

### P1-5. Production feature configuration is not deliverable through current Compose

Docker Compose uses project `.env` for interpolation; it does not place arbitrary keys into a
container unless the service declares `environment` or `env_file`.

Current production `server.environment` passes database and OCR URL values only. Therefore the
documented operator instructions to set app-update, diagnostics, or LLM variables in
`/opt/teatiers/.env` do not put them into the server container.

Required:

- use an explicit server `env_file` with mode `0600` for non-secret switches/metadata and Compose
  secrets or a root-only env file for credentials;
- enumerate required variables in a checked-in `.env.example`/runbook without values;
- add `docker compose config` plus a container-level config smoke that asserts intended feature
  enablement without printing secret values;
- deploy app/server configuration as one release manifest so an APK cannot advertise diagnostics or
  updater behavior the server does not provide.

Do not put the diagnostics shared token in Lockbox under the pretense it is secret; it ships in the
APK. Protect real credentials (DB, LLM, backup, offline signing key) separately.

### P1-6. Release CI is improved but still not a release gate

Resolved:

- release runs Android `check`;
- PR CI runs Room baseline validation on an emulator;
- release verifies that the APK is signed.

Still open:

- tag validation accepts any string beginning with `v` and a digit; it does not require exact semver;
- tag is not compared with `versionName`;
- `versionCode` is not checked against the previous public release;
- release workflow does not run or depend on the emulator migration/upgrade job for the tagged commit;
- no install-over-v0.1.0 smoke proves the package, signer, and database upgrade together;
- APK has no CycloneDX attachment or GitHub artifact attestation despite containers having both;
- the release workflow does not assert the exact expected certificate fingerprint.

Required: make one release workflow/job graph produce and attest the APK only after exact version,
unit/lint, schema, emulator upgrade, signing fingerprint, and package identity checks succeed.

### P1-7. Signing and attesting images without deploy-time verification remains audit-only

Current build pipelines produce immutable SHA tags, digests, keyless signatures, and attestations.
The deployment still allows `SERVER_IMAGE=:latest` and `OCR_SIDECAR_IMAGE=:latest`, while verification
is a manual README option.

Create a small release manifest:

```text
release id
server image digest
ocr image digest
database migration version
APK version code + SHA-256 + signing certificate
configuration revision
previous release manifest
```

A deploy script or small Ansible playbook should:

1. verify cosign identity and GitHub attestation for both digests;
2. render Compose with digests only;
3. pull, migrate/start, and run health/smoke checks;
4. record the applied manifest;
5. roll back containers to the prior manifest when health fails. Database rollback remains
   forward-fix/restore and must never blindly reverse a destructive migration.

Kubernetes/ArgoCD remains the wrong scale.

### P1-8. Single-host operational gaps remain

- Docker JSON logs have no explicit rotation limits;
- the backup service account still has folder-wide `storage.admin`;
- SSH is open to `0.0.0.0/0` and serial console is enabled;
- health checks are same-host;
- no external stale-backup, low-disk, or uptime alert is defined;
- restore rehearsal is documented but no dated result/RTO is recorded;
- mutable image cleanup and disk watermarks are not automated;
- off-box dumps rely on storage access controls but do not have application-level encryption or an
  independently checked repository.

Proportionate improvements:

- Docker `local` logging driver or `json-file` max-size/max-file;
- bucket-scoped least privilege and deletion protection/versioning where supported;
- operator CIDR/VPN/bastion restriction for SSH and disable unused serial console;
- an off-host health/backup-age probe, even a scheduled GitHub workflow initially;
- quarterly restore drill with recovered timestamp, row counts, checksum, and elapsed time;
- evaluate restic around the logical dumps if encrypted, verifiable S3 snapshots and retention
  automation justify one extra binary. Do not add pgBackRest/WAL-G until RPO or write volume requires
  PITR.

### P2-6. Container scanning does not cover the shipped OS/runtime image

OSV scans Gradle/Python dependency graphs, which is useful but does not cover base-image OS packages
or container misconfiguration. Dependabot also lacks a server-Dockerfile entry.

Adopt Trivy for the built server and OCR images before push/release. It covers OS packages, language
dependencies, secrets, licenses, and IaC/misconfiguration. Keep OSV as the fast ecosystem advisory
gate; the tools cover different layers.

Add Dependabot Docker coverage for `/server` and pin any new scan action by a reviewed major/SHA.

### P3. The documented static-analysis gate is not actually configured

- neither Gradle build applies detekt, ktlint, or Spotless;
- decision-log prose says a full `check` includes ktlint and detekt, which is false for the audited
  build;
- Android `check` does run lint and unit tests, but the configured rule requiring Kotlin static/style
  analysis is not enforced;
- the current Android build also reports a deprecated `sourceSets...assets.srcDir` API and broader
  Gradle features that will be incompatible with Gradle 10.

Re-verify current Kotlin 2.4/AGP 9 compatibility, then wire one supported formatter/static analyzer
into `check`. Do not add a tool that cannot parse the pinned Kotlin version merely to satisfy a box.
Fix the known Gradle deprecations before a wrapper 10 upgrade rather than using a broad warning
baseline.

## 9. Catalog and content architecture

### P1-9. Run 20 is complete; build the locked small ingestion pipeline later

Run 20 is judged and decision #131 settles the source and facts-only direction. The dedicated adversarial
review [`2026-06-21-catalog-scraping-plan-review.md`](2026-06-21-catalog-scraping-plan-review.md) found that
the implementation wording still needs correction before it is built:

- first source `artoftea.ru`; `tea.ru` excluded by robots;
- local one-off job, not a production crawler;
- `httpx + selectolax` is appropriate for a fixed-URL parser pilot; use Scrapy's maintained robots,
  throttle, retry, cache, restart, export, and stats machinery for actual sitemap traversal;
- raw response/content-hash cache;
- facts only into public catalog;
- do not persist vendor prose in the first cut; structured facts are sufficient;
- `dedup_key` is a uniqueness guard, not cross-script canonical identity: source-record identity,
  aliases, reviewed match decisions, and canonical identity must be separate;
- the startup `CatalogSeeder` is insert-or-skip, not an update-capable bulk importer;
- human review and CI export guards;
- build deferred until release blockers are closed.

Do not start another scraping research run. Build a dedicated explicit importer before the crawler, with
staging evidence, source external IDs, field-level provenance, import-run ID, parser version, match decision,
stable public identity, and rollback/audit outputs. The current startup `CatalogSeeder` should remain a small
bootstrap mechanism, not become the scraper/importer.

The committed seed remains exactly 100 teas, with zero source URLs and zero images. Documentation
claiming approximately 300 is false.

### P2-7. Future images require a first-party privacy and license boundary

When images are added:

- ingest only independently licensed content;
- record license, source URL, retrieval time, hash, dimensions, and review status;
- validate decode/MIME/size;
- mirror immutable bytes to first-party object storage;
- serve the first-party URL so Android does not disclose user IP/request metadata to arbitrary vendor
  hosts;
- never interpret “scraping allowed” as permission to redistribute vendor photos.

## 10. Documentation and decision hygiene

`context/plan.md` is nominally authoritative but currently contradicts implementation and later
decisions in release-relevant ways:

- run 07 is called pending;
- seed is approximately 300 instead of 100;
- backend is called “no user data/no PII” despite miss queries, OCR text, and opt-in diagnostics;
- live search is described as exact substring although `pg_trgm` is implemented;
- crawling is still banned despite #129/#131;
- queued enrichment is described as reconnect-driven although it is app/board-open retry;
- first public APK is presented as a future gate after v0.1.0 shipped;
- Room, backup, resolve budget, i18n, and release checklist states are stale;
- external `~/context/git/TeaTiers` notes still describe Phase 0 and decisions only through #28.

Required:

1. keep `context/decisions.md` append-only;
2. rewrite current-state sections of `context/plan.md` instead of layering more “drifted” warnings;
3. move historical architecture prose into a clearly marked history appendix if it must remain;
4. make the root `task.md`/`architecture.md` pointers concise and accurate;
5. update OCR/infra runbooks and code comments that still say pre-launch/thread/Python 3.12;
6. treat external operator notes as an index/runbook, not a stale second source of truth.

## 11. Target architecture

```text
Android
  Room:
    boards -> placements -> personal tea samples
                            -> names / notes / flavors / purchases / photos
                            -> optional cached catalog reference
  DataStore:
    onboarding seeded flag
    appearance/language
    diagnostics consent/capability
    update and backup freshness
  bounded backup/update staging
        |
        | HTTPS /api/v1, app-version headers
        | explicit per-action OCR/resolve consent
        v
Caddy
  -> Spring API
       -> PostgreSQL canonical catalog + provenance/staging/job state
       -> one single-flight private OCR worker

Offline operator path
  fetch evidence -> parse -> normalize -> match -> review -> approved snapshot/upsert

Release path
  tested commit -> immutable APK/images/schema/config manifest
                -> signatures/attestations verified by consumers
                -> one-host Compose deploy + rollback record
```

Boundary rules:

1. Personal sample data never enters the shared catalog.
2. Typed/OCR text crosses the boundary only after accurate disclosure and explicit action.
3. Catalog facts carry source evidence; vendor expression/photos do not ship without independent rights.
4. A remote update manifest cannot select its own trust root.
5. Every background job has an explicit process-death outcome.
6. Empty user state is valid state, not a first-install signal.
7. One host remains acceptable only with off-host backup and observation.

## 12. Open-source reuse decisions

| Solution | Decision | Reason |
|---|---|---|
| Room plugin + room-testing | Keep/adopt fully | Native migration baseline and emulator validation; already landed. |
| Obtainium | Offer now for testers | Removes most custom release-discovery/install ownership until updater trust is complete. |
| TUF | Use as design reference; defer full framework | Correctly handles signed metadata, rollback/freeze, expiry, and key compromise. Full role/delegation machinery is likely excessive for one APK today. |
| Ackpine | Keep only as installer adapter | It does not authenticate manifests or APK identity. |
| Pebble ProcessPool | Proof, then likely adopt | Maintained pool abstraction with per-task timeout and worker restart; can replace private Python executor internals if RapidOCR warmup/restart tests pass. |
| db-scheduler | Conditional before AI enable | Persistent one-table PostgreSQL jobs, retries, and transactionally staged work without a broker. A stale-PENDING sweep is simpler while AI is off. |
| Trivy | Adopt now | Complements OSV by scanning shipped images, OS packages, secrets, licenses, and config. |
| restic | Evaluate in ops pass | Encrypted/verifiable S3 snapshots and restore checks; useful around logical dumps if key management is acceptable. |
| Ansible | Optional, likely useful | Smallest established tool for repeatable one-host digest-verified deploy and rollback. |
| Caffeine | Keep with corrected semantics | Good memory bound; not a strict rate-limit guarantee under size eviction. |
| Bucket4j | Conditional | Only if real token-bucket/global semantics are required. |
| httpx/selectolax/pypinyin | Adopt for deferred run-20 build | Verified, small, matches the locked local one-off ingestion design. |
| pgBackRest/WAL-G | Defer | No demonstrated PITR/RPO need yet. |
| Meilisearch/OpenSearch | Reject | PostgreSQL search is sufficient. |
| Kubernetes/ArgoCD | Reject | Wrong operating scale and adds a control plane. |
| Kafka/RabbitMQ | Reject | No workload requires a broker. |
| Sentry/GlitchTip self-host | Reject now | First-party diagnostics is adequate once it is actually configured and verified. |

Primary references checked:

- TUF overview: https://theupdateframework.io/docs/overview/
- Pebble: https://github.com/noxdafox/pebble
- Trivy: https://github.com/aquasecurity/trivy
- restic: https://github.com/restic/restic
- db-scheduler: https://github.com/kagkarlsson/db-scheduler
- Docker Compose container environment:
  https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/

## 13. Recommended execution sequence

### Before another public APK

1. Secure/recover/test the release key.
2. Fix updater package/signer trust or keep the custom manifest disabled and document Obtainium.
3. Fix last-board reseeding and add the restart test.
4. Publish/link the privacy policy; either wire diagnostics end-to-end or hide unavailable capability.
5. Complete release tag/version/fingerprint/attestation gates.
6. Add backup export validity and failure cleanup.

### v7 product-model release

1. Add representative v6 migration fixture and last-public APK install test.
2. Execute the locked six-PR v7 design sequence.
3. Ship backup v2/up-converter in the same compatibility program.
4. Verify a signed install over v0.1.0 with all user data and photo paths preserved.

### Reliability and deployment

1. Make OCR single-flight and self-healing; prove timeout/worker-death recovery.
2. Deliver server feature config explicitly.
3. Add API golden fixtures and app-version observability.
4. Scan built containers with Trivy.
5. Verify and deploy digests as one release manifest.
6. Add log rotation, external health/disk/backup-age alerts, and a dated restore drill.

### Catalog growth

1. Keep current 100-tea catalog honest in documentation.
2. Build run-20's local staging/review pipeline only after release blockers.
3. Start with miss-log-demanded records and one source.
4. Require facts-only/export guards and first-party licensed image handling.
5. Keep production free of crawlers and browser automation.

### AI enable day

1. Re-verify provider model, price, logging/privacy behavior, and feature config.
2. Add stale-PENDING recovery or db-scheduler.
3. Make daily cost accounting durable if it is presented as a hard ceiling.
4. Require reviewed publication and explicit typed/OCR-text policy.

## 14. Release gate

Block the next public APK unless:

- [ ] v0.1.0 signing-key recovery drill passed;
- [ ] exact semver tag equals `versionName` and `versionCode` increased;
- [ ] expected package name and embedded release certificate are verified;
- [ ] updater manifest is independently authenticated or custom updater remains disabled;
- [x] Room v6 schema is exported and release destructive fallback is absent;
- [ ] install/upgrade from v0.1.0 preserves a representative DB and photo paths;
- [ ] deleting the last board stays deleted after process restart;
- [ ] privacy policy/copy match deployed diagnostics, OCR, miss, and AI behavior;
- [ ] backup large/corrupt/missing/duplicate cases pass, with no false-success export;
- [x] JVM/unit/lint checks pass locally on current baseline;
- [ ] tagged release depends on emulator migration/upgrade tests;
- [ ] APK SBOM/attestation and exact certificate fingerprint are published;
- [ ] server and sidecar are deployed by verified digest as one manifest;
- [ ] OCR hang and worker-death tests prove recovery;
- [ ] external health, disk, and backup-age state are known;
- [ ] a recent off-box restore drill is recorded;
- [ ] current plan/runbooks describe the shipped behavior.

## 15. Research disposition

No new `research/` folder is justified now.

- Updater architecture is already answered by judged run 17. The gap is failure to complete its
  public-release security phase.
- Diagnostics architecture is already answered by run 15. The gap is configuration and integration.
- OCR model/correction questions are covered by runs 13, 18, and 19. The new issue is process
  supervision and needs a deterministic concurrency proof, not model synthesis.
- Scraping is settled by judged run 20 and decision #131.
- Canonical-reference/sample identity is settled by decision #132 and its adversarially reviewed
  design.

The next useful evidence is executable: updater adversarial tests, repository restart tests, v6->v7
migration tests, OCR overlapping-timeout tests, a container scan, and a restore drill.

## 16. Final recommendation

Keep the system small. Finish the trust, deletion, migration, and recovery semantics of what already
ships before adding catalog volume or enabling AI. The strongest architecture work is already done:
local-first ownership, the v7 domain design, Postgres search, opt-in OCR, and a one-host topology.
The remaining risk comes from incomplete transitions between those components and from release
claims that are not yet enforced by executable gates.
