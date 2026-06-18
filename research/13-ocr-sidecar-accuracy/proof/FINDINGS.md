# Slice-1b OCR proof — measured findings (2026-06-18)

"Scaffold + measure first" (decision #100): get provenance/footprint/accuracy DATA before
committing the sidecar + compose wiring. Harness: [`run_proof.py`](run_proof.py) (pins in
[`requirements.txt`](requirements.txt)); run in a Python 3.12 venv. Reproduce:

```bash
conda create -y -n ocrproof python=3.12 && conda activate ocrproof
pip install -r research/13-ocr-sidecar-accuracy/proof/requirements.txt
python research/13-ocr-sidecar-accuracy/proof/run_proof.py   # writes out/report.json
```

Stack = the run-13 pins exactly: `rapidocr==3.8.4`, `onnxruntime==1.27.0`, eslav PP-OCRv5 mobile
rec + `ch_PP-OCRv5_det_mobile` (language-agnostic det), angle-cls **off**, ONNX
`intra_op=2`/`inter_op=1`, `enable_cpu_mem_arena=false`, det `limit_side_len=960` (max).

Corpus = the **live 100-tea catalog seed** (`server/.../seed/catalog-seed.json`): 233 unique ru+en
names, each rendered to a clean image and a degraded ("phone photo": +2.5° rotate, blur, JPEG q58)
variant → 466 OCR calls. **This is a synthetic floor — rendered Arial, not real packaging — so
clean-CER is optimistic and real photos must still be measured before any accuracy claim.**

## 1. Provenance gate — PASS ✅

The ONNX RapidOCR fetched byte-match the SHA256 pinned in its `default_models.yaml`, confirming
opus's run-13 provenance claim empirically. **This is the exact gate slice-1b CI must enforce.**

| model | size | SHA256 | match |
|-------|------|--------|:-----:|
| `eslav_PP-OCRv5_rec_mobile.onnx` | 7.91 MB | `08705d67…c489aab` | ✅ |
| `ch_PP-OCRv5_det_mobile.onnx`    | 4.82 MB | `4d97c44a…6ae78ae` | ✅ |

## 2. Footprint — fits the 4 GB VM, no resize ✅

Process RSS, in-process (macOS/ARM; Linux-container RSS will differ slightly but the order holds):

| stage | RSS |
|-------|-----|
| after import | 30 MB |
| after engine init | 135 MB |
| after warmup | 158 MB |
| **peak during 466-call batch** | **248 MB** |

Far under run-13's "resize only if steady RSS > ~3.5 GB". Add FastAPI/uvicorn (~tens of MB) and a
concurrency cap of 1 and the sidecar should sit well under ~400 MB resident. **Decision: keep the
4 GB / 2 vCPU VM; the slice-1b compose entry can use `mem_limit≈1g` with comfortable headroom.**
Throughput on this dev box: **~28 img/s, ~35 ms/img** (single-threaded path).

## 3. Accuracy (synthetic floor)

CER = char edit-distance / reference chars (lower = better); exact = whole-string match rate.

| variant | en CER | ru CER | en exact | ru exact |
|---------|:------:|:------:|:--------:|:--------:|
| clean    | 0.07% | 4.52%  | 99.2% | 80.2% |
| degraded | 4.47% | 22.0%  | 95.5% | 64.4% |

en is near-perfect clean. The ru numbers are **systematic and characterizable**, not random:

- **Cyrillic↔Latin homoglyph substitution** (dominant) — the eslav model carries a joint
  Cyrillic+Latin charset and picks the Latin twin for identical-looking glyphs:
  `Ассам→Accam`, `Лунцзин→Лунц3ин` (з→3), `Синча→Siнча`, `Лемонграсс→Lемонгрass`,
  `Русский→Russkiy`, `Дун→Dun`. Character-accurate to the eye, but a different codepoint.
- **`Г→Ф` confusion** on `Гу…` sequences: `Гуапянь→Фуапянь`, `Гуй→Фуй`, `Гун→Фун` — a genuine
  model weakness on this synthetic typeface.
- **case + box-split artifacts**: `иглы→иГЛЫ`, `М Маоцзянь`, `в вербена`.
- The **degraded ru 22%** is inflated by two-word **box-order swaps** (`Хуаншань Маофэн →
  Маофэн Хуаншань`) that whole-string CER penalizes but which aren't character errors.

## 4. Implications for slice 1b

- **Build it on the 4 GB VM as-is** — no resize (§2). Config = exactly what was measured (§ stack).
- **CI must byte-verify the two SHA256 in §1** before trusting a baked-in model (run-13's gate).
- **Homoglyph confusion is a known limitation, not a blocker:** the flow is OCR → *user reviews/edits
  the text* → it becomes `sourceText` → the resolver matches names, so a human catches `Accam`.
  Worth a note in the slice-3 review UI; a cautious server-side Cyrillic-homoglyph normalization
  could be a later enhancement (risky — must not corrupt genuinely-Latin text).
- **Still owed before any user-facing accuracy claim:** CER on *real* packaging photos (curved,
  low-contrast, stylized fonts, mixed scripts). This proof only establishes a clean-text floor.

## 5. Real-packaging CER harness (#105) — built, awaiting a photo corpus

[`measure_photos.py`](measure_photos.py) runs the **identical** sidecar engine config (+ EXIF
orientation, review F2) over a corpus of real photos and reports corpus CER / exact-match / ms-img /
peak RSS vs hand labels. Drop photos + a `ground_truth.tsv` into [`corpus/`](corpus/README.md)
(gitignored — copyrighted packaging + privacy) and run `python measure_photos.py --corpus corpus`.
Validated end-to-end on clean renders (CER 0.0% / exact 100%, peak RSS ~176 MB). **The real number
needs a real photo corpus** — the one input that can't be synthesized.
