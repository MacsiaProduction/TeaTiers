package com.macsia.teatiers.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.client.FlavorProfile
import com.macsia.teatiers.client.FlavorPrompts
import com.macsia.teatiers.client.FoundationModelsClient
import com.macsia.teatiers.client.LlmNames
import com.macsia.teatiers.client.LlmProperties
import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.TeaType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/** Outcome of one enrichment attempt; [Failure.reason] is stored verbatim in `enrichment_error`. */
sealed interface EnrichmentResult {
    data class Success(
        val type: TeaType,
        val flavors: Map<FlavorDimension, Int>,
        val blurbRu: String?,
        val names: LlmNames?,
        val confidence: Float,
        val model: String,
    ) : EnrichmentResult

    data class Failure(val reason: String) : EnrichmentResult
}

/**
 * Background worker for the paid enrichment tier (plan.md section 6 step 3; decision #65). On a
 * Wikidata miss [enrich] runs off the request thread: it prompts a Foundation Model, then validates
 * the reply in Kotlin — the model contract is never trusted (research 07). All writes go through
 * [EnrichmentStubService] so no transaction is held across the slow network call.
 */
@Service
class LlmEnrichmentService(
    private val client: FoundationModelsClient,
    private val props: LlmProperties,
    private val stubService: EnrichmentStubService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Own mapper: parsing the model's JSON has no shared config with the web layer, and a standalone
    // mapper keeps the service free of the auto-configured ObjectMapper bean.
    private val objectMapper = jacksonObjectMapper()

    @Async("enrichmentExecutor")
    fun enrich(teaId: Long, name: String, sourceText: String?) {
        try {
            when (val result = profile(name, sourceText)) {
                is EnrichmentResult.Success -> stubService.applyResult(teaId, result)
                is EnrichmentResult.Failure -> {
                    log.info("Enrichment failed for tea {}: {}", teaId, result.reason)
                    stubService.markFailed(teaId, result.reason)
                }
            }
        } catch (ex: Exception) {
            // Never let an async task die silently with the row stuck on PENDING.
            log.warn("Enrichment crashed for tea {}: {}", teaId, ex.message)
            runCatching { stubService.markFailed(teaId, "internal error") }
        }
    }

    /** Visible for testing: build the prompt, call the model, and validate — no DB access. */
    fun profile(name: String, sourceText: String?): EnrichmentResult {
        val grounded = !sourceText.isNullOrBlank()
        val vendor = sourceText?.let(EnrichmentText::sanitizeVendorText).orEmpty()
        // Route Chinese-source requests to the booster (Qwen3); everything else to the primary (Alice Flash).
        val model = if (EnrichmentText.containsHan(name) || EnrichmentText.containsHan(vendor)) {
            props.boosterModel
        } else {
            props.primaryModel
        }
        val system = if (grounded) FlavorPrompts.systemGrounded else FlavorPrompts.systemZeroShot
        val user = if (grounded) FlavorPrompts.groundedUser(name, vendor) else FlavorPrompts.zeroShotUser(name)

        val raw = client.chatJson(model, system, FlavorPrompts.fewShot, user)
            ?: return EnrichmentResult.Failure("llm unavailable")
        val parsed = parse(raw) ?: return EnrichmentResult.Failure("invalid json")
        return validate(parsed, grounded, vendor, model)
    }

    private fun parse(raw: String): FlavorProfile? =
        runCatching { objectMapper.readValue(raw, FlavorProfile::class.java) }.getOrNull()

    private fun validate(p: FlavorProfile, grounded: Boolean, vendor: String, model: String): EnrichmentResult {
        val dims = p.dimensions ?: return EnrichmentResult.Failure("no dimensions")
        val flavors = FlavorDimension.entries.associateWith { dim -> dims[dim.name]?.value?.coerceIn(0, 5) }
        if (flavors.values.any { it == null }) return EnrichmentResult.Failure("incomplete dimensions")
        val values = flavors.mapValues { requireNotNull(it.value) }

        val type = p.type?.let { runCatching { TeaType.valueOf(it.trim().uppercase()) }.getOrNull() } ?: TeaType.OTHER

        var confidence = (p.overallConfidence ?: 0.0).coerceIn(0.0, 1.0)
        // Zero-shot has no external evidence: cap then discount (research 07 multiplicative gate).
        if (!grounded) confidence = minOf(confidence, ZERO_SHOT_CAP) * ZERO_SHOT_FACTOR
        // Central-tendency guard: a profile parked entirely in {2,3} is almost always low-signal.
        if (values.values.all { it == 2 || it == 3 }) confidence *= CENTRAL_TENDENCY_FACTOR

        val blurb = p.shortBlurbRu?.let(EnrichmentText::cleanBlurb)?.takeIf { it.isNotBlank() }?.let { cleaned ->
            // Drop a blurb that copies the vendor prose / echoes injected text (copyright + injection guard).
            if (grounded && EnrichmentText.shingleOverlap(cleaned, vendor) >= COPY_OVERLAP_THRESHOLD) null else cleaned
        }

        return EnrichmentResult.Success(type, values, blurb, p.names, confidence.toFloat(), model)
    }

    private companion object {
        const val ZERO_SHOT_CAP = 0.6
        const val ZERO_SHOT_FACTOR = 0.7
        const val CENTRAL_TENDENCY_FACTOR = 0.5
        const val COPY_OVERLAP_THRESHOLD = 0.5
    }
}
