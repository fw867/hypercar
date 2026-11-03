package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class StartupReadinessTest {
    private val ready = StartupReadiness(source = "car_ui", shizukuReady = true, overlayAllowed = true)

    @Test fun firstInstallMustChooseSourceEvenWithPermissionsAndShizuku() {
        assertFalse(ready.copy(source = null).manualReady)
        assertFalse(ready.copy(source = "unknown").automaticReady)
    }

    @Test fun selfUiAlwaysRequiresRunningGrantedShizuku() {
        assertTrue(ready.manualReady)
        assertTrue(ready.automaticReady)
        assertFalse(ready.copy(shizukuReady = false).manualReady)
        assertFalse(ready.copy(shizukuReady = false, carWithInstalled = true).automaticReady)
    }

    @Test fun carWithRequiresItsOwnPackageButNotShizuku() {
        val carWith = ready.copy(source = "carwith", shizukuReady = false, carWithInstalled = true)
        assertTrue(carWith.manualReady)
        assertTrue(carWith.automaticReady)
        assertFalse(carWith.copy(carWithInstalled = false, shizukuReady = true).manualReady)
    }

    @Test fun requiredPermissionRevocationBlocksBothEntries() {
        for (permission in listOf("附近设备", "通知", "定位")) {
            val blocked = ready.copy(missingPermissions = listOf(permission))
            assertFalse(blocked.manualReady)
            assertFalse(blocked.automaticReady)
            assertTrue(blocked.manualIssues.contains(permission))
        }
    }

    @Test fun radiosAndRequiredLocationServiceMustBeEnabled() {
        assertFalse(ready.copy(bluetoothEnabled = false).manualReady)
        assertFalse(ready.copy(wifiEnabled = false).automaticReady)
        assertFalse(ready.copy(locationEnabled = false).manualReady)
    }

    @Test fun missingOverlayBlocksAutomaticOnly() {
        val foregroundOnly = ready.copy(overlayAllowed = false)
        assertTrue(foregroundOnly.manualReady)
        assertFalse(foregroundOnly.automaticReady)
        assertEquals(1, foregroundOnly.automaticIssues.size)
    }
}
