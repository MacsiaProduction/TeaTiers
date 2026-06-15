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

    @Test
    fun `tier actions forward the bound board id to the repository`() = runTest {
        coEvery { repository.addTier(any(), any()) } just Runs
        coEvery { repository.renameTier(any(), any(), any()) } just Runs
        coEvery { repository.setTierColor(any(), any(), any()) } just Runs
        coEvery { repository.reorderTiers(any(), any()) } just Runs
        coEvery { repository.removeTier(any(), any()) } just Runs
        val viewModel = BoardViewModel(repository)
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
    fun `tier actions are a no-op when no board is bound`() = runTest {
        val viewModel = BoardViewModel(repository)

        viewModel.addTier("Новый")
        viewModel.removeTier(tierId = "a")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.addTier(any(), any()) }
        coVerify(exactly = 0) { repository.removeTier(any(), any()) }
    }
}
