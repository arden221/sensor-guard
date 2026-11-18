package com.sensorguard.earbuds.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.guardDataStore: DataStore<Preferences> by preferencesDataStore(name = "sensor_guard_prefs")

/**
 * Snapshot of parsed Apple Continuity BLE proximity pairing battery data.
 */
data class EarbudBatteryState(
    val modelName: String = "No beacon detected",
    val isReliableModel: Boolean = false,
    val leftPercent: Int? = null,
    val rightPercent: Int? = null,
    val casePercent: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val lastUpdatedMillis: Long = 0L
) {
    fun formatSlot(percent: Int?, charging: Boolean): String {
        if (!isReliableModel || percent == null) return "unavailable"
        return if (charging) "$percent% (⚡)" else "$percent%"
    }

    fun toNotificationSummary(): String {
        if (lastUpdatedMillis == 0L) return "Battery: waiting for BLE advertisement..."
        if (!isReliableModel) return "Battery ($modelName): unavailable"
        return "L: ${formatSlot(leftPercent, leftCharging)} | " +
            "R: ${formatSlot(rightPercent, rightCharging)} | " +
            "Case: ${formatSlot(casePercent, caseCharging)}"
    }
}

data class PairedBtDevice(
    val name: String,
    val address: String
)

data class LogItem(
    val id: Long,
    val timestamp: String,
    val message: String
)

/**
 * Process-wide singleton state holder shared by SensorGuardService, MediaMonitorListener,
 * BypassPauseTileService, and MainActivity Compose UI.
 */
object GuardRepository {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    private val KEY_GUARD_ENABLED = booleanPreferencesKey("guard_enabled")
    private val KEY_RESUME_DELAY_MS = intPreferencesKey("resume_delay_ms")
    private val KEY_RAPID_PAUSE_COUNT = intPreferencesKey("rapid_pause_count")
    private val KEY_RAPID_PAUSE_WINDOW_SEC = intPreferencesKey("rapid_pause_window_sec")
    private val KEY_WHITELIST_ADDRESSES = stringSetPreferencesKey("whitelist_addresses")

    private val _guardEnabled = MutableStateFlow(true)
    val guardEnabled: StateFlow<Boolean> = _guardEnabled.asStateFlow()

    private val _resumeDelayMs = MutableStateFlow(150)
    val resumeDelayMs: StateFlow<Int> = _resumeDelayMs.asStateFlow()

    private val _rapidPauseCount = MutableStateFlow(2)
    val rapidPauseCount: StateFlow<Int> = _rapidPauseCount.asStateFlow()

    private val _rapidPauseWindowSec = MutableStateFlow(5)
    val rapidPauseWindowSec: StateFlow<Int> = _rapidPauseWindowSec.asStateFlow()

    private val _whitelistedAddresses = MutableStateFlow<Set<String>>(emptySet())
    val whitelistedAddresses: StateFlow<Set<String>> = _whitelistedAddresses.asStateFlow()

    private val _activeA2dpAddresses = MutableStateFlow<Set<String>>(emptySet())
    val activeA2dpAddresses: StateFlow<Set<String>> = _activeA2dpAddresses.asStateFlow()

    private val _activeA2dpNames = MutableStateFlow<List<String>>(emptyList())
    val activeA2dpNames: StateFlow<List<String>> = _activeA2dpNames.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedBtDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedBtDevice>> = _pairedDevices.asStateFlow()

    private val _batteryState = MutableStateFlow(EarbudBatteryState())
    val batteryState: StateFlow<EarbudBatteryState> = _batteryState.asStateFlow()

    private val _manualCooldownUntilElapsed = MutableStateFlow(0L)
    val manualCooldownUntilElapsed: StateFlow<Long> = _manualCooldownUntilElapsed.asStateFlow()

    private val _loopLockoutUntilElapsed = MutableStateFlow(0L)
    val loopLockoutUntilElapsed: StateFlow<Long> = _loopLockoutUntilElapsed.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<LogItem>>(emptyList())
    val eventLogs: StateFlow<List<LogItem>> = _eventLogs.asStateFlow()

    // Callback registered by MediaMonitorListener so service/tile actions can pause active sessions
    @Volatile
    var pauseAllSessionsAction: (() -> Int)? = null

