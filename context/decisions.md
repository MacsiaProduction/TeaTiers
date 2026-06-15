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
