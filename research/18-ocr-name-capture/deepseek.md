## Track 1 — Local Post-Correction (Highest ROI, Keep No-Egress)

### 1. Cyrillic↔Latin/Digit Confusable Normalizer

**Concrete Confusable Map**

The dominant defect in your measurements is homoglyph substitution — Latin/digit glyphs standing in for visually similar Cyrillic letters. Based on your observed failures (`Гунфу→Фyнфy`, `ХУН→XYH`, `Н→H`, `В→B`, `Ш→W`, `з→3`, `а→9`, `p→р`) and Unicode confusables data, here is the production-grade mapping to apply **from OCR output (Latin/digit) back to Cyrillic**:

| OCR Output (Latin/Digit) | Target Cyrillic | Notes |
|--------------------------|-----------------|-------|
| `A` | `А` | U+0041 → U+0410 |
| `B` | `В` | U+0042 → U+0412 |
| `C` | `С` | U+0043 → U+0421 |
| `E` | `Е` | U+0045 → U+0415 |
| `H` | `Н` | U+0048 → U+041D |
| `K` | `К` | U+004B → U+041A |
| `M` | `М` | U+004D → U+041C |
| `O` | `О` | U+004F → U+041E |
| `P` | `Р` | U+0050 → U+0420 |
| `T` | `Т` | U+0054 → U+0422 |
| `X` | `Х` | U+0058 → U+0425 |
| `Y` | `У` | U+0059 → U+0423 |
| `a` | `а` | U+0061 → U+0430 |
| `c` | `с` | U+0063 → U+0441 |
| `e` | `е` | U+0065 → U+0435 |
| `o` | `о` | U+006F → U+043E |
| `p` | `р` | U+0070 → U+0440 |
| `x` | `х` | U+0078 → U+0445 |
| `y` | `у` | U+0079 → U+0443 |
| `W` | `Ш` | U+0057 → U+0428 |
| `w` | `ш` | U+0077 → U+0448 |
| `3` | `з` | digit three → Cyrillic зе |
| `6` | `б` | digit six → Cyrillic бе |
| `9` | `а` | digit nine → Cyrillic а (observed) |
| `0` | `о` | digit zero → Cyrillic о |

**Libraries to Use**

- **`homoglyphs`** (PyPI, v2.0.3) — provides `Homoglyphs().get_combinations()` with ISO 15924 category filtering. Load with `categories=('CYRILLIC', 'LATIN', 'COMMON')` to get bidirectional confusable mappings.
- **`icukit`** (PyPI) — ICU-based Unicode normalization (NFKC) + spoof/homograph detection.
- Unicode **`confusables.txt`** (from Unicode Consortium) — the authoritative source; `homoglyphs` already incorporates it.

**Decision Rule for When to Apply**

Do **not** apply globally — that would corrupt genuine Latin tokens like `HONG LO` or `Gaba`. Instead:

1. **Token-level script detection** using `homoglyphs.Categories.detect(token)` or `icukit` script detection.
2. **Apply normalization only if:**
   - The token is **mixed-script** (contains both Cyrillic and Latin/digit confusables) **AND**
   - The **majority** of characters in the **line** (not just the token) are Cyrillic — use a simple heuristic: count Cyrillic vs. Latin code points in the line; if Cyrillic > 60%, apply.
   - **OR** the token contains any digit confusable (`3`, `6`, `9`, `0`) — these almost never appear in genuine tea brand names (except vintage years), so unconditionally map digits in Cyrillic-majority lines.
3. **Post-normalization:** run Unicode NFKC normalization (`unicodedata.normalize('NFKC', text)`) to collapse compatibility decompositions.

