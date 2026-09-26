package com.loudmusic.tinyfingerstopper.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Transparent, fills whatever it is put in, and swallows every touch that reaches it.
 *
 * Used twice: as the contents of the system overlay window in [OverlayLockService],
 * and as the top layer of Kid Player. In both cases the only way past it is the
 * two-corner hold in [UnlockGesture].
 */
@SuppressLint("ViewConstructor")
class BlockerOverlayView(
    context: Context,
    holdMillis: Long,
    private val onUnlock: () -> Unit,
) : View(context) {

    private val cornerPx = dp(UnlockGesture.CORNER_DP)
    private val gesture = UnlockGesture(holdMillis)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val arcBounds = RectF()

    private var diagonalHeld = false
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (gesture.update(diagonalHeld, SystemClock.uptimeMillis())) {
                stopTicking()
                gesture.reset()
                invalidate()
                onUnlock()
                return
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        diagonalHeld = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> pointersHoldDiagonal(event)
        }
        if (diagonalHeld) {
            startTicking()
        } else {
            stopTicking()
            gesture.reset()
            invalidate()
        }
        // Always. This view exists to be a dead end for touches.
        return true
    }

    /**
     * Swallow navigation keys, the Back gesture above all. Anything else - volume,
     * mostly - falls through to the system as normal.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SEARCH,
        -> true
        else -> super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val progress = gesture.progress
        if (progress < UnlockGesture.FEEDBACK_AFTER_FRACTION) return
        val shown = (progress - UnlockGesture.FEEDBACK_AFTER_FRACTION) /
            (1f - UnlockGesture.FEEDBACK_AFTER_FRACTION)
        arcPaint.alpha = (shown * MAX_ARC_ALPHA).toInt().coerceIn(0, 255)

        val radius = cornerPx * 0.45f
        val inset = cornerPx * 0.5f
        val sweep = 360f * progress
        drawArc(canvas, inset, inset, radius, sweep)
        drawArc(canvas, width - inset, inset, radius, sweep)
        drawArc(canvas, inset, height - inset, radius, sweep)
        drawArc(canvas, width - inset, height - inset, radius, sweep)
    }

    private fun drawArc(canvas: Canvas, cx: Float, cy: Float, radius: Float, sweep: Float) {
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcBounds, START_ANGLE, sweep, false, arcPaint)
    }

    private fun pointersHoldDiagonal(event: MotionEvent): Boolean {
        val count = event.pointerCount
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        var kept = 0
        for (i in 0 until count) {
            // A pointer that is in the act of leaving should not count as held.
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                i == event.actionIndex
            ) {
                continue
            }
            xs[kept] = event.getX(i)
            ys[kept] = event.getY(i)
            kept++
        }
        return UnlockGesture.diagonalPairHeld(xs, ys, kept, width, height, cornerPx)
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        postOnAnimation(ticker)
    }

    private fun stopTicking() {
        ticking = false
        removeCallbacks(ticker)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val START_ANGLE = -90f
        const val MAX_ARC_ALPHA = 190
    }
}
