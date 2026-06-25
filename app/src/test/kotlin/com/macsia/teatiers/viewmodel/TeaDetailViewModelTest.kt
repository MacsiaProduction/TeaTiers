package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.repository.CatalogDetailResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.CatalogProvenance
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeaDetailViewModelTest {

    private val repository = mockk<TeaBoardRepository>()
    private val catalog = mockk<CatalogRepository>()
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        every { repository.boards } returns MutableStateFlow(emptyList())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun tea(catalogTeaId: Long?, flavor: List<FlavorScore> = emptyList()) =
        Tea(id = "t1", nameRu = "Чай", type = TeaType.GREEN, flavor = flavor, catalogTeaId = catalogTeaId)

    private fun referenceDetail(vararg flavors: FlavorScore) = CatalogDetailResult.Loaded(
        CatalogTeaDetail(
            id = 5, type = TeaType.GREEN, originCountry = null, region = null, cultivar = null,
            oxidationMin = null, oxidationMax = null, brand = null, image = null,
            names = emptyList(), descriptions = emptyList(), flavors = flavors.toList(),
            provenance = CatalogProvenance("curated", null, null, "verified", null),
        ),
    )

    @Test
    fun `reference flavors are fetched for a catalog-linked tea`() = runTest {
        coEvery { repository.tea("t1") } returns tea(catalogTeaId = 5)
        coEvery { catalog.detail(5) } returns referenceDetail(FlavorScore(FlavorDimension.GRASSY, 4))
        val vm = TeaDetailViewModel(repository, catalog)
        vm.bind("t1")

        backgroundScope.launch { vm.referenceFlavors.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(FlavorScore(FlavorDimension.GRASSY, 4)), vm.referenceFlavors.value)
    }

    @Test
    fun `no catalog lookup for an unlinked tea`() = runTest {
        coEvery { repository.tea("t1") } returns tea(catalogTeaId = null)
        val vm = TeaDetailViewModel(repository, catalog)
        vm.bind("t1")

        backgroundScope.launch { vm.referenceFlavors.collect {} }
        advanceUntilIdle()

        assertTrue(vm.referenceFlavors.value.isEmpty())
        coVerify(exactly = 0) { catalog.detail(any()) }
    }

    @Test
    fun `uiState resolves to Loaded for an existing tea`() = runTest {
        coEvery { repository.tea("t1") } returns tea(catalogTeaId = null)
        val vm = TeaDetailViewModel(repository, catalog)
        vm.bind("t1")

        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is TeaDetailUiState.Loaded)
        assertEquals("t1", (state as TeaDetailUiState.Loaded).tea.id)
    }

    @Test
    fun `uiState resolves to NotFound when the tea is missing`() = runTest {
        coEvery { repository.tea("gone") } returns null
        val vm = TeaDetailViewModel(repository, catalog)
        vm.bind("gone")

        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(TeaDetailUiState.NotFound, vm.uiState.value)
    }

    @Test
    fun `useReferenceAsMyRating copies the reference profile into the user tea`() = runTest {
        val reference = listOf(FlavorScore(FlavorDimension.GRASSY, 4), FlavorScore(FlavorDimension.UMAMI, 3))
        coEvery { repository.tea("t1") } returns tea(catalogTeaId = 5)
        coEvery { catalog.detail(5) } returns referenceDetail(*reference.toTypedArray())
        coEvery { repository.updateTea(any(), any()) } just Runs
        val vm = TeaDetailViewModel(repository, catalog)
        vm.bind("t1")
        backgroundScope.launch { vm.referenceFlavors.collect {} }
        advanceUntilIdle()

        vm.useReferenceAsMyRating()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateTea("t1", match { it.flavor == reference }) }
    }
}
