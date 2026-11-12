package com.ahmad.laptopguard.protocol

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PairedLaptop(
    val version: Int,
    val deviceIdHex: String,
    val secretHex: String,
    val deviceName: String
) {
    fun deviceIdBytes(): ByteArray = deviceIdHex.hexToBytes()
    fun secretBytes(): ByteArray = secretHex.hexToBytes()
}

/**
 * Parses QR pairing URIs (laptopguard://pair?...) and LG1-... manual fallback codes.
 */
object PairingCode {
    private val WHITESPACE_AND_DASHES = Regex("[\\s-]+")
    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

    fun tryParse(rawInput: String?): PairedLaptop? {
        if (rawInput.isNullOrBlank()) return null
        val trimmed = rawInput.trim()

        return if (trimmed.startsWith("laptopguard://", ignoreCase = true)) {
            tryParseUri(trimmed)
        } else {
            tryParseManualCode(trimmed)
        }
    }

    private fun tryParseUri(rawUri: String): PairedLaptop? {
        val withoutScheme = rawUri.substringAfter("://", "")
        val hostPart = withoutScheme.substringBefore('?').trimEnd('/')
        if (!hostPart.equals("pair", ignoreCase = true)) return null

        val query = withoutScheme.substringAfter('?', "")
        if (query.isEmpty()) return null

        val params = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eqIdx = part.indexOf('=')
            if (eqIdx <= 0) continue
            val key = decodeUrlPart(part.substring(0, eqIdx)).lowercase()
            val value = decodeUrlPart(part.substring(eqIdx + 1))
            params[key] = value
        }

        val version = params["v"]?.toIntOrNull() ?: return null
        if (version != 1) return null

        val idHex = params["id"] ?: return null
        val secretHex = params["key"] ?: return null
        if (!isValidHex(idHex, BeaconPacket.DEVICE_ID_LENGTH * 2) ||
            !isValidHex(secretHex, BeaconPacket.SECRET_LENGTH * 2)
        ) {
            return null
        }

        val name = sanitizeName(params["name"])
        return PairedLaptop(
            version = 1,
            deviceIdHex = idHex.lowercase(),
            secretHex = secretHex.lowercase(),
            deviceName = name
        )
    }

    private fun tryParseManualCode(code: String): PairedLaptop? {
        val compact = WHITESPACE_AND_DASHES.replace(code, "").uppercase()
        if (!compact.startsWith("LG1")) return null

        val hexPart = compact.substring(3)
        val expectedLength = (BeaconPacket.DEVICE_ID_LENGTH + BeaconPacket.SECRET_LENGTH) * 2
        if (hexPart.length != expectedLength || !HEX_REGEX.matches(hexPart)) return null

        val idLen = BeaconPacket.DEVICE_ID_LENGTH * 2
        val idHex = hexPart.substring(0, idLen).lowercase()
        val secretHex = hexPart.substring(idLen).lowercase()

        return PairedLaptop(
            version = 1,
            deviceIdHex = idHex,
            secretHex = secretHex,
            deviceName = "My Laptop"
        )
    }

    private fun decodeUrlPart(value: String): String {
        return try {
            // Preserve literal '+' only if encoded, otherwise URLDecoder handles %XX cleanly.
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    private fun isValidHex(value: String, expectedLength: Int): Boolean {
        return value.length == expectedLength && HEX_REGEX.matches(value)
    }

    fun sanitizeName(name: String?): String {
        val cleaned = name?.trim().orEmpty()
        if (cleaned.isEmpty()) return "My Laptop"
        return if (cleaned.length > 32) cleaned.substring(0, 32) else cleaned
    }
}

