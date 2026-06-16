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
}
