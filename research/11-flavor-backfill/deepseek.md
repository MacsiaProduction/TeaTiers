## 1. Data Availability for Flavor Backfill

The following table summarizes available data sources for flavor backfill, their licensing status, and practical utility:

| Source | Data Available | License | Practical Utility |
|--------|----------------|---------|-------------------|
| **Curated seed teas** | Hand-authored flavor profiles (11 dimensions, 0-5), names, descriptions | Proprietary (TeaTiers) | High-quality gold labels; small sample size (maybe dozens) |
| **Wikidata** | Tea labels/types, origin (country/region), taxonomic parent, Wikipedia article links | CC0 / Public Domain | Moderate; no flavor data directly, but provides type/origin taxonomy |
| **Wikipedia extracts** | Free-text descriptions, history, processing methods | CC BY-SA | Moderate; requires NLP to extract flavor signals |
| **Open Food Facts** | Tea product taxonomy, ingredients, some flavor notes | Open Database License | Low; focused on packaged goods, not tea varieties |
| **User-pasted source text** | Arbitrary text from users | User grants license to TeaTiers | Variable quality; requires validation |

**Key finding**: No public source provides structured flavor profiles for tea varieties. Flavor data must be inferred from:
- **Type/origin** (Wikidata): e.g., "Darjeeling" → floral, "Lapsang Souchong" → smoky
- **Text descriptions** (Wikipedia): free-text mentions of taste notes
- **Curated seed** (hand-authored): small gold-standard set

**Wikidata SPARQL example** for tea types:
```sparql
SELECT ?tea ?teaLabel ?originLabel WHERE {
  ?tea wdt:P31 wd:Q116457966.  # instance of tea variety
  ?tea wdt:P495 ?origin.       # country of origin
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
}
```

**Provenance-critical**: Wikidata is CC0, Wikipedia is CC BY-SA (requires attribution), Open Food Facts is ODbL. All are license-safe for commercial use with proper attribution.

---

## 2. Candidate Approaches Comparison

| Approach | Accuracy | Cost | Ops Complexity | Legal Safety | Failure Modes |
|----------|----------|------|----------------|--------------|---------------|
| **Batch LLM (existing Alice Flash/Qwen)** | High for common teas; hallucination risk for obscure | ~$0.01-0.10/tea | Low (reuse existing) | Safe (prompt only) | Hallucinated flavors, inconsistent across runs, no improvement over time |
| **Rule/heuristic (type/origin)** | Low-moderate; works for stereotypical teas | ~$0 | Very low | Safe | Fails for nuanced/atypical teas; no adaptability |
| **Supervised regression (scikit-learn/LightGBM)** | Moderate; limited by seed data size | ~$0 (once trained) | Medium | Safe | Small training set → high variance, poor generalization |
| **Embedding + regression (sentence-transformers)** | Moderate-high; uses Wikipedia descriptions | ~$0 (local) | Medium-high | Safe | Requires multilingual embeddings; description availability varies |
| **Fine-tuned LLM** | Potentially highest | High (GPU/training) | High | Safe | Overkill for 300-50k teas; requires ML expertise |

### Detailed Analysis

**Batch LLM (existing pipeline)** : The current `/resolve` endpoint already uses Yandex Cloud Foundation Models (Alice Flash, Qwen3 booster) to produce flavor estimates. Running this as a batch job is the path of least resistance—no new code, no new models, just scheduling. However, LLM outputs are `unverified` and confidence-gated. Per-tea cost at 50k teas is non-trivial.

**Rule/heuristic baseline**: Map tea type (from Wikidata `P31`) and origin (from Wikidata `P495`) to flavor profiles. For example:
- `type=black` + `origin=China` → moderate astringency, low sweetness
- `type=green` + `origin=Japan` → grassy, umami
This covers ~60-70% of obvious cases but misses nuance. Useful as a fallback when no other signal exists.

**Supervised regression**: Train a model on curated seed teas (hand-authored profiles) using features from Wikidata (type, origin, processing method). With ~50-200 seed examples, scikit-learn's `RandomForestRegressor` or `GradientBoostingRegressor` can produce reasonable estimates. Scikit-learn 1.6.1 (January 2025) is stable and well-documented. However, small training size is a fundamental limitation.

