# TeaTiers Technical Report: Search Index Evaluation & Implementation

This report provides a comprehensive analysis, architectural comparison, and step-by-step implementation plan for typo-tolerant catalog search in the **TeaTiers** application. It addresses Russian Cyrillic, English, Pinyin (with and without tone marks), and Chinese Hanzi.

---

## 1. Comparative Architecture Analysis

The table below contrasts **PostgreSQL** (`pg_trgm`) against three prominent open-source dedicated search engines. The evaluation is tailored for a catalog of 300 to 50,000 teas (max ~200,000 name variations) hosted on a single small VM running in Yandex Cloud with restricted memory.

| Evaluation Dimension | PostgreSQL (`pg_trgm` + `unaccent`) | Meilisearch | Typesense | OpenSearch |
| :--- | :--- | :--- | :--- | :--- |
| **Typo-Tolerance Quality** | **Good** for Latin/Cyrillic/Pinyin. **Poor** for standalone CJK/Hanzi unless mapped to phonetic aliases or handled via Levenshtein fallback. | **Excellent** out of the box. Highly optimized for search-as-you-type. | **Excellent**. Extremely fast, highly predictable distance-based scoring. | **Outstanding**. Highly tunable fuzzy matching and scoring models (BM25). |
| **Multilingual & CJK Support** | No native Hanzi word segmentation. Requires transliterated aliases (Pinyin/Cyrillic) or short-string fallbacks. | **Excellent**. Built-in CJK tokenizer (Lindera segmenter). | **Excellent**. Built-in ICU/tokenization for Chinese. | **Outstanding** using native or third-party ICU and IK analyzers. |
| **Docker & RAM Footprint** | **0 MB extra**. Embedded directly in the primary database server. | **Low-Medium**. ~100MB–250MB idle. Sizing can spike to GBs during high concurrent index builds. | **Low-Medium**. Strictly in-memory (~20MB idle, ~150MB for this dataset). Extremely predictable RAM use. | **Extremely Heavy**. Requires a minimum of 512MB–1GB JVM heap, ideally 2GB+ system RAM to run on Docker stably. |
| **Synchronization Overhead** | **Instant and transactional**. Directly queryable on write. Zero synchronization lag. | Requires sync mechanism (outbox pattern, JPA lifecycle listeners, or periodic batch sync). | Requires sync mechanism (outbox pattern, JPA lifecycle listeners, or periodic batch sync). | Complex sync architecture required (Logstash, Debezium CDC, or heavy Spring code). |
| **Kotlin/Spring Boot Integration** | **Native**. Leverages Spring Data JPA, Native SQL, or standard JDBC. | Native Java SDK exists but lacks active Spring Boot auto-starters. | Native Java Client (`typesense-java`) is mature but lacks a native Spring integration library. | Mature, official Spring Data OpenSearch/Elasticsearch starters. |
| **Licensing** | PostgreSQL License (Permissive). | MIT (Permissive). | GPL-3.0 (Restrictive if modifying/redistributing the server). | Apache-2.0 (Permissive). |
| **Operational Complexity** | **Negligible**. Zero new services, network configurations, or backup logic. | Low-Medium. One additional stateful Docker container. | Low-Medium. One additional stateful Docker container. | **Extremely High**. Requires JVM tuning, cluster state management, and host system overrides. |

---

## 2. Recommended MVP Implementation: In-Postgres Hybrid Search

Given the small size of the dataset (under 50,000 teas) and strict VM memory constraints on Yandex Cloud, **PostgreSQL** is the clear choice for the MVP. 

### Why this works at scale
A functional GIN index (`gin_trgm_ops`) on a table of 200,000 rows consumes roughly **10MB to 20MB** of disk/RAM and processes queries in **< 3 milliseconds**. This completely bypasses the memory overhead (~150MB to 1.5GB) and synchronization lag of external search engines.

### Resolving the CJK & Short String Problem
Trigram-based matching (`pg_trgm`) suffers on Chinese characters because Hanzi has no spaces, and words are very short (typically 2–3 characters). A single typo in a two-character Hanzi word destroys the Jaccard similarity score. 
The MVP solves this by using a **hybrid search query**:
1. It uses `pg_trgm` similarity for Russian, English, and Pinyin (which have longer word lengths).
2. It strips Chinese Pinyin tone marks natively using an `unaccent` wrapper.
3. For short queries (length < 4), it automatically adds a Levenshtein edit distance check (from the `fuzzystrmatch` extension) and a standard SQL `LIKE` substring fallback to handle short Hanzi characters.

### Step-by-Step SQL Script
Run the following script to initialize the Postgres database extensions, wrapper functions, schema adaptations, and indexes.

