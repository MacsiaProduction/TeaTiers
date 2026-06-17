# TeaTiers Flavor-Backfill Modeling Decision Report

*Technical decision report — prepared 17 June 2026. Text-only. All library versions verified against authoritative upstream (PyPI / official docs / GitHub releases) on the date of writing; could-not-verify items are flagged in the Caveats section.*

## TL;DR
- **Build NO Python model for the MVP. Use the existing Yandex async LLM pipeline as the flavor-backfill batch job, backed by a deterministic heuristic prior table, and DEFER a supervised Python model until you have ≥300–500 human-verified flavor labels.** Labeled N today is far too small for supervised learning, and full/LoRA fine-tuning is not justified at any realistic TeaTiers scale.
- **No license-safe source carries structured flavor data.** Wikidata (CC0) has *no* taste/flavor property at all; Open Food Facts is ODbL share-alike and carries no usable tea taste data; Wikipedia extracts (CC BY-SA) contain prose flavor descriptors but oblige attribution + share-alike. Your curated seed profiles are the only gold labels.
- **Every flavor row must be stored `unverified` with per-dimension confidence and full provenance** (engine, model version, prompt/artifact hash, source-text fingerprint, timestamp), must never clobber user overrides or curated rows, and must support re-backfill keyed on idempotency hashes.

## Key Findings

### 1. Verified latest stable library versions (checked 17 June 2026)
- **scikit-learn 1.9.0** — released **2 June 2026** (PyPI shows scikit_learn-1.9.0 wheels "Uploaded Jun 2, 2026"; scikit-learn.org news "June 2026 — scikit-learn 1.9.0 is available"). Supports Python 3.11–3.14. BSD-3-Clause.
- **sentence-transformers 5.5.1** — released **20 May 2026** (conda-forge / pepy.tech: "latest version is 5.5.1, released May 20, 2026 … Apache 2.0"). See caveat on a later June patch. Apache-2.0.
- **LightGBM 4.6.0** — released **14 February 2025** (GitHub 4.6.0 release assets timestamped 2025-02-14; PyPI "Latest version: 4.6.0 … The MIT License (MIT), Copyright (c) Microsoft Corporation"). Still latest stable as of June 2026; the "4.6.0.99" seen in docs is an EffVer dev marker, not a release. Project moved to `lightgbm-org/LightGBM` in March 2026. MIT.
- **XGBoost 3.2.0** — released **10 February 2026** (PyPI "Released: Feb 10, 2026 … Apache-2.0 … Requires Python >=3.10"; docs changelog header "3.2.0 (2026 Feb 09)"). Apache-2.0.
- **CatBoost 1.2.10** — released **19 February 2026** (GitHub releases, assets timestamped 2026-02-19). Apache-2.0.
- **PyTorch 2.12.0** — released **13 May 2026** (PyPI torch project page). BSD-3.
- **NumPy 2.4.6** — released **18 May 2026** (numpy.org news/release history).
- **pandas 3.0.3** — released **11 May 2026** (PyPI; pandas.pydata.org). Note: pandas 3.0 defaults to a PyArrow-backed string dtype and Copy-on-Write — minor migration considerations.
- **SciPy 1.17.1** — released **22–23 February 2026** (scipy.org news; PyPI). Requires Python 3.11+.
- **mord 0.7** (scikit-learn-compatible ordinal regression) on PyPI — small/older but functional; linear models only.

### 2. Embedding-model licenses (HuggingFace model cards, 17 June 2026)
- **intfloat/multilingual-e5-large** — **MIT**; XLM-RoBERTa, ~560M params, **1024-dim**, 512-token max. `-base` is 768-dim, also MIT. Strong ru/en/zh. **Recommended primary** for the deferred model.
- **BAAI/bge-m3** — **MIT**; ~568M params, **1024-dim**, "Multi-linguality (100+ languages), Multi-granularities (input length up to 8192)". **Recommended alternative**, especially for longer descriptions.
- **sentence-transformers/LaBSE** — **Apache-2.0**; 768-dim, 109 languages, cross-lingual-retrieval optimized.
- **sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2** — **Apache-2.0**; 384-dim, 50+ languages, ~118M params; smallest/cheapest CPU option.
All four are permissively licensed (MIT/Apache-2.0): safe to run locally and to store derived embeddings and predictions.

