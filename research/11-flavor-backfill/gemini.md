# Technical Report: Automated Flavor-Profile Backfill Strategy for TeaTiers

**Project:** TeaTiers  
**Subject:** Feasibility, Architecture, and Evaluation of a Python-based Flavor-Backfill Model vs. Alternative Catalog Ingestion Strategies  
**Date:** June 17, 2026  

---

## 1. Comparison of Candidate Approaches

To scale the TeaTiers flavor profile database from a few hundred seed teas up to a catalog of 300 to 50,000 teas, we evaluate five distinct architectural patterns. Each candidate is analyzed across accuracy, operating costs (OPEX), development/operational complexity, legal/provenance risk, and failure modes.

| Candidate Approach | Accuracy & Detail | Operational Cost (OPEX) | Complexity / Dev Effort | Legal & Provenance Risk | Expected Failure Modes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Rule-Based / Heuristic Baseline** *(JVM-native matching on tea type & origin)* | **Low.** Assigns static profiles based on broad classifications (e.g., all "Assam Black" get the exact same vector). | **Near Zero.** Executes locally in-JVM via Kotlin/Spring Boot. No API dependency. | **Very Low.** Simple pattern-matching blocks and database lookup maps. | **Zero.** Built on purely logical rules. No external data dependencies or copyright issues. | Misses nuances; fails for rare variants; assigns identical, generic vectors to highly distinct single estates. |
| **2. Batch LLM Pipeline** *(Yandex Alice Flash / Qwen3 booster over Wikipedia extracts)* | **High.** Captures nuanced descriptions, understands structural intent, handles multilingual text directly. | **Medium-High.** Linear cost scaling per API token call. Approx. $50–$150 to resolve 50,000 teas. | **Medium.** Reuses existing `/resolve` endpoint; requires a batch worker with queue management. | **Low.** Restricts input to CC0 (Wikidata) and CC-BY-SA 4.0 (Wikipedia) text with stored attribution URLs. | Hallucination of unmentioned traits; JSON schema formatting failures; API timeout/rate-limiting blockages. |
| **3. Text Embeddings + Regression** *(Python: sentence-transformers + Scikit-Learn Ridge)* | **Medium-High.** Projects text to dense semantic vectors; maps descriptions to the 11 flavor dimensions. | **Very Low.** Locally hosted, lightweight CPU-bound inference (no GPU required for linear models). | **High.** Requires setting up a Python service, model artifact registry, and custom build pipelines. | **Very Low.** Uses open-weight sentence embeddings and internally owned, hand-authored curated seed profiles. | Extrapolations outside the [0.0, 5.0] target range; "garbage-in/garbage-out" on low-quality/stub Wikipedia articles. |
| **4. Supervised Tabular Model** *(Python: CatBoost/XGBoost on categorical properties)* | **Medium-Low.** Fails to capture narrative flavor descriptions. Relies solely on sparse, discrete taxonomy. | **Very Low.** Fast CPU inference of tabular data models. | **High.** Massive overhead for categorical feature engineering, missing-value imputation, and target-encoding. | **Zero.** Trained purely on open Wikidata attributes and proprietary seed vectors. | Extreme overfitting to rare categories; collapses to average values when encountering unseen origins. |
| **5. Fine-Tuning a Custom Transformer** *(e.g., DeBERTa-v3 or Llama-3-8B-Instruct)* | **High (Potential).** Tailored specifically to tea lexicon and flavor-profile generation tasks. | **Very High.** Requires GPU clusters for training and dedicated high-RAM VPS for ongoing inference. | **Extremely High.** Complex ML lifecycle, prompt tuning, hyperparameter sweep, and specialized infrastructure. | **Medium.** High risk of learning and outputting copyrighted texts; potential license contamination. | Catastrophic forgetting; over-fitting to the tiny curated dataset; high API latency or serving timeouts. |

