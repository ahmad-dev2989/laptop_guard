package com.ahmad.laptopguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.ahmad.laptopguard.alarm.AlarmPlayer
import com.ahmad.laptopguard.data.GuardPreferences
import com.ahmad.laptopguard.monitor.GuardPhase
import com.ahmad.laptopguard.service.GuardForegroundService
import com.ahmad.laptopguard.service.GuardStateHolder
import com.ahmad.laptopguard.ui.MainScreen
import com.ahmad.laptopguard.ui.QrScannerScreen
import com.ahmad.laptopguard.ui.theme.LaptopGuardTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: GuardPreferences
    private lateinit var testAlarmPlayer: AlarmPlayer

    private var pairedLaptop by mutableStateOf<com.ahmad.laptopguard.protocol.PairedLaptop?>(null)
    private var settings by mutableStateOf(com.ahmad.laptopguard.data.GuardSettings())
    private var showQrScanner by mutableStateOf(false)
    private var isBatteryIgnored by mutableStateOf(false)
    private var isDndGranted by mutableStateOf(false)
    private var isTestingAlarm by mutableStateOf(false)
    private var testAlarmJob: Job? = null

    private val requestGuardPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val missingRequired = requiredBlePermissions().any { results[it] != true && !hasPermission(it) }
        if (missingRequired) {
            Toast.makeText(this, "Bluetooth permissions are required to track your laptop.", Toast.LENGTH_LONG).show()
        } else {
            GuardForegroundService.startService(this)
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            Toast.makeText(this, "Camera permission is needed to scan the laptop QR code.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = GuardPreferences(this)
        testAlarmPlayer = AlarmPlayer(this)
        pairedLaptop = prefs.loadPairedLaptop()
        settings = prefs.loadSettings()
        refreshSystemFlags()

        setContent {
            val runtimeState by GuardStateHolder.state.collectAsState()
            val scope = rememberCoroutineScope()

            if (runtimeState.snapshot.phase == GuardPhase.ALARMING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }

            LaptopGuardTheme {
                if (showQrScanner) {
                    QrScannerScreen(
                        onPaired = { laptop ->
                            prefs.savePairedLaptop(laptop)
                            pairedLaptop = laptop
                            showQrScanner = false
                            Toast.makeText(this, "Paired permanently with ${laptop.deviceName}", Toast.LENGTH_SHORT).show()
                        },
                        onClose = { showQrScanner = false }
                    )
                } else {
                    MainScreen(
                        pairedLaptop = pairedLaptop,
                        settings = settings,
                        runtimeState = runtimeState,
                        isBatteryOptimizationIgnored = isBatteryIgnored,
                        isDndPolicyGranted = isDndGranted,
                        isTestingAlarm = isTestingAlarm,
                        onOpenQrScanner = {
                            if (hasPermission(Manifest.permission.CAMERA)) {
                                showQrScanner = true
                            } else {
                                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onManualPair = { laptop ->
                            prefs.savePairedLaptop(laptop)
                            pairedLaptop = laptop
                            Toast.makeText(this, "Paired permanently!", Toast.LENGTH_SHORT).show()
                        },
                        onUnpair = {
                            if (runtimeState.serviceRunning) {
                                GuardForegroundService.sendAction(this, GuardForegroundService.ACTION_STOP)
                            }
                            prefs.clearPairedLaptop()
                            pairedLaptop = null
                        },
                        onStartGuard = { ensurePermissionsAndStartGuard() },
                        onStopGuard = {
                            GuardForegroundService.sendAction(this, GuardForegroundService.ACTION_STOP)
                        },
                        onAcknowledgeAlarm = {
                            GuardForegroundService.sendAction(this, GuardForegroundService.ACTION_ACKNOWLEDGE)
                        },
                        onUpdateSettings = { newSettings ->
                            settings = newSettings
                            prefs.saveSettings(newSettings)
                            if (runtimeState.serviceRunning) {
                                GuardForegroundService.sendAction(this, GuardForegroundService.ACTION_REFRESH_SETTINGS)
                            }
                        },
                        onToggleTestAlarm = {
                            if (isTestingAlarm) {
                                testAlarmJob?.cancel()
                                testAlarmPlayer.stop()
                                isTestingAlarm = false
                            } else {
                                isTestingAlarm = true
                                testAlarmPlayer.start(settings.boostAlarmVolume)
                                testAlarmJob = scope.launch {
                                    delay(3000L)
                                    testAlarmPlayer.stop()
                                    isTestingAlarm = false
                                }
                            }
                        },
                        onRequestBatteryExemption = { requestBatteryOptimizationExemption() },
                        onRequestDndAccess = { openDndPolicySettings() },
                        onOpenBluetoothSettings = {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemFlags()
    }

    override fun onDestroy() {
        testAlarmJob?.cancel()
        testAlarmPlayer.stop()
        super.onDestroy()
    }

    private fun ensurePermissionsAndStartGuard() {
        val needed = buildList {
            addAll(requiredBlePermissions())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filterNot(::hasPermission)

        if (needed.isEmpty()) {
            GuardForegroundService.startService(this)
        } else {
            requestGuardPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requiredBlePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshSystemFlags() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryIgnored = pm.isIgnoringBatteryOptimizations(packageName)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        isDndGranted = nm.isNotificationPolicyAccessGranted
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openDndPolicySettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

