This assessment evaluates whether the Google Gemini API free tier can serve as the primary enrichment LLM for **TeaTiers** from a Germany (EU) VPN egress. 

The evaluation reveals a critical geolocation-based restriction regarding the European Economic Area (EEA) and Google AI Studio's free tier. 

---

### 1. Provider Comparison

| Provider | Free tier (EU, no card?) | Stores+serves output OK? | Trains on free data? | ru/zh tea-naming quality | Signup friction | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Google Gemini** | **No**. Requesting from EEA (including Germany) blocks free tier unless a billing card is attached. | **Yes**, for standard LLM completions. (Except Search/Maps Grounding). | **Yes**. (Cannot disable training on Unpaid Tier). | **High**. Prone to "English-bridge" literal translations without strict prompting. | **Low** (but blocked). Requires a Google account with age verification. | VPN egress inside the EU directly triggers the EEA billing requirement. |
| **DeepSeek** | **No perpetual tier**. Only a one-time 5M token credit (30d expiry) on signup. | **Yes**. Intellectual property terms state that you own the outputs. | **Yes** (unless explicitly opted out via enterprise/paid routes). | **Outstanding**. Highly accurate on pinyin, characters, and Russian phonetics. | **Low**. Email/Google signup. Topping up requires a non-RU international card. | Post-free pricing is incredibly low ($0.14/1M input for `deepseek-v4-flash`). |
| **Groq** | **Yes**. Perpetual free Developer plan available in EU with zero card requirements. | **Yes**. Standard open-weights model licenses apply. | **No** (unless user opts in). | **Very Good** (when using `qwen/qwen3-32b` or `deepseek-r1-distill-llama-70b`). | **Low**. Basic email registration; instant key generation. | Limits: 30 RPM, 14,400 RPD, 30,000 TPM. Extremely fast inference speeds. |
| **Mistral** | **Yes**. Genuinely free "Experiment" plan available in EU with no card. | **Yes**. Standard terms permit database storage. | **Yes** (on unpaid/experimental tier). | **Moderate**. Decent multilingual but struggles with fine Chinese transliteration. | **Low**. Standard email verification; workspace setup. | Limit is ~1 request per second. Headquartered in France. |
| **YandexGPT** | **No perpetual tier** (trial grants only), but standard direct API is paid. | **Yes**. Checked and permitted for database storage. | **No** (standard commercial terms opt-out). | **Very Good for classics** (struggles/hallucinates on obscure variants). | **Medium**. Requires Yandex Cloud passport verification. | Always direct from Russia, bypassing the VPN. Excellent direct backup. |

---

### 2. Gemini Free Tier from the EU (Decisive Findings)

#### Geolocation Gating (Q1)
While Google supports the Gemini API and AI Studio in Germany, the **unpaid free tier is not natively available in the EEA, UK, or Switzerland without a linked Cloud Billing account**. 
* **The Block**: If you route API calls through a Germany/EU VPN egress without a credit card attached to the project, Google's API gateway geolocates the IP to Germany and returns a `400 — User location is not supported for the API use without a billing account linked` error.
* **The Billing Workaround**: To bypass this block while keeping your egress in Germany, you **must enable Google Cloud Billing** on the project. While this does require an active, non-Russian credit card on file, Google AI Studio usage **remains free of charge** as long as your requests stay within the model's free-tier rate limits. 

#### Free-Tier Limits (Q1)
If you enable billing to unlock EU access, the free-tier limits as of mid-2026 are:
* **Gemini 2.5 Flash-Lite** / **Gemini 3.1 Flash-Lite**: 30 RPM / 1,500 RPD / 1,000,000 TPM
* **Gemini 2.5 Flash**: 15 RPM / 1,500 RPD / 1,000,000 TPM
* **Gemini 2.5 Pro**: 5 RPM / 50 RPD / 250,000 TPM (Pro models were restricted in April 2026 to push heavy reasoning use to paid tiers).

