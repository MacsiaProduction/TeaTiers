# TeaTiers OCR Quality Plan: Raising Cyrillic Tea-Name Capture (Local-First)

## TL;DR
- **Do Track 1 (local Cyrillic↔Latin confusable post-correction + Postgres fuzzy recovery) now** — it is zero-egress, fits the VM trivially, and should recover most of the 6/10 "garbled-but-recoverable" clean-print cases, plausibly lifting strict capture from 20% toward 60–80% with no model change.
- **Track 2 (local handwriting) verdict: preprocessing is worth a small conditional pipeline, but no CPU-only ONNX handwriting model is worth swapping in** — the only models that actually read cursive Cyrillic (TrOCR-RU variants, 334M params) are ~1.3 GB PyTorch, far too heavy for 4 GB/2 vCPU, and cursive Cyrillic is genuinely hard for all local CPU OCR. Handwriting should not block the MVP.
- **Track 3 (opt-in Yandex Vision OCR fallback): GO as a narrowly-scoped, explicit per-photo opt-in for handwriting only** — it nailed the test photos, is RU-reachable with no VPN, costs ≈130 ₽/1000 printed or ≈1,500 ₽/1000 handwritten (VAT incl.), and `x-data-logging-enabled: false` stops Yandex storing the image. But the default stays local; for the MVP, manual entry is an acceptable substitute.

## Key Findings
- The dominant defect (homoglyph substitution on clean print) is **mechanically reversible** with a deterministic per-token normalizer keyed to script-majority detection, because the script is *right* (Cyrillic word) but individual glyphs were decoded as Latin/digit lookalikes.
- The real failure cliff (handwriting/cursive/stylized markers producing wrong-script Latin garbage) is **not** repairable by homoglyph fixes — the recognizer emitted the wrong script entirely, so there is nothing to map back.
- All proposed Track 1 libraries are pure-Python, tiny, and run comfortably in the existing sidecar; the confusable map can be hand-built (most reliable) and optionally cross-checked against Unicode UTS #39 `confusables.txt`.
- No local CPU handwriting model fits the box and beats the current stack; the honest options are preprocessing (marginal), manual entry (free), or opt-in cloud (best quality).

## Details

### TRACK 1 — Local post-correction (highest ROI, keeps no-egress)

**1a. The confusable normalizer (Latin/digit → Cyrillic)**

Build a *small, hand-curated, reversed* map specialized for the observed OCR confusions, rather than relying on a generic library's full table (the generic tables map Cyrillic→Latin/ASCII for spoof detection — you need the reverse, and you need it narrow to avoid corrupting real Latin). Core map (uppercase and lowercase handled separately because case is itself a defect signal):

| OCR glyph (Latin/digit) | → Cyrillic | Notes |
|---|---|---|
| `A`→`А`, `a`→`а` | Latin A → Cyrillic А | |
| `B`→`В` | Latin B → Cyrillic Ve | |
| `E`→`Е`, `e`→`е` | | |
| `K`→`К`, `k`→`к` | | |
| `M`→`М` | | |
| `H`→`Н` | Latin H → Cyrillic En (your "ХУН→XYH", "Н→H") | |
| `O`→`О`, `o`→`о` | | |
| `P`→`Р`, `p`→`р` | Latin P → Cyrillic Er | |
| `C`→`С`, `c`→`с` | | |
| `T`→`Т`, `t`→`т` | | |
| `y`→`у`, `Y`→`У` | your "Гунфу→Фyнфy" | |
| `X`→`Х`, `x`→`х` | your "ХУН→XYH" | |
| `W`→`Ш` | your "Ш→W" | shape-based, not in strict Unicode confusables — keep |
| `3`→`з` | digit→letter | |
| `6`→`б` | digit→letter | |
| `9`→`а` (or `я`) | your "а→9" reversed — ambiguous, low-confidence | |
| `0`→`О`/`о` | digit→letter | |
| `4`→`ч` (optional) | shape-based | |
| `bl`→`ы` (optional digraph) | shape-based | |
| `n`→`п`, `u`→`и` (optional, risky) | only inside confirmed-Cyrillic tokens | |

