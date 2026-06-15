package com.macsia.teatiers.viewmodel

import app.cash.turbine.test
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tier
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

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.boards } returns MutableStateFlow(listOf(board))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tiers expose the bound board's tiers sorted by position`() = runTest {
        val viewModel = AddTeaViewModel(repository)

        viewModel.tiers.test {
            viewModel.bind("b")
            // the StateFlow starts empty; wait for the first emission carrying the bound tiers
            var tiers = awaitItem()
            while (tiers.isEmpty()) tiers = awaitItem()
            assertEquals(listOf("s", "a"), tiers.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit persists the mapped tea and notifies on success`() = runTest {
        coEvery { repository.addTea(any(), any(), any()) } just Runs
        val viewModel = AddTeaViewModel(repository)
        viewModel.bind("b")
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
        val viewModel = AddTeaViewModel(repository)
        viewModel.bind("b")

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        coVerify(exactly = 0) { repository.addTea(any(), any(), any()) }
    }

    @Test
    fun `submit is a no-op when no board is bound`() = runTest {
        val viewModel = AddTeaViewModel(repository)
        viewModel.update { it.copy(nameRu = "Чай") }

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        coVerify(exactly = 0) { repository.addTea(any(), any(), any()) }
    }
}
