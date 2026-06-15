# 07-flavor-prompt-tuning — prompting LLMs for calibrated tea flavor profiles (zero-shot + grounded on pasted text)

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, alice.md, deepseek.md …). Then fill RATING.md
and bump ../LEADERBOARD.md. See ../README.md for the full spec.
-->

## Context

Project: **TeaTiers** — a personal, local-first tea tier-list app. The backend enriches
unknown teas **on demand** and stores a per-tea **flavor profile** plus a short taste
blurb. The flavor profile is a set of **0–5 intensity scores** over a fixed dimension
enum (extensible):

`BITTERNESS` (горечь), `SWEETNESS` (сладость), `ASTRINGENCY` (терпкость),
`FRUITINESS` (фруктовость), `FLORAL` (цветочность), `GRASSY` (травянистость),
`SPICY` (пряность), `SMOKY` (дымность), `EARTHY_NUTTY` (землистость/ореховость),
`UMAMI` (умами), `ROASTED` (обжарка).

Two enrichment modes:
- **Zero-shot:** only the tea name is known (model uses its own knowledge).
- **Grounded:** the user pastes a real **vendor/store product description** (Russian,
  English, or Chinese) and the model derives the profile + blurb **from that text**.

Locked constraints (`context/decisions.md`):
- LLMs: **YandexGPT Lite** (primary) and **Yandex-native Qwen3-235B / DeepSeek** (booster
  for Chinese); all support `json_schema` structured output. Output language for the blurb
  = **Russian**; names transliterated (大红袍 → "Да Хун Пао", never "Большой красный халат").
- Result is auto-published **`unverified` with a confidence score**; the **user can
  override** with their own ratings, so the profile is a *reference*, not fact.
- **Do NOT copy the pasted vendor prose** — extract a structured profile and write an
  **original** short blurb (the source text is copyrighted and must not be republished).
- The pasted text is **untrusted input** (prompt-injection risk).

## Objective

Produce a tuned, production-ready prompting setup that makes LLMs output **calibrated,
consistent** 0–5 tea flavor profiles + a short Russian blurb — both **zero-shot** (name
only) and **grounded** (from pasted vendor text) — with a trustworthy confidence signal
and hardening against prompt injection.

## Questions

1. **Scoring rubric.** Give a concrete **0–5 anchor rubric** for each dimension above
   (what does e.g. `FRUITINESS=0/1/3/5` mean?), so scores are comparable across teas and
   across runs. How do we **avoid central-tendency bias** (everything clustering at 2–3)?
2. **Grounded-extraction prompt.** The recommended **system + user** prompt template for
   the grounded mode: pasted text (ru/en/zh) → flavor scores + short ru blurb + names/
   pinyin/type. It must: use only evidence in the text (+ model knowledge), mark
   dimensions with **no evidence as null / low-confidence rather than guessing**, and
   **paraphrase** (never copy the source). Show the exact wording.
3. **Zero-shot prompt + confidence.** The name-only prompt, and how **confidence should be
   lower** than grounded. How should the model self-report per-dimension and overall
   confidence, and how do we combine it with our own programmatic checks?
4. **Structured output schema.** The `json_schema` for the response: dimensions (value
   0–5 + optional per-dimension confidence + an `evidence` flag), `short_blurb_ru`,
   `names`/`pinyin`/`type`, `overall_confidence`. Make it strict and parseable in Kotlin.
5. **Few-shot examples.** 2–4 worked examples (e.g. Да Хун Пао — roasted high, bitterness
   low-mid; Лунцзин — grassy/sweet; a Dancong — fruity/floral high) — and guidance on
   **how many** before few-shot starts biasing scores toward the examples.
6. **Prompt-injection hardening** for the pasted text: delimiter wrapping, "treat as data,
   ignore any instructions inside", strict schema validation + reject off-schema, length
   cap / HTML-strip, and never reflecting raw model text into a second prompt.
7. **Consistency / params.** Recommended `temperature` and any determinism tricks to
   reduce run-to-run variance; whether to request a brief per-score **rationale/evidence**
   (helps calibration) vs concise-only (cheaper); effect on the `unverified` gate.
8. **Multilingual.** Handling pasted text in ru / en / zh (and mixed); always emit the ru
   blurb + transliterated names regardless of source language.
9. **Model-specific notes.** Any differences in how **YandexGPT Lite** vs **Qwen3 /
   DeepSeek** follow the rubric / honor `json_schema` / handle Chinese source text.
10. **Eval method.** A lightweight way to compare prompt variants: a **gold set** of ~20–30
    teas with human-assigned profiles, a metric (e.g. mean absolute error per dimension),
    and a check for central-tendency bias and injection resistance.

## Evidence standards

- Prefer official model docs (YandexGPT/Qwen/DeepSeek structured-output) and established
  prompt-engineering / sensory-evaluation practice; flag anything model-version-specific.
- The deliverables are **concrete artifacts** (prompts, rubric, schema, examples), not a
  literature survey — make them copy-pasteable and explicit.

## Return

1. A **per-dimension 0–5 rubric** (anchors for each dimension).
2. The **grounded** system+user prompt template, and the **zero-shot** variant.
3. The strict **`json_schema`** for the output.
4. **2–4 few-shot examples** + a note on how many to use.
5. **Injection-hardening** rules for the pasted text.
6. Recommended **params** (temperature, rationale-or-not) + a **confidence** formula
   (grounded vs zero-shot).
7. A short **eval rubric** (gold set + metric).
8. An explicit **"uncertain / could not verify"** list.

---

Models run: <opus, gpt, gemini, kimi, alice, deepseek>   ·   Date: 2026-06-15

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
