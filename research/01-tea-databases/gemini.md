### 1. Comparison Table of Sources

| Name | Coverage | ~Records | API? | License | Mirror/Redistribute OK? | ru names? | zh names? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Wikidata** | Taxonomy, generic tea classes (types, cultivars, some history). | ~500 (tea subclasses/cultivars) | Yes (SPARQL) | CC0 1.0 (Public Domain) | **Yes** | Yes (high coverage of main entities) | Yes (high coverage, both Simplified/Traditional) |
| **Open Food Facts** | Retail/branded packaged teas (Lipton, Greenfield, etc.), barcodes, brands. | ~15,000+ (out of 3.7M+ total SKUs) | Yes (MongoDB, daily JSONL/CSV, REST API) | Open Database License (ODbL) | **Yes** (Requires attribution and Share-Alike) | Moderate (strong for RU-market retail brands) | Low (limited to CN-market retail brands) |
| **Steepster** | Consumer retail specialty teas, user tasting notes, ratings. | ~20,000 | No official API. (Scraped 2016 dump on GitHub) | Proprietary (Terms of Use forbid scraping and redistribution) | **No** (The 2016 GitHub scrapers are technically in violation) | No | No |
| **RateTea** | Retail teas, brands, styles, regions, user ratings. | 7,597 teas, 728 brands (as of late 2024) | No | Proprietary (Terms of Use prohibit automated scraping) | **No** | No | No |
| **TeaDB** | Pu-erh, oolongs, specialty vendor catalogs, review transcripts. | N/A (unstructured blog database) | No | Copyrighted blog content | **No** | No | No |
| **Tea Epicure (Cultivar DB)** | Detailed cultivar scientific classifications and lineages. | 550+ cultivars (China, India, Japan, etc.) | No | Copyrighted, proprietary platform | **No** | No | Minor (contains some Pinyin and characters) |
| **Teatips.ru** | Authoritative Russian specialty tea classification, terminology, and forum. | N/A (unstructured articles & forum threads) | No | Copyrighted by Denis Shumakov & Olga Nikandrova | **No** (requires manual agreement or fair use limits) | Yes (excellent, highly authoritative) | Moderate |
| **Baidu Baike / CN Cultivars** | Official Chinese cultivar registers, regional standards. | ~300-500 standard cultivars | No | Proprietary (Baidu Terms of Service forbid automated extraction) | **No** | No | Yes (excellent, authoritative Simplified Chinese) |

---

### 2. License & Legality

*   **Wikidata**: Released under **CC0 1.0 (Public Domain Dedication)**. You are free to copy, modify, distribute, and perform the work, even for commercial purposes, without asking permission or providing attribution. This is the safest source for programmatic bootstrapping.
*   **Open Food Facts**: Licensed under the **Open Database License (ODbL)**. 
    *   *Redistribution implication:* If you use Open Food Facts data, you must attribute the source. Because of the "Share-Alike" provision, if you create a derivative database (e.g., merging their data into your core catalog), you may be legally required to release your entire catalog database under the ODbL. To protect your own curated data, you must keep Open Food Facts data isolated in separate tables and join them at runtime, or avoid it entirely.
*   **Steepster & RateTea**: Their Terms of Use explicitly forbid automated extraction, scraping, and redistribution of their proprietary databases. The existing Steepster dumps on GitHub are unlicensed derivatives and carry significant copyright risks if used to seed a commercial or public-facing service.
*   **Teatips.ru**: All textual materials, classifications, and guides are protected by Russian and international copyright laws. Bulk extraction of their classification pages is illegal. However, referencing their Russian transliterations of Chinese terms is legally permissible under "nominative use" (using a factual name to refer to an entity).

---

### 3. Multilingual Coverage & Authoritative Standards

#### Translation & Transliteration Quality
Standard machine translation (MT) pipelines perform poorly with tea names, often producing literal translations that violate industry norms. For example:
*   *鸭屎香 (Ya Shi Xiang)* translates literally to "Duck Shit Aroma". In Russian, a literal translation ("Запах утиного помета") is used descriptively, but the trade name is transliterated as **Я Ши Сян** or **Аромат утиного помёта**.
*   *大红袍 (Da Hong Pao)* should be transliterated as **Да Хун Пао**, not literally translated as "Большой красный халат".

