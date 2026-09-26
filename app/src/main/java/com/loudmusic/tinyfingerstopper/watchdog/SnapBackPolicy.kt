package com.loudmusic.tinyfingerstopper.watchdog

/**
 * Decides, once per poll, whether to bring the locked app back to the front.
 *
 * The distinction that matters is between two things that look alike from a
 * single poll:
 *
 *  - A child swiping Home over and over. Every relaunch works; they just keep
 *    undoing it. This must never slow the snap-back, however many times it happens.
 *  - A relaunch that is not landing - the app dropped into picture-in-picture, or a
 *    system dialog is winning. Hammering it twice a second is pointless, so after
 *    [maxFailedRelaunches] in a row that never brought it back, slow down to one try
 *    every [backoffMillis]. Never stop: what failed a moment ago can work now.
 *
 * Either way the lock itself stays up. The policy only decides when to relaunch;
 * nothing here can release the touch blocker. The ways out of the lock are the
 * two-corner hold, the auto-unlock timer and a restart.
 *
 * Pure and clock-injected so the patterns seen on a real phone can be tested on
 * the JVM.
 */
class SnapBackPolicy(
    private val relaunchGraceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val maxFailedRelaunches: Int = DEFAULT_MAX_FAILED,
    private val backoffMillis: Long = DEFAULT_BACKOFF_MILLIS,
) {

    enum class Action { NONE, RELAUNCH }

    private var lastRelaunchAt = 0L
    private var awaitingReturn = false
    private var failedRelaunches = 0

    /** Relaunches have failed often enough in a row that the policy has slowed down. */
    val isStruggling: Boolean get() = failedRelaunches >= maxFailedRelaunches

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
        val wait = if (isStruggling) backoffMillis else relaunchGraceMillis
        if (awaitingReturn && now - lastRelaunchAt < wait) {
            // Give the last relaunch time to land before judging it.
            return Action.NONE
        }
        if (awaitingReturn) failedRelaunches++
        lastRelaunchAt = now
        awaitingReturn = true
        return Action.RELAUNCH
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 1_500L
        const val DEFAULT_MAX_FAILED = 5
        const val DEFAULT_BACKOFF_MILLIS = 5_000L
    }
}
