# Decision

Ship a **local-only, higher-resolution RapidOCR/PaddleOCR pipeline** for descriptions, keep the current no-photo-egress product rule, and add a **description-safe correction layer** that is dictionary/domain-gated rather than a blind confusable normalizer. The strongest practical finding is negative: **PP-OCRv5 has no official East-Slavic/Russian “server” recognizer** comparable to `PP-OCRv5_server_rec`; the official server recognizer is for Simplified Chinese, Traditional Chinese, English, Japanese, pinyin, handwriting, and related scenarios, not Russian. The best Russian-capable recognizer remains the PP-OCRv5 **mobile-tier East-Slavic/Cyrillic recognizer**, so the accuracy win should come mainly from **better detection scale, conditional preprocessing, better paragraph reconstruction, and safer post-correction**, not from a nonexistent Russian server-rec model.  ([Hugging Face][1])

The recommended production target is:

> **Yandex Cloud standard-v3, 4 vCPU / 16 GB RAM, 100% vCPU, CPU-only, concurrency 1.**
> Run **PP-OCRv5 detection at 1280 by default**, conditionally rerun difficult images at **1536**, recognize with **`eslav_PP-OCRv5_mobile_rec`**, then apply **dictionary/domain-gated correction** and feed only the resulting **text** into the existing enrichment tier.

Expected outcome: for clean printed Russian descriptions, this should move from the current “usable but visibly degraded” state toward **light-to-moderate editing** rather than retyping. It will not close the full gap to AI-vision OCR on glossy, curved, low-light, tiny-text packages, but it is good enough for the review-before-save and enrichment-seed flow.

---

# Track A — best local full-text OCR

## A1. Model decision

### What not to do: do not switch Russian recognition to `PP-OCRv5_server_rec`

`PP-OCRv5_server_rec` is attractive because it is larger, but the official model card says it supports **Simplified Chinese, Traditional Chinese, English, Japanese**, plus pinyin, rare characters, handwriting, vertical text, and related scenarios. It does **not** claim Russian or East-Slavic support. Its model card also labels it Apache-2.0 and gives a line-level accuracy metric, but that metric is for its supported language/scenario mix, not Russian package paragraphs. ([Hugging Face][1])

So the answer to the central “server rec” question is:

| Question                                                              | Answer                                                                                                                                   |
| --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Is there an official East-Slavic / Russian `PP-OCRv5_server_rec`?     | **I found no official one.**                                                                                                             |
| Should TeaTiers use `PP-OCRv5_server_rec` for Russian descriptions?   | **No.** It is the wrong recognition alphabet/domain.                                                                                     |
| Is `eslav_PP-OCRv5_mobile_rec` still the best PP-OCRv5 RU recognizer? | **Yes, among the official/current sources I found.**                                                                                     |
| Where can “bigger VM” still help?                                     | Higher detector resolution, optional server detector experiments, multiple candidate passes, better preprocessing, and safer correction. |

### Recommended recognition model

Use **`eslav_PP-OCRv5_mobile_rec`** as the main recognizer. PaddleX lists it as an East-Slavic recognition model trained on the PP-OCRv5 framework, supporting **East Slavic languages, English, and numeric text recognition**, with **85.8% line-level accuracy**, **21.20 ms CPU normal-mode / 5.32 ms high-performance-mode per line** in Paddle’s benchmark environment, and **14 MB storage size**. Paddle’s benchmark hardware for that table is an Intel Xeon Gold 6271C CPU, so those per-line numbers should not be treated as full-photo latency on a small VM. ([paddlepaddle.github.io][2])

For the existing RapidOCR/ONNX sidecar, the practical pinned ONNX pair is the PP-OCRv5 ONNX export set:

