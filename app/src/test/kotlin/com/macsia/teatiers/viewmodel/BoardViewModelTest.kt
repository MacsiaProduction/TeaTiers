package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.R
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.TeaSampleEntity
import com.macsia.teatiers.data.db.TierEntity
import com.macsia.teatiers.data.repository.DeletedTea
import com.macsia.teatiers.data.repository.DeletedTier
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.domain.model.Board
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest {

    private val repository = mockk<TeaBoardRepository>()
    private val enrichmentManager = mockk<TeaEnrichmentManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.boards } returns MutableStateFlow(emptyList<Board>())
        every { repository.boardsLoaded } returns MutableStateFlow(true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState stays Loading until the boards flow has loaded (R4-REG-2)`() = runTest {
        every { repository.boardsLoaded } returns MutableStateFlow(false)
        val viewModel = BoardViewModel(repository, enrichmentManager)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.bind("b")
        advanceUntilIdle()

        assertEquals(BoardScreenState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `uiState is NotFound when boards loaded but the bound id is absent (R4-REG-2)`() = runTest {
        // boards=empty + boardsLoaded=true (setUp): a deleted board, or the boards flow settling on the
        // terminal read-failure empty list — must resolve to NotFound, never a perpetual spinner.
        val viewModel = BoardViewModel(repository, enrichmentManager)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.bind("missing")
        advanceUntilIdle()

        assertEquals(BoardScreenState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun `uiState is Loaded when the bound board is present`() = runTest {
        val board = Board(id = "b", name = "Board", tiers = emptyList(), placements = emptyMap(), unranked = emptyList())
        every { repository.boards } returns MutableStateFlow(listOf(board))
        val viewModel = BoardViewModel(repository, enrichmentManager)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.bind("b")
        advanceUntilIdle()

        assertEquals(BoardScreenState.Loaded(board.toUiState()), viewModel.uiState.value)
    }

    @Test
    fun `movePlacement forwards the bound board id to the repository`() = runTest {
        coEvery { repository.moveTea(any(), any(), any(), any()) } just Runs
        val viewModel = BoardViewModel(repository, enrichmentManager)
        viewModel.bind("b")

        viewModel.movePlacement(placementId = "p1", targetTierId = "a", targetIndex = 2)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.moveTea(eq("b"), eq("p1"), eq("a"), eq(2)) }
    }

    @Test
    fun `movePlacement is a no-op when no board is bound`() = runTest {
        val viewModel = BoardViewModel(repository, enrichmentManager)

        viewModel.movePlacement(placementId = "p1", targetTierId = "a", targetIndex = 0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.moveTea(any(), any(), any(), any()) }
    }

    @Test
    fun `removePlacement forwards to the repository (no boardId needed)`() = runTest {
        coEvery { repository.removePlacement(any()) } returns null
        val viewModel = BoardViewModel(repository, enrichmentManager)

        viewModel.removePlacement("p1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.removePlacement(eq("p1")) }
    }

    @Test
    fun `removePlacement offers an undo that restores the placement`() = runTest {
        val placement = PlacementEntity(id = "p1", boardId = "b", teaId = "t1", tierId = null, position = 0)
        coEvery { repository.removePlacement("p1") } returns placement
        coEvery { repository.restorePlacement(placement) } just Runs
        val viewModel = BoardViewModel(repository, enrichmentManager)
        viewModel.bind("b")
        val events = mutableListOf<ShowSnackbar>()
        backgroundScope.launch { viewModel.events.collect { events += it } }

        viewModel.removePlacement("p1")
        advanceUntilIdle()

        val event = events.single()
        assertEquals(R.string.snackbar_placement_removed, event.messageRes)
        assertEquals(R.string.action_undo, event.actionLabelRes)

        event.onAction!!.invoke() // user taps "Вернуть"
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.restorePlacement(placement) }
    }

    @Test
    fun `deleteTea forwards to the repository`() = runTest {
        coEvery { repository.deleteTea(any()) } returns null
        val viewModel = BoardViewModel(repository, enrichmentManager)

        viewModel.deleteTea("tea-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteTea(eq("tea-1")) }
    }

    @Test
    fun `deleteTea offers an undo that restores the tea`() = runTest {
        val snapshot = DeletedTea(
            tea = TeaSampleEntity(
                id = "tea-1", nameRu = "Зелёный", nameZh = null, pinyin = null, nameEn = null,
                type = "GREEN", origin = null, shortBlurb = null, notes = null,
            ),
            flavors = emptyList(),
            purchases = emptyList(),
            placements = emptyList(),
            photos = emptyList(),
        )
        coEvery { repository.deleteTea("tea-1") } returns snapshot
        coEvery { repository.restoreTea(snapshot) } just Runs
        val viewModel = BoardViewModel(repository, enrichmentManager)
        val events = mutableListOf<ShowSnackbar>()
        backgroundScope.launch { viewModel.events.collect { events += it } }

        viewModel.deleteTea("tea-1")
        advanceUntilIdle()

        val event = events.single()
        assertEquals(R.string.snackbar_tea_deleted, event.messageRes)
        assertEquals(R.string.action_undo, event.actionLabelRes)

        event.onAction!!.invoke() // user taps "Вернуть"
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.restoreTea(snapshot) }
    }

    @Test
    fun `tier actions forward the bound board id to the repository`() = runTest {
        coEvery { repository.addTier(any(), any()) } just Runs
        coEvery { repository.renameTier(any(), any(), any()) } just Runs
        coEvery { repository.setTierColor(any(), any(), any()) } just Runs
        coEvery { repository.reorderTiers(any(), any()) } just Runs
        coEvery { repository.removeTier(any(), any()) } returns null
        val viewModel = BoardViewModel(repository, enrichmentManager)
        viewModel.bind("b")

        viewModel.addTier("Новый")
        viewModel.renameTier(tierId = "s", label = "Топ")
        viewModel.setTierColor(tierId = "s", colorArgb = 0xFF356A4BL)
        viewModel.reorderTiers(orderedTierIds = listOf("a", "s"))
        viewModel.removeTier(tierId = "a")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.addTier(eq("b"), eq("Новый")) }
        coVerify(exactly = 1) { repository.renameTier(eq("b"), eq("s"), eq("Топ")) }
        coVerify(exactly = 1) { repository.setTierColor(eq("b"), eq("s"), eq(0xFF356A4BL)) }
        coVerify(exactly = 1) { repository.reorderTiers(eq("b"), eq(listOf("a", "s"))) }
        coVerify(exactly = 1) { repository.removeTier(eq("b"), eq("a")) }
    }

    @Test
    fun `removeTier offers an undo that restores the tier`() = runTest {
        val tier = TierEntity(id = "s", boardId = "b", label = "S", position = 0, colorArgb = null)
        val deleted = DeletedTier(tier = tier, placements = emptyList())
        coEvery { repository.removeTier("b", "s") } returns deleted
        coEvery { repository.restoreTier(deleted) } just Runs
        val viewModel = BoardViewModel(repository, enrichmentManager)
        viewModel.bind("b")
        val events = mutableListOf<ShowSnackbar>()
        backgroundScope.launch { viewModel.events.collect { events += it } }

        viewModel.removeTier("s")
        advanceUntilIdle()

        val event = events.single()
        assertEquals(R.string.snackbar_tier_deleted, event.messageRes)
        assertEquals(R.string.action_undo, event.actionLabelRes)

        event.onAction!!.invoke() // user taps "Вернуть"
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.restoreTier(deleted) }
    }

    @Test
    fun `tier actions are a no-op when no board is bound`() = runTest {
        val viewModel = BoardViewModel(repository, enrichmentManager)

        viewModel.addTier("Новый")
        viewModel.removeTier(tierId = "a")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.addTier(any(), any()) }
        coVerify(exactly = 0) { repository.removeTier(any(), any()) }
    }

    @Test
    fun `bind resumes pending enrichment on every call, not just the first (UX2-P2-18)`() = runTest {
        val viewModel = BoardViewModel(repository, enrichmentManager)

        viewModel.bind("b")
        viewModel.bind("b")

        // No per-VM-instance gate: the manager's own cooldown is the single throttle now.
        verify(exactly = 2) { enrichmentManager.resumePending() }
    }

    @Test
    fun `retryEnrichment dispatches when the tea is not already in flight`() = runTest {
        every { enrichmentManager.isInFlight("t1") } returns false
        val viewModel = BoardViewModel(repository, enrichmentManager)

        viewModel.retryEnrichment("t1")

        verify(exactly = 1) { enrichmentManager.retry("t1") }
    }

    @Test
    fun `retryEnrichment gives feedback instead of silently no-op'ing on a double-tap (UX2-P2-15)`() = runTest {
        every { enrichmentManager.isInFlight("t1") } returns true
        val viewModel = BoardViewModel(repository, enrichmentManager)
        val events = mutableListOf<ShowSnackbar>()
        backgroundScope.launch { viewModel.events.collect { events += it } }

        viewModel.retryEnrichment("t1")
        advanceUntilIdle()

        verify(exactly = 0) { enrichmentManager.retry("t1") }
        assertEquals(R.string.enrichment_already_retrying, events.single().messageRes)
    }
}
