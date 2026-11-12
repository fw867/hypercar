package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class WifiApHintTest {
    private val scan="  3e:af:6d:8e:33:09       5745        -38             2.203    Beam Pro                          [WPA2-PSK][ESS]"
    @Test fun requiresExactSsidAndInvitedApRatherThanBluetoothMac() {
        val hint=WifiApHint.fromScan(scan,"Beam Pro","3E:AF:6D:8E:33:09")!!
        assertEquals(5745,hint.frequency)
        assertEquals(2.203,hint.ageSeconds,0.001)
        assertNull(WifiApHint.fromScan(scan,"Beam","3e:af:6d:8e:33:09"))
        assertNull(WifiApHint.fromScan(scan,"Beam Pro","54:2f:04:37:44:15"))
        assertNull(WifiApHint.fromScan(scan,"Beam Pro",null))
    }
    @Test fun rejectsInvalidAndMulticastIdentity() {
        assertNull(WifiApHint.fromScan(scan,"Beam Pro","unknown"))
        assertNull(WifiApHint.fromScan(scan.replace("3e:","3f:"),"Beam Pro","3f:af:6d:8e:33:09"))
        assertNull(WifiApHint.fromScan("malformed","Beam Pro","3e:af:6d:8e:33:09"))
    }
}
