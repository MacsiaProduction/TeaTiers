#!/usr/bin/env python3
"""
Empirical reconsideration of the OCR approach (post-measurement): does a homoglyph-fold post-process
and/or the pure-`cyrillic` rec model fix the dominant ru failure (Cyrillic↔Latin homoglyphs) WITHOUT
breaking en? Tested on the realistic-synthetic corpus (corpus-synth/, exact GT) + the real photos
(corpus/, name-capture). Run in the ocrproof venv. Prints deltas; results feed the design doc.
"""
from __future__ import annotations

import json
import sys
import unicodedata
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
from rapidfuzz import fuzz
from rapidfuzz.distance import Levenshtein

HERE = Path(__file__).resolve().parent

# Latin → Cyrillic lookalikes (incl. digit '3'→'з'). Applied per-token, ONLY to mostly-Cyrillic
# tokens, so genuine Latin (en names) is left alone.
L2C = {
    "A": "А", "B": "В", "E": "Е", "K": "К", "M": "М", "H": "Н", "O": "О", "P": "Р", "C": "С",
    "T": "Т", "X": "Х", "Y": "У", "a": "а", "c": "с", "e": "е", "o": "о", "p": "р", "x": "х",
    "y": "у", "3": "з", "m": "м", "k": "к", "B": "В",
}
CYR = lambda ch: "А" <= ch <= "я" or ch in "ЁёЇїІіЄєҐґ"
LAT = lambda ch: "A" <= ch <= "Z" or "a" <= ch <= "z"


def fold_token(tok: str) -> str:
    cyr = sum(1 for ch in tok if CYR(ch))
    lat = sum(1 for ch in tok if LAT(ch))
    # Mostly-Cyrillic token (and at least one Cyrillic letter) → fold Latin lookalikes to Cyrillic.
    if cyr > 0 and cyr >= lat:
        return "".join(L2C.get(ch, ch) for ch in tok)
    return tok


def fold(s: str) -> str:
    return " ".join(fold_token(t) for t in s.split())


def norm(s: str) -> str:
    return unicodedata.normalize("NFC", " ".join(s.split()))


def cer(rows) -> tuple[float, float]:
    d = sum(Levenshtein.distance(norm(r), norm(h)) for r, h in rows)
    rl = sum(len(norm(r)) for r, h in rows)
    ex = sum(1 for r, h in rows if norm(r) == norm(h))
    return (round(100 * d / rl, 2) if rl else 0.0, round(100 * ex / len(rows), 1) if rows else 0.0)


def build_engine(lang):
    from rapidocr import LangDet, LangRec, ModelType, OCRVersion, RapidOCR

    return RapidOCR(params={
        "Global.use_cls": False,
        "Det.lang_type": LangDet.CH, "Det.ocr_version": OCRVersion.PPOCRV5,
        "Det.model_type": ModelType.MOBILE, "Det.limit_side_len": 960, "Det.limit_type": "max",
        "Rec.lang_type": lang, "Rec.ocr_version": OCRVersion.PPOCRV5, "Rec.model_type": ModelType.MOBILE,
        "EngineConfig.onnxruntime.intra_op_num_threads": 2, "EngineConfig.onnxruntime.inter_op_num_threads": 1,
        "EngineConfig.onnxruntime.enable_cpu_mem_arena": False,
    })


def ocr_text(engine, path: Path, upscale_short_below: int = 0) -> str:
    img = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    # Conditional low-res upscale: only when the SHORT side is genuinely small (a low-res capture),
    # resize it up (bicubic) before OCR. Unconditional upscale regresses good images, so it's gated.
    if upscale_short_below:
        w, h = img.size
        short = min(w, h)
        if short < upscale_short_below:
            scale = 960 / short
            img = img.resize((round(w * scale), round(h * scale)), Image.BICUBIC)
    res = engine(np.array(img))
    txts = getattr(res, "txts", None)
    return " ".join(str(t) for t in txts) if txts else ""


