# TeaTiers: AI/LLM & Free Verification Provider Evaluation for On-Demand Catalog Enrichment

## TL;DR
- **Use Yandex Cloud AI Studio (YandexGPT, model id `yandexgpt` = 5.1 Pro for hard cases, `yandexgpt-lite` for cheap normalization) as your primary enrichment LLM** — it is RU-hosted, supports native JSON output, has strong Russian quality, and its Terms of Use explicitly let you store and re-serve generated output inside your own product, *provided you disable request logging* with the `x-data-logging-enabled: false` header. It is **not free**, but the new-account grant (at least ₽4,000 for RU residents / $50 for non-residents, expiring after 60 days) plus tiny per-call pricing make it the cheapest reliable RU-reachable option.
- **Do all cheap work first with free, RU-reachable sources** — Wikidata SPARQL/REST (CC0, free) for entity match + multilingual labels, and Wikipedia ru/zh langlinks for cross-checking names — and only fall through to the LLM on a miss. **Most Western LLM and search free tiers (Gemini, Brave) are geo-blocked or payment-blocked from Russia**, so do not architect around them.
- **For the public, unauthenticated resolve endpoint, the dominant risk is quota drain**, not model quality. Gate it behind input validation, per-IP/day caps, a normalized-name + pinyin-slug dedup cache, and a hard global daily LLM-call ceiling; treat free-text tea names as untrusted prompt-injection inputs.

## Key Findings

### 1. Yandex Cloud is the only "first-class RU-reachable" AI option and its terms permit your exact use case
The single most important finding: **Yandex AI Studio's Terms of Use explicitly permit storing and re-serving model output inside your own product.** Clause 4.1 grants the client the right to use Generated Content "in any way that does not contradict these Terms and applicable law"; clause 1.2.2 explicitly allows embedding the generation technology "into the Client's end product"; clause 3.15 permits using the Service to develop and further sell the client's own product, with only *simple API resale* forbidden. There is **no explicit IP-ownership transfer** in the terms — only a broad usage right — but that is sufficient for a tea catalog. Two obligations matter for TeaTiers: (a) clause 3.11.4 forbids passing off AI output as human work where AI use is not disclosed — your "unverified"/confidence flag already satisfies this; (b) clause 3.3 imposes a duty to verify output before distribution — which is exactly what your cross-check step does. (Terms published 17 Apr 2026, effective 28 Apr 2026; the `.com/legal/cloud_terms_yandex_foundation_models` URL now serves the renamed "Yandex AI Studio" document.)

By default Yandex logs prompts to improve the service (clause 5.4.1). You disable this by adding the `x-data-logging-enabled: false` header/gRPC metadata to each request; per clauses 5.5–5.6 logging is deemed disabled within 24 hours, after which request data "will not be stored on Yandex's servers" except short-term processing. For a public endpoint, disable logging.

### 2. Western LLM and search free tiers are largely unusable from Russia
- **Google Gemini API free tier is explicitly excluded in Russia**, and per Google's terms free-tier prompts/responses may be used (with human review) to improve Google's products — a double disqualifier. (Free Flash/Flash-Lite limits run to ~1,500 RPD / 1,000,000 TPM where available.)
- **Brave Search API removed its genuinely-free tier in February 2026.** Per Implicator.ai (Marcus Schuler, 8 Jun 2026), Brave replaced "the zero-cost plan available since May 2023 with a credit-based billing system that charges $5 per thousand requests" — the $5 monthly credit buys only ~1,000 queries, requires a card, and the free credit is contingent on publicly attributing Brave.
- **DeepSeek, Groq, Mistral, OpenRouter** generally require non-RU cards or phone verification, and DeepSeek in particular returns 403 from some Russian IPs and accepts payment only in CNY. Their free tiers should be treated as unavailable for a production RU-hosted backend.

