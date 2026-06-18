# TeaTiers OCR sidecar (slice 1b)

A tiny FastAPI service that runs **RapidOCR** (ONNX, CPU) for the catalog server. The server proxies
a user-scanned tea-packaging photo here and gets back the recognized text; the user reviews/edits it
before it becomes a resolve `sourceText`. Research 13 / decisions #96 / #100 / #105.

Reachable **only on the private compose network** — never published. Image bytes and recognized text
are **never logged** (only sizes/timing/status).

## API (matches the server's `OcrClient`)

| method | path | request | response |
|--------|------|---------|----------|
| `POST` | `/ocr` | multipart, part `file` = image | `{"text": "<raw concatenated text>"}` |
| `GET`  | `/health` | — | `{"status": "ok"\|"loading"}` |

The server owns rate-limiting (`/teas/ocr` has its own window, decision #103), text sanitization,
length-capping, and the upload size cap; this service returns raw text and bounds the body
defensively (`OCR_MAX_IMAGE_BYTES`, default 10 MB).

## Models & provenance

Pinned in [`models.lock`](models.lock): eslav PP-OCRv5 **mobile rec** + `ch_PP-OCRv5_det_mobile`
(language-agnostic det), both Apache-2.0. The tiny `ch_ppocr_mobile_v2.0_cls_mobile` is also baked
even though angle-cls is off — RapidOCR downloads it at construction regardless, so baking it +
passing `Cls.model_path` keeps the runtime egress-free. [`fetch_models.sh`](fetch_models.sh) downloads them at
**build time** and `sha256sum -c` against the pinned digests — a checksum mismatch fails the build
(the provenance gate; the SHAs are RapidOCR 3.8.4's own, byte-verified by the slice-1b proof in
`research/13-ocr-sidecar-accuracy/proof/`). Models are baked into the image, so the **runtime makes
no network calls**. To move to fully self-converted ONNX (run-13's preferred provenance), swap the
URLs in `models.lock` and re-pin the SHAs.

## Engine config (measured, decision #105)

eslav rec + mobile det, angle-cls **off**, ONNX `intra_op=2`/`inter_op=1`, no CPU mem-arena, det
downscale `limit_side_len=960`. Loaded once at startup + warmed up. **Concurrency cap = 1** (single
uvicorn worker) so CPU/RSS stay bounded.

## Footprint (measured)

Peak RSS ~250 MB; ~35 ms/image on a dev box. Fits the 4 GB / 2 vCPU VM with headroom — **no resize**
(see the slice-1b proof). Prod compose caps it at `mem_limit 1g`, `cpus 1.5`.

## Build & run

```bash
docker build -t teatiers-ocr-sidecar ocr-sidecar      # fetches + verifies models, bakes them in
docker run --rm -p 8099:8099 teatiers-ocr-sidecar
curl -F file=@some-packaging.jpg http://localhost:8099/ocr
```

In the stack it runs as the `ocr-sidecar` service; the server points at it via
`TEATIERS_OCR_SIDECAR_URL` (e.g. `http://ocr-sidecar:8099`). Locally:
`docker compose --profile ocr up` (and set `TEATIERS_OCR_SIDECAR_URL`).
