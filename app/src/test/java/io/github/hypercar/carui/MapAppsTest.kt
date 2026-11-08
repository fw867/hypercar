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
        val launch = MapProvider.BAIDU.carLaunchCommand(42, MapPresentation.CARD, true)
        assertTrue(launch.contains("com.baidu.BaiduMap/com.baidu.hicar.HiCarWelcomeScreen"))
        assertTrue(launch.contains("-a com.ucar.intent.action.UCAR"))
        assertTrue(launch.contains("--ei screenMode 1"))
        assertTrue(launch.endsWith("--display 42"))
    }

    @Test fun amapCardUsesNativeCarWithPreviewContract() {
        val launch = MapProvider.AMAP.carLaunchCommand(42, MapPresentation.CARD, true)
        assertTrue(launch.contains("UcarSplashActivity"))
        assertTrue(launch.contains("--ei screenMode 2"))
        assertTrue(launch.contains("-c com.ucar.intent.category.MAP_PREVIEW "))
        assertTrue(launch.contains("--ez isUCarNaviUseVerticalScreenLayout false"))
        assertFalse(launch.contains("--activity-clear-task"))
    }

    @Test fun fullscreenReusesMapActivityWithoutReenteringSplashOrPreview() {
        val command = MapProvider.AMAP.carLaunchCommand(42, MapPresentation.FULLSCREEN, false)
        assertTrue(command.contains("UcarMapActivity"))
        assertTrue(command.contains("--ei screenMode 1"))
        assertTrue(command.contains("-c com.ucar.intent.category.UCAR "))
        assertFalse(command.contains("SplashActivity"))
        assertFalse(command.contains("--activity-clear-task"))
    }

    @Test fun oneTimeNativeOrientationOptionCannotLeakPortraitLayoutFromCardIntoFullscreen() {
        for (presentation in MapPresentation.entries) for (initial in listOf(true, false)) {
            val command = MapProvider.AMAP.carLaunchCommand(42, presentation, initial)
            assertTrue(command.contains("--ez isUCarNaviUseVerticalScreenLayout false"))
            assertFalse(command.contains("--ez isUCarNaviUseVerticalScreenLayout true"))
        }
    }

    @Test fun launchAndLayoutUpdatesLetMapControlItsOwnCruise() {
        for (provider in MapProvider.entries) {
            for (presentation in MapPresentation.entries) {
                assertFalse(provider.carLaunchCommand(42, presentation, true).contains("-d "))
                assertFalse(provider.carLaunchCommand(42, presentation, false).contains("-d "))
            }
        }
    }

}
