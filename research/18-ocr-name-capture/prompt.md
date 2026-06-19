# 18-ocr-name-capture — improving tea-NAME capture from real packaging photos (local-first, RU)

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android (Kotlin + Compose) tier-list app for teas, **Russia-first**,
**no accounts / no PII**, GMS-free. A small Kotlin/Spring catalog backend runs on a **single 4 GB / 2 vCPU
Yandex Cloud VM** (Caddy + Spring + Postgres + an OCR sidecar, ~3.4 GB committed). One feature: the user
photographs a tea package, the app uploads it to a **first-party OCR sidecar**, and the recognized text is
shown so the user **confirms / edits the tea name** before it’s saved (review-before-`sourceText`). The
catalog is the source of truth; OCR just gives the user a starting point.

**Current OCR (decisions #96/#105/#114):** a local **RapidOCR 3.8.4** sidecar (ONNX), **PP-OCRv5 eslav
(East-Slavic) mobile rec + PP-OCRv5 mobile det**, angle-cls off, det downscale 960, conditional low-res
upscale, concurrency 1, models baked in. It was chosen **local / no-egress** for privacy: the photo goes
device → our VM only, **never to an external OCR API**. That no-egress property (#96) is a product value,
not an accident.

**What we just measured (research/13 `proof/FINDINGS.md`, n=10 real photos, 2026-06-19).** Against a
head-to-head with two AI-vision OCRs (Yandex **Alice**, **DeepSeek**) on the same photos:

- **Our raw name-capture is weak:** strict capture (`rapidfuzz.partial_ratio ≥ 85`) **2/10 (20%)**, **mean
  name similarity 70.4/100**; human-judged name-quality mean **66.7** (3 letter-clean / 6 garbled-but-
  recoverable / 1 outright fail). Alice scored 97 (8/2/0), DeepSeek 98 (9/1/0) — the AI-vision engines
  essentially nail these photos, including handwriting.
- **The dominant defect is mechanical:** Cyrillic↔Latin/digit **homoglyph substitution** even on clean
  high-contrast print — `Гунфу→Фyнфy`, `ХУН→XYH`, `Н→H`, `В→B`, `Ш→W`, `з→3`, `а→9` — plus case
  mismatches and word-order/space slips. The names are **~70% correct, degraded not random.**
- **The real gap is handwriting:** cursive / stylized marker names (#9 “Gaba Ladykiller”, #10 “Дун Фан Мэй
  Жэнь”) are a **failure cliff** — #10 came out as wrong-script Latin garbage (`Dyn.Pam MaiXan`), not
  recoverable. Homoglyph fixes can’t help (the script itself is wrong).

So: keep the local engine for the MVP (review UX tolerates it), but **close the gap** without lightly
throwing away no-egress. This run decides how.

Hard constraints your answer MUST respect:

- **Local / no-egress is the default and strongly preferred.** Any option that sends the photo to an
  external API breaks #96 and must be justified as an **explicit, opt-in, narrowly-scoped fallback** only —
  never the default path. Flag egress clearly.
- **Fits the 4 GB / 2 vCPU VM** alongside the existing stack (~3.4 GB committed, OCR concurrency 1). A new
  local model must fit CPU + RAM there (the current sidecar peaks ~0.7–1.2 GB). No GPU.
- **Russian-first** (Cyrillic), with some pinyin/English on labels; Chinese glyphs are out of scope (the
  eslav model skips them, fine).
- Pin **exact versions** for every library/model/API you propose; flag anything you’re unsure exists.

## Objective

Decide **how to raise tea-NAME capture** from real RU packaging, in priority order: (1) a cheap local
post-processor for the homoglyph/case/fuzzy-match gap, (2) local handwriting handling, and (3) a go/no-go
framing for an optional opt-in AI-vision fallback — without giving up no-egress for the 90% the local
engine already handles.

## Questions

**Track 1 — local post-correction (highest ROI; keep no-egress).**
1. Design a **Cyrillic↔Latin/digit confusable normalizer** for OCR output: the concrete confusable map
   (which Latin/digit glyphs map back to which Cyrillic, e.g. `y→у`, `X→Х`, `H→Н`, `B→В`, `W→Ш`, `3→з`,
   `6→б`, `p→р`, `9→а`?), and the **decision rule** for when to apply it (per-token “is this token mostly
   Cyrillic / does it sit in a Cyrillic-majority line?” — how to detect script-mixing robustly without
   corrupting genuinely-Latin tokens like `HONG LO` / `Gaba`). Reference Unicode confusables data
   (e.g. `confusables.txt` / `unicodedata`) and any maintained library.
2. How should the corrected OCR text be **matched against the existing Postgres catalog** to recover the
   canonical name — case-insensitive + accent-insensitive fuzzy match (we already use pg_trgm for search;
   `rapidfuzz` on the server)? What similarity threshold + tie-breaking avoids false matches while
   recovering the “70% similar” cases? Should this run in the **sidecar (Python)** or the **Spring server**?
3. Roughly how much of our measured gap would Track 1 close (the 6 “garbled-but-recoverable” cases)? What
   does it NOT fix?

**Track 2 — local handwriting / stylized names (the real gap).**
4. Can the **current RapidOCR/PP-OCR stack** be improved on handwritten Cyrillic via **preprocessing only**
   (adaptive binarization, deskew, contrast/CLAHE, underline/line removal, denoise) — what concrete
   OpenCV/Pillow pipeline, and is it safe to apply unconditionally or must it be conditional (it regressed
   us before on good shots — see the upscale lesson, #114)?
5. Is there a **local, CPU, ONNX-able handwriting/STR model** for Cyrillic that fits the VM — e.g. a
   PP-OCRv5 server rec, a different RapidOCR rec model, EasyOCR, TrOCR-small, or a Cyrillic HTR model? Pin
   exact models + sizes + license + ru support, and estimate CPU latency / RAM on 2 vCPU. Is any of them
   actually better on cursive Cyrillic than what we have, or is handwriting just hard for all local CPU OCR?

**Track 3 — optional opt-in AI-vision fallback (deferred decision; give us the facts).**
6. If we add a narrowly-scoped, **opt-in** AI-vision fallback **only** for photos the local engine fails
   (or that the user explicitly flags as handwritten): what’s the best **RU-reachable, no-VPN** option?
   Detail **Yandex Vision OCR** (the `ocr/v1` text-recognition API): exact endpoint, auth, RU-language
   support, **pricing per 1000 images**, latency, and data-retention/privacy terms (does Yandex retain the
   image? can it be disabled?). Compare to sending the image to an **LLM vision API** (we already call
   Yandex’s LLM tier). Note GMS/egress/PII implications and how to keep it strictly opt-in + per-photo.
7. Give a crisp **recommendation + sequencing**: do Track 1 now? Track 2 now or after? Is the AI-vision
   fallback worth it for the handful of handwritten cases, or should handwritten names just be manual entry
   for the MVP? Be decisive.

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts. Pin exact versions + model files +
  SHAs/sizes where relevant; explicitly flag anything you are not certain exists.
- For any external API (Yandex Vision etc.): cite the official pricing + data-retention pages with dates.
- Respect the constraints above: call out every option that breaks no-egress, needs GMS, or won’t fit the
  4 GB VM.

## Return

A prioritized plan: Track 1 design (confusable map + match strategy + where it runs), Track 2 verdict
(preprocessing pipeline + whether any local handwriting model is worth swapping in), and a Track 3 go/no-go
with the Yandex Vision facts. End with a one-paragraph recommendation we can turn into a decision.
