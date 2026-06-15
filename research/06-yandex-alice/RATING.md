# Rating — 06-yandex-alice

Prompt: ./prompt.md   ·   Date judged: 2026-06-15

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| alice    |    5     |   5   |       5       |    1     |    5    | 4.40  |  1   |
| gemini   |    4     |   4   |       5       |    2     |    5    | 3.75  |  2   |
| deepseek |    2     |   3   |       4       |    4     |    4    | 2.30  |  3   |
| kimi     |    -     |   -   |       -       |    -     |    -    |   -   |  –   |

<!-- Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** alice — best-sourced (official `aistudio.yandex.ru` docs throughout),
correct model URIs (`gpt://<folder>/aliceai-llm` + `aliceai-llm-flash`), exact prices,
and the most honest caveats: it explicitly flags that **Flash's `json_schema` support is
NOT confirmed in the docs** (only YandexGPT/`aliceai-llm` are) and that **no
Chinese→Russian transliteration benchmark exists**. gemini is close and added the
single most valuable finding of the run (below). deepseek had the wrong URI slug
(`alice-ai` vs `aliceai-llm`), wrongly claimed Foundation Models has no JSON-schema
support, and didn't know basic YandexGPT facts.

**Big finding (gemini, corroborated by opus run-03's model list):** **Yandex Cloud AI
Studio now hosts DeepSeek V4 Flash and Qwen3-235B natively** in its model gallery,
callable via the same OpenAI-compatible SDK. → The Chinese-name booster could run
**natively on Yandex Cloud**, removing the **German-VPN dependency entirely** (decisions
#16/#17). Pending: confirm in the console that these models are available + their price.

**Reuse:** → feeds `decisions.md` (#17/#18) + plan §6:
- **Alice AI LLM** = a real, distinct Foundation Models model (NOT the consumer Alice
  assistant / Skills), `gpt://<folder>/aliceai-llm`, **64k context**, same endpoints
  (`ai.api.cloud.yandex.net/v1`), auth, logging-off header, and `json_schema` as
  YandexGPT. A cheaper `aliceai-llm-flash` exists.
- **Pricing (consensus):** Alice flagship ≈ 0.5 ₽/1k in, 1.2 ₽/1k out (≈6× Lite on
  output); YandexGPT Lite ≈ 0.2/0.2; **Alice Flash ≈ 0.1/0.2** (cheapest input).
- **Recommendation (winner's):** keep **YandexGPT Lite as the documented primary**
  (proven `json_schema`, mature, cheap); **benchmark `aliceai-llm-flash`** (cheaper
  input, 64k) and `aliceai-llm` (hard cases) during Phase 2 and promote only if they
  test better. Don't lock a switch on unverified quality.
- Confirms our Phase-5 stance: a booster is still needed for rare Chinese names
  regardless of the Yandex primary.

**Discard / verify:**
- **deepseek's model URI `gpt://<folder>/alice-ai[-flash]`** — wrong slug; correct is
  `aliceai-llm[-flash]` (alice/gemini, sourced). 
- **deepseek's claim that Foundation Models lacks JSON-schema/`response_format`** — false
  (alice/gemini + opus run-03 confirm support). Don't act on it.
- **Flash `json_schema` support** — unconfirmed (alice's caveat); test before relying on
  strict structured output with Flash.
- **Transliteration quality ranking** of Alice vs Lite — inferential (no benchmark);
  decide by our own eval in Phase 2.
- **`aliceai-llm-flash` price / grant coverage** — confirm in the live console.
