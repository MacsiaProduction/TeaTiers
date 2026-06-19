package com.macsia.teatiers.data.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class Sha256Test {

    // NIST/RFC test vectors so a refactor can't silently change the digest the updater pins on.
    @Test
    fun `known vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.ofBytes(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.ofBytes("abc".toByteArray()))
    }

    @Test
    fun `ofFile streams the same digest as ofBytes`(@TempDir dir: File) {
        val bytes = "the quick brown fox".toByteArray()
        val file = File(dir, "blob.bin").apply { writeBytes(bytes) }

        assertEquals(Sha256.ofBytes(bytes), Sha256.ofFile(file))
    }

    @Test
    fun `ofFile handles a payload larger than the read buffer`(@TempDir dir: File) {
        val bytes = ByteArray(200_000) { (it % 251).toByte() } // > the 64 KiB streaming buffer
        val file = File(dir, "big.bin").apply { writeBytes(bytes) }

        assertEquals(Sha256.ofBytes(bytes), Sha256.ofFile(file))
    }
}
