package com.loudmusic.tinyfingerstopper.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.loudmusic.tinyfingerstopper.R
import com.loudmusic.tinyfingerstopper.prefs.Prefs
import com.loudmusic.tinyfingerstopper.tile.LockTileService
import com.loudmusic.tinyfingerstopper.watchdog.ForegroundAppWatchdog
import com.loudmusic.tinyfingerstopper.watchdog.UsageAccess

/**
 * Owns the system overlay for as long as the lock is armed.
 *
 * Nothing about the armed state is written to disk, and the service refuses to
 * re-arm itself on a system restart, so a reboot or a low-memory kill always
 * leaves the phone unlocked. See [BootSafety].
 *
 * The ongoing notification carries no unlock button. Android puts the notification
 * shade above any app overlay, so anything tappable there is a one-tap way out of
 * the lock for whoever is holding the phone.
 */
class OverlayLockService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: BlockerOverlayView? = null
    private var watchdog: ForegroundAppWatchdog? = null
    private var autoUnlockDeadline = 0L
    private var autoUnlockWindow = 0L
    private var secondsUntilArmed = 0

    private val countdownTick = object : Runnable {
        override fun run() {
            secondsUntilArmed--
            if (secondsUntilArmed <= 0) {
                addOverlayAndArm()
            } else {
                notificationManager.notify(NOTIFICATION_ID, pendingNotification(secondsUntilArmed))
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private val autoUnlockCheck = object : Runnable {
        override fun run() {
            if (BootSafety.isDeadlineExpired(autoUnlockDeadline, autoUnlockWindow)) {
                disarm()
            } else {
                // postDelayed runs on uptime, which stops in deep sleep, so re-check
                // rather than trusting a single long delay to land on time.
                handler.postDelayed(this, AUTO_UNLOCK_RECHECK_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us on its own after killing the
        // process. Arming is only ever a deliberate act, so never infer one here.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_ARM -> handleArm()
            ACTION_CANCEL_PENDING -> cancelPending()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // If the process is killed instead, the overlay window dies with it, which
        // is exactly the behaviour we want. This is just the orderly path.
        teardown()
        super.onDestroy()
    }

    private fun handleArm() {
        if (LockController.state != LockController.State.IDLE) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.error_no_overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val delay = prefs.armDelaySeconds
        if (delay <= 0) {
            startInForeground(armedNotification())
            addOverlayAndArm()
            return
        }

        // Give the parent a window to pin the app from Recents first. Screen pinning
        // is the only thing that actually keeps the shade shut, and it cannot be
        // started from here because only the app being pinned can ask for it.
        secondsUntilArmed = delay
        startInForeground(pendingNotification(delay))
        LockController.setState(LockController.State.PENDING)
        requestTileUpdate()
        handler.postDelayed(countdownTick, 1_000L)
    }

    private fun addOverlayAndArm() {
        handler.removeCallbacks(countdownTick)
        if (overlay != null) return

        val view = BlockerOverlayView(this, prefs.holdMillis) { disarm() }
        if (runCatching { windowManager.addView(view, overlayParams()) }.isFailure) {
            Toast.makeText(this, R.string.error_overlay_failed, Toast.LENGTH_LONG).show()
            LockController.setState(LockController.State.IDLE)
            requestTileUpdate()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        overlay = view

        notificationManager.notify(NOTIFICATION_ID, armedNotification())
        startAutoUnlock()
        startWatchdog()

        LockController.setState(LockController.State.ARMED)
        requestTileUpdate()
    }

    private fun cancelPending() {
        if (LockController.state != LockController.State.PENDING) return
        // Nothing is locked yet, so there is nothing to protect here.
        teardown()
        requestTileUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun disarm() {
        teardown()
        requestTileUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        handler.removeCallbacks(countdownTick)
        handler.removeCallbacks(autoUnlockCheck)
        watchdog?.stop()
        watchdog = null
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        LockController.setState(LockController.State.IDLE)
    }

    private fun startAutoUnlock() {
        val minutes = prefs.autoUnlockMinutes
        if (minutes <= 0) return
        autoUnlockWindow = minutes * 60_000L
        autoUnlockDeadline = SystemClock.elapsedRealtime() + autoUnlockWindow
        handler.postDelayed(autoUnlockCheck, autoUnlockWindow)
    }

    private fun startWatchdog() {
        if (!prefs.snapBackEnabled || !UsageAccess.isGranted(this)) return
        val target = ForegroundAppWatchdog.currentForegroundPackage(this)
        // Nothing to snap back to if we cannot tell what was in front, or if what
        // was in front was us.
        if (target == null || target == packageName) return
        watchdog = ForegroundAppWatchdog(this, target) {
            Toast.makeText(this, R.string.snap_back_gave_up, Toast.LENGTH_LONG).show()
            disarm()
        }.also { it.start() }
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (prefs.keepScreenOn) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Reach into the cutout so the notch corners are covered too.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun armedNotification(): Notification = baseNotification()
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setStyle(
            Notification.BigTextStyle().bigText(getString(R.string.notification_text)),
        )
        .build()

    private fun pendingNotification(seconds: Int): Notification = baseNotification()
        .setContentTitle(getString(R.string.notification_pending_title, seconds))
        .setContentText(getString(R.string.notification_pending_text))
        .setStyle(
            Notification.BigTextStyle()
                .bigText(getString(R.string.notification_pending_text)),
        )
        .build()

    /**
     * No actions and no content intent. Everything tappable in the shade is
     * reachable by whoever is holding the phone, so the shade gets to be a status
     * display and nothing more.
     */
    private fun baseNotification(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)

    private fun requestTileUpdate() {
        runCatching {
            TileService.requestListeningState(
                this,
                ComponentName(this, LockTileService::class.java),
            )
        }
    }

    companion object {
        const val ACTION_ARM = "com.loudmusic.tinyfingerstopper.ARM"
        const val ACTION_CANCEL_PENDING = "com.loudmusic.tinyfingerstopper.CANCEL_PENDING"

        private const val CHANNEL_ID = "lock_status"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_UNLOCK_RECHECK_MS = 30_000L
    }
}
