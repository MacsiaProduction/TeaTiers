package com.macsia.teatiers.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Centralizes API errors as RFC-7807 problem+json (rule 30-backend). Spring MVC's built-in
 * exceptions (bad query-param conversion, 404 on unknown routes, etc.) are already rendered as
 * problem+json via `spring.mvc.problemdetails.enabled`; this advice only adds the domain cases.
 */
@RestControllerAdvice
class CatalogExceptionHandler {

    @ExceptionHandler(TeaNotFoundException::class)
    fun handleNotFound(ex: TeaNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Tea not found").apply {
            title = "Tea not found"
        }

    @ExceptionHandler(RateLimitException::class)
    fun handleRateLimited(ex: RateLimitException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.message ?: "Too many requests").apply {
            title = "Rate limit exceeded"
        }

    @ExceptionHandler(OcrUnavailableException::class)
    fun handleOcrUnavailable(ex: OcrUnavailableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message ?: "OCR unavailable").apply {
            title = "OCR unavailable"
        }

    @ExceptionHandler(OcrFailedException::class)
    fun handleOcrFailed(ex: OcrFailedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.message ?: "OCR failed").apply {
            title = "OCR failed"
        }

    @ExceptionHandler(OcrImageTooLargeException::class)
    fun handleOcrTooLarge(ex: OcrImageTooLargeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, ex.message ?: "Image too large").apply {
            title = "Image too large"
        }

    @ExceptionHandler(OcrBadRequestException::class)
    fun handleOcrBadRequest(ex: OcrBadRequestException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Bad request").apply {
            title = "Bad request"
        }
}
