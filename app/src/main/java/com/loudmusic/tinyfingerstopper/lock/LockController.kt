package com.loudmusic.tinyfingerstopper.lock

import android.content.Context
import android.content.Intent
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single source of truth for "is the screen locked right now".
 *
 * In memory only, on purpose. See [BootSafety].
 */
object LockController {

    fun interface Listener {
        fun onArmedChanged(armed: Boolean)
    }

    @Volatile
    var isArmed: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Registering also delivers the current state immediately. */
    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onArmedChanged(isArmed)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    internal fun setArmed(armed: Boolean) {
        if (isArmed == armed) return
        isArmed = armed
        listeners.forEach { it.onArmedChanged(armed) }
    }

    fun arm(context: Context) = send(context, OverlayLockService.ACTION_ARM)

    fun disarm(context: Context) = send(context, OverlayLockService.ACTION_DISARM)

    fun toggle(context: Context) {
        if (isArmed) disarm(context) else arm(context)
    }

    private fun send(context: Context, action: String) {
        val intent = Intent(context, OverlayLockService::class.java).setAction(action)
        // Starting a foreground service from a Quick Settings tile is permitted here
        // because the app holds SYSTEM_ALERT_WINDOW, an explicit exemption from the
        // Android 12+ restrictions on background foreground-service starts.
        context.startForegroundService(intent)
    }
}
