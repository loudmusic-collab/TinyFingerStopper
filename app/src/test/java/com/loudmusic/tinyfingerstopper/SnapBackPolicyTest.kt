package com.loudmusic.tinyfingerstopper

import com.loudmusic.tinyfingerstopper.watchdog.SnapBackPolicy
import com.loudmusic.tinyfingerstopper.watchdog.SnapBackPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapBackPolicyTest {

    private val grace = 1_500L
    private val backoff = 5_000L
    private val policy = SnapBackPolicy(
        relaunchGraceMillis = grace,
        maxFailedRelaunches = 5,
        backoffMillis = backoff,
    )

    @Test
    fun `leaves the app alone while it is in front`() {
        assertEquals(Action.NONE, policy.decide(0L, targetInFront = true, targetReturnedSinceRelaunch = false))
    }

    @Test
    fun `relaunches when something else takes the front`() {
        assertEquals(Action.RELAUNCH, policy.decide(0L, targetInFront = false, targetReturnedSinceRelaunch = false))
    }

    @Test
    fun `does not relaunch again while the last one is still landing`() {
        policy.decide(0L, targetInFront = false, targetReturnedSinceRelaunch = false)
        assertEquals(Action.NONE, policy.decide(500L, targetInFront = false, targetReturnedSinceRelaunch = false))
        assertEquals(Action.NONE, policy.decide(1_000L, targetInFront = false, targetReturnedSinceRelaunch = false))
    }

    /**
     * Seen on a real phone: Home, it snaps back, Home again, over and over. An early
     * version counted every poll where the app was not in front and released the
     * whole lock after a handful. Every one of these relaunches works, so this must
     * go on indefinitely, at full speed.
     */
    @Test
    fun `swiping Home over and over never slows the snap-back`() {
        var now = 0L
        repeat(200) {
            assertEquals(
                "snap-back #$it",
                Action.RELAUNCH,
                policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false),
            )
            now += 500L
            assertEquals(Action.NONE, policy.decide(now, targetInFront = true, targetReturnedSinceRelaunch = true))
            assertFalse(policy.isStruggling)
            now += 500L
        }
    }

    /**
     * Same, but swiped away again before the next poll ever sees the app in front.
     * The relaunch still worked - the usage events say so - and must not count as
     * a failure.
     */
    @Test
    fun `a relaunch that worked counts even if the next poll missed it`() {
        var now = 0L
        repeat(50) {
            policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = it > 0)
            assertFalse("poll #$it", policy.isStruggling)
            now += grace
        }
    }

    /**
     * Also seen on a real phone: Home made YouTube drop into picture-in-picture,
     * which no relaunch could undo, and an earlier version stopped snapping back
     * for good. Now it slows down and keeps trying.
     */
    @Test
    fun `a relaunch that keeps failing slows down but never stops`() {
        var now = 0L
        val relaunchTimes = mutableListOf<Long>()
        // Poll every half second for two minutes; nothing ever comes back.
        while (now <= 120_000L) {
            if (policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false) == Action.RELAUNCH) {
                relaunchTimes += now
            }
            now += 500L
        }
        assertTrue(policy.isStruggling)

        // The first few come at the grace interval...
        val early = relaunchTimes.take(6).zipWithNext { a, b -> b - a }
        assertTrue("early gaps $early", early.all { it in grace..grace + 500L })
        // ...then it settles to the backoff interval, and is still going at the end.
        val late = relaunchTimes.takeLast(5).zipWithNext { a, b -> b - a }
        assertTrue("late gaps $late", late.all { it in backoff..backoff + 500L })
        assertTrue("still relaunching near the end", relaunchTimes.last() > 110_000L)
    }

    @Test
    fun `one success clears the slowdown`() {
        var now = 0L
        while (!policy.isStruggling) {
            policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false)
            now += grace
        }
        policy.decide(now, targetInFront = true, targetReturnedSinceRelaunch = true)
        assertFalse(policy.isStruggling)

        // Back to full speed: an escape now is relaunched straight away.
        now += 500L
        assertEquals(Action.RELAUNCH, policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false))
        now += grace
        assertEquals(Action.RELAUNCH, policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false))
    }
}
