# TeaTiers post-scrape-foundation architecture and design review

Date: 2026-06-21

Status: current-state review after decision #136 and scrape-foundation commits through `21fda80`.
This file supersedes the active-finding lists in the earlier 2026-06-21 reviews where current code has
resolved or changed them. Those files remain useful history and detailed evidence for their baselines.

Audited baseline: clean `main` at `21fda80`, equal to `origin/main` at review start.

Authority order used here:

1. `context/decisions.md` for locked intent;
2. current focused designs, especially `context/design/tea-sample-split-v7.md`;
3. live code and migrations;
4. `context/plan.md`, which now contains enough stale material that it cannot be trusted without the
   first three sources;
5. older review and research artifacts as evidence, not current truth.

## 1. Executive verdict

The core product and deployment shape remains right for TeaTiers:

- a personal, local-first Android app with no account requirement;
- Room for private user data and DataStore for preferences;
- one narrow Spring/PostgreSQL shared-catalog service;
- an optional, private OCR sidecar;
- offline/operator-controlled catalog ingestion;
- one small Docker Compose host, with off-box backup;
- PostgreSQL `pg_trgm` rather than a second search service.

Do not add Kubernetes, Kafka, Redis, Elasticsearch, an account service, or cloud sync to solve the
current problems. The risks are identity, state-machine integrity, privacy, release continuity, and
operational enforcement. More infrastructure would increase the number of boundaries without fixing
those invariants.

The product idea should be stated more precisely:

> TeaTiers catalogs a person's **physical tea samples** and ranks those samples on multiple boards,
> while an optional shared **canonical tea reference** supplies names and factual metadata.

That wording resolves several recurring design confusions. A Da Hong Pao reference is not the same
thing as a 2025 vendor lot the user bought; the user's notes, photos, flavor rating, harvest, batch,
purchase, and rank belong to the sample. The shared server catalog owns only canonical reference facts.

The scrape foundation is directionally correct but is **not safe to use for a public catalog import yet**.
Its most important documented guarantees are only partially implemented:

1. stable public identity is not end-to-end and is not reproducible from the committed seed;
2. per-run robots and dry-run gates are metadata, not enforced gates;
3. changed observations cannot create a fresh review after an earlier approval;
4. merge writes omit scalar provenance and approved aliases remain non-authoritative;
5. lifecycle rows are not filtered from search and the shipped/current Android model ignores `publicId`;
6. the optional operator HTTP controller has no authentication if it is enabled.

These are implementation defects, not unanswered research questions. Fix them before building the
actual Art of Tea fetcher or importing scraped records.

Before the next public APK, the non-ingestion priorities remain release-key recovery, updater trust,
privacy/diagnostics truth, and the lossless Room v7 sample/reference migration. The new server UUID work
also changes that migration design: `catalog_refs.id` must no longer be a volatile server `Long`.

## 2. Scope and verification

Reviewed:

- `task.md`, `architecture.md`, all current files under `context/`, and the external operator notes at
  `~/context/git/TeaTiers`;
- decisions through #136 and judged research through run 21;
- all current review artifacts, with findings re-checked against `21fda80`;
- Android Room schema, repositories, backup, updater, diagnostics, catalog DTOs, UI-state boundaries,
  release wiring, and the v7 split design;
- server API, search, resolve/enrichment, diagnostics, ingestion staging, matching, review, canonical
  writes, Flyway V7-V9, lifecycle, and seed behavior;
- OCR sidecar runtime, tests/workflows, container isolation, and health model;
- CI, release, image publication, Compose, OpenTofu, SSH exposure, and backups;
- upstream Android Room/signing guidance and the primary documentation for TUF, Scrapy/Protego,
  Sigstore, Trivy, and restic.

Validation performed on the reviewed tree:

- `server/gradlew --no-daemon check`: passed;
- `app/gradlew --no-daemon check assembleDebug`: passed;
- app lint: passed;
- current Room schema export remained clean;
- host OCR tests could not run because host Python 3.14 has no `pytest` installed and no local Docker
  daemon was available. The image build/smoke workflow remains the runtime check; this is a validation
  limitation, not evidence of a failure;
- no keystore contents were read. Only the operator file mode was checked.

## 3. What is sound and should be preserved

### 3.1 Product and Android

- Local-first/no-account is the right privacy and complexity tradeoff for the current product.
- Multiple boards plus a global personal tea collection is correct.
- The locked `CatalogTeaRef`/`TeaSample` split is the right domain direction.
- User flavor ratings and catalog reference flavor must remain distinct.
- App-private photo copies plus explicit export/import are appropriate for a sideloaded, GMS-free app.
- The v6 public Room baseline is now real: schema export is committed, release builds do not use
  destructive migration, and emulator infrastructure exists.
