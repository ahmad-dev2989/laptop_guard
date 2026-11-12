package com.ahmad.laptopguard.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {

    @Test
    fun parseQrUri_preservesDeviceNameAndKeys() {
        val uri = "laptopguard://pair?v=1&id=0123456789abcdef&key=00112233445566778899aabbccddeeff&name=Lenovo%20ThinkBook%20%231"
        val parsed = PairingCode.tryParse(uri)

        assertNotNull(parsed)
        assertEquals(1, parsed!!.version)
        assertEquals("0123456789abcdef", parsed.deviceIdHex)
        assertEquals("00112233445566778899aabbccddeeff", parsed.secretHex)
        assertEquals("Lenovo ThinkBook #1", parsed.deviceName)
    }

    @Test
    fun parseManualCode_toleratesSpacesCaseAndDashes() {
        val code = "  lg1 - 0123 4567 89AB CDEF - 00112233 44556677 8899AABB CCDDEEFF "
        val parsed = PairingCode.tryParse(code)

        assertNotNull(parsed)
        assertEquals("0123456789abcdef", parsed!!.deviceIdHex)
        assertEquals("00112233445566778899aabbccddeeff", parsed.secretHex)
        assertEquals("My Laptop", parsed.deviceName)
    }

    @Test
    fun parse_rejectsMalformedInputs() {
        assertNull(PairingCode.tryParse(""))
        assertNull(PairingCode.tryParse("https://example.com"))
        assertNull(PairingCode.tryParse("laptopguard://pair?v=2&id=0123456789abcdef&key=00112233445566778899aabbccddeeff"))
        assertNull(PairingCode.tryParse("LG1-1234"))
    }
}

