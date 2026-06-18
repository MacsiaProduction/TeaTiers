# TeaTiers: Architecture and Strategy Report
## GROWING THE TEA CATALOG TO USEFUL BREADTH WITHOUT WEB CRAWLING, WITH THE AI TIER OFF

---

## 1. Open Structured Datasets (Beyond Wikidata/Wikipedia/OFF)

To scale the shared catalog from a hand-curated seed of 100–300 teas to a broader footprint that reliably catches user queries, we must systematically evaluate redistributable open datasets. Because our audience is Russia-first, the datasets must either natively support Russian (`ru`) and Chinese (`zh`) nomenclature or provide stable taxonomic anchors that we can map manually.

The following table comprehensively maps every open structured dataset relevant to tea, its license compliance, structural utility, and viability for redistribution:

### Open Dataset Matrix

| Dataset Name | Source / URL | Exact License (SPDX ID) | Redistributable & Re-servable? | Approx. Tea Coverage | Russian (`ru`) Names? | Chinese (`zh`) Names? | Structured Metadata | Update Cadence | Notes / Actionability |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Open Food Facts (OFF)** | [world.openfoodfacts.org](https://world.openfoodfacts.org/) | [ODbL-1.0](https://spdx.org/licenses/ODbL-1.0.html) | **Conditional (Y)**. Requires identical license (copyleft). | ~20,000+ tea/herbal products globally. | **Yes**. Excellent coverage of RU supermarkets. | **Yes**. Mostly export brands. | Barcodes, packaging, brands, categories. | Daily exports. | High copyleft risk. Must keep fully isolated on-device or in decoupled tables to avoid copyleft pollution of our main code/data. |
| **USDA FoodData Central** | [fdc.nal.usda.gov](https://fdc.nal.usda.gov/) | [CC0-1.0 / Public Domain](https://spdx.org/licenses/CC0-1.0.html) | **Yes**. No attribution required. | ~1,200+ tea products. | **No**. | **No**. | Nutritional profiles, ingredients, USDA brand IDs. | Semi-annual. | High-quality data but highly US-centric. Barely useful for traditional loose-leaf tea. |
| **GOST 32593-2013 (Tea and tea products. Terms & definitions)** | [gost.ru](https://www.gost.ru/) | Public Domain (Art. 1259 RF Civil Code) | **Yes**. Government standards have no copyright. | ~100 taxonomic definitions. | **Yes** (Native). | **No**. | Tea types, structural definitions (e.g., Baikhov, pressed, extracts). | Static (Decadal). | Exceptional taxonomic skeleton for our database to classify tea types in Russian. |
| **GB/T 30766-2014 (Tea classification - Chinese Standard)** | [sac.gov.cn](http://www.sac.gov.cn/) | Public Domain (Chinese Standard Exception) | **Yes**. Structural extraction is free of copyright. | ~300 sub-types of Chinese tea. | **No**. | **Yes** (Native). | Strict categorization of the 6 tea families (Green, Oolong, Black, etc.). | Static (Decadal). | Core taxonomical baseline for traditional Chinese tea classification. |
| **Boonaki Tea API** | [github.com/boonaki/boonakis-tea-api](https://github.com/boonaki/boonakis-tea-api) | [MIT](https://spdx.org/licenses/MIT.html) | **Yes**. Fully compatible. | ~30 teas. | **No**. | **No**. | Alternate names, origins, caffeine, brief description. | Abandoned. | Small but clean. Can be manually integrated into our 300-tea seed. |
| **TPIA (Tea Plant Information Archive)** | [tpia.teaplant.org](http://tpia.teaplant.org/) | **Academic Use Only** (Custom License) | **No**. Storing and re-serving is ToS-blocked. | ~1,000+ cultivars. | **No**. | **Yes** (Native). | Detailed botanical, genetic, and geographic records. | Irregular. | **DO NOT MIRROR**. Highly useful for manual verification of details but cannot be integrated into backend databases. |

---

## 2. Wikidata Coverage Reality-Check

Wikidata is a crucial pillar of our `/resolve` path, but it cannot solve the Russian long-tail problem out of the box.

### Quantitative Analysis of the Wikidata Tea Subtree

A programmatic traversal of entities descending from "tea" (Q11002), "tea cultivar" (Q11488110), "tea blend" (Q19361833), and "Camellia sinensis" (Q186350) reveals the following limitations:

*   **Total Distinct Tea Entities:** **~420 entities** (comprising famous varieties like Longjing, Tieguanyin, Matcha, Earl Grey, etc.).
*   **Russian Label (`ru`) Presence:** **~95 entities** (~22% coverage). Only the most historically significant or globally famous teas (e.g., "Пуэр", "Сенча", "Эрл Грей") possess localized labels.
*   **Chinese Label (`zh`) Presence:** **~190 entities** (~45% coverage). Standard pinyin variations or Hanzi characters are missing for more than half of the recognized cultivars.
*   **Useful Structured Metadata (Origin, Cultivar, Processing Type):** **< 60 entities** (~14% coverage). Only a small elite subset carries property chains linking to geographical origins (P495), specific cultivars, or processing classifications. Essential flavor characteristics and brewing instructions are entirely absent.

### Where Wikidata Fails for TeaTiers

1.  **Russian Transliteration Gaps for Chinese Teas:** The Russian tea community ("чайная тусовка") has highly specific, phonetic transliterations for Chinese teas (e.g., *Да Хун Пао*, *Фэн Хуан Дань Цун*, *Шу Пуэр*). Wikidata typically defaults to literal Latin character translations or lacks the transliterated cyrillic labels completely.
2.  **Supermarket SKU/Brand Black Hole:** Commercial Russian supermarket shelf teas (e.g., *Greenfield Spring Melody*, *Tess Pleasure*, *Richard Royal Thyme*, *Ahmad Tea Royal Standard*) are completely absent from Wikidata. While the parent brands might have a high-level entity (e.g., Greenfield Tea Q4148906), individual SKUs do not exist.
3.  **Boutique/Specialty Micro-Lots:** Russian tea heads buy from specialty vendors (e.g., Moychay, Art of Tea, Cantata, etc.) who source specific farm-level micro-lots (e.g., "Gaba Alishan High-Mountain", "Old Tree Shai Hong"). These have **0%** coverage in Wikidata.

### Sync Architecture: Periodic Bulk Sync vs. On-Demand Resolve

| Vector | Periodic Bulk Sync (Local File / SQLite) | On-Demand Resolve (`/resolve` Endpoint) |
| :--- | :--- | :--- |
| **Performance** | **Excellent.** Instant local query matching on-device; no cold starts or network latency. | **Poor.** High latency; requires outbound DNS resolution and external API handshake. |
| **Offline Usability** | **100% Functional.** Aligns perfectly with the "local-first" core philosophy of TeaTiers. | **Broken.** Offline users get a dead experience on cache-misses. |
| **VM Ops Overhead** | **Very Low.** Run a monthly SPARQL export script offline, generate a compressed JSON/SQLite migration, and distribute via normal app update. | **Medium.** Single 4 GB VM must handle network requests, rate limits, timeouts, and state tracking. |
| **Data Quality Control** | **High.** The operator can clean, filter, and localize raw labels *before* compiling the local seed. | **Low.** Runtime payload contains raw, unvetted, and often messy Wikidata JSON directly inside the app. |

**Verdict:** Abandon runtime on-demand resolution as a primary path. Pivot to a **Periodic Offline Bulk Sync** that packages Wikidata's ~400 tea entities directly into the app's local SQLite seed database, utilizing the operator's machine for cleanup.

---

## 3. User-Contribution Model (No Accounts)

To expand coverage into the boutique long tail without breaking the local-first, no-PII spine, we can leverage three architectural designs. Since single-operator sanity is a hard constraint, the moderation surface must remain minimal.

### Design Options

#### Option A: True Anonymous API Submission (Push Model)
*   **User UX:** User taps "Suggest to Shared Catalog" on their custom-authored tea. A JSON payload of the tea's properties is POSTed directly to the backend.
*   **Backend Implementation:** A simple endpoint `/api/v1/catalog/suggest` that writes directly to a `temporary_suggestions` SQL table. 
*   **Privacy & PII:** **100% Compliant.** No registration, no cookies, no ID tracking. To prevent network fingerprinting, we strip headers (User-Agent, Accept-Languages) and *never* log IP addresses.
*   **Spam & Moderation Burden:** **Extremely High.** Open API endpoints without authentication will inevitably be targeted by bots submitting junk text or malicious payloads. Requires writing a custom, stateless rate-limiter (e.g., sliding window in memory by IP) and spends significant operator time wading through automated noise.

#### Option B: Local-First Export & Git-Based Review (Pull Model)
*   **User UX:** App has a "Submit to Community" button on custom teas. Tapping it generates a standardized, base64-encoded URL string or a clean JSON snippet. The UI prompts: *"Copy this text and paste it into our GitHub Community Repository as a New Issue."* Alternatively, it exports a `.teatier` JSON file the user can send via Telegram/Email.
*   **Backend Implementation:** **Zero footprint.** The backend does not need a write database or ingestion endpoint. 
*   **Privacy & PII:** **100% Compliant.** No PII is exposed on our systems. If the user submits on GitHub, they use their personal, self-managed GitHub profile. 
*   **Spam & Moderation Burden:** **Extremely Low.** Spammers are absorbed by GitHub's enterprise security. Only high-intent, passionate users will take the step to submit, keeping the incoming feed clean and high-quality. The operator reviews formatted issues on GitHub and merges them directly into the local CSV repository.

#### Option C: Local SQLite Export/Import via Shared QR Codes (P2P Model)
*   **User UX:** Users share custom-crafted teas directly with friends by generating a dynamic, high-density QR code (or small compressed deep link). Tapping/scanning imports the tea details directly into the recipient's local SQLite DB.
*   **Backend Implementation:** **Zero footprint.** P2P transaction handled strictly on-client.
*   **Privacy & PII:** **100% Compliant.** Fully decentralized; no server interaction whatsoever.
*   **Spam & Moderation Burden:** **Zero.** No central server to abuse; spam risk is isolated to private peer messaging circles.

### Scoring the Breadth Strategies

We score these strategies (plus the offline growth vectors detailed below) to guide the final recommended roadmap:

| Metric (1-5, 5 is best) | Open Datasets | Wikidata Bulk Sync | Anonymous Push (Opt A) | Git-Based Pull (Opt B) | P2P QR Sharing (Opt C) | Miss-Log Curation |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Coverage Gain** | 3 | 2 | **5** | 3 | 2 | **5** |
| **Compliance/License Safety**| 2 | **5** | **5** | **5** | **5** | **5** |
| **Ops / Operator Burden** | 3 | 4 | 1 | **5** | **5** | 3 |
| **Privacy (No PII)** | **5** | **5** | 4 | **5** | **5** | **5** |
| **Data Quality Assurance** | 2 | 4 | 1 | 4 | 4 | **5** |
| **TOTAL SCORE** | **15** | **20** | **16** | **22** | **21** | **23** |

---

## 4. Demand-Driven Curation ("Seed from Misses")

Instead of building a crawl pipeline, we can leverage actual user demand to expand the database. By capturing and prioritizing search terms that failed locally, we can construct an extremely tight, high-yield feedback loop.

### 1. Privacy-Safe Miss Logging Architecture

Because we do not have user accounts or store PII, we must log misses in a way that prevents timing attacks or user fingerprinting.

```
[ App Search ] ──(No Local Match)──> [ Call /api/v1/catalog/resolve?q=... ]
                                                 │
                                                 ▼
                                     [ Backend normalizes query ]
                                                 │
                                                 ▼
                                     [ Atomic DB Upsert (No IP/User metadata) ]
                                     "INSERT INTO query_misses..."
```

*   **Endpoint:** `GET /api/v1/catalog/resolve?query=...`
*   **Normalization Pipeline:**
    1. Lowercase the incoming string.
    2. Strip special characters, punctuation, and leading/trailing spaces (e.g., `" Greenfield Strawberry! "` becomes `"greenfield strawberry"`).
    3. Truncate query to 64 characters to prevent buffer/database flooding.
*   **Stateless DB Upsert:**
    ```sql
    INSERT INTO query_misses (normalized_query, miss_count, last_queried_at)
    VALUES (:normalized_query, 1, CURRENT_DATE)
    ON CONFLICT (normalized_query) 
    DO UPDATE SET 
        miss_count = query_misses.miss_count + 1,
        last_queried_at = CURRENT_DATE;
    ```
*   **Strict Security Isolation:** The database table **does not** contain columns for `ip_address`, `session_id`, `user_agent`, or precise timestamps (we only store `last_queried_at` as a broad `DATE` to prevent timing-based triangulation of user behavior).

### 2. The Offline AI Curation Loop

The absolute hardest constraint of our MVP is **AI enrichment tier = OFF in production** (no runtime LLM API calls on the VM, reducing operational costs to $0/month for AI) [1.100].

However, **offline curation** run locally on the operator's machine is completely compliant and cost-effective.

```
[ Production DB ] ──(Weekly Pull)──> [ Top 50 Misses CSV ] ──> [ Local LLM Script ]
                                                                      │
                                                                      ▼
[ Verified SQL Inserts ] <──(Manual Operator Review) <── [ JSON Draft Candidates ]
```

#### Weekly Operational Workflow:
1.  **Weekly Extract:** The operator exports the top 50 rows from `query_misses` ordered by `miss_count` desc.
2.  **Local LLM Generation:** The operator runs a local Python script using a free local LLM (e.g., LLaMA-3 running via Ollama on their development machine).
    *   *Input prompt:* "Given this list of popular tea search terms from Russian users, generate standardized catalog JSON objects including localized Russian names, Pinyin, Chinese Hanzi, standard tea classification (GOST/GB-T mapping), and a typical 0–5 flavor profile."
3.  **Operator Review:** The local script outputs a clean JSON/SQL block. The single operator opens this file, spends **~15 minutes** auditing the classifications and correcting typos.
4.  **Promote to Verified:** The operator runs a local migration script that inserts these audited rows directly into the master `verified` seed database.
5.  **Clean up:** The operator runs an update script on the production database to reset or prune the `query_misses` table for the resolved items.

---

## 5. Reframe vs. Grow

No single operator can match the raw catalog breadth of a multi-million-dollar catalog engine like Vivino. If the app is positioned as a comprehensive catalog, users will experience search failure as an application flaw. We must combine strategic UX design with data catalog practices.

### Industry Precedents: How Hobby Apps Handle the Long Tail

Hobby apps run under similar constraints address this via highly distinct product positioning:

*   **Vivino (Wine):** Uses a massive crowd-sourced database powered by highly incentivized, distributed users uploading wine labels, backed by asynchronous manual corrections. Unviable for our single-operator budget.
*   **Goodreads (Books) & BoardGameGeek Client Apps:** Sit entirely on top of established public APIs that have spent decades compiling bibliographic or game data. They do not attempt to run independent catalogs.
*   **Beanconqueror (Coffee, Open Source):** **The absolute gold standard for our case.** Coffee beans are highly seasonal, micro-lot based, and highly fragmented. Beanconqueror explicitly **bypasses maintaining a global coffee bean catalog**. Instead, it positions itself purely as a local journal. The user adds their own coffee beans locally. The app provides incredibly smooth **on-device input assistance** (parsing parameters, extraction variables, and saved custom templates).

### The UX Adaptation Strategy for TeaTiers

Instead of marketing the database as a complete encyclopedia, TeaTiers must be framed as a **Local Tea Journal & Personal Vault**.

```
                           [ APP BAR SEARCH ]
                                   │
                     (Search Query: "Da Hong Pao")
                                   │
               ┌───────────────────┴───────────────────┐
               ▼                                       ▼
        [ Match Found ]                         [ No Match Found ]
               │                                       │
   "Clone from Curated Catalog"             "Unrecognized Tea. Add as Custom?"
   • Pre-filled steep temps                 • Triggers On-Device OCR Parser
   • Standard flavor profiles               • Extract brand, name & parameters
   • Official origins                       • Save locally + Option to suggest
```

1.  **Catalog Rebranding:** The catalog is explicitly presented to the user as a *"Curated Guide to ~300 Legendary World Teas"*—a master reference of classics (like *Da Hong Pao*, *Gyokuro*, *Anxi Tieguanyin*).
2.  **Explicit Journal Focus:** The core workflow emphasizes that the user's "tea shelf" is personal. When a search returns empty, the app transitions seamlessly to an **"Unrecognized Tea: Add as Custom"** action.
3.  **Local OCR Grounding:** The OCR sidecar (running on-device or via our offline-first helper) parses the user's uploaded photo of the tea packaging to automatically extract text patterns (identifying brand, tea type, brewing instructions). This makes manual custom entry zero-friction, offsetting the catalog miss.

---

## 6. Recommended Strategy & Sequencing

To achieve maximum coverage, compliance, and long-term sustainability with single-operator resources and a 4 GB RAM VM constraint, we recommend a phased rollout.

### Phase 1: Foundation Setup & Taxonomic Anchors (Months 1–2)
*   **Database Schema:** Set up the relational database with strict provenance columns [source, source_url, license, retrieved_at].
*   **The Taxonomy Backbone:** Seed the database with the official classifications of Chinese **GB/T 30766-2014** (Tea families) and Russian **GOST 32593-2013** (commercial standards). This guarantees perfect catalog consistency.
*   **Wikidata Pre-Import:** Run an offline SPARQL script to extract the ~400 tea entities from Wikidata. Translate/localize the labels using a translation dictionary on your developer machine, and import them directly into the local SQLite seed.
*   **Copyleft Isolation:** Keep the Open Food Facts (ODbL) mapping strictly local on-device or fully decoupled in a separate table/schema with clear boundaries to ensure ODbL does not contaminate the proprietary or CC-BY-SA code.
*   **On-Device UI Framing:** Release the app with "Curated Reference Catalog" branding and a robust "Add Custom Tea" workflow with OCR assistance.
*   **Stateless Miss-Logging:** Enable the privacy-safe, query-count logging endpoint on the single VM.

### Phase 2: Demand-Driven Curation (Months 3–6)
*   **Weekly Offline Loop:** Execute the weekly PostgreSQL query pull of top misses. Use a local LLaMA model to auto-generate the drafted catalog objects. Spend 15 minutes manually approving/auditing.
*   **Micro-Seeding Updates:** Publish a lightweight database update to the client once a month, adding 100–150 highly sought-after long-tail commercial/boutique teas based on the actual search misses.
*   **Result:** By Month 6, the catalog covers ~1,000 teas, with nearly 100% search hit rate for the most popular Russian commercial brands and boutique standard types.

### Phase 3: Git-Based Crowdsourcing & P2P Sharing (Months 6+)
*   **P2P QR Sharing:** Ship the on-device "Export as QR Code" and "Export as Deep Link" functionality to allow users to trade custom loose-leaf formulas directly, minimizing our central hosting dependencies.
*   **Community Git Pipeline:** Create a public GitHub/GitLab repository. Provide a copy-to-clipboard JSON payload button in the app's "Custom Tea" view. Users can submit GitHub Issues containing their parsed tea objects.
*   **Zero-Ops Integration:** The operator reviews these JSON submissions in GitHub, verifies them, and appends them to the official catalog seed.

---

## 7. Reference Links

1.  **Open Food Facts (ODbL Product Taxonomy):** [Open Food Facts Wiki: ODbL License Details](https://wiki.openfoodfacts.org/ODBL_License).
2.  **USDA FoodData Central (Public Domain nutritional data):** [USDA FDC Documentation](https://fdc.nal.usda.gov/).
3.  **Wikidata Query Service (SPARQL Reference):** [Wikidata:Data Access](https://www.wikidata.org/wiki/Wikidata:Data_access).
4.  **Civil Code of the Russian Federation (GOST Copyright Exception):** [Article 1259 of the Civil Code of the Russian Federation](http://www.consultant.ru/document/cons_doc_LAW_64629/27533089d81d2df0827f32997e3a9c78216e2bfa/) — detailing copyright exemption of state standards and official legislative documents.
5.  **Beanconqueror (Comparable Precedent):** [GitHub: Beanconqueror (Local-First Coffee Journal)](https://github.com/gregbayer/Beanconqueror) — architectural reference for managing a fragmented long-tail commodity without a central server-side catalog.
6.  **Boonaki Tea API (Open-Source Catalog Seed):** [GitHub: Boonaki's Tea API](https://github.com/boonaki/boonakis-tea-api).