# TeaTiers design & architecture review — 2026-06-18 (post-OCR slice, deep code pass)

Scope reviewed:
- `task.md`, `architecture.md`, `context/plan.md`, `context/decisions.md` (#1–#99)
- prior review files under `context/review/` (5 earlier passes)
- focused plans: `context/shared-teas/`, `context/photos/`, `context/polish/`, `context/flavor-system/`
- research runs + ratings under `research/` (incl. the just-judged 10/11/12)
- the **actual source** of the Android app, backend, infra/CI, backup, and the new OCR slice — not just the docs

`task.md` / `architecture.md` are legacy sketches; authority is `context/plan.md` + the append-only
`context/decisions.md`. This pass **supersedes `2026-06-18-current-design-architecture-review.md`** for
active findings: that review was dispositioned in decision #96, and decisions #97–#99 then changed the
state. Unlike the prior passes (which were doc-led), this one read the implementation and verified each
new finding against the code, so most items below are **new** and were not surfaced before.

---

## 1. What's resolved since the last review (don't re-open)

- **Privacy copy vs `sourceText`** — fixed (#96); `settings_about_privacy` now discloses the pasted
  packaging description. [strings.xml:74](app/src/main/res/values/strings.xml:74)
- **Backup OOM / zip-bomb** — hardened (#97): per-photo, per-JSON, count, and a 256 MB cumulative
  decompressed cap. [BackupArchive.kt](app/src/main/kotlin/com/macsia/teatiers/data/backup/BackupArchive.kt)
- **X-Forwarded-For spoof / dev-8080 exposure** — verified non-issue + documented; dev compose bound to
  `127.0.0.1` (#98). [docker-compose.yml:36](docker-compose.yml:36)
- **Curated seed** — 13 → 50 → **100** (#93/#95), staged toward ~300.
- **Typo search, GHCR cutover, off-box backup, reference-image list, reference-vs-mine flavor** — all
  live (#84/#91, #83, #82, #89, #90).

The direction is still coherent and correct: local-first app, no accounts, backend as a catalog/enrichment
service holding no user data, Postgres catalog DB, no open-ended web crawling, no arbitrary web images, no
GMS-only MVP dependency. **Nothing below argues for a redesign** — these are correctness fixes, a few
hardening items, and one genuinely open architectural fork (OCR).

---

## 2. New findings (verified against code)

### P1 — Background enrichment permanently dead-ends a tea when two differently-typed names resolve to the same catalog row

The `teas.catalogTeaId` column has a **UNIQUE index** (added in the second-pass review as a schema
invariant), but `applyPatch` writes it with a **blind UPDATE** and never pre-checks for an existing link:

- UNIQUE index: [Entities.kt:64](app/src/main/kotlin/com/macsia/teatiers/data/db/Entities.kt:64)
- blind `UPDATE ... SET catalogTeaId = :catalogTeaId WHERE id = :teaId`: [TeaDao.kt:172](app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDao.kt:172)
- `applyPatch` sets `catalogTeaId = detail.id` unconditionally: [TeaEnrichmentManager.kt:131](app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt:131)
- the broad `catch (_: Exception)` flips the row to `FAILED`: [TeaEnrichmentManager.kt:92](app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt:92)
- a `findTeaIdByCatalogId` query already exists but is **not used** in the patch path: [TeaDao.kt:62](app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDao.kt:62)

**Scenario (plausible in plain ru+en use):** add tea A "Tieguanyin" → resolves to catalog id 5, links.
Later add tea B "тегуанинь" — the local name-matcher treats it as a different tea (no dedup), so it's a
fresh user-tea → enrichment resolves it server-side (fuzzy / Wikidata) to **the same** catalog id 5 →
`patchEnrichment` throws `SQLiteConstraintException` on the UNIQUE index → caught → **B is marked FAILED**.
The user taps "Повторить уточнение", which re-resolves to id 5, hits the same constraint, and FAILs again —
**a permanent dead-end with no recovery**. The failure is silent (only a per-card retry hint).

Why no test caught it: `FakeTeaDao.patchEnrichment` is a plain map write with no UNIQUE enforcement, so
`TeaEnrichmentManagerTest` cannot reproduce it.

**Fix:** in `applyPatch`, call `findTeaIdByCatalogId(detail.id)` first; if it returns a *different* local
tea, treat it as a same-catalog dedup (merge into the existing user-tea, or at minimum mark DONE without
writing the duplicate link) instead of letting the constraint throw. Add a Room-instrumented test, or model
the UNIQUE index in `FakeTeaDao`.

### P1 — `/teas/ocr` shares the `/resolve` rate-limit window and burns the token before cheap validation

Both endpoints call the *same* limiter on the same per-client window, and OCR consumes it before the
empty/size checks:

- resolve: [TeaController.kt:62](server/src/main/kotlin/com/macsia/teatiers/controller/TeaController.kt:62)
- ocr: [TeaController.kt:77](server/src/main/kotlin/com/macsia/teatiers/controller/TeaController.kt:77) (token acquired at :77, `isEmpty`/`size` only checked at :78–79)

The intended UX is "scan photo (OCR) → review → resolve". With one shared 20/min bucket, a user who scans
a few labels can exhaust the budget and get **429s on the actual catalog lookup** (or vice-versa) — and the
two paths have very different cost profiles (OCR = sidecar CPU; resolve = Wikidata/LLM spend). Empty/oversized
uploads also still burn quota.

**Fix:** give OCR its own window; move `isEmpty`/`size` guards *before* `tryAcquire`. This is also the point
where the hand-rolled limiter has earned a replacement — see §3 (Bucket4j + Caffeine).

### P1 — Production containers run with zero privilege hardening; this matters most right before the Python OCR sidecar lands

`infra/deploy/docker-compose.prod.yml` sets no `cap_drop`, `read_only`, `security_opt:
[no-new-privileges:true]`, `pids_limit`, or `tmpfs` on any service (only `mem_limit` on `server`). An RCE in
the public-facing server or Caddy gets a full default capability set and a writable root FS. **Slice 1b adds
a FastAPI + onnxruntime sidecar** — a much larger attack surface than a JRE, processing attacker-supplied
images — onto the same host.

**Fix (before the sidecar merges):** add `security_opt: ["no-new-privileges:true"]` and `cap_drop: [ALL]`
to every service (Caddy needs `cap_add: [NET_BIND_SERVICE]`); make `server` and the future sidecar
`read_only: true` with explicit `tmpfs: [/tmp]`.

### P1 — VM has no measured memory headroom for the OCR sidecar; `db`/`caddy` are uncapped

`mem_limit` is set only on `server` (1500m); `db` and `caddy` are unbounded, on a **4 GB / 2 vCPU @ 50%
core-fraction** VM ([infra/compute.tf](infra/compute.tf)). RapidOCR PP-OCRv5 + onnxruntime typically needs
~400 MB–1 GB resident plus CPU spikes. Added unbounded to a box already running a 1.5 GB JVM + Postgres +
Caddy + OS, concurrent OCR risks the OOM killer reaping Postgres. The 50% core-fraction also throttles
inference.

**Fix:** measure the sidecar's RSS first (this is what decision #99's "resize the VM only if needed" should
be gated on), set explicit `mem_limit`/`cpus` on `db`, `caddy`, and the sidecar, and do the arithmetic
against 4 GB before deploying — a bump to 8 GB (or core-fraction 100%) is the likely answer.

### P1 — OSV-Scanner was chosen (#96) but is not wired into CI

CI runs only Gradle `check` + Android `assembleDebug`; there is no OSV/Dependency-Check workflow or advisory
task in `.github/workflows/`, `app/`, `server/`, or `infra/`. Pinned versions and unit tests are **not** an
advisory gate, so a vulnerable transitive dependency can land without a failing build. The decision is made;
only the wiring is missing.

**Fix:** add the official `google/osv-scanner-action` reusable workflow (recursive scan on PR + main,
optionally a scheduled full scan), and update the plan §7.1 release-gate line from "OWASP Dependency-Check"
to OSV-Scanner.

### P2 — `applyPatch`/backup `replaceAll` orphan photo files on disk (unbounded growth)

`replaceAll` deletes only board/tea rows (cascade); it never touches photo files under
`<filesDir>/tea_photos/`: [TeaDao.kt:269](app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDao.kt:269).
A destructive backup import writes the new photos, then `replaceAll`, leaving the entire previous photo set
as dead files forever; repeated restores grow `filesDir` without bound (the bytes are also re-imported under
fresh UUIDs, so a backup-of-a-backup doubles on disk). `AndroidPhotoStore.delete()` exists but is never
called for orphans. This is distinct from the OOM hardening (#97).

**Fix:** after `replaceAll`, diff on-disk `tea_photos` files against the paths now referenced in the DB and
delete the unreferenced ones (best-effort, off the DB transaction); a periodic orphan sweep on app-start is
also cheap.

### P2 — The three backend HTTP clients use `SimpleClientHttpRequestFactory` (no pooling, no keep-alive)

`OcrClient`, `FoundationModelsClient`, and `WikidataSparqlClient` all build `RestClient` on
`SimpleClientHttpRequestFactory` (JDK `HttpURLConnection`, no connection pool). Every Wikidata/LLM/OCR call
re-opens a socket and re-does TLS — avoidable latency and FD churn, worst on the retried LLM path.

**Fix:** swap the request factory once (shared by all three) to a pooled client —
`HttpComponentsClientHttpRequestFactory` (Apache HttpClient 5) or `JdkClientHttpRequestFactory`. Keep
`RestClient`; no need for WebClient/WebFlux.

### P2 — LLM daily budget under-counts real spend; the client retries non-idempotent 4xx

`LlmDailyBudget` is charged once per resolve, but `FoundationModelsClient.chatJson` retries
`props.maxAttempts` (=2) on the broad `RestClientException` catch — which includes `4xx`. So (a) the
"global daily cost ceiling" can be 2–4× under the actual billable call count, and (b) a 400/401/429 is
retried after a fixed 700 ms sleep, burning a second blocked-or-billed call and parking one of only 2–4
enrichment-pool threads.

**Fix:** charge the budget per actual model call (or divide the cap by `maxAttempts` and document it), and
narrow the retry to `HttpServerErrorException`/`ResourceAccessException` with jitter. This retry+budget
cluster is the strongest case for **Resilience4j** (`Retry` + `RateLimiter`/`Bulkhead`) instead of three
hand-rolled `repeat + Thread.sleep` loops — see §3.

### P2 — OCR sidecar URL is taken raw from env with no validation (SSRF foot-gun)

`OcrClient` posts to `"${sidecarUrl}/ocr"` with no scheme/host check; the sanitizer cleans only the response
*text*, never the URL. It's operator-controlled (so lower severity), but nothing enforces the "private
compose network only" claim — a typo or copy-paste of a metadata/internal address turns the backend into a
request forwarder that streams uploaded image bytes to that host.

**Fix:** validate `sidecarUrl` at startup (require `http`/`https`, ideally restrict to the compose service
host / private range) and fail fast.

### P2 — `GET /teas/{id}` and `/resolve` carry a 4-collection N+1

`TeaCatalogService.detail()` → `findById` then touches `images`, `names`, `descriptions`, `flavors` (all
LAZY `@OneToMany`) with `open-in-view: false` ⇒ 1 + 4 SELECTs per detail; `ResolveService` re-calls
`detail()` after `saveAndFlush`, doubling it. The summary/search path already solved this with a
`findAllWithNames` fetch-join.

**Fix:** add an `@EntityGraph`/subselect detail query (you can't fetch-join 4 `List` bags in one query —
`MultipleBagFetchException`; use `Set` or split graphs), and have `ResolveService` reuse the loaded entity
instead of re-querying.

### P2 — Single-VM / single-AZ has a tested backup but no written restore runbook (RTO)

Off-box S3 backup + a real restore rehearsal shipped (#82) — genuinely good. The residual gap is operational:
there is no documented RTO / "VM dies → rebuild from IaC + restore latest dump" runbook, and a zone outage
is total downtime. Acceptable at hobby scale, but it should be a **written accepted risk** with a named RTO,
not an implicit one (Managed PostgreSQL stays the upgrade path).

### P3 — assorted

- **Backend fixed-window limiter allows ~2× burst at the window boundary** and only purges its client map
  above 10k entries (an O(n) scan on the hot path when it does). Folds into the Bucket4j/Caffeine swap (§3).
- **Wikidata zh label filter `LANG(?zh)="zh-hans"` under-matches** (most items are tagged `zh`/`zh-cn`);
  broaden to `STRSTARTS(LANG(?zh),"zh")`. Low priority while zh is deferred (#94).
- **`AndroidPhotoStore` logs the full `content://` source URI** on failure unconditionally (not
  `BuildConfig.DEBUG`-gated, unlike the network logger). Gate it.
- **CI actions pinned to floating tags** (`@v6`/`@v5`/`@v4`), including `publish-image.yml` which has
  `packages: write` and pushes the image prod pulls — a hijacked tag is a direct path to prod. Pin to commit
  SHAs (Dependabot keeps them current). Prioritize the publish workflow.
- **Published image is unsigned, no provenance/SBOM, and the VM pulls a mutable `:latest`.** Enable buildx
  `--provenance --sbom` + cosign keyless signing; deploy by digest or `:<sha>` (the workflow already pushes
  `:<sha>`).
- **`backup_storage` SA holds `storage.admin` folder-wide** (its own TODO says tighten to `storage.uploader`
  on the one bucket). The static key sits on an internet-facing VM; leak ⇒ read/delete every bucket incl. tf
  state. Tighten to a bucket-scoped binding.
- **`tofu plan` runs on every PR with editor-scoped creds in the job env**; scope `YC_SA_KEY` to least
  privilege and gate `plan` to same-repo PRs.
- **Prod compose dropped the `server`/`caddy` healthchecks** the dev compose has, so post-reboot Caddy can
  proxy to a not-yet-ready server (502s). Port the `actuator/health` check into prod.

---

## 3. Architecture / design assessment (the core ask)

### 3a. OCR is the one open architectural fork, and it is currently half-decided

This is the most important item in the review. Decision #99 shipped a **server-side** OCR contract
(`POST /teas/ocr`: multipart image → RapidOCR sidecar → text), but **research run 10 explicitly discarded
server-side photo upload** as a privacy/ops mismatch — that was gemini's *losing* answer, and the winning
opus answer keeps photo bytes on-device behind an `OcrEngine` interface
([RATING.md:17,46](research/10-photo-ocr-grounding/RATING.md:17)). The decision is *defensible* (on-device
RapidOCR on no-GMS Android is genuinely hard; the image is held only for the call and never stored), but:

1. **The decision text frames RapidOCR as "user choice" without acknowledging it reverses the research it
   cites.** The log's strength is flagging when an implementation diverges from its research; #99 is the one
   place that didn't.
2. **The product surface hasn't caught up.** Global privacy copy still says photos never leave the device;
   there is no app caller yet. The architecture has materially moved from "photos never leave the device" to
   "an explicitly chosen image may be uploaded," and the copy/UX must catch up *before* the scan UI ships.

**Decide before slice 3 (the app scan UI):** commit to server-side (record that it supersedes the run-10 MVP
recommendation; make the scan opt-in **per image**, never auto-upload existing tea photos; preview+confirm
the extracted text before it becomes `sourceText`; rewrite privacy copy to say packaging images are uploaded
only on an explicit scan, processed for text, and not stored; verify the sidecar never logs image bytes or
text) **or** revert to the run-10 on-device `OcrEngine` plan. Right now it's a contradictory in-between.

### 3b. The entire AI-enrichment subsystem is built but **dormant** — make "MVP ships with no AI" an explicit decision

Decision #88 left the Foundation Models tier **off in production** (no key on the VM). That means runs
03/04/06/07/08/12 + the #65 bake-off + the whole `LlmEnrichmentService`/`FlavorPrompts` stack currently
produce **zero runtime behaviour** — enrichment is Wikidata + 100 curated teas + cache only. Combined with
the seed at 100/300, **first-run catalog quality rests entirely on curated-seed coverage matching the user's
habits.** This is a reasonable MVP, but it's being chosen implicitly. Decide deliberately: ship MVP with
enrichment off and **finish the seed to ~300** (the cheaper, lower-risk path), or spend the ~$0.57/300-tea
async backfill (run 11's estimate) to populate flavors. These two interact and should be one decision.

Two latent blockers exist *the day the LLM tier is switched on* (neither is a today-problem, but "enabling
enrichment" is not a config flip):
- **`aliceai-llm-flash` is hardcoded as the production primary** in #65 and `context/flavor-system/prompts.md`,
  but the exact API model-list **slug is still unverified** on Yandex's official table (only `aliceai-llm`
  appears). Verify live or the resolve tier 404s.
- **The flavor prompts are still zh-aware** (Han-routing to the Qwen3 booster, `names.pinyin`) while #94
  deferred zh. Reconcile the zh-deferral with the prompt contract before enabling.

### 3c. Flavor-backfill (run 11) has an unbuilt schema prerequisite the reviews missed

Run 11's plan (reuse the LLM tier as a batch job + per-dimension provenance + confidence) is sound and was
correctly judged. But the **current `tea_flavor` table cannot represent it**: V1 is
`tea_flavor(tea_id, dimension, intensity)` with `UNIQUE(tea_id, dimension)` — **no per-dimension `status`,
`confidence`, `provenance`, or `enrichment_run`**; `unverified` lives only at the `tea` level. So the day
that workstream starts it needs a schema migration that no plan/decision currently lists as a prerequisite.
Flag it in the plan now so it isn't discovered mid-build.

### 3d. Open-source the reviews haven't named

Prior passes thoroughly endorsed pg_trgm, Coil, Room/Retrofit/Hilt, Testcontainers, WorkManager-as-future,
RapidOCR/PaddleOCR, and OSV-Scanner. Concrete **gaps**:

- **No crash/error telemetry — and local-first makes that worse, not better.** With no backend session and a
  live destructive-migration risk, a post-release crash that wipes a user's DB is **invisible** to the
  developer. **Sentry** ships a self-hostable, GMS-free Android SDK that can point at the existing Yandex VM
  (no Google dependency, fits the no-GMS lock). This deserves a deliberate keep/skip decision, not silent
  omission. (ACRA is the lighter self-hosted alternative the plan already mentions in passing.)
- **The rate-limiter + budget + retry cluster** (hand-rolled in 4 places, with the boundary-burst and 4xx-retry
  bugs above) is the clearest "stop reinventing" target: **Bucket4j** (token-bucket per key, smooths the burst)
  + **Caffeine** (`maximumSize`/`expireAfterAccess` for correct bucket eviction) + **Resilience4j** (`Retry`
  with backoff+jitter, `Bulkhead` for the enrichment pool). All Apache-2.0, mature, and less subtle than the
  current `synchronized(Window)` + manual purge + `repeat/Thread.sleep`.
- **OCR image preprocessing.** If server-side OCR proceeds, the eslav rec model's small training set (§3e)
  makes preprocessing disproportionately important; PP-OCRv5's doc-orientation/unwarping modules (already in
  the ONNX package) are the right lever for curved/glossy packaging — name it in the sidecar plan rather than
  discovering it after accuracy disappoints.

No change warranted on search/image/DI/test stacks — those are genuinely well-covered, and there's still no
measured need for Meilisearch/OpenSearch/Typesense after pg_trgm.

### 3e. Decisions that are sound — keep as-is

On-demand enrichment, the no-VPN/Yandex-native lock, and "no web crawling" (#17/#18/#45) are well-litigated
and internally consistent — no re-examination warranted. Flavor-profile subjectivity is handled about as
well as it can be (code-side clamping, confidence gates, central-tendency guard, user override always wins,
run-11's extreme-value recall gate). Keep the custom Compose nav and hand-rolled drag (tested, fit the
current size — see the latent note below). Keep self-hosted Postgres now that off-box backups are live.

**What the codebase does notably well** (verified): prompt-injection defense is layered and real
(`EnrichmentText` strip + delimiter-wrapped data-not-instructions prompt + shingle-overlap blurb-copy
rejection); model output is never trusted (strict `json_schema` + full Kotlin re-validation + coercion);
transaction boundaries keep the slow LLM/Wikidata calls outside any DB lock; insert races are handled with
`saveAndFlush` + `DataIntegrityViolationException` re-read backed by real DB unique indexes; the daily budget
fails *closed*; secrets hygiene is clean (no state/keys/`.env` in git, Lockbox + sensitive outputs, Postgres
never published); the server runs non-root from a digest-pinned base; backup zip-bomb bounds and FileProvider
scoping are tight; and reorder math + DAO multi-table writes are pure/transactional/tested.

> **Latent footgun (not a bug today):** the custom nav renders only `backStack.last()` with no per-destination
> `ViewModelStoreOwner`, so every screen ViewModel is effectively an Activity-scoped singleton that never gets
> `onCleared()` on back-navigation. State leaks are masked only because each `bind()` defensively resets all
> state first. At minimum, document that the `bind()` reset is **load-bearing**; this is the concrete failure
> mode that would justify revisiting Navigation-Compose later.

---

## 4. Carried-forward known items (still open, already tracked)

- **P0 — Room destructive migration** (`exportSchema=false` + `fallbackToDestructiveMigration`, now across 6
  versions). Decided to defer to M5 (#70.1), but it is the hard first-public-APK blocker: declare the public
  schema version, turn on schema export + commit the baseline, drop destructive fallback in release builds,
  add `MigrationTestHelper` tests. The **baseline-capture** is the long pole, not writing `Migration` objects.
  Note `context/photos/plan.md` still presents destructive migration as acceptable "pre-launch" — stale guidance.
- **P2 — Curated seed 100 → ~300** (#95). Continue staged; reuse as the search/flavor/transliteration gold corpus.
- **P2 — `values-en` not shipped** while the picker offers English; either ship it or label English "not ready"
  for release (zh correctly deferred, #94).
- **P2 — Offline catalog cache search is substring-only** (intentional for MVP, #96); fine if it stays documented.
- **Doc hygiene:** plan §7.1 release-gate rows for GHCR / off-box backup / dependency-scanner still read
  partly stale vs decisions #82/#83/#96 — refresh with "superseded by #N" pointers (keep `decisions.md`
  append-only).

---

## 5. Research follow-ups

Runs 10/11/12 are judged and answer the open-source reuse questions; the rating workflow is healthy (run 10
correctly *overrode its own winner* on the ML-Kit-Cyrillic point). Two verification-grade runs are warranted —
one now, one deliberately later:

- **Run 13 — OCR sidecar accuracy & ops (warranted now; slice 1b depends on it).** Scaffolded at
  `research/13-ocr-sidecar-accuracy/`. The eslav PP-OCRv5 rec model is real (Apache-2.0) **but was trained on
  only ~7,031 text images**, and the ONNX RapidOCR consumes comes from a **community repo**, not an official
  PaddlePaddle ONNX release (mobile-only; no server-grade eslav ONNX). Real ru/en tea-packaging accuracy is an
  open empirical question, not a given. Pin the exact model source/license/SHA, measure CER on a real packaging
  corpus (the 100-tea seed + photos), quantify the sidecar's RSS vs the 4 GB VM, and decide preprocessing on/off.
- **Run 14 — re-verify the Yandex async / structured-output surface (later, time-sensitive).** Do **not** run
  now (it will go stale): run it immediately before the background-enrichment tier is built. Yandex appears to
  be steering Alice models toward a new OpenAI-compatible Responses API, and the `aliceai-llm-flash` slug +
  pricing are still unconfirmed — both affect run 12's locked async design.

Not warranted (would be busywork): a fresh flavor-backfill run (run 11 is comprehensive; the ≥300–500-label
trigger isn't reached), a fresh batch-vs-async run (run 12 settled it), or anything on maps/web-search/search-
engine (closed #20/#45/#79).

---

## 6. Suggested next sequence

1. **Decide the OCR privacy/product fork (§3a)** before any app scan UI — and record the supersession of run-10.
2. **Harden prod containers + size the VM for the sidecar (§2 P1×2)** before slice 1b merges.
3. **Wire OSV-Scanner CI** (decision already made) and refresh plan §7.1.
4. **Fix the duplicate-`catalogTeaId` enrichment dead-end** (P1) + add a Room-instrumented test.
5. **Separate the OCR rate-limit window** and move validation before the limiter (P1).
6. Decide **MVP-with-no-AI vs finish-seed-to-300 vs async backfill (§3b)**; if enabling AI, verify the
   `aliceai-llm-flash` slug + reconcile zh first.
7. Run **research 13** (OCR accuracy/footprint) to de-risk slice 1b.
8. Swap the three `RestClient`s to a pooled factory; adopt Bucket4j+Caffeine+Resilience4j for the
   limiter/budget/retry cluster (§3d).
9. Plan the **Room public-schema cutover** before any public APK; add the **flavor-provenance schema migration**
   to the plan as a backfill prerequisite (§3c).
10. Tidy-ups: photo-orphan sweep, SA least-privilege, CI SHA-pinning + image signing, backup integrity check +
    failure alert, prod healthchecks, crash-telemetry keep/skip decision (Sentry/ACRA).

## External references checked
- PostgreSQL `pg_trgm`: https://www.postgresql.org/docs/current/pgtrgm.html
- Android Room migrations / destructive fallback: https://developer.android.com/training/data-storage/room/migrating-db-versions
- OSV-Scanner GitHub Action: https://google.github.io/osv-scanner/github-action/
- RapidOCR: https://github.com/RapidAI/RapidOCR · eslav rec model: https://huggingface.co/PaddlePaddle/eslav_PP-OCRv5_mobile_rec
- Bucket4j: https://github.com/bucket4j/bucket4j · Resilience4j: https://resilience4j.readme.io/ · Caffeine: https://github.com/ben-manes/caffeine
- Sentry Android (self-hostable): https://docs.sentry.io/platforms/android/ · ACRA: https://github.com/ACRA/acra
