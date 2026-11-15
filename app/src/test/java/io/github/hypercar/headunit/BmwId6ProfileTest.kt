package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class BmwId6ProfileTest {
    @Test fun independentExperimentalProfilePreservesSavedMguAndId9Policies() {
        val p = HeadUnitProfiles.BMW_ID6
        assertEquals("bmw_id6", p.id)
        assertFalse(p.verified)
        assertTrue(p.nbtAudioFirst)
        assertTrue(p.screenResourceLifecycleEnabled(false))
        assertFalse(p.id9ScreenBootstrap)
        assertFalse(p.mbuxScreenBootstrap)
        assertFalse(p.openEaSessions)
        assertFalse(p.sendTransportIdentifier)
        assertFalse(p.dropProfilesBeforeIap2)
        assertFalse(p.allowOutboundIap2)
        assertEquals(1, HeadUnitProfiles.builtin.count { it.id == p.id })
        assertFalse(ReceiverCompatibility.useBmwBootstrap(p.id, "NBT-EVO"))
        for (other in HeadUnitProfiles.builtin.filter { it.id != p.id }) {
            assertFalse(other.id, other.nbtAudioFirst)
            assertEquals(other.id, other, other.connectionPolicy())
            assertFalse(other.copy(nbtAudioFirst = true).connectionPolicy().nbtAudioFirst)
        }
        assertTrue(ReceiverCompatibility.useBmwBootstrap(HeadUnitProfiles.BMW_ID85.id, "MGU-55"))
    }

    @Test fun remoteAndCacheCannotReenableCompetingBootstrapOrDowngradeId6Policy() {
        val p = HeadUnitProfiles.BMW_ID6
        val stale = p.copy(nbtAudioFirst = false, id9ScreenBootstrap = true,
            mbuxScreenBootstrap = true, useScreenResourceLifecycle = false)
        assertEquals(p, stale.connectionPolicy())
        assertEquals(listOf(p), RemoteProfiles.parse(RemoteProfiles.encode(listOf(p))))
        assertEquals(p, RemoteProfiles.parse("""[{"id":"bmw_id6","nbtAudioFirst":false,
            "id9ScreenBootstrap":true,"mbuxScreenBootstrap":true,"useScreenResourceLifecycle":false}]""").single())
        assertFalse(RemoteProfiles.canOverrideBuiltin(p.id, 10))
        assertTrue(RemoteProfiles.canOverrideBuiltin(p.id, 11))
    }
}
