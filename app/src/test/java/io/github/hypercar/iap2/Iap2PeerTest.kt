package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2PeerTest {
    @Test fun softwareIdentityIsScopedToCurrentPeerAndSession() {
        Iap2Peer.clear()
        Iap2Peer.remember("AA:BB:CC:DD:EE:01", "Beam Pro")
        Iap2Peer.rememberIdentification("AA:BB:CC:DD:EE:01", "DiPlay")
        assertEquals("DiPlay", Iap2Peer.softwareIdentityFor("aa:bb:cc:dd:ee:01"))
        assertNull(Iap2Peer.softwareIdentityFor("AA:BB:CC:DD:EE:02"))
        Iap2Peer.remember("AA:BB:CC:DD:EE:02", "BMW")
        Iap2Peer.rememberIdentification("AA:BB:CC:DD:EE:01", "DiPlay")
        assertNull(Iap2Peer.softwareIdentityFor("AA:BB:CC:DD:EE:02"))
        Iap2Peer.rememberIdentification("AA:BB:CC:DD:EE:02", "BMW MGU")
        Iap2Peer.remember("AA:BB:CC:DD:EE:02", "BMW")
        assertNull(Iap2Peer.softwareIdentityFor("AA:BB:CC:DD:EE:02"))
        Iap2Peer.clear()
    }
}
