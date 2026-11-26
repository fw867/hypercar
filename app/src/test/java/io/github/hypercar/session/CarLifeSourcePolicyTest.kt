package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class CarLifeSourcePolicyTest {
    @Test fun systemCallAudioRequiresOptIn() {
        assertTrue(CarLifeSourcePolicy.allowsCallAudio("carwith", false))
        assertTrue(CarLifeSourcePolicy.allowsCallAudio("carwith", true))
        assertFalse(CarLifeSourcePolicy.allowsCallAudio("system_carlife", false))
        assertTrue(CarLifeSourcePolicy.allowsCallAudio("system_carlife", true))
        assertFalse(CarLifeSourcePolicy.allowsCallAudio("car_ui", true))
    }

    private val first="10:11:12:13:14:15"
    private val second="20:21:22:23:24:25"
    @Test fun onlyCarLifeSourcesShareTheGateway() {
        assertTrue(CarLifeSourcePolicy.isGateway("carwith"))
        assertTrue(CarLifeSourcePolicy.isGateway("system_carlife"))
        for (source in listOf("car_ui","dex","unknown","")) assertFalse(CarLifeSourcePolicy.isGateway(source))
    }
    @Test fun liveSystemEngineIsNeverRelaunchedForALateDisplay() {
        assertTrue(CarLifeSourcePolicy.shouldReopen("carwith",false))
        assertTrue(CarLifeSourcePolicy.shouldReopen("carwith",true))
        assertFalse(CarLifeSourcePolicy.shouldReopen("system_carlife",false))
        assertTrue(CarLifeSourcePolicy.shouldReopen("system_carlife",true))
        assertFalse(CarLifeSourcePolicy.shouldReopen("car_ui",true))
    }
    @Test fun actualReceiverWinsWithoutBorrowingAnotherCarsName() {
        val id=CarLifeSourcePolicy.huIdentity(first,second,"Other car",setOf("$first|Current car|1","$second|Other car|2"))!!
        assertEquals(first,id.address)
        assertEquals("Current car",id.name)
    }
    @Test fun selectedTargetWorksBeforeBluetoothIdentification() {
        val id=CarLifeSourcePolicy.huIdentity(null,first.lowercase(),"My car",emptySet())!!
        assertEquals(first,id.address)
        assertEquals("My car",id.name)
    }
    @Test fun unrelatedRecentPairingIsNeverAnIdentityFallback() {
        assertNull(CarLifeSourcePolicy.huIdentity(null,null,null,setOf("$second|Recent car|999")))
        val id=CarLifeSourcePolicy.huIdentity(first,second,"Other car",setOf("$second|Other car|999"))!!
        assertEquals(first,id.name)
    }
    @Test fun invalidTargetDoesNotBecomeAnHuAddress() {
        assertNull(CarLifeSourcePolicy.huIdentity("invalid","00:00:00:00:00:00","Bad",emptySet()))
    }
    @Test fun broadcastAndMaskedReceiverFallBackToAValidTarget() {
        for (invalid in listOf("FF:FF:FF:FF:FF:FF","ff:ff:ff:ff:ff:ff","02:00:00:00:00:00","00:00:00:00:00:00")) {
            assertNull(CarLifeSourcePolicy.huIdentity(invalid,null,null,emptySet()))
            assertEquals(second,CarLifeSourcePolicy.huIdentity(invalid,second,"Target",emptySet())!!.address)
        }
    }
    @Test fun bluetoothAddressUsesOneCanonicalCase() {
        assertEquals("AA:BB:CC:DD:EE:FF",CarLifeSourcePolicy.huIdentity("aa:bb:cc:dd:ee:ff",null,null,emptySet())!!.address)
    }
}
