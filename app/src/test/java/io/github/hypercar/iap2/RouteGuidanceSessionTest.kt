package io.github.hypercar.iap2

import io.github.hypercar.navigation.NavigationCue
import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceSessionTest {
    private val cue = NavigationCue("右转进入测试路", 2, 300)
    private fun start(id: Int) = Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, id) + Iap2Csm.param(1, byteArrayOf()) + Iap2Csm.param(2, byteArrayOf()))
    private fun fields(f: Iap2Csm.Frame) = Iap2Csm.parseParams(f.body).associate { it.id to it.value }
    @Test fun requiresSubscriptionAndScope() {
        val s = RouteGuidanceSession {}
        assertTrue(s.updates(0, true, cue).isEmpty())
        s.handle(start(42)); assertTrue(s.updates(1, false, cue).isEmpty())
    }
    @Test fun realComponentAndManeuverBeforeReference() {
        val s = RouteGuidanceSession {}; s.handle(start(0x1234))
        val frames = s.updates(0, true, cue)
        assertEquals(listOf(0x5202, 0x5201), frames.map { it.messageId })
        frames.forEach { assertArrayEquals(byteArrayOf(0x12, 0x34), fields(it)[0]) }
        assertArrayEquals(byteArrayOf(2), fields(frames[0])[3])
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x2c), fields(frames[1])[0x0a])
        assertArrayEquals(byteArrayOf(0, 0), fields(frames[1])[0x0d])
        assertArrayEquals(byteArrayOf(0, 1), fields(frames[1])[0x0e])
        assertFalse(fields(frames[1]).containsKey(5)); assertFalse(fields(frames[0]).containsKey(8))
    }
    @Test fun clearsOnceOnCancellationAndScopeChange() {
        val s = RouteGuidanceSession {}; s.handle(start(42)); s.updates(0, true, cue)
        val clear = s.updates(1000, true, null).single()
        assertEquals(0x5201, clear.messageId)
        assertArrayEquals(byteArrayOf(0), fields(clear)[1])
        assertArrayEquals(byteArrayOf(0, 0), fields(clear)[0x0e])
        assertTrue(s.updates(2000, true, null).isEmpty())
        assertEquals(2, s.updates(3000, true, cue).size)
        assertArrayEquals(byteArrayOf(0), fields(s.updates(4000, false, cue).single())[1])
    }
    @Test fun stopAndNewGenerationDoNotReuseSubscriptions() {
        val s = RouteGuidanceSession {}; s.handle(start(42))
        s.handle(Iap2Csm.Frame(0x5203, Iap2Csm.u16(0, 42)))
        assertTrue(s.updates(0, true, cue).isEmpty())
        assertTrue(RouteGuidanceSession {}.updates(0, true, cue).isEmpty())
    }
    @Test fun rejectsMalformedAndFiltersOptionalFields() {
        val s = RouteGuidanceSession {}
        s.handle(Iap2Csm.Frame(0x5200, byteArrayOf(0, 6, 0, 0, 42)))
        assertTrue(s.updates(0, true, cue).isEmpty())
        s.handle(Iap2Csm.Frame(0x5200, Iap2Csm.u16(0, 42)))
        val f = fields(s.updates(1, true, cue).last())
        assertFalse(f.containsKey(0x13)); assertFalse(f.containsKey(0x14))
    }
    @Test fun rateLimitAndDeduplicate() {
        val s = RouteGuidanceSession {}; s.handle(start(42)); s.updates(0, true, cue)
        assertTrue(s.updates(100, true, cue.copy(distanceMeters = 200)).isEmpty())
        assertTrue(s.updates(1000, true, cue).isEmpty())
        assertEquals(2, s.updates(1001, true, cue.copy(distanceMeters = 200)).size)
    }

    @Test fun structuredCarWithUturnReachesWireAsCpUturnWithRoadAndActualSource() {
        val s = RouteGuidanceSession {}; s.handle(start(42))
        val cue = io.github.hypercar.navigation.CarWithCueMapper.map(2, 5, 35, "test")!!
        val frames = s.updates(0, true, cue)
        assertArrayEquals(byteArrayOf(4), fields(frames[0])[3])
        assertArrayEquals("test\u0000".encodeToByteArray(), fields(frames[0])[4])
        assertArrayEquals("CarWith\u0000".encodeToByteArray(), fields(frames[1])[0x13])
        assertArrayEquals(byteArrayOf(0, 0, 0, 35), fields(frames[1])[0x0a])
        assertFalse(fields(frames[1]).containsKey(7)) // segment total is NOT route remaining distance
    }

    @Test fun idleSourceStillSupportsGuidanceInsteadOfAdvertisingItAsUnsupported() {
        val s = RouteGuidanceSession {}; s.handle(start(42))
        val idle = fields(s.updates(0, true, null).single())
        assertArrayEquals(byteArrayOf(0), idle[1])
        assertArrayEquals(byteArrayOf(1), idle[0x14])
        assertArrayEquals(byteArrayOf(0), fields(s.updates(1000, false, null).single())[0x14])
    }
}
