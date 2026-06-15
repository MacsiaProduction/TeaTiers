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
