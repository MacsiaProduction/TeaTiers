# Locked decisions

Append-only. Each entry is a decision we have committed to. Don't rewrite history —
add a new dated entry that supersedes an old one if something changes. Stack defaults
already locked in `AGENTS.md` / `.cursor/rules/*` are not repeated here unless we
deviated.

## 2026-06-14 — Product & architecture shape (from planning Q&A)

1. **App model: personal, local-first. No accounts, no auth.**
   All user data (teas added, tier placements, boards, purchase locations, notes)
   lives **on-device in Room**. There is no user login and no cloud storage of user
   data, so there is no auth, no per-user backend storage, and no sync/conflict
   resolution to build.

2. **Backend = shared tea-catalog service only.**
   The single Linux backend's sole job is to ingest/mirror public tea data and serve
   a **read-only, multilingual tea-catalog search/detail API**. It holds no user
   data. (Write paths for user-contributed catalog entries are out of MVP scope.)

3. **Target market: Russia first.**
   Distribution via **RuStore + direct APK**, not primarily Google Play. Implication:
   **do not hard-depend on Google Play Services**. Yandex is the intended default map
   provider (see decision 9, pending research).

4. **Catalog delivery: live query + cache.**
   The app searches the backend catalog over the network and caches results in Room
   for offline reuse. The user's own tier data is always fully local and works
   offline; catalog *search of unseen teas* requires connectivity.

5. **Catalog is rich metadata.**
   Each catalog tea carries: tea type, origin/region, optional cultivar, oxidation,
   optional brand/vendor — plus **ru/en/zh names** (zh ideally with pinyin). The
   schema is locale-aware from the first migration.

6. **Tiers are user-customizable.**
   Users can rename, reorder, add/remove, and recolor tiers. Ships with a default
   S/A/B/C/D ladder.

7. **Multiple boards.**
   A user can create several tier lists (e.g. per tea type or per year); each board
   has its own tiers and tea placements.

8. **Purchase location: map pin-drop + reverse geocode (MVP).**
   Pick a point on a map, auto-fill the place name (shown in ru); also allow a
   marketplace URL or free text. The provider sits behind one Kotlin interface.

9. **Maps/geocoding provider — RESOLVED 2026-06-14** (research `02-maps-geo-android`,
   winner opus; see its `RATING.md`). **This flips the earlier assumption** that Yandex
   was the default. MVP **and** likely long-term default is **MapLibre Native + keyless
   tiles (OpenFreeMap, style URL kept swappable) + reverse geocode via on-device
   `android.location.Geocoder` (guarded by `isPresent()` + the API-33 async overload),
   falling back to Photon (`lang=ru`) / Nominatim (1 req/s, custom User-Agent, cache,
   ODbL attribution).** No API key, no Google Play Services.
   - **Yandex MapKit is demoted to an optional, terms-gated upgrade**, NOT the default:
     its *free* terms reportedly prohibit storing geocoder results in our own DB — which
     is exactly what a local-first app does (saves the place name to Room). Storing it
     would need a paid commercial license. Revisit only with a verified key + terms.
   - **Google Maps: do not ship** — hard GMS runtime dependency fails on no-GMS RuStore
     devices (and GCP billing is impractical with RU cards).
   - All SDK version pins from the research **conflict** — verify against Maven Central
     at integration time; do not copy a pin from the answers.

10. **Catalog sourcing strategy — RESOLVED 2026-06-14** (research `01-tea-databases`,
    winner opus; see its `RATING.md`). **Lead with a hand-curated ~300-tea seed**
    (budget ~30–50 person-hours), then mirror only the redistributable open core:
    **Wikidata (CC0)** for ru/zh/pinyin names = the backbone; **Wikipedia (CC-BY-SA)**
    prose where share-alike is accepted; **Open Food Facts (ODbL)** category taxonomy
    kept *isolated* from curated data to avoid copyleft pollution.
    - **Never mirror/redistribute** Steepster, RateTea, TeaDB, Tea Guardian, World Tea
      Directory, Moychay, Baidu Baike — all rights reserved; human reference only.
    - Authoritative naming refs: **GB/T 30766-2014** (zh six-category type enum),
      **GOST 32593-2013** + **Teatips.ru** (ru terms); transliterate trade names, never
      literal-translate. Verify every Wikidata QID before import.
    - Per-row provenance (`source`/`source_url`/`license`/`retrieved_at`) is mandatory
      for ODbL/CC-BY-SA share-alike compliance.

11. **Deploy: host-agnostic Docker Compose.**
    Multi-stage Docker image + `docker-compose` (backend + PostgreSQL) + a systemd
    unit / Makefile entry points. Point it at any Linux VPS later.

12. **UI languages: Russian-only UI at MVP.**
    App UI strings ship in **ru** first; en/zh UI added in a later phase. Tea **names**
    still carry all of ru/en/zh regardless of UI language.

## 2026-06-15 — Catalog enrichment (AI + verification)

13. **Enrichment widens catalog *quality*, on-demand, server-side.**
    A backend enrichment step fills/normalizes ru/en/zh + pinyin names, verifies/cross-
    checks facts, and auto-fills a user's custom tea. It does **not** crawl the web to
    *discover* new teas. Keys are server-side only — the app never calls an AI/search
    provider directly.

14. **On-demand "enrich-on-miss", auto-publish `unverified`.**
    No background batch. When `POST /api/v1/teas/resolve` misses the catalog, the backend
    enriches that one tea (Wikidata CC0 first → YandexGPT fallback), cross-checks, and
    **publishes it immediately flagged `verification_status='unverified'` with a
    confidence score**. The published row is the cache: the next user who types the same
    tea gets it with no further enrichment. A later curation pass promotes `verified`.
    Implication: the catalog API is **write-capable** → per-IP rate-limit, input caps,
    dedup, and a daily enrichment-call budget are required. Only a raw tea-name string
    reaches the backend; no user identifier (so still no PII server-side).

15. **Providers — RESOLVED 2026-06-15** (research `03-ai-enrichment`, winner opus /
    `opus-2`; see its `RATING.md`). The ToS trap is cleared: **YandexGPT permits storing
    and re-serving model output** (AI Studio Terms cl. 4.1 / 1.2.2 / 3.15) when request
    logging is disabled (`x-data-logging-enabled: false`); cl. 3.3 (verify before
    distribution) mandates our `unverified`+confidence gate. Chosen stack:
    - **Primary LLM: YandexGPT Lite** (OpenAI-compatible endpoint, `json_schema` output,
      IAM/API-key auth, `maxTokens` capped, **logging disabled**); Pro only for hard
      cases. Funded by the ~₽4,000/$50 60-day grant, then pay-as-you-go (no perpetual
      free LLM tier).
    - **Free verification spine: Wikidata** (`wbsearchentities` + SPARQL, CC0) **→
      Wikipedia ru/zh sitelinks** — RU-reachable, ToS-clean, resolves most known teas
      with zero LLM spend (the "Wikidata-first" step).
    - **Free LLM fallback: GigaChat (Sber)** — 1,000,000 free tokens/year, RU-reachable
      (needs RU phone/SberID). **Caveat: Freemium is personal/non-commercial — needs a
      paid Sber contract if TeaTiers is ever monetized.**
    - **Yandex Translate: prose only, NEVER tea names** (translates literally; no pinyin).
    - **Do NOT integrate** Gemini (RU geo-blocked) or Brave (free tier removed Feb 2026).
      Groq/Mistral/OpenRouter/DeepSeek/Tavily RU-reachability is unverified — don't
      architect around them.
    - **Confidence is programmatic** (Wikidata/Wikipedia match + `pinyin4j` Hanzi↔pinyin
      check + a transliteration litmus test that penalizes literal RU translation), not
      the model's self-rating.

## 2026-06-15 (addendum) — German VPN reopens Western free LLMs