### Architectural Trade-offs for Scale (300 to 50,000 Teas)
- **Financial & API Limits:** At 300 teas, batch LLM processing is trivial (~$0.50). At 50,000 teas, calling a commercial LLM API for every row is cost-prohibitive for a small bootstrap project and hits severe rate limits. A local Python embedding model or rule baseline costs nothing in API fees.
- **Data Scarcity Constraints:** Small supervised models (Approaches 3 and 4) require significant "gold" training data. Training a model with 11 continuous targets on fewer than 500 hand-authored profiles will lead to immediate overfitting. This makes building a standalone custom ML model highly premature at launch.

---

## 2. Recommended MVP Path and Post-MVP Path

### Data Ingestion and Source Availability Analysis

Before choosing a route, we must evaluate what data we can legally and reliably ingest without unauthorized scraping:

1. **Wikidata (CC0 License):** 
   - *Utility:* High-accuracy taxonomic labels. We query `P31` (instance of) / `P279` (subclass of) to identify tea varieties, `P495` (country of origin), `P186` (material used), and localized labels/aliases in `en`, `ru`, `zh-Hans`, and Pinyin.
   - *Limitation:* Extremely sparse descriptive text. Wikidata will not contain sensory descriptors like "smoky" or "astringent" for 99% of entries.
2. **Wikipedia Text Extracts (CC-BY-SA 4.0 License):**
   - *Utility:* High semantic density. The introductory paragraphs from Wikipedia contain crucial taste, processing, and cultural profiles.
   - *Limitation:* Only available for major tea varieties (approx. 500–1,500 teas globally).
3. **Open Food Facts (ODbL 1.0 License):**
   - *Utility:* Highly structured. However, OFF is heavily skewed toward commercial, packaged consumer goods (e.g., mass-market tea bags, ready-to-drink iced teas) rather than artisanal, single-origin whole-leaf teas.
   - *Limitation:* The **ODbL (Open Database License)** is highly viral. If we merge or create derivative tables combining OFF data directly with our hand-authored seed database, we may be legally compelled to release the entire TeaTiers proprietary database under ODbL.
4. **User-Pasted Text:**
   - *Utility:* High. Users paste a vendor's descriptive paragraph on-demand in the client app.
   - *Limitation:* Must be parsed in real-time, requiring a fast, lightweight API resolver.

---

### The Recommended MVP Path: Hybrid Heuristic + Batch LLM

For the initial launch (up to 5,000 teas), **do not build a custom Python model**. Building a dedicated machine learning pipeline at this stage is a classic over-engineering trap. Instead, implement a **Hybrid Heuristic + Selective Batch LLM Pipeline** directly within the Kotlin/Spring Boot application backend.

```
                  ┌──────────────────────────────────────────────┐
                  │          Inbound Tea Catalog Ingest          │
                  └──────────────────────┬───────────────────────┘
                                         │
                                         ▼
                        /─────────────────────────────────\
                       <  Does Wikipedia description text  >
                       <      exist in local database?     >
                        \─────────────────────────────────/
                                  /               \
                            YES  /                 \  NO
                                /                   \
                               ▼                     ▼
               ┌──────────────────────────────┐     ┌──────────────────────────────┐
               │  Enqueue for Async LLM       │     │ Apply Kotlin-based           │
               │  Batch Processing (Qwen3)    │     │ Rule/Heuristic Baseline      │
               └──────────────┬───────────────┘     └──────────────┬───────────────┘
                              │                                    │
                              ▼                                    ▼
               ┌──────────────────────────────┐     ┌──────────────────────────────┐
               │ Target: Granular, Nuanced    │     │ Target: Fallback Profile     │
               │ Flavor Profile (Unverified)  │     │ mapped to Tea Class (Static) │
               └──────────────────────────────┘     └──────────────────────────────┘
```

#### MVP Execution Steps:
1. **The Heuristic Baseline Engine (Kotlin):**
   Write a deterministic rule-engine in Spring Boot that maps a tea's resolved taxonomy (e.g., Broad Green Tea, Shou Puerh, Wuyi Rock Oolong) to a baseline flavor profile vector based on known styles. This guarantees **100% catalog coverage** on Day 1.