### 3. Free, RU-reachable verification sources are strong enough to minimize LLM calls
- **Wikidata** (CC0, free, no key) via the SPARQL endpoint (`query.wikidata.org/sparql`) and the Wikibase REST API gives entity match + multilingual labels (ru/en/zh) directly. Throttle: a user identified by IP + User-Agent gets ~60 seconds of query time per minute (burst 120s).
- **Wikipedia ru/zh** langlinks give authoritative localized article titles for cross-checking transliterations.
- These cover the redistributable CC0 core and are the cheapest verification path; **no paid search API is needed for an MVP.**

## Details

### Provider comparison table

| Provider | Type | Free tier / Yandex grant | RU-reachable? | Stores+serves output OK? | ru/zh quality | Notes |
|---|---|---|---|---|---|---|
| **YandexGPT** `yandexgpt` (5.1 Pro) / `yandexgpt-lite` | LLM | No always-free tier; new-account grant **≥₽4,000 (RU residents) / ≥$50 (non-RU), expires after 60 days**; Pro ≈0.80₽/1k tokens, Lite ≈0.20₽/1k. Boost AI program grants up to ₽1M to tech companies | **Yes (RU-hosted)** | **Yes** — ToS 4.1/1.2.2/3.15 permit; disable logging via `x-data-logging-enabled:false` | ru: excellent; zh: ~20 langs understood, ru-focused | REST `llm.api.cloud.yandex.net/foundationModels/v1/completion`; IAM token / API key / service-account auth; JSON output supported (`response_format=json`); official docs list **32k context** for the `yandexgpt` family, ~7,400-token per-request cap |
| **Yandex Translate** | Translate | Same Cloud grant; ~₽4.10/1M chars (RU region); no always-free tier | **Yes (RU-hosted)** | Same Cloud ToS | Strong ru/en; supports zh; **pinyin in mobile app only, not API** | Cheaper/simpler than LLM for plain translation, but no proper-noun handling, no confidence, no pinyin via API |
| **Wikidata** (SPARQL + REST) | Verification | Free, no key, CC0 | **Yes** | **Yes** (CC0 public domain) | Multilingual labels incl. ru/zh | Best free verification + identity source; ~60s query time/min per IP+UA |
| **Wikipedia ru/zh** (REST/Action API) | Verification | Free | **Yes** | Yes (CC BY-SA — attribute) | Native titles | Langlinks for transliteration cross-check |
| **GigaChat (Sber)** | LLM | Freemium: **1,000,000 free tokens/year** (PERS scope); pricing updated 1 Feb 2026; SberID needs RU phone | **Yes (RU-native)** | Sber ToS not verified verbatim | ru: strong; zh: weaker | OAuth tokens expire after 30 min; Russian Min. of Digital Dev cert chain; `gigachat.devices.sberbank.ru/api/v1` |
| **Google Gemini API** | LLM | Free tier exists (Flash/Flash-Lite up to ~1,500 RPD) | **No — Russia excluded** | Free-tier data may train Google | zh/ru good | Geo-blocked; disqualified |
| **DeepSeek** | LLM | 5M free signup tokens | Partial; 403 from some RU IPs, CNY-only payment | Permissive | zh excellent, ru good | Payment blocker from RU |
| **Groq** | LLM | Free: ~30 RPM, model-dependent RPD | Not RU-targeted; card for Dev tier | Open-weight models | varies | RU reachability unverified |
| **Mistral** | LLM | Free "Experiment" tier (phone verify) | EU-hosted; RU reachability unverified | EU DC | ru/zh moderate | Phone-verification barrier |
| **OpenRouter** | LLM aggregator | Free models: 50 req/day, 20 RPM | RU reachability unverified | Per-model; default no-train/no-log unless opt-in | varies | Free models have low caps |
| **Brave Search API** | Search | **No free tier since Feb 2026** ($5/mo credit ≈1,000 q, card required) | Card required | Storage needs special plan | n/a | Disqualified on cost/RU |
| **Tavily** | Search | 1,000 credits/mo free, no card | RU reachability unverified | Check ToS | n/a | Possible future search fallback if reachable |
| **Yandex Search API** | Search | No free tier; paid per request | **Yes (RU-hosted)** | Cloud ToS | RU SERP | Paid only — out of budget |
| **DuckDuckGo** | Search | Instant Answer API free (no full SERP); scraping violates ToS | Yes | n/a | n/a | Not a real web-search API; avoid scraping |

