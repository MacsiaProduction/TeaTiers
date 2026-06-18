# TeaTiers Slice 1b: Verified Implementation of a Russian+English PP-OCRv5 OCR Sidecar

## TL;DR
- **Ship RapidOCR (Python `rapidocr==3.8.4`) on ONNX Runtime CPU with the official PaddleOCR PP-OCRv5 mobile detector + the eslav_PP-OCRv5 mobile recognizer, all baked into the image by SHA.** The eslav ONNX that RapidOCR ships is a conversion of the *identical* official Apache-2.0 PaddlePaddle weights (the Paddle `inference.pdiparams` SHA256 `f11057b0…91c32b` matches byte-for-byte between RapidOCR's `default_models.yaml` and the official Hugging Face file), so you get a provenance-clean Cyrillic path without hand-running paddle2onnx — though doing your own paddle2onnx conversion and pinning your own SHA remains the gold-standard fallback.
- **It fits 4 GB** with concurrency capped at 1, mobile models, `intra_op_num_threads=2`/`inter_op_num_threads=1`/sequential execution, load-once + warmup, and a container `mem_limit` of ~900 MB–1.2 GB. Estimated per-image latency on 2 vCPU is roughly 0.5–2 s; resident set during inference is in the low-to-mid hundreds of MB. A bump to 8 GB is **not** required; if you ever measure OOM, the 4→8 GB delta on Yandex Cloud is roughly **700–950 ₽/month** (see Caveats for the pricing-vintage conflict).
- **Accuracy will be good but not flawless on tea packaging.** The eslav recognizer scores exactly **81.6%** *whole-line* accuracy on PaddleOCR's East-Slavic set (one wrong char — including punctuation — fails the whole line), implying a substantially lower per-character error rate. Expect the real failure modes to be curved/reflective foil and tiny low-contrast print, not the script itself. Keep Tesseract `rus+eng` only as a provenance-bulletproof measuring stick, not the primary engine.

## Key Findings

1. **The provenance dilemma is a false binary.** The official `PaddlePaddle/eslav_PP-OCRv5_mobile_rec` weights are genuinely Apache-2.0 and hosted on Hugging Face; PaddleOCR ships them only in Paddle (`.pdiparams`/`inference.json`) format, with no official ONNX release. But the RapidOCR project's `eslav_PP-OCRv5_rec_mobile.onnx` is a conversion of those *exact* weights — the Paddle source-file SHA256 in RapidOCR's `default_models.yaml` (`f11057b05d8517868bca505271278973d706600d9dcc184cbcf5c4512091c32b`) is identical to the SHA256 of the official Hugging Face `inference.pdiparams`. So you are not choosing between "thin-provenance blob" and "switch engines": you can re-host/re-verify RapidOCR's ONNX, *or* run paddle2onnx yourself on the official `.pdiparams` to mint your own ONNX + SHA.
2. **Detection is language-agnostic; the angle classifier is optional.** `PP-OCRv5_mobile_det` is a single multilingual text detector (Chinese/English/Japanese, plus curved/rotated/handwritten), shared across all recognition languages. The text-line orientation classifier (cls) only matters when text can appear upside-down (180°); for hand-held packaging photos it is usually unnecessary and adds load.
3. **The eslav training set is small — exactly 7,031 images — but it's a fine-tune on the multilingual base, not a from-scratch model.** Per PaddleOCR's docs: "East Slavic Language Dataset: The latest PP-OCRv5 dataset containing a total of 7,031 text images in Russian, Belarusian, and Ukrainian." Its 81.6% whole-line score is respectable for scene text. For mixed Cyrillic+Latin labels the eslav dictionary includes Latin characters, so it reads "Greenfield" and "Чай" on one label.
4. **Tesseract `rus+eng` is the conservative, provenance-perfect fallback** (tessdata Apache-2.0) but is documented to degrade badly on curved text, perspective distortion, glossy photos and scene text — exactly the tea-packaging case. Use it to benchmark CER, not as the default.
5. **It fits 4 GB.** ONNX Runtime CPU with a single loaded pipeline, thread caps and concurrency=1 keeps resident memory in the low hundreds of MB, leaving headroom alongside the JVM, Postgres and Caddy.

## Details

### Q1 — Models: exact artifacts, sources, licenses, sizes, pins

**Recommended pipeline (all Apache-2.0, all PP-OCRv5 mobile):**

| Stage | Artifact | Source (recommended) | License | Size | Pin (SHA256) | ru+en fit |
|---|---|---|---|---|---|---|
| **Detection** | `ch_PP-OCRv5_det_mobile.onnx` (= PP-OCRv5_mobile_det) | RapidOCR ModelScope `RapidAI/RapidOCR` v3.8.0 (or self-convert from official Paddle det) | Apache-2.0 | single-digit MB mobile (confirm exact bytes at build) | `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae` | Language-agnostic detector; shared across all langs |
| **Recognition (primary)** | `eslav_PP-OCRv5_rec_mobile.onnx` + `ppocrv5_eslav_dict.txt` | RapidOCR ModelScope v3.8.0 (ONNX) — conversion of official `PaddlePaddle/eslav_PP-OCRv5_mobile_rec` | Apache-2.0 | 7.5 MB (ONNX); Paddle `.pdiparams` 7.81 MB | ONNX: `08705d6721849b1347d26187f15a5e362c431963a2a62bfff4feac578c489aab`; Paddle source: `f11057b05d8517868bca505271278973d706600d9dcc184cbcf5c4512091c32b` | East-Slavic (ru/uk/be) + Latin chars in dict → handles mixed ru+en |
| **Recognition (alt to A/B test)** | `cyrillic_PP-OCRv5_rec_mobile.onnx` + `ppocrv5_cyrillic_dict.txt` | RapidOCR ModelScope v3.8.0 | Apache-2.0 | ~7.5 MB | `90f761b4bfcce0c8c561c0cb5c887b0971d3ec01c32164bdf7374a35b0982711` | Broader Cyrillic dictionary; compare CER vs eslav |
| **Angle classifier (optional)** | `ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx` | RapidOCR ModelScope v3.8.0 | Apache-2.0 | <1 MB | `54379ae5174d026780215fc748a7f31910dee36818e63d49e17dc598ecc82df7` | Only needed if labels can be 180° rotated |

**Provenance ranking for the Cyrillic recognizer (per the user's framing):**
- **(a) Self-converted official eslav ONNX** — gold standard. Download official `PaddlePaddle/eslav_PP-OCRv5_mobile_rec` (HF commit `561929d8a8f3bb20f2d1e1ab4467608d84361e88`), run `paddlex --paddle2onnx` (opset 17), pin your own SHA, commit to your model repo. Feasible and documented (PaddleOCR's "Obtaining ONNX Models" page + paddle2onnx readme).
- **(a′) RapidOCR's eslav ONNX, re-hosted/re-verified by the team** — functionally equivalent because it's a conversion of the *same* official `.pdiparams` (SHA match proven above). This is the pragmatic default: less build complexity, identical weights. Re-host the file in your own bucket and pin the SHA so you don't depend on ModelScope at deploy time.
- **(b) Multilingual/Cyrillic PP-OCRv5 rec** — the `cyrillic_PP-OCRv5_rec_mobile` model is trained on a slightly larger 7,600-image Cyrillic set vs the 7,031-image eslav set (PaddleOCR docs: "Cyrillic Dataset: The latest PP-OCRv5 Cyrillic recognition dataset contains a total of 7,600 text images"); worth an A/B on your real packaging, but the eslav variant is purpose-built for East-Slavic and is the better starting default.
- **(c) Tesseract `rus+eng`** — rock-solid provenance (tessdata_best, Apache-2.0), but lower expected scene-text accuracy; keep as the CER yardstick.

**Disqualified for the primary path:** `monkt/paddleocr-onnx` (a third-party ONNX repo whose "det v5" is an 84 MB blob — the *server* detector, not mobile — and whose provenance/conversion settings are unverified). Acceptable only if your team re-converts/re-verifies, which defeats the point when RapidOCR's SHA-matched conversion exists.

**Is there a server-grade eslav variant?** No. PaddleOCR ships eslav only as `*_mobile_rec`; the only "server" rec models are the CJK/en/ja `PP-OCRv5_server_rec`. For ru+en the mobile recognizer is the correct (and only) PP-OCRv5 East-Slavic choice — which is fine on a 2-vCPU box.

**What the 7,031-image eslav set implies:** the model is a fine-tune over the multilingual PP-OCRv5 recognizer, so the small set adapts an already-trained network rather than training from zero. Production implication: strong on clean printed Cyrillic, weaker on stylized/condensed display fonts and decorative packaging type. This argues for (i) keeping the eslav model as default, (ii) A/B-testing the broader `cyrillic` model, and (iii) gating low-confidence outputs to the user rather than trusting them.

### Q2 — Library stack & pinned versions

**Choice: RapidOCR (the 2025+ unified `rapidocr` package) over both the legacy `rapidocr-onnxruntime` and hand-rolled PaddleOCR/ORT.** Rationale: the legacy `rapidocr-onnxruntime` (last release 1.4.4, Jan 2025) is flagged inactive/discontinued (Snyk: "hasn't seen any new versions released to PyPI in the past 12 months"); the unified `rapidocr` package is actively maintained — `rapidocr-3.8.4-py3-none-any.whl` was published to PyPI on Jun 15, 2026 (Apache-2.0, by RapidAI). Calling PaddleOCR directly drags in the full `paddlepaddle` runtime (heavy); hand-rolling raw ORT means re-implementing DB post-processing and CTC decoding for no benefit. RapidOCR gives you a thin, ORT-only inference path.

**Pinned dependency + base-image list (CPU, no GPU):**

| Component | Pin | Notes |
|---|---|---|
| Base image | `python:3.12-slim` (pin by digest, e.g. `python@sha256:…`) | 3.12/3.13 are the safe 2026 choices |
| `rapidocr` | `==3.8.4` | Avoid 3.8.2 (yanked: missing `arch_config.yaml`) |
| `onnxruntime` | `==1.27.0` (or 1.26.0) | CPU wheels uploaded to PyPI Jun 15, 2026 incl. CPython 3.12 manylinux x86-64; MIT, by Microsoft. Do **not** install `onnxruntime-gpu` |
| `fastapi` | `==0.136.1` | latest stable 2026 |
| `uvicorn[standard]` | `==0.49.0` | BSD-3, released Jun 3, 2026 |
| `opencv-python-headless` | pin latest at build | headless avoids GUI libs |
| `numpy`, `pillow`, `pyclipper`, `shapely`, `pyyaml`, `six` | transitively pinned by rapidocr | lockfile them |
| (build-time only) `paddlepaddle==3.0.0` + `paddle2onnx`/`paddlex` | conversion stage only | not in runtime image |

Flagged uncertainties: the exact on-disk byte size of `ch_PP-OCRv5_det_mobile.onnx` should be confirmed at build (mobile det is single-digit MB, **not** the 84 MB "v5 det" some third-party repos list — that larger figure is the server detector). `monkt/paddleocr-onnx` size/provenance claims are unverified.

### Q3 — Accuracy on real tea packaging

Official numbers: the eslav recognizer scores **81.6%** on PaddleOCR's East-Slavic dataset, per the official `PaddlePaddle/eslav_PP-OCRv5_mobile_rec` Hugging Face model card ("eslav_PP-OCRv5_mobile_rec | 81.6 … Note: If any character (including punctuation) in a line was incorrect, the entire line was marked as wrong"). Because that metric marks a whole line wrong on a single character slip, per-character CER is materially lower than (100−81.6)%. PP-OCRv5's overall weighted recognition average is reported at 80.1% across hard categories in the PaddlePaddle team's arXiv paper "PP-OCRv5: A Specialized 5M-Parameter Model Rivaling Billion-Parameter Vision-Language Models on OCR Tasks" (arXiv:2603.24373; PP-OCRv5_mobile is the 5M-parameter variant, Cui, Zhang et al., Baidu Inc.). Treat both as upper-bound lab figures, not packaging guarantees.

Realistic expectations by scenario:
- **Clean flat printed ru+en (front label, good light):** very good — expect low single-digit CER.
- **Mixed Cyrillic+Latin on one label:** handled well; the eslav dict contains Latin glyphs, so brand names in English and ingredients in Russian coexist.
- **Small multi-line back-of-pack print:** moderate; detection may merge/miss lines and tiny type (<10 px line height) drops sharply — RapidOCR's own guidance flags text under ~10 px as a failure zone.
- **Curved/cylindrical caddies & glossy/reflective foil:** the hardest case; specular highlights and curvature cause both detection misses and recognition substitutions.
- **Low-light phone photos:** noticeable degradation from blur/noise; this is where the low-confidence fallback earns its keep.

By contrast, Tesseract `rus+eng` is documented (Tesseract issue threads and third-party engine analyses) to fail on curved text, perspective distortion and scene photos — confirming PP-OCRv5 is the right primary engine.

### Q4 — Preprocessing verdict

**Do enable (measurable wins):**
- **Downscale to a max dimension** before detection (RapidOCR `limit_side_len` ≈ 960, drop to 640 for speed). Larger images don't improve accuracy proportionally and cost RAM/latency.
- **Mild contrast normalization / CLAHE** for low-contrast or glossy labels — cheap and helps the hardest cases.
- **Deskew** if you can detect dominant text angle cheaply.

**Usually skip (latency/RAM with little ru+en payoff):**
- **Document-orientation classification** and **UVDoc-style text-image unwarping** — PaddleOCR's own docs warn "auxiliary features such as image unwarping can impact inference accuracy… more features do not necessarily yield better results and may increase resource usage." For single-label hand-held photos these add load without reliable benefit.
- **Angle/text-line orientation classifier** — only enable if photos are routinely upside-down; otherwise default the pipeline with cls disabled, measure, and enable only if the test matrix shows rotated failures.
- **Aggressive binarization** — modern CNN recognizers prefer grayscale/RGB; hard thresholding often *hurts* on uneven lighting.

Net: prefer downscale + light contrast + optional deskew; leave the heavy doc-orientation/unwarp/cls modules off by default and turn them on only where the test matrix proves a gain.

### Q5 — Resource footprint & ops on 4 GB / 2 vCPU

**Verdict: fits 4 GB with the settings below.** A single loaded RapidOCR pipeline (det+rec, mobile, ~13 MB of weights total) on ONNX Runtime CPU has a resident set in the low hundreds of MB; reported ORT inference-time memory for small models is dominated by the CPU memory arena (a documented ORT issue showed the arena alone driving multi-GB spikes for large tensors — controllable via `enable_cpu_mem_arena`). For these tiny OCR models, post-warmup idle RSS is roughly a few hundred MB, rising transiently during inference (arena + image buffers) — comfortably inside 4 GB alongside a 1.5 GB JVM, Postgres and Caddy, especially since the heavy LLM tier is off and the JVM heap can be trimmed below 1.5 GB if needed.

**ONNX Runtime / threading settings:**
- `intra_op_num_threads = 2` (match the 2 vCPU), `inter_op_num_threads = 1`
- `execution_mode = ORT_SEQUENTIAL`
- `graph_optimization_level = ORT_ENABLE_ALL`
- Consider `enable_cpu_mem_arena = False` if you observe the arena pre-allocating large blocks and want a smaller, steadier RSS (small, accuracy-neutral latency cost); otherwise leave the arena on for speed.
- Disable thread spinning (`session.intra_op.allow_spinning=0`, `session.inter_op.allow_spinning=0`) to avoid burning CPU between requests on a shared box.

**Concurrency & container limits:**
- Application-level **concurrency cap = 1** (a semaphore/queue around the OCR call); reject or queue concurrent requests. This bounds peak RAM and prevents the OCR box from starving the JVM.
- `mem_limit: 1g` (start ~900 MB–1.2 GB), `cpus: "1.0"` for the sidecar container in docker-compose.
- Load model once at startup; warmup with a synthetic image on boot so the first real request isn't slow.

**Hardening (private compose network, image-in-memory-only):**
- Non-root user (`USER 10001`), `read_only: true` root filesystem (+ a small `tmpfs` for scratch), `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`.
- **No network egress**: the sidecar needs no outbound access — keep it off the default bridge, expose only to the backend on the internal compose network, and bake all models in so there is no runtime pull. This also sidesteps the real risk that Hugging Face / Paddle / ModelScope CDNs may be unreliable or unreachable from Russia.
- **Never log image bytes or recognized text** — set RapidOCR/ORT log level to critical, log only request IDs, sizes, latency and confidence.

**Resize verdict:** **Stay on 4 GB.** Only resize if you *measure* idle+inference RSS that genuinely doesn't fit. If that happens, going from 4 GB to 8 GB on the same 2 vCPU @ 50% Yandex VM costs roughly **700–950 ₽/month** extra (just the 4 added GB of RAM; the range reflects a pricing-vintage conflict — see Caveats). For a hobby app where correctness beats a few hundred rubles, that's an acceptable escape hatch — but not the default.

### Q6 — Integration & test plan

**FastAPI `/ocr` + `/health` sketch (baked-in assets, warmup-on-start, concurrency=1):**

```python
import asyncio, io
import numpy as np
from PIL import Image
from fastapi import FastAPI, UploadFile, File, HTTPException
from rapidocr import RapidOCR

app = FastAPI()
_sema = asyncio.Semaphore(1)          # concurrency cap = 1
engine: RapidOCR | None = None
CONF_THRESHOLD = 0.6                   # tune against the test matrix

@app.on_event("startup")
def _load():
    global engine
    engine = RapidOCR(params={
        "Global.log_level": "critical",
        # point det/rec at the baked-in, SHA-pinned local files:
        "Det.model_path": "/models/ch_PP-OCRv5_det_mobile.onnx",
        "Rec.model_path": "/models/eslav_PP-OCRv5_rec_mobile.onnx",
        "Rec.rec_keys_path": "/models/ppocrv5_eslav_dict.txt",
        "Global.use_cls": False,
        # ORT session options:
        "EngineConfig.onnxruntime.intra_op_num_threads": 2,
        "EngineConfig.onnxruntime.inter_op_num_threads": 1,
    })
    warm = np.zeros((32, 320, 3), dtype=np.uint8)   # warmup
    engine(warm)

@app.get("/health")
def health():
    return {"status": "ok", "model_loaded": engine is not None}

@app.post("/ocr")
async def ocr(image: UploadFile = File(...)):
    if engine is None:
        raise HTTPException(503, "model not ready")
    raw = await image.read()                          # in memory only; never persisted
    try:
        img = np.array(Image.open(io.BytesIO(raw)).convert("RGB"))
    except Exception:
        raise HTTPException(400, "bad image")
    async with _sema:
        result = await asyncio.to_thread(engine, img)
    lines = [(t, float(s)) for t, s in zip(result.txts or [], result.scores or [])]
    mean_conf = sum(s for _, s in lines) / len(lines) if lines else 0.0
    return {
        "text": "\n".join(t for t, _ in lines),
        "lines": [{"text": t, "confidence": s} for t, s in lines],
        "mean_confidence": mean_conf,
        "low_confidence": mean_conf < CONF_THRESHOLD,   # app asks user to retake/edit
    }
```
*(Exact RapidOCR param keys should be confirmed against the 3.8.x config schema at build; the structure above reflects the documented `params={...}` override mechanism.)*

**Dockerfile sketch (pinned base by digest, models baked in, optional build-time conversion):**

```dockerfile
# --- optional conversion stage: mint your own ONNX + SHA from official Paddle weights ---
FROM python@sha256:<paddle-builder-digest> AS convert
RUN pip install --no-cache-dir paddlepaddle==3.0.0 paddlex paddle2onnx
# download official PaddlePaddle/eslav_PP-OCRv5_mobile_rec (.pdiparams/inference.json) here,
# then: paddlex --paddle2onnx --paddle_model_dir ./eslav --onnx_model_dir ./eslav_onnx --opset_version 17
# (skip this stage entirely if re-hosting RapidOCR's SHA-matched ONNX instead)

# --- runtime ---
FROM python@sha256:<python-3.12-slim-digest>
RUN useradd -u 10001 -m app
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt   # rapidocr==3.8.4, onnxruntime==1.27.0,
                                                     # fastapi==0.136.1, uvicorn[standard]==0.49.0,
                                                     # opencv-python-headless, pillow
COPY --chown=app:app models/ /models/                # det+rec+dict baked in, SHA-verified in CI
COPY --chown=app:app app/ /app/
USER 10001
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```
docker-compose: `mem_limit: 1g`, `cpus: "1.0"`, `read_only: true`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, internal network only, no published ports. CI verifies each model file's SHA256 against the pinned values before building.

**15–20 image ru/en test matrix (covering Q3 failure modes):**

| # | Category | Example content |
|---|---|---|
| 1–3 | Flat printed front label, good light | Brand (Latin) + "Чай чёрный" (Cyrillic) |
| 4–5 | Mixed Cyrillic+Latin single line | "Greenfield Классический" |
| 6–8 | Multi-line small back-of-pack print | Ingredients/состав, net weight, barcode digits |
| 9–11 | Curved/cylindrical caddy | wrap-around text |
| 12–13 | Glossy/reflective foil sachet | specular highlights over text |
| 14–15 | Low-light / phone blur | dim kitchen photo |
| 16–17 | Rotated 90–180° | label photographed sideways/upside-down |
| 18–20 | Decorative/condensed display fonts | stylized tea-house branding |

**Pass/fail rubric (CER = Levenshtein distance / reference length, per image):**
- **Pass:** CER ≤ 0.10 (≤10% character error) on the union of detected text vs ground truth.
- **Marginal:** 0.10 < CER ≤ 0.25 — acceptable *only if* `mean_confidence` correctly flags it low so the app prompts the user.
- **Fail:** CER > 0.25, **or** confidence fails to flag a high-CER result (silent error — the worst outcome).
- Target: ≥80% of categories 1–8 Pass; categories 9–20 may be Marginal but **must** trigger the low-confidence flag.

**Low-confidence fallback:** when `mean_confidence < 0.6` (tune on the matrix), return `low_confidence: true` so the Android app shows the recognized text as an *editable draft* and offers "retake photo." Never auto-commit low-confidence OCR into the catalog.

## Recommendations

1. **Build the default now:** RapidOCR `rapidocr==3.8.4` + `onnxruntime==1.27.0`, PP-OCRv5 mobile det + eslav mobile rec, baked in by SHA, concurrency=1, 4 GB, container `mem_limit: 1g`. Re-host the eslav ONNX (SHA `08705d67…489aab`) and det ONNX (SHA `4d97c44a…ae78ae`) in your own storage and verify SHAs in CI.
2. **Run the gold-standard conversion in parallel:** in a build stage, paddle2onnx the official `eslav_PP-OCRv5_mobile_rec` (.pdiparams SHA `f11057b0…91c32b`) and diff its outputs against the RapidOCR ONNX. If they match (they should — same weights), you've independently certified provenance and can choose either at deploy.
3. **A/B the recognizer:** run the 15–20 image matrix through eslav vs `cyrillic_PP-OCRv5_rec_mobile` and pick the lower-CER one. Keep Tesseract `rus+eng` (tessdata_best, Apache-2.0) wired as a one-off measuring baseline, not in the hot path.
4. **Tune the confidence threshold** on the matrix so that every CER>0.25 image is flagged low-confidence. This is the single highest-leverage correctness lever.
5. **Resize trigger (the benchmark that changes the decision):** only move to 8 GB if measured steady-state RSS (sidecar + JVM + Postgres + Caddy) exceeds ~3.5 GB under load, or you see OOM-kills. The cost of that move is ~700–950 ₽/month; below that threshold, stay on 4 GB.

## Caveats
- **Whole-line vs CER:** the 81.6% eslav and 80.1% PP-OCRv5 figures are lab metrics (whole-line / weighted averages on PaddleOCR's own sets); your packaging CER will differ and must be measured on the test matrix. Treat vendor numbers skeptically.
- **Yandex RAM pricing conflict:** two sourced figures disagree on the rate per GB-hour. An older published Yandex rate of ₽0.2441/GB-hour yields 4 GB × 720 h ≈ **₽703/month**; the current (June 2026, post-VAT-increase) page implies ~₽0.33/GB-hour, yielding ≈ **₽950/month**. The true number sits in this range and depends only on the RAM rate (the 50%-vCPU rate is irrelevant to the *delta*). Confirm against the live Yandex Compute Cloud pricing page before budgeting. Either way it is a few hundred rubles — within the "correctness outweighs cost" tolerance.
- **Detection ONNX size:** stated as single-digit MB for the mobile detector but should be byte-confirmed at build; the 84 MB "v5 det" in some third-party repos is the *server* detector, not what you want.
- **RapidOCR config keys** for pinning local model paths and ORT threads should be validated against the exact 3.8.x schema; the API changed across the 1.x→3.x migration.
- **ModelScope/HF/Paddle reachability from Russia** is itself a deployment risk — this is precisely why every model is baked into the image and the sidecar has zero egress.

## Do-Not-Do List
- **Do not** bake in `monkt/paddleocr-onnx` (or any third-party ONNX blob) as the primary recognizer without re-converting/re-verifying — provenance and conversion settings are unverified, and its "det" is the heavy 84 MB server model.
- **Do not** use the legacy `rapidocr-onnxruntime` (1.4.4, discontinued) — use the maintained unified `rapidocr` package.
- **Do not** install `onnxruntime-gpu` — there is no GPU; it wastes image size and may break.
- **Do not** ship Chinese/CJK rec models or the default `PP-OCRv5_server_rec` — out of scope, larger, and worse for Cyrillic.
- **Do not** use any GPL or CC-BY-NC weights; everything specified here (PaddleOCR/RapidOCR models, tessdata) is Apache-2.0.
- **Do not** let the sidecar reach the internet at runtime or pull models on boot — bake them in, pin SHAs in CI.
- **Do not** persist or log image bytes or recognized text.
- **Do not** enable doc-unwarp / doc-orientation / angle-cls by default "just in case" — they add RAM/latency and can *lower* accuracy; enable only where the test matrix proves a gain.
- **Do not** allow concurrent OCR requests — cap concurrency at 1 to protect the shared 4 GB box.
- **Do not** "just bump to 8 GB" preemptively — optimize for 4 GB first; resize only on a measured OOM/RSS trigger.
- **Do not** auto-commit low-confidence OCR — flag it for user review.