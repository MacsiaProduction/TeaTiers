package com.macsia.teatiers.viewmodel

import app.cash.turbine.test
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Board
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

    @Test
    fun `purchase helpers add update and remove drafts`() = runTest {
        val viewModel = AddTeaViewModel(repository)
        viewModel.bind("b")

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
        val viewModel = AddTeaViewModel(repository)
        viewModel.bind("b")

        viewModel.removePurchase(5)
        viewModel.updatePurchase(7) { it.copy(text = "noop") }

        assertTrue(viewModel.form.value.purchases.isEmpty())
    }

    @Test
    fun `bind in edit mode prefills the form from the tea`() = runTest {
        val tea = Tea(
            id = "t1",
            nameRu = "Да Хун Пао",
            type = TeaType.OOLONG,
            origin = "Уишань",
            flavor = listOf(FlavorScore(FlavorDimension.ROASTED, 5)),
            notes = "после обеда",
            purchaseLocations = listOf(PurchaseLocation.FreeText("Рынок", "поездка")),
        )
        every { repository.tea(eq("b"), eq("t1")) } returns tea
        val viewModel = AddTeaViewModel(repository)

        viewModel.bind("b", teaId = "t1")

        val form = viewModel.form.value
        assertEquals("Да Хун Пао", form.nameRu)
        assertEquals(TeaType.OOLONG, form.type)
        assertEquals(mapOf(FlavorDimension.ROASTED to 5), form.flavors)
        assertEquals(1, form.purchases.size)
        assertEquals("Рынок", form.purchases[0].text)
        assertEquals("t1", viewModel.editingTeaId.value)
    }

    @Test
    fun `submit in edit mode calls updateTea, not addTea`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN)
        every { repository.tea(eq("b"), eq("t1")) } returns tea
        coEvery { repository.updateTea(any(), any(), any()) } just Runs
        val viewModel = AddTeaViewModel(repository)
        viewModel.bind("b", teaId = "t1")
        viewModel.update { it.copy(nameRu = "Новый чай", notes = "обновлено") }

        var saved = false
        viewModel.submit { saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        coVerify(exactly = 1) {
            repository.updateTea(
                eq("b"),
                eq("t1"),
                match { it.nameRu == "Новый чай" && it.notes == "обновлено" },
            )
        }
        coVerify(exactly = 0) { repository.addTea(any(), any(), any()) }
    }

    @Test
    fun `bind clears the form when re-binding to a fresh add flow`() = runTest {
        val tea = Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN, notes = "заметка")
        every { repository.tea(eq("b"), eq("t1")) } returns tea
        val viewModel = AddTeaViewModel(repository)
        viewModel.bind("b", teaId = "t1")
        // simulate the user navigating away from edit and into the add flow on the same VM
        viewModel.bind("b")

        val form = viewModel.form.value
        assertEquals("", form.nameRu)
        assertEquals("", form.notes)
        assertTrue(form.purchases.isEmpty())
        assertEquals(null, viewModel.editingTeaId.value)
    }
}
