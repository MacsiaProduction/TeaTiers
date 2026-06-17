# 11-flavor-backfill - batch flavor profiles for Wikidata catalog teas

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, ...). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** - a local-first Android tea tier-list app with a Kotlin/Spring Boot
catalog backend and PostgreSQL. The backend catalog has:

- `tea`
- `tea_name` with `en`, `ru`, `zh-Hans`, `pinyin`
- `tea_description`
- `tea_flavor(tea_id, dimension, intensity 0..5)`

Flavor dimensions are fixed for now:
`BITTERNESS`, `SWEETNESS`, `ASTRINGENCY`, `FRUITINESS`, `FLORAL`, `GRASSY`, `SPICY`,
`SMOKY`, `EARTHY_NUTTY`, `UMAMI`, `ROASTED`.

Current enrichment:
- Curated seed teas have hand-authored flavor profiles.
- `/resolve` can use Wikidata first, then Yandex Cloud Foundation Models (Alice Flash,
  Qwen3 booster) to produce names, type, short ru blurb, and flavor estimates.
- LLM output is `unverified`, schema-validated, confidence-gated, and user-overridable.
- No open-ended web crawling; license-safe sources only: curated seed, Wikidata, Wikipedia
  where attribution/share-alike is handled, Open Food Facts isolated.

New idea to evaluate: "write our own model in Python to backfill flavors for all wiki teas."

## Objective

Decide whether TeaTiers should build a Python flavor-backfill model, reuse the existing
LLM tier as a batch job, use simpler heuristics, or defer. Produce an implementation plan
that respects license, provenance, confidence, and MVP scope.

## Questions

1. What data is actually available for flavor backfill without scraping non-redistributable
   tea sites? Evaluate Wikidata labels/types/origins, Wikipedia extracts, curated seed
   profiles, user-pasted source text, and Open Food Facts taxonomy.
2. Compare approaches:
   - Batch use of the existing Alice Flash/Qwen prompt pipeline
   - Rule/heuristic baseline by tea type/origin/known style
   - Small supervised Python model trained on curated seed + future human ratings
   - Embedding/regression model using multilingual text descriptions
   - Fine-tuning a model
3. For a catalog of 300-50,000 teas, which approach is most accurate, cheapest, easiest to
   operate, and safest legally? Include expected failure modes.
4. If a Python model is justified, recommend concrete open-source libraries and architecture:
   scikit-learn, sentence-transformers, LightGBM/XGBoost/CatBoost, PyTorch, or other
   maintained tools. Pin exact versions only if verified.
5. How should outputs be stored?
   - `tea_flavor` rows as `unverified`
   - per-dimension confidence or only row/catalog confidence
   - provenance (`enriched_by`, model version, prompt/hash, source text fingerprint)
   - re-run/migration strategy when the model changes
6. Design an evaluation set and acceptance thresholds:
   - gold teas per type and edge cases
   - per-dimension MAE
   - calibration for 0/5 extremes
   - transliteration and type classification checks
7. Identify what must be researched or verified before coding.

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts.
- Pin exact versions; explicitly flag anything you are not certain exists.
- Cite every claim with a link and publication/update date; prefer recent sources.
- Treat model benchmark claims skeptically unless reproducible.
- Keep licensing/provenance first-class; do not recommend scraping non-redistributable sites.

## Return

Return:

1. A comparison table of the candidate approaches.
2. Recommended MVP path and post-MVP path.
3. Data model/provenance additions if needed.
4. Batch job architecture: inputs, outputs, scheduling, idempotency, failure handling.
5. Evaluation plan and release gates.
6. "Do not do" list.