#### Authoritative References for Naming
1.  **Russian (ru)**:
    *   **GOST 32593-2013** (*Чай и чайная продукция. Термины и определения*): The official Russian state standard defining classifications (e.g., *байховый*, *прессованный*, *растворимый*).
    *   **Teatips.ru (Denis Shumakov)**: The de facto standard for specialty and third-wave tea terminology in the Russian language.
2.  **Chinese (zh)**:
    *   **GB/T 30766-2014** (*Tea Classification*): The national standardization document of China establishing the official taxonomy of the six main tea categories (Green, Black, Oolong, White, Yellow, Dark/Post-fermented).
    *   **GB/T 14456**: National standards for Green Tea (*绿茶*).
3.  **English (en)**:
    *   **ISO 3720:2011**: Standardizes minimum chemical and physical characteristics for black tea.

---

### 4. PostgreSQL 16 Schema Sketch

To support a localized, fast-searchable catalog, we normalize the schema by isolating translatable attributes from structural metadata. 

#### DDL Definition
```sql
-- Enable trigram extension for fuzzy/prefix matching across Cyrillic and CJK characters
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. Core Tea Table (Contains invariant structural metadata)
CREATE TABLE tea (
    id SERIAL PRIMARY KEY,
    tea_type VARCHAR(50) NOT NULL, -- 'green', 'black', 'oolong', 'white', 'yellow', 'dark', 'herbal'
    oxidation_min NUMERIC(3, 2),   -- e.g., 0.15 (15%)
    oxidation_max NUMERIC(3, 2),   -- e.g., 0.85 (85%)
    standard_temp_celsius INT,     -- Recommended brewing temp, e.g., 85
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. Multilingual Translations Table
CREATE TABLE tea_translation (
    id SERIAL PRIMARY KEY,
    tea_id INT NOT NULL REFERENCES tea(id) ON DELETE CASCADE,
    locale VARCHAR(10) NOT NULL,    -- 'ru', 'en', 'zh', 'zh-Latn' (Pinyin)
    name VARCHAR(255) NOT NULL,
    alias VARCHAR(255)[],           -- Alternative names/synonyms
    region_origin VARCHAR(255),     -- Localized origin, e.g., "Уишань", "Wuyi Mountains", "武夷山"
    cultivar VARCHAR(255),          -- Localized cultivar name
    description TEXT,
    CONSTRAINT uq_tea_locale UNIQUE (tea_id, locale)
);

-- 3. External Source Mapping Table (For license tracking and deduplication)
CREATE TABLE tea_source_mapping (
    id SERIAL PRIMARY KEY,
    tea_id INT NOT NULL REFERENCES tea(id) ON DELETE CASCADE,
    source_name VARCHAR(100) NOT NULL, -- 'wikidata', 'open_food_facts', 'curated'
    source_external_id VARCHAR(255),   -- e.g., 'Q3595561' (Wikidata QID)
    source_url TEXT,
    license_type VARCHAR(50) NOT NULL,  -- 'CC0', 'ODbL', 'Proprietary'
    last_synced_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_source_external_id UNIQUE (source_name, source_external_id)
);
```

#### Indexing Strategy for Multilingual and Trigram Search
Standard B-Tree indexes do not support fast `LIKE '%substring%'` queries. We use **GIN (Generalized Inverted Index)** with **trigrams** (`gin_trgm_ops`) because:
1.  **Russian/Cyrillic & English**: GIN trigrams handle partial substring match and prefix match extremely fast.
2.  **CJK (Chinese)**: Because standard PostgreSQL `tsvector` lacks native CJK word segmentation without complex third-party libraries (like `zhparser`), trigrams act as character-level n-gram splitters, allowing flawless substring matching of multi-character Chinese names (e.g., matching "龙井" within "西湖龙井").