- Compose UDF/MVVM, Hilt, coroutines/Flow, Room, Retrofit, and DataStore remain proportionate.

### 3.2 Shared catalog

- PostgreSQL is sufficient for catalog, provenance, staging, review, and search at this scale.
- `pg_trgm` plus exact substring handling is a better fit than another always-on search service.
- Separating source-record identity, canonical identity, and field provenance is the correct model.
- Human review before scraped create/merge is the right pilot policy.
- Facts-only public output and quarantined/non-public source evidence are the right content boundary.
- A stable client-facing identifier and soft merge/retraction semantics are necessary.

### 3.3 Operations

- The single Compose host is still appropriate.
- Caddy, network segmentation, non-root/read-only containers, resource limits, digest-pinned base images,
  OSV dependency scanning, and opt-in diagnostics are good choices.
- Cosign signing and GitHub build-provenance attestations are already produced and should be enforced at
  deployment rather than replaced.
- The OCR sidecar belongs on a private no-egress/no-database network and should remain optional.

## 4. Disposition of the preceding June 21 findings

| Earlier item | Current state at `21fda80` |
|---|---|
| Destructive Room migration | Resolved for v6; every future version still needs an explicit migration and device test. |
| Backup export declares a missing photo | Resolved by `1d4165c`; keep the regression test. |
| Caddy/XFF limiter bypass | The third-pass diagnosis was disproved against the pinned Caddy image, and explicit overwrite landed in `d3843dd`; resolved. |
| Deleting the final board re-seeds samples | Resolved by `03183a0`. |
| Add/edit form resets on recreation | Resolved by `92d96a5`. |
| OCR sibling cancellation/broken pool | Resolved for the identified race by `88d525d`; runtime/CI alignment and health depth remain open. |
| Scrape source CHECK and staging schema absent | Structurally resolved by Flyway V7-V9, but the behavioral gates below remain open. |
| Catalog stable public ID absent | Column/API field added, but end-to-end stability is not complete; finding is narrowed, not resolved. |
| Scrape review/provenance machinery absent | Tables/services exist, but revision, merge provenance, and reviewer-boundary guarantees are incomplete. |
| Release-key recovery | Open. |
| Updater trust boundary | Open. |
| Privacy/diagnostics operational truth | Open. |
| Canonical tea vs physical sample | Designed, not implemented. |
| LLM prompt/stub/recovery findings | Open and dormant while production has no LLM key. |
| Deploy-time image verification | Open. |
| Single-host restore/alert/SSH hardening | Open. |

## 5. Blocking findings in the scrape foundation

### FND-P0-1. Stable catalog identity is not end-to-end or rebuild-stable

Evidence:

- Flyway V7 adds `tea.public_id UUID`, but existing rows receive random values and JPA-created rows use
  `UUID.randomUUID()` (`V7__scrape_source_and_public_id.sql:24-31`, `Tea.kt:80-84`);
- `SeedTea` and `catalog-seed.json` carry no `publicId`; a blank rebuild assigns every seeded tea a new
  random public ID (`SeedModels.kt:12-29`, `CatalogSeeder.kt:62-102`);
- `tea_legacy_id_map` is populated in the current database but is not reproducible from the seed and is
  not consulted by `TeaCatalogService.detail(Long)` (`TeaCatalogService.kt:58`);
- if a rebuilt database reuses numeric id 42 for a different tea, `/teas/42` returns the new row before
  any legacy mapping could help;
- `recordOnce()` uses `ON CONFLICT DO NOTHING`, so numeric-ID reuse can silently retain a map that
  disagrees with the row now occupying that ID;
- the Android wire/domain/cache models still decode and persist only `Long id`; they ignore `publicId`,
  `status`, and `supersededByPublicId`, and call only `/teas/{id}`;
- the locked Android v7 design explicitly defines `catalog_refs.id: Long` as `server tea.id`.

Impact:

- the advertised restore/reseed protection does not exist;
- a DB rebuild can make an installed client reference the wrong tea, which is worse than a 404;
- implementing the current Room v7 design would make the volatile ID a new foreign-key contract and
  require another migration later;
- merge/retraction cannot reach the current client.

Required:

1. Put a stable UUID on every curated seed record and preserve it across all seed, import, backup, and
   restore paths. Do not derive it from a mutable display name.
2. Treat `publicId` as the only new-client catalog identity.
3. Add `publicId` to Android wire/domain/cache/ref models and use `/by-public-id/{uuid}` for detail/polling.
4. Migrate Room v7 `catalog_refs` to a UUID stored as canonical text or 16-byte value; retain the old
   numeric ID only as a compatibility field during the upgrade.
