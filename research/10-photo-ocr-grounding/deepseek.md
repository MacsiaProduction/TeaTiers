# OCR for TeaTiers: Comprehensive Technology Evaluation & Implementation Roadmap

## Executive Summary

For TeaTiers — a local-first Android app targeting RuStore with no Google Play Services dependency — **PaddleOCR with PP-OCRv6 Tiny/Small on-device** is the recommended MVP choice. It delivers the best balance of accuracy (especially for mixed Chinese/Russian/English), model size (as small as 1.5MB), Apache 2.0 licensing, and complete offline operation. ML Kit is disqualified due to Google Play Services dependency. Tesseract is a viable open-source fallback but underperforms on challenging real-world packaging photos.

---

## 1. Comparison Table: OCR Options for TeaTiers

| **Dimension** | **Tesseract (tess-two / Tesseract4Android)** | **PaddleOCR (PP-OCRv6)** | **ML Kit Bundled** | **ML Kit Play Services** | **EasyOCR (via ONNX)** |
|---------------|---------------------------------------------|--------------------------|-------------------|--------------------------|------------------------|
| **License** | Apache 2.0 | Apache 2.0 | Proprietary (Google) | Proprietary (Google) | Apache 2.0 |
| **Open Source** | ✅ Yes | ✅ Yes | ❌ No | ❌ No | ✅ Yes |
| **Google Play Services Required** | ❌ No | ❌ No | ❌ No | ✅ Yes | ❌ No |
| **Model Size** | ~8-15MB per language | 1.5MB (Tiny) – 7.7MB (Small) | ~4MB/script/arch | ~260KB/script/arch | ~15MB+ |
| **Total APK Impact** | 25MB+ (multiple langs) | 1.5-14.6MB | 4MB × scripts × ABIs | ~260KB × scripts × ABIs | 15MB+ |
| **Languages Supported** | 100+ | 100+ (PP-OCRv5: 106 langs incl. Russian) | Latin + Chinese + Japanese + Korean + Devanagari | Same as bundled | 80+ |
| **Russian Support** | ✅ Yes (rus.traineddata) | ✅ Yes (ru) | ✅ Yes (Latin script) | ✅ Yes | ✅ Yes |
| **Chinese Support** | ✅ Yes (chi_sim/chi_tra) | ✅ Yes (excellent) | ✅ Yes (dedicated model) | ✅ Yes | ✅ Yes |
| **Mixed Script (Cyrillic+Latin+Hanzi)** | Poor (separate models needed) | ✅ Excellent (unified model) | ⚠️ Requires multiple models | ⚠️ Requires multiple models | ⚠️ Requires multiple models |
| **Reported Chinese Accuracy** | ~87-92% | 94-98% | ~92% | ~92% | Moderate |
| **Curved/Small Text** | Poor (<75% on tilted) | Good (88.7% curved) | Moderate | Moderate | Moderate |
| **On-Device Inference** | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes (via Play Services) | ✅ Yes |
| **Model Download** | Bundled in APK or runtime | Bundled in APK or runtime | Bundled in APK | Dynamic via Play Services | Bundled in APK |
| **Integration Complexity** | High (JNI, NDK) | Medium (Paddle Lite) | Low (Gradle dependency) | Low | High (ONNX conversion) |
| **Maintained Android Bindings** | ✅ Tesseract4Android v4.9.0 | ✅ Yes (Paddle Lite) | ✅ Yes | ✅ Yes | ⚠️ Community/experimental |
| **Current Version** | Tesseract 5.5.1 | PP-OCRv6 (2026) | text-recognition:16.0.1 | play-services:19.0.1 | 1.7.1 |

---

## 2. Detailed Option Analysis

### 2.1 Tesseract / Tesseract4Android

**Overview:** The classic open-source OCR engine, now maintained via the Tesseract4Android fork. Uses LSTM neural networks since v5.x.

**Pros:**
- Truly open source (Apache 2.0)
- No Google Play Services dependency
- Mature, well-understood codebase
- 100+ languages with community-trained models

**Cons:**
- **Model bloat**: Each language traineddata is 8-15MB; Russian + Chinese + English would push 25MB+
- **Poor on challenging inputs**: Curved text 52.1% vs PaddleOCR 88.7%; tilted text <75%
- **Mixed-script weakness**: Cannot handle Cyrillic+Latin+Hanzi in one pass efficiently
- **Integration complexity**: JNI bindings, NDK compilation, manual model file management
- **Slower**: 320-450ms per image

**Verdict:** Viable fallback if PaddleOCR proves problematic, but not recommended as primary.

---

### 2.2 PaddleOCR (PP-OCRv6)

