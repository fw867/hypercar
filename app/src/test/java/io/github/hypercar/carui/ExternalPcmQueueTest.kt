package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class ExternalPcmQueueTest {
    @Test fun pcmRetainsLittleEndianBytesAndEndDrainsWithoutAddingSilence() {
        val q = ExternalPcmQueue()
        val bytes = byteArrayOf(0x34,0x12,0xff.toByte(),0x7f)
        assertTrue(q.offer(bytes,16000)); bytes[0]=0
        q.end(); assertFalse(q.offer(byteArrayOf(0,0),16000))
        assertFalse(q.drained())
        assertArrayEquals(byteArrayOf(0x34,0x12,0xff.toByte(),0x7f),q.poll())
        assertTrue(q.drained())
    }
    @Test fun malformedAndBackloggedAudioFailInsteadOfGrowingOrChangingFormat() {
        val q=ExternalPcmQueue()
        assertFalse(q.offer(byteArrayOf(1),16000))
        assertFalse(q.offer(byteArrayOf(0,0),48000))
        assertFalse(q.offer(ByteArray(8194),16000))
        repeat(8){assertTrue(q.offer(byteArrayOf(it.toByte(),0),16000))}
        assertFalse(q.offer(byteArrayOf(9,0),16000))
        q.cancel();assertTrue(q.drained());assertNull(q.poll())
    }
    @Test fun epochsDoNotShareQueuedSpeech() {
        val old=ExternalPcmQueue();old.offer(byteArrayOf(1,0),16000);old.cancel()
        val next=ExternalPcmQueue();next.offer(byteArrayOf(2,0),16000)
        assertNull(old.poll());assertArrayEquals(byteArrayOf(2,0),next.poll())
    }
}