#### Output Storage & Training Terms (Q2)
* **Storage and Re-serving**: Google's terms **permit** storing standard model output in a local database and re-serving it to your users, even commercially. In the *Google APIs Terms of Service*, Google states it does not acquire ownership of content transmitted or generated. 
  * *Critical Caveat*: The restriction against caching, storing, or copying output **only applies if you use "Grounding with Google Search" or "Grounding with Google Maps"**. For standard, ungrounded catalog completions, there is no storage ban.
* **Training and Human Review**: Under the Unpaid (free) tier, **prompts and outputs are used to train and improve Google models**, and human reviewers may process your inputs/outputs. 
  * *Disabling Training*: You **cannot** opt out of training on the unpaid free tier. However, if you link a Cloud Billing account (even if you stay within the free limits), your requests are automatically classified as a "Paid Service," which legally binds Google to **not** use your data for training.

---

### 3. DeepSeek (Zh Booster Evaluation) (Q3)

* **No Perpetual Free Tier**: New developer accounts at `platform.deepseek.com` receive a one-off promotional grant of **5 million tokens** valid for 30 days. After this, you must transition to pay-as-you-go.
* **Signup & Friction**: Email or Google signup is simple. Global platform registrations do not require Chinese phone numbers or credit cards to use the free 5M tokens. 
* **Russian Payment Obstacle**: To top up after the promotional tokens expire, you must pay via PayPal or an international card. Because Russian cards are globally blocked from international processing, topping up requires a non-RU bank card or account.
* **Terms & Data Transit**: DeepSeek's terms assert that the user owns the inputs and outputs, permitting database storage and re-serving. However, DeepSeek is headquartered in China, and all API data is processed on Chinese servers under domestic Chinese regulations.
* **Pricing**: Following the release of **DeepSeek V4** in late April 2026, prices are incredibly low:
  * **`deepseek-v4-flash`**: $0.14 / 1M input (cache miss), $0.28 / 1M output.
  * **`deepseek-v4-pro`**: $0.435 / 1M input, $0.87 / 1M output (75% promotional discount made permanent on May 22, 2026).
  * *Note*: The legacy `deepseek-chat` and `deepseek-reasoner` aliases will be deprecated on July 24, 2026.

---

### 4. Groq & Mistral (Free Fallbacks) (Q4)

#### Groq
* **Limits**: The Developer (Free) plan allows **30 RPM / 14,400 RPD / 30,000 TPM**.
* **Friction & EU**: Zero signup friction. Only requires email verification. No payment card is required, and there are **no geoblocks inside the EU (Germany)**.
* **ToS**: Permits storing and re-serving. Groq does not train on developer API completions.
* **Tea Quality**: Exceptional when querying `qwen/qwen3-32b` or distilled reasoning models.

#### Mistral
* **Limits**: The "Experiment" (Free) plan enforces a global **~1 request per second** limit. 
* **Friction & EU**: Based in France, no credit card required to sign up or create keys. Perfect reachability from Germany.
* **ToS**: Standard terms allow output storage, but Mistral may use free-tier prompt data to improve its models.
* **Better than YandexGPT?** Yes. Both Groq and Mistral are superior fallback options because they natively support structured JSON schemas, handle low-volume background tasks efficiently, and Groq's integration of the **Qwen** family provides far better translation for obscure Chinese tea varieties.

---

### 5. Head-to-Head Tea Naming Transliteration (Q5)

This ranking focuses on transliterating Chinese tea trade names into phonetic Russian (e.g., 大红袍 $\rightarrow$ "Да Хун Пао", **not** literal translation "Большой красный халат"), and generating correct pinyin and Chinese characters:

