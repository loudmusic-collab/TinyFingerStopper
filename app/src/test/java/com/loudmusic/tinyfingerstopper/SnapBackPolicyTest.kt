package com.loudmusic.tinyfingerstopper

import com.loudmusic.tinyfingerstopper.watchdog.SnapBackPolicy
import com.loudmusic.tinyfingerstopper.watchdog.SnapBackPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SnapBackPolicyTest {

    private val grace = 1_500L
    private val policy = SnapBackPolicy(relaunchGraceMillis = grace, maxFailedRelaunches = 5)

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
     * The pattern that broke on a real phone: Home, it snaps back, Home again, over
     * and over. The old watchdog counted every poll where the app was not in front
     * and released the whole lock after a handful. Every one of these relaunches
     * works, so this must go on indefinitely.
     */
    @Test
    fun `swiping Home over and over never stops the snap-back`() {
        var now = 0L
        repeat(200) {
            // Home: the launcher is in front.
            assertEquals(
                "snap-back #$it",
                Action.RELAUNCH,
                policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false),
            )
            now += 500L
            // It came back.
            assertEquals(Action.NONE, policy.decide(now, targetInFront = true, targetReturnedSinceRelaunch = true))
            now += 500L
        }
    }

    /**
     * Same thing, but the child is quick enough to swipe away again before the next
     * poll ever sees the app in front. The relaunch still worked - the usage events
     * say so - and must not be counted as a failure.
     */
    @Test
    fun `a relaunch that worked counts even if the next poll missed it`() {
        var now = 0L
        repeat(50) {
            val action = policy.decide(
                now,
                targetInFront = false,
                targetReturnedSinceRelaunch = it > 0,
            )
            assertNotEquals("poll #$it", Action.PAUSE, action)
            now += grace
        }
    }

    @Test
    fun `an app that never comes back pauses snap-back`() {
        var now = 0L
        val actions = mutableListOf<Action>()
        repeat(20) {
            actions += policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false)
            now += grace
        }
        // One initial relaunch, then four more that each fail, then give up on the fifth failure.
        assertEquals(Action.PAUSE, actions[5])
        assertEquals(5, actions.take(5).count { it == Action.RELAUNCH })
    }

    @Test
    fun `a success in between resets the failure count`() {
        var now = 0L
        repeat(4) {
            policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false)
            now += grace
        }
        policy.decide(now, targetInFront = true, targetReturnedSinceRelaunch = true)
        now += grace
        repeat(4) {
            assertNotEquals(
                Action.PAUSE,
                policy.decide(now, targetInFront = false, targetReturnedSinceRelaunch = false),
            )
            now += grace
        }
    }
}
