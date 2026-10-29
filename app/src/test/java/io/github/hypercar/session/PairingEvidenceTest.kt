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
}
