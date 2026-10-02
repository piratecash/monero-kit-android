package com.m2049r.xmrwallet.model

import com.piratecash.monero.signer.HardwareWalletErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletStatusTest {

    @Test
    fun constructor_legacySignature_hasNoHardwareError() {
        val status = Wallet.Status(1, "error")

        assertNull(status.hardwareWalletError)
    }

    @Test
    fun constructor_hardwareErrorCode_exposesTypedError() {
        val status = Wallet.Status(
            1,
            "wrong wallet",
            HardwareWalletErrorCode.WrongWallet.code,
        )

        assertEquals(HardwareWalletErrorCode.WrongWallet, status.hardwareWalletError)
    }
}
