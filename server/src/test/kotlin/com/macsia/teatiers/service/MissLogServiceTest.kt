package com.macsia.teatiers.service

import com.macsia.teatiers.repository.CatalogMissRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.data.domain.Limit

/** Pure-logic tests for the miss-log normalization + bounds (the upsert itself is covered by IT). */
class MissLogServiceTest {

    private val repository = mockk<CatalogMissRepository>(relaxed = true)
    private val service = MissLogService(repository)

    @Test
    fun `record collapses whitespace and lowercases before the upsert`() {
        service.record("  Da   Hong   PAO  ")

        verify { repository.recordMiss("da hong pao") }
    }

    @Test
    fun `record preserves Cyrillic letters (no diacritic stripping, unlike the dedup key)`() {
        service.record("Чёрный Чай")

        // ё/й survive so the operator can read the row; only case + whitespace are normalized.
        verify { repository.recordMiss("чёрный чай") }
    }

    @Test
    fun `record ignores a blank or whitespace-only name`() {
        service.record("    ")

        verify(exactly = 0) { repository.recordMiss(any()) }
    }

    @Test
    fun `record caps a pathologically long query`() {
        service.record("a".repeat(500))

        verify { repository.recordMiss("a".repeat(200)) }
    }

    @Test
    fun `topMisses clamps the requested limit into range`() {
        val limit = slot<Limit>()
        every { repository.findAllByOrderByMissCountDesc(capture(limit)) } returns emptyList()

        service.topMisses(99_999)

        assertEquals(500, limit.captured.max())
    }
}
