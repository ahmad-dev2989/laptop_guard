package com.ahmad.laptopguard.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconPacketTest {

    private val deviceId = "0123456789abcdef".hexToBytes()
    private val secret = "00112233445566778899aabbccddeeff".hexToBytes()
    private val nonce = "12345678".hexToBytes()

    @Test
    fun buildAndVerify_roundTrip_succeeds() {
        val flags = BeaconPacket.FLAG_TRAVEL_ACTIVE or BeaconPacket.FLAG_KEEP_AWAKE
        val payload = BeaconPacket.buildPayload(
            deviceId = deviceId,
            secret = secret,
            statusFlags = flags,
            batteryPercent = 84,
            nonce = nonce
        )

        assertEquals(BeaconPacket.PAYLOAD_LENGTH, payload.size)
        assertEquals('L'.code.toByte(), payload[0])
        assertEquals('G'.code.toByte(), payload[1])

        val parsed = BeaconPacket.tryVerifyAndParse(payload, deviceId, secret)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.version)
        assertArrayEquals(deviceId, parsed.deviceId)
        assertTrue(parsed.isTravelActive)
        assertTrue(parsed.isKeepAwakeEnabled)
        assertEquals(84, parsed.batteryPercent)
        assertArrayEquals(nonce, parsed.nonce)
    }

    @Test
    fun tryVerifyAndParse_rejectsAnyTamperedByte() {
        val payload = BeaconPacket.buildPayload(
            deviceId = deviceId,
            secret = secret,
            statusFlags = BeaconPacket.FLAG_TRAVEL_ACTIVE,
            batteryPercent = 70,
            nonce = nonce
        )

        for (index in payload.indices) {
            val copy = payload.copyOf()
            copy[index] = (copy[index].toInt() xor 0xFF).toByte()
            assertNull("Tampered byte at index $index should fail", BeaconPacket.tryVerifyAndParse(copy, deviceId, secret))
        }
    }

    @Test
    fun filterPrefix_matchesPayloadHeader() {
        val payload = BeaconPacket.buildPayload(deviceId, secret, BeaconPacket.FLAG_TRAVEL_ACTIVE, 90, nonce)
        val prefix = BeaconPacket.buildFilterPrefix(deviceId)
        val mask = BeaconPacket.buildFilterMask()

        assertEquals(BeaconPacket.HARDWARE_PREFIX_LENGTH, prefix.size)
        assertEquals(BeaconPacket.HARDWARE_PREFIX_LENGTH, mask.size)
        assertArrayEquals(payload.copyOfRange(0, BeaconPacket.HARDWARE_PREFIX_LENGTH), prefix)

        val headerPrefix = BeaconPacket.buildLaptopHeaderPrefix()
        assertArrayEquals(payload.copyOfRange(0, 3), headerPrefix)
    }

    @Test
    fun phoneAckPacket_usesLaMagicAndVerifies() {
        val ack = BeaconPacket.buildPayload(
            deviceId = deviceId,
            secret = secret,
            statusFlags = BeaconPacket.PHONE_FLAG_GUARD_ACTIVE or BeaconPacket.PHONE_FLAG_PAIRING_HANDSHAKE,
            batteryPercent = 88,
            nonce = nonce,
            magic1 = BeaconPacket.PHONE_MAGIC_1
        )

        assertNull(BeaconPacket.tryVerifyAndParse(ack, deviceId, secret, BeaconPacket.MAGIC_1))
        val parsed = BeaconPacket.tryVerifyAndParse(ack, deviceId, secret, BeaconPacket.PHONE_MAGIC_1)
        assertNotNull(parsed)
        assertEquals(88, parsed!!.batteryPercent)
    }
}

