# 20-catalog-scraping — Russian Tea Site Scraping Design & Operating Model

## 1. Sources

To quickly grow the Russia-first tea catalog of TeaTiers, we must target high-quality, authentic sources that offer rich metadata on terroirs, cultivars, and tea types. Below are the ranked Russian-reachable sources, evaluated for technical structure and taxonomic reliability.

### Rank 1: Moychay.ru (Мойчай.ру) — *The Breadth Champion*
*   **Catalog Size:** Over 5,000 active and historical items (including an invaluable archive "Архив" of rare/out-of-stock teas).
*   **Structured Data Exposed:** Tea type (highly granular: Sheng Pu-erh, Shu Pu-erh, Wuyi Oolong, Dancong Oolong, etc.), raw material harvest year ("сырье"), pressing year ("пресс"), precise location/terroir (e.g., "округ Мэнхай", "горы Уишань"), cultivar/plant type (e.g., "Цяому" - arbor tree, "Да Е" - large leaf), and categorized flavor tags (e.g., "Древесный", "Ореховый", "Сухофрукты").
*   **Page Structure:** Heavily Server-Side Rendered (SSR) HTML. Deep catalog nesting (e.g., `/catalog/ulun/uishanskij_ulun/...`) enables easy extraction. DOM selectors are clean, consistent, and highly indexable.
*   **Robots.txt Posture:** Standard e-commerce crawler exclusions (search, filters, cart blocked), but permissive for product lists and detail pages.
*   **Taxonomy Cleanliness:** Highly standardized. It uses correct botanical terminology and authentic transliterations, making it perfect for seeding a clean catalog.

### Rank 2: Artoftea.ru (Art of Tea) — *The Flavor Mapping Gold Standard*
*   **Catalog Size:** ~500–1,000 highly curated, premium items.
*   **Structured Data Exposed:** Precise geographic region ("горный хребет Уишань, провинция Фуцзянь"), and a highly detailed **0–5 scale flavor profile mapping** (dimensions: sweetness, sourness, bitterness, fruitiness, nuttiness/spiciness, minerality). This perfectly aligns with our `tea_flavor` schema.
*   **Page Structure:** Fast, static/SSR semantic HTML. Product details are cleanly nested in CSS classes like `.product-info`. No heavy JS rendering is required to fetch details.
*   **Robots.txt Posture:** Permissive for standard catalog indexing.
*   **Taxonomy Cleanliness:** Pristine. Zero keyword-stuffed titles. Highly reliable cultivar data.

### Rank 3: Teatips.ru — *The Reference Encyclopedia*
*   **Catalog Size:** Several hundred historical, regional, and botanical tea articles.
*   **Structured Data Exposed:** Rich historical text, historical tea catalog classifications, and regional notes.
*   **Page Structure:** Ancient, lightweight, static HTML. Extremely fast to parse but lacks a uniform e-commerce item-detail structure.
*   **Robots.txt Posture:** Fully open.
*   **Taxonomy Cleanliness:** Academic and accurate, but difficult to scrape in a structured manner. Best used as a secondary reference check rather than an automated seed.

### Rank 4: Specialized E-Commerce (e.g., 101tea.ru, Cantata.ru) — *Deprioritized*
*   **Why:** Huge catalogs, but highly polluted with heavily flavored teas, artificial additives, and low-quality descriptions. Parsing authentic cultivars from these platforms is inefficient and produces noisy data.

### Rank 5: Marketplaces (Ozon, Wildberries, Yandex Market) — *Strictly Exclude*
*   **Why:** Highly dynamic Single Page Apps (SPAs) guarded by aggressive Web Application Firewalls (WAFs), CAPTCHAs, and IP bans. The catalog taxonomy is severely cluttered with keyword-stuffed merchant titles (e.g., "Puer 10 years slimming tea gift set").

---

## 2. Scrape + Parse Tooling Stack

To operate safely on a **4 GB RAM VM** (or run locally on a developer laptop without triggering resource constraints), we must avoid heavy headless browsers (like Chromium-driven `playwright` which easily hogs 500MB+ RAM). The chosen stack is purely **asynchronous, lightweight, and C-optimized**:

```python
# requirements.txt
httpx==0.27.0            # BSD-3-Clause: Async HTTP client with HTTP/2 support
selectolax==0.3.21        # MIT: Cython binding to Lexbor, 10x-20x faster than BeautifulSoup
pydantic==2.7.1          # MIT: Fast data parsing, validation, and JSON schemas
hishel==0.1.5            # BSD-3-Clause: RFC 9111 HTTP caching for HTTPX
```

### Politeness & Infrastructure Strategy
1.  **RFC-Compliant Caching:** We use `hishel` with an `AsyncSqliteStorage` backend. *Rule: Scrape once, parse many times.* When tweaking CSS selectors in our Python scripts, the pages are loaded instantly from the local SQLite cache instead of pounding the vendor's servers.
2.  **Robots.txt Compliance:** Use Python's built-in `urllib.robotparser` to dynamically query `/robots.txt` and respect crawling pathways, caching rules locally.
3.  **Transparent User-Agent:** 
    `User-Agent: TeaTiersCatalogBot/1.0 (+https://teatiers.org/bot; admin@teatiers.org)`
4.  **Rate-Limiting & Jitter:** Run scraping pipelines strictly sequentially (no massive concurrent pools). Apply a random delay between requests:
    `await asyncio.sleep(2.0 + random.uniform(0.5, 3.5))` to mimic natural browsing patterns.

---

## 3. Data Model + Dedup

Scraped entities must be mapped cleanly into our Postgres schema, identifying duplicates across multiple sources (e.g., matching a "Да Хун Пао" from Moychay with one from Art of Tea).

```
                      +-------------------+
                      |   Scraped JSON    |
                      +---------+---------+
                                |
                   [Parse fields & Transliterate]
                                |
                                v
               +---------------------------------+
               |  Generate Unique 'dedup_key'    |
               | (e.g., 'china_wuyi_dahongpao')  |
               +----------------+----------------+
                                |
                 [Look up in Postgres 'tea']
                                |
             +------------------+------------------+
             |                                     |
       [Match Found]                        [No Match Found]
             |                                     |
             v                                     v
+------------------------+             +-----------------------+
|  Add as Alias to       |             |  Check Similarity via |
|  existing 'tea' via    |             |  'pg_trgm' on names   |
|  new 'tea_name' row    |             +-----------+-----------+
+------------------------+                         |
                                            [Sim > 0.85?]
                                                   |
                                     +-------------+-------------+
                                     |                           |
                                  {Yes}                        {No}
                                     |                           |
                                     v                           v
                       +---------------------------+   +-------------------+
                       | Group as Alias under      |   | Create new 'tea'  |
                       | existing 'tea_id'         |   | record with       |
                       +---------------------------+   | 'verified=false'  |
                                                       +-------------------+
```

### 1. Generating the `dedup_key`
Every scraped tea goes through a normalization pipeline:
*   Remove punctuation, weight markings (e.g., `100г`, `357g`), and commercial fluff (e.g., "Премиум", "Высший сорт").
*   Transliterate Russian Cyrillic characters to standard Latin equivalents using a library like `anyascii` (e.g., `Да Хун Пао` -> `da hong pao`).
*   Sort words alphabetically to prevent order-shuffled duplicates (e.g., `Шу Пуэр` vs `Пуэр Шу`).
*   Concatenate: `{normalized_origin_country}_{normalized_region}_{normalized_canonical_name}`.

### 2. Matching with `pg_trgm` (GIN index on `name_norm`)
If a deterministic lookup by `dedup_key` fails, we query Postgres using trigram similarity on the normalized name column:
```sql
SELECT t.id, t.dedup_key, similarity(n.name_norm, :scraped_name_norm) as sim
FROM tea t
JOIN tea_name n ON t.id = n.tea_id
WHERE n.name_norm % :scraped_name_norm
ORDER BY sim DESC
LIMIT 1;
```
*   **Similarity > 0.85:** High confidence match. Do not insert a new `tea` row. Instead, add a new alias record in the `tea_name` table linking to the existing `tea_id` (e.g., linking the Chinese characters, English, Pinyin, and Russian names under one single tea entity).
*   **Similarity between 0.50 and 0.85:** Write to a merge-review queue table for manual operator verification.
*   **Similarity < 0.50:** Treat as a completely new tea variety. Insert a new row in the `tea` table with `verified = false`.