**Overview:** Baidu's open-source OCR toolkit, purpose-built for mobile deployment with Paddle Lite. PP-OCRv6 (2026) offers three-tier models: Tiny (1.5M), Small (7.7M), Medium (34.5M).

**Pros:**
- **Apache 2.0 licensed** — fully open source
- **No Google Play Services** — pure offline
- **Tiny model size**: 1.5MB for PP-OCRv6 Tiny, 14.6MB for PP-OCRv4 full pipeline
- **Superior mixed-script support**: Unified model handles Chinese, English, and 46 Latin-script languages including Russian
- **Excellent accuracy**: 94.3%+ Chinese, 88.7% on curved text vs Tesseract's 52.1%
- **Fast**: 50-150ms on modern devices
- **80-106 languages** supported
- **Active development**: PP-OCRv6 released June 2026

**Cons:**
- More complex integration than ML Kit (Paddle Lite + NDK)
- Need to bundle model files or download at runtime

**Verdict:** **BEST CHOICE** for TeaTiers. Use PP-OCRv6 Tiny (1.5MB) for MVP, upgrade to Small (7.7MB) post-MVP if accuracy needs improve.

---

### 2.3 Google ML Kit

**Overview:** Google's mobile ML SDK with text recognition APIs.

**Pros:**
- Easy integration (Gradle dependencies)
- Fast (150-190ms)
- Good Latin script recognition
- Bundled mode avoids Play Services for the model itself

**Cons:**
- **Proprietary/closed source** — no source code access
- **Bundled mode still requires Google libraries** — the SDK itself (`com.google.mlkit:*`) comes from Google's Maven repo
- **Mixed-script weakness**: Requires separate Latin + Chinese models; cannot handle Cyrillic+Latin+Hanzi in one unified pass
- **Russian is only supported via Latin script** — Cyrillic recognition is not guaranteed
- **Model per script per ABI**: ~4MB per script per architecture bundled
- **Vendor lock-in**

**Verdict:** **DISQUALIFIED** — violates no-GMS requirement and is not open source.

---

### 2.4 EasyOCR (via ONNX Runtime)

**Overview:** PyTorch-based OCR with CRAFT detector + CRNN recognizer.

**Pros:**
- Apache 2.0 licensed
- 80+ languages
- No Google Play Services

**Cons:**
- **Mobile deployment is experimental**: Requires PyTorch→ONNX→TensorFlow→TFLite conversion
- **No official Android SDK** — community ports only
- **Larger model size**: ~15MB+
- **Slower**: 210-260ms
- **Less accurate than PaddleOCR** on real-world documents

**Verdict:** Not production-ready for Android. Avoid.

---

## 3. Recommended MVP and Post-MVP Approach

### MVP: PaddleOCR PP-OCRv6 Tiny (On-Device)

| **Decision** | **Choice** | **Rationale** |
|--------------|------------|---------------|
| **OCR Engine** | PaddleOCR (PP-OCRv6 Tiny) | 1.5MB model, Apache 2.0, no GMS |
| **Deployment** | On-device via Paddle Lite | Privacy-first, no backend costs, no photo egress |
| **Model Sourcing** | Bundled in APK `assets/` | No runtime downloads, works offline |
| **Language Model** | PP-OCRv6 unified multilingual | Handles Russian+English+Chinese mixed text |
| **OCR Trigger** | User-initiated (button) | Not automatic — user controls privacy |
| **Output Flow** | Review → Edit → Submit | User confirms before sending to backend |

### Post-MVP Upgrade Path

1. **PP-OCRv6 Small (7.7MB)** — if Tiny accuracy insufficient for packaging text
2. **Image pre-processing** — OpenCV adaptive thresholding, perspective correction
3. **Confidence filtering** — Only send text blocks above confidence threshold
4. **Language-specific model** — If unified model struggles, consider dedicated Russian or Chinese models
5. **Batch processing** — If user takes multiple photos, process in background with WorkManager

---

## 4. App/Backend Architecture Sketch

### 4.1 On-Device OCR Flow

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  User takes     │     │  Image          │     │  PP-OCRv6       │
│  photo of tea   │────▶│  Pre-processing │────▶│  Tiny (on-     │
│  packaging      │     │  (optional)     │     │  device)        │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                                        │
                                                        ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Submit to      │     │  User edits     │     │  OCR result     │
│  backend        │◀────│  text in UI     │◀────│  displayed      │
│  /resolve       │     │                 │     │  for review     │
└─────────────────┘     └─────────────────┘     └─────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│  Backend: sanitize → length cap → injection guard → Wikidata  │
│  → Yandex Foundation Models → structured flavor profile       │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 Integration Sketch — PaddleOCR (Primary)

