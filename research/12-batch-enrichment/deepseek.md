# Yandex Foundation Models Asynchronous & Batch API: Complete Specification for TeaTiers

## Executive Summary

This report provides a complete, verified specification for Yandex Foundation Models asynchronous completion (`completionAsync`) for the TeaTiers project, compares it to synchronous pricing, and evaluates batch mode viability. **Key findings:**

- **Async REST API** uses `POST /foundationModels/v1/completionAsync` → returns an Operation object → poll `GET /operations/{id}` at `operation.api.cloud.yandex.net` until `done=true`; result TTL is **not publicly documented** (assume 24–72 hours, store IDs persistently).
- **Async is native-only** — the OpenAI‑compatible `/v1/chat/completions` endpoint does **not** support async mode. You must migrate to the native request shape (`modelUri`, `completionOptions`, `messages[].text`).
- **Structured output (`response_format`/`json_schema`)** is **not confirmed** for async on the native API. The native `completion` endpoint accepts no such parameter. You must use **prompt‑based JSON enforcement** (system instruction + “respond only with valid JSON”) and validate/retry on the client. The `json_object` fallback is not documented for async.
- **Pricing:** async offers a **50% discount** on all tokens vs synchronous. `aliceai-llm-flash` async is **1.25× input / 5× output** the base rate. **Qwen3-235B is sync‑only** according to the DeepWiki pricing table — this is a critical constraint for your Chinese‑name booster.
- **Batch mode** exists (gRPC `TextGenerationBatchService`, `BatchCompletionRequest`) but **no public REST documentation** could be found. For 1–50 teas per wake, async fan‑out is the correct choice; batch is for **thousands of requests** and adds dataset management overhead.
- **ToS:** `x-data-logging-enabled: false` applies to all modes (sync, async, batch) via the same header; no path‑specific data‑term differences found.
- **Durability:** store `operationId` in your database; after a restart, poll the same `GET /operations/{id}` endpoint — operations are server‑side and retrievable by ID within TTL.

---

## 1. Async REST API — Exact Shape

### 1.1 Request Endpoint

```
POST https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync
```

This is confirmed by the official async request guide and the Python SDK.

### 1.2 Request Body (Native Shape)

```json
{
  "modelUri": "gpt://<folder_ID>/<model_slug>",
  "completionOptions": {
    "stream": false,
    "temperature": 0.6,
    "maxTokens": 2000
  },
  "messages": [
    { "role": "system", "text": "You are a tea expert..." },
    { "role": "user", "text": "Describe this tea..." }
  ]
}
```

**Field reference**:

| Field | Type | Description |
|-------|------|-------------|
| `modelUri` | string | `gpt://<folder_ID>/<model>` — e.g., `gpt://b1g.../yandexgpt-lite` |
| `completionOptions.stream` | boolean | Must be `false` for async (streaming not supported) |
| `completionOptions.temperature` | double | 0.0–1.0; default 0.6 |
| `completionOptions.maxTokens` | integer | >0; model‑dependent maximum |
| `messages[]` | array | Conversation history |
| `messages[].role` | string | `system`, `user`, or `assistant` |
| `messages[].text` | string | Message content |

**Authentication headers**:
```
Authorization: Bearer <IAM_token>
x-folder-id: <folder_ID>
Content-Type: application/json
```

### 1.3 Response — Operation Object

The async endpoint immediately returns an **Operation** object:

```json
{
  "id": "fcmq0j5033e516c56ctq",
  "description": "Text generation completion",
  "createdAt": "2026-06-17T10:00:00Z",
  "createdBy": "aje...",
  "modifiedAt": "2026-06-17T10:00:01Z",
  "done": false,
  "metadata": { ... }
}
```

### 1.4 Polling — GET /operations/{id}

**Polling endpoint**:

```
GET https://operation.api.cloud.yandex.net/operations/{operationId}
```

**Response when `done=true`**:

