package com.sensorguard.earbuds.service

import android.content.ComponentName
import android.content.Context
import android.media.session.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.sensorguard.earbuds.data.GuardRepository

/**
 * Monitors active MediaSessions via MediaSessionManager and registers a MediaController.Callback
 * on every active media controller.
 *
 * ANDROID PLATFORM LIMITATION ON PAUSE ORIGIN:
 * Android's MediaSession framework does NOT expose whether a PlaybackState transition from
 * STATE_PLAYING to STATE_PAUSED was triggered by an on-screen user tap, a lock-screen button,
 * or a hardware AVRCP/in-ear proximity sensor event from Bluetooth earbuds. Because an unprivileged
 * app cannot reliably detect screen taps inside third-party media apps without invasive Accessibility
 * overlays, SensorGuard uses timing heuristics (continuous playback >= 2 seconds or rapid-pause burst
 * detection) combined with active Bluetooth A2DP routing and an explicit "Pause Media (Bypass Guard)"
 * manual override action in the notification and Quick Settings tile.
 */
class MediaMonitorListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null

    private data class TrackedController(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var lastState: Int,
        var playingStartedElapsed: Long
    )

    private val trackedSessions = mutableMapOf<MediaSession.Token, TrackedController>()
    private val recentPauseTimestamps = ArrayDeque<Long>()
    private val recentAutoResumeTimestamps = ArrayDeque<Long>()

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncControllers(controllers.orEmpty())
        }

    override fun onCreate() {
        super.onCreate()
        GuardRepository.initialize(applicationContext)
        GuardRepository.pauseAllSessionsAction = { pauseAllActiveSessions() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        GuardRepository.appendLog("NotificationListener connected; attaching MediaSession observer")
        registerSessionObserver()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        GuardRepository.appendLog("NotificationListener disconnected")
        unregisterSessionObserver()
    }

    override fun onDestroy() {
        if (GuardRepository.pauseAllSessionsAction != null) {
            GuardRepository.pauseAllSessionsAction = null
        }
        unregisterSessionObserver()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun registerSessionObserver() {
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        sessionManager = mgr
        val comp = ComponentName(this, MediaMonitorListener::class.java)
        try {
            val initial = mgr.getActiveSessions(comp)
            syncControllers(initial)
            mgr.addOnActiveSessionsChangedListener(activeSessionsListener, comp, mainHandler)
        } catch (sec: SecurityException) {
            GuardRepository.appendLog("MediaSession access denied: enable Notification Access")
        }
    }

    private fun unregisterSessionObserver() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {
        }
        trackedSessions.values.forEach { entry ->
            try {
                entry.controller.unregisterCallback(entry.callback)
            } catch (_: Exception) {
            }
        }
        trackedSessions.clear()
    }

    private fun syncControllers(currentControllers: List<MediaController>) {
        val incomingTokens = currentControllers.map { it.sessionToken }.toSet()

        // Remove stale controllers
        val staleTokens = trackedSessions.keys.filter { it !in incomingTokens }
        for (token in staleTokens) {
            trackedSessions.remove(token)?.let { removed ->
                try {
                    removed.controller.unregisterCallback(removed.callback)
                } catch (_: Exception) {
                }
            }
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        for (controller in currentControllers) {
            val token = controller.sessionToken
            if (trackedSessions.containsKey(token)) continue

            val initialState = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            val initialPlayStart = if (initialState == PlaybackState.STATE_PLAYING) nowElapsed else 0L

            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val newState = state?.state ?: return
                    handleStateTransition(token, controller, newState)
                }

                override fun onSessionDestroyed() {
                    trackedSessions.remove(token)?.let {
                        try {
                            controller.unregisterCallback(this)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            controller.registerCallback(cb, mainHandler)
            trackedSessions[token] = TrackedController(
                controller = controller,
                callback = cb,
                lastState = initialState,
                playingStartedElapsed = initialPlayStart
            )
            GuardRepository.appendLog("Registered session: ${controller.packageName}")
        }
    }

    private fun handleStateTransition(
        token: MediaSession.Token,
        controller: MediaController,
        newState: Int
    ) {
        val tracked = trackedSessions[token] ?: return
        val previousState = tracked.lastState
        if (previousState == newState) return

        val nowElapsed = SystemClock.elapsedRealtime()

        if (newState == PlaybackState.STATE_PLAYING) {
            tracked.playingStartedElapsed = nowElapsed
            tracked.lastState = newState
            return
        }

        if (previousState == PlaybackState.STATE_PLAYING && newState == PlaybackState.STATE_PAUSED) {
            val playedDurationMs = if (tracked.playingStartedElapsed > 0L) {
                nowElapsed - tracked.playingStartedElapsed
            } else {
                0L
            }
            tracked.lastState = newState
            evaluatePlayToPauseTransition(controller, playedDurationMs, nowElapsed)
        } else {
            tracked.lastState = newState
        }
    }

    private fun evaluatePlayToPauseTransition(
        controller: MediaController,
        playedDurationMs: Long,
        nowElapsed: Long
    ) {
        val pkg = controller.packageName

        if (!GuardRepository.guardEnabled.value) {
            GuardRepository.appendLog("Ignored pause ($pkg): Guard is OFF")
            return
        }

        val cooldownUntil = GuardRepository.manualCooldownUntilElapsed.value
        if (nowElapsed < cooldownUntil) {
            val remainingSec = (cooldownUntil - nowElapsed) / 1000L
            GuardRepository.appendLog("Ignored pause ($pkg): Manual bypass cooldown active (${remainingSec}s left)")
            return
        }

        val lockoutUntil = GuardRepository.loopLockoutUntilElapsed.value
        if (nowElapsed < lockoutUntil) {
            val remainingSec = (lockoutUntil - nowElapsed) / 1000L
            GuardRepository.appendLog("Ignored pause ($pkg): Loop protection active (${remainingSec}s left)")
            return
        }

        if (!GuardRepository.isGuardedA2dpConnected()) {
            GuardRepository.appendLog("Ignored pause ($pkg): No guarded Bluetooth A2DP device active")
            return
        }

        // Record timestamp for rapid-pause burst evaluation
        val rapidWindowMs = GuardRepository.rapidPauseWindowSec.value * 1000L
        val rapidThreshold = GuardRepository.rapidPauseCount.value
        recentPauseTimestamps.addLast(nowElapsed)
        while (recentPauseTimestamps.isNotEmpty() && nowElapsed - recentPauseTimestamps.first() > rapidWindowMs) {
            recentPauseTimestamps.removeFirst()
        }

        val playedAtLeastTwoSec = playedDurationMs >= 2_000L
        val isRapidPauseBurst = recentPauseTimestamps.size > rapidThreshold

        if (!playedAtLeastTwoSec && !isRapidPauseBurst) {
            GuardRepository.appendLog(
                "Ignored pause ($pkg): played ${playedDurationMs}ms (<2000ms) and pauses in window=${recentPauseTimestamps.size} (<= $rapidThreshold)"
            )
            return
        }

        // Loop protection: if auto-resume already fired 4 times within 30s, halt for 60s
        while (recentAutoResumeTimestamps.isNotEmpty() && nowElapsed - recentAutoResumeTimestamps.first() > 30_000L) {
            recentAutoResumeTimestamps.removeFirst()
        }
        if (recentAutoResumeTimestamps.size >= 4) {
            val haltUntil = nowElapsed + 60_000L
            GuardRepository.setLoopLockoutUntil(haltUntil)
            GuardRepository.appendLog(
                "LOOP PROTECTION TRIGGERED: 4 auto-resumes within 30s. Pausing guard for 60s."
            )
            return
        }

        val reason = if (isRapidPauseBurst) {
            "rapid-pause burst (${recentPauseTimestamps.size} pauses in ${GuardRepository.rapidPauseWindowSec.value}s)"
        } else {
            "played ${playedDurationMs}ms before sudden pause"
        }

        val delayMs = GuardRepository.resumeDelayMs.value.toLong()
        recentAutoResumeTimestamps.addLast(nowElapsed)
        GuardRepository.appendLog("Suspected phantom pause on $pkg ($reason). Auto-resuming in ${delayMs}ms")

        mainHandler.postDelayed({
            val currentTime = SystemClock.elapsedRealtime()
            if (GuardRepository.guardEnabled.value &&
                currentTime >= GuardRepository.manualCooldownUntilElapsed.value &&
                currentTime >= GuardRepository.loopLockoutUntilElapsed.value &&
                GuardRepository.isGuardedA2dpConnected()
            ) {
                try {
                    controller.transportControls.play()
                    GuardRepository.appendLog("Executed transportControls.play() on $pkg")
                } catch (e: Exception) {
                    GuardRepository.appendLog("Failed transportControls.play() on $pkg: ${e.message}")
                }
            }
        }, delayMs)
    }

    private fun pauseAllActiveSessions(): Int {
        var count = 0
        for (entry in trackedSessions.values) {
            val state = entry.controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                try {
                    entry.controller.transportControls.pause()
                    count++
                } catch (_: Exception) {
                }
            }
        }
        return count
    }
}
