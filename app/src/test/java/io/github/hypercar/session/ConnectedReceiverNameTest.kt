package io.github.hypercar.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedReceiverNameTest {
    @Test fun actualPeerWinsOverAliasForAnotherCar() {
        assertEquals("MBUX", connectedReceiverName("奔驰", "aa", "bb", "我的宝马", "AA", "MBUX", "车载蓝牙"))
    }
    @Test fun aliasOnlyAppliesToTheMatchingReceiver() {
        assertEquals("我的奔驰", connectedReceiverName("奔驰", "aa", "AA", "我的奔驰", "aa", "MBUX", null))
    }
    @Test fun oldPeerAndBlankNamesFallBackToCurrentProfile() {
        assertEquals("奔驰 Mercedes-Benz", connectedReceiverName("奔驰 Mercedes-Benz", "aa", "bb", "BMW", "bb", "BMW", " "))
        assertEquals("BMW ID6", connectedReceiverName("BMW ID6", null, "bb", "旧车", "bb", "旧对端", null))
    }
}
