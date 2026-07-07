"""
TeaTiers OCR sidecar (slice 1b; research 13 / decisions #96/#100/#105).

A tiny FastAPI service that the catalog server proxies a user-scanned packaging photo to
(`POST /ocr`, multipart `file`) and gets back the recognized text as `{"text": ...}`. The server
owns rate-limiting, sanitization, and the size cap; this service just runs RapidOCR and returns the
raw concatenated text. Reachable only on the private compose network — never published.

Engine = the slice-1b-measured config (decision #105): eslav PP-OCRv5 mobile rec + PP-OCRv5 mobile
det, angle-cls OFF, ONNX intra=2/inter=1, no CPU mem-arena, det downscale limit 960. Models are
baked into the image and loaded from $OCR_MODELS_DIR (no runtime egress). Inference runs in a single
worker SUBPROCESS (max_workers=1) so CPU/RSS stay bounded AND a wedged inference can be killed on the
deadline (review P1-7). The image bytes and the recognized text are NEVER logged.
"""
from __future__ import annotations

import asyncio
import logging
import multiprocessing
import os
import time
from concurrent.futures import ProcessPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from PIL import Image
import io

from description_correct import correct_description

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("ocr-sidecar")

MODELS_DIR = os.environ.get("OCR_MODELS_DIR", "/opt/ocr-models")
REC_MODEL = os.path.join(MODELS_DIR, "eslav_PP-OCRv5_rec_mobile.onnx")
DET_MODEL = os.path.join(MODELS_DIR, "ch_PP-OCRv5_det_mobile.onnx")
# Angle-cls is OFF at inference, but RapidOCR still constructs (and would DOWNLOAD) the cls model at
# init — so we bake it and pass Cls.model_path to keep the runtime egress-free / read-only-safe.
CLS_MODEL = os.path.join(MODELS_DIR, "ch_ppocr_mobile_v2.0_cls_mobile.onnx")
# Defense-in-depth: the server already caps uploads (8 MB), but bound it here too.
MAX_IMAGE_BYTES = 10 * 1024 * 1024
# The byte cap does NOT bound DECODED pixels: a low-entropy PNG can be <8 MB yet decode to a huge
# RGB array (then doubled by np.array) and OOM the 1 GB container. PIL only *warns* at its default
# MAX_IMAGE_PIXELS and *raises* at 2× that, so a sub-179 MP bomb slips through — hence an explicit
# header-size check before the big allocation. 30 MP is generous for real photos (the app already
# downscales to ≤1600 px) and blocks bombs; PIL's default stays as a backstop (not lowered).
MAX_IMAGE_PIXELS = 30_000_000
# Cap the text the sidecar returns so the server's sanitizer can't be handed an unbounded blob.
MAX_TEXT_CHARS = 8192
# Conditional low-res upscale (RECONSIDER option C, decision #114): when the SHORT side is small (a
# low-res capture), upscale to UPSCALE_TARGET_SHORT before OCR — the one measured accuracy win
# (real-photo name-capture 3/4 -> 4/4). It MUST stay conditional (an unconditional upscale regresses
# good shots: ru 9.2%->17% CER), bicubic, and pixel-capped (an unbounded upscale of a wide low-res
# image could OOM the container — PaddleOCR issue #16168).
UPSCALE_SHORT_BELOW = 500
UPSCALE_TARGET_SHORT = 960
# Hard wall-clock deadline on a single inference. On expiry the request fails 504 AND the worker
# subprocess is SIGKILLed (a wedged native ONNX call can't be interrupted in-process), then a fresh
# worker is spawned — so the stuck work actually dies instead of leaking a thread that pins CPU/RAM
# until the next request (review P1-7). The next request pays one worker-respawn warmup.
INFERENCE_DEADLINE_S = 15.0

