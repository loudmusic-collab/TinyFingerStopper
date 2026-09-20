package com.loudmusic.tinyfingerstopper.watchdog

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Android will not let an ordinary app block the Home gesture, so rather than
 * prevent that escape this undoes it: poll which app is in front and bring the
 * locked one back when it changes.
 *
 * Relaunching an activity from the background is allowed here because the app
 * holds SYSTEM_ALERT_WINDOW, which is an explicit exemption from the background
 * activity launch restrictions.
 *
 * Requires usage access. Without it the lock still works, it just cannot heal.
 */
class ForegroundAppWatchdog(
    private val context: Context,
    private val targetPackage: String,
    private val onGiveUp: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val recentRelaunches = ArrayDeque<Long>()
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            if (running) handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
    }

    private fun step() {
        // The parent unlocking the phone is expected. Never fight the keyguard.
        if (keyguard?.isKeyguardLocked == true) return

        val current = currentForegroundPackage(context) ?: return
        if (current == targetPackage || current == context.packageName) return

        val now = SystemClock.elapsedRealtime()
        recentRelaunches.addLast(now)
        while (recentRelaunches.isNotEmpty() &&
            now - recentRelaunches.first() > GIVE_UP_WINDOW_MS
        ) {
            recentRelaunches.removeFirst()
        }
        // Snapping back over and over means we are losing to something we cannot
        // beat - a system dialog, a crash loop, an app that will not resume. Let go
        // rather than leave the phone unusable.
        if (recentRelaunches.size > GIVE_UP_COUNT) {
            stop()
            onGiveUp()
            return
        }

        val launch = context.packageManager.getLaunchIntentForPackage(targetPackage) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(launch) }
    }

    companion object {
        private const val POLL_MS = 900L
        private const val LOOKBACK_MS = 15_000L
        private const val GIVE_UP_WINDOW_MS = 20_000L
        private const val GIVE_UP_COUNT = 6

        /**
         * The most recently resumed activity's package, or null if usage access is
         * missing or nothing was resumed inside the lookback window.
         */
        fun currentForegroundPackage(context: Context): String? {
            val usageStats = context.getSystemService(UsageStatsManager::class.java)
                ?: return null
            val end = System.currentTimeMillis()
            val events = runCatching { usageStats.queryEvents(end - LOOKBACK_MS, end) }
                .getOrNull() ?: return null
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED under its older name, which is the same constant
                // and is the one that exists all the way back to our minSdk.
                @Suppress("DEPRECATION")
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    latest = event.packageName
                }
            }
            return latest
        }
    }
}