### 3. Data availability for flavor signal (license-first)
- **Wikidata (CC0 — no attribution required; confirmed at Wikidata:Licensing & Data access):** Tea = **Q6097**; Camellia sinensis = **Q101815** (distinct items). Named varieties exist with multilingual labels — Da Hong Pao = **Q835553**, Tieguanyin = **Q1328947**, Matcha = **Q822331** (ru "Матча", zh "抹茶"), green tea = **Q484083**. Usable properties: instance-of (**P31**), subclass-of (**P279**), country of origin (**P495**). **Critically, Wikidata has NO taste/flavor/organoleptic property.** The nearest properties — Scoville grade (P2658, chili peppers only), edibility (P789, mushrooms), FEMA number (P8266, regulatory flavor-ingredient ID) — are inapplicable to tea. **Net: Wikidata yields names/types/origins as model *features* only, never flavor labels.**
- **Wikipedia extracts (CC BY-SA 3.0/4.0):** TextExtracts / MediaWiki REST summary API returns article intros; the REST response includes a per-page `license` object. Tea articles *do* contain prose flavor descriptors ("malty", "floral", "grassy", "roasted", "honey", "mineral"), but coverage and consistency are moderate. **Storing extracts or text-derived data triggers attribution + share-alike obligations.** RU-reachable.
- **Curated seed profiles:** Hand-authored, fully owned, highest quality, small N — the gold/training-label source.
- **User-pasted source text:** License is *user-asserted*. Treat as untrusted provenance: store a source-text fingerprint and a user-asserted license flag; never redistribute.
- **Open Food Facts (database = ODbL 1.0; contents = Database Contents License; images = CC BY-SA):** ODbL imposes attribution AND share-alike — merging OFF data into your DB can force you to release the resulting database as open data. OFF carries ingredients/nutrition/categories/labels, **not** tea taste profiles. Net: not worth the share-alike burden for flavor backfill; keep isolated, as already planned.

### 4. Tea-type → flavor priors (license-safe basis)
**ISO 20715:2023** ("Tea — Classification of tea types", published 8 March 2023, ISO/TC 34/SC 8) states verbatim: *"This document establishes a classification for the types of tea produced from the plant Camellia sinensis (L.) O. Kuntze"* and *"The basic tea types are black tea (see ISO 3720), green tea (see ISO 11287), white tea (see ISO/TR 12591), oolong tea (see ISO 20716), dark tea and yellow tea."* It explicitly excludes herbal/fruit infusions. **These standards classify types but publish NO type→11-dimension intensity mapping** — the heuristic table must be hand-built from your curated seed plus general tea science. The well-supported directional priors (green → high GRASSY/low ROASTED; roasted Wuyi oolong → high ROASTED/EARTHY_NUTTY; black → malty, higher BITTERNESS/ASTRINGENCY; Lapsang Souchong → high SMOKY) are sound but not a citable numeric standard.

### 5. Ordinal nature of the target (0..5 intensities)
The 11 intensities are **ordinal**, not pure-continuous or nominal. Options: (a) regression + rounding/clipping — simplest, but regresses to the mean and under-predicts the 0/5 extremes; (b) ordinal regression via `mord` (threshold / immediate-threshold / all-threshold models, scikit-learn-compatible, but linear-only); (c) the **ordinal "McRank" reduction** — train K binary classifiers per dimension for P(y≤c) and compute the expected value (better extreme behavior, multi-output friendly). Once labels exist, per-dimension gradient-boosted regressors (LightGBM/XGBoost/CatBoost) with rounding are the pragmatic baseline, with `mord`/McRank as the ordinal-aware upgrade.

