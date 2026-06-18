"""
TeaTiers OCR sidecar (slice 1b; research 13 / decisions #96/#100/#105).

A tiny FastAPI service that the catalog server proxies a user-scanned packaging photo to
(`POST /ocr`, multipart `file`) and gets back the recognized text as `{"text": ...}`. The server
owns rate-limiting, sanitization, and the size cap; this service just runs RapidOCR and returns the
raw concatenated text. Reachable only on the private compose network — never published.

Engine = the slice-1b-measured config (decision #105): eslav PP-OCRv5 mobile rec + PP-OCRv5 mobile
det, angle-cls OFF, ONNX intra=2/inter=1, no CPU mem-arena, det downscale limit 960. Models are
baked into the image and loaded from $OCR_MODELS_DIR (no runtime egress). Concurrency is capped at
1 (a single-thread executor) so CPU/RSS stay bounded on the 4 GB VM. The image bytes and the
recognized text are NEVER logged.
"""
from __future__ import annotations

import asyncio
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from PIL import Image
import io

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("ocr-sidecar")

MODELS_DIR = os.environ.get("OCR_MODELS_DIR", "/opt/ocr-models")
REC_MODEL = os.path.join(MODELS_DIR, "eslav_PP-OCRv5_rec_mobile.onnx")
DET_MODEL = os.path.join(MODELS_DIR, "ch_PP-OCRv5_det_mobile.onnx")
# Angle-cls is OFF at inference, but RapidOCR still constructs (and would DOWNLOAD) the cls model at
# init — so we bake it and pass Cls.model_path to keep the runtime egress-free / read-only-safe.
CLS_MODEL = os.path.join(MODELS_DIR, "ch_ppocr_mobile_v2.0_cls_mobile.onnx")
# Defense-in-depth: the server already caps uploads (8 MB), but bound it here too.
MAX_IMAGE_BYTES = int(os.environ.get("OCR_MAX_IMAGE_BYTES", str(10 * 1024 * 1024)))
# The byte cap does NOT bound DECODED pixels: a low-entropy PNG can be <8 MB yet decode to a huge
# RGB array (then doubled by np.array) and OOM the 1 GB container. PIL only *warns* at its default
# MAX_IMAGE_PIXELS and *raises* at 2× that, so a sub-179 MP bomb slips through — hence an explicit
# header-size check before the big allocation. 30 MP is generous for real photos (the app already
# downscales to ≤1600 px) and blocks bombs; PIL's default stays as a backstop (not lowered).
MAX_IMAGE_PIXELS = int(os.environ.get("OCR_MAX_IMAGE_PIXELS", str(30_000_000)))
# Cap the text the sidecar returns so the server's sanitizer can't be handed an unbounded blob.
MAX_TEXT_CHARS = int(os.environ.get("OCR_MAX_TEXT_CHARS", str(8192)))

# Concurrency cap = 1: a single worker serializes inference so peak CPU/RSS stay bounded.
_executor = ThreadPoolExecutor(max_workers=1)
_engine = None


def _build_engine():
    from rapidocr import LangDet, LangRec, ModelType, OCRVersion, RapidOCR

    return RapidOCR(
        params={
            "Global.use_cls": False,
            # cls stays off, but the path must point at the baked model (see CLS_MODEL above).
            "Cls.model_path": CLS_MODEL,
            "Det.model_path": DET_MODEL,
            "Det.lang_type": LangDet.CH,
            "Det.ocr_version": OCRVersion.PPOCRV5,
            "Det.model_type": ModelType.MOBILE,
            "Det.limit_side_len": 960,
            "Det.limit_type": "max",
            "Rec.model_path": REC_MODEL,
            "Rec.lang_type": LangRec.ESLAV,
            "Rec.ocr_version": OCRVersion.PPOCRV5,
            "Rec.model_type": ModelType.MOBILE,
            "EngineConfig.onnxruntime.intra_op_num_threads": 2,
            "EngineConfig.onnxruntime.inter_op_num_threads": 1,
            "EngineConfig.onnxruntime.enable_cpu_mem_arena": False,
        }
    )


def within_pixel_budget(image_bytes: bytes) -> bool:
    """
    Header-only check that the DECODED pixel count is within budget — runs before the big RGB
    allocation. Returns False for an over-budget image (→ 413). A non-decodable blob returns True
    so the recognizer's own error path handles it (→ 500), keeping this purely a bomb guard.
    """
    try:
        with Image.open(io.BytesIO(image_bytes)) as img:
            width, height = img.size
    except Image.DecompressionBombError:
        return False
    except Exception:
        return True  # not a valid image; let _recognize fail it, don't mask as "too large"
    return width * height <= MAX_IMAGE_PIXELS


def _recognize(image_bytes: bytes) -> str:
    """Runs on the single-worker executor. Returns the concatenated recognized text (length-capped)."""
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    arr = np.array(img)
    result = _engine(arr)
    txts = getattr(result, "txts", None)
    if not txts:
        return ""
    return " ".join(str(t) for t in txts)[:MAX_TEXT_CHARS]


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _engine
    for p in (REC_MODEL, DET_MODEL, CLS_MODEL):
        if not os.path.exists(p):
            raise RuntimeError(f"OCR model missing: {p} (was the image built with fetch_models.sh?)")
    log.info("Loading RapidOCR (eslav PP-OCRv5 rec + mobile det, cls off)…")
    _engine = _build_engine()
    # Warm up so the first real request doesn't pay the graph-init cost.
    white = np.full((48, 160, 3), 255, dtype=np.uint8)
    await asyncio.get_event_loop().run_in_executor(_executor, lambda: _engine(white))
    log.info("OCR engine ready.")
    yield


app = FastAPI(title="TeaTiers OCR sidecar", lifespan=lifespan)


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse({"status": "ok" if _engine is not None else "loading"})


@app.post("/ocr")
async def ocr(file: UploadFile = File(...)) -> JSONResponse:
    data = await file.read()
    # Never log the bytes or the recognized text — only sizes/status/timing.
    if not data:
        raise HTTPException(status_code=400, detail="empty image")
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="image too large")
    # Decompression-bomb guard: reject by decoded pixel count before the big allocation.
    if not within_pixel_budget(data):
        raise HTTPException(status_code=413, detail="image dimensions too large")
    started = time.time()
    try:
        text = await asyncio.get_event_loop().run_in_executor(_executor, _recognize, data)
    except Exception:
        log.exception("OCR recognition failed (%d bytes)", len(data))
        raise HTTPException(status_code=500, detail="recognition failed")
    log.info("ocr ok: %d bytes -> %d chars in %d ms", len(data), len(text), int(1000 * (time.time() - started)))
    return JSONResponse({"text": text})
