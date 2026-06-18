# OCR approach — post-measurement reconsideration (2026-06-19)

A step-back re-weighing of the whole OCR approach now that we have **measured** data (synthetic +
real photos), not just the run-13 design bet. Produced by a multi-agent assess → adversarial-verify
workflow whose every decision-critical claim was fact-checked against primary sources and re-run
against the corpora in [`proof/`](proof/). Extends run 13; supersedes nothing — it confirms the
core choice and re-prioritizes what to do next.

## 1. What the data actually says

| measurement | ru | en | source |
|-------------|:--:|:--:|--------|
| synthetic clean CER | 4.5% | 0.07% | [`proof/FINDINGS.md`](proof/FINDINGS.md) §3 |
| realistic-synthetic CER (perspective/glare/fonts, n=233) | 9.2% | 0% | §5 |
| **real photos, name-capture (n=4)** | **3/4 (75%)** | — | §5 |

Two — and only two — failure modes, on both synthetic and real:
1. **Cyrillic↔Latin homoglyph substitution** (`Ассам→Accam`, `КPАСНЫЙ`, `250 гp`) — structural to the
   eslav model's joint Cyrillic+Latin charset.
2. **Low-resolution detection failure** — the *single* real-photo miss was a 430 px image where the
   detector lost half the name (`Фуцзянь Хун Ча → Фуцзянь`).

And the product context: OCR output is **never authoritative** — it's reviewed/edited by the user,
becomes `sourceText`, and the **pg_trgm resolver** (`name_norm = lower(f_unaccent(name))`, V4) fuzzy-
matches the name. So the product metric is **name-capture**, not whole-string CER, and most homoglyph/
case/diacritic drift is absorbed by review + fuzzy match.

## 2. The decisive empirical result

Tested locally ([`proof/reconsider_test.py`](proof/reconsider_test.py), confirmed twice incl. an
independent verifier re-run):

| variant | synth ru CER | synth en | real-photo capture |
|---------|:---:|:---:|:---:|
| eslav-mobile (current) | 9.2% | 0% | 3/4 (89) |
| + homoglyph-fold post-process | 8.1% (+6pp exact) | 0% | **3/4 (89)** ← no real gain |
| `cyrillic` rec model | 8.2% | 0% | **3/4 (88)** ← ≈ eslav |
| **eslav + conditional low-res upscale** | — | — | **4/4 (100)** ✅ |

The rec-level fixes (homoglyph-fold, cyrillic model) move the *synthetic* ru number a little but give
**zero** real-photo gain — because the real miss is **detection, not recognition**. **Conditional
upscaling of small images** (short side < 500 px → 960 px, bicubic, before OCR) recovers the miss
(`smm_1` 57→100) with **no regression** on the good shots. That is the highest-leverage, lowest-cost
lever, and it falls out of the data, not speculation.

## 3. Options weighed (verdicts after adversarial verification)

| # | Option | Verdict | Why (grounded in the data + verified sources) |
|---|--------|:-------:|-----------------------------------------------|
| **C** | **Conditional low-res upscale** (sidecar) + **client capture guidance** | **DO NOW** | The only change that moves real-photo capture **3/4 → 4/4**. Must be *conditional* (unconditional upscale regresses ru 9.2→17%, en 0→5.5%) + bicubic + pixel-capped (RAM). Directly attacks the one real miss. Trivial code. |
| **F** | **Keep-with-polish** — ship eslav-mobile as-is + capture UX + a "couldn't read it, re-scan" path | **DO NOW** | 75% name-capture *with human review + a fuzzy resolver* is good enough for an MVP whose OCR isn't authoritative. Don't re-architect a working feature; instrument real capture/edit rates in the wild and let data decide. |
| **D** | **Homoglyph handling** | **LATER, in the resolver** | The OcrSanitizer fold is a +small, en-safe polish but gives **zero** real-photo gain and review already catches it. If anywhere, fold Cyrillic↔Latin lookalikes at **resolve-match time** (catalog names are canonical Cyrillic) — a better, single place. Don't ship the OCR-side fold. |
| **A** | **Yandex Vision OCR** (cloud, en-ru model) | **PILOT** | The strongest *external* option: a dedicated en-ru model should dissolve the homoglyph class, and a production detector should beat the 8 MB mobile det on low-res — attacking *both* failure modes. ~$1.08/1000 printed (negligible at MVP scale, verified). Same trust boundary (image already reaches our Yandex-Cloud backend; server-side call ⇒ still GMS-free; 152-FZ UZ-1). **But** no measured win on our corpus (96–98% is marketing), and it adds a managed-service dependency on a path the free sidecar already covers. → **measure it on the real corpus via `measure_photos.py` head-to-head**, keep eslav default, use Vision as an optional/low-confidence fallback. |
| **B** | **Server-grade PP-OCRv5 models** | **SKIP** | Verified: **no server-grade Cyrillic/eslav REC model exists** (all PP-OCRv5 language recs are mobile-only; the only server rec is Chinese-charset). The server *det* exists (+5.7 Hmean) but is 84 MB / ~3× latency and wouldn't fix the homoglyph (charset) issue. Dead end. |
| **E** | **Tesseract / EasyOCR / on-device / rec-model swap** | **SKIP** | Tesseract degrades exactly on scene-text *detection* (our real failure); EasyOCR needs ~1.8 GB (> the sidecar cap); `cyrillic`≈`eslav` (measured); on-device runs the **same eslav weights** ⇒ identical accuracy, only a privacy upside that's already mitigated (opt-in/never-stored/never-logged). The live server choice is still right. |