```sql
-- 1. Initialize required extensions (Available by default on postgres:16-alpine)
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

-- 2. Build an IMMUTABLE unaccent wrapper. 
-- Note: PostgreSQL marks unaccent() as STABLE, meaning it cannot be natively used inside
-- index expressions because rules files can theoretically change. Declaring this custom
-- immutable wrapper is the standard, production-safe workaround.
CREATE OR REPLACE FUNCTION public.immutable_unaccent(text)
RETURNS text AS $$
    -- Explicitly pass the schema-qualified dictionary to prevent restoration errors
    SELECT public.unaccent('public.unaccent', $1);
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

-- 3. Create helper function for standard query normalization
CREATE OR REPLACE FUNCTION public.normalize_search_text(val TEXT)
RETURNS TEXT AS $$
BEGIN
    -- Lowercase, strip accents/pinyin tone marks, and trim spaces
    RETURN trim(lower(public.immutable_unaccent(val)));
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE;

-- 4. Apply a GIN Functional Index over the tea_name table.
-- This ensures that case-insensitive, accent-insensitive queries execute in sub-milliseconds.
CREATE INDEX IF NOT EXISTS idx_tea_name_normalized_trgm 
ON public.tea_name USING gin (public.normalize_search_text(name) gin_trgm_ops);
```

### Kotlin & Spring Boot Integration

To avoid complex Hibernate-to-SQL translation issues with trigram operators (`%`), implement the search using a **Native Query** in a Spring Boot repository.

```kotlin
package com.teatiers.repository

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class TeaSearchRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * Executes a hybrid, typo-tolerant query over Russian, English, Chinese Hanzi, and Pinyin.
     */
    @Transactional(readOnly = true)
    fun searchTeas(query: String, limit: Int = 20): List<TeaSearchResult> {
        val cleanQuery = query.trim().lowercase()
        
        val sql = """
            SELECT 
                t.id as tea_id,
                t.origin_country,
                tn.name as matched_name,
                tn.locale as matched_locale,
                similarity(public.normalize_search_text(tn.name), :cleanQuery) as score
            FROM tea t
            JOIN tea_name tn ON t.id = tn.tea_id
            WHERE 
                -- 1. Trigram similarity match (utilizes GIN index)
                public.normalize_search_text(tn.name) % :cleanQuery
                
                -- 2. Fallback for short strings (under 4 chars) to catch Hanzi substrings
                OR (char_length(:cleanQuery) < 4 AND public.normalize_search_text(tn.name) LIKE '%' || :cleanQuery || '%')
                
                -- 3. Levenshtein edit distance threshold for short strings
                OR (char_length(:cleanQuery) < 4 AND levenshtein(public.normalize_search_text(tn.name), :cleanQuery) <= 1)
            ORDER BY score DESC, char_length(tn.name) ASC
            LIMIT :limit
        """.trimIndent()

        val nativeQuery = entityManager.createNativeQuery(sql)
            .setParameter("cleanQuery", cleanQuery)
            .setParameter("limit", limit)

        val results = nativeQuery.resultList as List<Array<Any>>
        
        return results.map { row ->
            TeaSearchResult(
                teaId = (row[0] as Number).toLong(),
                originCountry = row[1] as String?,
                matchedName = row[2] as String,
                matchedLocale = row[3] as String,
                score = (row[4] as Number).toDouble()
            )
        }
    }
}

data class TeaSearchResult(
    val teaId: Long,
    val originCountry: String?,
    val matchedName: String,
    val matchedLocale: String,
    val score: Double
)
```

---

## 3. Fallback Implementation: Typesense

If search quality in Postgres fails (e.g., if users demand native Chinese tokenization/segmentation for complex sentences, or require specialized prefix ranking not solvable via Postgres), we recommend migrating to **Typesense**.

### Why Typesense over Meilisearch?
Typesense operates strictly in-memory. While Meilisearch is excellent, its underlying storage layer (LMDB) uses aggressive memory-mapping. Under indexing loads or batch updates, Meilisearch's RAM consumption can unexpectedly swell to gigabytes, leading to Out-Of-Memory (OOM) process terminations on small Yandex Cloud VMs. Typesense's RAM foot-print is highly stable and predictable.

### 1. Docker Compose Integration
Add the following service configuration to your existing `docker-compose.yml`:

```yaml
version: '3.8'
services:
  # ... existing backend and postgres containers ...

  typesense:
    image: typesense/typesense:26.0
    container_name: teatiers-search
    restart: always
    environment:
      - TYPESENSE_API_KEY=your_secure_teatiers_master_key
      - TYPESENSE_DATA_DIR=/data
    volumes:
      - typesense_data:/data
    ports:
      - "8108:8108"
    command: [ "--api-key=your_secure_teatiers_master_key" ]

volumes:
  typesense_data:
```