1. **DeepSeek (V4-Flash / Pro) — Rank 1 (Gold Standard)**: Developed in China, this model has deep domain-specific knowledge of Chinese tea sub-varieties (such as niche Dancongs or Rock Teas). It easily correlates characters, standard pinyin, and correct phonetic Russian trade transliterations without spelling bugs.
2. **YandexGPT (Lite) — Rank 2 (Excellent for classics, poor for niche)**: Excellent for highly famous teas (like "Да Хун Пао" or "Те Гуань Инь") because of a rich Russian web indexing corpus. However, it fails on obscure micro-lots or Dancong cultivars, resorting to awkward, literal machine translations (e.g., literally translating "鸭屎香" $\rightarrow$ "Аромат утиного помёта" rather than transliterating to "Я Ши Сян").
3. **Google Gemini (2.5 Flash-Lite) — Rank 3 (Capable but prone to translation errors)**: Highly multilingual, but suffers from "English-bridge translation." It often translates Chinese to English silently first ("Big Red Robe"), and then translates that English string to Russian ("Большой красный халат"). This requires a strict, defensive system prompt to bypass.
4. **Groq (via Qwen3-32B) — Rank 4 (Flawless Chinese, minor Russian awkwardness)**: Perfect Chinese characters and pinyin. The Russian transliterations are highly accurate but can sometimes contain minor spelling irregularities (e.g., outputting "Да Хон Пао" instead of the standard "Да Хун Пао").
5. **Mistral — Rank 5 (Weakest)**: Prone to hallucinating transliterative spelling and occasionally struggles to generate Chinese characters/pinyin accurately in a highly structured format.

---

### 6. Recommended Strategy and Configuration (Q6)

#### Revised Architecture Order
The primary Gemini plan must be revised because routing through a Germany VPN activates Google's EEA block, requiring an active credit card to unlock. 

To build a **perpetual, 100% free, card-free, and split-tunnel friendly enrichment pipeline**, the recommended routing order is:

$$\text{Groq (Primary LLM via VPN)} \rightarrow \text{YandexGPT Lite (Local Russian Fallback direct)} \rightarrow \text{Mistral API (VPN Fallback)}$$

#### Primary LLM request config (Groq with Qwen3-32B)
* **Model ID**: `qwen/qwen3-32b` (highly capable dense multilingual model optimized for structured reasoning and speed).
* **Endpoint**: `https://api.groq.com/openai/v1/chat/completions`
* **Training Opt-Out**: No action required; Groq does not train on developer API data by default.
* **JSON Schema / Structured Output**: Fully supported natively using `response_format`.

**Kotlin (WebClient) Request Payload Example:**

```json
{
  "model": "qwen/qwen3-32b",
  "temperature": 0.1,
  "response_format": {
    "type": "json_object"
  },
  "messages": [
    {
      "role": "system",
      "content": "You are a professional Chinese-to-Russian tea catalog translator. Your task is to enrich unknown tea trade names. You must phonetically transliterate the Chinese trade name into standard Russian cyrillic tea terminology (e.g. 大红袍 -> 'Да Хун Пао', NOT 'Большой красный халат'; 铁观音 -> 'Те Гуань Инь', NOT 'Железная богиня'). Do not translate literally unless specified. You must return valid JSON matching the requested schema."
    },
    {
      "role": "user",
      "content": "Analyze the tea: '鸭屎香'. Return JSON with keys: 'original_characters' (string), 'pinyin' (string), 'russian_transliteration' (string), 'confidence_score' (float between 0 and 1)."
    }
  ]
}
```

---

### 7. Uncertainties / Could Not Verify

* **Google's IP Geolocation Parser**: Whether specific residential or small-scale Germany VPN endpoints occasionally bypass Google's EEA billing check due to lagging IP databases.
* **DeepSeek RU Card Gateway Behavior**: Whether certain Russian UnionPay cards can bypass DeepSeek's merchant gateway restrictions under Alipay/WeChat Pay systems.
* **Groq Model Deprecation Cycle**: The exact lifespan of `groq/deepseek-r1-distill-llama-70b` and `qwen/qwen3-32b` as Groq updates its model catalog.
* **Mistral Background Abuse Checks**: Whether Mistral actively throttles continuous background server-to-server calls originating from free "Experiment" workspace keys.

