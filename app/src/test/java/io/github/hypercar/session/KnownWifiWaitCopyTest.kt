package io.github.hypercar.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownWifiWaitCopyTest {
    @Test fun boundWifiWithNoSessionAfterWindowIsNotAHandoff() {
        assertTrue(KnownWifiWaitCopy.awaitingHeadUnitIap2(true, 0, true))
        assertEquals("车机尚未发起 iAP2，ctrl 邀请要等这次蓝牙连入", KnownWifiWaitCopy.HERO)
        val line = KnownWifiWaitCopy.progress(45, 0)
        assertTrue(line.contains(KnownWifiWaitCopy.HERO))
        assertTrue(line.contains("已等 45s"))
        assertTrue(line.contains("本轮蓝牙会话=0"))
        assertFalse(line.contains("交棒"))
        assertFalse(line.contains("不以持续 iAP2"))
    }

    @Test fun windowStillOpenOrSessionStartedKeepsTheHandoffWordingOut() {
        assertFalse(KnownWifiWaitCopy.awaitingHeadUnitIap2(true, 0, false))
        assertFalse(KnownWifiWaitCopy.awaitingHeadUnitIap2(true, 1, true))
        assertFalse(KnownWifiWaitCopy.awaitingHeadUnitIap2(false, 0, true))
    }
}
