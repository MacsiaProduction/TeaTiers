package com.macsia.teatiers.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FlavorScoreTest {

    @Test
    fun `intensity within range is accepted`() {
        assertEquals(5, FlavorScore(FlavorDimension.SWEETNESS, 5).intensity)
    }

    @Test
    fun `intensity above the scale is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlavorScore(FlavorDimension.SWEETNESS, 6)
        }
    }

    @Test
    fun `negative intensity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlavorScore(FlavorDimension.BITTERNESS, -1)
        }
    }
}
