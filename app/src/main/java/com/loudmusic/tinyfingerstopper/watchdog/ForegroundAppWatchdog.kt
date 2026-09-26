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
 * This only ever moves apps around. It has no way to release the lock, on purpose:
 * see [SnapBackPolicy].
 *
 * Requires usage access. Without it the lock still works, it just cannot heal.
 */
class ForegroundAppWatchdog(
    private val context: Context,
    private val targetPackage: String,
    private val onStruggling: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val policy = SnapBackPolicy()
    private var lastRelaunchWallTime = 0L
    private var running = false
    private var warnedStruggling = false

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

        val snapshot = snapshot(context, targetPackage, lastRelaunchWallTime) ?: return
        val inFront = snapshot.latest == targetPackage || snapshot.latest == context.packageName

        when (policy.decide(SystemClock.elapsedRealtime(), inFront, snapshot.targetReturned)) {
            SnapBackPolicy.Action.NONE -> Unit
            SnapBackPolicy.Action.RELAUNCH -> relaunch()
        }

        // Say so once per bad patch, not on every slowed-down retry.
        if (policy.isStruggling && !warnedStruggling) {
            warnedStruggling = true
            onStruggling()
        } else if (!policy.isStruggling) {
            warnedStruggling = false
        }
    }

    private fun relaunch() {
        val launch = context.packageManager.getLaunchIntentForPackage(targetPackage) ?: return
        // Exactly the flags a launcher sends when you tap an app's icon, so this does
        // whatever tapping the icon would do. REORDER_TO_FRONT used to be here, and it
        // reorders activities inside their task rather than moving the task itself -
        // the wrong thing when the task is sitting in picture-in-picture.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        lastRelaunchWallTime = System.currentTimeMillis()
        runCatching { context.startActivity(launch) }
    }

    private class Snapshot(val latest: String?, val targetReturned: Boolean)

    companion object {
        private const val POLL_MS = 500L
        private const val LOOKBACK_MS = 15_000L

        /**
         * The most recently resumed activity's package, or null if usage access is
         * missing or nothing was resumed inside the lookback window.
         */
        fun currentForegroundPackage(context: Context): String? =
            snapshot(context, targetPackage = null, sinceWallTime = 0L)?.latest

        /**
         * One pass over recent usage events: which package is in front now, and
         * whether [targetPackage] came to the front at any point since [sinceWallTime].
         * The second half is what lets a successful relaunch be recognised even if
         * the child swiped it away again before the next poll.
         */
        private fun snapshot(
            context: Context,
            targetPackage: String?,
            sinceWallTime: Long,
        ): Snapshot? {
            val usageStats = context.getSystemService(UsageStatsManager::class.java)
                ?: return null
            val end = System.currentTimeMillis()
            val lookbackStart = end - LOOKBACK_MS
            val start = if (sinceWallTime > 0L) minOf(sinceWallTime, lookbackStart) else lookbackStart
            val events = runCatching { usageStats.queryEvents(start, end) }
                .getOrNull() ?: return null
            val event = UsageEvents.Event()
            var latest: String? = null
            var targetReturned = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED under its older name, which is the same constant
                // and is the one that exists all the way back to our minSdk.
                @Suppress("DEPRECATION")
                if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                latest = event.packageName
                if (sinceWallTime > 0L &&
                    event.packageName == targetPackage &&
                    event.timeStamp >= sinceWallTime
                ) {
                    targetReturned = true
                }
            }
            return Snapshot(latest, targetReturned)
        }
    }
}