**Gradle dependencies (app/build.gradle):**
```gradle
dependencies {
    // Paddle Lite inference engine
    implementation 'com.baidu.paddle:paddle-lite-mobile:2.12+'
    // Alternatively, use prebuilt AAR from PaddleOCR releases
    implementation 'com.baidu.paddle:lite_ocr_all:0.0.1'  // Check latest
}
```

**Note:** Exact Maven coordinates vary. The recommended approach is building from the official PaddleOCR Android demo or using prebuilt AAR from the Paddle-Lite repository.

**Model files placement:**
```
app/src/main/assets/
├── ppocrv6_tiny_det/      # Detection model (~0.5MB)
├── ppocrv6_tiny_rec/      # Recognition model (~1.0MB)
└── ppocrv6_tiny_cls/      # Direction classifier (~0.1MB)
```

**Core OCR code (Kotlin):**
```kotlin
class TeaPhotoOCRService(private val context: Context) {
    
    private lateinit var ocrPredictor: OCRPredictor
    
    fun init() {
        // Load models from assets
        val config = OCRConfig.Builder()
            .setDetModelPath("assets/ppocrv6_tiny_det")
            .setRecModelPath("assets/ppocrv6_tiny_rec")
            .setClsModelPath("assets/ppocrv6_tiny_cls")
            .setUseOpenCL(true)  // GPU acceleration if available
            .build()
        ocrPredictor = OCRPredictor(config)
    }
    
    suspend fun extractText(bitmap: Bitmap): OcrResult {
        return withContext(Dispatchers.IO) {
            // Pre-process: resize to reasonable dimensions
            val processed = preprocessImage(bitmap)
            ocrPredictor.detect(processed)
        }
    }
    
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        // Optional: OpenCV for contrast enhancement, deskew
        // Keep under 1280x720 for performance
        return bitmap
    }
}
```

### 4.3 Integration Sketch — Tesseract4Android (Fallback)

**Gradle (settings.gradle):**
```gradle
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

**Gradle (app/build.gradle):**
```gradle
dependencies {
    // Standard (single-threaded)
    implementation 'cz.adaptech.tesseract4android:tesseract4android:4.9.0'
    // OR OpenMP (multi-threaded)
    implementation 'cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0'
}
```

**Model files:**
```
app/src/main/assets/tessdata/
├── eng.traineddata      # English (~8MB)
├── rus.traineddata      # Russian (~10MB)
└── chi_sim.traineddata  # Simplified Chinese (~12MB)
```

**Core code:**
```kotlin
class TesseractOCRService(private val context: Context) {
    private lateinit var tessApi: TessBaseAPI
    
    fun init() {
        // Copy models from assets to internal storage
        val dataPath = File(context.filesDir, "tesseract").absolutePath
        tessApi = TessBaseAPI()
        // Multiple languages: English + Russian + Chinese
        tessApi.init(dataPath, "eng+rus+chi_sim")
    }
    