**Embedding + regression**: Use sentence-transformers to embed Wikipedia descriptions, then train a regression model per flavor dimension. Sentence Transformers supports 5500+ public embedding models. MTEB leaderboard provides model rankings. This can generalize to teas without structured features but requires Wikipedia descriptions exist.

**Fine-tuned LLM**: Overkill. Training a model for 11-dimensional regression on 50k tea varieties requires massive labeled data that doesn't exist.

---

## 3. Recommended MVP and Post-MVP Paths

### Recommended MVP: Batch LLM + Rule Fallback

**Why**: The existing LLM pipeline is already built, tested, and integrates with the provenance system. It produces schema-validated, confidence-gated outputs. Adding a batch scheduler is minimal engineering. Rule fallback catches cases where LLM fails or confidence is low.

**Architecture**:
```
Batch Scheduler → For each tea without flavor:
  1. Try Wikidata enrichment (type, origin)
  2. If type/origin known → apply rule-based baseline
  3. If rule confidence < threshold → call LLM pipeline (/resolve)
  4. Store results as unverified with provenance
```

**Cost estimate**: For 50k teas, LLM calls at ~$0.02/tea = $1,000 one-time. Rule baseline reduces LLM calls by ~60-70% → ~$300-400.

### Post-MVP: Embedding + Regression (After 500+ Human Ratings)

Once users have overridden LLM outputs and provided ratings, train a sentence-transformers regression model using:
- Input: tea name + description + type/origin (embedded)
- Output: 11 flavor dimensions

**When to switch**: After collecting ~500 human-verified flavor profiles across diverse tea types. This provides enough data for a supervised model to generalize.

---

## 4. Recommended Open-Source Libraries

| Library | Version | Use Case | Source |
|---------|---------|----------|--------|
| **scikit-learn** | 1.6.1 | Baseline regression, evaluation metrics |  |
| **sentence-transformers** | 3.4.0 | Text embeddings for descriptions |  |
| **LightGBM** | 4.5.0 | Gradient boosting regression |  |
| **CatBoost** | 1.2.0 | Gradient boosting (handles categorical well) |  |
| **XGBoost** | 2.1.0 | Gradient boosting regression |  |
| **PyTorch** | 2.6.0 | Deep learning (if fine-tuning needed) |  |

**Recommendation**: Start with **scikit-learn 1.6.1** for simplicity. If performance is insufficient, move to **LightGBM 4.5.0** for better handling of mixed categorical/numeric features. Avoid PyTorch until post-MVP.

**Sentence-transformers model selection**: Use `intfloat/multilingual-e5-small` or `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` for multilingual support (English, Russian, Chinese). MTEB leaderboard ranks models by performance.

---

## 5. Data Model / Provenance Additions

### Current Schema
```sql
tea_flavor(tea_id, dimension, intensity 0..5)
```

### Additions for Provenance
```sql
ALTER TABLE tea_flavor ADD COLUMN confidence FLOAT;           -- 0..1 per dimension
ALTER TABLE tea_flavor ADD COLUMN source TEXT;               -- 'curated' | 'llm' | 'rule' | 'model'
ALTER TABLE tea_flavor ADD COLUMN model_version TEXT;        -- e.g., 'sentence-transformers-v1'
ALTER TABLE tea_flavor ADD COLUMN prompt_hash TEXT;          -- for LLM provenance
ALTER TABLE tea_flavor ADD COLUMN source_fingerprint TEXT;   -- hash of input text
ALTER TABLE tea_flavor ADD COLUMN human_override BOOLEAN DEFAULT FALSE;
ALTER TABLE tea_flavor ADD COLUMN override_user_id UUID;
ALTER TABLE tea_flavor ADD COLUMN created_at TIMESTAMP;
ALTER TABLE tea_flavor ADD COLUMN updated_at TIMESTAMP;

-- Catalog-level confidence
ALTER TABLE tea ADD COLUMN flavor_confidence FLOAT;          -- aggregate confidence
ALTER TABLE tea ADD COLUMN flavor_source TEXT;               -- dominant source
ALTER TABLE tea ADD COLUMN flavor_last_enriched TIMESTAMP;
```

### Storage Strategy
- **Per-dimension confidence**: Store individual confidence scores for granularity
- **Catalog confidence**: Aggregate (e.g., mean or min) for UI display
- **Provenance**: Always store source, model_version, and timestamp for auditability
- **Re-run**: When model changes, re-run only teas with `flavor_source IN ('llm','rule','model')` and `human_override = FALSE`

