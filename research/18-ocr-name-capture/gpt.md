# Decision

Do **Track 1 now**. It is the highest-return fix, keeps the no-egress promise, fits the VM, and directly targets the measured failure mode: OCR output that is **not random**, but degraded by Cyrillic/Latin/digit confusables and spacing/case errors.

Do **Track 2 only as a conditional retry pipeline**, not as a model swap. The current PP-OCRv5 East-Slavic mobile recognizer is already the right local printed-text model class for Russian packaging. Local CPU handwriting recognition is not mature enough, not small enough, or not reliably Russian/package-domain enough to replace it for MVP.

Do **Track 3 as deferred, explicit, per-photo opt-in only**. The best RU-reachable no-VPN fallback is **Yandex Vision OCR**, specifically its handwritten OCR model for Russian/English, but it breaks the no-egress product property and should not become the default path.

---

# 0. Current stack baseline to keep

Keep the current sidecar baseline:

```text
rapidocr==3.8.4
recognizer: eslav_PP-OCRv5_mobile_rec
detector: PP-OCRv5_mobile_det
angle/textline orientation: off by default
det limit/downscale: 960
conditional low-res upscale only
concurrency: 1
```

RapidOCR 3.8.4 is current enough for this design; PyPI lists `rapidocr 3.8.4`, released June 15, 2026, Apache-2.0, Python `>=3.8,<4`, with wheel SHA256 `1a8400df99ea2348a6d1902d1c6fa64c29edcf56b3c58cecd4cf1e5ff51b7ae2`. ([PyPI][1]) RapidOCR’s own model table says PP-OCRv5 Cyrillic recognition is supported in RapidOCR `>=3.5.0`, with `lang_type=cyrillic`, `model_type=mobile`, and ONNXRuntime support; importantly, the same table does **not** show a Cyrillic PP-OCRv5 “server” recognizer option. ([rapidai.github.io][2])

PaddleX’s model card gives the East-Slavic mobile recognizer a **14 MB** model size and lists Russian/East-Slavic plus English/numeric support; it reports CPU inference measurements in a controlled 8-thread Xeon environment, so those figures should be treated as relative model-size evidence, not as your 2-vCPU latency guarantee. ([PaddlePaddle][3]) The PP-OCRv5 mobile detector is also small, **4.7 MB**, while the server detector is much larger, **84.3 MB**, and much slower on CPU in the same published environment. ([PaddlePaddle][4])

The consequence: your local printed-text OCR foundation is good enough. The current failure is mostly **post-recognition interpretation**, not “wrong engine family”.

---

# 1. Track 1 — local post-correction and catalog recovery

## 1.1 Principle: do not “normalize the OCR text”; generate candidate interpretations

The safe design is **not**:

```text
raw OCR → globally replace Latin with Cyrillic → show corrected text
```

That will corrupt real Latin tea/package text such as:

```text
Gaba
HONG LO
LADYKILLER
LAPSANG
OOLONG
DA HONG PAO
```

The correct design is:

```text
raw OCR lines
  → script-aware token analysis
  → generate a small set of candidate variants
  → match variants against catalog names
  → show raw OCR + best canonical suggestion
  → user confirms/edits
```

Unicode’s confusables data is useful as a reference, but not as the whole algorithm. UTS #39 defines confusable detection and skeletons for security use cases; ICU describes two strings as confusable when they map to the same skeleton. ([Unicode][5]) The Unicode confusables data comes from `confusables.txt`; it is designed to detect visual ambiguity, not to decide whether OCR intended Russian or English. ([GitHub][6])

So: use Unicode confusables as **evidence**, but implement a **small, TeaTiers-specific OCR correction map**.

Recommended pinned helper for tests only:

```text
confusable-homoglyphs==3.3.1
```

PyPI lists `confusable-homoglyphs 3.3.1`, released January 30, 2024, as a maintained package for detecting confusable Unicode homoglyphs. ([PyPI][7]) I would not put it in the hot path; keep the production map deterministic and reviewable.

---

## 1.2 Concrete confusable map

Use three tiers: **safe default Cyrillic recovery**, **candidate-only recovery**, and **Latin-protection recovery**.

### Tier A — safe default: ASCII/digit → Cyrillic in Cyrillic context

Apply these only when the token is already mixed-script, or the line is clearly Cyrillic-majority, or the catalog candidate being scored is Cyrillic.

