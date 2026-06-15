# Web-Grounded LLM Enrichment for TeaTiers: Viability Assessment (June 2026)

## TL;DR
- **Upgrade plan.md §6 — but NOT via any built-in "grounded LLM" tool.** Google Gemini's `googleSearch` grounding is free and EU-reachable, but its Terms of Service explicitly forbid storing "Grounded Results" beyond 2 years and forbid re-serving them to anyone except the user who submitted the prompt — a hard blocker for TeaTiers' store-and-reserve model. The only compliant, low-cost, EU-reachable, ru/zh-capable design is a **search-only API (whose ToS permits storing snippets) + your existing plain Gemini Flash LLM to synthesize and store the description you own.**
- **Recommended primary: Tavily Search API (search layer) → Gemini 2.5/3.x Flash *without* the googleSearch tool (synthesis layer).** At 30 calls/day this is $0 (Tavily free 1,000/mo + Gemini free tier). At 80 calls/day it is ~$19/mo (Tavily PAYG) with Gemini still free. You own and may store/re-serve the Gemini-generated text because the storage restriction applies *only* to Grounding-with-Google-Search output, not to plain generation.
- **No-VPN fallback: YandexGPT Lite plain (no search).** Yandex's own Search API ToS (clause 2.7.4) forbids storing/caching search results, so there is no compliant Russian-side grounding path; degrade gracefully to ungrounded YandexGPT.

## Key Findings
- Gemini grounding is the cheapest grounded option but is legally unusable for a stored, re-served catalog. Verified verbatim from Google's terms (effective March 23, 2026).
- No major built-in grounded LLM offers a free tier without a credit card that *also* permits storing+reserving the grounded snippets. OpenAI, Anthropic and Perplexity all permit storing/serving the generated output, but none has a permanent card-free free tier.
- Wikidata/Wikipedia cover all five test teas in en + zh (and mostly ru), so generic web grounding is only needed for the long tail — keeping enrichment volume low enough to stay near/within free tiers.
- DeepSeek, Qwen and Mistral ship **no** built-in web search; they are BYO-retrieval → not applicable as grounded providers.

## Comparison Table

| Provider | Free tier (EU, no card?) | Grounded-search support | Stores + serves output OK? | ru/zh tea-naming quality (Q4) | Monthly cost @ 80 calls/day | Notes |
|---|---|---|---|---|---|---|
| **Gemini `googleSearch` grounding** | ✅ Yes — free, no card, no expiry (Flash/Flash-Lite 1,500 RPD) | Built-in | ❌ **NO** — ToS prohibits store/re-serve beyond 2yr & to other users | 5/5 | $0 (within free grounding quota) | **Excluded:** storage ToS blocker |
| **Gemini Flash (plain) + Tavily** ⭐ | ✅ Yes (Gemini free; Tavily 1,000/mo free, no card) | BYO (Tavily) | ✅ Yes — plain Gemini output is yours; Tavily snippets storable | 5/5 | **~$19** (Tavily PAYG); $0 at 30/day | **Recommended primary** |
| **OpenAI `web_search` (Responses API)** | ❌ No card-free free tier | Built-in | ✅ Yes — Output assigned to you | 5/5 | ~$30–40 | Card required |
| **Anthropic `web_search`** | ❌ No API free tier | Built-in | ✅ Yes — output to customer | 5/5 | ~$45 (Haiku 4.5) | Card required; strong zh index |
| **Perplexity Sonar** | ❌ No permanent API free tier | Built-in (default) | ⚠️ Ambiguous (snippet redistribution) | 5/5 | ~$19 | Card required |
| **Serper + Gemini Flash** | ✅ 2,500 free searches; Gemini free | BYO (Serper) | ⚠️ Risky — raw Google SERP, caching grey | 5/5 | ~$2.40 | SerpAPI litigation overhang |
| **Brave Search + Gemini Flash** | ❌ Card required ($5 credit/mo) | BYO (Brave) | ⚠️ Uncertain (ZDR implies OK, no explicit grant) | 5/5 | ~$7–12 | Free tier killed Feb 2026 |
| **Yandex Search API + LLM** | n/a | BYO (Yandex) | ❌ **NO** — ToS 2.7.4 forbids storing/caching | — | — | Hard blocker |
| **Wikidata / Wikipedia** | ✅ Free, no card | n/a (structured lookup) | ✅ Wikidata CC0 fully storable; Wikipedia CC BY-SA (attribution) | covers all 5 famous teas | $0 | **Primary source, not fallback** |
| **DeepSeek / Qwen / Mistral** | varies | ❌ None (BYO only) | n/a | — | — | Not applicable as grounded providers |

