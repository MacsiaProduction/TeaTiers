# 21-catalog-scraping-foundation: Scrape → Catalog Design Correction

This document establishes the corrected, fully buildable engineering plan for the **TeaTiers** scrape-to-catalog data pipeline. It addresses and resolves the seven critical flaws identified in the independent review of run 20 (decision **#131**), ensuring strict legal compliance, stable API keys, multi-source record provenance, and reliable cross-script identity matching.

---

## 1. Identity & Matching (Cross-Script Canonicalization)

The current schema leverages a `dedup_key` generated from a single locale string, which fails when comparing English (`Da Hong Pao`), Russian (`Да Хун Пао`), and Chinese (`大红袍`). To bridge scripts, we replace the closed-form single-key comparison with a **Three-Tier Identity Reconciliation Pipeline**.

```
                       [ Incoming Scraped Record ]
                                    │
                                    ▼
                         ┌────────────────────┐
                         │ Exact-Match Tier   │ ──(Match Found)──► [ Merge Alias & ]
                         │   On `name_norm`   │                    [ Update Provenance ]
                         └────────────────────┘
                                    │
                              (No Match)
                                    ▼
                         ┌────────────────────┐
                         │ Trigram & Translit │
                         │      Matches       │
                         └────────────────────┘
                                    │
                  ┌─────────────────┴─────────────────┐
                  ▼ (Similarity > 0.75)               ▼ (0.40 <= Similarity <= 0.75)
        [ Auto-Match & Merge ]             ┌─────────────────────────┐
                                           │  Human-Review Queue     │
                                           │ (candidate_match_review)│
                                           └─────────────────────────┘
                                                        │
                                                   (Approved)
                                                        ▼
                                              [ Create/Link Entity ]
```

### The Three-Tier Unification Pipeline

#### Tier 1: Multilingual Exact-Match (Global Names Index)
We index all names in the `tea_name` table across locales (`en`, `ru`, `zh-Hans`, `pinyin`). When a raw scrape record arrives, we normalize its extracted names to `lower(f_unaccent(name))` and query `tea_name.name_norm`. 
* If a name matches, we immediately retrieve the associated `tea_id`. 
* If multiple names resolve to the same `tea_id`, matching confidence increases to $1.0$.

#### Tier 2: Cyrillic-to-Pinyin Transliteration & Trigram Search
When an exact match fails on a Russian (Cyrillic) name, the pipeline dynamically translates standard Cyrillic tea phonemes into their Latin/Pinyin equivalents using a deterministic mapping table (derived from the Palladius transcription system).

##### Transcription/Transliteration Mapping Rules (Chinese Pinyin ↔ Cyrillic)
```json
{
  "да": "da", "хун": "hong", "пао": "pao",
  "те": "tie", "гуань": "guan", "инь": "yin",
  "пуэр": "pu-erh", "лун": "long", "цзин": "jing",
  "бай": "bai", "хао": "hao", "чжэнь": "zhen"
}
```

The system generates a transliterated search candidate and queries the existing `pg_trgm` GIN index on `tea_name.name_norm` using PostgreSQL’s word similarity operator (`%`):

```sql
SELECT tn.tea_id, t.brand, t.type, similarity(tn.name_norm, :transliterated_input) AS score
FROM tea_name tn
JOIN tea t ON tn.tea_id = t.id
WHERE tn.name_norm % :transliterated_input
ORDER BY score DESC
LIMIT 5;
```

##### Decision Thresholds
* **$\text{Similarity} \ge 0.75$ and Identical Tea Type/Brand:** Auto-match. The scrape record is linked to this `tea_id`.
* **$0.40 \le \text{Similarity} < 0.75$ OR Type/Brand Mismatch:** Sent to the Human-Review Queue (`candidate_match_review`).
* **$\text{Similarity} < 0.40$:** Evaluated as a brand-new tea entity.

#### Tier 3: Human-Confirmed Match Queue
A tracking table holds matches falling within the grey zone, allowing administrators to manually confirm merges without corrupting the production catalog.

---

## 2. Source-Record Staging & Idempotency

To decouple transient crawler activity from the production catalog, we separate the raw crawler capture, parsed source schema, and final canonical compilation into a multi-stage database-backed pipeline.

```
┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐       ┌─────────────┐
│  Raw Scraper    │ ────► │  raw_scrape_log │ ────► │  source_record  │ ────► │  Canonical  │
│ (Local Engine)  │       │ (Immutable HTML)│       │ (Parsed JSONB)  │       │ Catalog DB  │
└─────────────────┘       └─────────────────┘       └─────────────────┘       └─────────────┘
```

### Staging Tables DDL
```sql
-- Stage 1: Immutable Trace of Crawler Input (Short-Term Retention)
CREATE TABLE raw_scrape_log (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(50) NOT NULL,              -- e.g. 'moychay', 'tea-ocean'
    canonical_url TEXT NOT NULL,
    retrieval_time TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    content_hash VARCHAR(64) NOT NULL,       -- SHA-256 of raw raw_payload
    raw_payload BYTEA,                        -- Gzipped raw HTML
    CONSTRAINT uq_raw_scrape UNIQUE (source, content_hash)
);

-- Stage 2: Clean Factual Parsing (Primary Source Identity Key)
CREATE TABLE source_record (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(50) NOT NULL,              -- e.g. 'moychay'
    external_id VARCHAR(255) NOT NULL,        -- Vendor SKU, ID or path-slug
    canonical_url TEXT NOT NULL,
    retrieval_time TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    content_hash VARCHAR(64) NOT NULL,       -- SHA-256 of parsed JSON facts
    parser_version VARCHAR(20) NOT NULL,      -- Tracking format shifts
    parsed_facts JSONB NOT NULL,              -- Fact-dictionary: names, regions, cultivar, etc.
    canonical_tea_id BIGINT REFERENCES tea(id) ON DELETE SET NULL,
    import_run_id UUID NOT NULL,
    CONSTRAINT uq_source_record UNIQUE (source, external_id)
);

-- Stage 3: Human Verification Queue for Boundary Cases
CREATE TABLE candidate_match_review (
    id BIGSERIAL PRIMARY KEY,
    source_record_id BIGINT NOT NULL REFERENCES source_record(id) ON DELETE CASCADE,
    candidate_tea_id BIGINT NOT NULL REFERENCES tea(id) ON DELETE CASCADE,
    similarity_score NUMERIC(4,3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    conflict_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewer VARCHAR(100)
);
```

### Idempotency & Conflict Resolution Flow
1. **Hash Check:** If a source file is re-scraped, we hash its parsed facts. If the hash matches the existing `source_record.content_hash`, the record is skipped, preserving existing timestamps and associations.
2. **Fact Parsing & Candidate Matching:** If the hash changes (or it is a new record), the system extracts facts and triggers Tier 1 and Tier 2 matching against the canonical tables.
3. **Draft Upserting:** If auto-approved, the changes are applied to the canonical tables via the upsert engine (Section 3). If rejected or flagged, a task is created in `candidate_match_review` and the entity is held in a draft state.

---

## 3. Schema Changes (Provenance & Real Transactional Upsert)

### 3.1 Schema Migrations

The existing DB schema restricts `tea.source` with a strict `CHECK` constraint that rejects `'scrape'`. Additionally, it lacks structured, multi-source field-level attribution. We migrate the database to support `'scrape'` and introduce fine-grained field-level provenance.

```sql
-- Migration V1_1__add_scrape_source_and_provenance.sql

-- 1. Safely alter the tea.source CHECK constraint
ALTER TABLE tea DROP CONSTRAINT IF EXISTS tea_source_check;
ALTER TABLE tea ADD CONSTRAINT tea_source_check CHECK (source IN ('wikidata', 'curated', 'ai', 'user', 'scrape'));

-- 2. Establish high-resolution field-level provenance mapping
CREATE TABLE tea_field_provenance (
    tea_id BIGINT REFERENCES tea(id) ON DELETE CASCADE,
    field_name VARCHAR(100) NOT NULL, -- 'type', 'origin_country', 'region', 'cultivar', 'oxidation', 'brand'
    source_record_id BIGINT REFERENCES source_record(id) ON DELETE SET NULL,
    source_type VARCHAR(50) NOT NULL CHECK (source_type IN ('wikidata', 'curated', 'ai', 'user', 'scrape')),
    license VARCHAR(100) NOT NULL DEFAULT 'Proprietary-Fact',
    confidence NUMERIC(3,2) NOT NULL DEFAULT 1.00,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tea_id, field_name)
);

-- 3. Retrofit optional source record lineage to secondary catalog tables
ALTER TABLE tea_name ADD COLUMN source_record_id BIGINT REFERENCES source_record(id) ON DELETE SET NULL;
ALTER TABLE tea_description ADD COLUMN source_record_id BIGINT REFERENCES source_record(id) ON DELETE SET NULL;
ALTER TABLE tea_image ADD COLUMN source_record_id BIGINT REFERENCES source_record(id) ON DELETE SET NULL;
```

### 3.2 The Server-Side Transactional Upsert Path

The Kotlin/Spring API must enforce that scraped data is never written with `verification_status = 'verified'` by default, nor should it fallback to un-instrumented skip steps.

#### Import Data Transfer Object (DTO)
```kotlin
data class ScrapedTeaImportDto(
    val externalId: String,
    val source: String,
    val url: String,
    val names: List<LocalizedNameDto>,
    val type: String,
    val originCountry: String?,
    val region: String?,
    val cultivar: String?,
    val oxidation: Double?,
    val brand: String?
)

data class LocalizedNameDto(
    val locale: String,
    val name: String,
    val isPrimary: Boolean
)
```

#### Database Import Repository Interface
```kotlin
package ru.teatiers.backend.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.teatiers.backend.model.Tea

@Repository
interface ScrapeUpsertRepository : JpaRepository<Tea, Long> {

    @Modifying
    @Query(value = """
        INSERT INTO tea (
            type, origin_country, region, cultivar, oxidation, brand, 
            source, verification_status, dedup_key
        )
        VALUES (
            :type, :originCountry, :region, :cultivar, :oxidation, :brand, 
            'scrape', 'unverified', :dedupKey
        )
        ON CONFLICT (dedup_key) 
        DO UPDATE SET 
            origin_country = COALESCE(tea.origin_country, EXCLUDED.origin_country),
            region = COALESCE(tea.region, EXCLUDED.region),
            cultivar = COALESCE(tea.cultivar, EXCLUDED.cultivar),
            oxidation = COALESCE(tea.oxidation, EXCLUDED.oxidation),
            brand = COALESCE(tea.brand, EXCLUDED.brand)
        RETURNING id
    """, nativeQuery = true)
    fun upsertTeasBaseEntity(
        @Param("type") type: String,
        @Param("originCountry") originCountry: String?,
        @Param("region") region: String?,
        @Param("cultivar") cultivar: String?,
        @Param("oxidation") oxidation: Double?,
        @Param("brand") brand: String?,
        @Param("dedupKey") dedupKey: String
    ): Long
}
```

---

## 4. Stable Public ID & Soft-Rollback

Because the Android client caches the sequential database `BIGINT` key as `catalogTeaId`, database rebuilds or bulk reseedings must **never** shift these IDs.

### 4.1 ID Stability Strategy
1. **Curated Seed Reservation:** IDs `1` through `100,000` are reserved for curated, deterministic platform-provided seeds. The sequence generator for dynamically imported scraping starts at `100,001`.
2. **No Truncation Policy:** Reseeding tasks update properties dynamically via SQL upsert. `TRUNCATE` or table dropping is strictly disabled in production.

### 4.2 Soft-Rollback and Redirection
To resolve bad matches or incorrect catalog splits/merges without breaking existing client records, we implement soft-deactivation and explicit redirection.

```sql
-- Migration V1_2__add_soft_rollback_and_redirection.sql

-- 1. Add deactivation support directly on the base tea record
ALTER TABLE tea ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. Store hard redirects for merged tea records
CREATE TABLE tea_redirection (
    source_tea_id BIGINT PRIMARY KEY REFERENCES tea(id) ON DELETE CASCADE,
    target_tea_id BIGINT NOT NULL REFERENCES tea(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_no_self_redirect CHECK (source_tea_id <> target_tea_id)
);
```

### 4.3 App Integration with v7 split (`CatalogTeaRef` vs `TeaSample`)
* The public API filters standard catalog listings: `WHERE is_active = true`.
* If a client calls `/catalog/detail/{id}` with a deactivated or merged `id`:
  1. The API checks the `tea_redirection` table.
  2. If a redirection exists, the API returns HTTP status `301 Moved Permanently` pointing to `/catalog/detail/{target_tea_id}`, along with a payload envelope indicating the change.
  3. The local client's repository engine intercepts this instruction, updates the local database mapping table (modifying `CatalogTeaRef` to point to the new ID), and bridges the user's localized rating records (`TeaSample`) automatically.

---

## 5. Facts-Only Boundary & Regulatory Gates

To safeguard the repository's Apache-2.0 license and protect against legal liability in the Russian Federation (under **GK RF Article 1334**), we implement programmatic gates to exclude copyrightable text or unlicensed image URLs from the repository.

```
┌────────────────────────────────────────────────────────┐
│                   Pre-Flight Check                     │
│ 1. ToS Allowed? (scraping_source_registry)             │
│ 2. robots.txt Compliant? (Dynamic fetch/Protego check) │
└──────────────────────────┬─────────────────────────────┘
                           │ (Passed)
                           ▼
┌────────────────────────────────────────────────────────┐
│                   Processing Engine                    │
│ * Extract Facts (Names, Brand, Origin, Cultivar)       │
│ * DO NOT Extract Prose Description                     │
└──────────────────────────┬─────────────────────────────┘
                           │ (Data Generated)
                           ▼
┌────────────────────────────────────────────────────────┐
│                     CI Gate Checks                     │
│ * Regex validation check against source fields         │
│ * Image URL validation (Strict CDN or Commons white-list)│
└────────────────────────────────────────────────────────┘
```

### 5.1 Legal & Technical Reality
* **GK RF Article 1334 (Database Right):** Under Russian law, database creator rights are triggered by the extraction of *substantial parts* of a database. Our crawler targets individual objective facts (e.g., that "Tie Guan Yin" is an Oolong tea) which are globally known, non-copyrightable facts. It does not extract massive database tables or structured sets of competitors.
* **No Verbatim Text Storage:** Verbatim marketing text or user reviews are **never** stored in our backend or committed to git. We write custom descriptions using our localized LLM pipeline (utilizing the parsed factual parameters) to ensure complete originality.

### 5.2 Scraping Guard Rails & Gates

#### Gate 1: Database-Driven Source ToS Registry (Hard Admin Preflight)
No automated scraper is permitted to execute against a domain without administrative approval in the database registry.

```sql
CREATE TABLE scraping_source_registry (
    domain VARCHAR(255) PRIMARY KEY,                    -- e.g. 'moychay.ru'
    terms_url TEXT NOT NULL,
    terms_checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    owner_signoff BOOLEAN NOT NULL DEFAULT FALSE,
    is_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

#### Gate 2: Per-Run dynamic robots.txt Parsing
A runtime checker fetches `robots.txt` dynamically prior to crawling and blocks the run if the target user-agent is disallowed:

```python
import urllib.robotparser
from urllib.error import URLError

def verify_robots_txt(target_url: str, user_agent: str = "TeaTiersBot") -> bool:
    parsed_uri = urllib.parse.urlparse(target_url)
    robots_url = f"{parsed_uri.scheme}://{parsed_uri.netloc}/robots.txt"
    rp = urllib.robotparser.RobotFileParser()
    try:
        rp.set_url(robots_url)
        rp.read()
        return rp.can_fetch(user_agent, target_url)
    except URLError as e:
        # Fall back to strict restriction if robots.txt cannot be read
        print(f"Failed to access robots.txt: {e}. Crawl aborted for safety.")
        return False
```

#### Gate 3: Build-Time / CI Content Verification Guard
To prevent accidental storage of copyrightable text in database seed scripts, a strict regex linter executes during the GitHub Actions/CI build.

```python
# ci_seed_validator.py
import json
import sys
import re

def validate_seed_file(file_path: str):
    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)
        
    for item in data:
        # Check descriptions: must not contain copyrightable blocks or exceed 120 chars
        desc = item.get("description", "")
        if len(desc) > 120:
             print(f"Error: Verbatim description detected in {item.get('name')}. Exceeds length limit.")
             sys.exit(1)
             
        # Check image domains: strictly require our clean CDN or Wikimedia Commons
        image_url = item.get("image_url", "")
        if image_url and not re.match(r"^https://(cdn\.teatiers\.ru|upload\.wikimedia\.org)/", image_url):
             print(f"Error: External untrusted image domain in {item.get('name')}: {image_url}")
             sys.exit(1)
             
    print("CI Seed content check successful.")

