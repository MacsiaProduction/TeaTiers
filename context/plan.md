# TeaTiers — implementation plan

Source of truth for the build plan. Decisions it rests on are in
`./decisions.md`; product spec in `../task.md`; stack rules in `../AGENTS.md`.
Research runs 01–06 are **resolved** (01–04 won by opus; 05–06 by alice): backend on
**Yandex Cloud via Terraform**, **no VPN** (booster runs on Yandex-native Qwen3/DeepSeek).
A **UX/cost review** (#19–21) then kept the always-on VM, **cut the map/geopoint from MVP**
(purchase location = free-text + URL only), and made tea-adding **optimistic with
background/queued enrichment**. The tea entity gained a **photo, flavor metrics, and
short/full descriptions** (#22–24), and enrichment can be **grounded on user-pasted vendor
text** (#25) — with **run 07 (flavor-prompt tuning) pending**. A **UX review** (#26–28)
added MVP **file export/import** (the local-first backup gap), a cross-board **"my teas"**
view, and the missing **states/surfaces** (enrichment status, empty/zero states, settings,
attributions, accessibility). See *Research & outcomes* (§9) and `decisions.md` #1–28.

## 1. What we're building

A personal, **local-first** Android tier-list app for teas. The user creates boards,
defines customizable tiers, adds teas (searched from a shared catalog or typed by hand),
drags them into tiers, records where they bought each (free text or a marketplace URL),
and adds their own **photo, taste ratings, and notes**. Each tea shows a **photo, a short
taste blurb, and a flavor profile** (горечь/сладость/фруктовость…), with the long
description tucked behind a **"read full"** affordance. All user data lives **on-device**;
a Yandex Cloud backend serves only the shared, multilingual (ru/en/zh) **tea catalog**
the app searches and caches.

Minimal-where-needed: no accounts, no cloud user data, no social features — those
were explicitly ruled out (`decisions.md` #1).

## 2. Architecture

```
┌─────────────────────────── Android app (Kotlin + Compose) ───────────────────────────┐
│  ui/ (Compose)   viewmodel/ (StateFlow)   domain/ (models, use cases)                  │
│  data/                                                                                 │
│    ├─ local  : Room  → boards, tiers, user teas, purchase locations, notes  (SOURCE)   │
│    │           DataStore → preferences                                                 │
│    ├─ remote : Retrofit/OkHttp → catalog search/detail + resolve (background, optimistic)│
│    └─ catalog cache : Room table of fetched catalog entries (offline reuse)            │
│  purchase location (MVP) = free-text place + marketplace URL only — no map/geo (#20)   │
└────────────────────────────────────────────────────────────────────────────────────┘
                              │  HTTPS  /api/v1/teas/...   (catalog only)
                              ▼
┌──── Yandex Cloud VM (Terraform-provisioned) — Kotlin + Spring Boot 3.x, JDK 21 ─────────┐
│  controller/ → service/ → repository/ (Spring Data JPA)                                 │
│  catalog search + detail API; resolve-on-miss enrich endpoint (RFC-7807, /api/v1)        │
│  enrichment/ : Wikidata(CC0) → YandexGPT Lite → Yandex-native Qwen3/DeepSeek zh-booster   │
│  all LLM calls = Yandex Cloud Foundation Models (one SDK, NO VPN); publish 'unverified'   │
│  seed import: curated ~300 teas + Wikidata subtree (CC0); rate-limit + dedup guard         │
└────────────────────────────────────────────────────────────────────────────────────┘
                              │  JDBC (localhost)
                              ▼
        PostgreSQL — self-hosted in the compose stack on the VM (Flyway migrations)
        Managed Service for PostgreSQL = upgrade path if backups/HA are needed.
```

Data-flow notes:
- The app **never** depends on the server for its own data. Offline, the user can do
  everything except search the catalog for teas not already cached.
- The backend holds **no user data / no PII**. It writes catalog rows two ways: the
  seed import and **on-demand enrich-on-miss** (publishing `unverified` teas). Only a
  raw tea-name string ever reaches it — never a user identifier.

## 3. Repo / module layout

```
TeaTiers/
├─ app/        Android client (Kotlin + Compose)        — rules: .cursor/rules/20-android
├─ server/     backend catalog service (Spring Boot)    — rules: .cursor/rules/30-backend
├─ context/    requirements, locked decisions, this plan (source of truth)
├─ research/   deep-research runs + ratings             — rules: .cursor/rules/70-research
├─ scripts/    automation (sync-agent-rules.sh, …)
├─ infra/      Terraform for Yandex Cloud (VM, VPC/SG, Lockbox, CR, S3+YDB state) — rules: 40-devops
├─ Makefile    build/run/deploy entry points (Phase 0)
├─ docker-compose.yml  backend + Postgres (M2)
├─ task.md     product spec      ├─ architecture.md  high-level arch
```

Both modules use Gradle Kotlin DSL with a `libs.versions.toml` version catalog. All
versions (Kotlin, AGP, Compose BOM, Spring Boot, minSdk/targetSdk/compileSdk, JDK
toolchains) are **pinned in the catalog and verified against upstream before use** —
not invented here (per AGENTS.md "never guess versions").

## 4. Data model

### 4a. Backend catalog (PostgreSQL, locale-aware) — confirmed by research 01

Adopt the winning (opus) schema; firm the exact DDL in the first Flyway migration.

- `tea` — `id`, `wikidata_qid` (nullable, cross-source identity key), `type` (enum from
  GB/T 30766-2014: green/white/yellow/oolong/black/dark + puer/herbal/blended/other),
  `origin_country`, `region`, `cultivar` (nullable), `oxidation_min/max` (nullable),
  `brand` (nullable), **`image_url` (nullable) + `image_license` + `image_source_url`**
  (one optional CC/Wikimedia reference image, **stored in Yandex Object Storage, not
  hotlinked** — #24), provenance: `source`, `source_url`, `license`, `retrieved_at`,
  `created_at`, `updated_at`.
- `tea_name` — `id`, `tea_id` FK, `locale` (`en`|`ru`|`zh-Hans`|`pinyin`), `name`,
  `is_primary`, optional per-name `source`/`license`. `UNIQUE(tea_id, locale, name)` +
  a partial unique index `(tea_id, locale) WHERE is_primary` (one primary per locale).
  Multi-row-per-locale so aliases fit — NOT one row per locale.
- `tea_description` — `tea_id` FK, `locale` (`ru`|`en`|`zh`), `short` (1–2 sentence taste
  blurb → the **card**), `full` (nullable → behind **"read full"**), `source`, `license`.
  Localized; **Wikipedia `full` text is CC-BY-SA → attribute + link**; the `short` blurb
  is own/AI to keep the card clean (#22).
- `tea_flavor` — `tea_id` FK, `dimension` (enum: `BITTERNESS`,`SWEETNESS`,`ASTRINGENCY`,
  `FRUITINESS`,`FLORAL`,`GRASSY`,`SPICY`,`SMOKY`,`EARTHY_NUTTY`,`UMAMI`,`ROASTED` —
  extensible), `intensity` smallint 0–5. The **reference** profile (curated for seed teas;
  AI-estimated `unverified` otherwise, #23). DB stores enum + number → drives a radar/bar;
  an **absent row = "unknown"** for that dimension (0 means *none*, not *unknown*).
  **Dimension labels (горечь/bitterness/苦味) are localized client-side** in `strings.xml`.
- Search: `pg_trgm` GIN on `tea_name.name` for prefix/substring across Cyrillic + CJK
  (trigrams cover CJK *substring* matching; defer `zhparser`/`pg_cjk_parser` word
  segmentation until actually needed). `unaccent` for Latin; ICU collations on PG 16.
- Per-row provenance is mandatory for ODbL/CC-BY-SA share-alike. Keep ODbL-derived
  (Open Food Facts) rows isolated from curated data to avoid copyleft pollution.
- **Enrichment provenance** (AI/web-derived rows): `verification_status`
  (`verified`|`unverified`), `confidence` (0–1), `enriched_by` (model id), `enriched_at`;
  `source` extends to `wikidata`|`curated`|`ai`|`user`. Clients can show an "unverified"
  hint; a later curation pass promotes rows to `verified`. See §6.
- **Dedup integrity (concurrency):** a `UNIQUE` index on the normalized dedup key
  (unaccented-lower name + pinyin slug + type) backs the §6 cache; enrich-on-miss inserts
  via **upsert** (`ON CONFLICT` → return existing) so two users resolving the same new tea
  at once can't create duplicate rows.

### 4b. Android local (Room) — the user's private data

- `board` — `id`, `name`, `position`, `createdAt`.
- `tier` — `id`, `boardId` FK, `label`, `colorArgb`, `position`. (Customizable;
  seeded with default S/A/B/C/D when a board is created.)
- `user_tea` — `id`, `boardId` FK, `tierId` (nullable — unranked tray), `position`,
  `catalogTeaId` (nullable link to a catalog entry), **denormalized name snapshot**
  (`nameRu/nameEn/nameZh`, `type`, `origin`) so boards render offline, `notes`,
  **`userPhotoUri` (nullable — the user's own photo, #24)**,
  **`enrichmentState` (`none`|`pending`|`queued`|`done`|`failed`, #28)**, `createdAt`.
  A `user_tea` is either catalog-linked (snapshot copied at add time) or fully custom
  (user-typed). `enrichmentState` drives the "enriching…/offline-queued/failed — retry" UI.
- `user_tea_flavor` — `userTeaId` FK, `dimension` (same enum as `tea_flavor`),
  `intensity` 0–5. The **user's own** taste ratings, which override/augment the catalog
  reference profile on their board (#23).
- `purchase_location` — `id`, `userTeaId` FK, `kind` (`URL`|`TEXT`), `url`, `text`.
  **MVP drops the geopoint/map** (#20); `GEOPOINT` + `lat`/`lng`/`placeName` are a
  post-MVP addition. If geo returns later, treat coords as sensitive (store minimally,
  never log — rule 50-secure).
- `catalog_cache` — fetched catalog search/detail results keyed by `catalogTeaId`,
  with `fetchedAt`, to back offline search of previously seen teas.

## 5. Backend API surface (`/api/v1`, RFC-7807 errors)

- `GET /api/v1/teas/search?q=&locale=&type=&origin=&limit=&cursor=` — paged catalog
  search; matches names in the given locale (and cross-locale). Returns id + all
  locale names + metadata.
- `GET /api/v1/teas/{id}` — full catalog detail.
- `GET /api/v1/teas/facets` — distinct types/origins for client-side filters (optional).
- `POST /api/v1/teas/resolve` — body `{ name, locale, sourceText? }`. Dedups against the
  catalog (normalized name / pinyin slug); on a miss, runs **on-demand enrichment** (§6)
  and returns the `unverified` tea. Optional **`sourceText`** = a pasted vendor description
  that **grounds** the flavor profile (#25). The client calls this **in the background
  after adding the tea locally** (optimistic — §6); if offline, it's **queued**.
  **Rate-limited per IP; `name` and `sourceText` length-capped + HTML-stripped + treated as
  untrusted; never per keystroke.**
- `GET /actuator/health` — liveness/readiness for compose/systemd.

DTOs separate from JPA entities; inputs validated with Bean Validation; no user data,
so no auth — but the write-capable `resolve` endpoint needs per-IP rate-limiting, a
max-length/charset cap on `name`, and a daily enrichment-call budget to protect the
free-tier AI quota.

## 6. Catalog enrichment (on-demand, AI + verification)

**Providers (research 03–06, decisions #15/#17/#18):** free verification spine
**Wikidata → Wikipedia** resolves most teas; primary LLM **YandexGPT Lite**
(`json_schema`, logging off — ToS cl. 4.1/3.15 permit store-and-serve); booster for
hard/low-confidence Chinese names = **Yandex-native Qwen3-235B / DeepSeek V4 Flash**
(same Foundation Models SDK). **All LLM calls are direct Yandex Cloud — the German VPN
is dropped** (decision #18). **Not used:** Gemini (EEA paid-only), Groq/VPN (no longer
needed), Mistral (weak zh), Yandex Translate for names (literal). Enrichment is
**on-demand only** and **auto-publishes `unverified`** with a confidence score.
*Verify in console that Qwen3-235B / DeepSeek Flash are listed + their price; if absent,
fall back to Groq-via-VPN (#17).* *M4 also benchmarks **Alice AI LLM Flash**
(`aliceai-llm-flash`, 64k, cheaper input) as a possible primary swap.*

**Client UX (decision #21):** adding a tea is **optimistic** — it lands on the board
instantly with the user's typed name; the client calls `/resolve` in the **background**
and patches the local row with ru/zh names + metadata when it returns. **Offline → the
enrichment is queued** and runs on reconnect, so logging a fresh purchase never blocks on
the network. Enriched names are **editable suggestions**, never authoritative — the user
can correct them on their own board.

Enrich-on-miss flow, triggered by `POST /api/v1/teas/resolve`:
1. **Dedup / cache** — normalize the input (lowercase, unaccent, pinyin slug) and match
   against `tea_name`; if found, return the existing tea. This *is* the cache — the
   second user to type a tea never triggers enrichment again.
2. **Wikidata first (free, CC0)** — look the name up; on a match, pull ru/en/zh + pinyin
   labels and metadata at high confidence. No LLM call needed.
3. **LLM step (YandexGPT Lite primary; Yandex-native Qwen3/DeepSeek zh-booster)** — on a
   Wikidata miss, call YandexGPT Lite (logging off) for strict `json_schema` JSON: ru/en/zh
   names + pinyin + type/origin, **a short ru taste blurb, and a flavor-profile estimate**
   (the `tea_flavor` dimensions, 0–5). **If the user pasted `sourceText` (#25), the LLM
   derives the profile + blurb *from that text* (grounded → higher confidence)** instead of
   guessing; dimensions with no evidence stay null/low, and the blurb is an **original
   paraphrase** (the raw prose is never stored/republished — copyright). If the result is a
   Chinese name with low confidence, re-ask **Yandex-hosted Qwen3-235B / DeepSeek** (same
   SDK, no VPN) and take the better. Always transliterate trade names, never literal-
   translate ("Да Хун Пао", not "Большой красный халат"). The blurb + flavor estimate are
   `unverified` and user-editable; **`full` description prefers a Wikipedia extract
   (CC-BY-SA, attributed)** when the Wikidata match has an article; **images are never
   generated** (CC/Wikimedia or the user's own photo only — #24). Prompts/rubric/schema/
   injection-guards are tuned in **research 07**.
4. **Cross-check & publish** — reconcile against Wikidata/Wikipedia and compute a
   **programmatic confidence** (entity match + `pinyin4j` Hanzi↔pinyin check + a
   transliteration litmus test that penalizes a literal RU translation) — *not* the
   model's self-rating. Set `verification_status` (`unverified` below threshold),
   `confidence`, `source='ai'`, `enriched_by`, insert, and return the row.

In scope (decisions 2026-06-15): translate/normalize names, verify/cross-check facts,
auto-fill a user's custom tea. **Out of scope:** open-ended web crawling / web-search
grounding to *discover* or enrich teas — **confirmed by research 08 / decision #45**:
under the no-VPN, all-Yandex-native lock (#18) there is no compliant web-grounding path
(Yandex Search API forbids storing results, ToS 2.7.4; Yandex LLMs have no built-in
search; Tavily/Gemini = the non-Yandex egress #18 removed). Grounding stays **Wikidata →
user-pasted `sourceText` (#25) → Yandex LLM**; never store built-in-grounded output.

Guardrails:
- **Keys server-side only** (rules 10/50) — the app never holds an AI/search key.
- **Quota protection:** per-IP rate limit, daily enrichment-call cap, the dedup cache
  above, and debounce (resolve fires on an explicit add, not search-as-you-type).
- **Cost order:** Wikidata (free) → YandexGPT Lite (grant→~₽0.2/1k) → Yandex-native
  Qwen3/DeepSeek only for hard zh (also grant-covered Yandex tokens). No VPN, no non-RU
  card, no other paid service.
- **No egress proxy / VPN** (decision #18) — every LLM call is direct Yandex Cloud
  Foundation Models via one OpenAI-compatible SDK. Off-Yandex calls are only the free
  Wikidata/Wikipedia verification lookups.
- **ToS:** all enrichment LLMs are Yandex Foundation Models — storing + re-serving output
  is permitted (AI Studio cl. 4.1/1.2.2/3.15, logging off via `x-data-logging-enabled:
  false`); cl. 3.3 mandates our verify-before-publish gate.

## 7. Build roadmap (milestones)

Phase 0 (scaffolding) and Phase 1 (research) are foundational/done. The build is then
sliced into **shippable milestones** so something usable + safe is in hand early —
**M1 (offline app) and M2 (backend) run in parallel**, with no cross-dependency until M3.
Dependency order: `M1 ∥ M2 → M3 → M4 → M5 → (M6 later)`.

**Phase 0 — Scaffolding (no blockers).**
Create `app/` and `server/` Gradle skeletons with version catalogs (pin + verify
versions), a root `Makefile`, `.dockerignore`, GitHub Actions CI running
`./gradlew check` for both modules, and `docker-compose.yml` (placeholder). Wire
Hilt, base packages, and a health endpoint. Outcome: both modules build green in CI.

**Phase 1 — Research.** Runs 01–08 ✅ DONE (01–04, 07–08 opus; 05–06 alice); decisions in
`decisions.md` #9/#10/#15–25/#44 and §9. Net: Yandex Cloud via Terraform, **VPN dropped**,
Yandex-native booster; **run 07** locked the flavor-prompt setup (#44, gates output
*quality*, not structure); **run 08** resolved to **not adopt** web-grounding (#45 — its
"upgrade" verdict assumed the now-dropped DE VPN; §6 "no web crawling" stands). Other open
items are console verifications (Qwen3/DeepSeek availability + price; provider version).

**M1 — Offline app skeleton (no backend) — ships first.**
Boards list/CRUD → board screen with customizable tiers (rename/reorder/recolor/add-
remove; default S/A/B/C/D) → add a tea by typing a name (custom, local) → **drag to rank**
(Compose reorder spike first) + unranked tray → notes → **purchase location** (free-text +
marketplace URL) → Room persistence → **file export/import** (share sheet/SAF, bundles
photos — #26) → **settings** (theme/dark, language, export-import, about + privacy note,
#28) → first-run/empty states + a11y basics. ru UI. **Outcome: a private, fully offline,
usable tier-list app with a backup path — no backend required.** Turbine/MockK +
`createComposeRule`.

**M2 — Backend catalog service (parallel with M1).**
Flyway schema (§4a, incl. flavor/description/image) → seed job: **Wikidata (CC0)** tea
subtree (`?t wdt:P31/wdt:P279* wd:Q6097`, en/ru/zh + pinyin — verify QIDs) + the curated
~300-tea seed (start from gemini's 15-row mockup; GOST/GB-T refs) **incl. curated flavor
profiles, short blurbs, and CC/Wikimedia images** + OFF category taxonomy (isolated, ODbL)
→ monthly Wikidata re-sync → **search + detail API** (names + descriptions + flavor +
image) → `@DataJpaTest`/`@WebMvcTest` + Testcontainers → multi-stage Docker image →
**`infra/` Terraform → deploy to the Yandex Cloud VM**. **Never scrape Steepster/RateTea/
Moychay/Baidu** — read, author names ourselves. Outcome: a deployed read-only catalog API.

*Progress (3 PRs): **PR 1 ✅ persistence foundation** — Flyway `V1` §4a schema + JPA entities +
Testcontainers harness (decision #50). PR 2 = read API (§5 search/detail). PR 3 = curated static
JSON seed. (Live Wikidata SPARQL re-sync + OFF taxonomy + Docker/Terraform deploy stay later in M2.)*

**M3 — Catalog integration (needs M1 + M2).**
Wire the app to the catalog: **catalog search** in add-tea (Retrofit + `catalog_cache`,
offline reuse) → **tea card** (photo/placeholder + name/pinyin + type chip) → **detail
screen** (descriptions + "read full" + attribution) → name display = **ru + pinyin +
hanzi** → **search-miss → "add it / paste a description"** CTA → **attributions/licenses**
screen (CC-BY-SA/ODbL/CC images + per-item source links). Outcome: teas resolve from the
shared catalog; custom teas still work.

**M4 — Enrichment, flavor & photos (needs M3).**
Backend: `POST /teas/resolve` **enrich-on-miss** (Wikidata → YandexGPT Lite → Yandex-native
Qwen3/DeepSeek booster, logging off) + grounded **`sourceText`** + **upsert dedup** + per-IP
rate-limit + daily LLM ceiling (fails closed to Wikidata-only); drop in **research-07**
prompts; **benchmark Alice Flash** as a possible primary (#18). App: **optimistic add +
background/queued enrichment + `enrichmentState` indicator/retry** (#21/#28) → **"paste a
description"** field (#25) → **flavor** display (reference radar/bar) + **user override**
(quick-rate + "reference vs mine", #23) → **user photo** capture (#24). Outcome: the full
taste-card experience.

**M5 — Find & release (needs M4).**
Cross-board **"my teas"** search/filter (#27) + in-board filter by type/origin → en/zh UI
→ catalog **curation pass** (promote `unverified`→`verified`, fix bad AI transliterations)
→ **RuStore packaging** + signing via CI secrets → OWASP Dependency-Check → release
hardening (no debug logging, cert-pinning consideration). Outcome: release-ready MVP.

**M6 — Post-MVP (deferred).**
**Maps & geopoint** (research 02 / #20: a `LocationPickerProvider` = MapLibre + OpenFreeMap
+ on-device `Geocoder`→Photon/Nominatim, or Yandex MapKit *pick-only*; adds `GEOPOINT` +
`ACCESS_COARSE_LOCATION` behind an optional "use my location"; Google Maps never shipped,
#9) · **share a tier list as an image** (#27) · optional **cloud auto-sync** of the export
(Yandex Disk via SAF, no accounts — #26) · promote **Alice Flash** to primary if it
benchmarks better (#18).

## 8. Cross-cutting

- **i18n:** ru UI at MVP (`res/values-ru` as default + `values` fallback), en/zh
  UI later; tea **names** and **descriptions** carry ru/en/zh in the model;
  **flavor-dimension labels** (горечь/bitterness/苦味) are UI strings in `strings.xml`,
  keyed by the stable `tea_flavor.dimension` enum (#23).
- **Testing:** JUnit5 + MockK everywhere; Turbine (Flow), Compose UI tests on the
  app; Testcontainers Postgres + slice tests on the server.
- **CI:** GitHub Actions, pinned actions, `contents: read`, Gradle cache,
  `./gradlew check` (compile, test, detekt/ktlint, Android lint, `assembleDebug`).
- **Security (rule 50):** validate all API inputs; parameterized queries; no secrets
  in VCS; TLS only; treat geopoints as sensitive PII (store minimal, never log).
- **Privacy transparency (#1/#13):** the app is local-first, but *adding/resolving a tea
  sends the typed name (and any pasted `sourceText`) to our backend + Yandex AI*. Disclose
  this plainly in first-run + settings/about; send only the tea string, never a user
  identifier. (Notes, photos, tiers, ratings, and locations never leave the device.)
- **Data portability (#26):** local-first with no cloud → ship JSON **export/import** via
  the share sheet/SAF (no GMS, no accounts) as the backup/migration path. **The export
  bundles the user's photo files (e.g. a zip), not just `userPhotoUri` references** — else
  a restore on a new device loses photos. Android Auto Backup is unavailable on no-GMS
  RuStore devices, so don't rely on it.
- **Observability:** backend logs via slf4j (never PII / geopoints / notes). No GMS → no
  Crashlytics; app crash reporting, if wanted, uses a GMS-free option (ACRA or self-hosted
  Sentry) — optional for MVP.
- **Accessibility & states (#28):** dark mode, font scaling, TalkBack labels (esp. the
  flavor chart); design first-run/empty/error/offline states, not just the happy path.
- **Deploy (decision #18, run 05):** **Yandex Cloud via Terraform** (`infra/`) — one
  Compute VM running `docker-compose` (backend + **self-hosted Postgres**), `yandex_vpc_*`
  + security group, service account, **Container Registry**, **Lockbox** secrets, and
  **S3 remote state on Object Storage + YDB locking**. Managed PostgreSQL is the upgrade
  path. Pin the provider (verify; `0.206.0`), and use the **`terraform-mirror.yandexcloud.net`**
  mirror (registry.terraform.io may be blocked from RU). `Makefile` + GitHub Actions
  (`plan` on PR, `apply` on main) drive it. Image stays portable (still just compose).

## 9. Research & outcomes

| Run | Status | Outcome | Feeds |
|-----|--------|---------|-------|
| `research/01-tea-databases/` | ✅ opus | Curated ~300-tea seed first; mirror only Wikidata (CC0) + Wikipedia (CC-BY-SA) + OFF taxonomy (ODbL, isolated); never mirror commercial/community sites; `tea`+`tea_name`+provenance schema; GB/T + GOST + Teatips.ru naming refs | §4a, M2 |
| `research/02-maps-geo-android/` | ✅ opus | MapLibre + OpenFreeMap + on-device/Photon geocode; Yandex demoted (free tier bans storing geocoder results); no Google Maps; pin-drop needs no permission | **M6 — deferred (#20)** |
| `research/03-ai-enrichment/` | ✅ opus (`opus-2`) | Wikidata→Wikipedia free verification spine; YandexGPT store+serve allowed (ToS cl. 4.1/3.15, logging off); Yandex Translate prose-only; Brave free tier gone; programmatic confidence (pinyin4j + transliteration litmus). *LLM order superseded by #16→#17 (run 04).* | §6, M4 `resolve` |
| `research/04-eu-egress-llm/` | ✅ opus | Gemini free tier is **EEA paid-only** (can't be primary). Final order: Wikidata→Wikipedia → **YandexGPT Lite** primary (direct, no VPN) → **Groq `qwen3-32b`** zh-booster via DE VPN → DeepSeek optional. *Superseded by #18: VPN dropped, booster now Yandex-native Qwen3/DeepSeek.* | §6, #17→#18 |
| `research/05-yandex-terraform/` | ✅ alice | Yandex Cloud via Terraform: VM + **self-hosted Postgres in compose** (Managed PG = upgrade), Lockbox, **S3+YDB state**, GH Actions, ≈700–1700 ₽/mo. RU gotcha: use `terraform-mirror.yandexcloud.net`. Provider verify (`0.206.0`) | §8 Deploy, decision #18 |
| `research/06-yandex-alice/` | ✅ alice | **Alice AI LLM** = distinct Foundation Models model (`aliceai-llm`, 64k; `aliceai-llm-flash` cheaper), same API/json_schema. Keep YandexGPT Lite primary, **benchmark Alice Flash** in M4. Bonus: Yandex hosts **Qwen3-235B/DeepSeek natively** → VPN dropped (#18) | §6, decision #18 |
| `research/07-flavor-prompt-tuning/` | ✅ opus | Calibrated 0–5 flavor prompts (zero-shot + grounded, #25): anchor rubric, ru system/user templates, strict `json_schema` (`$defs.dim`, inline if `$defs` rejected), ≤3 few-shot, injection hardening, multiplicative confidence gate, MAE≤1.0 eval. **Yandex caveats:** `json_schema` unconfirmed on Lite (keep `json_object` fallback), no Yandex-managed DeepSeek URI, native vs OpenAI-compat request shapes differ, Qwen3 thinking-mode ≠ structured output | §6 step 3, decision #44, #25/#23 |
| `research/08-ai-web-search/` | ✅ opus → **not adopted (#45)** | Revisited §6 under a DE-VPN premise that #18 retired. **Decision #45: keep "no web crawling".** No compliant path under #18's no-VPN/Yandex-native lock (Yandex Search ToS-blocked 2.7.4; Yandex LLMs no built-in search; Tavily/Gemini = the egress #18 removed). Durable findings kept for a future revisit: never store built-in `googleSearch`-grounded output (Gemini ToS); Tavily = only card-free search-only API; Wikidata weak on RU transliterations → user-pasted text is the right long-tail grounding | §6, decision #45 |

Full reasoning + the per-run **Discard** lists (unverified QIDs, conflicting SDK
version pins) are in each run's `RATING.md` — honor them before writing code.

## 10. Risks & mitigations

- **Tea data is sparse / English-only / non-redistributable** → confirmed; mitigated by
  curated ~300-tea seed + Wikidata CC0 backbone (decisions #10). Only the license-safe
  open core is mirrored; commercial/community sites are reference-only.
- **Maps stack risks (SDK version-pin conflicts, MapLibre-Compose maturity, no-GMS
  certification)** → **all deferred with the maps feature to post-MVP (#20)**; revisit in
  M6. Not on the MVP critical path.
- **No Google Play Services on RuStore devices** → avoid all GMS-dependent libraries
  (maps, location, push). No maps and no push in MVP, so this is a non-issue for MVP.
- **CJK/Cyrillic search quality in Postgres** → validate `pg_trgm` (and any CJK
  tokenization) during research 01 / M2 before committing the index design.
- **Compose drag-and-drop reorder** is fiddly → time-boxed spike at the start of
  M1 before building the full board UI.
- **AI enrichment hallucinates tea names/transliterations** → publish as `unverified`
  with a confidence score, prefer Wikidata (CC0) over the LLM, cross-check, and curate
  later (§6). Never present an enriched name as authoritative.
- **Western AI providers / VPN dependency** → eliminated (decision #18): all enrichment
  LLMs (YandexGPT + Yandex-native Qwen3/DeepSeek booster) are direct Yandex Cloud, no VPN.
  Residual: **verify Qwen3-235B / DeepSeek Flash are actually in the Yandex gallery + their
  price** before building; if absent, fall back to Groq-via-VPN (#17).
- **Single-provider concentration on Yandex Cloud** (compute + DB + AI) → accepted for a
  hobby-scale RU-first app; mitigated because the image stays portable (compose) and the
  off-Yandex pieces (free Wikidata/Wikipedia, the client app) aren't locked in. Managed PG
  and a second host remain available if needed.
- **Always-on VM cost (~700–1700 ₽/mo) runs even at zero traffic** → reviewed and
  **accepted (#19)** for operational simplicity over scale-to-zero serverless; the 4,000 ₽
  grant covers the early months. Revisit serverless only if idle cost becomes a concern.
- **Optimistic enrichment = brief eventual consistency** → a freshly-added tea shows the
  user's typed name for a moment, then names/metadata patch in when `/resolve` returns
  (or stay as typed if offline). Acceptable and clearer than a blocking spinner (#21);
  make the patch non-jarring (don't reorder/refocus) and keep names editable.
- **AI-estimated flavor profiles are subjective guesses** → catalog flavor is a
  *reference* only, flagged `unverified`, and the **user can override** with their own
  ratings (#23). Don't present an AI flavor profile as fact; prefer the user's own once set.
- **Catalog image sourcing/licensing effort** → only CC/Wikimedia images for seed teas
  (curation work) + the user's own photo + a type placeholder; **no auto-fetched web
  images** (#24). Most teas may show a placeholder until curated or the user adds a photo —
  acceptable.
- **Pasted vendor text is untrusted + copyrighted (#25)** → injection-harden it (data not
  instructions, delimiter-wrapped, length-capped, HTML-stripped, output schema-validated)
  and **never store/republish the raw prose** — derive a structured profile + an original
  paraphrase only. Prompt tuned in research 07; grounded ≠ verified (still user-overridable).
- **Device loss = total data loss** (local-first, no accounts, no working auto-backup on
  RuStore) → mitigated by **MVP file export/import** via share sheet/SAF (#26); prompt new
  users to export, and offer optional cloud auto-sync later. The single biggest UX risk if
  left unaddressed.
- **Silent enrichment failure** (optimistic background calls) → tracked via
  `user_tea.enrichmentState` with a visible status + manual retry (#28); never leave a tea
  stuck "loading" with no recourse.
- **Terraform provider version / RU reachability** → verify the pin (`0.206.0`) on the
  Registry; use the `terraform-mirror.yandexcloud.net` mirror from RU (run 05).
- **Free-tier quota exhaustion / abuse of the write-capable `resolve` endpoint** →
  per-IP rate limit, global daily LLM ceiling that fails closed, input caps, dedup cache,
  prompt-injection isolation (§6).
- **Provider ToS forbidding stored output** → cleared for YandexGPT (cl. 4.1/3.15 allow,
  logging off); all enrichment LLMs are now Yandex Foundation Models (#18). Gemini
  excluded (EEA paid-only).

## 11. Open items (decide at build time — not blockers)

- **Import strategy:** merge vs replace when importing a backup onto a device that already
  has data (default: ask the user; or merge by stable ids).
- **Delete a tier that has teas:** its teas fall back to the unranked tray (sensible
  default; confirm in the tier editor).
- **Catalog snapshot staleness:** `user_tea` snapshots names at add-time; if the catalog
  later improves a tea, the board won't update. Acceptable (your tea = your snapshot);
  optionally offer a "refresh from catalog" action on the detail screen.
- **API versioning:** keep `/api/v1` backward-compatible; add a min-supported-app-version /
  graceful-degradation path since shipped APKs (RuStore/sideload) lag the backend.
- **Public-endpoint hardening:** beyond per-IP rate limits, consider a static app-shared
  secret in `BuildConfig` (not truly secret on RuStore, but raises the bar vs anonymous
  scripts hitting the write-capable `resolve`).
- **Console verifications:** Qwen3-235B / DeepSeek Flash availability + pricing in the
  Yandex gallery — before **M4** (#18); the Terraform provider version — before **M2**
  (#18 / run 05).
- **Import the live VM into Terraform (M2):** a first `teatiers` VM + `teatiers-sg` +
  static IP `93.77.185.62` (`tea.macsia.fun`) were hand-created via `yc` to unblock DNS —
  `terraform import` them in M2 so IaC owns them (see `infra/README.md`).
- **Run 07 (flavor-prompt tuning)** ✅ resolved — artifacts + Yandex caveats locked in
  decision #44; they drop into §6 step 3 / the M4 prompt module (tunes quality, not
  structure).
- **Run 08 (ai-web-search) §6 disposition** ✅ resolved — decision #45: **not adopted**,
  §6 "no open-ended web crawling" stands (no compliant web-grounding path under #18's
  no-VPN/Yandex-native lock). Durable findings kept for a future revisit only.

## 12. Requirements coverage (vs `../task.md` / `../architecture.md`)

- task.md: add teas ✓, tiers ✓ (customizable), notes ✓, multilingual ru/en/zh ✓,
  integrate/mirror tea DBs ✓ (#10). **Geopoint (Google/Yandex/OSM) — intentionally
  deferred post-MVP (#20)**; MVP keeps the marketplace-link + free-text "where bought".
- architecture.md: 1 mobile (Android/Kotlin) ✓, Linux server ✓ (Yandex Cloud VM),
  DB ✓ (Postgres), Kotlin app+backend ✓. **"Minimal where needed"** — the MVP has grown
  rich (catalog + enrichment + flavor + photos + export + my-teas); see the sequencing note.
