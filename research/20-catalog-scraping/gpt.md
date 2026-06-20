# Executive verdict

Scraping is now worth doing, but **not as a live production crawler** and not as a way to copy a vendor catalog wholesale. The best TeaTiers design is a **facts-only acquisition pipeline**:

**specialist RU tea shops → local scrape cache → extracted factual observations → dedup/review queue → seed/import path → existing Wikidata + YandexGPT enrichment generates original descriptions.**

Do **not** start with Ozon/Wildberries/Yandex Market. Start with **Art of Tea + Daochai**, then add **Moychay** and **Cha-shop** once the dedup and staging model are stable. These sites expose useful factual tea fields in readable HTML; marketplace pages expose characteristics too, but are noisier, more duplicate-heavy, more bot-hostile, and more likely to mix seller SEO prose with weak taxonomy.

A key evidence note: I verified sample product/category pages for Art of Tea, Moychay, Cha-shop, and Daochai as readable HTML, and I found no `application/ld+json` on the sampled product pages, so the first parser should be HTML/CSS-selector based, not JSON-LD based. ([Art of Tea. Искусство Чая][1])

---

# 1. Ranked RU-reachable sources

## Source ranking

| Rank | Source                                                  | Use                                      | Why it ranks here                                                                                                                                                                                             | Verified structure                                                                                                                                                                                                                                                                     | Robots / crawl posture                                                                                                                                                                                                                                          | Rough size / quality                                                                                                                                                                 |
| ---: | ------------------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
|    1 | **Art of Tea**                                          | Primary specialist source                | Clean category taxonomy, good factual fields: Russian product name, tea type/category, country, region, year, product parameters. Strong first source for canonical tea varieties and Chinese tea categories. | Verified product page contains title, variants, region, harvest year, country, and name/parameter fields; verified sitemap/category tree with tea categories and subcategories such as oolong, puer, white, green, red, Wuyi, Dan Cong, Taiwan, GABA. ([Art of Tea. Искусство Чая][1]) | Exact robots posture was **not conclusively verified** in this run. Runtime robots preflight must be mandatory. Product/category HTML is reachable, but do not assume permission.                                                                               | Likely hundreds of tea/product pages. Taxonomy looks cleaner than marketplaces.                                                                                                      |
|    2 | **Daochai**                                             | Primary specialist source                | Good product/lot metadata, province/region hints, batch/year data, product codes, category pages. Useful for Wuyi/Taiwan/Chinese teas and taste-tag extraction as weak enrichment input.                      | Verified product page has title, breadcrumb/category, prose section, taste groups, batch/province fields; verified category listing exposes product titles, product codes, excerpts, prices, variants, availability. ([daochai.ru][2])                                                 | Exact robots posture was **not conclusively verified** from the source itself. Third-party search snippets suggested robots/sitemap existence, but treat that as insufficient. Runtime check required.                                                          | Likely hundreds of current/archived lots. Quality high, but product lots must not all become separate canonical teas.                                                                |
|    3 | **Moychay**                                             | Broad specialist source, second wave     | Very broad catalog, useful SKU/product IDs, taste tags, brewing parameters, harvest/year facts. Probably the largest specialist shop candidate.                                                               | Verified product page has title, SKU/article, stock/review block, brewing parameters, taste tags, and harvest/processing information. ([moychay.ru][3])                                                                                                                                | Exact robots posture was **not conclusively verified** in this run. Runtime check required.                                                                                                                                                                     | A third-party directory claims around 10,000 positions, but that should be treated as an unverified external estimate, not an official count. ([moychay.ru.sitescorechecker.com][4]) |
|    4 | **Cha-shop**                                            | High-quality specialist/reference source | Smaller, but useful for clean tea taxonomy, SKU, translated names, Wuyi/cultivar explanations, factory/processing facts. Good for validating canonical names and cultivar distinctions.                       | Verified product page has breadcrumb taxonomy, title, translated name, SKU, categories/tags, packaging variants, and cultivar/processing discussion. Homepage product sections are server-rendered. ([Магазин правильного чая][5])                                                     | Exact robots posture was **not conclusively verified** from source. Third-party snippets claim permissive robots for major crawlers, but do not rely on that. Runtime check required.                                                                           | Likely low hundreds. Lower breadth than Moychay, but cleaner editorial quality.                                                                                                      |
|    5 | **Tea.ru / TeaTerra / TeaTips / similar article sites** | Reference validation, not bulk catalog   | Useful for canonical names, pinyin/Chinese names, origin/type descriptions. Poor fit for bulk import because most value is prose, not structured factual product data.                                        | Search/opened results show encyclopedia/article pages about teas such as Da Hong Pao with names, transliteration, class/origin facts. ([tea.ru][6])                                                                                                                                    | Must check per site before crawling. For first cut, do not crawl broadly; use selected pages manually or as validation references.                                                                                                                              | Medium reference value, low structured catalog value.                                                                                                                                |
|    6 | **Wildberries / Ozon / Yandex Market / Megamarket**     | Later, limited alias/brand/demand source | Useful for mass-market brand names, seller aliases, pack sizes, and product characteristics; bad as canonical tea source due to duplicates, SEO prose, low taxonomy quality, and anti-bot friction.           | Search snippets show marketplace product characteristics such as tea type, form, origin country, brand, composition, and shelf-life. Yandex Market direct open reached a captcha page. ([wildberries.ru][7])                                                                           | Marketplace robots/captcha posture is more restrictive/fragile. Search snippets show Wildberries and Ozon robots blocking several catalog/category/search-style paths; Yandex also actively presents captcha to automated-looking access. ([wildberries.ru][8]) | Huge listing volume, but low canonical signal. Use only after specialist-source pipeline works.                                                                                      |

