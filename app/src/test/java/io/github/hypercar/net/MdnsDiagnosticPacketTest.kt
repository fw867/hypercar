package io.github.hypercar.net

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class MdnsDiagnosticPacketTest {
    private fun name(s: String) = ByteArrayOutputStream().apply {
        s.split('.').forEach { val b = it.toByteArray(); write(b.size); write(b) }; write(0)
    }.toByteArray()
    private fun packet(
        flags: Int = 0x8400,
        questions: Int = 0,
        answers: Int = 0,
        authority: Int = 0,
        additional: Int = 0,
        body: ByteArray,
    ) = ByteArrayOutputStream().apply {
        val out = DataOutputStream(this)
        listOf(0, flags, questions, answers, authority, additional).forEach(out::writeShort); write(body)
    }.toByteArray()
    private fun rr(owner: ByteArray, type: Int, data: ByteArray, ttl: Int = 120) =
        ByteArrayOutputStream().apply {
            val out = DataOutputStream(this); write(owner); out.writeShort(type); out.writeShort(0x8001)
            out.writeInt(ttl); out.writeShort(data.size); write(data)
        }.toByteArray()

    @Test fun recordsQueryTypeAndUnicastPreferenceWithoutPretendingItIsAReply() {
        val p = packet(flags = 0, questions = 1,
            body = name("_carplay-ctrl._tcp.local") + byteArrayOf(0, 12, 0x80.toByte(), 1))
        val decoded = MdnsDiagnosticPacket.decode(p)!!
        assertFalse(decoded.response)
        assertTrue(decoded.details.single().startsWith("Q section=question "))
        assertTrue(decoded.details.any { it.contains("_carplay-ctrl._tcp.local") && it.contains("QU=true") })
    }

    @Test fun distinguishesKnownAnswersProbeAuthorityAndAdditionalInOneQuery() {
        val service = name("_carplay-ctrl._tcp.local")
        val owner = name("phone._carplay-ctrl._tcp.local")
        val question = service + byteArrayOf(0, 12, 0, 1)
        val ptr = rr(service, 12, owner)
        val srv = rr(owner, 33, byteArrayOf(0, 0, 0, 0, 0x83.toByte(), 0xd5.toByte()) + name("phone.local"))
        val addr = rr(name("phone.local"), 1, byteArrayOf(10, 0, 0, 1))
        val p = packet(flags = 0, questions = 1, answers = 1, authority = 1, additional = 1,
            body = question + ptr + srv + addr)
        val decoded = MdnsDiagnosticPacket.decode(p)!!
        assertFalse(decoded.response)
        assertEquals(listOf("question", "known-answer", "probe-authority", "additional"), sections(decoded))
        assertTrue(decoded.details[1].contains("target=phone._carplay-ctrl._tcp.local"))
        assertTrue(decoded.details[2].contains("port=33749"))
        assertTrue(decoded.details[3].contains("address=10.0.0.1"))
    }

    @Test fun responseSectionsStayDistinctEvenWhenFilteredRecordsPrecedeThem() {
        val unrelated = rr(name("printer._ipp._tcp.local"), 16, byteArrayOf(0))
        val service = name("_carplay-ctrl._tcp.local")
        val owner = name("phone._carplay-ctrl._tcp.local")
        val ptr = rr(service, 12, owner)
        val addr = rr(name("phone.local"), 1, byteArrayOf(10, 0, 0, 1))
        val p = packet(answers = 2, authority = 1, additional = 1, body = unrelated + ptr + ptr + addr)
        val decoded = MdnsDiagnosticPacket.decode(p)!!
        assertTrue(decoded.response)
        assertEquals(listOf("response-answer", "response-authority", "additional"), sections(decoded))
        assertNull(MdnsDiagnosticPacket.decode(p.copyOf(p.size - 1)))
    }

    private fun sections(decoded: MdnsDiagnosticPacket): List<String> =
        decoded.details.map { it.substringAfter("section=").substringBefore(' ') }

    @Test fun compressedSrvIncludesPortHostFlushAndGoodbye() {
        val owner = name("phone._carplay-ctrl._tcp.local")
        val srv = rr(owner, 33, byteArrayOf(0, 0, 0, 0, 0x83.toByte(), 0xd5.toByte()) + name("phone.local"), ttl = 0)
        val p = packet(answers = 2, body = srv + rr(byteArrayOf(0xc0.toByte(), 12), 16, byteArrayOf(0)))
        val decoded = MdnsDiagnosticPacket.decode(p)!!
        assertTrue(decoded.response)
        assertTrue(decoded.details.any { it.contains("33749") && it.contains("phone.local") && it.contains("ttl=0") && it.contains("flush=true") })
    }

    @Test fun txtOnlyAllowsIdentityAndVersionNeverCredentialsOrUnknownValues() {
        val txt = listOf("id=84:AE:DE:56:D3:76", "srcvers=770.8.1", "psk=SECRET", "token=PRIVATE").flatMap {
            listOf(it.length.toByte()) + it.toByteArray().toList()
        }.toByteArray()
        val p = packet(answers = 1, body = rr(name("phone._carplay-ctrl._tcp.local"), 16, txt))
        val result = MdnsDiagnosticPacket.decode(p)!!.details.joinToString()
        assertTrue(result.contains("84:AE:DE:56:D3:76")); assertTrue(result.contains("770.8.1"))
        assertFalse(result.contains("SECRET")); assertFalse(result.contains("PRIVATE")); assertFalse(result.contains("token"))
    }

    @Test fun malformedSuffixDiscardsEarlierValidRecordsAndCompressionCyclesTerminate() {
        val good = rr(name("_carplay-ctrl._tcp.local"), 12, name("phone._carplay-ctrl._tcp.local"))
        assertNull(MdnsDiagnosticPacket.decode(packet(answers = 2, body = good + byteArrayOf(0xc0.toByte(), 12))))
        assertNull(MdnsDiagnosticPacket.decode(packet(questions = 1, body = byteArrayOf(0xc0.toByte(), 12, 0, 12, 0, 1))))
        val valid = packet(answers = 1, body = good)
        for (n in 0 until valid.size) assertNull("truncation at $n", MdnsDiagnosticPacket.decode(valid.copyOf(n)))
    }

    @Test fun retainsIpv4AndIpv6AndSkipsUnrelatedTxt() {
        val p = packet(answers = 3, body = rr(name("phone.local"), 1, byteArrayOf(172.toByte(),16,222.toByte(),98)) +
            rr(name("phone.local"), 28, byteArrayOf(0xfe.toByte(),0x80.toByte()) + ByteArray(13) + byteArrayOf(1)) +
            rr(name("printer._ipp._tcp.local"), 16, byteArrayOf(7) + "psk=BAD".toByteArray()))
        val text = MdnsDiagnosticPacket.decode(p)!!.details.joinToString()
        assertTrue(text.contains("172.16.222.98")); assertTrue(text.contains("fe80:")); assertFalse(text.contains("BAD"))
    }
}