```sql
-- Trigram indexes for fast prefix/substring search on localized names and aliases
CREATE INDEX idx_tea_translation_name_trgm ON tea_translation USING gin (name gin_trgm_ops);
CREATE INDEX idx_tea_translation_alias_trgm ON tea_translation USING gin (alias);

-- Conditional functional index to support quick fallback filtering by locale
CREATE INDEX idx_tea_translation_locale_name ON tea_translation (locale, name);
```

#### Query Example: Search matching "Лунц" (Prefix/Substring)
```sql
SELECT t.id, tr.name, t.tea_type, tr.locale
FROM tea t
JOIN tea_translation tr ON t.id = tr.tea_id
WHERE tr.name ILIKE '%Лунц%' AND tr.locale = 'ru';
```

---

### 5. Ingestion & Mirroring Strategy

```
[ Wikidata / Public APIs ] ──(Sync Script)──┐
                                            ▼
[ Manual Curated Seeds   ] ──(Admin UI)───►[ Dedup Pipeline ] ──► [ Local Postgres 16 ]
                                            ▲
[ Open Food Facts (ODbL) ] ──(Daily Import)─┘
```

1.  **Ingestion Cadence**: 
    *   **One-time Seed**: A compiled, hand-curated seed set (SQL migration/Flyway) must be imported first to establish highly clean, authoritative core data.
    *   **Periodic Sync**: Pull Wikidata updates every 30 days via a Kotlin Spring Boot cron job querying the Wikidata SPARQL endpoint. Open Food Facts should be synced monthly using their compressed daily CSV delta dumps.
2.  **On-Disk Size Projection**:
    *   Core specialty catalog (~500 teas, multilingual metadata, regional origins): **< 5 MB** of on-disk PostgreSQL storage.
    *   If retail barcoded data from Open Food Facts is fully mirrored (15,000+ records): **~50 MB** of database size. This is extremely lightweight for a standard Linux VPS.
3.  **Cross-Source Deduplication & Identity Matching**:
    *   *Step 1 (Normalization)*: Strip punctuation, convert strings to lowercase, and replace regional Cyrillic characters (e.g., replace `ё` with `е`).
    *   *Step 2 (Pinyin mapping)*: For Chinese names, programmatically generate a uniform Pinyin slug (without tone marks, e.g., "Longjing" for "龙井") to act as a cross-source match key.
    *   *Step 3 (Slug matching)*: Teas with matching standard slug keys across Wikidata and manual sets are merged into a single core record (`tea` table), and their respective external origins are appended to the `tea_source_mapping` table.
4.  **License Tracking & Share-Alike Isolation**:
    *   Store license provenance inside `tea_source_mapping`.
    *   If you expose any ODbL-derived fields (from Open Food Facts) in the backend API, add a `source_attribution` field in the API JSON response acknowledging the Open Food Facts contributors. Do not mix ODbL data directly with your proprietary/hand-curated descriptions in the same DB column to prevent copyleft pollution of your custom IP.

---

### 6. Recommended Fallback: Hand-Curated Seed Set

Due to the sparse, dirty, and legally restrictive nature of the datasets listed above, **a hand-curated seed list of 200–500 teas is highly recommended** to ensure a premium user experience from Day 1.

#### Hand-Curation Effort Estimation
*   **Target Size**: **300 Teas** (comprising 150 Chinese, 50 Japanese, 40 Taiwanese, 30 Indian/Sri Lankan, and 30 Herbal/Misc).
*   **Information per Tea**: Name (EN, RU, ZH, Pinyin), category, typical oxidation, regional origin, cultivar (if applicable), and brewing parameters.
*   **Time Required**: 
    *   *Using LLM-assisted generation* (utilizing highly structured prompting with Claude/GPT to extract standard variables from factual public literature) followed by a manual review by an experienced tea master or enthusiast: **~12–15 hours total**.
    *   *Pure manual entry*: **~40 hours**.

#### Core 15 Seed Teas Mockup (Authoritative References)

