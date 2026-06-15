# Rating — 03-ai-enrichment

Prompt: ./prompt.md   ·   Date judged: 2026-06-15

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus-2   |    5     |   5   |       5       |    1     |    4    | 4.30  |  1   |
| opus     |    5     |   4   |       5       |    2     |    4    | 4.00  |  2   |
| gemini   |    4     |   4   |       5       |    2     |    5    | 3.75  |  3   |
| gpt      |    3     |   3   |       3       |    3     |    4    | 2.50  |  4   |
| kimi     |    -     |   -   |       -       |    -     |    -    |   -   |  –   |

<!-- Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. · kimi not run -->
<!-- opus-2 = a re-run of opus; both aggregate under `opus` on the leaderboard. -->

**Winner:** opus (the `opus-2` re-run) — all four converge on the same stack (YandexGPT
primary + Wikidata/Wikipedia free verification spine + GigaChat free fallback; Yandex
Translate for prose only, never names; Gemini geo-blocked; Brave free tier gone). The
opus-2 re-run is the most precise and best-sourced: exact model URIs
(`gpt://<folder>/yandexgpt-lite`), the OpenAI-compatible endpoint, per-1k-token pricing,
quotas, the verbatim ToS clauses with effective dates, and the cleanest "could not
verify" list. opus (run 1) is nearly as good; gemini adds the best concrete engineering
bits; gpt has a factual error.

**Decision impact — resolves the enrichment ToS trap.** YandexGPT **permits storing and
re-serving model output** (AI Studio Terms cl. 4.1 / 1.2.2 / 3.15), provided request
logging is disabled (`x-data-logging-enabled: false`). So enrich-on-miss → publish to
our DB is contractually fine, and cl. 3.3 (customer must verify output) is a direct
mandate for our `unverified` flag + confidence gate. Enrichment is GO on Yandex Cloud.

**Reuse:** → `context/decisions.md` #15 + `context/plan.md` §6:
- **Primary LLM: YandexGPT Lite** via the OpenAI-compatible endpoint with `json_schema`
  structured output, IAM/API-key auth, `maxTokens` capped, **data-logging disabled**;
  Pro only for hard cases. Funded by the ~₽4,000/$50 60-day grant, then pay-as-you-go
  (Lite ≈ $0.0017/1k tokens). No perpetual free LLM tier.
- **Free verification spine: Wikidata** (`wbsearchentities` + SPARQL, CC0) **→ Wikipedia
  ru/zh sitelinks.** RU-reachable, ToS-clean; resolves most known teas with zero LLM
  spend. This is the "Wikidata-first" in the pipeline.
- **Free LLM fallback: GigaChat (Sber)** — 1,000,000 free tokens/year, RU-reachable
  without VPN (needs RU phone/SberID). **But its Freemium is personal/non-commercial —
  if TeaTiers is ever monetized, this fallback needs a paid Sber contract** (gemini).
- **Yandex Translate: prose/descriptions only, NEVER tea names** (literal: 大红袍 →
  "Большой красный халат" ≠ "Да Хун Пао"); no pinyin via the Cloud API.
- **Confidence = programmatic weighted agreement**, not the model's self-rating:
  Wikidata/Wikipedia entity match (+0.5), pinyin↔Hanzi check via a local lib
  (`pinyin4j`, +0.25), and a **transliteration "litmus test"** that penalizes a literal
  Russian translation (−0.5). Below threshold → stays `unverified`.
- **Guardrails:** dedup via a UNIQUE pinyin-slug + NFC-zh key (insert-collision returns
  cached row); per-IP + **global daily LLM ceiling that fails closed** (serve
  Wikidata-only when exceeded); input length/charset caps; prompt-injection isolation
  (user text as data, hardened system prompt, strict schema validation). Bucket4j is a
  fine Spring-side limiter.

**Discard (verify before code depends on it):**
- **gpt's claim that GigaChat has no free tier** — wrong; opus/opus-2/gemini and Sber's
  tariffs doc all confirm 1,000,000 free tokens/year. Don't act on gpt here.
- **RU-reachability of Groq / Mistral / OpenRouter / DeepSeek / Tavily** — all four mark
  these UNVERIFIED; only Yandex + GigaChat are confirmed RU-reachable and only Gemini is
  confirmed *blocked*. Don't architect around the unverified ones. (gemini's confident
  "DeepSeek: unblocked" is stronger than the evidence.)
- **YandexGPT context window** — official docs say **32,768** for the `yandexgpt` family;
  treat any "128k Pro" figure (opus run 1 caveat) as vendor-claimed. Plenty for us.
- **Exact grant amount/expiry** (~₽4,000/$50, 60-day) and **per-token pricing** — vary by
  residency/date and some cite legacy `cloud.yandex.com`; confirm in the live console.
- **Yandex AI Studio English ToS** is not served (Russian only) — clause quotes are
  unofficial translations; re-read the RU terms before relying on them long-term.
- gpt's **Hugging Face / Qwant** suggestions and over-broad "Groq has no geo-restrictions"
  — unverified; skip.