| OCR char | Candidate Cyrillic | Why                                           |
| -------- | -----------------: | --------------------------------------------- |
| `A`      |                `А` | identical uppercase                           |
| `B`      |                `В` | identical uppercase                           |
| `C`      |                `С` | identical uppercase                           |
| `E`      |                `Е` | identical uppercase                           |
| `H`      |                `Н` | observed: `XYH → ХУН`                         |
| `K`      |                `К` | identical uppercase                           |
| `M`      |                `М` | identical uppercase                           |
| `O`      |                `О` | identical uppercase                           |
| `P`      |                `Р` | identical uppercase                           |
| `T`      |                `Т` | identical uppercase                           |
| `X`      |                `Х` | observed: `XYH → ХУН`                         |
| `Y`      |                `У` | observed OCR Latin `Y` for Cyrillic `У`       |
| `a`      |                `а` | identical lowercase in many fonts             |
| `c`      |                `с` | identical lowercase                           |
| `e`      |                `е` | identical lowercase                           |
| `o`      |                `о` | identical lowercase                           |
| `p`      |                `р` | identical lowercase                           |
| `x`      |                `х` | identical lowercase                           |
| `y`      |                `у` | observed: `Фyнфy → Фунфу`                     |
| `0`      |          `О` / `о` | context-dependent                             |
| `3`      |          `З` / `з` | observed: `з → 3`                             |
| `6`      |                `б` | OCR digit/letter confusion                    |
| `9`      |                `а` | observed: `а → 9`                             |
| `W`      |                `Ш` | OCR-specific, observed/likely for block print |
| `w`      |                `ш` | OCR-specific, lower-confidence                |

`W/w → Ш/ш` is **not** a universal Unicode homoglyph rule. Treat it as an OCR-domain rule and require stronger context or catalog-score improvement.

### Tier B — candidate-only Cyrillic recovery

These should not mutate displayed text directly. Use only inside candidate scoring.

| OCR char        |       Candidate Cyrillic | Risk                                                           |
| --------------- | -----------------------: | -------------------------------------------------------------- |
| `4`             |                      `ч` | common OCR-ish confusion, but false positives in grades/prices |
| `r`             |                      `г` | font/stylized risk                                             |
| `n`             |                      `п` | too risky for real Latin                                       |
| `u`             |                      `и` | too risky for pinyin/English                                   |
| `b`             |                `ь` / `в` | too risky for `Gaba`, brands, English                          |
| `I` / `l` / `1` | `І` / `л` / `I` variants | too ambiguous for Russian catalog matching                     |

Use these only when:

```text
score(candidate_after_mapping, catalog_name) - score(raw, catalog_name) >= 10
AND final_score >= high threshold
AND candidate is not a protected Latin/pinyin token
```

### Tier C — Cyrillic → Latin for protecting genuine Latin names

When matching Latin/pinyin catalog aliases, generate the inverse direction too:

| OCR char  | Candidate Latin |
| --------- | --------------: |
| `А` / `а` |       `A` / `a` |
| `В`       |             `B` |
| `С` / `с` |       `C` / `c` |
| `Е` / `е` |       `E` / `e` |
| `Н`       |             `H` |
| `К` / `к` |       `K` / `k` |
| `М` / `м` |       `M` / `m` |
| `О` / `о` |       `O` / `o` |
| `Р` / `р` |       `P` / `p` |
| `Т`       |             `T` |
| `Х` / `х` |       `X` / `x` |
| `У` / `у` |       `Y` / `y` |

This protects cases such as:

```text
Gаbа → Gaba
НONG LO → HONG LO
Dа Нong Рао → Da Hong Pao
```

---

## 1.3 Script detection rule

Implement script detection using Unicode ranges, not language detection.

For each OCR line and token, count:

```text
cyr = chars in U+0400..U+052F
lat = ASCII Latin A-Z/a-z plus Latin-1 letters
dig = 0-9
other = punctuation, spaces, symbols
```

Then compute:

```text
script_letters = cyr + lat
cyr_ratio = cyr / max(1, script_letters)
lat_ratio = lat / max(1, script_letters)
```

Classify line:

```text
CYRILLIC_MAJORITY if:
  cyr >= 4 AND cyr_ratio >= 0.55

LATIN_MAJORITY if:
  lat >= 4 AND lat_ratio >= 0.70

MIXED_OR_UNKNOWN otherwise
```

Classify token:

```text
MIXED_CYR_TOKEN if:
  token has >= 1 Cyrillic char
  AND token has >= 1 Latin/digit confusable

PURE_LATIN_TOKEN if:
  token has Latin letters only, no Cyrillic
```

Then apply correction rules:

