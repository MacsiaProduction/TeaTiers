# Rating - 11-flavor-backfill

Prompt: ./prompt.md   ·   Date judged: 2026-06-17

Scale 1-5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many -> lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output - the numeric Score is only a tiebreaker. See ../README.md -> *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    1     |    4    |  4.65 |  1   |
| gemini   |    4     |   4   |       5       |    2     |    5    |  4.25 |  2   |
| deepseek |    3     |   3   |       3       |    3     |    4    |  3.10 |  3   |

<!-- Optional Score = 0.35*Accuracy + 0.20*Depth + 0.25*Actionability + 0.10*Clarity - 0.10*Halluc. -->

**Winner:** opus - the only answer that gets the central fact cleanly right: **Wikidata has no
taste/flavor property** (the nearest — P2658 Scoville/chilis, P789 edibility/mushrooms, P8266 FEMA —
are inapplicable), so it yields features (type/origin) not labels; the curated seed is the only gold.
It pins library versions accurately *with honest could-not-verify caveats*, costs the job against the
real Yandex async API, and ships a per-dimension provenance schema + quantitative release gates —
respecting the Yandex-native / no-scrape / MVP-simplicity locks. gemini reaches the same
"no Python model for MVP" verdict with strong engineering and good stack-fit (in-JVM heuristics,
Resilience4j circuit breaker) but is looser on provenance granularity and a few specifics. deepseek's
library table is grossly stale (sklearn 1.6.1, sentence-transformers 3.4.0) with empty Source columns,
and it proposes hard-DELETE of old rows (destroys the audit trail).

**Reuse:**
- **Core decision:** do NOT build a Python model for MVP — reuse the existing Yandex LLM tier as a
  batch job + a deterministic heuristic prior table (100% Day-1 coverage); defer a supervised model
  until ~300-500 human-verified labels exist.
- **Provenance schema (opus):** separate `enrichment_run` + `tea_flavor_provenance` side-table keyed
  on `idempotency_key = hash(tea_id|dimension|engine|model_version|source_fingerprint)`; per-dimension
  `confidence` + `status` (curated/unverified/verified/user_override); append-only audit (never
  hard-delete); re-backfill touches only `unverified` model-authored rows.
- **Eval gates (opus):** 50-150 gold teas ≥8-12 per ISO type + edge cases; per-dimension MAE +
  macro-MAE; a **separate extreme-value recall gate on true 0s/5s**; transliteration sub-eval
  (大红袍 → "Да Хун Пао", not literal); type-classification accuracy. Thresholds: overall MAE ≤ 0.8,
  no single-dim MAE > 1.5, extreme recall ≥ 0.7, transliteration ≥ 0.95, type ≥ 0.9.
- Stack-fit details from gemini/deepseek: heuristic engine in Kotlin/Spring in-JVM; Resilience4j
  circuit breaker (~20% rolling-window failure trip) on the batch pipeline.
- **Verify upstream:** opus QIDs (Tea=Q6097 etc.) + a live SPARQL COUNT of the tea subtree; library
  pins (sklearn 1.9.0, lightgbm 4.6.0, sentence-transformers 5.6.0 — gemini's is current, opus's 5.5.1
  is one patch stale but caveated); embedding licenses/commit hashes (e5-large / bge-m3 = MIT).

**Discard:**
- deepseek's whole library table (stale, uncited) and its `googletrans`/`pyicu` transliteration tooling
  (unofficial; conflicts with the no-machine-translation-for-names principle).
- deepseek's hard-DELETE re-backfill (kills auditability) and row-level `overall_confidence` (the prompt
  asks for per-dimension).
- All three cost ceilings as fact — opus's token-modeled ~$94/50k is best-grounded but still
  verify against live Yandex pricing; gemini's "$50-150" and deepseek's "$1,000 one-time" are ungrounded.
- Any embedding/regression model or LLM fine-tune as MVP (all three correctly reject); deepseek's
  cosine-similarity ≥0.98 cross-lingual gate (over-tight, not a correctness signal).

> LEADERBOARD: +1 Wins opus; +1 Runs judged for opus, gemini, deepseek.