**Pseudo‑code:**
```python
import homoglyphs as hg
from unicodedata import normalize

hg_cat = hg.Categories()
CONFUSABLE_MAP = {...}  # full map above

def should_normalize_token(token: str, line: str) -> bool:
    if not any(c in token for c in CONFUSABLE_MAP): return False
    cyr_count = sum(1 for c in line if 'Cyrillic' in unicodedata.name(c, ''))
    lat_count = sum(1 for c in line if 'LATIN' in unicodedata.name(c, ''))
    if cyr_count + lat_count == 0: return False
    return cyr_count / (cyr_count + lat_count) > 0.6

def normalize_ocr(text: str) -> str:
    lines = text.split('\n')
    out = []
    for line in lines:
        tokens = line.split()
        new_tokens = []
        for tok in tokens:
            if should_normalize_token(tok, line):
                for src, dst in CONFUSABLE_MAP.items():
                    tok = tok.replace(src, dst)
            new_tokens.append(tok)
        out.append(' '.join(new_tokens))
    return normalize('NFKC', '\n'.join(out))
```

### 2. Matching Against Postgres Catalog

**Strategy**

- **Case-insensitive + accent-insensitive** fuzzy match using **Postgres `pg_trgm`** with `similarity()` and GIN indexes for fast candidate retrieval.
- **Server-side (`rapidfuzz` in Spring)** for final ranking — the sidecar returns corrected OCR text; the Spring server does the catalog lookup.

**Threshold + Tie-Breaking**

Your measured mean similarity is 70.4/100, with the recoverable cases in the 70–85 range. Recommend:

- **`pg_trgm.similarity() ≥ 0.4`** as the candidate retrieval threshold (recall-oriented).
- **`rapidfuzz.fuzz.partial_ratio() ≥ 75`** as the acceptance threshold for automatic canonical name selection.
- **Tie-breaking:** if multiple candidates score ≥ 75, pick the **shortest** (tea names are typically concise) — or, if you have usage frequency data, the most frequent.

**Where to Run**

| Component | Location | Rationale |
|-----------|----------|-----------|
| Confusable normalization | **Sidecar (Python)** | Immediately after OCR, before returning to Spring |
| Candidate retrieval (`pg_trgm`) | **Postgres** | Uses GIN index, fast |
| Final ranking (`rapidfuzz`) | **Spring server** | Centralized business logic, can log/tune thresholds |

### 3. Gap Closure Estimate

**What Track 1 closes:** The 6 “garbled-but-recoverable” cases — specifically:
- All **homoglyph substitutions** on clean print (`Гунфу→Фyнфy` → `Гунфу`, `ХУН→XYH` → `ХУН`, etc.)
- **Case mismatches** (normalization handles this)
- **Word-order/space slips** where the catalog has the same tokens in a different order (`partial_ratio` catches these)

**Estimated improvement:** From 2/10 (20%) → **6–7/10 (60–70%)** strict capture. The confusable map alone addresses the dominant mechanical defect.

