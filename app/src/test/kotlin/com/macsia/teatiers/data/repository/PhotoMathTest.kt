package com.macsia.teatiers.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-math coverage for [computePhotoPositions]. The behaviour mirrors the tier reorder
 * helper (decisions.md #39): drops unknowns, appends omissions in their current order, and
 * returns empty when nothing actually changed so the caller can skip the write.
 */
class PhotoMathTest {

    @Test
    fun `reorder produces contiguous positions for the requested order`() {
        val positions = computePhotoPositions(
            currentOrder = listOf("a", "b", "c"),
            requestedOrder = listOf("c", "a", "b"),
        )
        assertEquals(listOf("c" to 0, "a" to 1, "b" to 2), positions.map { it.photoId to it.position })
    }

    @Test
    fun `unknown ids in the request are dropped`() {
        val positions = computePhotoPositions(
            currentOrder = listOf("a", "b"),
            requestedOrder = listOf("z", "b", "a"),
        )
        assertEquals(listOf("b" to 0, "a" to 1), positions.map { it.photoId to it.position })
    }

    @Test
    fun `omitted photos keep their relative order at the tail`() {
        val positions = computePhotoPositions(
            currentOrder = listOf("a", "b", "c", "d"),
            requestedOrder = listOf("d"),
        )
        assertEquals(
            listOf("d" to 0, "a" to 1, "b" to 2, "c" to 3),
            positions.map { it.photoId to it.position },
        )
    }

    @Test
    fun `idle drop returns empty so the caller skips the write`() {
        val positions = computePhotoPositions(
            currentOrder = listOf("a", "b", "c"),
            requestedOrder = listOf("a", "b", "c"),
        )
        assertEquals(emptyList<Pair<String, Int>>(), positions.map { it.photoId to it.position })
    }
}
