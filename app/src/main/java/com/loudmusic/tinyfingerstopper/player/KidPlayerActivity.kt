package com.loudmusic.tinyfingerstopper.player

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

    /**
     * YouTube refuses to play an embed it cannot attribute to an embedding site or
     * app (errors 150 to 153). Apps identify themselves as `https://<application-id>/`,
     * and for a page loaded with loadDataWithBaseURL the base URL is what becomes the
     * Referer. This used to be https://www.youtube.com, which claims to be YouTube
     * itself, and YouTube rejected it.
     */
    private val appOrigin: String get() = "https://$packageName"

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
            webViewClient = StayOnPlayer()
            addJavascriptInterface(PlayerBridge(), BRIDGE_NAME)
        }
        root.addView(web, matchParent())

        blocker = BlockerOverlayView(this, Prefs(this).holdMillis) { unlock() }
        blocker.visibility = View.GONE
        root.addView(blocker, matchParent())

        setContentView(root)
        web.loadDataWithBaseURL("$appOrigin/", playerHtml(videoId), "text/html", "utf-8", null)
        // No lock yet. It goes on when the player reports that the video is actually
        // playing, so a video YouTube refuses to play never leaves you pinned to an
        // error screen.
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
    }

    override fun onDestroy() {
        if (locked) unlock()
        // onCreate bails out before building the WebView when there is no video id.
        if (::web.isInitialized) web.destroy()
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

    private fun onPlayerError(code: Int) {
        val message = when (code) {
            101, 150 -> getString(R.string.player_error_embedding_disabled)
            152, 153 -> getString(R.string.player_error_rejected, code)
            100 -> getString(R.string.player_error_unavailable)
            else -> getString(R.string.player_error_generic, code)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        // Only give up if the lock never went on. Once it is on, the ways out stay
        // what they always are: the corner hold, the unpin gesture, or a restart.
        if (!locked) finish()
    }

    /**
     * Keeps everything inside the player. Links in the embed - the YouTube logo,
     * "Watch on YouTube", end screens - navigate the top frame or hand off to the
     * YouTube app, and either would be a way out. The player's own frame still loads.
     */
    private class StayOnPlayer : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame
    }

    /** The page's only way to talk back. Called on a WebView thread. */
    private inner class PlayerBridge {
        @JavascriptInterface
        fun onPlaying() {
            runOnUiThread { lock() }
        }

        @JavascriptInterface
        fun onPlayerError(code: Int) {
            runOnUiThread { this@KidPlayerActivity.onPlayerError(code) }
        }
    }

    private fun resolveVideoId(): String? {
        val fromExtra = intent?.getStringExtra(EXTRA_VIDEO)
        val fromShare = intent?.getStringExtra(Intent.EXTRA_TEXT)
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

    private fun playerHtml(videoId: String) = PLAYER_HTML
        .replace(VIDEO_ID_TOKEN, videoId)
        .replace(ORIGIN_TOKEN, appOrigin)

    companion object {
        const val EXTRA_VIDEO = "com.loudmusic.tinyfingerstopper.VIDEO"

        private const val BRIDGE_NAME = "TinyFinger"
        private const val VIDEO_ID_TOKEN = "__VIDEO_ID__"
        private const val ORIGIN_TOKEN = "__ORIGIN__"

        // The official IFrame Player API, which is the supported way to embed a
        // YouTube video in your own surface. The referrer policy is spelled out
        // rather than left to the WebView's default, because a Referer that never
        // arrives is the other common cause of YouTube's configuration errors.
        private val PLAYER_HTML = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport"
                    content="width=device-width, initial-scale=1, user-scalable=no">
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <style>
                html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
                #player { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
              </style>
            </head>
            <body>
              <div id="player"></div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                function bridge() {
                  return typeof TinyFinger === 'undefined' ? null : TinyFinger;
                }
                function onYouTubeIframeAPIReady() {
                  new YT.Player('player', {
                    videoId: '__VIDEO_ID__',
                    playerVars: {
                      autoplay: 1,
                      playsinline: 1,
                      rel: 0,
                      controls: 1,
                      origin: '__ORIGIN__',
                      widget_referrer: '__ORIGIN__'
                    },
                    events: {
                      onReady: function (e) { e.target.playVideo(); },
                      onStateChange: function (e) {
                        if (e.data === YT.PlayerState.PLAYING && bridge()) bridge().onPlaying();
                      },
                      onError: function (e) {
                        if (bridge()) bridge().onPlayerError(e.data);
                      }
                    }
                  });
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