## Details

### Deliverable 1 — Comparison of the five candidate approaches

| Approach | Accuracy (small N) | Training-data needs | Cold-start | Per-item inference cost | Ops complexity | Legal/license safety | Explainability |
|---|---|---|---|---|---|---|---|
| **1. Batch/async LLM (Yandex Alice AI LLM / YandexGPT async)** | Moderate–good for common teas; hallucinates on obscure teas | None (prompt-engineered) | Excellent — works day 1 | ~$0.0019/tea (model below) | Low–moderate (already built; submit→store-id→poll) | High (Yandex Terms permit storing/re-serving output; must flag `unverified`) | Low (opaque; mitigate via prompt hash + source fingerprint) |
| **2. Rule/heuristic priors by type/origin** | Decent for typical teas; wrong for atypical (roasted green, smoked black) | None (hand-built table) | Excellent | ~$0 (deterministic) | Very low | Very high (own table + CC0 Wikidata types) | Very high (fully auditable rules) |
| **3. Supervised Python model (multi-output ordinal regression)** | Poor at <300 labels; improves with N | Few hundred → thousands of labels | Poor (needs labels) | ~$0 (local CPU) | Moderate (train/eval/version pipeline) | High (BSD/MIT/Apache libs; CC0 features) | Moderate (feature importances, SHAP) |
| **4. Embedding + regressor (multilingual SBERT → LightGBM/ridge/mord)** | Poor→moderate at small N; regresses to mean | Same labels as #3 + text inputs | Poor | ~$0 (local; embed once, cache) | Moderate–high (embedding infra + regressor) | High (MIT/Apache embeddings; CC BY-SA text needs attribution) | Low–moderate |
| **5. Fine-tune LLM (full or LoRA)** | Unreliable at tiny N; high overfit risk | Thousands of high-quality labels | Very poor | Training cost + serving | High | Mixed (model-license dependent; heavy ops) | Very low |

### Deliverable 2 — Recommended MVP and post-MVP path

**MVP (now):**
1. Ship a **deterministic heuristic prior table** (Approach 2) as baseline + fallback for every tea, built from curated seed + ISO type classification. Engine tag `heuristic`.
2. Run the **Yandex async LLM job** (Approach 1) for teas lacking curated profiles — Alice AI LLM async ($0.002084/1K in, $0.008335/1K out) or YandexGPT. Store outputs `unverified`, per-dimension confidence, schema-validated, confidence-gated. On low confidence or validation failure, fall back to the heuristic prior.
3. Keep curated seed as gold; let users override anything.

**Post-MVP (trigger: ≥300–500 human-verified labels spanning all ISO types + edge cases):**
4. Train **Approach 4** — multilingual-e5-large (or bge-m3) embeddings of `name + type + origin + extract + description` → per-dimension LightGBM regressor (with rounding) or `mord`/McRank for ordinal-awareness. The LLM tier becomes the label bootstrapper and ongoing fallback for low-confidence/low-coverage teas.
5. Promote the Python model to primary only after it passes the release gates (Deliverable 5). Re-evaluate quarterly.
6. **Do not fine-tune an LLM** unless you reach thousands of verified labels AND the embedding+regressor plateaus below target — unlikely at any realistic TeaTiers scale.

### Deliverable 3 — Data model / provenance schema additions (PostgreSQL)

A dedicated provenance side-table plus an enrichment-run table keeps `tea_flavor` clean while remaining provenance-complete:

