package com.macsia.teatiers.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Real-Room (Robolectric, in-memory) regression coverage for the addPhoto race found in the
 * post-merge review of the round-2 usage-quality audit: concurrent `addPhoto` calls for the same
 * tea (reachable since PhotoStrip's gallery picker went multi-select) used to read the next
 * position via a separate suspend call before inserting, so two adds could land on the same
 * position. `TeaDao.addPhoto` now computes the position inside its own @Transaction, and Room
 * serializes concurrent transactions on one database — this asserts that holds under real
 * concurrent load, not just sequential calls.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class TeaDaoAddPhotoConcurrencyTest {

    private lateinit var db: TeaDatabase
    private lateinit var dao: TeaDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, TeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.teaDao()
    }

    @After
    fun teardown() = db.close()

    private fun photo(id: String) = PhotoEntity(
        id = id,
        teaId = "t1",
        uri = "file:///$id.jpg",
        position = 0, // deliberately wrong/stale — addPhoto must recompute it, not trust this
        source = "USER",
        license = null,
        sourceUrl = null,
        createdAtEpochMs = 0,
    )

    @Test
    fun `concurrent addPhoto calls for the same tea never collide on position`() = runBlocking {
        dao.insertTeas(
            listOf(
                TeaSampleEntity(
                    id = "t1", nameRu = "Чай", nameZh = null, pinyin = null, nameEn = null,
                    type = "GREEN", origin = null, shortBlurb = null, notes = null,
                ),
            ),
        )

        val photoCount = 8
        coroutineScopeAddAll(photoCount)

        val positions = dao.loadPhotos("t1").map { it.position }
        assertEquals(photoCount, positions.size) // no row lost
        assertEquals(positions.toSet().size, positions.size) // no duplicate position
        assertEquals((0 until photoCount).toList(), positions.sorted())
    }

    private suspend fun coroutineScopeAddAll(count: Int) = kotlinx.coroutines.coroutineScope {
        (0 until count).map { i ->
            async(Dispatchers.Default) { dao.addPhoto(photo("p$i")) }
        }.awaitAll()
    }
}
