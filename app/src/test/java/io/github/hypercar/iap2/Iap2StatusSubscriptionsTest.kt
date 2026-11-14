package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2StatusSubscriptionsTest {
    private fun frame(id:Int)=Iap2Csm.Frame(id,byteArrayOf())
    @Test fun earlyRequestsWaitForAuthAndStopCancelsEveryPendingFamily() {
        for((start,stop) in listOf(0x4154 to 0x4156,0x4157 to 0x4159,0x4170 to 0x4172,0xae00 to 0xae02,0xad00 to 0xad02)) {
            val s=Iap2StatusSubscriptions();assertTrue(s.handle(frame(start)))
            assertTrue(s.drain().isEmpty())
            assertTrue(s.handle(frame(stop)));s.authenticated()
            assertTrue(s.drain().isEmpty())
        }
    }
    @Test fun repeatedRequestsCoalesceAndEveryStatusBodyIsWholeTlv() {
        val s=Iap2StatusSubscriptions()
        repeat(100) { for(id in listOf(0x4154,0x4157,0x4170,0xae00,0xad00)) s.handle(frame(id)) }
        s.authenticated()
        val replies=s.drain()
        assertEquals(listOf(0x4155,0x4158,0x4171,0xae01,0xae01,0xae01,0xad01),replies.map{it.messageId})
        replies.forEach { assertEquals(it.body.size,Iap2Csm.parseParams(it.body).sumOf{p->p.value.size+4}) }
        assertTrue(s.drain().isEmpty())
        val communications=Iap2Csm.parseParams(replies.first{it.messageId==0x4158}.body)
        assertTrue(communications.all{it.value.contentEquals(byteArrayOf(0))})
    }
    @Test fun resyncDropsPendingRepliesAndWifiResetRequiresAuthenticationAgain() {
        val s=Iap2StatusSubscriptions();s.authenticated();s.handle(frame(0x4154));s.restartLink()
        assertTrue(s.drain().isEmpty());assertTrue(s.isAuthenticated)
        s.reset();s.handle(frame(0x4154));assertTrue(s.drain().isEmpty())
        s.authenticated();assertEquals(0x4155,s.drain().single().messageId)
        assertFalse(s.handle(frame(0xea02))) // No speculative application launch.
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun communicationsReplyContainsOnlyRequestedKnownFlagsInBoxOrder() {
        val s = Iap2StatusSubscriptions(); s.authenticated()
        // Presence tests follow the firmware, including duplicate and out-of-order fields.
        val request = hex("000400110004000c000400080004000500040005000400040004ffff")
        s.handle(Iap2Csm.Frame(0x4157, request))
        val reply = s.drain().single()
        assertEquals(0x4158, reply.messageId)
        assertArrayEquals(hex("000500050000050008000005000c000005001100"), reply.body)
        assertEquals(20, reply.body.size) // Four u8 TLVs, not the old unconditional 50 bytes.
    }

    @Test fun communicationsEmptyOrUnavailableFieldsDoNotInventTelephonyState() {
        val s = Iap2StatusSubscriptions(); s.authenticated()
        for (request in listOf("", "000400000004000100040002000400040004ffff")) {
            s.handle(Iap2Csm.Frame(0x4157, hex(request)))
            val reply = s.drain().single()
            assertEquals(0x4158, reply.messageId)
            assertTrue(reply.body.isEmpty())
        }
        val supported = listOf(5,6,7,8,12,13,14,15,16,17)
        s.handle(Iap2Csm.Frame(0x4157, supported.fold(byteArrayOf()) { b, id -> b + Iap2Csm.param(id, byteArrayOf()) }))
        val fields = Iap2Csm.parseParams(s.drain().single().body)
        assertEquals(supported, fields.map { it.id })
        fields.forEach { assertArrayEquals(byteArrayOf(0), it.value) }
    }

    @Test fun communicationsEarlyRequestsCoalesceAndStopCancelsUntilAuthenticated() {
        val s = Iap2StatusSubscriptions()
        s.handle(Iap2Csm.Frame(0x4157, hex("00040005")))
        s.handle(Iap2Csm.Frame(0x4157, hex("00040011")))
        assertTrue(s.drain().isEmpty())
        s.authenticated()
        assertArrayEquals(hex("0005001100"), s.drain().single().body)
        s.handle(Iap2Csm.Frame(0x4157, hex("00040005")))
        s.handle(frame(0x4159))
        assertTrue(s.drain().isEmpty())
    }

    @Test fun malformedCommunicationsRequestsAreRejectedAndLogsNeverEchoValues() {
        val logs = mutableListOf<String>()
        val s = Iap2StatusSubscriptions(log = logs::add); s.authenticated()
        for (request in listOf("00", "00030005", "00050005", "0004000500")) {
            s.handle(Iap2Csm.Frame(0x4157, hex(request)))
            assertTrue(s.drain().isEmpty())
        }
        s.handle(Iap2Csm.Frame(0x4157, Iap2Csm.string(4,"private-operator") + hex("00040005")))
        assertArrayEquals(hex("0005000500"), s.drain().single().body)
        assertTrue(logs.any { "0x4158 fields=[5] body_bytes=5" in it })
        assertFalse(logs.any { "private-operator" in it })
    }

    @Test fun listReplyMatchesBoxPresenceMappingAndByteWidths() {
        // Firmware 0x61824..0x61868: getPara(1)->addPara<u8>(0,0), getPara(6)->(5,0).
        val requests = listOf(
            "00040001" to "0005000000",
            "00040006" to "0005000500",
            "0004000600040001" to "00050000000005000500",
            "0005000000" to "", // selector/other fields do not request the two flags
            "" to "",
            "0004000100040001" to "0005000000", // presence, not duplicate replies
        )
        for ((request, expected) in requests) {
            val s = Iap2StatusSubscriptions(); s.authenticated()
            assertTrue(s.handle(Iap2Csm.Frame(0x4170, hex(request))))
            val reply = s.drain().single()
            assertEquals(0x4171, reply.messageId)
            assertArrayEquals("request=$request", hex(expected), reply.body)
            assertTrue(Iap2Csm.parseParams(reply.body).none { it.id == 2 || it.id == 7 })
        }
    }

    @Test fun listSubscriptionKeepsLatestRequestAndStopCancelsDeferredReply() {
        val s = Iap2StatusSubscriptions()
        s.handle(Iap2Csm.Frame(0x4170, hex("00040001")))
        s.handle(Iap2Csm.Frame(0x4170, hex("00040006")))
        assertTrue(s.drain().isEmpty())
        s.authenticated()
        assertArrayEquals(hex("0005000500"), s.drain().single().body)
        s.handle(Iap2Csm.Frame(0x4170, hex("00040001")))
        s.handle(frame(0x4172))
        assertTrue(s.drain().isEmpty())
    }

    @Test fun malformedListRequestCannotGenerateInventedReplyAndDiagnosticsOmitValues() {
        val logs = mutableListOf<String>()
        val s = Iap2StatusSubscriptions(log = logs::add); s.authenticated()
        for (request in listOf("00", "00030001", "00050001", "0004000100")) {
            assertTrue(s.handle(Iap2Csm.Frame(0x4170, hex(request))))
            assertTrue(s.drain().isEmpty())
        }
        s.handle(Iap2Csm.Frame(0x4170, Iap2Csm.string(1,"private-contact")))
        assertArrayEquals(hex("0005000000"), s.drain().single().body)
        assertTrue(logs.any { "0x4171 fields=[0] body_bytes=5" in it })
        assertFalse(logs.any { "private-contact" in it })
    }

    @Test fun authParametersRejectEmptyDuplicateAndTruncatedData() {
        val good=Iap2Csm.param(0,byteArrayOf(1,2))
        assertTrue(Iap2Csm.hasNonEmptyParameter(good,0))
        for(bad in listOf(byteArrayOf(),Iap2Csm.param(0,byteArrayOf()),good+good,good+byteArrayOf(0),good.copyOf(good.size-1)))
            assertFalse(Iap2Csm.hasNonEmptyParameter(bad,0))
    }
}
