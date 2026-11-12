package com.ahmad.laptopguard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahmad.laptopguard.R
import com.ahmad.laptopguard.data.GuardSettings
import com.ahmad.laptopguard.monitor.AlarmReason
import com.ahmad.laptopguard.monitor.GuardPhase
import com.ahmad.laptopguard.protocol.PairedLaptop
import com.ahmad.laptopguard.protocol.PairingCode
import com.ahmad.laptopguard.service.GuardRuntimeState
import com.ahmad.laptopguard.ui.theme.AccentOrange
import com.ahmad.laptopguard.ui.theme.AccentOrangeStrong
import com.ahmad.laptopguard.ui.theme.BgCard
import com.ahmad.laptopguard.ui.theme.BgCardElevated
import com.ahmad.laptopguard.ui.theme.BgMain
import com.ahmad.laptopguard.ui.theme.BorderSubtle
import com.ahmad.laptopguard.ui.theme.DangerRed
import com.ahmad.laptopguard.ui.theme.SafeGreen
import com.ahmad.laptopguard.ui.theme.TextPrimary
import com.ahmad.laptopguard.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    pairedLaptop: PairedLaptop?,
    settings: GuardSettings,
    runtimeState: GuardRuntimeState,
    isBatteryOptimizationIgnored: Boolean,
    isDndPolicyGranted: Boolean,
    isTestingAlarm: Boolean,
    onOpenQrScanner: () -> Unit,
    onManualPair: (PairedLaptop) -> Unit,
    onUnpair: () -> Unit,
    onStartGuard: () -> Unit,
    onStopGuard: () -> Unit,
    onAcknowledgeAlarm: () -> Unit,
    onUpdateSettings: (GuardSettings) -> Unit,
    onToggleTestAlarm: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestDndAccess: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    var showUnpairDialog by remember { mutableStateOf(false) }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair Laptop?") },
            text = { Text("This removes your saved laptop pairing from this phone. You will need to scan the QR code again.") },
            confirmButton = {
                TextButton(onClick = {
                    showUnpairDialog = false
                    onUnpair()
                }) {
                    Text("Unpair", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        containerColor = BgMain
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeaderBar(runtimeState = runtimeState)

            if (pairedLaptop == null) {
                PairingOnboardingCard(
                    onOpenQrScanner = onOpenQrScanner,
                    onManualPair = onManualPair
                )
            } else {
                if (runtimeState.snapshot.phase == GuardPhase.ALARMING) {
                    ActiveAlarmBanner(
                        laptopName = pairedLaptop.deviceName,
                        reason = runtimeState.snapshot.alarmReason,
                        smoothedRssi = runtimeState.snapshot.smoothedRssi,
                        onAcknowledge = onAcknowledgeAlarm,
                        onStopGuard = onStopGuard
                    )
                }

                if (!runtimeState.bluetoothEnabled || runtimeState.statusError != null) {
                    WarningBanner(
                        message = runtimeState.statusError ?: "Phone Bluetooth is turned OFF.",
                        showBluetoothButton = !runtimeState.bluetoothEnabled,
                        onOpenBluetoothSettings = onOpenBluetoothSettings
                    )
                }

                PairedLaptopCard(
                    laptop = pairedLaptop,
                    runtimeState = runtimeState,
                    onStartGuard = onStartGuard,
                    onStopGuard = onStopGuard,
                    onRequestUnpair = { showUnpairDialog = true }
                )

                SignalCalibrationCard(
                    settings = settings,
                    runtimeState = runtimeState,
                    onUpdateSettings = onUpdateSettings
                )
            }

            AlarmAndPowerCard(
                settings = settings,
                isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                isDndPolicyGranted = isDndPolicyGranted,
                isTestingAlarm = isTestingAlarm,
                onUpdateSettings = onUpdateSettings,
                onToggleTestAlarm = onToggleTestAlarm,
                onRequestBatteryExemption = onRequestBatteryExemption,
                onRequestDndAccess = onRequestDndAccess
            )
        }
    }
}

