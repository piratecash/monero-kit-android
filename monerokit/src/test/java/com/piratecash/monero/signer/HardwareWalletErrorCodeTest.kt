package com.piratecash.monero.signer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareWalletErrorCodeTest {
    @Test
    fun codes_publicContract_areContiguousAndRoundTrip() {
        val codes = HardwareWalletErrorCode.entries

        assertEquals((1..31).toList(), codes.map(HardwareWalletErrorCode::code))
        codes.forEach { code ->
            assertEquals(code, HardwareWalletErrorCode.fromCode(code.code))
            assertEquals(code, HardwareWalletErrorCode.fromCodeOrNull(code.code))
        }
    }

    @Test
    fun fromCode_unknownValue_mapsOnlyTolerantLookupToProtocol() {
        assertEquals(
            HardwareWalletErrorCode.Protocol,
            HardwareWalletErrorCode.fromCode(Int.MAX_VALUE),
        )
        assertNull(HardwareWalletErrorCode.fromCodeOrNull(Int.MAX_VALUE))
    }
}
