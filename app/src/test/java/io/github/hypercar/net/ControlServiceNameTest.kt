package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class ControlServiceNameTest {
    @Test fun onlyId85UsesPhoneName() {
        assertEquals("小米 Phone", ControlServiceName.forProfile("bmw_id85", "小米 Phone"))
        for (id in listOf("bmw_id9", "bmw_id6", "generic", "mercedes", "winplay")) {
            assertEquals("sender", ControlServiceName.forProfile(id, "小米 Phone"))
        }
    }

    @Test fun dnsLabelUsesWholeUnicodeCodePointsWithin63Bytes() {
        val name = ControlServiceName.forProfile("bmw_id85", "🚗".repeat(20))
        assertEquals("🚗".repeat(15), name)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 63)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test fun controlCharactersAndBlankNamesAreSanitized() {
        assertEquals("My Phone", ControlServiceName.forProfile("bmw_id85", " \u0000My Phone\n "))
        assertEquals("HyperCar", ControlServiceName.forProfile("bmw_id85", " \n "))
    }
}

