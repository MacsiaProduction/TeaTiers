## 1. Model Table & Provenance-Ranked Recommendation

### Model Table

| Component | Artifact | Source | License | Size | Pinned Version/SHA | ru+en Fit |
|-----------|----------|--------|---------|------|-------------------|-----------|
| **Detection** | `PP-OCRv5_mobile_det_infer` (Paddle format) → self-converted to ONNX | Official PaddlePaddle [inference model](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_det_infer.tar) | Apache-2.0 | 4.7 MB (Paddle) | PaddleOCR 3.x / commit `c166448875bcecb8d3b7628fd697ac1c28f8705b` | Excellent — 79.0% detection Hmean, mobile model fits 4 GB |
| **Angle Classifier** | `ch_ppocr_mobile_v2.0_cls_infer` (Paddle format) → self-converted to ONNX | Official PaddleOCR v2.0 classifier | Apache-2.0 | ~1 MB | PaddleOCR 2.0+ | Optional — adds latency; use only if images are frequently rotated |
| **Recognition (Primary)** | `cyrillic_PP-OCRv5_mobile_rec` (Paddle format) → self-converted to ONNX | Official PaddlePaddle via `lang="ru"` download | Apache-2.0 | ~10–16 MB | PaddleOCR 3.x / commit `c166448875bcecb8d3b7628fd697ac1c28f8705b` | **Best fit** — 7,600 Cyrillic training images; supports Russian, Belarusian, Ukrainian, Serbian (Cyrillic), Bulgarian, +30更多西里尔字母语言 |
| **Recognition (Fallback)** | `eslav_PP-OCRv5_mobile_rec` | Official PaddlePaddle via `lang="ru"` | Apache-2.0 | ~10–16 MB | PaddleOCR 3.x | 7,031 East Slavic training images (Russian, Belarusian, Ukrainian); slightly smaller dataset than `cyrillic` |
| **Recognition (Latin baseline)** | `latin_PP-OCRv5_mobile_rec` | Official PaddlePaddle | Apache-2.0 | ~10–16 MB | PaddleOCR 3.x | 84.7% accuracy on Latin dataset; 3,111 training images — use only for English-only labels |

**Key distinction**: `cyrillic_PP-OCRv5_mobile_rec` covers **all Cyrillic-based languages** (Russian, Ukrainian, Belarusian, Serbian, Bulgarian, Kazakh, etc.), while `eslav_PP-OCRv5_mobile_rec` is limited to East Slavic (Russian, Belarusian, Ukrainian). For Russian tea packaging that may include Ukrainian or Serbian text, `cyrillic` is the safer choice.

### Provenance-Ranked Recommendation

**Rank 1 (Recommended): Self-converted official `cyrillic_PP-OCRv5_mobile_rec` ONNX**

The official PaddlePaddle weights are Apache-2.0 and can be self-converted to ONNX at build time using `paddle2onnx`. This gives you full provenance control — you own the SHA, the conversion pipeline, and can re-verify.

**Rank 2: Self-converted official `eslav_PP-OCRv5_mobile_rec` ONNX**

Same provenance as Rank 1, but with a smaller training set (7,031 vs 7,600 images) and narrower language coverage. Only choose this if you explicitly want to limit to East Slavic scripts.

**Rank 3: Community ONNX blobs (e.g., monkt/paddleocr-onnx, RapidAI)**

Disqualified as primary per your provenance rule. These are convenient but introduce supply-chain risk. If used, they must be re-hosted and checksum-verified.

**Rank 4: Tesseract 5 rus+eng**

Conservative fallback. Apache-2.0, provenance-clean, but substantially lower accuracy on curved/glossy packaging than PaddleOCR. Tesseract 5.0 achieves ~91.7% English accuracy on clean ICDAR 2013 data, but real-world tea packaging (curved, reflective, mixed script) will be significantly worse. PaddleOCR's PP-OCRv5 multilingual model shows >30% accuracy improvement over PP-OCRv3 for multilingual text — Tesseract is not competitive here.