# Inference runs in a SINGLE worker SUBPROCESS so a timed-out call is killable (a thread is not).
# `spawn` keeps the worker independent of the parent's threads; the executor is created in `lifespan`
# (NOT at import) so a spawned worker re-importing this module can't recursively create its own pool.
_MP = multiprocessing.get_context("spawn")
_executor: ProcessPoolExecutor | None = None
_engine = None  # built per-worker by `_init_worker`; the parent process never holds the model
_ready = False  # parent-side readiness (the parent's `_engine` stays None now that inference forked out)
# Serialize inference to the single worker at the asyncio level (review N6): the server permits a few
# in-flight /ocr requests (OcrProperties.maxConcurrent), but only ONE may hold the worker at a time, so
# when a timeout SIGKILLs + respawns the worker there is never a SIBLING future on the pool to be
# cancelled (which would surface as an unhandled CancelledError → 500). Waiters queue here, not on the
# pool. A module-level Lock binds lazily to the running loop on first use (Python 3.10+).
_lock = asyncio.Lock()


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


def _init_worker() -> None:
    """ProcessPoolExecutor initializer — runs ONCE per worker process. Builds + warms the engine (and
    the corrector's lazy pymorphy3 dictionary) so the worker is ready before its first real request.
    A missing model here raises, which surfaces as a BrokenProcessPool on the first submit."""
    global _engine
    _engine = _build_engine()
    _engine(np.full((48, 160, 3), 255, dtype=np.uint8))  # warm the ONNX graph
    correct_description("тест")  # warm the corrector's lazily-loaded Russian dictionary


def _new_executor() -> ProcessPoolExecutor:
    """A fresh single-worker process pool. The worker spawns lazily on the first submitted task."""
    return ProcessPoolExecutor(max_workers=1, mp_context=_MP, initializer=_init_worker)


def _kill_executor(ex: ProcessPoolExecutor) -> None:
    """SIGKILL the worker process(es) so a wedged inference actually dies, then tear the pool down.
    `shutdown()` alone would WAIT for the running task (it can't cancel a native call), so we kill the
    processes first. The pool is discarded afterwards — a new one is created by the caller."""
    for proc in list(getattr(ex, "_processes", {}).values()):
        proc.kill()
    ex.shutdown(wait=False, cancel_futures=True)


def _swap_executor(stale: ProcessPoolExecutor | None, *, kill: bool) -> None:
    """Replace a wedged/broken worker pool and mark the service NOT ready until the next request
    re-warms it (OCR-P2-1). The new pool's worker spawns + builds/warms its engine lazily on the next
    submit, so `/health` must report `loading` in the meantime instead of the stale `ok` left over from
    startup; a successful inference flips `_ready` back to True. No-op (and stays ready) when there is no
    real pool to replace — e.g. unit tests on the default in-process executor (`_executor is None`)."""
    global _executor, _ready
    if stale is None:
        return
    _ready = False
    _executor = _new_executor()
    if kill:
        _kill_executor(stale)
    else:
        stale.shutdown(wait=False)


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


def _maybe_upscale(img: Image.Image) -> Image.Image:
    """
    Conditionally upscale a low-resolution capture before OCR (RECONSIDER option C, decision #114).
    Only when the SHORT side is below [UPSCALE_SHORT_BELOW] — good shots are left untouched, since an
    unconditional upscale regresses them — using bicubic, and skipped entirely if the result would
    exceed the pixel budget (RAM guard for a wide low-res input).
    """
    width, height = img.size
    short = min(width, height)
    if short == 0 or short >= UPSCALE_SHORT_BELOW:
        return img
    scale = UPSCALE_TARGET_SHORT / short
    new_w, new_h = round(width * scale), round(height * scale)
    if new_w * new_h > MAX_IMAGE_PIXELS:
        return img  # would exceed the pixel budget; OCR at native resolution instead
    return img.resize((new_w, new_h), Image.BICUBIC)


class UnreadableImageError(Exception):
    """The uploaded bytes can't be decoded as an image (corrupt file / unsupported format) — a client
    problem, not a sidecar fault (UX2-P1-7): retrying later won't help, unlike a transport/engine error."""


def _recognize(image_bytes: bytes) -> str:
    """Runs on the worker subprocess. Returns the concatenated recognized text (length-capped)."""
    try:
        img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    except Exception as exc:
        raise UnreadableImageError(str(exc)) from None
    img = _maybe_upscale(img)
    arr = np.array(img)
    result = _engine(arr)
    txts = getattr(result, "txts", None)
    if not txts:
        return ""
    return " ".join(str(t) for t in txts)[:MAX_TEXT_CHARS]


