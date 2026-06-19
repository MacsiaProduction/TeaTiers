package com.macsia.teatiers.data.update

import java.io.File
import java.security.MessageDigest

/** Lowercase-hex SHA-256 helpers used by the updater to pin a downloaded APK + its signing cert. */
internal object Sha256 {

    fun ofBytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Streams the file so a multi-MB APK isn't held in memory. */
    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
