# Research model leaderboard

Running tally across every rated run under `research/`. After you finish a run's
`RATING.md`, add **+1 Wins** for the winner and **+1 Runs judged** for each model
you scored. **Win-count is the signal** — it tells you which model to reach for
first for a given kind of question. Don't average per-run scores here (different
prompts aren't comparable); these are counts only. See `README.md` → *Leaderboard*.

| Model  | Wins | Runs judged | Notable strengths |
|--------|:----:|:-----------:|-------------------|
| opus   | 11   |     12      | Legal/ToS/license precision (incl. Gemini Grounded-Results store-ban), exact API+pricing specifics, Yandex-API dialect quirks, honest caveats, flags bad QIDs & version conflicts; resolves provenance with CI-verifiable SHA evidence (run 13); precise self-host RAM floors + OFAC/152-FZ (run 15) |
| alice  |  2   |      8      | Best on the Yandex-native topics (Terraform, Alice LLM, Gemini-EEA-paid-only clause): RU-specific gotchas, official docs, honest "unverified" caveats; strong + honest on OCR (run 13) but skips the asked-for pinned SHAs; honest-but-incomplete + a hallucinated dep on run 15 |
| gemini |  0   |     14      | Sharp framing + concrete engineering; surfaced native-Yandex DeepSeek/Qwen3 hosting and Wikidata QIDs; occasionally over-confident on unverified specifics, misses ToS/privacy traps (run 10), fabricates SHAs/CER + ships non-running snippets (run 13), fabricated a GMS-free "reflection probe" + stale pins (run 15) |
| gpt    |  1   |      8      | **Won run 15** — best-fit telemetry pick for the constrained VM (ACRA + first-party endpoint, not a heavy dashboard) + the standout out-of-Room count-sentinel data-loss detector, strict allowlist, current pins + correct licenses. Earlier runs concise-but-shallow with factual errors (GigaChat free tier; Gemini-EEA backwards; booby-trapped grounding path in 08) |
| deepseek | 0 |     10      | Useful ideas (COI image, app-fetches-Lockbox, on-device PaddleOCR for ru OCR) but recurring stale/uncertain specifics (model ids, library pins — run 13: legacy rapidocr; run 15: wrong Sentry license + Celery-vs-rq + stale pins) |
| deepagent | 0 |     1      | Weakest on run 13: fabricated/Chinese model names passed off as Cyrillic, CUDA config in a CPU-only answer, sidecar exposes the backend's path |
| kimi   |  0   |      0      |                   |
| grok   |  0   |      0      |                   |
| fable  |  0   |      0      |                   |

<!-- Log (newest first): -->
<!-- 15-crash-telemetry → winner: gpt (2026-06-18)  models judged: gpt, opus, gemini, deepseek, alice -->
<!-- 13-ocr-sidecar-accuracy → winner: opus (2026-06-18)  models judged: opus, alice, gemini, deepseek, deepagent -->
<!-- 12-batch-enrichment → winner: opus (2026-06-17)  models judged: opus, gemini, deepseek -->
<!-- 11-flavor-backfill → winner: opus (2026-06-17)  models judged: opus, gemini, deepseek -->
<!-- 10-photo-ocr-grounding → winner: opus (2026-06-17)  models judged: opus, gemini, deepseek -->
<!-- 09-typo-search     → winner: opus (2026-06-17)  models judged: opus, gemini, gpt, alice -->
<!-- 08-ai-web-search   → winner: opus (2026-06-15)  models judged: opus, alice, gemini, gpt, deepseek -->
<!-- 07-flavor-prompt-tuning → winner: opus (2026-06-15)  models judged: opus, gemini, deepseek, alice, gpt -->
<!-- 06-yandex-alice    → winner: alice (2026-06-15) -->
<!-- 05-yandex-terraform → winner: alice (2026-06-15) -->
<!-- 04-eu-egress-llm   → winner: opus (2026-06-15) -->
<!-- 03-ai-enrichment   → winner: opus (opus-2 re-run) (2026-06-15) -->
<!-- 02-maps-geo-android → winner: opus (2026-06-14) -->
<!-- 01-tea-databases   → winner: opus (2026-06-14) -->
<!-- Note: `deepseek` aggregates the run-04 `deepseek-flash` variant. -->

> Add a row when you first use a new model. Keep slugs identical to the answer-file
> names (`opus`, `gpt`, `gemini`, `kimi`, `grok`, `fable`, …) so counts aggregate.