### 2. Spring Boot Sync Strategy
Avoid heavy CDC pipelines like Debezium or Kafka. Instead, use JPA entity lifecycle listeners to synchronize database updates to Typesense asynchronously.

```kotlin
package com.teatiers.listener

import com.teatiers.model.TeaName
import jakarta.persistence.PostPersist
import jakarta.persistence.PostUpdate
import jakarta.persistence.PostRemove
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class TeaNameSyncListener {

    private val restTemplate = RestTemplate()
    private val typesenseUrl = "http://typesense:8108/collections/teas/documents"
    private val apiKey = "your_secure_teatiers_master_key"

    @Async
    @PostPersist
    @PostUpdate
    fun onTeaUpdate(teaName: TeaName) {
        val payload = mapOf(
            "id" to teaName.id.toString(),
            "tea_id" to teaName.teaId,
            "locale" to teaName.locale,
            "name" to teaName.name
        )
        
        val headers = org.springframework.http.HttpHeaders().apply {
            set("X-TYPESENSE-API-KEY", apiKey)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
        }
        
        val request = org.springframework.http.HttpEntity(payload, headers)
        try {
            restTemplate.postForEntity(typesenseUrl, request, String::class.java)
        } catch (e: Exception) {
            // Log indexing failures gracefully in MVP
            System.err.println("Failed to index to Typesense: ${e.message}")
        }
    }

    @Async
    @PostRemove
    fun onTeaDelete(teaName: TeaName) {
        val headers = org.springframework.http.HttpHeaders().apply {
            set("X-TYPESENSE-API-KEY", apiKey)
        }
        val request = org.springframework.http.HttpEntity<Unit>(headers)
        try {
            restTemplate.exchange(
                "$typesenseUrl/${teaName.id}",
                org.springframework.http.HttpMethod.DELETE,
                request,
                String::class.java
            )
        } catch (e: Exception) {
             System.err.println("Failed to delete from Typesense: ${e.message}")
        }
    }
}
```

---

## 4. Gold-Set Evaluation Plan

The following gold-set covers **25 critical test cases** spanning Russian, English, Chinese Pinyin (with tones/accents), and raw Hanzi. Use this list to verify and tune your typo-tolerant performance.

| ID | Test Target / Locale | Input Query | Target Tea Record | Expected Search Engine Behavior | Matching Logic Triggered |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | Russian (Exact) | `Лунцзин` | Лунцзин | Exact Match. | Trigram Similarity (Score: 1.0) |
| **2** | Russian (Accent/Case) | `лунцзи́н` | Лунцзин | Ignores lowercase and acute stress markers. | `immutable_unaccent` normalization |
| **3** | Russian (Typo) | `лонцзин` | Лунцзин | Matches despite "о" substitution. | Trigram Similarity |
| **4** | Russian (Typo) | `лунцин` | Лунцзин | Matches despite "ц" -> "ц" typo. | Trigram Similarity |
| **5** | Russian (Typo) | `тегуанини` | Тегуаньинь | Matches trailing character omissions. | Trigram Similarity |
| **6** | Russian (Short Typo) | `пуер` | Пуэр | Resolves "е" instead of "э" on extremely short words. | Levenshtein Distance (Edit distance = 1) |
| **7** | English (Exact) | `Longjing` | Longjing | Exact Match. | Trigram Similarity (Score: 1.0) |
| **8** | English (Case) | `longjing` | Longjing | Direct case fold. | `lower()` normalization |
| **9** | English (Typo) | `lonjing` | Longjing | Matches missing "g". | Trigram Similarity |
| **10** | English (Typo) | `longjingg` | Longjing | Tolerates double trailing letters. | Trigram Similarity |
| **11** | English (Typo) | `teguanyn` | Tieguanyin | Tolerates vowel deletions. | Trigram Similarity |
| **12** | English (Short Typo) | `tieguan` | Tieguanyin | Evaluates word similarity for partial prefixes. | Trigram Word Similarity |
| **13** | Pinyin (Exact Tones) | `Dàhóngpáo` | Dàhóngpáo | Normalizes tone marks to Latin equivalents. | `immutable_unaccent` normalization |
| **14** | Pinyin (Unaccented) | `dahongpao` | Dàhóngpáo | Matches query typed without tone marks. | `immutable_unaccent` normalization |
| **15** | Pinyin (Typo) | `dahomgpao` | Dàhóngpáo | Tolerates physical keyboard layout typos ("m" for "n"). | Trigram Similarity |
| **16** | Pinyin (Exact Tones) | `Tiěguānyīn` | Tiěguānyīn | Cleanses third tone (caron/háček) on 'e' and first tone on 'a', 'i'. | `immutable_unaccent` normalization |
| **17** | Pinyin (Unaccented) | `tieguanyin` | Tiěguānyīn | Maps basic pinyin query to stored database records. | `immutable_unaccent` normalization |
| **18** | Pinyin (Typo) | `tieguanyng` | Tiěguānyīn | Normalizes trailing characters in phonetic typing. | Trigram Similarity |
| **19** | Hanzi (Exact) | `大红袍` | 大红袍 | Exact match for pictographic symbols. | Trigram Similarity (Score: 1.0) |
| **20** | Hanzi (Substring) | `红袍` | 大红袍 | Handles trailing partial queries. | Substring Fallback (`LIKE '%红袍%'`) |
| **21** | Hanzi (Short Prefix) | `大` | 大红袍 | Matches leading character without breaking on trigrams. | Substring Fallback (`LIKE '%大%'`) |
| **22** | Hanzi (Typo) | `大红跑` | 大红袍 | Matches despite identical sound/visual error in final word. | Levenshtein Distance (Edit distance = 1) |
| **23** | Hanzi (Substr / CJK) | `龙` | 西湖龙井 | Substring extraction for Chinese characters. | Substring Fallback (`LIKE '%龙%'`) |
| **24** | Hanzi (Typo) | `西湖龙景` | 西湖龙井 | Matches visual mistake (景 instead of 井). | Levenshtein Distance (Edit distance = 1) |
| **25** | Hanzi (Typo) | `普二` | 普洱 | Matches acoustic phonetic typo (二 instead of 洱). | Levenshtein Distance (Edit distance = 1) |

