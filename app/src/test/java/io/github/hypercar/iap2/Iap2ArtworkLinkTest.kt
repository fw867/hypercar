package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2ArtworkLinkTest {
    private fun packet(control:Int,seq:Int,ack:Int,session:Int=0,body:ByteArray=byteArrayOf()):ByteArray {
        val n=9+if(body.isEmpty())0 else body.size+1
        val h=byteArrayOf(0xff.toByte(),0x5a,(n shr 8).toByte(),n.toByte(),control.toByte(),seq.toByte(),ack.toByte(),session.toByte(),0)
        h[8]=(-h.sumOf { it.toInt() and 255 }).toByte()
        return if(body.isEmpty())h else h+body+(-body.sumOf { it.toInt() and 255 }).toByte()
    }
    private fun normal(fileId:Int?=37):Iap2LinkEngine {
        val engine=Iap2LinkEngine()
        engine.start(0,passive=true)
        val sessions=mutableListOf(Iap2LinkEngine.SessionDescriptor(1,0,2))
        fileId?.let { sessions+=Iap2LinkEngine.SessionDescriptor(it,1,2) }
        val sync=Iap2LinkEngine.SynchronizationPayload(4,128,4000,1000,4,1,sessions).encode()
        engine.feed(packet(0x80,40,0,body=sync),1)
        val answer=engine.takeOutput()
        engine.feed(packet(0x40,40,answer[5].toInt() and 255),2)
        engine.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state)
        return engine
    }
    @Test fun fileDataUsesNegotiatedSessionAndDoesNotBecomeCsm() {
        val engine=normal()
        val data=byteArrayOf(7,4,1)
        assertTrue(engine.sendFileTransfer(data,3))
        val output=engine.takeOutput()
        assertEquals(37,output[7].toInt())
        assertArrayEquals(data,output.copyOfRange(9,output.size-1))
        engine.feed(packet(0x40,41,output[5].toInt() and 255,37,byteArrayOf(7,1)),4)
        val event=engine.pollEvent() as Iap2LinkEngine.Event.FileTransfer
        assertArrayEquals(byteArrayOf(7,1),event.bytes)
        assertNull(engine.pollEvent())
    }
    @Test fun missingOrInvalidFileSessionNeverFabricatesDefaultTwelve() {
        for(id in listOf(null,1,0,256)) {
            val engine=normal(id)
            assertNull(engine.fileTransferSession)
            assertFalse(engine.sendFileTransfer(byteArrayOf(1,4),3))
            assertFalse(engine.isDead)
        }
    }
    @Test fun oversizeArtworkPacketIsRejectedWithoutKillingControl() {
        val engine=normal()
        assertFalse(engine.sendFileTransfer(ByteArray(119),3))
        assertTrue(engine.sendControl(Iap2Csm.encode(0x4e09),4))
        assertEquals(1,engine.takeOutput()[7].toInt())
        assertFalse(engine.isDead)
    }
    @Test fun artworkRetryKeepsItsSessionAndExhaustionDoesNotKillCarplay() {
        val engine=normal()
        assertTrue(engine.sendFileTransfer(byteArrayOf(1,4),3))
        engine.takeOutput()
        engine.advanceTime(4004)
        val retry=engine.takeOutput()
        assertEquals(37,retry[7].toInt())
        engine.advanceTime(8005)
        engine.takeOutput()
        assertFalse(engine.isDead)
        // Dropping an optional retry must not fabricate a peer ACK.
        assertEquals(1,engine.outstandingPackets)
        assertTrue(engine.sendControl(Iap2Csm.encode(0x5001),8006))
        assertEquals(1,engine.takeOutput()[7].toInt())
    }
}
