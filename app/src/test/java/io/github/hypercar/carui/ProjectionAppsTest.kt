package io.github.hypercar.carui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionAppsTest {
    @Test fun filtersBuiltInsAndKeepsOneEntryPerLaunchablePackage() {
        val launcherPackages = listOf(
            "io.github.hypercar", "com.miui.carlink", "com.autonavi.minimap", "com.baidu.BaiduMap",
            "com.tencent.qqmusic", "com.ss.android.ugc.aweme", "com.phoenix.read",
            "com.example.reader", "com.example.video", "com.example.reader",
        )
        assertEquals(listOf("com.example.reader", "com.example.video"),
            ProjectionApps.eligiblePackages(launcherPackages, "io.github.hypercar"))
    }

    @Test fun carListOnlyIncludesSelectedInstalledCustomApps() {
        val saved = setOf("com.example.reader", "com.example.uninstalled", "com.luna.music")
        val installed = listOf("com.luna.music", "com.example.reader", "com.example.other")
        assertEquals(listOf("com.example.reader"),
            ProjectionApps.eligiblePackages(installed.filter { it in saved }, "io.github.hypercar"))
    }
}
