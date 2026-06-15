# Research model leaderboard

Running tally across every rated run under `research/`. After you finish a run's
`RATING.md`, add **+1 Wins** for the winner and **+1 Runs judged** for each model
you scored. **Win-count is the signal** — it tells you which model to reach for
first for a given kind of question. Don't average per-run scores here (different
prompts aren't comparable); these are counts only. See `README.md` → *Leaderboard*.

| Model  | Wins | Runs judged | Notable strengths |
|--------|:----:|:-----------:|-------------------|
| opus   |  4   |      4      | Legal/ToS/license precision, exact API+pricing specifics, honest caveats, flags bad QIDs & version conflicts |
| alice  |  2   |      3      | Best on the Yandex-native topics (Terraform, Alice LLM): RU-specific gotchas, official docs, honest "unverified" caveats |
| gemini |  0   |      6      | Sharp framing + concrete engineering; surfaced native-Yandex DeepSeek/Qwen3 hosting; occasionally over-confident on unverified specifics |
| gpt    |  0   |      4      | Concise but shallow, with recurring factual errors (GigaChat free tier; got the Gemini-EEA question backwards) |
| deepseek | 0 |      3      | Useful ideas (COI image, app-fetches-Lockbox) but recurring wrong specifics (provider/model ids, "no JSON-schema") |
| kimi   |  0   |      0      |                   |
| grok   |  0   |      0      |                   |
| fable  |  0   |      0      |                   |

<!-- Log (newest first): -->
<!-- 06-yandex-alice    → winner: alice (2026-06-15) -->
<!-- 05-yandex-terraform → winner: alice (2026-06-15) -->
<!-- 04-eu-egress-llm   → winner: opus (2026-06-15) -->
<!-- 03-ai-enrichment   → winner: opus (opus-2 re-run) (2026-06-15) -->
<!-- 02-maps-geo-android → winner: opus (2026-06-14) -->
<!-- 01-tea-databases   → winner: opus (2026-06-14) -->
<!-- Note: `deepseek` aggregates the run-04 `deepseek-flash` variant. -->

> Add a row when you first use a new model. Keep slugs identical to the answer-file
> names (`opus`, `gpt`, `gemini`, `kimi`, `grok`, `fable`, …) so counts aggregate.
