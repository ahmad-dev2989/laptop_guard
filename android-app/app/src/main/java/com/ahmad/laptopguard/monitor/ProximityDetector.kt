package com.ahmad.laptopguard.monitor

import kotlin.math.roundToInt

enum class GuardPhase {
    IDLE,
    SEARCHING,
    SAFE,
    WEAK_PENDING,
    ALARMING
}

enum class AlarmReason {
    NONE,
    SIGNAL_TOO_WEAK,
    SIGNAL_LOST
}

data class ProximityConfig(
    val thresholdDbm: Int = -76,
    val holdSeconds: Float = 2.5f,
    val lostTimeoutSeconds: Float = 6.0f,
    val emaAlpha: Float = 0.35f,
    val windowSize: Int = 5
)

data class ProximitySnapshot(
    val phase: GuardPhase,
    val alarmReason: AlarmReason,
    val latestRawRssi: Int?,
    val smoothedRssi: Int?,
    val laptopBatteryPercent: Int?,
    val laptopAcConnected: Boolean,
    val laptopKeepAwake: Boolean,
    val lastPacketAgeMs: Long?,
    val pendingProgress: Float, // 0f..1f while counting toward alarm
    val snoozedRemainingMs: Long
)

/**
 * Pure state machine that smooths noisy BLE RSSI readings, tracks packet loss,
 * and decides when to enter WEAK_PENDING or ALARMING without false spikes.
 */