5. Make the numeric endpoint resolve through an immutable legacy map and reject any ambiguous/reused ID;
   do not direct-load the current `tea.id` as the compatibility rule.
6. Add blank-database rebuild tests using a frozen seed plus old numeric and UUID client references.
7. Filter merged/retracted rows from normal browse/search/facets and return explicit tombstone/redirect
   semantics only on identity lookup.

Acceptance:

- restore an empty database from migrations plus the committed seed and obtain byte-identical public IDs;
- an old numeric client never resolves to a different tea after identity-sequence changes;
- a new client follows a merge to the survivor and handles retraction;
- normal search never returns merged, retracted, PENDING, or FAILED rows.

### FND-P0-2. Robots, run ownership, and dry-run are not enforced

Evidence:

- `ImportRun` has robots snapshot fields, but `startRun()` accepts no robots evidence and checks only
  `active && termsSignedOffAt != null` (`SourceSite.importAllowed()`);
- a run can start with `robotsDecision=null`, and `ingest()` does not require an allowed decision;
- `ingest(importRunId, observation)` does not load the run, confirm it is running, confirm its source site
  matches the observation, or confirm parser version/time/bounds;
- `dryRun` is stored but never consulted by matching, review, or canonical write;
- a record from site B can be attached to a run for site A as long as both IDs exist;
- `finishRun()` accepts any string and silently does nothing for a missing run;
- run counters are never updated.

Impact: the code records the appearance of a safety spine without making it a transaction invariant. A
`dry_run=true` observation can be approved into the public catalog, and no code proves a fresh robots
decision existed for the run.

Required:

1. Introduce an explicit run state machine: `created -> preflight_allowed -> ingesting -> reviewed ->
   applied/failed/blocked`.
2. Require a robots fetch timestamp, status, content hash, parser decision, user agent, and allowed host set
   before the run becomes ingestible. Fail closed on fetch/parse uncertainty.
3. In `ingest()`, lock and validate the run, site, state, `dryRun`, parser version, and observation host.
4. A dry run may stage, match, and render a patch, but canonical approval must reject it.
5. Enforce statuses with domain enums plus database constraints; reject unknown finish states.
6. Update counts transactionally or compute them from records; do not expose decorative counters.

### FND-P0-3. Observation revisions stop flowing after the first approval

Evidence:

- changed facts set `source_record.status='reparse_pending'` and rebuild the normalized candidate;
- `IdentityMatchService.proposeFor()` immediately returns the first earlier `approved_new` or
  `approved_merge` decision and creates no fresh pending review;
- there is no source-record revision table or decision link to a content hash/version;
- the comment says a correction flows, but the state machine prevents that flow.

Impact: source corrections, parser fixes, and changed source facts can never be reviewed or applied after
the first approval. The record says `reparse_pending` indefinitely while the old canonical value remains.

Required:

- make parsed observations immutable revisions keyed by source identity + content hash, or version the
  existing record explicitly;
- bind each proposal and decision to the exact revision/hash reviewed;
- allow a later revision to create a new pending decision without revoking audit history;
- make approval reject stale decisions when the current content hash no longer matches;
- prove changed, unchanged, rejected, and rolled-back re-imports with integration tests.

### FND-P0-4. Approved merges do not produce complete provenance or authoritative identity

Evidence:

- `applyApprovedMerge()` fills null scalar fields, then calls
  `writeProvenanceAndAliases(... includeScalarFields=false)`;
- that flag suppresses **all** scalar provenance, including values the merge just wrote;
- `tea_field_provenance` stores a field name and source links but not the claimed value, selection status,
  or superseded decision; duplicate rows are unconstrained;
- every approved scraped name is saved as `origin='library_derived', verified=false`;
- `ReviewService` never calls `IdentityAliasService.addAuthoritative()`, despite that service's contract
  saying an approved review promotes an alias;
- future Tier-0 matching therefore cannot reuse the human-approved aliases produced by this pipeline.

Impact: a canonical row can contain scraped values without the promised field-level lineage, and the human
work spent resolving cross-script identity is not reused. Conflict/reversal cannot be reconstructed from
the provenance table alone.

Required:

1. Track exactly which fields changed during merge and write provenance for those fields in the same
   transaction.
2. Prefer a field-claim model containing `field_name`, typed/canonicalized value, source record revision,
   decision, reviewer, and validity interval; keep a separate pointer/decision for the selected value.
