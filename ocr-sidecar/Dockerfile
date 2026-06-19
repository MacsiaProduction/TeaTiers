# TeaTiers OCR sidecar (slice 1b; research 13 / decision #105).
# Base pinned by digest (rule 40-devops); tag kept for readability.
# Refresh with: docker manifest inspect --verbose python:3.12-slim | jq '.[0].Descriptor.digest'

# --- Stage 1: fetch + SHA256-verify the pinned ONNX models. -------------------------------------
# Keeps curl/apt out of the runtime image and fails the build on any checksum mismatch
# (the slice-1b provenance gate). Models are baked in -> the runtime needs no network egress.
FROM python:3.14-slim@sha256:44dd04494ee8f3b538294360e7c4b3acb87c8268e4d0a4828a6500b1eff50061 AS models
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY models.lock fetch_models.sh ./
RUN sh fetch_models.sh /opt/ocr-models

# --- Stage 2: runtime. --------------------------------------------------------------------------
FROM python:3.14-slim@sha256:44dd04494ee8f3b538294360e7c4b3acb87c8268e4d0a4828a6500b1eff50061 AS runtime
# libgomp1 backs onnxruntime's thread pool; pillow + opencv-headless ship their own libs.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home --home-dir /app appuser
WORKDIR /app

COPY requirements.txt ./
# rapidocr depends on the FULL opencv-python (needs libGL/libX11/libxcb GUI libs) and pip installs
# it alongside our headless pin. On a headless server we want headless only — so drop both opencv
# variants and reinstall headless-only, which links no GUI libs (rapidocr imports cv2 either way).
RUN pip install --no-cache-dir -r requirements.txt \
 && pip uninstall -y opencv-python opencv-python-headless \
 && pip install --no-cache-dir opencv-python-headless==4.13.0.92

COPY --from=models /opt/ocr-models /opt/ocr-models
COPY app.py ./
ENV OCR_MODELS_DIR=/opt/ocr-models

USER appuser
EXPOSE 8099
# Single uvicorn worker = concurrency cap 1 (run-13): bounds CPU/RSS on the shared 4 GB VM.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8099", "--workers", "1"]
