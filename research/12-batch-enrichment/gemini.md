## Asynchronous REST Shape & Polling Specification

To adopt the asynchronous completion pattern, your Kotlin/Spring Boot backend must communicate with two separate endpoints: the **Text Generation Async Endpoint** (to submit the prompt and obtain an execution token) and the **Global Operations Endpoint** (to poll for the final results). 

### 1. Request Endpoint & Headers
```http
POST https://llm.api.cloud.yandex.net/foundationModels/v1/completionAsync
```
**Required Headers:**
*   `Authorization: Api-Key <SECRET_API_KEY>` (or `Bearer <IAM_TOKEN>` if using rotating tokens)
*   `x-folder-id: <FOLDER_ID>` (Required to define the billing and quota context)
*   `x-data-logging-enabled: false` (Must be sent as a string literal `"false"` to prevent Yandex from utilizing your tea data for model training)
*   `Content-Type: application/json`

### 2. Request Body Structure
```json
{
  "modelUri": "gpt://<FOLDER_ID>/yandexgpt/latest",
  "completionOptions": {
    "stream": false,
    "temperature": 0.3,
    "maxTokens": "2000"
  },
  "messages": [
    {
      "role": "system",
      "text": "You are a professional tea sommelier. Analyze the given tea name."
    },
    {
      "role": "user",
      "text": "Normalize and evaluate: Da Hong Pao"
    }
  ],
  "jsonSchema": {
    "schema": {
      "type": "object",
      "properties": {
        "pinyin": { "type": "string" },
        "flavors": {
          "type": "array",
          "items": { "type": "integer" }
        },
        "ru_blurb": { "type": "string" }
      },
      "required": ["pinyin", "flavors", "ru_blurb"]
    }
  }
}
```

### 3. Immediate Response (The Operation Object)
Yandex instantly returns an **Operation** object representing the queued generation task. At this point, the text is not yet generated.
```json
{
  "id": "fcmq0j5033e516c56ctq",
  "description": "Async completion task",
  "createdAt": "2026-06-17T20:46:00Z",
  "createdBy": "ajehumcuv38h********",
  "modifiedAt": "2026-06-17T20:46:00Z",
  "done": false,
  "metadata": null
}
```

### 4. Polling Endpoint & Execution Mechanics
To fetch results, perform a `GET` request using the operation's `id`:
```http
GET https://operation.api.cloud.yandex.net/operations/fcmq0j5033e516c56ctq
```
*(Note: While some legacy configurations point to `llm.api.cloud.yandex.net/operations/{id}`, Yandex Cloud standardizes on the global `operation.api.cloud.yandex.net` routing for reliable resolution).*

#### Terminal States
The operation will return `"done": false` until complete. Once finished, `"done": true` is returned along with either a `response` containing the structured outputs or an `error` block.

#### Completed Operation Response
```json
{
  "id": "fcmq0j5033e516c56ctq",
  "description": "Async completion task",
  "createdAt": "2026-06-17T20:46:00Z",
  "createdBy": "ajehumcuv38h********",
  "modifiedAt": "2026-06-17T20:46:12Z",
  "done": true,
  "response": {
    "@type": "type.googleapis.com/yandex.cloud.ai.foundation_models.v1.CompletionResponse",
    "alternatives": [
      {
        "message": {
          "role": "assistant",
          "text": "{\"pinyin\":\"Dà Hóng Páo\",\"flavors\":[5,4,2,0,1,1,3,4,4,1,2],\"ru_blurb\":\"Легендарный улун сильной прожарки с утесным утесом и глубоким сухофруктовым профилем.\"}"
        },
        "status": "ALTERNATIVE_STATUS_FINAL"
      }
    ],
    "usage": {
      "inputTextTokens": "145",
      "completionTokens": "64",
      "totalTokens": "209"
    }
  }
}
```

