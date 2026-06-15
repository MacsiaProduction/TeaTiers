package com.macsia.teatiers.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Liveness ping under the versioned API base. Detailed health lives at /actuator/health. */
@RestController
@RequestMapping("/api/v1")
class HealthController {

    @GetMapping("/ping")
    fun ping(): PingResponse = PingResponse(status = "ok", service = "teatiers-catalog")
}

data class PingResponse(val status: String, val service: String)
