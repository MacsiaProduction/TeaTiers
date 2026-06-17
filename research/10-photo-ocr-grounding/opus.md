# OCR Implementation Path for TeaTiers (No‑GMS Android, Russia‑First)

## TL;DR
- **Ship the MVP with on-device OCR using Google ML Kit Text Recognition v2 in BUNDLED mode (`com.google.mlkit:text-recognition:16.0.1` + `text-recognition-chinese:16.0.1`), which runs fully on-device and does NOT require Google Play Services — verified against Google's own documentation — making it safe for RuStore/no-GMS devices, privacy-preserving (photo bytes never leave the device), and adding ~4 MB per script per ABI.** Keep ML Kit behind a thin `OcrEngine` interface so engines can be swapped later.
- **For the post-MVP upgrade, add an open-source, license-clean path using RapidOCR (Apache-2.0 ONNX port of PaddleOCR PP-OCRv5) for materially better Cyrillic and vertical/curved Chinese accuracy**, either on-device via ONNX Runtime Mobile or as an optional server-side resolve path. Avoid Surya (GPL-3.0 code + non-commercial CC-BY-NC-SA weights) and avoid the unbundled `com.google.android.gms:play-services-mlkit-*` variant (requires GMS, will fail on RuStore devices).
- **OCR output feeds the existing `sourceText` grounding path only after a mandatory user review/confirm step, with a length cap (~2,000 chars), sanitization, and prompt-injection guards; cleaned text is sent once to `/api/v1/teas/resolve` and not persisted server-side beyond the request.** Photo bytes stay on-device in all MVP and recommended post-MVP paths.

## Key Findings

