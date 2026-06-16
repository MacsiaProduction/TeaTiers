package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.TierTemplate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardsViewModelTest {

    private val repository = mockk<TeaBoardRepository>()
    private val enrichmentManager = mockk<TeaEnrichmentManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.boards } returns MutableStateFlow(emptyList<Board>())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening the home screen resumes pending enrichment`() = runTest {
        BoardsViewModel(repository, enrichmentManager)

        verify(exactly = 1) { enrichmentManager.resumePending() }
    }

    @Test
    fun `createBoard forwards label and template to the repository`() = runTest {
        coEvery { repository.createBoard(any(), any()) } returns "board-x"
        val viewModel = BoardsViewModel(repository, enrichmentManager)

        viewModel.createBoard(label = "Утренние пуэры", template = TierTemplate.F_TO_S)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.createBoard(eq("Утренние пуэры"), eq(TierTemplate.F_TO_S))
        }
    }

    @Test
    fun `createBoard forwards each template kind verbatim`() = runTest {
        coEvery { repository.createBoard(any(), any()) } returns "board-x"
        val viewModel = BoardsViewModel(repository, enrichmentManager)

        viewModel.createBoard(label = "F-S", template = TierTemplate.F_TO_S)
        viewModel.createBoard(label = "1-10", template = TierTemplate.ONE_TO_TEN)
        viewModel.createBoard(label = "Пусто", template = TierTemplate.BLANK)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.createBoard(eq("F-S"), eq(TierTemplate.F_TO_S)) }
        coVerify(exactly = 1) { repository.createBoard(eq("1-10"), eq(TierTemplate.ONE_TO_TEN)) }
        coVerify(exactly = 1) { repository.createBoard(eq("Пусто"), eq(TierTemplate.BLANK)) }
    }
}