| Component                              |                                Model / path to pin |                                                   Size from source | License / status                                               | Verdict                                                                 |
| -------------------------------------- | -------------------------------------------------: | -----------------------------------------------------------------: | -------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Detection                              |    `monkt/paddleocr-onnx`, `detection/v5/det.onnx` |                                                             ~84 MB | Apache-2.0; model card says commercial use allowed             | Use as current RapidOCR-compatible v5 detector candidate.               |
| Recognition                            | `monkt/paddleocr-onnx`, `languages/eslav/rec.onnx` |                                                            ~7.5 MB | Apache-2.0; supports Russian, Bulgarian, Ukrainian, Belarusian | Main RapidOCR recognizer.                                               |
| Alternative official Paddle recognizer |                        `eslav_PP-OCRv5_mobile_rec` |                                                              14 MB | Paddle/PaddleX model; East-Slavic + English + numbers          | Main model if using native PaddleOCR/PaddleX instead of ONNX.           |
| Fallback recognizer                    |        `PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec` | size not visible in the opened card; official card says Apache-2.0 | Supports Cyrillic; 80.27% line-level accuracy                  | Evaluate, but likely not better than East-Slavic for Russian packaging. |
| Do-not-use for RU                      |                 `PaddlePaddle/PP-OCRv5_server_rec` |                                             81 MB in PaddleX table | Apache-2.0; CJK/English/Japanese, not Russian                  | Do not use for Russian descriptions.                                    |

The ONNX model card gives the exact RapidOCR usage pattern and explicitly says to use PP-OCRv5 detection with PP-OCRv5 recognition rather than mixing v3/v5 families. It also lists East-Slavic support as Russian/Bulgarian/Ukrainian/Belarusian. ([Hugging Face][3])

**Pinning note:** I would not invent SHA256 hashes here. The public docs/model cards I could access list model names, model IDs, sizes, and licenses, but not stable SHA256 values in the rendered text. The build should pin by **repository revision/commit** where possible and compute/store local SHA256 at Docker build time:

```bash
sha256sum models/det.onnx models/eslav_rec.onnx > /app/model-sha256.txt
```

Expose those hashes in `/health/ocr` so a production incident can tell which exact model artifact was running.

---

## A2. Detection resolution: where the real accuracy gain is

The current `det_limit_side_len=960` was a RAM bound, not an accuracy optimum. For package descriptions, small text is the dominant failure mode. PaddleOCR/PaddleX exposes `limit_side_len`, `limit_type`, `box_thresh`, `unclip_ratio`, and related text detection parameters; the docs explicitly describe `limit_side_len` as the side-length constraint for the detection model and note higher values such as 1216 for higher-resolution images requiring larger detection scales. ([paddlepaddle.github.io][4])

Recommended production behavior:

| Pass   | When                          |                          Detector scale | Purpose                                                      |
| ------ | ----------------------------- | --------------------------------------: | ------------------------------------------------------------ |
| Pass 1 | Always                        | `limit_type=max`, `limit_side_len=1280` | New default. Better small-text boxes without explosive cost. |
| Pass 2 | Conditional                   | `limit_type=max`, `limit_side_len=1536` | Rerun only if Pass 1 looks incomplete or low-confidence.     |
| Pass 3 | Debug / manual benchmark only |                             `1792–2048` | Not default; too slow and may increase false positives.      |

Trigger Pass 2 when any of these is true:

```text
recognized_cyrillic_chars < 250
OR median_detected_box_height_px < 18
OR mean_rec_score < 0.82
OR dictionary_hit_ratio < 0.55
OR detected_lines < expected_min_lines_for_description_panel
```

Keep `concurrency=1`. Description OCR is user-visible and not batch throughput; concurrency 1 avoids RAM spikes and keeps the VM predictable.

### Expected CPU latency and RAM

These are engineering estimates, not vendor-published benchmarks for a Yandex VM. Paddle’s published recognition numbers are per-line on Xeon Gold 6271C, while full package OCR latency is dominated by detection scale, image pixels, preprocessing, and number of detected lines. ([paddlepaddle.github.io][2])

Assuming **Yandex Cloud standard-v3, 4 vCPU / 16 GB, 100% vCPU**, CPU-only, MKL/ONNX Runtime enabled where available:

| OCR mode             | Expected p50 | Expected p95 | Peak OCR-sidecar RSS | Use                                 |
| -------------------- | -----------: | -----------: | -------------------: | ----------------------------------- |
| Current-ish 960 pass |      1.5–3 s |        4–6 s |           0.8–1.5 GB | Keep only as comparison baseline.   |
| 1280 pass            |      2.5–5 s |        6–9 s |           1.5–2.5 GB | **Default.**                        |
| 1536 pass            |        4–8 s |      10–15 s |             2.5–4 GB | Conditional retry.                  |
| 2048 pass            |      8–20+ s |        20+ s |              5–8+ GB | Debug only, not production default. |