**Decision rule (per token, to protect genuine Latin like "HONG LO", "Gaba"):**
1. Tokenize the OCR line on whitespace/punctuation.
2. For each token, count characters that are *unambiguously Cyrillic* (in the Cyrillic Unicode block) vs *unambiguously Latin* (letters with **no** Cyrillic homoglyph, e.g. `D, F, G, L, Q, R, S, U, V, Z, b, d, f, g, h, j, l, m, n, q, r, s, t, u, v, w, z` — careful, some of these *are* homoglyphs) vs *ambiguous* (the homoglyph set above).
3. Compute the **line-level** script majority too: if the line already contains ≥1 unambiguous Cyrillic letter, treat the line as Cyrillic-context.
4. **Apply the map to a token only if** (a) the token contains ≥1 unambiguous Cyrillic char, OR (b) the line is Cyrillic-context AND the token is composed *entirely* of ambiguous/mappable glyphs (this catches an all-caps "XYH" sitting in a Cyrillic line).
5. **Never map** a token that contains an unambiguous *Latin-only* letter (D, F, G, R, S, …) — that marks it as a genuine Latin word ("Gaba", "HONG LO", "GABA"). This is the guard that prevents corrupting pinyin/English.
6. Produce **both** the normalized and original strings and let the Postgres match step (1b) score both; keep whichever matches the catalog better. This makes the normalizer safe-by-construction: a wrong normalization simply loses the match race.

**Pinned libraries (all pure-Python, MIT/Apache, run in the existing sidecar):**
- **`confusable_homoglyphs==3.3.1`** (MIT; released 2024-01-30; sdist 325.5 kB, wheel 144.8 kB). Useful for *script detection* (`is_confusable`, mixed-script checks) and to validate your hand map against Unicode data. Maintained fork at sr.ht/~valhalla. Bundles Unicode `confusables.json` + `categories.json`.
- **`confusables==1.2.0`** (MIT; released 2021-03-24; inactive but stable). Provides `confusable_characters(c)` and `normalize(...)`; it ships an older Unicode `confusables.txt` (8.0.0) — usable but **do not depend on it for currency**; prefer your hand map.
- **`homoglyphs==2.0.4`** (MIT) — `to_ascii`/`get_combinations` with explicit `languages={'ru','en'}`; its examples show exactly the Cyrillic↔Latin behavior you need (`'ТЕСТ' → ['TECT']`). Good for generating candidate sets.
- Stdlib **`unicodedata`** (built into CPython) for `unicodedata.name()`/`category()` script classification — zero dependency, the cleanest way to ask "is this codepoint Cyrillic?".
- **Authoritative reference data:** Unicode UTS #39 `confusables.txt` (https://www.unicode.org/Public/security/latest/confusables.txt), Unicode License v3. Use it to *audit* your hand map, not at runtime.

Recommendation: **ship a hand-built map + `unicodedata` script detection as the runtime path** (deterministic, auditable, no surprise corruption), and use `confusable_homoglyphs==3.3.1` only in tests to confirm you haven't missed a common pair.

**1b. Matching corrected text against the Postgres catalog**

You already run `pg_trgm` and server-side `rapidfuzz`. Recommended design:

- Normalize both sides for comparison: lowercase + accent-fold. In Postgres use the **`unaccent`** extension + `lower()`; pg_trgm similarity is already case-insensitive in a default build. Store a generated/normalized `name_norm` column with a **GIN `gin_trgm_ops`** index for speed.
- **Candidate generation in Postgres:** `SELECT id, name, similarity(name_norm, :q) AS sml FROM teas WHERE name_norm % :q ORDER BY sml DESC LIMIT 10;` — the `%` operator uses `pg_trgm.similarity_threshold`, which per the PostgreSQL official docs (F.35 pg_trgm) is *"Sets the current similarity threshold that is used by the % operator. The threshold must be between 0 and 1 (default is 0.3)."* (For reference, `word_similarity` defaults to 0.6 and `strict_word_similarity` to 0.5.) For short tea names, set the session threshold to ~0.3–0.4 to generate candidates without missing the ~70%-similar cases.
- **Re-rank candidates with rapidfuzz** (already in the Python sidecar): score each candidate with `fuzz.WRatio` AND `fuzz.token_sort_ratio` (handles your word-order/space slips) against the normalized OCR string, plus run the comparison on **both** the raw OCR text and the confusable-normalized text; take the max.
- **Threshold + tie-break recommendation:**
  - Auto-accept if best `token_sort_ratio ≥ 88` (or `WRatio ≥ 90`) **and** the margin to the 2nd-best candidate is ≥ 8 points.
  - "Suggest, ask to confirm" band: best score 70–88 → show the candidate as a pre-filled suggestion the user taps to accept (this is where most of your 70%-similar cases live and where a confirm-tap is far better UX than silent error).
  - Reject/await manual entry if best < 70 or the top-2 margin < 4 (ambiguous).
  - Tie-break order: higher rapidfuzz score → higher pg_trgm `similarity()` → shorter edit distance → catalog popularity/recency.