def split_synth(per_photo, transform):
    ru = [(p["ref"], transform(p["hyp"])) for p in per_photo if p["file"].startswith("ru_")]
    en = [(p["ref"], transform(p["hyp"])) for p in per_photo if p["file"].startswith("en_")]
    return ru, en


def main():
    from rapidocr import LangRec

    # --- A) eslav baseline + B) eslav+fold, recomputed from the cached realistic-synth outputs ----
    synth = json.loads((HERE / "out" / "photo_cer_synth.json").read_text())["per_photo"]
    print("== realistic-synthetic (233 imgs), ru / en CER% (exact%) ==")
    for label, tf in [("eslav (baseline)", lambda s: s), ("eslav + homoglyph-fold", fold)]:
        ru, en = split_synth(synth, tf)
        print(f"  {label:26}  ru {cer(ru)}   en {cer(en)}")

    # --- C) cyrillic rec model, re-OCR'd on the synth corpus -------------------------------------
    synth_dir = HERE / "corpus-synth"
    if synth_dir.is_dir():
        gt = {}
        for line in (synth_dir / "ground_truth.tsv").read_text().splitlines():
            if "\t" in line and not line.startswith("#"):
                k, v = line.split("\t", 1); gt[k] = v
        eng_cyr = build_engine(LangRec.CYRILLIC)
        ru = []; en = []
        for f in sorted(synth_dir.glob("*.jpg")):
            ref = gt.get(f.name)
            if not ref:
                continue
            hyp = ocr_text(eng_cyr, f)
            (ru if f.name.startswith("ru_") else en).append((ref, hyp))
        print(f"  {'cyrillic model':26}  ru {cer(ru)}   en {cer(en)}   <- en expected to collapse")

    # --- Real photos: name-capture under each variant --------------------------------------------
    real = HERE / "corpus"
    gtr = {}
    for line in (real / "ground_truth.tsv").read_text().splitlines():
        if "\t" in line and not line.startswith("#"):
            k, v = line.split("\t", 1); gtr[k] = v
    photos = sorted(p for p in real.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"})
    eng_es = build_engine(LangRec.ESLAV)
    eng_cy = build_engine(LangRec.CYRILLIC)
    print("\n== real photos (n=%d): name-capture partial_ratio per variant ==" % len(photos))
    print(f"  {'file':14} {'name':16} {'eslav':>6} {'+fold':>6} {'cyr':>6} {'+upscale':>9}")
    agg = {"eslav": [], "fold": [], "cyr": [], "upscale": []}
    for p in photos:
        name = gtr.get(p.name, "")
        es = ocr_text(eng_es, p)
        cy = ocr_text(eng_cy, p)
        up = ocr_text(eng_es, p, upscale_short_below=500)  # eslav + conditional low-res upscale
        v_es = fuzz.partial_ratio(norm(name), norm(es))
        v_fo = fuzz.partial_ratio(norm(name), norm(fold(es)))
        v_cy = fuzz.partial_ratio(norm(name), norm(cy))
        v_up = fuzz.partial_ratio(norm(name), norm(up))
        for k, v in [("eslav", v_es), ("fold", v_fo), ("cyr", v_cy), ("upscale", v_up)]:
            agg[k].append(v)
        print(f"  {p.name:14} {name[:16]:16} {v_es:6.0f} {v_fo:6.0f} {v_cy:6.0f} {v_up:9.0f}")
    cap = lambda xs: f"{sum(1 for x in xs if x >= 85)}/{len(xs)} (mean {sum(xs)/len(xs):.0f})"
    print(f"  capture: eslav {cap(agg['eslav'])}  +fold {cap(agg['fold'])}  cyr {cap(agg['cyr'])}"
          f"  eslav+upscale {cap(agg['upscale'])}")


if __name__ == "__main__":
    main()
