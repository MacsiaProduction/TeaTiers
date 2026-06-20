# Rating — 19-ocr-description-extraction

Prompt: ./prompt.md   ·   Date judged: 2026-06-20

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    2     |    5    |  4.30 |  1   |
| gpt      |    5     |   5   |       5       |    2     |    4    |  4.20 |  2   |
| deepseek |    4     |   4   |       4       |    3     |    4    |  3.30 |  3   |
| alice    |    4     |   3   |       3       |    3     |    3    |  2.75 |  4   |
| gemini   |    3     |   3   |       2       |    4     |    3    |  2.05 |  5   |
| qwen     |    1     |   2   |       2       |    5     |    3    |  1.05 |  6   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**The whole run hinges on one verified negative finding** — and only the top three got it cleanly.
**There is NO server-tier Cyrillic/Russian recognizer**: `PP-OCRv5_server_rec` is CJK+EN+JP only; Russian
exists *only* as the mobile rec (`eslav_PP-OCRv5_mobile_rec`, 7.5 MB). So "upsize the VM → better RU model"
is impossible — the quality lever is the **detector + detection resolution + correction**, not the
recognizer. Five of six confirmed this; **qwen fabricated the opposite** (that `server_rec` supports
Cyrillic), which would have *dropped Russian recognition entirely*. The unanimous design: keep eslav mobile
rec, swap in **`PP-OCRv5_server_det` (84 MB)**, raise `det_limit_side_len` 960→**1280** (`limit_type=min`),
add resolution-aware **keep-best** preprocessing, and replace the blind confusable normalizer with a
**dictionary-gated** corrector (`pymorphy3` real-word oracle). RAM is a non-issue on the confirmed pelican
node (4 vCPU / **32 GB** AMD) — so **every model's VM-sizing + ₽ section is moot** (all assumed an 8–16 GB
Intel box and gated detection resolution on a RAM ceiling that doesn't apply).

**Winner: opus** — leads with and rigorously substantiates the load-bearing negative finding, names the
exact upgrade (server *det* + det-res, keep eslav *rec*), and nails the `Букет→Вукет` fix: a candidate
**lattice** that generates BOTH `B→В` and `B→Б`, accepts the `pymorphy3`-validated real Russian word with
highest frequency, and **keeps the raw token when nothing validates** (never invents). Most honest about
its own limits (labels every CER a projection; flags unverified sizes). Fact-checks confirmed nearly all
its pins.

**gpt (co-winner, #2)** — same correct design with the best *engineering* rigor: build-time `sha256sum` +
`/health/ocr` instead of trusting quoted hashes, the exact `pymorphy3-dicts-ru==2.4.417150.4580142`, the
conditional-1536-rerun triggers (low cyrillic count / low rec score / low dict-hit-ratio), a typed
`/teas/ocr` response contract, and a formal A-vs-B benchmark protocol. Longest + slightly less crisp.

**Reuse** — into the build:
- **opus:** detector-not-recognizer framing; the exact stack (keep `eslav_PP-OCRv5_mobile_rec` 7.5 MB + add
  `PP-OCRv5_server_det` 84 MB + `det_limit_side_len=1280` `limit_type=min`); resolution-aware **keep-best**
  preprocessing tied to the #114 regression; the **candidate-lattice + keep-raw** corrector; pins
  `opencv-python-headless==4.10.x` / `Pillow>=10.4` / `numpy<2.3`; sha256-at-build + "CER = projection" honesty.
- **gpt:** build-time `sha256sum`; `pymorphy3-dicts-ru==2.4.417150.4580142`; conditional-1536 triggers; the
  typed `/teas/ocr` contract (rawText, correctedText, paragraphs+confidence, typed tokens, corrections,
  quality band); the n=10/30 A-vs-B protocol; keep blind confusable-normalize ONLY in the name-match path.
- **deepseek:** `pymorphy3` + `hunspell ru_RU` "accept if EITHER validates"; honest residual accounting
  (~Гунфу→Фунфу, pinyin/vendor tokens unfixable → leave to Track C's LLM); the off-by-default,
  per-image-consent opt-in cloud toggle as the #96-respecting escape hatch.
- **alice:** the proper-noun / tea-grade **whitelist** (don't "correct" Пуэр / Те Гуань Инь); token-skip for
  URLs/units/Latin brands; fine-tuning eslav rec on labeled RU packaging as a future no-egress lever.
- **gemini:** the runnable `pymorphy3` normalizer logic + the genuine-Latin guard regex — **code only**, not
  its EasyOCR config or numbers.

**Discard** — keep OUT of the build:
- **FATAL (qwen):** "`PP-OCRv5_server_rec` supports Cyrillic" — **verified FALSE** (CJK+EN+JP only); swapping
  to it would drop Russian. Also its invented ONNX filenames (`ch_PP-OCRv5_server_v2.0_*`), USD/placeholder
  ₽ pricing, and `pyphen`-as-spellcheck.
- **gemini's EasyOCR migration** — built on a wrong `cyrillic_g2.pth` size (it's **~15.3 MB**, not 215 MB
  (opus) or 120 MB (gemini) — both wrong) and on `det_limit_side_len` (a Paddle/RapidOCR param EasyOCR
  doesn't use). Plus code bugs (`cv2.BORDER_RECLAIM`, lowercase `wdt:p31` SPARQL). Discard the migration.
- **ALL VM-size + ₽ figures (every model)** — for a box we're not on. Pelican is fixed 4 vCPU / 32 GB; RAM
  isn't the constraint, **per-scan latency (~5 s) + the PaddleOCR-3.x mkldnn OOM pathology** are. Discard
  det-resolution caps justified by a RAM ceiling (re-evaluate 1536 by MEASURED CER + latency).
- **ALL CER figures are PROJECTIONS** (opus 6–15%, gpt 5–35%, deepseek 8–15%/40–50% recovery, gemini 3–6%,
  alice 15–25%) — none from the n=10 set. Do not report as measured; re-measure after each change.
- **Stale/suspect pins:** deepseek `rapidocr==3.3.0` (current 3.8.4), `pymorphy3==0.2.0`, `cyhunspell==2.0.3`;
  any quoted model **SHA256** (none were provided — compute locally at build). Surya is GPU-oriented +
  OpenRAIL-M commercial-restricted — out regardless.
