package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.R
import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.repository.DeletedBoard
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.data.settings.SettingsRepository
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.TierTemplate
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardsViewModelTest {

    private val repository = mockk<TeaBoardRepository>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val enrichmentManager = mockk<TeaEnrichmentManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.boards } returns MutableStateFlow(emptyList<Board>())
        every { settings.introDismissed } returns flowOf(false)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening the home screen resumes pending enrichment`() = runTest {
        BoardsViewModel(repository, settings, enrichmentManager)

        verify(exactly = 1) { enrichmentManager.resumePending() }
    }

    @Test
    fun `createBoard forwards label and template to the repository`() = runTest {
        coEvery { repository.createBoard(any(), any()) } returns "board-x"
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)

        viewModel.createBoard(label = "Утренние пуэры", template = TierTemplate.F_TO_S)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.createBoard(eq("Утренние пуэры"), eq(TierTemplate.F_TO_S))
        }
    }

    @Test
    fun `createBoard emits the new board id so the screen can open it`() = runTest {
        coEvery { repository.createBoard(any(), any()) } returns "board-new"
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)
        val opened = mutableListOf<String>()
        backgroundScope.launch { viewModel.createdBoard.collect { opened += it } }

        viewModel.createBoard("Подборка", TierTemplate.F_TO_S)
        advanceUntilIdle()

        assertEquals(listOf("board-new"), opened)
    }

    @Test
    fun `createBoard with a blank label emits no navigation`() = runTest {
        coEvery { repository.createBoard(any(), any()) } returns null // repository rejects blank
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)
        val opened = mutableListOf<String>()
        backgroundScope.launch { viewModel.createdBoard.collect { opened += it } }

        viewModel.createBoard("   ", TierTemplate.F_TO_S)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun `createBoard forwards each template kind verbatim`() = runTest {
        coEvery { repository.createBoard(any(), any()) } returns "board-x"
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)

        viewModel.createBoard(label = "F-S", template = TierTemplate.F_TO_S)
        viewModel.createBoard(label = "1-10", template = TierTemplate.ONE_TO_TEN)
        viewModel.createBoard(label = "Пусто", template = TierTemplate.BLANK)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.createBoard(eq("F-S"), eq(TierTemplate.F_TO_S)) }
        coVerify(exactly = 1) { repository.createBoard(eq("1-10"), eq(TierTemplate.ONE_TO_TEN)) }
        coVerify(exactly = 1) { repository.createBoard(eq("Пусто"), eq(TierTemplate.BLANK)) }
    }

    @Test
    fun `renameBoard forwards id and name to the repository`() = runTest {
        coEvery { repository.renameBoard(any(), any()) } just Runs
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)

        viewModel.renameBoard(boardId = "board-1", name = "Мои улуны")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.renameBoard(eq("board-1"), eq("Мои улуны")) }
    }

    @Test
    fun `deleteBoard offers an undo that restores the board`() = runTest {
        val deleted = DeletedBoard(
            board = BoardEntity(id = "board-1", name = "Доска", position = 0),
            tiers = emptyList(),
            placements = emptyList(),
        )
        coEvery { repository.deleteBoard("board-1") } returns deleted
        coEvery { repository.restoreBoard(deleted) } just Runs
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)
        val events = mutableListOf<ShowSnackbar>()
        backgroundScope.launch { viewModel.events.collect { events += it } }

        viewModel.deleteBoard("board-1")
        advanceUntilIdle()

        val event = events.single()
        assertEquals(R.string.snackbar_board_deleted, event.messageRes)
        assertEquals(R.string.action_undo, event.actionLabelRes)

        event.onAction!!.invoke() // user taps "Вернуть"
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.restoreBoard(deleted) }
    }

    @Test
    fun `showIntro is true until the intro has been dismissed`() = runTest {
        every { settings.introDismissed } returns flowOf(false)
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)
        backgroundScope.launch { viewModel.showIntro.collect {} } // activate the WhileSubscribed flow
        advanceUntilIdle()

        assertTrue(viewModel.showIntro.value)
    }

    @Test
    fun `dismissIntro persists the dismissal`() = runTest {
        coEvery { settings.setIntroDismissed() } just Runs
        val viewModel = BoardsViewModel(repository, settings, enrichmentManager)

        viewModel.dismissIntro()
        advanceUntilIdle()

        coVerify(exactly = 1) { settings.setIntroDismissed() }
    }
}
