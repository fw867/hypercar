package io.github.hypercar.iap2

import io.github.hypercar.headunit.HeadUnitProfiles
import org.junit.Assert.*
import org.junit.Test

class Iap2MediaBluetoothTest {
    private val descriptor=byteArrayOf(5,12,9,1,0xa1.toByte(),1,0x15,0,0x25,2,9,0,9,0xb5.toByte(),9,0xb6.toByte(),0x75,8,0x95.toByte(),1,0x81.toByte(),0,0xc0.toByte())
    private fun start(d:ByteArray=descriptor)=Iap2Csm.encode(0x6800,Iap2Csm.u16(0,1)+Iap2Csm.param(4,d))
    private fun report(value:Int)=Iap2Csm.encode(0x6802,Iap2Csm.u16(0,1)+Iap2Csm.param(1,byteArrayOf(value.toByte())))
    private fun identity(serial:Boolean=false)=Iap2Csm.encode(0x1d01,
        Iap2Csm.string(0,"BMW-test")+Iap2Csm.string(1,"MGU-fixture")+
        Iap2Csm.param(6,byteArrayOf(0x57,3,0x68,0,0x68,2,0x68,3)+(if(serial) byteArrayOf(0xaa.toByte(),6) else byteArrayOf()))+
        Iap2Csm.param(7,byteArrayOf(0x57,2))+
        Iap2Csm.group(0x12,Iap2Csm.u16(0,1),Iap2Csm.u8(2,1)))
    private inner class Channel(val subscribeEarly: Boolean = false,
        val serial:Boolean=false,val statusEarly:Boolean=false,val rejectIdentification:Boolean=false,
        val certificateBody:ByteArray=Iap2Csm.param(0,byteArrayOf(1)),
        val signatureBody:ByteArray=Iap2Csm.param(0,byteArrayOf(1))) : Iap2LinkChannel {
        val queue=ArrayDeque<ByteArray>()
        val sent=mutableListOf<Int>()
        val updates=mutableListOf<Iap2Csm.Frame>()
        var time=1_000_000L
        var open=true
        var endWhenEmpty=false
        override val isAlive get()=open
        override fun awaitReady(timeoutMillis:Long)=true
        override fun peerMaxControlPayload()=65525
        override fun close(){open=false}
        override fun recvControl(timeoutMillis:Long):ByteArray? {
            if(queue.isNotEmpty())return queue.removeFirst()
            time+=timeoutMillis*1_000_000
            if(endWhenEmpty)open=false
            return null
        }
        override fun sendControl(bytes:ByteArray,awaitAck:Boolean):Boolean {
            for(frame in Iap2Csm.Parser().offer(bytes)) {
                if(frame.messageId in listOf(0x4155,0x4158,0x4171,0xae01))
                    assertTrue("Status subscriptions must wait for auth",0xaa05 in sent)
                if(frame.messageId==0x5001) {
                    assertTrue("Metadata must wait for authentication",0xaa05 in sent)
                    updates+=frame
                }
                sent+=frame.messageId
                when(frame.messageId){
                    0x1d00->queue+=(if(rejectIdentification) Iap2Csm.encode(0x1d03) else identity(serial))+start()+report(1)+(if(subscribeEarly) Iap2Csm.encode(0x5000) else byteArrayOf())+
                        (if(statusEarly) listOf(0x4154,0x4157,0x4170,0xae00).fold(byteArrayOf()){a,id->a+Iap2Csm.encode(id)} else byteArrayOf()) // All in ONE RFCOMM read, before auth.
                    0xaa00->queue+=if(serial && frame.body.isNotEmpty()) Iap2Csm.encode(0xaa06,Iap2Csm.param(0,byteArrayOf(1)))
                        else Iap2Csm.encode(0xaa01,certificateBody)
                    0xaa02->queue+=Iap2Csm.encode(0xaa03,signatureBody)
                    0x5702->queue+=Iap2Csm.encode(0x5703,Iap2Csm.string(1,"fixture-ap")+Iap2Csm.string(2,"fixture-pass")+Iap2Csm.u8(3,2)+Iap2Csm.u8(4,6))
                }
            }
            return open
        }
    }
    @Test fun identificationRejectionFailsImmediatelyInsteadOfWaitingForTimeout() {
        val channel=Channel(rejectIdentification=true)
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
        try {session.run(10_000);fail("rejected identity") } catch(expected:java.io.IOException) {
            assertTrue(expected.message!!.contains("拒绝"))
        }
        assertEquals(listOf(0x1d00),channel.sent)
        assertEquals(1_000_000L,channel.time)
    }

