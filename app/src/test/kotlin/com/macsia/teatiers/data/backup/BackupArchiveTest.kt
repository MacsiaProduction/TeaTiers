package com.macsia.teatiers.data.backup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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
}
