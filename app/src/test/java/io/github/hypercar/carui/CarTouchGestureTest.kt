package io.github.hypercar.carui

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class CarTouchGestureTest {
    private fun frame(action: Int) = requireNotNull(CarTouchFrame.fromNative(action, intArrayOf(0, 30, 90)))

    @Test fun takesAndReturnsWhileDownAndUpAreWaitingOnMain() {
        val gesture = CarTouchGesture()
        var current = CarTouchGesture.Grant(1, 11)
        val old = current
        val events = mutableListOf<Int>()
        val target = { CarTouchGesture.Target({ true }, { events += it.masked }) }
        val queue = listOf<() -> Unit>(
            { gesture.dispatch(frame(MotionEvent.ACTION_DOWN), old, old == current, target) },
            { gesture.dispatch(frame(MotionEvent.ACTION_UP), old, old == current, target) },
        )
        current = CarTouchGesture.Grant(1, 13)
        queue.forEach { it() }
        assertEquals(emptyList<Int>(), events)

        gesture.dispatch(frame(MotionEvent.ACTION_DOWN), current, true, target)
        gesture.dispatch(frame(MotionEvent.ACTION_CANCEL), old, false, target)
        gesture.dispatch(frame(MotionEvent.ACTION_UP), current, true, target)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events)
    }

    @Test fun roundTripToSamePageStillCancelsTheOriginalGesture() {
        val page = CarTouchPage()
        val original = page.snapshot()
        val gesture = CarTouchGesture()
        val events = mutableListOf<Int>()
        val target = { CarTouchGesture.Target({ page.snapshot() == original }, { events += it.masked }) }

        gesture.dispatch(frame(MotionEvent.ACTION_DOWN), target)
        page.showMap(true)
        page.showMap(false)
        gesture.dispatch(frame(MotionEvent.ACTION_UP), target)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), events)
    }

    @Test fun leavingMapDropsQueuedUpBeforeCancellingTheInjectedDown() {
        val page = CarTouchPage()
        page.showMap(true)
        val map = page.snapshot()
        val events = mutableListOf<Int>()
        page.dispatch(map) { events += MotionEvent.ACTION_DOWN }
        val queuedUp = { page.dispatch(map) { events += MotionEvent.ACTION_UP } }

        page.showMap(false)
        queuedUp()
        events += MotionEvent.ACTION_CANCEL
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), events)
    }

    @Test fun singleFingerCancellationStaysOnThePageThatReceivedDown() {
        for ((origin, replacement) in listOf("home" to "map", "map" to "home")) {
            var visible = origin
            val events = mutableListOf<Triple<String, Int, Int>>()
            val gesture = CarTouchGesture()
            fun dispatch(action: Int, x: Int) {
                val frame = requireNotNull(CarTouchFrame.fromNative(action, intArrayOf(0, x, 90)))
                gesture.dispatch(frame) {
                    val page = visible
                    CarTouchGesture.Target(
                        isCurrent = { visible == page },
                        send = { events += Triple(page, it.masked, it.x) },
                    )
                }
            }

            dispatch(MotionEvent.ACTION_DOWN, 30)
            dispatch(MotionEvent.ACTION_MOVE, 35)
            visible = replacement
            dispatch(MotionEvent.ACTION_CANCEL, 80)
            dispatch(MotionEvent.ACTION_UP, 80)
            dispatch(MotionEvent.ACTION_DOWN, 81)
            dispatch(MotionEvent.ACTION_UP, 82)
            assertEquals(listOf(
                Triple(origin, MotionEvent.ACTION_DOWN, 30),
                Triple(origin, MotionEvent.ACTION_MOVE, 35),
                Triple(origin, MotionEvent.ACTION_CANCEL, 35),
                Triple(replacement, MotionEvent.ACTION_DOWN, 81),
                Triple(replacement, MotionEvent.ACTION_UP, 82),
            ), events)
        }
    }
}
