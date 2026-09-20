package com.loudmusic.tinyfingerstopper.lock

import android.content.Context
import android.os.SystemClock

/**
 * A restart must always cancel the lock.
 *
 * That guarantee is structural rather than defensive. Four things make it true:
 *
 *  1. Armed state lives only in [LockController]'s memory. Nothing writes it to disk.
 *  2. The app holds no RECEIVE_BOOT_COMPLETED permission and registers no
 *     BOOT_COMPLETED receiver, so nothing of ours runs at boot at all.
 *  3. [OverlayLockService] returns START_NOT_STICKY and stops itself when handed a
 *     null intent, so the system never resurrects an armed lock after killing the
 *     process.
 *  4. The overlay window belongs to the process. When the process goes, so does it.
 *
 * `LockSafetyTest` fails the build if 2 or 3 ever stop being true.
 *
 * What is left in this file is belt and braces: a wipe of a "volatile" preferences
 * file so that lock state persisted here by mistake is still cleared on the next
 * process start, and a reboot-aware deadline check for the auto-unlock timer.
 */
object BootSafety {

    private const val VOLATILE_PREFS = "volatile_state"

    /** Called from `TinyFingerApp.onCreate`, so on every cold start. */
    fun clearVolatileState(context: Context) {
        context.getSharedPreferences(VOLATILE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Deadlines are recorded against [SystemClock.elapsedRealtime], which restarts
     * near zero at boot. If a stored deadline sits further in the future than the
     * window it was created with, the clock restarted underneath it and the deadline
     * means nothing - treat it as already expired rather than as a lock with hours
     * left to run.
     */
    fun isDeadlineExpired(deadlineElapsedRealtime: Long, windowMillis: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (deadlineElapsedRealtime - now > windowMillis) return true
        return now >= deadlineElapsedRealtime
    }
}