**Verdict**: **Self-converted `cyrillic_PP-OCRv5_mobile_rec` ONNX is the recommendation.** The 7,600-image Cyrillic dataset is small but adequate for MVP tea packaging; the accuracy trade-off is acceptable given the 4 GB RAM constraint. If CER on real packaging proves unacceptable, the fallback path is to train a fine-tuned model (out of MVP scope).


## 2. Pinned Dependencies + Base Image

### Self-Conversion Build-Time Dependencies

These are **build-time only** — not in the runtime image:

```
paddlepaddle==3.0.0          # or 3.1.x (PaddlePaddle framework)
paddleocr==3.0.0             # or 3.1.x
paddle2onnx==1.0.5+          # conversion tool
```

**Self-conversion command** (from official docs):

```bash
# Download official inference model
wget https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_det_infer.tar
tar -xf PP-OCRv5_mobile_det_infer.tar

# Convert to ONNX
paddle2onnx --model_dir PP-OCRv5_mobile_det_infer \
            --model_filename inference.pdmodel \
            --params_filename inference.pdiparams \
            --save_file PP-OCRv5_mobile_det.onnx \
            --opset_version 11
```

**Important**: Paddle2ONNX conversion for PP-OCRv5 server models can show accuracy degradation (H-mean -5.8% in some cases), but **mobile models convert without accuracy loss**. This is another reason to use mobile models.

### Runtime FastAPI Sidecar Dependencies

| Package | Version | Notes |
|---------|---------|-------|
| `rapidocr_onnxruntime` | **1.4.4** | Latest PyPI release; integrates PP-OCRv5 models |
| `onnxruntime` | **1.20.0+** (latest stable) | Must be installed separately; CPU-only for 4 GB VM |
| `fastapi` | **0.115.0+** | Latest stable |
| `uvicorn[standard]` | **0.32.0+** | With `standard` extra for performance |
| `opencv-python-headless` | **4.10.0+** | Image preprocessing; headless to save space |
| `python-multipart` | **0.0.12+** | For FastAPI file uploads |
| `pydantic` | **2.10.0+** | Request/response models |

### Python Base Image

```dockerfile
FROM python:3.11-slim-bookworm
```

Why: Debian Bookworm is stable, `slim` reduces image size, Python 3.11 is well-supported by ONNX Runtime and RapidOCR. Python 3.12+ may have compatibility issues with some ONNX Runtime builds.


## 3. Accuracy Expectations & Failure Modes

### Realistic CER/WER Expectations on Tea Packaging

| Scenario | Expected CER | Expected WER | Notes |
|----------|-------------|--------------|-------|
| Flat label, good lighting, printed sans-serif | **<5%** | **<10%** | Best case |
| Curved package (tea tin/box) | **5–15%** | **15–30%** | PP-OCRv5 detection Hmean 79% handles some curvature |
| Glossy/reflective label | **10–20%** | **25–40%** | Reflection creates false contours |
| Mixed Cyrillic+Latin | **8–18%** | **20–35%** | Model supports both; confusion between similar glyphs (e.g., `А` vs `A`) |
| Multi-line small print | **10–25%** | **30–50%** | Detection may merge/split lines |
| Low-light phone photo | **15–30%** | **40–60%** | Noise amplifies all errors |

### Failure Modes

1. **Cyrillic-specific confusions**: `Ь`/`Ъ`, `Ш`/`Щ`, `Е`/`Ё` — the 7,600-image dataset may not cover all font variations
2. **Mixed-script boundaries**: Words with both Cyrillic and Latin may be mis-segmented
3. **Curved text detection**: DBNet can output non-rectangular polygons, but severe curvature may cause crop distortion before recognition
4. **Low-confidence fallback**: The model returns per-character confidence scores; set a threshold (e.g., 0.5) to flag low-confidence results for user review
5. **Glossy reflections**: Detection may hallucinate text boxes on reflective surfaces


## 4. Preprocessing Verdict

