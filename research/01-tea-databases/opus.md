# Sourcing & Populating a Multilingual (ru/en/zh) Tea Catalog for "TeaTiers"

## TL;DR
- **Build a CC0/open-licensed redistributable core from Wikidata (CC0) + Wikipedia (CC-BY-SA) + Open Food Facts (ODbL), and lead with a curated seed list of ~300 canonical teas** — community/commercial sites (Steepster, RateTea, TeaDB, Tea Guardian, Moychay, Baidu Baike) explicitly reserve all rights and must NOT be mirrored or redistributed; use them only as human-read reference for hand-curating ru/zh names.
- **Only Wikidata gives you legally-safe, redistributable multilingual labels** (Russian, Chinese, and machine-readable pinyin transliterations) for famous teas; coverage is good for well-known Chinese/Japanese/Indian teas but thin in the long tail, so ru/zh names for most catalog entries will have to be hand-curated against authoritative references (Wikidata labels, GB/T standards, vendor sites).
- **For Postgres 16, use a `tea` + `tea_name(tea_id, locale, name, is_primary)` schema with pg_trgm GIN indexes for Cyrillic/substring search and a CJK-aware FTS path (zhparser or pg_cjk_parser bigrams), plus per-row provenance columns** to satisfy ODbL/CC-BY-SA share-alike and attribution.

## Key Findings

### 1. The legal fault line determines everything
The single most important finding: tea data sources split cleanly into a **redistributable open core** and a **look-but-don't-copy tier**.

