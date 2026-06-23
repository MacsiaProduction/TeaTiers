package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.TeaSampleEntity
import com.macsia.teatiers.domain.model.CatalogDescription
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogProvenance
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.EnrichmentState
import com.macsia.teatiers.domain.model.TeaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeaEnrichmentManagerTest {

    private val catalog = mockk<CatalogRepository>()

    private suspend fun TestScope.managerWith(vararg teas: TeaSampleEntity): Pair<TeaEnrichmentManager, FakeTeaDao> {
        val dao = FakeTeaDao()
        if (teas.isNotEmpty()) dao.insertTeas(teas.toList())
        // Zero poll interval: delay(0) is a no-op, so the enrich/poll loop runs inline under the
        // UnconfinedTestDispatcher and needs no virtual-time advancement to settle.
        val manager = TeaEnrichmentManager(dao, catalog, backgroundScope).apply { pollIntervalMs = 0 }
        return manager to dao
    }

    private fun teaRow(
        id: String,
        name: String = "Чай",
        state: EnrichmentState = EnrichmentState.NONE,
        catalogTeaId: Long? = null,
    ) =
        TeaSampleEntity(
            id = id, nameRu = name, nameZh = null, pinyin = null, nameEn = null,
            type = "OTHER", origin = null, shortBlurb = null, notes = null,
            catalogTeaId = catalogTeaId, enrichmentState = state.name,
        )

    private fun detail(id: Long, state: EnrichmentState? = null) = CatalogTeaDetail(
        id = id, type = TeaType.GREEN, originCountry = "CN", region = null, cultivar = null,
        oxidationMin = null, oxidationMax = null, brand = null, image = null,
        names = listOf(
            CatalogName("ru", "Лунцзин", isPrimary = true),
            CatalogName("pinyin", "lóngjǐng", isPrimary = true),
        ),
        descriptions = listOf(CatalogDescription("ru", "Зелёный чай из Сиху.", null, "ai", null)),
        flavors = emptyList(),
        provenance = CatalogProvenance("ai", null, null, "unverified", 0.7f),
        enrichmentState = state,
    )

    @Test
    fun `Matched patches the row from the catalog and marks DONE`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Matched(detail(id = 7))
        val (manager, dao) = managerWith(teaRow("t1", name = "лунцзин"))

        manager.enrich("t1", "лунцзин")
        advanceUntilIdle()

        val row = dao.loadTeaRow("t1")!!
        assertEquals("Лунцзин", row.nameRu)
        assertEquals("lóngjǐng", row.pinyin)
        assertEquals("CN", row.origin)
        assertEquals("Зелёный чай из Сиху.", row.shortBlurb)
        assertEquals(7L, row.catalogTeaId)
        assertEquals(EnrichmentState.DONE.name, row.enrichmentState)
    }

    @Test
    fun `a Matched but still-FAILED server stub keeps the row FAILED and does not patch`() =
        runTest(UnconfinedTestDispatcher()) {
            // The server found the row (MATCHED) but it's a FAILED LLM stub it didn't re-arm (LLM off /
            // budget spent). Forcing DONE would hide the failure + drop retry (second-pass review P0).
            coEvery { catalog.resolve(any(), any(), any()) } returns
                ResolveResult.Matched(detail(id = 9, state = EnrichmentState.FAILED))
            val (manager, dao) = managerWith(teaRow("t1", name = "Мой чай"))

            manager.enrich("t1", "Мой чай")
            advanceUntilIdle()

            val row = dao.loadTeaRow("t1")!!
            assertEquals("Мой чай", row.nameRu) // not patched to the catalog name
            assertEquals(null, row.catalogTeaId) // no catalog link written
            assertEquals(EnrichmentState.FAILED.name, row.enrichmentState)
        }

    @Test
    fun `Enriching polls the detail until DONE then patches`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Enriching(catalogTeaId = 9)
        // First poll still PENDING, second poll DONE — exercises the polling loop.
        coEvery { catalog.detail(9) } returnsMany listOf(
            CatalogDetailResult.Loaded(detail(9, EnrichmentState.PENDING)),
            CatalogDetailResult.Loaded(detail(9, EnrichmentState.DONE)),
        )
        val (manager, dao) = managerWith(teaRow("t1"))

        manager.enrich("t1", "Чай")
        advanceUntilIdle()

        val row = dao.loadTeaRow("t1")!!
        assertEquals(9L, row.catalogTeaId)
        assertEquals("Лунцзин", row.nameRu)
        assertEquals(EnrichmentState.DONE.name, row.enrichmentState)
    }

    @Test
    fun `Enriching that the server fails marks the row FAILED`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Enriching(catalogTeaId = 9)
        coEvery { catalog.detail(9) } returns CatalogDetailResult.Loaded(detail(9, EnrichmentState.FAILED))
        val (manager, dao) = managerWith(teaRow("t1"))

        manager.enrich("t1", "Чай")
        advanceUntilIdle()

        assertEquals(EnrichmentState.FAILED.name, dao.loadTeaRow("t1")!!.enrichmentState)
    }

    @Test
    fun `Offline queues the tea for a later retry without patching`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Offline
        val (manager, dao) = managerWith(teaRow("t1", name = "Мой чай"))

        manager.enrich("t1", "Мой чай")
        advanceUntilIdle()

        val row = dao.loadTeaRow("t1")!!
        assertEquals("Мой чай", row.nameRu)
        assertEquals(EnrichmentState.QUEUED.name, row.enrichmentState)
    }

    @Test
    fun `Unresolved clears the spinner to DONE and keeps the typed tea`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Unresolved
        val (manager, dao) = managerWith(teaRow("t1", name = "Свой чай"))

        manager.enrich("t1", "Свой чай")
        advanceUntilIdle()

        val row = dao.loadTeaRow("t1")!!
        assertEquals("Свой чай", row.nameRu)
        assertEquals(EnrichmentState.DONE.name, row.enrichmentState)
    }

    @Test
    fun `retry re-resolves a FAILED tea using its current name`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Matched(detail(id = 3))
        val (manager, dao) = managerWith(teaRow("t1", name = "лунцзин", state = EnrichmentState.FAILED))

        manager.retry("t1")
        advanceUntilIdle()

        val row = dao.loadTeaRow("t1")!!
        assertEquals(3L, row.catalogTeaId)
        assertEquals(EnrichmentState.DONE.name, row.enrichmentState)
    }

    @Test
    fun `two samples resolving to the same catalog ref both link to it (v7, no UNIQUE)`() =
        runTest(UnconfinedTestDispatcher()) {
            // t1 ("Tieguanyin") already links catalog ref 5. t2 ("тегуанинь") is a differently-scripted
            // duplicate that resolves server-side to the SAME ref 5. v7 (#132) dropped the catalogTeaId
            // UNIQUE, so BOTH samples now link to ref 5 (findings #16/#23) — the old code left t2 unlinked
            // to dodge the UNIQUE throw, stranding a sample that should share the catalog identity.
            coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Matched(detail(id = 5))
            val (manager, dao) = managerWith(
                teaRow("t1", name = "Tieguanyin", state = EnrichmentState.DONE, catalogTeaId = 5),
                teaRow("t2", name = "тегуанинь"),
            )

            manager.enrich("t2", "тегуанинь")
            advanceUntilIdle()

            val t2 = dao.loadTeaRow("t2")!!
            assertEquals(EnrichmentState.DONE.name, t2.enrichmentState)
            assertEquals(5L, t2.catalogTeaId) // v7: the duplicate links to the SHARED ref, no longer stranded
            assertEquals(5L, dao.loadTeaRow("t1")!!.catalogTeaId) // original link untouched
            // …and it gets the catalog's enriched names/blurb (review F6), same catalog-wins merge.
            assertEquals("Лунцзин", t2.nameRu)
            assertEquals("lóngjǐng", t2.pinyin)
            assertEquals("Зелёный чай из Сиху.", t2.shortBlurb)

            // A retry stays settled too.
            manager.retry("t2")
            advanceUntilIdle()
            assertEquals(EnrichmentState.DONE.name, dao.loadTeaRow("t2")!!.enrichmentState)
        }

    @Test
    fun `a second dispatch while one is already in flight is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        coEvery { catalog.resolve(any(), any(), any()) } coAnswers {
            gate.await() // hold the first enrichment in flight
            ResolveResult.Matched(detail(id = 7))
        }
        val (manager, dao) = managerWith(teaRow("t1"))

        manager.enrich("t1", "Чай") // first: marks t1 in-flight, suspends in resolve
        advanceUntilIdle()
        manager.enrich("t1", "Чай") // second: same tea still in flight -> skipped before resolve
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { catalog.resolve(any(), any(), any()) }
        assertEquals(EnrichmentState.DONE.name, dao.loadTeaRow("t1")!!.enrichmentState)
    }

    @Test
    fun `resumePending re-dispatches PENDING and QUEUED teas only`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Matched(detail(id = 5))
        val (manager, dao) = managerWith(
            teaRow("pending", state = EnrichmentState.PENDING),
            teaRow("queued", state = EnrichmentState.QUEUED),
            teaRow("done", state = EnrichmentState.DONE),
        )

        manager.resumePending()
        advanceUntilIdle()

        assertEquals(EnrichmentState.DONE.name, dao.loadTeaRow("pending")!!.enrichmentState)
        assertEquals(EnrichmentState.DONE.name, dao.loadTeaRow("queued")!!.enrichmentState)
        // The already-DONE tea was not re-resolved (no catalog link was written to it).
        assertEquals(null, dao.loadTeaRow("done")!!.catalogTeaId)
    }

    @Test
    fun `resumePending runs at most once per process so board-open cannot re-burn a resolve token`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { catalog.resolve(any(), any(), any()) } returns ResolveResult.Matched(detail(id = 5))
            val (manager, dao) = managerWith(teaRow("p1", state = EnrichmentState.PENDING))

            manager.resumePending() // first call (app-launch): sweeps the PENDING tea
            advanceUntilIdle()
            assertEquals(EnrichmentState.DONE.name, dao.loadTeaRow("p1")!!.enrichmentState)

            // Re-arm a PENDING tea and call resumePending AGAIN (another board-open): the once-per-process
            // guard (AND-P1-7) must NOT re-dispatch — no second /resolve token is spent.
            dao.updateEnrichmentState("p1", EnrichmentState.PENDING.name)
            manager.resumePending()
            advanceUntilIdle()

            assertEquals(EnrichmentState.PENDING.name, dao.loadTeaRow("p1")!!.enrichmentState) // untouched
            coVerify(exactly = 1) { catalog.resolve(any(), any(), any()) }
        }
}