## Recommended source priority

**First cut: Art of Tea + Daochai.** They give the best balance of readable structure, specialist taxonomy, and manageable catalog size. Art of Tea is better for structured fields and category hierarchy; Daochai is better for lot metadata and taste-tag hints. ([Art of Tea. Искусство Чая][1])

**Second cut: Moychay + Cha-shop.** Moychay adds breadth; Cha-shop adds high-quality normalization clues around Wuyi/cultivar topics. Moychay should wait until the importer understands “vendor lot vs canonical tea”, because otherwise it will create many near-duplicate teas. ([moychay.ru][3])

**Marketplaces are not first-cut sources.** They are better for later miss-log driven lookups: “what do Russian users search for / what brand aliases exist?” rather than “what is the canonical tea catalog?” The direct Yandex Market fetch hitting captcha is enough reason not to design the MVP pipeline around headless browser marketplace scraping. ([Яндекс Маркет][9])

---

# 2. Scrape and parse tooling

## Least-heavy stack

Use a **small Python extractor**, not Scrapy at first.

| Component          |                      Pin | License      | Role                                  | Why                                                                                                                                                                                                                      |
| ------------------ | -----------------------: | ------------ | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **httpx**          |          `httpx==0.28.1` | BSD-3-Clause | HTTP client                           | Current PyPI metadata shows 0.28.1 and BSD-3-Clause; HTTPX supports sync/async HTTP, HTTP/1.1 and HTTP/2. ([Pypi Stats][10])                                                                                             |
| **selectolax**     |     `selectolax==0.4.10` | MIT          | Primary HTML parser                   | Current PyPI page shows 0.4.10, released May 26, 2026, and describes it as a fast HTML5 parser with CSS selectors. License metadata should be rechecked in lockfile; recent PyPI metadata/search lists MIT. ([PyPI][11]) |
| **lxml**           |            `lxml==6.1.1` | BSD-3-Clause | XML sitemap parsing + fallback parser | PyPI shows 6.1.1, released May 18, 2026, with BSD-3-Clause license. ([PyPI][12])                                                                                                                                         |
| **beautifulsoup4** | `beautifulsoup4==4.15.0` | MIT          | Debug/fallback parser only            | PyPI/search metadata shows 4.15.0 as current and MIT license. Use only where selectolax selectors are inconvenient. ([Уязвимости и исправления][13])                                                                     |
| **Scrapy**         |         `Scrapy==2.16.0` | BSD-3-Clause | Optional second-stage crawler         | Current PyPI/search metadata shows 2.16.0; Scrapy is useful once breadth, retries, throttling, caching, and crawl scheduling matter. ([PyPI][14])                                                                        |
| **Playwright**     |     `playwright==1.60.0` | Apache-2.0   | Last-resort JS/browser probe          | Current PyPI metadata shows 1.60.0 and Apache-2.0. Use only for JS-rendered/captcha-heavy pages; do not run it as the default path on the 4 GB VM. ([PyPI][15])                                                          |

## Why not start with Scrapy?

Scrapy is appropriate once you crawl thousands of pages across many domains. For the first TeaTiers import, the job is narrower:

1. fetch category pages;
2. extract product links;
3. fetch product pages;
4. parse a dozen known fields;
5. write JSONL;
6. review and import.

For that, `httpx + selectolax + lxml` is easier to audit, easier to run locally, and easier to keep facts-only.

## Why not start with Playwright?

Playwright is useful for marketplace probes, but it is operationally heavy and increases the chance of accidentally building an arms race with anti-bot systems. The specialist tea pages I sampled are readable without browser automation. Save Playwright for **manual diagnostics** or later marketplace experiments, not the core pipeline. ([Art of Tea. Искусство Чая][1])

## Robots and politeness

