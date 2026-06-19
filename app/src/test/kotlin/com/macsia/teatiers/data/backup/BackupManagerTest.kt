package com.macsia.teatiers.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.FlavorEntity
import com.macsia.teatiers.data.db.PhotoEntity
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.PurchaseLocationEntity
import com.macsia.teatiers.data.db.SeedEntities
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaEntity
import com.macsia.teatiers.data.db.TierEntity
import com.macsia.teatiers.data.repository.FakePhotoStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the [BackupManager] import orchestration end-to-end against a real backup archive, with the
 * Android I/O surface (SAF [Uri] + [ContentResolver]) mocked and the DB/[FakePhotoStore] faked. The
 * focus is the review 2026-06-18 fix: a destructive replace-all must **sweep the orphaned prior
 * photo corpus** (PhotoStore.reconcile), keeping only the files the freshly-imported rows reference.
 */
class BackupManagerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun snapshot() = SeedEntities(
        boards = listOf(BoardEntity("b1", "Daily", 0)),
        tiers = listOf(TierEntity("t1", "b1", "S", 0, 0xFFFF8800)),
        teas = listOf(
            TeaEntity(
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

    /** A valid backup zip carrying the snapshot + one bundled file photo. */
    private fun archiveBytes(): ByteArray {
        val bundle = snapshot().toBundle(exportedAtEpochMs = 1_000L, appVersion = "0.1.0")
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, json.encodeToString(bundle), mapOf("ph1.png" to byteArrayOf(1, 2, 3)))
        return out.toByteArray()
    }

    private fun managerReading(bytes: ByteArray, dao: TeaDao, photoStore: FakePhotoStore): Pair<BackupManager, Uri> {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver> {
            every { openAssetFileDescriptor(uri, "r") } returns null // skip the cheap size guard
            every { openInputStream(uri) } returns bytes.inputStream()
        }
        val context = mockk<Context> { every { contentResolver } returns resolver }
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
}