### The no-GMS question (most critical decision)
- ML Kit text recognition ships in two delivery modes. The **bundled** artifacts live in the `com.google.mlkit` namespace and statically link the model into the APK; the **unbundled** artifacts live in `com.google.android.gms:play-services-mlkit-*` and download/run the model via Google Play Services.
- Google's official text-recognition Android doc (last updated 2026-05-29) describes bundled models as "statically linked to your app at build time" and "available immediately," versus unbundled models "dynamically downloaded via Google Play Services." Google's official ML Kit Android migration guide states verbatim: "ML Kit uses the com.google.android.gms namespace for libraries that depend on Google Play Services. Bundled models are delivered as part of your application. Thin models must be downloaded."
- **Verdict: Bundled ML Kit text recognition performs OCR on-device with no Google Play Services required at runtime.** Every observed runtime failure in the field ("Waiting for the text recognition model to be downloaded. Please wait"; "Failed to init thin text recognizer"; "Not allowed since feature flag for ML Kit is not enabled") is tied to the *unbundled/GMS* variant, not the bundled one. The bundled OCR module was, in fact, created in response to a developer request (googlesamples/mlkit issue #241) for a "text-recognition with bundled model which works offline without relying on play services."
- **Caveat (gating):** No primary source provides a clean success log on a fully de-Googled device, so a smoke test on a real RuStore/no-GMS (or microG) device is mandatory before shipping. Also ensure the build packages the bundled native model assets — a documented Xamarin failure (`zzm.zza … null object reference`) was a packaging issue, not a GMS requirement.

### Version, license, and size facts (verified against upstream)
- **Tesseract4Android (adaptech-cz):** Latest release **4.9.0**, June 11, 2025, which per the GitHub Releases page "Updated Tesseract to 5.5.1 · Updated libpng to 1.6.48 · Added helper method getConfidentText to get results filtered by confidence." License **Apache-2.0**; ~920 GitHub stars. Dependencies: `cz.adaptech.tesseract4android:tesseract4android:4.9.0` (Standard) or `:tesseract4android-openmp:4.9.0` (OpenMP). Pure on-device, no GMS. Requires tessdata files at runtime.
- **tessdata model sizes:** Three repos — `tessdata` (legacy + LSTM, largest), `tessdata_best` (~15 MB/language, most accurate, LSTM only), `tessdata_fast` (~5 MB/language, integerized LSTM, fastest, shipped in Debian/Ubuntu). TeaTiers would need rus, eng, chi_sim, chi_tra: roughly 20 MB (fast) to 60 MB (best) total. Only `--oem 1` (LSTM) works with fast/best files.
- **ML Kit Text Recognition v2 (bundled):** Current version **16.0.1** for all five script artifacts (Latin, Chinese, Devanagari, Japanese, Korean), last released Aug 2024, still latest as of June 2026. **Proprietary Google library — NOT open source.** Size: Google's docs state ~**4 MB per script per architecture** (bundled). Per Google's official ML Kit Release Notes, the unbundled variant provides "same functionalities … however backed by Google Play Services, with app size impact shrinked to only ~260 KB." Supports Latin, Chinese, Devanagari, Japanese, Korean — **no dedicated Cyrillic model** (Russian is read by the Latin recognizer, at reduced accuracy). Requires Android API 23+.
- **PaddleOCR / PP-OCRv5:** PaddleOCR 3.0 with PP-OCRv5 (2025), **Apache-2.0**. A single recognition model covers Simplified/Traditional Chinese, Pinyin, English, Japanese; separate language models include an **East Slavic (eslav) model covering Russian, Belarusian, Ukrainian** plus a Cyrillic dataset. PP-OCRv5 specifically upgraded vertical text, handwriting, and rare-character handling versus v4 (vendor-reported +13 points end-to-end on internal sets). Mobile variants exist (`PP-OCRv5_mobile_det`, `PP-OCRv5_mobile_rec`); the English mobile-rec model is ~9.6 MB. Requires the heavy PaddlePaddle runtime unless converted to ONNX. A newer PP-OCRv6 appears in the repo with tiny(1.5M)/small(7.7M)/medium(34.5M) tiers.
- **RapidOCR (RapidAI):** **Apache-2.0**, ONNX port of PaddleOCR models that removes the PaddlePaddle dependency (runs on ONNX Runtime / OpenVINO / MNN / PyTorch). Native bindings for Python, C++, Java, C#. The ONNX Runtime Mobile variant compresses models to under ~10 MB and targets ARM, with documented Android/iOS deployments. Default Chinese+English; other languages via converted PP-OCRv5 models (eslav for Russian). Community ONNX conversions show server detection det.onnx ~84 MB but mobile det far smaller, English rec.onnx ~7.5 MB, and per-language rec models in single-digit MB.
- **Surya OCR (datalab-to / VikParuchuri):** Current **0.17.0**; code **GPL-3.0-or-later**; model weights **CC-BY-NC-SA-4.0** (non-commercial). Per the project README the non-commercial restriction "is waived for any organization with less than $5M USD in gross revenue in the most recent 12-month period AND less than $5M in lifetime VC/angel funding raised … You also must not be competitive with the Datalab API." Supports 90+ languages incl. Russian and Chinese, but is **server-side only (PyTorch) and specialized for document OCR — explicitly "will likely not work on photos."** License + photo limitation make it unsuitable.
- **docTR (Mindee):** Apache-2.0, detection+recognition, TF/PyTorch backends — server-side. Viable as a server option but heavier and less tuned for Cyrillic/vertical-Chinese than PaddleOCR/RapidOCR.
- **Yandex Vision OCR (cloud):** REST/gRPC API, endpoint `POST https://ocr.<host>/ocr/v1/recognizeText`. Per Yandex Cloud's service page it "supports and automatically identifies 48 languages," and "allows you to work with JPEG, PNG, and PDF formats. File sizes should be no larger than 10 MB with no more than 200 pages per file." The English-Russian model is best for RU+EN. Critically, per Yandex's OCR concepts doc, "Only a single model is used each time you recognize a text. For example, if an image contains text in Chinese and Japanese, only one language will be recognized. To recognize both, send another request specifying the other language." Charged per analyzed image. **Cloud-only — sending photo bytes off-device conflicts with the local-first/privacy posture**; relevant only as an optional server path (you already use Yandex Cloud).

### Multilingual accuracy reality
- **Cyrillic (Russian):** ML Kit has no dedicated Cyrillic model (reads via Latin — degraded on Russian). PaddleOCR/RapidOCR have a dedicated East Slavic PP-OCRv5 model plus Cyrillic training data; Tesseract has a mature `rus.traineddata`. For Russian packaging, the PaddleOCR family and Tesseract are stronger than ML Kit.
- **Vertical / curved / glossy Chinese:** PP-OCRv5 specifically improved vertical, curved, and textured-background text — its strongest differentiator. ML Kit's Chinese model handles standard Chinese well but is weaker on vertical layouts; Tesseract is weakest on curved/vertical/reflective real-world photos.
- **Mixed Cyrillic/Latin/Hanzi on one label:** No single small model excels. ML Kit runs Latin and Chinese as separate recognizers; PaddleOCR needs per-script models; Yandex uses one model per request. Expect multiple passes or partial extraction — surface this in the review UI.
- **Low-light photos:** All engines degrade; preprocessing (contrast, deskew, PP-OCRv5 doc-unwarping/orientation) helps. Treat low confidence as a first-class outcome with a retake/retype fallback.
- **Benchmark caution:** Most comparative accuracy numbers (third-party "best OCR 2026" blog tests) are not reproducible/official; PaddleOCR's own figures are self-reported on internal datasets. Treat all as directional only.

### Comparison table

| Engine | Version (2026) | License | On-device? | Needs GMS? | Cyrillic | Vertical/curved Chinese | Size impact |
|---|---|---|---|---|---|---|---|
| **ML Kit bundled** | 16.0.1 | Proprietary (Google) | Yes | **No** | Weak (via Latin) | OK (not great vertical) | ~4 MB/script/ABI |
| **ML Kit unbundled** | 19.0.1 (Latin) | Proprietary | Yes (model via GMS) | **Yes** ❌ | Weak | OK | ~260 KB/script |
| **Tesseract4Android** | 4.9.0 (Tesseract 5.5.1) | Apache-2.0 | Yes | No | Good (`rus`) | Weak on photos | ~5–15 MB/lang |
| **PaddleOCR PP-OCRv5** | 3.0 / v5 | Apache-2.0 | Heavy (PaddlePaddle) | No | Strong (eslav) | Strong | mobile rec ~9.6 MB |
| **RapidOCR (ONNX)** | PP-OCRv5 models | Apache-2.0 | Yes (ONNX Runtime Mobile) | No | Strong (eslav) | Strong | <~10 MB mobile |
| **Surya** | 0.17.0 | GPL-3.0 + CC-BY-NC-SA weights ❌ | Server (PyTorch) | No | Yes | Yes (docs only) | large |
| **docTR** | current | Apache-2.0 | Server | No | Moderate | Moderate | large |
| **Yandex Vision OCR** | cloud API | Commercial cloud | No (cloud) ❌ | No | Strong (RU model) | One model/request | n/a |

## Details

### Recommended architecture
1. **Capture/import:** User photo copied into app-private storage (existing behavior). No new storage permission if using the system photo picker / SAF or CameraX writing to app-private dirs.
2. **OCR (on-device):** A background `OcrEngine.recognize(bitmap, scripts)` call (Coroutines on `Dispatchers.Default`, or WorkManager for batch/retry) runs ML Kit bundled recognizers (Latin + Chinese for MVP). Output: text blocks + per-element confidence.
3. **Post-process:** Merge blocks, strip control/zero-width chars, normalize Unicode (NFC), collapse whitespace, drop low-confidence elements, cap to ~2,000 chars, language-tag.
4. **Review/confirm UI:** Show extracted text in an editable field. User edits/approves before submission. Nothing auto-submits.
5. **Grounding:** On confirm, send cleaned text as `sourceText` to `/api/v1/teas/resolve`. Backend enrichment flows Wikidata first, then Yandex Cloud Foundation Models. Photo bytes never leave the device.
6. **Fallback:** If confidence is low or text is empty, prompt retake/retype; never silently submit garbage.

### Integration details (top 2 choices)

**Choice 1 — ML Kit bundled (MVP):**
```gradle
android { defaultConfig { minSdk = 23 } }   // ML Kit requires API 23+
dependencies {
    implementation 'com.google.mlkit:text-recognition:16.0.1'          // Latin (English, Russian-via-Latin)
    implementation 'com.google.mlkit:text-recognition-chinese:16.0.1'  // Chinese
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2'
}
```
- Recognizers: `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` (Latin) and `TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())` (Chinese).
- Input: `InputImage.fromBitmap(bitmap, rotationDegrees)` — always pass correct rotation. Call `recognizer.close()` to avoid leaks. No GMS, no model download step, immediate availability.
- Permissions: CameraX camera permission if capturing; none extra if importing via photo picker.

**Choice 2 — RapidOCR ONNX (post-MVP upgrade):**
- ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android`) + PP-OCRv5 mobile det + eslav rec (Russian) and chinese rec models converted to ONNX (Apache-2.0). Download models on first use (not bundled) to keep base APK small; verify SHA-256 of downloaded models.
- Wrap behind the same `OcrEngine` interface so it is a drop-in alternative/supplement to ML Kit. Run inference on a background dispatcher; consider PP-OCRv5 doc-orientation/unwarping pre-steps for curved/glossy labels.

### MVP vs post-MVP
- **MVP:** ML Kit bundled Latin + Chinese. Smallest effort, no GMS, fully offline. Accept weaker Russian for v1; if Russian quality is unacceptable in testing, add Tesseract `rus` as an interim Cyrillic pass.
- **Post-MVP option A (on-device upgrade):** RapidOCR (ONNX Runtime Mobile) with PP-OCRv5 eslav + chinese mobile models behind the same interface — best Cyrillic and vertical Chinese; Apache-2.0; no GMS.
- **Post-MVP option B (server-side, opt-in only):** "Improve recognition" path sending the image to a backend running RapidOCR/PaddleOCR self-hosted, or Yandex Vision OCR. Must be explicit opt-in because it breaks the photo-stays-on-device guarantee.

### Security / privacy / copyright checklist
- Photo bytes never leave device in MVP and recommended post-MVP (option A).
- Mandatory human review before submission; no auto-submit.
- `sourceText` length cap (~2,000 chars) + sanitization (strip control/zero-width chars, NFC-normalize Unicode, remove markup).
- Prompt-injection guards: treat OCR text as untrusted *data*, never instructions; wrap `sourceText` in clear delimiters in the backend prompt and instruct the model to treat it as quoted evidence only; strip imperative injection patterns.
- Copyright: packaging blurbs may be copyrighted — use OCR text only as factual grounding, cap length, prefer extracting structured facts over storing raw passages, do not republish verbatim.
- Raw OCR text: send once with the resolve request; do not persist server-side beyond request handling; on device, store only user-confirmed text (or nothing).
- No new dangerous permissions: CameraX + system photo picker; avoid broad storage permission.

### Test plan
- **Unit:** post-processing (sanitization, length cap, confidence filtering, Unicode normalization).
- **Engine contract tests** against `OcrEngine` so ML Kit and RapidOCR are interchangeable.
- **Gating device test:** at least one real no-GMS/RuStore device (or de-Googled/microG device) confirming bundled ML Kit initializes and recognizes without GMS.
- **Corpus:** curved/small packages, glossy/reflective labels, vertical Chinese, mixed Cyrillic+Latin+Hanzi, low-light. Record CER/precision qualitatively per engine.
- **Confidence threshold tuning** + low-confidence fallback UX.
- **Prompt-injection red-team:** feed labels containing "ignore previous instructions"-style text; confirm the backend treats it as inert data.

### "Do not do" list
- Do not use unbundled `com.google.android.gms:play-services-mlkit-text-recognition` — it requires GMS and fails on RuStore/no-GMS devices.
- Do not use Surya in the product (GPL-3.0 code + non-commercial CC-BY-NC-SA weights + "not competitive with Datalab" clause + not designed for photos).
- Do not auto-submit OCR text without user review.
- Do not send photo bytes to any server without explicit opt-in.
- Do not store long verbatim copyrighted packaging text.
- Do not feed OCR text into prompts as trusted instructions.
- Do not ship the full PaddlePaddle runtime on-device — use ONNX (RapidOCR) if going on-device.
- Do not assume ML Kit reads Russian well — it has no Cyrillic model.

## Recommendations
1. **Now (MVP):** Implement the `OcrEngine` interface; integrate ML Kit bundled `text-recognition:16.0.1` + `text-recognition-chinese:16.0.1`. Build the review/confirm UI and the sanitization/length-cap/injection-guard layer. Wire confirmed text to `/api/v1/teas/resolve`.
2. **Gating test:** Validate bundled ML Kit on a real no-GMS RuStore device before committing. **Threshold that changes the plan:** if it fails to initialize without GMS, switch the MVP engine to Tesseract4Android 4.9.0 (rus+eng+chi_sim+chi_tra, tessdata_fast) or RapidOCR ONNX.
3. **If Russian quality is poor in MVP testing:** add Tesseract `rus` as an interim Cyrillic pass, or jump straight to post-MVP option A (RapidOCR + PP-OCRv5 eslav). Threshold: if Russian-label CER is unacceptably high in your test corpus.
4. **Post-MVP:** Add RapidOCR (ONNX Runtime Mobile, PP-OCRv5 eslav + chinese mobile models) behind the same interface, models downloaded on first use, for best Cyrillic + vertical Chinese.
5. **Only if on-device is insufficient:** offer explicit opt-in server-side OCR (self-hosted RapidOCR/PaddleOCR, or Yandex Vision OCR) — never default-on, given the privacy posture.

## Caveats
- Vendor benchmark/accuracy claims are largely self-reported or from non-reproducible blog tests; treat as directional.
- The "bundled ML Kit works with zero GMS" conclusion rests on Google's architecture docs and the absence of GMS dependency in the `com.google.mlkit` namespace, not on a published de-Googled-device success log — hence the mandatory smoke test.
- ML Kit 16.0.1 has been stable since Aug 2024; confirm no newer version at integration time.
- ONNX model sizes vary by conversion source; verify exact mobile det/rec sizes against upstream before bundling/downloading.
- Mixed-script single-label OCR is unsolved at small model scale; set user expectations and allow manual editing.
- Yandex Vision OCR's single-model-per-request behavior means mixed Cyrillic+Hanzi labels need multiple billed requests — a cost and latency consideration if you ever use the server path.