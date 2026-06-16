package com.macsia.teatiers.client

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tunables for the Foundation Models enrichment tier (plan.md section 6 step 3; decision #65 bake-off).
 *
 * Models are config-selectable: [primaryModel] (ru/en, Alice Flash) writes the Russian blurb;
 * [boosterModel] (Qwen3-235B) handles Chinese-source text. Both are reached through the Yandex
 * OpenAI-compatible endpoint with one `response_format:{type:"json_schema"}` code path.
 *
 * [apiKey] is injected from the environment at deploy time (sourced from Lockbox
 * `teatiers-llm-api-key`); it is never committed (rule 50-secure). A blank key disables the tier.
 */
@ConfigurationProperties(prefix = "teatiers.llm")
data class LlmProperties(
    val enabled: Boolean = true,
    /** Yandex OpenAI-compatible base; the chat-completions path is appended by the client. */
    val endpoint: String = "https://llm.api.cloud.yandex.net/v1/chat/completions",
    /** Folder that scopes the `gpt://<folder>/<model>` URIs; same folder as the SA/key. */
    val folderId: String = "",
    /** Injected from env (Lockbox); blank => tier disabled even if `enabled=true`. */
    val apiKey: String = "",
    /** Primary model slug for ru/en source (bake-off winner: Alice Flash). */
    val primaryModel: String = "aliceai-llm-flash",
    /** Booster model slug for Chinese source text (bake-off: Qwen3-235B). */
    val boosterModel: String = "qwen3-235b-a22b-fp8/latest",
    /** 0 for deterministic structured scoring (research 07). */
    val temperature: Double = 0.0,
    val maxTokens: Int = 1800,
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 30_000,
    val maxAttempts: Int = 2,
)