## 4. Recommendation

**The core architecture is right — keep the self-hosted eslav-mobile server-side sidecar.** It's
free, private (no runtime egress, never logged/stored, opt-in per scan), GMS-free, fits the 4 GB VM,
and matches the AI-tier-OFF / solo-dev MVP. The reconsideration does **not** call for a re-architecture.

What to actually do, in priority order:

1. **Ship the conditional low-res upscale in the sidecar** (option C) — the one verified accuracy win
   (3/4 → 4/4). Gate on short side < ~500 px, bicubic, cap the upscaled pixel count (defends the RAM
   bound, decision #102/#106). *(Small `ocr-sidecar/app.py` change → code, so via PR.)*
2. **Client capture guidance** in the scan UI (option C/F) — a min-resolution check / "move closer for
   a clearer photo" hint + a graceful "couldn't read it, try again" path for a poor shot. Attacks the
   detection floor at the source.
3. **Instrument** real-world capture / re-scan / edit rates from day one — n=4 is a signal, not a
   number; let live data decide whether anything heavier (the Vision pilot) is ever warranted.
4. **PILOT Yandex Vision OCR** when more accuracy is wanted — measure its en-ru model on the real-photo
   corpus head-to-head; only adopt on a measured win **and** after confirming the
   `x-data-logging-enabled: false` header actually suppresses retention for the OCR endpoint.
5. **Defer homoglyph handling to the resolver** (option D), not the OCR sidecar.
6. **Grow the real-photo corpus** (`proof/corpus/`) so every number above firms up.

**SKIP:** server-grade models (no Cyrillic server rec), Tesseract/EasyOCR (worse/heavier), an
on-device swap (same weights), a rec-model swap (`cyrillic`≈`eslav`).

## 5. Open questions / verify-live before relying on them

- **Yandex Vision retention**: the `x-data-logging-enabled: false` header is documented for the LLM/
  text services but **not explicitly for the OCR endpoint** — confirm it applies before any "never
  retained" privacy copy. Also re-check the live RUB-with-VAT rate and synchronous latency on ~1600 px
  photos. Vision file limit is **10 MB** (not 20). The en-ru model is selected by `languageCodes=["ru","en"]`
  with no other languages (the `model` field selects page/handwritten/table, not the language model).
- **Upscale RAM**: bound the upscaled pixel count before `np.array` (an unbounded min-side upscale can
  blow RAM — PaddleOCR issue #16168: 25 GB on a pathological input). Our conditional gate + cap avoids it.
- **Sample size**: every real-photo conclusion rests on n=4. A larger corpus could shift the 75%/100%
  numbers — but it would shift them identically for every eslav-based engine, so it wouldn't change the
  *relative* verdicts.

## References (verified during this pass)
- Yandex Vision OCR pricing/API/models: https://aistudio.yandex.ru/docs/en/vision/pricing · https://yandex.cloud/en/docs/vision/concepts/ocr/
- PaddleOCR PP-OCRv5 model list / server-vs-mobile (det Hmean 0.827 vs 0.770; no Cyrillic server rec): https://paddlepaddle.github.io/PaddleOCR/ · RapidOCR `default_models.yaml`: https://github.com/RapidAI/RapidOCR
- Low-res preprocessing (PaddleOCR `limit_type=min` guidance; runaway-upscale RAM issue #16168): https://github.com/PaddlePaddle/PaddleOCR/issues/16168 · super-resolution OCR gain (Lat & Jawahar, ICPR 2018): https://doi.org/10.1109/ICPR.2018.8545609
- EasyOCR footprint / Tesseract4Android (Apache-2.0, no GMS): https://github.com/JaidedAI/EasyOCR · https://github.com/adaptech-cz/Tesseract4Android
- ML Kit has no Cyrillic model (why run-10's on-device override doesn't generalize): https://developers.google.com/ml-kit/vision/text-recognition/v2
