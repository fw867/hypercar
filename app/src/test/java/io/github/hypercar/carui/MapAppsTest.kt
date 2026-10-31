package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class MapAppsTest {
    @Test fun installedPreferredMapWinsWhenBothArePresent() {
        assertEquals(MapProvider.BAIDU, MapApps.resolve(MapProvider.BAIDU, MapProvider.entries))
        assertEquals(MapProvider.AMAP, MapApps.resolve(MapProvider.AMAP, MapProvider.entries))
    }

    @Test fun missingPreferredMapFallsBackToTheOnlyInstalledMap() {
        assertEquals(MapProvider.BAIDU, MapApps.resolve(MapProvider.AMAP, listOf(MapProvider.BAIDU)))
        assertEquals(MapProvider.AMAP, MapApps.resolve(MapProvider.BAIDU, listOf(MapProvider.AMAP)))
        assertNull(MapApps.resolve(MapProvider.BAIDU, emptyList()))
    }

    @Test fun persistedSelectionSurvivesKnownIdsAndHandlesUnknownValues() {
        assertEquals(MapProvider.BAIDU, MapApps.fromId("baidu"))
        assertEquals(MapProvider.AMAP, MapApps.fromId(null))
        assertEquals(MapProvider.AMAP, MapApps.fromId("removed-provider"))
    }

    @Test fun baiduUsesInstalledCarInterfaceOnTheSameDisplay() {
        val launch = MapProvider.BAIDU.carLaunchCommand(42, 2, true)
        assertTrue(launch.contains("com.baidu.BaiduMap/com.baidu.hicar.HiCarWelcomeScreen"))
        assertTrue(launch.contains("-a com.ucar.intent.action.UCAR"))
        assertTrue(launch.contains("--ei screenMode 1"))
        assertTrue(launch.endsWith("--display 42"))
        assertFalse(MapProvider.BAIDU.supportsLayoutIntent)
    }

    @Test fun amapKeepsExistingUcarLayoutContract() {
        assertTrue(MapProvider.AMAP.carLaunchCommand(42, 2, true).contains("UcarSplashActivity"))
        val update = MapProvider.AMAP.carLaunchCommand(42, 1, false)
        assertTrue(update.contains("UcarMapActivity"))
        assertTrue(update.contains("--ei screenMode 1"))
        assertTrue(MapProvider.AMAP.supportsLayoutIntent)
    }

    @Test fun launchAndLayoutUpdatesLetMapControlItsOwnCruise() {
        for (provider in MapProvider.entries) {
            assertFalse(provider.carLaunchCommand(42, 2, true).contains("-d "))
            assertFalse(provider.carLaunchCommand(42, 1, false).contains("-d "))
        }
    }

}
