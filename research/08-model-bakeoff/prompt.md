# 08-model-bakeoff — picking the LLM(s) for TeaTiers flavor enrichment (§6 step 3)

<!--
ADAPTED RUN. The standard research/ workflow is "one question -> many models ->
verbatim answers -> RATING". This run is a PROGRAMMATIC BAKE-OFF: the *prompt is
fixed* (the production flavor-profiling prompt below, from run 07's winner) and the
*model is the variable*. `run_bakeoff.py` sends this exact prompt to every candidate
over a shared gold set of teas, parses the structured output, and scores per-dimension
MAE + central-tendency + injection resistance. Per-model raw outputs land in
`results/<model>.jsonl` (verbatim, the evidence); `results/scores.md` is the table;
`RATING.md` names the winner. No per-model prompt tailoring (fair comparison).
-->

## Goal

Choose the production model(s) for the on-demand tea flavor-profiling tier (plan §6
step 3). Two roles:

- **Primary** (ru/en source, writes the Russian blurb) — cheap, strong Russian, honors
  `json_schema`, low per-dimension error.
- **Chinese-source booster** — best on zh vendor text / zh tea names.

## Candidate models (verified live 2026-06-16, folder-scoped to our SA)

Endpoint + structured-output dialect differ by model (confirmed by probe, see
`context/decisions.md`):

| Model | `modelUri` slug | Endpoint | json_schema | ₽/1k in–out |
|-------|-----------------|----------|:-----------:|-------------|
| YandexGPT Lite | `yandexgpt-lite` | native | yes | 0.2 / 0.2 |
| YandexGPT Pro 5.1 | `yandexgpt/rc` | native | yes | 0.8 / 0.8 |
| Alice LLM | `aliceai-llm` | native | yes | 0.5 / 1.2 |
| Alice Flash | `aliceai-llm-flash` | OpenAI-compat | yes | 0.1 / 0.2 |
| Qwen3-235B | `qwen3-235b-a22b-fp8/latest` | OpenAI-compat | yes | 0.5 / 0.5 |
| DeepSeek V4 Flash | `deepseek-v4-flash` | OpenAI-compat | yes | 0.3 / 0.5 |

- **Native** = `POST llm.api.cloud.yandex.net/foundationModels/v1/completion`,
  structured output via a top-level `jsonSchema:{schema:{…}}`.
- **OpenAI-compat** = `POST llm.api.cloud.yandex.net/v1/chat/completions`,
  structured output via `response_format:{type:"json_schema",json_schema:{name,strict,schema}}`.
- Auth: `Authorization: Api-Key <key>` (key read at runtime from Lockbox
  `teatiers-llm-api-key`; never in VCS).

## Method (from research/07 winner — opus.md)

- **Mode tested:** zero-shot (tea **name only**) — this isolates the model's tea
  knowledge + rubric adherence + central-tendency bias, the things that separate models.
- **Prompt:** run-07 zero-shot system prompt + the 0/1/3/5 anchor rubric + **3** maximally
  different few-shot examples (Да Хун Пао yancha / Лунцзин green / Ми Лань Сян oolong) +
  the strict `json_schema` (the `dim` object **inlined 11×**, not `$ref` — some Yandex
  endpoints choke on `$defs`). Temperature 0.
- **Gold set:** `gold.json` — 24 teas spanning every type and the dimension extremes
  (UMAMI 5 gyokuro, SMOKY 5 lapsang, EARTHY 5 shou, FLORAL 5 jasmine, SPICY 5 masala,
  BITTERNESS 4 young sheng, ASTRINGENCY 4 assam, ROASTED 4 hojicha). The 3 few-shot teas
  are **excluded** from the gold set (no leakage). Anchors are a synthesis of standard
  tasting notes — **flagged for taster recalibration**, not lab truth.
- **Metrics:** per-dimension and overall **MAE** (target ≤ 1.0); **central-tendency**
  (share of scores in {2,3} and 0/5 usage vs gold); **valid-JSON / schema rate**; a small
  **injection set** in grounded mode (attack-success target 0%); **type accuracy**,
  transliteration sanity, latency, est. cost.

## Deliverable

`results/scores.md` (the metric table) + `RATING.md` (winner for **primary** and for
**zh-booster**, with reuse/discard) + a `LEADERBOARD.md` bump.

---

Run date: 2026-06-16
