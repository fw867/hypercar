package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2ConnectionFieldsTest {
    private fun params(body:ByteArray)=Iap2Csm.parseParams(body)
    private fun standard(ssid:String="fixture",password:String="fixture-pass",channel:Int=6,security:Int=2)=
        Iap2Csm.string(0,ssid)+Iap2Csm.string(1,password)+Iap2Csm.u8(2,channel)+Iap2Csm.string(3,"192.168.77.1")+Iap2Csm.u8(4,security)
    @Test fun standardApKeepsIpLikeCredentialsAndLowChannelSeparateFromSecurity() {
        val ap=Iap2Messages.parseWirelessAp(params(standard("192.168.2.8","a:b:c:d:e",1,2)))!!
        assertEquals("192.168.2.8",ap.ssid);assertEquals("a:b:c:d:e",ap.passphrase)
        assertEquals(1,ap.channel);assertEquals(2,ap.securityType)
        val ep=Iap2Messages.parseStartSession(Iap2Csm.param(1,standard("192.168.2.8","192.168.7.9")))
        assertEquals(listOf("192.168.77.1"),ep.ipAddresses)
    }
    @Test fun wifiConfigurationLayoutDoesNotMistakeLowChannelForSecurity() {
        for(channel in 1..4) for(password in listOf("fixture-pass","")) {
            val ap=Iap2Messages.parseWirelessAp(params(Iap2Csm.param(0,byteArrayOf(2,3,4,5,6,7))+
                Iap2Csm.string(1,"fixture")+Iap2Csm.string(2,password)+Iap2Csm.u8(3,2)+Iap2Csm.u8(4,channel)))!!
            assertEquals(channel,ap.channel);assertEquals(2,ap.securityType);assertEquals(password,ap.passphrase)
        }
    }
    @Test fun incompleteBinaryGroupsDoNotBecomePhantomOrOpenNetworks() {
        for(body in listOf(Iap2Csm.u8(2,6)+Iap2Csm.string(3,"192.168.77.1"),
                Iap2Csm.string(0,"fixture"),Iap2Csm.param(0,byteArrayOf(0xff.toByte(),0))+Iap2Csm.string(1,"password")))
            assertNull(Iap2Messages.parseWirelessAp(params(body)))
        assertNotNull(Iap2Messages.parseWirelessAp(params(standard(password="",security=0))))
    }
    @Test fun ssidLimitIsUtf8BytesRatherThanCharacterCount() {
        assertNotNull(Iap2Messages.parseWirelessAp(params(standard(ssid="字".repeat(10)))))
        assertNull(Iap2Messages.parseWirelessAp(params(standard(ssid="字".repeat(11)))))
    }
    @Test fun startSessionRecordsAuthFlagsButDoesNotTreatUuidAsPublicKey() {
        val key="ab".repeat(32)
        for(value in listOf(key,key+"suffix")) {
            val ep=Iap2Messages.parseStartSession(Iap2Csm.string(4,value)+Iap2Csm.string(6,"sdk-fixture")+Iap2Csm.u8(8,1))
            assertEquals(key,ep.publicKeyHex);assertEquals("sdk-fixture",ep.sdkVersion);assertEquals(true,ep.mutualAuth)
        }
        assertNull(Iap2Messages.parseStartSession(Iap2Csm.string(4,"11111111-2222-3333-4444-555555555555")).publicKeyHex)
        assertNull(Iap2Messages.parseStartSession(byteArrayOf()).mutualAuth)
        assertEquals(false,Iap2Messages.parseStartSession(Iap2Csm.u8(8,0)).mutualAuth)
        assertEquals(0,Iap2Messages.parseStartSession(Iap2Csm.u32(2,65536)).airPlayPort)
    }
}
