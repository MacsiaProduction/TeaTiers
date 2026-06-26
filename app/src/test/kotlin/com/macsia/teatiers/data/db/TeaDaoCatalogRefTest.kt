package com.macsia.teatiers.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Real-Room (Robolectric, in-memory) coverage for the catalog-ref upsert. Guards the monotonic
 * [TeaDao.upsertRef] contract: [CatalogRefEntity.catalogPublicId] is the durable cross-rebuild key
 * (#137-C2), lazily backfilled and never allowed to regress to null when an older server re-resolves
 * a ref without one. The bug this pins: a naive full-row @Upsert wiped a stamped UUID back to null.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class TeaDaoCatalogRefTest {

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

    @Test
    fun `re-resolving a ref without a publicId keeps the stamped one`() = runBlocking {
        dao.upsertRef(CatalogRefEntity(id = 42, type = "OOLONG", catalogPublicId = "uuid-abc", fetchedAtEpochMs = 1))
        // Older server: same ref, no publicId — other facts may update but the durable UUID must survive.
        dao.upsertRef(CatalogRefEntity(id = 42, type = "OOLONG", catalogPublicId = null, brand = "Acme", fetchedAtEpochMs = 2))

        assertEquals("uuid-abc", dao.loadRefPublicId(42))
    }

    @Test
    fun `a later publicId still backfills a stub that had none`() = runBlocking {
        dao.upsertRef(CatalogRefEntity(id = 7, type = "GREEN", fetchedAtEpochMs = 1))
        dao.upsertRef(CatalogRefEntity(id = 7, type = "GREEN", catalogPublicId = "uuid-xyz", fetchedAtEpochMs = 2))

        assertEquals("uuid-xyz", dao.loadRefPublicId(7))
    }
}
