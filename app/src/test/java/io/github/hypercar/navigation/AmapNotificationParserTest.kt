package io.github.hypercar.navigation

import org.junit.Assert.*
import org.junit.Test

class AmapNotificationParserTest {
    @Test fun explicitTurnAndUnitConversion() {
        val cue = AmapNotificationParser.parse(listOf("300米后右转进入测试路"), true)!!
        assertEquals(300L, cue.distanceMeters); assertEquals(2, cue.maneuver)
        assertEquals("右转进入测试路", cue.instruction)
        val slight = AmapNotificationParser.parse(listOf("1.25公里后向左前方行驶"), true)!!
        assertEquals(1250L, slight.distanceMeters); assertEquals(49, slight.maneuver)
    }
    @Test fun genericAndAmbiguousNotificationsAreIgnored() {
        listOf("导航中", "剩余12公里，预计20分钟", "右转优惠券", "300米后到达附近商店", "999999999999公里后右转")
            .forEach { assertNull(it, AmapNotificationParser.parse(listOf(it), true)) }
        assertNull(AmapNotificationParser.parse(listOf("300米后右转"), false))
    }
    @Test fun arrivalAndRerouteHaveNoInventedTurn() {
        val cue = AmapNotificationParser.parse(listOf("已到达目的地"), true)!!
        assertEquals(2, cue.state); assertNull(cue.maneuver); assertNull(cue.distanceMeters)
        assertEquals(5, AmapNotificationParser.parse(listOf("正在重新规划路线"), true)!!.state)
    }
    @Test fun rejectsInvalidWireValues() {
        assertThrows(IllegalArgumentException::class.java) { NavigationCue("right", 256, 1) }
        assertThrows(IllegalArgumentException::class.java) { NavigationCue("right", 2, -1) }
        assertThrows(IllegalArgumentException::class.java) { NavigationCue("x\u0000", 1, 1) }
    }
}
