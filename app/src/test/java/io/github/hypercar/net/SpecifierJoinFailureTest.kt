package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class SpecifierJoinFailureTest {
    @Test fun mercedesThirtySecondTimelineIsTimeoutRatherThanImmediateRejection() {
        val message = SpecifierJoinFailure.describe(30_012, 30_000, true, true)
        assertTrue(message.contains("超时"))
        assertTrue(message.contains("30012ms"))
        assertFalse(message.contains("立刻"))
        assertFalse(message.contains("已被系统保存"))
    }
    @Test fun earlyRefusalDoesNotInventForegroundOrPasswordCause() {
        val message = SpecifierJoinFailure.describe(120, 60_000, true, true)
        assertTrue(message.contains("不能仅凭此回调判定原因"))
        assertFalse(message.contains("超时"))
    }
    @Test fun timeoutIsRecognizedWithAndWithoutTheLastSystemCallback() {
        for (callback in listOf(true, false)) {
            assertTrue(SpecifierJoinFailure.describe(60_001, 60_000, callback, true).contains("超时"))
        }
    }
    @Test fun disabledWifiHasAnActionableCauseEvenAtDeadline() {
        val message = SpecifierJoinFailure.describe(60_001, 60_000, true, false)
        assertTrue(message.contains("Wi-Fi 开关已关闭"))
        assertFalse(message.contains("超时"))
    }
}
