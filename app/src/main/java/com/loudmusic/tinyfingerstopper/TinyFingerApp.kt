package com.loudmusic.tinyfingerstopper

import android.app.Application
import com.loudmusic.tinyfingerstopper.lock.BootSafety

class TinyFingerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Runs on every cold process start, which includes the first start after a
        // reboot. Backstop for the invariant documented in BootSafety.
        BootSafety.clearVolatileState(this)
    }
}
