package io.github.hypercar.headunit

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteProfilesTest {
    @Test fun schemaSevenCannotRestoreVolkswagenUnverifiedLabelOrChangeItsHandshake() {
        val old = RemoteProfiles.parse("""{"_schema":7,"profiles":[{
            "id":"volkswagen_mib","verified":false,"notes":"未实车验证"}]}""")
        val effective = RemoteProfiles.merge(HeadUnitProfiles.builtin, old, 7)
            .single { it.id == "volkswagen_mib" }
        assertEquals(HeadUnitProfiles.VOLKSWAGEN, effective)
        assertTrue(effective.verified)
        assertFalse(HeadUnitProfiles.MERCEDES_MBUX.verified)
    }

    @Test fun cachedSchemaSixCannotDisableAudiHandshakeMessages() {
        val old = RemoteProfiles.parse("""{"_schema":6,"profiles":[{
            "id":"audi_mib3","sendCarPlayAvailability":false,
            "openEaSessions":false,"sendTransportIdentifier":false}]}""")
        val effective = RemoteProfiles.merge(HeadUnitProfiles.builtin, old, 6)
            .single { it.id == "audi_mib3" }
        assertEquals(HeadUnitProfiles.AUDI_MIB3, effective)
        assertTrue(effective.sendCarPlayAvailability)
        assertTrue(effective.openEaSessions)
        assertTrue(effective.sendTransportIdentifier)
        // Credential-only MMI must still discover the endpoint without a ctrl invite.
        assertFalse(effective.requireAirplayActivation)
        assertFalse(effective.requireFreshCtrlInvite)
    }

    @Test fun volkswagenFieldsInheritAndRoundTripWithoutOldCacheOverridingTheGate() {
        val vw = HeadUnitProfiles.VOLKSWAGEN
        val inherited = RemoteProfiles.parse("""{"profiles":[{"id":"volkswagen_mib"}]}""").single()
        assertEquals(vw, inherited)
        val stale = vw.copy(requireFreshCtrlInvite = false, allowProactiveWifiSession = true)
        val merged = RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(stale), 5)
        assertEquals(vw, merged.single { it.id == vw.id })
        assertEquals(listOf(vw), RemoteProfiles.parse(RemoteProfiles.encode(listOf(vw))))

    }

    @Test fun omittedFieldsInheritEachBuiltinIncludingActivationAndHints() {
        for (builtin in HeadUnitProfiles.builtin) {
            val parsed = RemoteProfiles.parse("""[{"id":"${builtin.id}"}]""").single()
            assertEquals(builtin.id, builtin, parsed)
        }
    }

    @Test fun explicitOverridesCanDisableActivationAndClearOptionalValues() {
        val parsed = RemoteProfiles.parse("""[{"id":"bmw_id85",
            "requireAirplayActivation":false,"dropProfilesBeforeIap2":false,
            "pairingHint":"","touchDisplay":""}]""").single()
        assertFalse(parsed.requireAirplayActivation)
        assertFalse(parsed.dropProfilesBeforeIap2)
        assertNull(parsed.pairingHint)
        assertNull(parsed.touchDisplay)
    }

    @Test fun newProfileUsesBmwTemplateForOmittedPolicies() {
        val parsed = RemoteProfiles.parse("""[{"id":"new_car","label":"New car"}]""").single()
        assertEquals(HeadUnitProfiles.BMW_ID85.copy(id = "new_car", label = "New car"), parsed)
    }

    @Test fun oldCacheCannotOverrideBuiltinsButCanAddProfiles() {
        val stale = HeadUnitProfiles.BMW_ID85.copy(
            requireAirplayActivation = false, dropProfilesBeforeIap2 = false,
        )
        val extra = stale.copy(id = "extra")
        val merged = RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(stale, extra), 3)
        assertEquals(HeadUnitProfiles.BMW_ID85, merged.first { it.id == stale.id })
        assertEquals(extra, merged.last())
    }

    @Test fun currentSchemaAcceptsIntentionalOverrides() {
        val override = HeadUnitProfiles.BMW_ID85.copy(requireAirplayActivation = false)
        val merged = RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(override), RemoteProfiles.SCHEMA)
        assertEquals(override, merged.first())
    }

    @Test fun exportPreservesAllPoliciesAndCanBeMergedAgain() {
        val profiles = HeadUnitProfiles.builtin + HeadUnitProfiles.WINPLAY.copy(
            id = "custom", requireAirplayActivation = true,
            touchDisplay = null, pairingHint = null,
        )
        val raw = RemoteProfiles.encode(profiles)
        val schema = JSONObject(raw).getInt("_schema")
        assertEquals(RemoteProfiles.SCHEMA, schema)
        val parsed = RemoteProfiles.parse(raw)
        assertEquals(profiles, parsed)
        assertEquals(profiles, RemoteProfiles.merge(HeadUnitProfiles.builtin, parsed, schema))
    }

    @Test fun publishedConfigMatchesBuiltinConnectionPolicies() {
        val config = listOf(File("../profiles/headunits.json"), File("profiles/headunits.json"))
            .first { it.isFile }.readText()
        assertEquals(RemoteProfiles.SCHEMA, JSONObject(config).getInt("_schema"))
        val remote = RemoteProfiles.parse(config).associateBy { it.id }
        assertEquals(HeadUnitProfiles.builtin.map { it.id }.toSet(), remote.keys)
        for (builtin in HeadUnitProfiles.builtin) {
            // Display copy may differ, transport and lifecycle policies must not drift.
            val actual = remote.getValue(builtin.id)
            assertEquals(builtin.id, builtin.copy(
                label = actual.label, model = actual.model, notes = actual.notes,
                pairingHint = actual.pairingHint,
            ), actual)
        }
    }
    @Test fun cachedSchemaFourCannotReenableBmwAudioProfileDrops() {
        val stale = RemoteProfiles.parse("""{"_schema":4,"profiles":[
            {"id":"bmw_id85","dropProfilesBeforeIap2":true}]}""")
        val effective = RemoteProfiles.merge(HeadUnitProfiles.builtin, stale, 4)
            .single { it.id == "bmw_id85" }
        assertFalse(effective.dropProfilesBeforeIap2)
        assertFalse(effective.allowOutboundIap2)
        assertTrue(effective.requireAirplayActivation)
    }
}
