package com.ahmad.laptopguard.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityDetectorTest {

    @Test
    fun strongSignal_remainsSafe_andSingleSpikeDoesNotFalseAlarm() {
        val detector = ProximityDetector(
            ProximityConfig(thresholdDbm = -75, holdSeconds = 2.0f, lostTimeoutSeconds = 5.0f)
        )
        detector.start(nowMs = 1000L)

        // Establish steady strong signal around -62 dBm
        detector.onVerifiedPacket(-62, 85, false, true, 1200L)
        detector.onVerifiedPacket(-63, 85, false, true, 1400L)
        detector.onVerifiedPacket(-61, 85, false, true, 1600L)
        // One noisy spike to -90 dBm is suppressed by median + EMA
        val snap = detector.onVerifiedPacket(-90, 85, false, true, 1800L)

        assertEquals(GuardPhase.SAFE, snap.phase)
        assertTrue((snap.smoothedRssi ?: -100) > -75)
    }

    @Test
    fun sustainedWeakSignal_entersPending_thenTriggersAlarm() {
        val detector = ProximityDetector(
            ProximityConfig(thresholdDbm = -75, holdSeconds = 2.0f, lostTimeoutSeconds = 6.0f)
        )
        detector.start(nowMs = 0L)

        // Feed consistently weak packets (-88 dBm)
        detector.onVerifiedPacket(-88, 80, false, true, 500L)
        val pending = detector.onVerifiedPacket(-88, 80, false, true, 1500L)
        assertEquals(GuardPhase.WEAK_PENDING, pending.phase)
        assertTrue(pending.pendingProgress > 0.4f)

        val alarm = detector.onVerifiedPacket(-88, 80, false, true, 2600L)
        assertEquals(GuardPhase.ALARMING, alarm.phase)
        assertEquals(AlarmReason.SIGNAL_TOO_WEAK, alarm.alarmReason)
    }

    @Test
    fun missingPackets_triggersSignalLost_andAcknowledgeSnoozes() {
        val detector = ProximityDetector(
            ProximityConfig(thresholdDbm = -75, holdSeconds = 2.0f, lostTimeoutSeconds = 5.0f)
        )
        detector.start(nowMs = 0L)
        val safe = detector.onVerifiedPacket(-60, 90, true, true, 1000L)
        assertEquals(GuardPhase.SAFE, safe.phase)

        val stillSearching = detector.evaluate(nowMs = 5500L)
        assertEquals(GuardPhase.SAFE, stillSearching.phase)

        val lost = detector.evaluate(nowMs = 6100L)
        assertEquals(GuardPhase.ALARMING, lost.phase)
        assertEquals(AlarmReason.SIGNAL_LOST, lost.alarmReason)

        detector.acknowledgeAlarm(nowMs = 6200L, snoozeDurationMs = 10_000L)
        val snoozed = detector.evaluate(nowMs = 7000L)
        assertEquals(GuardPhase.SAFE, snoozed.phase)
        assertTrue(snoozed.snoozedRemainingMs > 8000L)
    }
}

