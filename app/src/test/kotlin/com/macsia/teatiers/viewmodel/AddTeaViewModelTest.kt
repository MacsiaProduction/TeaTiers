package com.macsia.teatiers.viewmodel

import android.net.Uri
import app.cash.turbine.test
import com.macsia.teatiers.data.repository.CatalogDetailResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.CatalogSearchResult
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogProvenance
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddTeaViewModelTest {

    private val board = Board(
        id = "b",
        name = "B",
        // out of order on purpose: the picker must show tiers sorted by position
        tiers = listOf(Tier("a", "A", 1), Tier("s", "S", 0)),
        placements = mapOf("s" to emptyList(), "a" to emptyList()),
        unranked = emptyList(),
    )

    private val repository = mockk<TeaBoardRepository>()
    private val catalogRepository = mockk<CatalogRepository>()

    // Shared scheduler between Main and the catalog tests so `debounce` virtual time advances.
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        every { repository.boards } returns MutableStateFlow(listOf(board))
        // Default for any code path that calls placementCountForTea(teaId) (e.g. bind on edit).
        coEvery { repository.placementCountForTea(any()) } returns 1
        // Default: catalog search returns nothing unless a test overrides it. Only fires when a
        // subscriber collects catalogSearch and the query is long enough.
        coEvery { catalogRepository.search(any(), any()) } returns
            CatalogSearchResult.Loaded(emptyList(), fromCache = false)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tiers expose the bound board's tiers sorted by position`() = runTest {
        val viewModel = AddTeaViewModel(repository, catalogRepository)

        viewModel.tiers.test {
            viewModel.bind(boardId = "b")
            // the StateFlow starts empty; wait for the first emission carrying the bound tiers
            var tiers = awaitItem()
            while (tiers.isEmpty()) tiers = awaitItem()
            assertEquals(listOf("s", "a"), tiers.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit persists the mapped tea and notifies on success`() = runTest {
        coEvery { repository.addTea(any(), any(), any()) } returns "tea-1"
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")
        viewModel.update { it.copy(nameRu = "Да Хун Пао", tierId = "a") }

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        coVerify(exactly = 1) {
            repository.addTea(eq("b"), match { it.nameRu == "Да Хун Пао" }, eq("a"))
        }
    }

    @Test
    fun `submit is a no-op when the ru name is blank`() = runTest {
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        coVerify(exactly = 0) { repository.addTea(any(), any(), any()) }
    }

    @Test
    fun `submit is a no-op when no board is bound (add flow)`() = runTest {
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        // Add flow with no boardId means we cannot place anywhere — submit must bail.
        viewModel.update { it.copy(nameRu = "Чай") }

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        coVerify(exactly = 0) { repository.addTea(any(), any(), any()) }
    }

    @Test
    fun `purchase helpers add update and remove drafts`() = runTest {
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")

        viewModel.addPurchase()
        viewModel.addPurchase()
        viewModel.updatePurchase(0) { it.copy(kind = PurchaseKind.MARKETPLACE, url = "https://shop") }
        viewModel.updatePurchase(1) { it.copy(text = "Рынок") }
        viewModel.removePurchase(0)

        val purchases = viewModel.form.value.purchases
        assertEquals(1, purchases.size)
        assertEquals(PurchaseKind.TEXT, purchases[0].kind)
        assertEquals("Рынок", purchases[0].text)
    }

    @Test
    fun `purchase helpers ignore out-of-range indices`() = runTest {
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")

        viewModel.removePurchase(5)
        viewModel.updatePurchase(7) { it.copy(text = "noop") }

        assertTrue(viewModel.form.value.purchases.isEmpty())
    }

    @Test
    fun `bind in edit mode prefills the form from the user-tea`() = runTest {
        val tea = Tea(
            id = "t1",
            nameRu = "Да Хун Пао",
            type = TeaType.OOLONG,
            origin = "Уишань",
            flavor = listOf(FlavorScore(FlavorDimension.ROASTED, 5)),
            notes = "после обеда",
            purchaseLocations = listOf(PurchaseLocation.FreeText("Рынок", "поездка")),
        )
        coEvery { repository.tea(eq("t1")) } returns tea
        coEvery { repository.placementCountForTea(eq("t1")) } returns 1
        val viewModel = AddTeaViewModel(repository, catalogRepository)

        viewModel.bind(teaId = "t1")
        advanceUntilIdle()

        val form = viewModel.form.value
        assertEquals("Да Хун Пао", form.nameRu)
        assertEquals(TeaType.OOLONG, form.type)
        assertEquals(mapOf(FlavorDimension.ROASTED to 5), form.flavors)
        assertEquals(1, form.purchases.size)
        assertEquals("Рынок", form.purchases[0].text)
        assertEquals("t1", viewModel.editingTeaId.value)
        assertEquals(1, viewModel.placementCount.value)
    }

    @Test
    fun `placementCount is exposed for the ripple caption`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN)
        coEvery { repository.tea(eq("t1")) } returns tea
        coEvery { repository.placementCountForTea(eq("t1")) } returns 3
        val viewModel = AddTeaViewModel(repository, catalogRepository)

        viewModel.bind(teaId = "t1")
        advanceUntilIdle()

        assertEquals(3, viewModel.placementCount.value)
    }

    @Test
    fun `submit in edit mode calls updateTea (no boardId), not addTea`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN)
        coEvery { repository.tea(eq("t1")) } returns tea
        coEvery { repository.placementCountForTea(eq("t1")) } returns 2
        coEvery { repository.updateTea(any(), any()) } just Runs
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(teaId = "t1")
        advanceUntilIdle()
        viewModel.update { it.copy(nameRu = "Новый чай", notes = "обновлено") }

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        coVerify(exactly = 1) {
            repository.updateTea(
                eq("t1"),
                match { it.nameRu == "Новый чай" && it.notes == "обновлено" },
            )
        }
        coVerify(exactly = 0) { repository.addTea(any(), any(), any()) }
    }

    @Test
    fun `bind clears the form when re-binding to a fresh add flow`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN, notes = "заметка")
        coEvery { repository.tea(eq("t1")) } returns tea
        coEvery { repository.placementCountForTea(eq("t1")) } returns 1
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(teaId = "t1")
        advanceUntilIdle()
        // simulate the user navigating away from edit and into the add flow on the same VM
        viewModel.bind(boardId = "b")

        val form = viewModel.form.value
        assertEquals("", form.nameRu)
        assertEquals("", form.notes)
        assertTrue(form.purchases.isEmpty())
        assertEquals(null, viewModel.editingTeaId.value)
        assertEquals(0, viewModel.placementCount.value)
    }

    @Test
    fun `deleteTea forwards to the repository and notifies on success`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN)
        coEvery { repository.tea(eq("t1")) } returns tea
        coEvery { repository.placementCountForTea(eq("t1")) } returns 1
        coEvery { repository.deleteTea(eq("t1")) } just Runs
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(teaId = "t1")
        advanceUntilIdle()

        var deleted = false
        viewModel.deleteTea { deleted = true }
        advanceUntilIdle()

        assertTrue(deleted)
        coVerify(exactly = 1) { repository.deleteTea(eq("t1")) }
    }

    @Test
    fun `deleteTea is a no-op when not in edit mode`() = runTest {
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")

        var deleted = false
        viewModel.deleteTea { deleted = true }
        advanceUntilIdle()

        assertFalse(deleted)
        coVerify(exactly = 0) { repository.deleteTea(any()) }
    }

    @Test
    fun `add-mode draft photos are materialized after the tea is saved`() = runTest {
        coEvery { repository.addTea(eq("b"), any(), any()) } returns "tea-new"
        coEvery { repository.addPhoto(any(), any()) } returns "photo-id"

        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")
        viewModel.update { it.copy(nameRu = "Да Хун Пао") }

        val uriOne = mockk<Uri>().also { every { it.toString() } returns "content://one" }
        val uriTwo = mockk<Uri>().also { every { it.toString() } returns "content://two" }
        viewModel.onAddPhoto(uriOne)
        viewModel.onAddPhoto(uriTwo)

        // Drafts visible on the strip while we are still composing the form. The `photos`
        // StateFlow is `WhileSubscribed`, so without a subscriber its value stays empty —
        // we assert the underlying draft buffer instead, which is the source of truth for
        // both the strip projection and the materialization-on-save loop.
        assertEquals(2, viewModel.draftPhotos.value.size)

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        coVerify(exactly = 1) { repository.addPhoto("tea-new", uriOne) }
        coVerify(exactly = 1) { repository.addPhoto("tea-new", uriTwo) }
        // Drafts cleared so a follow-up bind starts fresh.
        assertTrue(viewModel.draftPhotos.value.isEmpty())
    }

    @Test
    fun `edit-mode addPhoto delegates to the repository immediately`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN)
        coEvery { repository.tea(eq("t1")) } returns tea
        coEvery { repository.addPhoto(eq("t1"), any()) } returns "photo-id"

        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(teaId = "t1")
        advanceUntilIdle()

        val uri = mockk<Uri>().also { every { it.toString() } returns "content://x" }
        viewModel.onAddPhoto(uri)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.addPhoto("t1", uri) }
        // Edit mode never stages drafts — the repo flow is the source of truth.
        assertTrue(viewModel.draftPhotos.value.isEmpty())
    }

    @Test
    fun `edit-mode removePhoto and reorderPhotos route through the repository`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN)
        coEvery { repository.tea(eq("t1")) } returns tea
        coEvery { repository.removePhoto(eq("t1"), any()) } just Runs
        coEvery { repository.reorderPhotos(eq("t1"), any()) } just Runs

        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(teaId = "t1")
        advanceUntilIdle()

        val orderedSlot = slot<List<String>>()
        viewModel.onRemovePhoto("photo-1")
        viewModel.onReorderPhotos(listOf("a", "b", "c"))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.removePhoto("t1", "photo-1") }
        coVerify(exactly = 1) { repository.reorderPhotos("t1", capture(orderedSlot)) }
        assertEquals(listOf("a", "b", "c"), orderedSlot.captured)
    }

    private fun catalogTea(
        id: Long,
        ru: String? = null,
        en: String? = null,
        pinyin: String? = null,
        type: TeaType = TeaType.GREEN,
        origin: String? = null,
        verification: String = "verified",
    ): CatalogTea = CatalogTea(
        id = id,
        type = type,
        originCountry = origin,
        brand = null,
        verificationStatus = verification,
        names = buildList {
            ru?.let { add(CatalogName("ru", it, isPrimary = true)) }
            en?.let { add(CatalogName("en", it, isPrimary = true)) }
            pinyin?.let { add(CatalogName("pinyin", it, isPrimary = false)) }
        },
    )

    @Test
    fun `catalog search debounces a short-then-valid query into loading then results`() =
        runTest(mainDispatcher) {
            val tea = catalogTea(1, ru = "Лунцзин", pinyin = "lóngjǐng", origin = "Китай")
            coEvery { catalogRepository.search(eq("лунц"), any()) } returns
                CatalogSearchResult.Loaded(listOf(tea), fromCache = false)
            val viewModel = AddTeaViewModel(repository, catalogRepository)

            viewModel.catalogSearch.test {
                assertEquals(CatalogSearchUiState.Idle, awaitItem())
                viewModel.onCatalogQuery("лунц")
                advanceTimeBy(CATALOG_SEARCH_DEBOUNCE_MS + 1)
                // `Loading` may be conflated away by the StateFlow when the (mocked) search
                // resolves synchronously; skip it and assert the settled state.
                var settled = awaitItem()
                if (settled == CatalogSearchUiState.Loading) settled = awaitItem()
                assertTrue(settled is CatalogSearchUiState.Results)
                settled as CatalogSearchUiState.Results
                assertFalse(settled.fromCache)
                assertEquals(listOf("Лунцзин"), settled.teas.map { it.displayName })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `catalog search ignores queries below the minimum length`() =
        runTest(mainDispatcher) {
            val viewModel = AddTeaViewModel(repository, catalogRepository)

            viewModel.catalogSearch.test {
                assertEquals(CatalogSearchUiState.Idle, awaitItem())
                viewModel.onCatalogQuery("л")
                advanceTimeBy(CATALOG_SEARCH_DEBOUNCE_MS + 1)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 0) { catalogRepository.search(any(), any()) }
        }

    @Test
    fun `catalog search surfaces empty results when nothing matches`() =
        runTest(mainDispatcher) {
            coEvery { catalogRepository.search(eq("zzzz"), any()) } returns
                CatalogSearchResult.Loaded(emptyList(), fromCache = false)
            val viewModel = AddTeaViewModel(repository, catalogRepository)

            viewModel.catalogSearch.test {
                assertEquals(CatalogSearchUiState.Idle, awaitItem())
                viewModel.onCatalogQuery("zzzz")
                advanceTimeBy(CATALOG_SEARCH_DEBOUNCE_MS + 1)
                var settled = awaitItem()
                if (settled == CatalogSearchUiState.Loading) settled = awaitItem()
                assertEquals(CatalogSearchUiState.Empty, settled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `catalog search reports offline when the repository falls back with nothing`() =
        runTest(mainDispatcher) {
            coEvery { catalogRepository.search(eq("чай"), any()) } returns CatalogSearchResult.Offline
            val viewModel = AddTeaViewModel(repository, catalogRepository)

            viewModel.catalogSearch.test {
                assertEquals(CatalogSearchUiState.Idle, awaitItem())
                viewModel.onCatalogQuery("чай")
                advanceTimeBy(CATALOG_SEARCH_DEBOUNCE_MS + 1)
                var settled = awaitItem()
                if (settled == CatalogSearchUiState.Loading) settled = awaitItem()
                assertEquals(CatalogSearchUiState.Offline, settled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pickCatalogTea prefills names type and origin and clears the search box`() = runTest {
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")
        viewModel.onCatalogQuery("лунц")

        viewModel.pickCatalogTea(
            catalogTea(
                1,
                ru = "Лунцзин",
                en = "Dragon Well",
                pinyin = "lóngjǐng",
                type = TeaType.GREEN,
                origin = "Китай",
            ),
        )

        val form = viewModel.form.value
        assertEquals("Лунцзин", form.nameRu)
        assertEquals("Dragon Well", form.nameEn)
        assertEquals("lóngjǐng", form.pinyin)
        assertEquals(TeaType.GREEN, form.type)
        assertEquals("Китай", form.origin)
        assertEquals("", viewModel.catalogQuery.value)
    }

    @Test
    fun `openCatalogDetail loads and surfaces the detail`() = runTest(mainDispatcher) {
        val detail = catalogTeaDetail(1, ru = "Лунцзин")
        coEvery { catalogRepository.detail(1) } returns CatalogDetailResult.Loaded(detail)
        val viewModel = AddTeaViewModel(repository, catalogRepository)

        viewModel.catalogDetail.test {
            assertEquals(CatalogDetailUiState.Hidden, awaitItem())
            viewModel.openCatalogDetail(1)
            // `Loading` may be conflated away when the (mocked) fetch resolves synchronously.
            var settled = awaitItem()
            if (settled == CatalogDetailUiState.Loading) settled = awaitItem()
            assertTrue(settled is CatalogDetailUiState.Loaded)
            assertEquals("Лунцзин", (settled as CatalogDetailUiState.Loaded).detail.nameRu)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `useCatalogDetail prefills the form and clears the search`() = runTest(mainDispatcher) {
        val viewModel = AddTeaViewModel(repository, catalogRepository)
        viewModel.bind(boardId = "b")
        viewModel.onCatalogQuery("лунц")

        viewModel.useCatalogDetail(
            catalogTeaDetail(1, ru = "Лунцзин", en = "Dragon Well", pinyin = "lóngjǐng", origin = "CN"),
        )

        val form = viewModel.form.value
        assertEquals("Лунцзин", form.nameRu)
        assertEquals("Dragon Well", form.nameEn)
        assertEquals(TeaType.GREEN, form.type)
        assertEquals("CN", form.origin)
        assertEquals("", viewModel.catalogQuery.value)
    }

    @Test
    fun `closeCatalogDetail hides the sheet after it was opened`() = runTest(mainDispatcher) {
        val detail = catalogTeaDetail(1, ru = "Лунцзин")
        coEvery { catalogRepository.detail(1) } returns CatalogDetailResult.Loaded(detail)
        val viewModel = AddTeaViewModel(repository, catalogRepository)

        viewModel.catalogDetail.test {
            assertEquals(CatalogDetailUiState.Hidden, awaitItem())
            viewModel.openCatalogDetail(1)
            var settled = awaitItem()
            if (settled == CatalogDetailUiState.Loading) settled = awaitItem()
            assertTrue(settled is CatalogDetailUiState.Loaded)
            viewModel.closeCatalogDetail()
            assertEquals(CatalogDetailUiState.Hidden, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retryCatalogDetail re-fetches after an error`() = runTest(mainDispatcher) {
        val detail = catalogTeaDetail(1, ru = "Лунцзин")
        coEvery { catalogRepository.detail(1) } returnsMany
            listOf(CatalogDetailResult.Error, CatalogDetailResult.Loaded(detail))
        val viewModel = AddTeaViewModel(repository, catalogRepository)

        viewModel.catalogDetail.test {
            assertEquals(CatalogDetailUiState.Hidden, awaitItem())
            viewModel.openCatalogDetail(1)
            var first = awaitItem()
            if (first == CatalogDetailUiState.Loading) first = awaitItem()
            assertEquals(CatalogDetailUiState.Error, first)
            viewModel.retryCatalogDetail()
            var second = awaitItem()
            if (second == CatalogDetailUiState.Loading) second = awaitItem()
            assertTrue(second is CatalogDetailUiState.Loaded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun catalogTeaDetail(
        id: Long,
        ru: String? = null,
        en: String? = null,
        pinyin: String? = null,
        type: TeaType = TeaType.GREEN,
        origin: String? = null,
        verification: String = "verified",
    ): CatalogTeaDetail = CatalogTeaDetail(
        id = id,
        type = type,
        originCountry = origin,
        region = null,
        cultivar = null,
        oxidationMin = null,
        oxidationMax = null,
        brand = null,
        image = null,
        names = buildList {
            ru?.let { add(CatalogName("ru", it, isPrimary = true)) }
            en?.let { add(CatalogName("en", it, isPrimary = true)) }
            pinyin?.let { add(CatalogName("pinyin", it, isPrimary = false)) }
        },
        descriptions = emptyList(),
        flavors = emptyList(),
        provenance = CatalogProvenance(
            source = "curated",
            sourceUrl = null,
            license = null,
            verificationStatus = verification,
            confidence = null,
        ),
    )
}
