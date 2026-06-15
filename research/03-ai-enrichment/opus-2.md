# TeaTiers Enrichment Pipeline: AI/LLM and Free Verification Providers (RU-Hosted)

## TL;DR
- **Build it on Yandex AI Studio (YandexGPT) as the primary LLM, with Wikidata + Wikipedia (MediaWiki) as the free, RU-reachable verification spine, and GigaChat (Sber) as the only genuinely RU-reachable free LLM fallback.** Yandex's terms (AI Studio Terms, clause 4.1) explicitly let you use generated output "in any manner not contradicting these Terms," and clause 3.15 carves out embedding output into your own product — so storing it in PostgreSQL and re-serving it to users is permitted.
- **Most Western "free tier" LLMs (Gemini, Groq, Mistral, OpenRouter, DeepSeek) are NOT reliably reachable from Russian IPs or require non-RU signup** — Google's official "Available regions" doc omits Russia entirely. Treat them as unusable from a Russia-hosted server unless you proxy (which breaks the "no new paid services / RU-reachability" constraint).
- **Yandex Translate is the cheap path for ru↔en prose but must NOT be used for tea names** — it produces literal translations (大红袍 → "Большой красный халат") instead of the correct transliteration "Да Хун Пао" (pinyin Dà Hóng Páo). Use it only for descriptions; use LLM + transliteration validation for names, and gate everything behind a confidence score.

## Key Findings

### Yandex is the only fully RU-first, terms-clean, free-grant-backed option
Yandex AI Studio (the rebranded Foundation Models service) is hosted in Russia, complies with 152-FZ, is reachable from RU IPs, gives new accounts an initial grant, supports JSON-schema structured output, and — critically — its terms permit storing and re-serving generated output as part of your own product.

### Free Western LLMs are a geo-blocking minefield from Russia
Gemini, Groq, Mistral, OpenRouter, and DeepSeek all have real free tiers, but reachability/signup from Russia is the blocker, not the quota. Only GigaChat (and Yandex itself) are confirmed RU-reachable.

### The free verification spine (Wikidata + Wikipedia) is RU-reachable and ToS-clean
This is where you get the most leverage for zero cost and is the heart of the "Wikidata-first" design.

## Comparison Table

