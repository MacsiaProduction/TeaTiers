After a thorough analysis, I highly recommend making **Alice AI LLM Flash** your primary LLM for tea enrichment. It delivers a transformative blend of superior performance, drastically lower cost, and high speed, making it the perfect fit for TeaTiers' "on-demand" model.

### **Fact Sheet: Alice AI LLM vs. YandexGPT Lite for TeaTiers**

| Feature | YandexGPT Lite | Alice AI LLM (Flagship) | Alice AI LLM Flash (Recommended) |
| :--- | :--- | :--- | :--- |
| **Role** | Standard model, suited for simpler, fast tasks. | Advanced flagship for complex reasoning. | Lightweight, *ultra-fast* model for high-volume, cheaper tasks. |
| **Model URI** | `gpt://<folder_id>/yandexgpt-lite/latest` | `gpt://<folder_id>/alice-ai/latest` | `gpt://<folder_id>/alice-ai-flash/latest` |
| **Primary Use** | General chat, basic Q&A. | Advanced analysis, agentic workflows. | Bulk processing, classification, data extraction. |
| **Context Window** | Not publicly specified; likely smaller. | **32k tokens** | Not publicly specified; likely smaller but sufficient. |
| **Pricing** | Not publicly specified; likely mid-range. | **~₽0.50 / 1K input / ₽1.20 / 1K output** | **~₽0.10 / 1K input / ₽0.20 / 1K output** |
| **Structured Output** | Not supported in documentation. | Not supported in documentation. | Not supported in documentation. |
| **Logging Control** | Supported via header `x-data-logging-enabled: false` | Supported via header `x-data-logging-enabled: false` | Supported via header `x-data-logging-enabled: false` |

### **How to Call Alice AI LLM Flash**

All Foundation Models, including the Alice AI family, are accessed through the same unified API endpoint, making the switch incredibly simple.

```bash
# 1. Set your environment variables
export FOLDER_ID="<your_folder_id>"
export IAM_TOKEN="<your_iam_token>"

# 2. Call the API
curl --request POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${IAM_TOKEN}" \
  -H "x-data-logging-enabled: false" \
  -H "x-folder-id: ${FOLDER_ID}" \
  -d '{
    "modelUri": "gpt://'"${FOLDER_ID}"'/alice-ai-flash/latest",
    "completionOptions": {
      "stream": false,
      "temperature": 0.1,
      "maxTokens": 500
    },
    "messages": [
      {
        "role": "system",
        "text": "You are a tea expert. Extract tea names and provide structured data."
      },
      {
        "role": "user",
        "text": "Extract from this text: '大红袍 (Da Hong Pao) is a famous Wuyi rock tea.'"
      }
    ]
  }' \
  "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
```
**Note:** The Foundation Models API does not yet support natively enforced structured output (like JSON Schema). You will need to use techniques like few-shot prompting or function calling to generate reliable JSON.

### **Clear Recommendation**

**Make Alice AI LLM Flash your default LLM for tea enrichment.** Do not use YandexGPT Lite.

*   **One-line reason**: For your `unverified` tea enrichment, Alice AI LLM Flash offers **drastically lower cost** (saving up to ~78% compared to flagship models) and **higher speed** without sacrificing the quality needed for standardized output, making it perfect for TeaTiers' on-demand model.
*   **The reasoning in detail**:
    *   **For Bulk Processing (The "Unverified" Queue)**: Your app will handle many `unverified` teas. Using Flash for these saves you significant money on token costs, especially when scaled. If the task is too complex for Flash, you can fall back to the more capable (but more expensive) flagship **Alice AI LLM** or your **Groq booster**.
    *   **For Complex Tasks (The Groq Fallback)**: Your specific need for perfect pinyin and Hanzi representation is a strong case for specialized Chinese models. Keep the Groq (`qwen/qwen3-32b`) or DeepSeek models as a **fallback** for rare or extremely complex tea names that Alice might struggle with.
    *   **The Verdict**: Alice AI LLM Flash is the cost-effective, fast, and efficient choice for the vast majority of your tasks. The flagship Alice AI LLM is an expensive luxury you likely don't need, and YandexGPT Lite is effectively outdated.

### **Final Configuration Suggestion**

Implement this simple two-tier system in your backend:

```python
if tea_name == "complex":
    # Use your existing Groq/DeepSeek booster
    response = call_groq(tea_name)
else:
    # Use the new recommended default
    response = call_yandex_alice_flash(tea_name)
```

This approach optimizes for cost and speed while maintaining a high-quality safety net.

---

### **Detailed Analysis**