3. Record conflicts even when an existing verified value wins; silent fill-null/ignore loses evidence.
4. Promote explicitly reviewed identity aliases to `human_confirmed, verified=true`; keep merely derived
   aliases unverified.
5. Add uniqueness/current-selection constraints so provenance has an unambiguous meaning.

### FND-P0-5. The operator HTTP surface is unauthenticated when enabled

Evidence:

- `IngestReviewController` is disabled by default, which is good;
- if `teatiers.ingest.review-api.enabled=true`, list/create/merge/reject endpoints require no token, mTLS,
  role, or network-origin check;
- `reviewer` is caller-supplied query text and is therefore not an authenticated actor identity;
- the same Spring service normally sits behind public Caddy routing.

Impact: one configuration mistake can expose canonical catalog mutation to the internet. A self-reported
reviewer string is not an audit identity.

Required:

- safest: remove the web controller and run review/apply as a local command/profile over an SSH tunnel or
  direct operator database connection;
- if HTTP remains, bind it to a separate private listener/network and require strong operator auth (mTLS
  or a separately stored high-entropy credential), CSRF-safe request bodies, and an authenticated actor;
- add a production-context test proving the controller bean is absent and a deployment assertion that the
  flag cannot be enabled on the public listener.

## 6. Additional ingestion and catalog findings

### FND-P1-1. Matching hides ambiguity

Exact and authoritative lookups return lists, but the matcher selects `firstOrNull()`. Trigram candidates
are flattened and only the single maximum is retained. Two canonical teas sharing a normalized alias or a
near tie are not surfaced as a conflict set. Human review reduces the risk but the UI is biased toward an
arbitrary row.

Return a ranked candidate set with the reason/evidence for each hit, detect ties/multiple authoritative
owners as a hard conflict, and never allow approval of an inactive/retracted target.

### FND-P1-2. Vendor conflict handling does not match its contract

The matcher comment says brand/vendor never auto-collapse, but `brandConflict()` checks only `brand`.
`vendor` is staged and then ignored by the canonical writer. More importantly, vendor/product/year/batch
normally describe an observation or physical sample, not the canonical tea.

Keep vendor/product/harvest/grade/price/availability on source observations. Do not copy a shop name into
canonical `tea.brand` unless a reviewer explicitly establishes that it is a branded product identity.

### FND-P1-3. Database constraints do not support concurrent idempotency

- `normalized_candidate.source_record_id` is indexed but not unique, although the repository assumes one;
- there is no unique partial constraint for one pending decision per source record/revision;
- review approval has no pessimistic lock or optimistic version;
- provenance has no uniqueness/current-selection constraint;
- source record lookup/update relies on application races around two separate unique keys.

Add database constraints for the intended cardinalities and use row locking or compare-and-set state
transitions for review/apply. Application `require()` calls alone are not a concurrency boundary.

### FND-P1-4. Evidence and input validation are incomplete

`raw_evidence` exists but the importer never writes it. `allowed_hosts` is not mapped or enforced by the
service. Canonical URL, external ID, source code, parser version, operator, reviewer, and `type` lack useful
length/shape constraints. The facts guard does not validate `type`, and canonical approval does not rerun
the guard against the exact stored revision.

Add strict schemas/constraints, URI canonicalization, exact HTTPS host allowlists, evidence persistence,
and validation at both ingest and approval. Unknown tea types should reject or enter an explicit mapping
queue, not silently become `OTHER` during canonical creation.

### FND-P1-5. Public lifecycle is additive rather than integrated

`status`, merge target, and public UUID were added to entities/DTOs, but browse, fuzzy search, facets,
numeric detail, resolve exact lookup, enrichment, and Android cache semantics were not migrated as a unit.
Normal search currently includes merged/retracted rows; PENDING/FAILED user stubs also remain public.

Define one catalog visibility predicate and use it everywhere. Compatibility fields may remain in v1 DTOs,
but identity and lifecycle behavior must have contract tests spanning server and Android fixtures.

## 7. Android/domain architecture

### AND-P0-1. Amend the v7 design before implementing it

`context/design/tea-sample-split-v7.md` is strong on lossless local migration, but server decision #136
made its identity type stale. The design still makes `catalog_refs.id: Long` equal to `server tea.id`.

Amend before code:

- `CatalogRefEntity.publicId` is the primary key (UUID encoded deterministically);
- optional `legacyNumericId` exists only to upgrade v6 rows and call the compatibility endpoint once;
- migration backfills ref stubs from current `catalogTeaId` as **legacy unresolved refs**;
- a post-upgrade reconciliation resolves numeric IDs to UUIDs without touching personal sample data;
- all new writes link by UUID only;
- backup format v2 carries UUID plus optional legacy ID;
- catalog names/facts live only on `CatalogRef`; personal names remain only on `TeaSample`;
- `shortBlurb`, catalog flavor, provenance, and server enrichment state belong to the ref; personal notes,
  flavor, vendor, product, harvest, batch, grade, purchases, and photos belong to the sample.