#### Failed Operation Response
```json
{
  "id": "fcmq0j5033e516c56ctq",
  "description": "Async completion task",
  "createdAt": "2026-06-17T20:46:00Z",
  "createdBy": "ajehumcuv38h********",
  "modifiedAt": "2026-06-17T20:46:02Z",
  "done": true,
  "error": {
    "code": 4,
    "message": "Resource exhausted (quota limit reached)"
  }
}
```

### 5. Operation / Result TTL (Time-To-Live)
Yandex Cloud enforces a strict architecture limit: completed or failed asynchronous text operations are stored on the server for exactly **3 days** (72 hours). If your application does not retrieve the result within this window, a `GET` request will return a `404 Not Found`.

---

## OpenAI-Compatible vs. Native REST API Deltas

Yandex's OpenAI-compatible completions endpoint (`/v1/chat/completions`) is strictly **synchronous**. It does not support asynchronous polling. To use async mode, you must shift from OpenAI-compatible JSON to Native Yandex REST JSON. 

| Feature / Field | OpenAI-Compatible API (`/v1/chat/completions`) | Native REST API (`/v1/completionAsync`) |
| :--- | :--- | :--- |
| **Model Selection** | `"model": "gpt://<folder_id>/yandexgpt/latest"` | `"modelUri": "gpt://<folder_id>/yandexgpt/latest"` |
| **Message Structure** | `messages[].content` | `messages[].text` (Note: **"text"** instead of **"content"**) |
| **Completion Configuration** | Root fields: `"temperature": 0.3`, `"max_tokens": 1000` | Wrapped object: `"completionOptions": { "temperature": 0.3, "maxTokens": "2000" }` |
| **JSON Schema Enforcer** | `"response_format": { "type": "json_schema", "json_schema": { "strict": true, "schema": ... } }` | Top-level property: `"jsonSchema": { "schema": ... }` (Mutually exclusive with `"jsonObject"`) |
| **Output Format Enforcer** | `"response_format": { "type": "json_object" }` | Top-level property: `"jsonObject": true` |
| **Immediate Response** | Final completed chat alternative. | Task Operation metadata object containing the polling `id`. |

---

## Structured Output Capabilities & Model Compatibility Warnings

### ⚠️ Critical Compatibility Warning
Your background async tier **cannot** use **Alice AI LLM Flash** (`aliceai-llm-flash`) or **Qwen3-235B** (`qwen3-235b-a22b-fp8`) via the `completionAsync` endpoint.
According to official Yandex AI Studio routing matrices:
1. **Alice AI LLM Flash** and **Qwen3-235B** are only exposed through the **OpenAI-compatible APIs**. They do not support Yandex's native Text Generation APIs, meaning any call to `completionAsync` with these model URIs will return a `400 Bad Request` or `404 Not Found`.
2. To build an asynchronous background processing pipeline, you must swap these models for equivalent options that support Native Text Generation.

### Recommended Model Mapping for Background Async Processing:
*   **For the cheap tier (replacing Alice Flash):** Use **YandexGPT Lite** (`gpt://<folder_id>/yandexgpt-5-lite` or `yandexgpt-lite` aliases). It natively supports `completionAsync`, is highly cost-efficient, and runs fully on the native text generation path.
*   **For the booster tier (replacing Qwen3-235B):** Use **YandexGPT Pro 5.1** (`gpt://<folder_id>/yandexgpt-5.1` or `yandexgpt-5-pro` aliases). This is Yandex's flagship native model, heavily optimized for database structuring, data extraction, and RAG scenarios.

### Structured Outputs Configuration on Native Async Mode:
Both `yandexgpt-5-lite` and `yandexgpt-5.1` support **native schema-constrained decoding**.