class ProximityDetector(
    private var config: ProximityConfig = ProximityConfig()
) {
    private val recentSamples = ArrayDeque<Int>()
    private var emaRssi: Double? = null
    private var isMonitoring = false

    private var lastRawRssi: Int? = null
    private var lastPacketTimestampMs: Long? = null
    private var laptopBatteryPct: Int? = null
    private var laptopAc = false
    private var laptopKeepAwake = false

    private var breachStartedAtMs: Long? = null
    private var activeAlarmReason: AlarmReason = AlarmReason.NONE
    private var snoozeUntilMs: Long = 0L

    fun updateConfig(newConfig: ProximityConfig) {
        config = newConfig
    }

    fun start(nowMs: Long) {
        recentSamples.clear()
        emaRssi = null
        lastRawRssi = null
        lastPacketTimestampMs = null
        laptopBatteryPct = null
        laptopAc = false
        laptopKeepAwake = false
        breachStartedAtMs = null
        activeAlarmReason = AlarmReason.NONE
        snoozeUntilMs = 0L
        isMonitoring = true
        evaluate(nowMs)
    }

    fun stop() {
        isMonitoring = false
        breachStartedAtMs = null
        activeAlarmReason = AlarmReason.NONE
        snoozeUntilMs = 0L
    }

    fun acknowledgeAlarm(nowMs: Long, snoozeDurationMs: Long = 15_000L) {
        if (!isMonitoring) return
        activeAlarmReason = AlarmReason.NONE
        breachStartedAtMs = null
        snoozeUntilMs = nowMs + snoozeDurationMs
    }

    fun onVerifiedPacket(
        rssi: Int,
        batteryPercent: Int,
        acConnected: Boolean,
        keepAwakeEnabled: Boolean,
        nowMs: Long
    ): ProximitySnapshot {
        if (!isMonitoring) return evaluate(nowMs)

        lastRawRssi = rssi
        lastPacketTimestampMs = nowMs
        laptopBatteryPct = if (batteryPercent in 0..100) batteryPercent else null
        laptopAc = acConnected
        laptopKeepAwake = keepAwakeEnabled

        recentSamples.addLast(rssi)
        while (recentSamples.size > config.windowSize) {
            recentSamples.removeFirst()
        }

        val median = computeMedian(recentSamples)
        emaRssi = if (emaRssi == null) {
            median.toDouble()
        } else {
            config.emaAlpha * median + (1.0 - config.emaAlpha) * emaRssi!!
        }

        return evaluate(nowMs)
    }

    fun evaluate(nowMs: Long): ProximitySnapshot {
        if (!isMonitoring) {
            return snapshot(
                phase = GuardPhase.IDLE,
                alarmReason = AlarmReason.NONE,
                nowMs = nowMs,
                pendingProgress = 0f
            )
        }

        val packetTime = lastPacketTimestampMs
        val currentSmoothed = emaRssi?.roundToInt()

        // Already alarming until acknowledged or stopped
        if (activeAlarmReason != AlarmReason.NONE) {
            return snapshot(
                phase = GuardPhase.ALARMING,
                alarmReason = activeAlarmReason,
                nowMs = nowMs,
                pendingProgress = 1f
            )
        }

        // Snooze grace window after user taps "I Have My Laptop"
        if (nowMs < snoozeUntilMs) {
            return snapshot(
                phase = if (packetTime == null) GuardPhase.SEARCHING else GuardPhase.SAFE,
                alarmReason = AlarmReason.NONE,
                nowMs = nowMs,
                pendingProgress = 0f
            )
        }

        if (packetTime == null || currentSmoothed == null) {
            return snapshot(
                phase = GuardPhase.SEARCHING,
                alarmReason = AlarmReason.NONE,
                nowMs = nowMs,
                pendingProgress = 0f
            )
        }

        val ageMs = (nowMs - packetTime).coerceAtLeast(0L)
        val lostTimeoutMs = (config.lostTimeoutSeconds * 1000f).toLong().coerceAtLeast(1000L)
        if (ageMs >= lostTimeoutMs) {
            activeAlarmReason = AlarmReason.SIGNAL_LOST
            return snapshot(
                phase = GuardPhase.ALARMING,
                alarmReason = AlarmReason.SIGNAL_LOST,
                nowMs = nowMs,
                pendingProgress = 1f
            )
        }

        // Check if smoothed RSSI is weaker than threshold
        if (currentSmoothed < config.thresholdDbm) {
            val started = breachStartedAtMs ?: nowMs.also { breachStartedAtMs = it }
            val holdMs = (config.holdSeconds * 1000f).toLong().coerceAtLeast(500L)
            val elapsed = (nowMs - started).coerceAtLeast(0L)
            if (elapsed >= holdMs) {
                activeAlarmReason = AlarmReason.SIGNAL_TOO_WEAK
                return snapshot(
                    phase = GuardPhase.ALARMING,
                    alarmReason = AlarmReason.SIGNAL_TOO_WEAK,
                    nowMs = nowMs,
                    pendingProgress = 1f
                )
            }

            return snapshot(
                phase = GuardPhase.WEAK_PENDING,
                alarmReason = AlarmReason.NONE,
                nowMs = nowMs,
                pendingProgress = (elapsed.toFloat() / holdMs.toFloat()).coerceIn(0f, 1f)
            )
        }

        // Signal recovered cleanly
        breachStartedAtMs = null
        return snapshot(
            phase = GuardPhase.SAFE,
            alarmReason = AlarmReason.NONE,
            nowMs = nowMs,
            pendingProgress = 0f
        )
    }

    private fun snapshot(
        phase: GuardPhase,
        alarmReason: AlarmReason,
        nowMs: Long,
        pendingProgress: Float
    ): ProximitySnapshot {
        val age = lastPacketTimestampMs?.let { (nowMs - it).coerceAtLeast(0L) }
        val snoozeRemaining = (snoozeUntilMs - nowMs).coerceAtLeast(0L)
        return ProximitySnapshot(
            phase = phase,
            alarmReason = alarmReason,
            latestRawRssi = lastRawRssi,
            smoothedRssi = emaRssi?.roundToInt(),
            laptopBatteryPercent = laptopBatteryPct,
            laptopAcConnected = laptopAc,
            laptopKeepAwake = laptopKeepAwake,
            lastPacketAgeMs = age,
            pendingProgress = pendingProgress,
            snoozedRemainingMs = snoozeRemaining
        )
    }

    companion object {
        fun computeMedian(values: Collection<Int>): Int {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}

