package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class ProjectionSurfaceInputTest {
    private data class Viewport(val texture: Int, val width: Int)
    private val events = mutableListOf<String>()
    private val input = ProjectionSurfaceInput<Viewport> { id, frame, time ->
        events.add("$id:${frame.action}@${frame.x},${frame.y}/$time")
    }
    private fun touch(viewport: Viewport, id: Int, action: Int, x: Int, y: Int, time: Long) =
        input.touch(viewport, id, requireNotNull(CarTouchFrame.fromNative(action, intArrayOf(0, x, y))), time)

    @Test fun resizeCancelsOldDragBeforeReplacementAndRequiresFreshDown() {
        val old = Viewport(1, 600)
        val resized = old.copy(width = 900)
        assertTrue(input.replace(old))
        touch(old, 7, 0, 100, 200, 11)
        assertFalse(input.replace(old.copy()))
        touch(old, 7, 2, 130, 210, 11)
        assertTrue(input.replace(resized))
        events.add("resize")
        touch(old, 7, 2, 150, 250, 11)
        touch(resized, 7, 1, 190, 290, 11)
        touch(resized, 7, 0, 300, 400, 12)
        touch(resized, 7, 1, 190, 290, 11)
        touch(resized, 7, 1, 300, 400, 12)
        assertEquals(listOf("7:0@100,200/11", "7:2@130,210/11", "7:3@130,210/11", "resize",
            "7:0@300,400/12", "7:1@300,400/12"), events)
    }

    @Test fun detachCancelsOnOriginalDisplayAndStaleEventsCannotReachReplacement() {
        val old = Viewport(1, 600)
        val fresh = Viewport(2, 600)
        input.replace(old)
        touch(old, 7, 0, 10, 20, 10)
        input.replace(null)
        events.add("detach")
        input.replace(fresh)
        touch(fresh, 9, 2, 13, 20, 10)
        touch(fresh, 9, 0, 30, 40, 20)
        touch(old, 7, 1, 10, 20, 10)
        input.cancel()
        touch(fresh, 9, 1, 30, 40, 20)
        assertEquals(listOf("7:0@10,20/10", "7:3@10,20/10", "detach", "9:0@30,40/20",
            "9:3@30,40/20"), events)
    }

    @Test fun liftingFirstPointerThenReplacingSurfaceCancelsOnlyTheRemainingPointer() {
        val old = Viewport(1, 600)
        input.replace(old)
        touch(old, 7, 0, 10, 20, 10)
        val points = intArrayOf(0, 11, 21, 1, 80, 90)
        input.touch(old, 7, requireNotNull(CarTouchFrame.fromNative(261, points)), 10)
        val lifted = requireNotNull(CarTouchFrame.fromNative(6, points))
        assertEquals(2, lifted.pointerCount)
        assertEquals(1, lifted.cancelAfter()?.pointerCount)
        input.touch(old, 7, lifted, 10)
        input.replace(old.copy(texture = 2))
        assertEquals(listOf("7:0@10,20/10", "7:261@80,90/10", "7:6@11,21/10", "7:3@80,90/10"), events)
        assertNull(CarTouchFrame.fromNative(517, points))
        assertNull(CarTouchFrame.fromNative(2, intArrayOf(0, 11, 21, 0, 80, 90)))
    }
}