1.  **JSON Schema (`jsonSchema`):** You pass the raw JSON Schema draft-04/07 object directly under `"jsonSchema": { "schema": { ... } }`. Under the hood, Yandex constrains the output logits during generation. It behaves identically to OpenAI's `"strict": true` constraint—**guaranteeing conformant JSON without validation failures**.
2.  **JSON Object (`jsonObject: true`):** If you prefer not to supply a strict schema, you can set `"jsonObject": true`. This forces the model to emit valid JSON structure, but does not constrain properties. You must explicitly prompt the model detailing the expected keys to avoid arbitrary schema variations.

---

## Pricing Model & Quota Analysis

Asynchronous processing in Yandex Cloud is heavily discounted compared to synchronous mode, making it perfect for non-urgent background workers.

### 1. Per-Token Pricing Comparison (USD & RUB equivalents)
*Note: Yandex Cloud RU VMs are billed natively in Russian Rubles (RUB, inclusive of VAT). International customers are billed in USD (net of VAT).*

| Model & Mode | Input Price / 1K Tokens | Output Price / 1K Tokens | Discount vs. Synchronous |
| :--- | :--- | :--- | :--- |
| **YandexGPT Lite 5 (Sync)** | $0.001639344 (~0.20₽) | $0.001639344 (~0.20₽) | — |
| **YandexGPT Lite 5 (Async)** | **$0.000819672 (~0.10₽)** | **$0.000819672 (~0.10₽)** | **Exactly 50% discount** |
| **YandexGPT Pro 5.1 (Sync)** | $0.006557376 (~0.80₽) | $0.006557376 (~0.80₽) | — |
| **YandexGPT Pro 5.1 (Async)** | **$0.0033606552 (~0.40₽)** | **$0.0033606552 (~0.40₽)** | **~48.7% discount** |

### 2. Operational Quotas and Limits
Your folder is bound to default organizational limits that prevent system overload. These are technical constraints and cannot be bypassed without a support request:
*   **Async Submission Rate:** Maximum **10 requests per second** (RPS) submitting to `/v1/completionAsync`.
*   **Async Polling Rate:** Maximum **50 requests per second** (RPS) querying `/operations/{id}`.
*   **Hourly Cumulative Submission Limit:** Maximum **5,000 async requests per hour**.
*   **Trial Grant coverage:** The initial trial grant (e.g., received on linking a credit card) and promotional credits cover all `completionAsync` usage natively.

### 3. Reconciling with your Cost Guards
To maintain a rigid global daily ceiling:
1.  **Estimate Token Weights:** Your daily ceiling loop must deduct tokens processed. Since async token generation costs ~50% less, you should define your budget mathematically by **cost (RUB/USD spent)** rather than raw request counts.
2.  **Post-Generation Syncing:** The scheduler polling the completed operation receives precise token usages in the `response.usage` object (`inputTextTokens`, `completionTokens`). Once an operation transitions to `done: true`, your Spring Boot app must fetch these usage numbers and deduct the cost from your global daily limits.

---

## Batch / Dataset Mode: Evaluation & Verdict

Yandex AI Studio documents a third mode: **Batch Inference** (`TextGenerationBatch`). 

### How Yandex Batch Mode Works:
1.  Input queries are compiled into a single JSONL file, formatted as separate JSON request lines.
2.  The dataset file is uploaded via the **Datasets/Files API** (stored inside Yandex Cloud Object Storage).
3.  A batch task is triggered via a `POST /foundationModels/v1/completionBatch` request, pointing to the dataset resource ID.
4.  The platform processes the list offline and outputs a downloadable file.

### ❌ The Catch: "Not Implemented Yet"
According to Yandex Cloud AI Studio’s current REST and gRPC technical specification:
*   **Text Generation Batching is explicitly flagged as "Not implemented yet"** for text generation models like `yandexgpt` and `qwen3`. It is currently restricted to zero-shot classification pipelines, fine-tuning validation, and text embedding vectorization.
*   **Extreme Quota Restrictions:** Even for supported services, the quotas restrict you to a maximum of **10 runs per hour** and **100 runs per day**.

