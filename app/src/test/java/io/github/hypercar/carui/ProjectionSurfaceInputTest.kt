package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class ProjectionSurfaceInputTest {
    private data class Viewport(val texture: Int, val width: Int)
    private val events = mutableListOf<String>()
    private val input = ProjectionSurfaceInput<Viewport> { id, action, x, y, time ->
        events.add("$id:$action@$x,$y/$time")
    }

    @Test fun resizeCancelsOldDragBeforeReplacementAndRequiresFreshDown() {
        val old = Viewport(1, 600)
        val resized = old.copy(width = 900)
        assertTrue(input.replace(old))
        input.touch(old, 7, 0, 100, 200, 11)
        assertFalse(input.replace(old.copy()))
        input.touch(old, 7, 2, 130, 210, 11)
        assertTrue(input.replace(resized))
        events.add("resize")
        input.touch(old, 7, 2, 150, 250, 11)
        input.touch(resized, 7, 1, 190, 290, 11)
        input.touch(resized, 7, 0, 300, 400, 12)
        input.touch(resized, 7, 1, 190, 290, 11)
        input.touch(resized, 7, 1, 300, 400, 12)
        assertEquals(listOf("7:0@100,200/11", "7:2@130,210/11", "7:3@130,210/11", "resize",
            "7:0@300,400/12", "7:1@300,400/12"), events)
    }

    @Test fun detachCancelsOnOriginalDisplayAndStaleEventsCannotReachReplacement() {
        val old = Viewport(1, 600)
        val fresh = Viewport(2, 600)
        input.replace(old)
        input.touch(old, 7, 0, 10, 20, 10)
        input.replace(null)
        events.add("detach")
        input.replace(fresh)
        input.touch(fresh, 9, 2, 13, 20, 10)
        input.touch(fresh, 9, 0, 30, 40, 20)
        input.touch(old, 7, 1, 10, 20, 10)
        input.cancel()
        input.touch(fresh, 9, 1, 30, 40, 20)
        assertEquals(listOf("7:0@10,20/10", "7:3@10,20/10", "detach", "9:0@30,40/20",
            "9:3@30,40/20"), events)
    }
}
