# TeaTiers architecture review refresh after hardening merges — 2026-06-22

Status: current-state review against main at a622da0 after PRs #121, #122, and #124.

This refresh supersedes active-finding conclusions in
2026-06-22-full-architecture-design-review.md where later merges changed the evidence. It does not replace
that file's broader product analysis. Historical task.md, architecture.md, and ~/context/git/TeaTiers were
treated as intent/history; locked decisions, focused designs, live code, migrations, tests, and the sibling
../researches/projects/TeaTiers repository were treated as current evidence.

## Executive verdict

The core architecture remains appropriate:

- local-first Android personal data, without accounts or cloud user storage;
- a Spring/PostgreSQL shared catalog rather than microservices or a separate search platform;
- PostgreSQL staging/review/canonical boundaries for imported facts;
- public UUID identity plus numeric compatibility for the already-shipped APK;
- a killable, network-isolated OCR sidecar;
- operator-controlled ingestion and facts-only publication.

The three hardening merges materially improve the system. Deterministic seed UUID reconciliation exists,
withdrawn catalog content has a compact tombstone, source re-registration invalidates approval, only one run
per source can be active, terminal run states cannot be rewritten, and type/corroboration claims plus
target-tea locking are implemented.

However, the project is not ready for a real scraper or another public APK. The highest-risk remaining issue
is the gap between claimed operational/state-machine guarantees and what executable paths enforce. C4 is not
complete: the database still has only running -> succeeded|failed|blocked, and canonical approval accepts a
non-dry running or succeeded run. There is no enforced reviewed/apply-authorized state.

Recommended order:

1. protect the release key and rehearse the production database migration;
2. deploy the current server by verified digest and prove numeric/UUID/lifecycle contracts;
3. finish the ingestion apply state, evidence/URL gates, and review concurrency;
4. implement Room v7 CatalogTeaRef / TeaSample and the UUID API cutover;
5. harden updates, privacy, bounded file I/O, backups, and monitoring;
6. only then run a fixture-backed, one-source, local scraper pilot.

## Verification performed

- Reviewed current source-of-truth and focused design documents, review artifacts, external context notes,
  and the extracted research repository.
- Re-checked live app, server, OCR, migrations, CI/release, Docker, backup, and infrastructure paths.
- Fresh server run: ./gradlew --no-daemon test --rerun-tasks — passed.
- Fresh Android run: ./gradlew --no-daemon testDebugUnitTest lint --rerun-tasks — passed.
- Android lint: 0 errors, 15 warnings. Actionable warnings include LocalContextResourcesRead, missing Android
  12+ dataExtractionRules, target-SDK drift, and unused accessibility/resources.
- OCR Python tests were not run locally: Python 3.14 is present, but pytest is not installed. CI is the
  current executable sidecar evidence.
- Public API probe on 2026-06-22: search still returns the old response without publicId; the committed
  Longjing UUID route returns 404. Production has not deployed the current migration/API baseline.
- External release keystore metadata: ~/context/git/TeaTiers/release.jks remains mode 0644.

## Closed or materially reduced since the earlier 2026-06-22 review

### Production UUID reconciliation — code closed, operations still open

V11__reconcile_seed_public_ids and SeedPublicIdReconciler now reconcile existing curated rows by dedup_key,
update the legacy map, remap merge pointers, fail on semantic UUID collision, fail if a curated row remains
outside the frozen seed UUID set, and are covered by integration tests.

This closes the code defect from P0-1. It does not close deployment continuity until an operator:

1. takes a current production backup;
2. rehearses V6/pre-V7 production data through V7–V12 offline;
3. verifies all curated rows, legacy mappings, UUID mappings, and representative lifecycle paths;
4. deploys the exact tested image digest;
5. runs post-deploy numeric/UUID/search/facet/resolve probes.

### Compact lifecycle tombstones — closed

The catalog now uses CatalogDetail.Full versus CatalogDetail.Tombstone, returns lifecycle-only data for
retracted or unresolvable merge chains, and uses the same behavior for numeric and UUID lookup. Tests assert
that withdrawn content is not serialized.

Minor API cleanup remains: a successful merged lookup returns the survivor as status=active with
supersededByPublicId equal to the survivor's own publicId. Add an explicit requestedPublicId or clear redirect
contract so clients do not have to infer why the response was superseded.