The key is not to promise a deterministic latency until benchmarking on the actual VM. The first production milestone should log `image_px`, `det_scale`, `line_count`, `mean_rec_score`, `dictionary_hit_ratio`, `wall_ms`, and `peak_rss_mb`.

---

## A3. Should you use `PP-OCRv5_server_det`?

Yes, but as an A/B candidate, not as a guaranteed immediate switch. `PP-OCRv5_server_det` is an official Apache-2.0 text detection model; its card says it targets complex layouts, varying text sizes, challenging backgrounds, rotated/curved text, and multiple languages, and it is used through `TextDetection(model_name="PP-OCRv5_server_det")`. ([Hugging Face][5])

However, the recognizer still stays East-Slavic mobile. The useful experiment is:

```text
A: v5 ONNX detector + eslav ONNX recognizer, 1280/1536
B: PP-OCRv5_server_det + eslav_PP-OCRv5_mobile_rec, 1280/1536
```

Acceptance criterion:

```text
Adopt server_det only if it improves description CER by >= 10–15% relative
or recovers materially more small-text lines
while keeping p95 <= 15 s and peak RSS <= 4 GB at concurrency 1.
```

Do not accept a detector just because it returns more boxes. More boxes can mean more garbage text, worse paragraph reconstruction, and worse enrichment context.

---

## A4. Other local OCR stacks

| Stack                              | RU support / license                                                                                                                                                                                  | Resource profile                                                                                                | Verdict for TeaTiers                                                  |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| **RapidOCR 3.8.4 + PP-OCRv5 ONNX** | RapidOCR is Apache-2.0, v3.8.4 is current as of June 2026; it converts PaddleOCR models to ONNX for multi-platform deployment. ([GitHub][6])                                                          | Lightest production path; already integrated.                                                                   | **Ship first.**                                                       |
| **Native PaddleOCR / PaddleX**     | Official PP-OCRv5 models and pipeline; exposes document orientation, unwarping, textline orientation, detection, recognition modules. ([paddlepaddle.github.io][7])                                   | Heavier Python/Paddle dependency than ONNX, but manageable on 4/16.                                             | Good A/B path, especially for `server_det`.                           |
| **EasyOCR**                        | Apache-2.0, supports 80+ languages including Russian and Cyrillic-script combinations. ([GitHub][8])                                                                                                  | PyTorch CPU path is usually slower and heavier.                                                                 | Keep as a benchmark fallback, not production first choice.            |
| **docTR**                          | Apache-2.0, active OCR framework with strong detection/recognition architecture. ([GitHub][9])                                                                                                        | Good document OCR framework, but I found no first-class Russian packaging recognizer that beats PP-OCRv5 eslav. | Not a first production candidate.                                     |
| **Surya OCR**                      | Multilingual and strong on benchmarks, but its model is ~650M parameters; code is Apache-2.0, weights use a modified AI Pubs OpenRAIL-M license with startup/research/personal limits. ([GitHub][10]) | GPU-oriented; CPU on a small VM is unattractive.                                                                | Do not ship unless licensing and CPU latency are explicitly accepted. |
| **Occular OCR**                    | Russian-document-focused, claims strong CPU speed/accuracy, but repo is small and code is GPL-3.0. ([GitHub][11])                                                                                     | Interesting, but immature and license-infectious for some packaging choices.                                    | Research-only A/B, not MVP production.                                |
| **TrOCR / generic seq2seq OCR**    | Mostly line-recognition; Russian models I found are not a clean printed-packaging drop-in.                                                                                                            | Heavy; needs line crops and domain tuning.                                                                      | Not worth it before collecting paired TeaTiers corrections.           |

---

## A5. Realistic quality target

Do not expect local CPU OCR to become AI-vision OCR just by upsizing the VM. The most defensible target is:

| Photo condition                                          | Expected after Track A + B                                      |
| -------------------------------------------------------- | --------------------------------------------------------------- |
| Flat printed label, good light, text height reasonable   | **5–12% CER**, mostly light editing.                            |
| Normal real package photo, mild curvature/glare          | **10–20% CER**, moderate editing but useful enrichment context. |
| Glossy bag, curved panel, tiny serif text, shadows/glare | **20–35%+ CER**, still user-correction-heavy.                   |
| Handwritten labels                                       | Out of scope for description quality; treat as best effort.     |
| Chinese glyphs                                           | Out of scope by decision; do not pretend to support them.       |

For the app, this is still a good trade: descriptions are long, so even a 75–90% faithful draft saves work, and enrichment does not require perfect prose. It requires enough signal: tea type, vendor, origin, brewing terms, flavor terms, and product-domain tokens.

---

## A6. Preprocessing pipeline

The preprocessing lesson from the name-OCR work carries over: **no unconditional destructive preprocessing**. Use candidate generation and scoring.

Recommended image candidates:

```text
Candidate 0: raw image, EXIF orientation fixed, RGB/BGR only.
Candidate 1: resize/upscale only if short side < 1200 px or median text height is low.
Candidate 2: grayscale + CLAHE for low-contrast matte labels.
Candidate 3: mild denoise + sharpen for noisy low-light photos.
Candidate 4: adaptive threshold only for flat, high-contrast paper labels.
```

Concrete defaults:

```python
# Candidate 1: safe upscale
scale = min(2.0, max(1.0, 1600 / max(h, w)))  # cap, do not blindly enlarge huge photos
resize = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_LANCZOS4)

# Candidate 2: contrast
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
gray2 = clahe.apply(gray)

# Candidate 3: mild denoise + unsharp
den = cv2.bilateralFilter(gray2, d=5, sigmaColor=35, sigmaSpace=35)
blur = cv2.GaussianBlur(den, (0, 0), 1.0)
sharp = cv2.addWeighted(den, 1.5, blur, -0.5, 0)

# Candidate 4: only if background looks flat / paper-like
bin_img = cv2.adaptiveThreshold(
    gray2, 255,
    cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
    cv2.THRESH_BINARY,
    31, 11
)
```

Candidate scoring:

```text
score =
  + 2.0 * normalized_cyrillic_char_count
  + 1.5 * mean_rec_score
  + 1.0 * dictionary_hit_ratio
  + 0.5 * plausible_line_geometry_score
  - 2.0 * garbage_symbol_ratio
  - 1.0 * excessive_duplicate_line_penalty
```

Keep the best candidate’s text, but retain raw OCR and candidate metadata internally for debugging. The user should see one editable description, not five variants.

Paragraph reconstruction matters nearly as much as recognition. Sort boxes by reading order, cluster lines by vertical overlap/baseline, join hyphenated line breaks only when both sides are dictionary-plausible, and preserve paragraph breaks when vertical gaps are larger than the median line gap.

---

# Track B — description-safe correction

## B1. Replace blind homoglyph normalization for descriptions

The shipped name-matching normalizer is useful for catalog search but unsafe for human-readable free text. The `Букет → Byкeт → Вукет` failure is exactly what a description pipeline must avoid: it converts obvious OCR garbage into a plausible-looking wrong word.

The fix is not “better confusable mapping.” The fix is **candidate generation + lexical gating + domain gating + confidence threshold**.

## B2. Recommended correction stack

Use:

1. **`pymorphy3` + `pymorphy3-dicts-ru`** as the main Russian morphology/known-word gate. The Russian dictionary package is published separately, is about 8.4 MB as source distribution, and its data is based on OpenCorpora under CC BY-SA; the package metadata exposes a SHA256 for the source tarball. ([PyPI][12])
2. **Hunspell Russian dictionary** as an independent spellcheck/suggestion source. Hunspell supports Unicode and complex morphology; Debian’s `hunspell-ru` package is around 1.1 MB download and 6.1 MB installed. ([Hunspell][13])
3. **A TeaTiers domain lexicon** for tea words that generic dictionaries often miss: `гунфу`, `улун`, `пуэр`, `шэн`, `шу`, `габа`, `ассам`, `цейлон`, `дарджилинг`, `юннань`, `фуцзянь`, `да хун пао`, `те гуань инь`, `сенча`, `матча`, `ройбуш`, vendor names, domains, and transliteration variants.
4. Optional **`pyspellchecker`** or **SymSpell** for fast frequency suggestions. `pyspellchecker` supports Russian and uses Levenshtein edit-distance candidates; SymSpell uses precomputed delete dictionaries and term frequencies for fast lookup. ([Pyspellchecker][14])

