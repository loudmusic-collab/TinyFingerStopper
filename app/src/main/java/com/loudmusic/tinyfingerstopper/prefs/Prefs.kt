package com.loudmusic.tinyfingerstopper.prefs

import android.content.Context
import java.security.MessageDigest

/**
 * Durable user preferences.
 *
 * This file stores how the lock should behave. It never stores whether the lock
 * is currently armed - that lives only in memory, so a restart always cancels it.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** How long the two-corner hold must be held before the lock releases. */
    var holdMillis: Long
        get() = sp.getLong(KEY_HOLD_MILLIS, DEFAULT_HOLD_MILLIS)
        set(value) = sp.edit().putLong(KEY_HOLD_MILLIS, value).apply()

    /**
     * Seconds to wait after the tile is tapped before the lock actually goes up.
     * A delay is what makes it possible to pin the app from Recents first, which is
     * the only thing that keeps the notification shade shut. 0 locks immediately.
     */
    var armDelaySeconds: Int
        get() = sp.getInt(KEY_ARM_DELAY_SECONDS, 0)
        set(value) = sp.edit().putInt(KEY_ARM_DELAY_SECONDS, value).apply()

    /** Release the lock unattended after this many minutes. 0 disables it. */
    var autoUnlockMinutes: Int
        get() = sp.getInt(KEY_AUTO_UNLOCK_MINUTES, DEFAULT_AUTO_UNLOCK_MINUTES)
        set(value) = sp.edit().putInt(KEY_AUTO_UNLOCK_MINUTES, value).apply()

    /** Keep the display awake for as long as the lock is armed. */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** Bring the locked app back to the front if something navigates away from it. */
    var snapBackEnabled: Boolean
        get() = sp.getBoolean(KEY_SNAP_BACK, true)
        set(value) = sp.edit().putBoolean(KEY_SNAP_BACK, value).apply()

    /** Last video played in Kid Player, so the setup screen can offer it again. */
    var lastVideoId: String?
        get() = sp.getString(KEY_LAST_VIDEO, null)
        set(value) = sp.edit().putString(KEY_LAST_VIDEO, value).apply()

    val hasPin: Boolean get() = sp.getString(KEY_PIN_HASH, null) != null

    /**
     * Stored as a salted hash. This is a child lock, not a security boundary, but
     * there is no reason to leave the PIN sitting in plain text either.
     */
    fun setPin(pin: String?) {
        if (pin.isNullOrEmpty()) {
            sp.edit().remove(KEY_PIN_HASH).apply()
        } else {
            sp.edit().putString(KEY_PIN_HASH, hash(pin)).apply()
        }
    }

    fun checkPin(pin: String): Boolean {
        val stored = sp.getString(KEY_PIN_HASH, null) ?: return true
        return stored == hash(pin)
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest((SALT + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val NAME = "settings"
        const val DEFAULT_HOLD_MILLIS = 2_000L
        const val DEFAULT_AUTO_UNLOCK_MINUTES = 90

        private const val SALT = "tinyfingerstopper:v1:"
        private const val KEY_HOLD_MILLIS = "hold_millis"
        private const val KEY_ARM_DELAY_SECONDS = "arm_delay_seconds"
        private const val KEY_AUTO_UNLOCK_MINUTES = "auto_unlock_minutes"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SNAP_BACK = "snap_back"
        private const val KEY_LAST_VIDEO = "last_video_id"
        private const val KEY_PIN_HASH = "pin_hash"
    }
}
