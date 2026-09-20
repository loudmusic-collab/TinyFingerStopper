package com.loudmusic.tinyfingerstopper.lock

/**
 * "Hold two opposite corners."
 *
 * Two fingers in diagonally opposite corner zones, held still for a while. Chosen
 * because a small hand cannot span the diagonal of a phone, so it is not something
 * a child stumbles into by mashing the screen, and it needs no on-screen affordance
 * that would teach them where to press.
 *
 * The geometry is a pure function so it can be tested on the JVM.
 */
class UnlockGesture(private val holdMillis: Long) {

    /** 0f when no hold is underway, otherwise how far through it is, 0f..1f. */
    var progress: Float = 0f
        private set

    private var holdStartedAt = 0L

    /**
     * @param diagonalHeld whether opposite corners are held right now.
     * @return true the moment the hold has lasted long enough.
     */
    fun update(diagonalHeld: Boolean, now: Long): Boolean {
        if (!diagonalHeld) {
            reset()
            return false
        }
        if (holdStartedAt == 0L) holdStartedAt = now
        val held = now - holdStartedAt
        progress = (held.toFloat() / holdMillis).coerceIn(0f, 1f)
        return held >= holdMillis
    }

    fun reset() {
        holdStartedAt = 0L
        progress = 0f
    }

    companion object {

        /** Side length of each corner hit zone, in dp. */
        const val CORNER_DP = 88f

        /**
         * Nothing is drawn until the hold is this far along, so a child who happens
         * to rest two fingers somewhere never sees that the corners do anything.
         */
        const val FEEDBACK_AFTER_FRACTION = 0.35f

        /**
         * @param count how many of [xs]/[ys] are real pointers.
         * @return true if at least one diagonally opposite pair of corners is held.
         */
        fun diagonalPairHeld(
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
            width: Int,
            height: Int,
            cornerPx: Float,
        ): Boolean {
            var topLeft = false
            var topRight = false
            var bottomLeft = false
            var bottomRight = false
            for (i in 0 until count) {
                val left = xs[i] <= cornerPx
                val right = xs[i] >= width - cornerPx
                val top = ys[i] <= cornerPx
                val bottom = ys[i] >= height - cornerPx
                if (left && top) topLeft = true
                if (right && top) topRight = true
                if (left && bottom) bottomLeft = true
                if (right && bottom) bottomRight = true
            }
            return (topLeft && bottomRight) || (topRight && bottomLeft)
        }
    }
}