### Source/run and provenance hardening — improved, not fully closed

PR #124 correctly added material source-change approval invalidation, fresh and complete robots checks, one
active run/source, locked ingest/finish transitions, immutable terminal states, removal of decorative counters,
type and corroborating claims, brand as a non-selected proposal, and target-tea locking around selected claims.

These reduce the exploitable surface. They do not satisfy the entire locked C4/C6 readiness bar.

## P0 — before another public APK or production migration

### P0-1 — production still runs the pre-public-ID contract

The live API returns the old search DTO and 404s the committed seed UUID route. Code and production are now
two incompatible identity generations.

Required:

- one digest-only deployment command that verifies Cosign identity and attestation;
- automatic backup and Flyway info/validate before replacement;
- deployment of the rehearsed digest;
- probes for search, numeric detail, UUID detail, merge/retraction, facets, resolve, and health;
- a release record containing commit, image digests, migration versions, probe results, and rollback point.

### P0-2 — release-signing key remains a single point of failure

The external keystore is still locally world-readable (0644), and no tested restore/sign/update drill is
evidenced.

Required owner action:

- move the working key out of a general context/sync directory and restrict permissions;
- create two encrypted offline backups with recovery material stored separately;
- record alias, package ID, certificate fingerprints, expiry, and recovery steps without passwords;
- restore from backup, sign a candidate, and prove it updates an installed previous APK;
- never generate a replacement key merely to rotate CI secrets.

### P0-3 — custom updater has no independent trust root

The same unsigned server manifest chooses the APK URL, APK hash, and signer hash. ApkVerifier also does not
verify package name, exact manifest version, declared byte size, manifest expiry, or rollback counter.
ApkDownloader has no header/actual-byte cap, writes directly to a fixed final path, does not fsync/atomically
rename, and logs URLs.

Before enabling it:

- embed an offline Ed25519 public key and sign a canonical manifest;
- include package name, exact version, minimum version, size, SHA-256, signer policy/history, issue/expiry time,
  and monotonic rollback counter;
- verify the signature before reading release fields;
- stream to a randomized partial path with byte caps, fsync, verify, and atomic rename;
- verify package ID, exact version, size/hash, and signer continuity;
- keep forced updates disabled unless a real compatibility/security need exists;
- use Obtainium for testers until this protocol is complete.

## P0 — before any scraped canonical write

### ING-P0-1 — C4 run/apply state is incomplete despite the tracker saying DONE

This is the main new correction from this refresh.

Evidence:

- V12 permits only running, succeeded, failed, and blocked; it does not implement the locked
  created -> preflight_allowed -> ingesting -> reviewed -> applied progression.
- startRun immediately creates status=running.
- finishRun can only move running to a terminal status.
- CanonicalUpsertService.requireApplyAllowed rejects dry, blocked, and failed runs, but accepts both running
  and succeeded runs.
- no transaction proves all current revisions were reviewed before canonical apply, and apply does not move
  the run into an applied state.

Failure mode: CLI approval can publish while the run is still ingesting, or after a generic succeeded marker
that only means the runner finished. The audit trail cannot prove fetched, reviewed, and applied completeness.

Required:

1. Add DB-constrained created, preflight_allowed, ingesting, awaiting_review, reviewed, applying, applied,
   failed, and blocked states.
2. Put transitions behind one locked transition service; terminal states remain immutable.
3. Stop ingestion before awaiting_review; require every current revision to have a terminal review decision.
4. Allow apply only from reviewed/applying and for the exact reviewed revision.
5. Mark applied only after all intended writes commit; define partial/failed recovery.
6. Test apply-while-running, pending decisions, duplicate apply, terminal rewrite, stale and cross-run revision.

context/review/INDEX.md should mark C4 PARTIAL / REOPEN P0, not DONE.

### ING-P0-2 — evidence and URL/host enforcement is schema decoration, not a gate

- source_site.allowed_hosts is not mapped or administered.
- no import path validates observation URLs, redirect targets, DNS/IP results, ports, or hosts.
- RawEvidenceRepository exists, but production code never writes RawEvidence; source revisions cannot prove
  which fetch produced them.
