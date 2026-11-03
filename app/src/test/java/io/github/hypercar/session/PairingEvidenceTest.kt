package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingEvidenceTest {
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

    @Test fun activePeerOverridesAFrozenOlderIdentityDuringRetry() {
        assertEquals("AA:BB:CC:DD:EE:02", PairingEvidence.receiverMac(
            "AA:BB:CC:DD:EE:02", mac, mac, desktop = true))
    }
}
