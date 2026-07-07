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

/** Unwraps a successful [TeaBoardRepository.addPhoto] result, failing loudly if it wasn't. */
private fun AddPhotoResult.requireAdded(): String =
    (this as? AddPhotoResult.Added)?.photoId ?: error("expected Added, was $this")

/**
 * End-to-end repository coverage for the photos surface (decisions.md #43): add → row + file,
 * remove → renumber + delete file, reorder → contiguous positions, deleteTea → keeps files for Undo.
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
        dao.seed(seed.boards, seed.tiers, seed.catalogRefs, seed.teas, seed.placements, seed.flavors, seed.purchases, seed.photos)
        return TeaBoardRepository(dao, photoStore, backgroundScope, SampleBoardProvider(), FakeOnboardingState(seeded = true))
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

        assertTrue(first is AddPhotoResult.Added)
        assertTrue(second is AddPhotoResult.Added)
        val photos = repository.tea("green")?.photos.orEmpty()
        assertEquals(listOf("/fake/1.jpg", "/fake/2.jpg"), photos.map { it.uri })
        assertEquals(listOf(0, 1), photos.map { it.position })
    }

    @Test
    fun `addPhoto surfaces Failed and writes nothing when the copy fails`() = runTest(UnconfinedTestDispatcher()) {
        val photoStore = FakePhotoStore().apply { failures += "content://broken" }
        val repository = repo(photoStore)
        advanceUntilIdle()

        val result = repository.addPhoto("green", fakeUri("content://broken"))
        advanceUntilIdle()

        assertEquals(AddPhotoResult.Failed, result)
        assertTrue(repository.tea("green")?.photos.orEmpty().isEmpty())
    }

    @Test
    fun `addPhoto surfaces the specific reason for an oversized or out-of-space photo (UX-P1-1)`() =
        runTest(UnconfinedTestDispatcher()) {
            val photoStore = FakePhotoStore().apply {
                tooLarge += "content://huge"
                outOfSpace += "content://full-disk"
            }
            val repository = repo(photoStore)
            advanceUntilIdle()

            val tooLargeResult = repository.addPhoto("green", fakeUri("content://huge"))
            val outOfSpaceResult = repository.addPhoto("green", fakeUri("content://full-disk"))
            advanceUntilIdle()

            assertEquals(AddPhotoResult.TooLarge, tooLargeResult)
            assertEquals(AddPhotoResult.OutOfSpace, outOfSpaceResult)
            assertTrue(repository.tea("green")?.photos.orEmpty().isEmpty())
        }

    @Test
    fun `removePhoto deletes the row, renumbers, and removes the file`() = runTest(UnconfinedTestDispatcher()) {
        val photoStore = FakePhotoStore()
        val repository = repo(photoStore)
        advanceUntilIdle()

        val a = repository.addPhoto("green", fakeUri("content://a")).requireAdded()
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

        val a = repository.addPhoto("green", fakeUri("content://a")).requireAdded()
        val b = repository.addPhoto("green", fakeUri("content://b")).requireAdded()
        val c = repository.addPhoto("green", fakeUri("content://c")).requireAdded()
        advanceUntilIdle()

        repository.reorderPhotos("green", listOf(c, a, b))
        advanceUntilIdle()

        val photos = repository.tea("green")?.photos.orEmpty()
        assertEquals(listOf(c, a, b), photos.map { it.id })
    }

    @Test
    fun `deleteTea keeps photo files for Undo and restoreTea re-references them`() =
        runTest(UnconfinedTestDispatcher()) {
            val photoStore = FakePhotoStore()
            val repository = repo(photoStore)
            advanceUntilIdle()

            repository.addPhoto("green", fakeUri("content://a"))
            repository.addPhoto("green", fakeUri("content://b"))
            advanceUntilIdle()

            val deleted = repository.deleteTea("green")
            advanceUntilIdle()

            // The tea + its rows are gone, but the files are deliberately NOT deleted: an Undo
            // re-references them; the app-open sweep reclaims them only if the delete is never undone.
            assertNotNull(deleted)
            assertNull(repository.tea("green"))
            assertTrue(repository.boards.value.single().placements.values.flatten().isEmpty())
            assertTrue(photoStore.deleted.isEmpty())
            assertEquals(setOf("/fake/1.jpg", "/fake/2.jpg"), photoStore.onDisk)

            repository.restoreTea(deleted!!)
            advanceUntilIdle()

            // Undo brings the tea back with its photos pointing at the same, still-present files.
            val photos = repository.tea("green")?.photos.orEmpty()
            assertEquals(listOf("/fake/1.jpg", "/fake/2.jpg"), photos.map { it.uri })
            assertTrue(
                repository.boards.value.single().placements.getValue("s").any { it.tea.id == "green" },
            )
        }
}
