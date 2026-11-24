package io.github.hypercar.headunit

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteProfilesTest {
    @Test fun mguUsesResourceLifecycleWithoutChangingBootstrapOrActivationPolicy() {
        val mgu = HeadUnitProfiles.BMW_ID85
        assertTrue(mgu.screenResourceLifecycleEnabled(false))
        assertFalse(mgu.nbtAudioFirst)
        assertFalse(mgu.id9ScreenBootstrap)
        assertFalse(mgu.mbuxScreenBootstrap)
        assertTrue(mgu.requireAirplayActivation)
        assertTrue(mgu.openEaSessions)
        assertTrue(mgu.sendTransportIdentifier)
        assertFalse(mgu.allowOutboundIap2)
        assertTrue(ReceiverCompatibility.useBmwBootstrap(mgu.id, "BMW MGU"))
    }

    @Test fun schemaTwelveProtectsMguLifecycleWhilePreservingOtherReceiverOverrides() {
        val mgu = HeadUnitProfiles.BMW_ID85
        val stale = mgu.copy(useScreenResourceLifecycle = false)
        for (schema in 8..11) {
            val merged = RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(stale), schema)
            assertTrue("schema=$schema", merged.single { it.id == mgu.id }.useScreenResourceLifecycle)
            assertFalse(RemoteProfiles.canOverrideBuiltin(mgu.id, schema))
        }
        assertEquals(13, RemoteProfiles.SCHEMA)
        assertTrue(RemoteProfiles.canOverrideBuiltin(mgu.id, 12))
        assertEquals(stale, RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(stale), 12)
            .single { it.id == mgu.id })
        for ((profile, schema) in listOf(HeadUnitProfiles.BMW_ID6 to 11,
            HeadUnitProfiles.BMW_ID9 to 9, HeadUnitProfiles.MERCEDES_MBUX to 10,
            HeadUnitProfiles.VOLKSWAGEN to 8)) {
            val override = profile.copy(preferMdnsAirplay = !profile.preferMdnsAirplay)
            assertEquals(override, RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(override), schema)
                .single { it.id == profile.id })
        }
    }

    @Test fun schemaSevenCannotRestoreVolkswagenUnverifiedLabelOrChangeItsHandshake() {
        val old = RemoteProfiles.parse("""{"_schema":7,"profiles":[{
            "id":"volkswagen_mib","verified":false,"notes":"未实车验证"}]}""")
        val effective = RemoteProfiles.merge(HeadUnitProfiles.builtin, old, 7)
            .single { it.id == "volkswagen_mib" }
        assertEquals(HeadUnitProfiles.VOLKSWAGEN, effective)
        assertTrue(effective.verified)
        assertTrue(HeadUnitProfiles.MERCEDES_MBUX.verified)
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
        val stale = vw.copy(requireFreshCtrlInvite = false)
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
        assertEquals(HeadUnitProfiles.BMW_ID85.copy(id = "new_car", label = "New car", verified = false,
            listing = HeadUnitListing()), parsed)
    }

    @Test fun id9BootstrapIsInheritedAndCannotBeDisabledBySchemaEightCache() {
        val id9 = HeadUnitProfiles.BMW_ID9
        assertEquals(id9, RemoteProfiles.parse("""[{"id":"bmw_id9"}]""").single())
        val old = id9.copy(id9ScreenBootstrap = false)
        assertTrue(RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(old), 8).single { it.id == id9.id }.id9ScreenBootstrap)
        assertEquals(id9, RemoteProfiles.parse(RemoteProfiles.encode(listOf(id9))).single())
    }

    @Test fun id9SchemaUpgradeDoesNotInvalidateExistingReceiversSchemaEightOverrides() {
        val overrides = listOf(HeadUnitProfiles.AUDI_MIB3, HeadUnitProfiles.VOLKSWAGEN, HeadUnitProfiles.WINPLAY,
            HeadUnitProfiles.GENERIC).map { it.copy(preferMdnsAirplay = !it.preferMdnsAirplay) }
        val oldId9 = HeadUnitProfiles.BMW_ID9.copy(id9ScreenBootstrap = false)
        val effective = RemoteProfiles.merge(HeadUnitProfiles.builtin, overrides + oldId9, 8)
        overrides.forEach { expected ->
            assertEquals(expected.id, expected, effective.single { it.id == expected.id })
            assertTrue(RemoteProfiles.canOverrideBuiltin(expected.id, 8))
            assertFalse(RemoteProfiles.canOverrideBuiltin(expected.id, 7))
        }
        assertEquals(HeadUnitProfiles.BMW_ID9, effective.single { it.id == oldId9.id })
        assertFalse(RemoteProfiles.canOverrideBuiltin(oldId9.id, 8))
        assertTrue(RemoteProfiles.canOverrideBuiltin(oldId9.id, 9))
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

    @Test fun oldVerificationCopyCannotHideTheCurrentResultOrChangeTransportOverrides() {
        val audi = HeadUnitProfiles.AUDI_MIB3
        val stale = audi.copy(label = "自定义奥迪", notes = "未实车验证", verified = false,
            model = "旧说明", pairingHint = "旧引导", allowOutboundIap2 = false)
        val updated = audi.copy(label = stale.label, allowOutboundIap2 = false)
        assertEquals(updated, RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(stale), 12)
            .single { it.id == audi.id })
        assertEquals(stale, RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(stale), 13)
            .single { it.id == audi.id })
    }

    @Test fun outdatedNotesCannotReplaceTheCurrentDescriptionForAnAlreadyVerifiedProfile() {
        val vw = HeadUnitProfiles.VOLKSWAGEN
        val cached = vw.copy(notes = "旧说明", preferMdnsAirplay = false)
        assertEquals(cached.copy(notes = vw.notes), RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(cached), 12)
            .single { it.id == vw.id })
        assertEquals(cached, RemoteProfiles.merge(HeadUnitProfiles.builtin, listOf(cached), 13)
            .single { it.id == vw.id })
    }

    @Test fun appVersionGatesRemoteAdditionsAndOverridesEvenWhenRestoredFromCache() {
        val raw = """{"profiles":[{"id":"legacy_extra","label":"Legacy"}],"versionedProfiles":[
            {"id":"bmw_id85","label":"New BMW","minVersionCode":10716},
            {"id":"legacy_extra","label":"Updated extra","minVersionCode":10716},
            {"id":"versioned_extra","label":"New receiver","minVersionCode":10716}
        ]}"""
        val cached = RemoteProfiles.parse(raw)
        fun effectiveAt(versionCode: Int, schema: Int = RemoteProfiles.SCHEMA) =
            RemoteProfiles.merge(HeadUnitProfiles.builtin, cached, schema, versionCode).associateBy { it.id }
        val older = effectiveAt(10715)
        assertEquals(HeadUnitProfiles.BMW_ID85, older.getValue("bmw_id85"))
        assertEquals("Legacy", older.getValue("legacy_extra").label)
        assertFalse("versioned_extra" in older)
        for (eligible in listOf(10716, 10717)) {
            val effective = effectiveAt(eligible)
            assertEquals("New BMW", effective.getValue("bmw_id85").label)
            assertEquals("Updated extra", effective.getValue("legacy_extra").label)
            assertEquals("New receiver", effective.getValue("versioned_extra").label)
            assertEquals(HeadUnitProfiles.BMW_ID85, effectiveAt(eligible, schema = 11).getValue("bmw_id85"))
        }
    }

    @Test fun invalidVersionRequirementsCannotEnableRemoteEntries() {
        val raw = """{"profiles":[{"id":"legacy"}],"versionedProfiles":[
            {"id":"missing"},{"id":"invalid","minVersionCode":"10716"},
            {"id":"fractional","minVersionCode":10716.9},{"id":"zero","minVersionCode":0},
            {"id":"negative","minVersionCode":-1},{"id":"overflow","minVersionCode":2147483648},
            {"id":"valid","minVersionCode":10716}
        ]}"""
        assertEquals(listOf("legacy", "valid"), RemoteProfiles.parse(raw).map { it.id })
    }

    @Test fun eachCatalogUsesItsOwnSchemaAndCountsOnlyAppliedRemoteEntries() {
        val old = RemoteProfiles.parseSnapshot("""{"_schema":11,"profiles":[
            {"id":"bmw_id85","label":"Old override"},{"id":"active_extra"},
            {"id":"active_extra","label":"Duplicate extra"}
        ],"versionedProfiles":[{"id":"locked_extra","minVersionCode":10717}]}""")
        val fresh = RemoteProfiles.parseSnapshot("""{"_schema":12,"profiles":[
            {"id":"bmw_id85","label":"Fresh override"}
        ]}""")
        val oldCatalog = RemoteProfiles.mergeCatalog(HeadUnitProfiles.builtin, old, 10716)
        val freshCatalog = RemoteProfiles.mergeCatalog(HeadUnitProfiles.builtin, fresh, 10716)
        assertEquals(setOf("active_extra"), oldCatalog.remoteIds)
        assertEquals(HeadUnitProfiles.BMW_ID85, oldCatalog.select("bmw_id85").profile)
        assertEquals(setOf("bmw_id85"), freshCatalog.remoteIds)
        assertEquals("Fresh override", freshCatalog.select("bmw_id85").profile?.label)
    }

    @Test fun remoteOnlySelectionCanBeLockedOrRemovedWithoutFallingBackToBmw() {
        val updates = RemoteProfiles.parseSnapshot("""{"_schema":12,"versionedProfiles":[
            {"id":"remote_car","label":"Remote Car","minVersionCode":10716},
            {"id":"remote_car","label":"New Remote Car","minVersionCode":10717},
            {"id":"bmw_id85","minVersionCode":10717}
        ]}""")
        val older = RemoteProfiles.mergeCatalog(HeadUnitProfiles.builtin, updates, 10715)
        assertNull(older.select("remote_car").profile)
        assertEquals("Remote Car", older.select("remote_car").label)
        assertTrue(older.select("remote_car").blockReason.orEmpty().contains("10716"))
        assertEquals(HeadUnitProfiles.BMW_ID85, older.select("bmw_id85").profile)
        assertNull(older.select("bmw_id85").blockReason)
        assertNull(older.select("removed_car").profile)
        assertNotNull(older.select("removed_car").blockReason)
        val eligible = RemoteProfiles.mergeCatalog(HeadUnitProfiles.builtin, updates, 10716)
        assertEquals("Remote Car", eligible.select("remote_car").profile?.label)
        assertNull(eligible.select("remote_car").blockReason)
    }

    @Test fun exportKeepsGatedEntriesInvisibleToLegacyReaders() {
        val plain = HeadUnitProfiles.GENERIC
        val gated = HeadUnitProfiles.MOTORCYCLE.copy(minVersionCode = 10716)
        val raw = RemoteProfiles.encode(listOf(plain, gated))
        assertEquals(plain.id, JSONObject(raw).getJSONArray("profiles").getJSONObject(0).getString("id"))
        assertEquals(1, JSONObject(raw).getJSONArray("profiles").length())
        assertEquals(listOf(plain, gated), RemoteProfiles.parse(raw))
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
        val legacyList = JSONObject(config).getJSONArray("profiles")
        assertFalse((0 until legacyList.length()).any {
            legacyList.getJSONObject(it).getString("id") == HeadUnitProfiles.MOTORCYCLE_ID
        })
        assertFalse((0 until legacyList.length()).any {
            legacyList.getJSONObject(it).has("minVersionCode")
        })
        val remote = RemoteProfiles.parse(config).associateBy { it.id }
        assertEquals(HeadUnitProfiles.builtin.map { it.id }.toSet(), remote.keys)
        assertTrue(HeadUnitProfiles.builtin.all { it.verified })
        assertTrue(remote.values.all { it.verified })
        assertEquals(10716, remote.getValue(HeadUnitProfiles.MOTORCYCLE_ID).minVersionCode)
        for (builtin in HeadUnitProfiles.builtin) {
            // Display copy may differ, transport and lifecycle policies must not drift.
            val actual = remote.getValue(builtin.id)
            assertEquals(builtin.id, builtin.copy(
                label = actual.label, model = actual.model, notes = actual.notes,
                pairingHint = actual.pairingHint, minVersionCode = actual.minVersionCode,
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