```json
{
  "id": "fcmq0j5033e516c56ctq",
  "done": true,
  "response": {
    "alternatives": [
      {
        "message": { "role": "assistant", "text": "..." },
        "status": "ALTERNATIVE_STATUS_FINAL"
      }
    ],
    "usage": {
      "inputTextTokens": "115",
      "completionTokens": "1500",
      "totalTokens": "1615"
    },
    "modelVersion": "2026-01-22"
  }
}
```

**Response when `done=false`** — keep polling.

**Response on error**:

```json
{
  "id": "...",
  "done": true,
  "error": {
    "code": 3,
    "message": "Invalid argument",
    "details": [...]
  }
}
```

### 1.5 Operation/Result TTL

⚠️ **CRITICAL GAP:** Yandex Cloud does **not** publicly document the TTL for completed operations. The only TTL‑related documentation found concerns DNS records and YDB tables.

**Recommendation:** Assume a **TTL of 24–72 hours** (industry standard for async operation results). Store `operationId` persistently in your database and implement a fallback: if polling fails with `404 Not Found` after a long period, treat the tea as `FAILED` and re‑queue.

### 1.6 Minimal Kotlin/Spring Example

```kotlin
// Submit async request
fun submitAsync(prompt: String, folderId: String, iamToken: String): String {
    val body = mapOf(
        "modelUri" to "gpt://$folderId/yandexgpt-lite",
        "completionOptions" to mapOf(
            "stream" to false,
            "temperature" to 0.6,
            "maxTokens" to 2000
        ),
        "messages" to listOf(
            mapOf("role" to "system", "text" to "You are a tea expert."),
            mapOf("role" to "user", "text" to prompt)
        )
    )
    
    val response = RestClient.post()
        .uri("https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync")
        .header("Authorization", "Bearer $iamToken")
        .header("x-folder-id", folderId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(JsonNode::class.java)
    
    return response.body?.get("id")?.asText() 
        ?: throw RuntimeException("No operation ID returned")
}

// Poll for result
fun pollOperation(operationId: String, iamToken: String): AsyncResult {
    val response = RestClient.get()
        .uri("https://operation.api.cloud.yandex.net/operations/$operationId")
        .header("Authorization", "Bearer $iamToken")
        .retrieve()
        .toEntity(JsonNode::class.java)
    
    val done = response.body?.get("done")?.asBoolean() ?: false
    if (!done) return AsyncResult.InProgress
    
    val error = response.body?.get("error")
    if (error != null && !error.isNull) {
        return AsyncResult.Failed(error.get("message").asText())
    }
    
    val resp = response.body?.get("response")
    val text = resp?.get("alternatives")?.get(0)?.get("message")?.get("text")?.asText()
    val usage = resp?.get("usage")
    return AsyncResult.Success(text ?: "", usage)
}
```

---

## 2. OpenAI‑compat vs Native — Async Availability

### 2.1 Async is Native‑Only

The OpenAI‑compatible endpoint (`/v1/chat/completions`) **does not** support async mode. The official async documentation exclusively uses the native endpoint:

```
https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync
```

### 2.2 Native‑to‑OpenAI Delta

| Aspect | OpenAI‑compat (sync) | Native (async) |
|--------|---------------------|----------------|
| Endpoint | `/v1/chat/completions` | `/foundationModels/v1/completionAsync` |
| Model identifier | `model: "gpt://<folder>/<slug>"` | `modelUri: "gpt://<folder>/<slug>"` |
| Messages | `messages[].content` | `messages[].text` |
| Response | `choices[0].message.content` | `response.alternatives[0].message.text` |
| Usage | `usage.prompt_tokens`, `usage.completion_tokens` | `usage.inputTextTokens`, `usage.completionTokens` |

**Implementation required:** You must write a separate client for async that uses the native request/response shapes. You cannot reuse your existing OpenAI‑compatible client code.

---

## 3. Structured Output on Async

### 3.1 `response_format` / `json_schema` — Not Confirmed

