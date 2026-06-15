package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Board
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest {

    private val repository = mockk<TeaBoardRepository>()

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
    fun `moveTea forwards the bound board id to the repository`() = runTest {
        coEvery { repository.moveTea(any(), any(), any(), any()) } just Runs
        val viewModel = BoardViewModel(repository)
        viewModel.bind("b")

        viewModel.moveTea(teaId = "t1", targetTierId = "a", targetIndex = 2)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.moveTea(eq("b"), eq("t1"), eq("a"), eq(2)) }
    }

    @Test
    fun `moveTea is a no-op when no board is bound`() = runTest {
        val viewModel = BoardViewModel(repository)

        viewModel.moveTea(teaId = "t1", targetTierId = "a", targetIndex = 0)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.moveTea(any(), any(), any(), any()) }
    }
}
