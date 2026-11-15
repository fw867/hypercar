package io.github.hypercar.net

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class AirplayDnsRecordsTest {
    private fun name(value: String): ByteArray = ByteArrayOutputStream().apply {
        value.split('.').forEach { write(it.length); write(it.toByteArray()) }; write(0)
    }.toByteArray()
    private fun rr(owner: String, type: Int, ttl: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val out = DataOutputStream(this); out.write(name(owner)); out.writeShort(type); out.writeShort(0x8001)
        out.writeInt(ttl); out.writeShort(body.size); out.write(body)
    }.toByteArray()
    private fun packet(answers: List<ByteArray> = emptyList(), extra: List<ByteArray> = emptyList()): ByteArray =
        ByteArrayOutputStream().apply {
            val out = DataOutputStream(this); out.writeShort(0); out.writeShort(0x8400); out.writeShort(0)
            out.writeShort(answers.size); out.writeShort(0); out.writeShort(extra.size)
            (answers + extra).forEach { out.write(it) }
        }.toByteArray()
    private val instance = "BMW._airplay._tcp.local"
    private fun srv(port: Int, ttl: Int = 120) = rr(instance, 33, ttl,
        byteArrayOf(0, 0, 0, 0, (port shr 8).toByte(), port.toByte()) + name("car.local"))
    private fun address(ttl: Int = 120) = rr("car.local", 1, ttl, byteArrayOf(192.toByte(), 168.toByte(), 1, 2))
    private fun AirplayDnsRecords.feed(bytes: ByteArray) = accept(bytes, bytes.size)

    @Test fun aRejectedSpeakerDoesNotHideTheNextSoftwareReceiver() {
        val records = AirplayDnsRecords()
        records.feed(packet(listOf(srv(7000)), listOf(address())))
        val winplay = "WinPlay._airplay._tcp.local"
        records.feed(packet(listOf(rr(winplay,33,120,byteArrayOf(0,0,0,0,0x42,0x68) + name("pc.local"))),
            listOf(rr("pc.local",1,120,byteArrayOf(192.toByte(),168.toByte(),1,206.toByte())))))
        val result = records.first { it.instance.startsWith("winplay.") }!!
        assertEquals("192.168.1.206",result.host)
        assertEquals(17000,result.port)
        assertNull(records.first { it.instance.startsWith("diplay.") })
    }

    @Test fun resolvesSrvTargetAfterSixBytePrefixAndAdditionalAddress() {
        val records = AirplayDnsRecords()
        records.feed(packet(listOf(srv(17000)), listOf(address())))
        val endpoint = records.first()!!
        assertEquals("192.168.1.2", endpoint.host); assertEquals(17000, endpoint.port)
        assertEquals(instance.lowercase(), endpoint.instance)
    }

    @Test fun splitRepliesPortReplacementGoodbyeAndExpiry() {
        var time = 0L; val records = AirplayDnsRecords { time }
        records.feed(packet(extra = listOf(address()))); assertNull(records.first())
        records.feed(packet(listOf(srv(7000, 1)))); assertEquals(7000, records.first()!!.port)
        records.feed(packet(listOf(srv(17000, 1)))); assertEquals(17000, records.first()!!.port)
        records.feed(packet(listOf(srv(17000, 0)))); time = 1000; assertNull(records.first())
        records.feed(packet(listOf(srv(17000, 1)))); time = 2000; assertNull(records.first())
    }

    @Test fun compressedNamesResolveAndMalformedPacketsDoNotPublishPartialState() {
        val records = AirplayDnsRecords()
        // Target name points to owner at byte 12. It need only be a legal name for this parser.
        val sr = rr(instance, 33, 120, byteArrayOf(0, 0, 0, 0, 0x1b, 0x58, 0xc0.toByte(), 12))
        val a = rr(instance, 1, 120, byteArrayOf(10, 0, 0, 2))
        records.feed(packet(listOf(sr), listOf(a))); assertEquals("10.0.0.2", records.first()!!.host)
        val broken = AirplayDnsRecords(); val valid = packet(listOf(srv(17000)), listOf(address()))
        broken.feed(valid.copyOf(valid.size - 1)); assertNull(broken.first())
        broken.feed(ByteArray(5)); assertNull(broken.first())
        // Self-referencing compressed owner is bounded and discarded.
        broken.feed(byteArrayOf(0,0,0x84.toByte(),0,0,0,0,1,0,0,0,0,0xc0.toByte(),12) + ByteArray(12))
        assertNull(broken.first())
    }
    @Test fun everyAddressOfOneInstanceGetsAChanceAndGoodbyeRemovesOnlyItsAddress() {
        var time = 0L; val records = AirplayDnsRecords { time }
        val second = rr("car.local", 1, 120, byteArrayOf(10,0,0,3))
        records.feed(packet(listOf(srv(7000)), listOf(address(), second)))
        assertEquals("192.168.1.2", records.first { it.host == "192.168.1.2" }?.host)
        assertEquals("10.0.0.3", records.first { it.host == "10.0.0.3" }?.host)
        assertEquals(listOf("10.0.0.3", "192.168.1.2"), records.first { it.host == "10.0.0.3" }?.addresses)
        records.feed(packet(listOf(rr("car.local",1,0,byteArrayOf(10,0,0,3)))))
        time = 1000
        assertEquals("192.168.1.2", records.first()?.host)
    }

    @Test fun aaaaOnlyReceiverResolvesWithoutInternetDns() {
        val records = AirplayDnsRecords()
        val ipv6 = byteArrayOf(0x20,1,0xd.toByte(),0xb8.toByte(),0,0,0,0,0,0,0,0,0,0,0,1)
        records.feed(packet(listOf(srv(7000)), listOf(rr("car.local",28,120,ipv6))))
        assertEquals("2001:db8:0:0:0:0:0:1", records.first()?.host)
    }

    @Test fun cacheFlushReplacesPreviousAddressSetButKeepsAllSiblingsInPacket() {
        var time = 0L; val records = AirplayDnsRecords { time }
        records.feed(packet(listOf(srv(7000)), listOf(address())))
        time = 1001
        records.feed(packet(listOf(rr("car.local",1,120,byteArrayOf(10,0,0,2)),
            rr("car.local",1,120,byteArrayOf(10,0,0,3)))))
        time = 2001
        assertNull(records.first { it.host == "192.168.1.2" })
        assertNotNull(records.first { it.host == "10.0.0.2" })
        assertNotNull(records.first { it.host == "10.0.0.3" })
    }

    @Test fun ptrDiscoveryActivelyResolvesServiceTxtAndThenBothHostFamilies() {
        val records = AirplayDnsRecords()
        records.feed(packet(listOf(rr("_airplay._tcp.local",12,120,name(instance)))))
        assertTrue(records.pendingQuestions().contains(AirplayDnsRecords.Question(instance.lowercase(),33)))
        assertTrue(records.pendingQuestions().contains(AirplayDnsRecords.Question(instance.lowercase(),16)))
        records.feed(packet(listOf(srv(7000))))
        assertTrue(records.pendingQuestions().contains(AirplayDnsRecords.Question("car.local",1)))
        assertTrue(records.pendingQuestions().contains(AirplayDnsRecords.Question("car.local",28)))
        records.feed(packet(listOf(rr("_airplay._tcp.local",12,0,name(instance)))))
        assertTrue(records.pendingQuestions().contains(AirplayDnsRecords.Question("car.local", 1)))
    }

    @Test fun linkLocalIpv6UsesReceivingInterfaceScope() {
        val records = AirplayDnsRecords()
        val ipv6 = ByteArray(16).apply { this[0]=0xfe.toByte(); this[1]=0x80.toByte(); this[15]=1 }
        val bytes = packet(listOf(srv(7000)),listOf(rr("car.local",28,120,ipv6)))
        records.accept(bytes, bytes.size, 7)
        assertEquals("fe80:0:0:0:0:0:0:1%7", records.first()?.host)
    }    @Test fun splitCacheFlushRepliesKeepAddressesReceivedWithinOneSecond() {
        var time = 0L; val records = AirplayDnsRecords { time }
        records.feed(packet(listOf(srv(7000)), listOf(address())))
        time = 100
        records.feed(packet(listOf(rr("car.local",1,120,byteArrayOf(10,0,0,3)))))
        assertEquals(listOf("192.168.1.2", "10.0.0.3"), records.first()?.addresses)
    }

    @Test fun goodbyeKeepsRecordForOneSecondAndAnnouncementCanRescueIt() {
        var time = 0L; val records = AirplayDnsRecords { time }
        records.feed(packet(listOf(srv(7000)),listOf(address())))
        records.feed(packet(listOf(address(0))))
        assertNotNull(records.first())
        time = 999; assertNotNull(records.first())
        records.feed(packet(listOf(address())))
        time = 1000; assertNotNull(records.first())
        records.feed(packet(listOf(address(0))))
        time = 2000; assertNull(records.first())
    }
}
