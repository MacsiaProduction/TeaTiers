# TeaTiers: Local-First RU Description OCR — Plan & Go Decision

## TL;DR
- **There is no server-tier Cyrillic recognizer to upgrade to.** PP-OCRv5's `server_rec` model card states it "aims to efficiently and accurately support the recognition of four major languages—Simplified Chinese, Traditional Chinese, English, and Japanese." Russian/Cyrillic exists *only* as mobile-tier rec (`eslav_PP-OCRv5_mobile_rec`, 7.5 MB; `cyrillic_PP-OCRv5_mobile_rec`, 7.7 MB). "Bigger VM → server rec" does **not** help RU text. The real local wins: keep the eslav mobile rec, **swap in the heavier server *detector* + raise detection resolution**, add resolution-aware preprocessing, and gate correction on a dictionary. Ship on a **4 vCPU / 16 GB Yandex Cloud Intel Ice Lake VM (~₽4,800/month compute)**.
- Realistic local CER on printed RU packaging paragraphs lands at roughly **6–15%** with the tuned stack (down from the degraded ~30% today) — "light edit," not "retype." It will not equal cloud AI-vision (~1–3% CER), but the gap is closable enough for a review-before-save flow that also seeds enrichment.
- **Replace the blind homoglyph normalizer with dictionary-gated correction** (homoglyph candidate accepted only if it yields a real RU word, via pymorphy3 validity + pyspellchecker/SymSpell frequency ranking; keep the raw token when nothing valid is found). A small local seq2seq corrector (`sage-fredt5-distilled-95m`, 95.6M params, MIT) is a possible later add but is autoregressive/slow on CPU; dictionary-gating is the better first cost/quality point. Cleaned text feeds the existing text-only enrichment tiers — the photo never leaves the VM, so decision #96 holds.

## Key Findings

### A1 — The "run 18" models, re-evaluated for a bigger CPU VM

**PP-OCRv5 SERVER rec does NOT support Cyrillic — verified.** The official `PaddlePaddle/PP-OCRv5_server_rec` Hugging Face model card reads: *"It aims to efficiently and accurately support the recognition of four major languages—Simplified Chinese, Traditional Chinese, English, and Japanese—as well as complex text scenarios such as handwriting, vertical text, pinyin, and rare characters using a single model."* The multilingual East-Slavic and Cyrillic recognizers are published **only** as mobile rec (PaddleOCR delivers its 106-language coverage — *"Korean, Spanish, French, Portuguese, German, Italian, Russian, Thai, Greek and more"* — through specialized mobile rec models, not `server_rec`). This is the single most important correction to the run-18 assumptions: upsizing the VM cannot buy a "server-tier Russian rec" because none exists upstream. Confirmed across PaddleOCR docs, the PP-OCRv5 multilingual model table, and the HF `eslav_PP-OCRv5_mobile_rec` / `cyrillic_PP-OCRv5_mobile_rec` cards.

Verified RU/Cyrillic rec options (all Apache-2.0, ONNX available via RapidOCR mirrors such as `monkt/paddleocr-onnx`):
- **`eslav_PP-OCRv5_mobile_rec`** — 7.5 MB ONNX; East-Slavic (Russian/Belarusian/Ukrainian); **line-exact accuracy 81.6%** on PaddleOCR's East-Slavic set (+31.4% vs previous gen). This is the currently shipped rec and remains the best-fit RU recognizer.
- **`cyrillic_PP-OCRv5_mobile_rec`** — 7.7 MB ONNX; 33 Cyrillic-script languages; **80.27%** line-exact accuracy. Broader script coverage, marginally lower headline accuracy; worth A/B-testing but unlikely to beat eslav on Russian.
- There is **no non-mobile Cyrillic rec.** Flagged explicitly: any "eslav/cyrillic server rec" id would be fabricated — do not deploy one.