Implement robots handling as a hard gate. The Robots Exclusion Protocol is standardized in RFC 9309, and Python’s standard `urllib.robotparser.RobotFileParser` exists specifically to answer whether a given user-agent can fetch a URL. ([RFC Editor][16])

Recommended crawler defaults:

```text
User-Agent:
TeaTiersCatalogBot/0.1 (+contact email; facts-only personal catalog; respects robots; no image/prose redistribution)

Per-domain concurrency:
1

Delay:
2-5 seconds jitter between requests

Timeouts:
connect=5s, read=10s, total=20s

Retries:
max 2 for 408/429/500/502/503/504
no retry for 403/captcha/robots-disallowed

Backoff:
30s, then 5min, then stop source for the run

Stop conditions:
captcha page detected
403 on product/category path
robots disallow
unexpected login wall
HTML layout mismatch above threshold
```

HTTPX has explicit resource-limit and timeout support, including connection limits and default timeout behavior, so use that instead of ad hoc socket settings. ([Python Httpx][17])

## Caching and incremental rescrape

Use a local SQLite crawl database plus raw HTML cache:

```text
.cache/scrapes/
  artoftea/
    pages/
      sha256(url).html.zst
    metadata.sqlite
  daochai/
    pages/
      sha256(url).html.zst
    metadata.sqlite
```

Store:

```text
url
canonical_url
source_code
http_status
etag
last_modified
content_hash
fetched_at
robots_allowed
parser_version
parse_status
layout_signature
```

On rescrape:

1. send `If-None-Match` / `If-Modified-Since` when available;
2. skip unchanged pages by `304` or same content hash;
3. parse only changed pages;
4. produce a deterministic JSONL observation file;
5. import idempotently.

Do **not** store raw HTML inside the public app repo or APK. Keep it in local/private scrape cache or server-side private storage only.

---

# 3. Data model and field mapping

## Add a staging layer

Do not import scraped pages directly into `tea`. Add a staging layer, even if the first implementation is just JSONL files.

Recommended minimal staging tables:

```sql
scrape_source (
  id bigserial primary key,
  code text unique not null,
  display_name text not null,
  base_domain text not null,
  robots_url text,
  robots_checked_at timestamptz,
  robots_allowed boolean,
  terms_checked_at timestamptz,
  notes text
);

scrape_run (
  id bigserial primary key,
  source_id bigint references scrape_source(id),
  started_at timestamptz not null,
  finished_at timestamptz,
  tool_version text not null,
  user_agent text not null,
  policy_version text not null,
  status text not null
);

scrape_page (
  id bigserial primary key,
  run_id bigint references scrape_run(id),
  source_id bigint references scrape_source(id),
  url text not null,
  canonical_url text,
  http_status int,
  etag text,
  last_modified text,
  content_hash text,
  fetched_at timestamptz,
  robots_allowed boolean not null,
  raw_cache_path text,
  parse_status text,
  unique(source_id, canonical_url)
);

scraped_tea_observation (
  id bigserial primary key,
  source_id bigint references scrape_source(id),
  page_id bigint references scrape_page(id),
  source_url text not null,
  source_product_id text,
  sku text,
  name_ru_raw text,
  name_en_raw text,
  name_zh_raw text,
  name_pinyin_raw text,
  aliases_raw jsonb,
  type_raw text,
  origin_country_raw text,
  region_raw text,
  cultivar_raw text,
  oxidation_raw text,
  roast_raw text,
  harvest_year int,
  batch_raw text,
  vendor_brand_raw text,
  tags_raw jsonb,
  brew_params_raw jsonb,
  taste_terms_raw jsonb,
  description_blob_id bigint,
  image_urls_raw jsonb,
  content_hash text not null,
  first_seen_at timestamptz not null,
  last_seen_at timestamptz not null,
  active boolean not null default true,
  parse_confidence numeric(4,3) not null default 0.5,
  unique(source_id, source_product_id),
  unique(source_id, source_url)
);
```

Optional but useful:

```sql
scraped_text_blob (
  id bigserial primary key,
  source_id bigint references scrape_source(id),
  source_url text not null,
  text_kind text not null, -- description, taste_prose, brewing_prose
  raw_text text not null,
  copyright_status text not null default 'unknown-do-not-redistribute',
  created_at timestamptz not null
);
```

That table is **server/private only** and excluded from public catalog export.

## Field mapping

