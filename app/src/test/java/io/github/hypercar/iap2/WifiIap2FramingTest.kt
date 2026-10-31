package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class WifiIap2FramingTest {
    private fun frame(length: Int, seq: Int) = ByteArray(length).apply {
        this[0]=0xff.toByte();this[1]=0x5a;this[2]=(length ushr 8).toByte();this[3]=length.toByte();this[5]=seq.toByte()
    }
    @Test fun ackIdentificationAndCertificateAreThreeOrderedCommands() {
        val ack=frame(9,101);val accepted=frame(16,102);val certificate=frame(16,103)
        val packets=WifiIap2Framing.split(ack+accepted+certificate)
        assertEquals(3,packets.size)
        assertArrayEquals(ack,packets[0]);assertArrayEquals(accepted,packets[1]);assertArrayEquals(certificate,packets[2])
        assertArrayEquals(ack+accepted+certificate,packets.reduce { a,b -> a+b })
    }
    @Test fun detectionAndLargeFramesRemainComplete() {
        val detect=byteArrayOf(0xff.toByte(),0x55,2,0,0xee.toByte(),0x10)
        val packet=frame(628,255)
        val frames=WifiIap2Framing.split(detect+packet)
        assertArrayEquals(detect,frames[0]);assertArrayEquals(packet,frames[1])
        assertTrue(WifiIap2Framing.split(byteArrayOf()).isEmpty())
    }
    @Test fun malformedOutputCannotSendAPartialAuthenticationFrame() {
        for (bytes in listOf(frame(16,1).copyOf(15),frame(8,1),byteArrayOf(1,2,3,4))) {
            try { WifiIap2Framing.split(bytes);fail("must reject invalid output") } catch (_: IllegalArgumentException) {}
        }
    }
}
