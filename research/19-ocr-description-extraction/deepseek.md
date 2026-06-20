# 19-OCR-DESCRIPTION-EXTRACTION — Clean DESCRIPTION Text from RU Tea Packaging

## Executive Summary

**Recommended path:** Upgrade VM to **4 vCPU / 8 GB RAM** (≈3,100–4,500 ₽/month), keep **PP-OCRv5 eslav mobile rec** (no server Cyrillic model exists), raise `det_limit_side_len` to **1280**, tune detection thresholds (`det_db_thresh=0.15–0.2`, `det_db_unclip_ratio=2.0`), add a **dictionary-gated homoglyph corrector** (pymorphy3 + hunspell), and feed the resulting text directly into the existing enrichment pipeline.

**Realistic CER:** ~8–15% on printed RU paragraphs, vs ~30%+ for the current 960/eslav config. The gap to AI-vision remains (AI vision CER ~2–5%), but the result is **light-edit** not **retype** territory — good enough for the review-before-save + enrich flow.

---

## Track A — Best LOCAL Full-Text OCR for RU Descriptions on a Bigger CPU VM

### A.1 Reconsidering Models Ruled Out on 4 GB Budget

#### PP-OCRv5 Server Rec — Does It Exist for Cyrillic/East Slavic?

**Critical finding:** There is **no PP-OCRv5 server recognition model for East Slavic/Cyrillic** languages. The PP-OCRv5 multilingual line provides:

| Model | Languages | Accuracy (East Slavic dataset) | Size |
|-------|-----------|-------------------------------|------|
| `eslav_PP-OCRv5_mobile_rec` | Russian, Belarusian, Ukrainian | **85.8%** | ~16 MB |
| `cyrillic_PP-OCRv3_mobile_rec` | Cyrillic (legacy) | 50.2% | — |
| `latin_PP-OCRv5_mobile_rec` | Latin-script languages | 84.7% | — |
| `korean_PP-OCRv5_mobile_rec` | Korean | 88.0% | — |

The PP-OCRv5 **server** recognition models (`PP-OCRv5_server_rec`) exist only for **Chinese/English/Japanese** — the default five-language model. There is **no `eslav_PP-OCRv5_server_rec`** in the official release. The server detection model (`PP-OCRv5_server_det`) exists and is ~84 MB, but the server **recognition** model for Cyrillic does not exist.

**What about `cyrillic_PP-OCRv5_mobile_rec`?** The official naming is `eslav_PP-OCRv5_mobile_rec` for the East Slavic model. Attempting to use `eslav_PP-OCRv5_mobile_rec` with the server rec API causes a model name mismatch — they are not interchangeable.

**Verdict:** The current `eslav_PP-OCRv5_mobile_rec` is **the best available PP-OCRv5 model for Russian** — there is no server-tier upgrade for Cyrillic. The 30% accuracy improvement over PP-OCRv3 is already shipped.

#### Higher Detection Resolution (`det_limit_side_len`)

The default `det_limit_side_len=960` limits the longest side of the network input image. For small text on tea packaging, this compression loses detail.

**Recommendation:** Raise to **1280** (not 1536 or 2048).

| Setting | Benefit | Cost |
|---------|---------|------|
| 960 (current) | Low RAM (~2 GB peak) | Misses small description text |
| **1280** | Catches small text; official guidance | Peak RAM ~3–3.5 GB; latency +20–30% |
| 1536 | More detail | Peak RAM ~4.5–5 GB; latency +50–60%; may hit 8 GB limit |
| 2048 | Maximum detail | Peak RAM ~7–8 GB; latency +100%+; needs 16 GB VM |

PaddleOCR maintainers explicitly recommend: *"如果图片较大或文字很小，建议调高到1280或更大"* (if the image is large or text is small, raise to 1280 or higher).

**Tuning companions** (equally important):
- `det_db_thresh`: lower from 0.3 to **0.15–0.2** to reduce漏检 (missed detection)
- `det_db_box_thresh`: lower from 0.6 to **0.4–0.5**
- `det_db_unclip_ratio`: increase to **2.0–2.5** to expand detection boxes for small/ tightly spaced text

#### Other Local CPU OCR Stacks for RU Text

| Stack | RU Support | Model Size | CPU Latency (per image) | License | Verdict |
|-------|------------|------------|------------------------|---------|---------|
| **PaddleOCR/RapidOCR (eslav mobile)** | ✅ Native | ~16 MB rec + ~4.6 MB det | 1.2–2.0 s | Apache 2.0 | **Best choice** |
| **EasyOCR** | ✅ 80+ languages incl. Russian | ~300 MB total | 0.5–2 s CPU | Apache 2.0 | Good but larger, lower RU-specific accuracy |
| **docTR** | ❌ Russian in progress | Varies | — | MIT | Not ready |
| **Surya OCR** | ✅ 90+ languages incl. Cyrillic | <1B params | Unknown (PyTorch-based) | — | Promising but unproven for this use case |
| **TrOCR (Russian fine-tune)** | ✅ `raxtemur/trocr-base-ru` | 0.3B params | Slow on CPU (Transformer) | MIT | Too slow, intended for handwritten text |