| Provider | Type | Free tier / Yandex grant | RU-reachable? | Stores+serves output OK? | ru/zh quality | Notes |
|---|---|---|---|---|---|---|
| **Yandex AI Studio / YandexGPT** | LLM | Initial grant ₽4,000 (RU resident, incl. VAT) / $50 (non-resident), expires 60 days; one-time; no perpetual free LLM API tier; console playground 10 prompts/hr without billing | **Yes (RU-hosted, 152-FZ)** | **Yes** — Terms cl. 4.1 + 1.2.2 + 3.15 (embed in own product) | Excellent Russian; zh present but verify transliteration | json_schema structured output; OpenAI-compatible endpoint; disable logging (cl. 4.2) |
| **Yandex Translate** | translate | Same Yandex grant; ~$4.10 / 1M chars (RU region, ex-VAT) after | **Yes** | Yes (same terms) | ru↔en good; **zh→ literal, NO pinyin, wrong for names** | Use for prose only, never tea names |
| **GigaChat (Sber)** | LLM | **1,000,000 free tokens/year** (Freemium, single-thread; limit refreshes every 12 months) | **Yes (RU-hosted, no VPN)** | Yes (Russian provider) | Best-in-class Russian; weaker niche zh | **Signup needs RU phone / SberID**; OpenAI-compatible; GigaChat-2/Pro/Max 128k ctx |
| **Google Gemini API** | LLM | Flash ~10 RPM/250k TPM/~1,500 RPD; Pro 5 RPM/100 RPD (post-Dec 2025 cuts); no card | **No — Russia not in supported regions** | n/a from RU | Strong | Officially unavailable in Russia |
| **Groq** | LLM | 30 RPM / 6k TPM; llama-3.1-8b-instant 14,400 RPD / 500k TPD; llama-3.3-70b 1,000 RPD / 12k TPM; no card (org-level) | **Unverified** (US co., sanction risk) | Per ToS, no training on data | Open models (Llama/Qwen); decent | OpenAI-compatible; reachability not officially supported |
| **Mistral La Plateforme** | LLM | Free "Experiment" tier, rate-limited (~1 RPS / 500k TPM / ~1B tok/mo, community-reported) | **Unverified** (EU-hosted) | Yes (EU residency) | EU-strong; zh moderate | Signup needs phone verification |
| **OpenRouter (free models)** | LLM | `:free` models 20 RPM / 50 RPD (→1,000 RPD after one-time $10 credit) | **Unverified** | No training on data; provider retention disable-able | Varies by model | Many underlying free models geo-restricted |
| **DeepSeek API** | LLM | **5,000,000 free tokens on signup** (~$8–10, ~30-day validity); no hard rate caps ("serves every request it can handle") | **Unverified** (China-hosted; generally RU-reachable, signup may need non-RU phone/card) | Pay-per-token after | Strong multilingual (zh + ru) | OpenAI- and Anthropic-compatible |
| **Wikidata SPARQL + wbsearchentities** | search/verify | **Free** | **Yes** | **Yes (CC0 data)** | Multilingual labels ru/en/zh | 60s query/min per IP; 10k-row cap; 60s timeout |
| **Wikipedia (MediaWiki API)** | search/verify | **Free** | **Yes** | Yes (CC BY-SA) | ru/zh article titles via sitelinks | Respect Retry-After / maxlag |
| **Brave Search API** | search | **Free tier eliminated Feb 2026** → $5 metered credit (~1,000 q), card + attribution required | Unverified | Storage needs explicit storage-rights plan | Independent index | No longer genuinely free |
| **Tavily** | search | **1,000 API credits/month free, no card** (basic search = 1 credit; PAYG $0.008/credit) | **Unverified** (US-hosted) | Yes | Good | Best free web-search fallback if reachable |
| **Yandex Search API** | search | Paid PAYG, no free tier beyond Yandex grant (~₽122,000 / 250k sync req) | **Yes** | Yes | RU-strong | Use sparingly within grant |
| **DuckDuckGo** | search | No official API | n/a | Gray-area | — | Not recommended as a dependency |

## Recommended Stack (under budget)
- **Primary LLM — YandexGPT Lite** (`gpt://<folder>/yandexgpt-lite`, OpenAI-compatible endpoint, json_schema): RU-hosted, terms permit store+serve, cheapest model, grant-funded. *Reason: only fully RU-first, ToS-clean, structured-output-capable option.*
- **Free LLM fallback — GigaChat (Sber):** 1M free tokens/year, RU-reachable without VPN, ideal for cross-checking Russian transliterations. *Reason: the only genuinely-free LLM confirmed reachable from a RU server.*
- **Free verification path — Wikidata SPARQL + `wbsearchentities` + Wikipedia ru/zh sitelinks:** *Reason: zero cost, RU-reachable, CC0/CC-BY-SA data with no storage restrictions; resolves most known teas with no LLM spend.*
- **Optional web cross-check — Tavily (1,000 free credits/mo)** only if Wikidata coverage is insufficient and after verifying RU reachability. *Reason: best remaining genuinely-free search tier; Brave's free tier is gone.*
- **Translate — Yandex Translate for descriptions only.** *Reason: cheap per-char ru↔en, but literal on names — wrong for tea trade names.*

## Details

### 1. Yandex Cloud Foundation Models / AI Studio (YandexGPT)
**Models and URIs** (AI Studio "Common instance models," updated Feb 3 2026):
- YandexGPT Pro 5.1 — `gpt://<folder_ID>/yandexgpt/rc` — 32,768 ctx
- YandexGPT Pro 5 — `gpt://<folder_ID>/yandexgpt/latest` — 32,768
- YandexGPT Lite 5 — `gpt://<folder_ID>/yandexgpt-lite` — 32,768
- Alice AI LLM — `gpt://<folder_ID>/aliceai-llm` — 32,768
- Also hosted: Qwen3 235B (`/qwen3-235b-a22b-fp8/latest`, 262,144), gpt-oss-120b/20b (131,072), Gemma 3 27B (131,072, subject to Gemma Terms)
- Branches: `/latest` (stable, default), `/rc`, `/deprecated`. Lifecycle: RC→Latest one month after announcement; old Latest→Deprecated, supported one further month.