```text
1. If token is MIXED_CYR_TOKEN:
     generate cyrillic_confusable_variant
     Example: Фyнфy → Фунфу

2. If token is PURE_LATIN_TOKEN and line is CYRILLIC_MAJORITY:
     generate cyrillic_confusable_variant
     but keep raw token too
     Example: XYH → ХУН

3. If token is PURE_LATIN_TOKEN and line is LATIN_MAJORITY:
     do not Cyrillic-normalize by default
     Example: HONG LO stays HONG LO

4. If token is PURE_LATIN_TOKEN and line is MIXED_OR_UNKNOWN:
     generate both raw and Cyrillic candidate variants
     let catalog scoring decide

5. If token has Latin plus digit confusables:
     generate both Latin-preserving and Cyrillic-recovery variants
     Example: G9ba → Gaba and Габа candidates

6. Never overwrite sourceText with corrected text.
     Store correction candidates separately.
```

Use a protected Latin/pinyin allowlist only as a **weak penalty**, not as a hard block. Suggested allowlist seeds:

```text
gaba
hong
lo
da
pao
mei
ren
dong
fang
dan
cong
oolong
lapsang
souchong
ladykiller
black
green
white
red
raw
ripe
shu
sheng
puer
pu-erh
```

The real protection is catalog-guided scoring: if `HONG LO` has a good Latin alias match, do not force it into Cyrillic.

---

## 1.4 Normalized forms to generate

For each OCR line and selected line-window, generate these variants:

```text
raw
casefolded
ё-normalized
punctuation-stripped
space-collapsed
cyrillic_confusable_variant
latin_confusable_variant
compact_no_space
token_sort_variant
```

Example:

```text
Raw:
  Фyнфy ХYH

Variants:
  фунфу хун
  Фунфу ХУН
  фунфухун
  хун фунфу
```

For `sourceText`, keep raw OCR. For the UI name suggestion, use the matched catalog canonical name.

---

## 1.5 Where Track 1 should run

Run **catalog matching in the Spring backend**, not in the OCR sidecar.

Reason:

```text
Sidecar responsibility:
  image → OCR lines/boxes/confidences

Spring responsibility:
  OCR text → normalized variants → catalog candidate search → canonical tea suggestion
```

This keeps the sidecar stateless and avoids giving it Postgres credentials or catalog-domain logic. It also keeps the catalog as the source of truth and lets the backend use existing `pg_trgm`, aliases, tea-name locale priorities, dedup keys, telemetry, and review UX.

The confusable normalizer can be implemented in Kotlin in Spring. If you want the same logic in Python for offline evaluation, generate the map from a shared YAML file and test both implementations against golden fixtures.

---

## 1.6 Postgres candidate generation

Postgres `pg_trgm` is appropriate here because it provides trigram-based similarity operators and functions for fuzzy alphanumeric text comparison. ([PostgreSQL][8]) Keep it as the **candidate generator**, not as the final decision-maker.

Add a catalog-side normalized search field.

Example normalization function:

```sql
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE OR REPLACE FUNCTION tea_name_norm(input text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT regexp_replace(
           replace(lower(unaccent(coalesce(input, ''))), 'ё', 'е'),
           '[^[:alnum:]\p{Cyrillic}]+',
           ' ',
           'g'
         )
$$;
```

Postgres regex support around Unicode character classes can vary by environment and collation, so test that exact `\p{Cyrillic}` expression under your Postgres image. If it is not accepted, use a simpler “replace punctuation with spaces, lower, unaccent, ё→е” function and let application-side script logic handle Cyrillic.

Better schema:

```sql
ALTER TABLE tea_name
  ADD COLUMN search_name_norm text;

UPDATE tea_name
SET search_name_norm = tea_name_norm(name);

CREATE INDEX tea_name_search_trgm_idx
  ON tea_name
  USING gin (search_name_norm gin_trgm_ops);
```

Candidate query:

```sql
WITH q AS (
  SELECT tea_name_norm(:variant) AS v
)
SELECT
  tn.tea_id,
  tn.locale,
  tn.name,
  tn.is_primary,
  tn.search_name_norm,
  similarity(tn.search_name_norm, q.v) AS trigram_similarity,
  word_similarity(q.v, tn.search_name_norm) AS word_similarity
FROM tea_name tn, q
WHERE
  tn.search_name_norm % q.v
  OR word_similarity(q.v, tn.search_name_norm) >= 0.55
ORDER BY
  GREATEST(
    similarity(tn.search_name_norm, q.v),
    word_similarity(q.v, tn.search_name_norm)
  ) DESC,
  tn.is_primary DESC
LIMIT 40;
```

Then do final ranking in application code with RapidFuzz-like metrics.

RapidFuzz scorers return normalized similarity scores in the 0–100 range and support score cutoffs; `token_sort_ratio`, `partial_ratio`, and related scorers are directly useful for OCR strings where word order and extra package text vary. ([rapidfuzz.github.io][9]) If you keep a Python evaluator in the sidecar/test harness, pin:

```text
rapidfuzz==3.14.5
```

For Spring, either keep your existing server-side RapidFuzz equivalent or implement the final few metrics in Kotlin. Do not add a new Python service just for matching.

