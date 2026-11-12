package com.ahmad.laptopguard.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ParsedBeacon(
    val version: Int,
    val deviceId: ByteArray,
    val statusFlags: Int,
    val batteryPercent: Int,
    val nonce: ByteArray,
    val authTag: ByteArray
) {
    val isTravelActive: Boolean get() = (statusFlags and BeaconPacket.FLAG_TRAVEL_ACTIVE) != 0
    val isKeepAwakeEnabled: Boolean get() = (statusFlags and BeaconPacket.FLAG_KEEP_AWAKE) != 0
    val isAcPowerConnected: Boolean get() = (statusFlags and BeaconPacket.FLAG_AC_POWER) != 0
}

/**
 * Builds and verifies the shared 23-byte BLE Manufacturer Specific Data payload defined in PROTOCOL.md.
 */
object BeaconPacket {
    const val COMPANY_ID = 0xFFFF
    const val PROTOCOL_VERSION = 0x01
    const val MAGIC_0: Byte = 'L'.code.toByte()
    const val MAGIC_1: Byte = 'G'.code.toByte()
    const val PHONE_MAGIC_1: Byte = 'A'.code.toByte()

    const val DEVICE_ID_LENGTH = 8
    const val SECRET_LENGTH = 16
    const val NONCE_LENGTH = 4
    const val AUTH_TAG_LENGTH = 6
    const val SIGNED_BODY_LENGTH = 17
    const val PAYLOAD_LENGTH = SIGNED_BODY_LENGTH + AUTH_TAG_LENGTH // 23 bytes
    const val HARDWARE_PREFIX_LENGTH = 11 // magic (2) + version (1) + deviceId (8)

    const val FLAG_TRAVEL_ACTIVE = 1 shl 0
    const val FLAG_KEEP_AWAKE = 1 shl 1
    const val FLAG_AC_POWER = 1 shl 2

    const val PHONE_FLAG_GUARD_ACTIVE = 1 shl 0
    const val PHONE_FLAG_PAIRING_HANDSHAKE = 1 shl 1

    fun buildPayload(
        deviceId: ByteArray,
        secret: ByteArray,
        statusFlags: Int,
        batteryPercent: Int,
        nonce: ByteArray,
        magic1: Byte = MAGIC_1
    ): ByteArray {
        require(deviceId.size == DEVICE_ID_LENGTH) { "Device ID must be $DEVICE_ID_LENGTH bytes." }
        require(secret.size == SECRET_LENGTH) { "Secret must be $SECRET_LENGTH bytes." }
        require(nonce.size == NONCE_LENGTH) { "Nonce must be $NONCE_LENGTH bytes." }

        val payload = ByteArray(PAYLOAD_LENGTH)
        payload[0] = MAGIC_0
        payload[1] = magic1
        payload[2] = PROTOCOL_VERSION.toByte()
        deviceId.copyInto(payload, destinationOffset = 3)
        payload[11] = (statusFlags and 0xFF).toByte()
        payload[12] = (batteryPercent and 0xFF).toByte()
        nonce.copyInto(payload, destinationOffset = 13)

        val mac = computeHmacPrefix(secret, payload)
        mac.copyInto(payload, destinationOffset = SIGNED_BODY_LENGTH)
        return payload
    }

    /**
     * Compact 3-byte hardware ScanFilter prefix ('L','G',0x01) compatible across all Android BLE chipsets.
     */
    fun buildLaptopHeaderPrefix(): ByteArray = byteArrayOf(MAGIC_0, MAGIC_1, PROTOCOL_VERSION.toByte())

    fun buildLaptopHeaderMask(): ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    /**
     * Builds the static 11-byte manufacturer prefix (magic + version + deviceId).
     */
    fun buildFilterPrefix(deviceId: ByteArray): ByteArray {
        require(deviceId.size == DEVICE_ID_LENGTH) { "Device ID must be $DEVICE_ID_LENGTH bytes." }
        val prefix = ByteArray(HARDWARE_PREFIX_LENGTH)
        prefix[0] = MAGIC_0
        prefix[1] = MAGIC_1
        prefix[2] = PROTOCOL_VERSION.toByte()
        deviceId.copyInto(prefix, destinationOffset = 3)
        return prefix
    }

    fun buildFilterMask(): ByteArray = ByteArray(HARDWARE_PREFIX_LENGTH) { 0xFF.toByte() }

    /**
     * Validates magic bytes, version, device ID, and HMAC-SHA256 tag in constant time.
     */
    fun tryVerifyAndParse(
        payload: ByteArray,
        expectedDeviceId: ByteArray,
        secret: ByteArray,
        expectedMagic1: Byte = MAGIC_1
    ): ParsedBeacon? {
        if (payload.size != PAYLOAD_LENGTH ||
            expectedDeviceId.size != DEVICE_ID_LENGTH ||
            secret.size != SECRET_LENGTH
        ) {
            return null
        }

        if (payload[0] != MAGIC_0 || payload[1] != expectedMagic1 || (payload[2].toInt() and 0xFF) != PROTOCOL_VERSION) {
            return null
        }

        val packetDeviceId = payload.copyOfRange(3, 3 + DEVICE_ID_LENGTH)
        if (!MessageDigest.isEqual(packetDeviceId, expectedDeviceId)) {
            return null
        }

        val expectedTag = computeHmacPrefix(secret, payload)
        val actualTag = payload.copyOfRange(SIGNED_BODY_LENGTH, PAYLOAD_LENGTH)
        if (!MessageDigest.isEqual(actualTag, expectedTag)) {
            return null
        }

        return ParsedBeacon(
            version = payload[2].toInt() and 0xFF,
            deviceId = packetDeviceId,
            statusFlags = payload[11].toInt() and 0xFF,
            batteryPercent = payload[12].toInt() and 0xFF,
            nonce = payload.copyOfRange(13, 13 + NONCE_LENGTH),
            authTag = actualTag
        )
    }

    private fun computeHmacPrefix(secret: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(payload, 0, SIGNED_BODY_LENGTH)
        return mac.doFinal().copyOfRange(0, AUTH_TAG_LENGTH)
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length." }
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
