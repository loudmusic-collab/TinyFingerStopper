package com.loudmusic.tinyfingerstopper.lock

import android.content.Context
import android.content.Intent
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single source of truth for where the lock is right now.
 *
 * In memory only, on purpose. See [BootSafety].
 *
 * Note what is deliberately missing: a `toggle`. Arming is one tap because you are
 * already holding a phone a child is reaching for, but nothing outside the overlay
 * itself may disarm. A tile that toggled both ways was a one-tap way out of the
 * lock for anyone who could open Quick Settings, which is to say for the child.
 */
object LockController {

    enum class State {
        /** Not locked. */
        IDLE,

        /** Counting down to locking, so the parent can pin the app first. */
        PENDING,

        /** Locked. Only the two-corner hold, the auto-unlock timer or a restart ends this. */
        ARMED,
    }

    fun interface Listener {
        fun onStateChanged(state: State)
    }

    @Volatile
    var state: State = State.IDLE
        private set

    val isArmed: Boolean get() = state == State.ARMED

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Registering also delivers the current state immediately. */
    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    internal fun setState(next: State) {
        if (state == next) return
        state = next
        listeners.forEach { it.onStateChanged(next) }
    }

    fun arm(context: Context) = send(context, OverlayLockService.ACTION_ARM)

    /** Only meaningful while [State.PENDING]; the countdown has not locked anything yet. */
    fun cancelPending(context: Context) = send(context, OverlayLockService.ACTION_CANCEL_PENDING)

    private fun send(context: Context, action: String) {
        val intent = Intent(context, OverlayLockService::class.java).setAction(action)
        // Starting a foreground service from a Quick Settings tile is permitted here
        // because the app holds SYSTEM_ALERT_WINDOW, an explicit exemption from the
        // Android 12+ restrictions on background foreground-service starts.
        context.startForegroundService(intent)
    }
}