**The heavier model you CAN actually deploy is the detector, not the recognizer.** `PP-OCRv5_server_det` is script-agnostic (detection is language-independent), and the ONNX detector is **84 MB** (`monkt/paddleocr-onnx` lists *"det.onnx # 84 MB - PP-OCRv5 detection"* vs *"rec.onnx # 7.5 MB"* per language). The server detector is materially stronger: PaddleOCR reports server-model average detection rising **from 0.662 (v4) to 0.827 (v5)**, and a **13-point end-to-end improvement** over PP-OCRv4_server on internal complex evaluation sets. Pairing **server det + eslav mobile rec** is the legitimate "spend RAM for fidelity" move: better localization of small description type, feeding the same RU recognizer. (For context on the mobile rec's efficiency, Baidu reports the 0.07B-param model processing *"over 370 characters per second on an Intel Xeon Gold 6271C CPU."*)

**Detection resolution (`det_limit_side_len`)** — official PP-OCRv5 CPU benchmark (Intel Xeon Gold 6271C, ~10 threads, 200 images):
- mobile, `max_640`: 1.31 s/img, 2023 MB peak
- mobile, `max_960` (≈ current): 1.49 s/img, 2049 MB peak
- mobile, `min_1280`: 2.00 s/img, 2233 MB peak
- server det, `min_736`: 4.34 s/img, 4021 MB peak
- server det, `min_1280`: 5.57 s/img, 4452 MB peak (full-pipeline peak host memory reaches ~5,993 MB in the 3.1.0 cross-CPU table)

Raising detection resolution materially improves recovery of small/dense description text (more, tighter boxes), at a roughly linear RAM/latency cost. run 13 dropped to 960 purely to fit 4 GB; with a bigger VM, move to **`min`-type, side_len 1280–1536**.

**Other local CPU stacks for full RU paragraphs:**
- **EasyOCR (ru)** — Apache-2.0; CRAFT detector (`craft_mlt_25k.pth`, 83 MB) + Cyrillic CRNN rec (gen-2 `cyrillic_g2.pth`, 215 MB). Real Cyrillic support and frequently strong on Russian, but ~1.8 GB resident after init and slow per-image CPU latency (community reports span several to tens of seconds/page; **no official CPU benchmark**). A viable quality challenger but heavier and slower than PP-OCRv5; note its known failure mode of forcing English loanwords into Cyrillic when the language list is fixed.
- **docTR (Mindee)** — Apache-2.0; **no ready pretrained Cyrillic recognizer** (pretrained rec targets English/French/Latin); Cyrillic requires training your own model. Not usable off-the-shelf for RU. (OnnxTR is an ONNX wrapper of the same models — same limitation.)
- **Surya** — code Apache-2.0 but **model weights are a modified AI-Pubs OpenRAIL-M license** (free only for orgs/individuals under ~$5M revenue/funding; earlier weights were CC-BY-NC-SA). Surya 2 is a 650M-param VLM served via vllm (GPU) or llama.cpp (CPU/Apple-Silicon). Heavy for a CPU sidecar and the weight license is a commercial-use flag. Not recommended for this constraint set.
- **TrOCR Cyrillic** (`raxtemur/trocr-base-ru` ~334M / 2.68 GB; `kazars24/trocr-base-handwritten-ru`; `cyrillic-trocr/trocr-handwritten-cyrillic`) — **handwriting-oriented**, autoregressive (slow on CPU), tuned for single text lines, not multi-line printed paragraphs. Wrong tool for printed descriptions.

### A2 — Realistic local CER and the operating point

The eslav rec's 81.6% is a **line-level exact-match** metric (a whole line counts "wrong" if any single character — including punctuation — is off), far more pessimistic than CER. On clean printed RU paragraphs, a tuned PP-OCRv5 pipeline (server det, higher detection resolution, resolution-aware preprocessing) realistically reaches **single-digit to low-double-digit CER (~6–15%)**. Cloud AI-vision (Yandex Alice / DeepSeek class) is materially better (~1–3% CER) thanks to strong language priors. On the practical "edit lightly vs retype" question, the tuned stack falls on the **edit-lightly** side once homoglyph noise is removed by dictionary-gated correction: most residual errors become a handful of per-paragraph fixes. It will not be flawless — which is exactly why the flow must stay **review-before-save**.

### A3 — Preprocessing for printed paragraphs

For *printed* paragraphs (unlike the name-crop case), a fixed pipeline is safe **only if resolution-aware and keep-best**, not blindly destructive. The #114 unconditional-upscale regression is the warning: upscaling already-large images wastes RAM/time and can soften edges. Recommended OpenCV/Pillow pipeline (pins: `opencv-python-headless==4.10.x`, `Pillow>=10.4`, `numpy<2.3`):
1. EXIF-orient; convert to grayscale.
2. **Conditional** upscale only if detected text height / image short side is below threshold (keep-best vs original by mean OCR confidence).
3. CLAHE (clipLimit≈2.0, tile 8×8) for local contrast — safe on printed labels.
4. Light denoise (`cv2.fastNlMeansDenoising`, h≈7) — safe; skip if it lowers mean detection confidence.
5. Deskew via min-area-rect on the text mask when |angle| > ~1.5°.
6. **Do NOT hard-binarize before PP-OCRv5** — it expects 3-channel/grayscale natural input; adaptive binarization (Sauvola) should be a *scored alternate candidate*, not unconditional. Keep the existing "keep-best across variants" guard.

### B4 — Description-safe correction

The shipped `confusable_normalize` is unsafe on free text because it maps Latin→Cyrillic *unconditionally* (Букет→Byкeт→**Вукет**): it turns garble into a plausible *wrong* word, worse than raw garble for a human reader. Replace it with **dictionary-gated, candidate-scored correction**:

Per token:
1. If protected (URL, known Latin brand like `artoftea.ru`, `Gaba`, pinyin, units г/мл/°C) → leave as-is (keep the existing guard).
2. Generate candidates: (a) raw token, (b) homoglyph-normalized variants — *all* plausible Latin→Cyrillic mappings, not just one (B→В **and** B→Б), (c) edit-distance-1 neighbours.
3. Accept the candidate that is a **real Russian word** with highest corpus frequency; if none is a real word, **keep the raw token** (never invent). Букет beats Вукет because Букет is a real, frequent word and Вукет is not.

Verified tools (all run comfortably at concurrency 1, sub-ms to low-ms per token):
- **pyspellchecker** — MIT; pure-Python; ships a Russian frequency dictionary (`language='ru'`, OpenSubtitles-derived); Levenshtein ≤2. Simplest drop-in for "real RU word + nearest correction."
- **SymSpell** — original is **LGPL-3.0** (copyleft; Python ports vary — verify the specific port's license); Symmetric-Delete, extremely fast; supports Cyrillic with a custom RU frequency dictionary (FrequencyWords / Wikipedia n-grams). Best if you need speed at scale or compound/word-segmentation.
- **pymorphy3** — MIT; OpenCorpora morphology for Russian/Ukrainian; `pymorphy3-dicts-ru` is MIT. Use as the *is-this-a-real-word* oracle (handles inflected forms a flat frequency list misses) and to prefer morphologically valid forms.
- **hunspell ru_RU** — tri-licensed (LGPL/MPL/GPL) dictionaries; mature but heavier integration; optional.

Recommended combination: **homoglyph-aware candidate generation → pymorphy3 validity check → pyspellchecker/SymSpell frequency ranking**, with a hard rule "if no candidate is a valid RU word, emit the raw OCR token." Use the "is the token mostly Cyrillic?" signal as a prior that *raises* the bar for accepting a Latin→Cyrillic remap (only remap when the result is a real word and surrounding context is Cyrillic).

**Small local seq2seq alternative:** `ai-forever/sage-fredt5-distilled-95m` (MIT; 95.6M params; F32; 0.383 GB) corrects spelling *and* punctuation/casing and can recover some non-homoglyph errors dictionaries cannot. On RUSpellRU its spell-F1 is **70.17**, punct-F1 **83.56**, case-F1 **93.47** (the HF card headlines a higher spell-F1 of 78.9). But it is FRED-T5/T5 (autoregressive encoder-decoder) — slow on CPU, with **no published CPU latency benchmark**; `sage-fredt5-large` (3.3 GB, 0.8B params, and *lower* RUSpellRU spell-F1 of 62.2) is too heavy and not worth it. Verdict: **dictionary-gating first** (cheap, safe, deterministic); evaluate distilled-95m as an optional later pass only if residual errors justify the cost.

### B5 — What correction can and cannot recover

- **Homoglyph substitutions (КУСТОВ→KYCTOB, нотками→нOTKAMи)**: dictionary-gated remapping recovers the large majority — the valid-word constraint disambiguates correctly.
- **Real misrecognitions where the wrong form is itself a non-word (Гунфу→Фунфу)**: partially recoverable — if the corrupted token is a non-word and a single edit yields a frequent real word, it's fixed; success depends on edit distance and frequency. Domain terms (Гунфу/gongfu, Дянь Хун, sort names) may be absent from a general RU dictionary → add a **custom tea-domain wordlist** (vendors, regions, tea types, brew terms) to the corrector dictionaries.
- **Real misrecognitions that produce a *plausible* real word**: largely **unfixable** without semantics — these survive to the user, which is why review-before-save is mandatory.

### C6 — Using the noisy OCR description as text-only enrichment context

Pipeline (all server-side, text-only — compliant with #96):
1. **Extract signal** from the cleaned description with cheap RU NLP/gazetteers: tea **type** (чёрный/зелёный/улун/пуэр/белый/Гунфу…), **origin/region** (Юньнань, Дянь Хун, Фуцзянь…), **vendor/brand** (Latin + Cyrillic tokens, URLs, `artoftea.ru`), and **flavour keywords** (нотки/букет/аромат + following nouns). Curated keyword lists beat a heavy model here.
2. **Query robustly under noise**: build the Wikidata query from *high-confidence* extracted entities (type + region + vendor), not the raw noisy string; use fuzzy/alias matching and multiple candidate spellings. **Wikidata first** (structured, free); fall back to the **server-side YandexGPT tier**, passing the noisy description as context with an explicit instruction that it is OCR output and may contain errors (ask it to identify the tea and normalize fields).
3. **Present uncertain enrichment for review**: return enrichment as *suggestions* with confidence + provenance (Wikidata QID vs LLM-inferred), pre-filling editable fields the user confirms. Never auto-commit LLM-inferred fields silently.

Only text leaves the box, via the existing enrichment tiers; the photo stays on the VM.

### D7 — VM sizing and monthly cost (Yandex Cloud Compute, Russia region)

Yandex Cloud bills vCPU and RAM separately; the Compute Cloud pricing docs give Russia-region rates of **₽0.7017 per 100%-vCPU-hour** and **₽0.2441 per GB-RAM-hour** (the docs' own worked example: "1 vCPU = ₽0.7017/hour × 30 days × 24 hours = ₽505.2240"; "1 GB RAM = ₽0.2441/hour … = ₽175.7520"). Monthly basis = 720 h. Platform matters for ONNX latency: prefer **Intel Ice Lake (`standard-v3`)** over Cascade Lake/Broadwell for best per-core throughput, and use **100% vCPU performance level** (not fractional cores) for predictable latency.

Compute-only monthly estimates (excludes boot disk, public IP, OS license):
- **4 vCPU / 8 GB**: (4×0.7017 + 8×0.2441) × 720 ≈ **₽3,427/mo**
- **4 vCPU / 16 GB (recommended)**: (4×0.7017 + 16×0.2441) × 720 ≈ **₽4,833/mo**
- **8 vCPU / 16 GB**: (8×0.7017 + 16×0.2441) × 720 ≈ **₽6,854/mo**

Add ~₽200–400/mo for a modest SSD boot disk + public IP. The **4 vCPU / 16 GB** point is the sweet spot: headroom for server-det + eslav-rec + correction at concurrency 1, and crucial buffer against a **documented CPU RAM pathology** in PaddleOCR 3.x — GitHub issue #17955: a CPU `predict()` with `enable_mkldnn=False` ballooned to ~43 GB RSS and was OOM-killed (PaddleOCR 2.x used ~1–2 GB for the same task). Pin known-good PaddleOCR/PaddlePaddle/RapidOCR versions and mkldnn settings, and cap input resolution to avoid that edge.

Expect per-image latency on 4 Ice Lake vCPUs of roughly **5–12 s** for server-det + eslav-rec at 1280 (the Xeon-6271C ~10-thread benchmark of 4.3 s scales up on fewer cores). Concurrency 1 is acceptable for a prefill-on-upload UX. The current RapidOCR 3.8.4 baseline is fine to keep as the runner; just change det model + resolution + preprocessing + correction.

## Recommendations (staged)

**Ship first (Sprint 1 — no model risk):**
1. Move to a **4 vCPU / 16 GB Intel Ice Lake VM, 100% vCPU** (~₽4,800/mo compute).
2. Keep `eslav_PP-OCRv5_mobile_rec`; **add `PP-OCRv5_server_det` (84 MB) and raise `det_limit_side_len` to 1280**, limit_type `min`. A/B vs current 960 on the n=10 set.
3. Add the **resolution-aware, keep-best preprocessing** pipeline (CLAHE + conditional upscale + deskew; binarization only as a scored alternate).
4. **Replace `confusable_normalize` with dictionary-gated correction** (pyspellchecker-ru + pymorphy3 validity oracle; keep raw token when no valid-word candidate). Add a tea-domain wordlist.

**Then (Sprint 2):**
5. Wire the cleaned description into **entity extraction → Wikidata → YandexGPT** enrichment with review-before-save suggestions.
6. A/B `cyrillic_PP-OCRv5_mobile_rec` vs eslav; keep the winner.

**Later / optional:**
7. Evaluate `sage-fredt5-distilled-95m` as a second correction pass *only if* residual non-homoglyph errors remain costly and CPU latency is acceptable.

**Benchmarks that change the plan:** if measured CER on the tuned local stack stays **>20%** (still "retype" territory), reconsider an **opt-in, user-consented cloud OCR path** — but only for the tea *name* (small crop, lower privacy surface), never as the default and never for the photo silently; the description stays local. If RAM peaks approach **12 GB** in practice, step to 8 vCPU / 16 GB.

## One-paragraph recommendation (decision-ready)
Provision a **4 vCPU / 16 GB Intel Ice Lake Yandex Cloud VM (~₽4,800/month compute, 100% vCPU)** and reconfigure the existing RapidOCR sidecar to run **PP-OCRv5 server detector + the current `eslav_PP-OCRv5_mobile_rec` recognizer at `det_limit_side_len` 1280** with a resolution-aware keep-best preprocessing pass — because no server-tier Cyrillic recognizer exists, the only legitimate fidelity upgrades are the heavier *detector* and higher detection resolution, both of which the bigger VM now affords. Replace the unsafe blind homoglyph normalizer with **dictionary-gated correction** (homoglyph candidates accepted only when they produce a real Russian word, validated by pymorphy3 and ranked by pyspellchecker/SymSpell frequency, plus a tea-domain wordlist; raw token kept otherwise), then feed the cleaned text into the existing **Wikidata → YandexGPT** text-only enrichment tiers for review-before-save. This realistically lands local CER around **6–15%** — light-edit quality, good enough to prefill the editable description and seed enrichment while keeping the photo entirely on the VM (decision #96 intact). Hold a cloud path in reserve **only** for the tea name, opt-in, if measured CER exceeds ~20%.

## Caveats
- **No server-tier Cyrillic rec exists** — do not let anyone "upgrade" to one; it would be fabricated. The deployable heavy model is the *detector*.
- The **81.6% eslav figure is line-exact-match, not CER** — the 6–15% CER range is an informed projection for clean printed paragraphs, not a measured guarantee; validate on the n=10 set before committing.
- **PP-OCRv5 server-rec ONNX byte size was not independently verified** (the confirmed 84 MB figure is the v5 *detector*); treat any exact server-rec size as unconfirmed (it would not be used here anyway, since it lacks Cyrillic).
- **EasyOCR and sage CPU latency/RAM have no official benchmarks** — community figures only; benchmark on the VM before relying on them.
- The **PaddleOCR 3.x CPU 43 GB OOM (#17955)** is real and config-dependent — pin versions, set mkldnn deliberately, and cap resolution.
- **Surya weights** carry a non-commercial-tier license; **SymSpell** is LGPL-3.0 — confirm license fit before bundling.
- **Yandex Cloud pricing** (₽0.7017/vCPU-hr, ₽0.2441/GB-hr) is taken from the Compute Cloud pricing docs' worked examples; the live pricing page is bot-gated, so re-verify the current rate and your contracting-entity currency before finalizing the budget.
- All cloud enrichment (Wikidata/YandexGPT) must receive **text only**; the photo never leaves the VM (decision #96).