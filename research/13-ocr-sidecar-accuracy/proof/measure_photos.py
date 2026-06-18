#!/usr/bin/env python3
"""
Real-packaging CER measurement (#105) — the empirical item the synthetic proof (run_proof.py /
FINDINGS.md) left owed. Runs the EXACT slice-1b sidecar engine config over a corpus of real tea-
packaging *photos* and reports character error rate (CER) vs hand-labelled ground truth, plus exact
match, throughput, and peak RSS.

Corpus layout (photos are NOT committed — copyrighted packaging + privacy; see corpus/README.md):
  <corpus>/                       (default: ./corpus, override with --corpus)
    *.jpg / *.png / *.jpeg / *.webp
    ground_truth.tsv              one line per labelled photo:  <filename> \\t <expected text>
                                  (blank lines + lines starting with # are ignored)

Run in the ocrproof venv (same pins as the sidecar):
  python measure_photos.py --corpus ./corpus
Photos with no ground-truth row are still OCR'd and printed (spot-check), but excluded from CER.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import unicodedata
from pathlib import Path

import psutil
from PIL import Image, ImageOps
from rapidfuzz.distance import Levenshtein

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def rss_mb() -> float:
    return psutil.Process().memory_info().rss / (1024 * 1024)


def norm(s: str) -> str:
    return unicodedata.normalize("NFC", " ".join(s.split()))


def build_engine():
    # IDENTICAL to the sidecar (ocr-sidecar/app.py) + the synthetic proof: eslav PP-OCRv5 mobile rec
    # + PP-OCRv5 mobile det, angle-cls OFF, ONNX intra2/inter1, no mem-arena, det downscale 960.
    from rapidocr import LangDet, LangRec, ModelType, OCRVersion, RapidOCR

    return RapidOCR(
        params={
            "Global.use_cls": False,
            "Det.lang_type": LangDet.CH,
            "Det.ocr_version": OCRVersion.PPOCRV5,
            "Det.model_type": ModelType.MOBILE,
            "Det.limit_side_len": 960,
            "Det.limit_type": "max",
            "Rec.lang_type": LangRec.ESLAV,
            "Rec.ocr_version": OCRVersion.PPOCRV5,
            "Rec.model_type": ModelType.MOBILE,
            "EngineConfig.onnxruntime.intra_op_num_threads": 2,
            "EngineConfig.onnxruntime.inter_op_num_threads": 1,
            "EngineConfig.onnxruntime.enable_cpu_mem_arena": False,
        }
    )


def recognize(engine, path: Path) -> str:
    import numpy as np

    # Honour EXIF orientation (the app applies it before upload — review F2 — so the measurement
    # must too, else portrait photos would score artificially badly).
    img = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    res = engine(np.array(img))
    txts = getattr(res, "txts", None)
    return " ".join(str(t) for t in txts) if txts else ""


def load_ground_truth(corpus: Path) -> dict[str, str]:
    gt = corpus / "ground_truth.tsv"
    labels: dict[str, str] = {}
    if not gt.exists():
        return labels
    for line in gt.read_text(encoding="utf-8").splitlines():
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#") or "\t" not in line:
            continue
        name, expected = line.split("\t", 1)
        labels[name.strip()] = expected.strip()
    return labels


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="corpus", help="dir with photos + ground_truth.tsv")
    ap.add_argument("--out", default="out/photo_cer.json")
    args = ap.parse_args()

    corpus = Path(args.corpus)
    if not corpus.is_dir():
        sys.exit(f"corpus dir not found: {corpus} — drop real packaging photos there (see corpus/README.md)")
    photos = sorted(p for p in corpus.iterdir() if p.suffix.lower() in IMG_EXT)
    if not photos:
        sys.exit(f"no images in {corpus}")
    labels = load_ground_truth(corpus)

    rss_import = rss_mb()
    engine = build_engine()
    import numpy as np

    engine(np.full((48, 160, 3), 255, dtype=np.uint8))  # warmup
    rss_warm = rss_mb()

    peak = rss_warm
    dist_sum = ref_sum = exact = labelled = 0
    per_photo: list[dict] = []
    unlabelled: list[dict] = []
    t0 = time.time()
    for p in photos:
        hyp = recognize(engine, p)
        peak = max(peak, rss_mb())
        ref = labels.get(p.name)
        if ref is None:
            unlabelled.append({"file": p.name, "recognized": norm(hyp)})
            continue
        d = Levenshtein.distance(norm(ref), norm(hyp))
        rl = len(norm(ref))
        dist_sum += d
        ref_sum += rl
        labelled += 1
        if norm(hyp) == norm(ref):
            exact += 1
        per_photo.append({
            "file": p.name, "ref": ref, "hyp": norm(hyp),
            "cer_pct": round(100 * d / rl, 1) if rl else None,
        })
    elapsed = time.time() - t0

    report = {
        "corpus": str(corpus),
        "photos": len(photos),
        "labelled": labelled,
        "unlabelled": len(unlabelled),
        "corpus_cer_pct": round(100 * dist_sum / ref_sum, 2) if ref_sum else None,
        "exact_match_pct": round(100 * exact / labelled, 1) if labelled else None,
        "ms_per_img": round(1000 * elapsed / len(photos), 1),
        "rss_mb": {"after_import": round(rss_import, 1), "after_warmup": round(rss_warm, 1),
                   "peak": round(peak, 1)},
        "per_photo": per_photo,
        "unlabelled_samples": unlabelled[:20],
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in ("per_photo", "unlabelled_samples")},
                     ensure_ascii=False, indent=2))
    if labelled == 0:
        print("\nNo ground-truth labels matched — add rows to corpus/ground_truth.tsv to get CER.")


if __name__ == "__main__":
    main()
