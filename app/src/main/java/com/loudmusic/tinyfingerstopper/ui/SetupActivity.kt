package com.loudmusic.tinyfingerstopper.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.loudmusic.tinyfingerstopper.R
import com.loudmusic.tinyfingerstopper.player.KidPlayerActivity
import com.loudmusic.tinyfingerstopper.prefs.Prefs
import com.loudmusic.tinyfingerstopper.tile.LockTileService
import com.loudmusic.tinyfingerstopper.watchdog.UsageAccess

/**
 * One-time setup, and the place to launch Kid Player from.
 *
 * Built in code rather than XML because it is a list of permission rows that each
 * need a live status, and that is less to keep in sync this way.
 */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private lateinit var videoInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()

        heading(getString(R.string.setup_overlay_heading))
        body(getString(R.string.setup_overlay_body))

        step(
            title = getString(R.string.step_overlay_title),
            detail = getString(R.string.step_overlay_detail),
            done = Settings.canDrawOverlays(this),
            required = true,
            action = getString(R.string.action_grant),
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            step(
                title = getString(R.string.step_notifications_title),
                detail = getString(R.string.step_notifications_detail),
                done = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                required = true,
                action = getString(R.string.action_grant),
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS,
                )
            }
        }

        step(
            title = getString(R.string.step_usage_title),
            detail = getString(R.string.step_usage_detail),
            done = UsageAccess.isGranted(this),
            required = false,
            action = getString(R.string.action_grant),
        ) {
            startActivity(UsageAccess.settingsIntent(this))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            step(
                title = getString(R.string.step_tile_title),
                detail = getString(R.string.step_tile_detail),
                done = false,
                required = false,
                action = getString(R.string.action_add_tile),
            ) {
                requestAddTile()
            }
        } else {
            body(getString(R.string.step_tile_detail_manual))
        }

        divider()
        heading(getString(R.string.setup_shade_heading))
        body(getString(R.string.setup_shade_body))

        setting(
            title = getString(R.string.setting_arm_delay_title),
            detail = getString(R.string.setting_arm_delay_detail),
            value = armDelayLabel(prefs.armDelaySeconds),
        ) {
            // Cycle rather than open a dialog; there are only three sensible values.
            prefs.armDelaySeconds = when (prefs.armDelaySeconds) {
                0 -> 5
                5 -> 10
                else -> 0
            }
            render()
        }

        divider()
        heading(getString(R.string.setup_player_heading))
        body(getString(R.string.setup_player_body))

        step(
            title = getString(R.string.step_pinning_title),
            detail = getString(R.string.step_pinning_detail),
            done = false,
            required = false,
            action = getString(R.string.action_open_settings),
        ) {
            // There is no direct deep link to the App pinning screen on every build,
            // so land on Security and say what to look for.
            val opened = runCatching {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }.isSuccess
            if (!opened) startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        videoInput = EditText(this).apply {
            hint = getString(R.string.video_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.lastVideoId.orEmpty())
        }
        container.addView(videoInput, rowParams())

        val play = Button(this).apply {
            text = getString(R.string.action_play_locked)
            setOnClickListener { launchPlayer() }
        }
        container.addView(play, rowParams())

        divider()
        body(getString(R.string.setup_unlock_help))
        body(getString(R.string.setup_reboot_help))
    }

    private fun launchPlayer() {
        val entered = videoInput.text?.toString().orEmpty()
        if (entered.isBlank()) {
            Toast.makeText(this, R.string.error_no_video_id, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, KidPlayerActivity::class.java)
                .putExtra(KidPlayerActivity.EXTRA_VIDEO, entered),
        )
    }

    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val statusBar = getSystemService(StatusBarManager::class.java) ?: return
        runCatching {
            statusBar.requestAddTileService(
                ComponentName(this, LockTileService::class.java),
                getString(R.string.tile_label_off),
                Icon.createWithResource(this, R.drawable.ic_lock),
                { runnable -> runnable.run() },
                { _ -> },
            )
        }.onFailure {
            Toast.makeText(this, R.string.step_tile_detail_manual, Toast.LENGTH_LONG).show()
        }
    }

    // --- tiny view helpers -------------------------------------------------

    private fun heading(text: String) {
        container.addView(
            TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(4))
            },
            rowParams(),
        )
    }

    private fun body(text: String) {
        container.addView(
            TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, dp(8))
            },
            rowParams(),
        )
    }

    private fun divider() {
        container.addView(
            View(this).apply { setBackgroundColor(Color.LTGRAY) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply { topMargin = dp(20); bottomMargin = dp(4) },
        )
    }

    private fun step(
        title: String,
        detail: String,
        done: Boolean,
        required: Boolean,
        action: String,
        onClick: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val marker = when {
            done -> getString(R.string.status_done)
            required -> getString(R.string.status_required)
            else -> getString(R.string.status_optional)
        }
        row.addView(
            TextView(this).apply {
                text = "$marker  $title"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        row.addView(
            TextView(this).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, dp(6))
            },
        )
        if (!done) {
            row.addView(
                Button(this).apply {
                    text = action
                    setOnClickListener { onClick() }
                }.also { it.gravity = Gravity.CENTER },
            )
        }
        container.addView(row, rowParams())
    }

    private fun armDelayLabel(seconds: Int): String = if (seconds <= 0) {
        getString(R.string.setting_arm_delay_none)
    } else {
        getString(R.string.setting_arm_delay_seconds, seconds)
    }

    private fun setting(
        title: String,
        detail: String,
        value: String,
        onClick: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(
            TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        row.addView(
            TextView(this).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, dp(6))
            },
        )
        row.addView(
            Button(this).apply {
                text = value
                setOnClickListener { onClick() }
            },
        )
        container.addView(row, rowParams())
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}