---

### 8. References

1. [Clarification on “Only Paid Services” for EEA/CH/UK - Google AI Developers Forum](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGSedZQicwdaXeu35tXH838VT1bbw2U1vMtGeLwwtSPO97ENHrNnllSMbJLgUfXHmAbbk3MHelg2AapctjVC8rIGu5nuTA7XS9U4RIiu3H-pjXO_GPUMuOm03w7ri58xUB99bDUGzGGoCy0a1PZsS2wkcvHe7OLud2PeqGmENfBPz2ju5u7koCUi1a4ioE=) *(December 2025)*
2. [Billing Info & Regional Policies - Google Gemini API Docs](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQHJTzoiiYFOWjWPmGLKuMvqrBeCib4bGCU4E9uzIaCadAy8gzp7zSrhT9vzwPgAHvR_natfoiDJG2mwiiVGlpjUw0X-F0UKvbJ4jpeng95Jf1gphEeNooHllQCT2pe5xsGwBg==) *(June 2026)*
3. [Gemini API Additional Terms of Service - Google AI for Developers](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEIyINnOHMoSDfBPeajTIBoW2eN6XDS9dH6TGNCkQflGJ1xN5cLb-XY11DLNiaT-JvGiUCX5OUMFiNS_LkGlit_UO7tESQyAAgDB0ue0CDyfYxDTN1ElQXKEJJL) *(April 2026)*
4. [User Location Blocked Without Billing Workarounds - Stack Overflow](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFoneOp3os64vhzevX89PBfjhxmVUo07WVuGyesyd-fGU6tDehyibF23m2uBhftIsC1dPP9A1E2dNOk16_0qpVJDclASVNInK5w1TcH4oOhbXYhO9rxRilzE-iwNl3_CLiTTsSV9dkaodXbpTppjjNxbACdRV01QuZtMJfdqDTtc1Syu2Gual-svPHUj4gLQHR6vhrJfOompSf2XLjqx7edGW4OcXJ6BepSkaM8te0fFKlB) *(May 2024 / Verified Active 2026)*
5. [DeepSeek Terms of Use & API Terms - DeepSeek Official](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFymHFC9JrEjF4I8FCgykn_1xTUc8Z1qfXfFzfMYrZC2HJQkegFpp0uXXL6P-xwhs9JdYWzRNIc9-n8pb121KnS32DUkR4R8soZsCRNNEYvUc5ZZ-mzmMUyDNO1Wmj3CvQ8nWqNeY6ccruE_DghlLC3cggLGW05GA==) *(March 2026)*
6. [DeepSeek V4 Pricing, Release and Migration Guidelines - CloudZero](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGcaC1sl0O4OdYVJ-wHvkuX5WPYKJR7ersDUCJTxTQYJy7eyqLl4s6kOgLgHkFRkvtxohjp3bbbgLcMaCRy1wtYW6klniz_XOPUm0inSPFi7MP7s6BlcT65hyU4zO5o20fHZlYwFQ==) *(June 2026)*
7. [Groq API Developer Quotas and Rate Limits - Groq Docs](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGYcGEQQtRjDYBgZIagaDtgW_nkjRQ8X3Ei0co2viGZaMqc_z-2at0KXV8h2F9zUAZnwnF31_chzcoFanhB56epJ4pjEXBWVLiHrLTLROEdbdZKmlEB9bP2-aZcs-Vd6g==) *(May/June 2026)*
8. [Mistral AI API Terms, Rates and Workspace Documentation - Mistral Docs](https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQH43Y7v3J1W6RIdI3ep1vCvSkFQ3BjiHQxUTQHYCTjN97XM34WCIFhvxEt_EUpVjMBJ1mS4Qiyo2BTlhmlnHfZx7bm7K4bA1RWC6aiRJm2k4GjCdjpvnTrd7On3iJT1iYyB1SoWtbjw_1pTFWFGabE=) *(June 2026)*