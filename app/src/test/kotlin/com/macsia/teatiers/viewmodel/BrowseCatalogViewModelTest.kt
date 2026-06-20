package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.repository.CatalogBrowseResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.TeaType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
        coEvery { catalogRepository.browse(any(), any()) } returns
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
        coEvery { catalogRepository.browse(any(), any()) } returns
            CatalogBrowseResult.Loaded(listOf(tea(1, "А")), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        val state = vm.state.value as BrowseCatalogUiState.Loaded
        assertTrue(state.endReached)
        assertFalse(state.canLoadMore)
    }

    @Test
    fun `an empty catalog surfaces Empty`() = runTest {
        coEvery { catalogRepository.browse(any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        assertEquals(BrowseCatalogUiState.Empty, vm.state.value)
    }

    @Test
    fun `loadMore appends the next page and advances the cursor`() = runTest {
        coEvery { catalogRepository.browse(any(), any()) } returnsMany listOf(
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
        coEvery { catalogRepository.browse(any(), any()) } returns
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
        coEvery { catalogRepository.browse(any(), any()) } returnsMany listOf(
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
        coEvery { catalogRepository.browse(any(), any()) } returnsMany listOf(
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
    fun `boards are exposed for the picker`() = runTest {
        every { boardRepository.boards } returns MutableStateFlow(
            listOf(Board(id = "b1", name = "Зелёные", tiers = emptyList(), placements = emptyMap(), unranked = emptyList())),
        )
        coEvery { catalogRepository.browse(any(), any()) } returns
            CatalogBrowseResult.Loaded(emptyList(), nextCursor = null)

        val vm = BrowseCatalogViewModel(catalogRepository, boardRepository)
        advanceUntilIdle()

        assertEquals(listOf(BoardPick("b1", "Зелёные")), vm.boards.value)
    }
}