```sql
-- Extend existing tea_flavor with status + per-dimension confidence
ALTER TABLE tea_flavor
  ADD COLUMN status TEXT NOT NULL DEFAULT 'unverified'   -- curated|unverified|verified|user_override
  ADD COLUMN confidence REAL,                            -- per-(tea,dimension), 0..1
  ADD COLUMN enrichment_run_id BIGINT REFERENCES enrichment_run(id);

-- New: one row per backfill execution
CREATE TABLE enrichment_run (
  id              BIGSERIAL PRIMARY KEY,
  engine          TEXT NOT NULL,        -- curated | llm | heuristic | python_model
  model_uri       TEXT,                 -- e.g. gpt://<folder>/aliceai-llm  or  e5-large+lgbm
  model_version   TEXT NOT NULL,
  artifact_hash   TEXT,                 -- model-artifact hash or prompt hash
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at     TIMESTAMPTZ,
  notes           TEXT
);

-- New: provenance per written flavor cell (audit trail)
CREATE TABLE tea_flavor_provenance (
  tea_id                  BIGINT NOT NULL,
  dimension               TEXT   NOT NULL,
  enriched_by             TEXT   NOT NULL,   -- curated|llm|heuristic|python_model
  model_version           TEXT,
  prompt_or_artifact_hash TEXT,
  source_text_fingerprint TEXT,              -- sha256 of input text used
  source_license          TEXT,             -- cc0|cc-by-sa|user_asserted|owned
  confidence              REAL,
  enrichment_run_id       BIGINT REFERENCES enrichment_run(id),
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  idempotency_key         TEXT NOT NULL,     -- hash(tea_id|dimension|engine|model_version|source_fingerprint)
  PRIMARY KEY (tea_id, dimension, enrichment_run_id)
);
```

**Per-dimension confidence is argued for:** a model may be confident on GRASSY for a green tea but uncertain on SMOKY. Row-level or catalog-level confidence loses this signal and prevents selective publishing/overriding.

**Migration / re-run strategy:** when a model version ships, re-backfill ONLY rows where `status='unverified'` AND `enriched_by IN ('llm','heuristic','python_model')`. Use `idempotency_key` to skip cells unchanged across (tea, dimension, model_version, source). Retain all prior provenance rows for audit — never hard-delete. Curated, verified, and `user_override` rows are immutable to automated jobs.

