package com.loudmusic.tinyfingerstopper.player

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import com.loudmusic.tinyfingerstopper.R
import com.loudmusic.tinyfingerstopper.lock.BlockerOverlayView
import com.loudmusic.tinyfingerstopper.prefs.Prefs

/**
 * The stronger of the two lock modes, and the one that needs no special permission.
 *
 * Because the video plays inside our own activity we can use screen pinning, which
 * genuinely blocks Home, Recents and the notification shade rather than merely
 * healing after them. With "Ask for PIN before unpinning" turned on in Android's
 * own security settings, getting out needs the device PIN.
 *
 * Screen pinning does not survive a reboot, so the restart-cancels-the-lock
 * guarantee holds here for free.
 */
class KidPlayerActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var blocker: BlockerOverlayView
    private var locked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoId = resolveVideoId()
        if (videoId == null) {
            Toast.makeText(this, R.string.error_no_video_id, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Prefs(this).lastVideoId = videoId

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // The IFrame player asks for fullscreen; without a chrome client that
            // request is silently dropped.
            webChromeClient = WebChromeClient()
        }
        root.addView(web, matchParent())

        blocker = BlockerOverlayView(this, Prefs(this).holdMillis) { unlock() }
        blocker.visibility = View.GONE
        root.addView(blocker, matchParent())

        setContentView(root)
        web.loadDataWithBaseURL(BASE_URL, playerHtml(videoId), "text/html", "utf-8", null)

        // Give the video a moment to start before the screen stops accepting input.
        web.postDelayed({ lock() }, LOCK_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
    }

    override fun onDestroy() {
        if (locked) unlock()
        web.destroy()
        super.onDestroy()
    }

    /** While locked, swallow hardware keys too, so volume cannot be wound to zero. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (locked) return true
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (locked) return
        super.onBackPressed()
    }

    private fun lock() {
        if (locked) return
        locked = true
        blocker.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Without device owner this is ordinary screen pinning: the system asks the
        // user to confirm, and it can be left with the documented unpin gesture.
        runCatching { startLockTask() }
        goImmersive()
    }

    private fun unlock() {
        if (!locked) return
        locked = false
        blocker.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val activityManager = getSystemService(ActivityManager::class.java)
        if (activityManager?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { stopLockTask() }
        }
    }

    private fun resolveVideoId(): String? {
        val fromExtra = intent?.getStringExtra(EXTRA_VIDEO)
        val fromShare = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        val fromData = intent?.dataString
        return YouTubeLinks.extractVideoId(fromExtra)
            ?: YouTubeLinks.extractVideoId(fromShare)
            ?: YouTubeLinks.extractVideoId(fromData)
    }

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun playerHtml(videoId: String) = PLAYER_HTML.replace(VIDEO_ID_TOKEN, videoId)

    companion object {
        const val EXTRA_VIDEO = "com.loudmusic.tinyfingerstopper.VIDEO"

        private const val BASE_URL = "https://www.youtube.com"
        private const val VIDEO_ID_TOKEN = "__VIDEO_ID__"
        private const val LOCK_DELAY_MS = 1_500L

        // The official IFrame Player API, which is the supported way to embed a
        // YouTube video in your own surface.
        private val PLAYER_HTML = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport"
                    content="width=device-width, initial-scale=1, user-scalable=no">
              <style>
                html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
                #player { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
              </style>
            </head>
            <body>
              <div id="player"></div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                function onYouTubeIframeAPIReady() {
                  new YT.Player('player', {
                    videoId: '__VIDEO_ID__',
                    playerVars: { autoplay: 1, playsinline: 1, rel: 0, controls: 1 },
                    events: {
                      onReady: function (e) { e.target.playVideo(); }
                    }
                  });
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
