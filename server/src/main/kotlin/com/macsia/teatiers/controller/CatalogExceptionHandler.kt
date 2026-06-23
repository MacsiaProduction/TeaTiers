package com.macsia.teatiers.controller

import jakarta.validation.ConstraintViolationException
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

    @ExceptionHandler(TeaPublicNotFoundException::class)
    fun handlePublicNotFound(ex: TeaPublicNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Tea not found").apply {
            title = "Tea not found"
        }

    @ExceptionHandler(RateLimitException::class)
    fun handleRateLimited(ex: RateLimitException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.message ?: "Too many requests").apply {
            title = "Rate limit exceeded"
        }

    @ExceptionHandler(EdgeOverloadException::class)
    fun handleEdgeOverload(ex: EdgeOverloadException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message ?: "Service is shedding load").apply {
            title = "Service overloaded"
        }

    @ExceptionHandler(OcrUnavailableException::class)
    fun handleOcrUnavailable(ex: OcrUnavailableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message ?: "OCR unavailable").apply {
            title = "OCR unavailable"
        }

    @ExceptionHandler(OcrBusyException::class)
    fun handleOcrBusy(ex: OcrBusyException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.message ?: "OCR is busy").apply {
            title = "OCR busy"
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

    // @Validated param caps (e.g. @Size on TeaController's q/locale/origin, SRV-P2-2) throw this; map it
    // to 400 problem+json rather than the default 500.
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid request parameter").apply {
            title = "Invalid request"
        }
}