- robots validation does not compare against the registered robots URL, persist an allowed-host snapshot, or
  bind the decision to a parser/library version.

Required:

- administer a normalized allowlist; changes invalidate approval;
- reject userinfo, fragments, unapproved ports, IP literals, local/private/link-local/reserved addresses, and
  non-allowlisted redirect/final targets;
- resolve and re-check DNS in the actual fetcher to reduce rebinding risk;
- persist immutable requested/final URL, status, type/length, body hash, time, parser/tool, robots snapshot,
  source, and run metadata for every revision;
- bind revision and decision to that evidence and fail apply if the chain is absent/inconsistent.

### ING-P0-3 — strict observation semantics remain open

- Unknown type is coerced to TeaType.OTHER.
- Country is not validated as ISO 3166-1 alpha-2.
- Free-text region can reach claims although #138 requires a reviewed Wikidata QID.
- facts are deserialized with unknown fields ignored.
- FactsOnlyGuard is not re-run at apply against the exact reviewed revision.

Reject or queue unknown type/country/region values, use strict schema decoding, and re-run semantic/facts-only
checks inside the canonical-apply transaction.

## P1 — catalog and review correctness

### CAT-P1-1 — matcher hides ambiguity and can target inactive rows

IdentityMatchService still takes firstOrNull for authoritative/exact results and one max trigram score. Exact
and trigram queries do not filter active tea rows; authoritative alias uniqueness is per tea, not global.

Return ranked candidates with per-hit evidence, make multi-owner/tied top results explicit conflicts, filter
active rows in SQL, and add a repair report for duplicate authoritative aliases. Do not auto-merge.

### CAT-P1-2 — review decision consumption is not concurrency-safe

Two CLI processes can load the same pending decision and both enter approval. Target-tea locking does not make
the decision single-consumer. Use findByIdForUpdate, @Version, or atomic UPDATE ... WHERE decision='pending',
assert one affected row before canonical write, and add a two-transaction test.

### CAT-P1-3 — field decisions are not a complete curation model

PR #124 records type/corroboration and keeps brand non-selected. Remaining gaps:

- pre-existing curated rows have no baseline selected claims;
- no explicit field-decision command promotes/rejects proposals;
- identity and field approval are coupled for most facts;
- provenance repair/backfill is unspecified.

Backfill curated claims and add explicit per-field accept/reject/keep-current actions before a second source.

## P1 — Android ownership and reliability

### AND-P1-1 — implement UUID CatalogTeaRef / physical TeaSample

Room v6 still uses unique numeric catalogTeaId, so users cannot record multiple purchases/lots of one canonical
tea. The v7 design is correct in direction but still contains obsolete Long examples below its UUID amendment.
Rewrite it into one executable UUID design before migration code.

- CatalogTeaRef(publicId UUID) owns refreshable shared facts and lifecycle.
- Many TeaSample rows reference one ref.
- sample name/notes/flavor/purchase/photo/placement remain user-owned.
- legacy numeric ID exists only in an upgrade stub.
- backup v2 and Room migration use the same reconciliation.
- unresolved refs remain visible and recoverable.

Test v6->v7 losslessness, multiple samples/ref, custom and no-board samples, multi-board placement, all child
tables/photo paths, unresolved refs, merge/retraction, backup v1->v2, and v2 round-trip.

### AND-P1-2 — server/app contract is not executable

Server DTOs now carry UUID/lifecycle; Android still decodes numeric IDs and ignores them. Freeze JSON fixtures
in both modules and test active, merged, retracted, broken-chain, unknown-ID, and old-server cases. New lookups
use UUID; numeric lookup is upgrade compatibility only.

### AND-P1-3 — enrichment can overwrite user-owned fields

applyEnrichmentPatch prefers nonblank catalog values and always replaces type despite blank-only comments.
The v7 split should refresh catalog refs only. Until then, fill blank-only or track field ownership/dirty state.

### AND-P1-4 — queued enrichment is process-lifetime work

App-scope coroutine plus startup resumePending is not durable. If background completion is promised, adopt
AndroidX WorkManager 2.11.2 with unique work/sample, network constraint, backoff, and idempotent calls.
Otherwise describe the real weaker behavior as retry on next app open.