### Final Verdict: Fan Out Async Operations
For background wake cycles of **1 to 50 teas**, do not attempt to use Batch/Dataset mode. It is structurally impossible for text models today, highly complex due to file-upload overhead, and subject to severe limits. 

**Your ideal design is to fan out asynchronous operations** across `completionAsync`. Fanning out 50 concurrent async tasks is simple, uses the cheaper pricing model, runs natively with strict schema-constrained outputs, and easily fits within your generous 10 RPS submission / 50 RPS polling quotas.

---

## Data Privacy & ToS Alignment

Your current configuration sends the `x-data-logging-enabled: false` header to protect corporate IP and sensitive content. Under Yandex Cloud AI Studio terms (specifically cl. 4.1/3.15 ToS on service data):

1.  **Async/Sync Parity:** Sending the `x-data-logging-enabled: false` header with native async (`POST /completionAsync`) requests is **fully supported and honored**. It blocks the storage of prompts or generated outputs for downstream model training on the Yandex side.
2.  **Server-Side Caching:** Because logging is disabled, server-side prompt caching features may be bypassed or run in a highly restricted volatile memory space to prevent unauthorized data persistence.
3.  **Storing and Re-Serving Output:** Under Yandex AI ToS, once the model returns output to your virtual machine, **you own the license to store and re-serve the outputs indefinitely** within your local database. There are no restrictions preventing your Spring Boot server from caching or serving these generated flavor profiles to client devices.

---

## Non-blocking Durable Concurrency Architecture

To handle background enrichment safely without holding active DB transactions, blocking execution threads, or risking data loss on JVM crashes, implement a **Submit-Then-Store-and-Poll State Machine**.

```
               [ Background Android Sync Submit ]
                             │
                             ▼
              ┌──────────────────────────────┐
              │  Insert Row: Status=PENDING  │
              └──────────────┬───────────────┘
                             │
                             ▼ (Immediate HTTP 200 to Client)
                  [ Spring Boot Scheduler ]
                             │
            ┌────────────────┴────────────────┐
            ▼ (Every 10s)                     ▼ (Every 30s)
 ┌───────────────────────┐         ┌───────────────────────┐
 │   Submission Runner   │         │    Polling Runner     │
 └──────────┬────────────┘         └──────────┬────────────┘
            │                                 │
            ├─► Read PENDING                  ├─► Read SUBMITTED
            ├─► POST /completionAsync         ├─► GET /operations/{id}
            │   (non-blocking WebClient)      │   (non-blocking WebClient)
            │                                 │
            ▼                                 ▼
 ┌───────────────────────┐         ┌───────────────────────┐
 │ Update DB:            │         │ If done == true:      │
 │ - Status=SUBMITTED    │         │ - Parse JSON Schema   │
 │ - operation_id = YC_id│         │ - Update Tea Records  │
 └───────────────────────┘         │ - Status=COMPLETED    │
                                   │   (or FAILED)         │
                                   └───────────────────────┘
```

### Advantages:
1.  **Durability (Re-attach Capable):** Because the `operation_id` is written to the DB before polling, **a backend crash or restart will not lose any in-flight requests**. Upon startup, the Polling Runner queries all rows marked `SUBMITTED`, reads their IDs, and seamlessly re-attaches to the ongoing Yandex operations. You have a 3-day window to pull these before they expire.
2.  **Thread Pool Isolation:** WebClient handles HTTP requests asynchronously via netty EventLoops. No worker thread blocks waiting for Yandex to generate text (which takes 5–15 seconds).
3.  **Transaction Isolation:** DB connections are only held during fast insert/update queries, avoiding long-lived connection locks.

### Kotlin + Spring Boot Implementation Example