    suspend fun extractText(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            tessApi.setImage(bitmap)
            tessApi.utf8Text
        }
    }
}
```

---

## 5. Security/Privacy/Copyright Checklist

### 5.1 Privacy (Photo Data)

| **Requirement** | **Status** | **Implementation** |
|-----------------|------------|-------------------|
| Photos never leave device | ✅ | OCR runs entirely on-device |
| No photo upload to backend | ✅ | Only structured text sent to `/resolve` |
| No photo stored in cloud | ✅ | Local storage only (app-private) |
| User controls when OCR runs | ✅ | Button-triggered, not automatic |

### 5.2 Copyright (Vendor Text)

| **Requirement** | **Status** | **Implementation** |
|-----------------|------------|-------------------|
| Raw vendor prose not republished | ✅ | Only derived structured data sent |
| Raw OCR text not stored long-term | ✅ | Discard after enrichment; optional local cache |
| Length cap on sourceText | ✅ | Cap at ~500 chars before backend send |
| Prompt injection guards | ✅ | Sanitize before sending to backend |
| No web crawling | ✅ | OCR only, no external fetch |

### 5.3 Licensing

| **Component** | **License** | **Compatibility** |
|---------------|-------------|-------------------|
| PaddleOCR | Apache 2.0 | ✅ Commercial use OK |
| Paddle Lite | Apache 2.0 | ✅ |
| Tesseract | Apache 2.0 | ✅ |
| Leptonica | BSD-like | ✅ |
| ML Kit | Proprietary | ❌ Not open source |

### 5.4 Data Flow (What Leaves Device)

```
┌────────────────────────────────────────────────────────────────────┐
│                    DATA THAT LEAVES DEVICE                        │
├────────────────────────────────────────────────────────────────────┤
│  • Sanitized OCR text (capped at ~500 chars)                     │
│  • Tea name (from user)                                          │
│  • No raw photo bytes                                            │
│  • No raw vendor prose (only derived structured data)            │
│  • No device identifiers beyond standard HTTP headers            │
└────────────────────────────────────────────────────────────────────┘
```

---

## 6. Test Plan: 15-20 Photo Scenarios

### Russian Tea Packaging (5-7 scenarios)

| # | Scenario | Expected Challenge | Success Criterion |
|---|----------|-------------------|-------------------|
| 1 | Clear Cyrillic text on flat label | Baseline | >90% chars correct |
| 2 | Cyrillic on curved tin/cylinder | Curved text | >80% chars correct |
| 3 | Cyrillic on glossy/mylar bag | Glare | >75% chars correct |
| 4 | Small-print ingredients (Cyrillic) | Small font | >70% chars correct |
| 5 | Mixed Cyrillic + English (bilingual label) | Mixed script | Both recognized |
| 6 | Low-light phone photo (indoor) | Low contrast | >70% chars correct |
| 7 | Blurry/motion-blurred photo | Blur | Graceful degradation |

### English Tea Packaging (3-5 scenarios)

| # | Scenario | Expected Challenge | Success Criterion |
|---|----------|-------------------|-------------------|
| 8 | Clear English on flat box | Baseline | >95% chars correct |
| 9 | English on curved tin | Curved | >85% chars correct |
| 10 | Fancy/serif font on premium package | Font variation | >80% chars correct |
| 11 | English with decorative elements | Background noise | >80% chars correct |

### Chinese Tea Packaging (3-5 scenarios)

| # | Scenario | Expected Challenge | Success Criterion |
|---|----------|-------------------|-------------------|
| 12 | Clear Simplified Chinese on flat package | Baseline | >90% chars correct |
| 13 | Vertical Chinese text (traditional layout) | Orientation | >80% chars correct |
| 14 | Mixed Chinese + English (export packaging) | Mixed script | Both recognized |
| 15 | Small-print Chinese (ingredients) | Small font | >70% chars correct |
| 16 | Calligraphic/stylized Chinese | Font variation | >60% chars correct |

### Mixed/Edge Cases (3-5 scenarios)

| # | Scenario | Expected Challenge | Success Criterion |
|---|----------|-------------------|-------------------|
| 17 | All three scripts on one package | Mixed Cyrillic+Latin+Hanzi | All scripts recognized |
| 18 | Very low resolution (resized photo) | Resolution | Graceful degradation |
| 19 | Text at angle (not level) | Rotation | >70% chars correct |
| 20 | Packaging with reflective/metallic surface | Reflection | >60% chars correct |

---

## 7. "Do Not Do" List

### ❌ Do Not:
1. **Do not send photo bytes to any backend** — violates privacy promise; keep OCR on-device
2. **Do not use ML Kit** — requires Google Play Services and is not open source
3. **Do not bundle all Tesseract language models** — would bloat APK to 30MB+
4. **Do not auto-submit OCR results** — always show user review/confirmation first
5. **Do not store raw OCR text permanently** — discard after enrichment; only keep derived structured data
6. **Do not skip sanitization** — OCR output can contain injection attempts
7. **Do not run OCR on main thread** — use `Dispatchers.IO` or background thread
8. **Do not ignore confidence scores** — low-confidence text should be flagged for user review
9. **Do not assume one language model works for all** — test Russian, English, Chinese separately
10. **Do not use EasyOCR** — not production-ready for Android
11. **Do not forget model licensing** — verify each model file's license before bundling
12. **Do not implement automatic background OCR** — user must explicitly trigger

---

## 8. Version Pinning & Verification Required

| **Component** | **Claimed Version** | **Verification Needed** | **Source** |
|---------------|---------------------|------------------------|------------|
| Tesseract4Android | 4.9.0 | Check JitPack for latest | |
| Tesseract OCR | 5.5.1 | Verify upstream release | |
| PP-OCRv6 Tiny | 1.5M params | Download from HuggingFace | |
| PP-OCRv6 Small | 7.7M params | Download from HuggingFace | |
| Paddle Lite | 2.12+ | Check official releases | |
| ML Kit text-recognition | 16.0.1 | Check Maven Central | |
| ML Kit Play Services | 19.0.1 | Check Maven Central | |

**Critical pre-coding actions:**
- [ ] Download and verify PP-OCRv6 model files from HuggingFace/PaddleOCR releases
- [ ] Test Paddle Lite Android demo app on target devices
- [ ] Verify Apache 2.0 license text in all distributed binaries
- [ ] Confirm no GPL-licensed components are inadvertently included
- [ ] Test on RuStore devices (no Google Play Services)