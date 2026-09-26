package com.loudmusic.tinyfingerstopper.watchdog

/**
 * Decides, once per poll, whether to bring the locked app back to the front.
 *
 * The distinction that matters is between two things that look alike from a
 * single poll:
 *
 *  - A child swiping Home over and over. Every relaunch works; they just keep
 *    undoing it. This must never stop the snap-back, however many times it happens.
 *  - An app that will not come back - it crashed, or a system dialog keeps winning.
 *    Relaunching forever is pointless, so after [maxFailedRelaunches] relaunches in a
 *    row that never brought it back, pause snap-back.
 *
 * Either way the lock itself stays up. Pausing snap-back only stops the relaunching;
 * it never releases the touch blocker. The only ways out of the lock are the
 * two-corner hold, the auto-unlock timer and a restart.
 *
 * Pure and clock-injected so the exact pattern that broke on a real phone - Home,
 * snap back, Home again - can be tested on the JVM.
 */
class SnapBackPolicy(
    private val relaunchGraceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val maxFailedRelaunches: Int = DEFAULT_MAX_FAILED,
) {

    enum class Action { NONE, RELAUNCH, PAUSE }

    private var lastRelaunchAt = 0L
    private var awaitingReturn = false
    private var failedRelaunches = 0

    /**
     * @param targetInFront the locked app is in front right now.
     * @param targetReturnedSinceRelaunch it came to the front at some point since the
     *   last relaunch, even if it has already been swiped away again. This is what
     *   tells a successful relaunch from a failed one when polls are far apart.
     */
    fun decide(now: Long, targetInFront: Boolean, targetReturnedSinceRelaunch: Boolean): Action {
        if (awaitingReturn && targetReturnedSinceRelaunch) {
            // The last relaunch worked, whatever has happened since.
            awaitingReturn = false
            failedRelaunches = 0
        }
        if (targetInFront) {
            awaitingReturn = false
            failedRelaunches = 0
            return Action.NONE
        }
        if (awaitingReturn && now - lastRelaunchAt < relaunchGraceMillis) {
            // Give the last relaunch time to land before judging it.
            return Action.NONE
        }
        if (awaitingReturn) {
            failedRelaunches++
            if (failedRelaunches >= maxFailedRelaunches) return Action.PAUSE
        }
        lastRelaunchAt = now
        awaitingReturn = true
        return Action.RELAUNCH
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 1_500L
        const val DEFAULT_MAX_FAILED = 5
    }
}