---

## 1.7 Final scoring formula

For each OCR line/window variant `v` and catalog candidate `c`, compute:

```text
base =
  max(
    ratio(v, c),
    token_sort_ratio(v, c),
    token_set_ratio(v, c),
    partial_ratio(v, c)
  )
```

Then adjust:

```text
+4   if candidate locale/script matches dominant OCR script
+3   if all high-IDF tea tokens are present after confusable normalization
+2   if candidate is primary name
+2   if candidate alias and primary name belong to same dedup_key cluster
-6   if script mismatch remains after best variant
-8   if length ratio is extreme: min(len(v), len(c)) / max(...) < 0.55
-10  if candidate is a very short name and not nearly exact
```

Treat name length carefully:

```text
Short canonical name, length < 8:
  accept only if score >= 92 and margin >= 10

Normal canonical name:
  high-confidence suggestion if score >= 86 and margin >= 6

Soft suggestion:
  score 78–85 and margin >= 10

No canonical suggestion:
  score < 78 or ambiguous top-2 candidates
```

Recommended UX bins:

| Score state             | Backend action                           | UI                                        |
| ----------------------- | ---------------------------------------- | ----------------------------------------- |
| `>= 86`, margin `>= 6`  | return `suggestedTeaId` + canonical name | prefill name, show “matched from catalog” |
| `78–85`, margin `>= 10` | return `possibleTeaId`                   | show “Maybe: …”                           |
| ambiguous top-2         | no auto-selection                        | show top 2–3 suggestions                  |
| `< 78`                  | no catalog match                         | show corrected OCR text only              |

For a 10-photo test set, do **not** tune thresholds by gut feel after implementation. Make a small golden file:

```json
{
  "photo_id": "13-ocr-001",
  "expected_name": "Гунфу Хун",
  "raw_ocr": "...",
  "accepted_variants": ["Гунфу Хун", "Хун Гунфу"],
  "must_not_match": ["..."]
}
```

Then report:

```text
strict capture
top-1 canonical recovery
top-3 suggestion recovery
false canonical match rate
manual edit distance after prefill
```

The most important metric is not raw OCR text similarity; it is **manual characters saved** and **wrong canonical matches avoided**.

---

## 1.8 Expected Track 1 gain

Given your n=10 results, Track 1 should recover most of the **6 garbled-but-recoverable** cases, provided the target names or aliases exist in the catalog.

Conservative expectation:

```text
Current strict capture: 2/10
After Track 1 top-1 canonical suggestion: about 5–7/10
After Track 1 top-3 suggestion: about 6–8/10
```

It should fix:

```text
Гунфу → Фyнфy-like degradation
ХУН → XYH
Н/В/С/Р/Х/У Latin substitutions
Ш → W
з → 3
а → 9
case mismatch
minor spacing / token order errors
extra package text around the name
```

It will **not** fix:

```text
true missed detection
severe blur/glare/crop
wrong line segmentation
wrong-script cursive garbage like Dyn.Pam MaiXan for Дун Фан Мэй Жэнь
names absent from the catalog
Chinese glyph OCR
cases where several teas have genuinely similar names
```

---

# 2. Track 2 — local handwriting and stylized packaging

## 2.1 Preprocessing can help, but only as a conditional retry

Do **not** apply binarization/CLAHE/deskew unconditionally. You already learned this from the upscale decision: transforms that rescue bad shots can damage good shots.

Use a two-pass design:

```text
Pass A:
  current baseline RapidOCR image

If Pass A has low confidence / weak catalog match / high homoglyph anomaly:
  Pass B:
    run 2–4 alternate preprocessed variants
    OCR each variant
    choose by catalog score + OCR confidence
```

OpenCV’s adaptive thresholding computes a local threshold per neighborhood and is appropriate for uneven illumination, but it is also destructive if the original image is already clean. ([docs.opencv.org][10]) Pillow’s `ImageOps.autocontrast` stretches image contrast by remapping histogram cutoffs and is a cheap non-destructive-ish candidate, but it still should be evaluated as an alternate, not a replacement. ([Pillow (PIL Fork)][11])

Pinned preprocessing dependencies:

```text
opencv-python-headless==4.13.0.92
Pillow==12.2.0
```

PyPI currently lists `opencv-python-headless 4.13.0.92`. ([PyPI][12])

---

## 2.2 Conditional retry trigger

Run preprocessing retry only when at least one of these is true:

```text
best_catalog_score < 78
OR OCR returned no line with length >= 4
OR mean_rec_confidence < 0.70
OR top candidate margin < 4
OR confusable_anomaly_rate > 0.20
OR user tapped "This is handwritten / marker text"
```

Where:

