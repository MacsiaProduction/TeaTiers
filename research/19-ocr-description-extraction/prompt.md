# 19-ocr-description-extraction — clean DESCRIPTION text from RU tea packaging, locally, on an upsizable VM

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android (Kotlin + Compose) tier-list app for teas, **Russia-first**,
**no accounts / no PII**, GMS-free. A small Kotlin/Spring catalog+enrichment backend runs on a **single
Yandex Cloud VM** (Caddy + Spring + Postgres + an OCR sidecar). The user photographs a tea package, the
app uploads it to a **first-party OCR sidecar**, and the recognized text is shown for review/edit.

**The goal has been reframed — read carefully.** Earlier work (run 18) optimized OCR for the tea **name**.
But the higher-value target is the **DESCRIPTION** — the long free-text on the package (flavour notes,
brewing guidance, origin/region, vendor). Rationale: a *name* is short, so the user can just type it; a
*description* is too long to type. The description is used for:

1. **Informing the user's taste scoring** (the package's flavour/brewing notes as a reference while rating).
2. **Seeding a NEW tea that is not in the catalog** — and specifically as **additional context fed into the
   existing web/Wikidata/LLM *enrichment* lookup** to identify + enrich that unknown tea. **The description
   text is an INPUT to enrichment, not the end product.** We do NOT want a cloud LLM doing the OCR
   *extraction*; we want clean local text that the existing enrichment tier can then use as context.

So success = a **clean, readable, faithful full-text DESCRIPTION**, captured **locally**, good enough to
(a) prefill an editable description field and (b) serve as a noisy-but-usable query/context for enrichment.
Descriptions are **NOT in the catalog**, so — unlike the name — there is **no fuzzy-catalog-match safety
net**: quality is pure OCR fidelity.

