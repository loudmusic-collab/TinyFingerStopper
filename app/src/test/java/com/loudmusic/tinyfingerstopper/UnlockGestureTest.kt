package com.loudmusic.tinyfingerstopper

import com.loudmusic.tinyfingerstopper.lock.UnlockGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockGestureTest {

    private val width = 1080
    private val height = 2400
    private val corner = 200f

    private fun held(vararg points: Pair<Float, Float>): Boolean {
        val xs = points.map { it.first }.toFloatArray()
        val ys = points.map { it.second }.toFloatArray()
        return UnlockGesture.diagonalPairHeld(xs, ys, points.size, width, height, corner)
    }

    @Test
    fun `opposite corners count`() {
        assertTrue(held(10f to 10f, 1070f to 2390f))
        assertTrue(held(1070f to 10f, 10f to 2390f))
    }

    @Test
    fun `adjacent corners do not count`() {
        assertFalse(held(10f to 10f, 1070f to 10f))
        assertFalse(held(10f to 10f, 10f to 2390f))
    }

    @Test
    fun `one finger never unlocks however it moves`() {
        assertFalse(held(10f to 10f))
        assertFalse(held(1070f to 2390f))
    }

    @Test
    fun `a handful of fingers in the middle does nothing`() {
        assertFalse(held(500f to 1200f, 600f to 1300f, 400f to 1100f, 700f to 900f))
    }

    @Test
    fun `an extra middle finger does not spoil a real diagonal`() {
        assertTrue(held(10f to 10f, 540f to 1200f, 1070f to 2390f))
    }

    @Test
    fun `hold has to last the full duration`() {
        val gesture = UnlockGesture(holdMillis = 2_000L)
        assertFalse(gesture.update(diagonalHeld = true, now = 1_000L))
        assertFalse(gesture.update(diagonalHeld = true, now = 2_500L))
        assertTrue(gesture.update(diagonalHeld = true, now = 3_000L))
    }

    @Test
    fun `letting go restarts the hold`() {
        val gesture = UnlockGesture(holdMillis = 2_000L)
        gesture.update(diagonalHeld = true, now = 1_000L)
        gesture.update(diagonalHeld = false, now = 2_000L)
        assertEquals(0f, gesture.progress, 0.001f)
        // The clock has moved well past the original deadline, but the hold that
        // matters started again at 2_500.
        assertFalse(gesture.update(diagonalHeld = true, now = 2_500L))
        assertFalse(gesture.update(diagonalHeld = true, now = 4_000L))
        assertTrue(gesture.update(diagonalHeld = true, now = 4_500L))
    }

    @Test
    fun `no feedback is drawn early in the hold`() {
        val gesture = UnlockGesture(holdMillis = 2_000L)
        gesture.update(diagonalHeld = true, now = 0L)
        gesture.update(diagonalHeld = true, now = 400L)
        assertTrue(
            "A child resting two fingers should never see that the corners do anything",
            gesture.progress < UnlockGesture.FEEDBACK_AFTER_FRACTION,
        )
    }
}
