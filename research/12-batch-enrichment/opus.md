# Yandex Foundation Models — Asynchronous Mode Spec for a Kotlin/Spring Boot Tea-Enrichment Backend

## TL;DR
- **Build native async (`completionAsync` → poll `operations/{id}`) on Alice AI LLM and YandexGPT, NOT on Qwen3-235B** — async is a native-REST-only feature, and Qwen3 235B is published as "OpenAI-compatible APIs" only (no native API → no async). For the models that support it, async is exactly 50% cheaper per token than sync.
- **Do NOT build batch mode now**: AI Studio batch text-generation does not list Alice AI LLM or YandexGPT at all (only open-source models). The one Qwen variant in the batch price list (Qwen3-235B-A22B) is billed at $0.050010 per 1,000 tokens with a documented 200,000-token-per-run minimum ("With models in batch mode, the minimum cost per run is 200,000 tokens") — far more expensive than async/sync for 1–50 short teas. Async is the right call; batch is not even available for your production models.
- **Restart-safe**: operations live server-side at Yandex with a **3-day result TTL**, so a Spring Boot instance can store the operation id, restart, and re-attach to fetch the result via `GET operation.api.cloud.yandex.net/operations/{id}` — submit-then-store-id, poll on a scheduler with backoff, no thread or DB transaction held per operation.

## Key Findings

### Async fact sheet
- **Submit endpoint:** `POST https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync`
- **Poll endpoint:** `GET https://operation.api.cloud.yandex.net/operations/{operation_id}`
- **Request body (native shape):** `modelUri`, `completionOptions.{stream,temperature,maxTokens,reasoningOptions.mode}`, `messages[]` with `messages[].role` + `messages[].text`.
- **Submit response:** an `Operation` object `{ id, description, createdAt, createdBy, modifiedAt, done:false, metadata:null }`. Per the official AI Studio overview: "In asynchronous mode, the model responds to a request by sending an Operation object containing the ID of the operation it is performing… get the result of it by submitting a request to a special output endpoint."
- **Poll response when complete:** `{ done:true, response:{ @type:…CompletionResponse, alternatives:[{message:{role,text},status:"ALTERNATIVE_STATUS_FINAL"}], usage:{inputTextTokens,completionTokens,totalTokens}, modelVersion }, id, … }`. On failure the Operation carries an `error` field instead of `response`. In-progress = `done:false`; terminal = `done:true` (with either `response` or `error`).
- **Result TTL:** 3 days (72 hours) — "Storage period for results of text asynchronous requests on the server — 3 days" (AI Studio Quotas and limits). After this the operation/result is purged. (Separately, generated YandexART images are retained only 12 hours.)
- **Model URIs:** Alice AI LLM = `gpt://<folder_id>/aliceai-llm` (32,768 context; "Text generation APIs, OpenAI-compatible APIs"). Qwen3 235B = `gpt://<folder_id>/qwen3-235b-a22b-fp8/latest` (262,144 context; "OpenAI-compatible APIs" only).
- **Structured output:** json_schema and json_object are documented for the Completions (OpenAI-compatible) path and for the native sync `completion` request body; they are NOT shown on the async guide.
- **Price (USD per 1,000 tokens, Russia region, ex-VAT):** Alice AI LLM sync $0.004168 in / $0.016670 out; async $0.002084 in / $0.008335 out (exactly 50% off). YandexGPT Pro 5.1 $0.003334 sync / $0.001667 async. Qwen3 235B $0.004168 sync, async not offered ("—"). For rouble-billed customers, a Mar 3 2026 published rate list gives Alice AI LLM at 0.50 ₽/1K input and 1.20 ₽/1K output; YandexGPT Pro 5.1 at 0.80 ₽/1K; YandexGPT Lite at 0.20 ₽/1K (verify against the live console price list, which is authoritative).
- **Async limits:** 10 submit requests/sec, 50 result-fetch requests/sec, 5,000 submit requests/hour; sync concurrent generations = 10.
- **ToS:** storing + re-serving model output is permitted; logging-off applies the same to all paths.

### 1. Async REST shape (confirmed)
The native asynchronous API is confirmed exactly as the user guessed. Submit with `POST https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync`; the body is the standard native CompletionRequest (`modelUri`, `completionOptions.{stream,temperature,maxTokens,reasoningOptions}`, `messages[].{role,text}`). The service returns an `Operation` object with `id` and `done:false`. Poll with `GET https://operation.api.cloud.yandex.net/operations/{id}` — there is no model-specific output endpoint for text generation; the generic Cloud Operations endpoint is used (image generation uses the same operations endpoint). When `done:true`, the `response` field holds the `CompletionResponse`; if it failed, an `error` field appears. The result is retrievable for 3 days after completion.