```text
confusable_anomaly_rate =
  number of ASCII/digit chars that are suspicious inside Cyrillic-majority lines
  / number of script/digit chars in those lines
```

Example: `Фyнфy XYH` has high anomaly rate.

---

## 2.3 Concrete preprocessing variants

Keep the number of retries small. With OCR concurrency 1 on a 2-vCPU VM, do not run a large image-processing search.

### Variant B1 — mild contrast + denoise

```python
img = load_rgb()
gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)

gray = cv2.fastNlMeansDenoising(gray, h=7)
gray = ImageOps.autocontrast(Image.fromarray(gray), cutoff=1)
```

Use for:

```text
low contrast
slightly noisy photos
printed labels
```

### Variant B2 — CLAHE + baseline OCR

```python
gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
gray2 = clahe.apply(gray)
```

Use for:

```text
uneven lighting
faded label
gray paper
marker on textured packaging
```

Do not use with aggressive clip limits by default; high CLAHE can amplify paper texture and tea-package decoration.

### Variant B3 — adaptive threshold

```python
gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
gray = cv2.GaussianBlur(gray, (3, 3), 0)

bin_img = cv2.adaptiveThreshold(
    gray,
    255,
    cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
    cv2.THRESH_BINARY,
    31,
    11
)
```

Use for:

```text
dark text on uneven background
handwritten marker on white label
```

Avoid as default. It can destroy colored printed packaging, thin strokes, and anti-aliased fonts.

### Variant B4 — deskew from OCR boxes

Do not run global Hough deskew on whole tea packaging. Use detected text boxes from Pass A:

```text
angles = angle of each OCR detection quadrilateral
median_angle = median of angles for boxes with confidence >= 0.5
if 2° <= abs(median_angle) <= 12°:
  rotate image by -median_angle
else:
  skip
```

This avoids “correcting” package artwork, slanted logos, or perspective artifacts.

### Variant B5 — line/underline removal only when detected

For marker labels, underlines or box borders can confuse OCR. Remove only long, thin horizontal lines:

```python
horizontal_kernel = cv2.getStructuringElement(
    cv2.MORPH_RECT,
    (max(25, img_width // 20), 1)
)

line_mask = cv2.morphologyEx(binary_inverse, cv2.MORPH_OPEN, horizontal_kernel)

if detected_line_length > 0.40 * image_width:
    cleaned = remove_or_inpaint(line_mask)
else:
    skip
```

Never apply this globally. Tea packages have borders, logos, grade marks, and decorative rules that are real context.

---

## 2.4 How to choose the best retry output

For each OCR result variant, compute:

```text
ocr_quality =
  mean_rec_confidence
  + number_of_reasonable_text_lines
  - penalty_for_too_many_single-character_boxes

catalog_quality =
  best_catalog_score
  + top1_top2_margin
  + script_consistency_bonus

final_quality =
  0.35 * ocr_quality + 0.65 * catalog_quality
```

Return the baseline result unless a retry improves:

```text
best_catalog_score by >= 5
OR changes "no suggestion" into "high-confidence suggestion"
```

This prevents preprocessing regressions on already good printed labels.

---

## 2.5 Local handwriting model options

### Verdict table

| Option                                  | Exact pin / model                                                                    |                      Russian support |                           Size / VM fit |                   Expected handwriting gain | Verdict                        |
| --------------------------------------- | ------------------------------------------------------------------------------------ | -----------------------------------: | --------------------------------------: | ------------------------------------------: | ------------------------------ |
| Current PP-OCRv5 East-Slavic mobile rec | `eslav_PP-OCRv5_mobile_rec` via `rapidocr==3.8.4`                                    |   Yes: East-Slavic + English/numeric |                    Excellent, 14 MB rec |             Good printed text, weak cursive | Keep                           |
| PP-OCRv5 server rec                     | Not available as Cyrillic server rec in RapidOCR table                               |             Not for this RU use case |                  Larger where available |            No proven RU handwriting benefit | Do not switch                  |
| EasyOCR                                 | `easyocr==1.7.2`                                                                     |           Russian/Cyrillic supported |                     Heavy PyTorch stack | Handwriting support listed as “coming next” | Do not deploy                  |
| Tesseract                               | Tesseract 5.x + `rus.traineddata`                                                    |                    Russian available |                            Small enough |     Mainly printed OCR, not package cursive | Optional offline baseline only |
| Microsoft TrOCR small handwritten       | HF `microsoft/trocr-small-handwritten`, commit `9354dd58...`                         | English IAM handwriting, not Russian |             247 MB weights plus runtime |                                      Not RU | Do not deploy                  |
| Community Cyrillic TrOCR                | `Mievst/trocr-base-handwritten-ru-ONNX`, `cyrillic-trocr/trocr-handwritten-cyrillic` |             Russian/Cyrillic claimed | Likely too heavy; uncertain license/ops |                     Needs offline benchmark | Research only                  |

