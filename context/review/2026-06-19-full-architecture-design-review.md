# TeaTiers — full design & architecture review — 2026-06-19

Scope reviewed (fresh, code-led pass — not doc-only):
- `task.md`, `architecture.md`, `context/plan.md`, `context/decisions.md` (#1–#113), `context/brainstorming.md`
- the focused plans: `context/shared-teas/`, `context/photos/`, `context/polish/`, `context/flavor-system/prompts.md`
- the 7 prior reviews under `context/review/` (so this adds, not repeats)
- research runs 01–15 + RATING/LEADERBOARD
- the **actual source**: `server/` (Kotlin/Spring), `app/` (Kotlin/Compose), `infra/` (OpenTofu + compose + Caddy + CI), `ocr-sidecar/` (FastAPI/onnxruntime)

`task.md` / `architecture.md` are legacy sketches; authority is `context/plan.md` + append-only `context/decisions.md`.
This pass **supersedes nothing** — it extends `2026-06-18-ocr-workstream-complete-review.md` to areas that the
rate-limit-truncated 2026-06-18 passes left thin (backend internals, app concurrency seams, infra/sidecar topology)
and re-frames at the **idea/architecture altitude** the user asked for. Produced by a multi-agent
find → adversarially-verify → design-critique workflow; **every new finding below was re-checked against source**,
and the adversarially-corrected severity is the one shown (several were downgraded, one was dropped as a false positive).

> **Headline.** The system is sound and unusually well-reasoned for its size — no redesign is warranted. The two
> things most worth your attention are not bugs: **(1) catalog breadth once the AI tier is off** — the original
> breadth answer (on-demand enrichment) was switched off without a replacement, so "how does a user find the tea
> they actually bought" is unanswered (§3.1, §5.1, new research run 16); and **(2) `plan.md` has drifted from the
> decision log** (§6) and would mislead anyone building from it. Everything else is correctness/hardening/polish.

---

## 1. Resolved since the prior reviews — corrections (do NOT carry these forward as open)

Verified against current source; prior-review items now closed:

- **EXIF orientation is fully implemented.** Earlier passes flagged "camera scans OCR'd sideways (no
  `ExifInterface`)" as P2. [ImageReader.kt:81-98](app/src/main/kotlin/com/macsia/teatiers/data/photos/ImageReader.kt#L81-L98)
  now handles all 8 orientation cases (incl. transpose/transverse) and folds rotation + mirror + downscale into a
  **single** `Matrix` with one output bitmap and explicit `recycle()`. **Closed.**