- **Redistributable (safe to mirror into Postgres and serve via your API):** Wikidata (CC0), Open Food Facts (ODbL database + CC-BY-SA contents), Wikipedia text (CC-BY-SA 4.0). These permit bulk download, local mirroring, and redistribution with the stated attribution/share-alike obligations.
- **NOT redistributable (terms reserve all rights / prohibit copying):** Steepster, RateTea, TeaDB/Tea Guardian/Cha Dao editorial, Moychay/Мойчай and other vendor catalogs, and Baidu Baike. Their facts (a tea's name, origin, type) are not themselves copyrightable, but their compiled databases and prose are, and their ToS forbid reproduction. Treat these as reference only.

Because TeaTiers is **Russia-first** (a Russian-language app targeting Russian users), Russia's data-localization law is relevant — but it governs **personal data of Russian citizens**, not a tea catalog. The law is **Federal Law No. 242-FZ** (dated July 21, 2014, amending Federal Law No. 152-FZ "On Personal Data"); its effective date was advanced to **Sept 1, 2015** by Federal Law No. 526-FZ (signed Dec 31, 2014). Per Duane Morris LLP, it "mandates that data operators... record, systematize, accumulate, store, amend, update and retrieve data using databases physically located in Russia." The tea catalog itself contains no personal data, so 242-FZ does not constrain where you mirror the catalog. It *does* apply to any user accounts/reviews TeaTiers stores (tier-list votes, usernames, emails): those must be recorded/stored in a database physically located in Russia. Keep the tea-catalog service and the user-data service architecturally separable so the catalog can live anywhere while user PII stays on Russian servers.

### 2. Source-by-source

**Wikidata** — The keystone. License: **CC0 / public domain** for all structured data in the main, property, and lexeme namespaces (confirmed at Wikidata:Licensing and dumps.wikimedia.org/legal.html, which states copyrights of structured data "are waived using the Creative Commons Zero (CC0) public domain dedication"). Labels, aliases, descriptions, and sitelinks are all CC0 and included in the JSON/RDF dumps; **no attribution required** (appreciated, not mandatory). Tea is modeled as item **Q6097** ("tea," labels in 215 languages) with a deep `subclass of` (P279) tree (green tea, oolong, black/"red" tea, white, yellow, dark/hei cha, pu'er) and many named teas as individual items. Verified multilingual coverage on specific items (checked June 14, 2026): Darjeeling tea (Q745933) has ru "Дарджилинг (чай)" and zh "大吉嶺茶"; Longjing tea (Q1069130) has ru "Лунцзин (чай)" and zh "龙井茶"; Matcha (Q822331) has ru "Маття" and zh "抹茶"; Pu'er tea (Q690098) carries native label "普洱茶" plus an explicit Hanyu Pinyin transliteration value "pǔ'ěrchá" as structured data. Access: SPARQL (query.wikidata.org), per-entity REST (Special:EntityData/Q###.json), and full JSON/RDF dumps. Update cadence: continuous edits; dumps generated regularly. NOTE: several QIDs commonly cited are wrong — Matcha is Q822331 (not Q333103, which is a bird); verify QIDs before importing.

**Wikipedia (en/ru/zh) category trees** — License: text **CC-BY-SA 4.0** (+ GFDL). Redistributable WITH attribution and share-alike. Useful for category trees (e.g., "Chinese teas," "Зелёный чай," "中国茶") and for prose descriptions and ru/zh article titles (which often supply the localized name). Access: MediaWiki API, dumps. Because it's share-alike, any descriptive text you copy obliges you to license your derived text under CC-BY-SA and attribute — manageable but a reason to prefer Wikidata labels (CC0) for the structured name fields and only pull Wikipedia prose where you accept SA.

**Open Food Facts** — License (per OFF's Data page, world.openfoodfacts.org/data): database under **ODbL v1.0**, individual contents under the **Database Contents License (DbCL)**, product images under **CC-BY-SA**. Redistributable with **attribution + share-alike**. Coverage: per OFF's own homepage (June 2026), "100.000+ contributors like you have added 4 000 000+ products from 150 countries"; beverages is a large category (~160,000). The OFF **categories taxonomy** (categories.txt on GitHub) is a multilingual DAG with translations per language and Wikidata links — directly useful for tea type/category localization. Caveat: OFF is packaged-retail-product oriented (barcodes, brands, Nutri-Score), not artisan loose-leaf cultivars; tea coverage is brand/SKU heavy and shallow on cultivar/oxidation. Bulk: nightly MongoDB/JSONL/CSV/Parquet dumps, SQLite, DuckDB; an uncompressed CSV is ~9 GB. The API page explicitly warns "Any attempt to scrape the database using the API will very likely be blocked, as full daily exports are available" — so use the dumps for bulk, not the API.

**Steepster** — Largest community tea DB: per Steepster's own Press Kit page (steepster.com/press), "Steepster is the largest community-edited database of teas on the Web, currently with 308913 ratings and 412122 tasting notes for 89876 teas from 8774 companies." License: **all rights reserved**; Terms of Use prohibit copying/reproduction of site content, and user submissions are licensed only to Steepster ("by submitting, you are also granting to Steepster a non-exclusive, royalty-free, worldwide license to use, display, reproduce..."). **Do NOT scrape, mirror, or redistribute.** Reference only. No public bulk API.

**RateTea** — Per RateTea's homepage (ratetea.com, June 2026): "7595 teas, 6759 ratings/reviews, 728 brands, 982 places, 174 styles of tea." Detailed brand/style/region classification by Alex Zorach / Merit Exchange LLC. No open license; standard copyright. **Do NOT mirror/redistribute.** Reference only; no API.

**TeaDB / boonaki tea-api** — Note disambiguation: "TeaDB" the popular site (teadb.org, pu'er-focused blog/video) is editorial, all-rights-reserved. There is a small open-source "boonaki/tea-database" + boonakis-tea-api on GitHub that aggregates simple tea info "mostly from Wikipedia" — small (dozens of entries), English-only, MIT-ish code but data derived from Wikipedia (so effectively CC-BY-SA). Also "theteaapi" — a free hand-curated static JSON REST API of teas/ingredients/brewing; small, English-only, license unstated (verify before use).

**Tea Guardian / Cha Dao** — Editorial reference sites (teaguardian.com; "Cha Dao" is a concept, not a database — searches surface Wikipedia's "Chinese tea culture" and various blogs). All-rights-reserved prose. Excellent for understanding cultivars/oxidation; **not a redistributable dataset.**

**World Tea Directory** — A business directory of tea companies, not a tea-variety database; no open license. Of limited value for a variety catalog and not redistributable.

**Kaggle / Mendeley tea datasets** — Predominantly **image/ML datasets** (tea-leaf disease, leaf-age quality, FAO production stats), NOT catalogs of named teas with ru/zh names. E.g., TeaLeafAgeQuality (2,208 images, Mendeley/ScienceDirect), "Tea FAO dataset," disease image sets. Licenses vary (often CC-BY for the research sets). Not useful for a multilingual variety catalog.

**Russian sources (Moychay/Мойчай, Real Tea Club, чайные базы)** — Moychay.ru is a large Russian vendor catalog (200–250+ loose-leaf teas with rich ru descriptions, type, origin, often pinyin-in-Cyrillic transliteration). Real Tea Club and similar are vendor shops. These are **commercial, all-rights-reserved** product catalogs — invaluable as a *human reference* for correct Russian tea names and transliterations, but you must NOT scrape/mirror/redistribute their catalogs. Use them to hand-author ru names.

**Chinese sources (Baidu Baike, GB/T standards, cultivar lists)** — Baidu Baike (百度百科) is rich on Chinese teas/cultivars but is **all-rights-reserved, censored, and itself a repeat copyright violator**; do NOT mirror. Per Baidu Baike's official page (baike.baidu.com): "As of January 6, 2026, Baidu baike has collected over 30 million entries. More than 8.03 million netizens have participated in editing." The authoritative classification reference is the national standard **GB/T 30766-2014 "Classification of tea"** (six categories: green/绿茶, white/白茶, yellow/黄茶, oolong/青茶, black-red/红茶, dark/黑茶), implemented Oct 26, 2014 — use it as the schema's canonical `tea_type` enumeration and for authoritative zh category names. (GB/T standards documents are themselves copyrighted/sold, but the six-category taxonomy is a fact you may use.)

### 3. Multilingual coverage reality
- **Russian + Chinese names exist, redistributably, only in Wikidata** — and only for famous teas (Longjing, Pu'er, Matcha, Darjeeling, Tieguanyin, etc.). Pinyin appears either as a label or via a dedicated Hanyu Pinyin transliteration property.
- **Wikipedia ru/zh** supplies localized article titles (= names) for teas notable enough to have articles, under CC-BY-SA.
- **OFF taxonomy** supplies localized *category* names (green tea, oolong…) in many languages.
- **Everything else** (the long tail of cultivars, vendor-specific teas, niche regional teas) will need **manual ru/zh authoring or machine translation** — with the caveat that MT mangles tea names (pinyin↔Cyrillic transliteration and CJK characters need human verification). Authoritative references for hand-curation: Wikidata multilingual labels, GB/T category names, and Russian vendor catalogs (Moychay) read by a human.

### 4. PostgreSQL schema and search
Standard Postgres FTS handles Russian well (built-in `russian` config + stemming) but **does not tokenize Chinese** (no spaces between words). Two viable CJK paths: (a) the **zhparser** extension (SCWS-based word segmentation) or **pg_jieba**, both somewhat poorly maintained but functional; or (b) **pg_cjk_parser**, which splits CJK into 2-gram tokens (supports PG 11–17). For prefix/substring search across Cyrillic and CJK alike, **pg_trgm** trigram GIN indexes are the pragmatic backbone (work on any script, support `LIKE %x%` and similarity). Use **ICU collations** (PG 16: `LOCALE_PROVIDER icu`) for correct Russian sorting and language-agnostic CJK ordering, and an **immutable unaccent** wrapper for accent-insensitive Latin matching.

### 5. Ingestion, mirroring, dedup, provenance
- **Wikidata:** one-time SPARQL/dump import of all `?t wdt:P31/wdt:P279* wd:Q6097` items with all `rdfs:label`/`skos:altLabel` in en/ru/zh + pinyin transliteration; then **periodic re-sync** (monthly) since Wikidata changes continuously. Store the QID as a stable cross-source key.
- **OFF:** import the categories taxonomy (small, GitHub) for localized type names; optionally import beverage/tea SKUs from the dump if you want brand/product rows. Periodic (the taxonomy changes slowly).
- **On-disk size:** a curated catalog of a few hundred to a few thousand teas with multilingual names and metadata is **tiny — well under 100 MB** including indexes. If you also mirror OFF tea SKUs it grows but stays modest; the full OFF dump (~9 GB CSV) is unnecessary — filter to tea categories.
- **Dedup/identity:** key on Wikidata QID where present; otherwise normalize (lowercased, unaccented, pinyin-normalized primary English/pinyin name + tea_type) as a match key, with human review for merges. Cross-source the same tea by QID first, then fuzzy trigram similarity on normalized names.
- **Provenance (required by ODbL/CC-BY-SA share-alike):** add per-row `source`, `source_id/url`, `license`, `source_retrieved_at` columns (and per-name provenance if names come from different sources). Surface attribution in the app/API for OFF (ODbL) and Wikipedia (CC-BY-SA) derived content; Wikidata (CC0) needs none but credit it anyway.

### 6. Fallback: curated seed
Open data will be **too sparse and uneven** for a polished ru/en/zh catalog out of the box. Recommended fallback (and honestly the recommended *primary* approach): **hand-curate a seed of ~300 canonical teas** (Chinese six-category famous teas, Japanese greens, Indian/Ceylon blacks, Taiwanese oolongs, herbals), pulling ru/zh/pinyin from Wikidata labels first and filling gaps from GB/T category names and Russian vendor references.

**Effort estimate:** A trilingual catalog row needs en + pinyin + zh + ru names plus type/origin/cultivar/oxidation. With Wikidata pre-supplying names for the famous ~30–40% of a 300-tea seed, a knowledgeable curator (or a bilingual ru + a zh reviewer) realistically does **~6–10 fully-verified rows per hour** including cross-checking transliterations. That is roughly **30–50 person-hours for 300 teas**, plus ~10–15 hours of upfront schema/import tooling. A 500-tea catalog scales to **~55–85 person-hours**. Budget an additional translator/reviewer pass (~10–15 hours) if you want native-quality zh and ru rather than transliteration-only.

## Details

### Comparison table of sources
| Name | Coverage | ~Records | API? | License | Mirror/redistribute OK? | ru names? | zh names? |
|------|----------|----------|------|---------|------------------------|-----------|-----------|
| **Wikidata** | Teas + types as items under Q6097 subclass tree; famous teas well-covered | Hundreds–low thousands of tea items (exact count unverified) | Yes (SPARQL, REST, dumps) | **CC0** | **YES**, no attribution required | Yes (famous teas) | Yes + pinyin (famous teas) |
| **Wikipedia en/ru/zh** | Category trees + articles for notable teas | Thousands of articles across langs | Yes (MediaWiki API, dumps) | **CC-BY-SA 4.0** | **YES** w/ attribution + share-alike | Yes (notable teas) | Yes (notable teas) |
| **Open Food Facts** | Packaged tea SKUs + multilingual category taxonomy | 4,000,000+ products total from 150 countries; beverages ~160k | Yes (dumps preferred; API discourages scraping) | DB **ODbL**, contents **DbCL**, images **CC-BY-SA** | **YES** w/ attribution + share-alike | Partial (category taxonomy) | Partial (category taxonomy) |
| **Steepster** | Community teas + tasting notes | 89,876 teas / 8,774 companies | No public bulk API | All rights reserved (ToS prohibits copying) | **NO** | No | No |
| **RateTea** | Detailed brand/style/region classification | 7,595 teas / 728 brands / 174 styles | No | All rights reserved | **NO** | No | No |
| **TeaDB (teadb.org)** | Pu'er editorial/video | n/a (blog) | No | All rights reserved | **NO** | No | Some zh in prose |
| **boonaki tea-api / theteaapi** | Simple tea facts (from Wikipedia) / curated JSON | Dozens–low hundreds | Yes (JSON) | Data ~CC-BY-SA (Wikipedia-derived) / unstated | Caution (verify) | No | No |
| **Tea Guardian / Cha Dao** | Editorial cultivar/oxidation reference | n/a | No | All rights reserved | **NO** | No | Some zh in prose |
| **World Tea Directory** | Tea companies directory | Business listings | No | All rights reserved | **NO** | No | No |
| **Kaggle/Mendeley tea datasets** | ML/image + FAO production | Image sets (e.g. 2,208 imgs) | Download | Varies (often CC-BY) | Sometimes (per dataset) | No | No |
| **Moychay/Мойчай (vendor)** | Rich ru loose-leaf catalog | 200–250+ teas | No | All rights reserved | **NO** | Yes (reference only) | Some (reference only) |
| **Baidu Baike** | Chinese teas/cultivars | 30M+ entries (all topics) | No | All rights reserved, censored | **NO** | n/a | Yes (reference only) |
| **GB/T 30766-2014** | Authoritative 6-category tea taxonomy | 6 categories + subtypes | No | Standard doc copyrighted; taxonomy is fact | Taxonomy usable as facts | n/a | Yes (authoritative category names) |

### Recommended sourcing strategy
1. **Lead with a curated seed (~300 teas)**, schema-keyed to Wikidata QIDs where they exist.
2. **Mirror the CC0/open core:** Wikidata (names/labels/pinyin/metadata — CC0, the backbone), OFF categories taxonomy (localized type names — ODbL), and selectively Wikipedia ru/zh titles/prose (CC-BY-SA) where you accept share-alike.
3. **Skip on license grounds (reference only, never mirror):** Steepster, RateTea, TeaDB, Tea Guardian, World Tea Directory, Moychay/vendor catalogs, Baidu Baike.
4. **Use GB/T 30766-2014's six categories** as your canonical `tea_type` enum and authoritative zh category names.
5. **Record provenance + license per row**; surface attribution for ODbL/CC-BY-SA content.

### PostgreSQL schema sketch (Postgres 16)
```sql
-- Database created with ICU provider for robust multilingual sort:
--   createdb teatiers --locale-provider=icu --icu-locale=und --template=template0
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
-- Optional CJK FTS: CREATE EXTENSION zhparser;  (or install pg_cjk_parser)

-- Canonical tea types from GB/T 30766-2014 (+ herbal/blended extensions)
CREATE TYPE tea_type AS ENUM
  ('green','white','yellow','oolong','black','dark','puer',
   'herbal','blended','flavored','other');

CREATE TABLE tea (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  wikidata_qid  text UNIQUE,                 -- cross-source identity key
  type          tea_type NOT NULL,
  origin_country text,                        -- ISO or free text
  region        text,                         -- province/district
  cultivar      text,                         -- optional
  oxidation_min smallint,                     -- % range, nullable
  oxidation_max smallint,
  brand         text,                         -- vendor/brand, nullable
  -- provenance (share-alike compliance)
  source        text NOT NULL,               -- 'wikidata','off','wikipedia','curated'
  source_id     text,
  source_url    text,
  license       text NOT NULL,               -- 'CC0','ODbL','CC-BY-SA-4.0', etc.
  source_retrieved_at timestamptz,
  created_at    timestamptz DEFAULT now(),
  updated_at    timestamptz DEFAULT now()
);

CREATE TABLE tea_name (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tea_id     bigint NOT NULL REFERENCES tea(id) ON DELETE CASCADE,
  locale     text   NOT NULL,                 -- 'en','ru','zh-Hans','pinyin'
  name       text   NOT NULL,
  is_primary boolean NOT NULL DEFAULT false,
  -- per-name provenance (a ru name may come from a different source than en)
  source     text,
  license    text,
  UNIQUE (tea_id, locale, name)
);
CREATE UNIQUE INDEX one_primary_per_locale
  ON tea_name (tea_id, locale) WHERE is_primary;

-- Fast multilingual prefix/substring search (works for Cyrillic + CJK):
CREATE INDEX tea_name_trgm ON tea_name USING gin (name gin_trgm_ops);
-- Accent-insensitive Latin search via immutable unaccent wrapper:
CREATE FUNCTION imm_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT unaccent('unaccent',$1) $$;
CREATE INDEX tea_name_unaccent_trgm
  ON tea_name USING gin (imm_unaccent(lower(name)) gin_trgm_ops);

-- Russian FTS (built-in), plus optional Chinese FTS via zhparser:
ALTER TABLE tea_name ADD COLUMN ts_ru tsvector
  GENERATED ALWAYS AS (to_tsvector('russian', name)) STORED;
CREATE INDEX tea_name_ts_ru ON tea_name USING gin (ts_ru);
-- For zh: CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser); ...
--   then a ts_zh generated column with to_tsvector('zh', name) + GIN.

-- Per-locale linguistic sort using ICU collations:
--   SELECT name FROM tea_name WHERE locale='ru' ORDER BY name COLLATE "ru-x-icu";
```
**Search strategy:** use pg_trgm GIN for autocomplete/substring (script-agnostic, covers Cyrillic and CJK), built-in `russian` FTS for Russian word search, and zhparser/pg_cjk_parser FTS for Chinese; route queries by detected script/locale. Use ICU collations in ORDER BY per locale. Note (Postgres caveat): truly case-/accent-insensitive ICU collations require `deterministic = false`; and glibc-based collations can shift across OS upgrades, so ICU collations are preferred for index stability.

### Ingestion specifics
- Wikidata import via SPARQL `SELECT ?t ?tLabel WHERE { ?t wdt:P31/wdt:P279* wd:Q6097 . }` pulling labels for en/ru/zh + pinyin transliteration; map to `tea` + `tea_name` rows, `license='CC0'`. Re-sync monthly.
- OFF taxonomy: parse categories.txt for tea nodes, load localized strings as `tea_name` rows for type categories, `license='ODbL'`.
- Provenance columns populated on every insert; nightly job refreshes `source_retrieved_at`.

## Recommendations
1. **Now (week 1):** Stand up the schema above on Postgres 16 with pg_trgm + unaccent + ICU; defer CJK FTS extension until you confirm Chinese word-search (not just substring) is needed — pg_trgm alone covers CJK substring search adequately for a tier-list app.
2. **Weeks 1–3:** Import the Wikidata tea subtree (CC0) as the backbone; import OFF category taxonomy for localized type names. This gives you a redistributable, multilingual spine for free.
3. **Weeks 2–6:** Hand-curate the ~300-tea seed (budget ~30–50 person-hours), filling ru/zh/pinyin from Wikidata first, then GB/T category names and human-read vendor references. **Do not scrape Moychay/Baidu/Steepster** — read them, author names yourself.
4. **Ongoing:** Monthly Wikidata re-sync; add teas on demand. Keep user/PII data in a separate service on Russian-located servers (242-FZ); the catalog service can live anywhere.
5. **Thresholds that change the plan:** If you need >2,000 teas with reliable zh word-search, invest in zhparser/pg_cjk_parser and a paid translator pass. If you ever want packaged-retail SKU breadth, import the OFF tea subset (accept ODbL attribution/share-alike). If you find a tea source offering an explicit open data license (CC0/CC-BY/ODbL) you've verified, it becomes mirror-eligible.

## High-quality reference links (with dates checked)
- Wikidata:Licensing — CC0 for all structured data — https://www.wikidata.org/wiki/Wikidata:Licensing (checked Jun 14, 2026)
- Wikimedia dump legal page — CC0 waiver for main/Property/Lexeme namespace data — https://dumps.wikimedia.org/legal.html (Jun 14, 2026)
- Open Food Facts Data/API page — ODbL + DbCL + CC-BY-SA images; nightly dumps; anti-scrape note — https://world.openfoodfacts.org/data (Jun 14, 2026)
- Open Food Facts global categories taxonomy (multilingual) — https://github.com/openfoodfacts/openfoodfacts-server/blob/main/taxonomies/food/categories.txt (Jun 14, 2026)
- Steepster Terms — all rights reserved, no copying — https://steepster.com/terms ; Press Kit counts — https://steepster.com/press (Jun 14, 2026)
- GB/T 30766-2014 "Classification of tea" — six-category national standard — https://www.chinesestandard.net/PDF/BOOK.aspx/GBT30766-2014 (Jun 14, 2026)
- zhparser (Chinese FTS extension) — https://github.com/amutu/zhparser ; pg_cjk_parser (CJK 2-gram parser, PG 11–17) — https://github.com/huangjimmy/pg_cjk_parser (Jun 14, 2026)
- PostgreSQL collation/ICU docs — https://www.postgresql.org/docs/current/collation.html (Jun 14, 2026)
- Duane Morris LLP on Federal Law No. 242-FZ (Russian data localization) — https://www.duanemorris.com/alerts/russia_personal_data_localization_law_goes_into_effect_1015.html (Jun 14, 2026)

## Caveats / Could-not-verify
- **Exact count of tea items in Wikidata:** NOT verified — the SPARQL count query could not be executed (live query endpoints were unreachable in this environment); re-run `SELECT (COUNT(DISTINCT ?t) AS ?c) WHERE { ?t wdt:P31/wdt:P279* wd:Q6097 }` at query.wikidata.org. Qualitatively it is hundreds to low thousands, not tens of thousands.
- **Some QIDs in circulation are wrong** (e.g., Matcha = Q822331, not Q333103; Pu'er = Q690098; Longjing = Q1069130; Darjeeling = Q745933; Tieguanyin = Q1328947). Verify each QID before import.
- **theteaapi / boonaki license** unstated or Wikipedia-derived — verify license before mirroring.
- **OFF tea depth:** beverage category counts are approximate and OFF is shallow on cultivar/oxidation for artisan teas.
- **GB/T 30766-2014 document** itself is a copyrighted/sold standard; only the six-category taxonomy (a fact) is used here, not the document text.
- **Web-scraping legality** is jurisdiction-specific and evolving (hiQ v. LinkedIn; Meta v. Bright Data, Jan 2024); even where public-data scraping is arguably lawful, the named commercial/community sites' ToS forbid reproduction, so redistribution remains the binding constraint regardless of scrape-access legality.
- **Russian vendor/Baidu coverage numbers** (Moychay 200–250 teas) are from the vendors' own pages and are approximate/seasonal.
- **Person-hour estimates** are reasoned planning figures, not measured; actuals depend on curator language fluency and tooling.w