### AND-P1-5 — file/image/update reads need one bounded-I/O policy

- image input uses readBytes before dimensions are inspected;
- photo copy/import has no byte or validated-type cap;
- deletion uses string-prefix containment rather than canonical parent equality;
- update downloads have no size cap or atomic finalization;
- release logs expose URLs, content URIs, and local paths.

Build one tested bounded-stream policy: declared/actual caps, sniffed allowed types, safe bounds decode,
canonical parent checks, randomized temp files, fsync, atomic move, cleanup, and redacted logs.

### Android quality backlog

- Escape percent and underscore in offline Room LIKE search.
- Cache detail by public UUID and persist add/edit drafts across process death.
- Add Android 12+ dataExtractionRules; use LocalResources.current; resolve actionable lint warnings.
- Add Compose tests for add/edit, placement, backup/restore, OCR, updater errors, large fonts, screen readers,
  and narrow/wide layouts.
- Add detekt/ktlint to check or correct the documented CI contract.

## P1 — privacy, abuse resistance, release, and operations

### PRIV-P1-1 — publish one accurate data-flow and retention contract

Runtime egress includes catalog queries, optional pasted/OCR text, opt-in diagnostics, update traffic, and
potential remote images. Copy still mentions unimplemented geopoints and does not describe all flows together.

- give raw miss strings a fixed maximum age; retain only aggregates afterward;
- remove raw typed names from ResolveService conflict logs;
- add DTO size/collection limits before diagnostic binding and lower the route body limit;
- keep remote catalog images disabled until first-party mirrored, licensed, attributed, and disclosed;
- version a table of trigger/consent, exact fields, recipient, purpose, retention, deletion, and offline behavior.

### PRIV-P1-2 — evictable per-client windows can reset abuse budgets

Caffeine eviction grants a fresh fixed window. Keep per-client fairness, but add edge/global overload budgets
and expensive-operation semaphores that key churn cannot reset. Redis is not justified at current scale.

### OPS-P1-1 — supply-chain evidence is produced but deploy does not consume it

CI signs/attests images, but production accepts arbitrary SERVER_IMAGE and OCR_SIDECAR_IMAGE; runbooks still
show latest tags. Require digest syntax and signature/attestation verification in the deploy entrypoint, plus a
release ledger and rollback rehearsal.

### OPS-P1-2 — backup and single-host controls remain manual/overprivileged

- backup credentials have folder-wide storage.admin;
- off-box upload and restore evidence are optional/manual;
- SSH is open to 0.0.0.0/0 and serial console is enabled;
- no external uptime/TLS/disk/restart/backup-age alerts are evidenced;
- Docker log rotation is absent;
- mixed OCR/API load has not been proven on the real VM.

Narrow IAM to one bucket/prefix, automate encrypted/versioned uploads and isolated restores, restrict admin
access, disable unused serial access, rotate logs, and alert on health/TLS/disk/memory/restart/backup age.
Reuse Spring Actuator/Micrometer; add Prometheus only if an operator will scrape it.

### OPS-P2 — CI/runtime drift and vulnerability coverage

- OCR CI/runtime Python versions differ; test the actual runtime or a small supported matrix.
- OCR readiness should fail after an unrecoverable worker-pool failure.
- OSV does not cover container/OS packages; add Trivy or equivalent.
- Gradle 10 deprecations are already visible in fresh builds.

## Open-source reuse decisions

