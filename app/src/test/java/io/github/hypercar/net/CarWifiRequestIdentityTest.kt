package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarWifiRequestIdentityTest {
    @Test fun pendingSecurityChangeReplacesRequestButAvailableSameHotspotSurvives() {
        val identity = CarWifiRequestIdentity("car", "password", 2)
        assertTrue(identity.requiresCredentialUpdate("car", "password", 4))
        assertFalse(identity.requiresCredentialUpdate("car", "password", 4, associated = true))
        assertFalse(identity.requiresCredentialUpdate("car", "password", 3))
        assertFalse(identity.requiresCredentialUpdate("car", "password", null))
        assertTrue(identity.requiresCredentialUpdate("other", "password", 4, associated = true))
        assertTrue(identity.requiresCredentialUpdate("car", "new-password", 4, associated = true))
        assertFalse(CarWifiRequestIdentity("car", "password").requiresCredentialUpdate("car", "password", 2))
    }
    @Test fun onlyAnExplicitDifferentSsidAllowsReplacingAnAvailableAssociation() {
        val identity = CarWifiRequestIdentity("car", "secret")
        assertFalse(identity.requiresDifferentHotspot("car"))
        assertFalse(identity.requiresDifferentHotspot(null))
        assertFalse(identity.requiresDifferentHotspot(""))
        assertFalse(identity.requiresDifferentHotspot("  "))
        assertTrue(identity.requiresDifferentHotspot("other-car"))
        // Optional or changed passwords never authorize disconnecting an associated same AP.
        assertFalse(identity.matches("car", null))
        assertFalse(identity.matches("car", "new-secret"))
        assertFalse(identity.requiresDifferentHotspot("car"))
    }
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
