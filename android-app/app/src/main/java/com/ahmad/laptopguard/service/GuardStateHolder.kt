package com.ahmad.laptopguard.service

import com.ahmad.laptopguard.monitor.AlarmReason
import com.ahmad.laptopguard.monitor.GuardPhase
import com.ahmad.laptopguard.monitor.ProximitySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuardRuntimeState(
    val serviceRunning: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val statusError: String? = null,
    val snapshot: ProximitySnapshot = ProximitySnapshot(
        phase = GuardPhase.IDLE,
        alarmReason = AlarmReason.NONE,
        latestRawRssi = null,
        smoothedRssi = null,
        laptopBatteryPercent = null,
        laptopAcConnected = false,
        laptopKeepAwake = false,
        lastPacketAgeMs = null,
        pendingProgress = 0f,
        snoozedRemainingMs = 0L
    )
)

object GuardStateHolder {
    private val _state = MutableStateFlow(GuardRuntimeState())
    val state: StateFlow<GuardRuntimeState> = _state.asStateFlow()

    fun update(transform: (GuardRuntimeState) -> GuardRuntimeState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = GuardRuntimeState()
    }
}