| Need | Decision | Reuse | Boundary |
|---|---|---|---|
| Durable Android retry | Adopt with Room v7 | AndroidX WorkManager 2.11.2 | Official unique work, constraints, backoff. Idempotent API semantics still required. |
| Tester updates | Adopt now operationally | Obtainium | Avoid enabling the unsafe custom manifest path. |
| Production update trust | Reuse threat model | TUF roles + offline Ed25519 manifest | No maintained official Android/JVM client was established; do not import abandoned Java code. |
| Catalog search | Keep | PostgreSQL pg_trgm + curated aliases | Require gold-set failure before another service. |
| Scrape pilot | Keep after gates | httpx, selectolax, Protego, pypinyin; Scrapy later | Local, pinned, fixture-backed, facts-only. |
| Region map | Defer UI; adopt later | MapLibre Native Android 13.0.2 | GMS-free/offline regions; use licensed bundled/first-party tiles. |
| Metrics | Adopt minimally | Spring Actuator/Micrometer | Add storage/UI only when used operationally. |
| Crash telemetry | Keep | ACRA + narrow endpoint | Finish limits, retention, and consent copy. |
| OCR | Keep measured engine | RapidOCR/ONNX | Change only against the real-photo corpus; add the tea glossary before correction. |
| Cross-script identity | Curate | pypinyin + Palladius/alias table | Libraries generate candidates; humans confirm identity. |
| Queue/cache/admin platform | Reject now | Kafka/RabbitMQ/Redis/generic CMS | PostgreSQL, WorkManager, and local CLI cover current scale. |

Official current checks:

- WorkManager: https://developer.android.com/jetpack/androidx/releases/work
- MapLibre Android/offline regions: https://maplibre.org/maplibre-native/android/api/
- Obtainium: https://github.com/ImranR98/Obtainium

## Research disposition

No new research run should be created now.

- Runs 01–21 cover sources, maps, infrastructure, AI/egress, search, telemetry, updater, OCR, breadth, and
  scrape foundation.
- Run 14 stays reserved for just-in-time Yandex async/model/price verification before the dormant AI tier.
- Current blockers are executable invariants, migration rehearsal, privacy decisions, and operator actions.
- Deep research now lives in ../researches/projects/TeaTiers; research/ here is only a pointer.

The next evidence should be tests and implementation: transition tests, URL/SSRF fixtures, decision-concurrency
tests, production-like V6 rehearsal, Room v7 fixtures, and signed-manifest threat tests.

## Ordered implementation plan

### Phase 0 — protect users and reconcile production

1. Restrict/back up/recover-test the release key.
2. Back up production and rehearse pre-V7 through V12 offline.
3. Build a digest-only verified deploy with pre/post contract probes.
4. Deploy current server and record the release/migration ledger.

### Phase 1 — complete ingestion safety

1. Implement reviewed/apply-authorized run states.
2. Enforce hosts, redirects, and SSRF-safe URL resolution.
3. Persist/bind immutable raw evidence.
4. Strict type/country/region/schema validation and apply-time guards.
5. Lock/CAS decision consumption.
6. Candidate sets, ambiguity, and active-only queries.
7. Backfill claims and add explicit field decisions.

### Phase 2 — Android ownership/API cutover

1. Rewrite v7 design as one UUID-only executable spec.
2. Implement refs/samples, lossless migration, and backup v2.
3. Freeze cross-module JSON contracts and use UUID lookups.
4. Refresh refs only; never overwrite sample-owned fields.
5. Add WorkManager and bounded I/O.

### Phase 3 — release and operations

1. Use Obtainium or implement the offline-signed manifest.
2. Complete tag/version/package/cert/emulator/SBOM/image-scan gates.
3. Enforce digest deploy, least-privilege backup, automated restore, alerts, and logs.
4. Publish versioned privacy/data-flow/retention copy.

### Phase 4 — one-source pilot

1. Owner signs off current ToS and live robots/host evidence.
2. Commit parser fixtures; unit tests have no network.
3. Run locally in dry-run and stage 5–10 facts-only observations.
4. Review every identity and mutable field.
5. Apply only through reviewed run state.
6. Audit prohibited content and test replay, correction, collision, merge, and withdrawal.

## Readiness gates

Ready for scraper only when production UUID continuity is deployed/rehearsed; source/host/redirect/SSRF and
evidence gates are enforced; runs distinguish ingest/review/apply; dry/running/unreviewed/stale data cannot
write; unknown semantics cannot coerce; ambiguity is explicit; claims and decisions are concurrency-safe;
retraction preserves identity but removes content; and replay/correction/collision/withdrawal tests pass.

Ready for the next public APK only when production serves UUID/lifecycle; Room and backup migrations are
lossless; key recovery is tested; updater is disabled/Obtainium-based or independently signed; privacy copy
matches egress/retention; bounded I/O and critical UI/accessibility tests pass; and digest deployment,
off-box backup, restore evidence, alerts, and rollback are operational.
