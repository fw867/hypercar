package io.github.hypercar.iap2

import io.github.hypercar.headunit.HeadUnitProfiles
import org.junit.Assert.*
import org.junit.Test

class Iap2MediaBluetoothTest {
    private val descriptor=byteArrayOf(5,12,9,1,0xa1.toByte(),1,0x15,0,0x25,2,9,0,9,0xb5.toByte(),9,0xb6.toByte(),0x75,8,0x95.toByte(),1,0x81.toByte(),0,0xc0.toByte())
    private fun start()=Iap2Csm.encode(0x6800,Iap2Csm.u16(0,1)+Iap2Csm.param(4,descriptor))
    private fun report(value:Int)=Iap2Csm.encode(0x6802,Iap2Csm.u16(0,1)+Iap2Csm.param(1,byteArrayOf(value.toByte())))
    private fun identity()=Iap2Csm.encode(0x1d01,
        Iap2Csm.string(0,"BMW-test")+Iap2Csm.string(1,"MGU-fixture")+
        Iap2Csm.param(6,byteArrayOf(0x57,3,0x68,0,0x68,2,0x68,3))+
        Iap2Csm.param(7,byteArrayOf(0x57,2))+
        Iap2Csm.group(0x12,Iap2Csm.u16(0,1),Iap2Csm.u8(2,1)))
    private inner class Channel : Iap2LinkChannel {
        val queue=ArrayDeque<ByteArray>()
        val sent=mutableListOf<Int>()
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
                sent+=frame.messageId
                when(frame.messageId){
                    0x1d00->queue+=identity()+start()+report(1) // All in ONE RFCOMM read, before auth.
                    0xaa00->queue+=Iap2Csm.encode(0xaa01,Iap2Csm.param(0,byteArrayOf(1)))
                    0xaa02->queue+=Iap2Csm.encode(0xaa03,Iap2Csm.param(0,byteArrayOf(1)))
                    0x5702->queue+=Iap2Csm.encode(0x5703,Iap2Csm.string(1,"fixture-ap")+Iap2Csm.string(2,"fixture-pass")+Iap2Csm.u8(3,2)+Iap2Csm.u8(4,6))
                }
            }
            return open
        }
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
