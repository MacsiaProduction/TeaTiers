### Fact Sheet

| Parameter | **Alice AI LLM** | **Alice AI LLM Flash** | **YandexGPT Lite** (v5) |
| :--- | :--- | :--- | :--- |
| **Exact Model URI** | `gpt://<folder_id>/aliceai-llm` (versioned `/latest` or `/rc`) | `gpt://<folder_id>/aliceai-llm-flash` (versioned `/latest`) | `gpt://<folder_id>/yandexgpt-5-lite` (or `yandexgpt-lite`) |
| **Context Window** | **64k tokens** (65,536) | **64k tokens** (65,536) | **32k tokens** (32,768) |
| **Endpoints** | REST, gRPC, OpenAI-compatible | OpenAI-compatible ONLY | REST, gRPC, OpenAI-compatible |
| **OpenAI Base URL** | `https://ai.api.cloud.yandex.net/v1` | `https://ai.api.cloud.yandex.net/v1` | `https://ai.api.cloud.yandex.net/v1` |
| **Auth Headers** | `Authorization: Bearer <iam_token>` or `Authorization: Api-Key <api_key>` + `x-folder-id: <folder_id>` | `Authorization: Bearer <iam_token>` or `Authorization: Api-Key <api_key>` + `x-folder-id: <folder_id>` | `Authorization: Bearer <iam_token>` or `Authorization: Api-Key <api_key>` + `x-folder-id: <folder_id>` |
| **Structured Output** | Fully supported (`response_format` JSON schema) | Fully supported (`response_format` JSON schema) | Fully supported (`response_format` JSON schema) |
| **Price (per 1k Input)** | **0.5 ₽** (incl. VAT) | **0.1 ₽** (incl. VAT) | **0.2 ₽** (incl. VAT) |
| **Price (per 1k Output)**| **1.2 ₽** (incl. VAT) | **0.2 ₽** (incl. VAT) | **0.2 ₽** (incl. VAT) |
| **Opt-Out Logging** | `x-data-logging-enabled: false` | `x-data-logging-enabled: false` | `x-data-logging-enabled: false` |
| **Output Retention ToS**| Allowed to store & serve output | Allowed to store & serve output | Allowed to store & serve output |

---

### 1. What "Alice AI LLM" is
Launched in late November 2025, **Alice AI LLM** is Yandex's flagship language model family offered in Yandex Cloud AI Studio. It represents a **distinct, next-generation foundational model family separate from standard YandexGPT**. 

* **The Core Distinctions:** While YandexGPT (Lite and Pro) is designed for generalized developer tasks (e.g., fast classification or batch processing), the Alice AI LLM is trained for agentic tasks, complex instructions, context extraction, and highly human-like conversational flows. 
* **Distinction from Consumer Tech:** This API is **strictly the raw foundation text model**. It is entirely distinct from the *consumer voice assistant* (which handles audio, hardware speaker integration, and smart home states) and the *Yandex Dialogs (Alice Skills) webhook platform*. 
* **Context & Capability:** At 64k tokens, it provides twice the context length of standard YandexGPT 5 Lite (32k). It currently leads the comprehensive SLAVA benchmark evaluating LLMs in the Russian language.

---

### 2. How to Call It
Alice AI LLM integrates with the same endpoints and authentication protocols as YandexGPT, natively supporting standard JSON schema structures (`json_schema`).

#### Minimal curl Example (Enriching Tea)
```bash
curl --request POST \
  --url https://ai.api.cloud.yandex.net/v1/chat/completions \
  --header "Authorization: Api-Key ${YANDEX_API_KEY}" \
  --header "x-folder-id: ${YANDEX_FOLDER_ID}" \
  --header "x-data-logging-enabled: false" \
  --header "Content-Type: application/json" \
  --data '{
    "model": "gpt://'${YANDEX_FOLDER_ID}'/aliceai-llm-flash/latest",
    "messages": [
      {
        "role": "system",
        "content": "You are an expert tea sommelier and linguist. Translate the tea name into pinyin and Russian transliteration. Output JSON strictly adhering to the schema."
      },
      {
        "role": "user",
        "content": "Tea name: 大红袍"
      }
    ],
    "response_format": {
      "type": "json_schema",
      "json_schema": {
        "name": "tea_enrichment_schema",
        "strict": true,
        "schema": {
          "type": "object",
          "properties": {
            "hanzi": { "type": "string" },
            "pinyin": { "type": "string" },
            "russian_transliteration": { "type": "string" },
            "russian_translation": { "type": "string" },
            "english_translation": { "type": "string" }
          },
          "required": ["hanzi", "pinyin", "russian_transliteration", "russian_translation", "english_translation"]
        }
      }
    }
  }'
```

---

### 3. Pricing, Limits, & Terms

