# Real-packaging CER corpus (#105)

Drop **real tea-packaging photos** here to measure the true ru+en OCR CER — the empirical item the
synthetic proof (`../FINDINGS.md`) left owed. Photos + the real label file are **gitignored** (the
packaging is copyrighted and this is a privacy-first project); only this README + the example label
file are committed.

## How to use

1. Put photos in this dir: `*.jpg` / `*.jpeg` / `*.png` / `*.webp` / `*.bmp`.
   - Aim for a representative spread: portrait + landscape (EXIF orientation is honoured), good + poor
     light, flat + curved/glossy packaging, ru-only / en-only / mixed-script, printed + handwritten.
   - 20–50 photos already give a usable signal; more is better.
2. Create `ground_truth.tsv` (this file's sibling) — one line per photo you've labelled:

   ```
   # filename <TAB> the text you'd expect OCR to read off the package
   longjing_front.jpg	Лунцзин
   da-hong-pao_box.jpg	Да Хун Пао Wuyi Rock Tea
   ```

   Use the **name/key text** a user would scan (what should land in the review field) — not every
   word on the package. Blank lines and `#` comments are ignored. Photos without a row are still
   OCR'd + printed (for spot-checking) but excluded from the CER number.
3. Run (in the `ocrproof` venv — same pins as the sidecar):

   ```bash
   python ../measure_photos.py --corpus .
   ```

   Output: corpus CER %, exact-match %, ms/img, peak RSS, and per-photo ref-vs-hyp (in
   `../out/photo_cer.json`).

## Why this matters

The proof measured a **synthetic clean-text floor** (rendered Arial): clean ru CER ~4.5% / en ~0.07%.
Real packaging (curved/glossy surfaces, stylized fonts, low contrast, mixed scripts, glare) will be
**worse** — this corpus produces the real number that should gate any user-facing accuracy claim.
The harness applies the same engine config AND EXIF orientation (review F2) as the shipped path.
