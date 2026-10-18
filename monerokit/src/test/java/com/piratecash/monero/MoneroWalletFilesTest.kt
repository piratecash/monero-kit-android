package com.piratecash.monero

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MoneroWalletFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun root_emptyFilesDir_createsWalletsDirectory() {
        val filesDir = temp.newFolder("files")
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }

        val root = MoneroWalletFiles.root(context)

        assertEquals(File(filesDir, "wallets"), root)
        assertTrue(root.isDirectory)
    }

    @Test
    fun file_walletName_resolvesInsideRoot() {
        val filesDir = temp.newFolder("files")
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }

        assertEquals(File(filesDir, "wallets/xmr"), MoneroWalletFiles.file(context, "xmr"))
    }
}