- **Where it runs:** keep the fuzzy *recovery* logic in the **Python sidecar** right after OCR — rapidfuzz is a C++/Python lib with no maintained JVM port, and the confusable normalizer is Python. The sidecar already has the OCR text in hand, the candidate set is small (Postgres returns ≤10 rows), and concurrency is 1, so the added CPU is negligible. Only push to the JVM/Spring side if you ever need the match inside a transaction; in that case the realistic JVM options are **Postgres `similarity()`/`word_similarity()` directly in SQL** (best — no new dependency), `org.apache.commons:commons-text` `LevenshteinDistance`/`JaroWinklerSimilarity`, or `me.xdrop:fuzzywuzzy` (a FuzzyWuzzy JVM port, less maintained). Pin rapidfuzz at **`rapidfuzz==3.14.5`** (MIT).

**1c. How much of the gap Track 1 closes — and what it won't fix**

- **Closes:** the 6 "garbled-but-recoverable" clean-print cases are exactly homoglyph + case + word-order/space defects. Confusable normalization repairs the glyph substitutions; `token_sort_ratio` absorbs the word-order/space slips; the catalog match recovers the canonical name even from a ~70%-similar string. Realistically Track 1 should recover **most of those 6**, moving strict capture from 2/10 toward **~6–8/10** and pulling mean name-similarity up substantially, with **zero egress and no model change**. (Treat the exact lift as a hypothesis to measure on your n=10 set, then expand the test set.)
- **Does NOT fix:** the handwriting/cursive/stylized-marker cases that produce *wrong-script Latin garbage*. There is no Cyrillic signal to map back, and the string is too corrupt for the catalog matcher to find the right row above threshold. It also won't help names genuinely absent from the catalog (matching can only recover what exists). And it can mildly hurt if the normalizer is applied unconditionally — hence the strict per-token guard and the "keep best of both" rule.

### TRACK 2 — Local handwriting / stylized names

**2a. Preprocessing-only pipeline (OpenCV/Pillow), with conditionality**

Pin **`opencv-python-headless==4.11.0.86`** (Apache-2.0; use *headless* on a server VM — no GUI libs, smaller, ~37–63 MB wheel) and **`Pillow==11.3.0`** (HPND/MIT-CMU). (Both have newer releases — opencv 4.13.0.92 from 2026-02-05, Pillow 12.2.0 — but 4.11.0.86 / 11.3.0 are stable, widely deployed, and avoid NumPy-2 churn; pin and test before bumping.)

Because the team previously **regressed on good shots from an unconditional upscale**, gate every transform behind a cheap quality probe and always **keep-best-of-both** (OCR the original AND the processed crop, keep the higher-confidence result):