**What it does NOT fix:**
- **Handwriting** (#9 “Gaba Ladykiller”, #10 “Дун Фан Мэй Жэнь”) — the script itself is wrong (`Dyn.Pam MaiXan`), not just glyph substitution.
- **Complete OCR failures** where the engine outputs nothing or unrelated text.
- **Genuine Latin brand names** (we intentionally don't normalize those).

---

## Track 2 — Local Handwriting / Stylized Names (The Real Gap)

### 4. Preprocessing-Only Improvements

**Concrete OpenCV/Pillow Pipeline**

```python
import cv2
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

def preprocess_for_handwriting(image: np.ndarray) -> np.ndarray:
    # 1. Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 2. CLAHE (Contrast Limited Adaptive Histogram Equalization)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))
    enhanced = clahe.apply(gray)
    
    # 3. Adaptive binarization (OTSU + Gaussian)
    binary = cv2.adaptiveThreshold(enhanced, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                   cv2.THRESH_BINARY, 11, 2)
    
    # 4. Denoise (fastNlMeansDenoising)
    denoised = cv2.fastNlMeansDenoising(binary, h=10)
    
    # 5. Deskew (via Hough transform or minAreaRect)
    coords = np.column_stack(np.where(denoised > 0))
    if len(coords) > 0:
        angle = cv2.minAreaRect(coords)[-1]
        if angle < -45: angle = 90 + angle
        if abs(angle) > 0.5:
            (h, w) = denoised.shape[:2]
            center = (w // 2, h // 2)
            M = cv2.getRotationMatrix2D(center, angle, 1.0)
            denoised = cv2.warpAffine(denoised, M, (w, h), flags=cv2.INTER_CUBIC,
                                      borderMode=cv2.BORDER_REPLICATE)
    return denoised
```

**Safety / Conditional Application**

**Do not apply unconditionally** — your upscale lesson (#114) proves that aggressive preprocessing regresses good shots. Instead:

- **Condition:** apply only when the detector’s **confidence** (or character count) is below a threshold, **OR** when the user explicitly selects “handwritten” in the UI.
- **Alternative:** run the preprocessing pipeline in **parallel** with the standard pipeline on a separate thread; if the preprocessed result has higher confidence / more characters, use it; otherwise discard.

### 5. Local Handwriting/STR Models for Cyrillic

**TrOCR-base-handwritten-ru (ONNX)**

| Attribute | Detail |
|-----------|--------|
| **Model** | `kazars24/trocr-base-handwritten-ru` → ONNX version: `Mievst/trocr-base-handwritten-ru-ONNX` |
| **Training** | 73,830 Cyrillic handwriting segments (Russian) |
| **CER** | 4.85% (Character Error Rate) |
| **License** | MIT (Microsoft TrOCR base) |
| **Size** | ~1.1 GB (base model) |
| **CPU Latency** | ~200× slower than Tesseract on CPU — ~2–5 seconds per crop on 2 vCPU |
| **RAM** | ~1.5–2.0 GB peak |
| **ONNX** | Yes, via `optimum` conversion |

**PP-OCRv5 Server Rec (vs. Mobile)**

RapidOCR v3.5.0 supports PP-OCRv5 server models. The **server** rec model is larger and more accurate than mobile, particularly on challenging scenarios including handwriting. Available in ONNX format via ModelScope.

| Attribute | PP-OCRv5 Server Rec | PP-OCRv5 Mobile Rec (current) |
|-----------|---------------------|-------------------------------|
| **Size** | ~15–20 MB | ~5–8 MB |
| **Latency** | ~2–3× mobile | baseline |
| **Handwriting** | Improved (explicitly called out) | weak |
| **RAM** | +200 MB | baseline |
| **ONNX** | Yes | Yes |

**EasyOCR Cyrillic**

EasyOCR 1.6.2+ includes a dedicated `cyrillic_g2` model as the default for Cyrillic script. CPU-only mode available. However, EasyOCR is **not** ONNX-native and has higher RAM footprint (~1.5 GB).

**Verdict**

| Model | Better on Cursive? | Fits VM? | CPU-feasible? | Recommended? |
|-------|-------------------|----------|---------------|--------------|
| TrOCR-base-handwritten-ru | **Yes** (SOTA for handwriting) | Tight (~2 GB peak) | No (2–5s/crop) | ❌ Not for MVP |
| PP-OCRv5 Server Rec | Marginally | Yes (+200 MB) | Yes (~0.5s) | ✅ **Try first** |
| EasyOCR (cyrillic_g2) | Unknown | Tight | Moderate | ❌ Not ONNX |
| Current PP-OCRv5 Mobile | Baseline | Yes | Yes | baseline |

**Recommendation:** **Upgrade from PP-OCRv5 mobile rec to PP-OCRv5 server rec** as a low-risk, low-cost first step. It adds ~200 MB RAM and ~2–3× latency but explicitly targets handwriting. If that doesn't close the handwriting gap, handwriting is fundamentally hard for all local CPU OCR — the real solution is either TrOCR (too heavy) or an external API.

---

## Track 3 — Optional Opt-In AI-Vision Fallback

### 6. Yandex Vision OCR — Full Specification

**Endpoint**
```
POST https://ocr.api.cloud.yandex.net/ocr/v1/recognizeTextAsync
```


**Authentication**
- IAM token: `Authorization: Bearer <IAM_token>` + `x-folder-id: <folder_ID>`
- API key (simpler, non-expiring): `Authorization: Api-Key <API_key>`

**Request Body**
```json
{
  "mimeType": "image/jpeg",
  "languageCodes": ["ru"],
  "model": "page",          // or "handwritten" for cursive
  "content": "<base64>"
}
```


**Supported Languages**
All text models support Russian and English.

**Handwriting Model**
Explicit `model: "handwritten"` for cursive/mixed handwritten+typed text in Russian and English.

**Pricing** (as of 14 February 2024)

| Service | Price per unit | Price per 1,000 units |
|---------|---------------|----------------------|
| Printed text recognition | 0.13 ₽ | 130 ₽ |
| **Handwritten text recognition** | **1.50 ₽** | **1,500 ₽** |

One unit = one image recognition request.

**Data Retention / Privacy**

- Yandex **does not store** images or results by default.
- Images are used **only** for providing the service, not for model training.
- **Opt-in** for storage/training: the client may include a special parameter in the request body to consent to Yandex storing and using the image for debugging/model improvement.
- **You control this** — simply **do not** include the opt-in parameter, and no data is retained.

**Latency**
- Synchronous (sync) ~1–3 seconds per image.
- Asynchronous (`...Async`) recommended for production — returns an operation ID; poll for completion.

**Comparison to LLM Vision API**

| Aspect | Yandex Vision OCR | Yandex LLM Vision |
|--------|-------------------|-------------------|
| **Purpose** | Text extraction | Understanding/description |
| **Cost** | 0.13–1.50 ₽/image | Higher (token-based) |
| **Handwriting** | Dedicated model | May hallucinate |
| **Output** | Raw text + bounding boxes | Natural language |

**Egress/PII/GMS Implications**

- **Breaks no-egress** — the image leaves your VM and goes to Yandex Cloud.
- **No GMS required** — REST API, works on any Android (GMS-free).
- **No PII** — tea packaging images contain no personal data; still, you should not log image contents.

**Opt-In Implementation**
```kotlin
// Android: user taps "Use AI fallback" checkbox
val useAI = settings.getBoolean("ocr_ai_fallback_enabled", false)
if (useAI && localConfidence < THRESHOLD) {
    uploadToYandexVision(image)
} else {
    uploadToLocalOCR(image)
}
```

### 7. Go/No-Go Recommendation

**Is the AI-vision fallback worth it?**

For the **handful of handwritten cases** (#9, #10 in your test set):

| Option | Cost | Privacy | UX |
|--------|------|---------|-----|
| Yandex Vision (handwritten) | 1.50 ₽/image (~$0.018) | Opt-out (no retention) | Automatic |
| Manual entry | Free | Perfect | User types name |

**Recommendation:** **Defer AI-vision fallback to post-MVP.** Here's why:

1. **Manual entry is acceptable** for the MVP — the user already reviews/edits the name before saving. Handwritten cases are rare; typing a few characters is not a usability disaster.
2. **Track 1 + PP-OCRv5 server rec** will close ~70% of the gap without breaking no-egress.
3. If the handwriting gap remains after those two improvements, **re-evaluate** — the cost is low (1.50 ₽/handwritten image) and Yandex's data retention is privacy-respecting, but it does break no-egress for those specific requests.

---

## Final One-Paragraph Recommendation

**Do Track 1 now** — implement the confusable normalizer in the sidecar (using `homoglyphs` v2.0.3 + NFKC normalization) with the script-majority decision rule, and wire `pg_trgm` + `rapidfuzz.partial_ratio(≥75)` in the Spring server for catalog matching. This will recover the 6 garbled-but-recoverable cases, lifting strict capture from 20% to ~60–70%. **Concurrently, swap the PP-OCRv5 mobile rec for the PP-OCRv5 server rec** — it explicitly improves handwriting, adds only ~200 MB RAM, and fits your VM. **Do not** deploy the aggressive OpenCV preprocessing pipeline unconditionally; make it conditional on low confidence or user opt-in to avoid regressing good shots. **Defer the Yandex Vision fallback** — handwritten names are rare and manual entry is fine for the MVP; if the handwriting gap persists after Track 1 + server rec, re-evaluate with Yandex Vision's handwritten model (1.50 ₽/image, no data retention by default) as an explicitly opt-in per-photo fallback, but accept that it breaks no-egress for that subset.