This also resolves design questions Q1/Q2: do not duplicate refreshable catalog facts into personal rows.
The display resolver uses a personal primary name first and falls back to ref names.

### AND-P1-1. The sample/reference split remains the next major product migration

Current Room v6 still enforces one personal tea per `catalogTeaId` and requires Russian `nameRu`. That blocks
two physical samples of one canonical tea and makes English-only/Chinese-only entry impossible.

Implement the locked split with:

- explicit `Migration(6, 7)`;
- committed schema 7;
- device migration tests from the real v0.1.0 schema;
- backup v1->v2 conversion and validate-before-replace;
- multiple samples per ref and multiple placements per sample;
- at least one localized personal name, not specifically Russian;
- catalog refresh that cannot overwrite personal names, notes, flavor, or sample metadata.

### AND-P1-2. Catalog DTO compatibility is not executable

The server and app duplicate DTOs, and the current change already demonstrates drift: the server added
`publicId/status/supersededByPublicId`, while the app silently ignores them. Lenient decoding prevents a
crash but also hides an unimplemented required behavior.

Add frozen JSON fixtures produced by the server and decoded by the app for search/detail/resolve/update,
including old/new fields, unknown enums, merge, retraction, and PENDING. OpenAPI generation is optional;
contract fixtures are sufficient at this scale and cheaper than introducing a runtime gateway.

### AND-P2-1. Offline catalog detail remains incomplete

Search summaries are cached but detail is network-only. Cache the last successful detail by stable UUID,
with an explicit age and stale indicator. Personal tea data remains usable regardless of cache state.

### AND-P2-2. Remote catalog images must be first-party or explicitly disclosed

Coil can fetch arbitrary catalog image URLs directly from the device. Once images exist, this exposes the
device IP/timing to the upstream host and contradicts the current manifest/privacy language. Mirror
reviewed/licensed images to a first-party immutable URL, store license/source/hash, and add UI error/a11y
states. Do not hotlink vendor images.

## 8. Release, privacy, and server findings still open

### REL-P0-1. Release-signing recovery is still a single point of failure

The observed operator `release.jks` is mode `0644`, and no tested independent recovery record was found.
The v0.1.0 certificate lineage is permanent for installed users.

Required before another public APK:

- mode `0600` on the operator copy;
- two encrypted offline copies in separate failure domains;
- certificate fingerprint/alias/recovery locations in a password manager/operator runbook, never Git;
- an isolated restore-and-sign drill that reproduces the published certificate fingerprint.

### REL-P0-2. The custom updater still trusts server-selected trust data

The manifest provides the APK URL, hash, and signer fingerprint. The same compromised service can select a
malicious APK and its certificate, so this is consistency checking rather than an independent trust root.
The client also does not verify package name, bound file size, HTTPS/host policy, or signed metadata; it
writes directly to the final filename. Checks run only when the user opens Settings.

Either keep the updater disabled and document an external updater such as Obtainium for testers, or finish
a signed, expiring, rollback-resistant manifest whose offline public key, expected package name, and release
certificate are embedded in the app. Download to a bounded `.partial`, verify, fsync, and atomically rename.
Full TUF is architecturally stronger but currently disproportionate without a maintained Kotlin client;
apply its root/targets/snapshot/timestamp threat model to the small signed manifest.

### REL-P1-1. Release CI is improved but not a complete release gate

`release.yml` now runs Android `check`, but it does not:

- require exact `vX.Y.Z` syntax and equality with `versionName`;
- prove `versionCode` is greater than the last public release;
- run the emulator migration/upgrade suite on the tagged commit;
- install the prior public APK/data and upgrade it with the candidate;
- verify the exact expected package and certificate fingerprint;
- publish an APK SBOM/provenance attestation.

### PRIV-P0-1. Privacy and feature capability remain operationally inconsistent

- the app has detailed in-app copy but no linked versioned policy, operator contact, or deletion process;
- diagnostics can be enabled in the UI even though the release and Compose paths do not provide matching
  client/server tokens, so consent can enable a non-functional feature;
- the manifest still says only a typed query leaves the app, while OCR, optional source text, diagnostics,
  updater requests, and remote images exist;
- enabling LLM enrichment makes a literal typed name a public PENDING/FAILED catalog row;
- free text can contain personal data even if the design does not request it.