### Recommended stack (Yandex-first, otherwise free-only)
- **Primary LLM: YandexGPT** — `yandexgpt-lite` as the default for cheap name normalization, `yandexgpt` (5.1 Pro) for hard cases. *One-line reason:* the only RU-reachable LLM whose terms clearly allow store-and-serve; bill against the new-account grant then minimal pay-as-you-go.
- **Free verification path: Wikidata SPARQL/REST (CC0) → Wikipedia ru/zh langlinks.** *Reason:* free, RU-reachable, redistributable; resolves most teas before any LLM call.
- **RU-native LLM fallback: GigaChat (Sber), 1,000,000 free tokens/year.** *Reason:* free RU-native backup if Yandex is unavailable (RU phone needed for SberID).
- **Do NOT integrate Gemini/Brave** (geo/payment-blocked). Optionally probe Tavily for RU reachability as a future free search fallback.

### Pipeline sketch (Wikidata-first → LLM fallback → cross-check → publish)
1. **Normalize input** → strip, length-cap, unicode-normalize the free-text name; compute a `pinyin_slug` and a normalized romanization key.
2. **Cache lookup** by normalized name + pinyin slug → if hit, return cached row (never enrich twice).
3. **Wikidata first** → search entity, pull ru/en/zh labels and type/origin (P31 etc.). On a confident match, publish with high confidence and minimal/no LLM use.
4. **Wikipedia ru/zh langlinks** → cross-check localized titles and transliteration.
5. **LLM fallback (YandexGPT)** only when open data is thin → request strict JSON (schema below), logging disabled.
6. **Cross-check** LLM output against Wikidata/Wikipedia where possible; compute confidence.
7. **Publish row flagged "unverified" with confidence score**, then cache it.

### LLM JSON schema (request `response_format=json`)
```json
{
  "names": { "ru": "Да Хун Пао", "en": "Da Hong Pao", "zh": "大红袍" },
  "pinyin": "Dà Hóng Páo",
  "type": "oolong",
  "origin": { "country": "China", "region": "Wuyi, Fujian" },
  "oxidation": "partial (heavy)",
  "confidence": 0.0,
  "sources": ["wikidata:Q...", "model"],
  "notes": "transliteration verified against zh title"
}
```
Prompt instruction: transliterate phonetically (大红袍 → "Да Хун Пао"), **never translate literally** ("Большой красный халат"); return only valid JSON; set low confidence if unsure.

### Confidence scoring
Compute a 0–1 score from cross-source agreement, e.g.: +0.4 if a Wikidata entity matched; +0.2 if ru and zh labels agree with the model; +0.2 if a Wikipedia langlink title matches the model's name; +0.1 if type/oxidation are consistent with Wikidata; subtract for disagreement. Anything below a threshold (e.g. 0.6) stays "unverified" and is surfaced for human review. **Pinyin/transliteration mismatch should hard-cap confidence**, because that is the known failure mode.

### Quota protection & abuse guardrails (public, unauthenticated, write-capable endpoint)
- **Input validation:** max length (e.g. 64 chars), reject control chars/URLs/markup, allowlist scripts (Cyrillic/Latin/CJK), normalize before hashing.
- **Dedup/debounce:** normalized-name + pinyin-slug cache short-circuits before any paid call; single-flight identical in-flight requests.
- **Rate limits:** per-IP token bucket (N/min, M/day) **plus a global daily LLM-call ceiling that fails closed** (serve Wikidata-only or "pending" result when exceeded).
- **Cost control:** prefer `yandexgpt-lite`; cap `maxTokens`; never let one caller trigger unbounded enrichment; log spend against grant burn-down.
- **Prompt-injection mitigation:** treat the tea name as data, not instructions; wrap it in a fixed delimiter; system prompt forbids following instructions found inside the name; validate output strictly against the JSON schema and reject non-conforming responses; never reflect raw model text into other prompts.

