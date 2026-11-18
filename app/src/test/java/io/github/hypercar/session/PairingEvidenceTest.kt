package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingEvidenceTest {
    @Test fun prewarmReadsOnlyTheIdentifiedCarsCanvasAndPrefersItsCurrentProfile() {
        val id9 = "bmw_id9:bt_112233445566"
        val id85 = "bmw_id85:bt_112233445566"
        val cache = mutableMapOf(id85 to (2880 to 960), "bmw_id9:bt_223344556677" to (1920 to 720))
        assertEquals(2880 to 960, PairingEvidence.cachedCanvas(id9, cache::get))
        cache[id9] = 2560 to 852
        assertEquals(2560 to 852, PairingEvidence.cachedCanvas(id9, cache::get))
        assertNull(PairingEvidence.cachedCanvas("bmw_id9:bt_334455667788", cache::get))
        assertNull(PairingEvidence.cachedCanvas(null, cache::get))
    }
    @get:Rule val temp = TemporaryFolder()
    private val mac = "AA:BB:CC:DD:EE:01"

    @Test fun globalIdentityAloneNeverMeansThisCarIsPaired() {
        temp.newFile("controller_identity.txt").writeText("local identity")
        assertFalse(PairingEvidence.read(temp.root, mac).mayDropProfiles)
    }

    @Test fun anotherCarsCredentialDoesNotAuthorizeDroppingProfiles() {
        temp.newFile("controller_identity.txt").writeText("local identity")
        temp.newFile("controller_identity.txt.paired-bt_aabbccddee02").writeText("1")
        assertFalse(PairingEvidence.read(temp.root, mac).mayDropProfiles)
    }

    @Test fun matchingCredentialRequiresLocalIdentityAsWell() {
        temp.newFile("controller_identity.txt.paired-bt_aabbccddee01").writeText("1")
        assertFalse(PairingEvidence.read(temp.root, mac).mayDropProfiles)
        temp.newFile("controller_identity.txt").writeText("local identity")
        assertTrue(PairingEvidence.read(temp.root, mac.lowercase()).mayDropProfiles)
    }

    @Test fun malformedAddressCannotBecomeACredentialPath() {
        assertNull(PairingEvidence.receiverKey("../../other"))
        assertEquals("bt_aabbccddee01", PairingEvidence.receiverKey(" AA:BB:CC:DD:EE:01 "))
    }
    @Test fun diPlayIncomingOnSharedWifiDoesNotInheritOldReceiversCredential() {
        val previous = "C0:BF:BE:E1:B5:F1"
        val current = "8C:7A:3D:41:E5:5C"
        temp.newFile("controller_identity.txt").writeText("local identity")
        temp.newFile("controller_identity.txt.paired-bt_c0bfbee1b5f1").writeText("1")
        assertTrue(PairingEvidence.read(temp.root, previous).receiverCredentialPresent)
        val selected = PairingEvidence.receiverMac(current, null, previous, desktop = true)
        assertEquals("bt_8c7a3d41e55c", PairingEvidence.receiverKey(selected))
        assertFalse(PairingEvidence.read(temp.root, selected).receiverCredentialPresent)
    }

    @Test fun sharedWifiWithoutLiveBluetoothUsesAirplayIdentityInsteadOfOldMac() {
        val selected = PairingEvidence.receiverMac(null, null, "C0:BF:BE:E1:B5:F1", desktop = true)
        assertNull(selected)
        assertNull(PairingEvidence.receiverKey(selected))
    }

    @Test fun bmwWifiOnlyReconnectRetainsItsOwnCredentialHint() {
        assertEquals(mac, PairingEvidence.receiverMac(null, null, mac, desktop = false))
        assertEquals(mac, PairingEvidence.receiverMac(null, mac, "AA:BB:CC:DD:EE:02", desktop = false))
    }

    @Test fun wifiWakeBeforeBindingReadsTheSameCanvasKeyAsTheSubsequentSession() {
        val profile = "bmw_id85"
        val otherCar = "AA:BB:CC:DD:EE:02"
        val sessionKey = PairingEvidence.canvasKey(profile, mac)
        assertEquals("bmw_id85:bt_aabbccddee01", sessionKey) // existing preferences
        assertEquals(sessionKey, PairingEvidence.canvasKey(profile, null, mac, otherCar))
        assertEquals(sessionKey, PairingEvidence.canvasKey(profile, mac, otherCar))
        assertNotEquals(sessionKey, PairingEvidence.canvasKey("winplay", mac))
        assertNull(PairingEvidence.canvasKey(profile, null, "invalid"))
    }

    @Test fun activePeerOverridesAFrozenOlderIdentityDuringRetry() {
        assertEquals("AA:BB:CC:DD:EE:02", PairingEvidence.receiverMac(
            "AA:BB:CC:DD:EE:02", mac, mac, desktop = true))
    }
}
