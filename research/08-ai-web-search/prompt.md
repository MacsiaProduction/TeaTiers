# 08-ai-web-search — Web-grounded LLM enrichment for tea descriptions

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a personal, local-first tier-list Android app for teas. A single
Linux backend (Kotlin + Spring Boot 4.1, JDK 21, PostgreSQL), **hosted in Russia**, serves
a multilingual (ru/en/zh + pinyin) tea catalog and enriches unknown teas **on demand**.
We have a **Germany (EU) VPN** and route only enrichment LLM HTTP calls through it
(decisions.md #15/#16, research/04-eu-egress-llm). Wikidata + YandexGPT stay direct.

What changed: brainstorm `context/brainstorming.md` reopens an explicitly out-of-scope
line in `context/plan.md` §6:

> *Out of scope for MVP: open-ended web crawling to discover teas.*

The original ruling (research/03-ai-enrichment) rejected web search because Brave's free
tier disappeared mid-2025 and we found no free RU-reachable search API. Today we have a
DE egress, so the constraint is "any provider reachable from a German IP, with terms that
permit storing and re-serving model output". We want to revisit whether a **web-grounded
LLM** can replace the locked **Wikidata → Wikipedia → user-pasted vendor text** chain as
the enrichment grounding, or at least act as the third stage when the first two miss.

Locked decisions relevant here:
- `decisions.md` #15/#16: Wikidata CC0 spine + curated seed; YandexGPT or Yandex-hosted
  open models behind a feature flag for free-text translation; ground LLM output on
  retrieved text only; never serve unverified LLM-only descriptions.
- `decisions.md` #25: enrichment is grounded on `sourceText` (Wikipedia / vendor copy
  pasted by the user) — *no* open-ended web search.
- `research/04-eu-egress-llm/RATING.md`: Gemini Flash free-tier from EU is the
  primary, YandexGPT Lite is the no-VPN fallback. **Both are pure LLMs without a built-in
  search tool**; structured output works but no grounding loop.
- `research/03-ai-enrichment/opus.md`: Brave Search free-tier is gone; Tavily/Serper/etc
  are paid. Bing (now `grounding.bing.com`) is Microsoft-only, paid. Yandex Search has a
  paid API.
- The recurring trap: a provider's terms must **permit storing model output in our DB and
  re-serving it**. This already disqualified Yandex *Maps*; it must be confirmed for any
  new provider.