### 3. Conflict Resolution & Merging
*   **Authority Hierarchy:** `Curated Seed` (Highest) > `Art of Tea` > `Moychay` (Lowest).
*   If a field (e.g., `cultivar` or `region`) is null in our catalog, update it with the highest-priority scraped value. Never overwrite human-curated values with scraped ones.

---

## 4. Facts vs. Copyrighted Expression (The Redistribution Line)

To safely build a public mobile application (with downloadable APKs), we must strictly separate **factual data** from **copyrighted creative expression**. 

```
                                      +------------------------+
                                      |  Scrape Source Page    |
                                      +-----------+------------+
                                                  |
                                                  v
                     +----------------------------+----------------------------+
                     |                                                         |
             [Factual Data]                                         [Creative Expression]
(Names, terroirs, cultivar, flavor scores)                   (Poetic copywriter descriptions, photos)
                     |                                                         |
                     v                                                         v
         +-----------------------+                                 +-----------------------+
         |   Save directly into  |                                 |   Store server-side   |
         |   public DB tables    |                                 |   only (source_text)  |
         +-----------+-----------+                                 +-----------+-----------+
                     |                                                         |
                     |                                                         v
                     |                                             +-----------------------+
                     |                                             |  Feed to YandexGPT as |
                     |                                             |   context for prompt  |
                     |                                             +-----------+-----------+
                     |                                                         |
                     |                                                         v
                     |                                             +-----------------------+
                     |                                             |   Generate custom,    |
                     |                                             |   non-infringing text |
                     |                                             +-----------+-----------+
                     |                                                         |
                     +----------------------------+----------------------------+
                                                  |
                                                  v
                                      +------------------------+
                                      | Packaged into public   |
                                      | catalog (APK / API)    |
                                      +------------------------+
```

### The Separation Policy
1.  **Publicly Redistributable (Factual):** Names of teas in any language (titles/botanical names are not copyrightable), terroirs, cultivars, oxidation levels, and numeric flavor profiles (0-5 scale). These are stored in the primary `tea`, `tea_name`, and `tea_flavor` tables and shipped directly in the public APK.
2.  **Server-Side Only (Copyrighted Expression):** Verbatim promotional text, brewing stories, and raw product photos. We **never** ship these to the public APK. Instead, we write scraped description text to a backend-only database table: `tea_scraping_staging(source_text)`. Product photos are discarded immediately; we rely entirely on Wikidata (CC0) or clean default vector illustration placeholders per tea type.
3.  **LLM Rewriting Pipeline:** A background worker sends the raw, backend-only `source_text` to our YandexGPT enrichment worker. The LLM is prompted to output a short, original, 2-3 sentence Russian description and map it to `tea_description`. This eliminates all copyright infringement risks from automated sweeps.

### Legal Context (RU & EU Realities)
*   **Russian Civil Code (Part IV, Article 1334):** Modelled after the EU Database Directive (96/9/EC), this article establishes a *sui generis* database maker's right protecting substantial material or financial investments. A legal presumption applies: any database containing **10,000 or more elements** is automatically protected. Extracting a "substantial part" of such a database is prohibited.
*   **Compliance Safe Harbor:** We are fully compliant because:
    1.  We are not extracting or cloning their database structure (their selection or arrangement of contents, which is protected under database copyright). Our Postgres schema is built specifically for local-first Android tier lists.
    2.  Our final database is a completely transformative dictionary compiled from multiple sources and enriched via our own YandexGPT engine.
    3.  We store `source` and `source_url` metadata for clear, transparent attribution, which mimics a citation index rather than data theft.