This will fit comfortably on the VM. Expect tens of MB of memory and usually **single-digit to tens of milliseconds** per description after OCR.

## B3. Token policy

Every token gets a class before correction:

```text
PROTECTED:
  URLs, domains, emails
  temperatures, ratios, weights, volumes, percentages
  product codes, lot codes
  tokens with mostly Latin and known as brand/pinyin/vendor
  all-uppercase short brand tokens
  mixed script tokens that look like URLs or names

CORRECTABLE:
  mostly Cyrillic words with 1–3 Latin/digit confusables
  mixed Cyrillic/Latin tokens with Russian morphology candidates
  unknown words within edit distance 1–2 of tea-domain lexicon entries

DO-NOT-CHANGE:
  already valid Russian words unless only script normalization changes
  unknown vendor names unless a domain lexicon match is very strong
  pinyin/English tea names unless explicitly in a transliteration map
```

## B4. Candidate generation

For each correctable token, generate a small lattice rather than one replacement.

Example: `Byкeт`

```text
Input chars:
B  y  к  e  т

Candidate options:
B -> В, Б
y -> у
e -> е
к -> к
т -> т

Generated candidates:
Вукет
Букет
...
```

Then score:

```text
score(candidate) =
  + 5.0 if candidate is in TeaTiers tea/domain lexicon
  + 4.0 if pymorphy3 parses it as a known Russian word
  + 2.0 if Hunspell accepts it
  + log_frequency_bonus
  - visual_confusable_cost
  - edit_distance_penalty
  - penalty_for_changing_protected_pattern
```

Accept only if:

```text
candidate_score - original_score >= margin
AND candidate is not protected
AND edit distance is bounded:
    <= 2 for short/medium words
    <= 25% of token length for longer words
AND candidate improves script consistency
```

For `Byкeт`, `Букет` wins because it is a real Russian word; `Вукет` should be rejected because it is not a valid Russian word and not a domain term.

## B5. Handling real OCR errors

Dictionary-gated correction can recover some non-homoglyph errors:

| Error                             | Recoverable?             | How                                                                  |
| --------------------------------- | ------------------------ | -------------------------------------------------------------------- |
| `Byкeт → Букет`                   | Yes                      | Confusable lattice + dictionary gate.                                |
| `нOTKAMи → нотками`               | Yes                      | Mixed-script repair + morphology.                                    |
| `KYCTOB → КУСТОВ`                 | Usually yes              | Uppercase confusable lattice + known Russian form.                   |
| `соворо → северо`                 | Often yes                | Unknown token close to valid prefix/word; frequency helps.           |
| `Фунфу → Гунфу`                   | Only with domain lexicon | Generic dictionary may not know `гунфу`; TeaTiers lexicon should.    |
| Missing line / cropped text       | No                       | OCR did not see it.                                                  |
| Wrong segmentation / merged words | Partly                   | Can suggest split, but should be conservative.                       |
| Vendor names                      | Dangerous                | Protect unless domain lexicon or URL evidence supports a correction. |

Realistically, this correction layer should remove **most homoglyph noise** and some 1-edit Russian mistakes, but it will not fix missing boxes, glare, or incorrectly recognized rare names. That is acceptable because the UI remains review-before-save.

## B6. Do not ship a local seq2seq corrector first

Post-OCR seq2seq models can help when trained on a matching dataset; for example, ByT5-style post-OCR correction work reports substantial CER reduction in specific historical-language settings, and Cyrillic post-OCR correction research exists. But these are not drop-in models for Russian tea packaging, and language models can regularize or invent text. ([aclanthology.org][15])

Use a model only after TeaTiers has its own paired data:

```text
(raw OCR, user-corrected description, image metadata, model version)
```

