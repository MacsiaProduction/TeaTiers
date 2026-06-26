package com.macsia.teatiers.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.CatalogRefEntity
import com.macsia.teatiers.data.db.FlavorEntity
import com.macsia.teatiers.data.db.PhotoEntity
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.PurchaseLocationEntity
import com.macsia.teatiers.data.db.SeedEntities
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaSampleEntity
import com.macsia.teatiers.data.db.TierEntity
import com.macsia.teatiers.data.repository.FakePhotoStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the [BackupManager] import orchestration end-to-end against a real backup archive, with the
 * Android I/O surface (SAF [Uri] + [ContentResolver]) mocked and the DB/[FakePhotoStore] faked. The
 * focus is the review 2026-06-18 fix: a destructive replace-all must **sweep the orphaned prior
 * photo corpus** (PhotoStore.reconcile), keeping only the files the freshly-imported rows reference.
 */
class BackupManagerTest {

    @TempDir
    lateinit var cacheDir: File

    @TempDir
    lateinit var filesDir: File

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // android.util.Log is unmocked on the JVM; stub it so the reject/failure log paths don't throw.
    @BeforeEach
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun unmockAndroidLog() = unmockkStatic(Log::class)

    private fun snapshot() = SeedEntities(
        boards = listOf(BoardEntity("b1", "Daily", 0)),
        tiers = listOf(TierEntity("t1", "b1", "S", 0, 0xFFFF8800)),
        catalogRefs = listOf(CatalogRefEntity(id = 42L, type = "OOLONG", fetchedAtEpochMs = 0L)),
        teas = listOf(
            TeaSampleEntity(
                "tea1", "Те Гуань Инь", null, "tie guan yin", null, "OOLONG", "Fujian", null, "nice",
                catalogTeaId = 42L, enrichmentState = "DONE",
            ),
        ),
        placements = listOf(PlacementEntity("p1", "b1", "tea1", "t1", 0)),
        flavors = listOf(FlavorEntity("tea1", "FLORAL", 4, 0)),
        purchases = listOf(PurchaseLocationEntity("pl1", "tea1", 0, "TEXT", null, "market")),
        photos = listOf(
            PhotoEntity("ph1", "tea1", "/data/tea_photos/abc.png", 0, "USER", null, null, 123L),
            PhotoEntity("ph2", "tea1", "https://x/y.jpg", 1, "CATALOG", "CC", "https://x", 456L),
        ),
    )

    /** A valid backup zip carrying the snapshot + the [photos] bundled file entries (name -> bytes). */
    private fun archiveBytes(photos: Map<String, ByteArray> = mapOf("ph1.png" to byteArrayOf(1, 2, 3))): ByteArray =
        archiveOf(snapshot().toBundle(exportedAtEpochMs = 1_000L, appVersion = "0.1.0"), photos)

    /** Zips an arbitrary [bundle] (+ [photos]) — lets a test forge e.g. a newer formatVersion. */
    private fun archiveOf(
        bundle: BackupBundle,
        photos: Map<String, ByteArray> = mapOf("ph1.png" to byteArrayOf(1, 2, 3)),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val sources = photos.map { (name, bytes) -> BackupArchive.PhotoSource(name) { bytes.inputStream() } }
        BackupArchive.write(out, json.encodeToString(bundle), sources)
        return out.toByteArray()
    }

    private fun managerReading(bytes: ByteArray, dao: TeaDao, photoStore: FakePhotoStore): Pair<BackupManager, Uri> {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver> {
            every { openAssetFileDescriptor(uri, "r") } returns null // skip the cheap size guard
            every { openInputStream(uri) } returns bytes.inputStream()
        }
        val context = mockk<Context> {
            every { contentResolver } returns resolver
            every { cacheDir } returns this@BackupManagerTest.cacheDir // import streams into a staging dir here
        }
        return BackupManager(context, dao, photoStore) to uri
    }

    @Test
    fun `import restores the db and sweeps the orphaned old photo corpus`() = runTest {
        val dao = mockk<TeaDao>(relaxed = true)
        // Pre-seed a file the prior corpus owned; the import must sweep it.
        val photoStore = FakePhotoStore().apply { onDisk += "/fake/old-orphan.jpg" }
        val (manager, uri) = managerReading(archiveBytes(), dao, photoStore)

        val result = manager.importFrom(uri)

        assertTrue(result is BackupResult.Imported, "expected Imported, got $result")
        assertEquals(1, (result as BackupResult.Imported).teaCount)

        // The restored rows were written in one replace-all...
        val written = slot<SeedEntities>()
        coVerify { dao.replaceAll(capture(written)) }
        assertEquals(2, written.captured.photos.size)

        // ...and the orphan was swept while the freshly-imported file is kept (the url photo, having
        // no local path, is correctly excluded from the keep-set).
        val importedPath = written.captured.photos.first { it.id == "ph1" }.uri
        assertTrue(photoStore.reconcileCalls.last() == setOf(importedPath), "keep-set should be only the local file")
        assertTrue(photoStore.deleted.contains("/fake/old-orphan.jpg"), "orphan must be swept")
        assertFalse(photoStore.onDisk.contains("/fake/old-orphan.jpg"))
        assertTrue(photoStore.onDisk.contains(importedPath), "imported file must be kept")
    }

