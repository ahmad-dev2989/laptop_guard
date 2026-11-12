package com.ahmad.laptopguard.data

import android.content.Context
import android.content.SharedPreferences
import com.ahmad.laptopguard.monitor.ProximityConfig
import com.ahmad.laptopguard.protocol.PairedLaptop
import com.ahmad.laptopguard.protocol.PairingCode

data class GuardSettings(
    val thresholdDbm: Int = -76,
    val holdSeconds: Float = 2.5f,
    val lostTimeoutSeconds: Float = 6.0f,
    val boostAlarmVolume: Boolean = true
) {
    fun toProximityConfig(): ProximityConfig = ProximityConfig(
        thresholdDbm = thresholdDbm,
        holdSeconds = holdSeconds,
        lostTimeoutSeconds = lostTimeoutSeconds
    )
}

class GuardPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadPairedLaptop(): PairedLaptop? {
        val idHex = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val secretHex = prefs.getString(KEY_SECRET, null) ?: return null
        val name = prefs.getString(KEY_DEVICE_NAME, "My Laptop") ?: "My Laptop"
        val candidate = "laptopguard://pair?v=1&id=$idHex&key=$secretHex&name=$name"
        return PairingCode.tryParse(candidate)?.copy(deviceName = PairingCode.sanitizeName(name))
    }

    fun savePairedLaptop(laptop: PairedLaptop) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, laptop.deviceIdHex)
            .putString(KEY_SECRET, laptop.secretHex)
            .putString(KEY_DEVICE_NAME, laptop.deviceName)
            .apply()
    }

    fun clearPairedLaptop() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_SECRET)
            .remove(KEY_DEVICE_NAME)
            .apply()
    }

    fun loadSettings(): GuardSettings = GuardSettings(
        thresholdDbm = prefs.getInt(KEY_THRESHOLD_DBM, -76).coerceIn(-98, -45),
        holdSeconds = prefs.getFloat(KEY_HOLD_SECONDS, 2.5f).coerceIn(1.0f, 8.0f),
        lostTimeoutSeconds = prefs.getFloat(KEY_LOST_SECONDS, 6.0f).coerceIn(3.0f, 15.0f),
        boostAlarmVolume = prefs.getBoolean(KEY_BOOST_VOLUME, true)
    )

    fun saveSettings(settings: GuardSettings) {
        prefs.edit()
            .putInt(KEY_THRESHOLD_DBM, settings.thresholdDbm)
            .putFloat(KEY_HOLD_SECONDS, settings.holdSeconds)
            .putFloat(KEY_LOST_SECONDS, settings.lostTimeoutSeconds)
            .putBoolean(KEY_BOOST_VOLUME, settings.boostAlarmVolume)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "laptop_guard_prefs"
        private const val KEY_DEVICE_ID = "paired_device_id"
        private const val KEY_SECRET = "paired_secret"
        private const val KEY_DEVICE_NAME = "paired_device_name"
        private const val KEY_THRESHOLD_DBM = "threshold_dbm"
        private const val KEY_HOLD_SECONDS = "hold_seconds"
        private const val KEY_LOST_SECONDS = "lost_timeout_seconds"
        private const val KEY_BOOST_VOLUME = "boost_alarm_volume"
    }
}

