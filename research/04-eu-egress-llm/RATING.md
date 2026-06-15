# Rating — 04-eu-egress-llm

Prompt: ./prompt.md   ·   Date judged: 2026-06-15

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model           | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|-----------------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus            |    5     |   5   |       5       |    1     |    4    | 4.30  |  1   |
| gemini          |    5     |   4   |       5       |    2     |    5    | 4.10  |  2   |
| alice           |    3     |   4   |       3       |    3     |    4    | 2.70  |  3   |
| deepseek-flash  |    3     |   2   |       3       |    3     |    4    | 2.30  |  4   |
| gpt             |    2     |   2   |       2       |    4     |    4    | 1.60  |  5   |

<!-- Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->
<!-- New responders this run: alice (Yandex Alice LLM), deepseek-flash (DeepSeek V4 Flash). -->

**Winner:** opus — the most rigorous and decisive. It quotes both binding Gemini clauses
verbatim ("only Paid Services when making API Clients available to users in the EEA…"
and "Paid data terms apply to all services for EEA accounts"), cleanly separates output
*ownership* (fine) from the EEA *billing* trigger (the blocker), gives full DeepSeek/
Groq/Mistral pricing + a zh COMET benchmark, re-confirms YandexGPT's store-and-serve
terms, supplies Track-A/Track-B request configs, and even caught + ignored a
prompt-injection attempt embedded in a search result. gemini is a very close 2nd and
has the best *fit-the-constraint* recommendation (Groq+Qwen3-32B perpetual-free).

**Decisive finding (overturns decision #16):** **Gemini's free tier cannot be the
primary** for our EU-egress product — Gemini API Additional Terms require Paid Services
to serve EEA/CH/UK and apply paid-data terms to EEA accounts; an unbilled EU project
gets `400 — User location not supported without a billing account` (gemini). GPT alone
claimed the opposite and is wrong. So the "Gemini primary" plan is dead; see the new
decision in `context/decisions.md`.

**Reuse (the confirmed facts):**
- **Gemini:** free-tier-from-EU is non-compliant/blocked without billing; *output
  ownership + commercial re-serving is permitted* (only Search/Maps **Grounding** bans
  caching). Only viable if we enable paid billing (non-RU card) — out of "free" scope.
- **DeepSeek:** 5M free tokens is a **one-time, 30-day** bonus (no card; Google sign-in
  works), **not perpetual**; top-up afterward needs a non-RU card. Best zh (opus: 0.901
  COMET; both winners rank it #1 for Chinese). Output rights assigned to you; opt out of
  training. `deepseek-v4-flash`, OpenAI-compatible at `api.deepseek.com`.
- **Groq:** **perpetually free, no card, EU-reachable, no training on API data**, JSON
  output; strong zh via `qwen/qwen3-32b`. Limits ~30 RPM / ~1K–14.4K RPD (TPD often
  binds). Best free *perpetual* option.
- **Mistral:** free Experiment tier (phone, no card), EU/GDPR, but weak Chinese — not
  worth it for tea naming.
- **YandexGPT Lite:** re-confirmed store-and-serve OK (cl. 4.1/1.2.2/3.15, logging off),
  JSON output, always works direct (no VPN); good on famous teas, weak on obscure zh.
- **Transliteration:** every model risks the literal-translation failure (大红袍 →
  "Большой красный халат"); pin "transliterate the pinyin into Cyrillic, do not
  translate" + few-shot examples regardless of model (gemini's system prompt is reusable).

**Discard:**
- **gpt's central claim** that Gemini free tier is EU-usable without a card and can be
  primary — wrong; contradicted by the actual ToS and the other four. Also its
  hallucinated Vertex endpoint and `gemini-3.5-flash` id.
- **alice's "DeepSeek has no free tier"** — wrong (5M bonus is real); and its quality
  ranking rests on one Russian blog test (Gemini 27/30 etc.) — treat as weak evidence.
- **deepseek-flash's specifics:** wrong Yandex endpoint (`/llm/v1alpha/...`), legacy
  `deepseek-chat`, outdated `gemini-1.5-flash`, "DeepSeek via OpenRouter 5M" — its
  *strategy* (Yandex-primary + DeepSeek booster) is sound but verify all ids/endpoints.
- **All Gemini free-tier RPM/RPD/TPM numbers** — Google no longer publishes a fixed
  table; treat any figure as indicative.
- **EEA-applicability edge case:** our *users* are in RU, only egress is DE — alice/opus
  note the restriction keys on account/user geography, so it's arguably ambiguous; we
  treat it conservatively as blocking rather than build on the edge.