**API surface:** REST + gRPC. Native: `https://llm.api.cloud.yandex.net/foundationModels/v1/completion`. OpenAI-compatible: `https://ai.api.cloud.yandex.net/v1` (set `base_url` + `project=<folder_ID>`). Auth via IAM token (`Authorization: Bearer <IAM_TOKEN>`), API key (recommended for service accounts, scope `yc.ai.foundationModels.execute`), or service-account/OAuth. Role: `ai.languageModels.user`.

**Structured JSON output:** Supported — `response_format` with `type:"json_schema"` (recommended) on the OpenAI-compatible API; native API supports `json_object` and `json_schema`. You must still instruct the model to emit JSON in the prompt.

**Free usage / grants:** New customers receive a one-time initial grant on first billing account, expiring in 60 days: at least ₽4,000 (incl. VAT) for RU residents or $50 for non-residents. The console AI Playground gives 10 free prompts/hour without billing, but **API access requires an ACTIVE or TRIAL_ACTIVE billing account.** No perpetual always-free LLM API tier; pay-as-you-go after the grant.

**Pricing (Russia region, ex-VAT, per 1,000 tokens, synchronous):** YandexGPT Lite $0.001667 in/out; Pro 5.1 $0.003334 (current 50% discount); Pro 5 $0.010002. Tokenizer calls free. (AI Studio pricing, updated Dec 24 2025.)

**Rate limits / throughput** (Foundation Models quotas): 10 concurrent synchronous generations; async 10 req/s (request), 50 req/s (response retrieval), 5,000 req/hour async; tokenization 50 req/s. Async results stored 3 days. Quotas raisable via support.

**Storage & re-serving of output — PERMITTED.** Yandex AI Studio Terms (placed Apr 17 2026, effective Apr 28 2026; renamed from "Yandex Foundation Models"):
- **Cl. 4.1:** "The Customer has the right to use the Service and the Generated Content in any manner not contradicting these Terms and applicable law."
- **Cl. 1.2.2:** permits "Embed the text, image, and audio generation technology into the Customer's end product."
- **Cl. 3.15:** Customer is the end commercial user; reselling the Service via simple API pass-through is prohibited, but this "does NOT apply to cases where the Service is...used by the Customer or User to refine and develop the Customer's or User's own product and to subsequently sell such product." → Storing output in your DB and re-serving via your own app is allowed; pure API reselling is not.
- **Cl. 3.1/3.2:** Yandex does not guarantee accuracy and is not responsible for content of Generated Content.
- **Cl. 3.3:** "The Customer must independently verify the Generated Content for accuracy, reliability, relevance, and legality before using...and/or before its further distribution." — a contractual basis for the `unverified` flag + confidence score.
- **Cl. 4.2:** Yandex may use your Requests + Generated Content for analytics/model improvement unless you disable logging. **Recommendation: disable data logging on the resolve endpoint.**
- The English `/en/` version of these terms is NOT served (Russian only); treat English wording as unofficial.

### 2. Yandex Translate API
- **Pricing:** per character incl. spaces/special chars, ~$4.10 / 1M chars (Russia region, ex-VAT); billed monthly; separate language-detection requests are chargeable. Max string 2,000 chars; glossary ≤50 pairs / ≤20,000 chars.
- **Free grant:** covered by the same Yandex Cloud initial grant; no separate perpetual free Translate tier. (Online "1M chars/day free" figures refer to the legacy translate.yandex public API, not the Cloud API — not applicable.)
- **Chinese (zh):** supported as a translation pair (en-zh, ru-zh). **Does NOT produce pinyin** — only translated text. Generate pinyin separately (pinyin library or the LLM).
- **Quality caveat:** MT translates literally rather than transliterating. 大红袍 → "Большой красный халат" (literal "big red robe") instead of the correct "Да Хун Пао." **Do not use Translate for tea names** — descriptive prose only.
- **Storage of output:** same Yandex terms; store+re-serve as part of your product permitted.

