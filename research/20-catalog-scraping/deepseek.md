# 20-catalog-scraping — Populating the Tea Catalog by Scraping RU Tea Sites


## 1. Sources — Ranked for a RU-First Tea Catalog

### Tier 1: Specialized Tea Retailers with Clean Product Feeds

| Source | URL | Structure | robots.txt | Est. Catalog Size | Taxonomy Quality |
|--------|-----|-----------|------------|-------------------|------------------|
| **Art of Tea** | artoftea.ru | Server-rendered (OpenCart/类似); product pages with `route=product/product` | Explicitly disallows `/*route=product/search`, but allows product detail pages | ~200–500 SKUs | High — Chinese teas with proper origin/region info |
| **H2O Company** | h2ocompany.ru | Server-rendered (1C-Bitrix); product cards with quick-view modals | Disallows `/search/`, `/compare/`, `/personal/`; allows product pages | ~200–400 SKUs | Medium — good type/origin data, some blends |
| **More Cha** | morecha.ru | Server-rendered; category-based catalog | robots.txt not found (assume permissive) | ~200–300 SKUs | Medium — decent origin data |

**Why Tier 1:** These are *tea-first* retailers with factual data (name, type, origin, region) in structured HTML. They have the highest signal-to-noise ratio for the fields TeaTiers needs. Art of Tea is the strongest candidate — it has been operating since 2012, has a clean sitemap (`sitemap.xml` referenced in robots.txt), and its category structure includes puer, oolong, black, green, white, and red teas.

### Tier 2: Generalist Marketplaces (Ozon, Wildberries, Yandex Market)

| Source | Structure | Anti-Bot | Catalog Size | Taxonomy Quality |
|--------|-----------|----------|--------------|------------------|
| **Ozon** | JS-heavy SPA; requires API or headless browser | Aggressive anti-bot | ~10k+ tea SKUs | Low — product titles are marketing-heavy |
| **Wildberries** | JS-heavy; public API available for sellers | Moderate anti-bot | ~10k+ tea SKUs | Low — minimal structured tea taxonomy |
| **Yandex Market** | JS-heavy; requires login to avoid CAPTCHA | Aggressive; captchas | ~5k+ tea SKUs | Low — seller-offer structure, not tea-centric |

**Why Tier 2 is lower priority:** Marketplaces have huge volume but poor *tea-specific* structured data. You get product names (often marketing-heavy), prices, and seller names — but cultivar, oxidation level, and region are rarely in structured fields. They also require headless browsers with stealth plugins, increasing operational complexity. Use these *only* for miss-log filling after Tier 1 is exhausted.

### Tier 3: Tea Encyclopedias / Reference Sites

| Source | Notes |
|--------|-------|
| **tea.ru** | Mostly a blog/media site, not a product catalog; `/catalog/` is disallowed in robots.txt |
| **tea-coffee.ru** | General info site with `/search.php` disallowed; low structured data |
| **Чайный гид** | App/guide, not a scrapeable web catalog |

**Verdict:** Not worth scraping for bulk catalog population.

### Ranking Summary

1. **Art of Tea** (artoftea.ru) — #1 pick: clean server-rendered HTML, tea-first catalog, sitemap available, permissive robots.txt for product pages.
2. **H2O Company** (h2ocompany.ru) — #2: solid secondary source, Bitrix-based with predictable URLs.
3. **More Cha** (morecha.ru) — #3: decent catalog, no robots.txt barrier.
4. **Ozon / Wildberries / Yandex Market** — fallback for miss-log demand only.
5. **Tea encyclopedias** — not recommended (low structured data).


## 2. Scrape + Parse Tooling — Pinned Stack

### Recommended Stack: Lightweight Static Scraper

```
Python 3.11+
httpx==0.28.1          (BSD-3-Clause) — async/sync HTTP client with HTTP/2 support
selectolax==0.3.27     (MIT) — fast HTML5 parser with CSS selectors
urllib.robotparser     (stdlib) — robots.txt parsing
tenacity==9.0.0        (Apache-2.0) — retry logic
loguru==0.7.3          (MIT) — structured logging
```

**Why this stack:**
- `httpx` outperforms `requests` for concurrent requests and supports HTTP/2
- `selectolax` is 3–5× faster than BeautifulSoup with lower memory footprint
- `urllib.robotparser` is stdlib — no extra dependency for robots.txt
- No headless browser needed for Tier 1 sites (server-rendered HTML)

**Politeness Strategy:**
```python
# robots.txt check before each domain
from urllib.robotparser import RobotFileParser
rp = RobotFileParser()
rp.set_url("https://artoftea.ru/robots.txt")
rp.read()
if not rp.can_fetch("TeaTiersBot", url):
    return  # skip

# Rate limiting
import time
time.sleep(1.0)  # 1 req/sec minimum
# Exponential backoff on 429/5xx
# Cache responses per session to avoid re-fetching
```