2. **The Selective LLM Ingestion Queue (Yandex Alice / Qwen3):**
   For teas that possess rich Wikipedia extracts or high-priority user interest, route them asynchronously to the existing `/resolve` LLM prompt pipeline. Since the prompting, JSON-schema validation, and parsing logic are already built, we reuse this pipeline as a backend cron-driven batch process, swapping out heuristic profiles with high-quality, LLM-generated flavor estimates.

---

### The Recommended Post-MVP Path: Semantic Text Regression

Once the catalog approaches **10,000+ entries** and we have collected **$N \ge 1,000$ hand-authored, curated, or user-verified gold-standard flavor profiles**, transition to a local, lightweight Python machine learning model.

#### Proposed Post-MVP Technical Stack:
- **Language Environment:** Python `3.11` / `3.12`
- **Core ML & Data Libraries:**
  - `numpy==2.2.3`
  - `pandas==2.2.3`
  - `scikit-learn==1.9.0` (for ML pipeline wrappers and baseline estimators)
  - `sentence-transformers==5.6.0` (for high-efficiency multilingual text vectorization)
  - `lightgbm==4.6.0` (for non-linear multi-target regression fallback)
- **Proposed Architecture:**
  1. **Feature Extraction:** Concatenate English, Russian, and Chinese names/descriptions into a single structured string. Vectorize this string using a robust, lightweight multilingual embedding model such as `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` or `intfloat/multilingual-e5-base`. This yields a dense 768-dimensional feature vector representing the semantic characteristics of the tea.
  2. **Model Training:** Train a `MultiOutputRegressor` using `scikit-learn`. The base estimator should be a simple **Ridge Regression** or **ElasticNet** with $L_1/L_2$ regularization to prevent overfitting on the high-dimensional embeddings given the relatively small training dataset size.
  3. **Target Post-Processing:** Because flavor profiles are bounded in $[0.0, 5.0]$, wrap the estimator outputs in a clipping layer: `np.clip(predictions, 0.0, 5.0)`.

#### Critical Prerequisites to Research Before Writing Code:
1. **Taxonomic Linkage Verification:** Verify if the Wikidata SPARQL queries reliably extract localized subclass values (`P279`) for broad regional classes (e.g., verifying that "Anxi Tieguanyin" resolves as a subclass of "Oolong").
2. **Semantic Representation Audit:** Verify how well the `multilingual-e5-base` embedding model maps transliterated Pinyin terms (e.g., "Sheng Pu-erh", "Da Hong Pao") to their localized counterparts ("生普洱", "大红袍"). Run a small cosine-similarity test in Python before committing to the architecture.
3. **Storage Strategy for Vector Embeddings:** Evaluate whether the primary PostgreSQL instance requires the `pgvector` extension to handle similarity matching, or if flat metadata tables are sufficient.

---

## 3. Data Model and Provenance Additions

To safely handle unverified automated backfills without corrupting curated or user-customized entries, the database schema must strictly record data provenance, confidence metrics, and source fingerprints.

### Proposed PostgreSQL DDL Additions

```sql
-- Track the specific process or model that generated the profile
CREATE TYPE flavor_provenance AS ENUM (
    'CURATED',           -- Hand-authored gold standard (protected)
    'USER_OVERRIDE',     -- User-customized values in client DB (protected)
    'LLM_BATCH',         -- Generated via batch Alice/Qwen LLM runs
    'HEURISTIC_RULE',    -- Derived from static class-based rule engine
    'PYTHON_ML_MODEL'    -- Generated via Python regression model
);

-- Meta tracking table for flavor profiles
CREATE TABLE tea_flavor_provenance (
    tea_id UUID PRIMARY KEY REFERENCES tea(id) ON DELETE CASCADE,
    provenance_type flavor_provenance NOT NULL,
    model_version VARCHAR(50) NOT NULL,            -- e.g., 'qwen3-booster-v1.2', 'heuristic-v1.0'
    overall_confidence NUMERIC(3,2) NOT NULL,      -- Value from 0.00 to 1.00
    source_text_fingerprint CHAR(64) DEFAULT NULL, -- SHA-256 hash of the input source text
    enriched_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_confidence CHECK (overall_confidence BETWEEN 0.00 AND 1.00)
);

-- Adjust core tea_flavor to handle confidence indicators and lock states
ALTER TABLE tea_flavor ADD COLUMN IF NOT EXISTS dimension_confidence NUMERIC(3,2) DEFAULT 1.00;
ALTER TABLE tea_flavor ADD CONSTRAINT chk_dim_confidence CHECK (dimension_confidence BETWEEN 0.00 AND 1.00);
```