### 3. Genuinely-free LLM alternatives — RU reachability
- **GigaChat (Sber) — RU-reachable, recommended free fallback.** Per Sber's official tariffs doc: "В рамках Freemium-режима пользователи получают 1 000 000 бесплатных токенов для генерации текста... Генерация текста выполняется в одном потоке. Лимит обновляется раз в 12 месяцев" (1,000,000 free tokens, single-thread, limit refreshes every 12 months). OAuth at `https://ngw.devices.sberbank.ru:9443/api/v2/oauth` (scope `GIGACHAT_API_PERS`); generation at `https://gigachat.devices.sberbank.ru/api/v1` (OpenAI-compatible). 152-FZ compliant, works without VPN from Russia/CIS. **Signup requires a Russian phone / SberID.** Models GigaChat-2 / -Pro / -Max all 128k context (gen-1 models deprecated, auto-redirected). Strong Russian; cross-check niche Chinese tea names.
- **Google Gemini API — GEO-BLOCKED from Russia.** Google's official "Available regions for Google AI Studio and Gemini API" (ai.google.dev/gemini-api/docs/available-regions) omits Russia from the supported list; unsupported regions get "Google AI Studio is not available in your region." Generous free tier is irrelevant from a RU server without a proxy.
- **Groq — no documented RU block but unverified/sanction risk.** Per console.groq.com/docs/rate-limits: free tier 30 RPM / 6,000 TPM; llama-3.1-8b-instant 14,400 RPD / 500,000 TPD; llama-3.3-70b-versatile 1,000 RPD / 12,000 TPM / 100,000 TPD. Org-level limits, no card, OpenAI-compatible. RU reachability not officially supported — flag uncertain.
- **Mistral La Plateforme — EU-hosted; signup needs phone verification; RU reachability unverified.** Free "Experiment" tier, rate-limited (~1 RPS / 500k TPM / ~1B tokens/month, community-reported).
- **OpenRouter — `:free` models at 20 RPM / 50 RPD (→1,000 RPD after one-time $10 credit); does not train on your data; provider retention disable-able.** RU reachability unverified; many free models themselves geo-restricted.
- **DeepSeek — China-hosted; per api-docs.deepseek.com new accounts get 5,000,000 free tokens (~$8–10, ~30-day validity); "serves every request it can handle, without per-user request caps"; OpenAI- and Anthropic-compatible.** Not officially RU-blocked and China infra is generally RU-reachable, but signup may need non-RU payment/phone — reachability uncertain. Strong zh + ru quality.

### 4. Free verification sources (no LLM)
- **Wikidata SPARQL (`https://query.wikidata.org/sparql`) — free, RU-reachable, ToS-clean.** XML or JSON (`format=json`/Accept header). Limits per IP+User-Agent: 60s query time/min (burst 120s), 30 errors/min; hard 10,000-row cap; 60s timeout. Use for entity matching and multilingual labels (ru/en/zh).
- **Wikidata `wbsearchentities` / MediaWiki Action API — free.** `wbsearchentities` for fuzzy label→QID; global Wikimedia rate limits; 429 → respect `Retry-After`; use `maxlag`. SPARQL `wikibase:mwapi` can call MediaWiki inline (e.g. EntitySearch).
- **Wikipedia (ru/zh) titles via MediaWiki API — free.** Use langlinks/sitelinks from the Wikidata item to get ru and zh article titles (more reliable than free-text search).
- **Web search APIs:** **Brave** free tier eliminated Feb 2026 → $5 metered credit (~1,000 q), card + attribution required; storing results needs an explicit storage-rights plan. **Tavily** — 1,000 API credits/month free, no card (basic search = 1 credit; PAYG $0.008/credit); RU reachability unverified. **DuckDuckGo** — no official API; not recommended. **Yandex Search API** — RU-reachable but paid PAYG, no free tier beyond the grant.
- **Recommended minimum set:** Wikidata SPARQL + `wbsearchentities` + Wikipedia ru/zh sitelinks. Zero cost, RU-reachable, no storage restrictions. Add a search API only if coverage is insufficient.

