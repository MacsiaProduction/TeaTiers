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
 * Real-Room (Robolectric, in-memory) regression coverage for UX2-P0-1/P0-2 (the round-2
 * usage-quality audit): a plain edit-save and a background enrichment patch share six scalar
 * columns ([TeaMergeFields]), and a stale edit-form snapshot must not blast away a concurrent
 * enrichment fill — or vice versa, silently discard a deliberate user edit.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class TeaDaoUpdateTeaMergeTest {

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

    private suspend fun seedTea(nameEn: String? = null, notes: String? = null) {
        dao.insertTeas(
            listOf(
                TeaSampleEntity(
                    id = "t1",
                    nameRu = "Чай",
                    nameZh = null,
                    pinyin = null,
                    nameEn = nameEn,
                    type = "GREEN",
                    origin = null,
                    shortBlurb = null,
                    notes = notes,
                ),
            ),
        )
    }

    @Test
    fun `updateTea with original preserves a concurrent enrichment fill on an untouched field`() = runBlocking {
        // The form was loaded when nameEn was still blank...
        seedTea(nameEn = null, notes = "исходно")
        val original = TeaMergeFields(nameRu = "Чай", nameZh = null, pinyin = null, nameEn = null, type = "GREEN", origin = null)

        // ...then enrichment resolved and filled nameEn in the background, before the user hit Save.
        dao.applyEnrichmentPatch(
            teaId = "t1",
            candidateNameRu = null,
            candidateNameZh = null,
            candidatePinyin = null,
            candidateNameEn = "Green tea",
            type = "GREEN",
            candidateOrigin = null,
            candidateShortBlurb = null,
            ref = CatalogRefEntity(id = 1, type = "GREEN", fetchedAtEpochMs = 1),
            state = "DONE",
        )

        // The user's save only ever touched `notes` — nameEn in their (stale) form is still blank.
        val edited = original.copy()
        dao.updateTea(
            teaId = "t1",
            edited = edited,
            original = original,
            notes = "обновлено",
            vendor = null,
            product = null,
            harvestYear = null,
            batch = null,
            grade = null,
            flavors = emptyList(),
            purchases = emptyList(),
        )

        val row = dao.loadTeaRow("t1")!!
        assertEquals("Green tea", row.nameEn) // enrichment's fill survives
        assertEquals("обновлено", row.notes) // the user's actual edit still lands
    }

    @Test
    fun `updateTea with original still applies a deliberate user edit to a shared column`() = runBlocking {
        seedTea(nameEn = null)
        val original = TeaMergeFields(nameRu = "Чай", nameZh = null, pinyin = null, nameEn = null, type = "GREEN", origin = null)

        // Enrichment fills nameEn first...
        dao.applyEnrichmentPatch(
            teaId = "t1",
            candidateNameRu = null,
            candidateNameZh = null,
            candidatePinyin = null,
            candidateNameEn = "Green tea",
            type = "GREEN",
            candidateOrigin = null,
            candidateShortBlurb = null,
            ref = CatalogRefEntity(id = 1, type = "GREEN", fetchedAtEpochMs = 1),
            state = "DONE",
        )

        // ...but the user explicitly typed their own English name before saving — their edit must win.
        val edited = original.copy(nameEn = "My own name")
        dao.updateTea(
            teaId = "t1",
            edited = edited,
            original = original,
            notes = null,
            vendor = null,
            product = null,
            harvestYear = null,
            batch = null,
            grade = null,
            flavors = emptyList(),
            purchases = emptyList(),
        )

        assertEquals("My own name", dao.loadTeaRow("t1")!!.nameEn)
    }

    @Test
    fun `updateTea with original clears a shared column the user deliberately blanked (UX3-P2-12)`() = runBlocking {
        seedTea(nameEn = "Old name")
        val original = TeaMergeFields(nameRu = "Чай", nameZh = null, pinyin = null, nameEn = "Old name", type = "GREEN", origin = null)

        // The user erased the English name they had loaded — clearing to blank must WIN (edited differs
        // from original), not be swallowed as a no-op by the diff-vs-original merge.
        val edited = original.copy(nameEn = null)
        dao.updateTea(
            teaId = "t1",
            edited = edited,
            original = original,
            notes = null,
            vendor = null,
            product = null,
            harvestYear = null,
            batch = null,
            grade = null,
            flavors = emptyList(),
            purchases = emptyList(),
        )

        assertEquals(null, dao.loadTeaRow("t1")!!.nameEn)
    }

    @Test
    fun `updateTea with no original falls back to an unconditional overwrite`() = runBlocking {
        seedTea(nameEn = "Old")

        dao.updateTea(
            teaId = "t1",
            edited = TeaMergeFields(nameRu = "Чай", nameZh = null, pinyin = null, nameEn = null, type = "GREEN", origin = null),
            original = null,
            notes = null,
            vendor = null,
            product = null,
            harvestYear = null,
            batch = null,
            grade = null,
            flavors = emptyList(),
            purchases = emptyList(),
        )

        assertEquals(null, dao.loadTeaRow("t1")!!.nameEn)
    }

    @Test
    fun `updateFlavors rewrites only the flavor rows`() = runBlocking {
        seedTea(notes = "заметка")
        dao.insertFlavors(listOf(FlavorEntity(teaId = "t1", dimension = "BITTERNESS", intensity = 2, position = 0)))

        dao.updateFlavors(
            "t1",
            listOf(FlavorEntity(teaId = "t1", dimension = "SWEETNESS", intensity = 4, position = 0)),
        )

        val row = dao.loadTea("t1")!!
        assertEquals(listOf("SWEETNESS"), row.flavors.map { it.dimension })
        assertEquals("заметка", row.tea.notes) // untouched by the flavor-only write
    }
}
