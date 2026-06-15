# Rating — 07-flavor-prompt-tuning

Prompt: ./prompt.md   ·   Date judged: 2026-06-15

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    1     |    4    | 4.30  |  1   |
| gemini   |    4     |   4   |       4       |    2     |    5    | 3.50  |  2   |
| deepseek |    3     |   4   |       4       |    3     |    4    | 2.95  |  3   |
| alice    |    3     |   3   |       3       |    3     |    4    | 2.50  |  4   |
| gpt      |    3     |   2   |       3       |    3     |    3    | 2.20  |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** opus — only one that catches the Yandex-API-stack ground truth
(no Yandex-managed DeepSeek URI; `json_schema` officially unconfirmed for
Lite; Yandex native vs OpenAI-compatible endpoint dialect quirks; Qwen3
thinking-mode incompat with structured output), plus the most principled
multiplicative confidence formula and the few-shot-bias citation (arXiv
2511.04053).

**Reuse:**
- Opus's grounded + zero-shot system prompts (RU, with `<VENDOR_TEXT>`
  wrap, anti-copy clause, Palladius transliteration rule) → seed for
  `context/flavor-system/prompts.md` when we draft them.
- Opus's per-dimension 0/1/3/5 anchor rubric (general rule + 11
  dimensions) → seed for the in-prompt rubric.
- Gemini's "Reference Tea Anchor" column (Sheng Pu-erh = BITTERNESS 3,
  Gyokuro = UMAMI 5, Lapsang = SMOKY 5, etc.) → human-rater calibration
  card.
- Opus's strict `json_schema` with `$defs.dim`,
  `additionalProperties:false`, transliterated + original + pinyin names,
  per-dim `evidence` flag → schema starting point. Inline the `dim`
  object 11 times if a model rejects `$defs` (Yandex-native endpoint).
- The three few-shot examples (Да Хун Пао yancha, Лунцзин green, Ми Лань
  Сян oolong) — maximally different, hold to ≤3 examples.
- Opus's four confidence multipliers (mode 1.0/0.7 · evidence-fraction ·
  name-resolution · schema-validity) → backend gate.
- Opus's Yandex API dialect notes (top-level `json_schema` wrapper for
  native API; `response_format:{type:"json_schema"}` for OpenAI-compat
  endpoint; SDK shape with schema fields directly under `json_schema`) →
  pick exactly one interface and pin it before integration.
- Opus's prompt-injection defense set (4k-char cap, HTML/markup strip,
  `<VENDOR_TEXT>` wrap, sandwich reminder, schema validation + fail-closed,
  n-gram overlap scan against source on the generated blurb) →
  pre-LLM hardening checklist.
- Opus's eval rubric (20–30 gold teas with extremes represented;
  per-dimension MAE ≤ 1.0; central-tendency check on 0/5 usage; injection
  attack-success rate target = 0%; transliteration regression list) →
  pre-launch gate.

**Discard:**
- Alice's "YandexGPT Lite контекст до 8K токенов" — it's 32,768 (per
  official docs).
- Alice's "Qwen3 на Яндексе ≤128K" — Yandex-hosted Qwen3-235B is 262,144
  (per official docs).
- Alice's / DeepSeek's "JSON Schema полная поддержка на YandexGPT Lite"
  — *officially unconfirmed*; every Yandex `json_schema` example uses
  Pro `yandexgpt/rc`. Test empirically, keep a `json_object` fallback.
- DeepSeek's implicit assumption that DeepSeek is Yandex-managed — it
  isn't (self-host on Yandex GPU cluster or call DeepSeek's own API,
  which supports only `json_object` for final output and needs the word
  "json" in the prompt).
- DeepSeek's `enum: ["green", …, null]` mixed inside a string enum —
  invalid Draft-07 (use a separate `type: ["string", "null"]` + enum
  pattern, the way Gemini and Opus model it).
- GPT's `temperature: 0.2–0.5` — too high for deterministic scoring;
  Opus's `0` (or Gemini's `0.1` as a soft compromise) is what the
  structured-extraction literature recommends.
- GPT's "2–4 few-shot examples достаточно" with no citation — research
  shows few-shot bias on numerical predictions grows monotonically with
  example count (arXiv 2511.04053); keep ≤3.
- GPT's claim that "DeepSeek поддерживает `guided_json`" — that's the
  vLLM/Aphrodite serving param, not a DeepSeek API surface.
- Gemini's 1500-char sanitize cap — unnecessarily tight; 4k matches
  realistic vendor prose without leaking attack surface.

> Then update ../LEADERBOARD.md: **+1 Wins** for the winner, **+1 Runs judged** for
> each model scored.