### Deliverable 4 — Batch job architecture (flavor-backfill, leveraging Yandex async)
- **Inputs:** `tea_id`s needing flavor (no curated profile, or stale model version); per tea, assemble a compact prompt context = name (en/ru/zh-Hans/pinyin) + type + origin + optional Wikipedia extract + optional user-pasted text.
- **Submit:** POST to `https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync` with the Alice AI LLM async URI, `x-data-logging-enabled: false`, JSON output. Strict `json_schema` on async is unconfirmed → use `json_object` + schema-in-prompt + server-side validation. Respect limits: 10 submit/s, 5,000 submit/hour.
- **Store the operation id** → poll `https://operation.api.cloud.yandex.net/operations/{id}` (≤50 fetch/s). Results carry a 3-day server-side TTL — fetch within the window or re-submit.
- **Validate & gate:** schema-validate; confidence-gate per dimension; on failure/low confidence fall back to the heuristic prior.
- **Write:** insert `tea_flavor` (status='unverified') + `tea_flavor_provenance` + link to `enrichment_run`. The idempotency key prevents duplicate writes on retry.
- **Failure handling:** exponential backoff on 429; dead-letter teas failing validation N times → heuristic fallback; because of the 3-day TTL, re-submit (don't re-poll) if a run stalls.
- **Scheduling:** triggered on new-tea ingestion and on model-version bump (re-backfilling model-authored rows only).
- **Why async, not batch:** Yandex BATCH text-gen does not offer Alice AI LLM/YandexGPT, and the only 235B batch entry (Qwen3-235B-A22B at $0.050010/1K tokens) carries a 200,000-token-per-run minimum — uneconomic for small tea volumes. Async is 50% cheaper than sync for native models and right-sized.

**Cost model (Alice AI LLM async).** Assume per tea ≈ 300 input tokens (prompt + context) and ≈ 150 output tokens (JSON of 11 dimensions + confidences). Async rates: $0.002084/1K in, $0.008335/1K out.
- Per tea ≈ (0.300 × $0.002084) + (0.150 × $0.008335) ≈ $0.000625 + $0.00125 ≈ **~$0.0019/tea**.
- **300 teas ≈ $0.57; ~5,000 teas ≈ $9.4; 50,000 teas ≈ $94.** Even at 2× the token budget, 50,000 teas ≈ ~$190. Cost is negligible versus operational simplicity. (YandexGPT Lite sync at $0.001667/1K would be cheaper still but forgoes the native async path/discount.)

### Scaling 300 → ~5,000 → 50,000 teas (Question 3)
- **300 teas:** LLM async is cheapest in total effort (≈$0.57) and most accurate cold-start; heuristic table covers fallback. A Python model is infeasible (no labels). **Winner: LLM + heuristic.**
- **~5,000 teas:** LLM async ≈$9.4, still trivial; if ≥300–500 verified labels exist, begin shadow-evaluating the embedding+regressor. **Winner: LLM primary, Python in shadow.**
- **50,000 teas:** LLM async ≈$94 one-time; if the Python model passes gates it becomes the cheapest steady-state path (≈$0/inference locally) and reduces hallucination risk on the long tail. **Winner: Python model primary (if gated), LLM fallback for low-coverage/low-confidence teas.**

**Expected failure modes by approach:** LLM — hallucinated flavors for obscure teas; non-determinism. Heuristics — wrong for atypical teas (roasted green, smoked black, milk oolong) and cascading errors when type is misclassified. Embedding/regressor — regression-to-mean (never predicting true 0s or 5s); sparse multilingual text for obscure teas; type-misclassification propagating into wrong priors. All approaches share the risk that no source carries real flavor ground truth, so error compounds without human verification.

### Deliverable 5 — Evaluation plan and release gates
- **Gold/reference set:** **50–150 gold teas**, ≥8–12 per principal ISO type (black, green, white, oolong, dark, yellow) plus deliberate edge cases: a roasted green, a heavily smoked black (Lapsang Souchong), a fruity herbal/tisane (flagged out-of-ISO-scope), a milk oolong, an aged pu'er. Hand-author flavor truth for each.
- **Primary metric:** per-dimension **Mean Absolute Error (MAE)** on the 0..5 scale + overall macro-MAE.
- **Extreme-value calibration:** measure **recall on true 0s** (absent) and **recall on true 5s** (dominant) separately — a dedicated gate distinct from MAE, because regression flattens extremes.
- **Transliteration sub-eval:** Chinese → Russian transliteration correctness (e.g. 大红袍 → "Да Хун Пао", *not* a literal translation "Большой красный халат"); exact-match accuracy against a curated key.
- **Type-classification sub-eval:** ISO-type prediction accuracy; misclassification cascades into wrong priors.
- **Release gates (ALL must pass before model-authored rows publish as `unverified`):** overall MAE ≤ 0.8; no single dimension MAE > 1.5; extreme recall ≥ 0.7; transliteration accuracy ≥ 0.95; type accuracy ≥ 0.9. Failing any gate → keep heuristic/LLM fallback for the affected teas.

### Deliverable 6 — "Do not do" list (anti-patterns)
1. **Do not scrape non-redistributable tea sites** — only curated seed, CC0 Wikidata, CC BY-SA Wikipedia (attribution/share-alike handled), and isolated ODbL Open Food Facts.
2. **Do not fine-tune an LLM on tiny N** — overfits, opaque, operationally heavy, unjustified at TeaTiers scale.
3. **Do not store flavor rows without provenance** — every row needs engine, model version, hash, source fingerprint, timestamp, confidence.
4. **Do not let model/heuristic rows clobber user overrides or curated/verified rows** — automated jobs touch only `unverified` model-authored rows.
5. **Do not trust extreme-value (0/5) predictions without calibration** — regression-to-mean silently flattens dominant/absent flavors.
6. **Do not use Yandex BATCH mode for small tea volumes** — 200,000-token-per-run minimum, no native-model support; use async.
7. **Do not use Yandex Translate (or any MT) for tea names** — use established transliteration (Да Хун Пао), not literal translation.
8. **Do not casually combine Open Food Facts data into your main DB** — ODbL share-alike can force open-sourcing the resulting database; keep OFF isolated.
9. **Do not treat Wikipedia-derived flavor text as attribution-free** — CC BY-SA requires attribution + share-alike on stored extracts and derivatives.
10. **Do not assume Wikidata has flavor data** — it carries names/types/origins only; no taste property exists.

## Recommendations (staged, with thresholds)
1. **Immediately:** build the heuristic prior table + wire the Yandex async backfill job with the provenance schema above. Publish auto rows `unverified`, per-dimension confidence, user-overridable. **Threshold to advance:** catalog-wide backfill with <5% validation-failure rate.
2. **Accumulate labels:** instrument user overrides + curate the gold set (50–150 teas). **Threshold to start the Python model:** ≥300–500 verified labels spanning all ISO types + edge cases.
3. **Build Approach 4** (multilingual-e5-large or bge-m3 → per-dimension LightGBM/`mord`). **Threshold to promote to primary:** passes all release gates (overall MAE ≤ 0.8; no dim MAE > 1.5; extreme recall ≥ 0.7; transliteration ≥ 0.95; type accuracy ≥ 0.9).
4. **Revisit fine-tuning** only if the embedding+regressor plateaus below target AND you hold thousands of verified labels — otherwise never.

### Question 7 — Verify before coding
- Run a live SPARQL COUNT of tea entities under Q6097 (`SELECT (COUNT(DISTINCT ?item) AS ?c) WHERE { ?item wdt:P31/wdt:P279* wd:Q6097 }`) to confirm real Wikidata coverage (currently only order-of-magnitude estimated ~300–1,000).
- Confirm exact library versions at install time and pin only verified: sklearn 1.9.0, sentence-transformers 5.5.1, lightgbm 4.6.0, xgboost 3.2.0, catboost 1.2.10, torch 2.12.0, numpy 2.4.6, pandas 3.0.3, scipy 1.17.1.
- Confirm OFF lacks usable tea taste data for your catalog before any integration; given ODbL share-alike, default to exclusion.
- Confirm embedding-model licenses at download (e5/bge-m3 = MIT; LaBSE/MiniLM = Apache-2.0) and pin model commit hashes.
- Decide the ordinal method (rounding vs `mord` vs McRank) empirically once labels exist.
- Size the curated seed/gold label set and build the CN→RU transliteration key.
- Confirm whether strict `json_schema` works on the Yandex async path, else fall back to `json_object` + schema-in-prompt + server-side validation.

## Caveats (could-not-verify items, explicitly flagged)
- **Wikidata tea entity count is UNVERIFIED** — the live SPARQL Query Service and the QLever mirror could not be queried (cache-only domain / HTTP 429). The **~300–1,000** figure is an order-of-magnitude estimate only; run the COUNT query above to confirm.
- **sentence-transformers exact latest patch:** conda-forge/pepy.tech show **5.5.1 (20 May 2026)** as latest stable; the PyPI page also lists a release dated 16 June 2026 whose exact patch number could not be confirmed — pin 5.5.1 unless a newer patch is verified at install.
- **LightGBM versioning:** **4.6.0 (14 Feb 2025)** is the latest stable release; docs showing "4.6.0.99" are an EffVer dev marker, not a release. Verify on PyPI at install.
- **Tea-type→flavor numeric mapping is NOT a published standard** — ISO 20715/3720/11287/20716 classify types but publish no 11-dimension intensity table; the heuristic priors must be hand-built and are inherently approximate.
- **Yandex async strict `json_schema` support is unconfirmed** (per provided background) — fallback is `json_object` + schema-in-prompt + validation.
- **Token-count cost estimates are illustrative** — actual cost depends on real prompt/output token counts; measure on a pilot batch before extrapolating to 50,000 teas.