* **Pricing Dynamics:**
  * Standard **Alice AI LLM** is significantly more expensive than YandexGPT Lite. Standard output tokens cost 6x more (**1.2 ₽** vs **0.2 ₽** per 1k).
  * However, **Alice AI LLM Flash** is exceptionally cost-effective. It costs **half** as much as YandexGPT Lite for incoming text (**0.1 ₽** vs **0.2 ₽** per 1k) and matches it for output generation (**0.2 ₽** per 1k).
* **Limits:** They share standard folder-level quotas on Yandex Cloud, which are easily scale-requested through support.
* **ToS & Privacy Compliance:** Just like YandexGPT, you can block data retention and training on your proprietary queries by passing `--header "x-data-logging-enabled: false"`. There are no specific restrictions on caching, storing, or serving generated results within your application's local-first database. Standard trial/account promotional grants cover these models completely.

---

### 4. Quality for Chinese→Russian Tea-Name Transliteration

1. **Russian Fluency & Jargon:** Alice AI LLM is superior to `yandexgpt-lite`. Because tea culture is very prominent on the Russian web (forums, blogs, stores), Alice AI LLM easily maps common trade names like 大红袍 to *"Да Хун Пао"* (correct Russian transliteration) and 铁观音 to *"Те Гуань Инь"*, rather than generating clumsy literal translations (such as "Большой красный халат").
2. **Where It Fails (The Need for a Booster):** Despite its strong Russian linguistic capabilities, Alice lacks deep multilingual training on rare, highly specialized Chinese characters and precise regional dialetical phonetics (such as Wuyi Rock teas or Dancong cultivars). It may still hallucinate pinyin tones or struggle with rare Hanzi.
3. **An Architectural Surprise:** Yandex Cloud AI Studio now hosts **DeepSeek V4 Flash** and **Qwen3 (235B)** directly within its Model Gallery. Consequently, instead of routing a Groq connection via a German VPN, you can run your Chinese-enrichment booster natively on the same Yandex backend using the standard OpenAI-compatible SDK.

---

### 5. Recommendation & Configuration

#### Recommendation: **Make Alice AI LLM Flash your default primary.**

**Why:** It outperforms `yandexgpt-lite` with a larger **64k context window** (vs 32k) and vastly superior dialogue/instruction-following capability. Economically, it is actually **cheaper** (0.1 ₽/1k input vs 0.2 ₽), making it a highly optimal, modern choice for standard tea-card formatting. If a rare tea fails to validate or format correctly, fall back synchronously to the flagship **Alice AI LLM** as your heavy-duty tier-2 verifier.

#### Configuration (Kotlin/Java conceptual setup for Alice AI LLM Flash)
```kotlin
val yandexApiKey = System.getenv("YANDEX_API_KEY")
val folderId = System.getenv("YANDEX_FOLDER_ID")

val teaEnrichmentConfig = OpenAIConfig(
    baseUrl = "https://ai.api.cloud.yandex.net/v1",
    apiKey = yandexApiKey,
    defaultHeaders = mapOf(
        "x-folder-id" to folderId,
        "x-data-logging-enabled" to "false" // Enforce privacy
    )
)

val modelUri = "gpt://$folderId/aliceai-llm-flash/latest"
```

---

### Reference Links

1. [Yandex Cloud AI Studio: Available Generative Models](https://yandex.cloud/en/docs/ai-studio/concepts/models) (Checked: June 2026)
2. [Yandex Cloud AI Studio: Pricing Policy](https://yandex.cloud/en/docs/ai-studio/pricing) (Checked: June 2026)
3. [Yandex Cloud AI Studio: Request Logging Opt-Out Doc](https://yandex.cloud/en/docs/ai-studio/concepts/data-logging) (Checked: June 2026)
4. [Yandex Cloud AI Studio: Quickstart Responses API](https://yandex.cloud/en/docs/ai-studio/quickstart) (Checked: June 2026)
5. [Habr: Yandex GPT and Alice AI API Integrations](https://habr.com/ru/articles/889345/) (Published: April 2026)
6. [Toolarium: YandexGPT & Alice AI Model Comparison & Benchmarks](https://toolarium.ru/yandexgpt-2026-models) (Published: March 2026)
7. [Caila AI: Yandex Model API Token Pricing Metrics](https://caila.io/app/just-ai/yandexgpt) (Checked: June 2026)

---

### "Uncertain / Could Not Verify" List

* **Rate Limits:** Exact default RPM (Requests Per Minute) and TPM (Tokens Per Minute) specifically allocated for `aliceai-llm-flash` versus `aliceai-llm` are not publicly cataloged, as limits in Yandex AI Studio are highly dependent on individual account tiers and folder limits (standard basic trial limits generally apply).
* **Chinese Translits Benchmarks:** No verified, public scientific benchmarks exist comparing the direct translation accuracy of Chinese tea jargon between Yandex's native models and Chinese models (like Qwen). Evaluating translation quality is qualitative and reliant on general performance data.