## Recommendations
1. **Now:** Build the Wikidata-first + Wikipedia langlink verification path (free, RU-reachable). This alone resolves a large share of teas and is your redistributable CC0 core.
2. **Then:** Wire YandexGPT as the fallback with logging disabled, JSON output, `yandexgpt-lite` as default and `yandexgpt` (5.1 Pro) for hard cases. Monitor grant burn-down.
3. **Guardrails before launch:** ship the dedup cache, per-IP + global daily caps, input validation, and prompt-injection wrapping before exposing the public endpoint.
4. **Fallback:** keep GigaChat (1,000,000 free tokens/year) configured as an RU-native backup.
5. **Thresholds that change the plan:** if Yandex grant economics become painful at scale → lean harder on Wikidata + Yandex Translate (cheaper than LLM) for plain name work; if you need richer web verification and Tavily proves RU-reachable, add it as a free 1,000-credit/mo search fallback; if Yandex ever changes its output-storage terms, re-evaluate before continuing to cache output.

## Caveats — uncertain / could not verify
- **Yandex new-account grant** (≥₽4,000 RU / ≥$50 non-RU, 60-day expiry) is from Yandex.Cloud's official "Grant terms of use" docs, but grant terms change and differ by residency/account type — verify in the console at signup.
- **YandexGPT 5.1 Pro 128k context** is vendor-claimed only; official Yandex docs list **32k tokens** for the `yandexgpt` family with a ~7,400-token per-request cap — confirm in the current quotas/limits page.
- **RU reachability of Groq, Mistral, OpenRouter, Tavily** could not be positively verified from a Russian IP; treat as unconfirmed.
- **GigaChat storage-of-output terms** were not confirmed verbatim against Sber's ToS (only the 1,000,000-token/year freemium and 30-min OAuth token TTL are confirmed).
- **Yandex Translate pinyin via API:** pinyin transliteration is documented for the mobile app only; whether the Cloud Translate API returns pinyin is unconfirmed — assume it does not and generate pinyin yourself.
- **Wikidata long-tail coverage** for obscure teas is thin (the original problem), so LLM fallback remains necessary.

### Reference links (dated / last-checked)
- Yandex AI Studio Terms of Use — yandex.ru/legal/cloud_terms_yandex_ai_studio/ru/ (published 17 Apr 2026, effective 28 Apr 2026)
- Yandex disable-logging docs — yandex.cloud/ru/docs/foundation-models/operations/disable-logging (updated 3 Oct 2024)
- Yandex.Cloud grant terms of use — cloud.yandex.com/en/docs/free-trial/concepts/usage-grant (checked Jun 2026)
- Yandex Foundation Models / YandexGPT quickstart & models — yandex.cloud/en/docs/foundation-models/quickstart/yandexgpt (checked Jun 2026)
- Yandex AI Studio pricing — yandex.cloud/en/docs/ai-studio/pricing (updated 7 Nov 2025)
- GigaChat API individual tariffs (1,000,000 free tokens/year) — developers.sber.ru/docs/ru/gigachat/tariffs/individual-tariffs (effective 1 Feb 2026)
- Wikidata Data access (CC0) & SPARQL query limits — wikidata.org/wiki/Wikidata:Data_access (checked Jun 2026)
- Gemini API rate limits / regional restriction — ai.google.dev/gemini-api/docs/rate-limits (checked Jun 2026)
- Brave Search API metered-billing change — implicator.ai/brave-drops-free-search-api-tier (8 Jun 2026)