Publish and link a versioned data-flow/retention table, expose server capabilities to the app, hide disabled
features, and require a reviewed/private candidate state before user text can become public catalog data.

### SRV-P1-1. LLM activation still has unsafe dormant behavior

Before setting a production key:

- sanitize and data-delimit the typed tea name in all prompts;
- apply output/overlap sanity checks to zero-shot as well as grounded blurbs;
- keep PENDING/FAILED and `source=user` rows out of public search;
- recover or expire stale PENDING jobs after restart;
- add a real Lockbox/provisioning path and Compose environment wiring;
- change exact resolve lookup to the indexed `name_norm = lower(f_unaccent(:q))` path;
- run reserved research 14 immediately before enabling the provider/model/API.

### SRV-P2-1. Diagnostics binds an oversized body before truncating fields

The DTO has no Bean Validation and the service truncates after JSON binding. Add `@Valid` plus per-field/map
caps and a small edge/application body limit for this route. The shared APK token is only anti-spam; keep
the global daily cap.

### OCR-P2-1. CI and runtime Python versions still differ

The image runs Python 3.14 while pytest and OSV dependency resolution run on 3.12; comments still refer to
3.12. Test and scan the exact runtime image or align all jobs to a supported interpreter. Keep the image
smoke test and add a worker-level health probe only if real failures justify its cost.

## 9. Deployment and operations

### OPS-P1-1. Image identity is produced but not enforced at deploy time

Workflows sign and attest immutable digests, while Compose accepts arbitrary `SERVER_IMAGE` and
`OCR_SIDECAR_IMAGE`; docs/examples still make `:latest` easy. Add one deploy script/target that accepts
digests only, verifies Cosign identity and GitHub attestation, writes a release manifest, pulls, migrates,
starts, and records the live digest. Reject mutable tags in production validation.

### OPS-P1-2. Backups have broad credentials and no enforced restore evidence

The backup service account has folder-wide `storage.admin` and a static key on the VM. Host compromise can
delete the backup bucket as well as upload dumps. Narrow to bucket-level upload-only permissions, use a
separate restore identity, enable provider-side versioning/retention protections where supported, encrypt
the backup independently, and record automated `restic check`/restore drill results.

The existing `pg_dump` is still useful as the logical backup payload. restic can wrap the dump to add
client-side encryption, deduplication, retention, and repository integrity checking; it does not replace a
real restore rehearsal.

### OPS-P1-3. Single-host detection and access controls remain weak

- SSH is open to `0.0.0.0/0`;
- no external health, disk, certificate, backup-age, or restore-age alert is defined;
- container log rotation is not explicit;
- serial console is enabled;
- the adopted VM is not reproducibly reprovisioned by current OpenTofu because live cloud-init metadata is
  intentionally omitted.

Restrict SSH by source/VPN/bastion, disable unused access paths, add external probes and disk/backup alerts,
configure log rotation, and maintain a tested rebuild runbook. Keep `prevent_destroy`, but do not confuse it
with disaster recovery.

### OPS-P2-1. Dependency scanning does not scan shipped OS/container layers

OSV covers language dependencies, not vulnerable packages in the server/OCR/base images. Add Trivy image
scanning after build and before publish/deploy, producing a CycloneDX/SPDX artifact. Keep OSV for source
dependency graphs; the tools are complementary.

## 10. Documentation architecture

`context/decisions.md` is valuable history but is now a 2,700-line append-only stream, while `plan.md` still
contains pre-public and pre-scraping assumptions. Focused designs can also become stale independently, as
the v7 `Long` identity now demonstrates.

Improve retrieval without rewriting history:

1. keep the append-only decision log;
2. add a compact decision index (`number -> topic -> active/superseded -> implementation evidence`);
3. make `plan.md` a current milestone/status document rather than a second historical narrative;
4. mark focused designs with `status`, `superseded-by`, `implementation baseline`, and unresolved questions;
5. add a machine-checkable current release gate to CI where possible;
6. update stale runbooks immediately when retention/source/deploy behavior changes.

Examples of current drift:

- `plan.md` still shows Room migration and backup fidelity unchecked despite landed work;
- old context subplans still recommend destructive migration based on prelaunch assumptions;
- the catalog curation runbook still says misses live forever;
- app/server docs describe `catalogTeaId Long` after server public UUID adoption;
- OCR/Compose comments still name Python 3.12 while the image runs 3.14;
- architecture/task root files remain historical sketches and should stay explicitly labeled as such.

## 11. Target architecture

