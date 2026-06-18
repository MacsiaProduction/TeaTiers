# 13-ocr-sidecar-accuracy — RapidOCR/PaddleOCR server sidecar: accuracy, models, footprint

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, deepseek.md, ...). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android tea tier-list app with a Kotlin/Spring Boot catalog
backend on a single Yandex Cloud VM. Target market is Russia first (RuStore + direct APK, no Google
Play Services). User data lives on-device; the backend stores only a shared tea catalog and holds no
user accounts.

We have **decided (decision #99, research run 10)** to do OCR **server-side** for MVP: a user opts in
per photo, the app POSTs a single packaging image to `POST /api/v1/teas/ocr`, the backend forwards it
to an **internal RapidOCR/PaddleOCR sidecar** (FastAPI + ONNX Runtime, private compose network, image
held in memory only and never stored), and the recognized text is returned for the user to review
before it becomes the `sourceText` that grounds flavor/blurb enrichment. The backend contract (rate
limit, size cap, sanitization, RFC-7807 errors) already ships; the sidecar itself is "slice 1b".

**Hard constraints:** Chinese/Hanzi OCR is **out of scope** (decision #94) — we need **Russian
(Cyrillic) + English (Latin) only**. The sidecar must fit on a **single 4 GB RAM / 2 vCPU (50%
core-fraction) Yandex VM** that already runs a ~1.5 GB-heap JVM + Postgres + Caddy, so RAM/CPU
footprint and cold-start matter. Apache-2.0 / permissive licensing only (no GPL/CC-BY-NC weights).

## Objective

Pin the exact, verified implementation of the RapidOCR/PaddleOCR PP-OCRv5 server sidecar for ru+en
tea packaging, quantify its real accuracy and resource footprint on this VM, and decide preprocessing —
so slice 1b can be built and deployed without surprises.

## Questions

1. **Models — exact artifacts, sources, licenses, sizes.** For a ru+en server pipeline, name the exact
   detection + recognition (+ optional angle-classifier) ONNX models to use, with their **download
   source (official PaddlePaddle/PaddleOCR vs RapidAI vs a community repo), license, file size, and a
   pinned version/commit/SHA**. Specifically assess the **East-Slavic (eslav) PP-OCRv5 mobile rec
   model**: is there an official ONNX release, or only a community conversion (e.g. `monkt/paddleocr-onnx`)?
   Is there a server-grade (non-mobile) eslav variant? The eslav rec model is reported to be trained on
   only ~7,000 text images — what does that imply for production Cyrillic accuracy, and is there a better
   Cyrillic option (e.g. a multilingual PP-OCRv5 rec, Tesseract `rus`+`eng`, or another maintained engine)?
   Also assess **self-converting the official Apache-2.0 PaddlePaddle eslav weights to ONNX with `paddle2onnx`
   at build time** (so we own a pinned SHA instead of trusting a third-party blob): is it viable, what are the
   exact tool/versions and steps, and does it yield a usable model?
   **Provenance rule for the recommendation:** a third-party ONNX blob (e.g. `monkt/paddleocr-onnx`) baked
   into a server image is **disqualified as the primary** unless we re-host/re-verify it ourselves. Prefer,
   in order: (a) a self-converted official eslav ONNX with our own SHA, (b) a multilingual PP-OCRv5 rec (state
   its Cyrillic training share), (c) **Tesseract `rus`+`eng`** as the conservative, provenance-clean fallback
   to measure against. The **deciding criterion is CER on real ru+en packaging** (Q3); provenance is a weighted
   cost, not a tiebreaker — accuracy must justify any provenance compromise, not the reverse.
2. **Library stack & pinned versions.** RapidOCR (`rapidocr-onnxruntime` / `rapidocr_onnxruntime` / the
   2025+ `rapidocr` package) vs calling PaddleOCR/ONNX Runtime directly: which is the right maintained
   choice for a FastAPI sidecar in 2026? Give exact pinned versions of `rapidocr*`, `onnxruntime`,
   `fastapi`, `uvicorn`, and the recommended Python base image. Flag anything you are not certain exists.
3. **Accuracy on real tea packaging.** How well will the chosen stack read ru+en text on: small curved
   packages, glossy/reflective labels, mixed Cyrillic+Latin on one label, multi-line small print, and
   low-light phone photos? Give realistic CER/WER expectations and the failure modes to expect. Cite
   benchmarks only if official or reproducible.
4. **Preprocessing — does it pay off, and which?** Given the small eslav training set, evaluate whether
   to enable PP-OCRv5's document-orientation / text-image-unwarping / angle-classifier stages, plus
   classic preprocessing (deskew, contrast/CLAHE, downscale-to-max-dimension). What measurably improves
   ru+en packaging OCR vs what just adds latency/RAM?
5. **Resource footprint & ops on a 4 GB / 2 vCPU VM.** Quantify resident memory (idle + during
   inference), model-load/cold-start time, and per-image latency for a single-request sidecar. Will it
   fit alongside a 1.5 GB JVM + Postgres + Caddy on 4 GB? **Optimize for the current 4 GB box first** —
   concurrency capped at 1, mobile (not server) models, load-model-once + warmup, ONNX thread caps, and a JVM
   heap trimmed below 1.5 GB if needed (the LLM tier is off) — then give the resize verdict in this exact form:
   **"fits 4 GB with settings X"** OR **"does not fit — needs N GB, +₽Y/mo"** with a concrete number (resizing
   is acceptable when the data justifies it, but only after the 4 GB levers are exhausted). Recommend
   ONNX Runtime threading settings (`intra_op`/`inter_op`, session options), a concurrency cap, and
   container `mem_limit`/`cpus`. How should the sidecar be hardened (non-root, read-only FS, cap-drop,
   no network egress, no logging of image bytes or recognized text)?
6. **Integration & test plan.** A concrete FastAPI `/ocr` endpoint sketch (multipart in, `{text}` out),
   health endpoint, model-warmup-on-start, and a Dockerfile that **bakes the model assets into the image,
   pinned by SHA, with the sidecar having no network egress at runtime** (assume this — download-at-runtime is
   a non-goal; justify only if there's a hard reason against baking). A 15–20 image ru/en test matrix (the
   failure modes in Q3) with a pass/fail rubric tied to CER, plus the low-confidence fallback behavior.

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts.
- Pin exact versions, model filenames, and licenses; explicitly flag anything you are not certain exists
  (community ONNX conversions, unofficial Maven/PyPI coords, size claims).
- Cite every claim with a link and its publication/update date; prefer recent (2025–2026) sources.
- Treat benchmark and footprint numbers skeptically unless official or reproducible.

## Return

1. A model table: det / rec / cls artifacts × {source, license, size, pinned version/SHA, ru+en fit}, plus a
   **provenance-ranked recommendation** (self-converted official eslav ONNX > multilingual PP-OCRv5 rec >
   Tesseract `rus`+`eng`), decided by CER on real packaging.
2. A pinned dependency + base-image list for the FastAPI sidecar (incl. `paddle2onnx` if self-conversion wins).
3. Realistic accuracy expectations + failure modes for ru+en tea packaging, and the preprocessing verdict.
4. A resource-footprint verdict in the **"fits 4 GB with settings X / needs N GB +₽Y/mo"** form, with ONNX
   threading + container limit settings and the 4 GB-first optimizations applied.
5. A FastAPI `/ocr` + **models-baked-in, no-egress** Dockerfile sketch and a 15–20 image ru/en test plan with
   a CER rubric.
6. A "do not do" list (wrong models, GPL/NC weights, unverified coords, runtime model downloads, footprint traps).

---

Models run: <opus, gemini, deepseek>   ·   Date: <YYYY-MM-DD>

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
