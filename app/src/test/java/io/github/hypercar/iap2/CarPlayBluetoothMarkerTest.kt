package io.github.hypercar.iap2

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayBluetoothMarkerTest {
    private val phone = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a")
    private val discovery = UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77")

    @Test fun bmwProfilesPublishDiscoveryBeforePhoneAndKeepTheExistingInboundRoute() {
        for (profile in listOf("bmw_id6", "bmw_id85", "bmw_id9", "bmw_custom")) {
            val markers = CarPlayBluetoothMarker.forProfile(profile)
            assertEquals(profile, listOf(discovery, phone), markers.map { it.uuid })
            assertEquals(profile, listOf(phone), markers.filter { it.acceptsIap2 }.map { it.uuid })
        }
    }

    @Test fun otherAndUnknownProfilesKeepOnlyTheOriginalPhoneMarker() {
        for (profile in listOf("mercedes_mbux", "audi_mib3", "volkswagen_mib", "generic", "winplay", "")) {
            assertEquals(listOf(phone), CarPlayBluetoothMarker.forProfile(profile).map { it.uuid })
        }
    }

    @Test fun phoneMarkerWaitsForPublishedDiscoveryEvenAfterAListenerFailure() {
        val services = CarPlayBluetoothMarker.forProfile("bmw_id85")
        val published = mutableSetOf<UUID>()
        fun required() = CarPlayBluetoothMarker.registrationPrefix(services) { it.uuid in published }.map { it.uuid }
        assertEquals(listOf(discovery), required())
        published.add(discovery)
        assertEquals(listOf(discovery, phone), required())
        published.add(phone)
        assertEquals(listOf(discovery, phone), required())
        published.remove(discovery)
        assertEquals(listOf(discovery), required())
    }
}
