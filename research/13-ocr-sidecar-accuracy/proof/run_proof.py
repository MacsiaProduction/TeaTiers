#!/usr/bin/env python3
"""
Slice-1b OCR proof (research 13 / decision #100, "scaffold + measure first").

Goal: get DATA before committing the sidecar + compose wiring —
  1. confirm RapidOCR resolves the run-13-specced models and that the downloaded
     ONNX byte-match the pinned SHA256 (provenance gate),
  2. measure character error rate (CER) on a ru+en corpus built from the live
     100-tea catalog seed, on clean renders and a degraded ("phone photo") variant,
  3. measure the process RSS (resident memory) so the 4 GB -> 8 GB VM decision is
     data-driven (run-13: resize only if steady RSS > ~3.5 GB).

This is a SYNTHETIC floor (rendered text, not real packaging), so the clean-CER is
an optimistic bound and the degraded-CER a rough proxy. Real packaging CER must
still be measured on photos before any user-facing accuracy claim.

NOT a server/app dependency — runs in the ocrproof venv (see requirements.txt).
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import time
import unicodedata
from pathlib import Path

import psutil
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from rapidfuzz.distance import Levenshtein

REPO = Path(__file__).resolve().parents[3]
SEED = REPO / "server/src/main/resources/seed/catalog-seed.json"
OUT = Path(__file__).resolve().parent / "out"
IMG = Path(__file__).resolve().parent / "images"

# The two models run-13 locked, with the SHA256 recorded in RapidOCR's default_models.yaml
# (v3.8.4). We re-hash the downloaded files to prove byte-identity — the CI gate for slice 1b.
PINNED_SHA = {
    "eslav_PP-OCRv5_rec_mobile.onnx": "08705d6721849b1347d26187f15a5e362c431963a2a62bfff4feac578c489aab",
    "ch_PP-OCRv5_det_mobile.onnx": "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
}

FONT_CANDIDATES = [
    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/Library/Fonts/Arial.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]


def rss_mb() -> float:
    return psutil.Process().memory_info().rss / (1024 * 1024)


def pick_font(size: int) -> ImageFont.FreeTypeFont:
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                f = ImageFont.truetype(p, size)
                # Confirm Cyrillic coverage (Arial/DejaVu have it; bail if tofu-only).
                if f.getbbox("Тест")[2] > 0:
                    return f
            except Exception:
                continue
    raise SystemExit("No Cyrillic-capable TrueType font found in FONT_CANDIDATES")


def load_corpus() -> list[dict]:
    data = json.loads(SEED.read_text(encoding="utf-8"))
    teas = data["teas"] if isinstance(data, dict) and "teas" in data else data
    seen: set[tuple[str, str]] = set()
    corpus: list[dict] = []
    for t in teas:
        for n in t.get("names", []):
            loc = n.get("locale")
            txt = (n.get("name") or "").strip()
            if loc in ("en", "ru") and txt:
                key = (loc, txt)
                if key in seen:
                    continue
                seen.add(key)
                corpus.append({"locale": loc, "text": txt})
    return corpus


def render(text: str, font: ImageFont.FreeTypeFont, degraded: bool) -> Image.Image:
    pad = 24
    bbox = font.getbbox(text)
    w, h = bbox[2] - bbox[0] + 2 * pad, bbox[3] - bbox[1] + 2 * pad
    img = Image.new("RGB", (max(w, 64), max(h, 48)), "white")
    d = ImageDraw.Draw(img)
    d.text((pad - bbox[0], pad - bbox[1]), text, fill=(15, 15, 15), font=font)
    if not degraded:
        return img
    # Approximate a hand-held phone photo of packaging: slight rotation, blur, faint
    # background tint, and JPEG recompression. A rough proxy, not real packaging.
    img = img.rotate(2.5, expand=True, fillcolor="white", resample=Image.BICUBIC)
    img = img.filter(ImageFilter.GaussianBlur(0.8))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=58)
    buf.seek(0)
    return Image.open(buf).convert("RGB")


def norm(s: str) -> str:
    # Collapse whitespace + NFC so spacing/box-split artifacts don't inflate CER.
    return unicodedata.normalize("NFC", " ".join(s.split()))


def cer(ref: str, hyp: str) -> tuple[int, int]:
    ref, hyp = norm(ref), norm(hyp)
    return Levenshtein.distance(ref, hyp), len(ref)


def recognized_text(engine, img: Image.Image) -> str:
    import numpy as np

    arr = np.array(img)  # RGB
    res = engine(arr)
    txts = getattr(res, "txts", None)
    if txts is None and isinstance(res, (list, tuple)) and res:
        first = res[0]
        if isinstance(first, (list, tuple)):
            txts = [b[1] for b in first if isinstance(b, (list, tuple)) and len(b) > 1]
    if not txts:
        return ""
    return " ".join(str(x) for x in txts)


def find_model_files() -> dict[str, Path]:
    import rapidocr

    root = Path(rapidocr.__file__).parent
    found: dict[str, Path] = {}
    for name in PINNED_SHA:
        for p in root.rglob(name):
            found[name] = p
            break
    # RapidOCR may also cache under ~/.cache or a models dir; search broadly as a fallback.
    if len(found) < len(PINNED_SHA):
        for base in [Path.home() / ".cache", root.parent]:
            for name in PINNED_SHA:
                if name in found:
                    continue
                for p in base.rglob(name):
                    found[name] = p
                    break
    return found


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    h.update(p.read_bytes())
    return h.hexdigest()


def main() -> None:
    OUT.mkdir(exist_ok=True)
    IMG.mkdir(exist_ok=True)
    report: dict = {"env": {}, "provenance": {}, "rss_mb": {}, "accuracy": {}}

    rss_import = rss_mb()
    from rapidocr import LangDet, LangRec, ModelType, OCRVersion, RapidOCR

    # eslav PP-OCRv5 mobile rec + PP-OCRv5 mobile det (language-agnostic "ch" det); angle-cls OFF;
    # ONNX intra=2/inter=1, no mem arena; det downscale limit 960 (run-13 preprocessing).
    engine = RapidOCR(
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
    rss_init = rss_mb()

    # --- Provenance gate: the downloaded ONNX must byte-match the pinned SHA256. ---
    files = find_model_files()
    for name, want in PINNED_SHA.items():
        p = files.get(name)
        if not p:
            report["provenance"][name] = {"status": "NOT_FOUND"}
            continue
        got = sha256(p)
        report["provenance"][name] = {
            "path": str(p),
            "size_bytes": p.stat().st_size,
            "sha256": got,
            "pinned": want,
            "match": got == want,
        }

    font = pick_font(48)
    corpus = load_corpus()

    # Warmup (excluded from timing/RSS-peak baseline).
    _ = recognized_text(engine, render("Чай", font, degraded=False))
    rss_warm = rss_mb()

    peak = rss_warm
    agg = {
        "clean": {"ru": [0, 0], "en": [0, 0]},
        "degraded": {"ru": [0, 0], "en": [0, 0]},
    }
    exact = {"clean": {"ru": [0, 0], "en": [0, 0]}, "degraded": {"ru": [0, 0], "en": [0, 0]}}
    samples: list[dict] = []
    t0 = time.time()
    for i, item in enumerate(corpus):
        loc, text = item["locale"], item["text"]
        for variant in ("clean", "degraded"):
            img = render(text, font, degraded=(variant == "degraded"))
            hyp = recognized_text(engine, img)
            dist, reflen = cer(text, hyp)
            agg[variant][loc][0] += dist
            agg[variant][loc][1] += reflen
            exact[variant][loc][1] += 1
            if norm(hyp) == norm(text):
                exact[variant][loc][0] += 1
            peak = max(peak, rss_mb())
            if i < 12:  # keep a few qualitative examples
                samples.append({"locale": loc, "variant": variant, "ref": text, "hyp": norm(hyp)})
    elapsed = time.time() - t0
    n_calls = len(corpus) * 2

    def pct(d_n):
        d, n = d_n
        return round(100 * d / n, 2) if n else None

    report["env"] = {"python": sys.version.split()[0], "calls": n_calls}
    report["rss_mb"] = {
        "after_import": round(rss_import, 1),
        "after_engine_init": round(rss_init, 1),
        "after_warmup": round(rss_warm, 1),
        "peak_during_batch": round(peak, 1),
    }
    report["accuracy"] = {
        "corpus_size": len(corpus),
        "cer_pct": {v: {loc: pct(agg[v][loc]) for loc in ("ru", "en")} for v in agg},
        "exact_match_pct": {
            v: {loc: (round(100 * exact[v][loc][0] / exact[v][loc][1], 1) if exact[v][loc][1] else None)
                for loc in ("ru", "en")}
            for v in exact
        },
        "throughput_imgs_per_s": round(n_calls / elapsed, 2),
        "ms_per_img": round(1000 * elapsed / n_calls, 1),
    }
    report["samples"] = samples

    (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