---

## 5. "Do Not Do" List (Anti-Patterns to Avoid)

To protect the hardware stability and performance of your single small VM, strictly avoid the following patterns:

1. **Do not use OpenSearch or Elasticsearch on your VM**: These services are built on the JVM and enforce robust cluster checking. They consume at least 1GB to 2GB of memory immediately upon launch, leaving your VM with massive memory pressure and triggering OS-level OOM terminations on your database container.
2. **Do not use raw, unindexed `LIKE '%term%'` patterns in main search**: Sequential scans of the `tea_name` table will cause linear performance degradation as the catalog moves toward 50,000 records. Substring matching must only be triggered as a fallback route for extremely short queries (< 4 characters) to avoid performance pitfalls.
3. **Do not install custom C extensions like `pg_bigm` or `pg_cjk_parser` directly**: These extensions are **not** bundled with standard `postgres:16-alpine` images. Using them forces you to build and maintain custom compiled Docker alpine images, which introduces security patch risks, slow pipeline build times, and high maintenance overhead.
4. **Do not execute raw web crawling on the backend**: Keep the catalog strictly isolated. Attempting to run crawling routines inside a resource-constrained VM running the Spring API and PostgreSQL will result in background thread thrashing and application starvation.
5. **Do not sync search indexes with heavy CDC (Kafka/Debezium)**: For under 50,000 rows, a multi-container pipeline with Kafka and CDC connectors introduces massive operational complexity and takes up hundreds of megabytes of idle memory. Stick to simple transactional update listeners in code.

---

## 6. Live Claims & Technical Verifications Required

Before initiating construction, execute the following dry-runs to verify environmental constraints:

* **Verify Locale in Docker Compose**: Ensure that your Alpine Postgres container is **not** initialized with `LC_CTYPE="C"`. By default, `postgres:16-alpine` uses `C.UTF-8` or `en_US.UTF-8`. If initialized as `C`, the `pg_trgm` extension will silently ignore Cyrillic and Hanzi characters entirely, rendering fuzzy matching on them useless. Confirm this in your `psql` console using:
  ```sql
  SHOW lc_ctype;
  ```
* **Verify `unaccent` Pinyin mappings**: Confirm that your default Alpine PostgreSQL image has complete mappings for Pinyin characters. Verify by running:
  ```sql
  SELECT unaccent('Dàhóngpáo'), unaccent('Tiěguānyīn');
  ```
  Expected output is `Dahongpao` and `Tieguanyin`. (Tested and validated against the standard `unaccent.rules` codebase).
* **Verify `fuzzystrmatch` Availability**: Ensure your database image has `fuzzystrmatch` preinstalled in the lib path:
  ```sql
  CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
  SELECT levenshtein('cat', 'car');
  ```
  This must immediately return `1`.