### Re-run, Backfill, and Migration Strategy
When a new model is trained or a rule engine version is deployed, we must execute a clean backfill without overwriting hand-curated or user-modified entries.

```sql
-- Safe update pattern: Purge and re-calculate only automated/unverified profiles
BEGIN;

-- 1. Identify and delete deprecated automated profiles
DELETE FROM tea_flavor
WHERE tea_id IN (
    SELECT tea_id 
    FROM tea_flavor_provenance 
    WHERE provenance_type IN ('HEURISTIC_RULE', 'LLM_BATCH', 'PYTHON_ML_MODEL')
      AND model_version != 'new-model-version-v2.0'
);

DELETE FROM tea_flavor_provenance
WHERE provenance_type IN ('HEURISTIC_RULE', 'LLM_BATCH', 'PYTHON_ML_MODEL')
  AND model_version != 'new-model-version-v2.0';

-- 2. Bulk insert new predictions...
-- (Inserted via Spring Batch or Python engine, ensuring provenance_type = 'PYTHON_ML_MODEL')

COMMIT;
```

---

## 4. Batch Job Architecture

The batch ingestion process operates as an asynchronous pipeline managed within the Spring Boot ecosystem. It queries target items, retrieves content from external source APIs, routes payloads to the verification engine, and commits structured records to PostgreSQL.

```
       +---------------------------------------------------------+
       |                  Spring Batch Ingest                    |
       +----------------------------┬----------------------------+
                                    │
                                    ▼
       +---------------------------------------------------------+
       |   Reader: SQL Query Unenriched/Outdated Tea Stubs      |
       +----------------------------┬----------------------------+
                                    │
                                    ▼
       +---------------------------------------------------------+
       |             Processor: Fetch Wikipedia Extracts        |
       +----------------------------┬----------------------------+
                                    │
                                    ├────────────────────────────┐
                                    ▼                            ▼
                       /─────────────────────────\  FAIL  +-------------+
                      <   Compute SHA-256 Hash    >──────►│ Log to DLQ  │
                       \─────────────────────────/        +-------------+
                                    │
                                    ▼ PASS
       +---------------------------------------------------------+
       |           Execute LLM / ML Prediction Pipeline          |
       +----------------------------┬----------------------------+
                                    │
                                    ▼
       +---------------------------------------------------------+
       |    Writer: Upsert `tea_flavor` & `tea_flavor_provenance`|
       +---------------------------------------------------------+
```

### Ingestion Pipeline Details

#### 1. Ingestion Job Inputs:
- Active database stubs imported from Wikidata where `tea_flavor_provenance` is missing or marked as outdated (`model_version` out of date).
- Target texts fetched from cached Wikipedia intros or active MediaWiki API page retrievals.

#### 2. Process Scheduling:
- Executes as a low-priority Spring Batch cron job or GitHub Actions workflow running on a weekly cadence during off-peak hours.

#### 3. Idempotency & Cache Safety:
- **Fingerprinting:** Before running any flavor estimation, hash the concatenated input text using SHA-256:
  $$\text{Fingerprint} = \text{SHA-256}(\text{English Name} + \text{Russian Name} + \text{Wikipedia Plaintext Extract})$$
- If the calculated fingerprint matches the stored `source_text_fingerprint` in `tea_flavor_provenance` and the active `model_version` has not changed, the item is skipped.