| Scraped field                    | Public table target                                                       | Handling                                                                                                                                                                                    |
| -------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Product title                    | `tea_name(locale='ru')` candidate                                         | Strip vendor-grade/packaging/year words before dedup. Keep raw title in observation.                                                                                                        |
| Explicit translated name         | `tea_name(locale='ru'/'en')` candidate                                    | Example: Cha-shop exposes a translated Russian name for Da Hong Pao. Use as alias, not necessarily primary. ([Магазин правильного чая][5])                                                  |
| Chinese characters in title/body | `tea_name(locale='zh-Hans')` candidate only if it is clearly the tea name | Do not treat province text such as Fujian as tea name. Daochai page exposed province text with Chinese characters, which is useful origin metadata, not a tea-name alias. ([daochai.ru][2]) |
| Pinyin / Latin transliteration   | `tea_name(locale='pinyin'/'en')` candidate                                | Prefer explicit pinyin from sources or Wikidata. Generate only if missing and mark generated/source accordingly.                                                                            |
| Breadcrumb/category              | `tea.type`, `region`, weak dedup feature                                  | Daochai and Cha-shop pages expose category paths; Art of Tea sitemap exposes category hierarchy. ([daochai.ru][2])                                                                          |
| Country/region/province          | `tea.origin_country`, `tea.region`                                        | Import if explicit. Art of Tea sample exposes China and Wuyi/Fujian region facts. ([Art of Tea. Искусство Чая][1])                                                                          |
| Cultivar                         | `tea.cultivar` or observation claim                                       | Import only if explicit; otherwise leave null. Cha-shop text mentions Wuyi cultivar distinctions, useful but should be reviewed. ([Магазин правильного чая][5])                             |
| Harvest year / batch             | Observation only by default                                               | Usually lot-specific, not canonical tea identity. Use for freshness/search, not canonical `tea` unless schema later supports lots.                                                          |
| Vendor SKU/article               | Observation only                                                          | Good idempotency key, not public canonical tea metadata. Moychay and Cha-shop pages expose SKU/article-style identifiers. ([moychay.ru][3])                                                 |
| Taste tags                       | Weak input to `tea_flavor` enrichment                                     | Do not copy prose. Map tags to dimensions as hints, then let enrichment/generator produce TeaTiers-owned profile.                                                                           |
| Vendor description               | **Not public `tea_description`**                                          | Store privately only if needed as `sourceText` input, or discard after extracting facts.                                                                                                    |
| Vendor images                    | **Not public `tea_image`**                                                | Store URL only as private observation if needed. Do not export unless license is explicitly permissive.                                                                                     |

## Dedup against existing catalog

Dedup must work across scripts and vendor naming noise. The target is not “one vendor product = one tea”; the target is “many vendor observations can support one canonical tea”.

### Step 1: normalize names

Create a shared normalizer for scraped names and existing `tea_name.name_norm`:

```text
lowercase
ё -> е
trim whitespace
remove punctuation and quote marks
normalize romanization spaces/hyphens
strip weights, pack sizes, prices
strip years and harvest/batch words
strip vendor grade words: premium, высший сорт, отборный, collection, private reserve
strip brewing/roast/lot words when they are not canonical identity
```

Examples:

```text
"Улун Да Хун Пао, высший сорт, 2025" -> "да хун пао"
"Да Хун Пао, средний огонь, 2025" -> "да хун пао"
"Da Hong Pao" -> "da hong pao"
"大红袍" -> "大红袍"
```

### Step 2: maintain an alias dictionary

Add a small curated alias table:

```sql
tea_alias_rule (
  id bigserial primary key,
  canonical_key text not null,
  locale text not null,
  alias_norm text not null,
  confidence numeric(4,3) not null,
  source text not null,
  unique(locale, alias_norm)
);
```

Examples:

```text
canonical_key: da-hong-pao
aliases: да хун пао, дахунпао, дхп, da hong pao, dahongpao, 大红袍

canonical_key: tie-guan-yin
aliases: те гуань инь, тегуаньинь, tie guan yin, tieguanyin, 铁观音

canonical_key: long-jing
aliases: лун цзин, лунцзин, long jing, longjing, 龙井
```

Wikidata remains the preferred source for cross-script canonical aliases where available, because Wikidata content is CC0 and is already part of your enrichment order.

### Step 3: candidate generation

For each observation:

1. exact alias match;
2. exact normalized dedup key match;
3. pg_trgm candidate search on `tea_name.name_norm`;
4. same-script fuzzy match;
5. cross-script alias match;
6. optional Wikidata match;
7. if no strong match, create review candidate.

Suggested SQL candidate query:

```sql
select
  t.id,
  tn.locale,
  tn.name,
  similarity(tn.name_norm, :obs_name_norm) as sim,
  t.type,
  t.origin_country,
  t.region,
  t.cultivar
from tea_name tn
join tea t on t.id = tn.tea_id
where tn.name_norm % :obs_name_norm
order by similarity(tn.name_norm, :obs_name_norm) desc
limit 20;
```

### Step 4: score candidates

Use a conservative score. False merges are more damaging than duplicate unverified rows.

```text
name / alias match          0.45
type agreement              0.20
origin country agreement    0.10
region agreement            0.10
cultivar agreement          0.10
source reliability          0.05
```

