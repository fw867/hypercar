package io.github.hypercar.session

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingIdentityFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val first = "AA:BB:CC:DD:EE:01"
    private val second = "AA:BB:CC:DD:EE:02"
    private fun legacy() = File(temp.root, PairingIdentityFiles.NAME)
    private fun marker(mac: String) = File(temp.root, "${PairingIdentityFiles.NAME}.paired-${PairingEvidence.receiverKey(mac)}")

    @Test fun resettingOneCarKeepsSharedIdentityAndOtherCarsCredentials() {
        legacy().writeText("shared secret")
        marker(first).writeText("1"); marker(second).writeText("1")
        val oldPk = File(temp.root, "controller_identity.txt.paired-deadbeef").apply { writeText("1") }
        PairingIdentityFiles.resetReceiver(temp.root, first)
        assertEquals("shared secret", legacy().readText())
        assertTrue(oldPk.isFile)
        assertFalse(marker(first).exists())
        assertTrue(PairingEvidence.read(temp.root, second).mayDropProfiles)
        assertFalse(PairingEvidence.read(temp.root, first).mayDropProfiles)
        val fresh = PairingIdentityFiles.location(temp.root, first)
        assertEquals("bt_aabbccddee01", fresh.backupKey)
        assertFalse(fresh.file.exists())
        assertNotEquals(legacy(), fresh.file)
        assertEquals(legacy(), PairingIdentityFiles.location(temp.root, second).file)
    }

    @Test fun directoryKeepsResetEffectiveAfterRestartEvenBeforeNewIdentityExists() {
        legacy().writeText("old identity")
        PairingIdentityFiles.resetReceiver(temp.root, first)
        val before = PairingIdentityFiles.location(temp.root, first)
        val restarted = PairingIdentityFiles.location(File(temp.root.absolutePath), first.lowercase())
        assertEquals(before, restarted)
        assertFalse(restarted.file.exists())
        assertNotNull(restarted.backupKey) // use only this car's backup, never shared backup
    }

    @Test fun repeatedResetRemovesOnlySelectedCarsIndependentIdentityAndMarkers() {
        PairingIdentityFiles.resetReceiver(temp.root, first)
        PairingIdentityFiles.resetReceiver(temp.root, second)
        val selected = PairingIdentityFiles.location(temp.root, first).file
        val other = PairingIdentityFiles.location(temp.root, second).file
        selected.writeText("selected private key")
        other.writeText("other private key")
        val credential = File(selected.parentFile, "${selected.name}.paired-old_pk").apply { writeText("1") }
        val otherCredential = File(other.parentFile, "${other.name}.paired-${PairingEvidence.receiverKey(second)}").apply { writeText("1") }
        PairingIdentityFiles.resetReceiver(temp.root, first)
        assertFalse(selected.exists()); assertFalse(credential.exists())
        assertEquals("other private key", other.readText()); assertTrue(otherCredential.isFile)
        assertTrue(PairingEvidence.read(temp.root, second).mayDropProfiles)
    }

    @Test fun newIdentityCannotUseLegacyMarkersFromSharedNamespace() {
        legacy().writeText("shared identity"); marker(first).writeText("1")
        PairingIdentityFiles.resetReceiver(temp.root, first)
        val fresh = PairingIdentityFiles.location(temp.root, first).file
        fresh.writeText("new identity")
        marker(first).writeText("1") // stale old callback / legacy file still cannot authorize new identity
        assertFalse(PairingEvidence.read(temp.root, first).mayDropProfiles)
        File(fresh.parentFile, "${fresh.name}.paired-${PairingEvidence.receiverKey(first)}").writeText("1")
        assertTrue(PairingEvidence.read(temp.root, first).mayDropProfiles)
    }

    @Test fun invalidOrUnspecifiedReceiverNeverFallsBackToGlobalReset() {
        legacy().writeText("unchanged")
        for (value in listOf("", "../../other", "invalid")) {
            try { PairingIdentityFiles.resetReceiver(temp.root, value); fail("expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("unchanged", legacy().readText())
        assertEquals(legacy(), PairingIdentityFiles.location(temp.root, null).file)
    }

    @Test fun failureToCreateScopedNamespaceKeepsSharedIdentity() {
        legacy().writeText("unchanged")
        File(temp.root, "pairing").writeText("blocked")
        try { PairingIdentityFiles.resetReceiver(temp.root, first); fail("expected IO failure") }
        catch (_: java.io.IOException) { }
        assertEquals("unchanged", legacy().readText())
    }

    @Test fun recordRemovalMatchesWholeMacAndKeepsOtherCarsOnSameSsid() {
        val cars = setOf("$first|BMW|1", "$second|DiPlay|2")
        val networks = setOf("Shared|$first", "Shared|$second", "A|B|$first", "A|B|$second")
        assertEquals(setOf("$second|DiPlay|2"), PairingResetRecords.pairedCars(cars, first.lowercase()))
        assertEquals(setOf("Shared|$second", "A|B|$second"), PairingResetRecords.networks(networks, first.lowercase()))
        assertEquals(emptySet<String>(), PairingResetRecords.networks(emptySet(), first))
    }
    @Test fun explicitGlobalResetClearsSharedAndDedicatedIdentitiesButPreservesOtherAppFiles() {
        legacy().writeText("shared identity")
        marker(first).writeText("1"); marker(second).writeText("1")
        val pk = File(temp.root, "controller_identity.txt.paired-deadbeef").apply { writeText("1") }
        PairingIdentityFiles.resetReceiver(temp.root, first)
        PairingIdentityFiles.location(temp.root, first).file.writeText("private identity")
        val unrelated = File(temp.root, "app-settings-test.txt").apply { writeText("keep") }
        PairingIdentityFiles.resetAll(temp.root)
        assertFalse(legacy().exists()); assertFalse(marker(second).exists()); assertFalse(pk.exists())
        assertFalse(File(temp.root, "pairing").exists())
        assertEquals("keep", unrelated.readText())
        assertNull(PairingIdentityFiles.location(temp.root, first).backupKey)
    }

    @Test fun globalResetOnEmptyInstallationIsSafe() {
        PairingIdentityFiles.resetAll(temp.root)
        assertTrue(temp.root.isDirectory)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

}