### What Pays Off

| Preprocessing | Payoff | Latency Cost | Recommendation |
|---------------|--------|--------------|----------------|
| **Downscale to max dimension 1280px** | **High** — reduces inference time without accuracy loss for packaging text | Low | **Enable** — resize if `max(h,w) > 1280` |
| **Contrast/CLAHE** | **Medium** — helps glossy/低光 images | Low | **Enable** — use `cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))` |
| **Angle classifier (cls)** | **Low** — most phone photos are upright | ~5-10ms per crop | **Disable for MVP** — enable only if users report rotated images |
| **Document orientation** | **None** — packaging is not a document | High | **Disable** — not applicable |
| **Text-image unwarping** | **Low** — adds complexity | High | **Disable** — PP-OCRv5 detection handles moderate distortion |

### Preprocessing Pipeline

```python
def preprocess_image(image: np.ndarray) -> np.ndarray:
    # 1. CLAHE for contrast
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l = clahe.apply(l)
    image = cv2.merge((l, a, b))
    image = cv2.cvtColor(image, cv2.COLOR_LAB2BGR)
    
    # 2. Downscale
    h, w = image.shape[:2]
    max_dim = 1280
    if max(h, w) > max_dim:
        scale = max_dim / max(h, w)
        new_w, new_h = int(w * scale), int(h * scale)
        image = cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_AREA)
    
    return image
```


## 5. Resource Footprint & Ops on 4 GB / 2 vCPU VM

### Memory Breakdown

| Component | Memory (idle) | Memory (during inference) |
|-----------|--------------|---------------------------|
| `PP-OCRv5_mobile_det` ONNX | ~5 MB | ~50–80 MB |
| `cyrillic_PP-OCRv5_mobile_rec` ONNX | ~15 MB | ~80–120 MB |
| ONNX Runtime session overhead | ~20 MB | ~50–100 MB |
| FastAPI + Uvicorn | ~30 MB | ~50 MB |
| Image buffer (1280px max) | 0 | ~5–10 MB |
| **Total sidecar** | **~70 MB** | **~200–350 MB** |

Reference: ncnn implementation of PP-OCRv5 reports ~106 MB during inference. ONNX Runtime with mobile models should be comparable or lower.

### Cold Start & Latency

| Metric | Estimate | Notes |
|--------|----------|-------|
| Model load (cold) | **2–5 seconds** | Loading two ONNX models + creating sessions |
| Per-image inference | **200–500 ms** | Detection + recognition; CPU-only on 2 vCPU |
| Total request latency | **300–800 ms** | Includes preprocessing + postprocessing |

### Will It Fit on 4 GB?

**Existing workload**: JVM (~1.5 GB heap) + Postgres (~200–400 MB) + Caddy (~50 MB) = **~2.0 GB**

**Sidecar**: ~350 MB peak = **~2.35 GB total**

**Headroom**: 4 GB - 2.35 GB = **~1.65 GB** for OS + filesystem cache

**Verdict**: **fits 4 GB with settings X**

### ONNX Runtime Threading Settings

```python
import onnxruntime as ort

sess_options = ort.SessionOptions()
sess_options.intra_op_num_threads = 1   # One thread per operator
sess_options.inter_op_num_threads = 1   # No parallel operators
sess_options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
sess_options.enable_cpu_mem_arena = False  # Reduce memory fragmentation

# Session for detection
det_session = ort.InferenceSession("PP-OCRv5_mobile_det.onnx", sess_options)

# Session for recognition
rec_session = ort.InferenceSession("cyrillic_PP-OCRv5_mobile_rec.onnx", sess_options)
```

### Container Limits

```yaml
# docker-compose.yml or podman run
services:
  ocr-sidecar:
    image: ocr-sidecar:latest
    mem_limit: 768m           # Hard cap
    mem_reservation: 512m     # Soft reservation
    cpus: 1.0                 # Reserve 1 full vCPU
    pids_limit: 100
    read_only: true
    security_opt:
      - no-new-privileges:true
```

