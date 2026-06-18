#!/usr/bin/env python3
"""
Generate a REALISTIC-synthetic packaging corpus for the CER measurement (#105), for use when no real
photos are available. Renders each ru+en seed name onto a packaging-like image with moderate (not
adversarial) phone-photo conditions: textured/colored background, a varied font, a perspective tilt,
an uneven-lighting gradient + a glare highlight, light blur, sensor noise, and JPEG compression. The
exact rendered string is the ground truth (so labels are perfect + reproducible), and the difficulty
sits between the clean floor and true real-world packaging.

  python gen_realistic.py --out corpus-synth [--variants 1] [--seed 13]
  python measure_photos.py --corpus corpus-synth

This is still SYNTHETIC — a stronger floor, not a substitute for real-photo CER. Output dir is
gitignored.
"""
from __future__ import annotations

import argparse
import os
import random
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_proof  # reuse load_corpus()

FONTS = [
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/System/Library/Fonts/Supplemental/Verdana.ttf",
    "/System/Library/Fonts/Supplemental/Georgia.ttf",
    "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
    "/System/Library/Fonts/Supplemental/Trebuchet MS.ttf",
    "/System/Library/Fonts/Supplemental/Tahoma.ttf",
]
FONTS = [f for f in FONTS if os.path.exists(f)]
# Tea-packaging-ish background colours (kraft, greens, tins, blacks, creams).
BG = [(196, 170, 120), (60, 90, 60), (30, 60, 45), (210, 200, 180), (40, 40, 45),
      (150, 60, 50), (235, 230, 220), (70, 100, 120), (120, 95, 70)]


def _bg(w: int, h: int, rng: random.Random) -> np.ndarray:
    a = np.array(rng.choice(BG), dtype=np.float32)
    b = np.array(rng.choice(BG), dtype=np.float32)
    grad = np.linspace(0, 1, w, dtype=np.float32)[None, :, None]  # left→right gradient
    img = a[None, None, :] * (1 - grad) + b[None, None, :] * grad
    img = np.repeat(img, h, axis=0)
    img += rng.uniform(4, 14) * np.random.randn(h, w, 3).astype(np.float32)  # paper grain
    return np.clip(img, 0, 255)


def _luminance(c) -> float:
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def _draw_text(canvas: np.ndarray, text: str, rng: random.Random) -> np.ndarray:
    h, w, _ = canvas.shape
    img = Image.fromarray(canvas.astype(np.uint8))
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(rng.choice(FONTS), rng.randint(58, 92))
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    if tw > w - 40:  # shrink to fit width
        font = ImageFont.truetype(font.path, max(28, int(font.size * (w - 40) / tw)))
        bbox = draw.textbbox((0, 0), text, font=font)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = rng.randint(20, max(21, w - tw - 20)) - bbox[0]
    y = rng.randint(20, max(21, h - th - 20)) - bbox[1]
    base = np.array(_bg(1, 1, rng)[0, 0])  # sample a bg-ish colour for contrast decision
    avg = _luminance(canvas.mean(axis=(0, 1)))
    fill = (245, 244, 240) if avg < 128 else (25, 22, 20)
    draw.text((x, y), text, fill=fill, font=font)
    return np.array(img).astype(np.float32)


def _perspective(img: np.ndarray, rng: random.Random) -> np.ndarray:
    h, w, _ = img.shape
    j = 0.08  # moderate tilt
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    dst = np.float32([[rng.uniform(0, j) * w, rng.uniform(0, j) * h],
                      [w - rng.uniform(0, j) * w, rng.uniform(0, j) * h],
                      [w - rng.uniform(0, j) * w, h - rng.uniform(0, j) * h],
                      [rng.uniform(0, j) * w, h - rng.uniform(0, j) * h]])
    m = cv2.getPerspectiveTransform(src, dst)
    return cv2.warpPerspective(img, m, (w, h), borderMode=cv2.BORDER_REPLICATE)


def _lighting(img: np.ndarray, rng: random.Random) -> np.ndarray:
    h, w, _ = img.shape
    gx = np.linspace(rng.uniform(0.7, 1.0), rng.uniform(0.9, 1.15), w, dtype=np.float32)
    img = img * gx[None, :, None]
    # glare highlight
    cx, cy = rng.randint(0, w), rng.randint(0, h)
    yy, xx = np.mgrid[0:h, 0:w]
    r = rng.uniform(0.15, 0.30) * max(w, h)
    glare = np.exp(-(((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * r * r))).astype(np.float32)
    img = img + glare[:, :, None] * rng.uniform(30, 80)
    return np.clip(img, 0, 255)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="corpus-synth")
    ap.add_argument("--variants", type=int, default=1)
    ap.add_argument("--seed", type=int, default=13)
    args = ap.parse_args()
    if not FONTS:
        sys.exit("no Cyrillic-capable fonts found")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    corpus = run_proof.load_corpus()

    gt_lines = ["# realistic-synthetic corpus (gen_realistic.py) — exact ground truth"]
    n = 0
    for i, item in enumerate(corpus):
        text = item["text"]
        for v in range(args.variants):
            rng = random.Random(args.seed * 100003 + i * 17 + v)  # reproducible per item/variant
            np.random.seed((args.seed * 100003 + i * 17 + v) % (2**32))
            w, h = rng.randint(820, 1100), rng.randint(460, 640)
            img = _bg(w, h, rng)
            img = _draw_text(img, text, rng)
            img = _perspective(img, rng)
            img = _lighting(img, rng)
            img = cv2.GaussianBlur(img, (0, 0), rng.uniform(0.4, 1.1))
            img += rng.uniform(2, 7) * np.random.randn(h, w, 3).astype(np.float32)  # sensor noise
            fname = f"{item['locale']}_{i:03d}_{v}.jpg"
            Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(
                out / fname, format="JPEG", quality=rng.randint(68, 88))
            gt_lines.append(f"{fname}\t{text}")
            n += 1
    (out / "ground_truth.tsv").write_text("\n".join(gt_lines) + "\n", encoding="utf-8")
    print(f"wrote {n} images + ground_truth.tsv to {out}/ (fonts: {len(FONTS)})")


if __name__ == "__main__":
    main()
