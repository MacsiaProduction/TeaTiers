# TeaTiers — Run 20: How to Scrape RU Tea Websites to Populate the Catalog

## TL;DR
- **Scrape artoftea.ru first** (server-rendered OpenCart, clean Cyrillic + transliterated-Chinese tea names, region/cultivar/oxidation and a 0–5 flavour breakdown baked into product pages) with a tiny Python stack (httpx 0.28.1 + selectolax 0.4.10 + pypinyin 0.55.0), parse to JSON, dedup with a pinyin-canonical normalized key, and bulk-import via the existing seed path. tea.ru is **off the table for now** because its live robots.txt disallows `/catalog/`, `/product/`, `/products/` for all user-agents.
- **Ship facts only.** Tea names (ru/zh/pinyin), type, origin/region, cultivar, oxidation, vendor are low-risk and wanted. Verbatim vendor descriptions and photos are copyright-protected expression — store scraped prose **server-side only** in a `source_text` column as enrichment input, never in the shipped catalog; the existing YandexGPT enrichment (decision #22) generates our own RU blurb + flavour profile.
- **Operating model: one-off bulk imports, not a live crawler.** Run the scraper on your local box, drive the source priority list from the `catalog_miss` log (decision #116 — scrape the most-wanted misses first), import as verified=false, QA, then flip verified=true. The 4 GB Yandex VM stays for backend + enrichment only.

## Key Findings

**1. The best RU source is a specialist vendor, not a marketplace.** artoftea.ru exposes exactly the factual fields the schema wants, in clean server-rendered HTML, with a transliterated-Chinese naming convention that maps directly onto `tea_name` locales and a built-in flavour breakdown that maps onto `tea_flavor`. Marketplaces (Ozon, Wildberries) are larger but JS-heavy, bot-hostile, and their tea taxonomy is polluted with gift sets, accessories and noise.

**2. robots.txt verified live, and it changes the ranking.** I fetched the actual files:
- **tea.ru/robots.txt (VERIFIED):** the `User-Agent: *` block contains `Disallow: /catalog/`, `Disallow: /product/`, `Disallow: /products/`, `Disallow: /search/`. The product catalog is explicitly disallowed for all bots. Given your "no hard line except redistribution" stance you *could* still fetch it, but it is the one candidate that explicitly tells all crawlers to stay out of the catalog — so I rank it low and recommend skipping it.
- **artoftea.ru:** the live `robots.txt` could not be fetched in this run (the fetch tool refused the URL as not-yet-seen). This is ASSUMED-permissive pending a one-line check; the product/catalog pages themselves are fully reachable and server-rendered (VERIFIED by fetching two of them). **Action before first scrape: fetch https://artoftea.ru/robots.txt and confirm `/puer/`, `/oolong/` etc. are not disallowed.**
- **Ozon:** `www.ozon.ru/robots.txt` exists (VERIFIED via search index) and is dominated by `Clean-param` directives; a direct fetch was refused with "Site disallows automated access" — Ozon actively blocks automated fetching and uses heavy anti-bot. Treat as hostile.
- **Wildberries:** the storefront `robots.txt` was not cleanly retrievable; WB exposes a **public storefront JSON API** (`card.wb.ru/cards/detail?nm=<id>`, `catalog.wb.ru/...`) widely used by open-source parsers, which is far easier than HTML scraping if WB is ever needed.

**3. The cross-script dedup problem has a real library answer — with a catch.** `pypinyin` 0.55.0 (MIT) has a built-in `Style.CYRILLIC` that emits Palladius (палладица) syllables: 大红袍 → да хун пао = "Да Хун Пао", 铁观音 → те гуань инь = "Те Гуань Инь". **Catch (verified via source/test inspection):** it keeps the tone digit (pypinyin's own test suite shows `pinyin(..., CYRILLIC)` returning forms like `['ао2']` and the docs give `中国 → чжун1 го2`, `pinyin('中心', style=Style.CYRILLIC) == [['чжун1'], ['синь1']]`), it transliterates syllable-by-syllable with no word merging (普洱 → "пу эр", not the vendor form "Пуэр"), and applies no Palladius composite/exception rules. So pypinyin is the *bridge* between Hans and Cyrillic, but the dedup key must be built from tone-free **plain pinyin** (`lazy_pinyin('中心') == ['zhong','xin']`), with a small exceptions table layered on top.

**4. The legal line is clean and well-established.** Facts (names, type, region, cultivar, oxidation, vendor) are not copyrightable. Vendor descriptions and photos are protected by copyright. A *collection* can attract a database right — EU sui generis (Directive 96/9/EC) and Russia's own equivalent, Civil Code Part IV Art. 1334 ("изготовитель базы данных"), which presumes protection when a DB has ≥10,000 elements. The safe rule: copy facts, regenerate expression, keep attribution.

**5. All recommended library versions exist and were verified on PyPI (June 2026).** No fabricated pins.

## Details

### Question 1 — Sources, ranked for a RU-first tea catalog

| Rank | Source | Structured factual data exposed | Rendering | robots.txt posture | Rough size | Taxonomy quality |
|---|---|---|---|---|---|---|
| **1** | **artoftea.ru** | Cyrillic name + transliterated-Chinese name, type (шу пуэр / шэн / улун / красный / белый / жёлтый / хэй ча), region-of-harvest (e.g. "горы Буланшань, уезд Мэнхай, Сишуанбаньна, Юньнань"), harvest year, format, **0–5 flavour facets** (Фруктовый/ягодный, Сладкий/кондитерский, Ореховый/пряный, Древесный, Цветочный, Травянистый, Минеральный/копчёный, Почвенный), "Воздействие" (effect) | **Server-rendered HTML** (OpenCart; clean slug URLs `/puer/shu-puer/<slug>`; `?page=N` pagination) — VERIFIED | ASSUMED permissive (must verify root) | ~100+ teas (their own line ~38; full shop 100+ sorts) | **Excellent** — flavour facets map 1:1 onto `tea_flavor`; region strings are precise |
| **2** | **moychay.ru** | Large Chinese-tea catalog with Cyrillic + transliterated names, type, often Hans + province in the title | Catalog at `/catalog/main`; rendering ASSUMED server-side (Bitrix-style) — NOT individually verified this run | ASSUMED; verify root | **~10,000 positions** (self-stated) | Good; broader and noisier than artoftea but the biggest specialist vendor |
| **3** | **RU tea encyclopedias/wikis** (chaochay.ru "Краткий словарь названий китайского чая"; yunnancraft.com/ru Chinese tea dictionary; daochai.ru tasting glossary; ru.wikipedia/ruwiki) | **Hans ↔ pinyin ↔ Cyrillic gloss tables** for canonical tea names and tasting terms — ideal to seed a controlled vocabulary / exceptions table | Server-rendered articles | Generally permissive | Tens–hundreds of canonical terms | Curated, authoritative for *canonical* names — use as a reference dictionary, not a product feed |
| **4** | **Yandex Market product pages** | Product title, brand, specs | JS-rendered, anti-bot | Hostile | Large | Noisy |
| **5** | **Wildberries** | Title, brand, description, characteristics via **public JSON API** `card.wb.ru/cards/detail?nm=<id>` | API returns JSON (no HTML scrape needed) | Storefront bot-hostile; API is an undocumented public endpoint | Huge | Very noisy for tea; mostly mass-market |
| **6** | **Ozon** | Title, specs | JS-rendered; `composer-api` backend | **Actively blocks bots** (direct robots fetch refused) | Huge | Very noisy |
| **—** | **tea.ru** | Would be good (large RU tea+coffee retailer) | Bitrix, server-rendered | **`Disallow: /catalog/`, `/product/`, `/products/` for `User-Agent: *` — VERIFIED** | Large | **Excluded** — explicitly disallows the catalog for all crawlers |

**Verified vs assumed, explicitly:**
- VERIFIED by fetching: tea.ru robots.txt directives; artoftea.ru category page (`/chay-ot-art-of-tea`) and product page (`/puer/shu-puer/cherny-tigr-2023-g`) are server-rendered with full facts in the HTML; artoftea flavour-facet and region structure.
- VERIFIED via search index: Ozon robots.txt is Clean-param-heavy and Ozon refuses automated fetches; WB public card API endpoint shape; pypinyin CYRILLIC behaviour (via source/test inspection).
- ASSUMED (verify before scraping): artoftea.ru robots.txt root; moychay.ru rendering + robots.txt.

**Why artoftea wins:** its product page literally contains a `Taste` field with comma-separated facet labels ("Древесный,Сладкий; кондитерский,Ореховый; пряный,Фруктовый; ягодный") and a precise `Регион сбора чая` string. That is a near-perfect source for `tea_flavor` (0–5 dims) and `tea.region` without any inference.

### Question 2 — Scrape + parse tooling (pinned, verified on PyPI, June 2026)

**Recommended least-heavy stack (server-rendered sites — covers artoftea + moychay):**

| Library | Version (verified PyPI) | License | Role |
|---|---|---|---|
| **httpx** | **0.28.1** | BSD-3-Clause | HTTP client (sync is fine; HTTP/2 capable). Latest stable line. |
| **selectolax** | **0.4.10** (rel. 26 May 2026; ships CPython 3.14 incl. free-threaded `cp314t` wheels across manylinux/musllinux/macOS/Windows) | MIT | Fast CSS-selector HTML parsing via Lexbor. ~10× lighter/faster than bs4+lxml; ideal on 4 GB. |
| **pypinyin** | **0.55.0** | MIT | Hans → pinyin (`Style.NORMAL`) for dedup keys; Hans → Cyrillic (`Style.CYRILLIC`, Palladius) to fill missing display locales. |
| **selectolax-based custom spider** | — | — | A ~150-line loop: sitemap/category → product URLs → parse → JSON. No framework needed. |

**Politeness libraries:**
- `urllib.robotparser` (stdlib, zero-dep) to honor robots.txt — sufficient. (`reppy` is an option but adds a dependency and has had maintenance gaps; stdlib is enough for two vendors, and I could not pin a single canonical current `reppy` version, so I deliberately avoid recommending it.)
- Manual rate-limit: 1 request / 2–5 s with jitter + exponential backoff on 429/5xx. httpx has timeouts; add a simple retry wrapper or `tenacity` (Apache-2.0/BSD).
- Caching/incremental: persist `{url: etag/last_modified, content_hash}` in a SQLite file and skip unchanged pages (the least-heavy option). `hishel` (httpx cache transport) is a heavier alternative.
- Identifying User-Agent: set something honest, e.g. `TeaTiersBot/0.1 (+contact)`.

**When to escalate to a real crawl or a browser:**
- **Scrapy 2.16.0** (BSD, rel. 19 May 2026) only if you later want many sources with built-in throttling (`AUTOTHROTTLE`), dedup, and resume. Overkill for 1–2 server-rendered vendors; fine on 4 GB but heavier than needed.
- **Playwright 1.60.0** (Apache-2.0, rel. 18 May 2026) + optional `scrapy-playwright` 0.0.47 (Jun 2026) **only** for JS-rendered/anti-bot sites (Ozon, Yandex Market, WB storefront). A headless Chromium is heavy on a 4 GB VM — so **run any Playwright work on your local box, never the VM.** For WB specifically, prefer the JSON API over a browser.
- bs4 (`beautifulsoup4` 4.15.0, MIT) + `lxml` 6.1.1 (BSD) are the well-known alternative; selectolax is recommended instead for memory/speed. All pins verified to exist on PyPI.

**Least-heavy stack that gets the job done on 4 GB:** httpx + selectolax + pypinyin + stdlib robotparser + a SQLite cache. No Scrapy, no browser. (And run scraping locally anyway — see Q5.)

**Anti-fabrication note:** every version above was checked against PyPI release listings dated 2025–2026: `selectolax 0.4.10` (May 2026), `scrapy 2.16.0` (May 2026), `playwright 1.60.0` (May 2026), `httpx 0.28.1`, `beautifulsoup4 4.15.0` (Jun 2026), `lxml 6.1.1`, `pypinyin 0.55.0`, `scrapy-playwright 0.0.47` (Jun 2026). The only thing I could not pin is a single canonical "reppy" current version — hence the stdlib recommendation.

### Question 3 — Data model mapping, dedup, idempotent import

**Field mapping (artoftea example → schema):**

| Scraped field | Target |
|---|---|
| Cyrillic product name ("Шу пуэр Чёрный Тигр") | `tea_name(locale='ru', name=..., is_primary=true, source='artoftea.ru', source_url=...)` |
| Transliterated-Chinese tokens in name ("Да Хун Пао") | `tea_name(locale='pinyin' or 'ru', ...)`; canonical pinyin via pypinyin if Hans known |
| Hans (from encyclopedia match or if present) | `tea_name(locale='zh-Hans', ...)` |
| Type ("шу пуэр") | `tea.type` (normalized to a controlled enum) |
| "Регион сбора чая" string | `tea.region` (+ `tea.origin_country='China'`) |
| Cultivar (when named, e.g. "гу шу", da bai) | `tea.cultivar` |
| Oxidation (derive from type: шэн/green=low, hun cha/red=full, etc.) | `tea.oxidation` |
| Brand / vendor | `tea.brand` and/or record `source` |
| Flavour facet labels | `tea_flavor` 0–5 dims (map their 8 facets onto your 6; presence→nonzero) |
| **Vendor description prose** | **`source_text` (server-side only) → enrichment input**, NOT `tea_description` shipped output |
| Vendor photos | **not stored in `tea_image` for shipping** (see Q4) |

**Normalized dedup key (the core of cross-source matching).** Build `dedup_key` so that "Да Хун Пао", "Da Hong Pao", and "大红袍" collapse to one tea:
1. If Hans is known → `lazy_pinyin(hans, style=Style.NORMAL)` → join → lowercase, strip spaces → `dahongpao`. This is the **canonical key** (NORMAL is tone-free: `lazy_pinyin('中心') == ['zhong','xin']`).
2. If only Cyrillic is known → normalize: lowercase, strip tone digits, strip spaces/punctuation/quotes, drop format/year tokens ("2023 г.", "блин 100 гр") → then map to canonical via a **Palladius↔pinyin lookup table** seeded from the encyclopedia dictionaries (chaochay/yunnancraft). Where a direct table hit exists ("да хун пао" → `dahongpao`), use it; else keep the normalized Cyrillic as a provisional key.
3. If only pinyin is known → same normalization (strip tones/spaces/case).

Store `name_norm` (you already have a generated column + pg_trgm GIN index) and use **trigram similarity** as the fuzzy fallback: on import, compute the canonical key; if no exact `dedup_key` match, run `name_norm % candidate` (pg_trgm) to surface near-duplicates for the operator to confirm. This catches transliteration variance (Цзинь Цзюнь Мэй vs Цзин Цзюнь Мэй) that exact keys miss.

**Important pypinyin caveat (verified):** because `Style.CYRILLIC` keeps tone digits (`中国 → чжун1 го2`; `pinyin('中心', CYRILLIC) == [['чжун1'],['синь1']]`) and doesn't merge syllables or apply exceptions, do NOT use its Cyrillic output *as* the key. Use pypinyin's **plain pinyin** (`Style.NORMAL`) for the canonical key, and use `Style.CYRILLIC` only to *generate a Cyrillic display name* when one is missing. Maintain a small hand-curated exceptions map. Two concrete classes of exception to seed it (per Wikipedia "Cyrillization of Chinese"): (a) **conventional name spellings** — Palladius writes Beijing/Nanjing as "Пекин (instead of Бэйцзин) and Нанкин (instead of Наньцзин)"; the merged tea-word "Пуэр" rather than "пу эр" is the same kind of convention; (b) **composite coda rule** — "coda ng is transcribed нъ when the following syllable starts with a vowel… Chang'an and Hengyang are transcribed as Чанъань and Хэнъян," which a per-syllable table will not produce. The RU encyclopedias give you the seed list for tea-specific cases.

**Multi-source merge / conflict resolution:**
- One `tea` row per `dedup_key`; multiple `tea_name`/`tea_description`/`tea_image` rows each carry their own `source`/`source_url`.
- Field-level precedence for scalar `tea` fields: **curated seed > Wikidata > vendor scrape > marketplace**. Keep `verified` flag; operator-curated wins.
- For region/cultivar conflicts between sources, prefer the more specific non-null value and keep the other in a provenance note; flag genuine contradictions for QA rather than silently overwriting.

**Idempotent re-import:** upsert on `dedup_key` for `tea`; upsert child rows on `(tea_id, locale, source)` natural keys (and `(tea_id, source_url)` for images). Re-running the same scrape produces zero net change. Store a per-source `content_hash` so unchanged pages are skipped and unchanged rows aren't rewritten.

### Question 4 — Facts vs copyrighted expression (the redistribution line)

**Ship in the public APK (low risk — these are facts):** tea names (ru/zh-Hans/pinyin), type, origin_country, region, cultivar, oxidation, brand/vendor, harvest year, and the *structured* flavour dimensions (the 0–5 numbers — facts/measurements, not prose). Keep `source`/`source_url` for attribution.

**Do NOT ship verbatim (high risk — protected expression):**
- **Vendor descriptions** — the marketing prose ("Сначала мы выбрали топовое моносырьё со старых чайных деревьев…") is an original literary work, copyright of the vendor.
- **Vendor photos** — original photographs, copyright of the vendor (this is also why decision #24 existed).

**Concrete policy (makes the clean path the easy path):**
1. Add a **server-side-only** `source_text` field (on a staging/enrichment table, never exported to the APK bundle). Scraped prose lands here purely as LLM input.
2. The existing enrichment tier (decision #22) reads facts + `source_text`, and **YandexGPT generates our own RU blurb + flavour profile** → that goes into `tea_description`/`tea_flavor` as shipped output. Prefer Wikidata (CC0) facts first, LLM second — unchanged.
3. **Never** populate `tea_image` from a scraped vendor URL for shipping. Keep decision #24's curated-CC/Wikimedia image rule for the *public* catalog. (You may store a `source_image_url` server-side for reference only.)
4. A build-time check should **fail the APK build if any shipped `tea_description.source` is a scraped vendor** or any `tea_image` points at a vendor domain — this is the "flag anywhere copied expression would enter the shipped product" guardrail.

**Legal mechanisms (short, real, sourced):**
- **Copyright on expression, not facts:** descriptions and photos are protected; the underlying facts (a tea's name, region, cultivar) are not. (Standard idea/expression distinction; reflected in EU and RU law alike.)
- **EU sui generis database right** (Directive 96/9/EC): protects a database with "substantial investment in obtaining, verification or presentation of the contents" against extraction/re-utilization of a substantial part — independent of copyright. In *Ryanair v PR Aviation* (**CJEU, Case C‑30/14, judgment of 15 January 2015**) the Court held, at recital 39, that "it is clear from the purpose and structure of Directive 96/9 that Articles 6(1), 8 and 15 thereof… are not applicable to a database which is not protected either by copyright or by the sui generis right under that directive, so that it does not prevent the adoption of contractual clauses concerning the conditions of use of such a database." In plain terms: where data is **not** protected by copyright or the database right, the site can still restrict scraping **by contract** (its ToS).
- **Russia — Civil Code Part IV, Art. 1334** ("исключительное право изготовителя базы данных"): the database maker gets an exclusive right to extract and reuse the contents where creation required "substantial costs"; the law **presumes** substantiality for a database of **≥10,000 independent elements**. (Relevant to moychay's "10,000 positions" but not to your selective fact extraction.) Note: extracting an *insignificant part* is explicitly outside this right.
- **Site ToS:** the actually-enforceable lever in most scraping disputes (per Ryanair). Worth a glance at artoftea's `Пользовательское соглашение`.

**Usable rule (don't over-lawyer):** *Take facts, take a small/insignificant share of any one source's collection, regenerate all prose with your own LLM, never redistribute vendor photos or text, keep source/source_url attribution.* That keeps the public APK clean even though scraping itself accepts a gray area for this personal project.

### Question 5 — Integration + operating model

**One-off bulk import, not a standing service.** For a personal catalog seeded from 1–2 vendors, a live crawler is unjustified ops burden and more legal/exposure surface. Recommended loop:
1. **Run the scraper on your local box** (not the 4 GB VM): scrape → emit `catalog_import.jsonl` (one tea per line with all locales, facts, and `source_text`).
2. Import through the **existing seed path** (the same CSV/JSON → Postgres loader you already use), as `verified=false`.
3. **Enrichment** runs as today: Wikidata (CC0) fills facts; YandexGPT generates RU blurb + flavour from facts+`source_text`.
4. **QA**: operator reviews `verified=false` rows (spot-check dedup merges and region/type), flips `verified=true`. pg_trgm near-dupe candidates are surfaced for confirmation here.
5. Re-run later is idempotent (upsert on `dedup_key`).

**Coexistence with seed, miss-log, enrichment:**
- **Curated seed stays authoritative** — scrape never overwrites a verified seed field.
- **Drive source priority from `catalog_miss` (#116):** export the top unresolved `/resolve` queries, and scrape/curate those teas first. This turns scraping into a demand-driven backfill rather than a blind bulk dump — highest catalog value per request.
- **Enrichment tier unchanged** — scraping just adds a new fact source upstream of it and a `source_text` input.

**Where scraping runs:** local box for HTML scraping and any Playwright work; the VM only runs the Spring/Postgres backend + enrichment. A "pelican node" (a separate cheap worker) is optional and only worth it if you later automate periodic refreshes — not needed for the first cut.

**QA leverages the existing verified/unverified flag** — import unverified, promote after review. No new state needed.

### Question 6 — Recommendation + sequencing (decisive)

**First cut, in order:**
1. **Verify artoftea.ru/robots.txt** (one fetch). If `/puer/`, `/oolong/`, `/redtea/` etc. aren't disallowed, proceed.
2. **Build the minimal scraper:** httpx 0.28.1 + selectolax 0.4.10 + pypinyin 0.55.0 + stdlib `robotparser` + SQLite cache. UA = `TeaTiersBot/0.1`. 1 req / 2–5 s + backoff.
3. **Walk artoftea categories** (`/puer`, `/oolong`, `/redtea`, `/greentea`, `/whitetea`, `/yellowtea`, `/xej-cha-chernyj-chaj`) via `?page=N`; collect product slugs; parse each product for name, type, region, year, flavour facets, effect, and capture description prose into `source_text`.
4. **Normalize + dedup:** canonical key from pinyin (`Style.NORMAL`), tone/space/case-stripped; pg_trgm fuzzy fallback; seed the Palladius↔pinyin exceptions table from chaochay.ru / yunnancraft.com dictionaries.
5. **Import** as verified=false via the existing seed loader → **enrich** (Wikidata then YandexGPT) → **QA** → flip verified=true.
6. **Facts-only guard:** ship facts + structured flavour; keep `source_text` and any vendor image URL server-side; build-time check rejects shipped vendor prose/images.
7. **Then add moychay.ru** as source #2 (verify robots/rendering first) for breadth, deduping into the same keys. Keep marketplaces (WB via JSON API only) as a last-resort, demand-driven option for specific misses.

## Recommendations
- **Build now:** the httpx + selectolax + pypinyin local scraper against artoftea.ru, importing facts + `source_text` through the existing seed path as verified=false. This is small, runs off-VM, and yields the highest-quality RU tea facts available.
- **Decision to record:** *"Run 20 — Scraping enabled. Primary source artoftea.ru (server-rendered, verified). tea.ru excluded (robots.txt disallows /catalog/). Tooling: httpx 0.28.1 + selectolax 0.4.10 + pypinyin 0.55.0, run locally, one-off bulk imports. Dedup via pinyin-canonical key + pg_trgm. Facts ship; vendor prose/photos stay server-side as enrichment input only; YandexGPT generates shipped descriptions. Source priority driven by catalog_miss (#116)."*
- **Thresholds that change the plan:** if artoftea robots.txt disallows the catalog → fall back to moychay.ru. If you need >2 sources or periodic refresh → graduate to Scrapy 2.16.0 with AUTOTHROTTLE on a dedicated worker. If a needed source is JS-only (Ozon/Yandex Market) → Playwright 1.60.0 on the local box, or skip in favour of WB's JSON API. If the catalog ever approaches redistributing a large share of one source's collection → revisit the Art. 1334 / sui generis exposure (you're currently extracting only an insignificant, fact-only part).

## Caveats
- **artoftea.ru robots.txt was NOT fetched this run** (tool refused the unseen URL); permissiveness is assumed pending a one-line check. The product pages themselves are confirmed reachable and server-rendered.
- **moychay.ru rendering and robots.txt were not individually verified** — confirm before scraping.
- **pypinyin `Style.CYRILLIC` is Palladius at the syllable level but keeps tone digits, doesn't merge syllables ("пу эр" ≠ "Пуэр"), and applies no exception rules** — so use plain pinyin (`Style.NORMAL`) for keys and a curated exceptions table for display names.
- Marketplace public JSON endpoints (e.g. `card.wb.ru`) are **undocumented and unstable**; they can change or rate-limit without notice.
- Legal notes here are practical orientation, not legal advice; the project explicitly accepts the gray area and limits exposure by shipping facts only.
- WB/Ozon anti-bot posture means any marketplace scraping will likely require a browser and will be brittle — another reason to prefer specialist vendors.