```text
Android (private, local-first)
  TeaSample UUID/string
    personal names, vendor/product/harvest/batch/grade
    notes, personal flavor, purchases, user photos
  BoardPlacement -> TeaSample
  CatalogTeaRef -> stable server public UUID (optional cache)
    catalog names/facts/provenance/reference flavor/images
  explicit export/import v2

Public catalog service
  CanonicalTea(public UUID, lifecycle)
  CanonicalName / Description / Flavor / first-party Image
  FieldClaim + selected decision + source-record revision
  search/read API
  resolve candidate API (non-public until accepted)

Offline operator ingestion
  SourceSite policy registry
  ImportRun with enforced robots/host/dry-run state
  immutable SourceObservation revisions + evidence hash
  normalized candidate -> ranked candidate set
  authenticated human review -> deterministic patch
  transactional apply/retract/merge with full audit

One Compose host
  Caddy -> server -> PostgreSQL
                 -> private OCR sidecar
  digest-only verified deployment
  least-privilege encrypted off-box backups
  external uptime/disk/backup alerts
```

The crawler never belongs on the API request path or the production VM. The canonical writer never accepts
raw web pages. The Android app never sends personal sample data to the shared catalog.

## 12. Open-source reuse decisions

| Boundary | Decision | Reason |
|---|---|---|
| Search | Keep PostgreSQL `pg_trgm` | Already sufficient, tested, and operationally cheap. |
| Android install | Keep Ackpine | It wraps GMS-free `PackageInstaller`; trust must be fixed above it, not by replacing it. |
| Update metadata | Use a small offline-signed manifest now; reassess TUF later | TUF addresses rollback/freeze/key-compromise classes, but a full client is disproportionate until a maintained Kotlin path exists. |
| Tester updates | Offer Obtainium as an optional external channel | Removes pressure to enable an immature in-app trust path. |
| Fixed-page parser pilot | Keep HTTPX + selectolax + Protego | Small, auditable, sufficient for fixed approved pages. |
| Sitemap/full traversal | Adopt Scrapy when traversal actually begins | Reuse robots middleware (Protego default), throttling, retries, stats, cache, and restartability rather than rebuilding a crawler. |
| Operator review | Prefer a CLI/profile over a public admin controller | A small command surface avoids adding an auth product to the public service. Picocli/Spring command wiring is sufficient. |
| Image provenance | Keep Cosign + GitHub attestations; enforce them | Production already creates the evidence; deployment must verify it. |
| Container scanning | Add Trivy | Complements OSV by scanning shipped image layers and OS packages. |
| Backups | Keep `pg_dump`; wrap/evolve with restic | Logical restore remains clear; restic adds encryption, retention, and integrity checks. |
| Crash diagnostics | Keep ACRA + first-party endpoint | Appropriate and GMS-free once capability/consent/body limits are correct. |
| AI job recovery | Start with a DB query + scheduled recovery; adopt db-scheduler only when the tier is active and durable job semantics are needed | Avoid a new queue/platform while the tier is off. |
| Search service, queue, orchestration | Reject Meilisearch/Elasticsearch, Kafka, Redis, Kubernetes | No demonstrated scale or isolation need. |

Primary upstream references checked for this review:

- Android Room migration/testing: https://developer.android.com/training/data-storage/room/migrating-db-versions
- Android signing continuity: https://developer.android.com/studio/publish/app-signing
- TUF threat model/roles: https://theupdateframework.io/docs/overview/
- Scrapy AutoThrottle: https://docs.scrapy.org/en/latest/topics/autothrottle.html
- Scrapy robots middleware and Protego: https://docs.scrapy.org/en/latest/topics/downloader-middleware.html#robotstxtmiddleware
- Sigstore/Cosign verification: https://docs.sigstore.dev/cosign/verifying/verify/
- GitHub artifact attestations: https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations/verifying-artifact-attestations-offline
- Trivy container image scanning: https://trivy.dev/latest/docs/target/container_image/
- restic repositories/backends: https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html

## 13. Recommended execution sequence

### Phase A — stop the new foundation from hardening the wrong contracts

1. Do not enable the review API or apply scraped records.
2. Append a correction to decision #136 for stable seed IDs, UUID Android refs, enforced run state,
   revision-bound decisions, and value-bearing field claims.
3. Amend the Room v7 design from `Long` catalog identity to stable public UUID plus legacy reconciliation.
4. Add failing integration/contract tests for every FND-P0 item before implementation.

### Phase B — complete catalog identity and lifecycle

1. Freeze public UUIDs into the curated seed.
2. Implement numeric compatibility through the legacy map.
3. Filter visibility consistently.
4. Add Android public-ID DTO/domain/cache/API support.
5. Add rebuild, merge, retract, and old-client contract tests.

### Phase C — make ingestion a real state machine