### Hardening

```dockerfile
# Dockerfile hardening
FROM python:3.11-slim-bookworm AS builder
# ... build steps ...

FROM python:3.11-slim-bookworm
RUN useradd -m -u 1000 ocr && \
    chown -R ocr:ocr /app
USER ocr
WORKDIR /app

# Capabilities
# In compose: cap_drop: ALL
# No network egress: no internet access at runtime
```

**Runtime network**: The sidecar should have **no network egress** — all models are baked into the image. If using `rapidocr_onnxruntime`, ensure it doesn't attempt to download models on first run (use local model paths).


## 6. FastAPI /ocr Endpoint + Dockerfile Sketch

### FastAPI Endpoint

```python
# main.py
import cv2
import numpy as np
from fastapi import FastAPI, File, UploadFile, HTTPException
from pydantic import BaseModel
import onnxruntime as ort
from rapidocr_onnxruntime import RapidOCR
import uvicorn

app = FastAPI(title="TeaTiers OCR Sidecar", version="1.0.0")

class OCRResponse(BaseModel):
    text: str
    confidence: float
    boxes: list  # optional, for debugging

# Load models at startup
ocr = RapidOCR(
    det_model_path="/app/models/PP-OCRv5_mobile_det.onnx",
    rec_model_path="/app/models/cyrillic_PP-OCRv5_mobile_rec.onnx",
    cls_model_path=None,  # disabled for MVP
    use_angle_cls=False,
)

@app.on_event("startup")
def warmup():
    """Warm up the model with a dummy request"""
    dummy = np.zeros((100, 100, 3), dtype=np.uint8)
    _ = ocr(dummy)

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/api/v1/teas/ocr", response_model=OCRResponse)
async def ocr_image(file: UploadFile = File(...)):
    # 1. Validate file size (e.g., 10 MB cap)
    contents = await file.read()
    if len(contents) > 10 * 1024 * 1024:
        raise HTTPException(413, "Image too large")
    
    # 2. Decode image
    nparr = np.frombuffer(contents, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(400, "Invalid image")
    
    # 3. Preprocess
    image = preprocess_image(image)
    
    # 4. Run OCR
    result, elapse = ocr(image)
    # result: list of [box, (text, confidence)] or None
    
    if result is None:
        return OCRResponse(text="", confidence=0.0, boxes=[])
    
    # 5. Concatenate results
    full_text = " ".join([item[1][0] for item in result])
    avg_conf = sum([item[1][1] for item in result]) / len(result) if result else 0.0
    
    return OCRResponse(text=full_text, confidence=avg_conf, boxes=[item[0] for item in result])

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000, workers=1)
```

### Dockerfile

```dockerfile
# Build stage: convert Paddle models to ONNX (if doing self-conversion)
FROM python:3.11-slim-bookworm AS converter

RUN pip install --no-cache-dir paddlepaddle==3.0.0 paddleocr==3.0.0 paddle2onnx==1.0.5

WORKDIR /models
# Download official inference models
RUN wget -q https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_det_infer.tar && \
    tar -xf PP-OCRv5_mobile_det_infer.tar && \
    paddle2onnx --model_dir PP-OCRv5_mobile_det_infer \
                --model_filename inference.pdmodel \
                --params_filename inference.pdiparams \
                --save_file PP-OCRv5_mobile_det.onnx \
                --opset_version 11

# ... repeat for recognition model (cyrillic_PP-OCRv5_mobile_rec)

# Runtime stage
FROM python:3.11-slim-bookworm

# Security: non-root user
RUN useradd -m -u 1000 ocr && \
    mkdir -p /app/models && \
    chown -R ocr:ocr /app

# Copy models from builder
COPY --from=converter --chown=ocr:ocr /models/*.onnx /app/models/
COPY --chown=ocr:ocr requirements.txt /app/
COPY --chown=ocr:ocr main.py /app/

USER ocr
WORKDIR /app

# Install runtime dependencies (no build tools)
RUN pip install --no-cache-dir -r requirements.txt

# Health check
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import requests; requests.get('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
```

