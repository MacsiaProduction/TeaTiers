package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.repository.CatalogBrowseResult
import com.macsia.teatiers.data.repository.CatalogFacetsResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.CatalogSearchResult
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.TeaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class BrowseCatalogViewModelTest {

    private val catalogRepository = mockk<CatalogRepository>()
    private val boardRepository = mockk<TeaBoardRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Default: no boards unless a test overrides it.
        every { boardRepository.boards } returns MutableStateFlow(emptyList())
        // Default: no facets unless a test overrides it (UX-F-1) — a fetch failure must not block browsing.
        coEvery { catalogRepository.facets() } returns CatalogFacetsResult.Error
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun tea(id: Long, name: String) = CatalogTea(
        id = id,
        type = TeaType.GREEN,
        originCountry = null,
        brand = null,
        verificationStatus = "verified",
        names = listOf(CatalogName("ru", name, isPrimary = true)),
    )

    @Test
    fun `loads the first page on init and keeps the cursor for more`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А"), tea(2, "Б")), nextCursor = 5L)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is BrowseCatalogUiState.Loaded)
        state as BrowseCatalogUiState.Loaded
        assertEquals(listOf(1L, 2L), state.teas.map { it.id })
        assertFalse(state.endReached)
        assertTrue(state.canLoadMore)
    }

    @Test
    fun `a null next cursor marks the end`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertTrue(state.endReached)
        assertFalse(state.canLoadMore)
    }

    @Test
    fun `an empty catalog surfaces Empty`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        assertEquals(BrowseCatalogUiState.Empty, vm.state.value)
    }

    @Test
    fun `loadMore appends the next page and advances the cursor`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returnsMany listOf(
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = 1L),
            CatalogBrowseResult.Loaded(listOf(tea(2, "Б")), nextCursor = null),
        )
        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertEquals(listOf(1L, 2L), state.teas.map { it.id })
        assertTrue(state.endReached)
    }

    @Test
    fun `loadMore is a no-op once the end is reached`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = null)
        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        // Still just the one page; no second browse was issued.
        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertEquals(listOf(1L), state.teas.map { it.id })
    }

    @Test
    fun `an offline first page surfaces Offline and retry reloads`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returnsMany listOf(
            CatalogBrowseResult.Offline,
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = null),
        )
        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()
        assertEquals(BrowseCatalogUiState.Offline, vm.state.value)

        vm.retry()
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertEquals(listOf(1L), state.teas.map { it.id })
    }

    @Test
    fun `a failed load-more is flagged and retryable`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returnsMany listOf(
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = 1L),
            CatalogBrowseResult.Error,
            CatalogBrowseResult.Loaded(listOf(tea(2, "Б")), nextCursor = null),
        )
        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        val failed = vm.state.value as BrowseCatalogUiState.Loaded
        assertTrue(failed.appendFailed)
        assertEquals(listOf(1L), failed.teas.map { it.id }) // page 1 still shown

        vm.retry()
        advanceUntilIdle()
        val recovered = vm.state.value as BrowseCatalogUiState.Loaded
        assertFalse(recovered.appendFailed)
        assertEquals(listOf(1L, 2L), recovered.teas.map { it.id })
        assertTrue(recovered.endReached)
    }

    @Test
    fun `a query switches the list to a single page of search results`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = 5L)
        coEvery { catalogRepository.search(any(), any(), any()) } returns
            CatalogSearchResult.Loaded(listOf(tea(9, "Улун")), fromCache = false)
        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.setQuery("улун")
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertEquals(listOf(9L), state.teas.map { it.id })
        assertTrue(state.endReached) // search has no load-more
        assertFalse(state.canLoadMore)
        assertFalse(state.truncated) // well under the page cap
    }

    @Test
    fun `a full page of search results is flagged truncated (UX-F-4)`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)
        val fullPage = (1..CatalogRepository.DEFAULT_LIMIT).map { tea(it.toLong(), "Чай $it") }
        coEvery { catalogRepository.search(any(), any(), any()) } returns
            CatalogSearchResult.Loaded(fullPage, fromCache = false)
        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.setQuery("чай")
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertTrue(state.truncated, "a full page (no cursor) may hide further matches")
    }

    @Test
    fun `clearing the query returns to the browse list`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = null)
        coEvery { catalogRepository.search(any(), any(), any()) } returns
            CatalogSearchResult.Loaded(listOf(tea(9, "Улун")), fromCache = false)
        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.setQuery("улун")
        advanceUntilIdle()
        vm.setQuery("")
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertEquals(listOf(1L), state.teas.map { it.id })
    }

    @Test
    fun `boards are exposed for the picker`() = runTest {
        every { boardRepository.boards } returns MutableStateFlow(
            listOf(Board(id = "b1", name = "Зелёные", tiers = emptyList(), placements = emptyMap(), unranked = emptyList())),
        )
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        assertEquals(listOf(BoardPick("b1", "Зелёные")), vm.boards.value)
    }

    @Test
    fun `availableTypes populates from facets on init (UX-F-1)`() = runTest {
        coEvery { catalogRepository.facets() } returns CatalogFacetsResult.Loaded(listOf(TeaType.GREEN, TeaType.OOLONG))
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        assertEquals(listOf(TeaType.GREEN, TeaType.OOLONG), vm.availableTypes.value)
    }

    @Test
    fun `a failed facets fetch leaves availableTypes empty instead of blocking browse (UX-F-1)`() = runTest {
        coEvery { catalogRepository.facets() } returns CatalogFacetsResult.Offline
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        assertTrue(vm.availableTypes.value.isEmpty())
        assertTrue(vm.state.value is BrowseCatalogUiState.Loaded)
    }

    @Test
    fun `setTypeFilter reruns browse with the selected type`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.setTypeFilter(TeaType.OOLONG)
        advanceUntilIdle()

        assertEquals(TeaType.OOLONG, vm.typeFilter.value)
        coVerify(exactly = 1) { catalogRepository.browse(cursor = isNull(), type = eq(TeaType.OOLONG), limit = any()) }
    }

    @Test
    fun `setTypeFilter reruns an active search with the selected type`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)
        coEvery { catalogRepository.search(any(), any(), any()) } returns
            CatalogSearchResult.Loaded(listOf(tea(9, "Улун")), fromCache = false)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()
        vm.setQuery("улун")
        advanceUntilIdle()

        vm.setTypeFilter(TeaType.OOLONG)
        advanceUntilIdle()

        coVerify(exactly = 1) { catalogRepository.search(eq("улун"), type = eq(TeaType.OOLONG), limit = any()) }
    }

    @Test
    fun `a type-filter change cancels a slower in-flight search instead of racing it (review)`() = runTest {
        coEvery { catalogRepository.browse(any(), any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)
        // The unfiltered search is held open on a gate; the type-filtered one resolves immediately.
        // setTypeFilter must CANCEL the still-pending unfiltered call (routed through the same
        // collectLatest pipeline as the query) rather than let it race the newer, filtered one and
        // possibly overwrite it if it happened to resolve later.
        val slowGate = CompletableDeferred<CatalogSearchResult>()
        coEvery { catalogRepository.search(eq("чай"), type = isNull(), limit = any()) } coAnswers { slowGate.await() }
        coEvery { catalogRepository.search(eq("чай"), type = eq(TeaType.OOLONG), limit = any()) } returns
            CatalogSearchResult.Loaded(listOf(tea(9, "Улун")), fromCache = false)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        vm.setQuery("чай")
        advanceUntilIdle() // debounce fires; the unfiltered search starts and suspends on slowGate

        vm.setTypeFilter(TeaType.OOLONG)
        advanceUntilIdle() // the type-filtered search resolves immediately

        // Completing the stale gate AFTER the filtered search already resolved must not clobber the
        // state — that would only happen if the earlier call were still alive (not actually cancelled).
        slowGate.complete(CatalogSearchResult.Loaded(listOf(tea(1, "Old")), fromCache = false))
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertEquals(listOf(9L), state.teas.map { it.id })
    }
}
