package com.sensorguard.earbuds.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorguard.earbuds.data.*
import com.sensorguard.earbuds.service.SensorGuardService
import kotlin.math.roundToInt

data class PermissionChecklistState(
    val btConnectGranted: Boolean = false,
    val btScanGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val permissionState = mutableStateOf(PermissionChecklistState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardRepository.initialize(applicationContext)
        refreshStatusesAndBondedDevices()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val perms by permissionState
                    SensorGuardDashboard(
                        perms = perms,
                        onPermissionsResult = { refreshStatusesAndBondedDevices() },
                        onOpenNotificationListenerSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onRequestIgnoreBatteryOptimizations = {
                            requestBatteryOptimizationExemption()
                        },
                        onToggleForegroundService = { shouldRun ->
                            val svcIntent = Intent(this, SensorGuardService::class.java).apply {
                                action = if (shouldRun) {
                                    SensorGuardService.ACTION_START
                                } else {
                                    SensorGuardService.ACTION_STOP
                                }
                            }
                            if (shouldRun) {
                                ContextCompat.startForegroundService(this, svcIntent)
                            } else {
                                startService(svcIntent)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check all runtime permissions, listener access, and battery exemption on every resume
        refreshStatusesAndBondedDevices()
    }

    private fun refreshStatusesAndBondedDevices() {
        val btConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val btScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notifListener = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        val powerMgr = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = powerMgr?.isIgnoringBatteryOptimizations(packageName) ?: false

        permissionState.value = PermissionChecklistState(
            btConnectGranted = btConnect,
            btScanGranted = btScan,
            postNotificationsGranted = postNotif,
            notificationListenerEnabled = notifListener,
            batteryOptimizationIgnored = batteryIgnored
        )

        if (btConnect) {
            try {
                val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val bonded = btMgr?.adapter?.bondedDevices.orEmpty().map { dev ->
                    PairedBtDevice(
                        name = dev.name ?: "Paired Device",
                        address = dev.address
                    )
                }
                GuardRepository.updatePairedDevices(bonded)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorGuardDashboard(
    perms: PermissionChecklistState,
    onPermissionsResult: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onToggleForegroundService: (Boolean) -> Unit
) {
    val guardEnabled by GuardRepository.guardEnabled.collectAsStateWithLifecycle()
    val serviceRunning by GuardRepository.serviceRunning.collectAsStateWithLifecycle()
    val resumeDelayMs by GuardRepository.resumeDelayMs.collectAsStateWithLifecycle()
    val rapidPauseCount by GuardRepository.rapidPauseCount.collectAsStateWithLifecycle()
    val rapidPauseWindowSec by GuardRepository.rapidPauseWindowSec.collectAsStateWithLifecycle()
    val pairedDevices by GuardRepository.pairedDevices.collectAsStateWithLifecycle()
    val whitelistedAddresses by GuardRepository.whitelistedAddresses.collectAsStateWithLifecycle()
    val activeA2dpNames by GuardRepository.activeA2dpNames.collectAsStateWithLifecycle()
    val batteryState by GuardRepository.batteryState.collectAsStateWithLifecycle()
    val eventLogs by GuardRepository.eventLogs.collectAsStateWithLifecycle()

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onPermissionsResult() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SensorGuard • Earbud Monitor") }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // 1. Permission Setup Card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Permissions & System Setup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        PermissionStepRow(
                            label = "BLUETOOTH_CONNECT",
                            granted = perms.btConnectGranted,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    singlePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                }
                            }
                        )

                        PermissionStepRow(
                            label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                "BLUETOOTH_SCAN"
                            } else {
                                "ACCESS_FINE_LOCATION (BLE API 29-30)"
                            },
                            granted = perms.btScanGranted,
                            onClick = {
                                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    Manifest.permission.BLUETOOTH_SCAN
                                } else {
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                }
                                singlePermissionLauncher.launch(perm)
                            }
                        )

                        PermissionStepRow(
                            label = "POST_NOTIFICATIONS",
                            granted = perms.postNotificationsGranted,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    singlePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )

                        PermissionStepRow(
                            label = "Notification Access (MediaSession)",
                            granted = perms.notificationListenerEnabled,
                            onClick = onOpenNotificationListenerSettings
                        )

                        PermissionStepRow(
                            label = "Battery Optimization Exemption",
                            granted = perms.batteryOptimizationIgnored,
                            onClick = onRequestIgnoreBatteryOptimizations
                        )
                    }
                }
            }

            // 2. Master Switch & Sliders Card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Phantom-Pause Guard",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                val activeSummary = if (activeA2dpNames.isEmpty()) {
                                    "Active A2DP: None"
                                } else {
                                    "Active A2DP: ${activeA2dpNames.joinToString(", ")}"
                                }
                                Text(
                                    text = activeSummary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = guardEnabled,
                                onCheckedChange = { GuardRepository.setGuardEnabled(it) }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onToggleForegroundService(!serviceRunning) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (serviceRunning) "Stop Guard Service" else "Start Guard Service")
                            }
                            OutlinedButton(
                                onClick = {
                                    GuardRepository.triggerManualBypassPause(
                                        origin = "In-App Button",
                                        nowElapsedRealtime = SystemClock.elapsedRealtime()
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pause (30s Bypass)")
                            }
                        }

                        Text("Auto-Resume Delay: ${resumeDelayMs} ms (50–500 ms)")
                        Slider(
                            value = resumeDelayMs.toFloat(),
                            onValueChange = { GuardRepository.setResumeDelayMs(it.roundToInt()) },
                            valueRange = 50f..500f
                        )

                        Text("Rapid-Pause Count Threshold: > $rapidPauseCount pauses")
                        Slider(
                            value = rapidPauseCount.toFloat(),
                            onValueChange = { GuardRepository.setRapidPauseCount(it.roundToInt()) },
                            valueRange = 1f..6f,
                            steps = 4
                        )

                        Text("Rapid-Pause Window: ${rapidPauseWindowSec} seconds")
                        Slider(
                            value = rapidPauseWindowSec.toFloat(),
                            onValueChange = { GuardRepository.setRapidPauseWindowSec(it.roundToInt()) },
                            valueRange = 2f..15f,
                            steps = 12
                        )
                    }
                }
            }

            // 3. Battery Card (L, R, Case)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "AirPods-Style BLE Battery (${batteryState.modelName})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BatterySlotColumn(
                                title = "Left",
                                value = batteryState.formatSlot(
                                    batteryState.leftPercent,
                                    batteryState.leftCharging
                                )
                            )
                            BatterySlotColumn(
                                title = "Right",
                                value = batteryState.formatSlot(
                                    batteryState.rightPercent,
                                    batteryState.rightCharging
                                )
                            )
                            BatterySlotColumn(
                                title = "Case",
                                value = batteryState.formatSlot(
                                    batteryState.casePercent,
                                    batteryState.caseCharging
                                )
                            )
                        }
                    }
                }
            }

            // 4. Device Whitelist Card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Guarded Bluetooth A2DP Whitelist",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (whitelistedAddresses.isEmpty()) {
                                "No devices checked — guarding ANY active Bluetooth A2DP output."
                            } else {
                                "Guarding ${whitelistedAddresses.size} selected device(s)."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (pairedDevices.isEmpty()) {
                            Text(
                                text = "No bonded Bluetooth devices found (or BLUETOOTH_CONNECT not yet granted).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            pairedDevices.forEach { device ->
                                val isChecked = whitelistedAddresses.contains(device.address)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(device.name, fontWeight = FontWeight.Medium)
                                        Text(
                                            device.address,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            GuardRepository.toggleWhitelistedAddress(
                                                device.address,
                                                checked
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Live Event Log Header + Capped 200-Entry List
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Live Event Log (${eventLogs.size}/200)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(onClick = { GuardRepository.clearLogs() }) {
                        Text("Clear")
                    }
                }
            }

            items(eventLogs, key = { it.id }) { item ->
                Text(
                    text = "[${item.timestamp}] ${item.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun PermissionStepRow(
    label: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (granted) "Status: Granted / Active" else "Status: Action Required",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = onClick,
            enabled = !granted
        ) {
            Text(if (granted) "Done" else "Grant")
        }
    }
}

@Composable
private fun BatterySlotColumn(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, style = MaterialTheme.typography.labelMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