### requirements.txt

```
fastapi==0.115.0
uvicorn[standard]==0.32.0
onnxruntime==1.20.0
rapidocr_onnxruntime==1.4.4
opencv-python-headless==4.10.0.84
python-multipart==0.0.12
pydantic==2.10.0
numpy==1.26.0
```


## 7. Test Plan: 15–20 Image ru/en Matrix

### Test Image Matrix

| # | Scenario | Expected Text | Pass/Fail Rubric |
|---|----------|---------------|------------------|
| 1 | Flat label, printed Cyrillic, good light | "Чай" | CER <5% |
| 2 | Flat label, printed Latin, good light | "Tea" | CER <5% |
| 3 | Mixed Cyrillic+Latin | "Азербайджанский чай" | CER <10% |
| 4 | Small print (ingredients) | Multi-line ingredient list | CER <15% |
| 5 | Curved tin can | Brand name on curve | CER <15% |
| 6 | Glossy/reflective label | Product name | CER <20% |
| 7 | Low-light photo | Any detectable text | CER <25% |
| 8 | Cyrillic only, serif font | "Краснодарский" | CER <10% |
| 9 | Cyrillic only, sans-serif | "Байкал" | CER <5% |
| 10 | English only, serif | "Earl Grey" | CER <5% |
| 11 | English only, sans-serif | "Breakfast" | CER <5% |
| 12 | Rotated image (45°) | Any text | CER <20% (if cls enabled) |
| 13 | Mixed case | "TeaTiers" | CER <10% |
| 14 | Numbers + Cyrillic | "Сорт №1" | CER <10% |
| 15 | Multi-line with punctuation | "Чай черный, листовой" | CER <15% |
| 16 | Handwritten-style font | Brand name in script | CER <25% |
| 17 | Low contrast (white text on light background) | Any text | CER <25% |
| 18 | Busy background (patterned package) | Any text | CER <20% |
| 19 | Very small text (<8pt) | Ingredients | CER <30% |
| 20 | Cyrillic with diacritics | "Ё" characters | CER <15% |

### CER Rubric

| CER Range | Verdict | Action |
|-----------|---------|--------|
| **<5%** | Excellent | Production-ready |
| **5–15%** | Acceptable | Monitor; user review handles errors |
| **15–25%** | Marginal | Consider preprocessing improvements |
| **>25%** | Fail | Fallback to Tesseract or flag for manual review |

**Low-confidence fallback**: If `avg_conf < 0.5`, return `{"text": "", "confidence": 0.0, "needs_review": true}` — the frontend prompts the user to manually enter the text.


## 8. "Do Not Do" List

| ❌ Don't | ✅ Do |
|----------|------|
| Use GPL/CC-BY-NC weights | Stick to Apache-2.0 official PaddlePaddle models |
| Download models at runtime | Bake models into the Docker image with pinned SHA |
| Use community ONNX blobs as primary (monkt/paddleocr-onnx) | Self-convert official Paddle models with `paddle2onnx` |
| Enable angle classifier by default | Disable it; enable only if user reports rotated images |
| Use server-grade detection models (84 MB) | Use mobile detection (4.7 MB) |
| Set `intra_op_num_threads > 1` | Keep at 1 for 2 vCPU; let the OS schedule |
| Log image bytes or recognized text | Log only request IDs and status codes |
| Run as root | Run as non-root user with dropped capabilities |
| Allow network egress | No network access at runtime (models baked in) |
| Use `opencv-python` (full) | Use `opencv-python-headless` to save space |
| Use Python 3.12+ | Use Python 3.11-slim for ONNX Runtime compatibility |
| Set memory limit too low (<512m) | Set `mem_limit: 768m` with `mem_reservation: 512m` |
| Ignore the JVM heap | Trim JVM heap below 1.5 GB if using LLM tier (currently off) |