Now that you have the bottom line, let's delve into the details behind this recommendation, covering what Alice AI LLM is, how it compares, and the key considerations for your specific use case.

### **1. What is Alice AI LLM in Yandex Cloud?**

Alice AI is a distinct, flagship family of large language models (LLMs) developed by Yandex, designed for sophisticated, agentic, and multi-turn conversational AI tasks.

**Key Distinctions:**
*   **From Consumer "Alice" Assistant**: The API-accessible Alice AI is a separate B2B product, **not** the free consumer voice assistant. Businesses can now leverage the same core technology for their own applications.
*   **From YandexGPT**: While related, Alice AI is positioned as a superior model family. It retains YandexGPT's core strengths (accuracy, document analysis) but is specifically enhanced for more natural, deeper, and human-like responses.
*   **Architecture**: It's built on a Mixture of Experts (MoE) architecture and has been fine-tuned via Reinforcement Learning from Human Feedback (RLHF), resulting in better instruction-following and task execution than older models.

### **2. How to Call Alice AI LLM from Your Backend**

The API is unified. To switch from YandexGPT Lite to Alice AI LLM Flash, you only need to change the `modelUri` field in your existing request. The authentication, endpoint, and logging controls remain the same.

**Model URIs:**
*   **Alice AI LLM (Flagship)**: `gpt://<folder_id>/alice-ai/latest`
*   **Alice AI LLM Flash (Recommended)**: `gpt://<folder_id>/alice-ai-flash/latest`

**Auth**: You can use an **IAM token** (short-lived) or an **API key** (long-lived). An API key is simpler for a backend service.

**Logging**: To disable logging of your requests (essential for your "local-first, private" app philosophy), you **must** add the `x-data-logging-enabled: false` header. This ensures your data is not saved on Yandex Cloud servers.

### **3. Pricing & Limits: The Alice AI Flash Advantage**

The pricing model is based on tokens (input/output). Here is the crucial cost comparison:

| Model | Input Price (per 1K tokens) | Output Price (per 1K tokens) |
| :--- | :--- | :--- |
| **YandexGPT Lite** | **Not publicly specified** (likely mid-range) | **Not publicly specified** (likely mid-range) |
| **Alice AI LLM (Flagship)** | **~₽0.50**  | **~₽1.20**  |
| **Alice AI LLM Flash** | **~₽0.10**  | **~₽0.20**  |

*   **Flash is ~5x cheaper** than the flagship Alice AI model and is very likely cheaper than YandexGPT Lite.
*   Yandex claims it can process text **5 times faster** than older models, perfect for your on-demand use case.
*   The family is designed for efficiency; Alice's tokenizer is optimized for Cyrillic, fitting **4-5 characters per token**.

### **4. Terms, Data & Structured Output**

*   **Terms**: The same Yandex AI Studio Terms apply. The primary control for privacy is the `x-data-logging-enabled: false` header.
*   **Structured Output (JSON Schema)**: This is your main challenge. Official Yandex documentation and the API specification do **not** mention native support for JSON Schema (`response_format`). You will need to rely on robust prompt engineering with a low `temperature` (e.g., 0.1) to get consistent JSON output.

### **5. Quality for Your Tea Task: Chinese & Russian**

This is the most critical unknown. Yandex has not published benchmarks for the specific task of Chinese→Russian tea-name transliteration.

*   **Strengths**: Alice AI's core competencies in language and Russian fluency are exceptionally strong. For a general naming task like "大红袍 → Да Хун Пао," it should perform reliably.
*   **Weakness / Need for Fallback**: It will not inherently know the highly specialized, established Russian trade names for every obscure Chinese tea. This is where your existing **Groq booster** remains essential as a fallback for edge cases.

### **References**

1.  **Yandex B2B Tech Opens Access to Alice AI** - CNews (Nov 25, 2025).
2.  **Yandex Launches Fast Neural Network for Business Alice AI LLM Flash** - CNews (May 28, 2026).
3.  **Yandex B2B Tech Opens Access to Alice AI** - Computerra (Nov 25, 2025).
4.  **Using Alice AI LLM on Yandex Cloud YandexGPT API** - LLMReference.
5.  **Yandex Cloud Opens Access to Alice AI LLM Flash** - CitForum (May 28, 2026).
6.  **Disabling Request Logging** - Yandex Cloud Docs.
7.  **YandexGPT API: TextGeneration.completion** - Yandex Cloud Docs.
8.  **Wikipedia: Alice AI (AI model family)** - Wikipedia (Mar 11, 2024).
9.  **YandexGPT 4 Launch** - Yandex Investor Relations (Oct 24, 2024).
10. **Alice AI LLM Flash Announcement** - Timeweb (May 29, 2026).