    @Test fun serialCertificateFlowAndEarlyStatusesSurviveBluetoothBringup() {
        val channel=Channel(serial=true,statusEarly=true)
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
        session.run(10_000)
        assertEquals(2,channel.sent.count{it==0xaa00})
        assertEquals(1,channel.sent.count{it==0x4155})
        assertEquals(1,channel.sent.count{it==0x4158})
        assertEquals(1,channel.sent.count{it==0x4171})
        assertEquals(3,channel.sent.count{it==0xae01})
        assertTrue(channel.sent.indexOf(0x4e09)<channel.sent.indexOf(0x4155))
    }
    @Test fun malformedCertificateOrSignatureNeverProducesAuthenticationSuccess() {
        for(channel in listOf(Channel(certificateBody=byteArrayOf()),Channel(signatureBody=byteArrayOf()),
                Channel(signatureBody=Iap2Csm.param(0,byteArrayOf(1))+byteArrayOf(0)))) {
            val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
            try { session.run(10_000);fail("invalid auth data must fail") } catch(expected:java.io.IOException) { }
            assertFalse(0xaa05 in channel.sent)
        }
    }
    @Test fun lateStartSessionApOverridesPreviousConfigurationFromSameBluetoothSession() {
        val channel=Channel();val endpoints=mutableListOf<CarPlayEndpoint>()
        val session=Iap2SenderSession(channel,{},onEndpoint=endpoints::add,profile=HeadUnitProfiles.WINPLAY,nanoTime={channel.time})
        session.run(10_000)
        assertEquals("fixture-ap",endpoints.last().ssid)
        channel.queue+=Iap2Csm.encode(0x4301,Iap2Csm.group(1,Iap2Csm.string(0,"new-ap"),
            Iap2Csm.string(1,"new-password"),Iap2Csm.u8(2,1),Iap2Csm.u8(4,2)))
        channel.endWhenEmpty=true;session.pumpIncomingForever()
        assertEquals("new-ap",endpoints.last().ssid);assertEquals("new-password",endpoints.last().passphrase)
    }

    @Test fun bluetoothEarlySubscriptionPublishesRealMetadataAfterAuthAndHonoursStop() {
        val channel=Channel(subscribeEarly=true)
        var state=Iap2NowPlaying(title="track",artist="artist",album="album",status=1)
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,
            nanoTime={channel.time},nowPlayingProvider={state})
        session.run(10_000)
        assertEquals(1,channel.updates.size)
        val item=Iap2Csm.parseParams(Iap2Csm.parseParams(channel.updates.single().body).first { it.id==0 }.value)
        assertEquals("track",item.first { it.id==1 }.asStringOrNull())
        assertEquals("album",item.first { it.id==6 }.asStringOrNull())
        assertTrue(item.none { it.id==26 }) // Fake link has no file-transfer capability.
        channel.queue+=Iap2Csm.encode(0x5002)
        state=state.copy(title="next");channel.endWhenEmpty=true
        session.pumpIncomingForever()
        assertEquals(1,channel.updates.size)
    }

    @Test fun authenticatedBluetoothVoiceEdgesUseTheSameCallbackAsWifiCsm() {
        val channel=Channel();val keys=mutableListOf<Int>()
        val session=Iap2SenderSession(channel,{},onEndpoint={},profile=HeadUnitProfiles.WINPLAY,
            nanoTime={channel.time},onMediaKey={keys+=it})
        session.run(10_000);keys.clear()
        val voice=byteArrayOf(5,12,9,1,0xa1.toByte(),1,0x15,0,0x25,1,0x75,1,0x95.toByte(),1,9,0xcf.toByte(),0x81.toByte(),2,0xc0.toByte())
        channel.queue+=start(voice)+report(1)+report(1)+report(0)
        channel.endWhenEmpty=true;session.pumpIncomingForever()
        assertEquals(listOf(0xcf,0x100cf),keys)
    }

    @Test fun bluetoothSameReadRegistrationWaitsForAuthAndLateRestartRemainsLive() {
        val channel=Channel();val keys=mutableListOf<Int>()
        val session=Iap2SenderSession(channel,{},onEndpoint={},
            profile=HeadUnitProfiles.WINPLAY, nanoTime={channel.time}, onMediaKey={
                assertTrue("Media must not dispatch before authentication",0xaa05 in channel.sent)
                keys+=it
            })
        val endpoint=session.run(10_000)
        assertEquals("fixture-ap",endpoint.ssid)
        assertEquals(listOf(0xb5),keys)
        assertFalse("StartHID is not an output-report request",0x6801 in channel.sent)
        channel.queue+=Iap2Csm.encode(0x6803,Iap2Csm.u16(0,1))+report(2)+start()+report(2)
        channel.endWhenEmpty=true
        session.pumpIncomingForever()
        assertEquals(listOf(0xb5,0xb6),keys)
    }
}
