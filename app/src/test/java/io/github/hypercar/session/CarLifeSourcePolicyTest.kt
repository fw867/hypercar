package io.github.hypercar.session

import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.settings.CarWithDisplay
import org.junit.Assert.*
import org.junit.Test

class CarLifeSourcePolicyTest {
    @Test fun id85CanHoldItsReceiversSocketWhileThePhoneSourceStarts() {
        val peer = "20:30:40:50:60:70"
        for (source in listOf("carwith", "system_carlife")) {
            assertTrue(CarLifeSourcePolicy.canPrepareBmwInbound(source, HeadUnitProfiles.BMW_ID85, peer))
            assertFalse(CarLifeSourcePolicy.canPrepareBmwInbound(source, HeadUnitProfiles.BMW_ID85, null))
            assertFalse(CarLifeSourcePolicy.canPrepareBmwInbound(source, HeadUnitProfiles.BMW_ID85, "02:00:00:00:00:00"))
            assertFalse(CarLifeSourcePolicy.canPrepareBmwInbound(source, HeadUnitProfiles.BMW_ID9, peer))
            assertFalse(CarLifeSourcePolicy.canPrepareBmwInbound(source, HeadUnitProfiles.MOTORCYCLE, peer))
        }
        assertTrue(CarLifeSourcePolicy.canPrepareBmwInbound("mirror", HeadUnitProfiles.BMW_ID9, null))
        assertTrue(CarLifeSourcePolicy.canPrepareBmwInbound("mirror", HeadUnitProfiles.MOTORCYCLE, null))
        assertFalse(CarLifeSourcePolicy.canPrepareBmwInbound("mirror", HeadUnitProfiles.GENERIC, peer))
    }

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
    @Test fun systemPrewarmUsesTheReceiverCacheThenTheManualCanvas() {
        val manual = 2880 to 900
        assertEquals(2736 to 800, CarLifeSourcePolicy.prewarmSize(true, CarWithDisplay(2736, 800, 60), manual))
        assertEquals(manual, CarLifeSourcePolicy.prewarmSize(true, null, manual))
        assertEquals(1920 to 1080, CarLifeSourcePolicy.prewarmSize(false, null, manual))
    }

    @Test fun systemCanvasRequiresEvidenceForTheCurrentReceiver() {
        val profile = "bmw_id85"
        val key = "bmw_id85:bt_101112131415"
        assertNull(CarLifeSourcePolicy.systemCanvasKey(profile, null, null, first, null))
        assertNull(CarLifeSourcePolicy.systemCanvasKey(profile, null, first, null, null))
        assertNull(CarLifeSourcePolicy.systemCanvasKey(profile, null, null, null, first))
        assertNull(CarLifeSourcePolicy.systemCanvasKey(profile, second, first, first, first))
        assertNull(CarLifeSourcePolicy.systemCanvasKey(profile, first, first, second, null))
        assertEquals(key, CarLifeSourcePolicy.systemCanvasKey(profile, first, first, null, null))
        assertEquals(key, CarLifeSourcePolicy.systemCanvasKey(profile, null, null, first, first))
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
