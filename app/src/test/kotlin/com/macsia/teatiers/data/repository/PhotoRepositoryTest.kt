package com.macsia.teatiers.data.repository

import android.net.Uri
import com.macsia.teatiers.data.db.toSeedEntities
import com.macsia.teatiers.data.sample.SampleBoardProvider
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end repository coverage for the photos surface (decisions.md #43): add → row + file,
 * remove → renumber + delete file, reorder → contiguous positions, deleteTea → file cascade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoRepositoryTest {

    private fun tea(id: String, type: TeaType = TeaType.GREEN, nameRu: String = id) =
        Tea(id = id, nameRu = nameRu, type = type)

    private fun place(boardId: String, tea: Tea): Placement =
        Placement(placementId = "$boardId-${tea.id}", tea = tea)

    private val seedBoard = Board(
        id = "b",
        name = "Доска",
        tiers = listOf(Tier("s", "S", 0)),
        placements = mapOf("s" to listOf(place("b", tea("green")))),
        unranked = emptyList(),
    )

    private suspend fun TestScope.repo(photoStore: FakePhotoStore = FakePhotoStore()): TeaBoardRepository {
        val dao = FakeTeaDao()
        val seed = listOf(seedBoard).toSeedEntities()
        dao.seed(seed.boards, seed.tiers, seed.teas, seed.placements, seed.flavors, seed.purchases, seed.photos)
        return TeaBoardRepository(dao, photoStore, backgroundScope, SampleBoardProvider())
    }

    private fun fakeUri(value: String): Uri = mockk<Uri>().also {
        every { it.toString() } returns value
    }

    @Test
    fun `addPhoto copies bytes and inserts at the next position`() = runTest(UnconfinedTestDispatcher()) {
        val photoStore = FakePhotoStore()
        val repository = repo(photoStore)
        advanceUntilIdle()

        val first = repository.addPhoto("green", fakeUri("content://a"))
        val second = repository.addPhoto("green", fakeUri("content://b"))
        advanceUntilIdle()

        assertNotNull(first)
        assertNotNull(second)
        val photos = repository.tea("green")?.photos.orEmpty()
        assertEquals(listOf("/fake/1.jpg", "/fake/2.jpg"), photos.map { it.uri })
        assertEquals(listOf(0, 1), photos.map { it.position })
    }

    @Test
    fun `addPhoto returns null and writes nothing when the copy fails`() = runTest(UnconfinedTestDispatcher()) {
        val photoStore = FakePhotoStore().apply { failures += "content://broken" }
        val repository = repo(photoStore)
        advanceUntilIdle()

        val id = repository.addPhoto("green", fakeUri("content://broken"))
        advanceUntilIdle()

        assertNull(id)
        assertTrue(repository.tea("green")?.photos.orEmpty().isEmpty())
    }

    @Test
    fun `removePhoto deletes the row, renumbers, and removes the file`() = runTest(UnconfinedTestDispatcher()) {
        val photoStore = FakePhotoStore()
        val repository = repo(photoStore)
        advanceUntilIdle()

        val a = repository.addPhoto("green", fakeUri("content://a"))!!
        repository.addPhoto("green", fakeUri("content://b"))
        repository.addPhoto("green", fakeUri("content://c"))
        advanceUntilIdle()

        repository.removePhoto("green", a)
        advanceUntilIdle()

        val survivors = repository.tea("green")?.photos.orEmpty()
        assertEquals(listOf("/fake/2.jpg", "/fake/3.jpg"), survivors.map { it.uri })
        assertEquals(listOf(0, 1), survivors.map { it.position })
        assertTrue(photoStore.deleted.contains("/fake/1.jpg"))
    }

    @Test
    fun `reorderPhotos rewrites positions to match the requested order`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repo()
        advanceUntilIdle()

        val a = repository.addPhoto("green", fakeUri("content://a"))!!
        val b = repository.addPhoto("green", fakeUri("content://b"))!!
        val c = repository.addPhoto("green", fakeUri("content://c"))!!
        advanceUntilIdle()

        repository.reorderPhotos("green", listOf(c, a, b))
        advanceUntilIdle()

        val photos = repository.tea("green")?.photos.orEmpty()
        assertEquals(listOf(c, a, b), photos.map { it.id })
    }

    @Test
    fun `deleteTea cascades photo files`() = runTest(UnconfinedTestDispatcher()) {
        val photoStore = FakePhotoStore()
        val repository = repo(photoStore)
        advanceUntilIdle()

        repository.addPhoto("green", fakeUri("content://a"))
        repository.addPhoto("green", fakeUri("content://b"))
        advanceUntilIdle()

        repository.deleteTea("green")
        advanceUntilIdle()

        // The user-tea is gone, both files were asked to be deleted, and the boards Flow
        // reflects an empty placement set so the UI can no longer reach the tea.
        assertNull(repository.tea("green"))
        assertEquals(setOf("/fake/1.jpg", "/fake/2.jpg"), photoStore.deleted.toSet())
        assertTrue(repository.boards.value.single().placements.values.flatten().isEmpty())
    }
}
