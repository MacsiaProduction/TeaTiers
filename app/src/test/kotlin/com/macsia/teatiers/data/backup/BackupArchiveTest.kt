package com.macsia.teatiers.data.backup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    @Test
    fun `write then read round-trips the json and photo bytes`() {
        val out = ByteArrayOutputStream()
        val photos = mapOf("a.png" to byteArrayOf(1, 2, 3), "b.jpg" to byteArrayOf(9))
        BackupArchive.write(out, """{"k":1}""", photos)

        val parsed = BackupArchive.read(out.toByteArray().inputStream())
        assertEquals("""{"k":1}""", parsed.json)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.photoBytes["a.png"])
        assertArrayEquals(byteArrayOf(9), parsed.photoBytes["b.jpg"])
    }

    @Test
    fun `a zip without the json entry yields blank json but still reads photos`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "x.png"))
            zip.write(byteArrayOf(7))
            zip.closeEntry()
        }
        val parsed = BackupArchive.read(out.toByteArray().inputStream())
        assertTrue(parsed.json.isBlank())
        assertArrayEquals(byteArrayOf(7), parsed.photoBytes["x.png"])
    }

    @Test
    fun `read rejects a photo larger than the per-photo cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{}", mapOf("big.jpg" to ByteArray(50)))
        assertThrows(BackupArchive.TooLargeException::class.java) {
            BackupArchive.read(out.toByteArray().inputStream(), BackupArchive.Limits(maxPhotoBytes = 10))
        }
    }

    @Test
    fun `read rejects more photos than the count cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{}", mapOf("a.jpg" to byteArrayOf(1), "b.jpg" to byteArrayOf(2), "c.jpg" to byteArrayOf(3)))
        assertThrows(BackupArchive.TooLargeException::class.java) {
            BackupArchive.read(out.toByteArray().inputStream(), BackupArchive.Limits(maxPhotoCount = 2))
        }
    }

    @Test
    fun `read rejects when cumulative uncompressed size exceeds the total cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{}", mapOf("a.jpg" to ByteArray(8), "b.jpg" to ByteArray(8)))
        assertThrows(BackupArchive.TooLargeException::class.java) {
            BackupArchive.read(out.toByteArray().inputStream(), BackupArchive.Limits(maxTotalBytes = 10))
        }
    }

    @Test
    fun `read rejects a json entry larger than the json cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, """{"k":"aaaaaaaaaaaaaaaaaaaa"}""", emptyMap())
        assertThrows(BackupArchive.TooLargeException::class.java) {
            BackupArchive.read(out.toByteArray().inputStream(), BackupArchive.Limits(maxJsonBytes = 5))
        }
    }

    @Test
    fun `read skips nested or path-traversal photo names`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "../evil.png")); zip.write(byteArrayOf(1)); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "sub/ok.png")); zip.write(byteArrayOf(2)); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "good.png")); zip.write(byteArrayOf(3)); zip.closeEntry()
        }
        val parsed = BackupArchive.read(out.toByteArray().inputStream())
        assertEquals(setOf("good.png"), parsed.photoBytes.keys)
        assertArrayEquals(byteArrayOf(3), parsed.photoBytes["good.png"])
    }
}