### 5. Enrichment pipeline design
**Flow:** normalize input → Wikidata-first (`wbsearchentities` + SPARQL for ru/en/zh labels + tea-related instance-of/P31 constraint) → if no confident match, LLM fallback (YandexGPT Lite, then GigaChat) → cross-check LLM output vs Wikidata/Wikipedia labels + transliteration validation → compute confidence → publish row flagged `unverified` with score → cache.

**LLM JSON schema (`response_format: json_schema`):**
```json
{
  "name": "tea_entity",
  "schema": {
    "type": "object",
    "properties": {
      "name_ru": {"type": "string", "description": "Russian TRANSLITERATION, e.g. Да Хун Пао — NOT a literal translation"},
      "name_en": {"type": "string", "description": "English/romanized common name, e.g. Da Hong Pao"},
      "name_zh": {"type": "string", "description": "Chinese characters, e.g. 大红袍"},
      "pinyin": {"type": "string", "description": "Tone-marked Hanyu Pinyin, e.g. Dà Hóng Páo"},
      "type": {"type": "string", "enum": ["green","black","oolong","white","yellow","dark","puer","herbal","other"]},
      "origin": {"type": "string", "description": "Region/province of origin"},
      "oxidation": {"type": "string", "description": "e.g. unoxidized, partially oxidized, fully oxidized"},
      "confidence_self": {"type": "number", "description": "model self-rated 0..1"}
    },
    "required": ["name_ru","name_en","name_zh","pinyin","type","confidence_self"]
  }
}
```
System prompt must explicitly instruct: transliterate Chinese tea names into Russian phonetically (大红袍 → "Да Хун Пао"), never translate literally ("Большой красный халат" is wrong); emit tone-marked pinyin; return null rather than guess.

**Confidence scoring (0..1), weighted agreement:**
- Wikidata exact QID match with zh label = strong anchor (+0.5)
- ru/en/zh label agreement between Wikidata and LLM (+0.15 each)
- **Transliteration validation:** regenerate a Russian transliteration from the pinyin via a deterministic rule table; if it matches the LLM's `name_ru` within edit-distance tolerance (+0.15). *This is what catches the "Большой красный халат" error.*
- Pinyin matches a romanization derived from the zh label (+0.1)
- Penalize if `name_ru` looks like a dictionary translation (contains common Russian words like "красный", "халат") rather than a transliteration
- Publish as `unverified` whenever confidence < threshold (e.g. 0.8); else `verified`

**Dedup / identity:** canonical key = pinyin slug (lowercased, toneless, hyphenated: `da-hong-pao`) + NFC-normalized zh. Store the Wikidata QID as hard identity when matched. Unicode-normalize, trim, collapse whitespace on all names.

### 6. Abuse & safety for the public unauthenticated resolve endpoint
- **Input validation:** cap tea-name length (≤64 chars), reject control chars, allow only letters/CJK/spaces/hyphens; reject inputs containing URLs/code/injection markers; normalize before processing.
- **Prompt-injection mitigation:** never concatenate user text into the system prompt; pass it strictly as a user message with a fixed, hardened system prompt; instruct the model to treat input only as a tea name and ignore embedded instructions; strictly validate output against the JSON schema and reject off-schema responses.
- **Cost-control guardrails:** Wikidata-first means most lookups never hit the LLM. Cache aggressively (resolved rows + negative cache for unknowns). Per-IP rate limit (e.g. 5/min, 50/day), global daily LLM cap well under grant burn rate, debounce identical/near-identical queries, queue/dedupe concurrent identical requests. Cap `maxTokens`; use YandexGPT Lite for the first pass. Disable Yandex data logging on these calls.