What "good enough" looks like for us: from the user's typed name (Russian, English, Pinyin,
or 汉字) plus optional pasted vendor text and product link, return a verified tea record:
canonical multilingual names + pinyin, type (green/oolong/black/dark/puer/white/yellow/
herbal/blended), origin (region/province/county), short blurb (2–3 sentences), and a
standardised flavor profile over our 11-dimensional system (`decisions.md` #23). Output
must be groundable: every claim must be traceable to a source URL the model used.

## Objective

Decide whether **web-grounded LLM enrichment is now viable for TeaTiers** under the
DE-egress constraint, ToS for storing+serving output, ru/zh tea-naming quality, and cost.
The decision drives plan.md §6: keep "out of scope" or upgrade to "web-grounded fallback
when Wikidata + user-pasted text miss". The output must let us either (a) confirm the
status quo with a one-line reason, or (b) name one provider + request config.

## Questions

1. **Web-grounded LLM APIs reachable from a German/EU IP, free-tier-without-card.**
   For each, list: provider name, the exact endpoint or SDK call that triggers
   *grounded* generation (the model is allowed to fetch web pages and cite them), free-tier
   limits (RPM/RPD/TPM/searches per day), whether **billing/credit card is required to
   activate the free tier**, and the date you verified each. Cover at least:
   - Google Gemini API: the **`googleSearch`** tool / "grounded with Google Search"
     mode on the free tier (does it cost extra above the base free-tier? is it gated to
     paid? which Flash / Flash-Lite model ids support it?).
   - OpenAI: the **`web_search`** built-in tool on the Responses API (free for any model
     id? rate-limited how? does the free tier even exist on a fresh account without
     billing?).
   - Anthropic Claude: the **`web_search`** server tool (does Anthropic offer a *free* tier
     reachable from EU without a billing card? if not, the lowest paid bracket is fine —
     pin price per 1M input/output + per search).
   - Perplexity API: the **`pplx-*`** / Sonar models (which are grounded by default;
     pricing per request including search; any free tier?).
   - You.com / Phind / others: any free or *very* cheap grounded-LLM API reachable from
     EU. Skip if you cannot find verifiable docs.
   - DeepSeek + Qwen + Mistral: do *any* of them ship a built-in web search tool, or is
     it always BYO retrieval? If BYO retrieval, treat them as not-applicable for this run
     (we already have YandexGPT for plain LLM).

2. **Search-only APIs that pair with our existing LLM.** If the answer to (1) is "no
   provider gives us free grounded generation from EU", a fallback architecture is
   `<search API> + <our chosen LLM from research 04>`. Verify:
   - **Brave Search API** — has the free tier come back in any form (community tier,
     researcher allowance)? If not, the cheapest paid tier (price per 1k requests).
   - **Tavily, Serper, You.com Search, SearXNG self-hosted** — free-tier limits, EU
     reachability, ToS on storing the *retrieved snippets and URLs* in our DB and
     re-serving them as part of a tea description.
   - **Yandex Search API** — pricing, ToS specifically on whether returned snippets can
     be stored and re-served from a third-party app (this trap killed Yandex Maps for us
     in research 02).
   - **Wikipedia/Wikidata MCP-like search** — does Wikipedia's search API give us enough
     coverage on niche Chinese/Russian tea names to skip generic web search entirely? Run
     5 representative queries and report hit/miss (e.g. "Да Хун Пао", "大红袍", "Lapsang
     Souchong", "Шу Пуэр", "Bai Hao Yin Zhen").

3. **Output-storage ToS for each candidate provider.** For every entry in (1) and (2),
   quote the exact contract clause that lets us **store the generated description /
   retrieved snippets in our PostgreSQL and re-serve them** to our app's users
   (commercially, in a public-store-distributed product). Many providers tacitly allow
   this; some explicitly forbid storing search snippets beyond a TTL. Where the terms are
   ambiguous, say so.

4. **Tea-naming quality smoke test.** Pick the top three candidates from (1)+(2) and run
   each on this exact set of 8 inputs (return the canonical record described under
   *Objective*):
   - "Da Hong Pao" (English)
   - "Да Хун Пао" (Russian)
   - "大红袍" (Chinese)
   - "lapsang souchong" (English, lower case, blend variant)
   - "Шу Пуэр" (Russian, dark tea)
   - "tieguanyin" (Pinyin, oolong)
   - "Бай Хао Инь Чжэнь" (Russian, white tea)
   - "Сосновый Уголь" (made-up Russian name; the model should refuse / mark uncertain
     instead of hallucinating a tea).
   Score each candidate on: name correctness across ru/en/zh + pinyin, type/origin
   accuracy, blurb quality (no obvious hallucination), and refusal-on-unknown for the
   eighth input.

5. **Real cost at our scale.** TeaTiers expects roughly **30–80 enrichment calls per
   day** in production (one user, occasional sharing). For each viable provider from (1)
   and (2), compute the **monthly cost at 80 calls/day, ~2k tokens in / ~1k out per call,
   1 grounded search per call**. Show the math; identify any minimum monthly fees.

6. **Recommendation.** Either:
   - "Keep plan.md §6 'out of scope' unchanged" — one-line reason (e.g. "no provider
     meets free + EU + storage-OK + ru/zh quality"), OR
   - "Upgrade to a web-grounded fallback when Wikidata + user-pasted text miss" — name
     the primary provider, give the exact request config (model id, endpoint, JSON-schema
     / structured-output for the grounded record, the search tool config, training
     opt-out), and the no-VPN fallback (probably "skip, use YandexGPT plain").

## Evidence standards

- Prefer official provider docs / pricing & terms pages over blog posts.
- Pin exact model ids / free-tier numbers / API versions / endpoint URLs; explicitly flag
  anything you could not confirm on an official page — especially **EU
  free-tier-without-card** and **store+serve-output** clauses.
- For tea-naming quality (Q4): treat the model's own output as evidence of quality, but
  cite a Wikipedia / Wikidata page for the canonical "correct" answer when scoring.
- Cite every claim with a link and its publication / last-checked date; prefer recent
  sources (≤ 90 days where possible).

## Return

1. A **comparison table** — Provider | Free tier (EU, no card?) | Grounded-search support
   (built-in / BYO / none) | Stores + serves output OK? | ru/zh tea-naming quality
   (Q4 score) | Monthly cost at 80 calls/day | Notes.
2. **Verdict on plan.md §6** (keep out-of-scope vs upgrade) with a one-line reason.
3. If the verdict is "upgrade": the recommended primary's full **request config** (model
   id, endpoint, structured-output schema for our canonical record, search tool config,
   training opt-out if any) and the fallback order.
4. **5–8 dated reference links** and an explicit **"uncertain / could not verify"** list,
   especially around (a) EU free-tier-without-card terms and (b) the right to store
   retrieved snippets + generated output.

---

Models run: opus, gpt, gemini, deepseek   ·   Date: 2026-06-15

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
