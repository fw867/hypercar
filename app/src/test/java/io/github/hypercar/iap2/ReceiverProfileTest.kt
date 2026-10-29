package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class ReceiverProfileTest {
    @Test fun bmwTwentySixMessagesWithoutWirelessIdsRemainUnknown() {
        val sends = setOf(0x4c00, 0x4c02, 0x4c03, 0x4c05, 0x4c07, 0x5000, 0x5002, 0x5003,
            0x6800, 0x6802, 0x6803, 0xae00, 0xae02, 0xea03)
        val receives = setOf(0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x5001, 0x6801,
            0x4c01, 0x4c04, 0x4e0d, 0xae01, 0xea00, 0xea01)
        assertEquals(26, sends.size + receives.size)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, ReceiverProfile.classifyWifiGen(sends, receives))
    }

    @Test fun explicitlyAdvertisedStartSessionSelectsNewPath() {
        assertEquals(ReceiverProfile.WifiGen.NEW,
            ReceiverProfile.classifyWifiGen(setOf(0x4301), null))
    }

    @Test fun legacyCredentialsTakePrecedenceEvenWhenStartSessionIsAvailable() {
        assertEquals(ReceiverProfile.WifiGen.LEGACY,
            ReceiverProfile.classifyWifiGen(setOf(0x4301, 0x5703), setOf(0x5702)))
    }
}