**Current OCR (decisions #96/#105/#114):** local **RapidOCR 3.8.4** (ONNX), **PP-OCRv5 eslav (East-Slavic)
MOBILE rec + PP-OCRv5 MOBILE det**, angle-cls off, det downscale `limit_side_len=960`, conditional low-res
upscale, **concurrency 1**, models baked in. Chosen **local / no-egress** for privacy: the photo goes
device → our VM only, **never to an external OCR/vision API** (decision #96 — a product value).

**What we measured (research/13 `proof/FINDINGS.md`; research/18; n=10 real RU packaging photos).**
- Full-text descriptions come out **~70% right but degraded**: Cyrillic↔Latin/digit **homoglyph**
  substitution (`Хун→Хyн`, `КУСТОВ→KYCTOB`, `нотками→нOTKAMи`, `Букет→Byкeт`) plus **real misrecognitions**
  the homoglyph class can't explain (`Гунфу→Фунфу`, `северо→соверо`).
- A **confusable normalizer** (run 18, already shipped: `ocr-sidecar/name_match.py` `confusable_normalize`)
  maps Latin/digit homoglyphs back to Cyrillic with a per-token guard that protects genuine Latin
  (`artoftea.ru`, `Gaba`). It fixed ~59 homoglyph chars across 8 descriptions — BUT on **free text** it has
  a failure the name-matching use case hid: it can map to the **wrong** Cyrillic (`Букет`→`Byкeт`→**`Вукет`**,
  because Latin `B` was a misread `Б` but the map sends `B→В`), turning obvious garble into a plausible
  *wrong word*. For catalog name-matching that's harmless (fuzzy match absorbs it); for a **human-read
  description it is worse than the raw garble**. And it cannot fix real misrecognitions at all.
- The two AI-vision OCRs we compared (Yandex **Alice**, **DeepSeek**) produce **clean full descriptions** —
  but sending the photo to them **breaks no-egress** (#96), so they are out for the *extraction* step.

**Two constraints changed since run 18 — both important:**
- **The VM can be UPSIZED.** Run 18 rejected better local models (PP-OCRv5 **server** rec, higher det
  resolution, TrOCR, etc.) *solely* because they didn't fit **4 GB / 2 vCPU** (~3.4 GB already committed).
  That ceiling is **relaxed**: we will pay for a bigger Yandex Cloud VM (more RAM + vCPU) if it materially
  improves local description fidelity. Still **CPU-only (no GPU)**, still **cost-conscious** (tell us the
  size + the monthly ₽), still **concurrency 1** is acceptable.
- **Cloud LLM/vision is allowed for ENRICHMENT, not for extraction.** Sending the *photo* off-box is still
  banned (#96). But once we have local OCR text, feeding that **text** into the existing network enrichment
  tiers (Wikidata; the existing YandexGPT LLM enrichment, which already runs server-side) is fine — that's
  text, not the image, and it's the established path.

RU-first (Cyrillic); labels also carry pinyin, English brand/vendor tokens, URLs, units (`г`, `мл`, `°C`),
and the occasional handwritten label (rarer for descriptions, which are usually printed). Chinese glyphs
are out of scope (the eslav model skips them).

## Objective

Decide **how to get a clean, faithful, local full-text DESCRIPTION** from a real RU tea package — good
enough to prefill an editable field and to feed the enrichment lookup — in priority order: (1) the best
**local CPU OCR** config for full-text RU fidelity now that the **VM can grow**, with the size + cost;
(2) a **description-safe correction** step that fixes errors without inventing wrong words (the
`Букет→Вукет` problem); (3) how to **use the noisy OCR description as enrichment context** for an
unknown tea. No photo egress.

## Questions

**Track A — best LOCAL full-text OCR for RU descriptions on a bigger CPU VM.**
1. Reconsider the models run 18 ruled out on the 4 GB budget, now that we can upsize. For each, pin the
   exact model files + sizes + license + RU support, and **estimate CPU latency + peak RAM** at a stated VM
   size (e.g. 4 vCPU / 8 GB, 4 vCPU / 16 GB — say which you assume):
   - **PP-OCRv5 *server* rec** (vs the current eslav *mobile* rec): does it materially cut full-text RU CER?
     Is there an **East-Slavic / Cyrillic server rec**, or only CJK/Latin? If eslav is mobile-only, what's
     the best Cyrillic-capable rec model that is NOT mobile-tier?
   - **Higher det resolution** (`det_limit_side_len` > 960, e.g. 1280/1536/2048): how much does raising it
     improve recognition of small description text, and the RAM/latency cost? (run 13 lowered it to 960
     purely to bound RAM on 4 GB.)
   - Other local CPU OCR stacks for full RU text: **EasyOCR** (ru), **docTR**, **Surya OCR**, **PaddleOCR**
     server pipeline, other **RapidOCR** rec models. Pin versions/sizes/licenses; which is actually best on
     **printed RU paragraphs** at a CPU-reasonable latency?
2. What **real full-text CER** can a local CPU stack realistically reach on RU packaging descriptions (not
   names) — give a defensible range and the best accuracy-vs-resource operating point. Is the gap to
   AI-vision closable enough that the user mostly *edits lightly* rather than *retypes*?
3. **Preprocessing for full text** (vs the name case): adaptive binarization, deskew, CLAHE, denoise,
   resolution. Safe to apply unconditionally for printed paragraphs, or must it stay conditional/keep-best
   (the #114 unconditional-upscale regression)? Concrete OpenCV/Pillow pipeline + pins.

**Track B — description-safe correction (fix errors without inventing wrong words).**
4. The shipped confusable normalizer is unsafe on free text (`Букет→Вукет`). Design a **description-safe**
   post-OCR correction that beats blind homoglyph mapping. Options to evaluate + pin (tool, dict, size,
   license, RU support, does it fit the VM, latency):
   - **Dictionary / spellcheck**-gated homoglyph correction: only accept a Latin→Cyrillic mapping if the
     result is a real RU word — **hunspell ru_RU**, **pyspellchecker**, **SymSpell + a RU frequency dict**,
     **Yandex `dawg`/`pymorphy3`** for RU morphology. How to combine "is this token mostly Cyrillic?" with
     "is the mapped form a real word?" so `Byкeт→Букет` (real) wins over `→Вукет` (non-word).
   - A **small LOCAL seq2seq OCR-post-corrector** (e.g. a byT5/T5-small char model, or a RU GEC model) —
     pin candidates + size; does any run on CPU at description length within budget, and does it actually
     help RU OCR noise? Or is dictionary-gated correction the better cost/quality point?
5. How much of the residual (non-homoglyph) error — `Гунфу→Фунфу`, `северо→соверо` — can correction realistically
   recover, and what stays unfixable (so the user still edits)?

**Track C — using the noisy OCR description as ENRICHMENT context (text only, no photo egress).**
6. For a NEW tea not in the catalog: how best to turn the noisy local OCR description into a **query/context**
   for the existing enrichment lookup (Wikidata first, then the server-side YandexGPT LLM tier) to identify
   the tea + enrich it. What to extract from the description as signal (type, origin/region, vendor, flavour
   keywords), how to query robustly given OCR noise, and how to present uncertain enrichment back for review.
   This is **text** enrichment via the existing server-side tiers — fine re: #96; the **photo** never leaves.

**Track D — recommendation.**
7. Crisp, decisive plan + sequencing: the local OCR config (model + det resolution) and the **VM size +
   monthly ₽** it needs; the correction step (dictionary-gated vs model); and the enrichment-context wiring.
   What ships first? Where does the realistic quality land, and is local good enough for the
   review-before-save + enrich flow, or is there a residual case for an opt-in cloud path we should name?

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts. Pin exact model files + sizes + SHAs/
  licenses + RU support; explicitly flag anything you are not certain exists (run 18 saw fabricated model
  ids, sizes, and pins — do not repeat them).
- For VM sizing, cite **Yandex Cloud Compute** pricing (vCPU/RAM, ₽/month, with the page + date) for the
  size you recommend; note the platform/CPU generation if it affects ONNX latency.
- Give realistic CPU latency/RAM numbers and say what hardware they assume (a Xeon benchmark ≠ a 2-vCPU VM).
- Respect the constraints: the **photo never leaves the VM** (no cloud OCR/vision for extraction); call out
  anything that breaks it. Text-only enrichment via the existing tiers is allowed.

## Return

A prioritized plan: Track A local-OCR config (model + det resolution + the VM size & monthly cost it needs),
Track B correction step (dictionary-gated vs small local model, with pins), Track C enrichment-context
wiring, and a Track D go decision. End with a one-paragraph recommendation we can turn into a decision.