| Step | Call (params) | Apply unconditionally? |
|---|---|---|
| Grayscale | `cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)` | Safe (internal only; don't feed gray back if model wants 3-ch) |
| Deskew | estimate angle via `cv2.minAreaRect` on thresholded mask → `cv2.warpAffine` rotate | **Conditional**: only if \|angle\| > ~2–3°; skip tiny angles (warps clean shots) |
| CLAHE (local contrast) | `clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8)); clahe.apply(gray)` | **Conditional**: only on low-contrast probe (e.g. std-dev of gray < threshold); clipLimit 2.0 is conservative |
| Denoise | `cv2.fastNlMeansDenoising(gray, h=10)` or light `cv2.GaussianBlur(gray,(3,3),0)` | **Conditional**: only if noise/grain detected; over-denoising erases thin strokes |
| Adaptive binarization | `cv2.adaptiveThreshold(gray,255,cv2.ADAPTIVE_THRESH_GAUSSIAN_C,cv2.THRESH_BINARY,blockSize=31,C=10)` | **Conditional/keep-both**: PP-OCR is trained on natural color images and often does *worse* on hard-binarized input — only try for very uneven lighting, and always race against the unprocessed crop |
| Underline/line removal | morphological open with a long horizontal kernel `cv2.getStructuringElement(cv2.MORPH_RECT,(40,1))` then subtract | **Conditional**: only when a long horizontal run is detected under text |
| Upscale | `cv2.resize(..., fx=2, fy=2, interpolation=cv2.INTER_CUBIC)` | **Conditional ONLY**: gate on detected text-height being small (e.g. line height < ~16 px); this is the step that previously caused regressions, so default OFF |

Net: a conditional CLAHE + conditional deskew + keep-best-of-both is the safe, ROI-positive subset. Aggressive binarization/upscale should be opt-in per-probe, never global.

**2b. Is any local CPU ONNX handwriting/STR model worth swapping in?**

| Candidate | Exact model/file | Size on disk | License | RU/Cyrillic | ONNX? | CPU latency/RAM on 2 vCPU | Verdict |
|---|---|---|---|---|---|---|---|
| **Current**: PP-OCRv5 eslav mobile rec + v5 mobile det | `eslav_pp-ocrv5_mobile_rec.onnx` 7.5 MB; v5 det (mobile) smaller than the 84 MB server det | ~tens of MB total | Apache-2.0 | Yes (East-Slavic incl. Russian, Ukrainian, Belarusian, Bulgarian) | Yes | Your measured baseline; fits ~0.7–1.2 GB | **Keep as default** |
| PP-OCRv5 **server** rec | `PP-OCRv5_server_rec` ONNX (paddle2onnx export) | ~84 MB class | Apache-2.0 | v5 server is CJK/EN/JP-centric, **not** an East-Slavic model | Yes (paddle2onnx) | PaddleOCR benchmark on Intel Xeon Gold 6271C: server det CPU-ONNX ~3,035 ms at 2048 px; rec adds more; RAM noticeably higher | **No** — not Cyrillic-specialized; far slower; doesn't target handwriting |
| EasyOCR `cyrillic_g2` | `cyrillic_g2` recognition + CRAFT detector | full models ~108 MB (community ONNX JPQD build compresses to ~17 MB) | Apache-2.0 | Yes (ru, default Cyrillic model) | Partial (community ONNX only; not first-party) | Heavier than PP mobile; CRAFT det is slow on CPU | **No** — print-oriented like PP-OCR; community ONNX is unofficial; unlikely to beat current on cursive |
| TrOCR small handwritten | `microsoft/trocr-small-handwritten` | **62M params** (DeiT-small encoder + MiniLM decoder, per Microsoft unilm/trocr README & arXiv:2311.17128) | MIT | **Latin/English only** (IAM) | Convertible, not first-party ONNX | Transformer decoder slow on CPU; moderate RAM | **No** — wrong script |
| TrOCR Cyrillic HTR | `kazars24/trocr-base-handwritten-ru`, `raxtemur/trocr-base-ru`, `cyrillic-trocr/trocr-handwritten-cyrillic`, `Kansallisarkisto/cyrillic-htr-model` | **TrOCR-base = 334M params** (BEiT-base encoder + RoBERTa-large decoder, per Microsoft unilm/trocr README; ≈1.3 GB fp32) | apache-2.0 / mixed | **Yes — purpose-built Russian/Cyrillic handwriting**; `kazars24/trocr-base-handwritten-ru` reports **0.048 CER** (trained 5 epochs on the SHIFT Lab CFT Cyrillic Handwriting Dataset, 73,830 Russian crops, 95/5 split) | PyTorch (no maintained CPU-ONNX); would need conversion | Encoder-decoder autoregressive; **multi-second per line on CPU**, ~1.3 GB+ RAM — **will not fit alongside the 3.4 GB stack on a 4 GB VM** | **No (cannot fit)** — best Cyrillic-handwriting accuracy but impossible on this box |

**Verdict:** Handwriting is genuinely hard for *all* local CPU OCR. The only models that actually do cursive Cyrillic well (TrOCR-RU base variants, 334M params, ≈1.3 GB) blow the 4 GB budget and are multi-second/line on 2 vCPU with no GPU. Smaller swap-ins (PP-OCRv5 server, EasyOCR) are print-oriented and not better on cursive; TrOCR-small (62M) is the wrong script. **Do not swap the recognizer.** Keep the eslav mobile rec; add the conditional preprocessing subset; route true handwriting to manual entry or the opt-in cloud fallback.

### TRACK 3 — Optional opt-in AI-vision fallback (the facts)

**Best RU-reachable, no-VPN option: Yandex Vision OCR (`ocr/v1` text recognition).** It is the same vendor family you already call for YandexGPT, reachable from a Yandex Cloud VM with no VPN, and it nailed the test photos including handwriting.

- **Endpoint:** `POST https://ocr.api.cloud.yandex.net/ocr/v1/recognizeText` (synchronous; async variant `/ocr/v1/recognizeTextAsync`). Body: `{"mimeType":"JPEG","languageCodes":["ru","en"],"model":"handwritten","content":"<base64>"}`. For handwriting set `"model":"handwritten"`; per the official Yandex Vision OCR docs (yandex.cloud/en/docs/vision/concepts/ocr/), *"The handwritten and table models only support Russian and English. To use these models, explicitly specify one or both languages in the languageCodes property for the OCR API."* — perfect for your scope. For clean print use the default `page` model with `languageCodes:["ru","en"]` (the RU-EN model).
- **Auth:** IAM token (`Authorization: Bearer <IAM_token>` + `x-folder-id: <folder>`), OR API key (`Authorization: Api-Key <key>`), OR service-account. API key is simplest for a server sidecar; with a service-account key you omit the folder header. Same IAM/service-account machinery as your existing YandexGPT calls.
- **Limits:** JPEG/PNG/PDF, ≤10 MB, ≤20 MP, 1 PDF page per request for sync.
- **Pricing (official Yandex Cloud Vision OCR pricing page, yandex.cloud/ru/docs/vision/pricing, marked "Обновлена 14 марта 2025 г.", accessed 2026-06-19; Russia region, ruble prices include VAT):** billed **per single unit** (one image/page = one unit). Printed-text recognition **0,13 ₽/unit (≈130 ₽ per 1000)**; **handwritten-text recognition 1,50 ₽/unit (≈1,500 ₽ per 1000)**; tables 1,20 ₽/unit; document templates (passport/driver-license/STS) 0,70 ₽/unit; license plates 0,13 ₽/unit. (The page also lists per-unit USD figures excl. VAT, ≈$0.0011 printed / ≈$0.0125 handwritten.) Because the fallback is opt-in and per-photo (only the handful of handwriting cases), monthly cost is trivial — even 1,000 handwritten fallbacks/month ≈ 1,500 ₽.
- **Latency:** "Recognize one page of text in just a few seconds" (official); sync mode is designed for interactive apps.
- **Data retention / privacy:** By default Yandex logs request data. Sending the header **`x-data-logging-enabled: false`** means, per official AI Studio docs, *"Requests with logging off will not be saved on Yandex Cloud servers."* This is the critical control: with that header the uploaded tea photo is **not stored**. Yandex Cloud also states it processes personal data under Russian data-protection requirements (152-FZ / UZ-1). **Note/gap:** Yandex's public docs do **not** publish a specific retention *duration* for the case where logging is left ON (the "3 days" figure circulating online is for Cloud Logging/Data Proc, a different product) — so the safe posture is to always send `x-data-logging-enabled: false` for user photos.
- **vs. sending the image to an LLM vision API (YandexGPT vision tier):** the same `x-data-logging-enabled: false` flag works on the AI Studio/foundationModels endpoints, so privacy posture is equivalent. But Vision OCR is cheaper per call, purpose-built for OCR (returns blocks/lines/words + confidence + bounding boxes), and has a dedicated `handwritten` model — so for *text capture* prefer Vision OCR over a general vision-LLM. Reserve the LLM only if you later want semantic parsing (e.g., "extract the tea name from this label") rather than raw OCR.
- **GMS / egress / PII implications:** This is the **only** path that breaks no-egress — flag it loudly. It needs **no Google Mobile Services** (the photo goes device → your VM → Yandex, all server-side; GMS is irrelevant since you don't use on-device Google ML Kit). The PII concern is that a label photo may incidentally contain other info; mitigate by (a) only ever sending the cropped detected text region or the single flagged photo, (b) always `x-data-logging-enabled: false`, (c) explicit per-photo user consent.
- **Keeping it strictly opt-in & per-photo:** trigger the fallback only when (i) the local engine's match confidence is below the reject threshold from Track 1b **and** (ii) the user explicitly taps "Try cloud recognition (sends this one photo to Yandex)" — never automatic, never batch, never default. Log the consent per photo. Default product value (device → own VM only) is unchanged for every non-flagged photo.

## Recommendations

**Sequencing (decisive):**
1. **Now — Track 1.** Ship the per-token confusable normalizer (hand map + `unicodedata` detection, `confusable_homoglyphs==3.3.1` in tests) and the pg_trgm-candidates → rapidfuzz-rerank recovery in the Python sidecar (`rapidfuzz==3.14.5`, Postgres `unaccent` + GIN `gin_trgm_ops`, `%`-operator threshold 0.3–0.4). Auto-accept ≥88/margin≥8; suggest-confirm 70–88; manual <70. This is the highest-ROI, zero-egress, no-model-change win. **Benchmark on the n=10 set, then expand to ≥50 real photos.**
2. **Next (small) — Track 2 preprocessing.** Add the conditional CLAHE + conditional deskew + keep-best-of-both subset (`opencv-python-headless==4.11.0.86`, `Pillow==11.3.0`). Keep aggressive binarization/upscale gated and default-off to avoid the prior good-shot regression. **Do not swap the recognizer** — no local CPU model both fits 4 GB and beats eslav mobile rec on cursive.
3. **Then — Track 3 as opt-in only.** For the MVP, **handwritten names → manual entry** is acceptable and ships fastest. Add the Yandex Vision OCR `handwritten` fallback as an explicit, per-photo, consent-gated button with `x-data-logging-enabled: false`. It is worth it precisely because it's cheap at low volume and is the only thing that actually reads cursive Cyrillic — but it must never become the default path.

**One-paragraph decision:** Build Track 1 now — it is the cheapest, safest, no-egress change and addresses the majority (the 6 garbled-but-recoverable clean-print cases) by mapping Latin/digit homoglyphs back to Cyrillic under a strict per-token script guard and recovering the canonical name via pg_trgm candidates re-ranked by rapidfuzz, with a suggest-confirm band for the ~70%-similar middle. Add the small conditional preprocessing pipeline next, but keep the current eslav mobile recognizer because no local CPU-only model both fits the 4 GB/2 vCPU VM and beats it on cursive Cyrillic. Treat handwriting as out-of-scope for automatic local OCR: ship manual entry for the MVP and offer Yandex Vision OCR's `handwritten` model strictly as an explicit, per-photo, consent-gated fallback with request logging disabled — cheap at the handful of cases you'll actually hit, and the only option that reliably reads cursive Cyrillic without breaking the local-first promise for everyone else.

## Caveats
- The "Track 1 lifts strict capture to ~6–8/10" figure is a **reasoned estimate**, not a measured result; the n=10 baseline is small. Measure before committing to numbers externally.
- The `W→Ш`, `9→а`, `6→б` mappings are shape-based and not all present in Unicode UTS #39 confusables; they are higher-risk and should only fire inside confirmed-Cyrillic tokens with the keep-best-of-both safety net.
- PP-OCRv5 det/rec exact on-disk sizes vary by export source; the eslav mobile rec is ~7.5 MB (Apache-2.0), but verify the exact baked-in file SHAs in your image. The v5 *server det* CPU latency (~3 s at 2048 px) is from PaddleOCR's published benchmark on an Intel Xeon, not your 2-vCPU VM — treat as directional.
- Yandex does **not** publish a retention duration for logging-ON requests; always send `x-data-logging-enabled: false`. Pricing was read from the official Yandex Cloud Vision OCR pricing page (updated 14 March 2025); the figures are Russia-region, VAT-inclusive — confirm in your own Yandex Cloud billing console before relying on exact kopeck figures, as region/contract currency changes them.
- Library "latest" versions move; everything here is pinned to a specific known-good version (confusable_homoglyphs 3.3.1, confusables 1.2.0, homoglyphs 2.0.4, rapidfuzz 3.14.5, opencv-python-headless 4.11.0.86, Pillow 11.3.0) — re-verify SHAs/licenses at integration time. opencv, Pillow, and rapidfuzz all have newer releases than the pinned ones.