#### 4. Failure Handling, Rate Limiting, and Circuit Breakers:
- **Rate-Limiting Compliance:** Enforce a strict delay bucket on external calls (e.g., maximum 5 requests/second when hitting Wikipedia API endpoints; respect Yandex Cloud hourly quota limits).
- **Circuit Breaker Strategy (Resilience4j):** If the LLM resolver API returns HTTP 429 (Too Many Requests), 5xx (Server Errors), or JSON serialization schema failures consistently, a circuit breaker must trip:
  - **Threshold:** Break execution if the failure rate exceeds 20% in a rolling window of 50 calls.
  - **Action:** Pause the entire batch job run, send an alert email, and write a run log detailing the error trace.
- **Dead Letter Queue (DLQ) & Quarantine:** If a specific tea's description triggers consistent JSON parsing errors or schema validation crashes, write a record to `enrichment_failed_log` detailing the `tea_id`, error context, and timestamp. The main batch pipeline then bypasses this item to prevent blockages.

---

## 5. Evaluation Plan and Release Gates

Before promoting any automated flavor backfill to production, the output must pass a structured suite of metric gates evaluated against a static gold-standard test set.

### 1. The Gold Evaluation Set

The evaluation dataset must consist of **50 hand-curated teas** with verified sensory profiles that cover all major tea categories and represent distinct extreme flavor attributes:

| Category | Example Gold Teas | Expected Flavor Profile Highlights (0.0 to 5.0 scale) |
| :--- | :--- | :--- |
| **Green Tea** | Japanese Sencha, West Lake Longjing | High `GRASSY` ($\ge 4.0$), High `UMAMI` ($\ge 3.5$), Low `ROASTED` ($\le 1.0$), Low `SMOKY` ($0.0$) |
| **Black Tea** | Assam CTC, Yunnan Dian Hong | High `ASTRINGENCY` ($\ge 4.0$), High `BITTERNESS` ($\ge 3.0$), High `SWEETNESS` ($\ge 2.5$) |
| **Oolong Tea** | Anxi Tie Guan Yin, Da Hong Pao | High `FLORAL` ($\ge 4.2$), High `ROASTED` for Da Hong Pao ($\ge 3.8$), Low `GRASSY` ($\le 1.5$) |
| **Dark / Puerh** | Aged Menghai Shou Puerh | High `EARTHY_NUTTY` ($\ge 4.5$), Low `ASTRINGENCY` ($\le 1.0$), Low `BITTERNESS` ($\le 1.0$) |
| **Scented / Herbal** | Yin Hao Jasmine, Chamomile (Pure) | Jasmine: Extreme `FLORAL` ($\ge 4.8$). Chamomile: High `FLORAL` ($\ge 4.0$), *All other dimensions close to 0.0*. |
| **Smoky Edge Case**| Lapsang Souchong (Zheng Shan Xiao Zhong) | Extreme `SMOKY` ($\ge 4.5$), Medium-Low `GRASSY` ($\le 0.5$) |

---

### 2. Quantitative Metric Formulations

To measure accuracy, we calculate the **Mean Absolute Error (MAE)** across the 11 flavor dimensions over the $N=50$ gold-standard teas:

$$\text{MAE}_d = \frac{1}{N} \sum_{i=1}^{N} \left| y_{i,d} - \hat{y}_{i,d} \right|$$

Where:
- $d$ represents one of the 11 flavor dimensions (e.g., `BITTERNESS`).
- $y_{i,d}$ is the hand-authored sensory score for tea $i$.
- $\hat{y}_{i,d}$ is the predicted score generated by the backfill pipeline.

#### Calibration Rules for Extremes (0/5 Check):
Sensory values near the boundaries are critical to user experience. The model must satisfy these strict bounds:
- **Zero-Value Threshold:** If $y_{i,d} = 0.0$ (e.g., `SMOKY` in Japanese Sencha), then $\hat{y}_{i,d}$ must be $\le 0.5$. It must not output random predictions or noise.
- **Extreme-Value Threshold:** If $y_{i,d} = 5.0$ (e.g., `SMOKY` in Lapsang Souchong), then $\hat{y}_{i,d}$ must be $\ge 4.0$.

