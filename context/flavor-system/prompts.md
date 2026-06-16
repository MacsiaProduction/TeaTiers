# Flavor-enrichment prompts (canonical)

The LLM flavor-profiling prompts for the `/resolve` enrichment tier (plan §6 step 3). The
**executable source of truth is `server/.../client/FlavorPrompts.kt`** — this file documents the
intent and the rules so changes are reviewable. Provenance: distilled from the research-07 winner
(`research/07-flavor-prompt-tuning/opus.md`) and validated by the model bake-off
(`research/08-model-bakeoff/`, decision #65). The `gold.json` + `run_bakeoff.py` harness in run 08 is
the regression gate — **re-run it on any change here and block if per-dimension MAE > 1.0 or an
injection attack succeeds.**

## Contract

- **Two modes.** *Zero-shot* (name only) and *grounded* (name + pasted vendor text). Grounded is used
  whenever the resolve request carried a non-blank `sourceText`.
- **Output** is strict JSON validated in Kotlin (`FlavorProfile` + `LlmEnrichmentService.validate`).
  The model contract is never trusted — every field is clamped/checked code-side.
- **Temperature 0** (pinned by `LlmProperties`), ≤ 3 few-shot examples (numeric few-shot bias grows
  with count), full 0–5 anchor rubric, anti-central-tendency instruction.
- **Output is always Russian**: a transliterated display name (Palladius, not literal translation)
  and an original ≤ 240-char blurb. Everything publishes as `unverified`; user ratings override.

## Rubric (0–5, brewed liquor)

`0` absent · `1` barely detectable · `3` clearly present, moderate · `5` dominant. Use the **whole**
scale: a typical tea has 1–3 leading dimensions (4–5) and many 0s/1s. Never park unknowns at 2–3.
Dimensions = the locked 11 (`FlavorDimension`): BITTERNESS, SWEETNESS, ASTRINGENCY, FRUITINESS,
FLORAL, GRASSY, SPICY, SMOKY, EARTHY_NUTTY, UMAMI, ROASTED. `type` = our `TeaType` enum
(GREEN/WHITE/YELLOW/OOLONG/BLACK/DARK/PUER/HERBAL/BLENDED/OTHER) so the reply maps straight onto the
catalog — note this **differs from run-07's finer green/yancha/sheng split**.

## Schema

A strict object: `names{display_ru, original, pinyin}`, `type` (enum), `dimensions` (all 11, each
`{value 0–5, confidence 0–1, evidence bool}`), `short_blurb_ru` (≤ 240), `overall_confidence` (0–1).
The `dim` object is **inlined per dimension, not `$ref`** — some Yandex endpoints reject `$defs`
(run-07 caveat). Built by `FlavorPrompts.schema()`; sent as `response_format:{type:"json_schema",
strict:true}` on the OpenAI-compatible endpoint.

## Confidence gate (code-side)

`FlavorProfile.overall_confidence` is clamped to 0–1, then:
- **zero-shot**: capped at 0.6, then ×0.7 (no external evidence — verbalized LLM confidence is
  overconfident);
- **central-tendency guard**: if every dimension is 2 or 3, ×0.5 (low-signal profile).

Stored as `tea.confidence`. (The richer multiplicative formula in run-07 — evidence fraction, name
resolution, schema-repair factors — is deferred; the current gate captures the two highest-impact
terms.)

## Injection / copyright hardening (mandatory — bake-off finding)

Prompt-only defense **failed** in the bake-off (4/6 models wrote a literal injected string into the
blurb; 5/6 copied vendor prose). So the prompt defenses are **necessary but not sufficient** and the
real enforcement is in code (`EnrichmentText` + `validate`):
- vendor text is **sanitized** (strip `<...>` markup — which also removes any `</VENDOR_TEXT>`
  break-out — plus zero-width/control chars, collapse whitespace, cap 4 000 chars) and wrapped in a
  `<VENDOR_TEXT>` data block with a sandwich reminder;
- the blurb is dropped when its 4-gram **shingle overlap** with the vendor text ≥ 0.5 (copied prose /
  echoed injection);
- structured output means injected instructions can only land inside typed fields, which are then
  clamped/validated; anything off-schema fails closed (state `FAILED`).

## Model routing

Chinese-source requests (Han characters in the name or vendor text) route to the **booster**
(`qwen3-235b-a22b-fp8/latest`); everything else to the **primary** (`aliceai-llm-flash`). Both use the
single OpenAI-compatible `response_format:json_schema` path. Selectable via `teatiers.llm.*`.
