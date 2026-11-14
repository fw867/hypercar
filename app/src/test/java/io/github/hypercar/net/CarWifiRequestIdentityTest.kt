package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarWifiRequestIdentityTest {
    @Test fun onlyExactLiveRequestCredentialsMaySupplyHiddenSsidEvidence() {
        val identity = CarWifiRequestIdentity("MBUX car", "car-secret")
        assertTrue(identity.matches("MBUX car", "car-secret"))
        assertFalse(identity.matches("BMW car", "car-secret"))
        assertFalse(identity.matches("MBUX car", "changed-secret"))
        assertFalse(identity.matches(null, "car-secret"))
        assertFalse(identity.matches("MBUX car", null))
        assertTrue(CarWifiRequestIdentity("open car", "").matches("open car", ""))
    }
}