def _recognize_and_correct(image_bytes: bytes) -> dict[str, str]:
    """Recognize then dictionary-gate-correct (decision #125 Track B), both on the worker so the whole
    thing is bounded by the one deadline. `text` is the raw OCR; `corrected` is the description-safe
    cleaned text (homoglyphs fixed to real RU words, un-validatable tokens kept raw)."""
    raw = _recognize(image_bytes)
    return {"text": raw, "corrected": correct_description(raw)}


def _warmup_probe() -> bool:
    """Runs in the worker after `_init_worker` — forces the worker to spawn + build its engine and
    confirms it is live, so the first real request doesn't pay graph-init."""
    return _engine is not None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _executor, _ready
    for p in (REC_MODEL, DET_MODEL, CLS_MODEL):
        if not os.path.exists(p):
            raise RuntimeError(f"OCR model missing: {p} (was the image built with fetch_models.sh?)")
    log.info("Starting OCR worker (eslav PP-OCRv5 rec + mobile det, cls off)…")
    _executor = _new_executor()
    # Force the worker to spawn + build/warm its engine + the corrector dictionary before we serve.
    ok = await asyncio.get_event_loop().run_in_executor(_executor, _warmup_probe)
    if not ok:
        raise RuntimeError("OCR worker failed to initialize the engine")
    _ready = True
    log.info("OCR engine ready.")
    yield
    if _executor is not None:
        _executor.shutdown(wait=False, cancel_futures=True)


app = FastAPI(title="TeaTiers OCR sidecar", lifespan=lifespan)


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse({"status": "ok" if _ready else "loading"})


@app.post("/ocr")
async def ocr(request: Request, file: UploadFile = File(...)) -> JSONResponse:
    global _executor, _ready
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
    # One inference at a time (review N6): hold the single worker exclusively so a kill-on-timeout can
    # never cancel a concurrently-submitted sibling's future (which would surface as an unhandled
    # CancelledError → 500). Other in-flight requests wait here, not on the pool.
    async with _lock:
        try:
            result = await asyncio.wait_for(
                asyncio.get_event_loop().run_in_executor(_executor, _recognize_and_correct, data),
                timeout=INFERENCE_DEADLINE_S,
            )
        except asyncio.TimeoutError:
            # The wedged inference can't be interrupted in-process; SIGKILL its worker so it actually
            # dies (no leaked thread pinning CPU/RAM), and swap in a fresh pool for the next request.
            # Safe under the lock — no sibling future is on the pool. `_executor` is None in unit tests
            # (default in-process executor; nothing to kill).
            log.warning("OCR timed out after %ss (%d bytes); killing + respawning the worker", INFERENCE_DEADLINE_S, len(data))
            _swap_executor(_executor, kill=True)  # SIGKILL the wedged worker; /health -> loading until re-warmed
            raise HTTPException(status_code=504, detail="recognition timed out")
        except UnreadableImageError:
            # UX2-P1-7: distinct from a transport/engine failure — this is the image's own fault, not the
            # sidecar's, so it must NOT collapse into the same 5xx a real outage gets (retrying won't help).
            log.info("OCR received undecodable image bytes (%d bytes)", len(data))
            raise HTTPException(status_code=422, detail="unrecognized image format")
        except BrokenProcessPool:
            # A worker died unexpectedly (OOM-kill, native segfault, a failed respawn-init). The pool is
            # permanently broken, so rebuild it for the NEXT request instead of 500-ing forever (N6).
            log.warning("OCR worker pool broke (%d bytes); rebuilding", len(data))
            _swap_executor(_executor, kill=False)  # rebuild; /health -> loading until re-warmed
            raise HTTPException(status_code=500, detail="recognition failed")
        except Exception:
            log.exception("OCR recognition failed (%d bytes)", len(data))
            raise HTTPException(status_code=500, detail="recognition failed")
    # A successful inference proves the worker is warmed (re-arms readiness after a respawn; OCR-P2-1).
    _ready = True
    log.info("ocr ok: %d bytes -> %d chars in %d ms", len(data), len(result["text"]), int(1000 * (time.time() - started)))
    return JSONResponse(result)
