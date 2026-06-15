package com.macsia.teatiers.domain.model

/** A single flavor axis rated on the shared 0..5 intensity scale (decisions.md #23). */
data class FlavorScore(val dimension: FlavorDimension, val intensity: Int) {
    init {
        require(intensity in 0..5) { "intensity must be in 0..5, was $intensity" }
    }
}
