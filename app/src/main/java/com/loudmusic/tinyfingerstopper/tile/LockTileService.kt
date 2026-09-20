package com.loudmusic.tinyfingerstopper.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.loudmusic.tinyfingerstopper.R
import com.loudmusic.tinyfingerstopper.lock.LockController
import com.loudmusic.tinyfingerstopper.ui.SetupActivity

/**
 * The Quick Settings toggle.
 *
 * Arming is one tap because it has to be - you are already holding a phone a child
 * is reaching for. Disarming is deliberately not here: it is the two-corner hold on
 * the overlay itself, or the notification's Unlock action.
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

    override fun onArmedChanged(armed: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (armed) R.drawable.ic_lock else R.drawable.ic_unlock,
        )
        tile.label = getString(if (armed) R.string.tile_label_on else R.string.tile_label_off)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle =
                getString(if (armed) R.string.tile_subtitle_on else R.string.tile_subtitle_off)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            openSetup()
            return
        }
        LockController.toggle(this)
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
