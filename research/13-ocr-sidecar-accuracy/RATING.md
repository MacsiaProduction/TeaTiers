# Rating — 13-ocr-sidecar-accuracy

Prompt: ./prompt.md   ·   Date judged: 2026-06-18

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model     | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|-----------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus      |    5     |   5   |       5       |    1     |    5    |  4.40 |  1   |
| alice     |    4     |   4   |       4       |    2     |    4    |  3.40 |  2   |
| gemini    |    3     |   4   |       3       |    3     |    4    |  2.70 |  3   |
| deepseek  |    3     |   4   |       3       |    3     |    4    |  2.70 |  4   |
| deepagent |    2     |   2   |       2       |    4     |    3    |  1.50 |  5   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** opus — the only answer that *resolves* the provenance question instead of asserting it: it
argues RapidOCR's `eslav_PP-OCRv5_rec_mobile.onnx` is a conversion of the **same official Apache-2.0
PaddlePaddle eslav weights**, evidenced by the source `.pdiparams` SHA256 recorded in RapidOCR's
`default_models.yaml` matching the official HF file (a precise, CI-verifiable claim, flagged as such), gives
the exact provenance ladder the brief asked for (self-convert official → re-host the SHA-matched ONNX →
multilingual rec → Tesseract yardstick), anchors accuracy on the **real** eslav card figure (81.6% whole-line,
explicitly NOT a packaging CER) rather than inventing numbers, returns the footprint verdict in the required
form (**fits 4 GB** with concrete ONNX/concurrency/mem settings; resize only if measured RSS > ~3.5 GB), and
pins deps verified against PyPI. deepseek/gemini reach the right *model family* but ship deploy-blocking
errors (deepseek pins the legacy `rapidocr_onnxruntime` 1.4.4 that has **no PP-OCRv5**; gemini reuses one
commit SHA across three different repos and its flagship code snippet doesn't run against its own pins); both
fabricate CER/footprint numbers. deepagent is worst (Chinese `ch_` models passed off as multilingual, a CUDA
block in a CPU answer). alice is solid and honest but pins **no SHA** (undercutting the self-convert point)
and leaks citation markup into its code.

**Reuse (lift into `ocr-sidecar/` + the slice-1b plan):**
- **Models (ru+en):** rec `eslav_PP-OCRv5_rec_mobile` + `ppocrv5_eslav_dict.txt`; det `PP-OCRv5_mobile_det`
  (language-agnostic); angle-cls **skipped** by default. All Apache-2.0.
- **Provenance:** prefer **(a) self-convert the official Paddle `.pdiparams` → ONNX via `paddle2onnx` opset 17
  at build time and pin our own SHA256**; acceptable fallback **(a′) re-host RapidOCR's SHA-matched ONNX in
  our own storage + verify in CI**. **Disqualify** `monkt/paddleocr-onnx` as primary (unverified; its det is
  the 84 MB server model). **CI must byte-verify** the `.pdiparams` SHA match opus relies on before trusting it.
- **Accuracy stance:** treat 81.6% whole-line / 80.1% PP-OCRv5 weighted-avg as **lab upper bounds, not
  packaging guarantees** — real ru+en CER must be **measured** on the test matrix (100-seed names + photos).
  Make no hard CER promises.
- **Footprint:** **fits the current 4 GB VM** with: concurrency cap = 1, mobile models, load-once + warmup,
  ONNX `intra_op=2`/`inter_op=1` `ORT_SEQUENTIAL`, `enable_cpu_mem_arena=False`, container `mem_limit≈1g`
  `cpus 1.0`, JVM heap trimmable (LLM tier off). Resize 4→8 GB **only if measured steady RSS > ~3.5 GB**.
- **Preprocessing:** downscale to `limit_side_len≈960` + light contrast/CLAHE + optional cheap deskew; **skip**
  doc-orientation, UVDoc unwarping, and angle-cls by default (Paddle's own warning: unwarp can *lower* accuracy).
- **Pinned deps:** `python:3.12-slim` (digest-pinned), `rapidocr==3.8.4` (NOT the yanked 3.8.2, NOT legacy
  `rapidocr-onnxruntime`), `onnxruntime==1.27.0` (CPU), `fastapi==0.136.x`, `uvicorn[standard]==0.49.0`,
  `opencv-python-headless`; build-only conversion stage `paddlepaddle==3.0.0` + `paddle2onnx`/`paddlex` (NOT in
  the runtime image). Bake models into the image (no runtime egress).
- **Hardening:** non-root, read-only FS + tmpfs, cap-drop ALL, no network egress, never log image bytes or text.

**Discard:** deepseek's `rapidocr_onnxruntime==1.4.4` (no PP-OCRv5 support) + its `inference.pdmodel`
conversion command (Paddle 3.0 ships `inference.json`); gemini's reused commit SHA across 3 repos + its
non-running `config=` snippet; deepagent's `ch_*` "multilingual" models + CUDA-in-CPU config + `set_session_option`
API; ALL uncited CER/WER and footprint/latency numbers from every model (measure instead); any GPL/CC-NC weights
(none slipped in). Verify the eslav `.pdiparams`↔ONNX byte-match in CI before relying on opus's provenance claim.

> LEADERBOARD: +1 Wins opus; +1 Runs judged for opus, alice, gemini, deepseek, deepagent.
