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
┌──── Yandex Cloud VM (Terraform-provisioned) — Kotlin + Spring Boot 4.x, JDK 21 ─────────┐
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
  **Typo tolerance ("several wrong symbols", task.md):** run 09 (#79) chose **in-Postgres `pg_trgm`**
  — an IMMUTABLE `f_unaccent` + a `tea_name.name_norm` generated column + a GIN `gin_trgm_ops` index +
  a `word_similarity`-ranked, threshold-gated query (ICU collation prereq); Meilisearch CE is the
  documented fallback. The live path is **still exact-substring `LIKE`** — the pg_trgm slice is the
  queued next backend build (see §9 / §11).
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
the zh-booster is simply **skipped** (stay on Wikidata + YandexGPT Lite) — Groq-via-VPN is
**not** a fallback, the VPN is dropped (#18 supersedes #17).* *M4 also benchmarks
**Alice AI LLM Flash** (`aliceai-llm-flash`, 64k, cheaper input) as a possible primary swap.*

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

*Progress ✅ M1 is feature-complete (offline, ru UI). Boards CRUD + board screen with
customizable tiers/tier editor (default S/A/B/C/D) → **Room 2.8.4** persistence, user-data only,
board-scoped (decision #34) → add/edit tea by typing → **hand-rolled drag-to-rank** + unranked
tray, no new dependency (decision #38) → notes → purchase location (free-text + URL) → **file
export/import** bundling photos via SAF (decision #49/#26) → **Settings** screen with DataStore +
per-app language + theme + About/privacy (decision #48/#28). **Pulled forward from later
milestones:** the cross-board **"Мои чаи"** view (#27, was M5; decision #47) and the **flavor
entry UI** across the full 11-dim vocabulary + local flavor radar/strip display (was M4; decision
#46). Tests = pure-JVM JUnit5 + MockK, no Robolectric yet (decision #37). **Not yet wired:** the
network layer (no Retrofit/`catalog_cache`) — that is M3. (Known cleanup: migrate the deprecated
`hiltViewModel()` import, decision #33.)*

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
Testcontainers harness (decision #50). **PR 2 ✅ read API** — §5 `/teas/search` (cursor-paged) +
`/teas/{id}` + `/teas/facets`, Criteria-based dynamic search, RFC-7807 errors (decision #51). **PR 3 ✅
curated static JSON seed** — 13 own-authored teas (ru/en/zh-Hans/pinyin + flavors + blurbs), idempotent
`CatalogSeeder` keyed on a shared `DedupKeys` normalizer, gated by `teatiers.seed.enabled` (decision #52).
**Deploy stack** ✅ `docker-compose` now runs server + self-hosted `postgres:16-alpine` (datasource from
env, DB port unpublished), verified end-to-end locally (Flyway + seed + API) (decision #54).
**Deployed** ✅ live on the Yandex Cloud VM via OpenTofu (imported VM/SG/IP + CR + puller SA + Lockbox)
behind Caddy auto-HTTPS at **https://tea.macsia.fun** (decisions #55–57). (Live Wikidata SPARQL
re-sync + OFF taxonomy are the only optional M2-data leftovers.)*

**M3 — Catalog integration (needs M1 + M2).**
Wire the app to the catalog: **catalog search** in add-tea (Retrofit + `catalog_cache`,
offline reuse) → **tea card** (photo/placeholder + name/pinyin + type chip) → **detail
screen** (descriptions + "read full" + attribution) → name display = **ru + pinyin +
hanzi** → **search-miss → "add it / paste a description"** CTA → **attributions/licenses**
screen (CC-BY-SA/ODbL/CC images + per-item source links). Outcome: teas resolve from the
shared catalog; custom teas still work.

*Progress ✅ **search foundation + add-tea search** (decision #59). Retrofit 3 + OkHttp 5 (BOM) +
kotlinx.serialization client behind `CatalogRepository` (network-first, offline-cache fallback);
base URL is a `BuildConfig.CATALOG_BASE_URL` (defaults to the live API, override via a Gradle
property). DTOs mirror the live contract verified against `https://tea.macsia.fun` (`type` =
uppercase enum, `primary` boolean, `zh-Hans`/`pinyin` locales). New `catalog_cache` Room table
(DB v4, destructive-migrate, pre-launch) keeps seen teas searchable offline. The Add-Tea screen
now has a debounced (≥2 chars, 300 ms) catalog search box; picking a result prefills
names/type/origin as **editable suggestions** (#21, never authoritative). MockWebServer repo
tests + ViewModel search tests added; lint + 126 unit tests green.*

*Progress ✅ **catalog detail** (decision #61). A search result's info action opens a **bottom
sheet** (not a back-stack destination, so the add form stays mounted and the in-progress search
is preserved) with the full reference card: CC image (Coil 3 `coil-network-okhttp`, reusing the
Retrofit OkHttp client via `SingletonImageLoader`), ru/pinyin/hanzi names, type + origin, a short
blurb with a "read full" expander, the reference flavor radar/strip, and source/license
attribution. `repository.detail(id)` is network-only (no cache: detail is reached only on an
explicit tap, so a miss offers retry, not a stale copy); unknown flavor axes are dropped and
intensities clamped to 0..5. "Использовать этот чай" prefills via the existing pick path. Repo +
ViewModel detail tests added; lint + 133 unit tests green; debug APK builds.*

*Progress ✅ **search-miss CTA** (decision #62). When the catalog has no match (or is
offline/errored), the result area now shows an "add «query» manually" button that carries the
typed text straight into the name field and routes focus there, instead of dead-ending — so a tea
the catalog doesn't know yet is one tap from being logged by hand. The "paste a description" half
is M4 enrichment (`/resolve` with `sourceText`), not this slice. The **tea card** item is already
covered by M1's `TeaCard` (photo/placeholder + name/pinyin + type chip + flavor strip) and **name
display = ru + pinyin + hanzi** is satisfied across the search rows, detail sheet, and card. **Still
open in M3:** the attributions/licenses screen (data-source + image credits with per-item links).*

*Progress ✅ **attributions/licenses screen** (decision #63) — **M3 complete**. A new
`Destination.Attributions`, reached from **Settings → About → "Источники данных и лицензии"**,
credits the open datasets the catalog draws from: **Wikidata** (CC0), **Wikipedia** (CC-BY-SA),
**Wikimedia Commons** (per-file CC), **Open Food Facts** (ODbL) — each with a one-line summary and
tappable links to the source site + license deed. Static, localized content (no API/state);
per-record and per-image links continue to live on the tea detail card. lint + unit tests green;
debug APK builds. **M3 is now feature-complete** — next is M4 (enrichment, flavor & photos).*

**M4 — Enrichment, flavor & photos (needs M3).**
Backend: `POST /teas/resolve` **enrich-on-miss** (Wikidata → YandexGPT Lite → Yandex-native
Qwen3/DeepSeek booster, logging off) + grounded **`sourceText`** + **upsert dedup** + per-IP
rate-limit + daily LLM ceiling (fails closed to Wikidata-only); drop in **research-07**
prompts; **benchmark Alice Flash** as a possible primary (#18). App: **optimistic add +
background/queued enrichment + `enrichmentState` indicator/retry** (#21/#28) → **"paste a
description"** field (#25) → **flavor** display (reference radar/bar) + **user override**
(quick-rate + "reference vs mine", #23) → **user photo** capture (#24). Outcome: the full
taste-card experience. *App-side note: the **local** flavor pieces already shipped in M1 — flavor
entry (full 11-dim) + the user's own radar/strip display (#46) and user photos. M4 adds the
**backend reference** profile, the "reference vs mine" split (#23), and the enrichment plumbing.*

*Progress ✅ **M4 slice 1 — `/resolve` Wikidata-first backbone** (decision #64, backend, PR off
`main`). `POST /api/v1/teas/resolve` now does §6 steps 1–2: normalize the typed name → **cache
hit** by unaccented name across locales (the dedup cache) → on a miss, a **Wikidata SPARQL** lookup
constrained to the `tea` (Q6097) `P31/P279*` subtree (so "Longjing" can't match the district) pulls
ru/en/zh-Hans labels + origin + category (→ `TeaType`) and **upserts an `unverified`, CC0,
high-confidence (0.9)** row guarded on `wikidata_qid`/`dedup_key` (idempotent under a concurrent-add
race); a full miss returns `UNRESOLVED` (creates nothing). Bean-validated request (name ≤200,
sourceText ≤4000 — `sourceText` accepted but reserved for the LLM tier), **per-client fixed-window
rate-limit** (429 problem+json), RFC-7807 errors. SPARQL query + the 11 tea-category QIDs were
**verified live** before coding. Tests: client parse/type-priority/query-shape, upsert guards,
resolve orchestration + race recovery, rate-limiter windows, and a Testcontainers IT (real
`unaccent` lookup + enrich + idempotency); full server suite green. **Yandex Foundation Models
access provisioned** (SA `teatiers-llm` + `ai.languageModels.user`; API key in Lockbox
`teatiers-llm-api-key`) to unblock the LLM tier.*

*Progress ✅ **M4 model bake-off** (decision #65, `research/08-model-bakeoff/`). Empirically picked
the enrichment-tier model(s) before building step 3: ran the run-07 prompt (rubric + few-shot +
strict json_schema) zero-shot over a 24-tea gold set on all 6 candidates at temp 0. All cleared
MAE ≤ 1.0 with 24/24 valid JSON. **Primary = Alice Flash** (cheapest, fastest, MAE 0.47 — beats
Lite, which is dropped); **zh booster = Qwen3-235B** (DeepSeek V4 Flash the strong alt, best MAE +
the only model immune to all injection attacks). Both OpenAI-compat → one `response_format:
json_schema` path. **Critical:** prompt-only injection defense failed (4/6 wrote "HACKED"), so the
run-07 code-side guards are mandatory. **Still open in M4:** zh-source + grounded gold sets;
promote prompts to `context/flavor-system/`; wire the server LLM tier (config-selectable modelUri,
Lockbox key) for §6 steps 3–4; then the app-side optimistic add / enrichment-state /
flavor reference-vs-mine / photos.*

*Progress ✅ **M4 slice 2 — async LLM enrichment tier** (decision #66, backend, branch off `main`).
`/resolve` now runs §6 step 3 on a Wikidata miss: it inserts a `PENDING` stub from the typed name and
returns **`ENRICHING`** at once, while a background `@Async` worker prompts the model (Alice Flash, or
the Qwen3 booster for Chinese source), **validates the JSON in Kotlin** (clamp 0–5, confidence gate,
shingle-overlap blurb drop, fail-closed on bad output — the #65 mandatory guards), and flips the row to
`DONE`/`FAILED`; the client polls `GET /teas/{id}` and can retry a `FAILED` row by re-resolving. Scope
is **miss-only** (no LLM on a Wikidata hit); the API key arrives as a **deploy-time env var** from
Lockbox, and a blank key keeps the tier off (miss → `UNRESOLVED`). Flyway **V2** adds
`enrichment_state`/`enrichment_error`; prompts promoted to `context/flavor-system/prompts.md` +
`FlavorPrompts.kt`. Tests: resolve orchestration + guards (unit) and stub→DONE / FAILED-reset over the
real schema (IT); full suite green. **Still open in M4:** zh-source + grounded gold sets; the app-side
optimistic add / `enrichmentState` UI / reference-vs-mine flavor / photos.*

*Progress ✅ **M4 app-side slice 1 — optimistic add + background enrichment + status/retry** (decision
#69, app). A freshly-added tea lands on the board instantly, then a background `/resolve`
(`TeaEnrichmentManager`, app-scope) patches in ru/zh names + metadata as **non-authoritative
suggestions** (#21). New **Room v5** fields `catalogTeaId` + `enrichmentState`
(`EnrichmentState{NONE,PENDING,QUEUED,DONE,FAILED}`); `CatalogApi.resolve` + `CatalogRepository.resolve`
(Matched / Enriching-poll / Unresolved / Offline→QUEUED / Error→FAILED, fail-closed). Dispatched only for
a **genuinely-new** tea (`addTea`→`AddedTea(created)`), never an auto-linked existing one. `TeaCard` shows
a muted "Уточняем…/В очереди" / error "Не удалось уточнить" hint; the board overflow offers
**"Повторить уточнение"** on FAILED; `resumePending()` re-runs stuck PENDING/QUEUED on launch (#28). 148
unit tests + lint + debug APK green. **Still open in M4:** the "paste a description" field (#25 — plumbed,
no UI yet), reference-vs-mine flavor (#23), zh-source/grounded gold sets.*

**M5 — Find & release (needs M4).**
~~Cross-board **"my teas"** search/filter (#27)~~ ✅ **shipped early in M1** (decision #47) +
in-board filter by type/origin → en/zh UI → catalog **curation pass** (promote
`unverified`→`verified`, fix bad AI transliterations) → **RuStore packaging** + signing via CI
secrets → OWASP Dependency-Check → release hardening (no debug logging, cert-pinning
consideration). Outcome: release-ready MVP.

**M6 — Post-MVP (deferred).**
**Maps & geopoint** (research 02 / #20: a `LocationPickerProvider` = MapLibre + OpenFreeMap
+ on-device `Geocoder`→Photon/Nominatim, or Yandex MapKit *pick-only*; adds `GEOPOINT` +
`ACCESS_COARSE_LOCATION` behind an optional "use my location"; Google Maps never shipped,
#9) · **share a tier list as an image** (#27) · optional **cloud auto-sync** of the export
(Yandex Disk via SAF, no accounts — #26) · promote **Alice Flash** to primary if it
benchmarks better (#18).

### 7.1 MVP release gate (first public APK)

The explicit checklist for "the first public/RuStore/sideload APK may ship" (from the
2026-06-17 architecture review; see `context/review/2026-06-17-disposition.md`). Until every
line is ✅ or a deliberate written waiver, the build stays internal-only.

- [ ] **Room migrations:** destructive fallback removed for future public schema changes;
  `exportSchema` on + a committed baseline; "public schema starts at vN" declared (open #70.1).
- [ ] **Backup fidelity:** export/import round-trips all current columns incl. the v5
  enrichment fields ✅ (#69) and photos ✅ (#26) — re-verify on each new column.
- [x] **Typo-tolerant catalog search** implemented + passes a ru/en/pinyin/zh search-gold set
  (run 09, #67) — ✅ pg_trgm `name_norm` + `word_similarity` (Flyway V4), `TeaSearchFuzzyIT` gold set green
  (#84). Not yet shipped to the live VM (next image runs V4).
- [ ] **Queued enrichment** is either durable (WorkManager) or the UI copy honestly says
  "runs only while the app is open."
- [ ] **`/resolve` contract** aligned with the client (async `ENRICHING` ✅ #66) + a **global
  daily LLM ceiling** that fails closed ✅ (#71).
- [ ] **LLM data-logging off** — `x-data-logging-enabled: false` header sent ✅ (#74); **verify** logging
  is actually disabled (header or folder-level) for the SA/folder before deploying a key (ToS, #32).
- [ ] **Dependency/security check** (OWASP Dependency-Check) wired, or deferred with a date.
- [ ] **Container registry** choice settled (`ghcr.io` vs YCR, #68) and a VM image pull verified.
- [ ] **Off-box DB backup** enabled, or local-only accepted in writing (open #70.3).
- [ ] **i18n:** `values-en`/`values-zh` shipped, or the picker copy makes ru-only explicit (open #70.5).
- [ ] **Release hardening:** no debug logging, signing via CI secrets, cert-pinning considered (M5).

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
- **Deploy (decision #18, run 05; tool = OpenTofu per #55):** **Yandex Cloud via OpenTofu** (`infra/`) — one
  Compute VM running `docker-compose` (backend + **self-hosted Postgres**), `yandex_vpc_*`
  + security group, service account, **Container Registry**, **Lockbox** secrets, and
  **S3 remote state on Object Storage + YDB locking**. Managed PostgreSQL is the upgrade
  path. Pin the provider (verify; `0.206.0`), and use the **`terraform-mirror.yandexcloud.net`**
  mirror (registry.terraform.io may be blocked from RU). `Makefile` + GitHub Actions
  (`plan` on PR, `apply` on main) drive it. Image stays portable (still just compose).
  **Planned (#68, task.md): move the image off Yandex Container Registry to `ghcr.io`** —
  retire the CR + puller SA, swap the cloud-init `docker login` to a ghcr token, and fold
  it into the still-open "build image in CI" item (GH Actions pushes to ghcr natively).
  Target M5 infra polish; settle ghcr reachability-from-RU + public-vs-token auth at build time.

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
| `research/09-typo-search/` | ✅ opus (#79) | **Typo-tolerant catalog search** → **in-Postgres `pg_trgm`** for ru/en/pinyin (no new always-on service, #19); Hanzi weak (accepted), Meilisearch CE the documented fallback if a gold set fails. Locked design: IMMUTABLE `f_unaccent` + `name_norm` generated column + GIN `gin_trgm_ops` + `word_similarity`-ranked query; ICU collation prereq. **Implementation queued** (not built) | §4a search, backend slice |

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
  price** before building the LLM tier; if absent, **skip the zh-booster** (stay on Wikidata +
  YandexGPT Lite) — Groq-via-VPN is **not** a fallback (#18 supersedes #17, VPN dropped).
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
  Yandex gallery — before **M4** (#18). ✅ Yandex provider version verified (`0.206.0`, #55).
- **Import the live VM into IaC (M2):** ✅ done (#57). The hand-created `teatiers` VM +
  `teatiers-sg` + static IP `93.77.185.62` (`tea.macsia.fun`) are now imported under
  OpenTofu; CR + puller SA + Lockbox were added (`0 destroyed`) and the Caddy+server+Postgres
  stack is live at **https://tea.macsia.fun** (see `infra/README.md`). Remaining infra polish
  (not blockers): move image build into CI (no local `buildx` under podman — built on the VM),
  pin base-image digests, wire GH Actions `plan`/`apply`.
- **Run 07 (flavor-prompt tuning)** ✅ resolved — artifacts + Yandex caveats locked in
  decision #44; they drop into §6 step 3 / the M4 prompt module (tunes quality, not
  structure).
- **Run 08 (ai-web-search) §6 disposition** ✅ resolved — decision #45: **not adopted**,
  §6 "no open-ended web crawling" stands (no compliant web-grounding path under #18's
  no-VPN/Yandex-native lock). Durable findings kept for a future revisit only.
- **Typo-tolerant search (#67, task.md)** ⏳ queued — current search is exact-substring
  `LIKE`; the fuzzy-index approach (pg_trgm similarity vs a dedicated engine) is decided by
  **research run 09** before any build. Not an M4 blocker.
- **Open image registry / ghcr (#68, task.md)** 📋 planned — move the server image off
  Yandex CR to `ghcr.io`, folded into the "build image in CI" infra item; target M5 polish.

## 12. Requirements coverage (vs `../task.md` / `../architecture.md`)

- task.md: add teas ✓, tiers ✓ (customizable), notes ✓, multilingual ru/en/zh ✓,
  integrate/mirror tea DBs ✓ (#10). **Geopoint (Google/Yandex/OSM) — intentionally
  deferred post-MVP (#20)**; MVP keeps the marketplace-link + free-text "where bought".
  **Typo-tolerant search index** ("several wrong symbols") ⏳ → research run 09 (#67);
  **open image registry** ("ghcr") 📋 → planned infra move (#68).
- architecture.md: 1 mobile (Android/Kotlin) ✓, Linux server ✓ (Yandex Cloud VM),
  DB ✓ (Postgres), Kotlin app+backend ✓. **"Minimal where needed"** — the MVP has grown
  rich (catalog + enrichment + flavor + photos + export + my-teas); see the sequencing note.