## Details

### Q1 — Web-grounded LLM APIs (EU-reachable, free-tier-without-card)

**Google Gemini API — `googleSearch` tool ("Grounding with Google Search").**
- Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`; SDK config `tools=[types.Tool(google_search=types.GoogleSearch())]`. Older models used `google_search_retrieval`; all current models use `google_search`.
- Models supporting grounding: gemini-2.5-flash, gemini-2.5-flash-lite, gemini-3-flash, gemini-3.1-flash-lite, gemini-3.5-flash. (gemini-2.0-flash shut down June 1, 2026.)
- Free-tier grounding allowance: Per AIFreeAPI's 2026 Gemini guide, "Grounding with Google Search... The first 1,500 queries per day are free on paid tiers, after which pricing is $35 per 1,000 grounding queries" (**Gemini 2.5 models**). For **Gemini 3.x models**, CostGoat (Jun 2026) states "Google Search grounding: 5,000 free prompts/month across the Gemini 3.x family," with overage per FindSkill.ai (May 2026) of "beyond that, expect ~$14 per 1,000 grounded queries." Base free tier: per PE Collective's Gemini free-tier guide (2026), Gemini 3 Flash on the free tier "runs 10 requests per minute, 250,000 tokens per minute, and 1,500 requests per day"; Gemini 3.1 Flash-Lite gives 15 RPM at the same 1,500 RPD — **no credit card required**, no expiration (ai.google.dev rate-limits, last updated 2026-05-28).
- Grounding is testable free in AI Studio and available across Europe (since Dec 2024). EU-reachable: **yes**. Card-free free tier: **yes**. Date verified: June 15, 2026.
- **Critical caveat: storing/re-serving grounded output is prohibited (see Q3).**

**OpenAI — `web_search` built-in tool on the Responses API.**
- `tools=[{"type":"web_search"}]` on the Responses API. Works across GPT-5.x family.
- Pricing: per ModelCostWatch citing OpenAI's pricing page, "standard web search at $10 per 1K calls, preview reasoning web search at $10 per 1K calls, and preview non-reasoning web search at $25 per 1K calls." OpenAI pricing docs add that for gpt-4o-mini/gpt-4.1-mini "search content tokens are billed as a fixed block of 8,000 input tokens per call." Model tokens billed separately at the model's rate.
- Free tier: **none.** "There's no permanent free tier on the OpenAI API itself." A payment method is required. EU-reachable: yes. Card-free: **no**. Verified June 2026 (platform.openai.com/docs/pricing).

**Anthropic Claude — `web_search` server tool.**
- `tools=[{"type":"web_search_20260209","name":"web_search"}]` (or `web_search_20250305`); admin must enable web search in Console. Web search results count as input tokens.
- Pricing: **$10 per 1,000 searches** + token costs. Lowest model bracket: Haiku 4.5 at **$1/$5 per MTok**; Sonnet 4.6 $3/$15; Opus 4.8 $5/$25.
- Free tier: **none for the API** — "There is no free tier for direct API access." Card required. EU-reachable: yes. Verified June 2026 (platform.claude.com/docs pricing; docs.anthropic.com web-search-tool).

**Perplexity — Sonar / Sonar Pro (grounded by default).**
- Sonar $1/$1 per MTok; Sonar Pro $3/$15; plus a per-request search fee **$5–$14 per 1,000 requests** by search-context size. Standalone Search API: **$5 per 1,000 requests** (raw results, no token cost). Citations included.
- Free tier: **no permanent API free tier** — free plan users get zero API credits and must add a payment method; Pro subscribers ($20/mo) get $5/mo API credit (reportedly may be discontinued — verify). Card required. EU-reachable: yes. Verified June 2026 (docs.perplexity.ai pricing).

**You.com / Phind / others:** You.com Search API exists (~$0–$50/1k per third-party trackers) but I could not confirm card-free free-tier terms or storage rights on an official page; treat as unverified. Phind has no documented public grounded-LLM API. **Skipped** per evidence standards.

**DeepSeek, Qwen, Mistral:** **No built-in web search tool.** All are BYO-retrieval (confirmed: "Mistral has no native search tool of its own… The same goes for DeepSeek, Qwen, Kimi, Llama"). → **Not applicable** as grounded providers.

### Q2 — Search-only APIs (pair with your own LLM)

**Brave Search API.** Free tier eliminated 2026-02-12. Now **$5 prepaid metered credits/month (~1,000 queries)** then **$5 per 1,000 requests** (Search/LLM-context endpoint); Answers $4/1k + $5/M tokens. **Credit card required**, attribution required, no spending cap. Brave markets a "Data for AI" plan for RAG/LLM grounding and advertises Zero Data Retention, implying storing the returned context for your app is intended-use (but the contractual storage grant is not as explicit as I'd like — flagged uncertain). EU-reachable: yes.

**Tavily.** Per Tavily Docs (Credits & Pricing): "You get 1,000 free API Credits every month. No credit card required... Pay-as-you-go: $0.008 per credit." Basic Search = 1 credit, Advanced = 2 credits; credits do not roll over month-to-month. Paid plans: Researcher $30/mo; Startup $100/mo. Purpose-built to return LLM-ready extracted content; SOC-2, zero-data-retention. ToS (tavily.com/terms, AUP): output is "AS IS," derived from publicly available data, customer is "solely responsible for… using the Output"; **no clause prohibiting storage or re-serving of returned content** — the AUP restricts only unlawful/infringing uses and requires downstream users be bound by equally restrictive terms. Storing snippets/URLs and re-serving as part of a tea description appears **permitted but at the customer's IP risk** (you must ensure the underlying content isn't infringing). US company, EU-reachable. **Best fit on storage terms + card-free free tier.**

**Serper.** **2,500 free searches**, then **$1 per 1,000 (Starter)** down to **$0.30/1k (Ultimate)**; credits valid 6 months. Returns raw Google SERP (titles/snippets/links). Cheapest at scale. Risk: it is a Google-SERP scraper; Google v. SerpAPI was filed Dec 19, 2025, and storing/redistributing Google SERP snippets sits in legally grey territory (Google's own terms restrict SERP caching). EU-reachable. **Cheapest but storage rights are ambiguous/risky.**

**You.com Search / SearXNG.** SearXNG self-hosted = no ToS storage limits (you run it; results come from upstream engines whose terms still apply to the snippets). Viable as a zero-cost, fully-controlled meta-search but you inherit upstream engines' snippet-rights risk and operational burden. You.com Search API: unverified storage terms — skip.

**Yandex Search API (Yandex.XML / Search API).** Priced per request via Yandex Cloud (no free public grounding tier confirmed). **ToS clause 2.7.4 explicitly prohibits: "Reproduce, copy, store or cache Yandex search results acquired through the Service, unless otherwise permitted herein"** and 2.7.7 forbids reordering/replacing the results. **This is a hard blocker** — you may not store or re-serve Yandex snippets. → Not usable for TeaTiers' Russian-side grounding.

**Wikipedia / Wikidata.** Wikidata is CC0 (public domain) — fully storable and re-servable, no restrictions. Wikipedia text is CC BY-SA (attribution + share-alike required if you reproduce text). Coverage for the five test queries:
- "Да Хун Пао" / "大红袍" → **hit**: en `Da Hong Pao`, zh `大红袍`, ru article exists; Wikidata item exists.
- "Lapsang Souchong" → **hit**: en `Lapsang souchong`, zh `正山小种`, ru exists; Wikidata item exists.
- "Шу Пуэр" → **partial hit**: covered under `Pu'er tea` / `Fermented tea` (ripe/shou); zh `普洱茶`; ru `Пуэр` exists. Shu/ripe is a sub-section, not always its own item.
- "Bai Hao Yin Zhen" → **hit**: en `Baihao Yinzhen` (Silver Needle), zh `白毫银针`, ru exists; Wikidata item exists.
- (Tieguanyin) → **hit**: en `Tieguanyin`, zh `铁观音`, ru `Те Гуань Инь`; Wikidata item exists.
**Conclusion:** Wikidata + Wikipedia cover all five famous teas in en + zh and mostly ru. Generic web search is needed only for the long tail (obscure/blended/vendor-specific names), so grounding fires rarely.

