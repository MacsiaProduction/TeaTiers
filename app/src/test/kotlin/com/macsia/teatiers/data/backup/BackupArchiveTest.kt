package com.macsia.teatiers.data.backup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    @TempDir
    lateinit var tmp: File
    private var seq = 0

    private fun sources(vararg pairs: Pair<String, ByteArray>): List<BackupArchive.PhotoSource> =
        pairs.map { (name, bytes) -> BackupArchive.PhotoSource(name) { bytes.inputStream() } }

    private fun extract(bytes: ByteArray, limits: BackupArchive.Limits = BackupArchive.Limits()): BackupArchive.Extracted =
        BackupArchive.extractTo(bytes.inputStream(), File(tmp, "stage-${seq++}"), limits)

    @Test
    fun `write then extract round-trips the json and photo bytes`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, """{"k":1}""", sources("a.png" to byteArrayOf(1, 2, 3), "b.jpg" to byteArrayOf(9)))

        val extracted = extract(out.toByteArray())
        assertEquals("""{"k":1}""", extracted.json)
        assertArrayEquals(byteArrayOf(1, 2, 3), extracted.photoFiles["a.png"]!!.readBytes())
        assertArrayEquals(byteArrayOf(9), extracted.photoFiles["b.jpg"]!!.readBytes())
    }

    @Test
    fun `a zip without the json entry yields blank json but still extracts photos`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "x.png"))
            zip.write(byteArrayOf(7))
            zip.closeEntry()
        }
        val extracted = extract(out.toByteArray())
        assertTrue(extracted.json.isBlank())
        assertArrayEquals(byteArrayOf(7), extracted.photoFiles["x.png"]!!.readBytes())
    }

    @Test
    fun `extract rejects a photo larger than the per-photo cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{}", sources("big.jpg" to ByteArray(50)))
        assertThrows(BackupArchive.TooLargeException::class.java) {
            extract(out.toByteArray(), BackupArchive.Limits(maxPhotoBytes = 10))
        }
    }

    @Test
    fun `extract rejects more photos than the count cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{}", sources("a.jpg" to byteArrayOf(1), "b.jpg" to byteArrayOf(2), "c.jpg" to byteArrayOf(3)))
        assertThrows(BackupArchive.TooLargeException::class.java) {
            extract(out.toByteArray(), BackupArchive.Limits(maxPhotoCount = 2))
        }
    }

    @Test
    fun `extract rejects when cumulative uncompressed size exceeds the total cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{}", sources("a.jpg" to ByteArray(8), "b.jpg" to ByteArray(8)))
        assertThrows(BackupArchive.TooLargeException::class.java) {
            extract(out.toByteArray(), BackupArchive.Limits(maxTotalBytes = 10))
        }
    }

    @Test
    fun `extract rejects a json entry larger than the json cap`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, """{"k":"aaaaaaaaaaaaaaaaaaaa"}""", emptyList())
        assertThrows(BackupArchive.TooLargeException::class.java) {
            extract(out.toByteArray(), BackupArchive.Limits(maxJsonBytes = 5))
        }
    }

    @Test
    fun `extract skips nested or path-traversal photo names`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "../evil.png")); zip.write(byteArrayOf(1)); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "sub/ok.png")); zip.write(byteArrayOf(2)); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BACKUP_PHOTO_DIR + "good.png")); zip.write(byteArrayOf(3)); zip.closeEntry()
        }
        val extracted = extract(out.toByteArray())
        assertEquals(setOf("good.png"), extracted.photoFiles.keys)
        assertArrayEquals(byteArrayOf(3), extracted.photoFiles["good.png"]!!.readBytes())
    }
}