EasyOCR supports many languages including Cyrillic and Russian, and its GitHub/PyPI metadata lists Apache-2.0; however, PyPI’s own “what’s coming next” still lists handwritten text support as future work, so it is not a good answer to your specific cursive-label gap. ([GitHub][13])

Tesseract is a local Apache-2.0 OCR engine with Unicode support and many language models, including Russian trained data, but it is not a strong bet for stylized/cursive package names compared with the current PaddleOCR-family printed-text recognizer. ([GitHub][14])

Microsoft’s `trocr-small-handwritten` is a real handwritten text recognizer, but it is trained/fine-tuned on IAM-style English handwriting; the model repository shows a 247 MB PyTorch model file at commit `9354dd58d4731469414774dd0595f551f68700fa`. ([huggingface.co][15]) Community Cyrillic TrOCR models exist, including Cyrillic/Russian-focused variants, but the ones surfaced are community artifacts with limited domain evidence; one Cyrillic TrOCR model card reports validation CER around 0.253 on its own data, which is not enough to justify deployment in your memory-constrained production sidecar. ([huggingface.co][16])

### Practical conclusion for handwriting

For MVP, do **not** swap in a local handwriting model.

The local CPU path should be:

```text
printed/stylized package → current PP-OCRv5 + Track 1 correction
low-confidence/handwritten → conditional preprocessing retries
still bad → manual entry, or explicit cloud fallback if enabled
```

Handwriting should be treated as a known local-OCR cliff, not as a problem you can cheaply solve under:

```text
4 GB RAM
2 vCPU
~3.4 GB already committed
no GPU
no external API by default
```

---

# 3. Track 3 — optional opt-in AI-vision fallback

## 3.1 Best RU-reachable no-VPN fallback: Yandex Vision OCR

The best fit is **Yandex Vision OCR**, not a general LLM vision call.

Use:

```http
POST https://ocr.api.cloud.yandex.net/ocr/v1/recognizeText
```

Official docs describe the REST method as `TextRecognition.Recognize`, with request body fields including base64 `content`, `mimeType`, `languageCodes`, and `model`. ([Yandex AI Studio][17]) The text-recognition guide shows authentication with either IAM token plus `x-folder-id` or an API key, and it uses `languageCodes: ["ru","en"]` and `model: "handwritten"` in its example. ([Yandex AI Studio][18])

For TeaTiers fallback:

```json
{
  "mimeType": "JPEG",
  "languageCodes": ["ru", "en"],
  "model": "handwritten",
  "content": "<base64 image bytes>"
}
```

Headers:

```http
Authorization: Bearer <IAM_TOKEN>
x-folder-id: <YANDEX_FOLDER_ID>
x-data-logging-enabled: false
```

Yandex documents that the handwritten OCR model recognizes a mix of printed and handwritten text in Russian and English. ([Yandex AI Studio][19]) The broader OCR docs also state that handwritten and table models support Russian and English, and that supported OCR image constraints include JPEG/PNG/PDF with limits such as 10 MB and 20 MP. ([Yandex AI Studio][20])

## 3.2 Pricing as of June 19, 2026

Yandex Vision OCR pricing is per successful recognition request. The official pricing page says each successful request is one pricing unit; multiple images/pages are charged separately. It lists:

```text
Printed text recognition:
  $0.0010827867 per image
  ≈ $1.0827867 per 1000 successful images

Handwriting recognition:
  $0.0124590144 per image
  ≈ $12.4590144 per 1000 successful images
```

The same page notes currency/VAT depends on the contracting Yandex Cloud entity, so a Russian Yandex.Cloud LLC account may see RUB pricing in the console rather than the USD values shown for some entities. ([Yandex AI Studio][21])

For your use case, the price is not the blocker. The blocker is product policy: it breaks no-egress.

## 3.3 Privacy and retention

Yandex’s docs say request data logging is enabled by default for AI services, and that sending:

```http
x-data-logging-enabled: false
```

disables saving request data; they state that requests with logging disabled “will not be saved on Yandex Cloud servers.” ([Yandex AI Studio][22])

That is good enough for an **opt-in fallback**, but it is not the same as no-egress. The image still leaves your VM and is processed by Yandex. So the product copy must say this clearly.

I did **not** find a stable official OCR latency guarantee in the docs I checked. Treat latency as empirical: measure from your Yandex VM with 20–50 sample calls before promising UX behavior.

## 3.4 Why Vision OCR is better than an LLM vision API for fallback