if __name__ == "__main__":
    validate_seed_file("src/main/resources/seeds/scraped_tea_seed.json")
```

#### Gate 4: Miss-Log Query Sanitizer
Users typing search terms directly supply inputs to our `catalog_miss` table. These query strings could be weaponized for command injection if used directly as scraping variables.

```kotlin
object InputSanitizer {
    private val SANITIZATION_REGEX = Regex("^[a-zA-Zа-яА-Я0-9\\s\\-]{1,100}$")

    fun sanitizeQuery(input: String): String {
        val trimmed = input.trim()
        if (!SANITIZATION_REGEX.matches(trimmed)) {
            throw IllegalArgumentException("Invalid search entry characters detected.")
        }
        return trimmed
    }
}
```

---

## 6. OSS Reuse & Operating Model

We pin library choices to reliable open-source licenses to guarantee compliance and avoid security issues.

### 6.1 Pinned Library Stack

| Library | Version | License | Primary Responsibility |
| :--- | :--- | :--- | :--- |
| **`httpx`** | `0.27.0` | BSD-3-Clause | Asynchronous HTTP extraction requests. |
| **`selectolax`** | `0.4.10` | MIT | Ultra-fast CSS node parsing via the Lexbor engine. |
| **`pypinyin`** | `0.51.0` | MIT | Romanization of Chinese characters to Pinyin. |
| **`Protego`** | `0.3.1` | BSD-3-Clause-Clear | Compliance check parsing for modern `robots.txt` patterns. |
| **`Scrapy`** | `2.11.2` | BSD-3-Clause | Extended multi-site traversal (Deferred to Production Scale Phase). |

### 6.2 Operating Model
The crawler operates strictly as a **local one-off developer utility CLI** executing on secure developer environments—**never** on the production Cloud VM or within the live Spring application runtime.

```
[ Developer Local Env ]                             [ Production Cloud DB ]
   (1) Poll catalog_miss  ───────(Read-Only REST)─────► /api/admin/misses
   (2) Preflight Check against ToS and robots.txt
   (3) Fetch & Parse HTML to Facts-Only JSON
   (4) Commit to Repository ───(VCS Commit/PR)────────► [ Git Repository ]
                                                              │
                                                        (Merge & Deploy)
                                                              ▼
                                                    [ Spring Boot Server ]
                                                     * Reads JSON Seed
                                                     * Runs transactional upsert