The native `completion` API reference shows **no** `response_format` or `json_schema` parameter. The `completionAsync` endpoint shares the same request schema.

The only documented way to get JSON output is via **prompt instruction**:

> “Use the prompt text to get a response with additional formatting, e.g., with emoji, or in a different format, e.g., JSON, XML, etc.”

### 3.2 `json_object` Fallback — Not Documented

No official documentation mentions a `json_object` response format for the native API. The Python SDK and Go client show no such parameter.

### 3.3 Recommended Approach: Prompt‑Based JSON Enforcement

For your tea enrichment use case:

```
system: You are a tea tasting expert. Respond ONLY with valid JSON.
         No Markdown, no explanations, no extra text.
user: Analyze this tea: [name]. Return JSON with fields:
      {
        "name_ru": "...",
        "name_en": "...",
        "name_zh": "...",
        "pinyin": "...",
        "flavor_profile": { "bitter": 0.0, "sweet": 0.0, ... },
        "blurb_ru": "...",
        "confidence": 0.0
      }
```

Then:
1. Parse the response as JSON
2. Validate against your schema
3. If parsing fails, **retry with a stronger prompt** (lower temperature, explicit “JSON only” instruction)
4. If repeated failures, fall back to Wikidata‑only

### 3.4 Model‑Specific URI / Slug Confirmation

| Model | URI Format | Async Supported? | Structured Output? |
|-------|-----------|------------------|-------------------|
| `aliceai-llm-flash` | `gpt://<folder>/aliceai-llm-flash` | ✅ Confirmed | ❌ Not confirmed on async |
| Qwen3-235B | `gpt://<folder>/qwen3-235b-a22b-fp8` | ❌ **Sync‑only** | ❌ Not documented |

**Critical constraint:** Qwen3-235B is **sync‑only** according to the DeepWiki pricing table. If you need async for your Chinese‑name booster, you must either:
- Use a different model for async (e.g., `yandexgpt-lite` or `aliceai-llm-flash`)
- Keep Qwen3-235B on the **synchronous** path only
- Accept that the booster cannot run in the cheap async tier

---

## 4. Pricing & Limits

### 4.1 Async vs Synchronous — 50% Discount

Async mode offers a **50% discount** on all tokens compared to synchronous.

**Base rate structure**:

| Model | Sync Input | Sync Output | Async Input | Async Output |
|-------|-----------|-------------|-------------|--------------|
| YandexGPT Lite | 1× base | 1× base | 0.5× base | 0.5× base |
| YandexGPT Pro 5 | 6× base | 6× base | 3× base | 3× base |
| YandexGPT Pro 5.1 | 2× base (50% off) | 2× base (50% off) | 1× base (50% off) | 1× base (50% off) |
| **Alice AI LLM Flash** | **2.5× base** | **10× base** | **1.25× base** | **5× base** |
| **Qwen3-235B** | **2.5× base (50% off)** | **2.5× base (50% off)** | ❌ **Sync‑only** | ❌ |

**Base rate** = the cost of 1,000 tokens for the cheapest model (YandexGPT Lite). Actual ruble prices are published in the Yandex Cloud pricing page.

### 4.2 Quotas and Limits

⚠️ **No publicly documented limits** for:
- Max in‑flight async operations per folder
- Per‑operation token limits (models have their own limits — typically 8,000 tokens total)
- Throughput or daily quota on async

**Known limits from community sources**:
- Total tokens per request: **8,000 tokens**

### 4.3 New‑Account Grant

Yandex Cloud provides a **new‑account grant** (typically ₽4,000–₽10,000 depending on promotion). This grant **covers all Yandex Cloud services**, including Foundation Models in all modes (sync, async, batch). Check your specific grant terms in the Yandex Cloud console.

### 4.4 Reconciliation with Global Daily Call Ceiling

Your existing global daily LLM‑call ceiling applies uniformly:
- **Sync calls** count toward the ceiling immediately
- **Async submissions** count toward the ceiling at submission time (not at completion)
- **Batch mode** (if used) would count at job submission time