- **Operator endpoint URLs are validated at startup.** `requireHttpUrl` is wired into **all three** clients —
  Wikidata ([WikidataSparqlClient.kt:27](server/src/main/kotlin/com/macsia/teatiers/client/WikidataSparqlClient.kt#L27)),
  LLM ([FoundationModelsClient.kt:26](server/src/main/kotlin/com/macsia/teatiers/client/FoundationModelsClient.kt#L26)),
  OCR ([OcrClient.kt:28](server/src/main/kotlin/com/macsia/teatiers/client/OcrClient.kt#L28)). The prior "URL used
  raw / SSRF foot-gun" item is **closed**.
- **Network segmentation shipped and is correct.** `docker-compose.prod.yml` splits edge/data/ocr with
  `internal: true` on data+ocr, so the attacker-facing sidecar has **no route** to Postgres and **no** egress — the
  earlier "flat compose network" P2 is **closed** (and verified live, #110).
- **OCR rate-limit** (own window + validate-before-acquire, #103), **#101 enrichment dead-end**, **UNIQUE
  `catalogTeaId`** (#78), **backup zip-bomb caps** (#97), **privacy copy** (#85/#96/#107) — all confirmed still
  correct in code.

**One investigated-and-dismissed:** the `file_paths.xml` `scans` cache-root is **not** dead config — a camera
`TakePicture` capture flow is fully wired ([AddTeaScreen.kt:125-127, 422-427, 486-493](app/src/main/kotlin/com/macsia/teatiers/ui/board/AddTeaScreen.kt#L486-L493))
and grants the FileProvider URI it reads. Dropping it would crash the camera scan. No action.

**Still the one hard pre-public blocker (carried, tracked, deliberately deferred to M5):** Room
`exportSchema=false` + `fallbackToDestructiveMigration` ([AppModule.kt:45](app/src/main/kotlin/com/macsia/teatiers/di/AppModule.kt#L45),
now v6). A silent destructive wipe gates everything; #111's out-of-Room row-count sentinel + the public-schema
cutover must land together before the first public APK (#70.1).

---

## 2. New findings (verified against code; severities are the adversarially-corrected ones)

Most are **P2/P3** and several sit on the **dormant** enrichment tier (AI off, #88/#100) — real enable-day defects
with zero current user impact. None argue for a redesign.

### Backend

**P2 — The Kotlin→sidecar OCR contract is entirely untested; the 502 path never exercised.**
[OcrClient.kt:43-61](server/src/main/kotlin/com/macsia/teatiers/client/OcrClient.kt#L43-L61) builds the multipart
`file` part, POSTs `{sidecarUrl}/ocr`, deserializes `{text}`, and maps `RestClientException→null→OcrFailedException`
— with **no test**. `OcrServiceTest` mocks `OcrClient` wholesale; `TeaControllerTest` covers 200/429/413/400/503 but
never the **502** (`OcrFailedException`) mapping in [CatalogExceptionHandler.kt:40-44](server/src/main/kotlin/com/macsia/teatiers/controller/CatalogExceptionHandler.kt#L40-L44).
The cross-repo contract (multipart field name, JSON key `text`) is pinned only on the Python side — a field-name or
content-type drift passes the whole Kotlin suite. **Fix:** a `MockRestServiceServer`/WireMock test on `OcrClient`
asserting the multipart shape + parse + exception mapping, and a `TeaControllerTest` 502 case. **OSS:** Spring
`MockRestServiceServer` / WireMock.

**P3 — Async enrichment worker has no graceful drain *and* no stale-PENDING recovery (two halves of one gap).**
`enrichmentExecutor` ([AsyncConfig.kt:21-28](server/src/main/kotlin/com/macsia/teatiers/config/AsyncConfig.kt#L21-L28))
never sets `setWaitForTasksToCompleteOnShutdown(true)`/`setAwaitTerminationSeconds(...)`, and there is **no**
`server.shutdown: graceful` in `application.yml`, so a redeploy SIGTERM interrupts the 2–4 running tasks and discards
up to 50 queued ones — each leaving its row at `enrichment_state='PENDING'` with no finisher, so the client polls
`GET /teas/{id}` forever. Meanwhile V2 created `tea_enrichment_pending_idx ... WHERE enrichment_state='PENDING'`
with the comment *"so a restart-recovery sweep of stuck PENDING rows stays cheap"* —
([V2__enrichment_state.sql:13-14](server/src/main/resources/db/migration/V2__enrichment_state.sql#L13-L14)) — but
**no sweep was ever built** (no `@Scheduled`, no `ApplicationRunner` other than the seeder, no finder): a dead index
backing a promised feature. *Corrected to P3:* the normal failure paths (`TaskRejectedException` → `markFailed`,
worker `catch` → `markFailed`) are correct, so the only orphan window is a hard crash/non-graceful shutdown *and* the
whole tier is OFF for MVP, so no PENDING rows exist in prod today. **Fix (before enabling AI):** drain on shutdown +
a startup/`@Scheduled` TTL sweep that flips stale `PENDING→FAILED` using the existing index (or delete the index +
comment if recovery stays deferred). Add one end-to-end test that drives a row through the `@Async` executor to DONE
— that path currently has none.

**P3 — `GET /teas/search` params are unvalidated and the endpoint is un-rate-limited.**
[TeaController.kt:42-50](server/src/main/kotlin/com/macsia/teatiers/controller/TeaController.kt#L42-L50) has no
`@Validated`/`@Size` on `q`/`locale`/`origin` (only the `/resolve` body is capped). `q` flows into the pg_trgm
native query whose `strpos(name_norm, …) > 0` OR-branch ([TeaSearchRepositoryImpl.kt:79-101](server/src/main/kotlin/com/macsia/teatiers/repository/TeaSearchRepositoryImpl.kt#L79-L101))
is **not** GIN-indexable → per-row `f_unaccent`/`strpos`. `q` is bounded only by Tomcat's ~8KB URI cap, and `/search`
(unlike `/resolve` and `/ocr`) has **no** rate limiter → an unauthenticated CPU-amplification vector. Bounded
(small catalog, `<%` branch is index-backed), hence P3. **Fix:** `@Validated` + `@field:Size(max≈100)` on `q`,
caps on `locale`/`origin`, and consider a light rate-limit on `/search`.

### App

**P2 — Background enrichment is a non-transactional read-modify-write that can clobber a concurrent user edit.**
[TeaEnrichmentManager.kt:131-166](app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt#L131-L166):
`applyPatch` reads `loadTeaRow`, merges in Kotlin, then writes `patchEnrichment` as a **separate** op — not in one
`@Transaction`. Enrichment runs on `@AppScope` and outlives the add screen; if the user edits + saves
(`updateTea` → `updateTeaFields`, overwriting the same name/type/origin columns) in the read→write gap, the manager
writes back stale catalog-merged values, silently dropping the edit (and can un-blank a field the user just cleared,
since the merge is computed against the pre-edit snapshot). **Fix:** move load+merge+write into one
`@Transaction open suspend fun` on `TeaDao` (the abstract-class DAO already hosts transactional defaults) so the
merge runs against the freshly-read row — this also centralizes the four near-identical `orKeep/orBlankFallback`
helpers next to the SQL.

**P2 — Backup import orphans the *entire* prior photo corpus on disk.**
[BackupManager.kt:116-129](app/src/main/kotlin/com/macsia/teatiers/data/backup/BackupManager.kt#L116-L129): import
writes the bundle's photos to fresh UUID files, then `dao.replaceAll` wipes all rows — whose own doc comment says
*"old ones are orphaned"* ([TeaDao.kt:286-291](app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDao.kt#L286-L291)).
The previous install's files are never deleted (their rows are gone before any sweep could enumerate them), so every
restore leaks the full prior photo set with no in-app reclaim. Distinct from the tracked replaceAll/applyPatch
orphan gap (this is the import path, full corpus). **Fix:** one reusable `PhotoStore.reconcile(knownPaths)` sweep
(delete files under `tea_photos/` not in the live row set) called after import and cheaply on app-open — the same
sweep closes the tracked orphan gap too. The pattern already exists in `deleteTea`
([TeaBoardRepository.kt:213-217](app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaBoardRepository.kt#L213-L217)).

**P2 — Edit-mode photo strip goes stale for a tea with zero placements.**
[AddTeaViewModel.kt:150-160](app/src/main/kotlin/com/macsia/teatiers/viewmodel/AddTeaViewModel.kt#L150-L160): the
edit-mode `photos` flow projects from `repository.boards` (a deduped `StateFlow`). A tea removed from every board
but not deleted is still editable via **My Teas → detail → edit**; a placement-less tea contributes nothing to
`List<Board>`, so the value-equal list is dropped by `StateFlow` dedup and the strip never refreshes — the user's
just-added photo appears to vanish (writes *do* persist; it self-corrects on re-entry). **Fix:** drive the edit-mode
photo projection off `observeAllTeas` (which includes placement-less teas) or a per-tea `observeTea(id)` Flow.

**P3 — Custom-nav restore can boot-loop on a malformed/truncated saved back stack.**
[Destination.kt:46-50](app/src/main/kotlin/com/macsia/teatiers/ui/nav/Destination.kt#L46-L50): `decodeDestination`
does unchecked `parts[1]` after `split(":")`; the encoder always emits an id, so this is unreachable *within a
version*, but the back stack round-trips through the saved-instance Bundle (`BackStackSaver`,
[TeaTiersApp.kt:26](app/src/main/kotlin/com/macsia/teatiers/ui/TeaTiersApp.kt#L26)), so OS truncation or a future
encode/version skew yields a colon-less entry → `IndexOutOfBoundsException` on restore → a *persisted* crash loop.
Trivial fix, severe-but-rare failure mode. **Fix:** `parts.getOrNull(1)` → fall back to the existing
`Destination.Boards` else-branch.

**P3 — Test fake under-models the `UNIQUE(catalogTeaId)` invariant on the insert path.**
`FakeTeaDao.insertTeas` ([FakeTeaDao.kt:82-85](app/src/test/kotlin/com/macsia/teatiers/data/repository/FakeTeaDao.kt#L82-L85))
doesn't enforce the unique index that `patchEnrichment`'s fake does. *Nuance (verified):* the most valuable case the
finding proposed (`addTea` dedup-by-catalog-id) **already** passes, and dropping the `findTeaIdByCatalogId` guard
**would** fail an existing test — so the regression is caught. The genuine residual gap is narrow: **there is no
`BackupManagerTest` at all**, so `replaceAll`/import rejecting a duplicate-link bundle (which relies on the real DB
constraint, caught only as a generic failure) is untested. **Fix:** add a `BackupManager` test for the
import-rejection path; mirroring the UNIQUE guard in the fake is optional.

### Infra / sidecar

**P2 — OCR sidecar inference has no deadline; one slow image wedges the single worker and stalls the whole OCR tier.**
[app.py:51,143](ocr-sidecar/app.py#L143): inference runs on a `ThreadPoolExecutor(max_workers=1)` via
`run_in_executor` with **no** `asyncio.wait_for`. The server's `OcrClient` read-timeout is 20s and abandons the call,
but a running CPython thread can't be cancelled and Starlette doesn't cancel executor work on disconnect — so the
worker keeps running, every queued `/ocr` blocks behind it, the server's concurrency gate fills, and the OCR tier
wedges even though the *server* is perfectly bounded. This is the sidecar-side counterpart the server-side timeout
can't fix. *Bounded* (input caps + `Det.limit_side_len=960` make a true runaway unlikely; opt-in tier; degrades to
503), hence a transient stall not an indefinite wedge → P2. **Fix:** wrap the executor call in `asyncio.wait_for(...,
timeout≈15s)` (stdlib, no dep) returning 503/504, ideally inference in a killable `ProcessPoolExecutor`/subprocess so
a runaway is actually reaped.

**P2 — The public Caddy edge has no request-body cap, no security headers, and no proxy timeouts.**
[Caddyfile:8-16](infra/deploy/Caddyfile#L8-L16) is just `encode` + `reverse_proxy server:8080`. No
`request_body { max_size }` (the only upload bound is Spring's 8MB cap *inside* the JVM — the OCR path is exactly an
attacker-supplied-image path); no HSTS/`X-Content-Type-Options`/`Referrer-Policy` (Caddy does **not** add HSTS
automatically); no `reverse_proxy` transport timeouts (a hung backend ties up edge conns). **Fix (≈10 lines):**
`request_body { max_size 10MB }`, a `header` block (HSTS + nosniff + `Referrer-Policy no-referrer`), and
`transport http { dial_timeout 5s; response_header_timeout 25s }`.

**P2 — IaC is no longer a faithful from-scratch recreate: cloud-init never emits `OCR_SIDECAR_IMAGE`.**
`docker-compose.prod.yml` references `image: ${OCR_SIDECAR_IMAGE}` for the sidecar, but
[cloud-init.yaml.tftpl:17-25](infra/templates/cloud-init.yaml.tftpl#L17-L25) writes only `SERVER_IMAGE`/`POSTGRES_*`
to `.env`, and there is **no** `ocr_sidecar_image` tofu variable. *Corrected P1→P2:* the recreate path is dormant
(cloud-init is intentionally **not** wired into VM metadata today; the live VM is hand-provisioned and its hand-written
`.env` includes the var per README), and a missing var degrades the OCR tier to 503 without taking the catalog down.
But the IaC↔compose drift means a real recreate ships a broken/partial stack. **Fix:** add the `ocr_sidecar_image`
variable, thread it through `compute.tf` locals, emit it in the `.env` block; add a CI check that every `${VAR}` in
the compose file is emitted by the template.

**P3 — `backup.sh` can leave a truncated dump that a manual restore could pick.**
[backup.sh:27](infra/deploy/backup.sh#L27): `pg_dump | gzip -c > "$out"` truncates `$out` before `pg_dump`
finishes; with `pipefail` a mid-stream failure aborts but leaves a partial `.sql.gz` (S3 copy + prune skipped). No
non-empty/loadability check. Now that `/resolve` writes non-seed catalog rows, a same-disk partial is a real (if
operator-gated) durability risk. **Fix:** dump to `$out.partial` + `mv` only on success (atomic) + `trap rm`. Note
`gzip -t` does **not** catch a truncated-SQL-but-valid-gzip stream — atomic rename or a row-count/size assert is the
real guard.

**P3 — OpenTofu lock carries only `h1:` (no `zh:`) hashes; CI `tofu init` isn't read-only.**
[infra/.terraform.lock.hcl](infra/.terraform.lock.hcl) + bootstrap. *Corrected P2→P3 with a fix correction:* the
project deliberately installs via the `terraform-mirror.yandexcloud.net` **network mirror** (registry.terraform.io is
RU-blocked, #18), and network mirrors structurally don't serve `zh:` hashes — so `tofu providers lock` would **not**
populate them here. The real residual is that [infra.yml:73](.github/workflows/infra.yml#L73) runs `tofu init`
without `-lockfile=readonly`, so a platform-hash mismatch silently *rewrites* the lock instead of failing. **Fix:**
add `-lockfile=readonly` to CI init (enforcement), not the zh:-hash remedy.

**P3 — Sidecar reads the whole upload into RAM before the byte-cap check.**
[app.py:132-137](ocr-sidecar/app.py#L132-L137): `data = await file.read()` materializes the full body before
`len(data) > MAX_IMAGE_BYTES`. Minor (internal-only network, server caps at 8MB upstream, 1g mem_limit OOM-kills
rather than exhausts host) but it defeats the stated defense-in-depth intent. **Fix:** check Content-Length/`file.size`
first, or read in a bounded loop.

> Plus the already-tracked items from `2026-06-18-ocr-workstream-complete-review.md` §4 that remain open and are
> **not** re-litigated here: the pixel-flood guard (note: `within_pixel_budget` is now **in place** at
> [app.py](ocr-sidecar/app.py) — verify it covers your worst case), 3× pooled HTTP factory, detail N+1, fixed-window
> boundary burst, LLM budget undercount + 4xx-retry, supply-chain SHA-pinning + cosign, restore-RTO runbook, SA
> least-privilege, VM egress deny, SSH tightening, seed 100→300, `values-en`.

---

## 3. Design & architecture assessment (the core ask — improving the idea)

The direction is correct and internally consistent; the reversals in the log (#18 VPN-drop, #42 shared-teas, #75
image-list, #100 server-OCR) are deliberate and documented. What follows is at the **idea altitude** — fair to the
existing rationale, then the strongest counter worth raising.

### 3.1 Catalog breadth is the existential product risk, and MVP has no engine for it (HIGH)
The whole value proposition is "type a tea → get a rich card." With the AI tier **off** (#88/#100), breadth comes
only from the curated seed (100→300, #95) and Wikidata-first `/resolve` (#64). The seed posture is genuinely right —
own-authored, license-clean, `verified`, deliberately biased to the ru+en long tail — and curated-first dodges the
scraping/web-grounding traps (#10/#45). **But:** ~300 teas is a rounding error against the real RU long tail (a
single Moychay catalog is thousands of SKUs; every shelf has a few teas no curated set will hold). With AI off, a
Wikidata miss returns `UNRESOLVED` → a bare custom tea. So the **modal** first experience for a motivated user
logging their actual shelf is "the catalog doesn't know my tea," which quietly demotes the product to a local
note-taker with tiers. The compounding "second user to type a tea gets it free" loop (#14) only exists when
`/resolve` can create rich rows — i.e. when AI is on or Wikidata hits; nothing grows the catalog *from usage* while
the write-path enrichment is dormant.

**Recommendations (cheap, no new always-on service):**
1. **Verify the free Wikidata resolve tier is actually ON in prod** — it needs no key/budget/card (#64 built it) and
   gives real "type any famous tea → ru/en/zh names" value at zero risk. If prod currently serves only the static
   seed, that is more value left on the table than intended. *(Open question — confirm against the live VM config.)*
2. **Capture the breadth signal you already have for free:** log (server-side, aggregate, name-string-only, no PII —
   already the #14 model) the **`UNRESOLVED` `/resolve` queries**. Top-failed-queries *is* your curation backlog and
   converts every seed/AI-on decision from intuition into "curate the top-50 misses next." Highest-leverage,
   lowest-cost instrument in the whole system.
3. **Reframe the catalog honestly in onboarding/empty-state copy** as a *seed of famous teas*, and make the
   search-miss CTA a first-class rich-custom-tea flow (OCR `sourceText` + user photo + local 11-dim flavor are all
   already built), not a consolation dead-end.
4. Make this a real research question — **scaffolded as run 16** (§7): compliant breadth without crawling.

### 3.2 Local-first is the right spine, but device-loss recovery rests on a ritual users won't perform (HIGH)
No-account/no-PII (#1) is the strongest architectural choice here — it deletes auth, sync, conflict-resolution, and
per-user backend cost in one stroke, and export/import (#26/#49/#97) is a genuine, GMS-free safety net. **But** export
is *manual and opt-in*, and the catastrophic event (lost/wiped phone) is exactly when the user didn't just export.
"Device loss = total loss" plus a deferred destructive-migration risk (#70.1) is the same data gone either way — which
is precisely why the #111 migration sentinel exists. **Recommendation (keep no-account; do *not* build sync):** move
durability from opt-in to near-automatic — promote the M6 "auto-export to the user's *own* cloud via SAF" (persisted
tree URI + a periodic snapshot, still zero accounts) toward near-MVP, and in the interim add recurring export nudges
keyed to data-at-risk (after N new teas), not just a one-time first-run prompt. The user should opt *out* of
durability, not remember to opt in.

### 3.3 OCR earns its complexity *because* it's the breadth backstop AI-off otherwise lacks (MEDIUM)
On its face the FastAPI sidecar + baked ONNX + SHA provenance + 3-network isolation + pixel-bomb guards + CER
sampling looks over-built for "read text off a pouch," and the measured accuracy is honestly mediocre (ru ~9% CER
synthetic; 3/4 real-photo name-capture; Cyrillic↔Latin homoglyph errors, #112/#113). **But** with AI off, the
highest-value enrichment input is user-pasted `sourceText` (#25), and OCR is the funnel that makes pasting Cyrillic
foil-pouch prose usable. The execution is disciplined: opt-in per image, review-before-use (the human catches the
homoglyphs — the exact failure mode), never stores bytes, fails closed. That's the right way to ship a low-accuracy
ML assist: behind a human gate, never as ground truth. **Recommendations:** (1) optimize the review UI for fast
correction (one-tap homoglyph fix / re-scan) — the product metric is *name-capture for the user to pick/correct*
(#113), not CER; (2) add the cheap server-side Cyrillic-homoglyph normalization pass (#105 "possible later") — it
attacks the one systematic real-world error class for ~free; (3) **resist** further OCR investment (better models, VM
resize, zh) — ROI is now far below seed expansion.

### 3.4 Single-VM is right, but it has quietly accreted and the 4 GB ceiling is starting to drive design (MEDIUM)
Single-VM + compose over any orchestrator is unambiguously correct here, and the infra is implemented-not-aspirational
(segmentation, cap-drop, digest/SHA pinning, Lockbox flow — all verified). **But** "Spring + Postgres" is now Spring +
Postgres + Caddy + OCR sidecar, and #111 explicitly shapes the telemetry design (first-party endpoint, no second
service) *because* GlitchTip "doesn't fit the ~3.4 GB-committed 4 GB VM." That sentence is the tell: the ceiling is
now a design input. And the catalog is no longer read-only seed — `/resolve` writes non-reproducible rows, so the
local-only, **non-atomic** backup (§2) is a genuine durability risk, not a convenience. **Recommendations:** (1)
either accept a modest **resize to 8 GB** as the relief valve or write an explicit "this VM hosts catalog + OCR only;
everything else is a documented upgrade-path service" boundary so creep is a decision, not a drift; (2) promote
off-box, **verified** backups + a restore-RTO runbook from "P2 tidy-up" to a real durability guarantee now that the
DB holds irreproducible state (Managed PostgreSQL stays the upgrade path).

### 3.5 AI-tier-off is sound, but be honest about the value spine without it (MEDIUM)
Shipping with the LLM tier off is admirable discipline — it sidesteps billing logistics (#86), hallucination, and
cost-runaway on a write endpoint, and the whole tier (bake-off, prompts, guards, async worker, budget) is a config
flip away. **But** with AI off, the backend's net contribution shrinks to a ~300-tea lookup + typo search; the
sophisticated enrichment pipeline the plan describes is exactly the part that's dark. The MVP is genuinely usable
(M1 is feature-complete offline), but it should be *positioned* as "the best offline tea tier-list, with a curated
reference catalog" — not half-sold as the enrichment platform. **Recommendation:** keep AI off for v1, but (1) define
the concrete trigger to turn it on (billing sorted + N curated teas + the existing eval gate + run-14 re-verify) so
"off" is a staged decision with an exit, not indefinite limbo where built code rots; (2) the **staleness clock is
already ticking** — `aliceai-llm-flash` slug unverified, prompts still zh-aware while #94 deferred zh.

### 3.6 zh deferral is a clean cut with one latent tension (MEDIUM)
Deferring zh UI/OCR/input (#94) while keeping zh-Hans + pinyin as reference/search keys is exactly the right way to
defer a locale (drop the input/UI burden, keep the data). **But** Chinese teas are the catalog's prestige *core*, and
the OCR most reliably garbles transliterated Chinese names via homoglyphs — so a user scanning a Chinese-tea package
gets the worst OCR outcome on the most important names. **Recommendation:** hold the deferral; close the gap on the
*transliterated* path that IS in scope — make pinyin + common RU transliterations of marquee Chinese teas dense in
the seed/search-gold set (so `тегуанинь`/`tieguanyin`/`те гуань инь` always hit), and prioritize the homoglyph
normalization (its highest payoff is here).

### What the codebase does notably well (verified — keep)
Backend: correct `/resolve` race handling (flush-inside-tx + `DataIntegrityViolationException` re-read), slow LLM/
Wikidata calls kept off the request thread and out of DB locks, daily budget fails *closed*, clean DTO/entity
separation, dense Testcontainers ITs, consistent "blank key/url → tier off → 503/UNRESOLVED" gating, `requireHttpUrl`
wired everywhere. App: the optimistic-add/background-enrichment/offline-resume state machine never forces a
server-FAILED/PENDING to local DONE and fails closed to a retryable FAILED; thorough a11y (per-axis `FlavorRadar`
descriptions, per-bar `clearAndSetSemantics`, a non-drag TalkBack equivalent for every drag); zero-recomposition drag
with a pure tested reorder; correct DI (one shared OkHttp pool for Retrofit + Coil, debug-gated logging, settings
degrade-to-default on corrupt read). Infra: genuinely strong network segmentation + container hardening + egress-free
SHA-pinned baked models + clean Lockbox secret flow + correct Caddy XFF reasoning. **And the decision log itself** is
a real asset — research-first, ToS/licensing-rigorous, append-only, honest about supersessions.

---

## 4. Open-source reuse (the explicit ask)

Consolidated across all four areas. The strongest signal is **as much about what NOT to add** as what to adopt.

| Subsystem | Today | Recommendation | When |
|---|---|---|---|
| Supply chain (CI) | `uses:` on mutable tags; no `dependabot.yml`; published image unsigned, no provenance | **Dependabot** (gradle ×2 + pip + docker + actions; auto-SHA-pins `uses:`) + **cosign** keyless + GitHub `attest-build-provenance` on `publish-image.yml` | **pre-public (highest cheap win)** |
| Crash/error telemetry | none | **ACRA** (`ch.acra:acra-http`, Apache-2.0, GMS-free) → first-party `/api/v1/client-diagnostics` — **already decided #111**; GlitchTip+sentry-android = documented upgrade path | pre-public, alongside Room cutover |
| OCR sidecar deadline | single worker, no inference timeout | `asyncio.wait_for` (stdlib) → 503/504; ideally killable `ProcessPoolExecutor`/subprocess. **No new dependency.** | before OCR serves real traffic |
| 3× outbound HTTP clients | `SimpleClientHttpRequestFactory` (no pool, HTTP/1.1) | **`JdkClientHttpRequestFactory`** (pooled `java.net.http`, HTTP/2, **zero new dep** — Spring already ships it); one shared bean | Wikidata now (live path); others when AI on |
| Outbound retry loops | hand-rolled `repeat + Thread.sleep` (fixed, no jitter, **retries 4xx**) | **spring-retry `RetryTemplate`** (or resilience4j-retry) scoped to transient 5xx/IO/timeout with backoff+jitter + circuit-breaker — fixes the 4xx-retry waste | when AI on; Wikidata opportunistically |
| OpenTofu lock enforcement | CI `tofu init` not read-only; lock has h1: only | `tofu init -lockfile=readonly` in `infra.yml` (zh: hashes **won't** populate under the RU network mirror — don't chase them) | next CI-driven apply |
| Caddy edge | bare `reverse_proxy` | config-only: `request_body{max_size}` + security `header` block + `transport` timeouts | pre-public |
| Postgres backup | `pg_dump\|gzip>out`, non-atomic, local-default | now: atomic temp+`mv`, turn S3 on; later (when enriched rows irreplaceable): **pgBackRest**/**WAL-G** for PITR + verified retention | now / pre-public |
| Catalog detail N+1 (1+4) | plain `findById` | Hibernate-native **`@EntityGraph`**/`@BatchSize` — only if profiling shows it; tiny catalog | low / if measured |

**Do NOT add (well-covered, or already in place — adding would be redundant/regressive):**
- **Rate-limiter / daily-budget:** keep the hand-rolled `FixedWindowRateLimiter` + `LlmDailyBudget` — they're
  ~50 lines, tested, correctly per-endpoint, capped (`MAX_TRACKED_CLIENTS`), single-VM. **Bucket4j/Resilience4j-
  ratelimiter buy distributed backends you don't have**; only the *retry* loops merit a library (above).
- **EXIF** — `androidx.exifinterface` already in `ImageReader`. **Pillow bomb-guard** — `within_pixel_budget`
  already does a header-only pixel check before the big allocation. **SBOM/OSV** — CycloneDX + OSV-Scanner already
  gated (#102). All three "in place."
- **WorkManager** — keep deferred (#92): enrichment is server-side and polled, so there's no client-side durable
  queue to schedule. Revisit only if a client upload/retry queue is added.
- **Room / Retrofit 3 / OkHttp 5 / Coil 3 / Hilt / kotlinx.serialization / Testcontainers / pg_trgm / RapidOCR /
  OpenTofu / custom Compose nav / hand-rolled drag** — all best-in-class-in-use or deliberate, well-justified
  hand-rolls. No Meilisearch/OpenSearch/Typesense (pg_trgm meets the typo requirement live). No orchestrator.

---

## 5. Strategic gaps (not in any decision/research; matter for *success*, not just correctness)

1. **Catalog-breadth strategy at scale (the biggest gap)** — see §3.1. No decision answers "how does a RU user
   reliably find the tea they bought" once AI is off. → new research run 16 (§7) + the UNRESOLVED-query log.
2. **No product-usage signal.** Local-first + no accounts means the only ground truth is server-side, and nothing
   aggregates "is this used, by how many, on which queries." Telemetry (#111) is scoped to crashes only. Define
   3–5 success metrics before public launch (distinct `/resolve` names/day, **search-miss rate**, top failed
   queries, enrichment success rate — all free server-side; plus one opt-in install ping) and write the number that
   means "keep going" vs "sunset."
3. **No catalog curation pipeline.** The plan repeatedly promises "a later pass promotes unverified→verified" but
   there's no operator surface to *see* unverified rows, no queue, no bulk promote/correct/delete, no count of how
   many exist or how wrong. Once `/resolve` writes from public traffic, quality drifts toward whatever the LLM
   hallucinated with no human in the loop. Spec a minimal read-only admin view (even a SQL view / tiny authed page)
   listing unverified rows newest-first with confidence + source query, + verify/edit/delete — demand-driven via
   the search-miss log. *(Note: under the AI-off MVP this is near-empty work — but it's a prerequisite the day AI
   turns on, and M5's "promote unverified→verified" milestone is written for an AI-on world that doesn't exist yet.)*
4. **API-versioning discipline — flagged in plan §11, never decided.** RuStore review latency + non-updating users
   guarantee shipped APKs lag the backend for months; the first breaking `/resolve`/catalog change (likely once the
   run-11 flavor-provenance schema lands) has no way to signal old clients or retire `/api/v1` safely. Decide now
   while only internal builds exist: send app-version/platform on every call; backend returns a soft
   "min-supported / upgrade-available" the client shows as a non-blocking banner; rule that `/api/v1` only ever
   *adds* fields (the client already tolerates unknown enums — good instinct, no policy).
5. **No RU legal/privacy-policy posture for a public release.** All ToS analysis is about LLM providers; there's no
   published privacy policy (RuStore requires one), no data-operator statement, no written 152-FZ conclusion (even
   "no PII server-side → minimal" should be recorded), and the in-app privacy *copy* has already drifted 4×. The OCR
   feature uploads user photos (transiently) — a public release needs a real policy, not just a Settings string.
   Add it + a RuStore compliance line to the §7.1 release gate.
6. **No onboarding** (acknowledged in #107). For an app whose biggest risk is silent device-loss and whose catalog
   is a thin seed, the lack of any first-run flow (export nudge, "famous-tea seed" expectation-setting, privacy
   disclosure *before* the user pastes vendor text) is a real gap. Empty-states (#28) partly compensate.
7. **No data-quality harness for the curated seed** — the entire MVP catalog. Quality is manual review + spot-fixes;
   there's no standing translit regression suite or Hanzi↔pinyin CI check beyond the one-off in #96. Reuse the
   100→300 seed as a search-gold + transliteration regression corpus (already half-suggested in prior reviews).
8. **No abuse ceiling for catalog *integrity* / the shared failure mode.** Cost is well-guarded (#71/#103), but the
   global LLM ceiling fails closed *for everyone* (one abuser blanks the catalog for all until rollover — DoS via
   the cost guard), and `/resolve` writes rows with no global row-count ceiling or anomaly alert on a 4 GB VM. Add a
   per-day unverified-row-creation cap + a normal-traffic reserve + one cheap daily threshold alert.
9. **No post-grant cost / long-term ownership plan.** ~700–1700 ₽/mo accepted (#19), "grant covers early months" —
   but nothing on what happens after, or the "if I stop paying" plan. Every installed APK is hard-pointed at
   `tea.macsia.fun` via `BuildConfig.CATALOG_BASE_URL` and fails hard if the host disappears. **Concrete first
   steps:** ship a compact read-only seed catalog *bundled in the APK* so search/detail degrade to "offline catalog"
   instead of failing; write a one-page sunset/cost-trajectory note (projected ₽/mo, the trigger to flip to
   serverless/Managed-PG, the final-APK-with-bundled-catalog plan).

---

## 6. Internal tensions & doc drift (fix `plan.md`; keep `decisions.md` append-only)

`context/plan.md` is the canonical build plan but has drifted from the decision log; a fresh contributor building
from it would implement superseded designs. Refresh these with "superseded by #N" pointers (do **not** rewrite
history):
- **§4b** still describes board-scoped `user_tea(boardId, tierId, position)` — **superseded by #42** (user-global
  `teas` + `placements`). `context/shared-teas/plan.md` is the accurate spec.
- **§4a** still describes a single `image_url` triple — **superseded by #75** (`tea_image` list).
- **§4a** claims catalog images are "stored in Yandex Object Storage, not hotlinked" — **never built**; the
  implemented reality is "store the CC/Wikimedia URL + license + attribution, load via Coil" (#61/#89). Either build
  the Object-Storage pipeline or correct the line.
- **§6** prose still names "YandexGPT Lite primary" — **superseded by #65** (Alice Flash primary; Lite dropped),
  already routed in code (#66). The "benchmark Alice Flash as a possible swap" caveat undersells that the swap
  happened.
- **M5** "promote unverified→verified / fix bad AI transliterations" assumes AI-on; under #88/#100 there are no
  AI-authored unverified rows yet (§5.3).
- **Research-folder hygiene:** `08-ai-web-search/` and `08-model-bakeoff/` share the `08` prefix (the bake-off
  RATING correctly notes it doesn't bump the leaderboard); `12-batch-enrichment/prompt.md` is internally titled
  "10-batch-enrichment"; run **14** is reserved-but-empty (intentional). Minor, but worth a cleanup note.

(The `tea_flavor(tea_id,dimension,intensity)` vs run-11 per-dimension-provenance tension is already honestly flagged
in §4a/#100 as an unbuilt prerequisite — adequate.)

---

## 7. Research follow-ups

**Closed — a new run would be busywork:** tea-data sourcing/licensing (01), maps/geo (02, shelved M6), LLM
provider/ToS (03/04/06 — only console price/availability verification remains, not research), web-grounding (08,
"no compliant path"), flavor prompts + model pick (07/08-bakeoff — residual is eval gold sets, not research), typo
search (09, live), OCR engine/footprint/provenance (10/13), crash-telemetry tool (15).

**Genuinely open / warranted:**
- **NEW — run 16: compliant catalog-breadth at scale (RU long tail) without web crawling, AI-off.**
  Scaffolded at `research/16-catalog-breadth/`. This is the **highest-value un-run question** and the one strategic
  gap with no research behind it: with crawling banned (#45) and AI off (#88), how does the catalog become broad
  enough to feel useful? (Structured open datasets beyond Wikidata, RU-specific open tea taxonomies, a
  user-contribution model, an operator seed-from-misses batch, or an explicit "famous-tea seed" reframe.)
  Decision-blocking and multi-model-worthy. *(Not auto-run — `prompt.md` is ready to send verbatim to the models.)*
- **Run 14 (Yandex async re-verify)** — correctly reserved; run it *immediately before* building the
  background-enrichment tier (the async/Responses API + `aliceai-llm-flash` slug/pricing can go stale), not now.
- **Not deep-research, but owed:** an on-device-OCR **device-verification spike** on the real RuStore/AGP-9
  toolchain (run 10 picked on-device but couldn't device-verify; #100 went server-side partly for that — it's the
  privacy-preserving path worth reopening if it's now verifiable), and the **zh-source + grounded eval gold sets**
  owed since #65 (eval-harness work).

---

## 8. Suggested next sequence

Ordered by leverage and current-impact (most new findings are dormant-tier or polish):

1. **Refresh the stale `plan.md` sections** (§6) — cheapest, prevents the next contributor building the wrong schema.
2. **Verify the free Wikidata `/resolve` tier is ON in prod**, and **start logging UNRESOLVED `/resolve` queries**
   (§3.1, §5.1/5.2) — the single highest-leverage, near-free move for breadth + the first real product signal.
3. **App data-integrity P2s** (small, real holes): the enrichment-merge **transactional** fix + the single reusable
   **`PhotoStore.reconcile`** sweep (closes import-orphan *and* the tracked orphan gap) + the placement-less
   photo-strip flow source.
4. **Infra hardening** (mostly config): Caddy body-cap/headers/timeouts; atomic `backup.sh` + turn S3 on; CI
   `tofu init -lockfile=readonly`; thread `OCR_SIDECAR_IMAGE` through cloud-init.
5. **OCR sidecar inference deadline** (`asyncio.wait_for`) — before the OCR tier serves real traffic.
6. **Pre-public batch:** Dependabot + SHA-pin actions + cosign/provenance; the Room public-schema cutover + #111
   telemetry sentinel; a published **privacy policy** + RuStore compliance line; the **nav-restore** `getOrNull`
   guard; the missing **`BackupManagerTest`** + the **OcrClient/502** contract tests.
7. **Enable-day (AI on), not before:** async graceful-drain + stale-PENDING sweep; `JdkClientHttpRequestFactory` +
   spring-retry on the outbound loops; verify `aliceai-llm-flash` slug + reconcile zh-aware prompts; run 14.
8. **Decide the strategy items:** API-versioning contract; catalog curation surface; post-grant cost / bundled-seed
   sunset plan; the breadth strategy informed by run 16.

---

## External references checked
- Spring `JdkClientHttpRequestFactory` (pooled JDK client): https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Spring `MockRestServiceServer` / WireMock: https://docs.spring.io/spring-framework/reference/testing/spring-mvc-test-client.html · https://wiremock.org/
- Spring Retry: https://github.com/spring-projects/spring-retry · Resilience4j: https://resilience4j.readme.io/
- Caddy `request_body` / `header` / `reverse_proxy transport`: https://caddyserver.com/docs/caddyfile/directives/reverse_proxy
- OpenTofu `-lockfile=readonly` / provider locks: https://opentofu.org/docs/cli/commands/init/
- Dependabot ecosystems: https://docs.github.com/en/code-security/dependabot · cosign keyless: https://docs.sigstore.dev/cosign/signing/overview/
- ACRA (GMS-free crash reporting): https://github.com/ACRA/acra
- pgBackRest: https://pgbackrest.org/ · WAL-G: https://github.com/wal-g/wal-g
- Spring Data JPA `@EntityGraph` / `MultipleBagFetchException`: https://docs.spring.io/spring-data/jpa/reference/jpa/entity-graph.html
- Room migrations / destructive fallback: https://developer.android.com/training/data-storage/room/migrating-db-versions
- Python `asyncio.wait_for`: https://docs.python.org/3/library/asyncio-task.html#asyncio.wait_for
