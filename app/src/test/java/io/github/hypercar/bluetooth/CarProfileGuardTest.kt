package io.github.hypercar.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机 HFP/A2DP 拦截的准入规则（纯函数，不碰蓝牙栈）。
 *
 * 现场依据：2026-11-01 11:05:10.840 手机自己回连 HFP → 11:05:13.038 车机
 * `Remote Terminated`，iAP2 从未打开；成功那次没有 HFP 先行。
 */
class CarProfileGuardTest {

    @Test fun onlyTargetCarIsTouched() {
        assertTrue(
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "9c:df:03:5d:fb:90",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
        assertFalse(
            "别的设备（耳机/手表）不碰",
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "B0:A3:F2:D4:FA:30",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
    }

    @Test fun unknownTargetIsIgnored() {
        assertFalse(
            CarProfilePolicy.shouldEnforce(
                targetAddress = null,
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
        assertFalse(
            CarProfilePolicy.shouldEnforce(
                targetAddress = "  ",
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
    }

    @Test fun throttlePreventsFightingWithCarReconnect() {
        val interval = CarProfilePolicy.MIN_INTERVAL_MILLIS
        assertFalse(
            "同一地址 3s 内不重复断",
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000 + interval - 1,
                lastEnforceAtMillis = 10_000,
            ),
        )
        assertTrue(
            "超过节流窗口允许再断一次",
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000 + interval,
                lastEnforceAtMillis = 10_000,
            ),
        )
    }
}