Decision bands:

```text
>= 0.85  auto-attach observation to existing tea
0.65-0.85 review queue
< 0.65   create new unverified tea candidate
```

### Step 5: avoid vendor-lot explosion

A scraped product like “Da Hong Pao, medium fire, 2025” should usually attach to canonical **Da Hong Pao**, not create a new public tea. Daochai and Art of Tea examples both include year/roast/grade-like modifiers that are useful as observation facts but risky as canonical identity. ([Art of Tea. Искусство Чая][1])

Create a public `tea` only when the distinction is stable and meaningful:

```text
Create/keep separate:
- different tea variety: Rou Gui vs Shui Xian vs Da Hong Pao
- named cultivar when catalog policy says cultivar is first-class
- named blend/brand product that users search for as a product

Do not create separate canonical tea:
- harvest year only
- pack size only
- vendor grade only
- roast level only, unless TeaTiers later models lots/roasts
- marketing label only
```

## Merge and conflict rules

Use a source-priority policy:

```text
1. Human-curated seed
2. Wikidata / Wikimedia-compatible open facts
3. Specialist tea shops with explicit fields
4. Specialist tea articles / encyclopedias
5. Marketplaces
6. LLM-generated guesses
```

Field-specific policy:

```text
tea.type:
  accept if source category is clear; prefer agreement from 2 sources.

origin_country:
  accept if explicit; low conflict for Chinese teas.

region:
  accept if explicit; prefer specialist sources over marketplaces.

cultivar:
  require explicit statement; otherwise leave null.

oxidation:
  infer from tea type only if schema needs it; otherwise leave null or coarse.

brand:
  store only if tea is actually a branded product/blend.
  Do not set brand to vendor shop for generic loose tea.

description:
  never copy vendor prose into public tea_description.

flavor:
  generated by existing enrichment using facts/tags as hints.
```

## Idempotent import

Use three unique keys:

```text
source identity:
(source_code, source_product_id)

URL identity:
(source_code, canonical_url)

content identity:
source_code + canonical_url + content_hash
```

Import flow:

```text
scrape -> observations.jsonl
observations.jsonl -> normalize -> candidates.jsonl
candidates.jsonl -> dedup preview
operator reviews conflicts
approved decisions -> import SQL/Flyway seed path
```

The importer should output:

```text
new_tea_count
merged_observation_count
new_alias_count
field_conflict_count
description_blocked_count
image_blocked_count
needs_review_count
```

Never let scraped data overwrite a verified curated field automatically. It can add a competing observation claim, but the public canonical field should change only through a merge rule or human approval.

---

# 4. Facts vs copyrighted expression policy

## Practical rule

**Ship facts. Do not ship copied expression.**

Safe-to-ship candidates:

```text
tea names
alternative names
type
origin country
region/province
cultivar
oxidation class
roast class when factual
harvest year / batch if modeled as observation
vendor/brand name where factual
source URL
source code
retrieved_at
```

Do-not-ship verbatim:

```text
vendor descriptions
taste prose
brewing prose
article paragraphs
reviews
photos/images
marketing slogans
long product explanations
```

Vendor pages I sampled contain both facts and expressive prose. For example, Art of Tea exposes factual fields such as country/region/year but also taste/description text; Daochai and Moychay expose rich taste descriptions/tags and brewing prose. Those should be parsed into factual hints, not copied into public descriptions. ([Art of Tea. Искусство Чая][1])

## Recommended schema values

For factual scraped names:

```text
tea_name.source = "scrape:artoftea:<page>"
tea_name.license = "unknown-site-terms; factual-extraction-only"
```

For generated descriptions:

```text
tea_description.source = "teatiers-enrichment:yandexgpt:<model/run>"
tea_description.license = "teatiers-generated"
```

For raw scraped prose kept privately:

```text
scraped_text_blob.copyright_status = "unknown-copyrighted-do-not-redistribute"
```

For images:

```text
tea_image:
  only allow if license is known permissive: CC0, CC-BY, CC-BY-SA with attribution, own photo, or user-provided with permission.

scraped image URL:
  private observation only, no public export.
```

This preserves the earlier #24 spirit for public APKs even though scraping is now allowed as a private acquisition tool.

## Legal note, short and practical

In Russian copyright practice, copyright attaches to works expressed in an objective form, and commentary on Russian law notes that copyright does **not** extend to facts, ideas, concepts, methods, discoveries, or similar non-expressive information. The same source lists catalog/encyclopedia and photographic works among protected categories, which is the practical reason not to copy vendor descriptions or photos. ([zuykov.com][18])