Even then, run it in **suggestion mode** behind a diff, not as an invisible rewrite. For MVP, deterministic correction is the better quality/cost/safety point.

---

# Track C — using noisy OCR as enrichment context

The enrichment tier should receive **structured text context**, not a raw untrusted wall of OCR text.

## C1. Server-side OCR output contract

Return this from `/api/v1/teas/ocr`:

```json
{
  "rawText": "...",
  "correctedText": "...",
  "paragraphs": [
    {
      "text": "...",
      "meanConfidence": 0.91,
      "source": "ocr+safe_correction"
    }
  ],
  "tokens": [
    {
      "text": "Юньнань",
      "kind": "origin_region",
      "confidence": 0.88,
      "sourceSpan": [120, 127]
    }
  ],
  "corrections": [
    {
      "from": "Byкeт",
      "to": "Букет",
      "reason": "confusable+dictionary",
      "confidence": 0.96
    }
  ],
  "quality": {
    "meanRecScore": 0.87,
    "dictionaryHitRatio": 0.71,
    "rerunScale": 1536,
    "qualityBand": "medium"
  }
}
```

The app can show `correctedText` in the editable description field and optionally provide a “show OCR fixes” diff for transparency.

## C2. Extract high-signal enrichment fields

Extract these before calling Wikidata/YandexGPT:

| Signal             | Examples                                                                                     | Use                                       |
| ------------------ | -------------------------------------------------------------------------------------------- | ----------------------------------------- |
| Tea type           | `пуэр`, `шэн`, `шу`, `улун`, `красный чай`, `черный чай`, `зеленый чай`, `белый чай`, `габа` | Catalog type and candidate narrowing.     |
| Region/origin      | `Юньнань`, `Фуцзянь`, `Тайвань`, `Ассам`, `Цейлон`, `Шри-Ланка`, `Индия`, `Китай`            | Query expansion and metadata.             |
| Vendor/brand       | domain, importer/manufacturer lines, brand-like uppercase tokens                             | Product identity and dedup.               |
| Product/name hints | quoted names, large text if available, repeated title-like phrases                           | Candidate name generation.                |
| Flavor words       | `мед`, `цветочный`, `дымный`, `ореховый`, `сухофрукты`, `бергамот`, `жасмин`                 | Flavor profile seed.                      |
| Brewing guidance   | `95°C`, `3 г`, `200 мл`, `2–3 мин`, проливы                                                  | User-facing notes and type sanity checks. |
| Warnings/noise     | low-confidence tokens, unknown mixed-script tokens                                           | Prevent overconfident enrichment.         |

## C3. Wikidata first, but do not expect SKU matching

Wikidata is useful for canonical tea types, regions, cultivars, and broad facts, not for most Russian-market package SKUs. Query strategy:

```text
1. High-confidence exact/domain tokens:
   vendor + corrected title fragment + tea type

2. Type + origin:
   "улун" + "Фуцзянь"
   "пуэр" + "Юньнань"
   "Ассам" + "черный чай"

3. Transliteration variants:
   Da Hong Pao / Да Хун Пао / 大红袍
   Tie Guan Yin / Те Гуань Инь / 铁观音

4. Do not query every OCR token.
```

The enrichment layer should treat Wikidata hits as **supporting evidence**, not proof that the package is the exact tea.

## C4. LLM enrichment prompt shape

Send the YandexGPT/text LLM tier a compact structured payload:

```json
{
  "userTypedName": "...",
  "ocrDescriptionCorrected": "...",
  "ocrDescriptionRaw": "...",
  "extractedSignals": {
    "teaTypeCandidates": ["улун"],
    "originCandidates": ["Фуцзянь"],
    "vendorCandidates": ["..."],
    "flavorTerms": ["цветочный", "медовый"],
    "brewingTerms": ["95°C", "5 г", "пролив"]
  },
  "lowConfidenceTokens": ["..."],
  "knownCorrections": [
    {"from": "Byкeт", "to": "Букет"}
  ]
}
```

The prompt should say:

```text
Use the OCR text as noisy evidence.
Do not invent a precise product if the evidence only supports a tea type.
Return candidate identities with confidence and evidence spans.
Separate:
  - tea type
  - likely commercial/product name
  - brand/vendor
  - origin/region
  - flavor profile hints
  - brewing notes
Mark uncertain fields as unknown.
```

