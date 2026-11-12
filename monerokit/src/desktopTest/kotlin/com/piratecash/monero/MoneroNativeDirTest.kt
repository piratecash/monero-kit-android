package com.piratecash.monero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MoneroNativeDirTest {
    private val nativeDir = Files.createTempDirectory("monero-native-dir")

    @Test
    fun loadFrom_nativeDirWithoutLibrary_failsNamingThePathWithoutExtracting() {
        val expected = nativeDir.resolve(System.mapLibraryName("monerujo")).toString()
        val extractedBefore = extractedCopies()

        val error = assertThrows(IllegalArgumentException::class.java) {
            MoneroNative.loadFrom(nativeDir.toString())
        }

        assertTrue(error.message.orEmpty().contains(expected))
        assertEquals(extractedBefore, extractedCopies())
    }

    @Test
    fun isHostSupported_nativeDirWithoutLibrary_returnsFalse() {
        assertFalse(MoneroNative.isHostSupported(nativeDir.toString()))
    }

    @Test
    fun isHostSupported_nativeDirWithLibrary_returnsTrue() {
        Files.createFile(nativeDir.resolve(System.mapLibraryName("monerujo")))

        assertTrue(MoneroNative.isHostSupported(nativeDir.toString()))
    }

    private fun extractedCopies(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("monero-kit") }.orEmpty().toSet()
}