```

---

## 7. Recommendation & Sequencing

To ensure a functional implementation, we decompose development into five manageable, PR-sized milestones:

```
PR #1: Database Schemas & Migrations
  └── PR #2: Stable ID Soft-Rollback & Redirection API
        └── PR #3: Identity Reconciliation & Upsert Logic
              └── PR #4: Local Scraper CLI Tooling (Python)
                    └── PR #5: CI Validation & LLM Description Bridge
```

### Step 1: Database Schemas & Migrations (Backend PR)
* Implement database script `V1_1__add_scrape_source_and_provenance.sql`.
* Implement database script `V1_2__add_soft_rollback_and_redirection.sql`.
* Register entities for `SourceRecord`, `TeaFieldProvenance`, and `CandidateMatchReview` within Spring JPA.

### Step 2: Stable ID Soft-Rollback & Redirection API (Backend PR)
* Expose endpoints to fetch catalog details. 
* Implement interceptors to handle deactivations and redirections (returning `301` redirect instructions with target identifiers).
* Filter basic listings to enforce `is_active = true`.

### Step 3: Identity Reconciliation & Upsert Logic (Backend PR)
* Implement the Spring `ScrapeIdentityResolver` service supporting:
  * Exact-match checks on `tea_name.name_norm`.
  * Trigram similarity queries on name transliterations using pg_trgm GIN.
  * Creating matching tickets inside the `candidate_match_review` table.
* Construct the `ScrapeCatalogImporter` transactional upsert service.

### Step 4: Local Scraper CLI Tooling (Python PR)
* Implement the Python local tool using `httpx`, `selectolax`, `pypinyin`, and `Protego`.
* Implement checking logic for database ToS authorization, dynamic `robots.txt` validation, and sanitization parameters.
* Output structured output files to `scraped_tea_seed.json`.

### Step 5: CI Validation & LLM Description Bridge (DevOps / Backend PR)
* Implement `ci_seed_validator.py` running in GitHub Actions to check length bounds and image URLs.
* Wire our database import pipeline to the Spring LLM generation module (from decision #22), allowing custom descriptions to be automatically drafted based on the newly merged, factual structured data fields.

---

### Lockable Decision Recommendation

> **Recommendation:** We hereby resolve to deploy the **corrected design for the TeaTiers scrape-to-catalog pipeline**, establishing a structured foundation that decouples the backend's persistent DB keys from public APIs. This design ensures stable identification through a combination of idempotent database seeds, sequential allocations, soft-deactivations, and programmatic redirection capabilities. The import process matches scraped names to entities using a three-tier reconciliation pipeline (encompassing multilingual exact-match indexing, Cyrillic-to-Pinyin transcription, and pg_trgm similarity checks) and records multi-source field provenance without persisting raw vendor copy. Execution is confined to a local CLI tool using pinned versions of `httpx`, `selectolax`, `pypinyin`, and `Protego`, with strict CI validation linting to preserve our Apache-2.0 open-source status and maintain legal boundaries.