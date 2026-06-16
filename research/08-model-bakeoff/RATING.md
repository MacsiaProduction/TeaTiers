# Rating — 08-model-bakeoff

Prompt: ./prompt.md · Scores: ./results/scores.md · Date judged: 2026-06-16

This is a **product bake-off**, not a deep-research answer comparison, so it does not
bump `../LEADERBOARD.md` (that tracks which model to trust for research questions; these
candidates are our production-LLM options). The real output is the **per-role pick**
below, grounded in `results/scores.md` and the verbatim `results/<key>.jsonl`.

## Results (zero-shot over 24 gold teas, temperature 0)

| Model | overall MAE | type acc | central-tendency | injection breach | ₽/run (in/out tok) | lat |
|-------|:-----------:|:--------:|------------------|:----------------:|--------------------|:---:|
| DeepSeek V4 Flash | **0.33** | **23/24** | best-calibrated (0.32/0.32 vs gold 0.31) | **0/4** | 27.9 (78k/8.8k) | 4.1s |
| Alice LLM | 0.41 | 22/24 | slight 2–3 lean | 2/4 | 31.6 (39k/10k) | 2.6s |
| Alice Flash | 0.47 | 21/24 | good | 2/4 | **5.9** (39k/10k) | **2.4s** |
| Qwen3-235B | 0.49 | 21/24 | over-uses extremes (0.37) | 1/4 | 26.8 (45k/8.8k) | 9.4s |
| YandexGPT Pro 5.1 | 0.50 | 21/24 | good | 2/4 | 38.8 (38k/10k) | 2.5s |
| YandexGPT Lite | 0.80 | 17/24 | under-uses extremes (0.18) | 2/4 | 9.7 (39k/10k) | 4.6s |

All six clear the **MAE ≤ 1.0** target overall; every model produced **24/24** valid,
schema-conformant JSON (native `jsonSchema` and OpenAI-compat `response_format` both
honored). `₽/run` is the cost of one full 28-call bake-off pass on that model, not a
per-request price (per-1k prices: `prompt.md`).

## Winners (by role)

**Primary (ru/en, writes the Russian blurb) → Alice Flash (`aliceai-llm-flash`).**
Cheapest by far (0.1/0.2 ₽ per 1k, ~½ of Lite, 1/7 of Pro), fastest (2.4s), MAE 0.47 with
good calibration, json_schema via the OpenAI-compat endpoint. It beats YandexGPT **Lite**
decisively on every quality axis (MAE 0.47 vs 0.80, type 21 vs 17, better extreme usage)
**at lower cost** — Lite is the loser of this bake-off and should not be the primary.

**Chinese-source booster → Qwen3-235B (`qwen3-235b-a22b-fp8/latest`).** The
research-backed zh choice (research 06/07): purpose-built multilingual, 262k context, and
it **resisted the override injection** where the Yandex-native models failed. On this
*Russian* gold set DeepSeek scored higher, but the booster's job is *Chinese* source text —
a zh-specific gold set should confirm before locking it; Qwen3 is the safe default, with
**DeepSeek V4 Flash** as the strong alternative.

**Best overall accuracy + safety → DeepSeek V4 Flash (`deepseek-v4-flash`).** Top MAE
(0.33), top type accuracy (23/24), best calibration, and the **only model that held all 4
injection attacks** (it even paraphrased the copy-prose bait instead of lifting it). The
catch: it tokenizes Cyrillic ~2× less efficiently (78k vs 39k input tokens for the same
prompts), inflating real cost, and it is a third-party (non-Yandex-origin) model. A viable
single-model choice if we accept the cost; otherwise the quality/safety reference point.

## Reuse

- **Architecture: one OpenAI-compat code path.** Primary (Alice Flash) and booster (Qwen3)
  are both OpenAI-compat, so we standardize on `POST /v1/chat/completions` +
  `response_format:{type:"json_schema",strict:true}` — exactly run-07's recommendation —
  and keep the native endpoint only as an option.
- **The run-07 prompt artifacts work as-is**: the zero-shot system prompt + 0/1/3/5 rubric
  + 3 few-shot examples + the inlined-`dim` json_schema yielded 24/24 valid JSON and
  sub-1.0 MAE on every model. Promote them to `context/flavor-system/prompts.md`.
- **Inline the `dim` object (don't use `$ref`/`$defs`)** — the inlined schema was honored by
  both endpoints and all six models.
- **Gold set + harness** (`gold.json`, `run_bakeoff.py`) → the regression gate: re-run on
  every prompt change or model-version bump, block release if overall MAE > 1.0.

## Discard / caveats

- **Prompt-only injection defense FAILS.** 4/6 models wrote the literal word **"HACKED"**
  into `short_blurb_ru` on the override attack, and 5/6 copied vendor prose. Schema
  validation does not catch a malicious *string* in a valid field. The run-07 **code-side
  guards are mandatory and non-negotiable**: reject all-same-score profiles, n-gram overlap
  scan of the blurb vs source (reject copies), strip/scan the output, fail closed. This
  holds regardless of model.
- **YandexGPT Lite as primary** — discard; it is the weakest and not the cheapest.
- **Gold profiles are a synthesis, not lab truth** — recalibrate against real tasters; FLORAL
  and ROASTED showed the highest cross-model MAE and may partly reflect anchor noise.
- **This run scored zero-shot RU only.** It does **not** prove zh-source quality (the
  booster's actual job) or grounded-extraction accuracy — both need their own gold sets
  before the booster pick is final.
