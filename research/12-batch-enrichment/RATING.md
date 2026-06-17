# Rating - 12-batch-enrichment

Prompt: ./prompt.md   ·   Date judged: 2026-06-17

Scale 1-5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many -> lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output - the numeric Score is only a tiebreaker. See ../README.md -> *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    2     |    5    |  4.40 |  1   |
| gemini   |    3     |   4   |       4       |    4     |    5    |  3.00 |  2   |
| deepseek |    3     |   3   |       3       |    3     |    4    |  2.80 |  3   |

<!-- Optional Score = 0.35*Accuracy + 0.20*Depth + 0.25*Actionability + 0.10*Clarity - 0.10*Halluc. -->

**Winner:** opus - the only answer that treats uncertain claims as uncertain: it flags
`aliceai-llm-flash` as an unverified slug, marks strict `json_schema`-on-async as unconfirmed (falls
back to `json_object` + schema-in-prompt + server-side validation), cites the 3-day result TTL to the
specific Quotas page, and gets the batch verdict right with concrete numbers (200k-token-per-run
minimum; Alice/YandexGPT absent from the batch model list). gemini and deepseek both assert convenient
but unproven facts as confirmed — and both **reverse the locked model choice** (recommend swapping to
YandexGPT Lite/Pro for async), which opus correctly avoids.

**Reuse:**
- **Async REST shape (unanimous, high confidence):** submit `POST .../foundationModels/v1/completionAsync`
  (native body: `modelUri`, `completionOptions.{stream,temperature,maxTokens}`, `messages[].{role,text}`)
  → poll generic `GET https://operation.api.cloud.yandex.net/operations/{id}`; terminal = `done:true`
  with `response.alternatives[0].message.text` + `usage`, or `error`.
- **async is native-only** (OpenAI-compat `/v1/chat/completions` is sync-only); **async = 50% off sync**
  per token; rate limits 10 submit/s, 50 fetch/s, 5,000 submit/hr.
- **Restart-safe pattern:** submit→store `operationId`→release thread; poll on a `@Scheduled` job with
  capped backoff; count submissions (not polls) against the global daily ceiling; re-attach by stored id
  after restart within the **3-day TTL** (re-submit, don't re-poll, on stall).
- **Batch verdict:** do NOT build batch now — production models aren't in the batch list, 200k-token
  per-run minimum, uneconomical for 1-50 short teas; async fan-out is correct.
- **Model routing:** keep `aliceai-llm-flash` (or confirmed `aliceai-llm`) on async for the cheap tier;
  **keep Qwen3-235B on the SYNC OpenAI-compat path** (no native/async route) — do not swap models.

**Discard:**
- gemini/deepseek's "swap to YandexGPT Lite/Pro 5.1 for async" — reverses the locked model choice (#65).
- gemini's strict-`jsonSchema`-on-async "guaranteed" claim and `completionBatch` REST endpoint (likely
  hallucinated); deepseek's `aliceai-llm-flash` async "✅ confirmed" + exact price multipliers and
  "8,000-token-per-request" community figure (contradicts the documented 32k/262k context windows).
- All specific per-1k prices and the trial-grant coverage claims as fact — verify on the live console
  price list / Quotas page (the 50%-off ratio and the 3-day TTL are the only figures worth trusting,
  and still verify TTL upstream).

> LEADERBOARD: +1 Wins opus; +1 Runs judged for opus, gemini, deepseek.
