package com.macsia.teatiers.service

import com.macsia.teatiers.config.MissLogProperties
import com.macsia.teatiers.repository.CatalogMissRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
    fun `record caps on a code-point boundary and never emits a lone surrogate`() {
        // 'İ' (U+0130) lowercases to 2 chars, so this 200-char (valid) input expands to 201 chars; a
        // naive take(200) would cut the trailing emoji's surrogate pair, leaving a lone surrogate
        // that isn't valid UTF-8 and would be rejected by the Postgres `text` column.
        val captured = slot<String>()
        service.record("İ" + "a".repeat(197) + "😀") // İ + 197×a + 😀 = 200 chars

        verify { repository.recordMiss(capture(captured)) }
        val stored = captured.captured
        // A lone surrogate would be replaced by '?' on a UTF-8 round-trip, so equality proves none.
        assertEquals(stored, String(stored.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `topMisses clamps the requested limit into range`() {
        val limit = slot<Limit>()
        every { repository.findAllByOrderByMissCountDesc(capture(limit)) } returns emptyList()

        service.topMisses(99_999)

        assertEquals(500, limit.captured.max())
    }

    @Test
    fun `purgeStale deletes rows past the retention window and below the keep threshold`() {
        // Fixed clock so the cutoff is deterministic: 2026-06-21 minus 90 days = 2026-03-23.
        val clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC)
        val service = MissLogService(
            repository,
            MissLogProperties(retentionDays = 90, minMissCountToKeep = 3),
            clock,
        )
        val cutoff = slot<LocalDate>()
        val minCount = slot<Long>()
        every { repository.deleteStale(capture(cutoff), capture(minCount)) } returns 4

        val removed = service.purgeStale()

        assertEquals(4, removed)
        assertEquals(LocalDate.of(2026, 3, 23), cutoff.captured)
        assertEquals(3L, minCount.captured) // popular rows (>= 3 misses) survive regardless of age
    }
}
