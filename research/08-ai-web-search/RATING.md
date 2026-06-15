# Rating — 08-ai-web-search

Prompt: ./prompt.md   ·   Date judged: 2026-06-15

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    1     |    5    | 4.40  |  1   |
| alice    |    4     |   4   |       4       |    2     |    4    | 3.40  |  2   |
| gemini   |    3     |   4   |       4       |    3     |    4    | 2.95  |  3   |
| deepseek |    2     |   3   |       3       |    4     |    4    | 1.95  |  4   |
| gpt      |    2     |   2   |       2       |    4     |    3    | 1.50  |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** opus — only one that catches THE decisive ToS trap with
verbatim citation: Google's "Grounding with Google Search" Additional
Terms (effective 2026-03-23) explicitly forbid storing/re-serving
Grounded Results in a database, display them to anyone except the
prompt-originator, or cache/syndicate/build an index from them. This
disqualifies the obvious "free + grounded" path that DeepSeek and GPT
both recommend. Opus also threads the needle: search-API (Tavily,
storage-clean) + plain Gemini Flash (no `googleSearch` tool, so the
output falls under permissive Generated Content terms and is yours to
store) is the only design that is simultaneously free-ish,
EU-reachable, ru/zh-capable, and ToS-clean.

**Reuse:**
- Opus's Q3 verbatim quote of Gemini "Grounded Results" Additional ToS
  (definition + display restriction + 2-year/narrow-purpose storage cap
  + anti-cache/anti-database/anti-syndicate clauses) → cite path
  `research/08-ai-web-search/opus.md` Q3 in `context/decisions.md`
  when we lock the §6 upgrade.
- Opus's recommended pipeline:
  1. Wikidata/Wikipedia (CC0 + CC-BY-SA) lookup as the **primary** spine
     — covers all 5 famous test teas in en + zh and mostly ru.
  2. User-pasted vendor text → grounded extraction (research/07 prompts).
  3. Tavily Search API (1,000 credits/mo free, no card, store+serve
     permitted with customer IP risk) → plain Gemini Flash
     (`responseMimeType: "application/json"` + `responseSchema`, NO
     `googleSearch` tool) for synthesis. Storage clean because we own
     plain Gemini output.
  4. No-VPN fallback: YandexGPT Lite plain (ungrounded), because
     Yandex Search API ToS 2.7.4 forbids store/cache of snippets.
- Opus's structured-output schema (names ru/en/zh/pinyin, type enum,
  origin region/province/county, blurb, flavor_profile, confidence,
  sources[]) — seed for the canonical record schema.
- Opus's cost matrix (Tavily ~$19/mo @ 80 calls/day PAYG, $0 @ 30/day;
  Gemini Flash stays free under 1,500 RPD) — pre-launch budget check.
- Alice's separate Gemini Additional ToS clause finding: "You may use
  only Paid Services when making API Clients available to users in the
  European Economic Area" — a *second* EEA-shaped Gemini blocker
  orthogonal to the Grounded Results clause; document both.
- Alice's empirical observation: Wikipedia coverage for Russian
  transliterations of Chinese teas is weaker (4/8) than en+zh (6/8),
  which justifies the web-grounded fallback over Wikipedia-only.
- Gemini's Q2 table with Wikidata Q-IDs per test tea (Q204481 Da Hong
  Pao, Q1154561 Lapsang, Q2068228 Shu Pu'er, Q1056554 Bai Hao Yin Zhen,
  Q1196160 Tieguanyin) → seed Wikidata QID lookup constants.
- The 8-query refusal-on-unknown design (Опus/Gemini/DeepSeek converge):
  explicit `confidence` field + system rule "if no Wikidata match and
  no vendor text → set confidence=unknown, don't fabricate names".
- Opus's caveat that Tavily was acquired by Nebius (Feb 2026) — design
  the search layer behind an interface from day one so we can swap to
  Serper / SearXNG / Exa if Tavily's terms shift.

**Discard:**
- **DeepSeek + GPT recommendation to use Gemini + `google_search` tool
  and store the grounded output as our own.** Explicitly forbidden by
  Gemini API Additional ToS — would put TeaTiers in immediate breach.
- Gemini's Q3 claim that "storing the synthesized summary of the tea is
  allowed" when grounding is on — also wrong (the definition of
  "Grounded Results" covers the whole generated response, not just the
  retrieved snippets); same ToS analysis error as DeepSeek and GPT,
  but Gemini's final recommendation doesn't depend on it.
- DeepSeek's "tested 8 inputs and got perfect refusal" Q4 evidence —
  unverifiable; no API call output shown; treat as documentation-only
  synthesis like Opus is honest about being.
- DeepSeek's reference URLs to `https://www.terms.law/...` — domain
  not verifiable; treat as fabricated.
- DeepSeek's self-attribution as "Claude Opus" at the bottom of the
  file — copy-paste artifact; ignore.
- DeepSeek's flavor-profile schema with 11 differently named
  dimensions (sweetness/bitterness/astringency/umami/smokiness/
  roastiness/floral/fruity/vegetal/spicy/earthy) — inconsistent with
  the canonical 11 from research/07 and `decisions.md` #23.
- DeepSeek's 0–10 flavor scale — research/07 (and `decisions.md`) lock
  0–5.
- Gemini's flavor profile dimension list (sweetness/astringency/body/
  floral/fruity/earthy/smoky/creamy/roasted/herbal/mineral) — also
  inconsistent with the canonical 11; "body", "creamy", "mineral",
  "herbal" are not in research/07.
- GPT's request body shape (`{"prompt": "...", "tools": ...}`) —
  Gemini API uses `{"contents": [{"parts": [{"text": ...}]}], "tools":
  ..., "generationConfig": ...}`; the bare `prompt` form is wrong.
- Conflicting Gemini-grounding free-tier numbers across the answers
  (500 RPD vs 1,500 RPD vs 5,000/mo, $14/1k vs $35/1k overage). Don't
  pin in code from any single answer — re-verify against
  ai.google.dev/gemini-api/docs/pricing before budgeting; immaterial
  to the recommendation since we use plain (ungrounded) Gemini anyway.
- Brave Search API as an option for *us* — needs a card (no permanent
  card-free tier since 2026-02), and the storage rights are
  contractually grey even with their RAG/ZDR marketing.
- Yandex Search API — terminal blocker (ToS 2.7.4).

> Then update ../LEADERBOARD.md: **+1 Wins** for the winner, **+1 Runs judged** for
> each model scored.