1. Enforce robots/host/ToS/run/dry-run gates.
2. Store immutable observation revisions and evidence.
3. Return candidate sets and explicit ambiguity.
4. Record value-bearing claims and complete merge provenance.
5. Promote human-confirmed aliases.
6. Replace or strongly isolate/authenticate the operator HTTP controller.
7. Prove idempotent apply, stale-decision rejection, correction, conflict, rollback, and second-run no-op.

### Phase D — next public APK safety

1. Complete release-key recovery drill.
2. Keep updater metadata disabled until the independent trust path is complete.
3. Publish/link the versioned privacy policy and capability-state behavior.
4. Implement the amended lossless Room v7 migration and backup v2.
5. Run the emulator upgrade on the release tag and publish identity/SBOM/provenance evidence.

### Phase E — operations

1. Digest-only verified deploy manifest.
2. Trivy image scan.
3. Least-privilege encrypted backup path and recorded restore drill.
4. SSH restriction, log rotation, and external uptime/disk/backup alerts.
5. Align OCR test/scan/runtime Python.

### Phase F — only then implement the scraper

1. Build the fixed 30-50-page Art of Tea fixture/parser pilot.
2. Persist evidence and source observations only; no canonical apply.
3. Label the matching corpus and measure candidate recall/reviewer precision.
4. Apply a tiny reviewed patch and prove rollback/no-op/rebuild identity.
5. Adopt Scrapy only when moving from fixed URLs to bounded traversal.

## 14. Acceptance gates

### Before any scraped canonical write

- [ ] stable UUIDs are reproducible from seed/backup/restore;
- [ ] Android and server contract tests use public UUID identity;
- [ ] legacy numeric ID cannot resolve to a different tea;
- [ ] normal search excludes merged/retracted/PENDING/FAILED/private candidates;
- [ ] run has current allowed robots evidence and exact source/host binding;
- [ ] dry run cannot mutate canonical tables;
- [ ] decision is bound to the reviewed source revision/content hash;
- [ ] changed facts generate a new review without erasing history;
- [ ] ambiguity is shown as multiple candidates/conflict;
- [ ] approved merge writes every selected field's value and provenance;
- [ ] reviewed aliases become human-confirmed; derived aliases remain unverified;
- [ ] operator mutation is not reachable anonymously from the public listener;
- [ ] same approved patch applied twice is a no-op;
- [ ] retract/merge/rollback preserves issued client identity.

### Before the next public APK

- [ ] release key recovered from an independent backup and fingerprint verified;
- [ ] Room v6->v7 migration preserves representative user data and photos on-device;
- [ ] backup v1->v2 conversion and restore pass before destructive replace;
- [ ] tag/version/versionCode/package/certificate checks pass;
- [ ] tagged release runs the emulator migration/upgrade test;
- [ ] updater is disabled or independently signed, package-pinned, bounded, and expiry/rollback protected;
- [ ] privacy policy and in-app capability states match deployed behavior;
- [ ] diagnostics and LLM features cannot become half-enabled by configuration drift.

### Before enabling LLM enrichment

- [ ] reserved research 14 re-verifies the current provider/model/API and policy;
- [ ] typed names are treated as untrusted data in prompts;
- [ ] candidate rows are private until accepted;
- [ ] PENDING recovery/expiry works after restart;
- [ ] exact lookup uses the indexed normalization;
- [ ] Lockbox/Compose configuration path is tested;
- [ ] budgets, retention, and policy copy are current.

## 15. Research disposition

No new research run is justified by this review.

- Run 20 already settled source selection, facts/expression boundaries, and the small parser stack.
- Run 21 already settled the intended ingestion/identity/provenance foundation.
- The new blockers are mismatches between that locked design and the implementation; another multi-model
  synthesis would delay fixes without reducing uncertainty.
- Source-specific robots/ToS/HTML verification should be a dated implementation preflight with fixtures,
  not another broad architecture prompt.
- Run 14 remains intentionally reserved and should be executed immediately before LLM enablement.

A future run is warranted only if one of these questions becomes real and cannot be settled by a small
code spike: maintained Kotlin TUF client availability, automated cross-script threshold calibration on a
labeled corpus, or a second source whose legal/technical shape materially differs from Art of Tea.

## 16. Final recommendation

Keep the architecture small and correct the contracts before adding the crawler.

The immediate target is not “more tea rows.” It is a trustworthy path from one source observation to one
reviewed, stable, reversible canonical decision without exposing personal data or breaking installed
client references. Once that path is proven, the existing open-source crawler and supply-chain tools can
grow throughput safely. Until then, bulk scraping would amplify identity and provenance defects faster
than the project can review them.