**User-Agent:** `TeaTiersBot/1.0 (+https://teatiers.app/bot)` — identifiable, non-spoofing.

**Caching:** Use `diskcache` (Apache-2.0) or `requests-cache` (BSD-3-Clause) to store HTML responses locally between runs.

**When to add Playwright:** Only if we move to Tier 2 (marketplaces). Then add:
```
playwright==1.49.0     (Apache-2.0)
playwright-stealth==1.0.0 (MIT)
```
But for Phase 1 — **don't**. Keep it simple.


## 3. Data Model + Dedup

### Field Mapping: Scraped → TeaTiers Schema

| Scraped Field | Source (Art of Tea example) | Target Table |
|---------------|----------------------------|--------------|
| Tea name (RU) | Product title h1 | `tea_name` (locale='ru', is_primary=true) |
| Tea name (ZH) | Often in product description or meta | `tea_name` (locale='zh-Hans') |
| Tea name (pinyin) | Often in product URL or meta | `tea_name` (locale='pinyin') |
| Type | Category breadcrumb (e.g., "Пуэр") | `tea.type` |
| Origin country | Product attributes / description | `tea.origin_country` |
| Region | Product attributes / description | `tea.region` |
| Cultivar | Product attributes / description | `tea.cultivar` |
| Oxidation | Product attributes / description | `tea.oxidation` |
| Brand | Site name / vendor | `tea.brand` |
| Source URL | Product page URL | `tea_name.source_url`, `tea.source_url` |
| Source name | "artoftea.ru" | `tea_name.source`, `tea.source` |

### Dedup Strategy

