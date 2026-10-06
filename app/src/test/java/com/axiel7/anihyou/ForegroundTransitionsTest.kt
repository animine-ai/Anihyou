package com.axiel7.anihyou

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTransitionsTest {
    private class Clockwork {
        val queue = mutableListOf<Runnable>()
        val post: (Runnable, Long) -> Unit = { runnable, _ -> queue += runnable }
        val cancel: (Runnable) -> Unit = { runnable -> queue.remove(runnable) }
        fun elapse() { queue.toList().also { queue.clear() }.forEach(Runnable::run) }
    }

    @Test fun theFirstStartedActivityIsAForegroundTransitionAndANestedStartIsNot() {
        var count = 0
        val clock = Clockwork()
        val tracker = ForegroundTransitions(postDelayed = clock.post, cancel = clock.cancel) { count++ }
        tracker.activityStarted()
        tracker.activityStarted()
        assertEquals(1, count)
    }

    @Test fun aRotationThatRestartsTheActivityWithinTheDebounceIsNotATransition() {
        var count = 0
        val clock = Clockwork()
        val tracker = ForegroundTransitions(postDelayed = clock.post, cancel = clock.cancel) { count++ }
        tracker.activityStarted()
        tracker.activityStopped()
        tracker.activityStarted() // recreated before the debounce ran
        clock.elapse()
        assertEquals(1, count)
    }

    @Test fun comingBackAfterTheDebounceIsANewTransition() {
        var count = 0
        val clock = Clockwork()
        val tracker = ForegroundTransitions(postDelayed = clock.post, cancel = clock.cancel) { count++ }
        tracker.activityStarted()
        tracker.activityStopped()
        clock.elapse() // the app is now in the background
        tracker.activityStarted()
        assertEquals(2, count)
    }

    @Test fun anActivityStoppingWhileAnotherIsStillStartedKeepsTheAppInTheForeground() {
        var count = 0
        val clock = Clockwork()
        val tracker = ForegroundTransitions(postDelayed = clock.post, cancel = clock.cancel) { count++ }
        tracker.activityStarted()
        tracker.activityStarted()
        tracker.activityStopped()
        clock.elapse()
        tracker.activityStarted()
        assertEquals(1, count)
    }
}