In the EU, database protection can apply separately from copyright: the Database Directive defines databases and says copyright protection of a database does not extend to the contents themselves, while a sui generis database right can protect substantial investment in obtaining, verifying, or presenting contents. EU guidance describes that sui generis right as preventing extraction/reuse of the whole or a substantial part and lasting 15 years. ([eur-lex.europa.eu][19])

Operationally, do this:

```text
Do:
- extract sparse factual fields
- attribute source/source_url/retrieved_at
- keep source observations auditable
- generate your own TeaTiers descriptions
- use scraped prose only as private enrichment input, if at all

Do not:
- mirror a site catalog wholesale into public export
- copy product descriptions
- copy photos
- copy reviews
- expose raw scrape cache
- claim a license you do not have
```

The project accepts scraping gray area for personal curation, but the **public APK/export should contain only facts, source attribution, and TeaTiers-generated expression**.

---

# 5. Integration and operating model

## Bulk import first, not ongoing scraper

Choose **one-off / periodic local bulk import** for v0.1–v0.2:

```text
local machine or operator box:
  scrape + cache + parse + normalize + dedup preview

repository:
  only parser configs, source definitions, and approved seed JSON/CSV
  no raw HTML cache
  no copied prose
  no vendor images

backend VM:
  Postgres import
  enrichment jobs
  API serving
```

Do **not** run a permanent crawler service on the Yandex VM. The VM is small and already hosts Kotlin/Spring, Postgres, Caddy, and OCR. A permanent crawler also increases legal/operational risk and creates another thing to monitor.

## Where to run

Recommended:

```text
Primary:
  local developer machine

Acceptable:
  pelican/control node if it has spare disk and network stability

Avoid:
  production 4 GB Yandex VM for crawling
  production VM for Playwright
  production VM for raw HTML cache
```

The production VM should receive only:

```text
approved seed/import file
staged observations
dedup decisions
generated descriptions
```

## Miss-log integration

Use the `catalog_miss` table as the prioritizer.

Weekly or before each curation sprint:

```sql
select normalized_query, count(*) as misses
from catalog_miss
where resolved_tea_id is null
group by normalized_query
order by misses desc
limit 200;
```

Then:

1. match top misses against existing catalog aliases;
2. search specialist-source scrape cache;
3. if absent, manually add source URLs for top misses;
4. scrape only those pages;
5. import high-demand matches first;
6. run enrichment only after dedup decision.

This avoids “scrape everything because we can” and turns scraping into **demand-driven curation acceleration**.

## Enrichment integration

Use scraped facts as input to the existing chain:

```text
existing:
  Wikidata CC0 first
  YandexGPT fallback

new:
  scraped factual observations before or beside LLM fallback
```

Recommended enrichment prompt input:

```json
{
  "canonical_names": {
    "ru": ["Да Хун Пао"],
    "pinyin": ["Da Hong Pao"],
    "zh-Hans": ["大红袍"]
  },
  "facts": {
    "type": "oolong",
    "origin_country": "China",
    "region": "Wuyi, Fujian",
    "roast": "medium",
    "cultivar": null
  },
  "source_observations": [
    {
      "source": "artoftea",
      "source_url": "...",
      "fields": ["region", "country", "year"]
    },
    {
      "source": "daochai",
      "source_url": "...",
      "fields": ["province", "batch", "taste_tags"]
    }
  ],
  "forbidden": [
    "Do not copy vendor wording",
    "Do not paraphrase one source closely",
    "Generate a short original TeaTiers description"
  ]
}
```

The generated public row goes to:

```text
tea_description.short_text
tea_flavor dimensions
```

The raw vendor prose remains private or discarded.

## QA model

Add import states:

```text
scraped_pending
auto_matched_unverified
needs_review
approved_unverified
verified
rejected
```

Quality gates before public export:

```text
1. No public tea_description row with source like scrape:*.
2. No public tea_image row with license unknown.
3. No public tea_name row without source/source_url.
4. No auto-created tea with only a marketplace source.
5. No canonical tea created from pack size / harvest year / price-only difference.
6. All high-confidence merges have at least name + type agreement.
7. All cross-script aliases are either Wikidata-backed, explicit in source, or human-approved.
```

Add a CI/export check:

```sql
-- block copied descriptions
select *
from tea_description
where source like 'scrape:%';

-- block unknown images
select *
from tea_image
where license is null
   or license in ('unknown', 'unknown-site-terms', 'no-redistribute');

-- block unsupported names
select *
from tea_name
where source is null
   or license is null;
```

---

# 6. Concrete first cut

## Build first

### Phase 1 — scraper skeleton

Implement:

```text
sources.yaml
robots preflight
httpx fetcher
HTML cache
selectolax parser
JSONL observation writer
layout tests with saved sample pages
```

Example `sources.yaml`:

```yaml
sources:
  artoftea:
    display_name: "Art of Tea"
    domain: "artoftea.ru"
    mode: "server_html"
    robots_required: true
    concurrency: 1
    delay_seconds_min: 2
    delay_seconds_max: 5
    parser: "parsers.artoftea:v1"
    export_policy: "facts_only"

  daochai:
    display_name: "Daochai"
    domain: "daochai.ru"
    mode: "server_html"
    robots_required: true
    concurrency: 1
    delay_seconds_min: 2
    delay_seconds_max: 5
    parser: "parsers.daochai:v1"
    export_policy: "facts_only"
```

### Phase 2 — Art of Tea parser

Parse:

```text
product URL
title
category/breadcrumb if available
type/category
country
region
year
parameter table
variant/pack info only as observation
description text only into private blob or discard
image URLs only as private blocked references
```

Art of Tea sample pages support this direction because product pages expose title, region, year, country, name/parameters, while sitemap exposes usable tea category hierarchy. ([Art of Tea. Искусство Чая][1])

### Phase 3 — Daochai parser

Parse:

```text
product URL
title
breadcrumb/category
product code if present
province/region
batch/year
roast/fire words
taste groups as tags
description private/discarded
image URLs private/blocked
```

Daochai sample pages support this because product and category pages expose title/category/product-code-like fields, batch/province data, tags, and server-rendered category listings. ([daochai.ru][2])

### Phase 4 — dedup preview

Produce:

```text
import_preview.csv
  observation_id
  source
  source_url
  raw_name
  normalized_name
  proposed_tea_id
  proposed_action
  score
  reasons
  conflict_fields
```

Actions:

```text
attach_existing
create_new_unverified
add_alias
needs_review
reject_non_tea
reject_accessory
reject_bundle
```

### Phase 5 — import approved rows

Import only:

```text
canonical facts
names/aliases
source attribution
generated descriptions
generated flavor profile
```

Do not import:

```text
vendor prose into tea_description
vendor images into tea_image
raw HTML into repo/APK
marketplace SEO text
reviews
```

---

# 7. Second wave

After Art of Tea + Daochai work:

## Add Moychay

Moychay has broad coverage and useful structured/taste/brewing blocks, but lots and out-of-stock products require stronger dedup. The sample page exposes SKU/article, brewing parameters, taste tags, and harvest/processing text, which are valuable observations but should not all become canonical tea records. ([moychay.ru][3])

## Add Cha-shop

Cha-shop is useful for cleaner specialist explanations and category/tag metadata. The sample page exposes translated names, SKU, categories/tags, packaging variants, and cultivar/processing discussion. Use it as a validation source and alias/cultivar source rather than a huge bulk source. ([Магазин правильного чая][5])

## Add marketplace probes only after that

Marketplace use cases:

```text
brand aliases
popular Russian product spellings
mass-market tea names
miss-log resolution for supermarket teas
packaged product matching
```

Do not use marketplaces for:

```text
canonical origin/cultivar facts unless corroborated
public descriptions
images
broad crawling
first-cut catalog population
```

---

# 8. What to record in `source`, `source_url`, and `license`

Recommended conventions:

```text
source:
  scrape:artoftea
  scrape:daochai
  scrape:moychay
  scrape:cha-shop
  wikidata
  teatiers-enrichment:yandexgpt

source_url:
  canonical product/article URL, stored server-side and in public catalog where acceptable

license:
  CC0                         -- Wikidata
  CC-BY-SA-4.0                 -- only if you intentionally import compatible Wikipedia text/facts with attribution
  unknown-site-terms-facts     -- factual extraction from vendor site
  unknown-do-not-redistribute  -- private raw scraped prose/image reference
  teatiers-generated           -- generated original description/flavor text
```

For public APK/export, allow:

```text
license in:
  CC0
  CC-BY / CC-BY-SA with attribution support
  unknown-site-terms-facts
  teatiers-generated
```

Block:

```text
unknown-do-not-redistribute
scraped-description
scraped-image
vendor-prose
review-text
```

---

# 9. Recommended final architecture