Yandex AI Studio supports multimodal models that can analyze images and return text, using base64 images and the OpenAI-compatible API. ([Yandex AI Studio][23]) Its pricing is token-based by model, including open-source multimodal model options in some modes, which makes simple “price per OCR image” less direct than Vision OCR. ([Yandex AI Studio][24])

For TeaTiers:

```text
Yandex Vision OCR:
  + dedicated OCR
  + RU/EN handwritten model
  + predictable per-image price
  + structured OCR result
  + less likely to hallucinate a tea name
  - still cloud egress

LLM vision:
  + may infer messy handwriting better in some cases
  + can normalize/explain
  - higher hallucination risk
  - less predictable cost
  - harder to separate OCR from “model guessed the tea”
  - still cloud egress
```

Given that the app’s catalog is the source of truth and the user reviews before saving, a deterministic OCR API is a better fallback than a VLM.

---

# 4. Strict opt-in fallback design

Do not silently call Yandex Vision after local failure.

Use this flow:

```text
1. User uploads photo to first-party TeaTiers OCR sidecar.
2. Local OCR runs.
3. Track 1 post-correction + catalog matching runs.
4. If confidence is low:
     show local result and manual edit field.
5. Optional button:
     "Try cloud OCR for this photo"
6. Before sending:
     show one-sentence disclosure.
7. If user confirms:
     strip EXIF
     downscale to <= 1600 px long side unless quality would suffer
     optionally crop to detected text/name area
     send once to Yandex Vision with logging disabled
8. Return OCR text to the same review screen.
9. Store only user-confirmed text/name, never the photo.
```

Suggested disclosure:

```text
Local OCR is unsure. You can optionally send this photo once to Yandex Cloud OCR, which may handle handwriting better. TeaTiers will not store the photo; Yandex request logging will be disabled. You can also type the name manually.
```

Backend safeguards:

```text
default disabled by feature flag
per-photo explicit user action
no persistent “always use cloud OCR” toggle for MVP
strip EXIF before sending
send only JPEG/PNG bytes, not user identifiers
no accounts, no user ID
rate limit per IP/device token
log only: timestamp, local confidence bucket, fallback used yes/no, HTTP status, latency, text length
never log image bytes
never log full OCR text unless user has confirmed it as sourceText
```

This preserves the product line:

```text
Default OCR is local/no-egress.
Cloud OCR is a user-selected rescue action.
```

---

# 5. Implementation sequence

## Phase 1 — Track 1 now

Add:

```text
Kotlin ConfusableNormalizer
Kotlin ScriptStats
CatalogCandidateMatcher
OCR golden-set tests
DB normalized search column/index
UI canonical suggestion state
```

Pin/test assets:

```text
rapidocr==3.8.4
rapidfuzz==3.14.5 only if Python evaluator is used
confusable-homoglyphs==3.3.1 only for test/reference tooling
Unicode confusables.txt vendored into repo with recorded SHA256
```

Do not depend on Unicode `latest` at runtime. Download the confusables file during development, commit it, and record the SHA256 in your repository. The web docs establish the data source, but I cannot honestly verify a stable SHA for your future checked-in file from the documentation alone.

Deliverable:

```text
/api/v1/teas/ocr response:
{
  "rawText": "...",
  "lines": [...],
  "correctedTextPreview": "...",
  "suggestedTea": {
    "teaId": "...",
    "canonicalName": "Гунфу Хун",
    "confidence": "HIGH",
    "score": 91,
    "matchedAlias": "..."
  },
  "alternatives": [...]
}
```

## Phase 2 — conditional preprocessing retry

Add only after Phase 1, because catalog score is the best way to decide whether preprocessing helped.

Pin:

```text
opencv-python-headless==4.13.0.92
Pillow==12.2.0
```

Run at most:

```text
baseline + 2 retry variants by default
baseline + 4 retry variants only for explicit handwritten flag
```

Do not run EasyOCR/TrOCR in production.

## Phase 3 — offline handwriting benchmark, not production

Create a separate benchmark folder:

```text
proof/18-handwriting-local/
  samples/
  crops/
  baseline_rapidocr.json
  preprocessing_retry.json
  easyocr_1_7_2.json
  trocr_cyrillic_candidate.json
  FINDINGS.md
```

Benchmark only line crops, not full package images. If a local Cyrillic HTR model cannot beat manual entry on your real package samples with acceptable latency/RAM, drop it.

## Phase 4 — optional Yandex Vision fallback behind feature flag

Only after the review UX and local path are solid.

Feature flag:

```text
TEATIERS_CLOUD_OCR_FALLBACK=false
```

When enabled:

```text
printed uncertain:
  model = page

user marked handwritten:
  model = handwritten
```

Use `languageCodes=["ru","en"]`, not broad auto-language, because Chinese is out of scope and RU/EN are your actual target.