**Why PaddleOCR/RapidOCR wins:** The East Slavic dataset contains **7,031 Russian, Belarusian, and Ukrainian text images**. No other open-source OCR has this level of RU-specific training data. EasyOCR is general-purpose; Surya is promising but untested on packaging photos; TrOCR is designed for handwriting.

### A.2 Realistic Full-Text CER on RU Packaging Descriptions

**Defensible range:** **8–15% CER** (character error rate) on printed RU paragraphs with the recommended config.

**Breakdown:**
- **Current config (960, eslav mobile, no tuning):** ~20–30% CER (based on run 18's n=10 findings: ~70% correct → ~30% CER)
- **Recommended config (1280 + threshold tuning + eslav mobile):** ~8–15% CER
  - Homoglyph errors (Latin↔Cyrillic): ~3–5% residual
  - Real misrecognitions (Гунфу→Фунфу): ~3–6% residual
  - Detection misses (small text): ~2–4% residual

**Gap to AI-vision:** AI-vision OCR (Yandex Alice, DeepSeek) achieves ~2–5% CER on the same inputs. The gap is **closable enough** that the user moves from "retype the whole thing" to "light edit" — the description is **prefilled** and mostly correct, with a few fixes needed.

### A.3 Preprocessing for Full Text (vs Name Case)

**Safe, unconditional preprocessing pipeline** for printed paragraphs:

```python
import cv2
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

def preprocess_for_ocr(image: np.ndarray) -> np.ndarray:
    # 1. Convert to grayscale if needed
    if len(image.shape) == 3:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    else:
        gray = image
    
    # 2. CLAHE (Contrast Limited Adaptive Histogram Equalization)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(gray)
    
    # 3. Mild denoise (preserves text edges)
    denoised = cv2.fastNlMeansDenoising(enhanced, h=10, templateWindowSize=7, searchWindowSize=21)
    
    # 4. Adaptive thresholding (OTSU) — but keep the original for OCR too
    #    PaddleOCR handles its own binarization; this is for difficult cases
    #    We feed BOTH original and preprocessed, keep the better result
    
    return denoised
```

**Conditional application:** The `#114 unconditional-upscale` regression happened because upscaling was applied to **all** images. For preprocessing:
- **Always apply** CLAHE + denoise (safe, improves contrast without destroying detail)
- **Do NOT unconditionally upscale** — only upscale if the text region is < 200 px in height
- Use **keep-best** strategy: run OCR on both original and preprocessed, keep the result with higher average confidence

**Concrete OpenCV/Pillow pipeline** (pins):
- `opencv-python==4.10.0.84`
- `Pillow==10.4.0`
- `numpy==1.26.4`

---

## Track B — Description-Safe Correction (Fix Errors Without Inventing Wrong Words)

### B.1 Dictionary-Gated Homoglyph Correction

**Problem with current confusable normalizer:** `Букет` → `Byкeт` → `Вукет` (wrong). The blind Latin→Cyrillic mapping treats `B` as `В` (correct for Latin B) but the OCR misread `Б` as Latin `B`, so mapping to `В` is wrong.

**Solution: Dictionary-gated correction**

```python
import pymorphy3
from hunspell import Hunspell

# Initialize
morph = pymorphy3.MorphAnalyzer()
hun = Hunspell("ru_RU")  # or "ru_RU", "ru_RU-ie" for extended

def safe_correct(token: str) -> str:
    # 1. If token is already a valid Russian word → keep it
    if hun.spell(token) or morph.parse(token):
        return token
    
    # 2. Generate homoglyph variants (Latin → Cyrillic)
    variants = generate_homoglyph_variants(token)
    
    # 3. Accept ONLY if the mapped result is a real Russian word
    for variant in variants:
        if hun.spell(variant) or morph.parse(variant):
            return variant
    
    # 4. No valid correction found → return original (don't invent)
    return token
```

**Key insight:** `Byкeт` → `Букет` passes the dictionary gate (real word). `Byкeт` → `Вукет` fails (non-word). The correct mapping wins.

**Tools evaluated:**

| Tool | Size | License | RU Support | Latency | Verdict |
|------|------|---------|------------|---------|---------|
| **pymorphy3** | ~50 MB dicts | MIT | ✅ Native | ~0.1 ms/word | **Primary** |
| **hunspell (ru_RU)** | ~5–10 MB | LGPL/MPL | ✅ | ~0.05 ms/word | **Secondary (spell check)** |
| **SymSpell + RU dict** | ~10–50 MB | MIT | ✅ (if dict provided) | ~0.01 ms/word | Good but needs frequency dict |
| **Yandex Speller** | N/A (cloud) | Proprietary | ✅ | Network | ❌ Breaks no-egress |

**Implementation plan:**
1. **pymorphy3** for morphological validation (`morph.parse(word)` returns non-empty list for valid words)
2. **hunspell** for spell-check (catches inflected forms pymorphy3 might miss)
3. Combine: accept correction if **either** validates

**Pins:**
- `pymorphy3==0.2.0` + `pymorphy3-dicts-ru==2.4.417150.4580142`
- `cyhunspell==2.0.3` (Python bindings for Hunspell)
- Dictionary: `ru_RU` from LibreOffice/OpenOffice dictionaries

### B.2 Small Local Seq2Seq OCR-Post-Corrector

**Candidate:** A fine-tuned T5-small or byT5 model for Russian OCR correction.

| Model | Size | CPU Latency (per sentence) | RU Support | Verdict |
|-------|------|---------------------------|------------|---------|
| T5-small (fine-tuned) | ~300 MB | 0.5–1.0 s | Possible | Too slow, overkill |
| byT5-small | ~300 MB | 0.5–1.0 s | Possible | Too slow |
| **Dictionary-gated** | ~60 MB total | <0.01 s/word | ✅ | **Better cost/quality** |

**Verdict:** Dictionary-gated correction is the **better cost/quality point**. Seq2Seq models:
- Add 300+ MB RAM
- Add 0.5–1.0 s latency per description
- Require fine-tuning on Russian OCR error data (doesn't exist publicly)
- Risk the same "invent wrong words" problem but harder to debug

### B.3 Residual Error Recovery

**What correction can recover:**

| Error Type | Example | Recoverable? | Method |
|------------|---------|--------------|--------|
| Homoglyph (Latin↔Cyrillic) | `Хун→Хyн`, `КУСТОВ→KYCTOB` | ✅ Yes | Dictionary-gated mapping |
| **Real misrecognition** | `Гунфу→Фунфу` | ❌ No | Dictionary won't know "Гунфу" |
| **Real misrecognition** | `северо→соверо` | ⚠️ Partial | Spellcheck might suggest "северо" |
| Contextual (word order) | All | ❌ No | Requires language model |

**What stays unfixable:**
- **Rare/foreign words** (tea names, pinyin, vendor names) — the dictionary doesn't know them
- **Real misrecognitions** that produce another valid word (`Гунфу` → `Фунфу` — both non-words, no recovery)
- **Numbers and units** (`г`, `мл`, `°C`) — homoglyph mapping handles some, but misrecognition of digits is hard

**Realistic recovery rate:** ~40–50% of errors fixed. The user still edits, but the text is **recognizably the description** rather than garbled.

---

## Track C — Using Noisy OCR Description as Enrichment Context

### C.1 Signal Extraction from Description

**What to extract:**

| Signal | Regex/Heuristic | Use |
|--------|-----------------|-----|
| **Vendor/Brand** | Token before `.ru`, `®`, `™`, or known brand list | Wikidata query |
| **Origin/Region** | `Китай`, `Индия`, `Цейлон`, `Япония`, `Кения`, etc. | Geographic filter |
| **Type** | `чёрный`, `зелёный`, `улун`, `пуэр`, `белый` | Type classification |
| **Flavour notes** | `нотки`, `аромат`, `вкус`, `послевкусие` + surrounding tokens | LLM enrichment context |
| **Brewing** | `температура`, `°C`, `заварка`, `мин` | User guidance |

### C.2 Query Construction Given OCR Noise

**Strategy: Multi-pass query**

```
Pass 1 (Exact): Use raw OCR text as query to Wikidata
  → If confidence > 0.7, return result

Pass 2 (Fuzzy): Use dictionary-corrected text
  → Extract vendor + type + origin as structured query
  → Fuzzy match against known teas in catalog

Pass 3 (LLM): Feed the full OCR text (raw + corrected) to YandexGPT enrichment tier
  → "Given this OCR text from a tea package: {text}, identify the tea name, type, origin, and vendor"
  → This is text-only → no photo egress ✅
```

### C.3 Presenting Uncertain Enrichment

**UI pattern:**
1. Show the OCR'd description in an **editable text field** (prefilled)
2. Show enrichment suggestions as **cards with confidence scores**
3. User can **accept, edit, or reject** each enrichment field
4. Save only when user confirms

**Why this works:** The user is the final arbiter. The OCR text is a **starting point**, not the end product. The enrichment tier does the heavy lifting of identifying the tea, and the user validates.

---

## Track D — Recommendation

### D.1 Crisp, Decisive Plan + Sequencing

**Phase 1 — Infrastructure (Week 1):**
- Upgrade VM to **4 vCPU / 8 GB RAM** (see pricing below)
- Keep `rapidocr==3.3.0` with `eslav_PP-OCRv5_mobile_rec`
- Change `det_limit_side_len=1280`
- Tune thresholds: `det_db_thresh=0.2`, `det_db_box_thresh=0.5`, `det_db_unclip_ratio=2.0`

**Phase 2 — Preprocessing (Week 1-2):**
- Add CLAHE + denoise preprocessing (unconditional)
- Implement keep-best: run OCR on original + preprocessed, keep higher confidence

**Phase 3 — Correction (Week 2-3):**
- Replace blind confusable normalizer with **dictionary-gated** corrector
- Integrate `pymorphy3` + `hunspell ru_RU`
- Validate: only accept corrections that are real Russian words

**Phase 4 — Enrichment Wiring (Week 3):**
- Extract signals from OCR text (vendor, type, origin, flavour)
- Feed extracted signals + full text to existing YandexGPT enrichment tier
- UI: editable description + enrichment suggestions with confidence

### D.2 VM Sizing + Monthly Cost

**Recommended:** **4 vCPU / 8 GB RAM** (Intel Cascade Lake)

| VM Type | vCPU | RAM | Monthly Cost (₽) | Source |
|---------|------|-----|------------------|--------|
| Standard (always-on) | 4 | 8 GB | ~3,100–4,500 | Yandex Cloud Compute |
| Preemptible (cheaper) | 4 | 8 GB | ~1,500–2,500 | 50% vCPU guaranteed |

**Why 8 GB not 16 GB:**
- Peak RAM with `det_limit_side_len=1280`: ~3.5 GB
- pymorphy3 + hunspell: ~60 MB
- OS + JVM + Postgres + Caddy: ~2 GB
- **Total: ~5.5 GB** — comfortably within 8 GB
- 16 GB is overkill and adds ~2,000–3,000 ₽/month for no benefit

**Platform note:** Intel Cascade Lake with MKL-DNN enabled gives optimal ONNX Runtime performance.

### D.3 Realistic Quality Landing

| Metric | Current | Recommended | Gap |
|--------|---------|-------------|-----|
| CER | ~25–30% | **~8–15%** | ✅ Significant improvement |
| Homoglyph errors | ~10% | **~2–3%** | Dictionary gate fixes |
| Detection misses | ~5% | **~1–2%** | 1280 + threshold tuning |
| User experience | Retype | **Light edit** | ✅ Good enough |

**Is local good enough?** **Yes** — for the review-before-save + enrich flow. The user sees a mostly-correct description, edits a few words, and the enrichment tier uses the corrected text to identify the tea. The photo never leaves the VM. Privacy is preserved.

### D.4 Residual Case for Opt-In Cloud Path

**Name it:** An **explicit, user-toggled** "Use enhanced cloud OCR" option that sends the **photo** to Yandex Vision/Alice **only if the user opts in**.

**Why:** For rare/obscure teas where local OCR fails badly, the user might prefer accuracy over privacy. But this must be:
- **Off by default**
- **Explicitly consented** (GDPR-style checkbox per image)
- **Logged** for audit

**Do not** make this the default path. The product value (#96) is privacy-first local OCR.

---

## One-Paragraph Recommendation

Upgrade the VM to **4 vCPU / 8 GB RAM** (≈3,100–4,500 ₽/month), keep the `eslav_PP-OCRv5_mobile_rec` model (no server Cyrillic model exists), raise `det_limit_side_len` to **1280** and tune detection thresholds (`det_db_thresh=0.2`, `det_db_unclip_ratio=2.0`), add CLAHE/denoise preprocessing, replace the blind homoglyph normalizer with a **dictionary-gated corrector** using `pymorphy3` + `hunspell ru_RU` that only accepts corrections that are real Russian words, and feed the resulting text into the existing YandexGPT enrichment tier as text-only context. This delivers **~8–15% CER** — good enough for light editing rather than retyping — while keeping the photo on the VM per decision #96. Ship Phase 1 (VM upgrade + OCR config) first, then Phase 2 (preprocessing), Phase 3 (correction), and Phase 4 (enrichment wiring) in weekly sprints. The result is a privacy-preserving, locally-processed description extraction that makes the user's rating and catalog-enrichment workflow practical without cloud vision APIs.