```text
                    ┌──────────────────────┐
                    │ catalog_miss          │
                    │ top unresolved names  │
                    └──────────┬───────────┘
                               │
                               ▼
┌──────────────┐     ┌──────────────────────┐
│ source config│────▶│ local scraper         │
│ robots rules │     │ httpx + selectolax    │
└──────────────┘     └──────────┬───────────┘
                                │
                                ▼
                      ┌─────────────────────┐
                      │ raw private cache    │
                      │ html + metadata      │
                      └──────────┬──────────┘
                                 │
                                 ▼
                      ┌─────────────────────┐
                      │ observations JSONL   │
                      │ facts + private refs │
                      └──────────┬──────────┘
                                 │
                                 ▼
                      ┌─────────────────────┐
                      │ normalize + dedup    │
                      │ pg_trgm + alias map  │
                      └──────────┬──────────┘
                                 │
                 ┌───────────────┴───────────────┐
                 ▼                               ▼
        ┌────────────────┐              ┌────────────────┐
        │ auto attach     │              │ review queue    │
        │ high confidence │              │ conflicts/new   │
        └────────┬───────┘              └────────┬───────┘
                 │                               │
                 └───────────────┬───────────────┘
                                 ▼
                      ┌─────────────────────┐
                      │ seed/import path     │
                      │ Postgres upsert      │
                      └──────────┬──────────┘
                                 │
                                 ▼
                      ┌─────────────────────┐
                      │ enrichment           │
                      │ Wikidata -> YandexGPT│
                      │ original blurb/flavor│
                      └──────────┬──────────┘
                                 │
                                 ▼
                      ┌─────────────────────┐
                      │ public catalog API   │
                      │ facts + generated    │
                      └─────────────────────┘
```

---

# Decision paragraph

Adopt a **facts-only, local-first scraping pipeline** for TeaTiers: start with **Art of Tea and Daochai**, using `httpx==0.28.1`, `selectolax==0.4.10`, `lxml==6.1.1`, and a mandatory robots/politeness/cache layer; output private scraped observations, not public catalog rows. Add a staging/dedup layer that maps vendor pages to canonical teas through normalized names, alias rules, pg_trgm similarity, and source-confidence scoring; import only approved factual fields through the existing seed path. Keep vendor descriptions, taste prose, reviews, and images out of the public APK; at most store raw prose privately as enrichment input, while the existing Wikidata → YandexGPT tier generates TeaTiers-owned descriptions and flavor profiles. After the first two sources work, add Moychay for breadth and Cha-shop for validation; postpone marketplaces to miss-log-driven brand/alias lookup only.

[1]: https://artoftea.ru/oolong/dahongpao-high-baking "Да Хун Пао высший сорт купить по цене 1080 руб."
[2]: https://daochai.ru/vid-chaya/ulun/uishanskie-uluny/da-hun-pao-sredniy-ogon-2024/?srsltid=AfmBOooRXkDBlvVsjQsZ4E3-yI8gWuEM3igrcfKte_c7N5rYLp3Zebtw "Да Хун Пао, средний огонь, 2025 - купить уишаньский улун с ценой 8.98₽ в интернет-магазине Daochai.ru"
[3]: https://moychay.ru/catalog/ulun/uishanskij_ulun/huachun-da-hun-pao?srsltid=AfmBOooE4LBOB9DKvosrZA1NmNBnxirycbNklJY4PDAcvBs1S4mEon8Q "Уишаньский улун Хуачунь Да Хун Пао"
[4]: https://moychay.ru.sitescorechecker.com/?utm_source=chatgpt.com "Чайный магазин Мойчай.ру | Китайский чай, травы, посуда ..."
[5]: https://cha-shop.ru/shop/dahongpao_p/ "Купить Да Хун Пао «Чжун Хо» - китайский Уишань"
[6]: https://tea.ru/article/nastoyashchiy-da-khun-pao-samyy-dorogoy-chay-v-mire-/?utm_source=chatgpt.com "Настоящий Да Хун Пао - самый дорогой чай в мире"
[7]: https://www.wildberries.ru/catalog/15607907/detail.aspx?utm_source=chatgpt.com "Чай китайский да хун пао красный халат сильной ..."
[8]: https://www.wildberries.ru/robots.txt?utm_source=chatgpt.com "robots.txt"
[9]: https://market.yandex.ru/card/da-khun-pao---bolshoy-krasnyy-khalat/102479869803/spec "Are you not a robot?"
[10]: https://pypistats.org/packages/httpx?utm_source=chatgpt.com "httpx"
[11]: https://pypi.org/project/selectolax/ "selectolax · PyPI"
[12]: https://pypi.org/project/lxml/ "lxml · PyPI"
[13]: https://security.snyk.io/package/pip/beautifulsoup4?utm_source=chatgpt.com "beautifulsoup4"
[14]: https://pypi.org/project/Scrapy/?utm_source=chatgpt.com "Scrapy"
[15]: https://pypi.org/project/playwright/?utm_source=chatgpt.com "playwright"
[16]: https://www.rfc-editor.org/info/rfc9309/?utm_source=chatgpt.com "RFC 9309: Robots Exclusion Protocol"
[17]: https://www.python-httpx.org/advanced/resource-limits/?utm_source=chatgpt.com "Resource Limits"
[18]: https://zuykov.com/en/about/articles/how-to-register-copyright-procedure-and-documentation/ "How to Register Copyright - Procedure and Documentation"
[19]: https://eur-lex.europa.eu/eli/dir/1996/9/oj/eng "Directive - 96/9 - EN - EUR-Lex"