    @Test
    fun `a destructive import snapshots the current data, then restoreSafetyBackup undoes it`() = runTest {
        // teaCount > 0 -> the pre-import safety snapshot is taken (it's skipped on an empty DB). The
        // current corpus is snapshot() (its ph1 file is absent here, so only the url photo survives the
        // export filter); the incoming archive is a separate valid backup.
        val dao = mockk<TeaDao>(relaxed = true)
        coEvery { dao.teaCount() } returns 1
        coEvery { dao.exportSnapshot() } returns snapshot()
        val photoStore = FakePhotoStore()
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver> {
            every { openAssetFileDescriptor(uri, "r") } returns null
            every { openInputStream(uri) } returns archiveBytes().inputStream()
        }
        val context = mockk<Context> {
            every { contentResolver } returns resolver
            every { cacheDir } returns this@BackupManagerTest.cacheDir
            every { filesDir } returns this@BackupManagerTest.filesDir
        }
        val manager = BackupManager(context, dao, photoStore)

        assertFalse(manager.hasSafetyBackup(), "no snapshot before the first restore")
        assertTrue(manager.importFrom(uri) is BackupResult.Imported)
        assertTrue(manager.hasSafetyBackup(), "the destructive import must leave a safety snapshot")

        // The snapshot is a real, restorable backup of the pre-import corpus (1 tea).
        val undone = manager.restoreSafetyBackup()
        assertTrue(undone is BackupResult.Imported, "expected the snapshot to restore, got $undone")
        assertEquals(1, (undone as BackupResult.Imported).teaCount)
    }

    @Test
    fun `restoreSafetyBackup with no snapshot is InvalidFile`() = runTest {
        val dao = mockk<TeaDao>(relaxed = true)
        val context = mockk<Context> {
            every { filesDir } returns this@BackupManagerTest.filesDir
            every { cacheDir } returns this@BackupManagerTest.cacheDir
        }
        val manager = BackupManager(context, dao, FakePhotoStore())

        assertTrue(manager.restoreSafetyBackup() is BackupResult.InvalidFile)
    }

    @Test
    fun `import of a zip without the backup json is InvalidFile and writes nothing`() = runTest {
        // A zip with only a photo entry (no backup.json) parses to a blank bundle -> InvalidFile,
        // without hitting any android.util.Log branch.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "x.png"))
            zip.write(byteArrayOf(7))
            zip.closeEntry()
        }
        val dao = mockk<TeaDao>(relaxed = true)
        val photoStore = FakePhotoStore()
        val (manager, uri) = managerReading(out.toByteArray(), dao, photoStore)

        val result = manager.importFrom(uri)