    private var logSequence = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        repoScope.launch {
            app.guardDataStore.data.collect { prefs ->
                _guardEnabled.value = prefs[KEY_GUARD_ENABLED] ?: true
                _resumeDelayMs.value = (prefs[KEY_RESUME_DELAY_MS] ?: 150).coerceIn(50, 500)
                _rapidPauseCount.value = (prefs[KEY_RAPID_PAUSE_COUNT] ?: 2).coerceIn(1, 8)
                _rapidPauseWindowSec.value = (prefs[KEY_RAPID_PAUSE_WINDOW_SEC] ?: 5).coerceIn(2, 20)
                _whitelistedAddresses.value = prefs[KEY_WHITELIST_ADDRESSES] ?: emptySet()
            }
        }
    }

    fun appendLog(message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = LogItem(
            id = ++logSequence,
            timestamp = stamp,
            message = message
        )
        _eventLogs.update { current ->
            (listOf(entry) + current).take(200)
        }
    }

    fun clearLogs() {
        _eventLogs.value = emptyList()
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun setGuardEnabled(enabled: Boolean) {
        _guardEnabled.value = enabled
        appendLog("Guard master switch set to ${if (enabled) "ON" else "OFF"}")
        persist { it[KEY_GUARD_ENABLED] = enabled }
    }

    fun toggleGuardEnabled(): Boolean {
        val next = !_guardEnabled.value
        setGuardEnabled(next)
        return next
    }

    fun setResumeDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(50, 500)
        _resumeDelayMs.value = clamped
        persist { it[KEY_RESUME_DELAY_MS] = clamped }
    }

    fun setRapidPauseCount(count: Int) {
        val clamped = count.coerceIn(1, 8)
        _rapidPauseCount.value = clamped
        persist { it[KEY_RAPID_PAUSE_COUNT] = clamped }
    }

    fun setRapidPauseWindowSec(seconds: Int) {
        val clamped = seconds.coerceIn(2, 20)
        _rapidPauseWindowSec.value = clamped
        persist { it[KEY_RAPID_PAUSE_WINDOW_SEC] = clamped }
    }

    fun toggleWhitelistedAddress(address: String, checked: Boolean) {
        val updated = _whitelistedAddresses.value.toMutableSet().apply {
            if (checked) add(address) else remove(address)
        }
        _whitelistedAddresses.value = updated
        appendLog("Whitelist ${if (checked) "added" else "removed"} $address")
        persist { it[KEY_WHITELIST_ADDRESSES] = updated }
    }

    fun updateActiveA2dpDevices(addresses: Set<String>, names: List<String>) {
        _activeA2dpAddresses.value = addresses
        _activeA2dpNames.value = names
    }

    fun updatePairedDevices(devices: List<PairedBtDevice>) {
        _pairedDevices.value = devices
    }

    fun updateBatteryState(state: EarbudBatteryState) {
        _batteryState.value = state
    }

    /**
     * Checks whether a guarded Bluetooth A2DP sink is currently routing audio.
     * Empty whitelist means any active Bluetooth A2DP output is guarded.
     */
    fun isGuardedA2dpConnected(): Boolean {
        val active = _activeA2dpAddresses.value
        if (active.isEmpty()) return false
        val whitelist = _whitelistedAddresses.value
        if (whitelist.isEmpty()) return true
        return active.any { addr -> whitelist.contains(addr) }
    }

    fun triggerManualBypassPause(origin: String, nowElapsedRealtime: Long) {
        val cooldownEnd = nowElapsedRealtime + 30_000L
        _manualCooldownUntilElapsed.value = cooldownEnd
        val pausedSessions = pauseAllSessionsAction?.invoke() ?: 0
        appendLog("Manual bypass pause ($origin): paused $pausedSessions session(s), guard suppressed for 30s")
    }

    fun setLoopLockoutUntil(untilElapsedRealtime: Long) {
        _loopLockoutUntilElapsed.value = untilElapsedRealtime
    }

    private fun persist(block: (MutablePreferences) -> Unit) {
        val ctx = appContext ?: return
        repoScope.launch {
            ctx.guardDataStore.edit(block)
        }
    }
}
