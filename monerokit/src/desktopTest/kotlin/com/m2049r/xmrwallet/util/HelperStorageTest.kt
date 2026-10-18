package com.m2049r.xmrwallet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HelperStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun getStorage_missingFolder_createsIt() {
        val base = temp.newFolder("base")

        val dir = Helper.getStorage(base, "wallets")

        assertEquals(File(base, "wallets"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun getStorage_existingFolder_keepsContents() {
        val base = temp.newFolder("base")
        val wallet = File(base, "wallets/xmr.keys").apply { parentFile.mkdirs(); writeText("keys") }

        Helper.getStorage(base, "wallets")

        assertEquals("keys", wallet.readText())
    }

    @Test
    fun getStorage_fileInTheWay_throws() {
        val base = temp.newFolder("base")
        File(base, "wallets").writeText("not a directory")

        assertThrows(IllegalStateException::class.java) { Helper.getStorage(base, "wallets") }
    }
}