The app should present the result as reviewable suggestions:

```text
Suggested type: Улун
Evidence: "улун", "Фуцзянь"

Suggested origin: Фуцзянь, China
Evidence: "Фуцзянь"

Suggested flavor hints:
floral 3/5, roasted 2/5, sweetness 2/5
Evidence: "цветочный", "медовый", "обжаренный"
```

Never silently replace the user’s editable description with LLM prose. The description is user-owned local text; enrichment output is a separate suggestion.

---

# Track D — recommended sequencing

## Phase 1 — OCR accuracy before correction

Ship a benchmark branch first:

```text
Pipeline A:
  RapidOCR 3.8.4
  PP-OCRv5 ONNX det
  eslav PP-OCRv5 ONNX rec
  det_limit_side_len=1280
  conditional 1536 rerun

Pipeline B:
  Native PaddleOCR/PaddleX
  PP-OCRv5_server_det
  eslav_PP-OCRv5_mobile_rec
  same 1280/1536 policy
```

Benchmark on the existing 10 photos plus at least 30 more real package-description photos. Measure:

```text
CER against manually corrected description
line recall
mean recognition score
dictionary hit ratio
homoglyph count
wall-clock latency
peak RSS
```

Adopt Pipeline B only if it beats Pipeline A by a meaningful margin under the same VM budget.

## Phase 2 — VM upgrade

Yandex Cloud pricing says Compute charges depend on vCPU, RAM, disks, traffic, and public IP; prices in RUB include VAT. The pricing example lists **100% vCPU at 1.24 ₽/hour** and RAM at **0.33 ₽/GB-hour**; Yandex also shows a standard-v3 preset with **4 vCPU / 8 GB / 50 GB SSD / dynamic IP** starting at **5,838 ₽/month**. Standard-v3 uses Intel Ice Lake, and 100% vCPU is intended for workloads needing continuous performance. ([yandex.cloud][16])

Recommended VM:

| VM                                               |                                                                                    Estimated monthly cost | Verdict                                                                          |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------------------: | -------------------------------------------------------------------------------- |
| 4 vCPU / 8 GB, standard-v3, 100%, 50 GB SSD      |                                                                   Official preset: from **5,838 ₽/month** | Minimum acceptable if cost pressure is high.                                     |
| **4 vCPU / 16 GB, standard-v3, 100%, 50 GB SSD** | Compute estimate: `720 * (4*1.24 + 16*0.33) = 7,373 ₽/30d`; with disk/IP, roughly **7,700–8,000 ₽/month** | **Recommended.** Enough headroom for OCR + Spring + Postgres + candidate reruns. |
| 8 vCPU / 16 GB, standard-v3, 100%, 50 GB SSD     |                       Compute estimate: about **10,944 ₽/30d** before disk/IP; roughly **11,300 ₽/month** | Reserve only if benchmarks show unacceptable latency.                            |

The recommendation is **4 vCPU / 16 GB**, not because OCR absolutely needs 16 GB for every request, but because the whole VM already runs Caddy, Spring, Postgres, and the OCR sidecar. The extra RAM prevents swap/death spirals during 1536 reruns and lets you keep diagnostic A/B code temporarily.

## Phase 3 — correction

Disable blind confusable normalization for descriptions. Keep it only in the name-matching path.

Add:

```text
description_corrector/
  tokenizer.py
  protected_tokens.py
  confusable_lattice.py
  ru_dictionary_gate.py
  tea_domain_lexicon.py
  correction_scorer.py
```

Ship with:

```text
pymorphy3
pymorphy3-dicts-ru==2.4.417150.4580142
hunspell-ru system package or bundled dictionary
TeaTiers domain lexicon v1
optional pyspellchecker/symspellpy benchmark
```

UI behavior:

```text
Default field: corrected description
Secondary action: show raw OCR
Optional diff: highlight OCR-safe corrections
```

## Phase 4 — enrichment context

Wire the corrected description into the existing enrichment lookup as **text-only context**:

```text
local OCR image -> raw text
raw text -> safe corrected text
safe corrected text -> signal extraction
signals + corrected text -> Wikidata lookup
signals + corrected text -> YandexGPT text enrichment if needed
suggestions -> user review
```

This respects the no-photo-egress rule because only text leaves the OCR sidecar path, and that text enrichment is already part of the server-side architecture.

---

# Go / no-go

**Go** on local description OCR with a bigger VM.

The winning plan is not “find a bigger Russian recognizer.” The winning plan is:

1. **Raise detection scale** from 960 to 1280 by default.
2. **Conditionally rerun at 1536** when the first pass looks weak.
3. Keep **`eslav_PP-OCRv5_mobile_rec`** for Russian recognition.
4. Evaluate **`PP-OCRv5_server_det`** only as a detector A/B.
5. Replace blind homoglyph normalization with **dictionary/domain-gated correction**.
6. Feed **corrected text + extracted signals**, not the photo, into enrichment.
7. Keep the UI review-first and preserve raw OCR for debugging.

One-paragraph decision: **Upsize to a 4 vCPU / 16 GB standard-v3 Yandex VM, keep extraction fully local, run RapidOCR/PP-OCRv5 with East-Slavic recognition at 1280 plus conditional 1536 detection, and add a conservative Russian dictionary + tea-domain correction layer before text-only enrichment. Do not use `PP-OCRv5_server_rec` for Russian, do not ship a seq2seq corrector yet, and do not introduce cloud image OCR into the default product. This should make package descriptions useful enough for editable prefill and enrichment seeding while preserving TeaTiers’ no-photo-egress value proposition.**

[1]: https://huggingface.co/PaddlePaddle/PP-OCRv5_server_rec "PaddlePaddle/PP-OCRv5_server_rec · Hugging Face"
[2]: https://paddlepaddle.github.io/PaddleX/3.1/en/module_usage/tutorials/ocr_modules/text_recognition.html "Text Recognition - PaddleX Documentation"
[3]: https://huggingface.co/monkt/paddleocr-onnx "monkt/paddleocr-onnx · Hugging Face"
[4]: https://paddlepaddle.github.io/PaddleOCR/main/en/version3.x/module_usage/text_detection.html "Text Detection Module - PaddleOCR Documentation"
[5]: https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det "PaddlePaddle/PP-OCRv5_server_det · Hugging Face"
[6]: https://github.com/rapidai/rapidocr "GitHub - RapidAI/RapidOCR:  Awesome OCR multiple programing languages toolkits based on ONNX Runtime, OpenVINO, MNN, PaddlePaddle, TensorRT and PyTorch. · GitHub"
[7]: https://paddlepaddle.github.io/PaddleX/3.2/en/pipeline_usage/tutorials/ocr_pipelines/OCR.html "OCR - PaddleX Documentation"
[8]: https://github.com/jaidedai/easyocr "GitHub - JaidedAI/EasyOCR: Ready-to-use OCR with 80+ supported languages and all popular writing scripts including Latin, Chinese, Arabic, Devanagari, Cyrillic and etc. · GitHub"
[9]: https://github.com/mindee/doctr "GitHub - mindee/doctr: docTR (Document Text Recognition) - a seamless, high-performing & accessible library for OCR-related tasks powered by Deep Learning. · GitHub"
[10]: https://github.com/datalab-to/surya "GitHub - datalab-to/surya: OCR, layout analysis, reading order, table recognition in 90+ languages · GitHub"
[11]: https://github.com/Bodhi42/Occular-ocr "GitHub - Bodhi42/Occular-ocr: Fast and accurate OCR for Russian documents · GitHub"
[12]: https://pypi.org/project/pymorphy3-dicts-ru/ "pymorphy3-dicts-ru · PyPI"
[13]: https://hunspell.github.io/ "Hunspell: About"
[14]: https://pyspellchecker.readthedocs.io/ "pyspellchecker — pyspellchecker 0.9.0 documentation"
[15]: https://aclanthology.org/2024.latechclfl-1.23/?utm_source=chatgpt.com "Post-OCR Correction of Digitized Swedish Newspapers ..."
[16]: https://yandex.cloud/ru/docs/compute/pricing "Правила тарификации для Yandex Compute Cloud | Yandex Cloud - Документация"
