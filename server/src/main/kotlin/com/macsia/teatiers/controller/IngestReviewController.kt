package com.macsia.teatiers.controller

import com.macsia.teatiers.dto.PendingMatchDto
import com.macsia.teatiers.dto.ReviewResultDto
import com.macsia.teatiers.service.ReviewService
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Operator-only review queue for scraped match decisions (decision #136). DISABLED by default
 * (`teatiers.ingest.review-api.enabled=true` to turn it on) because the operating model keeps the review
 * tool OFF the public request path -- it is meant to run on the operator's box, behind network controls,
 * not on the prod VM. There is no auto-merge: every canonical write happens through an explicit approval.
 */
@RestController
@RequestMapping("/api/v1/admin/ingest/review")
@ConditionalOnProperty(name = ["teatiers.ingest.review-api.enabled"], havingValue = "true")
class IngestReviewController(
    private val reviewService: ReviewService,
) {

    @GetMapping("/pending")
    fun pending(@RequestParam(defaultValue = "50") limit: Int): List<PendingMatchDto> =
        reviewService.pending(limit)

    @GetMapping("/count")
    fun count(): Long = reviewService.pendingCount()

    @PostMapping("/{decisionId}/approve-new")
    fun approveNew(
        @PathVariable decisionId: Long,
        @RequestParam @Size(max = MAX_REVIEWER_LEN) reviewer: String,
    ): ReviewResultDto = reviewService.approveNew(decisionId, reviewer)

    @PostMapping("/{decisionId}/approve-merge")
    fun approveMerge(
        @PathVariable decisionId: Long,
        @RequestParam @Size(max = MAX_REVIEWER_LEN) reviewer: String,
        @RequestParam(required = false) targetTeaId: Long?,
    ): ReviewResultDto = reviewService.approveMerge(decisionId, reviewer, targetTeaId)

    @PostMapping("/{decisionId}/reject")
    fun reject(
        @PathVariable decisionId: Long,
        @RequestParam @Size(max = MAX_REVIEWER_LEN) reviewer: String,
    ): ReviewResultDto = reviewService.reject(decisionId, reviewer)

    private companion object {
        const val MAX_REVIEWER_LEN = 100
    }
}