## Recommendations
**Staged plan:**
1. **Now:** Stand up Wikidata-first resolution (SPARQL + `wbsearchentities` + Wikipedia ru/zh sitelinks) — free, RU-reachable, ToS-clean, resolves most well-known teas with no LLM cost.
2. **Primary LLM:** Wire YandexGPT Lite via the OpenAI-compatible endpoint with `json_schema`, IAM/API-key auth, logging disabled, `maxTokens` capped. Validate on the ₽4,000 grant; budget pay-as-you-go (Lite ≈ $0.0017/1k tokens) thereafter.
3. **Free fallback:** GigaChat (1M free tokens/year, RU-reachable) when YandexGPT is down or to cross-check Russian transliterations. Requires RU phone/SberID for the key.
4. **Verification + confidence:** implement the weighted-agreement score with transliteration validation as the gate for the `unverified` flag.
5. **Guardrails:** ship per-IP + global rate limits, caching, input caps, and schema-validated output before public exposure.

**Thresholds that change the plan:** If confident Wikidata matches cover < ~60% of the catalog, add Tavily (1,000 free credits/mo) for a web cross-check — but verify RU reachability first. If LLM spend approaches the grant burn rate, drop to Lite-only and tighten daily caps. If you ever need to resell raw API access (not your product), Yandex cl. 3.15 forbids it — renegotiate with Yandex.

## Dated Reference Links
- Yandex AI Studio "Common instance models" — updated **Feb 3 2026** — yandex.cloud/en/docs/ai-studio/concepts/generation/models
- Yandex AI Studio pricing policy — updated **Dec 24 2025** — yandex.cloud/en/docs/ai-studio/pricing
- Yandex Foundation Models structured output (json_schema) — accessed **Jun 14 2026** — aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/structured-output.html
- Yandex Cloud Trial period / Grant terms (₽4,000 / $50, 60-day) — accessed **Jun 14 2026** — cloud.yandex.com/en/docs/free-trial/concepts/usage-grant
- Yandex AI Studio Terms of Use (cl. 4.1 / 3.15 / 3.3, effective **Apr 28 2026**) — yandex.ru/legal/cloud_terms_yandex_ai_studio/ru/ (Russian only)
- GigaChat individual tariffs (1M free tokens/yr) — accessed **Jun 14 2026** — developers.sber.ru/docs/ru/gigachat/tariffs/individual-tariffs
- Google "Available regions for Gemini API" (Russia absent) — accessed **Jun 14 2026** — ai.google.dev/gemini-api/docs/available-regions
- Groq rate limits — accessed **Jun 2026** — console.groq.com/docs/rate-limits
- DeepSeek pricing/free tokens — accessed **Jun 14 2026** — api-docs.deepseek.com/quick_start/pricing
- Tavily credits & pricing — accessed **Jun 14 2026** — docs.tavily.com/documentation/api-credits
- Wikidata SPARQL service / query limits — accessed **Jun 14 2026** — wikidata.org/wiki/Wikidata:SPARQL_query_service

## Caveats / Could-not-verify
- **Yandex AI Studio Terms English version:** not publicly served; all clause quotes are Russian original + unofficial translation (subagent-retrieved). The output-storage permission rests on clauses 4.1 / 1.2.2 / 3.15 (effective Apr 28 2026).
- **Yandex free grant amount/expiry:** ₽4,000 / $50, 60-day expiry per official docs, but some cited pages are on the legacy cloud.yandex.com domain — confirm current figures in the live billing console.
- **RU reachability of Groq, Mistral, OpenRouter, DeepSeek, Tavily, Brave:** none publish an official statement confirming or denying Russian-IP access; reachability is **UNVERIFIED** and may require non-RU signup. Only Gemini is confirmed (officially unavailable in Russia). **GigaChat and Yandex are confirmed RU-reachable.**
- **Yandex Translate free allowance:** no perpetual free tier on Yandex Cloud beyond the shared grant; "1M chars/day free" figures online refer to the legacy public API, not the Cloud API.
- **DeepSeek/GigaChat zh tea-naming quality:** asserted as "adequate when cross-checked," based on general multilingual benchmarks, not a tea-specific evaluation — hence the mandatory transliteration-validation gate.
- **Foundation Models English "limits" doc** (yandex.cloud/en/docs/foundation-models/concepts/limits) appears stale (still labeled "Preview," older token figures) versus the Feb 3 2026 AI Studio models page showing 32,768 context — trust the newer AI Studio docs.