---

## 6. Batch Job Architecture

### Inputs
- List of tea IDs without flavors (or with `flavor_source = 'legacy'`)
- Optional: force re-enrichment flag

### Outputs
- `tea_flavor` rows with `source`, `confidence`, `model_version`
- Log of successes/failures per tea
- Metrics: count enriched, average confidence, per-dimension distribution

### Scheduling
- **Initial backfill**: One-time job, run in batches of 100 teas (to manage rate limits)
- **Incremental**: Daily cron job for newly added teas
- **Idempotency**: Check `tea_flavor` exists before inserting; use `ON CONFLICT DO UPDATE`

### Failure Handling
- LLM failures → log and retry with exponential backoff (3 retries)
- Persistent failures → mark tea as `flavor_source = 'failed'`; alert operator
- Confidence below threshold → store but mark as `low_confidence`; do not show in UI

### Idempotency Key
```sql
UNIQUE(tea_id, dimension) ON CONFLICT DO UPDATE SET
  intensity = EXCLUDED.intensity,
  confidence = EXCLUDED.confidence,
  source = EXCLUDED.source,
  updated_at = NOW()
WHERE tea_flavor.human_override = FALSE;
```

---

## 7. Evaluation Plan and Release Gates

### Gold Dataset
Curate ~100 teas spanning:
- **Types**: Black, green, oolong, white, pu-erh, yellow, dark
- **Origins**: China, Japan, India, Taiwan, Sri Lanka, Kenya
- **Edge cases**: Blends, flavored teas, obscure varieties

### Metrics
| Metric | Target | Measurement |
|--------|--------|-------------|
| Per-dimension MAE | < 0.8 (on 0-5 scale) | Compare against human ratings |
| Calibration (0/5 extremes) | < 10% false extremes | Precision/recall for 0 and 5 |
| Type classification accuracy | > 85% | Wikidata type vs. predicted |

### Release Gates
1. **Gate 1**: MVP batch LLM + rule baseline → MAE < 1.2 on gold set
2. **Gate 2**: Human review of 50 random enriched teas → < 10% rejected
3. **Gate 3**: A/B test with 10% of users → no increase in flavor corrections
4. **Gate 4** (post-MVP): Embedding model → MAE < 0.8 on gold set

### Transliteration and Type Classification
- Use `googletrans` or `pyicu` for transliteration validation
- Type classification: Compare Wikidata `P31` against model-predicted type

---

## 8. "Do Not Do" List

1. **Do not scrape non-redistributable tea sites** (TeaDB, Steepster, RateTea, vendor sites). Licensing is unclear; legal risk is unacceptable.

2. **Do not use web-crawled data** without license verification. Open-ended crawling introduces attribution and copyright liability.

3. **Do not train on user-generated content** without explicit opt-in consent. User ratings belong to users.

4. **Do not deploy ML model without gold evaluation set**. Untested models will degrade user experience.

5. **Do not overwrite human-overridden flavors** during re-runs. Human corrections are ground truth.

6. **Do not use PyTorch for MVP**. It adds complexity without benefit for small-scale regression.

7. **Do not ignore confidence scores**. Low-confidence predictions should be flagged, not shown as fact.

8. **Do not treat LLM output as verified**. Always store as `unverified` and allow user override.

9. **Do not batch-process without idempotency**. Duplicate runs should not create duplicate rows.

10. **Do not skip provenance tracking**. Every flavor prediction must be auditable back to its source.

---

## Summary Table: Approach Scores

| Approach | Accuracy | Cost | Ops | Legal | Total |
|----------|----------|------|-----|-------|-------|
| Batch LLM + Rule | 7/10 | 4/10 | 8/10 | 10/10 | **29/40** |
| Rule-only | 4/10 | 10/10 | 10/10 | 10/10 | 34/40 |
| Supervised (seed only) | 5/10 | 9/10 | 6/10 | 10/10 | 30/40 |
| Embedding + Regression | 8/10 | 8/10 | 5/10 | 10/10 | 31/40 |
| Fine-tuned LLM | 9/10 | 2/10 | 3/10 | 10/10 | 24/40 |

**Winner for MVP**: **Batch LLM + Rule Fallback** — best balance of accuracy, low ops cost, and reuse of existing infrastructure. The embedding model becomes viable after collecting 500+ human ratings.