#### Cross-Lingual Consistency Tests:
Execute matching tests to verify that synonym resolving works. Confirm that processing the names:
- `"龙井茶"` (zh-Hans)
- `"Longjing Tea"` (en)
- `"Колодец Дракона"` (ru)
- `"lóngjǐng chá"` (pinyin)

returns vectors that match each other with a **Cosine Similarity $\ge 0.98$**.

---

### 3. CI/CD Release Gates

To automate testing, run an validation script in the build pipeline prior to database migration deployment:

```
                  ┌──────────────────────────────────────────────┐
                  │            Trigger CI/CD Pipeline            │
                  └──────────────────────┬───────────────────────┘
                                         │
                                         ▼
                        /─────────────────────────────────\
                       <   Gate 1: Is overall MAE < 0.80   >
                       <  & Critical Dim MAE < 0.60?      >
                        \─────────────────────────────────/
                                  /               \
                            YES  /                 \  NO
                                /                   \
                               ▼                     ▼
                        /─────────────────────────\ ┌──────────────────────────────┐
                       <  Gate 2: Are zero/five   > │ Fail Build: Reject Model     │
                       <  boundaries respected?   > │ Artifacts                    │
                        \─────────────────────────/ └──────────────────────────────┘
                                  /               \
                            YES  /                 \  NO
                                /                   \
                               ▼                     ▼
               ┌──────────────────────────────┐     ┌──────────────────────────────┐
               │ Proceed: Export Sqlite / DB  │     │ Fail Build: Reject Model     │
               │ Migration to Android Client  │     │ Artifacts                    │
               └──────────────────────────────┘     └──────────────────────────────┘
```

- **Gate 1 (Averaged Error Limits):** 
  - Overall MAE across all 11 dimensions must be **$< 0.80$**.
  - MAE on critical structural dimensions (`BITTERNESS`, `SWEETNESS`, `ASTRINGENCY`) must be **$< 0.60$**.
- **Gate 2 (Extreme Value Bounds):**
  - No gold-standard zero-value records may exceed a predicted intensity of **$0.75$**.
- **Gate 3 (Data Integrity Constraints):**
  - 100% of generated outputs must conform to PostgreSQL database constraints (values must be numeric, not null, and reside strictly in `[0.00, 5.00]`).

---

## 6. "Do Not Do" List

To avoid legal exposure, excessive cloud costs, and architectural fragmentation, the development team must adhere to these strict limits:

1. **Do NOT Scrape Commercial Tea Vendor Platforms:**
   - Under no circumstances write or execute web crawlers targeting sites like *Yunnan Sourcing*, *Teavivre*, *Steepster*, or *Mei Leaf*. These storefronts have explicit terms of service prohibiting automated extraction. Violations risk IP blacklisting, brand damage, and legal cease-and-desists.
2. **Do NOT Mix Open Food Facts (ODbL) into Curated Tables:**
   - Do not directly write OFF imported rows into the `tea` or `tea_flavor` tables of our core proprietary database. Keep OFF imports strictly isolated in a decoupled database schema or microservice to avoid contamination from the **ODbL copyleft / share-alike virality trigger**.
3. **Do NOT Execute Synchronous LLM Calls in Critical Paths:**
   - Never force a mobile client to block and wait for a synchronous `/resolve` LLM response during standard catalog searches or app launches. All LLM/ML-based estimations must run offline as batch jobs, or serve as slow, explicitly labeled on-demand falling-back tasks.
4. **Do NOT Delete Source Provenance Metadata:**
   - Never permit database updates to overwrite or drop `tea_flavor_provenance` records. Retaining historical record tracking is essential to target and overwrite specific outdated automated profiles when running database schema updates.
5. **Do NOT Train Machine Learning Models on Very Small Datasets:**
   - Do not spend engineering cycles developing complex Python neural networks, custom gradient-boosted trees, or vector regressors if the verified curated training seed contains **fewer than 500 gold-standard records**. Until that dataset scale is achieved, fallback rules and selective LLM pipelines are cheaper, more predictable, and significantly easier to maintain.