@Composable
private fun HeaderBar(runtimeState: GuardRuntimeState) {
    val phase = runtimeState.snapshot.phase
    val (pillLabel, pillColor) = when (phase) {
        GuardPhase.IDLE -> "IDLE" to TextSecondary
        GuardPhase.SEARCHING -> "SEARCHING" to AccentOrange
        GuardPhase.SAFE -> "PROTECTED" to SafeGreen
        GuardPhase.WEAK_PENDING -> "WEAK SIGNAL" to AccentOrangeStrong
        GuardPhase.ALARMING -> "ALARMING" to DangerRed
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Laptop Guard Icon",
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Laptop Guard",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "BLE proximity alarm (silent bypass)",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Surface(
                color = pillColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, pillColor.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = pillLabel,
                    color = pillColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun PairingOnboardingCard(
    onOpenQrScanner: () -> Unit,
    onManualPair: (PairedLaptop) -> Unit
) {
    var manualInput by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Pair Your Laptop Once",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Open Laptop Guard on your Windows laptop and scan its QR code. Your pairing stays saved permanently.",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onOpenQrScanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrangeStrong),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Laptop QR Code", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Or paste the manual pairing code (LG1-...):",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = manualInput,
                onValueChange = {
                    manualInput = it
                    parseError = null
                },
                placeholder = { Text("LG1-...", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (parseError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = parseError!!, color = DangerRed, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val parsed = PairingCode.tryParse(manualInput)
                    if (parsed != null) {
                        onManualPair(parsed)
                    } else {
                        parseError = "Invalid code. Copy the full LG1-... code or laptopguard://pair URI."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Connect with Code")
            }
        }
    }
}

@Composable
private fun ActiveAlarmBanner(
    laptopName: String,
    reason: AlarmReason,
    smoothedRssi: Int?,
    onAcknowledge: () -> Unit,
    onStopGuard: () -> Unit
) {
    val reasonText = when (reason) {
        AlarmReason.SIGNAL_LOST -> "Lost Bluetooth signal from $laptopName."
        AlarmReason.SIGNAL_TOO_WEAK -> "$laptopName signal dropped below your threshold (${smoothedRssi ?: "--"} dBm)."
        AlarmReason.NONE -> "Check $laptopName now."
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1215)),
        border = BorderStroke(2.dp, DangerRed),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFCA5A5))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CHECK YOUR LAPTOP BAG!",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = reasonText, color = Color(0xFFFECACA), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onAcknowledge,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) {
                    Text("I Have My Laptop", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onStopGuard,
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5))
                ) {
                    Text("Stop Guard", color = Color(0xFFFECACA))
                }
            }
        }
    }
}

