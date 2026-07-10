package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Regression coverage for the UX2-P2-16 debounce (the text field must not lag typing). */
@OptIn(ExperimentalCoroutinesApi::class)
class MyTeasViewModelTest {

    private val repository = mockk<TeaBoardRepository>()

    private fun tea(id: String, name: String) = Tea(id = id, nameRu = name, type = TeaType.GREEN)

    @BeforeEach
    fun setUp() {
        // StandardTestDispatcher (not Unconfined): debounce needs real virtual-time control.
        Dispatchers.setMain(StandardTestDispatcher())
        every { repository.allTeas } returns MutableStateFlow(listOf(tea("t1", "Лунцзин"), tea("t2", "Ганпаудер")))
        every { repository.boards } returns MutableStateFlow(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state_query updates immediately, before the debounce settles`() = runTest {
        val viewModel = MyTeasViewModel(repository)
        viewModel.state.onEach {}.launchIn(backgroundScope)
        advanceUntilIdle()

        viewModel.setQuery("Лун")
        // Process the immediate `query` emission (undebounced) WITHOUT letting virtual time advance —
        // the debounced `filtered` pipeline must not have a chance to fire yet.
        runCurrent()

        assertEquals("Лун", viewModel.state.value.query)
    }

    @Test
    fun `state is loading until the first tea-list emission, then not (UX3-P2-3)`() = runTest {
        // A SharedFlow with no initial value so the combine can't run until we emit — mirrors Room's
        // cold-start read landing after the screen first composes.
        val teas = MutableSharedFlow<List<Tea>>(replay = 0)
        every { repository.allTeas } returns teas
        val viewModel = MyTeasViewModel(repository)
        viewModel.state.onEach {}.launchIn(backgroundScope)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.loading) // nothing emitted yet -> spinner, not the empty state

        teas.emit(emptyList())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading) // emitted (even empty) -> load is done
        assertTrue(viewModel.state.value.collectionEmpty)
    }

    @Test
    fun `items only reflect the new query once the debounce elapses`() = runTest {
        val viewModel = MyTeasViewModel(repository)
        viewModel.state.onEach {}.launchIn(backgroundScope)
        advanceUntilIdle()

        viewModel.setQuery("Лун")
        runCurrent()

        // Immediately after typing: filtering hasn't caught up yet (still the unfiltered/prior list).
        assertEquals(2, viewModel.state.value.items.size)

        advanceTimeBy(CATALOG_SEARCH_DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(listOf("t1"), viewModel.state.value.items.map { it.tea.id })
    }

    @Test
    fun `state never emits a typeFilter and items pair from different selections (post-merge review)`() = runTest {
        every { repository.allTeas } returns MutableStateFlow(
            listOf(tea("t1", "Лунцзин"), Tea(id = "t2", nameRu = "Да Хун Пао", type = TeaType.OOLONG)),
        )
        val viewModel = MyTeasViewModel(repository)
        val emissions = mutableListOf<MyTeasUiState>()
        viewModel.state.onEach { emissions += it }.launchIn(backgroundScope)
        advanceUntilIdle()

        viewModel.toggleType(TeaType.GREEN)
        advanceUntilIdle()

        // Every emission where the filter is GREEN must only ever list GREEN teas — `typeFilter` and
        // `items` come from the same `filtered` snapshot now, so they can't straddle two selections.
        emissions.filter { it.typeFilter == TeaType.GREEN }.forEach { snapshot ->
            assertTrue(snapshot.items.all { it.tea.type == TeaType.GREEN }, "stale items paired with new typeFilter: $snapshot")
        }
        assertEquals(listOf("t1"), viewModel.state.value.items.map { it.tea.id })
    }
}