### Q3 — Output-storage ToS (the decisive analysis)

**Google Gemini "Grounding with Google Search" — STORE+RESERVE PROHIBITED.** Verbatim from Gemini API Additional Terms of Service (effective March 23, 2026; page last updated 2026-04-28):
- Definition: *"'Grounded Results' mean responses that Google generates using the prompt… and results from Google's search engine."* The **entire generated response** is a Grounded Result when grounding is on — there is no carve-out separating the model's prose from the snippets.
- Display restriction: *"You will only… display the Grounded Results with the associated Search Suggestion(s) to the end user who submitted the prompt."*
- Storage restriction: *"You will not… copy, store, or implement any click tracking… of Grounded Results… except that: You may copy and store, for up to two (2) years, the text of the Grounded Result(s): (1)… to evaluate and optimize the display…; (2) in chat history of an end user… only for the purpose of allowing that end user to view their chat history; and (3) temporarily for… resubmitting…"*
- Anti-database clause: *"You will not… cache, frame, syndicate, resell, analyze, train on, or otherwise learn from Grounded Results… it is a violation of these terms to use Grounding with Google Search to extract or collect one or more of these components for another purpose (for example… using Links to build an index… or to create a database)."*
- **Verdict: Storing grounded tea descriptions in PostgreSQL and re-serving them commercially and indefinitely to *other* users is prohibited on three independent grounds** (display-to-originator-only; 2-year/narrow-purpose storage cap; explicit anti-cache/anti-database/anti-syndicate clause). Note: Google's general "Use of Generated Content" clause (Google won't claim ownership of generated content) still applies to **plain, ungrounded** Gemini output — which is why the recommended design uses Gemini *without* the grounding tool.