@Composable
private fun WarningBanner(
    message: String,
    showBluetoothButton: Boolean,
    onOpenBluetoothSettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1C10)),
        border = BorderStroke(1.dp, AccentOrangeStrong),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BluetoothDisabled, contentDescription = null, tint = AccentOrange)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                color = Color(0xFFFDE68A),
                fontSize = 12.5.sp,
                modifier = Modifier.weight(1f)
            )
            if (showBluetoothButton) {
                TextButton(onClick = onOpenBluetoothSettings) {
                    Text("Turn On", color = AccentOrange, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PairedLaptopCard(
    laptop: PairedLaptop,
    runtimeState: GuardRuntimeState,
    onStartGuard: () -> Unit,
    onStopGuard: () -> Unit,
    onRequestUnpair: () -> Unit
) {
    val snap = runtimeState.snapshot
    Card(
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = laptop.deviceName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Permanent ID: ${laptop.deviceIdHex.uppercase()}",
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val linkStatusText = when {
                        !runtimeState.serviceRunning -> "Saved — tap Start Travel Guard to connect"
                        snap.phase == GuardPhase.SEARCHING -> "Connecting — waiting for laptop signal..."
                        snap.smoothedRssi != null -> "Connected • Live signal ${snap.smoothedRssi} dBm"
                        else -> "Monitoring active"
                    }
                    val linkStatusColor = when {
                        !runtimeState.serviceRunning -> TextSecondary
                        snap.phase == GuardPhase.SAFE -> SafeGreen
                        snap.phase == GuardPhase.SEARCHING -> AccentOrange
                        else -> DangerRed
                    }
                    Text(
                        text = linkStatusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = linkStatusColor
                    )
                }
                IconButton(onClick = onRequestUnpair, enabled = !runtimeState.serviceRunning) {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = "Unpair laptop",
                        tint = if (runtimeState.serviceRunning) TextSecondary.copy(alpha = 0.4f) else TextSecondary
                    )
                }
            }

            if (snap.laptopBatteryPercent != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = SafeGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val acLabel = if (snap.laptopAcConnected) " • Plugged in" else ""
                    val awakeLabel = if (snap.laptopKeepAwake) " • Keep-Awake ON" else ""
                    Text(
                        text = "Laptop Battery: ${snap.laptopBatteryPercent}%$acLabel$awakeLabel",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (runtimeState.serviceRunning) {
                Button(
                    onClick = onStopGuard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Stop Travel Guard", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                Button(
                    onClick = onStartGuard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrangeStrong),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Start Travel Guard", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            if (snap.phase == GuardPhase.WEAK_PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Signal below threshold — alarm triggering soon...",
                    color = AccentOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { snap.pendingProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = DangerRed,
                    trackColor = BgCardElevated
                )
            }
        }
    }
}

@Composable
private fun SignalCalibrationCard(
    settings: GuardSettings,
    runtimeState: GuardRuntimeState,
    onUpdateSettings: (GuardSettings) -> Unit
) {
    val snap = runtimeState.snapshot

    // 5-second calibration sampling state
    var isCalibrating by remember { mutableStateOf(false) }
    var calibrationSecondsLeft by remember { mutableIntStateOf(0) }
    val calibrationSamples = remember { mutableStateListOf<Int>() }
    var calibrationResult by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    LaunchedEffect(isCalibrating) {
        if (!isCalibrating) return@LaunchedEffect
        calibrationSamples.clear()
        calibrationResult = null
        for (sec in 5 downTo 1) {
            calibrationSecondsLeft = sec
            delay(1000L)
        }
        isCalibrating = false
        if (calibrationSamples.isNotEmpty()) {
            val sorted = calibrationSamples.sorted()
            calibrationResult = Triple(sorted.first(), sorted[sorted.size / 2], sorted.last())
        }
    }

    // Record live smoothed/raw RSSI whenever a packet updates during calibration
    LaunchedEffect(isCalibrating, snap.latestRawRssi, snap.lastPacketAgeMs) {
        if (isCalibrating && snap.latestRawRssi != null && (snap.lastPacketAgeMs ?: 9999L) < 1500L) {
            calibrationSamples.add(snap.latestRawRssi)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = AccentOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Live Signal & Threshold Tuning",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live dBm Telemetry Row
            Surface(
                color = BgCardElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricColumn(
                        label = "Smoothed",
                        value = snap.smoothedRssi?.let { "$it dBm" } ?: "-- dBm",
                        color = if ((snap.smoothedRssi ?: -120) >= settings.thresholdDbm) SafeGreen else DangerRed
                    )
                    MetricColumn(
                        label = "Raw",
                        value = snap.latestRawRssi?.let { "$it dBm" } ?: "-- dBm",
                        color = TextPrimary
                    )
                    MetricColumn(
                        label = "Packet Age",
                        value = snap.lastPacketAgeMs?.let { "${"%.1f".format(it / 1000f)}s" } ?: "--",
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            SignalGaugeBar(
                currentRssi = snap.smoothedRssi,
                thresholdDbm = settings.thresholdDbm
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Threshold Slider (-95 .. -45 dBm)
            Text(
                text = "Alarm Threshold: ${settings.thresholdDbm} dBm  (${thresholdHint(settings.thresholdDbm)})",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Slider(
                value = settings.thresholdDbm.toFloat(),
                onValueChange = { onUpdateSettings(settings.copy(thresholdDbm = it.roundToInt())) },
                valueRange = -95f..-45f,
                steps = 49,
                colors = SliderDefaults.colors(
                    thumbColor = AccentOrange,
                    activeTrackColor = AccentOrangeStrong
                )
            )
            Text(
                text = "Higher number (e.g. -65) triggers closer; lower number (e.g. -85) allows farther walking.",
                fontSize = 11.5.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Hold Duration Slider
            Text(
                text = "Hold Weak Signal Before Alarm: ${"%.1f".format(settings.holdSeconds)}s",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Slider(
                value = settings.holdSeconds,
                onValueChange = {
                    val snapped = (it * 2f).roundToInt() / 2f
                    onUpdateSettings(settings.copy(holdSeconds = snapped))
                },
                valueRange = 1.0f..8.0f,
                steps = 13,
                colors = SliderDefaults.colors(
                    thumbColor = AccentOrange,
                    activeTrackColor = AccentOrangeStrong
                )
            )

            // Lost Timeout Slider
            Text(
                text = "Signal Lost Timeout: ${settings.lostTimeoutSeconds.roundToInt()}s",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Slider(
                value = settings.lostTimeoutSeconds,
                onValueChange = {
                    onUpdateSettings(settings.copy(lostTimeoutSeconds = it.roundToInt().toFloat()))
                },
                valueRange = 3.0f..15.0f,
                steps = 11,
                colors = SliderDefaults.colors(
                    thumbColor = AccentOrange,
                    activeTrackColor = AccentOrangeStrong
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 5-second Baseline Calibration Helper
            OutlinedButton(
                onClick = { isCalibrating = true },
                enabled = runtimeState.serviceRunning && !isCalibrating,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    if (isCalibrating) "Measuring... (${calibrationSecondsLeft}s)"
                    else "Calibrate Threshold at Current Spot (5s)"
                )
            }

            if (!runtimeState.serviceRunning) {
                Text(
                    text = "Start Travel Guard first to measure live RSSI and calibrate.",
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            calibrationResult?.let { (minRssi, medianRssi, maxRssi) ->
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = BgCardElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Measured: Min $minRssi • Median $medianRssi • Max $maxRssi dBm",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val safeMargin = (medianRssi - 6).coerceIn(-95, -45)
                            OutlinedButton(
                                onClick = { onUpdateSettings(settings.copy(thresholdDbm = safeMargin)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Safe ($safeMargin dBm)", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    onUpdateSettings(settings.copy(thresholdDbm = medianRssi.coerceIn(-95, -45)))
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentOrangeStrong)
                            ) {
                                Text("Exact ($medianRssi dBm)", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalGaugeBar(currentRssi: Int?, thresholdDbm: Int) {
    // Map -100..-40 dBm into 0f..1f
    fun normalize(dbm: Int): Float = ((dbm + 100f) / 60f).coerceIn(0f, 1f)
    val currentFrac = currentRssi?.let(::normalize) ?: 0f
    val threshFrac = normalize(thresholdDbm)
    val isSafe = currentRssi != null && currentRssi >= thresholdDbm

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(BgCardElevated)
        ) {
            if (currentRssi != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentFrac)
                        .height(14.dp)
                        .background(if (isSafe) SafeGreen else DangerRed)
                )
            }
            // Threshold marker line
            Box(
                modifier = Modifier
                    .fillMaxWidth(threshFrac)
                    .height(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(3.dp)
                        .height(14.dp)
                        .background(Color.White)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-100 dBm (Far)", fontSize = 10.5.sp, color = TextSecondary)
            Text("Threshold: $thresholdDbm", fontSize = 10.5.sp, color = Color.White)
            Text("-40 dBm (Close)", fontSize = 10.5.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary)
        Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun AlarmAndPowerCard(
    settings: GuardSettings,
    isBatteryOptimizationIgnored: Boolean,
    isDndPolicyGranted: Boolean,
    isTestingAlarm: Boolean,
    onUpdateSettings: (GuardSettings) -> Unit,
    onToggleTestAlarm: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestDndAccess: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AccentOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Silent Alarm & Background Reliability",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Boost Alarm Volume on Trigger",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Uses Android's Alarm stream so it rings loudly even when your phone is on Silent.",
                        fontSize = 11.5.sp,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = settings.boostAlarmVolume,
                    onCheckedChange = { onUpdateSettings(settings.copy(boostAlarmVolume = it)) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onToggleTestAlarm,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (isTestingAlarm) "Stop Test Alarm" else "Test Silent Alarm (3s)")
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isBatteryOptimizationIgnored) SafeGreen else AccentOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBatteryOptimizationIgnored)
                            "Unrestricted background battery enabled"
                        else
                            "Allow unrestricted background battery",
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
                if (!isBatteryOptimizationIgnored) {
                    TextButton(onClick = onRequestBatteryExemption) {
                        Text("Allow", color = AccentOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (!isDndPolicyGranted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Optional: Allow volume boost during Do Not Disturb",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRequestDndAccess) {
                        Text("Grant", color = AccentOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun thresholdHint(dbm: Int): String = when {
    dbm >= -62 -> "Very Tight"
    dbm >= -72 -> "Tight"
    dbm >= -80 -> "Balanced"
    else -> "Wide Buffer"
}