**Normalization key (existing #16):** `name_norm` generated column with:
- Lowercase
- Remove extra spaces
- Strip common suffixes ("чай", "tea", "сорт")
- pg_trgm GIN index for fuzzy matching

**Dedup workflow:**
1. For each scraped tea, generate candidate `name_norm` from RU name
2. Query existing `tea` table: `WHERE name_norm % candidate` (pg_trgm similarity > 0.7)
3. If match found → **merge**: add scraped data as additional `tea_name`/`tea` rows, flag conflicts
4. If no match → **insert** new `tea` + `tea_name` (RU primary)

**Conflict resolution:**
- **Type/origin/region/cultivar/oxidation:** Prefer existing curated data; if conflicting, keep both with `source` tracking, flag for manual review
- **Names:** Always add new names (different locales, different sources) — more names = better search

**Idempotent re-import:**
- Each scraped record gets a `source_url` + `source` composite key
- Before insert: `SELECT id FROM tea WHERE source_url = ? AND source = ?`
- If exists → update/merge; if not → insert
- Use `ON CONFLICT` in Postgres with a unique constraint on `(source_url, source)`

**Cross-source merge (Да Хун Пао / Da Hong Pao / 大红袍):**
- The `name_norm` fuzzy match catches these via pg_trgm
- Manual curation for edge cases (miss-log can surface these)


## 4. Facts vs Copyrighted Expression — The Public APK Line

### Safe to Store + Ship (Facts)
- Tea name (any language)
- Type (black/green/oolong/puer/etc.)
- Origin country, region
- Cultivar
- Oxidation level
- Brand/vendor name
- Source attribution (`source`, `source_url`)

These are **facts** — not copyrightable under Russian law (Article 1259 of the Civil Code protects *original works of authorship*, not facts).

### NOT to Ship in Public APK (Copyrighted Expression)
- Verbatim vendor descriptions (prose)
- Vendor photos/images
- Long-form tasting notes written by the vendor

### Concrete Policy

```
┌─────────────────────────────────────────────────────────────────┐
│  SCRAPE LAYER (runs locally or on VM)                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Raw HTML → extract:                                    │   │
│  │    • facts (name, type, origin, cultivar, oxidation)    │   │
│  │    • source_text (FULL vendor description) — stored    │   │
│  │      ONLY as enrichment INPUT, flagged raw=true        │   │
│  │    • image_url — stored ONLY as reference, NOT shipped │   │
│  └─────────────────────────────────────────────────────────┘   │
│                           ↓                                    │
│  ENRICHMENT LAYER (existing YandexGPT)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Input: facts + (optional) raw_text for context        │   │
│  │  Output: our OWN flavor profile (0-5) + short RU blurb │   │
│  │  License: CC0 (we generated it)                        │   │
│  └─────────────────────────────────────────────────────────┘   │
│                           ↓                                    │
│  PUBLIC APK (ships only)                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  • tea facts (names, type, origin, etc.)               │   │
│  │  • OUR generated flavor profile + blurb (CC0)          │   │
│  │  • source attribution (vendor name, URL)               │   │
│  │  • NO verbatim vendor descriptions                      │   │
│  │  • NO vendor images                                     │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

**Database flags:**
- `tea_description.is_llm_generated` (bool) — true for our generated descriptions
- `tea_description.is_raw_scraped` (bool) — true for verbatim vendor text (server-side only)
- `tea_image.is_shippable` (bool) — false for scraped images; only CC/Wikimedia images ship

**Attribution:** Always store `source` (vendor name) and `source_url` on every scraped record. This is good practice and satisfies moral rights attribution under Russian law (Article 1274).

**Legal reality check:** 
- **Russian copyright:** Product descriptions are copyrightable if original. Facts are not.
- **EU sui generis database right:** Protects *investment* in databases, not individual facts. TeaTiers is not reusing the *structure* of any vendor's catalog, just extracting facts.
- **ToS:** Some sites prohibit scraping in ToS. This is a personal project accepting gray-area risk; the mitigation is attribution + not redistributing copyrighted expression.


## 5. Integration + Operating Model

### Phase 1: One-Off Bulk Import (Recommended First Cut)

```
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│  Local machine   │───▶│  CSV/JSON export │───▶│  VM Postgres    │
│  (run scraper)   │    │  (facts only)    │    │  (import via     │
│                  │    │                  │    │   existing seed) │
└──────────────────┘    └──────────────────┘    └──────────────────┘
```

**Why local first:**
- 4 GB VM is tight for Python + headless browser; keep scraping off the VM
- Local runs are easier to debug, iterate, and QA
- CSV/JSON import uses the *existing seed path* — minimal new code

**Import command** (reuse existing seed infrastructure):
```bash
python manage.py import_tea_catalog --source artoftea --file scraped_teas.json
```

### Phase 2: Miss-Log Driven Scraping (Ongoing)

The miss-log (`catalog_miss`) already records unresolved `/resolve` queries【decision #116】. After bulk import:

1. **Periodic job** (daily/weekly) reads top N miss-log entries
2. For each miss, query configured sources (Tier 1 first)
3. If found → import, mark miss as `resolved`
4. If not found → log for manual curation

**Where it runs:** Local box or a lightweight CI runner (GitHub Actions) — not the 4 GB VM.

### QA / Verified Flag

- All scraped imports start with `verified=false`
- Manual curation (via admin UI or SQL) sets `verified=true`
- The public APK can filter `verified=true` initially, or show both with a badge

### Coexistence with Curated Seed

| Priority | Source | Action |
|----------|--------|--------|
| 1 | Curated/Wikidata seed | Never overwrite |
| 2 | Scraped facts | Add new teas, enrich existing with new names |
| 3 | LLM-generated descriptions | Overwrite only if newer/generated |

**Rule:** Curated data (verified=true) always wins conflicts. Scraped data fills gaps.


## 6. Recommendation + Sequencing

### One-Paragraph Decision

> **Start with `artoftea.ru` as the single source.** Build a lightweight Python scraper using `httpx==0.28.1` + `selectolax==0.3.27` with 1 req/sec politeness, robots.txt honoring, and disk caching. Extract only **facts** (name-ru, type, origin, region, cultivar, oxidation) — skip descriptions and images entirely. Export to JSON, then import via the existing seed path into Postgres with `source='artoftea.ru'` and `verified=false`. Run locally, not on the VM. Once the pipeline works, add `h2ocompany.ru` as a second source, then wire the miss-log to drive targeted scraping for the most-wanted teas. Keep vendor prose and images server-side as *enrichment input only* — never ship them in the public APK. This gives you 200–500 new teas in the first run with zero legal redistribution risk.

### Immediate Next Steps

1. **Build** `scraper/artoftea.py` — product list pagination + detail page parser
2. **Test** on 10 products, validate extracted fields
3. **Run** full catalog scrape (est. 1–2 hours with politeness)
4. **Import** via seed path → `tea` + `tea_name` tables populated
5. **Trigger** enrichment (YandexGPT) for new teas → generate descriptions + flavor profiles
6. **Verify** in APK that only facts + generated content appear
7. **Repeat** for h2ocompany.ru
8. **Wire** miss-log to scraper for demand-driven filling

### Pinned Versions Summary

| Package | Version | License | Purpose |
|---------|---------|---------|---------|
| Python | 3.11+ | PSF | Runtime |
| httpx | 0.28.1 | BSD-3-Clause | HTTP client |
| selectolax | 0.3.27 | MIT | HTML parsing |
| tenacity | 9.0.0 | Apache-2.0 | Retry logic |
| loguru | 0.7.3 | MIT | Logging |
| diskcache | 5.6.3 | Apache-2.0 | Response cache |
| psycopg2-binary | 2.9.10 | LGPL | Postgres import |