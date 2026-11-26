package io.github.hypercar.headunit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitPickerTest {
    @Test fun groupsByBrandAndKeepsSpecialCategoriesSeparate() {
        val groups = HeadUnitPicker.groups(HeadUnitProfiles.builtin).associateBy { it.label }
        assertEquals(setOf("bmw_id85", "bmw_id6", "bmw_id9"),
            groups.getValue("宝马 BMW").profiles.map { it.id }.toSet())
        assertEquals(listOf(HeadUnitProfiles.MOTORCYCLE), groups.getValue("摩托车").profiles)
        assertEquals(listOf(HeadUnitProfiles.GENERIC), groups.getValue("通用").profiles)
        assertEquals(listOf(HeadUnitProfiles.WINPLAY), groups.getValue("桌面接收端").profiles)
    }

    @Test fun searchUsesModelAliasesAndPlatformWithoutDuplicatingProfiles() {
        val profiles = HeadUnitProfiles.builtin
        assertEquals(listOf(HeadUnitProfiles.VOLKSWAGEN), HeadUnitPicker.search(profiles, "大众 帕萨特2025"))
        assertEquals(listOf(HeadUnitProfiles.VOLKSWAGEN), HeadUnitPicker.search(profiles, "ID.7"))
        assertEquals(listOf(HeadUnitProfiles.BMW_ID85), HeadUnitPicker.search(profiles, "ID7"))
        assertEquals(listOf(HeadUnitProfiles.BMW_ID85), HeadUnitPicker.search(profiles, "MGU55"))
        assertEquals(listOf(HeadUnitProfiles.BMW_ID6), HeadUnitPicker.search(profiles, "宝马 nbt evo"))
        assertEquals(listOf(HeadUnitProfiles.BMW_ID9), HeadUnitPicker.search(profiles, "IDC23"))
        assertEquals(listOf(HeadUnitProfiles.MOTORCYCLE), HeadUnitPicker.search(profiles, "突破者 m2pro"))
    }

    @Test fun citroenIsListedUnderItsBrandAndSearchableByTheC5XSample() {
        val profiles = HeadUnitProfiles.builtin
        val group = HeadUnitPicker.groups(profiles).single { it.id == "brand:citroen" }
        val profile = group.profiles.single()
        assertEquals("雪铁龙 Citroën", group.label)
        assertEquals("citroen_generic", profile.id)
        for (query in listOf("凡尔赛 C5 X", "Citroen C5X", "Marelli", "CARLINE_1")) {
            assertEquals(query, listOf(profile), HeadUnitPicker.search(profiles, query))
        }
    }

    @Test fun groupingUsesRemoteMetadataButSearchOnlySeesEligibleEntries() {
        val snapshot = RemoteProfiles.parseSnapshot("""{"_schema":13,"profiles":[
            {"id":"ford_one","label":"SYNC 4","category":"car","brandId":"ford","brandName":"福特 Ford",
             "vehicleModels":["蒙迪欧"],"aliases":["Mondeo"],"platformName":"SYNC 4"},
            {"id":"unspecified","label":"Custom"},
            {"id":"bmw_id85","label":"BMW 控制器"}
        ],"versionedProfiles":[
            {"id":"ford_two","label":"Future","brandId":"ford","brandName":"福特 Ford","minVersionCode":10717}
        ]}""")
        val old = RemoteProfiles.mergeCatalog(HeadUnitProfiles.builtin, snapshot, 10716)
        val groups = HeadUnitPicker.groups(old.profiles).associateBy { it.label }
        val ford = groups.getValue("福特 Ford").profiles.single()
        assertEquals("ford_one", ford.id)
        assertEquals(listOf(ford), HeadUnitPicker.search(old.profiles, "ford mondeo"))
        assertEquals(listOf("unspecified"), groups.getValue("未分类").profiles.map { it.id })
        assertTrue(groups.getValue("宝马 BMW").profiles.any { it.label == "BMW 控制器" })
        assertFalse(groups.getValue("福特 Ford").profiles.any { it.id == "ford_two" })
        assertTrue(HeadUnitPicker.search(old.profiles, "Future").isEmpty())
        assertEquals(listOf(ford), RemoteProfiles.parse(RemoteProfiles.encode(listOf(ford))))
    }
}