### 2. OpenAI-compat vs native for async
Asynchronous mode is **native-only**. The OpenAI-compatible `/v1/chat/completions` (and Responses API) are documented only for synchronous mode. The async delta vs your current OpenAI-compat sync calls:
- Endpoint: `…/foundationModels/v1/completionAsync` (not `/v1/chat/completions`).
- Model identifier: `modelUri: "gpt://<folder>/<slug>"` as a top-level body field (not OpenAI's `model`).
- Options: `completionOptions.{stream,temperature,maxTokens}` (not top-level `temperature`/`max_tokens`).
- Messages: `messages[].text` (not OpenAI's `messages[].content`); roles `system`/`user`/`assistant` unchanged.
- Auth header identical (`Authorization: Api-Key …` or `Bearer <IAM>`), plus `x-folder-id` for IAM auth. Keep `x-data-logging-enabled: false`.

### 3. Structured output on async
The native sync `completion` request body supports `json_object: true` and a top-level `json_schema` object, and the OpenAI-compatible Completions API supports `response_format:{type:"json_schema", json_schema:{…}}`. **The async guide does not demonstrate response_format / json_schema**, so strict json_schema on `completionAsync` is *unconfirmed on an official page*. The safe fallback is `json_object: true` plus an explicit "reply only in JSON following this schema" instruction in the prompt, which is the documented behavior for native completion.

Model URIs pinned:
- **Alice AI LLM = `gpt://<folder_id>/aliceai-llm`** — confirmed on the official model list.
- **Qwen3 235B = `gpt://<folder_id>/qwen3-235b-a22b-fp8/latest`** — confirmed, OpenAI-compatible only (no native/async).
- **`aliceai-llm-flash` cannot be confirmed on an official Yandex model-list page.** The "Alice AI LLM Flash" product is real — a Yandex press release dated May 28, 2026 describes it as "almost 5 times cheaper than before" and says it "in 56% of cases surpasses the GPT-5.4 mini neural network in terms of the quality of solving business problems" — but the official Common-instance models table lists only `aliceai-llm`. Treat `aliceai-llm-flash` as an unverified slug until it appears in the model-list docs.

### 4. Pricing & limits
Async is exactly 50% cheaper per token than sync for the supported native models (Alice AI LLM, YandexGPT). Exact USD figures above. Async limits: 10 submit/s, 5,000 submit/hour, 50 result-fetch/s. There is no documented hard cap on simultaneously in-flight operations beyond these rate limits; the 5,000/hour submit ceiling is the binding throughput constraint. Free new-account grant: Yandex Cloud's grant applies broadly to Model Gallery token usage; per the Grant terms, the grant "expires after 60 days" and for non-residents of the Russian Federation "the grant amount is at least $50, excluding taxes and fees" (split roughly $15 for Compute Cloud + $35 for other services; for RF residents at least ₽4000 incl. VAT). No async-specific exclusion is documented.

### 5. Batch / dataset mode
Batch processing supplies input as a **dataset** (uploaded via the Datasets API / ML SDK), runs a dedicated model instance over it, and writes results to another dataset. Per the official AI Studio overview: "The result is saved as another dataset, which you can download in Parquet format or use immediately… It may take several hours to generate the result. You can process data in batch mode in the management console, using ML SDK, or via the Batch API." **Minimum cost per run = 200,000 tokens** ("With models in batch mode, the minimum cost per run is 200,000 tokens"). The official batch price list contains **only open-source models** (Qwen2.5/Qwen3 family, Gemma3, Llama-3.x, DeepSeek distills, phi-4, QwQ) — **Alice AI LLM and YandexGPT are not in the batch list at all**. The only 235B entry is `Qwen3-235B-A22B` at **$0.050010 per 1,000 tokens** — roughly 12× the common-instance sync price of `qwen3-235b-a22b-fp8` ($0.004168/1k).

**Verdict for 1–50 teas/wake:** async wins decisively. Batch is not implemented for your chosen models (Alice AI LLM is absent from the batch list), the only comparable batch model is ~12× more expensive per token, and the 200,000-token-per-run minimum means a 1–50 short-tea batch would pay for far more than it uses. Build async now; revisit batch only if volumes grow into the hundreds-of-thousands-of-tokens-per-run range AND you switch to an open-source batch-listed model.

### 6. ToS on async/batch
The governing document is the Yandex AI Studio Terms of Use (formerly "Yandex Foundation Models" terms; current version effective April 28, 2026). Clause 4.1 grants the customer the right to use the Service and Generated Content by any means not contradicting the terms. Clause 3.15 confirms the customer is the end commercial user; reselling the Service via raw API function calls is prohibited, but using outputs to build/develop and sell the customer's *own* product is explicitly allowed — which covers storing and re-serving enrichment results inside your tea app. Logging controls (clauses 5.4–5.6): the customer can disable logging; logging is considered disabled 24 hours after the action; once disabled, request data is not stored on Yandex servers (beyond brief processing necessary to serve the request) and is not used for training. These data terms are written at the Service level, with no path-specific carve-out for async or batch — `x-data-logging-enabled: false` (or the folder-level logging-off setting) applies to `completionAsync` and batch the same as to sync.

### 7. Server concurrency & durability
Recommended pattern: on each background wake, submit each queued tea with `completionAsync`, immediately persist `{teaId, operationId, status=PENDING, submittedAt}`, and return the thread to the pool — do not block. A scheduled poller (e.g., Spring `@Scheduled`) batches outstanding ids, calls `GET operations/{id}` with capped exponential backoff, and on `done:true` writes the result and marks DONE (or FAILED on `error`). Because operations live server-side at Yandex for 3 days, a backend that restarts mid-flight re-reads stored operation ids and resumes polling — no state is lost as long as you fetch within the TTL. Respect the 50 result-fetch/s and 5,000 submit/hour ceilings, and reconcile with your existing global daily LLM-call ceiling by counting each `completionAsync` submission (not each poll) against it. Note Yandex's official guidance: the generic Operation resource (the same lifecycle used here) is created for state-changing async calls and is retrieved by id via the Operations Get method; the doc does not state a TTL there — the only official retention number (3 days) is on the Quotas-and-limits page.

## Details

### Minimal end-to-end how-to (curl)
Submit:
```bash
export FOLDER_ID=<folder_id>
export IAM_TOKEN=<IAM_token>
cat > body.json <<'JSON'
{
  "modelUri": "gpt://<folder_id>/aliceai-llm",
  "completionOptions": { "stream": false, "temperature": 0.3, "maxTokens": "2000" },
  "json_object": true,
  "messages": [
    { "role": "system", "text": "You enrich tea records. Reply ONLY with JSON matching: {name, origin, type, flavor_notes[]}. No Markdown." },
    { "role": "user", "text": "Tea: Da Hong Pao, Wuyi rock oolong." }
  ]
}
JSON
curl --request POST \
  --header "Content-Type: application/json" \
  --header "Authorization: Bearer ${IAM_TOKEN}" \
  --header "x-folder-id: ${FOLDER_ID}" \
  --header "x-data-logging-enabled: false" \
  --data "@body.json" \
  "https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync"
# -> { "id": "d7qi6shlbvo5****", "done": false, ... }
```
Poll:
```bash
curl --request GET \
  --header "Authorization: Bearer ${IAM_TOKEN}" \
  "https://operation.api.cloud.yandex.net/operations/<operation_id>"
# done:false while running; done:true with response.alternatives[0].message.text when finished
```
(For json_schema attempts, add a top-level `json_schema` object as documented for native sync `completion`; treat strict behavior on async as unverified and validate the returned JSON yourself.)

### Kotlin/Spring sketch
```kotlin
// 1) submit
data class AsyncReq(val modelUri:String, val completionOptions:Opts, val messages:List<Msg>, val json_object:Boolean=true)
val op = webClient.post()
  .uri("https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync")
  .header("Authorization","Api-Key $apiKey")
  .header("x-folder-id",folderId)
  .header("x-data-logging-enabled","false")
  .bodyValue(AsyncReq("gpt://$folderId/aliceai-llm", opts, msgs))
  .retrieve().bodyToMono(Operation::class.java).block()
repo.save(TeaOp(teaId, op.id, "PENDING"))   // persist, release thread

// 2) scheduled poller with backoff
@Scheduled(fixedDelay = 15_000)
fun poll() = repo.findPending().forEach { t ->
  val o = webClient.get()
    .uri("https://operation.api.cloud.yandex.net/operations/${t.operationId}")
    .header("Authorization","Api-Key $apiKey")
    .retrieve().bodyToMono(Operation::class.java).block()
  if (o.done) {
    if (o.response != null) repo.complete(t, o.response.alternatives[0].message.text)
    else repo.fail(t, o.error)
  }
}
```
ML SDK alternative (Python, for reference): `operation = model.run_deferred(messages)`, then `operation.get_status()` in a loop or `operation.wait()`, then `operation.get_result()` — the same submit/poll/fetch lifecycle the REST calls expose.

## Recommendations
1. **Ship native async on `aliceai-llm` now** for the primary path; keep YandexGPT (`gpt://<folder>/yandexgpt/rc`) as an async-capable fallback. Both honor the 50% async discount. Threshold to revisit: if p95 enrichment latency over async (a couple of minutes to potentially several hours in worst case) hurts UX, fall back to sync OpenAI-compat for that tea.
2. **Keep the Chinese-name booster on Qwen3-235B but call it SYNCHRONOUSLY** via the OpenAI-compat endpoint with `gpt://<folder>/qwen3-235b-a22b-fp8/latest` — Qwen3 has no async/native path. Do not attempt `completionAsync` with the Qwen URI; it will not route.
3. **Use `json_object:true` + explicit schema-in-prompt on the async path** until you verify strict `json_schema` on `completionAsync` in your own folder; keep strict `json_schema` (`response_format`) on the OpenAI-compat sync calls where it is officially supported. Always validate returned JSON server-side.
4. **Persist `operationId` per tea and poll on a scheduler with backoff**; count submissions (not polls) against your global daily LLM ceiling; fetch every result within the 3-day TTL (re-attach by stored id after any restart).
5. **Do not invest in batch mode** unless you both (a) move to an open-source batch-listed model and (b) routinely exceed ~200,000 tokens per run; at 1–50 short teas you would pay the 200k-token minimum for a fraction of the work. Keep batch as a "future upgrade if volume explodes" note, not a current build.
6. **Confirm the `aliceai-llm-flash` slug before relying on it.** Since the docs list only `aliceai-llm`, point production config at `aliceai-llm` and add a feature flag to switch to `aliceai-llm-flash` only after you see it in the official model-list table and confirm its API availability/pricing.

## Caveats / Could-not-verify list
- **Async strict `json_schema`**: NOT shown on the official async guide; only `json_object` + native `json_schema` are documented for sync. Treat strict json_schema on `completionAsync` as unverified — validate output yourself.
- **`aliceai-llm-flash` slug**: not on any official model-list page; only `aliceai-llm` is listed. Unverified despite the product existing (May 28, 2026 launch).
- **Async availability per model**: the official model table marks Qwen3 235B / gpt-oss / Gemma as "OpenAI-compatible APIs" only (no native API → no async); the pricing table shows "—" for their async price, corroborating no async. Alice AI LLM and YandexGPT are async-capable.
- **Batch model support for Alice AI LLM / Qwen3-235B-A22B-fp8**: Alice AI LLM is absent from the batch price list; only `Qwen3-235B-A22B` (open-source) appears, at $0.050010/1k tokens. Batch for your exact production models is effectively "not available."
- **Async output/result endpoint**: confirmed to be the generic `https://operation.api.cloud.yandex.net/operations/{id}` for text (no separate model-specific output endpoint), despite the overview page's generic wording "a special output endpoint (its value depends on the model)" — that caveat applies to other modalities, not text completion.
- **Operation TTL**: 3 days, confirmed on the Quotas-and-limits page only (not repeated on the async guide or the Operation-object concept page).
- **Exact async prices** are region-specific (USD figures are the Russia region, ex-VAT, for customers of the Dubai/Serbia entities); the rouble figures (0.50/1.20 ₽ for Alice AI LLM) come from a secondary rate list and should be confirmed against the live console price list.
- **Free grant**: per the Grant terms it expires after 60 days and is "at least $50" for non-residents; no async-specific exclusion or inclusion is spelled out.

### Dated reference links
- Overview of AI Studio AI models (modes; async returns Operation; batch → Parquet) — yandex.cloud/en/docs/ai-studio/concepts/generation/ — updated Dec 12, 2025.
- Common instance models (model URIs: `aliceai-llm`, `qwen3-235b-a22b-fp8`; available APIs per model) — yandex.cloud/en/docs/ai-studio/concepts/generation/models — updated Feb 3, 2026.
- Sending an asynchronous request (curl submit → `completionAsync`, poll `operations/{id}`, response shapes) — yandex.cloud/en/docs/ai-studio/operations/generation/async-request — checked Jun 17, 2026.
- AI Studio pricing policy (sync vs async per-token prices, 50% async discount, batch model list + 200k-token batch minimum) — yandex.cloud/en/docs/ai-studio/pricing — updated Dec 24, 2025.
- AI Studio quotas and limits (10 submit/s, 5,000 submit/hr, 50 fetch/s; 3-day async result TTL; batch 10 runs/hr, 100/day) — yandex.cloud/en/docs/ai-studio/concepts/limits — checked Jun 17, 2026.
- Formatting model responses / structured output (`json_object`, `json_schema` on native + OpenAI-compat) — aistudio.yandex.ru/docs/en/ai-studio/concepts/generation/structured-output.html — checked Jun 17, 2026.
- Yandex Cloud API "Operation object" concept (generic Operation lifecycle, GET `operations/{id}`) — cloud.yandex.com/en/docs/api-design-guide/concepts/operation — updated Apr 23, 2025.
- Yandex AI Studio Terms of Use (clauses 3.15, 4.1, 5.4–5.6 logging-off) — yandex.ru/legal/cloud_terms_yandex_ai_studio/ru/ — effective Apr 28, 2026.