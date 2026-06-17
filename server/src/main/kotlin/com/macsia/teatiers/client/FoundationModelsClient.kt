package com.macsia.teatiers.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Calls Yandex Foundation Models through the OpenAI-compatible chat-completions endpoint with strict
 * `response_format:{type:"json_schema"}` (decision #65 — one code path for Alice Flash + Qwen3). The
 * API key is taken from [LlmProperties] (injected from Lockbox via env); callers must check
 * [isEnabled] first so a missing key degrades the enrichment tier gracefully instead of 401-ing.
 */
@Component
class FoundationModelsClient(
    private val props: LlmProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val isEnabled: Boolean get() = props.enabled && props.apiKey.isNotBlank() && props.folderId.isNotBlank()

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(props.endpoint)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Api-Key ${props.apiKey}")
        // Opt out of Yandex request/response logging (AI Studio ToS cl. 4.1/3.15): the basis for
        // storing + re-serving model output. Standard on the native API; harmless if the OpenAI-compat
        // endpoint ignores it. Confirm logging is actually off (header or folder-level) before deploy.
        .defaultHeader("x-data-logging-enabled", "false")
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs.toLong()))
                setReadTimeout(Duration.ofMillis(props.readTimeoutMs.toLong()))
            },
        )
        .build()

    /** Returns the model's raw JSON content (the structured profile), or null on outage/failure. */
    fun chatJson(modelSlug: String, system: String, fewShot: List<Pair<String, String>>, user: String): String? {
        val messages = buildList {
            add(mapOf("role" to "system", "content" to system))
            fewShot.forEach { (u, a) ->
                add(mapOf("role" to "user", "content" to u))
                add(mapOf("role" to "assistant", "content" to a))
            }
            add(mapOf("role" to "user", "content" to user))
        }
        val body = mapOf(
            "model" to "gpt://${props.folderId}/$modelSlug",
            "messages" to messages,
            "temperature" to props.temperature,
            "max_tokens" to props.maxTokens,
            "response_format" to mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf("name" to "tea_profile", "strict" to true, "schema" to FlavorPrompts.schema()),
            ),
        )
        var lastError: Exception? = null
        repeat(props.maxAttempts) { attempt ->
            try {
                val response = restClient.post().uri("").body(body).retrieve().body(ChatResponse::class.java)
                return response?.choices?.firstOrNull()?.message?.content
            } catch (ex: RestClientException) {
                lastError = ex
                if (attempt < props.maxAttempts - 1) Thread.sleep(RETRY_BACKOFF_MS)
            }
        }
        log.warn("Foundation Models call failed for {} after {} attempts: {}", modelSlug, props.maxAttempts, lastError?.message)
        return null
    }

    private companion object {
        const val RETRY_BACKOFF_MS = 700L
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatResponse(val choices: List<ChatChoice>?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatChoice(val message: ChatMessageContent?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatMessageContent(val content: String?)