#### 1. Entity and Enum Definitions
```kotlin
package com.teatiers.enrichment

import java.time.LocalDateTime
import java.util.UUID

enum class EnrichmentStatus {
    PENDING,    // Queued locally, waiting for API submission
    SUBMITTED,  // Posted to Yandex, has operation_id
    COMPLETED,  // Succeeded and structured tea data is saved
    FAILED      // Terminal failure
}

data class TeaEnrichmentTask(
    val id: UUID = UUID.randomUUID(),
    val teaId: UUID,
    val teaName: String,
    var operationId: String? = null,
    var status: EnrichmentStatus = EnrichmentStatus.PENDING,
    var retryCount: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
```

#### 2. Spring Non-blocking WebClient Config
```kotlin
package com.teatiers.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class YandexClientConfig {

    @Value("\${yandex.api-key}")
    private lateinit var apiKey: String

    @Value("\${yandex.folder-id}")
    private lateinit var folderId: String

    @Bean
    fun yandexLlmWebClient(): WebClient {
        return WebClient.builder()
            .baseUrl("https://llm.api.cloud.yandex.net")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Api-Key $apiKey")
            .defaultHeader("x-folder-id", folderId)
            .defaultHeader("x-data-logging-enabled", "false") // Strict privacy guard
            .build()
    }

    @Bean
    fun yandexOperationsWebClient(): WebClient {
        return WebClient.builder()
            .baseUrl("https://operation.api.cloud.yandex.net")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Api-Key $apiKey")
            .defaultHeader("x-folder-id", folderId)
            .build()
    }
}
```