16. **Revised LLM order (supersedes the provider ordering in #15; verification spine and
    guardrails from #15 still stand).** We have a **Germany (EU) VPN** and can route the
    backend's egress through it, which removes the *reachability* blocker that demoted
    Gemini/DeepSeek/Groq/Mistral in research 03.
    - **Primary LLM: Google Gemini free tier**, reached via the DE egress — genuinely
      $0 (no grant burn), strong ru + zh. **Safety net: YandexGPT Lite** (direct, no VPN)
      whenever the VPN is down, Gemini quota is hit, or EU free-tier access fails.
    - **Data egress: unrestricted** — tea-name strings carry no user data, so Google
      (Gemini), China (DeepSeek), EU (Mistral), US (Groq) are all acceptable. DeepSeek
      stays available as an optional booster for hard Chinese names.
    - **Routing: split-tunnel.** Only enrichment HTTP clients (Gemini/DeepSeek/etc.)
      egress through the German VPN, via a per-client proxy in the `enrichment/` module.
      Wikidata, YandexGPT, and all inbound traffic stay direct (RU).
    - **Still GigaChat-free?** No longer the default fallback — superseded by YandexGPT
      as the no-VPN safety net. Keep as a tertiary option only.
    - **PENDING verification → `research/04-eu-egress-llm/`:** confirm Gemini's free tier
      works from an EU IP **without a billing card** and that its ToS **permits storing +
      re-serving output** (the recurring trap). If it fails, fall back to YandexGPT
      primary (#15) — no redesign needed.

17. **Final enrichment LLM order — RESOLVED 2026-06-15** (research `04-eu-egress-llm`,
    winner opus; supersedes the "Gemini primary" in #16). Run 04 confirmed Gemini's free
    tier is **non-viable as primary**: Gemini API Additional Terms require *Paid Services*
    to serve EEA/CH/UK and an unbilled EU project gets a `400 — user location not
    supported without billing`. (Output storage itself is permitted; the EU-free premise
    is what fails.) User chose **YandexGPT-primary with a VPN booster**, and prefers **not
    to depend on a non-RU payment card**. Final order:
    1. **Wikidata → Wikipedia** (free, direct, no VPN) — resolves most teas, no LLM spend.
    2. **YandexGPT Lite** (direct, **no VPN**) — primary LLM on a Wikidata miss; ToS-clean
       store+serve (cl. 4.1/3.15), logging off, `json_schema`. Cheap (grant → ~₽0.2/1k).
    3. **Groq `qwen/qwen3-32b` via the Germany VPN** — **booster** invoked only for hard /
       low-confidence Chinese names where Yandex is weak. **Perpetually free, no card**,
       no training on API data, JSON output.
    4. **DeepSeek** (via VPN) — *optional* extra zh booster while the one-time 5M-token
       bonus lasts; **not a committed dependency** (top-up needs a non-RU card → avoided).
    - **VPN role = booster-only.** The common path (Wikidata + YandexGPT) is fully
      VPN-independent; only the Groq/DeepSeek booster calls tunnel through Germany. If the
      VPN is down, enrichment still works (Wikidata + Yandex), just weaker on obscure zh.
    - **Not used:** Gemini (EEA paid-only), Mistral (weak zh), GigaChat (superseded), any
      paid top-up.
    - Booster trigger: confidence below threshold AND a Chinese/CJK name present.

## 2026-06-15 (addendum 2) — Yandex Cloud native (Terraform + native booster, VPN dropped)

18. **Go all-in on Yandex Cloud; drop the German VPN.** Research `05-yandex-terraform`
    (winner alice) and `06-yandex-alice` (winner alice) — see their `RATING.md`.
    - **Deploy (refines #11):** provision on **Yandex Cloud via Terraform** — one Compute
      VM (`standard-v3`, burstable) running our **`docker-compose` with self-hosted
      Postgres** (Managed PostgreSQL only if backups/HA are later needed), `yandex_vpc_*`
      + security group (443/80 + SSH-from-my-IP), service account, **Container Registry**,
      **Lockbox** secrets, **S3 remote state on Object Storage + YDB locking**, and
      GitHub Actions CI. Pin the provider (verify — `0.206.0` per two of three answers).
      **RU gotcha:** the provider may be unreachable from `registry.terraform.io` → use
      the `terraform-mirror.yandexcloud.net` mirror. Still containerized/portable, so #11's
      spirit holds; the concrete target is now Yandex Cloud.
    - **Booster → Yandex-native (supersedes the VPN booster in #17).** Yandex Cloud
      Foundation Models **hosts Qwen3-235B and DeepSeek V4 Flash natively** (per run 06 +
      opus run-03), callable via the same OpenAI-compatible SDK. So the hard-Chinese-name
      booster runs **on Yandex Cloud directly** — **the German VPN is dropped entirely**.
      No split-tunnel, no egress proxy; all LLM calls (YandexGPT primary + Qwen3/DeepSeek
      booster) are direct Yandex Cloud, billed as cheap grant-covered tokens.
      **Verify in console** that Qwen3-235B / DeepSeek Flash are listed + their pricing
      before building; if absent, the prior Groq-via-VPN path (#17) is the fallback.
    - **Alice AI LLM:** a real, distinct Foundation Models model (`gpt://<folder>/
      aliceai-llm`, 64k ctx; cheaper `aliceai-llm-flash`), same API/auth/logging/json_schema
      as YandexGPT. **Keep YandexGPT Lite as the documented primary for now; benchmark
      `aliceai-llm-flash` (cheaper input, 64k) and `aliceai-llm` (hard cases) in the
      enrichment milestone (M4)** and promote only if they test better. Flash's `json_schema` support is unconfirmed —
      test before relying on strict structured output. No transliteration benchmark exists.
    - **Net effect:** the VPN, split-tunnel, and egress-proxy from #16/#17 are **removed**.
      Everything (catalog API, Postgres, all enrichment LLMs) lives on Yandex Cloud,
      provisioned by Terraform. Off-Yandex pieces remain only on the *client* (MapLibre/
      OpenFreeMap, on-device geocode) and the free Wikidata/Wikipedia verification calls.

## 2026-06-15 (addendum 3) — UX & cost review

19. **Backend hosting: keep the always-on VM (confirms #11/#18).** Reviewed scale-to-zero
    serverless (cheaper idle, ~0 ₽ on the free tier) vs the always-on VM (~700–1700 ₽/mo).
    **Chose the VM** for operational simplicity (no GraalVM/cold-start work); the 4,000 ₽
    grant covers the early months and the cost is acceptable for a hobby-scale app.
    Serverless stays an option if idle cost ever matters.

20. **Purchase location MVP = free-text + marketplace URL only; the map/geopoint is CUT
    from MVP (supersedes the map pin-drop in #8 for MVP scope).** Reverse-geocoding to
    Russian addresses via OSM/Nominatim is the weak link (patchy RU coverage, public-
    Nominatim production ban + 1 req/s, unreliable on-device `Geocoder` on no-GMS devices),
    for a *secondary* feature. So MVP stores only a free-text place and/or a marketplace
    URL — no `GEOPOINT`, no map, **no location permissions**. The maps stack (research 02:
    MapLibre/OpenFreeMap/Geocoder, or Yandex MapKit pick-only) and `GEOPOINT` are a
    **post-MVP (M6)** item; research 02's findings stand but aren't used in MVP.

21. **Enrichment is non-blocking; enriched names are editable suggestions.** Adding a tea
    is **optimistic** — it appears on the board instantly with the user's typed name; the
    client calls `/resolve` in the **background** and patches the local row with ru/zh
    names + metadata when it returns. **Offline → the enrichment is queued** and runs on
    reconnect, so logging a fresh purchase never blocks on the network (preserves the
    local-first promise of #1). The enriched name is a **suggestion the user can edit** on
    their own board — never presented as authoritative (pairs with the `unverified` flag).

## 2026-06-15 (addendum 4) — Tea entity: flavor metrics, image, descriptions

22. **Tea entity extended for a photo + taste card (not a wall of text).** The card shows
    **image + name/pinyin + type chip + a short taste blurb + a mini flavor bar**; the
    **detail** screen shows the full flavor radar, origin/cultivar, and the long text only
    behind a **"read full"** affordance. Schema adds:
    - `tea_description(tea_id, locale, short, full?, source, license)` — localized ru/en/zh.
      `short` = 1–2 sentence taste blurb (own/AI) for the card; `full` = long text behind
      "read full". **Full text from Wikipedia is CC-BY-SA → attribute + link to source.**
    - Image fields on `tea` (see #24).
    - Flavor metrics (see #23).

23. **Flavor metrics = catalog reference + user override.** Taste is subjective, so:
    - **Catalog** `tea_flavor(tea_id, dimension, intensity 0–5)` — a *reference* profile:
      hand-curated for seed teas, **AI-estimated and `unverified`** for the rest (the LLM
      enrichment now also returns a flavor estimate + short blurb; both editable, never
      authoritative).
    - **Room** `user_tea_flavor(user_tea_id, dimension, intensity 0–5)` — the user's own
      ratings, which override/augment the reference on their board.
    - `dimension` is a **stable enum** (BITTERNESS, SWEETNESS, ASTRINGENCY, FRUITINESS,
      FLORAL, GRASSY, SPICY, SMOKY, EARTHY_NUTTY, UMAMI, ROASTED — extensible); the DB
      stores enum + number (drives a radar/bar chart). **Labels (горечь/bitterness/苦味)
      are localized client-side** in `strings.xml`, not in the DB.

24. **Photos = user-attached + optional CC catalog image; no arbitrary web images.**
    - **Room** `user_tea.user_photo_uri` — the user's own photo of their tea (local,
      license-clean, the ideal source for a personal app).
    - **Catalog** `tea.image_url?` + `image_license` + `image_source_url` — an optional
      **CC / Wikimedia** reference image (curated for seed teas).
    - Fallback = a **type-based placeholder** (e.g. a green-tea swatch) when neither exists.
    - **Never auto-fetch arbitrary web images** — licensing (consistent with #10). The LLM
      does not generate images.

## 2026-06-15 (addendum 5) — Grounded enrichment from user-pasted text

25. **Optional pasted source text grounds the flavor profile (and improves the shared
    catalog).** The add-tea flow offers an optional **"paste a description"** field (e.g.
    a store's product page, ru/en/zh); `POST /api/v1/teas/resolve` gains an optional
    `sourceText`. When present, the LLM **derives** the flavor profile + short blurb **from
    that text** (grounded mode) instead of guessing from the name — less hallucination,
    **higher confidence**.
    - **Destination:** the grounded result **enriches the shared catalog** entry (still
      `unverified`, but higher confidence than zero-shot); the user can still override with
      their own ratings (#23). One paste benefits everyone who later looks up that tea.
    - **Copyright:** we **extract a structured profile** and write an **original
      paraphrased** blurb; we **never store or republish the raw pasted prose** in the
      catalog (the vendor text is copyrighted — consistent with #10). The user may keep the
      raw text in their own local notes.
    - **Untrusted input:** `sourceText` is treated as **data, not instructions** —
      delimiter-wrapped, length-capped, HTML-stripped, injection-hardened, output strictly
      schema-validated (extends the §6 abuse guards / #14).
    - **Prompt:** the grounded + zero-shot prompts, rubric, JSON schema, and injection
      guards are tuned via **`research/07-flavor-prompt-tuning/`**.

## 2026-06-15 (addendum 6) — UX review: durability, navigation, states

26. **Data backup/portability: file export/import in MVP (no accounts, no GMS).** Local-
    first + no accounts + no-GMS means **Android Auto Backup (Google Drive) won't work** on
    RuStore devices — so device loss = data loss without our own mechanism. MVP ships
    **"export everything to a JSON file" + "import"** via the Android **share sheet /
    Storage Access Framework** (save to Yandex Disk / Telegram / Files; restore on a new
    device). **Later (post-MVP):** optional auto-sync to the *user's own* cloud (e.g.
    Yandex Disk via SAF) — still **no accounts**. This is the safety net for #1's local-only
    model. Exported file carries only the user's own data (boards/tiers/teas/notes/photos
    refs/ratings) — no secrets.

27. **Cross-board "my teas" view in MVP.** Beyond per-board filtering, MVP includes a
    **global view of all the user's teas with search/filter across boards** (find "that
    oolong I rated last year"). **Sharing a tier list as an image** stays **post-MVP**
    (high delight, but not MVP-critical).

28. **UX states & surfaces (fold-ins from the review).** The MVP must also cover the
    moments that make the app feel finished — not just the happy path:
    - **Enrichment status:** add `user_tea.enrichmentState` (`none`|`pending`|`queued`|
      `done`|`failed`) so the UI can show a subtle "enriching…/offline-queued/failed —
      retry" indicator (the optimistic patch of #21 must be observable + recoverable).
    - **Empty / zero states:** first-run (no boards), empty board, and especially
      **search-found-nothing → smooth "add it yourself / paste a description"** (ties into
      the optimistic-add + grounded path, #21/#25).
    - **Attributions/licenses screen:** CC-BY-SA (Wikipedia), ODbL (OFF), CC images legally
      require visible attribution; surface the stored provenance + per-item source links.
    - **Settings screen:** language, theme (incl. dark), export/import (#26), about.
    - **Flavor entry UX:** a **quick-rate mode** (a few key dimensions, not all 11) and a
      clear **"catalog reference vs my rating"** display (#23).
    - **Name display rule:** card shows the **ru primary name + pinyin + zh hanzi**; full
      locale set on detail.
    - **Accessibility:** dark mode, font scaling, TalkBack labels (esp. the flavor chart).

## 2026-06-15 (addendum 7) — Phase 0 scaffolding & toolchain

29. **Spring Boot 4.1.0 (deviation from rule `30-backend` "Spring Boot 3.x").** The 3.x line
    is end-of-life/limited at this date (3.4 EOL Dec-2025, 3.5 OSS support ends ~mid-2026),
    so a greenfield service should not start on it. **4.1.0** (GA 2026-06) on **JDK 21** is
    the pinned baseline; WebMVC + Actuator only for Phase 0. Note: Boot 4 moved some test
    slice annotations (`@WebMvcTest`) to a new module/package — the Phase 0 controller test is
    a plain unit test; a proper slice test is wired in M2 once the controller layer grows.

30. **Android design system = "Настой" (user-selected 2026-06-15).** Identity grounded in tea
    itself, not a generic template:
    - **Palette:** porcelain light surfaces (`#F3F4F0`/`#FAFAF6`) and a "steeped" dark theme
      (`#14110E`); **tea-leaf green** (`#356A4B`) as the single brand accent; amber tertiary.
    - **Signature = brewed-liquor color coding:** every tea carries the color of its *настой*
      (a per-`TeaType` liquor swatch) used on cards, chips, and the featured panel.
    - **Tier ramp** S→D is a **steeping/oxidation gradient** (deep roasted-red → pale sage),
      not a meme rainbow; tiers stay user-recolorable (#6).
    - **Flavor radar** is the detail motif (a Canvas polygon over the #23 dimensions); compact
      labeled **flavor bars** on cards.
    - **Type:** Material 3 type scale on the system family for now (full Cyrillic + CJK
      fallback, nothing binary bundled, no GMS downloadable-fonts per #3/#9); documented
      `res/font/` drop-in slot to adopt **Golos Text** later. ru-first copy (#12).

31. **Phase 0 build structure & static-analysis deferrals (pragmatic, reversible).**
    - **Two independent Gradle builds** (`app/` JDK 17, `server/` JDK 21), each with its own
      wrapper (**Gradle 9.5.1**) and `libs.versions.toml`, orchestrated by a root `Makefile`
      + a 2-job GitHub Actions matrix. Rationale: different JDK toolchains and disjoint plugin
      ecosystems; cleaner than one root multi-project build. (Revisit if cross-module sharing
      grows.)
    - **Pinned, mutually-compatible toolchain** (verified upstream, not guessed): AGP 9.2.1,
      Kotlin 2.4.0, KSP 2.3.9 (Kotlin docs pair 2.4.0↔2.3.9), Hilt 2.59.2 (adds AGP 9
      support), Compose BOM 2026.05.01, `compileSdk`/`targetSdk` 36, `minSdk` 26.
    - **detekt/ktlint deferred:** no detekt release supports Kotlin 2.4 / Gradle 9 yet; wire
      them when a compatible build ships. `check` still runs tests + Android Lint.
    - **Android Lint `abortOnError=false` for Phase 0 only** (no SDK on the dev host → lint
      can't be vetted locally); keeps CI green while still reporting. Curated lint baseline +
      `abortOnError` return in M1.
    - **Package base `com.macsia.teatiers`** (not `fun.macsia.*` — `fun` is a Kotlin keyword).
    - App unit smoke tests use **JUnit4** (Android default, zero extra config); JUnit5 + MockK
      + Turbine adopted in M1 with real ViewModel tests.

## 2026-06-15 (addendum 8) — AGP-9 build wiring (verified green in CI, PR #1)

32. **Corrections to #31 found while making `app` build green in CI** (run `27546775866`,
    both jobs success). These are deliberate; do not revert without re-breaking AGP 9:
    - **No `org.jetbrains.kotlin.android` plugin.** AGP 9 ships **built-in Kotlin** and rejects
      that plugin outright. It is removed from the app plugins block and the version catalog.
      The **Compose compiler plugin** (`org.jetbrains.kotlin.plugin.compose`, `version.ref =
      kotlin`) pins the built-in Kotlin Gradle Plugin to our **2.4.0**; the `kotlin { … }` DSL
      (`jvmToolchain(17)`) remains available.
    - **`compileSdk` = 37.0, not 36** (supersedes #31's "36" for compileSdk only; `targetSdk`
      stays 36, `minSdk` 26). The pinned Compose BOM pulls `androidx.core:1.19.0`, which
      mandates `compileSdk >= 37`; AGP 9.2 supports max API 37.0. Use the block DSL
      `compileSdk { version = release(37) { minorApiLevel = 0 } }` — CI runners install the
      platform as `android-37.0`, so the integer `compileSdk = 37` form fails to resolve
      `android-37`.
    - **Hilt + Kotlin 2.4 metadata override.** `hiltJavaCompileDebug` fails with "Provided
      Metadata instance has version 2.4.0, while maximum supported version is 2.3.0" because
      Dagger 2.59.2 (and 2.60) bundle an older `kotlin-metadata-jvm`. Fix: add
      `ksp("org.jetbrains.kotlin:kotlin-metadata-jvm")` pinned to the Kotlin version so the
      highest version wins on the processor classpath (canonical fix per `google/dagger#5001`,
      mirrors `android/nowinandroid`).
    - **Known non-fatal warning (M1):** `hiltViewModel()` is deprecated and moved to package
      `androidx.hilt.lifecycle.viewmodel.compose`; the import migration is an M1 cleanup.

## 2026-06-15 (addendum 9) — B+C UI pass + M1 Room persistence

33. **B+C UI pass closed out #30/#31/#32 deferrals (PR #2).**
    - **Golos Text** (OFL variable TTF) is now bundled in `res/font/` and wired into the M3
      type scale via `FontVariation` (`@OptIn(ExperimentalTextApi::class)`); the OFL license
      ships in `assets/licenses/`. **Supersedes #30's** "system fonts now / `res/font/` slot
      later" and the #31 "nothing binary bundled" note.
    - **Android Lint `abortOnError = true`** (warnings stay non-fatal). **Supersedes the
      Phase-0 `abortOnError=false`** in #31/#32; a committed baseline is added only if a
      future finding is genuinely unavoidable.
    - **`hiltViewModel()` import migrated** to `androidx.hilt.lifecycle.viewmodel.compose`
      (artifact `hilt-lifecycle-viewmodel-compose` 1.3.0); dropped `hilt-navigation-compose`
      so there is no transitive `androidx.navigation`. **Resolves the #32 known warning.**
    - **Navigation = a tiny dependency-free saveable back stack** (`Destination` sealed type
      + `BackStackSaver`), not navigation-compose — the graph is small (boards → board → tea
      detail / add tea). Revisit if the graph grows.

34. **M1 persistence = Room 2.8.4, user data only, board-scoped teas.** Backs
    `TeaBoardRepository` with Room (per #1 local-first) so boards/tiers/teas/flavors/notes/
    purchase-locations survive process death; the public repo surface is unchanged from
    Phase 0, so the screens/ViewModels did not change shape.
    - **Version: Room 2.8.4** (latest stable, Nov-2025; Room 3.0 is still alpha). `room-runtime`
      + `room-ktx` (Flow/suspend) + `room-compiler` via KSP; inherits the Kotlin-2.4 metadata
      override (#32) already on the KSP classpath.
    - **Schema (normalized, board-scoped):** `boards`, `tiers(boardId FK)`, `teas(boardId FK,
      tierId?)`, `tea_flavors(teaId FK, dimension, intensity, PK[teaId,dimension])`,
      `purchase_locations(teaId FK, kind, label?, value)`. A tea is **board-scoped** (one
      placement = one row keyed by a board-unique id), **not** a shared catalog — the shared
      tea catalog is M2/M4 with the backend. Enum columns store the enum `name` as text
      (#10/#23) and convert in the mappers (no TypeConverter).
    - **`exportSchema = false` for v1** (no migrations to diff yet); flip it on with a
      `room.schemaLocation` + committed JSON when the schema first changes.
    - **First-run seeding** from `SampleBoardProvider` on an empty DB, launched on an injected
      app-lifetime `@AppScope CoroutineScope`; `boards` is a hot `StateFlow`
      (`SharingStarted.Eagerly`) so synchronous reads see loaded data and an added tea appears
      on every screen at once.
    - **Writes are `suspend`** (`addTea`) inside a single `@Transaction`; the add-tea flow
      navigates back after the row is persisted (the StateFlow reflects it everywhere).

35. **Purchase-location code realigned to #20 (removed geo that slipped into the B+C pass).**
    The B+C UI had introduced a `GEO` purchase kind (map provider + lat/lng) plus a
    `MapLinkProvider`, contradicting the locked #20 MVP cut (free-text + marketplace URL only;
    no geopoint, no location permission). Removed `PurchaseLocation.Geo` / `GeoProvider` /
    `MapLinkProvider`, the GEO add-form UI, the geo detail row, and the geo strings. So
    `purchase_locations.kind` is `URL | TEXT` only. The map/geopoint returns post-MVP (M6).

36. **DataStore deferred (user-selected "Room only").** Preferences (theme/language, the #28
    settings) are not wired in M1 — only Room user-data persistence. DataStore lands with the
    settings screen in a later milestone.

## 2026-06-15 (addendum 10) — M1 unit tests

37. **M1 tests = pure-JVM JUnit 5, no Robolectric (yet).** Cover the logic that does not need
    an Android runtime: entity↔domain mappers, the add-tea form/model functions, the
    `TeaBoardRepository` (over a hand-written in-memory `FakeTeaDao`), and `AddTeaViewModel`.
    - **JUnit 5.14.4 + MockK 1.14.9 + Turbine 1.2.1 + kotlinx-coroutines-test 1.11.0**; the
      module runs on the JUnit Platform (`testOptions.unitTests.all { useJUnitPlatform() }`).
      The two Phase-0 tests were migrated JUnit 4 → 5; **drops the JUnit 4 dependency.**
    - **No Robolectric:** Room verifies its queries/relations at compile time, so the DAO is
      faked rather than run on a simulated device; this avoids pinning a Robolectric/SDK combo
      against AGP 9 + JDK 17. Instrumented Room/Compose tests are a later (M-test) milestone.
    - **Coroutine tests** use `runTest` + `UnconfinedTestDispatcher`; ViewModel tests set the
      Main dispatcher (`Dispatchers.setMain`/`resetMain`) and assert flows with Turbine.

## 2026-06-15 (addendum 11) — drag-to-rank (tier-list core)

38. **Drag-to-rank is hand-rolled, no new dependency** (consistent with the custom nav back stack
    in #33). The board's tier rows + the unranked tray are drop targets; a long press picks up a
    `TeaCard` (so it does not fight the row's horizontal scroll or the column's vertical scroll),
    a floating ghost follows the finger, and dropping re-ranks the tea.
    - **Geometry in root coordinates.** A small composition-scoped `BoardDragState` tracks the
      pointer, the per-group bounds (`onGloballyPositioned`/`boundsInRoot`), and each card's
      center-x. Per-frame values are read from layout/draw lambdas (`offset {}`, `drawBehind {}`,
      `graphicsLayer {}`) so a drag move never recomposes the board. Drag state lives in the UI,
      not the ViewModel — only the resolved drop calls back.
    - **Drop resolution.** The target group is the row whose vertical band holds the pointer
      (nearest band as a fallback for gaps); the insertion index is the count of the *other* teas
      whose center-x is left of the drop point. An unknown/!valid tier resolves to the tray.
    - **Persistence = per-tier contiguous positions, one transaction.** `TeaDao.applyPlacements`
      rewrites `(tierId, position)` for every tea in the affected group(s) to `0..n`. Only ordering
      *within* a group matters (`toDomain` sorts each group by position), so untouched groups are
      left alone and absolute position values may differ across groups — never a collision.
      `TeaBoardRepository.moveTea` calls a pure, unit-tested `computeMovePlacements`.
    - **Accessibility.** Each card carries TalkBack custom actions ("move to tier X" / "to
      unranked") for every other group, so ranking never *requires* a precise drag. The tray is a
      permanent drop target (rendered even when empty) so a tea can always be dragged back out.

## 2026-06-15 (addendum 12) — customizable tiers

39. **Tiers are user-editable (rename / recolor / reorder / add / remove) on a dedicated editor
    screen**, reached from a "Тиры" action in the board's top bar. A new `Destination.TierEditor`
    follows the custom back-stack nav (#33); the editor reuses `BoardViewModel` (same Room-backed
    repository the board reads), not a new VM.
    - **Delete drops teas into the tray, never deletes them** (open item #11). `teas.tierId` is a
      plain nullable column with **no FK to `tiers`** (only the board FK cascades), so a deleted
      tier would orphan its teas — `toDomain` would place them in neither a tier nor the tray and
      they'd vanish. So `TeaDao.removeTier` reassigns the tier's teas to the tray (`tierId = null`,
      appended after the existing tray, renumbered 0..n) **and** deletes the tier in one
      `@Transaction`. A confirmation dialog states this before deleting.
    - **Reorder = contiguous positions, one transaction** (mirrors drag-to-rank #38). The editor's
      up/down buttons send the new id order; `TeaBoardRepository.reorderTiers` calls a pure,
      unit-tested `computeTierPositions` (drops unknown ids, appends omitted tiers, renumbers
      0..n, no-ops when unchanged) and `TeaDao.reorderTiers` writes it. The reassignment math is
      `computeTrayReassignment`, also pure + unit-tested.
    - **Color override stays optional** (#6). The picker offers a curated tea-toned palette plus
      "По умолчанию" which clears `colorArgb` back to null → the position-based ramp. Labels are
      trimmed and **blank renames are ignored** so a tier always keeps a usable label.
    - **Icons restricted to `material-icons-core`** (the only icons dependency): Add /
      KeyboardArrowUp / KeyboardArrowDown / Delete are all present; the extended icon set is not a
      dependency.

## 2026-06-15 (addendum 13) — tea editing

40. **Tea editing reuses the add flow as a single add/edit screen** (`Destination.EditTea`,
    "Изменить" action on `TeaDetailScreen`). One `AddTeaForm` / `AddTeaViewModel` / `AddTeaScreen`
    handles both modes — add when `editingTeaId` is null, edit when it carries the tea id — to
    avoid a parallel screen and keep the form rules (trim, drop blanks, filter zero-intensity
    flavors, blank ru name disables Save) in one place.
    - **Update writes only the editable columns and replaces the child rows** in one
      `TeaDao.updateTea` `@Transaction`: `updateTeaFields(...)` + delete-and-insert for flavor and
      purchase rows. The DAO never touches `boardId` / `tierId` / `position` / `shortBlurb`, so
      editing the form never moves the tea on the board (drag-to-rank owns placement) and never
      overwrites the catalog/AI-derived blurb.
    - **Tier picker is hidden in edit mode.** Including it would let the same form both edit *and*
      re-rank, conflicting with drag-to-rank as the single placement surface; the form's `tierId`
      is ignored on save when editing.
    - **`AddTeaViewModel.bind(boardId, teaId?)` deterministically rewrites the form** every call
      (empty for add, `tea.toForm()` for edit), because the host activity reuses the same VM
      across navigations and a recomposition with a new key would otherwise leak stale state.
    - **Multiple purchase locations.** `AddTeaForm.purchases: List<PurchaseDraft>` replaces the
      single-row `purchase`/`includePurchase` pair; the editor renders one card per row with
      add/remove. The model already supported a list (seed teas have two), so this also closes a
      gap in the add flow rather than only enabling editing.
    - **Pure-JVM tests cover** `Tea.toForm` / `PurchaseLocation.toDraft` round-tripping, the
      purchase-list helpers, edit-mode bind + submit (calls `updateTea`, not `addTea`), and the
      end-to-end `TeaBoardRepository.updateTea` over `FakeTeaDao` (preserves tier/position,
      replaces flavors + purchases).

## 2026-06-15 (addendum 14) — board creation + tier templates

41. **A board is created from one of three tier templates: F-S, 1-10, or blank** (brainstorm
    point B, `context/brainstorming.md`). The Phase 0 / M1 build had no user-facing create-board
    flow at all — boards only came from `SampleBoardProvider`'s seed. This addendum closes that gap
    *and* settles the default tier set: the brainstorm called for two literal defaults plus a
    custom path, so F-S replaces the implicit "S/A/B/C/D" we used to seed sample boards.
    - **`TierTemplate` is a domain enum** (`F_TO_S`, `ONE_TO_TEN`, `BLANK`) with a pure
      `seedTiers(boardId)` extension that returns the initial `List<Tier>`. Tier ids are
      namespaced by the board id (`"<boardId>-s"`, `"<boardId>-n10"`, …) because `TierEntity.id`
      is a global primary key — two boards on the same template would otherwise collide.
    - **Tier *labels* stay literal Latin / Arabic numerals** ("S/A/B/C/D/F", "1"…"10"). Tier-list
      shorthand reads identically across ru/en/zh; localising "S" buys nothing and would break the
      visual identity. The user can rename anything via the tier editor (#39) post-creation.
    - **`TeaBoardRepository.createBoard(label, template)` validates the label** (trim,
      blank-is-no-op-returns-null) and writes the board + seeded tiers in one
      `TeaDao.createBoardWithTiers` `@Transaction` so an interrupted insert never leaves a board
      without its template's tiers. The new board id is `"board-<uuid8>"` to keep ids
      human-readable in tests and logs.
    - **The board-create UI is a Material 3 dialog (name field + radio template picker)** opened
      from a plain icon-only FAB on `BoardsScreen` (matches the minimalist "Настой" direction).
      The empty state also exposes a primary "Создать первую подборку" button so first-run users
      see a CTA without having to discover the FAB. Default-selected template is F-S.
    - **The boards Flow is hot (`SharingStarted.Eagerly` on `@AppScope`) so a new board appears
      instantly** without manual refresh. Existing seeded sample boards keep their original
      S/A/B/C/D shape — they aren't migrated; they're just no longer reachable as a "preset"
      because S/A/B/C/D is just F-S minus the F.
    - **Pure-JVM tests cover** each template's seeded tier set and order, the blank-label no-op,
      tier-id namespacing across two F-S boards, and the VM forwarding the right
      `(label, template)` to the repository.

## 2026-06-15 (addendum 15) — shared user-tea collection across boards

42. **Teas are now user-global, not board-scoped (reopens #34)** — brainstorm point A,
    `context/brainstorming.md`. A tea (Да Хун Пао you bought once) is one row in the user's
    collection; it can sit on N boards through N **placements**. Editing the tea's notes,
    flavor, photos (later), or purchase locations on the edit screen ripples to every board
    the tea is on, so the user never re-types the same data per board. This is the model the
    brainstorm called for and supersedes the board-scoped TeaEntity from #34.
    - **Schema:** new `placements(boardId FK, teaId FK, tierId?, position, UNIQUE(boardId, teaId))`
      table; `teas` drops `boardId/tierId/position`. The UNIQUE enforces "one tea cannot sit
      on the same board twice". Placements have **no FK to `tiers`** (only the board FK
      cascades) — same trade-off as #39, so removeTier explicitly reassigns placements to the
      tray inside one transaction. `BoardWithChildren` now nests `placements: List<PlacementWithTea>`
      and the placement → tea side is 1:1 by FK + UNIQUE.
    - **Migration is destructive** (DB version 1 → 2; `fallbackToDestructiveMigration(dropAllTables = true)`):
      pre-launch the only durable user data is sample seed; a real `Migration(1, 2)` becomes
      mandatory before we ship to a real user.
    - **Resolve-or-create on add (auto-link).** `TeaBoardRepository.addTea(boardId, tea, tier?)`
      first looks for an existing user-tea by name; on a hit it reuses that user-tea (does **not**
      overwrite its fields) and only inserts a new placement, on a miss it inserts both. Match
      key is the first non-blank of `(nameRu trimmed, nameZh trimmed, pinyin trimmed)`,
      Unicode-case-insensitive on `nameRu` and `pinyin`. Done in Kotlin (not SQLite) because
      built-in `LOWER` is ASCII-only and would silently miss Russian uppercase.
    - **Two delete paths:** "Убрать с подборки" deletes one placement (tea row stays, other
      boards keep their copy); "Удалить чай совсем" deletes the user-tea (FK cascade drops every
      placement, flavor, purchase). The destructive option is always confirmation-gated. Both
      live in a per-card overflow menu on the board, plus a top-bar overflow on detail/edit.
    - **Routing is by `teaId`.** `Destination.TeaDetail(teaId)` and `Destination.EditTea(teaId)`
      drop their old `boardId` — the user-tea is shared, so detail/edit are board-agnostic.
      `Destination.AddTea(boardId)` keeps its board id (the new placement targets that board).
    - **Ripple notice** on the edit screen ("Изменения видны во всех подборках, где есть этот
      чай.") is shown only when `placementCountForTea(teaId) > 1` — a single-placement tea has
      no ripple to warn about.
    - **Drag handle is the placement, not the tea.** `BoardDragState`/`computeMovePlacements`
      operate on `placementId` so moving a tea on board A never affects the same tea on board
      B. `BoardModels.TierWithPlacements` (renamed from `TierWithTeas`) carries `Placement`
      instead of `Tea`.
    - **SampleBoardProvider also fixed.** Tier ids in seed boards are now namespaced by board
      (e.g. `"favorites-s"`, `"oolongs-s"`) — without this the global PK on `TierEntity` would
      have crashed the seed transaction the moment a second board referenced `"s"` again.
      That bug shipped silently with #34 (only one board ever seeded); the migration to v2
      surfaces and fixes it.
    - **Pure-JVM tests cover** the resolve-or-create match (Russian uppercase + whitespace),
      `removePlacement` keeping the user-tea visible on other boards, `deleteTea` cascading,
      `placementCountForTea`, `updateTea` rippling across boards, the placement-keyed
      `computeMovePlacements`, the placement-aware `Mappers`/`toSeedEntities` (one tea row +
      multiple placements), and the new VM surface (`movePlacement` / `removePlacement` /
      `deleteTea` / `placementCount`).

## 2026-06-15 (addendum 16) — photo list per tea

43. **Photos are now a list per user-tea (reopens #24).** Brainstorm point C, `context/brainstorming.md`:
    "the tea pages should include a photo list … with ability to add user photo." #24's
    single-photo `user_photo_uri` and "no arbitrary web fetch" line are superseded for the count
    side (list, not single field) and **kept** for the licensing line (web/AI search photos still
    out of MVP — a future flip needs licensing solved first). MVP scope is **user-uploaded only**;
    catalog/CC photos plug in later (M4) without another schema migration.
    - **Schema (Room v3 → destructive migration again, same rationale as #42).** New
      `tea_photos(id PK, teaId FK ON DELETE CASCADE, uri, position, source ENUM USER|CATALOG,
      license?, sourceUrl?, createdAtEpochMs)`; index on `teaId`. `TeaWithChildren` joins photos
      alongside flavor/purchase children. `Tea.photos: List<TeaPhoto>` lands the data in domain.
      `Migration(2, 3)` is **mandatory** before we ship to a real user; for now the builder
      configures `fallbackToDestructiveMigration(dropAllTables = true)` so first launch on the new
      schema drops the older data and the sample provider reseeds.
    - **Storage = app-private copy, not `content://` URIs.** Picked photos are copied through a
      `PhotoStore` interface into `<filesDir>/tea_photos/<uuid>.<ext>`; the absolute path goes in
      the row. Persisted-URI permissions are too fragile for a local-first export-import app
      (#1, #26): a gallery cleanup or app-data wipe of the source app silently breaks the link.
      Trade-off accepted: disk usage doubles for any photo that came from internal storage.
      `PhotoStore` is a Hilt `@Binds` so unit tests swap it for an in-memory fake.
    - **Resolve-or-create on add.** `addTea` now returns the user-tea id (existing-by-name-match
      or newly-created); `AddTeaViewModel` uses that to materialize add-mode draft photos after
      the placement row is committed. `addPhoto` / `removePhoto` / `reorderPhotos` operate on the
      user-tea, so a photo added on board A is visible on the same tea on board B too — same
      ripple semantics as #42 for notes/flavor/purchases.
    - **Image lib = Coil 3.5.0** (`io.coil-kt.coil3:coil-compose`). Compose-friendly, multiplatform,
      Kotlin-2.4.0-aligned, no GMS hard dependency. **No `coil-network-*` artefact in MVP** —
      the loader is local-file-only (`AsyncImage` on a `file://` path). Hilt provides a
      `@Singleton ImageLoader` with `crossfade(true)`.
    - **Permissions: none.** `ActivityResultContracts.PickVisualMedia` fronts the user-grant on
      API 33+; androidx falls back to `OPEN_DOCUMENT` on older releases — also permission-free.
      No `READ_MEDIA_IMAGES`, no `READ_EXTERNAL_STORAGE` declared in the manifest.
    - **UI surfaces.**
      - Add/edit screen: `PhotoStripField` — horizontal strip of square thumbs + an "Add" tile
        + an "x" badge that gates removal behind a confirm dialog. Long-press starts a
        hand-rolled drag (mirrors the tier-list drag pattern, #38) that swaps neighbours by
        center-x crossing, no Compose lib dep.
      - Detail screen: `PhotoGallery` — horizontal pager with dot indicator; tap any photo to
        open `PhotoZoomDialog` (full-screen pager + `transformable` pinch/pan + double-tap reset).
      - `TeaCard`: when `photos.isNotEmpty()`, the leading 18 dp `LiquorSwatch` is replaced by a
        small Coil thumbnail of the first photo so cards on the board read at a glance; the
        swatch stays the fallback when there are no photos (preserves the Настой identity, #30).
    - **Cleanup paths.** `deleteTea` enumerates the tea's photo paths *before* the row goes,
      then asks `PhotoStore.delete(...)` for each one outside the SQLite write transaction so a
      slow filesystem call cannot stall the DB lock. `removePhoto` does the same for one row.
      Best-effort: a missing file is treated as success, never blocks the row delete.
    - **Out of this PR** (carried as future work):
      - Catalog/CC photos (M4, with the backend catalog).
      - AI/web-search photos — see `research/08-ai-web-search/`. #24's "no arbitrary web fetch"
        still stands; a future flip needs the licensing question solved first.
      - Real `Migration(1, 2)` and `Migration(2, 3)` — both become mandatory before public release.
      - Export/import (#26) — the photos directory is part of the planned bundle but not built here.
    - **Pure-JVM tests cover** `computePhotoPositions` (renumber, no-op, drop unknown, append
      omissions), the repository surface (`addPhoto` / `removePhoto` / `reorderPhotos` /
      `deleteTea` cascade) over a `FakePhotoStore`, and the VM (add-mode draft buffer
      materialized on save, edit-mode immediate delegation, reorder forwarding).

## 2026-06-16 — flavor-prompt tuning RESOLVED (research 07)

44. **Flavor-prompt setup RESOLVED 2026-06-16** (research `07-flavor-prompt-tuning`,
    winner opus; see its `RATING.md`). This closes the "run 07 pending" gate on #25/#23
    and fixes the *quality* contract for the M4 enrichment LLM (it does not change any
    schema or API shape). Lock these artifacts as the starting point for the M4 prompt
    module (verify each against the live Yandex API at integration time — they are a
    synthesis, not gospel):
    - **0–5 anchor rubric** per dimension with the general rule `0 = absent; 1 = barely
      detectable; 3 = clearly present, moderate; 5 = dominant` — score the *brewed
      liquor*, force full-scale use, separate "no evidence" (`evidence=false`, low conf)
      from "evidence says absent" (`value=0`, `evidence=true`) to fight central-tendency
      bias. Opus's 11-line rubric + gemini's "Reference Tea Anchor" column (Gyokuro UMAMI
      5, Lapsang SMOKY 5, young Sheng BITTERNESS 3, …) seed the human-rater calibration
      card.
    - **Two prompt modes:** grounded (vendor `<VENDOR_TEXT>`-wrapped text → derive
      profile + original ru blurb) and zero-shot (name only, `overall_confidence` capped
      ≤ 0.6). Russian system prompts, anti-copy clause, Palladius transliteration rule
      (大红袍 → "Да Хун Пао", never "Большой красный халат").
    - **Strict `json_schema`** with a reused `$defs.dim` (`value` int 0–5, `confidence`
      0–1, `evidence` bool), `additionalProperties:false`, names (display_ru/original/
      pinyin), type enum, `short_blurb_ru` (≤240), `overall_confidence`. **Inline the
      `dim` object 11× if a model rejects `$defs`** (Yandex native endpoint).
    - **≤3 maximally-different few-shot examples** (Да Хун Пао yancha · Лунцзин green ·
      Ми Лань Сян oolong) — few-shot bias on numeric output grows monotonically with
      example count (arXiv 2511.04053), and smaller models (Lite) are more susceptible.
    - **Confidence is a multiplicative gate** (model_overall × mode[1.0 grounded / 0.7
      zero-shot] × evidence-fraction × name-resolution × schema-validity), clamped in
      Kotlin — *not* the model's self-rating. Reconciles with the programmatic confidence
      already locked in #15 (pinyin4j + transliteration litmus).
    - **Injection hardening for `sourceText`** (extends #25): 4k-char cap, HTML/markup +
      zero-width strip, `<VENDOR_TEXT>` delimiter wrap, sandwich reminder, schema-validate
      + fail-closed, n-gram overlap scan of the generated blurb vs source, never reflect
      raw model text into a second call.
    - **Params:** temperature 0 for the numeric profile (re-roll on invalid JSON, don't
      raise temp); optional separate temp ~0.3 call for prose only.
    - **Eval gate before M4 ships:** 20–30-tea gold set with extremes represented,
      per-dimension MAE ≤ 1.0, central-tendency check on 0/5 usage, injection
      attack-success = 0%, transliteration regression list.
    - **Yandex-API caveats (from opus's Discard list — these will break a naive build):**
      *(a)* `json_schema` support is **officially unconfirmed for `yandexgpt-lite`** (all
      Yandex examples use Pro `yandexgpt/rc`) → keep a `json_object` + Kotlin-validation
      fallback; *(b)* **no Yandex-managed DeepSeek URI** (self-host or DeepSeek's own
      `json_object`-only API) — note this also nuances #17/#18's "Yandex-native DeepSeek"
      assumption; verify in console (already an open item in #18); *(c)* the **native
      Yandex API** (top-level `json_schema` wrapper) and the **OpenAI-compatible endpoint**
      (`response_format:{type:"json_schema"}`) take different request shapes — pick one and
      pin it; *(d)* **Qwen3 thinking-mode does not support structured output** and leaks
      reasoning into content → call the booster in non-thinking mode.
    - **Discard (do not copy into code):** Alice's "Lite ctx = 8K" (it's 32,768) and
      "Qwen3-on-Yandex = 128K" (262,144); GPT's temp 0.2–0.5 (too high for scoring);
      DeepSeek's 0–10 scale and `enum:[…, null]` Draft-07 violation; any claim that Lite
      "fully supports json_schema".

## 2026-06-16 — web-grounded enrichment NOT adopted (research 08)

45. **§6 web-grounded enrichment fallback = NOT adopted; "no open-ended web crawling"
    STANDS** (research `08-ai-web-search`, winner opus; see its `RATING.md`).
    Run 08's *verdict* was "upgrade §6 to a web-grounded fallback", but that verdict was
    produced against a prompt premise — a **Germany (EU) VPN egress** routing enrichment
    LLM calls (it cites `#15/#16`) — that **decision #18 already retired** (VPN dropped,
    all enrichment moved to direct Yandex Cloud Foundation Models). Re-read under the
    *current* locked architecture, web-grounding has **no compliant path**:
    - **Yandex Search API is a hard blocker** — ToS clause 2.7.4 forbids reproducing /
      copying / storing / caching search results (the same store-and-serve trap that
      demoted Yandex Maps in #9). A local-first catalog must persist what it serves, so
      Yandex's own search is unusable for grounding.
    - **YandexGPT / Yandex-native Qwen3 / DeepSeek have no built-in web-search tool** —
      they are BYO-retrieval (run 04 + run 08). There is no Yandex-native grounded-generate.
    - **Tavily + plain LLM / Gemini grounding** = exactly the **non-Yandex egress that
      #18 deliberately removed**; re-adding it reverses a locked decision, adds a
      US/Nebius-owned dependency (Tavily acquired Feb 2026, RU-reachability unverified)
      and a ToS/IP-risk surface, for marginal coverage on obscure vendor teas.
    - **The existing grounding is sufficient:** Wikidata → Wikipedia (free, CC0/CC-BY-SA)
      resolves famous teas; **user-pasted `sourceText` (#25)** grounds the long tail;
      YandexGPT/Yandex-native fills the rest. So enrichment grounding stays
      **Wikidata → user-pasted text → Yandex LLM**, with **no web search** — §6's
      "Out of scope: open-ended web crawling to discover teas" line is unchanged, and
      #24's "never auto-fetch arbitrary web images" likewise stands.
    - **Durable findings recorded for a possible future revisit** (relevant ONLY if we
      ever re-introduce a non-Yandex egress — not planned):
      - **Never store/re-serve built-in `googleSearch`-grounded output.** Gemini API
        Additional ToS (eff. 2026-03-23) define "Grounded Results" as the whole response
        when grounding is on, restrict display to the prompt-originator, cap storage at
        2 years for narrow purposes, and forbid cache/syndicate/build-a-database. (Plain
        *ungrounded* Gemini output is storable — but Gemini is EEA-paid-only anyway, #17.)
      - **Tavily** is the only card-free (1,000/mo) search-only API; storage permitted at
        customer IP risk; US/Nebius-owned, RU-reachability unverified.
      - Wikidata/Wikipedia cover famous teas in en+zh but are **weaker on Russian
        transliterations** (alice) — which is exactly why user-pasted text, not web
        search, is the right long-tail grounding for a RU-first app.
    - **Net:** no schema, API, or architecture change. §6 stays as written; the only
      output of run 08 is this "considered, not adopted" record + the future-revisit
      notes above.

## 2026-06-16 — flavor entry UX: quick-rate + full 11-dim reach

46. **The add/edit form now reaches the full locked 11-dim flavor vocabulary** (decisions
    #23/#28/#44), not just the six quick-rate axes. The domain enum, labels, radar, and the
    `setFlavor`/`toTea`/`toForm` data path were already dimension-agnostic; only the form UI
    was capped at `QuickRateDimensions`, so a user could never record GRASSY / SPICY / SMOKY
    / EARTHY_NUTTY / UMAMI even though the detail radar draws them. This is a **client-only,
    no-AI, no-backend** change.
    - **Quick-rate stays the default short list** (#28): SWEETNESS, BITTERNESS, ASTRINGENCY,
      FLORAL, FRUITINESS, ROASTED. A **"Показать все вкусы"** toggle reveals the remaining
      five (`ExtendedRateDimensions` = the enum minus the quick set, in enum order).
    - **A rated extended axis stays visible when collapsed** — `visibleExtendedDimensions`
      (pure, unit-tested) shows quick + any extended dim with intensity > 0, so editing a tea
      that already carries SMOKY never hides it; the toggle only appears when there is
      something hidden (or it is already expanded). `animateContentSize` makes the reveal
      non-jarring (consistent with the polish pass, #43).
    - **Calibration aid, not a 66-string rubric.** Surface only #44's *general* anchor as a
      one-line legend ("0 — нет · 3 — заметно · 5 — доминирует"). The detailed per-dimension
      anchors from #44 are the **LLM prompt's** job (M4), not the user form's — keeping the
      quick-rate flow short. The "catalog reference vs my rating" split (#23/#28) still waits
      on the backend reference profile (M4).
    - **A11y:** each `Slider` carries a `contentDescription` (`a11y_flavor_dim` = "<axis>:
      <n> из 5") so TalkBack announces the axis on an adjustable control, not a bare percent.
    - **Strings:** `field_flavor` retitled "Вкус"; added `flavor_scale_hint`,
      `flavor_show_all`, `flavor_show_less` (ru). **Pure-JVM tests** cover the quick/extended
      partition (exact cover, no overlap) and the collapsed-hides-zero / expanded-shows-all
      visibility rule.

## 2026-06-16 — cross-board "my teas" view (M1, #27)

47. **A cross-board "Мои чаи" view ships in M1** (decisions.md #27): a read-only, searchable,
    type-filterable list of every user-tea across all boards. Reached from a **Search action in
    the Boards top bar** (a second top-level `Destination.MyTeas` on the existing dependency-free
    back stack, #33); tapping a row opens the shared `TeaDetail`, which already owns edit/delete.
    Client-only, no AI/backend.
    - **Source = new `TeaDao.observeAllTeas()`** (`SELECT * FROM teas`, ordered in Kotlin), exposed
      as `TeaBoardRepository.allTeas: Flow<List<Tea>>` — a **cold** Room flow (unlike the eager
      `boards`, it has no synchronous reader, so the query only runs while the screen is open).
      It includes **teas with zero placements** (removed from every board but not deleted, #42),
      which finally makes orphaned teas reachable + deletable — previously they were unreachable
      from any screen.
    - **Pure, unit-tested helpers** in `MyTeasModels`: `filterMyTeas` (type filter + case-
      insensitive substring over ru/en/pinyin/zh, sorted by `nameRu.lowercase()` so Russian
      uppercase orders correctly — SQLite's ASCII `LOWER`/`NOCASE` cannot) and `placementCounts`
      (per-tea board tally from the `boards` snapshot; a tea on no board is absent → count 0).
    - **`MyTeasViewModel`** combines `allTeas` + `boards` + query + type filter into one
      `MyTeasUiState` (items with a per-tea board count, the set of types actually present for the
      filter chips, and a `collectionEmpty` flag distinguishing "no teas" from "no matches").
    - **UI:** search field (leading Search icon + clear), horizontally-scrollable type filter
      chips (only for present types; tapping the active chip clears it), and a `LazyColumn` of
      full-width rows (photo/liquor-swatch + names + type chip + "на N подборках" / "Не в
      подборках"); two empty states. Each row is `mergeDescendants` for a single TalkBack node.
    - **Strings:** `my_teas_*` + the `my_tea_board_count` plural (ru). **Sharing a tier list as an
      image stays post-MVP** (#27, unchanged).

## 2026-06-16 — settings screen + DataStore + per-app language (M1, #28/#36)

48. **A Settings screen ships in M1** (decisions.md #28), reached from a **gear action in the
    Boards top bar** (next to the my-teas Search action), as a new `Destination.Settings` on the
    custom back stack (#33). It exposes appearance, language, and an About/privacy block.
    - **Theme mode = System / Light / Dark**, plus a **Dynamic color (Material You) opt-in toggle
      shown only on Android 12+** (`Build.VERSION_CODES.S`). The tea-green brand scheme stays the
      default; dynamic color, when on, recolors only the Material scheme — the `LocalTeaColors`
      liquor palette stays brand-fixed regardless (so type swatches never drift with wallpaper).
      `TeaTiersTheme` now takes `(themeMode, dynamicColor)` and resolves dark via the pure,
      unit-tested `isDarkTheme(mode, isSystemInDarkTheme())`.
    - **Persistence = Preferences DataStore (#36 wired now):** new dependency
      `androidx.datastore:datastore-preferences 1.2.1`. `SettingsRepository` stores `theme_mode`
      (enum name) + `dynamic_color` (bool) on a private IO scope, falls back to defaults on an
      `IOException` read, and degrades an unknown stored enum to `SYSTEM`. `SettingsViewModel`
      exposes `StateFlow<AppSettings>`, collected at the **activity root** (`MainActivity`) so the
      theme applies app-wide (one-frame default-theme flash on cold start is accepted for MVP).
    - **In-app language switch = AppCompat per-app locales.** Added `androidx.appcompat 1.7.1`;
      `MainActivity` now extends `AppCompatActivity` (still 100% Compose-hosted) so
      `AppCompatDelegate.setApplicationLocales` drives the locale across all API levels. The window
      themes are reparented to `Theme.AppCompat.*.NoActionBar` (required by AppCompatActivity;
      colors still come from Compose). Manifest adds `android:localeConfig=@xml/locales_config`
      (ru/en/zh) and the `AppLocalesMetadataHolderService` + `autoStoreLocales` meta-data so the
      choice persists on API < 33. Picker offers System / Русский / English / 中文; pure helper
      `appLanguageOf(tags)` maps the active locale's primary subtag back to the selection.
    - **Translations gap (deliberate):** only `values/` (Russian) exists today; **`values-en` /
      `values-zh` land in M5.** Until then the picker switches the locale but every string resolves
      to Russian — the mechanism + `locales_config` are wired now so M5 only adds resource files. A
      `settings_language_hint` caption tells the user translations are coming.
    - **About/privacy:** app name + `Версия <versionName>` (via `BuildConfig`, so
      `buildConfig = true` is now enabled) + an on-device-only privacy note that explicitly flags
      purchase geopoints as sensitive + a tea-DB credits line.
    - **Tests:** pure-JVM `SettingsModelsTest` covers `isDarkTheme` (system follows flag;
      light/dark ignore it) and `appLanguageOf` (primary-subtag match across region/script,
      comma-list first-wins, null/empty/unknown → SYSTEM, tag table).

## 2026-06-16 — file export / import (M1, #26)

49. **Export/import ships in M1** (decisions.md #26), in the Settings screen's "Данные" section.
    The bundle is a **zip**: `backup.json` (a faithful 1:1 of the DB tables — boards/tiers/teas/
    placements/flavors/purchases/photos, so placement positions + tier ids round-trip) + the actual
    **photo files** under `photos/` (so a restore on a new device keeps photos, per plan.md). No
    accounts, no GMS.
    - **Import = replace-all (destructive), behind a confirm dialog** (the open item in plan.md is
      now resolved this way; it matches the "restore a backup" mental model and sidesteps id-clash
      merge logic). `TeaDao.replaceAll` wipes boards+teas (FK cascade clears the rest) and re-seeds
      in one transaction; photos are written to disk *before* the row insert. Old photo files are
      orphaned on disk (acceptable for MVP).
    - **Export delivery = SAF "create document" (save-to) + a Share action.** Save uses
      `ActivityResultContracts.CreateDocument("application/zip")`; Share writes to `cacheDir/backups/`
      and hands a `FileProvider` (`${'$'}{applicationId}.fileprovider`, `@xml/file_paths`) content
      URI to `ACTION_SEND`. Import uses `OpenDocument(["application/zip","application/octet-stream"])`
      (octet-stream because some providers relabel a .zip).
    - **Serialization = kotlinx.serialization** (new plugin `kotlin-serialization` pinned to Kotlin
      2.4.0 + `kotlinx-serialization-json 1.11.0`). `@Serializable` DTOs are decoupled from the Room
      entities and gated by `formatVersion` (1); a bundle with a newer `formatVersion` is rejected
      as `InvalidFile`. `Json{ignoreUnknownKeys=true}` keeps old backups loadable after additive
      schema growth.
    - **Layering:** pure `BackupModels` (entities ↔ DTOs; file-backed photo → `photos/<id>.<ext>`,
      URL-backed photo keeps its `uri`; a bundled photo whose file is missing on restore is dropped)
      + pure `BackupArchive` (java.util.zip read/write) — both unit-tested on the JVM — under a thin
      Android `BackupManager` (SAF streams, `PhotoStore.importInto`, `dao.replaceAll`). `BackupViewModel`
      reports results as one-shot snackbars / a share-intent request.
    - **Tests:** `BackupModelsTest` (file-vs-URL photo handling, full table round-trip, missing-file
      drop, JSON encode→decode pipeline) + `BackupArchiveTest` (zip write/read, no-json zip → blank).
    - **Out of scope (post-MVP, unchanged):** optional auto-sync to the user's own cloud (Yandex
      Disk via SAF, still no accounts).

50. **Backend persistence foundation lands (M2 stripe, PR 1 of 3)** — the `§4a` catalog schema as
    a Flyway migration + JPA entities + a Testcontainers integration harness. Read API (`§5`) and the
    Wikidata seed follow as PRs 2 and 3.
    - **Schema (`V1__catalog_schema.sql`)** implements plan.md `§4a`: `tea` (surrogate `id`, nullable
      `wikidata_qid`, type/origin/region/cultivar/oxidation/brand, image ref triple, provenance
      `source`/`source_url`/`license`/`retrieved_at`, enrichment `verification_status`/`confidence`/
      `enriched_by`/`enriched_at`, app-maintained `dedup_key`, `created_at`/`updated_at`), `tea_name`
      (multi-row per locale `en`/`ru`/`zh-Hans`/`pinyin`, `UNIQUE(tea_id,locale,name)` + partial-unique
      primary per locale + `pg_trgm` GIN on `name`), `tea_description` (`ru`/`en`/`zh`, `short_text`/
      `full_text`, one row per locale), `tea_flavor` (11-dim + 0–5 intensity, one row per dimension).
      Extensions `pg_trgm` + `unaccent`; `tea_dedup_key` UNIQUE + partial-unique `wikidata_qid` back the
      `§6` enrich-on-miss upsert (PR 3).
    - **Logical enums are stored as `text` + `CHECK`, not native PG `ENUM`** — the flavor vocabulary is
      explicitly extensible (decision #46) and text maps cleanly to JPA `@Enumerated(STRING)` under
      `ddl-auto: validate`. `type`/`dimension` are typed enums (`TeaType`/`FlavorDimension`); free-form
      provenance fields (`source`, `verification_status`, `locale`) stay `String`, validated by the DB
      `CHECK`. `full_text`/`short_text` avoid the reserved word `full`.
    - **Flyway owns the schema; Hibernate only validates** (`ddl-auto: validate`, `open-in-view: false`).
      `Instant` is mapped to `TIMESTAMP WITH TIME ZONE` (`hibernate.type.preferred_instant_jdbc_type`) so
      validate matches the `timestamptz` columns; smallint↔`Short`, real↔`Float`.
    - **DB credentials are env-sourced** (`POSTGRES_URL/USER/PASSWORD`) with local-dev defaults only
      (rule 50-secure); production overrides via env.
    - **Spring Boot 4 gotchas resolved (verified against upstream 2026-06-16):** Boot 4 modularized
      auto-configuration — bare `org.flywaydb:flyway-core` no longer triggers migrations, so the build
      uses the new **`spring-boot-starter-flyway`** (+ `flyway-database-postgresql` runtime). Testcontainers
      **2.0.5** (managed by the Boot 4 BOM) renamed module artifacts with a `testcontainers-` prefix
      (`testcontainers-postgresql`, `testcontainers-junit-jupiter`), relocated `PostgreSQLContainer` to
      `org.testcontainers.postgresql`, and dropped the self-generic type param (use the raw type).
    - **Test harness:** `AbstractIntegrationTest` runs one **singleton** `postgres:16-alpine` container
      for the whole JVM (not `@Testcontainers`, which stops/restarts the static container per class and
      breaks Spring's cached context port); `@ServiceConnection` wires the datasource and Flyway migrates
      on context start. `contextLoads` + a `TeaRepository` round-trip (cascade names/descriptions/flavors,
      `findByWikidataQid`/`findByDedupKey`) prove the schema end-to-end. Ryuk is disabled locally for
      rootless podman; a JVM shutdown hook stops the singleton.

51. **Read-only catalog API lands (M2 stripe, PR 2 of 3)** — the `§5` endpoints over the PR-1 schema.
    Seed (PR 3) and the deploy/Docker/Terraform tail still follow.
    - **Endpoints** under `/api/v1/teas`: `GET /search?q=&locale=&type=&origin=&cursor=&limit=` (cursor-
      paged), `GET /{id}` (full detail), `GET /facets` (distinct types/origins for filter chips). Write/
      enrich (`POST /resolve`) stays in M4.
    - **DTOs are decoupled from entities** (`TeaSummaryDto`/`TeaDetailDto`/`TeaNameDto`/`TeaDescriptionDto`/
      `TeaFlavorDto`/`TeaImageDto`/`TeaProvenanceDto`/`PageDto`/`FacetsDto`). Detail exposes provenance +
      `verificationStatus` so clients can show the "unverified" hint (#23). Names are returned primary-first.
    - **Search semantics:** a tea matches when **any** of its names contains `q` (case-insensitive
      substring), optionally narrowed by name `locale`, `type`, `origin`. **Keyset pagination by id**:
      `cursor` = the last id seen; `nextCursor` is non-null only when a full page was returned. `limit`
      defaults to 20, capped at 50. Two-step load (matching ids, then teas with names fetch-joined) avoids
      the in-memory-pagination warning and N+1.
    - **Dynamic search uses the JPA Criteria API** (a `TeaSearchRepository` fragment), not a `:p is null or
      ...` JPQL query: on PostgreSQL an untyped null bind is inferred as `bytea`, so `lower('%'||NULL||'%')`
      fails with `function lower(bytea) does not exist`. Building predicates dynamically omits null filters
      entirely. User `q` is escaped for LIKE wildcards (`%`/`_`) and matched as a literal.
    - **Errors = RFC-7807 problem+json** (`spring.mvc.problemdetails.enabled` + a `@RestControllerAdvice`).
      Built-in MVC exceptions (bad enum/number params, unknown routes) render as problem+json out of the
      box; the advice only adds the domain 404 (`TeaNotFoundException` -> "Tea not found").
    - **Spring Boot 4 gotcha:** `@WebMvcTest` moved to `org.springframework.boot.webmvc.test.autoconfigure`
      and now needs the explicit **`spring-boot-starter-webmvc-test`** test dependency (it was dropped from
      `spring-boot-starter-test` by the Boot 4 modularization).
    - **Tests:** a `@WebMvcTest` slice (service mocked via a MockK `@TestConfiguration` bean) asserts the
      JSON contract + 404/400 problem+json; a Testcontainers `TeaCatalogServiceIT` proves the real Criteria
      SQL (substring + locale/type filters, cursor paging, detail, facets) against PostgreSQL.

52. **Curated catalog seed lands (M2 stripe, PR 3 of 3)** — a static JSON snapshot + an idempotent
    seeder, closing the three-PR backend stripe. The live Wikidata SPARQL re-sync, OFF taxonomy, and
    the Docker/Terraform deploy tail still follow later in M2.
    - **Seed source is a curated static snapshot, not a live Wikidata query** (decision #10 favored a
      curated seed first). `resources/seed/catalog-seed.json` holds 13 own-authored Chinese teas across
      green/oolong/white/yellow/black/dark/puer, each with `en`/`ru`/`zh-Hans`/`pinyin` names (primary-
      flagged), `en`/`ru` blurbs, and a curated 11-dim flavor profile. Rows are `source: "curated"`,
      `verification_status: "verified"`, no `wikidata_qid` — we don't ship unverified QIDs, and CI never
      touches the network. Re-syncing real Wikidata QIDs/images is deferred (still M2).
    - **`DedupKeys.of(primaryName, pinyin, type)`** is the single source of truth for `tea.dedup_key`:
      NFD-unaccent + lowercase + whitespace-collapse on the primary name, a `[^a-z0-9]`-stripped pinyin
      slug, and the `TeaType` name, joined `name|slug|TYPE`. The same helper backs the `§6` enrich-on-miss
      upsert (M4), so the seed and future on-demand resolves agree on identity. Unit-tested (`DedupKeysTest`).
    - **`CatalogSeeder` is idempotent** — it computes each tea's dedup key and skips any row already present
      (`findByDedupKey`), so re-running on every boot inserts nothing. `@Transactional`; returns the insert
      count for logging/tests. It uses a **dedicated Jackson 2 `ObjectMapper`** (`jacksonObjectMapper()`),
      not the injected bean: Spring Boot 4's auto-configured mapper is **Jackson 3 (`tools.jackson`)**, so
      `com.fasterxml.jackson.databind.ObjectMapper` has no bean — and the trusted build-artifact seed file
      doesn't need the web layer's mapper config anyway.
    - **Startup wiring:** `CatalogSeedRunner` (an `ApplicationRunner`) calls the seeder once on boot, gated
      by `teatiers.seed.enabled` (`@ConditionalOnProperty`, `matchIfMissing = true` -> on by default).
      `AbstractIntegrationTest` sets `teatiers.seed.enabled=false` so ITs keep an empty catalog and control
      seeding explicitly.
    - **Tests:** `CatalogSeederIT` (Testcontainers) asserts the first run inserts the curated teas, a second
      run inserts nothing (idempotent), and a seeded tea round-trips its localized names + flavors +
      descriptions; `DedupKeysTest` covers normalization (diacritics, whitespace, pinyin slug).

53. **Stack docs aligned to Spring Boot 4 (ratifies #29).** The `00-core` / `30-backend` rules and
    plan `§2` still read "Spring Boot 3.x" even though the build has been on **4.1.0** since Phase 0
    (decision #29). Updated all three to "Spring Boot 4.x" and regenerated `AGENTS.md` via
    `scripts/sync-agent-rules.sh`, so the canonical stack line stops misleading future work. The
    `research/*/prompt.md` files keep "3.x" verbatim — they are historical research artifacts
    (rule `70-research`: never edit a sent prompt). Concrete Boot-4 consequences surfaced while
    building the catalog service, consolidated here: (a) Boot 4 modularized auto-config and test
    slices, so `spring-boot-starter-flyway` (migrations no longer trigger from bare `flyway-core`)
    and `spring-boot-starter-webmvc-test` (`@WebMvcTest`) are explicit deps (#50/#51); (b) the
    auto-configured `ObjectMapper` bean is now **Jackson 3** (`tools.jackson`), so code needing the
    classic `com.fasterxml.jackson` mapper builds its own (the seeder, #52).

54. **Backend deploy stack = docker-compose (server + self-hosted Postgres) (M2).** The Phase-0
    `docker-compose.yml` ran the server alone; now that the schema/seed have landed it carries a
    `db` (`postgres:16-alpine`, matching the Testcontainers image) with a named volume and a
    `pg_isready` healthcheck, and the server waits on `depends_on: condition: service_healthy`. The
    DB port is **not** published (only the server, on the compose network, reaches it); the server
    reads `POSTGRES_URL/USER/PASSWORD` from the environment (compose defaults are dev-only, rule
    50-secure — real values come from env/Lockbox on the VM). Verified end-to-end locally: image
    builds, Flyway migrates `v1`, the seeder inserts 13 teas and is idempotent across a restart, and
    `/teas/search` + `/facets` return the seeded data. Still open in M2: image delivery to the VM
    (build-on-VM vs Yandex Container Registry), TLS/reverse-proxy for `tea.macsia.fun` (80/443 are
    the only open ingress; 8080 is not), and the `infra/` Terraform + `terraform import` of the
    hand-created VM/SG/IP.

55. **IaC tool = OpenTofu, not HashiCorp Terraform (refines #18/run 05).** HashiCorp's Terraform CLI
    binary is region-blocked from RU (`releases.hashicorp.com` → "content not available in your
    region"; brew's formula 404s), and the project is VPN-free (#18). **OpenTofu (`tofu`)** is the
    drop-in, RU-reachable, Yandex-supported CLI — identical `.tf`, identical provider. Provider pin
    **verified: `yandex-cloud/yandex` 0.206.0 is latest** (registry, 2026-05-28). Two install gotchas,
    both handled in `infra/`: (a) the provider **source must be fully-qualified**
    `registry.terraform.io/yandex-cloud/yandex` — OpenTofu's default registry (`registry.opentofu.org`)
    lags far behind (stuck pre-0.206); (b) provider installation goes through Yandex's mirror
    `terraform-mirror.yandexcloud.net` via a committed `infra/tofurc` (`TF_CLI_CONFIG_FILE`), and the
    lock file is generated multi-platform (`darwin_arm64` + `linux_amd64`) with `tofu providers lock
    -net-mirror=...`. Chose native S3 state locking (`use_lockfile`) over a YDB lock table (single
    operator). The rules/plan keep saying "Terraform" generically; treat it as "Terraform-compatible IaC".

56. **M2 deploy IaC foundation lands (validate-only; applies pending).** `infra/` is a complete
    OpenTofu config: a `bootstrap/` module (local state) that creates the remote-state SA + static
    access key + versioned Object Storage bucket; and a root module that **adopts the hand-created
    VM/SG/static-IP via config-driven `import` blocks** and adds the new pieces — a **Container
    Registry** + least-privilege **`teatiers-puller` SA** (registry images.puller, attached to the VM)
    + a **Lockbox** secret holding a generated Postgres password. The VM's `cloud-init` (rendered via
    `templatefile`) installs Docker, logs in to CR with the metadata IAM token, and brings up the
    on-VM stack: **Caddy (auto Let's Encrypt for `tea.macsia.fun`) → server (pulled from CR) →
    self-hosted Postgres** (`infra/deploy/`), with only Caddy's 80/443 published and a `mem_limit` so
    the JVM + Postgres + Caddy fit the 4 GB box. `prevent_destroy` guards the VM + IP; the README
    runbook insists `tofu plan` show **zero destroy/replace** before any apply. Both modules pass
    `tofu validate`; **nothing is applied yet** — the credentialed bootstrap/import/apply/push/deploy
    steps follow. Image delivery = YCR, TLS = Caddy, IaC = Terraform-first per the user's choices.

57. **M2 backend is LIVE on Yandex Cloud (2026-06-16).** Ran the full credentialed phase. Bootstrap
    applied (state SA + key + versioned `teatiers-tfstate` bucket); the root module **imported the
    VM/SG/static-IP and added CR + puller SA + Lockbox** with a `0-destroy` plan. Two fixes were made
    during apply, now in the repo: the adopted VM's **`ssh-keys` metadata is set explicitly and
    `user-data` left unset** — because `user-data` embeds the generated password, including it made
    the whole metadata map "known after apply", so the plan could not prove SSH access survived;
    dropping it keeps metadata fully-known (the only VM change is attaching the SA, a brief
    stop/start) and a new `vm_cloud_init` output exposes the provisioning script. **Image delivery
    workaround:** the local engine is podman (no `buildx`; cross-arch builds fail on overlay mounts),
    so the arch-independent bootJar is built natively, copied to the amd64 VM, wrapped in a 4-line
    runtime image there, and pushed to CR — the canonical multi-stage `server/Dockerfile` stays for
    buildx/CI environments. Deploy = `infra/deploy/` compose on the VM: **Caddy (Let's Encrypt) →
    server (from CR) → Postgres**, DB password from Lockbox in a `0600 .env`, `restart:
    unless-stopped`. Verified externally: `https://tea.macsia.fun/actuator/health` = UP, `/teas/search`
    serves the 13 seeded teas, valid Let's Encrypt cert. **M2 is done** (read-only catalog API
    deployed); remaining catalog-data tasks (live Wikidata re-sync, OFF taxonomy) are optional/M-later.

## 2026-06-16 — post-deploy infra hardening (CI, digests, backups)

58. **Infra hardening after the live deploy (rule 40-devops).** Five follow-ups to the #57 deploy,
    no behavior change to the running app:
    - **CI runners off Node 20.** GitHub forces Node-20 JS actions to Node 24 starting 2026-06-16, so
      `ci.yml` bumps `actions/checkout@v4 -> v6`, `actions/setup-java@v4 -> v5`,
      `android-actions/setup-android@v3 -> v4` (latest majors verified on the registry; rule 40 still
      satisfied — pinned to a major tag).
    - **Base images pinned by digest** (the #50/#57 `server/Dockerfile` TODO): both `eclipse-temurin`
      stages + `caddy:2-alpine` + `postgres:16-alpine` (in `infra/deploy/docker-compose.prod.yml`) now
      pin the **multi-arch index digest** (`tag@sha256:…`, tag kept for readability; refresh via
      `docker manifest inspect --verbose`). The repo-root *dev* compose stays on tags (dev convenience,
      matches the Testcontainers tag).
    - **Image publish in CI** (`publish-image.yml`): GH runners are native amd64, so building there
      sidesteps the local podman cross-arch problem (#57). Pushes `:latest` + `:<sha>` to CR on
      push-to-main touching `server/**` (or manual). **Guarded** — no-ops unless secret `YC_SA_KEY`
      (a *pusher* SA, separate from the VM's `teatiers-puller`, least privilege) and variable
      `YC_REGISTRY_ID` exist, so main never goes red before the user wires them.
    - **OpenTofu in CI** (`infra.yml`): `plan` on infra PRs, **`apply` only via manual dispatch** —
      apply-on-main is intentionally not automatic (single operator + `prevent_destroy` on VM/IP make
      an unattended apply risky). Guarded on `AWS_*` (state backend) + `YC_SA_KEY`.
    - **Postgres backup** (`infra/deploy/backup.sh` + `teatiers-backup.{service,timer}`): daily
      container `pg_dump` -> gzip under `/opt/teatiers/backups`, 14-day retention. **Local-on-disk by
      default** (the catalog is reproducible from Flyway + the committed seed); off-box copy to Object
      Storage is opt-in via `BACKUP_S3_URI`. Installed on the VM is a credentialed step (README).
    - All three workflows are **inert until their secrets exist** (documented in `infra/README.md`);
      the user must add: pusher `YC_SA_KEY` + `YC_REGISTRY_ID` var (publish), `AWS_ACCESS_KEY_ID`/
      `AWS_SECRET_ACCESS_KEY` + folder-editor `YC_SA_KEY` (infra). Untested in this environment.

## 2026-06-16 — M3 start: catalog client + add-tea search

59. **M3 first slice = networking foundation + add-tea catalog search** (app-side; plan §M3). Scope
    deliberately stops at search + prefill — tea-card visuals, the detail screen, the search-miss CTA,
    and the attributions screen are later M3 slices.
    - **Client stack:** Retrofit 3.0.0 + the first-party `converter-kotlinx-serialization`, OkHttp via
      the **5.4.0 BOM** (5.x is source-compatible with the 4.12 Retrofit 3 declares; latest stable
      verified on Maven Central — the bare `latestVersion` query returns an `alpha`, so it was read
      from `maven-metadata.xml`). One Retrofit instance in `NetworkModule`; logging interceptor is
      `BASIC` and **gated to debug** (rule 50: no verbose logging shipped, the query is user input).
      The catalog API is read-only + holds no user data, so **no auth** here.
    - **Base URL** = `BuildConfig.CATALOG_BASE_URL`, default `https://tea.macsia.fun/api/v1/`,
      overridable per build via a Gradle property (so a local server or staging needs no code change).
    - **Contract verified live** before writing DTOs (rule 00: never guess a schema). `GET
      /teas/search` returns `type` as the **uppercase enum name** (`"GREEN"`; the server DTO types it
      as the `TeaType` enum, Jackson serializes `name()`), the boolean name flag serializes as
      **`primary`** (Jackson strips the `is` from `isPrimary`), locales are `en`/`ru`/`zh-Hans`/
      `pinyin`. DTOs keep `type` as a **raw String** and fold unknown values to `TeaType.OTHER` in the
      mapper, so a new server category never breaks deserialization on an old client.
    - **Repository** is **network-first with an offline fallback**: on success it caches the page; on
      `IOException` it returns cached rows (`fromCache=true`) or `Offline`, on `HttpException` cached
      rows or `Error`. The `catalog_cache` Room table (**DB v4**, still `fallbackToDestructiveMigration`
      pre-launch) denormalizes every name into a lowercased `searchText` so the offline `LIKE` matches
      any locale (SQLite `LIKE` only case-folds ASCII) and keeps the structured names as JSON.
    - **UX:** Add-Tea (add mode only — edit is local-only) gets a debounced search box (**≥2 chars,
      300 ms**, `flatMapLatest` cancels stale requests). Picking a result prefills names/type/origin as
      **editable suggestions, never authoritative** (#21) — the user owns their board; flavors/blurb
      wait for the detail-endpoint slice.
    - **Tests:** MockWebServer covers the repo (hit caches, blank-query short-circuit, 5xx→Error vs
      cache, network-down→Offline); ViewModel tests cover debounce/min-length/empty/offline + prefill
      (`Loading` is asserted tolerantly — a `StateFlow` conflates it away when the mocked search
      resolves synchronously). `lint` + 126 unit tests green (PR #24).

60. **Dependency refresh to latest stable (2026-06-16, PR #25, stacked on #24).** Re-verified every
    pin against Maven Central / Google Maven / Gradle. Most were already latest; profitable bumps only:
    app `lifecycle` 2.9.4→**2.10.0**; `mockk`→**1.14.11** on both modules (app was 1.14.9, server a
    stale 1.13.13, now unified); app JUnit `5.14.4`→**6.1.0** (major). JUnit 6 was safe to take because
    the app runs unit tests via **AGP-native `useJUnitPlatform()`** (no mannodermaus plugin to gate
    Platform 6); verified by running 126 app tests + the server suite green. Left at latest-stable:
    Kotlin 2.4.0, AGP 9.2.1, Gradle 9.5.1, KSP 2.3.9, Hilt 2.59.2, Compose BOM 2026.05.01, Room 2.8.4,
    Retrofit 3.0.0, OkHttp 5.4.0, Coil 3.5.0, Spring Boot 4.1.0. (Local-only: server Testcontainers ITs
    need `TESTCONTAINERS_RYUK_DISABLED=true` because the Ryuk reaper image won't start in the sandbox;
    CI Docker is unaffected.)

61. **M3 next slice = catalog detail as a bottom sheet** (app-side; plan §M3, PR #26 stacked on #25).
    The detail view is surfaced as a **`ModalBottomSheet` over the add form**, not a back-stack
    destination. Rationale: the app's tiny custom back stack (`TeaTiersApp`) fully unmounts the top
    screen, and `AddTeaScreen` re-binds (resets the form + search) on every (re)entry via
    `LaunchedEffect(boardId, teaId)`. A separate destination would therefore wipe the in-progress add
    and the live search on return. A sheet keeps `AddTeaScreen` mounted, so "Использовать этот чай" can
    prefill through the **same `AddTeaViewModel.pickCatalogTea` path** and the search survives. (A reusable
    full-screen detail destination for a future tea-card→detail browse path is deferred.)
    - **Entry point:** each search result row now has a distinct **info action** (its own a11y node)
      that opens the sheet; tapping the row body still prefills as before.
    - **Data:** `repository.detail(id)` is **network-only** (no cache, unlike search) — detail is reached
      only on an explicit tap, so a failure offers **retry** rather than a stale offline copy. Result is
      `Loaded`/`Offline`/`Error`; the VM models the sheet as `Hidden`/`Loading`/`Loaded`/`Offline`/`Error`
      and uses a **retry bump-counter** (a plain id re-set would be conflated by the `StateFlow`). Mapper
      **drops unknown flavor axes** and **clamps** intensities to the shared 0..5 scale; descriptions are
      picked by device language then ru→en→first.
    - **Images:** added Coil 3 `coil-network-okhttp` and routed Coil's process-wide singleton through the
      Hilt `ImageLoader` (via `SingletonImageLoader.Factory` + an `EntryPoint`), so catalog reference
      images reuse the **Retrofit OkHttp client** (one connection pool) and crossfade; this also activates
      the previously-dead Hilt loader for the existing user-photo paths. Image + description carry their
      **source/license attribution** (rule/plan: CC content must be credited).
    - **Tests:** MockWebServer repo tests (detail parse + flavor drop/clamp, 5xx→Error, network→Offline);
      ViewModel tests (open→Loaded, use→prefill+clear, close→Hidden, retry→re-fetch, tolerant of `Loading`
      conflation). `lint` + **133** unit tests green; debug APK builds.

62. **M3 next slice = search-miss "add it manually" CTA** (app-side; plan §M3, PR stacked on #26).
    When the catalog search reaches a terminal non-result state (`Empty`/`Offline`/`Error`), the result
    area now renders an **"add «query» manually"** button instead of a passive hint. Tapping it calls
    `AddTeaViewModel.addManuallyFromQuery()`, which copies the trimmed query into `nameRu`, clears the
    search box, and arms the existing **name-focus pump** (`pendingNameFocus` → `consumeNameRequiredFocus`)
    so the user lands in the form with the text already carried over — no retyping, no dead end.
    - **Scope:** this is only the **"add it"** half. The plan's **"paste a description"** half is M4
      enrichment (`POST /resolve` with `sourceText`, decision #21/#25) and depends on a backend that is
      still read-only; deliberately deferred, not built here.
    - **Already covered, marked done:** the M3 **tea-card** item (photo/placeholder + name/pinyin + type
      chip + flavor strip) is M1's `TeaCard`, and **name display = ru + pinyin + hanzi** holds across the
      search rows, the detail sheet, and the card — so no new work. **Only the attributions/licenses
      screen remains open in M3.**
    - **Tests:** ViewModel test (trims the query into `nameRu`, clears the box, arms focus). `lint` + unit
      tests green; debug APK builds.

63. **M3 final slice = attributions/licenses screen** (app-side; plan §M3, PR off `main`). A new
    `Destination.Attributions` (added to the tiny custom back stack), reached from **Settings → About**
    via a "Источники данных и лицензии" row, credits the open datasets the catalog is built from:
    **Wikidata** (CC0 1.0), **Wikipedia** (CC BY-SA 4.0), **Wikimedia Commons** (per-file CC BY-SA / CC BY),
    **Open Food Facts** (ODbL 1.0). Each card shows a localized one-line summary plus two links — the
    source site and the license deed — opened via `ACTION_VIEW` (failure swallowed so a missing browser
    can't crash the screen).
    - **Static, not fetched:** the list is an in-file `val` of a small `DataSourceCredit` (literal name +
      license id + URLs, `@StringRes` summary). Source names and license identifiers stay **literal**
      (proper nouns / standard ids); only the human summaries localize. No VM/state/I-O, so it is a
      **stateless composable**, not a VM-backed screen.
    - **Scope:** dataset-level credit only (decision: `attr_scope=data_only`). **Per-record and per-image**
      attribution already lives on the **detail card** (`CatalogProvenance`, #61); third-party OSS-library
      licenses were explicitly **out of scope** for this slice.
    - **Tests:** no new unit logic (static screen); `lint` + the existing app unit tests green, debug APK
      builds. **This closes M3** — the catalog is wired end-to-end (search → detail → manual-add → credits).

64. **M4 slice 1 = `/resolve` Wikidata-first backbone** (backend; plan §6 steps 1–2, PR off `main`).
    `POST /api/v1/teas/resolve` (body `{ name, locale?, sourceText? }`) resolves a typed tea name to a
    catalog row. The **LLM tier (§6 steps 3–4) is deliberately deferred** to a later slice; this ships the
    reliable, fully-testable spine. *Numbered #64 (not #63) because the M3 attributions slice holds #63 on
    the still-open PR #29; this branch was cut from `main` before it.*
    - **Flow.** (1) **Cache/dedup hit** — trim the input and look it up via a native
      `lower(unaccent(name)) = lower(unaccent(:q))` join over `tea_name` (the V1 `unaccent` ext folds Latin
      diacritics / pinyin tone marks; no-op for Cyrillic/CJK), returning the lowest-id match → `MATCHED`.
      This *is* the cache: the second user to type a known tea never re-enriches. (2) On a miss, **Wikidata
      SPARQL** (`query.wikidata.org/sparql`) — one query constrained to the **tea `Q6097` `P31/P279*`
      subtree** (so a plain label search can't return "Longjing **District**"), returning the QID, the
      reachable tea **category → `TeaType`**, the **en/ru/zh-Hans** labels, the ISO origin (`P495→P297`), and
      the English gloss. A hit **upserts** a new row → `ENRICHED`; a miss → `UNRESOLVED` (creates nothing —
      this is the "fails closed to Wikidata-only" budget behavior with the LLM tier off).
    - **Verified-before-coding (rule 00-core: never guess an API).** The SPARQL shape and the **11 tea
      category QIDs** were confirmed against the **live** endpoint 2026-06-16: green `Q484083`, black
      `Q203415`, oolong `Q231587`, white `Q238306`, yellow `Q824529`, pu'er `Q690098`, fermented `Q2542583`
      (→DARK), masala chai `Q877661` + flavored `Q3526264`/`Q137611595` (→BLENDED), "tea without tea leaves"
      `Q11617958` (→HERBAL); root tea = `Q6097`. Category→type uses a **most-specific-first priority** so a
      pu'er (subclass of fermented tea) resolves to PUER, not DARK.
    - **Provenance / quality.** Imported rows are `source='wikidata'`, `license='CC0-1.0'`,
      `sourceUrl=…/wiki/<qid>`, `confidence=0.9`, `enriched_by='wikidata'`, but **`verification_status =
      'unverified'`** — the schema reserves `verified` for a human curation pass, so auto-imports never claim
      it. One name per locale (each its locale's primary); the English gloss lands as a `tea_description`
      short text.
    - **Idempotency / races.** The upsert runs in its **own transaction** and is guarded on
      `findByWikidataQid` then `findByDedupKey`; a lost insert race surfaces as a unique violation on
      `wikidata_qid`/`dedup_key`, which the resolve service **recovers from by re-reading** → `MATCHED`. So
      concurrent first-time resolves of the same tea never duplicate.
    - **API hardening (rules 30/50).** Bean Validation: `name` `@NotBlank` ≤200, `locale` ≤16, `sourceText`
      ≤4000. **`sourceText` is part of the contract but reserved for the LLM tier** (accepted, validated,
      ignored now) so the app's later "paste a description" slice needs no API change. **Per-client
      fixed-window rate-limit** (`teatiers.resolve.rate-per-minute`, default 20) keyed on the forwarded
      client IP → `429` problem+json; validation → `400` problem+json (existing `@ControllerAdvice`).
      Wikidata client has a descriptive **User-Agent** (Wikimedia UA policy), connect/read timeouts, one
      retry+backoff, and degrades to `UNRESOLVED` on outage (never 500s the request).
    - **Tests.** Client parse/grouping/type-priority/query-shape (no network); upsert guard + entity-build;
      resolve orchestration incl. the race-recovery path; rate-limiter window rollover; a **Testcontainers
      IT** exercising the real `unaccent` lookup (case+accent folding) + enrich + idempotency over the live
      schema. Full server suite green (local Testcontainers needs `TESTCONTAINERS_RYUK_DISABLED=true` under
      rootless podman — environmental, CI on Docker is unaffected).
    - **Yandex Foundation Models access provisioned (unblocks the LLM tier).** Created SA **`teatiers-llm`**
      (`ajen2fbad5836jeoahvu`) with the single role **`ai.languageModels.user`** on folder
      `b1g9o3v21bogpvduaj1l`; minted an API key (`aje80d2532nevh5g5jfb`) and stored **only in Lockbox** secret
      **`teatiers-llm-api-key`** (`e6qhj4rksk0mod5ea32j`, entries `api-key` + `api-key-id`) — never in VCS
      (rule 50-secure). The secret value was never printed (created → piped to Lockbox via stdin); an orphan
      key from a first failed attempt was deleted. **Not yet done:** confirm Qwen3-235B/DeepSeek Flash gallery
      availability + price, and wire the server to read the key from Lockbox — both land with the LLM tier.

65. **M4 LLM model bake-off → Alice Flash (primary) + Qwen3-235B (zh booster)** (research run 08,
    `research/08-model-bakeoff/`; stacked on the #64 branch, PR off `main`). Empirically picked the
    production model(s) for the §6-step-3 flavor-enrichment tier instead of guessing. *Numbered #65 after #64
    (still on the open PR #30).*
    - **Live model map (verified 2026-06-16, our SA/folder; corrects run-07 assumptions).** Foundation Models
      reachable two ways: **native** `foundationModels/v1/completion` (structured = top-level
      `jsonSchema:{schema}`) and **OpenAI-compat** `/v1/chat/completions` (structured =
      `response_format:{type:"json_schema",strict:true}`). `json_schema` is **honored on Lite, Pro/rc, Alice
      LLM, Alice Flash, and Qwen3-235B** (resolves run-07's "unconfirmed on Lite"). Open models (Qwen3
      `qwen3-235b-a22b-fp8/latest`, gpt-oss, Gemma, DeepSeek `deepseek-v4-flash`, Alice Flash
      `aliceai-llm-flash`) are **OpenAI-compat only** (native → 404). **DeepSeek IS now Yandex-hosted** (gallery
      grew since run-07, which said it wasn't). Prices (₽/1k in–out incl. VAT): Lite 0.2/0.2, Alice Flash
      0.1/0.2, Qwen3 0.5/0.5, DeepSeek V4 Flash 0.3/0.5, Alice LLM 0.5/1.2, Pro 5.1 0.8/0.8.
    - **Bake-off** (zero-shot over a 24-tea gold set spanning all types + dimension extremes, temp 0, run-07
      prompt + rubric + 3 few-shot + inlined-`dim` schema). **All 6 cleared MAE ≤ 1.0 and returned 24/24 valid
      JSON.** Overall MAE: DeepSeek 0.33 < Alice LLM 0.41 < Alice Flash 0.47 < Qwen3 0.49 ≈ Pro 0.50 << **Lite
      0.80** (weakest, worst type accuracy 17/24).
    - **Picks.** **Primary = Alice Flash** (`aliceai-llm-flash`): cheapest (≈½ of Lite), fastest (2.4s), MAE
      0.47, json_schema — beats Lite on every quality axis at lower cost, so **Lite is dropped as primary**.
      **zh booster = Qwen3-235B** (research-backed, 262k ctx; **DeepSeek V4 Flash** the strong alt — it topped
      MAE/type/injection but tokenizes Cyrillic ~2× and is third-party). Both picks are OpenAI-compat → **one
      `response_format:json_schema` code path** (run-07's recommendation).
    - **Injection finding (critical).** Prompt-only defense **fails**: on the override attack **4/6 models
      (Lite, Pro, Alice LLM, Alice Flash) wrote the literal "HACKED"** into the blurb, and 5/6 copied vendor
      prose; only **DeepSeek (0/4) and Qwen3 (1/4)** were robust. Schema validation can't catch a malicious
      string in a valid field → the run-07 **code-side guards are mandatory** (reject all-same-score profiles,
      n-gram overlap scan blurb-vs-source, output scan, fail closed) regardless of model.
    - **Still open:** a **zh-source** gold set + a **grounded-extraction** gold set before the booster pick is
      final; promote run-07 prompts to `context/flavor-system/prompts.md`; then wire the server LLM tier
      (config-selectable modelUri, key from Lockbox). The `gold.json` + `run_bakeoff.py` harness is the
      regression gate (re-run on prompt/model-version change; block if MAE > 1.0).

66. **M4 slice 2 — async LLM flavor-enrichment tier wired (`/resolve` step 3)** (backend, branch
    `feat/llm-enrich-tier` off `main`). Built the §6-step-3 paid enrichment tier behind the Wikidata
    backbone, using the #65 picks. **User-selected shape (AskQuestion):** `async_now` + `env_deploy` +
    `miss_only`.
    - **Async, miss-only.** On a **Wikidata miss** (LLM tier enabled), `/resolve` inserts a minimal
      `PENDING` stub row (`source='ai'`, type `OTHER` placeholder, the user's typed name as the primary
      name) and returns **`ENRICHING`** immediately; a background `@Async` worker (`enrichmentExecutor`,
      bounded pool, `AbortPolicy`) calls the model, validates, and flips the row to **`DONE`/`FAILED`**.
      The client polls `GET /teas/{id}` on `enrichmentState`. LLM enrichment does **not** run on a
      Wikidata hit (decision: reference profile only fills genuine gaps for now). With the tier disabled
      (blank key/folder), a miss stays `UNRESOLVED` — the backbone is unchanged.
    - **Schema (Flyway V2).** `tea.enrichment_state` (`PENDING|DONE|FAILED`, NULL = not LLM-managed) +
      `enrichment_error` (short reason, never user text) + a partial index on `PENDING` for restart sweeps.
      `TeaDetailDto` exposes `enrichmentState`; `ResolveStatus` gains `ENRICHING`.
    - **Key delivery (`env_deploy`).** `teatiers.llm.api-key` / `folder-id` are injected as **env vars at
      deploy** (`TEATIERS_LLM_API_KEY` / `YC_FOLDER_ID`, sourced from Lockbox `teatiers-llm-api-key`) —
      never in VCS (rule 50-secure). Blank key **or** `enabled=false` ⇒ tier off (`FoundationModelsClient.
      isEnabled`).
    - **Client + validation.** One `FoundationModelsClient` over the OpenAI-compat endpoint
      (`response_format:json_schema`, timeouts + one retry/backoff). Code-side guards are authoritative
      (per #65): reject non-JSON / incomplete dimensions / fail closed; clamp dimension values 0–5 and
      confidence; zero-shot cap×0.7 + central-tendency ×0.5 gate; **drop the blurb on ≥ 0.5 shingle
      overlap** with the vendor text (copyright + injection). Chinese-source requests route to the Qwen3
      booster, all else to Alice Flash.
    - **Retry path.** Re-`/resolve`-ing a name whose stub is `FAILED` re-arms it to `PENDING` and
      re-dispatches (carrying any newly pasted `sourceText`). Stub creation is idempotent on `dedup_key`
      with concurrent-insert-race recovery.
    - **Tests.** Unit: resolve orchestration (miss→ENRICHING, PENDING cache hit, FAILED retry, races),
      `EnrichmentText` guards, `LlmEnrichmentService.profile` (clamp/route/copy-drop/parse failures).
      Testcontainers IT: stub create→DONE (flavors + ru blurb + aliases + type + confidence) and
      FAILED/reset transitions over the real V2 schema. Full server suite green
      (`TESTCONTAINERS_RYUK_DISABLED=true` locally).
    - **Still open (unchanged from #65):** zh-source + grounded gold sets before the booster pick is final;
      app-side optimistic add / `enrichmentState` UI / reference-vs-mine flavor / photos.

67. **New requirement (task.md) — typo-tolerant catalog search → research run 09 first** (no code yet).
    `task.md` grew a line: *"for search of teas in db we should utilize ready search index that allows several
    wrong symbols."* This is a **real gap**: today's catalog search
    (`TeaSearchRepositoryImpl.searchIds`) is a plain case-insensitive `LIKE '%q%'` **substring** match — it
    has **zero edit-distance tolerance** (a typed "Longjng" never finds "Longjing"). The `pg_trgm` + `unaccent`
    extensions and a trigram GIN index already exist (V1 `tea_name_trgm_idx`) but the search path never calls
    `similarity()`/`word_similarity()` or any fuzzy operator. *Numbered #67 (after #66, held by the open M4
    slice-2 PR #32 — this docs branch was cut off `main`).*
    - **Decision (AskUserQuestion): research-first.** Do **research run 09** before committing an approach —
      compare **in-Postgres `pg_trgm` similarity/`word_similarity` + threshold** (no new infra; extension +
      index already present; fits the single-VM, ops-simple ethos #19 + accepted single-provider lock) vs a
      **dedicated typo-tolerant engine** (Meilisearch / Typesense / OpenSearch — best-in-class fuzzy, but a new
      always-on container on the small VM = RAM/cost + a sync pipeline). Evaluate **ru + en + CJK** typo
      tolerance specifically (trigrams handle Cyrillic/Latin edit distance well; CJK substring differently).
    - **Scope/sequencing.** Recorded, **not built**. Run 09 is queued (kick off via the `research-workflow`
      skill); the implementing backend slice lands around **M5** (or a dedicated search slice) once run 09
      resolves. Not on the M4 critical path. See plan §4a search note + §9 (run 09 row) + §11.

68. **New requirement (task.md) — migrate the container image off Yandex CR to ghcr.io** (planned infra; no
    code yet). `task.md` also grew: *"move from yandex image repository to open ones like ghcr."* Today the
    server image lives in **Yandex Container Registry** (`cr.yandex/<reg>/teatiers-server`, decisions #55–57):
    the VM pulls it via an IAM `docker login cr.yandex` in cloud-init, the image ref is the `SERVER_IMAGE`
    var, and `infra/registry.tf` provisions the CR + puller SA. *Numbered #68.*
    - **Plan:** move the image to **GitHub Container Registry** (`ghcr.io/macsiaproduction/teatiers-server`).
      Touch points: `infra/registry.tf` (drop/retire the CR + puller SA after cutover), the cloud-init
      `docker login` (ghcr token instead of `yc iam create-token`), `SERVER_IMAGE` / `infra/outputs.tf`, the
      deploy compose, and the build/push docs in `infra/README.md`.
    - **Settle at build time (not blockers):** (a) **ghcr.io reachability from the Yandex VM** (RU egress) —
      verify a pull works before cutover; (b) **auth** — public image (no pull secret) vs a GH PAT / deploy
      token stored in **Lockbox** (rule 50-secure, never in VCS); (c) **combine with the still-open "move
      image build into CI" infra item** — GH Actions builds + pushes to ghcr natively (no local `buildx`
      under podman), so this is a clean combined win.
    - **Scope/sequencing.** Recorded, **not built**. Target: **M5 release hardening / infra polish**; not a
      blocker for M4. See plan §8 Deploy note + §11.

69. **M4 app-side slice 1 — optimistic add + background enrichment + status/retry** (app, plan §6/§M4,
    decisions #21/#28; branch stacked on the #67/#68 docs branch off `main`). Wires the app to the §6
    `/resolve` backbone (#64/#66): a freshly-added tea lands on the board instantly, then a background
    `/resolve` patches in ru/zh names + metadata, with a visible status + manual retry so a tea is never
    stuck loading. *Numbered #69 after #67/#68 (this branch carries them); the server slices hold #64–#66.*
    - **Schema (Room v5, destructive pre-launch).** `TeaEntity` gains `catalogTeaId: Long?` + a
      `enrichmentState` string backed by a new domain enum **`EnrichmentState { NONE, PENDING, QUEUED,
      DONE, FAILED }`** (the plan §4b `none|pending|queued|done|failed`). New DAO ops: `updateEnrichmentState`,
      `patchEnrichment` (merge non-blank catalog fields), `teasNeedingEnrichment` (resume sweep), `loadTeaRow`.
      The **backup bundle** (#49) carries both new fields (`BackupTea` + round-trip tests) so a restore
      keeps catalog linkage + enrichment state; `formatVersion` stays 1 (defaulted, so a pre-v5 backup
      restores as an un-enriched custom tea).
    - **Network.** App `CatalogApi.resolve` `POST /teas/resolve` + `ResolveRequestDto`/`ResolveResponseDto`;
      `enrichmentState` added to the detail DTO/`CatalogTeaDetail`. `CatalogRepository.resolve(name, locale?,
      sourceText?)` returns a sealed `ResolveResult` (Matched / Enriching(catalogId) / Unresolved / Offline /
      Error). `sourceText` (#25 "paste a description") is plumbed through but its UI is a later slice.
    - **Engine — `TeaEnrichmentManager`** (`@Singleton`, app-scope). Sets the row `PENDING`, then:
      Matched → patch + `DONE`; Enriching → poll `GET /teas/{id}` (bounded, injectable interval) → `DONE`
      patch / `FAILED`; Unresolved → `DONE` (keep the typed tea); Offline → `QUEUED`; Error/unexpected →
      `FAILED` (fail-closed, never a stuck spinner). The patch is a **non-authoritative suggestion** (#21):
      catalog fills a field only where the user left it blank. `retry(teaId)` re-resolves; `resumePending()`
      re-dispatches `PENDING`/`QUEUED` rows on launch (process-death/offline recovery, #28).
    - **Dispatch / gating.** `TeaBoardRepository.addTea` now returns `AddedTea(teaId, created)`; the ViewModel
      enriches **only a genuinely-new tea** (`created`), never an auto-linked existing one (#42) — so a
      curated tea on another board is never silently overwritten. `BoardViewModel` exposes `retryEnrichment`
      and calls `resumePending()` once on bind.
    - **UI.** `TeaCard` shows a muted "Уточняем…/В очереди" hint (spinner while PENDING) and an error-toned
      "Не удалось уточнить" — visible on every surface that renders a card (board + "Мои чаи"). The board
      card's overflow gains a **"Повторить уточнение"** item, shown only on `FAILED`.
    - **Tests.** `TeaEnrichmentManagerTest` (Matched/Enriching-poll→DONE/poll→FAILED/Offline→QUEUED/Unresolved/
      retry/resume over `FakeTeaDao` + a mock repo), `DefaultCatalogRepositoryTest` resolve cases
      (MockWebServer), and `AddTeaViewModelTest` dispatch gating (created vs auto-linked). **Full app suite =
      148 unit tests green; lint + debug APK build.** *Still open in M4 (unchanged): the "paste a description"
      field UI, the reference-vs-mine flavor split (#23), zh-source/grounded gold sets.*

70. **Architecture review (2026-06-17) — disposition + open decisions** (`context/review/`). Reviewed the
    plan/decisions + app/backend/infra shape; full per-item disposition in
    `context/review/2026-06-17-disposition.md`, the explicit ship checklist in plan **§7.1 MVP release gate**.
    - **Done autonomously:** backup now round-trips the v5 enrichment fields (#69); the release gate +
      this disposition are written.
    - **Planned autonomously (separate verified PRs, no decision needed):** (a) a **global daily LLM
      ceiling** (per-IP rate limit alone is weak behind NAT — add a global daily enrichment-call cap that
      fails closed to Wikidata-only); (b) **`catalogTeaId`-first local dedup** (link a picked catalog tea by
      `catalogTeaId`, block a duplicate non-null `catalogTeaId`, name-match as fallback); (c) **WorkManager**
      for durable `QUEUED`/`PENDING` enrichment retry (the app-scope manager stays for in-app optimistic
      patching). Search typo-tolerance stays gated on **run 09** (#67); ghcr on **#68**.
    - **OPEN — needs your decision (not actioned; recorded as unresolved):**
      - **#70.1 Room public-schema cutover** — **RESOLVED 2026-06-17: leave as-is until M5.** Keep
        destructive `fallbackToDestructiveMigration` + `exportSchema=false` during active M4/M5 development;
        the cutover (export schema + commit baseline + drop destructive + real migrations) is an **M5
        release-gate task** (plan §7.1). Until then internal builds wipe on upgrade — accepted.
      - **#70.2 Catalog image list (backend).** Is a backend `tea_image` *list* table in MVP scope, or do
        catalog teas stay single-`image_url` until post-MVP? (Web image fetching stays banned regardless.)
      - **#70.3 Off-box DB backup.** `/resolve` writes non-seed rows, so the DB is no longer reproducible from
        VCS — enable Object Storage `pg_dump` off-box backups, or accept local-only in writing.
      - **#70.4 Curated seed expansion (13 → ~300, #10).** Prioritize the curated-seed content effort vs lean
        on fuzzy search + Wikidata resolve first. Content effort, not code.
      - **#70.5 en/zh UI timing.** Ship `values-en`/`values-zh` for the release, or keep ru-only with explicit
        picker copy (M5).
      - **#70.6 Background WorkManager for queued enrichment.** In-app resume now runs on every app-open
        (#73), but a tea queued offline while the app is *closed* still waits for the next open. True
        background retry (process-death / reboot) needs **WorkManager** — a new dependency + a Hilt+WorkManager
        runtime integration not device-verifiable here against the AGP 9 / Kotlin 2.4 toolchain. *Decision
        needed:* add it (recommend the Hilt `EntryPoint` worker pattern, network-constrained, unique-by-tea-id
        work), or accept resume-on-app-open as sufficient for MVP (the release gate's "honest copy" branch).

71. **Global daily LLM ceiling (backend cost protection)** (review P1, plan §6 "quota protection"; off
    `main`). The per-IP `ResolveRateLimiter` bounds one caller's burst, but many users share an IP behind
    NAT / mobile networks, so a new **`LlmDailyBudget`** caps total enrichment LLM calls across *all*
    callers per UTC day. `ResolveService` checks it after `isEnabled`: on a Wikidata **miss** with the
    budget exhausted it **fails closed to `UNRESOLVED`** (no stub, no LLM call) instead of queuing
    unbounded paid work; a `FAILED`-stub retry is gated the same way (stays `FAILED`). Cap is
    `teatiers.llm.daily-call-cap` (default 200; `<= 0` = unlimited). In-memory (single server, rule
    40-devops) with a swappable clock; a restart only loosens the cap, never tightens it — fine for cost
    protection. Tests: `LlmDailyBudgetTest` (cap / UTC rollover / unlimited) + a `ResolveServiceTest` case
    (exhausted → UNRESOLVED, no stub/dispatch); affected server unit tests green.

72. **`catalogTeaId`-first local dedup (app)** (review P1, plan §4b/#42; off `main`). Once a tea carries a
    catalog id, that id is the **strongest local identity key** — stronger than the name match. `AddTeaForm`
    gains `catalogTeaId`, set by `pickCatalogTea` (the catalog pick), carried through `toTea`/`toForm`.
    `TeaBoardRepository.addTea`'s resolve-or-create now matches by `catalogTeaId` **first**
    (`TeaDao.findTeaIdByCatalogId`) and falls back to the name match — so two adds of the same catalog tea,
    even with differently-typed names, resolve to **one** user-tea and a second catalog-linked row is never
    created. The add ViewModel also **skips the background `/resolve`** for a catalog-linked tea (`created &&
    catalogTeaId == null`) since a pick already carries the names + id (no redundant re-resolve). Tests:
    repository dedup-by-catalog-id across two boards; ViewModel carries the id + skips enrichment on a pick.
    Full app suite green.

73. **In-app enrichment resume hardening (app)** (review P1 "durable queued enrichment"; off `main`).
    The fully-verifiable slice of the review's WorkManager item: (a) `resumePending()` now also fires from
    **`BoardsViewModel`** (the app's home), so a `QUEUED`/`PENDING` tea retries on **app-open**, not only
    when a specific board is opened; (b) `TeaEnrichmentManager` keeps an **in-flight set** so the same tea
    swept by both the home and a board (or any double dispatch) runs **once** — no wasted resolve / daily
    token. Tests: a gated-resolve concurrency test (second dispatch dropped) + `BoardsViewModel` resumes on
    init; full app suite green. **Deliberately NOT done here:** true post-process-death / reboot background
    retry via **WorkManager** — it adds a dependency and a Hilt+WorkManager runtime integration that can't be
    device-verified in this environment against the AGP 9 / Kotlin 2.4 toolchain, for modest reward over
    resume-on-app-open. Recorded as **open decision #70.6** below.

74. **LLM data-logging opt-out header (ToS)** (review finding on #32; AskUserQuestion). `FoundationModelsClient`
    now sends **`x-data-logging-enabled: false`** on every call — the AI Studio ToS cl. 4.1/3.15 basis for
    storing + re-serving model output (plan §6). Standard on the native Foundation Models API; harmless if the
    OpenAI-compat endpoint ignores it. **Deploy prerequisite (user to verify):** confirm request logging is
    actually disabled for the SA/folder — via this header *or* the folder-level setting — in the Yandex
    console before deploying a real key. Added to the **release gate** (plan §7.1). The tier stays off until a
    key is deployed, so there is no live exposure now.

75. **Backend catalog image LIST** (resolves open #70.2; AskUserQuestion → "pull into MVP"). The catalog now
    stores a **list** of reference images per tea instead of the single `image_url` triple — matching the
    app, which already renders a photo list. **Flyway V3** adds `tea_image` (`tea_id` FK cascade, `position`,
    `url`, `license`, `source_url`, `source`; `UNIQUE(tea_id, position)`), **migrates** any existing single
    image to position 0, and **drops** `tea.image_url/image_license/image_source_url`. New `TeaImage`
    `@OneToMany` (`@OrderBy position`) + `addImage`; `SeedTea` gains `images` and `CatalogSeeder` seeds them.
    `TeaDetailDto` exposes **`images: List<TeaImageDto>`** and keeps **`image`** = the first, for back-compat
    (the current app reads `image`; the new `images` is ignored by its lenient JSON until it adds a gallery).
    Still **CC/Wikimedia + user photos only** — web image fetching stays banned (#24); Wikidata import sets no
    image. Tests: `TeaCatalogServiceIT` (ordered list + first-as-`image`), `TeaControllerTest` (JSON shape).
    Full server suite green (Testcontainers ITs, `TESTCONTAINERS_RYUK_DISABLED=true` locally). *App-side
    gallery (showing >1 image on the detail sheet) is a small optional follow-up; back-compat means no app
    change is required.*

76. **Image delivery moved to ghcr.io** (resolves #68 / task.md "move from yandex image repository to open
    ones like ghcr"; AskUserQuestion). The server image now publishes to **`ghcr.io/macsiaproduction/
    teatiers-server`** instead of Yandex Container Registry. `publish-image.yml` pushes `:latest` + `:<sha>`
    on every `main` push touching `server/**` using the built-in **`GITHUB_TOKEN`** (`packages: write`) — no
    external registry secret, and it solves the cross-arch buildx/podman problem (#57) on a native amd64
    runner. Cloud-init drops the `docker login cr.yandex` IAM step (the ghcr package is **public**, so the VM
    pulls anonymously). `outputs.tf`/`variables.tf` point at the ghcr ref. **YCR + the puller SA are kept
    provisioned but deprecated** (`registry.tf` flagged) — *not destroyed* in this change so the
    currently-running image isn't lost. **User to do at cutover** (infra/README §3): (1) make the ghcr
    package public; (2) set `SERVER_IMAGE=ghcr.io/macsiaproduction/teatiers-server:latest` on the VM +
    `docker compose pull && up -d`; (3) verify `tea.macsia.fun/actuator/health`; (4) then delete
    `registry.tf` + its outputs and `tofu apply` to retire YCR. `tofu validate` + `fmt` clean.

77. **Off-box DB backups provisioned** (resolves #70.3; AskUserQuestion). `/resolve` now writes catalog
    rows that aren't in the committed seed, so the DB is no longer reproducible from VCS — off-box durability
    matters. `deploy/backup.sh` already supported an opt-in S3 upload (`BACKUP_S3_URI`); this adds the
    Terraform to **enable** it: `backups.tf` provisions an Object Storage **bucket** + a dedicated
    **`teatiers-backup` SA** + static key, with a **lifecycle rule** expiring dumps after
    `backup_retention_days` (30). The systemd unit gains `EnvironmentFile=-/opt/teatiers/backup.env` (the `-`
    keeps it working when absent). **User to enable** (infra/README "Backups"): `tofu apply`, write
    `/opt/teatiers/backup.env` from the `backup_*` outputs (kept out of VCS), `apt install awscli`, run the
    service, and do the documented **restore rehearsal** (pull latest dump from S3 → load into a throwaway
    DB → sanity-check). `tofu validate` + `fmt` clean. Local-on-disk dumps remain the default if not enabled.

78. **Second-pass review correctness fixes (app)** (`context/review/2026-06-17-second-pass.md`; off `main`).
    Two findings from the post-merge review:
    - **P0 — a FAILED/PENDING stub returned as `MATCHED` was hidden as local `DONE`.** When the server's
      `cacheHit` finds an existing LLM stub but can't re-arm it (LLM tier off, or `LlmDailyBudget` spent), it
      returns `MATCHED` with the row's `enrichmentState` still `FAILED`. The client mapped `MATCHED` →
      `applyPatch` → always wrote local `DONE`, **silently dropping the retry affordance**. Fixed in
      `TeaEnrichmentManager`: a `Matched` result now branches on `detail.enrichmentState` — `FAILED` → keep
      local `FAILED`; `PENDING` → poll it out; `DONE`/null (settled, e.g. Wikidata/cached) → patch + `DONE`.
      Regression test added (Matched-but-FAILED keeps FAILED, no patch).
    - **P1 — `catalogTeaId` dedup wasn't DB-enforced.** Added a **UNIQUE index** on `teas.catalogTeaId`
      (Room **v6**, destructive pre-launch). SQLite treats NULLs as distinct, so unlimited custom teas are
      fine, but two user-teas can never link to the same catalog row — closing the gap the repository check
      (#72) alone left to concurrent adds / backup import. Full app suite green.

79. **Research run 09 judged → typo search = pg_trgm in Postgres** (resolves #67; `research/09-typo-search/`).
    Winner **opus** (gemini #2, gpt #3, alice #4); 3/4 picked pg_trgm-first, alice dissented (Meilisearch).
    **Decision: implement in-Postgres `pg_trgm`** for ru/en/pinyin typo tolerance — no new always-on service
    (honors the ops-simplicity lock #19); **Hanzi is weak** (Tom Lane: trigrams "fairly useless" on multibyte
    — accepted), with **Meilisearch CE (MIT)** as the documented fallback *only if* a gold set fails.
    **Locked design (opus, verbatim — next implementation slice):**
    - Flyway: an **IMMUTABLE `f_unaccent(text)`** wrapper (`public.unaccent('public.unaccent', $1)`; plain
      `unaccent` is only STABLE), a **`tea_name.name_norm`** `GENERATED ALWAYS AS (lower(f_unaccent(name)))
      STORED` column, and a **GIN `gin_trgm_ops`** index on `name_norm` (replacing `tea_name_trgm_idx`).
    - Query: `word_similarity(qnorm, name_norm)` / the `<%` operator, **threshold ~0.3–0.45** (tune on a
      ru/en/pinyin gold set), ranked `ORDER BY max(word_similarity) DESC, id`; exact-substring always matches.
    - **Prereq:** the DB must NOT be `C`/`POSIX` collation (ICU provider) or pg_trgm ignores Cyrillic; the
      IT must verify this. Ranked results don't id-cursor-paginate cleanly → fuzzy queries return a single
      best-match page (`nextCursor=null`); the blank-query browse path keeps id-cursor. **Not yet built.**

80. **(task.md, future) Python flavor-backfill model — recorded, not started.** task.md grew: *"writing our
    own model in python to backfill flavors for all wiki teas."* The Wikidata-imported catalog rows have **no
    flavor profile** (only curated seed + LLM-enriched rows do). Idea: an offline Python job that estimates a
    flavor profile (the 11-dim 0–5 vocab) for every Wikidata tea and backfills `tea_flavor` as `unverified`.
    *Needs a decision:* model approach (a small trained/regression model vs reuse the existing LLM tier as a
    batch job), provenance/confidence, and whether it's MVP or post-MVP. Big new workstream — would get its
    own research run + plan slice. Flagged for your direction.

81. **(task.md, future) OCR tea-photo text → enrichment grounding — recorded, not started.** task.md grew:
    *"tea photos can also include tea text description, so we should use local model to get text from them and
    include in prompts to local/cloud model."* Idea: OCR the packaging text off a user/catalog photo and feed
    it as the `sourceText` grounding the flavor/blurb enrichment (#25). *Needs a decision:* on-device OCR
    (ML Kit needs GMS — unavailable on RuStore; Tesseract/PaddleOCR are GMS-free options) vs server-side; how
    it ties into the existing `sourceText` path + the injection/copyright guards (#65). Big new workstream —
    own research + slice. Flagged for your direction.

82. **Autonomous deploy run (off-box backup live; ghcr publish confirmed)** (user granted `gh`+`yc`).
    - **Off-box DB backup (#77) — DEPLOYED + verified end-to-end.** `tofu apply` created the
      `teatiers-backups` bucket + `teatiers-backup` SA + key (clean plan: 4 add / 0 change / 0 destroy; the
      bucket needed one retry past Yandex IAM propagation). On the VM: installed **AWS CLI v2** (apt has no
      `awscli` on noble → official zip), wrote `/opt/teatiers/backup.env` (0600, from the `backup_*` tofu
      outputs), enabled the daily `teatiers-backup.timer` (next 03:17 UTC), and ran a **restore rehearsal** —
      pulled the latest dump from S3, loaded it into a throwaway DB (13 teas / 55 names), dropped it. **Bug
      found + fixed:** AWS CLI v2 derives a malformed `ru-central1-` region from VM metadata and aborts →
      pinned `--region ru-central1` in `backup.sh`. Live service stayed healthy (200) throughout.
    - **ghcr (#76) — image confirmed published** (the `publish-image.yml` run on the #42 merge succeeded), so
      `ghcr.io/macsiaproduction/teatiers-server` exists. **Cutover still pending one GitHub-UI step:** make the
      package **public** — the `gh` CLI token lacks `read:packages`/`write:packages` and there is no REST path
      to flip a *user* package's visibility, so it can't be done autonomously. The VM stays on **YCR**
      (healthy, `Up 18h`) until the package is public + the VM is repointed; **YCR not retired** (destructive,
      gated on the cutover).
    - **LLM logging-off (#74) — moot:** the VM `.env` has no `TEATIERS_LLM_API_KEY`, so the tier is off and
      nothing is logged; the header ships with the next image regardless.

83. **ghcr cutover complete + YCR retired** (after you made the `teatiers-server` package public).
    - **VM cut over to ghcr** (`SERVER_IMAGE=ghcr.io/macsiaproduction/teatiers-server:latest`, `compose pull`
      + `up -d`). The ghcr `:latest` was **newer than the running container** (which was the original V1
      deploy), so this also **shipped the latest server code**: Flyway **V1→V2→V3 all applied** on the live
      DB (enrichment_state #66 + tea_image #75), verified — `tea_image` table + `enrichment_state` column
      present, and the live API returns search + detail with the new `images` field. Health stayed `200`.
      Both migrations were schema-only on the 13 seed teas (no data to migrate). `.env` backed up to
      `.env.bak-ycr`.
    - **YCR retired:** deleted the registry image (`yc container image delete`) then the **registry +
      its IAM binding** (`tofu apply`; plan was 2-destroy / 1-change / 0-add). The **puller SA is kept** —
      it is the VM's `service_account_id` (compute.tf), so destroying it would force a VM update; per the
      README it stays attached harmlessly (its only role binding is gone). `registry.tf` now holds just the
      SA; the `registry_id` output is removed. Service healthy throughout; **no Yandex Container Registry
      remains** — image delivery is fully ghcr (#76).

84. **Typo-tolerant catalog search BUILT** (implements the #79 locked design; resolves #67; closes the
    original `task.md` "search ... allows several wrong symbols" requirement + the MVP release-gate line).
    The live search path was literal `LIKE '%q%'` (zero edit-distance); it is now in-Postgres `pg_trgm`.
    - **Migration `V4__name_norm_trgm.sql`:** IMMUTABLE `f_unaccent(text)` wrapper
      (`public.unaccent('public.unaccent', $1)`), a `tea_name.name_norm` `GENERATED ALWAYS AS
      (lower(f_unaccent(name))) STORED` column, a GIN `gin_trgm_ops` index on `name_norm`, and a drop of the
      V1 raw-name `tea_name_trgm_idx` (replaced). Applies on top of V1–V3 with data (ALTER ADD GENERATED
      backfills existing rows) — verified.
    - **Query (`TeaSearchRepositoryImpl`):** blank `q` keeps the Criteria id-cursor browse; non-blank `q`
      runs a native ranked query — `lower(f_unaccent(:q)) <% n.name_norm` (GIN-indexed) OR `strpos(...) > 0`
      (literal substring; covers exact CJK and sub-3-char queries trigrams handle poorly), `GROUP BY t.id`,
      `ORDER BY max(word_similarity) DESC, max(similarity) DESC, t.id`, single page (`nextCursor=null`).
      The `similarity()` tiebreaker makes an exact short name outrank the same word inside a longer one
      (e.g. "пуэр" -> "Пуэр" above "Шу Пуэр Менхай"). The threshold is lowered transaction-locally with
      `select set_config('pg_trgm.word_similarity_threshold','0.3',true)` — the ORM-safe `SET LOCAL` (a
      plain SELECT, no native-DML ambiguity). `TeaCatalogService.search` reorders the fetched teas back to
      the ranked-id order (`findAllWithNames` re-sorts by id) and only paginates the browse path.
    - **Threshold = 0.3**, tuned on the run-09 gold set: the hardest case (1-char substitution in a short
      word, "улонг" -> "Улун") scores `word_similarity` 0.333, so 0.3 keeps full recall; everything else is
      >= 0.41. Encoded as a constant; re-tune via `TeaSearchFuzzyIT`.
    - **Collation prereq — RESOLVED empirically (the #79 open risk):** verified against the production image
      `postgres:16-alpine` that its DEFAULT cluster locale is `en_US.utf8` (libc provider, UTF8 encoding) —
      NOT the feared `C`/`POSIX`. Under it musl libc folds Cyrillic case correctly (`lower('УЛУН')`='улун')
      and trigram-classifies Cyrillic as characters, and `f_unaccent('Lǜ Chá')`='Lu Cha' strips pinyin tone
      marks. So **no explicit ICU collation and no cluster re-init are required**. `TeaSearchFuzzyIT` asserts
      the prereq (datcollate not in C/POSIX, encoding UTF8) so a regression to a C-locale image fails in CI,
      not production.
    - **Tests:** `TeaSearchFuzzyIT` (new) — the 20-query ru/en/pinyin/zh gold set, the collation assertion,
      the ranking tiebreaker, locale scoping, a false-positive guard, and single-page-no-cursor — all green
      against real `postgres:16-alpine`; existing `TeaCatalogServiceIT` (substring + locale + browse cursor)
      still green; full `./gradlew check` (tests + ktlint + detekt) passes. **No app change needed** — the
      client already calls `/teas/search?q=` and gets typo tolerance for free (it just no longer paginates a
      text query, which the #79 design accepts). **Hanzi typo tolerance stays out of scope** (Tom Lane:
      trigrams "fairly useless" on multibyte); exact Hanzi substring works via the `strpos` branch.
      Meilisearch CE (MIT) remains the documented fallback only if a future gold set fails. Not yet deployed
      to the live VM (next image ship runs V4).

85. **Privacy copy corrected to match actual network behavior** (P0 trust bug from the 2026-06-17
    full-design review). `settings_about_privacy` claimed "Все данные ... никуда не отправляются", but the
    add flow sends the typed tea name to the catalog API on search and `/resolve`. Rewrote it to state the
    truth: boards, teas, ratings, notes, photos, and purchase places (incl. geopoints) stay on-device and
    are not uploaded; search/name-clarification send only the typed tea name to the TeaTiers catalog
    service. Also extended `catalog_search_hint` to say the name is sent to the catalog service. ru-only
    `values` (no en/zh files yet), house style kept (em dashes, «», ё; no NBSP introduced in isolation).
    Strings-only change, XML validated. Did NOT mention LLM/Yandex enrichment in the copy because the tier
    is off in prod (no key on the VM, #82) and the "paste a description" `sourceText` UI (#25) isn't built;
    revisit the copy when either ships.

86. **AI billing move to summertime98755 — DEFERRED (no IAM rights).** task: bill Foundation Models usage
    under the `cloud-summertime9875` cloud (b1gu62djbhs46pdcn2kp, org bpffbe9ivue2omhoatm6; has free credits)
    instead of `cloud-macsiaproduction`, leaving the TeaTiers catalog/VM/DB on macsia. FM usage bills to the
    cloud owning the folder in the `gpt://<folder>/...` URI, so the move = run the LLM SA + key against a
    summertime folder (`default` = b1g89hsg2mhm2ic1768h) and repoint the VM's `YC_FOLDER_ID` +
    `TEATIERS_LLM_API_KEY`. My account (`ajesrra4gqk28jd76te3`) only has `editor` on that folder, which
    cannot grant `ai.languageModels.user` to an SA — so the move is blocked pending folder `admin`/
    `resource-manager.admin` rights (or the owner running the grant). Created then deleted a throwaway SA
    while scoping; no resources left behind. Macsia AI (`teatiers-llm` SA + `teatiers-llm-api-key` Lockbox)
    stays in place but dormant (tier off, #82) — nothing bills AI to macsia today. Revisit when rights are
    granted.

87. **"Paste a description" grounding field shipped (#25 UI; resolves the review's "server-ready, not
    product-ready" P1).** The backend has accepted/forwarded `sourceText` since #64/#66, but the app had
    no way to provide it — obscure-tea enrichment was zero-shot (highest hallucination risk). Added an
    optional **add-mode-only** multiline field in `AddTeaScreen` (after Notes), hard-capped to the server
    limit (`SourceTextMaxLength = 4000`, mirrors `MAX_SOURCE_TEXT_LENGTH`) with a live counter and a hint
    that the text is sent to the catalog service for clarification and **not** stored in the shared
    catalog. `sourceText` lives only on the transient `AddTeaForm` — intentionally NOT in `toTea()`/Room
    (raw vendor text is never persisted locally or republished, per the run-10 / #65 guards). On save,
    `submit()` passes `form.sourceText.trim().ifBlank{null}` into the existing
    `enrichmentManager.enrich(teaId, name, sourceText)` → `catalog.resolve(...)` chain, only for a
    genuinely-new, non-catalog-linked tea. Tests: two `AddTeaViewModelTest` cases (blurb forwarded trimmed;
    blank → null); app unit suite green. Note: with the LLM tier off in prod (#82) the blurb is accepted by
    `/resolve` but not yet acted on; the field carries its own disclosure, so global
    `settings_about_privacy` (#85) was left unchanged. The OCR photo → sourceText path (run 10) feeds this
    same field later (on-device RapidOCR/PaddleOCR, not ML Kit — see run-10 RATING).

88. **AI-billing move to summertime98755 DROPPED for now; AI tier stays off for MVP (closes the #86
    direction + the review's "LLM production enablement" P1).** User decision: do not pursue the summertime
    billing move at this time (the folder-`admin` rights gap from #86 isn't worth resolving now). The
    Foundation Models tier therefore stays **off in production** (no `TEATIERS_LLM_API_KEY` on the VM, #82);
    enrichment is Wikidata + curated/cached catalog only. No code change needed — the user-facing copy is
    already AI-neutral and truthful (search/clarification "sends the tea name to the catalog service", #85;
    the #25 field says "sent for clarification", #87) and the en/zh picker already says ru-only
    (`settings_language_hint`). The macsia `teatiers-llm` SA + `teatiers-llm-api-key` Lockbox stay dormant.
    Revisit (and the run-12 async design) only if/when AI enrichment is actually turned on.

89. **App renders the catalog image LIST, not just the first (review P1; backend ready since #75/#70.2).**
    The backend `TeaDetailDto` has exposed `images: List` (+ back-compat `image`) since #75, but the app
    decoded only `image`. Added `images: List<TeaImageDto>` to the app `TeaDetailDto` and
    `images: List<CatalogImage>` to the `CatalogTeaDetail` domain (kept `image` as the first-of-list
    back-compat). The mapper tolerates either wire shape (prefer the list; fall back to the single
    `image`; keep `image` populated as `images.first`). `CatalogDetailSheet` now renders every image in the
    detail gallery and credits each one in the provenance block (per-image CC attribution), instead of
    dropping all but the first. Web-image fetching stays banned — curated CC/Wikimedia or user photos only.
    Tests: new `CatalogMappersTest` (list mapped + `image`=first; single-`image` fallback → one-item list;
    none → empty + null); app unit suite green.

90. **Reference-vs-mine flavor shown together on the user-tea detail (review P1; fulfills the #23
    promise).** Catalog detail already showed reference flavors while browsing, and the user's own
    rating showed on the local tea, but the two were never side-by-side on a catalog-linked user tea.
    `TeaDetailViewModel` now exposes `referenceFlavors: StateFlow<List<FlavorScore>>` — fetched **on
    demand** via `catalog.detail(catalogTeaId)` (no Room cache → no schema bump), keyed on the catalog id
    (distinct, so a boards re-emit never re-fetches) and **fail-closed** (unlinked/offline/error → empty,
    block hidden). `TeaDetailScreen` renders a labeled "Моя оценка" block and, for a linked tea, a
    "Справочно из каталога" block beside it (a shared `FlavorBlock` = radar≥3 axes + strip, reference in
    `tertiary` colour). When the user has **no** rating yet, it shows the reference as the suggestion plus a
    one-tap **"Использовать как мою оценку"** that copies it into the user's ratings via the existing
    `updateTea` edit path. Local `tea_flavors` stay user-owned — never overwritten by the catalog
    (decision #23). Tests: new `TeaDetailViewModelTest` (linked → fetched; unlinked → empty + no lookup;
    copy → `updateTea` with the reference profile); app unit suite green.

91. **pg_trgm typo search DEPLOYED + verified live (closes #84's "not yet deployed" + the release-gate
    line).** Pulled `ghcr.io/macsiaproduction/teatiers-server:latest` (the last server change was the V4
    merge #49; #50/#52/#53/#54 were app-only so the image was unchanged) on the `teatiers` VM and
    `docker compose up -d`. Flyway **V4 applied** on the live catalog DB (history now 1–4; `name_norm`
    populated for 55 names; `tea_name_norm_trgm_idx` present); health stayed UP. **Live typo probes over
    HTTPS** (`/api/v1/teas/search?q=`) resolve to the right tea: `longjng`→Longjing, `biluochn`→Biluochun,
    `тегуанинь`→Tieguanyin (Cyrillic fuzzy works), `лапсанг сучонг`→Lapsang Souchong, `keemnu`→Keemun,
    `龙井`→Longjing; `zzzqqq`→no match. The original task.md "several wrong symbols" requirement is now
    real in production.

92. **Queued-enrichment copy made honest instead of adding WorkManager (resolves the open #70.6 fork;
    user picked the minimal-MVP path).** A tea queued while offline still retries only on app-open
    (`resumePending`), not via a durable background job. Rather than add AndroidX WorkManager (dependency +
    non-device-verifiable wiring), the offline card copy now states the real behavior:
    `enrichment_status_queued` = **"Нет сети — уточним при открытии"** (was the vague "В очереди"). Durable
    WorkManager remains a documented post-MVP upgrade if background durability is later required.

93. **Curated seed expanded 13 → 50 (review P2; first stage of the ~300 target, decision #10).** Added
    37 own-authored, verified teas to `catalog-seed.json` (bundle `version` 2): more Chinese greens
    (Taiping Houkui, Lu'an Guapian, Xinyang Maojian, Anji Bai Cha, Gunpowder), oolongs (Phoenix Dan Cong,
    Rougui, Shui Xian, Dong Ding, Alishan, Jin Xuan, Baozhong, Huangjin Gui), blacks (Dianhong, Jin Jun
    Mei), whites (Bai Mudan, Shou Mei), yellow (Meng Ding Huang Ya), dark/pu'er (Sheng Pu'er, Liu Bao, Fu
    Zhuan), plus **Japanese** greens (Sencha, Gyokuro, Matcha, Hojicha, Genmaicha, Bancha — en+ru names
    only, no zh-Hans/pinyin), **Taiwanese** oolongs, Indian/Ceylon blacks (Nilgiri, Ceylon, Masala Chai),
    blends (English Breakfast, Jasmine), **herbal** (Rooibos, Hibiscus/Karkade, Chamomile, Peppermint),
    and Korean Nokcha. Each carries multilingual names (ru transliteration + Hanzi + toned pinyin for
    Chinese teas), an own-worded en+ru blurb, and an 11-axis flavor profile; `source=curated`,
    `verificationStatus=verified`. No commercial/community-DB imports (provenance lock). Seeder is
    idempotent (dedup on en-name+pinyin+type), so it adds only the 37 new rows on the next deploy;
    `CatalogSeederIT` green against the 50-tea bundle. Doubles as a fuzzy-search gold-set source. **Next
    stage: 50 → 100 → ~300.**

94. **Chinese (zh) support DEFERRED to the far future (user decision).** Scope narrows to **ru + en**:
    - **UI:** Chinese removed from the in-app language picker — dropped `AppLanguage.CHINESE`, the
      `locales_config.xml` `zh` entry, and the unused `settings_language_zh` string; a previously-stored
      `zh` per-app override now falls through to SYSTEM. (en UI still planned for M5; ru is the source.)
    - **OCR:** Chinese/Hanzi is out of scope for the OCR feature (run 10) — it targets **ru + en only**,
      which removes the hardest requirement (vertical/curved Hanzi) and simplifies the engine choice.
    - **Catalog data is NOT touched:** existing `zh-Hans` (Hanzi) names on Chinese teas stay as reference
      display data (authoritative names, harmless), and **`pinyin` remains the romanized search key** so a
      Chinese-named tea is still findable by typing Latin/transliterated text. The seed keeps carrying
      Hanzi+pinyin for Chinese teas — deferral is about *Chinese-language UI/OCR/input support*, not about
      dropping Chinese teas or their names from the product.
    Revisit (zh UI translations, Chinese OCR) only in the far future. Tests updated (`SettingsModelsTest`).

95. **Curated seed expanded 50 → 100 (continues #93; second stage of the ~300 target).** Added 50 more
    own-authored, verified teas (bundle `version` 3), deliberately **biased non-Chinese** (aligns with the
    #94 ru+en focus and avoids Hanzi-accuracy risk): more **Japanese** (Kukicha, Shincha, Kabusecha,
    Tencha, Konacha, Mecha), **Korean** (Sejak, Hwangcha), **Indian/Nepali** (Darjeeling first/second
    flush, Sikkim Temi, Ilam), **Ceylon** regions (Nuwara Eliya, Uva), other black origins (Kenyan,
    Turkish/Rize, Georgian, **Krasnodar** — the RU home tea, Vietnamese green), classic **blends** (Russian
    Caravan, Moroccan Mint, Irish Breakfast, Lady Grey, Vanilla Black), a broad **herbal/tisane** set
    (Lemongrass, Ginger, Lavender, Tulsi, Honeybush, Rosehip, Lemon Verbena, Butterfly Pea, Yerba Mate,
    Spearmint, Fennel, Elderflower, Nettle, Chrysanthemum), plus a confident batch of famous **Chinese**
    teas keeping Hanzi+pinyin as reference (Wuyi Shui Jin Gui / Bai Ji Guan / Tie Luo Han, Zhu Ye Qing,
    Enshi Yu Lu, Duyun Maojian, Mengding Ganlu, Laoshan Green, Yueguang Bai, Gongmei, Huoshan Huangya,
    Tian Jian). All en+ru blurbs own-worded, 11-axis flavor profiles, `source=curated`/`verified`; no
    commercial/community-DB imports. By-type spread now: green 28, black 18, herbal 18, oolong 13, blended
    9, white 5, yellow 4, dark 3, pu'er 2. `CatalogSeederIT` green against the 100-tea bundle (idempotent;
    redeploy adds only the 50 new rows). **Next stage: 100 → ~300.**

96. **2026-06-18 architecture review dispositioned** (`context/review/2026-06-18-current-design-architecture-review.md`;
    verified by an 11-agent review workflow that confirmed all findings, corrected severities, refuted one
    exploit chain, and surfaced 6 missed issues). **Cleanup done in this slice:**
    - **Privacy copy re-fixed (trust bug I re-introduced in #87):** `settings_about_privacy` said the typed
      name is sent and "больше ничего" — false once the optional `sourceText` ships. Rewrote it to disclose
      that the pasted "описание с упаковки" is also sent for clarification and not stored in the catalog
      (matches the honest per-field hint #87 and plan.md §8). values/ is the only locale dir.
    - **Seed data fixes:** `Lu'an Guapian` ru name `Лю Ань`→`Луань` (six 六 here reads *lù*, matching its own
      pinyin/region); Japanese translit normalized to the dominant `-ча` (`Генмайтя`→`Генмайча`,
      `Синтя`→`Синча`). The other 99 teas verified clean (0 dedup collisions, all 39 Hanzi↔pinyin pairs correct).
    - **Stale `plan.md` rows refreshed:** GHCR move "Planned" → DONE (#76/#83, YCR retired); run-09 row
      "Implementation queued" → BUILT + DEPLOYED LIVE (#84/#91).
    - **`.gitignore`:** added `*.code-workspace`.
    - **Live deploy recorded:** after #95 the VM was `docker compose pull && up -d` to the 100-tea image —
      verified live (Darjeeling First Flush, Krasnodar, Yerba Mate etc. resolve on tea.macsia.fun); the
      earlier #91 entry only covered the V4/13-tea state.
    - **Offline catalog cache search is intentionally substring-only (`LIKE`) for MVP** — typo tolerance is a
      server/pg_trgm-only feature; on-device fuzzy fallback is out of scope (no Meilisearch/FTS for the cache).
    **Open decisions resolved (user, 2026-06-18):** dependency advisory gate = **OSV-Scanner in CI** (one job,
    both Gradle modules + infra); **SSH ingress kept world-open (key-only) for now** (revisit before public
    release) — but Caddy will be made authoritative for `X-Forwarded-For` + 8080 confirmed closed regardless;
    **backup import/export to be hardened now** (size/count caps + streaming, OOM guard); **next feature =
    OCR slice 1** (PaddleOCR PP-OCRv5 sidecar + `/teas/ocr` endpoint, per the #25/run-10 server-side opt-in
    design). Room destructive-migration cutover **stays deferred to M5** (#70.1, re-confirmed). Each follow-up
    lands as its own PR.

97. **Backup import hardened against OOM / zip bombs (#96 follow-up; review P1→P2).** `BackupArchive.read`
    now enforces a `Limits` budget while reading an untrusted picked archive: per-photo cap (20 MB),
    per-JSON cap (16 MB), photo-count cap (1000), and a **cumulative-decompressed cap (256 MB)** that bounds
    peak memory and defends against a zip bomb (a 200 MB archive could otherwise decompress to GBs). Breach →
    `BackupArchive.TooLargeException`. `BackupManager.importFrom` adds a cheap up-front SAF size pre-check
    (reject > 200 MB before reading) and maps the exception to a new `BackupResult.TooLarge` →
    `backup_too_large` user message. Path-traversal/nested photo entry names are skipped defensively (on top
    of the existing UUID-rename mitigation). `Limits` defaults = production values; tests pass tiny values.
    Tests: 5 new `BackupArchiveTest` cases (per-photo / count / cumulative / json caps + nested-name skip);
    app unit suite green. NOTE: peak memory is now *bounded* (≤ ~256 MB, off-main-thread) but photos are still
    buffered in a `Map` before write — full streaming (eliminating the Map) is a deferred optimization, and
    export (own data) was left unbounded. The Caddy `X-Forwarded-For` authoritative-header fix + dev-8080
    localhost bind land in a separate infra PR + deploy.

98. **X-Forwarded-For spoofing — verified NON-issue; documented + dev-8080 bind (#96 follow-up; review P1
    deploy-boundary).** The review feared a client could spoof XFF to evade the per-client `/resolve` cost
    limiter (which keys off the first XFF hop). **Verified against Caddy docs:** Caddy v2 with NO
    `trusted_proxies` set (our case — Caddy is the edge) **ignores client-supplied `X-Forwarded-*` and sets
    the real client IP**, so the header is not spoofable; the backend is also only reachable through Caddy
    (8080 unpublished in prod). My first attempt added `header_up X-Forwarded-For {remote_host}` — Caddy
    flagged it as *unnecessary* (the default already does this), confirming the spoof gap doesn't exist on
    2.7+. Reverted that to a documenting comment (and a note: add `trusted_proxies` only if a CDN is ever
    fronted). Also bound the **dev** compose 8080 to `127.0.0.1` (LAN hygiene; prod already publishes only
    Caddy 80/443). SSH ingress **stays world-open (key-only)** per the 2026-06-18 decision — revisit before
    public release. The corrected (comment-only) Caddyfile was deployed via scp + `caddy reload` (graceful);
    site healthy, live search verified. Net: no functional infra change was needed — the protection already
    held; this entry records the verification + the dev-bind.

99. **OCR slice 1a — backend `/teas/ocr` contract shipped (decision #96; runtime = RapidOCR, user choice).**
    The CI-verifiable server half of the OCR feature, tier OFF until the sidecar is configured (mirrors how
    the LLM tier shipped before its key). `POST /api/v1/teas/ocr` (multipart `file`): per-client rate-limited
    (shares the `/resolve` window), rejects empty (400) and oversized (413, `teatiers.ocr.max-image-bytes`
    8 MB + Spring multipart 8 MB cap) uploads, then `OcrService` → `OcrClient` (RestClient, multipart) posts
    the image to the internal RapidOCR sidecar and `OcrSanitizer` cleans the result (NFC, strip
    zero-width/control chars, collapse whitespace, cap to 4000 = the `sourceText` limit). Returns
    `OcrResponseDto{text}` for client-side **review** before it becomes `sourceText` (#25); the image is held
    only for the call and **never stored**. `teatiers.ocr.sidecar-url` is injected at deploy time
    (`TEATIERS_OCR_SIDECAR_URL`); blank/`enabled=false` → 503. Errors are RFC-7807 (503 unavailable / 502
    failed / 413 too-large / 400 / 429). Tests: `OcrSanitizerTest` (cap, zero-width/control strip, whitespace
    collapse, NFC) + 4 `TeaControllerTest` cases (text / 503 / 413 / 400); full `./gradlew check` green.
    **Next — slice 1b:** the RapidOCR PP-OCRv5 **eslav** sidecar (`ocr-sidecar/`: FastAPI + onnxruntime,
    Dockerfile, prod-compose service) with local OCR verification; then slice 2 = deploy (+ resize the VM
    only if RapidOCR's footprint needs it) and slice 3 = the app scan UI. Verified the RapidOCR Cyrillic
    path beforehand (East-Slavic PP-OCRv5 rec model, det v5; dict includes Latin so one pass covers ru+en).

100. **Post-OCR review dispositioned + run-13 judged → plan adapted (2026-06-18).** A deep code-pass review
    (`context/review/2026-06-18-post-ocr-architecture-review.md`) + research run 13 (OCR sidecar) were analyzed
    by a 10-agent workflow that **rated run 13** and **verified every review finding against source**. All five
    code findings confirmed (severities adjusted). Two product forks were put to the user and **decided**:
    - **OCR stays server-side (confirms #99; explicitly SUPERSEDES research run-10's on-device MVP winner).**
      Guardrails locked: scan is **opt-in per image** (never auto-uploads existing tea photos), extracted text
      is **previewed/confirmed** before becoming `sourceText`, the global privacy copy is **rewritten before
      the scan UI (slice 3)** ships, and the sidecar **never logs image bytes or text**.
    - **MVP ships with the AI tier OFF (confirms #88); catalog quality comes from the curated seed, continued
      100 → ~300.** Async flavor-backfill is deferred (needs billing sorted + the flavor-provenance schema
      below). So runs 03/04/06/07/08/11/12 + the #65 bake-off stay dormant-but-built for a post-MVP AI turn.
    **Run 13 verdict (winner opus; see `research/13-ocr-sidecar-accuracy/RATING.md`):** slice-1b sidecar pinned —
    rec `eslav_PP-OCRv5_mobile_rec` + det `PP-OCRv5_mobile_det` (Apache-2.0); **self-convert official Paddle
    weights → ONNX at build (or re-host RapidOCR's SHA-matched ONNX) and CI byte-verifies the SHA**; deps
    `rapidocr==3.8.4` (NOT legacy `rapidocr-onnxruntime`), `onnxruntime==1.27.0`, `fastapi`/`uvicorn[standard]`
    on digest-pinned `python:3.12-slim`, models baked in, no runtime egress; **fits the current 4 GB VM**
    (concurrency 1, mobile models, load-once+warmup, ONNX `intra_op=2`/`inter_op=1` `ORT_SEQUENTIAL`,
    `mem_limit≈1g`) — **resize 4→8 GB only if measured RSS > ~3.5 GB**; preprocessing = downscale + light
    contrast (skip unwarp/orientation). Real ru+en CER must be **measured**, not promised.
    **Verified findings → fix queue (file:line for the PRs):**
    - **P1 enrichment dead-end** — `applyPatch` ([TeaEnrichmentManager.kt:131]) blind-UPDATEs the UNIQUE
      `catalogTeaId` ([Entities.kt:66]); two differently-typed teas ("Tieguanyin"/"тегуанинь") resolving to the
      same catalog id → `SQLiteConstraintException` → caught → permanent FAILED. Fix: pre-check
      `findTeaIdByCatalogId` (already exists, unused) and settle the dupe DONE. Add a Room-instrumented test
      (FakeTeaDao doesn't enforce UNIQUE, so the unit test missed it). (nameEn isn't even a match key — worse.)
    - **P1 prod containers unhardened + OSV-Scanner unwired + prod dropped server/caddy healthchecks** — harden
      `docker-compose.prod.yml` (cap_drop ALL, no-new-privileges, read_only+tmpfs, pids_limit, mem_limit on
      db/caddy/sidecar) **before** the Python sidecar lands; wire `google/osv-scanner-action`; port the
      `actuator/health` check into prod.
    - **P2 OCR rate-limit** (slice 1a, #99) — give `/teas/ocr` its own window (not the shared `/resolve` one)
      and move `isEmpty`/`size` validation BEFORE `tryAcquire`.
    - **P2 LLM budget undercount + 4xx retry** — `LlmDailyBudget` charges once but `chatJson` retries
      maxAttempts (×2 undercount); broad `RestClientException` catch retries 4xx. Charge per attempt (or ÷
      maxAttempts); retry only 5xx/timeouts + jitter. (Dormant tier, so not urgent.)
    - **P2 flavor-provenance schema is an unbuilt prerequisite** — current `tea_flavor(tea_id,dimension,intensity)`
      UNIQUE(tea_id,dimension) can't hold run-11's per-dimension status/confidence/provenance/enrichment_run;
      flagged in the plan now, migration built only when the backfill workstream starts.
    **Adapted next sequence:** (1) fix the P1 enrichment dead-end (+ Room test); (2) prod-hardening + OSV +
    healthchecks; (3) OCR rate-limit window + validate-first; (4) doc refresh + flavor-schema prereq flag;
    (5) **slice 1b** RapidOCR sidecar per the opus spec, with a local OCR proof + measured RSS; (6) **slice 3**
    app scan UI + the rewritten privacy copy. P2 cleanups (fold in opportunistically): LLM budget/retry, pooled
    `RestClient` factory (replace `SimpleClientHttpRequestFactory` ×3), Bucket4j+Caffeine+Resilience4j for the
    limiter/budget/retry cluster, photo-orphan sweep after `replaceAll`, image SHA-pin/cosign + deploy-by-digest,
    `backup_storage` SA least-privilege, written restore-RTO runbook, crash-telemetry keep/skip (Sentry/ACRA).
    **Carried-forward:** Room destructive-migration cutover still deferred to M5 (#70.1, the hard first-public
    blocker); seed 100→~300 (#95); `values-en` ship-or-label before release. **Run 14 (re-verify Yandex async)
    deliberately NOT run now** — run it immediately before the background-enrichment tier is built (it goes stale).

101. **Fixed the P1 enrichment dead-end (#100 step 1).** `TeaEnrichmentManager.applyPatch` blind-UPDATEd the
     UNIQUE `catalogTeaId`, so when two differently-scripted user-teas the name-matcher can't unify (e.g.
     "Tieguanyin" / "тегуанинь") resolved to the SAME catalog row, the second write threw
     `SQLiteConstraintException` → caught → the tea was parked at FAILED, and every "Повторить уточнение"
     re-threw — a permanent dead-end. Fix: `applyPatch` now pre-checks `dao.findTeaIdByCatalogId(detail.id)`
     (a query that already existed but was unused on this path); if a *different* local tea already owns the
     link, the duplicate is settled **DONE** (spinner cleared, no retry trap) without rewriting the link,
     leaving the original intact. The catalog row stays one-user-tea-per-row (the UNIQUE invariant holds).
     Root-caused why tests missed it: `FakeTeaDao.patchEnrichment` was a plain map write with no UNIQUE
     enforcement — now it throws on a non-null `catalogTeaId` already held by a different tea, mirroring Room,
     and a new `TeaEnrichmentManagerTest` case reproduces the scenario (would fail pre-fix) + checks retry
     doesn't re-dead-end. Note the matcher is even weaker than the report (nameEn isn't a match key) — broader
     local-dedup hardening is left as a separate follow-up; this fix removes the permanent-FAILED trap. App
     unit suite green.

102. **Prod-container hardening + OSV-Scanner gate + ported healthcheck (#100 step 2).** Three P1s from the
     post-OCR review, landed together **before** the FastAPI/onnxruntime OCR sidecar (a far larger,
     image-processing attack surface) joins the host.
     - **`docker-compose.prod.yml` hardening.** Every service now runs `security_opt: [no-new-privileges]`
       + `cap_drop: [ALL]`, with only the genuinely-needed caps added back: Caddy `NET_BIND_SERVICE`
       (bind 80/443); Postgres `CHOWN,DAC_OVERRIDE,FOWNER,SETGID,SETUID` (the official entrypoint's
       first-boot data-dir chown + gosu drop to the postgres user — no-new-privileges still blocks
       setuid-binary escalation, and gosu drops rather than gains privileges so it's unaffected). The
       stateless server (already non-root `appuser`) is `read_only: true` + `tmpfs: [/tmp]` (JVM hsperfdata
       + Spring's ≤8 MB multipart spill; tmpfs counts against `mem_limit` so it can't grow unbounded).
       `pids_limit` on all three (server 512 / db 256 / caddy 128); `mem_limit` added to db (768m) + caddy
       (128m), server keeps 1500m. **`cpus` deliberately deferred to the slice-1b PR** which does the
       measured 4 GB arithmetic (review's other P1) rather than guessing CPU caps now.
     - **Ported server healthcheck.** Prod had dropped the dev compose's `server` healthcheck, so a
       post-reboot Caddy could proxy to a not-yet-migrated server (502s). Added the `curl actuator/health`
       check (curl ships in the runtime image) and changed Caddy's `depends_on` to
       `server: {condition: service_healthy}`.
     - **OSV-Scanner wired (decision #96).** OSV can't parse `build.gradle.kts`, so the chosen path is a
       **CycloneDX SBOM → OSV**: applied `org.cyclonedx.bom` 3.2.4 to both modules (added to each version
       catalog), scoped each `cyclonedxBom` to the **shipped** graph only (server `runtimeClasspath`, app
       `releaseRuntimeClasspath`, `includeBuildEnvironment=false`) and pinned the JSON output path. Scoping
       was essential: the unscoped app SBOM dragged in AGP build-tooling deps (netty, bouncycastle,
       httpclient, commons-lang3) with dozens of CVEs that never ship to a device — pure noise. New
       `.github/workflows/osv-scanner.yml` runs `google/osv-scanner-action@v2.3.8` over each SBOM on PR +
       main. **Implemented as two jobs (server JDK 21 / app JDK 17) mirroring ci.yml**, not the literal
       "one job" of #100 — per-module JDK correctness + parallelism, same single gate. Verified locally
       with osv-scanner 2.3.8: both modules **No issues found** (server 101 pkgs, app 196 pkgs), so the gate
       is green on introduction. Plan §7.1 gate row + M5 narrative + §8 CI line updated. The sidecar's
       Python deps get folded into the scan when slice 1b lands.

103. **OCR rate-limit fixed: own window + validate-before-acquire (#100 step 3, review P2).** `/teas/ocr`
     was sharing `/resolve`'s rate-limit window and acquiring a token **before** validating the upload, so
     (a) OCR traffic depleted the `/resolve` budget and vice versa, and (b) an empty/oversized image still
     burned a token. Fixes:
     - **Validate-before-acquire.** `ocr()` now runs the cheap request-shape checks (`isEmpty` → 400,
       `size > maxImageBytes` → 413) **before** `tryAcquire`, so a malformed upload can't spend the
       caller's budget.
     - **Own window.** Generalized `ResolveRateLimiter` → a reusable `FixedWindowRateLimiter(ratePerMinute)`
       (dropped `@Component`); a new `RateLimiterConfig` provides two beans — `resolveRateLimiter`
       (`ResolveProperties.ratePerMinute`=20) and `ocrRateLimiter` (new `OcrProperties.ratePerMinute`=10,
       lower because a scan triggers sidecar inference, heavier than a Wikidata/cache hit). `TeaController`
       selects between them with `@Qualifier`, so each endpoint keeps an independent window. Renamed the
       now-shared `ResolveRateLimitException` → `RateLimitException` (still maps to 429 "Rate limit
       exceeded"). New tests cover independent budgets, OCR's own window, 429 on OCR-window exhaustion, and
       validate-before-acquire (`verify(exactly = 0)` the token isn't spent on a 400/413). Server unit
       suite green; the full-context Testcontainers ITs (which exercise the real `RateLimiterConfig`
       wiring) run in CI. **Deferred** (separate P2, dormant tier): the Bucket4j/Caffeine/Resilience4j
       migration + the LLM budget-undercount/4xx-retry fix.

104. **Doc refresh — review "doc hygiene" closed (#100 step 4).** Marked the two now-stale §7.1 release-gate
     rows done with decision pointers: **container registry** (ghcr cutover complete / YCR retired #83, publish
     + VM pull verified #82) and **off-box DB backup** (live since #82; restore-RTO runbook still a P2 tidy-up).
     Added a public-schema-cutover pointer (→ #70.1/#100, destructive fallback internal-only) to
     `context/photos/plan.md`. The flavor-provenance schema prerequisite was already flagged in plan §4a
     (lines ~111–114, "unbuilt until the backfill workstream starts") by #100, so no further change there.
     Doc-only; no code. **Sequence status:** #100 steps 1–4 done; next is **slice 1b** (the RapidOCR sidecar
     per the run-13/opus spec) then **slice 3** (app scan UI + rewritten privacy copy).

105. **Slice-1b "measure first" proof — greenlit, no VM resize (user-chosen path).** Before wiring the
     sidecar, built a local OCR proof (`research/13-ocr-sidecar-accuracy/proof/`, py3.12 venv, run-13 pins
     exactly: `rapidocr==3.8.4`/`onnxruntime==1.27.0`, eslav PP-OCRv5 mobile rec + `ch_PP-OCRv5_det_mobile`,
     cls off, intra2/inter1, no mem-arena, det limit 960) over a 233-name ru+en corpus drawn from the live
     100-tea seed (clean + degraded renders, 466 OCR calls). Results (see `proof/FINDINGS.md`):
     - **Provenance gate PASS** — both fetched ONNX byte-match the SHA256 pinned in RapidOCR's
       `default_models.yaml` (eslav rec `08705d67…aab` 7.91 MB; det `4d97c44a…8ae` 4.82 MB), confirming
       opus's run-13 claim. **This is the exact byte-verify gate slice-1b CI must enforce on the baked model.**
     - **Footprint: peak RSS 248 MB** (import 30 → init 135 → warmup 158 → batch-peak 248), far under
       run-13's 3.5 GB resize trigger. **Decision: keep the 4 GB / 2 vCPU VM**; size the sidecar compose
       entry at `mem_limit≈1g` with headroom. ~28 img/s, ~35 ms/img on the dev box.
     - **Accuracy (synthetic floor, NOT real packaging):** clean en CER 0.07% / ru 4.5% (en exact 99%,
       ru exact 80%); degraded en 4.5% / ru 22%. ru errors are **systematic**: Cyrillic↔Latin homoglyph
       substitution (`Ассам→Accam`, `Лунцзин→Лунц3ин`), a `Г→Ф` confusion, case + box-split, and the
       degraded-ru figure is inflated by two-word box-order swaps (not char errors).
     **Implications:** build slice 1b on the current VM with the measured config; CI byte-verifies the two
     SHAs; homoglyph confusion is a documented limitation (the flow is OCR → *user reviews/edits* →
     `sourceText`, so a human catches it; a cautious server-side Cyrillic-homoglyph normalization is a
     possible later enhancement). **Still owed before any accuracy claim:** CER on real packaging photos —
     this proof only establishes a clean-text floor. The harness + findings are committed; rendered images,
     fetched models, and `out/` are gitignored.

106. **Built the slice-1b OCR sidecar (`ocr-sidecar/`).** A FastAPI service the catalog server proxies
     `POST /teas/ocr` to (matches slice-1a `OcrClient`: multipart `file` → `{"text": …}`; plus `/health`).
     Loads RapidOCR once + warmup, **concurrency cap 1** (single uvicorn worker), and **never logs image
     bytes or text** (only sizes/timing). Engine = the #105-measured config (eslav PP-OCRv5 mobile rec +
     `ch_PP-OCRv5_det_mobile`, cls off, ONNX intra2/inter1, no mem-arena, det limit 960).
     - **Provenance/egress:** models pinned in `models.lock` + fetched & SHA256-verified at build by
       `fetch_models.sh` (a mismatch fails the build — the gate); baked into the image, so the runtime
       makes **no network calls**. **Gotcha found + fixed:** RapidOCR downloads the angle-cls model at
       construction *even with `use_cls=false`*, which would break the egress-free/read-only container — so
       the tiny `ch_ppocr_mobile_v2.0_cls_mobile` is baked too and `Cls.model_path` set to suppress it.
       Verified locally: with the package cache hidden and only the 3 baked models present, the app boots
       and `/ocr` returns correct ru+en text with **0 download attempts**.
     - **Image:** two-stage, `python:3.12-slim` digest-pinned, non-root (uid 10001), `libgomp1` for
       onnxruntime.
     - **Compose:** prod gets a hardened `ocr-sidecar` (cap_drop ALL, no-new-privileges, read_only+tmpfs,
       `pids_limit 128`, **`mem_limit 1g`/`cpus 1.5`** — the measured arithmetic #102 deferred to here,
       per #105's ~250 MB RSS → fits 4 GB, no resize) + a stdlib `/health` healthcheck. The server gets
       `TEATIERS_OCR_SIDECAR_URL` but **does NOT `depend_on`** the sidecar — OCR fails closed (503/502) so
       the catalog stays up if it's down. Dev compose: opt-in `--profile ocr`.
     - **CI:** new `ocr-sidecar.yml` builds the image (runs the SHA gate), **smoke-tests** it boots +
       loads models + `/health` green (validates the no-egress boot + native libs in-CI, since Docker is
       down locally), and pushes to `ghcr.io/<owner>/teatiers-ocr-sidecar` on main (mirrors
       publish-image.yml). `osv-scanner.yml` gains a Python job: `pip freeze` → scan with `--no-resolve`
       (complete + deterministic, like the Gradle SBOMs). **The gate already paid off:** it caught
       `python-multipart==0.0.20`'s 7 CVEs (up to 8.6) → bumped to 0.0.32 before merge.
     - **Deploy:** `infra/README` documents making the new ghcr package public + adding `OCR_SIDECAR_IMAGE`
       to the VM `.env`. Backend OCR is now deployable end-to-end; **no client calls it until slice 3**
       (app scan UI + rewritten privacy copy) — the next step. Real-packaging CER (#105) still owed.

107. **Slice 3 — app "scan packaging" UI + rewritten privacy copy (#100 step 6; the OCR sequence's last
     piece).** Add mode now has a **Сканировать упаковку** button (camera **or** gallery, both
     permission-free: gallery via `PickVisualMedia`, camera via `TakePicture` into a FileProvider URI under
     `cacheDir/scans/`). The flow honours the decision-#100 guardrails: **opt-in per image** (a regular tea
     photo is never auto-uploaded), the recognized text is shown in an **editable review dialog** and only
     becomes `sourceText` on **confirm** (appended, capped at `SourceTextMaxLength`), and the image is never
     stored (old captures are cleared from the cache before each shot). Data path: `CatalogApi.ocr`
     (`@Multipart POST teas/ocr`) → `DefaultCatalogRepository.ocr` returning a typed `OcrResult`
     (Recognized/Offline/TooLarge/Unavailable/RateLimited/Error from the HTTP code). A new injectable
     `ImageReader` (`AndroidImageReader`) reads + **downscales** the picked image (≤1600 px, JPEG 85) so a
     full-res phone photo stays well under the server cap — and keeps `AddTeaViewModel.scanLabel` JVM-testable
     (the VM has no Bitmap/Context). The button is text-only (the project ships `material-icons-core`, not the
     extended set). **Privacy copy:** the Settings/About disclosure (the app's only privacy surface — there's
     no onboarding screen) was rewritten to state the scan sends the chosen photo for recognition only, it's
     deleted immediately, scanning is per-request, and ordinary photos never leave the device. ru ships in
     `values/strings.xml`; the **en draft is in the PR for review** (a partial `values-en` would degrade the
     ru-first MVP — full en is the separate ship-or-label item #70.5). Tests: repo `ocr()` (multipart shape +
     all status→outcome mappings + offline/empty) and VM scan (recognized→review, confirm→sourceText, append,
     blank/offline→idle, unreadable→idle + sidecar not called). `./gradlew check assembleDebug` green locally.
     **This closes the entire #100 adapted sequence (steps 1–6) and the OCR workstream (slices 1a/1b/3).**
     Remaining OCR follow-up: measure real-packaging CER (#105) once the sidecar is deployed + a photo corpus
     exists.

108. **Deployed the OCR sidecar + #65 prod-hardening to the live VM (user-authorized; OCR tier now live).**
     The updated `infra/deploy/docker-compose.prod.yml` replaced `/opt/teatiers/docker-compose.yml` (timestamped
     backup kept for rollback) and `OCR_SIDECAR_IMAGE` was added to the VM `.env`. The ghcr package was already
     public (the VM pulled it anonymously — no visibility step needed; the `gh` token lacks `packages` scope
     regardless). `docker compose up -d` recreated all four services and they came up **healthy in the gated
     order** (db → server → caddy) — **validating the #65 hardening in prod**: Postgres boots fine with
     `cap_drop ALL` + the 5 added caps (CHOWN/DAC_OVERRIDE/FOWNER/SETGID/SETUID), the server runs read-only,
     Caddy binds 80/443 with only `NET_BIND_SERVICE`. Volume keys (`teatiers_pgdata`, `caddy_*`) matched the
     deployed compose, so no data-loss risk (verified before deploying).
     **Gotcha:** `docker compose up -d` does **not** re-pull a mutable `:latest` tag, so the server first
     recreated from a stale cached image predating `/teas/ocr` → POST returned **405** (the path fell through to
     `GET /teas/{id}`). Fixed with `docker compose pull server && up -d server` (infra/README already prescribes
     pull-first — follow it). **Verified live:** `/teas/facets` 200; `POST /api/v1/teas/ocr` returns
     `{"text":"Тегуаньинь"}` / `{"text":"Da Hong Pao"}` (200) end-to-end through Caddy→server→sidecar. The
     backend OCR tier is live; users reach it once the slice-3 app build (#70) ships. Real-packaging CER (#105)
     still owed.

109. **Two new reviews dispositioned → 8 fixes shipped, after adversarial verification (2026-06-18).** Two
     files landed under `context/review/`. **`current-design`** is **stale/superseded** — every finding was
     already resolved this session (privacy copy #107, OSV gate #102, backup OOM #97, deploy hardening/XFF
     #98/#102, stale docs #104/#67); only the Room cutover (#70.1) + seed→300 (#95) remain, already tracked.
     **`ocr-workstream-complete`** reviews the never-before-seen sidecar + scan UI; its §2 findings were each
     **independently verified against source** by a 10-agent workflow before any fix.
     **Verification mattered:** F1 (pixel-bomb) was *refuted* by a verifier claiming PIL's default
     `MAX_IMAGE_PIXELS` blocks a 13000² image — but PIL only *warns* at that and *raises* at 2× it, so a
     169 MP image decodes (warn-only) → I re-confirmed F1 was real and fixed it correctly (header pixel-budget
     check, NOT lowering PIL's default as the review proposed). Outcomes (PRs #72–#76):
     - **F1** sidecar decompression-bomb → header pixel-budget 413 (#72). **F8** sidecar output cap + server
       `OcrSanitizer` cap-early (#72/#73). **F9** sidecar pytest + server `OcrServiceTest` proxy-contract (#72/#73).
     - **F2** EXIF orientation in `AndroidImageReader` (camera scans were sideways; sidecar has cls off) — #75.
     - **F3** prod compose network split + **F5** (sidecar portion) `internal` nets so the sidecar can't reach
       Postgres *or* the internet (#74). **F4** global OCR concurrency `Semaphore` → fast-fail 503 (#73).
     - **F10** shared startup http(s) validation on the 3 operator-set endpoint URLs (#73).
     - **F6** merge catalog metadata into same-catalog duplicates (link-free) + `nameEn` in the local
       matcher (#76).
     **Deferred (deliberate):** **F7** Wikidata `zh` label broadening — needs live SPARQL verification
     (row-multiplication risk), cosmetic-reference-only, zh is deferred (#94). **F5 VM-wide egress whitelist**
     — risky (could break ACME/Wikidata/Yandex), needs measuring real egress; the `internal` nets already
     neutralize the sidecar-specific risk. §4 carried-forward items (Room cutover, photo-orphan sweep, pooled
     `RestClient`, supply-chain SHA-pin, restore-RTO runbook, `backup_storage` SA least-privilege) stay tracked,
     not re-opened. **Research:** run-15 (GMS-free crash telemetry) answers gathered but **unrated** — judge
     before the public APK; run-13 real-packaging CER (#105) still owed; run-14 reserved. **Redeploy:** the
     #72/#73 image rebuilds (ghcr `:latest`) + #74 network split applied to the live VM in a controlled
     redeploy (#110).

110. **Redeployed the review hardening to the live VM (user-authorized full redeploy; verified).** Pulled the
     rebuilt server (`:latest` w/ F4 concurrency gate, F8 sanitize cap, F10 URL validation) + sidecar (F1
     pixel-budget guard, F8 output cap) images and applied the network-segmented compose (#74) — `docker
     compose up -d` created the `teatiers_edge`/`teatiers_data`/`teatiers_ocr` nets and recreated all four
     containers healthy in the gated order. **Verified live:** `/teas/facets` 200; `POST /teas/ocr` →
     `{"text":"Тегуаньинь"}` 200 (server→sidecar over the `ocr` net works); and crucially the **isolation
     holds** — from inside the sidecar container, `db:5432` is unreachable (DNS `gaierror`: not on the data
     net) and the public internet (`1.1.1.1:443`) is unreachable (`OSError`: the internal `ocr` net has no
     gateway). The timestamped backup compose is kept for rollback. F2 (EXIF) + F6 (dedup) are app-side and
     reach users with the next APK build. The OCR backend tier is now live AND hardened end-to-end.

111. **Research run 15 (crash telemetry) judged → telemetry plan decided (2026-06-18).** Rated the 5
     gathered answers (`research/15-crash-telemetry/RATING.md`); **winner = gpt** (its first leaderboard win),
     opus a close #2; gemini/deepseek/alice trailed on real errors (gemini fabricated a GMS-free "reflection
     probe" + stale pins; deepseek got Sentry's license wrong — it's **FSL→Apache-2.0 after 2y**, not BSL/3y —
     + a Celery-vs-rq worker bug; alice was honest-but-incomplete with a hallucinated `epitaph` dep).
     LEADERBOARD updated. **Applied to planning (plan §8 Observability):**
     - **KEEP crash/error telemetry for the public MVP.** A destructive Room migration silently wipes local
       data without crashing, and local-first means no backend session would ever reveal it — flying blind is
       the unacceptable risk.
     - **Adopt ACRA (`ch.acra:acra-http`, GMS-free, Apache-2.0) → a first-party `/api/v1/client-diagnostics`
       endpoint** on the existing backend (gpt's pick — best fit for TeaTiers' real constraints: GMS-free,
       self-hosted, data-minimized, **zero new service/RAM**). **GlitchTip + `io.sentry:sentry-android`**
       (MIT, GMS-free, self-hosted) is the documented **upgrade path**, NOT MVP — its Django+Celery+Postgres
       stack doesn't fit the ~3.4 GB-committed 4 GB VM (co-host OOM risk / 2nd-VM cost). **Sentry self-host is
       OUT** (installer 7 GB errors-only / 14 GB full RAM floor); **Sentry SaaS is OFAC-blocked from Russia**.
     - **Design (vendor-independent core):** the silent-wipe detector is an **out-of-Room before/after
       row-count sentinel** (DataStore: last-known boards/teas/notes/photos counts; non-zero→zero after a
       version/schema change ⇒ emit `room_migration_signal`) **plus** the `onDestructiveMigration` callback —
       the count-sentinel (gpt) is more robust than the callback alone. Strict **allowlist** (app/OS/device/
       version/stacktrace + numeric counts only — never names/notes/photo-URIs/coords/board-names), backend
       token-rejecting sanitizer, **opt-in** (off by default + new privacy-disclosure copy), don't persist
       client IP, 30–90d retention, CI **GMS-gate** on `releaseRuntimeClasspath`.
     - **Not built now** — it's a **pre-public-APK slice, built alongside the Room cutover (#70.1)** (the
       migration sentinel is the whole point). `proof`-style real-packaging CER (#105) is the immediate task.

112. **CER measurement (#105): harness built + a realistic-synthetic floor measured; real-photo number
     still owed.** No real tea-packaging photos exist anywhere (repo, proof dir, upload folder), so the true
     real-photo CER can't be produced yet. Built `proof/measure_photos.py` (runs the identical sidecar
     engine config + EXIF orientation over a photo corpus → corpus CER / exact-match / ms-img / peak RSS;
     real photos + labels gitignored) and, per the user "find or generate" call, `proof/gen_realistic.py`
     (renders the 233 ru+en seed names onto packaging-like images: textured bg, 7 fonts, perspective tilt,
     uneven lighting + glare, blur, noise, JPEG — exact ground truth; moderate ≈ phone-photo-at-an-angle).
     **Measured (realistic-synthetic, 233 imgs):** **en 0.0% CER / 100% exact; ru 9.23% CER / 72.3% exact**
     (combined 4.08% / 88%) — roughly **2× the clean floor** for ru, en still perfect. Same systematic ru
     failures as the clean proof (Cyrillic↔Latin homoglyphs, `Ассам→Accam`) **plus** a new realistic one:
     short ru words under glare/warp sometimes fail detection entirely (`Синча→""`). Peak RSS **739 MB** on
     the larger renders (app uploads downscale to ≤1600 px, so smaller in practice) — still < the 1 GB cap.
     **Verdict:** still SYNTHETIC — a stronger floor, not real-world; the review-before-`sourceText` flow
     tolerates it (user fixes a homoglyph / re-scans an empty), so usability note not blocker. **The
     real-photo gold number remains owed** — the harness is ready the moment a photo corpus
     (`proof/corpus/` + `ground_truth.tsv`) exists. See `proof/FINDINGS.md` §5.

113. **Real-photo CER — first sample measured (n=4, user-provided 2026-06-18).** The user supplied 4 real
     RU-marketplace tea-product photos (Wildberries / SberMegaMarket / Yandex Market). Real packaging is
     **multi-block/multi-script**, so whole-string CER is meaningless (the model reads brand/weight/
     description too → ~1630%, which the harness flags) — the product metric is **name-capture** (does the
     key tea name get read *somewhere* for the user to pick). Enhanced `measure_photos.py` to report it
     (rapidfuzz partial-ratio). **Result: 3/4 captured (75%), mean 89/100.** ГАБА АЛИШАНЬ ✅, Лапсанг Сушонг
     ✅ (small print), HONG LO ✅ (multilingual); the **miss** was the lowest-res image (430 px MYASNOV
     pouch — read `Фуцзянь` + the `КPАСНЫЙ` Р→P homoglyph but lost `Хун Ча`). Real failure modes match the
     synthetic ones — **Cyrillic↔Latin homoglyphs persist** (`КPАСНЫЙ`, `250 гp`) and **low-res hurts**.
     Tiny sample (more photos in `proof/corpus/` firm it up), but the verdict is consistent across
     synthetic + real: **usable given the OCR→review→`sourceText` flow** (user fixes homoglyphs / re-scans a
     poor shot); homoglyphs + low resolution are the watch-items. Photos are gitignored (copyright/privacy);
     FINDINGS §5 + the harness are committed.
     *(Process note: per the user, pure-docs/research changes — like this entry, FINDINGS, RATING — now
     commit straight to `main` without a PR; only product/build/CI code goes through branch→PR→CI.)*

114. **OCR approach reconsidered post-measurement → KEEP, re-prioritized (2026-06-19).** A multi-agent
     assess→adversarial-verify workflow + local empirical tests (`proof/reconsider_test.py`) re-weighed the
     whole OCR approach against the measured data. **Conclusion: the core architecture is right — keep the
     self-hosted eslav-mobile server-side sidecar** (free, private/no-egress/never-logged, GMS-free, fits
     4 GB, AI-off MVP); no re-architecture. **Decisive empirical finding:** the two rec-level fixes give
     ZERO real-photo gain — homoglyph-fold (ru 9.2→8.1% CER synth but real-photo capture unchanged 3/4) and
     the `cyrillic` rec model (≈eslav) — because the one real miss is **detection, not recognition**;
     **conditional low-res upscale** (short side <500 px → 960, bicubic, before OCR) recovers it
     (real-photo capture **3/4 → 4/4**, no regression; confirmed twice). Verdicts (full write-up:
     `research/13-ocr-sidecar-accuracy/RECONSIDER.md`):
     - **DO NOW:** (C) conditional low-res upscale in the sidecar [the one verified accuracy win — small
       `app.py` change → via PR; must be conditional + bicubic + pixel-capped, unconditional regresses
       ru 9.2→17%]; (F) keep-with-polish — ship as-is + client capture-quality UX + a "couldn't read it,
       re-scan" path + instrument real capture/edit rates.
     - **PILOT:** (A) Yandex Vision OCR — dedicated en-ru model + stronger detector attack BOTH failure
       modes at ~$1.08/1000 (verified), same trust boundary (image already on our Yandex-Cloud backend;
       server-side ⇒ GMS-free; 152-FZ UZ-1), but NO measured win on our corpus + the `x-data-logging-enabled:
       false` no-retention header isn't documented for the OCR endpoint → measure head-to-head via
       `measure_photos.py` first; keep eslav default, Vision as optional/low-confidence fallback.
     - **LATER:** (D) handle homoglyphs at the **resolver** (pg_trgm, canonical-Cyrillic catalog), not in
       OcrSanitizer (the OCR-side fold is redundant given review + fuzzy match).
     - **SKIP:** (B) server-grade models [verified: NO PP-OCRv5 Cyrillic *server* rec exists — all language
       recs are mobile-only; server det is 84 MB/~3× latency and won't fix the charset homoglyph]; (E)
       Tesseract/EasyOCR [worse on detection / ~1.8 GB > cap], on-device swap [same eslav weights = identical
       accuracy, only an already-mitigated privacy upside], rec-model swap [`cyrillic`≈`eslav`].
     Next concrete action: implement the conditional upscale (C) in the sidecar. Research tooling + docs
     committed straight to main; the sidecar change goes via PR.