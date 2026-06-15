# 03-ai-enrichment — free / Yandex-Cloud AI + search providers for on-demand tea-catalog enrichment

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

## Context

Project: **TeaTiers** — a personal, local-first tier-list Android app for teas. A single
Linux backend (Kotlin + Spring Boot 3.x + PostgreSQL) serves a shared, multilingual
(ru/en/zh + pinyin) **tea catalog**. The catalog's redistributable core comes from
Wikidata (CC0) + a hand-curated seed; open data is English-heavy and thin in the long
tail, so we want to **enrich on demand** with AI.

Locked decisions relevant here:
- Enrichment is **server-side only** (API keys never ship in the app) and runs
  **on-demand** ("enrich-on-miss"): when a user adds a tea not in the catalog, the
  backend resolves it, then **auto-publishes it flagged `unverified` with a confidence
  score**. The published row is cached so the same tea is never enriched twice.
- Enrichment **jobs in scope:** (a) translate/normalize names to ru/en/zh + pinyin and
  fix transliterations, (b) verify/cross-check type/origin/oxidation against
  authoritative sources, (c) auto-fill a user's custom tea. **Out of scope:** crawling
  to *discover* new teas.
- **Cost constraint (hard):** prefer **Yandex Cloud** AI quota the user already has;
  otherwise only **genuinely-free** options. **No new paid services.**
- The backend is **hosted in Russia** → Western APIs may be geo-blocked; RU
  reachability is a first-class requirement.
- Tea-name transliteration is error-prone (e.g. 大红袍 → "Да Хун Пао", never "Большой
  красный халат"), so verification and confidence matter.

## Objective

Decide which AI/LLM and (only if free + RU-reachable) search/verification providers to
use for on-demand tea-catalog enrichment, under a strict "Yandex Cloud first, otherwise
free-only, no new paid services" budget — including each provider's free-tier limits,
RU reachability, ru/zh tea-naming quality, and whether their **terms permit storing and
re-serving model output** in our own database.

## Questions

1. **Yandex Cloud Foundation Models (YandexGPT).** Current model names/versions and API
   (REST/gRPC, auth via IAM token / API key / service account); how free usage / grants
   work (the "free grant" for new Cloud accounts, any always-free tier, and how billing
   kicks in after); rate/throughput limits; structured-JSON output support; and — most
   importantly — do the **terms permit storing generated output in our DB and serving it
   to users**? Pin model ids and cite the official Yandex Cloud docs.
2. **Yandex Translate API (Cloud).** Is it a cheaper/simpler path than the LLM for
   ru↔en name translation? Free grant / limits; does it handle CJK (zh) and produce
   pinyin or only translation? Quality caveats for proper nouns / tea trade names.
   Storage-of-output terms.
3. **Genuinely-free LLM alternatives reachable from a RU-hosted server.** Assess
   free-tier LLM APIs (e.g. Google Gemini API free tier, Groq free tier, Mistral free
   tier, OpenRouter free models, DeepSeek, GigaChat/Sber) on: is there a real free tier,
   its limits, **whether the endpoint is reachable from Russian IPs / needs a non-RU
   card or phone**, ToS on storing output, and ru/zh quality. Explicitly flag which are
   geo-blocked or require paid signup.
4. **Free verification sources (no LLM).** How to verify/cross-check a tea cheaply: the
   **Wikidata API/SPARQL** (free) for entity match + multilingual labels; Wikipedia
   (ru/zh) titles; any free, RU-reachable web-search API (Brave, Tavily, DuckDuckGo,
   Yandex Search API) with its free-tier limits and ToS. Recommend the minimum that
   gives good coverage without a paid search API.
5. **Enrichment pipeline design.** Given "Wikidata first → LLM fallback → cross-check →
   publish unverified", recommend: the prompt/JSON-schema shape for the LLM call
   (fields: ru/en/zh names, pinyin, type, origin, oxidation, confidence), how to compute
   a trustworthy **confidence score**, dedup/identity strategy (normalized name + pinyin
   slug), and how to keep the free-tier quota safe (per-IP rate limits, daily caps,
   caching, debounce).
6. **Abuse & safety.** This is a public, unauthenticated, write-capable endpoint taking
   a free-text "tea name". Recommend input validation/caps, prompt-injection mitigation,
   and cost-control guardrails so a malicious caller can't drain the AI quota.

## Evidence standards

- Prefer official provider docs / pricing & terms pages over blog posts.
- Pin exact model ids / API versions / free-tier numbers; explicitly flag any number you
  could not confirm on an official page, and any RU-reachability claim you could not
  verify.
- Cite every claim with a link and its publication/last-checked date. Be explicit about
  each provider's **storing-and-serving-output** terms and **geo-blocking**.

## Return

1. A **comparison table**: `Provider | Type (LLM/translate/search) | Free tier / Yandex
   grant | RU-reachable? | Stores+serves output OK? | ru/zh quality | Notes`.
2. A **recommended stack** under the budget: primary (Yandex Cloud), free fallbacks, and
   the free verification path — with a one-line reason each.
3. A **pipeline sketch** (Wikidata-first → LLM fallback → cross-check → publish), the
   LLM **JSON schema**, and the confidence-scoring approach.
4. **Quota-protection + abuse guardrails** for the public `resolve` endpoint.
5. 5–8 dated reference links and an explicit **"uncertain / could not verify"** list
   (especially Yandex free-grant terms, output-storage terms, and RU geo-blocking).

---

Models run: <opus, gpt, gemini, kimi>   ·   Date: 2026-06-15

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