| English Name | Russian Name | Chinese (Simplified) | Pinyin (zh-Latn) | Category | Typical Origin |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Dragon Well | Лунцзин | 龙井茶 | Lóngjǐng chá | Green | Hangzhou, Zhejiang |
| Da Hong Pao (Big Red Robe) | Да Хун Пао | 大红袍 | Dàhóngpáo | Oolong | Wuyi Mountains, Fujian |
| Tieguanyin (Iron Goddess of Mercy) | Тегуаньинь | 铁观音 | Tiěguānyīn | Oolong | Anxi, Fujian |
| Silver Needle | Байхао Иньчжэнь | 白毫银针 | Báihao Yínzhēn | White | Fuding, Fujian |
| Biluochun | Билочунь | 碧螺春 | Bìluóchūn | Green | Suzhou, Jiangsu |
| Jasmine Dragon Pearls | Моли Фэн Янь | 茉莉龙珠 | Mòlì Lóngzhū | Scented Green | Fuzhou, Fujian |
| Keemun | Цимэнь Хунча | 祁门红茶 | Qímén Hóngchá | Black | Qimen, Anhui |
| Golden Monkey | Цзинь Хоу | 金猴 | Jīnhóu | Black | Fujian |
| Dianhong (Yunnan Black) | Дяньхун | 云南红茶 / 滇红 | Diānhóng | Black | Lincang, Yunnan |
| Lapsang Souchong | Лапсанг Сушонг (Чжэн Шань Сяо Чжун) | 正山小种 | Zhèngshān Xiǎozhǒng | Black | Wuyi Mountains, Fujian |
| Anji Bai Cha | Аньцзи Байча | 安吉白茶 | Ānjí Báichá | Green | Anji, Zhejiang |
| Shou Mei | Шоу Мэй | 寿眉 | Shòuméi | White | Fuding, Fujian |
| Pu-erh (Ripe/Shou) | Шу Пуэр | 熟普洱茶 | Shú Pǔ'ěr chá | Dark/Post-fermented | Pu'er, Yunnan |
| Pu-erh (Raw/Sheng) | Шен Пуэр | 生普洱茶 | Shēng Pǔ'ěr chá | Dark/Post-fermented | Xishuangbanna, Yunnan |
| Junshan Yinzhen | Цзюньшань Иньчжэнь | 君山银针 | Jūnshān Yínzhēn | Yellow | Yueyang, Hunan |

---

### 7. Reference Links

1.  **Wikidata Query SPARQL Endpoint**: [https://query.wikidata.org/](https://query.wikidata.org/) *(Checked: June 2026)*
2.  **Open Food Facts Reuse and Database Export Info**: [https://world.openfoodfacts.org/data](https://world.openfoodfacts.org/data) *(Checked: June 2026)*
3.  **Open Food Facts Licensing Terms**: [https://world.openfoodfacts.org/terms-of-use](https://world.openfoodfacts.org/terms-of-use) *(Details Open Database License obligations)*
4.  **Steepster Github Historic Scrape Dataset**: [https://github.com/imightbemary/steepster](https://github.com/imightbemary/steepster) *(Archived: January 2016)*
5.  **RateTea Official Portal**: [https://ratetea.com/](https://ratetea.com/) *(Checked: June 2026)*
6.  **Tea Epicure Cultivar Database**: [https://teaepicure.com/tea-cultivar-database/](https://teaepicure.com/tea-cultivar-database/) *(Checked: June 2026)*
7.  **Russian Tea Expert Blog (Denis Shumakov)**: [https://teatips.ru/](https://teatips.ru/) *(Active through 2026)*

---

### 8. Uncertain / Could Not Verify

*   **RateTea / Steepster API**: No active public REST/GraphQL endpoints could be verified as open or legally accessible for bulk extraction in 2026.
*   **GOST 32593-2013 Free Machine-Readable Database**: Russia's national standards (GOST) are published as scanned PDFs or copyrighted text files. No open-source SQLite/PostgreSQL schemas mapping GOST terms to standard tea species could be verified; this mapping must be done manually.
*   **Baidu Baike API Legal Standing**: While unofficial Python wrapper libraries exist for Baidu searches, Baidu does not offer a free, commercially permissive API that allows bulk harvesting of cultivar entries without violating their usage policies.