**OpenAI — STORE+RESERVE OK.** Terms of Use §Content: *"As between you and OpenAI… you (a) retain your ownership rights in Input and (b) own the Output. We hereby assign to you all our right, title, and interest, if any, in and to Output."* Help Center: *"OpenAI will not claim copyright over content generated by the API."* You may use Output commercially (sale/publication). Caveat: web_search retrieved content is "Third Party Output" subject to its own terms, and purely AI-generated text may not be copyrightable (others may get similar output). Net: storing/serving your generated descriptions is permitted.

**Anthropic — STORE+RESERVE OK (output ownership to customer).** Standard commercial terms assign output to the customer; web_search results are billed as input tokens with automatic citations. No clause forbids storing the generated answer. (Anthropic's web index has strong Chinese coverage.) Storing permitted; card required to access.

**Perplexity Sonar — AMBIGUOUS, lean caution.** Sonar returns answers + citations; Perplexity's API terms permit use of outputs but the developer ToS around caching/redistributing retrieved snippets is not as clearly permissive as OpenAI's output-assignment language. Flagged **ambiguous** — verify before relying on store-and-reserve.

**Brave / Tavily / Serper.** Tavily: no storage prohibition found (permissive, customer bears IP risk). Brave: "Zero Data Retention" on Brave's side + "Data for AI" positioning implies you may store the context, but the explicit grant is not spelled out — **flagged uncertain**. Serper: raw Google SERP — storage/redistribution legally grey (Google SERP caching restrictions; SerpAPI litigation). **Yandex: explicitly prohibited (2.7.4).**

### Q4 — Tea-naming quality (documentation-based, NOT a live benchmark)

⚠️ This is a documentation-based assessment of multilingual capability and known model quality, not live API execution. Canonical "correct" answers are anchored to Wikipedia/Wikidata.

**Canonical records (ground truth):**
1/2/3. **Da Hong Pao** — ru *Да Хун Пао*, en *Da Hong Pao / Big Red Robe*, zh *大红袍*, pinyin *Dà Hóng Páo*; type **oolong** (Wuyi rock tea/yancha); origin **Wuyi Mountains, Fujian, China**.
4. **Lapsang souchong** — ru *Лапсан Сушонг / Чжэншань Сяочжун*, en *Lapsang souchong*, zh *正山小种*, pinyin *Zhèngshān xiǎozhǒng*; type **black** (smoked; blend variants e.g. Russian Caravan); origin **Tongmu, Wuyi Mountains, Fujian**.
5. **Shu Pu'er** — ru *Шу Пуэр*, en *Ripe/Shou Pu'er*, zh *熟普洱*, pinyin *shú pǔ'ěr*; type **dark/puer (post-fermented)**; origin **Yunnan, China**.
6. **Tieguanyin** — ru *Те Гуань Инь*, en *Tieguanyin / Iron Goddess*, zh *铁观音*, pinyin *Tiě Guānyīn*; type **oolong**; origin **Anxi County, Fujian**.
7. **Bai Hao Yin Zhen** — ru *Бай Хао Инь Чжэнь / Серебряные иглы*, en *Baihao Yinzhen / Silver Needle*, zh *白毫银针*, pinyin *Báiháo Yínzhēn*; type **white**; origin **Fuding/Zhenghe, Fujian**.
8. **"Сосновый Уголь" ("Pine Charcoal")** — **not a real canonical tea name**; correct behavior = refuse / mark uncertain / return low-confidence, not fabricate.

**Top-3 candidate scoring (0–5):**

| Capability | Tavily + Gemini Flash (plain) | OpenAI GPT web_search | Gemini googleSearch (reference only — ToS-blocked) |
|---|---|---|---|
| Name correctness ru/en/zh + pinyin | 5 — Gemini Flash multilingual is strong on CJK + Cyrillic; snippets reinforce | 5 | 5 |
| Type/origin accuracy | 5 (grounded on Wikidata/Wikipedia snippets) | 5 | 5 |
| Blurb quality (no hallucination) | 4–5 (grounded; depends on snippet quality) | 5 | 5 |
| Refusal-on-unknown (input 8) | 4 (needs explicit prompt instruction + low-confidence flag in schema) | 4 | 4 |

All three score highly on the famous teas because the canonical data is well-represented on the open web in all three languages. The differentiator is **legality, not quality**: Gemini grounding (right column) is excluded for store-and-reserve. Refusal-on-unknown depends on prompt engineering and a confidence field in the structured schema, not on grounding per se.

### Q5 — Real cost at 80 calls/day (2,400 calls/mo; ~4.8M input, ~2.4M output tokens; ~2,400 searches/mo)

- **Gemini 2.5/3.x Flash grounding (reference, ToS-blocked):** 2,400 grounded prompts/mo is **within free quota** (2.5 = 1,500/day free; 3.x = 5,000/mo free) and Flash tokens are free on the free tier → **$0/mo**. On paid: ~$1.44 input + ~$6.00 output (at $0.30/$2.50 per MTok) ≈ **$7.44/mo**, grounding still free under quota. *Excluded due to storage ToS.*
- **Tavily + Gemini Flash (plain) — RECOMMENDED:** Search: 2,400 × $0.008 = **$19.20/mo** PAYG (or $30/mo Researcher; free 1,000/mo covers ≤33 calls/day → **$0 at the 30/day low end**). LLM synthesis: Gemini Flash free tier (2,400 calls « 1,500 RPD) = **$0**. **Total: $0 at 30/day; ~$19/mo at 80/day.**
- **Serper + Gemini Flash:** Search 2,400 × $1/1k = **$2.40/mo** (first 2,500 free); LLM $0 → **~$2.40/mo** but storage-rights risk.
- **Brave + Gemini Flash:** Search ~$12/mo (2,400 q, less $5 credit ≈ $7) ; LLM $0 → **~$7–12/mo**; card required.
- **OpenAI web_search (self-contained):** search 2,400 × $10/1k = **$24/mo** + content tokens (8k/call block on mini ≈ 19.2M input tokens) + model tokens → **~$30–40/mo**; no free tier, card required.
- **Anthropic web_search (self-contained):** search $24/mo + Haiku 4.5 tokens (~$16.80) + search-result input tokens → **~$45/mo**; card required.
- **Perplexity Sonar:** ~$7.20 tokens + ~$12 request fee (low context) ≈ **~$19/mo**; no free tier, card required; storage ambiguous.

No provider here has a hard monthly minimum except the practical ~$5 Brave credit floor. Gemini free tier (1,500 RPD) fully covers 80/day for the LLM step.

### Q6 — Recommendation

**Verdict: UPGRADE plan.md §6 to "web-grounded fallback when Wikidata + user-pasted text miss" — implemented as a search-API + plain-LLM pipeline, NOT a built-in grounded-LLM tool.** One-line reason: built-in Gemini grounding is free + EU but legally cannot be stored/re-served; the compliant, low-cost, ru/zh-capable path is Tavily (storable snippets) → plain Gemini Flash (you own + may store the generated text).

## Recommendations (staged)

1. **Keep Wikidata + user-pasted text as the primary enrichment source** (CC0, fully storable, covers all famous teas in en/zh/ru). Grounding is a *fallback only* on miss → keeps volume low (likely within Tavily's free 1,000/mo at 30/day).
2. **Add the web-grounded fallback as: Tavily Search API → Gemini Flash (no googleSearch tool) → store the generated canonical record.** This is the only design that is free-ish, EU-reachable, ru/zh-strong, and ToS-clean for store+reserve.
3. **Do NOT enable Gemini's `googleSearch` / `google_search_retrieval` tool for any content you persist.** Use Gemini purely as a synthesizer over Tavily snippets so the output falls under Gemini's permissive "Use of Generated Content" terms, not the restrictive Grounding terms.
4. **No-VPN path: YandexGPT Lite plain, ungrounded.** Yandex Search API ToS (2.7.4) forbids storing snippets, so there is no compliant Russian-side grounding; degrade to ungrounded YandexGPT and rely on Wikidata.
5. **Benchmarks that would change this:** (a) If Tavily usage routinely exceeds ~1,000/mo, evaluate Serper (~$2.40/mo) but only after confirming SERP-snippet storage rights in writing; (b) if you ever need self-contained grounding with full output-ownership and can accept a card + ~$30/mo, OpenAI web_search becomes viable; (c) if Google ever adds a "storable grounding" tier, revisit.

### Recommended primary request config

**Search layer — Tavily:**
```
POST https://api.tavily.com/search
{ "api_key": "<key>", "query": "<tea name> tea origin type flavor",
  "search_depth": "basic", "max_results": 5, "include_answer": false,
  "include_raw_content": true }
```

**Synthesis layer — Gemini Flash (plain, NO grounding tool), structured output:**
```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent
config: { responseMimeType: "application/json", responseSchema: {…below…} }
// tools: NONE (do not pass google_search) — ensures output is plain generated content
```
Structured-output JSON schema for the canonical record:
```
{ "type":"object","properties":{
  "names":{"type":"object","properties":{
     "ru":{"type":"string"},"en":{"type":"string"},"zh":{"type":"string"},"pinyin":{"type":"string"}}},
  "type":{"type":"string","enum":["green","oolong","black","dark","puer","white","yellow","herbal","blended"]},
  "origin":{"type":"object","properties":{"region":{"type":"string"},"province":{"type":"string"},"county":{"type":"string"}}},
  "blurb":{"type":"string"},
  "flavor_profile":{"type":"object","properties":{ /* 11 dimensions: sweetness, bitterness, astringency, umami, floral, fruity, roasted, smoky, mineral, earthy, body */ }},
  "confidence":{"type":"number"},
  "sources":{"type":"array","items":{"type":"string"}}
}}
```
- Set `confidence` low and instruct the model to refuse/flag when inputs (e.g., "Сосновый Уголь") don't match a real tea.
- **Training/data opt-out:** Gemini free tier *uses* prompts to improve products; to opt out, enable billing (paid tier / Vertex AI removes the training clause). For a single-user personal app this may be acceptable, but note it explicitly.

**Fallback order:** (1) Wikidata/Wikipedia lookup → (2) user-pasted text → (3) Tavily + Gemini Flash synthesis (EU/VPN path) → (4) no-VPN: YandexGPT Lite plain, ungrounded.

## 5–8 Dated Reference Links
1. Google — Gemini API Additional Terms of Service (Grounding restrictions), effective 2026-03-23, page updated 2026-04-28: https://ai.google.dev/gemini-api/terms
2. Google — Grounding with Google Search docs (tool config, free-tier note), updated 2026-05-18/2026-06-12: https://ai.google.dev/gemini-api/docs/grounding/
3. Google — Gemini API pricing (grounding billed per query; retrieved context not charged as input), updated 2026-06-09: https://ai.google.dev/gemini-api/docs/pricing
4. Tavily — Credits & Pricing ("1,000 free API Credits every month, no credit card... $0.008 per credit"), accessed 2026-06-15: https://docs.tavily.com/documentation/api-credits and Terms: https://www.tavily.com/terms
5. Yandex.XML (Web Search API) Terms of Service — clause 2.7.4 prohibiting copy/store/cache of results, accessed 2026-06-15: https://yandex.com/legal/xml/en/
6. OpenAI — Terms of Use §Content (output ownership assigned to user), accessed 2026-06-15: https://openai.com/policies/row-terms-of-use/ ; pricing (web search $10/1k): https://platform.openai.com/docs/pricing/
7. Anthropic — Web search tool docs ($10/1,000 searches; web_search_20260209), accessed 2026-06-15: https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/web-search-tool
8. Implicator.ai — "Brave Kills Free Search API Tier" (free tier removed 2026-02-12, $5/1k), 2026-06-08: https://www.implicator.ai/brave-drops-free-search-api-tier-puts-all-developers-on-metered-billing/

## Caveats / Uncertain — could not fully verify
- **(a) EU free-tier-without-card terms:** Confirmed card-free for **Gemini** (free tier, no card, no expiration), **Tavily** ("1,000 free API Credits every month. No credit card required"), and **Serper** (2,500 free). **OpenAI, Anthropic, Perplexity, Brave all require a card** (no permanent card-free free tier). Perplexity Pro's $5/mo API credit "may have been discontinued" per one source — verify.
- **(b) Store+reserve rights:** **Gemini grounding = prohibited (verified verbatim).** **OpenAI/Anthropic = permitted (output assigned to you).** **Tavily = no prohibition found but you bear IP risk on underlying content** (not an explicit grant — verify for your jurisdiction). **Brave = "Zero Data Retention" + RAG positioning implies storage OK but no explicit contractual grant — uncertain.** **Perplexity API snippet-redistribution = ambiguous.** **Yandex = explicitly prohibited (2.7.4).**
- Gemini Flash exact paid token rates vary by source ($0.15/$0.60 vs $0.30/$2.50 for 2.5 Flash) — re-check ai.google.dev/gemini-api/docs/pricing before budgeting; immaterial to the recommendation since the LLM step stays on the free tier.
- Tea-naming quality (Q4) is documentation-based, not a live benchmark; validate with a small live eval set before shipping.
- **Tavily ownership change:** Per Bloomberg, "Nebius...will pay $275 million for Tavily" (deal announced Feb 10, 2026); Nebius's press release states the deal "is expected to close in the next few weeks" and that "Tavily will continue operating under its current brand, serving existing customers." Possible future pricing/terms changes → design a swap-out path to Serper/SearXNG from day one.