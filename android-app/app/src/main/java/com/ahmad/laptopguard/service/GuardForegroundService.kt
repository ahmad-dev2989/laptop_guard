package com.ahmad.laptopguard.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.ahmad.laptopguard.MainActivity
import com.ahmad.laptopguard.R
import com.ahmad.laptopguard.alarm.AlarmPlayer
import com.ahmad.laptopguard.data.GuardPreferences
import com.ahmad.laptopguard.data.GuardSettings
import com.ahmad.laptopguard.monitor.AlarmReason
import com.ahmad.laptopguard.monitor.GuardPhase
import com.ahmad.laptopguard.monitor.ProximityDetector
import com.ahmad.laptopguard.monitor.ProximitySnapshot
import com.ahmad.laptopguard.protocol.BeaconPacket
import com.ahmad.laptopguard.protocol.PairedLaptop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GuardForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: GuardPreferences
    private lateinit var alarmPlayer: AlarmPlayer
    private lateinit var notificationManager: NotificationManager

    private val detector = ProximityDetector()
    private var pairedLaptop: PairedLaptop? = null
    private var settings: GuardSettings = GuardSettings()

    private var scanner: BluetoothLeScanner? = null
    private var scanActive = false
    private var evaluatorJob: Job? = null
    private var alarmWakeLock: PowerManager.WakeLock? = null
    private var lastNotificationPhase: GuardPhase? = null
    private var lastNotificationUpdateMs = 0L

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    stopScanning()
                    GuardStateHolder.update {
                        it.copy(
                            bluetoothEnabled = false,
                            statusError = "Phone Bluetooth turned OFF — proximity scanning paused."
                        )
                    }
                    updateOngoingNotification("Bluetooth OFF — turn Bluetooth on to monitor.", urgent = false)
                }
                BluetoothAdapter.STATE_ON -> {
                    GuardStateHolder.update { it.copy(bluetoothEnabled = true, statusError = null) }
                    startScanning()
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanActive = false
            GuardStateHolder.update {
                it.copy(statusError = "BLE scan error ($errorCode). Try stopping and restarting Travel Guard.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardPreferences(this)
        alarmPlayer = AlarmPlayer(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannels()
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGuardAndSelf()
                return START_NOT_STICKY
            }
            ACTION_ACKNOWLEDGE -> {
                acknowledgeAlarm()
                return START_STICKY
            }
            ACTION_REFRESH_SETTINGS -> {
                settings = prefs.loadSettings()
                detector.updateConfig(settings.toProximityConfig())
                return START_STICKY
            }
        }

        val laptop = prefs.loadPairedLaptop()
        if (laptop == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        pairedLaptop = laptop
        settings = prefs.loadSettings()
        detector.updateConfig(settings.toProximityConfig())

        startForeground(NOTIFICATION_ID, buildStatusNotification("Searching for ${laptop.deviceName}...", urgent = false))

        if (!GuardStateHolder.state.value.serviceRunning) {
            val now = SystemClock.elapsedRealtime()
            detector.start(now)
            val initialSnapshot = detector.evaluate(now)
            GuardStateHolder.update {
                it.copy(
                    serviceRunning = true,
                    bluetoothEnabled = isBluetoothOn(),
                    statusError = null,
                    snapshot = initialSnapshot
                )
            }
        }

        startScanning()
        startEvaluationLoop()
        return START_STICKY
    }

    private fun handleScanResult(result: ScanResult) {
        val laptop = pairedLaptop ?: return
        val mfgBytes = result.scanRecord?.getManufacturerSpecificData(BeaconPacket.COMPANY_ID) ?: return
        val parsed = BeaconPacket.tryVerifyAndParse(
            payload = mfgBytes,
            expectedDeviceId = laptop.deviceIdBytes(),
            secret = laptop.secretBytes()
        ) ?: return

        val now = SystemClock.elapsedRealtime()
        val snap = synchronized(detector) {
            detector.onVerifiedPacket(
                rssi = result.rssi,
                batteryPercent = parsed.batteryPercent,
                acConnected = parsed.isAcPowerConnected,
                keepAwakeEnabled = parsed.isKeepAwakeEnabled,
                nowMs = now
            )
        }
        applySnapshot(snap, now)
    }

    private fun startEvaluationLoop() {
        if (evaluatorJob?.isActive == true) return
        evaluatorJob = serviceScope.launch {
            while (isActive) {
                delay(400L)
                val now = SystemClock.elapsedRealtime()
                val snap = synchronized(detector) { detector.evaluate(now) }
                applySnapshot(snap, now)
            }
        }
    }

    private fun applySnapshot(snap: ProximitySnapshot, nowMs: Long) {
        GuardStateHolder.update { it.copy(snapshot = snap) }

        if (snap.phase == GuardPhase.ALARMING) {
            acquireAlarmWakeLock()
            alarmPlayer.start(settings.boostAlarmVolume)
        } else {
            alarmPlayer.stop()
            releaseAlarmWakeLock()
        }

        val phaseChanged = snap.phase != lastNotificationPhase
        val dueForPeriodicRefresh = nowMs - lastNotificationUpdateMs >= 4000L
        if (phaseChanged || dueForPeriodicRefresh) {
            lastNotificationPhase = snap.phase
            lastNotificationUpdateMs = nowMs
            val laptopName = pairedLaptop?.deviceName ?: "Laptop"
            val text = when (snap.phase) {
                GuardPhase.IDLE -> "Idle"
                GuardPhase.SEARCHING -> "Searching for $laptopName..."
                GuardPhase.SAFE -> "Protected • Signal ${snap.smoothedRssi ?: "--"} dBm (Threshold ${settings.thresholdDbm} dBm)"
                GuardPhase.WEAK_PENDING -> "Signal weak (${snap.smoothedRssi ?: "--"} dBm) — alarm soon..."
                GuardPhase.ALARMING -> when (snap.alarmReason) {
                    AlarmReason.SIGNAL_LOST -> "SIGNAL LOST! Check $laptopName now!"
                    AlarmReason.SIGNAL_TOO_WEAK -> "TOO FAR (${snap.smoothedRssi ?: "--"} dBm)! Check $laptopName!"
                    AlarmReason.NONE -> "Check $laptopName!"
                }
            }
            updateOngoingNotification(text, urgent = snap.phase == GuardPhase.ALARMING)
        }
    }

    private fun acknowledgeAlarm() {
        alarmPlayer.stop()
        releaseAlarmWakeLock()
        val now = SystemClock.elapsedRealtime()
        val snap = synchronized(detector) {
            detector.acknowledgeAlarm(now, snoozeDurationMs = 15_000L)
            detector.evaluate(now)
        }
        applySnapshot(snap, now)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanActive) return
        val laptop = pairedLaptop ?: return
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            GuardStateHolder.update {
                it.copy(
                    bluetoothEnabled = false,
                    statusError = "Turn ON phone Bluetooth to monitor ${laptop.deviceName}."
                )
            }
            return
        }

        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            GuardStateHolder.update {
                it.copy(statusError = "BLE scanner unavailable on this phone.")
            }
            return
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(
                BeaconPacket.COMPANY_ID,
                BeaconPacket.buildFilterPrefix(laptop.deviceIdBytes()),
                BeaconPacket.buildFilterMask()
            )
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        try {
            leScanner.startScan(listOf(filter), scanSettings, scanCallback)
            scanner = leScanner
            scanActive = true
            GuardStateHolder.update { it.copy(bluetoothEnabled = true, statusError = null) }
        } catch (se: SecurityException) {
            GuardStateHolder.update {
                it.copy(statusError = "Bluetooth scan permission required: ${se.message}")
            }
        } catch (e: Exception) {
            GuardStateHolder.update {
                it.copy(statusError = "Could not start BLE scan: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!scanActive) return
        scanActive = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun stopGuardAndSelf() {
        evaluatorJob?.cancel()
        stopScanning()
        alarmPlayer.stop()
        releaseAlarmWakeLock()
        synchronized(detector) { detector.stop() }
        GuardStateHolder.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isBluetoothOn(): Boolean {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return btManager?.adapter?.isEnabled == true
    }

    private fun acquireAlarmWakeLock() {
        if (alarmWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        alarmWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LaptopGuard::AlarmWake").apply {
            acquire(60_000L)
        }
    }

    private fun releaseAlarmWakeLock() {
        try {
            if (alarmWakeLock?.isHeld == true) {
                alarmWakeLock?.release()
            }
        } catch (_: Exception) {
        }
        alarmWakeLock = null
    }

    private fun ensureNotificationChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "Travel Guard Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent status notification while monitoring your laptop"
        }

        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            "Laptop Proximity Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Urgent alert when your laptop signal drops or disappears"
            setSound(null, null) // Audio is controlled directly by AlarmPlayer on STREAM_ALARM
            enableVibration(false)
        }

        notificationManager.createNotificationChannel(statusChannel)
        notificationManager.createNotificationChannel(alarmChannel)
    }

    private fun updateOngoingNotification(text: String, urgent: Boolean) {
        notificationManager.notify(NOTIFICATION_ID, buildStatusNotification(text, urgent))
    }

    private fun buildStatusNotification(text: String, urgent: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GuardForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, if (urgent) CHANNEL_ALARM else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(if (urgent) "CHECK YOUR LAPTOP BAG!" else "Laptop Guard Active")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(!urgent)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop Guard", stopPending)

        if (urgent) {
            val ackIntent = Intent(this, GuardForegroundService::class.java).apply {
                action = ACTION_ACKNOWLEDGE
            }
            val ackPending = PendingIntent.getService(
                this,
                3,
                ackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "I Have My Laptop", ackPending)
            builder.setFullScreenIntent(openPending, true)
        }

        return builder.build()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        evaluatorJob?.cancel()
        stopScanning()
        alarmPlayer.stop()
        releaseAlarmWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ahmad.laptopguard.START"
        const val ACTION_STOP = "com.ahmad.laptopguard.STOP"
        const val ACTION_ACKNOWLEDGE = "com.ahmad.laptopguard.ACKNOWLEDGE"
        const val ACTION_REFRESH_SETTINGS = "com.ahmad.laptopguard.REFRESH_SETTINGS"

        private const val CHANNEL_STATUS = "guard_status"
        private const val CHANNEL_ALARM = "guard_alarm"
        private const val NOTIFICATION_ID = 4201

        fun startService(context: Context) {
            val intent = Intent(context, GuardForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, GuardForegroundService::class.java).apply {
                this.action = action
            }
            context.startService(intent)
        }
    }
}

