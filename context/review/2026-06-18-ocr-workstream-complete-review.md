# TeaTiers design & architecture review — 2026-06-18 (OCR workstream complete; deep code pass)

Scope reviewed:
- `task.md`, `architecture.md`, `context/plan.md`, `context/decisions.md` (#1–#107)
- the two prior code-pass reviews under `context/review/` (2026-06-18 current-design + post-OCR)
- focused plans: `context/shared-teas/`, `context/photos/`, `context/polish/`, `context/flavor-system/`
- research runs + ratings (incl. run 13's `proof/FINDINGS.md`)
- the **actual source** of the never-before-reviewed code: the Python OCR sidecar (`ocr-sidecar/`,
  slice 1b/#106) and the app "scan packaging" UI (slice 3/#107), plus an adversarial re-verification of
  every recent fix (#101–#107) against the code it claims to fix.

`task.md` / `architecture.md` are legacy sketches; authority is `context/plan.md` + the append-only
`context/decisions.md`. This pass **supersedes nothing** — it extends the 2026-06-18 post-OCR review
to the code that landed *after* it (the sidecar + scan UI did not exist when that review ran). It was
produced by a multi-agent find → adversarial-verify → completeness-critic workflow whose findings were
each checked against source and against the #96–#107 dispositions, then re-verified by hand on the
highest-value items.

---

## 1. What's resolved since the last review — verified correct AND complete (do not re-open)

Each of the #101–#107 fixes was adversarially re-checked against the code (not the decision text). All
hold up:

- **#101 enrichment dead-end (duplicate `catalogTeaId`)** — FIXED correctly. `applyPatch` pre-checks
  `findTeaIdByCatalogId` and settles a colliding duplicate `DONE` without rewriting the UNIQUE link
  ([TeaEnrichmentManager.kt:138-142](app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt#L138-L142)).
  The test fake now mirrors the Room UNIQUE index and the scenario test (`Tieguanyin`/`тегуанинь`) asserts
  no re-dead-end on retry. *(One residual UX gap remains — see §2 P3.)*
- **#102 prod container hardening** — COMPLETE. `cap_drop: [ALL]` + `no-new-privileges` + explicit
  `pids_limit`/`mem_limit` on every service; `server` and the sidecar `read_only: true` + `tmpfs: [/tmp]`;
  only-needed caps re-added (Caddy `NET_BIND_SERVICE`, Postgres root→gosu set). Server healthcheck ported
  with `depends_on: condition: service_healthy`
  ([docker-compose.prod.yml](infra/deploy/docker-compose.prod.yml)). OSV-Scanner wired over scoped
  CycloneDX SBOMs for both Gradle modules + the Python sidecar.
- **#103 OCR rate-limit** — COMPLETE. `/teas/ocr` has its own `FixedWindowRateLimiter` bean (10/min) vs
  `/resolve` (20/min), and `isEmpty`/`size` validation runs **before** `tryAcquire`
  ([TeaController.kt:81-83](server/src/main/kotlin/com/macsia/teatiers/controller/TeaController.kt#L81-L83)).
- **#105/#106 sidecar** — provenance gate is real (`fetch_models.sh` SHA256-verifies each ONNX, fails the
  build on mismatch; models baked, no runtime egress incl. the angle-cls download gotcha), concurrency
  capped at 1, and image bytes / recognized text are never logged
  ([app.py:108-121](ocr-sidecar/app.py#L108-L121)).
- **#107 scan UI + privacy copy** — guardrails hold in code: scan is opt-in **per image** (a regular tea
  photo is never auto-uploaded), recognized text is shown in an **editable** dialog and only becomes
  `sourceText` on confirm, the capture is cleared from cache, and the rewritten
  `settings_about_privacy` accurately discloses the scan upload + immediate deletion
  ([strings.xml:75](app/src/main/res/values/strings.xml#L75)). The privacy copy now matches actual
  network behavior — the trust bug from the earlier passes is genuinely closed.

The direction remains coherent and correct: local-first app, no accounts, backend as a catalog/enrichment
service holding no user data, Postgres catalog DB, no web crawling, no arbitrary web images, OCR fails
closed, models egress-free, no GMS-only MVP dependency. **Nothing below argues for a redesign.** The OCR
server-side fork (supersedes run-10's on-device winner, #100) and the "MVP ships with the AI tier OFF"
posture (#88/#100) are deliberate, documented, and sound. The shared-teas `placements` refactor (#42)
shipped and is clean.

---

## 2. New findings (verified against code; could not have been found before — the sidecar + scan UI are new)

Severities are the adversarially-corrected ones. Every item here was checked **not** to be a restatement
of an already-tracked issue (those are consolidated in §4).

### P2 — OCR sidecar pixel-flood / decompression bomb: the byte cap does not bound *decoded* pixels (NEW)

The server caps the upload at 8 MB and the sidecar at 10 MB, but the sidecar then does
`Image.open(io.BytesIO(image_bytes)).convert("RGB")` → `np.array(img)` with **no `Image.MAX_IMAGE_PIXELS`
guard and no dimension check** ([app.py:72-80](ocr-sidecar/app.py#L72-L80)). The byte cap is the wrong
axis: a flat/low-entropy PNG of ~13000×13000 px compresses to well under 8 MB yet decodes to a ~535 MB RGB
ndarray plus PIL's decode buffer (~1 GB transient) — against `mem_limit: 1g`
([docker-compose.prod.yml:121](infra/deploy/docker-compose.prod.yml#L121)) the cgroup OOM-kills the
sidecar. Pillow's default `MAX_IMAGE_PIXELS` only *warns* at ~89 MP and errors at ~179 MP, so a sub-179 MP
bomb sails through. The `Det.limit_side_len: 960` downscale happens *after* the full decode, so it doesn't
help. **Crucially the #103 validate-before-acquire fix does not defend here** — a bomb is small in *bytes*,
so it passes the size check, spends a rate token, and reaches the sidecar; at 10 req/min/IP it is a
repeatable tier-level DoS. The #105 "peak RSS 248 MB" figure was measured on a synthetic *text-render*
corpus, not crafted inputs, so it does not bound this.

**Blast radius:** OCR tier only (private network, concurrency 1, own `mem_limit`; db/server/caddy have their
own caps), and it self-heals via `restart: unless-stopped` — so P2, not P0/P1. **Fix (cheap, in `app.py`):**
set `Image.MAX_IMAGE_PIXELS = 16_000_000` (or lower) and guard `img.size[0]*img.size[1]` before
`np.array`, returning 413 on breach (catch `Image.DecompressionBombError`); optionally drop the server
`maxImageBytes` to 2–4 MB and add an onnxruntime inference timeout. No new dependency — Pillow already
ships the guard, it just isn't enforced.

### P2 — Scanned camera photos are OCR'd sideways: EXIF orientation is never applied (NEW)

`AndroidImageReader.read` decodes the picked/captured image with `BitmapFactory.decodeByteArray` (which
ignores the EXIF Orientation tag) and re-encodes to JPEG (dropping the tag), with **zero `ExifInterface`
use anywhere in the app** ([ImageReader.kt:30-54](app/src/main/kotlin/com/macsia/teatiers/data/photos/ImageReader.kt#L30-L54)).
Most camera apps store landscape sensor pixels + an Orientation tag rather than rotating pixels, so a photo
shot in portrait arrives rotated ~90°. The sidecar runs with angle-cls **OFF**
([app.py:52](ocr-sidecar/app.py#L52)) — by design (#105) — so there is **no server-side rotation
recovery**, and RapidOCR's horizontal det+rec will largely fail on a rotated image → near-empty/garbage
text for the *primary camera-scan path*. This is distinct from the post-OCR review's "doc-unwarp for
curved/glossy packaging" note (sub-degree warp); this is discrete 90/180/270° camera orientation.

P2 (accuracy, not crash/security; the editable review step lets the user abandon a failed scan, capping it
below P1). **Fix:** read AndroidX `ExifInterface` from the *original* bytes (line 33, before re-encode) and
fold the rotation into the existing `createScaledBitmap` Matrix so no extra full-res bitmap is allocated.
**Open-source:** `androidx.exifinterface:exifinterface`.

### P2 — Flat compose network: a compromised image-processing sidecar can reach Postgres directly (NEW)

`docker-compose.prod.yml` declares **no custom networks** — caddy, server, db, and the sidecar all share
the implicit default bridge. The file's own header flags that the sidecar "processes attacker-supplied
images on the same host," yet that sidecar (FastAPI + onnxruntime + Pillow native-code surface over
untrusted images — by far the largest RCE surface on the box) can open `db:5432` directly. The #102
hardening locked the *container* (caps, read-only, pids) but not the *topology*.

**Fix (cheap defense-in-depth, matches the stated threat model):** declare three networks — an edge net
(caddy↔server), a data net (server↔db), and an ocr net (server↔sidecar) — so the sidecar has no route to
Postgres. Pure compose change, no app code.

### P2 — OCR proxy chain has no global concurrency bound → Tomcat thread-exhaustion path (NEW)

The chain is asymmetric: the sidecar serializes inference (one `ThreadPoolExecutor(max_workers=1)`,
`app.py:43`, single uvicorn worker), the server's `OcrClient` read-timeout is generous, Tomcat is at its
200-thread default (no `server.tomcat.*` tuning in `application.yml`), and the OCR rate limiter is
**per-client** (10/min) with **no global cap on concurrent `/teas/ocr` requests**. Enough distinct clients
each scanning → many Tomcat worker threads block waiting behind the single sidecar worker, degrading the
*whole catalog API* (not just OCR), not just the OCR tier. XFF is Caddy-locked (#98) so it needs real
distinct IPs, which bounds it, hence P2 not P1.

**Fix:** a small server-side bounded `Semaphore`/permit count (matching sidecar workers) that fast-fails
503 when the sidecar is saturated, and/or make `/teas/ocr` async (`DeferredResult`) so blocked requests
don't pin Tomcat threads. Pairs naturally with the Resilience4j `Bulkhead` already on the §4 reuse list.

### P3 — VM egress is unrestricted (`0.0.0.0/0`, all ports) — now that the host runs untrusted-image OCR (NEW angle on a tracked item)

`infra/security_group.tf` allows all egress to `0.0.0.0/0` ports 0–65535 ([security_group.tf:28-34](infra/security_group.tf#L28-L34)),
and SSH ingress is world-open (a known, deliberately-deferred decision — #96/#98). The *new* angle the
prior reviews couldn't weigh: the sidecar needs **zero** egress (#105 baked all models, runtime makes no
network calls), so a compromised sidecar today has an unconstrained exfil path that costs nothing to close.
**Fix:** a sidecar-scoped egress deny (or, with the §2 network split, simply no gateway on the ocr net);
optionally restrict the *server's* egress to Wikidata + Yandex Foundation Models + ACME. SSH-tightening
stays the separately-tracked pre-public item.

### P3 — `#101` fix leaves the duplicate user-tea with **zero** catalog metadata (NEW improvement)

After #101, when tea B ("тегуанинь") resolves to a catalog row already owned by tea A ("Tieguanyin"), B is
correctly settled `DONE` — but it gets **none** of the catalog's ru/en/pinyin names, blurb, or type
([TeaEnrichmentManager.kt:139-142](app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt#L139-L142)).
B displays the user's raw typed string forever while A shows the enriched card — an asymmetric,
confusing result. The fix could **merge the catalog names/blurb into B without writing the UNIQUE
`catalogTeaId` link** (the link is the only thing the index forbids; the suggestion fields are free).
Root cause is upstream: the local auto-link matcher projects only `nameRu`/`nameZh`/`pinyin`, **not
`nameEn`** ([TeaDao.kt:58](server/.../) → `loadTeaMatchKeys`, [TeaDao.kt:58](app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDao.kt#L58)),
so en-vs-ru spellings never dedup locally in the first place. This is the "broader local-dedup hardening"
#101 deferred — concrete next step: add `nameEn` to the match projection and merge metadata into
same-catalog duplicates.

### P3 — Wikidata `zh` label filter is too strict (`LANG(?zh)="zh-hans"`) (NEW, untracked)

The resolve-time SPARQL uses a direct `rdfs:label` triple with `FILTER(LANG(?zh) = "zh-hans")`
([WikidataSparqlClient.kt:103](server/src/main/kotlin/com/macsia/teatiers/client/WikidataSparqlClient.kt#L103)).
Direct triple patterns get no automatic label fallback, and most Wikidata items store their Chinese label
under the generic `zh` (or `zh-cn`) code — so Chinese teas resolved via Wikidata silently come back without
their Hanzi reference name. Impact is **cosmetic-reference-only** (it does *not* push resolution to the LLM
— `ResolveService` accepts any of en/ru/zh-Hans and short-circuits before the LLM), and zh is deferred
(#94) but zh-Hans is explicitly kept as reference data + pinyin as the search key — so worth fixing. **Fix:**
`FILTER(LANG(?zh) IN ("zh-hans","zh-cn","zh-sg","zh"))` with a deterministic preference, **or** switch this
one label to `SERVICE wikibase:label`. Avoid the naive `STRSTARTS(LANG(?zh),"zh")` — it also matches
`zh-hant`/`zh-yue`. Verify live before shipping. (The unit test hardcodes the `?zh` binding, so it gives
false confidence — add a query-shape assertion.)

### P3 — `OcrSanitizer` and the sidecar response are unbounded before the length cap (NEW, minor)

`OcrSanitizer.clean` NFC-normalizes + regex-scans the **full** raw string and only caps to
`maxTextLength` (4000) at the very end; the sidecar itself puts **no length cap** on the concatenated text
it returns ([app.py:80](ocr-sidecar/app.py#L80)). Not a strong DoS given the small realistic output, but
worst-case sanitizer work is bounded by input, not output. **Fix:** cap length *before* the normalize/regex
pass, or cap the sidecar output.

### P3 — No tests for the sidecar or the OCR proxy contract (NEW)

`ocr-sidecar/` has **zero** pytest, and the proxy boundary (OcrClient multipart shape, OcrService 502/503
mapping, the `{text: null}` contract) is only partially exercised. **Fix:** add a sidecar pytest (empty→400,
oversized→413, valid PNG→200 `{text}`, a pixel-bomb→413 once the §2 guard lands) and a contract test that
`OcrClient` parses `{text: null}`.

### P3 — Operator-config URL validation gap is broader than OCR (sharpening of a tracked item)

The post-OCR review flagged `OcrProperties.sidecarUrl` as used raw (still true,
[OcrClient.kt:46-47](server/src/main/kotlin/com/macsia/teatiers/client/OcrClient.kt#L46-L47)). The new
observation: `LlmProperties.endpoint` and `WikidataProperties.endpoint` are **equally** unvalidated
operator-set URLs, so a one-client fix is inconsistent. This is config-hygiene (operator-controlled, *not*
an attacker-reachable SSRF — no request data influences these), so P3. **Fix:** one shared startup
`require(scheme in http/https)` check applied to all three.

---

## 3. Architecture & design assessment (the core ask) + open-source reuse

### 3a. Architecture is sound; the two open forks are deliberately decided

- **OCR is now end-to-end server-side (slices 1a/1b/3 all shipped).** The privacy/product fork is resolved
  and honestly disclosed; the fail-closed degradation (no key → 503, catalog stays up) and egress-free
  baked models are good. The residuals are the §2 P2s (pixel guard, EXIF, network segmentation,
  concurrency bound) — robustness, not design.
- **MVP ships with the AI tier OFF (#88/#100).** Correct call: it removes the biggest cost/ToS surface,
  and catalog quality rests on the curated seed (continue 100→~300). The whole `LlmEnrichmentService`
  stack is dormant-but-built. The two latent blockers for the day it's switched on are still real and
  should be re-verified *then*, not now: the `aliceai-llm-flash` slug is unconfirmed on Yandex's table,
  and the flavor prompts remain zh-aware while #94 deferred zh.

What the codebase does **notably well** (verified, keep as-is): layered prompt-injection defense
(`EnrichmentText` strip + delimiter-wrapped data-not-instructions + shingle-overlap blurb rejection); model
output never trusted (strict `json_schema` + full Kotlin re-validation + clamp); slow Wikidata/LLM calls
outside DB locks; insert races handled with `saveAndFlush` + `DataIntegrityViolationException` re-read on
real DB unique indexes; the daily LLM budget fails *closed*; secrets hygiene clean (Lockbox, no keys/state
in git, Postgres unpublished); non-root digest-pinned images; backup zip-bomb bounds (#97) + FileProvider
scoping; pure/transactional/tested reorder + DAO writes; the `placements` refactor (#42).

### 3b. Open-source reuse — where to lean on ready solutions (the explicit ask)

| Subsystem | Today | Recommendation | When |
|---|---|---|---|
| 3× HTTP clients (`SimpleClientHttpRequestFactory`, no pool) | hand-rolled per client | **`JdkClientHttpRequestFactory`** (JDK 21 `java.net.http`, pooled, **zero new dependency**) shared as one bean — preferred over pulling in Apache HttpClient5, which isn't on the classpath | opportunistic (Wikidata is the only path that benefits today; LLM dormant, OCR is loopback) |
| catalog detail N+1 (1+4 SELECTs) | plain `findById` | **Spring Data JPA `@EntityGraph`** (names eager; split the other bags — `MultipleBagFetchException` blocks fetch-joining 4 `List`s) | low priority (corrected to P3 — tiny collections, ~100–300 rows) |
| rate-limit + retry + budget cluster | `FixedWindowRateLimiter` + `repeat`/`Thread.sleep` + once-per-resolve charge | **Bucket4j** (token bucket, no boundary burst) + **Caffeine** (bucket eviction) + **Resilience4j** (`Retry` w/ jitter, `Bulkhead` for the §2 concurrency bound) | when the AI tier is enabled (already the #103-deferred plan) |
| OCR image rotation | none | **AndroidX `ExifInterface`** | with the §2 P2 fix |
| sidecar pixel bound | none | **Pillow `MAX_IMAGE_PIXELS`** (built-in) + an `asyncio.Semaphore`/Starlette concurrency-limit | with the §2 P2 fix |
| GMS-free crash/error telemetry | **none** | self-hosted **Sentry** / **GlitchTip** / **ACRA** — local-first makes a post-release DB-wipe crash *invisible* to the dev; deserves a deliberate keep/skip decision, not silent omission | research run (§5) → decision before public APK |
| supply-chain | floating action tags, `:latest`, unsigned | **Dependabot** (SHA-pin actions) + **cosign** keyless + buildx `--provenance --sbom` + deploy-by-digest | pre-public (tracked, §4) |

**Keep / do NOT add (well-covered, no measured need):** Coil 3, Room, Retrofit 3 + OkHttp 5, Hilt,
Testcontainers, **pg_trgm** (no Meilisearch/OpenSearch/Typesense — the typo requirement is met live), the
custom Compose nav + hand-rolled drag (tested, fit the size), RapidOCR, OSV-Scanner, OpenTofu,
WorkManager-as-future (#92 chose honest app-open retry deliberately).

---

## 4. Carried-forward / tracked items — status after #101–#107 (don't re-open; this is the consolidated truth)

The #102 hardening pass closed the *runtime* container P1s but left the *supply-chain* and *data-lifecycle*
items open. These are all already tracked; listed so nobody re-discovers them as "new":

- **P0 — Room destructive migration** (`exportSchema=false` + `fallbackToDestructiveMigration`, now v6).
  Still the hard first-public-APK blocker; deliberately waivered to M5 (#70.1). Note when the cutover
  happens it must commit a baseline for the **current** v6 (not start at vN+1) or the first migration test
  has nothing to diff. `context/photos/plan.md` still calls destructive migration acceptable "pre-launch"
  — now pointer-corrected by #104, fine.
- **P2 — photo-orphan sweep** after `replaceAll`/`applyPatch` (#100 tidy-up; `AndroidPhotoStore.delete`
  exists, never called for orphans). Unbounded `filesDir` growth on repeated restores.
- **P2 — pooled `RestClient` factory** (×3, #107) → use `JdkClientHttpRequestFactory` (§3b).
- **P2→P3 — catalog detail N+1** (prior review): corrected to **1+4 SELECTs, not 1+8** — no path
  re-queries `detail()` (the prior review's "doubling" was wrong), collections are tiny, traffic low.
  `@EntityGraph` is a clean polish, not urgent.
- **P2 — LLM budget undercount + 4xx retry** (#100/#103): real but **dormant tier** → no live spend; the
  cap is a *call* cap (so ×2 undercount at default `maxAttempts`, not "tokens"). Fix with the Resilience4j
  cluster when the tier is enabled.
- **P3 — fixed-window ~2× boundary burst + O(n) purge >10k IPs** (#103-deferred): textbook fixed-window
  property; the limiter's own doc says "not a security control"; the real backstop is the daily budget.
- **P2/P3 — sidecar URL not validated** (prior review, unfixed; operator-controlled). Fold into the §2 P3
  broader-URL-validation fix.
- **P3 — `AndroidPhotoStore`/`ImageReader` log `content://` URIs un-gated** (prior review): minor (logcat
  not cross-app readable on API 31+; URIs are opaque MediaStore ids), but `isMinifyEnabled=false` means
  they ship in release — gate behind `BuildConfig.DEBUG`.
- **P2 — CI actions on floating tags** (prior review): **corrected attribution** — `ci.yml` has only
  `contents: read`, so the GHCR-write risk is in **`publish-image.yml`** + **`ocr-sidecar.yml`**
  (`packages: write`, push the `:latest` the VM pulls). Pin those (+ all) to SHAs via Dependabot first.
- **P2 — restore-RTO runbook** (#104): backup + a rehearsal exist and work; what's missing is a
  consolidated "VM dies → `tofu apply` + restore latest S3 dump → verify health" runbook + a named RTO and
  a written accepted-risk for single-AZ. `infra/RESTORE.md`.
- **P3 — `backup_storage` SA holds `storage.admin` folder-wide** (its own TODO,
  [backups.tf:6-16](infra/backups.tf#L6-L16)): a static key on an internet-facing VM that can read/delete
  every bucket incl. the tf state. Tighten to `storage.uploader` on the one bucket.
- **P3 — VM pulls mutable `:latest`, image unsigned, no provenance/SBOM** (prior review): deploy-by-digest
  or `:<sha>` (already pushed), cosign keyless, buildx `--provenance --sbom`.
- **flavor-provenance schema prerequisite** for run-11 backfill (#100, flagged in plan §4a): the current
  `tea_flavor(tea_id,dimension,intensity)` can't hold per-dimension status/confidence/provenance — migration
  built only when that workstream starts. Adequately flagged.
- **Seed 100 → ~300** (#95); **`values-en` ship-or-label** before public release (#70.5); **real-packaging
  OCR CER still owed** (#105 — only a synthetic-text floor measured so far).

---

## 5. Research follow-ups

- **Extend run 13 (no new folder) — measure real-packaging CER.** The single owed empirical item (#105):
  run the committed `proof/` harness over actual ru/en tea-packaging *photos* (not synthetic renders),
  and re-measure **with the §2 EXIF fix in place** (rotated input is the dominant real-world failure mode).
  Add a crafted pixel-bomb case to confirm the §2 guard. This is empirical proof work under the existing
  run, not a multi-model question.
- **NEW run 15 — GMS-free crash/error telemetry for a local-first RuStore app** (scaffolded at
  `research/15-crash-telemetry/`). Genuinely open, decision-blocking, and multi-model-worthy (self-host on
  the existing Yandex VM, GMS-free, RU data-residency/ToS, RuStore compatibility, cost): Sentry self-hosted
  vs **GlitchTip** vs **ACRA** vs none. Local-first makes a silent post-release DB-wipe (the P0 migration
  risk) invisible without it. Decide keep/skip **before** the first public APK.
- **Run 14 stays reserved** for the deliberately-deferred "re-verify the Yandex async / structured-output
  surface" (post-OCR review §5) — run it immediately before the background-enrichment tier is built, not now.
- **Not warranted (busywork):** a fresh OCR-engine, flavor-backfill, batch-vs-async, maps, web-search, or
  search-engine run — all closed/comprehensive (#79/#45/#20, runs 10/11/12/13).

---

## 6. Suggested next sequence

1. **Sidecar pixel guard** (§2 P2) — `Image.MAX_IMAGE_PIXELS` + dimension check → 413; add the pixel-bomb
   pytest. Cheapest, highest-value robustness fix; do it before any real traffic.
2. **EXIF rotation** in `AndroidImageReader` (§2 P2) — unblocks the primary camera-scan accuracy; re-run
   the run-13 CER proof with it on.
3. **Compose network segmentation** + sidecar egress deny (§2 P2 + P3) — defense-in-depth matching the
   stated image-processing threat model. Pure infra.
4. **OCR global concurrency bound** (§2 P2) — server-side semaphore → 503 fast-fail, or async `/teas/ocr`.
5. Add the **sidecar/OCR-chain tests** (§2 P3) and the **Wikidata zh-label** broadening (§2 P3, verify live).
6. Decide **crash telemetry** via run 15; queue the **supply-chain** tightening (SHA-pin publish workflows,
   SA least-privilege, deploy-by-digest + cosign) and the **Room public-schema cutover** before any public APK.
7. Opportunistic: `JdkClientHttpRequestFactory` pooling, `@EntityGraph` detail, photo-orphan sweep,
   `#101` metadata-merge for same-catalog duplicates (+ `nameEn` in the local matcher), `RESTORE.md`.

## External references checked
- Pillow decompression-bomb / `MAX_IMAGE_PIXELS`: https://pillow.readthedocs.io/en/stable/reference/Image.html#PIL.Image.open
- AndroidX ExifInterface: https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
- Docker Compose networks (segmentation): https://docs.docker.com/compose/networking/
- Spring `JdkClientHttpRequestFactory` (JDK 21 pooled client): https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Spring Data JPA `@EntityGraph` + `MultipleBagFetchException`: https://docs.spring.io/spring-data/jpa/reference/jpa/entity-graph.html
- Bucket4j: https://github.com/bucket4j/bucket4j · Resilience4j: https://resilience4j.readme.io/ · Caffeine: https://github.com/ben-manes/caffeine
- Wikidata label-service / language fallback: https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service/queries#Label_service
- Sentry self-hosted: https://develop.sentry.dev/self-hosted/ · GlitchTip: https://glitchtip.com/ · ACRA: https://github.com/ACRA/acra
- OSV-Scanner action: https://google.github.io/osv-scanner/github-action/ · cosign keyless: https://docs.sigstore.dev/cosign/signing/overview/
