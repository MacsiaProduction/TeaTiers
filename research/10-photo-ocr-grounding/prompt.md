# 10-photo-ocr-grounding - OCR tea-photo text for grounded enrichment

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, ...). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** - a local-first Android tea tier-list app with a Kotlin/Spring Boot
catalog backend. Target market is Russia first: RuStore + direct APK, no dependency on
Google Play Services. User data stays on-device in Room; backend stores only a shared
tea catalog. The app already supports user photos copied into app-private storage and
has a backend `/api/v1/teas/resolve` endpoint that accepts optional `sourceText`.

Current enrichment architecture:
- Search/resolve sends only tea name and optional pasted source text to our backend.
- Backend uses Wikidata first, then Yandex Cloud Foundation Models (Alice Flash primary,
  Qwen3 booster) with strict JSON schema.
- Raw pasted vendor prose is treated as untrusted/copyrighted input: sanitize, length cap,
  prompt-injection guards, do not store or republish raw text; derive structured flavor
  profile + original short blurb.
- No open-ended web crawling and no arbitrary web image fetching.

New idea to evaluate: tea photos can include packaging/product-description text. We may
OCR that text and feed it into the existing `sourceText` grounding path.

## Objective

Choose the best MVP and post-MVP implementation path for extracting text from user tea
photos and using it as grounded enrichment evidence, while preserving no-GMS, privacy,
licensing, and app-size constraints.

## Questions

1. Compare ready OCR options for this exact app:
   - Tesseract / tess-two or maintained Android bindings
   - PaddleOCR / PP-OCR mobile or server-side deployment
   - ML Kit bundled text recognition (Latin + Chinese), explicitly noting it is not open-source
     and comparing bundled vs Google Play Services modes
   - any other maintained open-source Android/server OCR option worth considering in 2026
2. For Russian, English, and Chinese tea packaging, which option is likely to work best on:
   small curved packages, glossy labels, vertical Chinese text, mixed Cyrillic/Latin/Hanzi,
   and low-light phone photos?
3. Should OCR run on-device or server-side for MVP? Consider:
   privacy expectations, no-GMS RuStore devices, APK size, CPU/battery, model downloads,
   backend cost, and whether photo bytes should ever leave the device.
4. How should OCR output flow into TeaTiers safely?
   - UI review/confirm step vs automatic submission
   - sourceText length cap and sanitization
   - injection/copyright guards from decisions #25/#44/#65
   - whether raw OCR text is stored locally, sent once, or discarded
5. Give exact integration sketches for the top 2 choices:
   dependencies, model files, permissions, threading/background work, testing strategy,
   and fallback if OCR produces low confidence.
6. Identify current versions, licenses, model sizes, and any claims that must be verified
   against upstream before coding.

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts.
- Pin exact versions; explicitly flag anything you are not certain exists.
- Cite every claim with a link and publication/update date; prefer recent sources.
- Treat benchmark claims skeptically unless they are official or reproducible.
- Pay special attention to licensing and whether a library needs Google Play Services.

## Return

Return:

1. A comparison table: Tesseract, PaddleOCR, ML Kit bundled, ML Kit Play Services,
   and any other serious candidate.
2. Recommended MVP approach and post-MVP upgrade path.
3. App/backend architecture sketch for the chosen approach.
4. Security/privacy/copyright checklist.
5. Test plan with 15-20 photo scenarios across ru/en/zh.
6. "Do not do" list.

