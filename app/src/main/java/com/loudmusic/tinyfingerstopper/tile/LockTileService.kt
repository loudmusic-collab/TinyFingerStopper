package com.loudmusic.tinyfingerstopper.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.loudmusic.tinyfingerstopper.R
import com.loudmusic.tinyfingerstopper.lock.LockController
import com.loudmusic.tinyfingerstopper.ui.SetupActivity

/**
 * The Quick Settings toggle - except it only goes one way.
 *
 * Arming is one tap. Disarming is not here at all: anyone who can pull down the
 * shade can reach this tile, and that includes the child holding the phone. The
 * ways out are the two-corner hold on the overlay, the auto-unlock timer, and
 * restarting the phone.
 */
class LockTileService : TileService(), LockController.Listener {

    override fun onStartListening() {
        super.onStartListening()
        // Registering delivers the current state straight away, so the tile is never
        // stale after the app's process has been killed and restarted.
        LockController.addListener(this)
    }

    override fun onStopListening() {
        LockController.removeListener(this)
        super.onStopListening()
    }

    override fun onStateChanged(state: LockController.State) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            LockController.State.IDLE -> Tile.STATE_INACTIVE
            LockController.State.PENDING, LockController.State.ARMED -> Tile.STATE_ACTIVE
        }
        val labelRes = when (state) {
            LockController.State.IDLE -> R.string.tile_label_off
            LockController.State.PENDING -> R.string.tile_label_pending
            LockController.State.ARMED -> R.string.tile_label_on
        }
        val subtitleRes = when (state) {
            LockController.State.IDLE -> R.string.tile_subtitle_off
            LockController.State.PENDING -> R.string.tile_subtitle_pending
            LockController.State.ARMED -> R.string.tile_subtitle_on
        }
        tile.icon = Icon.createWithResource(
            this,
            if (state == LockController.State.IDLE) R.drawable.ic_unlock else R.drawable.ic_lock,
        )
        tile.label = getString(labelRes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(subtitleRes)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (LockController.state) {
            LockController.State.ARMED ->
                // Deliberately inert. Say what does work instead.
                Toast.makeText(this, R.string.tile_hint_armed, Toast.LENGTH_LONG).show()

            LockController.State.PENDING ->
                // Nothing is locked yet, so backing out is still free.
                LockController.cancelPending(this)

            LockController.State.IDLE ->
                if (Settings.canDrawOverlays(this)) {
                    LockController.arm(this)
                } else {
                    openSetup()
                }
        }
    }

    private fun openSetup() {
        val intent = Intent(this, SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws from Android 14 onwards.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
