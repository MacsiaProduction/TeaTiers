# Research model leaderboard

Running tally across every rated run under `research/`. After you finish a run's
`RATING.md`, add **+1 Wins** for the winner and **+1 Runs judged** for each model
you scored. **Win-count is the signal** — it tells you which model to reach for
first for a given kind of question. Don't average per-run scores here (different
prompts aren't comparable); these are counts only. See `README.md` → *Leaderboard*.

| Model  | Wins | Runs judged | Notable strengths |
|--------|:----:|:-----------:|-------------------|
| opus   | 14   |     16      | Legal/ToS/license precision (incl. Gemini Grounded-Results store-ban), exact API+pricing specifics, Yandex-API dialect quirks, honest caveats, flags bad QIDs & version conflicts; resolves provenance with CI-verifiable SHA evidence (run 13); precise self-host RAM floors + OFAC/152-FZ (run 15); net-new RU-first lead nobody else found — Rospatent/eAmbrosia GI registers (run 16, close #2); **won run 17** — most security-correct autoupdate design (signer-cert pin via apkContentsSigners, offline-signed-manifest insight that decouples integrity from host/TLS); **won run 18** — best-engineered OCR confusable normalizer (per-token unambiguous-Latin guard + keep-best-of-both, margin-guarded threshold ladder), all load-bearing pins verified; **won run 19** — nailed the load-bearing negative finding (no server RU rec → upgrade the detector not the recognizer) + the Букет→Вукет fix (candidate-lattice + pymorphy3 + keep-raw), most honest about projections |
| alice  |  2   |     12      | Best on the Yandex-native topics (Terraform, Alice LLM, Gemini-EEA-paid-only clause): RU-specific gotchas, official docs, honest "unverified" caveats; strong + honest on OCR (run 13) but skips the asked-for pinned SHAs; honest-but-incomplete + a hallucinated dep on run 15; honest "no magic dataset" core but sloppy + an off-constraint OpenAI suggestion (run 16); strong autoupdate architecture but a load-bearing "silent install, no dialog" error + fabricated RU stats (run 17) |
| gemini |  0   |     18      | Sharp framing + concrete engineering; surfaced native-Yandex DeepSeek/Qwen3 hosting and Wikidata QIDs; occasionally over-confident on unverified specifics, misses ToS/privacy traps (run 10), fabricates SHAs/CER + ships non-running snippets (run 13), fabricated a GMS-free "reflection probe" + stale pins (run 15), invented "Chinese Standard Exception" license (run 16; best product insight: Beanconqueror reframe), cert-fingerprint + manifest-canonicalization bugs in run-17 security code |
| gpt    |  2   |     12      | **Won runs 15 & 16** — best-fit picks for the *actual* constraints: telemetry (ACRA + first-party endpoint, count-sentinel) and catalog-breadth (reframe-as-reference-seed + no-PII seed-from-misses, every license correct, zero fabrications). Close #2 on run 17 — equally security-literate (pin the cert not the APK; X-Signature header to dodge JSON canonicalization). Earlier runs concise-but-shallow with factual errors (GigaChat free tier; Gemini-EEA backwards; booby-trapped grounding path in 08) |
| deepseek | 0 |     14      | Useful ideas (COI image, app-fetches-Lockbox, on-device PaddleOCR for ru OCR) but recurring stale/uncertain specifics + fabrications (model ids, library pins — run 13: legacy rapidocr; run 15: wrong Sentry license + Celery-vs-rq + stale pins; run 16: "BioTea" biomedical corpus misIDed as tea data; run 17: fsync-scope + rotated-cert-history signer-pin bugs) |
| deepagent | 0 |     1      | Weakest on run 13: fabricated/Chinese model names passed off as Cyrillic, CUDA config in a CPU-only answer, sidecar exposes the backend's path |
| qwen   |  0   |      1      | First judged (run 19): Tracks B/C reasonable, but FATALLY fabricated that PP-OCRv5_server_rec supports Cyrillic (verified false — would drop Russian) + invented ONNX filenames + placeholder pricing; last place |
| kimi   |  0   |      0      |                   |
| grok   |  0   |      0      |                   |
| fable  |  0   |      0      |                   |

<!-- Log (newest first): -->
<!-- 19-ocr-description-extraction → winner: opus (2026-06-20)  models judged: opus, gpt, deepseek, alice, gemini, qwen -->
<!-- 18-ocr-name-capture → winner: opus (2026-06-20)  models judged: opus, gpt, alice, deepseek, gemini -->
<!-- 17-app-autoupdate → winner: opus (2026-06-19)  models judged: opus, gpt, deepseek, gemini, alice -->
<!-- 16-catalog-breadth → winner: gpt (2026-06-19)  models judged: gpt, opus, gemini, deepseek, alice -->
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
