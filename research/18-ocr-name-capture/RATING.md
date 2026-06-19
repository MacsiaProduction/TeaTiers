# Rating — 18-ocr-name-capture

Prompt: ./prompt.md   ·   Date judged: 2026-06-20

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model    | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus     |    5     |   5   |       5       |    2     |    4    |  4.20 |  1   |
| gpt      |    4     |   5   |       5       |    3     |    5    |  3.85 |  2   |
| alice    |    3     |   4   |       4       |    3     |    3    |  2.85 |  3   |
| deepseek |    2     |   3   |       3       |    4     |    3    |  1.95 |  4   |
| gemini   |    2     |   3   |       3       |    5     |    4    |  1.95 |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**The plan is unanimous; the split is on engineering rigor + hallucination.** All five agree: the gap is
**post-recognition interpretation, not a wrong engine** — build a one-directional Latin/digit→Cyrillic
**confusable normalizer** (same core map: `A→А B→В E→Е H→Н K→К M→М O→О P→Р C→С T→Т X→Х Y→У W→Ш 3→з 6→б
9→а 0→О`), gated behind a per-line script-majority / non-homoglyph-Latin guard so genuine Latin tokens
(`HONG LO`, `Gaba`, `Lapsang`) aren't corrupted; use **pg_trgm** (GIN trgm + unaccent + lower) as a
candidate **generator** and **rapidfuzz** as the re-ranker; **ship Track 1 now** (zero egress, highest
ROI). On Track 2 all agree preprocessing must be **conditional** (citing the #114 unconditional-upscale
regression) and that **no local CPU handwriting model fits the 4 GB / 2 vCPU VM *and* beats the current
`eslav_pp-ocrv5_mobile_rec.onnx`** — keep the recognizer; route true cursive to manual entry. On Track 3
all agree **Yandex Vision OCR** (`/ocr/v1/recognizeText`, `model:"handwritten"`, ru+en) is the right —
but egress-breaking — cloud tool, only as a strictly per-photo **opt-in**.

**Winner: opus** — best-engineered and most verifiable. Its **per-token unambiguous-Latin guard +
keep-best-of-both** (emit normalized AND original, let the catalog match score both, keep the higher —
safe-by-construction) directly solves the run's hardest sub-question (not corrupting `Gaba`/`HONG LO`),
and its **threshold ladder is the only one with a margin-to-2nd-best guard** (auto-accept
token_sort_ratio ≥ 88 AND margin ≥ 8; suggest-confirm 70–88; reject < 70; tie-break chain). Its
load-bearing pins **verified CONFIRMED** (`confusable_homoglyphs` 3.3.1, `rapidfuzz` 3.14.5, eslav rec
7.5 MB, PP-OCRv5 server 84 MB/3035 ms, TrOCR-base 334 M ≈ 1.3 GB → the "won't fit" math is right). Only
minor non-load-bearing slips (opencv wheel "37–63 MB" ≈ actual 28–54 MB; wrong arXiv id for trocr-small;
"confusables ships Unicode 8.0.0" detail off).

**gpt (close #2)** has the **strongest architecture** — keep the sidecar **stateless with no DB creds**
(normalize in the sidecar; pg_trgm candidate-gen + final ranking app-side in Spring), an immutable
`tea_name_norm()` SQL function feeding a generated GIN-indexed `name_norm` column, and a default-off
feature-flag framing for the cloud fallback. It lost only on two fabrications: a **made-up rapidocr
3.8.4 wheel SHA256** and **10-significant-figure Yandex USD prices** ($0.0010827867…).

**Reuse** — into the build:
- **opus:** the per-token decision rule (unambiguous-Latin-only guard + Cyrillic-context all-glyph rule)
  + keep-best-of-both; the full threshold ladder (≥88 & margin≥8 / 70–88 / <70 + tie-breaks); rapidfuzz
  scorer = max(WRatio, token_sort_ratio) over BOTH raw and normalized text; the TrOCR-vs-4 GB rejection
  math. `confusable_homoglyphs` 3.3.1 in **tests only** (it's a spoof-*detection* lib — it does not
  provide the replacement map; the runtime map is hand-rolled + stdlib `unicodedata` for script
  detection).
- **gpt:** the **stateless-sidecar / match-in-Spring** split (no DB creds in the sidecar); the immutable
  `tea_name_norm()` (unaccent + ё→е + lower) → generated `name_norm` GIN column; the inverse-map idea to
  actively protect pinyin/Latin; the default-off feature flag for cloud.
- **gemini/alice:** the UX — a Compose **per-photo consent dialog** for cloud opt-in, and a "show raw
  OCR for manual entry" fallback when nothing clears threshold.
- **Verified-safe pins:** `confusable_homoglyphs==3.3.1` (MIT), `rapidfuzz==3.14.5` (MIT),
  `opencv-python-headless==4.11.0.86` (Apache-2.0), `eslav_pp-ocrv5_mobile_rec.onnx` (7.5 MB, Apache-2.0).

**Discard** — do NOT let these reach the build:
- **REFUTED** — deepseek's load-bearing T2 pick "**PP-OCRv5 *server* rec targets/improves handwriting**"
  (false — PP-OCR rec is general scene-text, not HTR; do not swap mobile→server expecting a cursive win).
- **HALLUCINATED** — gemini's HF model `Kansallisarkisto/cyrillic-large-handwritten-onnx` @ 1.22 GB (repo
  id + size fabricated); deepseek's `icukit` PyPI lib (the real ICU binding is **PyICU**); alice's
  `confusable-homoglyphs 3.2.0 "updated 8 June 2026"` (real latest is **3.3.1, 2024-01-30**).
- **WRONG/STALE PINS** — gemini's `rapidocr_onnxruntime==1.3.24` (conflicts with the stack's RapidOCR
  3.8.4); deepseek's "RapidOCR v3.5.0"; gpt's fabricated rapidocr 3.8.4 **wheel SHA256** — never pin by it.
- **UNCITED / IMPLAUSIBLE PRICING** — gpt's 10-sig-fig USD prices and gemini's "Verified for 2026"
  0.1321 ₽/1.52 ₽ with no citation. Use **~0.13 ₽ printed / ~1.50 ₽ handwritten (RUB region)** as a ROUGH
  planning figure only and **re-verify on the live Yandex pricing page** before committing.
- **UNVERIFIED PRIVACY STORY** — the `x-data-logging-enabled: false` "no-storage" guarantee (claimed by
  opus/gpt/deepseek, flagged HIGH-suspicion by gemini's own checker, contradicted by alice's "3-day, no
  toggle"). The single least-verified T3 claim — **do not ship a no-storage consent promise on this
  header alone**; confirm against current Yandex docs first, and crop-to-text + per-photo consent
  regardless.
- **NOT MEASURED** — every model's Track-1 lift (60–80 %, 2/10→6–8/10) is an **unvalidated estimate**.
  Benchmark on the existing n=10, then ≥50 photos, before reporting any number as a result. opus's 3035 ms
  PP-OCRv5 figure is a Xeon Gold 6271C bench, NOT a 2-vCPU-VM measurement (no model gave a real on-VM number).