---

# 6. Final recommendation

For MVP, implement **Track 1 immediately** in the Spring backend: a deterministic Cyrillic/Latin/digit confusable candidate generator plus `pg_trgm` candidate retrieval and RapidFuzz-style final ranking. This should turn many of the current “70% correct but ugly” OCR outputs into correct catalog suggestions without changing the local/no-egress promise. Add **Track 2 only as conditional preprocessing retries**, selected by catalog-score improvement, and do **not** deploy EasyOCR, Tesseract, or TrOCR as a new production recognizer. Treat true cursive handwriting as manual-entry territory for MVP. Add **Yandex Vision OCR** only later as a feature-flagged, explicit, per-photo opt-in fallback with EXIF stripping and `x-data-logging-enabled:false`; it is technically the best RU/no-VPN cloud fallback, but it must remain a narrowly scoped escape hatch, not the TeaTiers default.

[1]: https://pypi.org/project/rapidocr/ "rapidocr · PyPI"
[2]: https://rapidai.github.io/RapidOCRDocs/main/model_list/ "RapidOCR 模型列表 - RapidOCR 文档"
[3]: https://paddlepaddle.github.io/PaddleX/3.2/en/module_usage/tutorials/ocr_modules/text_recognition.html "Text Recognition - PaddleX Documentation"
[4]: https://paddlepaddle.github.io/PaddleX/3.4/en/module_usage/tutorials/ocr_modules/text_detection.html "Text Detection - PaddleX Documentation"
[5]: https://www.unicode.org/reports/tr39/?utm_source=chatgpt.com "UTS #39: Unicode Security Mechanisms"
[6]: https://github.com/unicode-org/ml-confusables-generator?utm_source=chatgpt.com "unicode-org/ml-confusables-generator"
[7]: https://pypi.org/project/confusable-homoglyphs/?utm_source=chatgpt.com "confusable-homoglyphs"
[8]: https://www.postgresql.org/docs/current/pgtrgm.html?utm_source=chatgpt.com "F.35. pg_trgm — support for similarity of text using trigram ..."
[9]: https://rapidfuzz.github.io/RapidFuzz/Usage/fuzz.html?utm_source=chatgpt.com "rapidfuzz.fuzz. - ratio"
[10]: https://docs.opencv.org/master/javadoc/org/opencv/imgproc/Imgproc.html?utm_source=chatgpt.com "Imgproc (OpenCV 4.14.0 Java documentation)"
[11]: https://pillow.readthedocs.io/en/stable/reference/ImageOps.html?utm_source=chatgpt.com "ImageOps module - Pillow (PIL Fork) 12.2.0 documentation"
[12]: https://pypi.org/project/opencv-python-headless/?utm_source=chatgpt.com "opencv-python-headless 4.13.0.92"
[13]: https://github.com/jaidedai/easyocr?utm_source=chatgpt.com "JaidedAI/EasyOCR: Ready-to-use OCR ..."
[14]: https://github.com/tesseract-ocr/tesseract?utm_source=chatgpt.com "Tesseract Open Source OCR Engine (main repository)"
[15]: https://huggingface.co/microsoft/trocr-small-handwritten?utm_source=chatgpt.com "microsoft/trocr-small-handwritten"
[16]: https://huggingface.co/Mievst/trocr-base-handwritten-ru-ONNX?utm_source=chatgpt.com "Mievst/trocr-base-handwritten-ru-ONNX"
[17]: https://aistudio.yandex.ru/docs/ru/vision/ocr/api-ref/TextRecognition/recognize "Vision OCR API, REST: TextRecognition.Recognize | Документация AI Studio"
[18]: https://aistudio.yandex.ru/docs/en/vision/operations/ocr/text-detection-image "Recognizing text in images | AI Studio documentation"
[19]: https://aistudio.yandex.ru/docs/en/vision/operations/ocr/text-detection-handwritten?utm_source=chatgpt.com "Recognizing handwriting in Yandex Vision OCR. ..."
[20]: https://aistudio.yandex.ru/docs/en/vision/concepts/ocr/?utm_source=chatgpt.com "About Yandex Vision OCR | AI Studio documentation"
[21]: https://aistudio.yandex.ru/docs/en/vision/pricing "Vision OCR pricing policy | AI Studio documentation"
[22]: https://aistudio.yandex.ru/docs/en/ai-studio/operations/disable-logging "Disabling request logging | AI Studio documentation"
[23]: https://aistudio.yandex.ru/docs/en/ai-studio/operations/generation/multimodels-request "How to send requests to multimodal models | AI Studio documentation"
[24]: https://aistudio.yandex.ru/docs/en/ai-studio/pricing "Yandex Cloud AI Studio pricing policy | AI Studio documentation"
