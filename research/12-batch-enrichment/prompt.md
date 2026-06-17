# 10-batch-enrichment — Yandex Foundation Models async completion (completionAsync + operations) for background batch tea enrichment

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a personal, local-first tea tier-list Android app. The Kotlin +
Spring Boot backend (RU-hosted, single Linux VM on Yandex Cloud) enriches unknown teas **on
demand**: it normalizes/translates a typed name to **ru/en/zh + pinyin**, estimates an 11-axis
0–5 flavor profile + a short ru blurb, verifies, and **auto-publishes the tea flagged
`unverified` with a confidence score**.

Current enrichment path (locked decisions):
- **Order:** Wikidata (free, CC0) → on a miss, an LLM. Keys are **server-side only**.
- **LLM tier (today):** the backend calls **Yandex Foundation Models via the
  OpenAI-compatible chat-completions endpoint** (one synchronous HTTP call per tea) with
  `response_format: {type:"json_schema", json_schema:{strict:true, schema:…}}`. Primary model
  = **Alice AI LLM Flash** (`aliceai-llm-flash`); Chinese-name booster = **Yandex-native
  Qwen3-235B**. All calls send `x-data-logging-enabled: false` (the AI Studio ToS basis,
  cl. 4.1/3.15, for storing + re-serving model output).
- **Hard lock:** everything stays **Yandex-native, NO VPN, no non-Yandex AI provider** (a
  prior decision dropped a Germany VPN + Groq/Gemini). Do **not** propose OpenAI/Anthropic/
  Gemini/Groq Batch APIs — they are out of scope and would reverse a locked decision.
- **Server-side flow:** on a Wikidata miss the backend inserts a `PENDING` stub row, returns
  status `ENRICHING` to the app **immediately**, runs the LLM call on a background thread, then
  flips the row to `DONE`/`FAILED`. The Android client **polls `GET /teas/{id}`** until settled.
- **Cost guards:** a per-IP rate limit + a **global daily LLM-call ceiling** that fails closed
  to Wikidata-only when exhausted.

What's changing and why: we are adding **durable background enrichment of teas queued while
offline** (Android WorkManager wakes when connectivity returns, even after the app was killed,
and submits all queued teas at once). For that background path we want the **cheaper,
non-urgent** Yandex tier. Yandex AI Studio documents three modes — **synchronous**,
**asynchronous**, and **batch**. We want to adopt **asynchronous completion**
(`completionAsync` → poll an operation → fetch the result) for the background tier, and
understand whether the heavier **batch/dataset mode** is worth it later.

## Objective

Give the exact, verified specification for calling Yandex Foundation Models in **asynchronous
mode** (`completionAsync` + operation polling) from a Kotlin/Spring backend for tea-name +
flavor enrichment, confirm structured-output and ToS behavior on that path, compare its cost to
synchronous, and tell us whether to also/instead use **batch mode** — so we can build a durable
background batch-enrichment tier without guessing the API.

## Questions

1. **Async REST shape.** Confirm the **asynchronous completion** REST API exactly:
   the request endpoint (e.g. `POST https://llm.api.cloud.yandex.net/foundationModels/v1/
   completionAsync`), the request body (`modelUri`, `completionOptions.{stream,temperature,
   maxTokens}`, `messages`), and the response (an **Operation** object). Then: how to **poll**
   it — `GET https://operation.api.cloud.yandex.net/operations/{id}` vs a model-specific
   **output endpoint**; the `done`/`response`/`error` fields; terminal vs in-progress states;
   and the **operation/result TTL** (how long after `done` can we still fetch the result).
   Give a minimal end-to-end **curl or Kotlin** example for our use case.
2. **OpenAI-compat vs native for async.** Is asynchronous mode available **only** on the native
   Foundation Models REST/gRPC API, or also via the **OpenAI-compatible** endpoint we use today
   (`/v1/chat/completions`, with `model: gpt://<folder>/<slug>`)? If async is native-only,
   state the exact request/response delta we must implement (the native shape differs from
   OpenAI-compat: `modelUri`, `completionOptions`, `messages[].text`).
3. **Structured output on async.** Does the **async** path support
   **`response_format`/`json_schema` with `strict`** for our models (`aliceai-llm-flash` and
   the **Qwen3-235B** booster)? If `json_schema` is unsupported or unconfirmed on async,
   confirm the **`json_object`** fallback and exactly how each model behaves. Pin each model's
   **exact `modelUri`/slug** for async (flag any you cannot confirm on an official page).
4. **Pricing & limits.** Per-token price of **async vs synchronous** for these models — quantify
   the discount (Yandex docs say async is "cheaper"). Any **per-operation, throughput, or daily
   quota** limits on async; max in-flight operations per folder; whether the **new-account
   grant** covers async. How should this reconcile with our existing global daily call ceiling?
5. **Batch/dataset mode — worth it?** Describe Yandex AI Studio **batch mode** (dataset-based
   bulk completion): how input is supplied (dataset format, where it's uploaded — Object
   Storage? a Datasets API?), how results are retrieved, pricing vs async, and **which models
   support it**. Critically: is the **batch text-generation Completion actually implemented** for
   `aliceai-llm-flash` / Qwen3-235B today, or is it "not implemented yet" / limited to specific
   models? Give a clear verdict: for ~1–50 teas per background wake, is batch materially
   cheaper/simpler than fanning out async operations, or is async the right call now with batch
   as a future upgrade?
6. **ToS on async/batch.** Confirm the AI Studio Terms still permit **storing + re-serving**
   model output on the async and batch paths (the cl. 4.1/3.15 basis), and that
   **`x-data-logging-enabled: false`** (or the folder-level logging-off setting) applies to
   `completionAsync` and batch the same way it does to sync. Flag any path-specific data-term
   difference.
7. **Server concurrency & durability.** Best practice for a single Spring Boot instance to
   **submit and poll many in-flight async operations** (one per queued tea) without holding a
   thread or a DB transaction per operation — e.g. submit-then-store-operation-id, poll on a
   scheduler with backoff. Since operations live **server-side at Yandex**, can a backend that
   **restarts** mid-flight **re-attach** to an operation by its stored id and still fetch the
   result (within TTL)? Any official guidance or gotchas.

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts.
- Pin exact versions; explicitly flag anything you are not certain exists.
- Cite every claim with a link and publication/update date; prefer recent sources.
- Treat model benchmark claims skeptically unless reproducible.
- Keep licensing/provenance first-class; do not recommend scraping non-redistributable sites.

