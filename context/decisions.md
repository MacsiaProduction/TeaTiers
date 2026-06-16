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