        assertTrue(result is BackupResult.InvalidFile, "expected InvalidFile, got $result")
        coVerify(exactly = 0) { dao.replaceAll(any()) }
        assertTrue(photoStore.reconcileCalls.isEmpty(), "a rejected import must not sweep photos")
    }

    @Test
    fun `import rejects a backup that omits a declared photo and leaves data untouched`() = runTest {
        // The snapshot's ph1 declares the bundled file "ph1.png"; build an archive that OMITS it.
        // Atomic restore (review P1-5): a missing declared photo rejects the WHOLE import — the old
        // code silently dropped it and half-restored the tea without its photo. Surfaced as a distinct
        // IncompleteArchive (not the generic "not a backup") so the user knows the file is truncated.
        val dao = mockk<TeaDao>(relaxed = true)
        val photoStore = FakePhotoStore()
        val (manager, uri) = managerReading(archiveBytes(photos = emptyMap()), dao, photoStore)

        val result = manager.importFrom(uri)

        assertTrue(result is BackupResult.IncompleteArchive, "expected IncompleteArchive for a missing photo, got $result")
        coVerify(exactly = 0) { dao.replaceAll(any()) }
        assertTrue(photoStore.reconcileCalls.isEmpty(), "a rejected import must not touch the live photo store")
    }

    @Test
    fun `import of a newer-format backup is IncompatibleVersion and writes nothing`() = runTest {
        // A backup whose formatVersion is ahead of this build can't be faithfully restored — the user
        // is told to update, not that their (valid) file isn't a backup.
        val newer = snapshot().toBundle(exportedAtEpochMs = 1_000L, appVersion = "9.9.9")
            .copy(formatVersion = BACKUP_FORMAT_VERSION + 1)
        val dao = mockk<TeaDao>(relaxed = true)
        val photoStore = FakePhotoStore()
        val (manager, uri) = managerReading(archiveOf(newer), dao, photoStore)

        val result = manager.importFrom(uri)

        assertTrue(result is BackupResult.IncompatibleVersion, "expected IncompatibleVersion, got $result")
        coVerify(exactly = 0) { dao.replaceAll(any()) }
        assertTrue(photoStore.reconcileCalls.isEmpty(), "a rejected import must not touch the live photo store")
    }

    @Test
    fun `export drops a missing-on-disk photo so its own archive still round-trips (review N1)`() = runTest {
        // snapshot()'s ph1 points at a path that does not exist here. Export must omit it from BOTH the
        // zip AND the declaring JSON so the strict importer accepts the archive (not reject it whole).
        val dao = mockk<TeaDao>(relaxed = true)
        coEvery { dao.exportSnapshot() } returns snapshot()
        val photoStore = FakePhotoStore()
        val exported = ByteArrayOutputStream()
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver> {
            every { openOutputStream(uri) } returns exported
            every { openAssetFileDescriptor(uri, "r") } returns null
            every { openInputStream(uri) } answers { exported.toByteArray().inputStream() }
        }
        val context = mockk<Context> {
            every { contentResolver } returns resolver
            every { cacheDir } returns this@BackupManagerTest.cacheDir
        }
        val manager = BackupManager(context, dao, photoStore)

        assertTrue(manager.exportTo(uri) is BackupResult.Exported)
        val result = manager.importFrom(uri)

        assertTrue(result is BackupResult.Imported, "a stale-photo export must re-import, got $result")
        val written = slot<SeedEntities>()
        coVerify { dao.replaceAll(capture(written)) }
        // The missing file photo (ph1) is gone from the archive; the url photo (ph2) survives.
        assertEquals(listOf("ph2"), written.captured.photos.map { it.id })
    }

    @Test
    fun `undo is one-shot — a second restoreSafetyBackup finds no snapshot (finding 1)`() = runTest {
        val dao = mockk<TeaDao>(relaxed = true)
        coEvery { dao.teaCount() } returns 1
        coEvery { dao.exportSnapshot() } returns snapshot()
        val photoStore = FakePhotoStore()
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver> {
            every { openAssetFileDescriptor(uri, "r") } returns null
            every { openInputStream(uri) } returns archiveBytes().inputStream()
        }
        val context = mockk<Context> {
            every { contentResolver } returns resolver
            every { cacheDir } returns this@BackupManagerTest.cacheDir
            every { filesDir } returns this@BackupManagerTest.filesDir
        }
        val manager = BackupManager(context, dao, photoStore)

        assertTrue(manager.importFrom(uri) is BackupResult.Imported)
        assertTrue(manager.hasSafetyBackup())

        // First undo consumes the snapshot...
        assertTrue(manager.restoreSafetyBackup() is BackupResult.Imported)
        assertFalse(manager.hasSafetyBackup(), "the undo must consume its one-shot snapshot")
        // ...so a second tap can't re-restore a now-stale snapshot over data added since.
        assertTrue(manager.restoreSafetyBackup() is BackupResult.InvalidFile)
    }

    @Test
    fun `a failed import cleans partial photos and leaves no stale safety backup (findings 14A 14B)`() = runTest {
        // Two file-backed photos; the SECOND fails to import. The restore must abort with no DB swap,
        // delete the first photo it already streamed into the live store (orphan), and NOT leave a
        // safety snapshot behind for a restore that never changed anything.
        val dao = mockk<TeaDao>(relaxed = true)
        coEvery { dao.teaCount() } returns 1
        coEvery { dao.exportSnapshot() } returns snapshot()
        val twoFilePhotos = snapshot().copy(
            photos = listOf(
                PhotoEntity("ph1", "tea1", "/data/tea_photos/a.png", 0, "USER", null, null, 1L),
                PhotoEntity("phB", "tea1", "/data/tea_photos/b.jpg", 1, "USER", null, null, 2L),
            ),
        )
        val bundle = twoFilePhotos.toBundle(exportedAtEpochMs = 1_000L, appVersion = "0.1.0")
        val bytes = archiveOf(bundle, photos = mapOf("ph1.png" to byteArrayOf(1), "phB.jpg" to byteArrayOf(2)))
        val photoStore = FakePhotoStore().apply { importFailures += "phB.jpg" }
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver> {
            every { openAssetFileDescriptor(uri, "r") } returns null
            every { openInputStream(uri) } returns bytes.inputStream()
        }
        val context = mockk<Context> {
            every { contentResolver } returns resolver
            every { cacheDir } returns this@BackupManagerTest.cacheDir
            every { filesDir } returns this@BackupManagerTest.filesDir
        }
        val manager = BackupManager(context, dao, photoStore)

        val result = manager.importFrom(uri)

        assertTrue(result is BackupResult.Failed, "a photo import failure must fail the whole restore, got $result")
        coVerify(exactly = 0) { dao.replaceAll(any()) }
        assertFalse(manager.hasSafetyBackup(), "a failed/no-op import must not leave a misleading undo")
        // The first photo, already streamed into the live store, is cleaned up rather than orphaned.
        assertEquals(1, photoStore.deleted.size, "the one already-written photo must be deleted")
        assertTrue(photoStore.deleted.first().startsWith("/fake/imported-"), "deleted the staged import file")
    }
}
