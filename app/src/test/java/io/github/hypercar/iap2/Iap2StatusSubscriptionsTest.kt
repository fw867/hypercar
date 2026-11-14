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
    @Test fun authParametersRejectEmptyDuplicateAndTruncatedData() {
        val good=Iap2Csm.param(0,byteArrayOf(1,2))
        assertTrue(Iap2Csm.hasNonEmptyParameter(good,0))
        for(bad in listOf(byteArrayOf(),Iap2Csm.param(0,byteArrayOf()),good+good,good+byteArrayOf(0),good.copyOf(good.size-1)))
            assertFalse(Iap2Csm.hasNonEmptyParameter(bad,0))
    }
}