---

## 5. Integration + Operating Model

To keep the cloud infrastructure light, secure, and resilient against IP blocks, the scraping architecture is split between local execution and cloud-based enrichment.

```
       [ DEVELOPER LAPTOP (LOCAL) ]                  [ YANDEX CLOUD VM (PRODUCTION) ]
+------------------------------------------+       +----------------------------------+
| 1. Run local Python scraper script       |       |                                  |
| 2. Persist requests locally via Hishel   |       |                                  |
| 3. Generate structured JSONL dump        |       |                                  |
+--------------------+---------------------+       |                                  |
                     |                             |                                  |
            [Upload via SSH]                       |                                  |
                     |                             |                                  |
                     v                             |                                  |
+------------------------------------------+       |                                  |
| 4. Execute safe Postgres import script   |------>| 5. Populate remote Database      |
|    - Matches against production misses   |       |    - Insert rows (verified=false)|
|    - Matches via pg_trgm similarity      |       |                                  |
+------------------------------------------+       +----------------+-----------------+
                                                                    |
                                                                    v
                                                   +----------------------------------+
                                                   | 6. Cloud Cron Worker runs:       |
                                                   |    - Scans unverified items      |
|                                                  |    - Sends context to YandexGPT  |
|                                                  |    - Generates original prose    |
                                                   +----------------+-----------------+
                                                                    |
                                                                    v
                                                   +----------------------------------+
                                                   | 7. Operator logs into Admin UI:  |
|                                                  |    - Inspects matches & text     |
|                                                  |    - Toggles verified = true     |
                                                   +----------------------------------+
```

### The Operating Steps
1.  **Local Execution:** The scraper runs on the developer's local machine. This prevents production VM resources from being exhausted and isolates the cloud VM's IP address from anti-bot blocks.
2.  **Local Caching:** Raw HTML remains stored inside local SQLite databases via `hishel`. The parsing logic yields a structured JSON Lines (`JSONL`) file containing factual properties and raw source text.
3.  **Miss Log Integration:** Before writing imports, the script pulls the `catalog_miss` unresolved queries from production [Decision #116]. It matches these highly wanted queries against the scraped dataset, ensuring that requested items are prioritized and imported first.
4.  **Database Seeding:** A secure Python utility inserts the data into the production Postgres DB. Triggers check for trigram matches on `tea_name`. New items are marked as `verified = false`.
5.  **Server-Side Enrichment:** A background cron job on the Yandex Cloud VM detects new unverified teas, retrieves their `source_text`, submits them to YandexGPT to generate our clean `tea_description`, and sets up the flavor profiles.
6.  **Human QA Review:** An operator logs into a simple internal Spring Boot thymeleaf admin page (or PgAdmin), reviews unverified entries, validates mapped aliases, and marks them as `verified = true` to release them to the public app catalog.

---

## 6. Recommendation + Sequencing

For the first iteration of our newly permitted scraping strategy, we will target **Art of Tea (Artoftea.ru)** first. This provider represents a low-volume (~800 items), high-value target with highly structured, clean flavor dimensions that map seamlessly to our 0–5 numeric schema. 

### The Concrete Sequencing:
1.  **Phase 1 (The Parser):** Write a local Python script using `httpx` + `selectolax` + `hishel` to extract all product URLs and details from `artoftea.ru`, saving the raw HTML cache locally.
2.  **Phase 2 (The Matcher):** Write a matching routine using `pg_trgm` GIN indexes on the `name_norm` column to detect and link aliases (handling variations like "Да Хун Пао", "Da Hong Pao", "大红袍") under a unified `tea_id`.
3.  **Phase 3 (The Rewriter):** Implement the server-side YandexGPT worker to ingest raw scraped text and generate 100% original, creative, non-infringing Russian descriptions, storing them in `tea_description` while discarding raw copywriter text.
4.  **Phase 4 (Scale to Moychay):** Scale up the pipeline to target categories and deep archive paths on `moychay.ru` to populate thousands of traditional Chinese, Japanese, and Russian herbal teas.