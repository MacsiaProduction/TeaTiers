package com.macsia.teatiers.data.update

import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import java.security.MessageDigest

/** Lowercase-hex SHA-256 helpers used by the updater to pin a downloaded APK + its signing cert. */
internal object Sha256 {

    fun ofBytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Streams the file via Okio so a multi-MB APK isn't held in memory. */
    fun ofFile(file: File): String {
        HashingSink.sha256(blackholeSink()).use { hashingSink ->
            file.source().buffer().use { source -> source.readAll(hashingSink) }
            return hex(hashingSink.hash.toByteArray())
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