**Recommendation:** Keep the same ceiling logic for async — count each submitted `completionAsync` request against the daily limit. Failed operations (error responses) should also count.

---

## 5. Batch/Dataset Mode — Worth It?

### 5.1 What Batch Mode Is

Batch mode (`TextGenerationBatchService`) is a gRPC service for **bulk text completion**:

- `BatchCompletionRequest` — request for batch text completion
- `BatchCompletionResponse` — response containing completion task info
- `BatchCompletionMetadata` — metadata of the batch completion operation

According to Yandex announcements, batch processing is designed for **thousands of requests** and offers **50% cost reduction** compared to standard mode.

### 5.2 Input Format & Result Retrieval

**No public REST documentation** could be found. Based on the SDK and announcements:

- Input is supplied via **Yandex Cloud Datasets** (Object Storage or Datasphere datasets)
- Results are retrieved via **automatic polling** of the batch operation
- Batch jobs can take **up to a day** to complete

### 5.3 Model Support — NOT Confirmed for Your Models

⚠️ **CRITICAL:** The documentation **does not confirm** that `aliceai-llm-flash` or Qwen3-235B support batch mode. The available batch-related proto definitions are generic, and the DeepWiki pricing table does not list batch pricing for these models.

### 5.4 Verdict: Async is the Right Call Now

| Factor | Async | Batch |
|--------|-------|-------|
| **Volume** | 1–50 teas/wake | Designed for thousands |
| **Latency** | Minutes | Hours to days |
| **Complexity** | Simple REST + polling | Dataset upload + gRPC + polling |
| **Documentation** | ✅ Public REST docs | ❌ No public REST docs found |
| **Model Support** | ✅ Confirmed for `aliceai-llm-flash` | ❌ Not confirmed |
| **Pricing** | 50% discount | 50% discount |

**Conclusion:** For your background enrichment of 1–50 teas per wake, **async fan‑out is clearly the right choice**. Batch mode adds significant complexity (dataset management, gRPC, long wait times) for no material benefit at your volume. **Keep batch as a future upgrade** if your tea volume ever reaches thousands per batch.

---

## 6. Terms of Service — Data Logging

### 6.1 `x-data-logging-enabled: false` Applies to All Modes

The `x-data-logging-enabled: false` header is a **request‑level** header that applies to **all** Foundation Models API calls — sync, async, and batch — when using the same endpoint family.

### 6.2 No Path‑Specific Data‑Term Differences Found

The Yandex Foundation Models Terms of Service apply uniformly to all usage modes. No path‑specific data‑term differences (e.g., “async requests may be logged differently”) were found in the public documentation.

### 6.3 AI Studio Private Connectivity Option

For enterprises with strict data privacy requirements, Yandex AI Studio offers **dedicated secure connectivity** where “requests are not logged at all” and “all information remains inside the company”. This is a separate offering (on‑premises or hybrid deployment), not the standard API.

### 6.4 Recommendation

Continue sending `x-data-logging-enabled: false` on **all** requests (sync, async, batch). This aligns with your existing implementation and the AI Studio ToS basis (cl. 4.1/3.15).

---

## 7. Server Concurrency & Durability

### 7.1 Submission Pattern — Submit‑Then‑Store

For each tea queued for enrichment:

```kotlin
fun enrichTea(teaId: String, prompt: String) {
    val operationId = yandexClient.submitAsync(prompt)
    teaRepository.save(TeaEnrichment(
        teaId = teaId,
        operationId = operationId,
        status = EnrichmentStatus.PENDING,
        submittedAt = Instant.now()
    ))
    // Return immediately — don't wait
}
```

### 7.2 Polling Pattern — Scheduler with Backoff

