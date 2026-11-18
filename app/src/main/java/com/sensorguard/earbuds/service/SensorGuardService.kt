package com.sensorguard.earbuds.service

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sensorguard.earbuds.R
import com.sensorguard.earbuds.ble.AirPodsBleParser
import com.sensorguard.earbuds.data.GuardRepository
import com.sensorguard.earbuds.data.PairedBtDevice
import com.sensorguard.earbuds.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Foreground service of type `connectedDevice` responsible for:
 * 1. Tracking active Bluetooth A2DP output devices via AudioManager + AudioDeviceCallback.
 * 2. Running short, low-power periodic BLE scan windows for Apple 0x004C proximity pairing beacons.
 * 3. Hosting the persistent notification with live battery telemetry and quick actions.
 */
class SensorGuardService : Service() {

    companion object {
        const val ACTION_START = "com.sensorguard.earbuds.ACTION_START"
        const val ACTION_STOP = "com.sensorguard.earbuds.ACTION_STOP"
        const val ACTION_BYPASS_PAUSE = "com.sensorguard.earbuds.ACTION_BYPASS_PAUSE"
        const val ACTION_TOGGLE_GUARD = "com.sensorguard.earbuds.ACTION_TOGGLE_GUARD"

        private const val NOTIFICATION_ID = 4101
        private const val CHANNEL_ID = "sensorguard_connected_device_channel"

        // Short 4-second low-power scan window every 25 seconds to preserve battery
        private const val BLE_SCAN_WINDOW_MS = 4_000L
        private const val BLE_SCAN_INTERVAL_MS = 25_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioManager: AudioManager? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanningBle = false
    private var bleLoopJob: Job? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioDevicesAndBondedList()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioDevicesAndBondedList()
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val appleData = record.getManufacturerSpecificData(AirPodsBleParser.APPLE_COMPANY_ID) ?: return
            val parsed = AirPodsBleParser.parseAppleManufacturerData(
                rawBytes = appleData,
                nowMillis = System.currentTimeMillis()
            ) ?: return
            GuardRepository.updateBatteryState(parsed)
        }
    }

    override fun onCreate() {
        super.onCreate()
        GuardRepository.initialize(applicationContext)
        createNotificationChannel()
        startForegroundWithConnectedDeviceType()
        GuardRepository.setServiceRunning(true)
        GuardRepository.appendLog("SensorGuardService started (connectedDevice)")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        refreshAudioDevicesAndBondedList()

        startPeriodicBleBatteryScan()

        // Keep notification updated whenever guard state, battery state, or A2DP state changes
        serviceScope.launch {
            combine(
                GuardRepository.guardEnabled,
                GuardRepository.batteryState,
                GuardRepository.activeA2dpNames
            ) { enabled, battery, a2dpNames ->
                Triple(enabled, battery, a2dpNames)
            }.collect {
                updateForegroundNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BYPASS_PAUSE -> {
                GuardRepository.triggerManualBypassPause(
                    origin = "Notification Action",
                    nowElapsedRealtime = SystemClock.elapsedRealtime()
                )
                updateForegroundNotification()
            }
            ACTION_TOGGLE_GUARD -> {
                GuardRepository.toggleGuardEnabled()
                updateForegroundNotification()
            }
            ACTION_STOP -> {
                GuardRepository.appendLog("Stopping SensorGuardService")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                refreshAudioDevicesAndBondedList()
                updateForegroundNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBleScanSafely()
        bleLoopJob?.cancel()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        GuardRepository.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithConnectedDeviceType() {
        val notification = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildStatusNotification())
    }

    private fun buildStatusNotification(): Notification {
        val guardOn = GuardRepository.guardEnabled.value
        val a2dpList = GuardRepository.activeA2dpNames.value
        val a2dpLabel = if (a2dpList.isEmpty()) "No A2DP sink" else a2dpList.joinToString(", ")
        val titleText = "Guard: ${if (guardOn) "ON" else "OFF"} • $a2dpLabel"
        val bodyText = GuardRepository.batteryState.value.toNotificationSummary()

        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bypassPausePendingIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, SensorGuardService::class.java).apply { action = ACTION_BYPASS_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleGuardPendingIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, SensorGuardService::class.java).apply { action = ACTION_TOGGLE_GUARD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_qs_tile,
                getString(R.string.action_pause_bypass),
                bypassPausePendingIntent
            )
            .addAction(
                R.drawable.ic_qs_tile,
                getString(R.string.action_toggle_guard),
                toggleGuardPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_guard_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_guard_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun refreshAudioDevicesAndBondedList() {
        val mgr = audioManager ?: return
        val outputs = mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val a2dpSinks = outputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

        val activeAddresses = a2dpSinks.mapNotNull { info ->
            info.address?.takeIf { it.isNotBlank() } ?: info.productName?.toString()
        }.toSet()

        val activeNames = a2dpSinks.map { info ->
            info.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth A2DP"
        }.distinct()

        GuardRepository.updateActiveA2dpDevices(activeAddresses, activeNames)

        if (hasBluetoothConnectPermission()) {
            try {
                val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val bonded = btMgr?.adapter?.bondedDevices.orEmpty().map { device ->
                    PairedBtDevice(
                        name = device.name ?: "Paired Earbuds",
                        address = device.address
                    )
                }
                GuardRepository.updatePairedDevices(bonded)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun startPeriodicBleBatteryScan() {
        bleLoopJob?.cancel()
        bleLoopJob = serviceScope.launch {
            while (isActive) {
                if (hasBluetoothScanPermission()) {
                    startLowPowerBleWindow()
                    delay(BLE_SCAN_WINDOW_MS)
                    stopBleScanSafely()
                }
                delay(BLE_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun startLowPowerBleWindow() {
        if (isScanningBle) return
        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter: BluetoothAdapter = btMgr.adapter ?: return
        if (!adapter.isEnabled) return

        val scanner = adapter.bluetoothLeScanner ?: return
        bleScanner = scanner

        // Filter specifically for Apple Continuity Company ID (0x004C) with SCAN_MODE_LOW_POWER
        val filter = ScanFilter.Builder()
            .setManufacturerData(AirPodsBleParser.APPLE_COMPANY_ID, byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0L)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, bleScanCallback)
            isScanningBle = true
        } catch (_: SecurityException) {
            isScanningBle = false
        } catch (_: Exception) {
            isScanningBle = false
        }
    }

    private fun stopBleScanSafely() {
        if (!isScanningBle) return
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        } finally {
            isScanningBle = false
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
    }
}
