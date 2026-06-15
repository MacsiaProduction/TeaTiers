# 06-yandex-alice — using Alice AI LLM from Yandex Cloud, and whether it fits TeaTiers enrichment

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

## Context

Project: **TeaTiers** — a personal, local-first tea tier-list app. The backend enriches
unknown teas **on demand**: it normalizes/translates names to **ru/en/zh + pinyin**,
verifies facts, and **auto-publishes the tea flagged `unverified` with a confidence
score**. Current plan (`context/decisions.md` #17): **Wikidata → Wikipedia** (free,
direct) first; **YandexGPT Lite** (Yandex Cloud Foundation Models, direct from our
RU-hosted backend, `json_schema` output, logging disabled) as the **primary LLM**; Groq
`qwen/qwen3-32b` via a Germany VPN as a Chinese-name booster.

We noticed Yandex Cloud Foundation Models also lists an **"Alice AI LLM"** model and want
to understand it and whether it should replace or supplement `yandexgpt-lite` as our
enrichment primary. The hard task is correct Chinese→Russian **transliteration** of tea
trade names (大红袍 → "Да Хун Пао", NOT the literal "Большой красный халат") plus correct
pinyin and Hanzi.

## Objective

Explain exactly what Alice AI LLM is in Yandex Cloud, how to call it from a backend, its
pricing/limits/terms relative to `yandexgpt-lite`, and give a clear recommendation on
whether to make Alice the enrichment primary (or keep YandexGPT Lite).

## Questions

1. **What it is.** What is **"Alice AI LLM"** in Yandex Cloud AI Studio / Foundation
   Models? Confirm its **exact model URI** (e.g. `gpt://<folder_id>/aliceai-llm` — verify
   the slug and any `/latest`/`/rc` branch), how it relates to the YandexGPT family
   (same models behind Alice? a distinct tuned model?), its **context window**, and its
   intended use. **Distinguish it clearly from the consumer "Alice" voice assistant and
   from Alice Skills / Dialogs** — we want the *Foundation Models LLM*, not the
   voice-assistant skills platform.
2. **How to call it.** Does it use the same Foundation Models **REST/gRPC** and
   **OpenAI-compatible** endpoints as YandexGPT (`llm.api.cloud.yandex.net/.../completion`
   and `ai.api.cloud.yandex.net/v1`)? Auth (IAM token / API key / service account), and
   does it support **structured output** (`response_format` / `json_schema`)? Give a
   minimal request example (curl or Kotlin) for our use case.
3. **Pricing & limits.** Per-token price vs `yandexgpt-lite` and `yandexgpt` (Pro); does
   the new-account **grant** cover it; rate/throughput quotas. Is it cheaper, similar, or
   pricier than `yandexgpt-lite`?
4. **Terms.** Do the same Yandex AI Studio Terms apply (store + re-serve output OK; logging
   disable via `x-data-logging-enabled: false`)? Any **Alice-specific** restriction
   (e.g. tied to the assistant, different data terms, region limits)?
5. **Quality for our task.** Is Alice AI LLM better than `yandexgpt-lite` at **Chinese→
   Russian tea-name transliteration**, pinyin, and Hanzi — or at Russian fluency? Any
   evidence/benchmarks. Where would it still fail (and need the Groq/DeepSeek booster)?
6. **Recommendation.** Should TeaTiers make **Alice the enrichment primary**, keep
   **YandexGPT Lite**, or use Alice only for specific cases? Give the config for whichever
   you recommend (model URI, endpoint, JSON-schema setup, logging-off header).

## Evidence standards

- Prefer official Yandex Cloud AI Studio / Foundation Models docs over blog posts.
- **Pin the exact model URI / id and price figures**; explicitly flag anything you can't
  confirm on an official page, and call out any confusion between the API LLM and the
  consumer Alice assistant.
- Cite every claim with a link and its publication/last-checked date; prefer recent
  sources.

## Return

1. A **fact sheet**: model URI/id, endpoint(s), auth, structured-output support, context
   window, price vs `yandexgpt-lite`, rate limits, ToS (store+serve, logging-off).
2. A **minimal how-to** request example for tea-name enrichment (JSON-schema output).
3. A **clear recommendation**: Alice as primary, keep YandexGPT Lite, or Alice for
   specific cases — with the one-line reason and the exact model config to use.
4. 5–8 dated reference links and an explicit **"uncertain / could not verify"** list
   (especially the exact model URI, pricing, and any Alice-vs-assistant confusion).

---

Models run: <opus, gpt, gemini, kimi>   ·   Date: 2026-06-15

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