```kotlin
@Scheduled(fixedDelay = 5000) // 5 seconds
fun pollPendingOperations() {
    val pending = teaRepository.findByStatus(EnrichmentStatus.PENDING)
    pending.forEach { enrichment ->
        val result = yandexClient.pollOperation(enrichment.operationId)
        when (result) {
            is InProgress -> {
                enrichment.pollCount++
                if (enrichment.pollCount > MAX_POLLS) {
                    enrichment.status = EnrichmentStatus.FAILED
                    enrichment.error = "Polling timeout"
                }
                teaRepository.save(enrichment)
            }
            is Success -> {
                enrichment.status = EnrichmentStatus.DONE
                enrichment.result = result.text
                teaRepository.save(enrichment)
            }
            is Failed -> {
                enrichment.status = EnrichmentStatus.FAILED
                enrichment.error = result.message
                teaRepository.save(enrichment)
            }
        }
    }
}
```

**Recommended backoff strategy**:
- Start with 2–5 second intervals
- Exponential backoff: 5s → 10s → 20s → 60s → 120s
- Cap at 120 seconds
- Max polls: ~200 (gives ~6 hours of polling before timeout)

### 7.3 Restart Resilience — Re‑attach by Operation ID

✅ **Yes** — operations are stored **server‑side at Yandex**. After a backend restart:

1. Load all `PENDING` enrichments from your database
2. For each, call `GET /operations/{operationId}`
3. If `done=true`, fetch the result
4. If `done=false`, continue polling
5. If `404 Not Found`, the operation TTL has expired — mark as `FAILED`

**No special “re‑attach” API is needed** — the operation ID is the only key required.

### 7.4 Official Guidance & Gotchas

**From Yandex Cloud async concepts**:

> “If an error occurs during an asynchronous operation, the server cancels all running procedures and returns the system to its previous state. Resources that were created during the operation will be deleted. The Operation object will have an `error` field with the error description.”

**Gotchas to watch for**:

| Issue | Mitigation |
|-------|------------|
| Operation TTL expiry | Store IDs; if `404`, mark as failed and re‑queue |
| Rate limiting on poll endpoint | Use exponential backoff; avoid polling all operations simultaneously |
| Stale `PENDING` records | Implement a maximum poll count or time‑based timeout (e.g., 6 hours) |
| Concurrent polling of same operation | Use `SELECT ... FOR UPDATE` or optimistic locking in your database |
| Network failures during poll | Retry with backoff; treat intermittent failures as `InProgress` |

---

## Summary Table

| Question | Answer |
|----------|--------|
| **Async REST endpoint** | `POST /foundationModels/v1/completionAsync` |
| **Poll endpoint** | `GET /operations/{id}` at `operation.api.cloud.yandex.net` |
| **Operation TTL** | Not publicly documented — assume 24–72 hours |
| **Async on OpenAI‑compat?** | ❌ No — native only |
| **Structured output on async?** | Not confirmed — use prompt‑based JSON |
| **Qwen3-235B async?** | ❌ No — sync‑only |
| **Alice AI LLM Flash async?** | ✅ Yes — 1.25× input / 5× output base rate |
| **Async discount** | 50% off all tokens |
| **Batch mode worth it?** | ❌ Not for 1–50 teas — async is simpler and equally discounted |
| **ToS data logging** | `x-data-logging-enabled: false` applies to all modes |
| **Restart resilience** | ✅ Yes — store `operationId`, poll after restart |

---

## Action Items for TeaTiers

1. **Implement native async client** — cannot reuse OpenAI‑compat code
2. **Use prompt‑based JSON** — `response_format`/`json_schema` not confirmed on async
3. **Keep Qwen3-235B sync‑only** — or switch to `aliceai-llm-flash` for async booster
4. **Store `operationId` in database** — essential for restart resilience
5. **Implement scheduler with exponential backoff** — avoid polling storms
6. **Set a TTL timeout** — assume 24 hours; mark as failed after that
7. **Monitor async costs** — 50% discount applies, but `aliceai-llm-flash` output is 5× base
8. **Skip batch mode for now** — revisit if tea volume reaches thousands per batch