package io.github.hypercar.session

import io.github.hypercar.headunit.HeadUnitProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownWifiWaitCopyTest {
    @Test fun boundWifiWithNoSessionAfterWindowIsNotAHandoff() {
        assertTrue(KnownWifiWaitCopy.awaitingHeadUnitIap2(true, 0, 0, true))
        assertEquals("车机尚未发起 iAP2，ctrl 邀请要等这次蓝牙连入", KnownWifiWaitCopy.HERO)
        val line = KnownWifiWaitCopy.progress(45, 0)
        assertTrue(line.contains(KnownWifiWaitCopy.HERO))
        assertTrue(line.contains("已等 45s"))
        assertTrue(line.contains("本轮蓝牙会话=0"))
        assertFalse(line.contains("交棒"))
        assertFalse(line.contains("不以持续 iAP2"))
    }

    @Test fun earlierProcessSessionsDoNotCountAsThisRound() {
        assertEquals(0, KnownWifiWaitCopy.sessionsThisRound(3, 3))
        assertTrue(KnownWifiWaitCopy.awaitingHeadUnitIap2(
            wifiBound = true,
            sessionStartCount = 3,
            roundBaseline = 3,
            discoverableWindowOver = true,
        ))
        val line = KnownWifiWaitCopy.progress(45, KnownWifiWaitCopy.sessionsThisRound(3, 3))
        assertTrue(line.contains("车机尚未发起 iAP2"))
        assertTrue(line.contains("本轮蓝牙会话=0"))
        assertFalse(line.contains("交棒"))
        assertEquals(1, KnownWifiWaitCopy.sessionsThisRound(4, 3))
        assertFalse(KnownWifiWaitCopy.awaitingHeadUnitIap2(true, 4, 3, true))
    }

    @Test fun windowStillOpenOrSessionStartedKeepsTheHandoffWordingOut() {
        assertFalse(KnownWifiWaitCopy.awaitingHeadUnitIap2(true, 0, 0, false))
        assertFalse(KnownWifiWaitCopy.awaitingHeadUnitIap2(true, 1, 0, true))
        assertFalse(KnownWifiWaitCopy.awaitingHeadUnitIap2(false, 0, 0, true))
    }

    @Test fun mercedesEndpointWaitDoesNotPrintTheBmwLine() {
        val mercedes = HeadUnitProfiles.MERCEDES_MBUX.id
        assertTrue(KnownWifiWaitCopy.waitsForHeadUnitEndpoint(mercedes))
        val duringWindow = KnownWifiWaitCopy.boundProgress(
            mercedes, waitedSeconds = 15, sessionsThisRound = 0, awaitingHeadUnitIap2 = false)
        val afterWindow = KnownWifiWaitCopy.boundProgress(
            mercedes, waitedSeconds = 30, sessionsThisRound = 0, awaitingHeadUnitIap2 = true)
        val step = KnownWifiWaitCopy.boundNegotiatingStep(mercedes, awaitingHeadUnitIap2 = true)
        for (line in listOf(duringWindow, afterWindow, step, KnownWifiWaitCopy.endpointOpening())) {
            assertFalse(line!!.contains("宝马"))
            assertFalse(line.contains("交棒"))
            assertFalse(line.contains("ctrl 邀请"))
            assertTrue(line.contains("0x4301"))
            assertTrue(line.contains("车机"))
        }
        assertEquals("等待车机这轮 iAP2 的 0x4301", step)
        assertEquals("SESSION: 还在等车机这轮 iAP2 上的 0x4301，已等 15s", duringWindow)
        assertFalse(duringWindow.contains("本轮蓝牙会话"))
    }

    @Test fun bmwKnownWifiWaitStillPrintsTheHandoffLine() {
        val bmw = HeadUnitProfiles.BMW_ID85.id
        assertFalse(KnownWifiWaitCopy.waitsForHeadUnitEndpoint(bmw))
        val duringWindow = KnownWifiWaitCopy.boundProgress(
            bmw, waitedSeconds = 15, sessionsThisRound = 0, awaitingHeadUnitIap2 = false)
        assertEquals(
            "SESSION: 等待本轮车机 Wi-Fi ctrl 邀请，已等 15s；" +
                "已绑定已知车机网络，宝马二次上车蓝牙常仅短窗口后交棒，" +
                "不以持续 iAP2 会话为成败（本轮蓝牙会话=0）",
            duringWindow,
        )
        assertEquals(KnownWifiWaitCopy.bmwBoundProgress(15, 1), KnownWifiWaitCopy.boundProgress(
            bmw, waitedSeconds = 15, sessionsThisRound = 1, awaitingHeadUnitIap2 = false))
        assertTrue(duringWindow.contains("本轮蓝牙会话=0"))
        val afterWindow = KnownWifiWaitCopy.boundProgress(
            bmw, waitedSeconds = 45, sessionsThisRound = 0, awaitingHeadUnitIap2 = true)
        assertEquals(KnownWifiWaitCopy.progress(45, 0), afterWindow)
        assertTrue(afterWindow.contains(KnownWifiWaitCopy.HERO))
        assertTrue(afterWindow.contains("本轮蓝牙会话=0"))
        assertEquals(KnownWifiWaitCopy.HERO, KnownWifiWaitCopy.boundNegotiatingStep(bmw, true))
        assertNull(KnownWifiWaitCopy.boundNegotiatingStep(bmw, false))
        assertEquals("车机尚未发起 iAP2，ctrl 邀请要等这次蓝牙连入", KnownWifiWaitCopy.HERO)
    }
}