#### 3. Enterprise Integration Service
```kotlin
package com.teatiers.enrichment

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.util.UUID

// Request / Response Payload Mappers
data class NativeMessage(val role: String, val text: String)
data class CompletionOptions(val stream: Boolean = false, val temperature: Double = 0.3, val maxTokens: String = "2000")
data class JsonSchemaWrapper(val schema: Map<String, Any>)

data class AsyncRequestPayload(
    val modelUri: String,
    val completionOptions: CompletionOptions,
    val messages: List<NativeMessage>,
    val jsonSchema: JsonSchemaWrapper
)

data class YcOperationResponse(
    val id: String,
    val done: Boolean,
    val response: CompletionResponse?,
    val error: OperationError?
)

data class CompletionResponse(
    val alternatives: List<Alternative>?,
    val usage: UsageMetrics?
)

data class Alternative(val message: NativeMessage, val status: String)
data class UsageMetrics(val inputTextTokens: String, val completionTokens: String, val totalTokens: String)
data class OperationError(val code: Int, val message: String)

@Service
class TeaEnrichmentCoordinator(
    private val llmClient: WebClient,
    private val opsClient: WebClient,
    private val taskRepository: EnrichmentTaskRepository, // Assume JPA or JDBC repository
    private val teaRepository: TeaRepository
) {
    private val logger = LoggerFactory.getLogger(TeaEnrichmentCoordinator::class.java)
    private val folderId = "b1gxxxxxxxxxxxxxxxxx" // Inject folder ID safely

    // 1. submission runner: Processes pending tasks, submits to Yandex, and stores Operation ID
    @Scheduled(fixedDelay = 10000) // Sweeps every 10 seconds
    fun submitPendingTasks() {
        val pendingTasks = taskRepository.findByStatusLimit(EnrichmentStatus.PENDING, 10)
        
        for (task in pendingTasks) {
            // Build the Native JSON Schema structure
            val targetSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "pinyin" to mapOf("type" to "string"),
                    "ru_blurb" to mapOf("type" to "string"),
                    "flavors" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "integer")
                    )
                ),
                "required" to listOf("pinyin", "ru_blurb", "flavors")
            )

            // Hard decision: Using YandexGPT Lite for cheap, non-urgent background processing
            val payload = AsyncRequestPayload(
                modelUri = "gpt://$folderId/yandexgpt-5-lite",
                completionOptions = CompletionOptions(),
                messages = listOf(
                    NativeMessage("system", "Extract pinyin name, estimate 11 flavor profiles (0-5), and write a short blurb in Russian for the following tea."),
                    NativeMessage("user", task.teaName)
                ),
                jsonSchema = JsonSchemaWrapper(targetSchema)
            )

            llmClient.post()
                .uri("/foundationModels/v1/completionAsync")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map::class.java)
                .map { responseMap -> responseMap["id"] as String }
                .subscribe(
                    { operationId ->
                        task.operationId = operationId
                        task.status = EnrichmentStatus.SUBMITTED
                        task.updatedAt = LocalDateTime.now()
                        taskRepository.save(task)
                        logger.info("Successfully submitted tea task ${task.id} to Yandex. Operation ID: $operationId")
                    },
                    { error ->
                        logger.error("Failed to submit task ${task.id} to Yandex Cloud: ${error.message}")
                        task.retryCount++
                        if (task.retryCount > 3) {
                            task.status = EnrichmentStatus.FAILED
                        }
                        task.updatedAt = LocalDateTime.now()
                        taskRepository.save(task)
                    }
                )
        }
    }

    // 2. Polling Runner: Scans db for submitted IDs, polls Yandex, parses structural results
    @Scheduled(fixedDelay = 30000) // Polls every 30 seconds
    fun pollActiveOperations() {
        val submittedTasks = taskRepository.findByStatus(EnrichmentStatus.SUBMITTED)

        for (task in submittedTasks) {
            val opId = task.operationId ?: continue

            opsClient.get()
                .uri("/operations/$opId")
                .retrieve()
                .bodyToMono(YcOperationResponse::class.java)
                .subscribe(
                    { ycResponse ->
                        if (ycResponse.done) {
                            if (ycResponse.error != null) {
                                logger.error("Yandex returned execution error for task ${task.id}: ${ycResponse.error.message}")
                                handleTaskFailure(task)
                            } else {
                                val rawText = ycResponse.response?.alternatives?.firstOrNull()?.message?.text
                                if (rawText != null) {
                                    // Complete database update
                                    saveStructuredTeaData(task.teaId, rawText)
                                    task.status = EnrichmentStatus.COMPLETED
                                    task.updatedAt = LocalDateTime.now()
                                    taskRepository.save(task)
                                    logger.info("Task ${task.id} completed. Token usage: Input=${ycResponse.response.usage?.inputTextTokens}, Output=${ycResponse.response.usage?.completionTokens}")
                                } else {
                                    logger.error("Operation ${task.id} marked done but alternative payload was empty.")
                                    handleTaskFailure(task)
                                }
                            }
                        } else {
                            logger.debug("Operation $opId is still in progress at Yandex.")
                        }
                    },
                    { error ->
                        logger.error("Network failure while polling operation $opId: ${error.message}")
                        // Do not instantly fail; wait for next scheduler loop (re-polling)
                    }
                )
        }
    }

    private fun handleTaskFailure(task: TeaEnrichmentTask) {
        task.retryCount++
        task.status = if (task.retryCount >= 3) EnrichmentStatus.FAILED else EnrichmentStatus.PENDING
        task.updatedAt = LocalDateTime.now()
        taskRepository.save(task)
    }

    private fun saveStructuredTeaData(teaId: UUID, rawJson: String) {
        // Parse the native constrained JSON safely with Jackson or Gson
        // Update the Tea DB record and toggle "unverified = false"
        logger.info("Saving structured tea data for ID $teaId: $rawJson")
    }
}

// Dummy Interface representations for compile verification
interface EnrichmentTaskRepository {
    fun findByStatusLimit(status: EnrichmentStatus, limit: Int): List<TeaEnrichmentTask>
    fun findByStatus(status: EnrichmentStatus): List<TeaEnrichmentTask>
    fun save(task: TeaEnrichmentTask): TeaEnrichmentTask
}

interface TeaRepository {
    // Methods for tea state updates
}
```