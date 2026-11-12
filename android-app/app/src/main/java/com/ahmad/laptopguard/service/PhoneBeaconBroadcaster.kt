package com.ahmad.laptopguard.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.ahmad.laptopguard.protocol.BeaconPacket
import com.ahmad.laptopguard.protocol.PairedLaptop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Broadcasts signed LA (0xFFFF) BLE packets from the phone so the Windows Laptop Guard app
 * confirms pairing immediately and shows live phone connection status.
 */
class PhoneBeaconBroadcaster(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val random = SecureRandom()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeCallback: AdvertiseCallback? = null
    private var refreshJob: Job? = null
    private var handshakeUntilMs: Long = 0L

    fun start(laptop: PairedLaptop, pairingHandshakeBurst: Boolean) {
        if (pairingHandshakeBurst) {
            handshakeUntilMs = SystemClock.elapsedRealtime() + 15_000L
        }

        if (refreshJob?.isActive == true) {
            publishOnce(laptop)
            return
        }

        publishOnce(laptop)
        refreshJob = scope.launch {
            while (isActive) {
                delay(10_000L)
                publishOnce(laptop)
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        stopCurrentAdvertise()
    }

    @SuppressLint("MissingPermission")
    private fun publishOnce(laptop: PairedLaptop) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) return

        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return
        stopCurrentAdvertise()

        val inHandshake = SystemClock.elapsedRealtime() < handshakeUntilMs
        var flags = BeaconPacket.PHONE_FLAG_GUARD_ACTIVE
        if (inHandshake) {
            flags = flags or BeaconPacket.PHONE_FLAG_PAIRING_HANDSHAKE
        }

        val nonce = ByteArray(BeaconPacket.NONCE_LENGTH).also(random::nextBytes)
        val payload = BeaconPacket.buildPayload(
            deviceId = laptop.deviceIdBytes(),
            secret = laptop.secretBytes(),
            statusFlags = flags,
            batteryPercent = readPhoneBatteryPercent(),
            nonce = nonce,
            magic1 = BeaconPacket.PHONE_MAGIC_1
        )

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (inHandshake) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_BALANCED
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(BeaconPacket.COMPANY_ID, payload)
            .build()

        val callback = object : AdvertiseCallback() {}
        try {
            leAdvertiser.startAdvertising(settings, data, callback)
            advertiser = leAdvertiser
            activeCallback = callback
        } catch (_: SecurityException) {
            // Permission may not be granted; scanning still works without phone advertising.
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopCurrentAdvertise() {
        val cb = activeCallback ?: return
        activeCallback = null
        try {
            advertiser?.stopAdvertising(cb)
        } catch (_: Exception) {
        }
    }

    private fun readPhoneBatteryPercent(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (cap in 0..100) cap else 255
        } catch (_: Exception) {
            255
        }
    }
}

