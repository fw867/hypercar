package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2MediaSessionTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private val array = bytes(0x05,0x0c,0x09,1,0xa1,1,0x15,0,0x25,3,0x09,0,0x09,0xb5,0x09,0xb6,0x09,0xcd,0x75,8,0x95,1,0x81,0,0xc0)
    private val bitmap = bytes(0x05,0x0c,0x09,1,0xa1,1,0x15,0,0x25,1,0x75,1,0x95,4,0x09,0xb0,0x09,0xb1,0x09,0xb5,0x09,0xb6,0x81,2,0x75,4,0x95,1,0x81,3,0xc0)
    private fun frame(id: Int, component: Int = 1, vararg body: ByteArray) =
        Iap2Csm.Frame(id, Iap2Csm.u16(0, component) + body.fold(byteArrayOf()) { a,b -> a+b })
    private fun start(component: Int = 1, descriptor: ByteArray = array) = frame(0x6800, component,
        Iap2Csm.u16(1, 0x1234), Iap2Csm.u16(2, 7), Iap2Csm.param(4, descriptor))
    private fun report(value: Int, component: Int = 1) = frame(0x6802, component, Iap2Csm.param(1, bytes(value)))
    private fun identified(keys: MutableList<Int>, ready: Boolean = true): Iap2MediaSession =
        Iap2MediaSession({}, keys::add).also { it.identify(listOf(Iap2Messages.HidComponent(1,1), Iap2Messages.HidComponent(2,1))); if (ready) it.authenticated() }

    @Test fun actualBmwComponentIsParsedSeparatelyFromAirplayUuid() {
        val body=Iap2Csm.group(0x12,Iap2Csm.u16(0,1),Iap2Csm.string(1,"CinemoHIDComponent"),Iap2Csm.u8(2,1))
        assertEquals(listOf(Iap2Messages.HidComponent(1,1)),Iap2Messages.parseIdentification(body).hidComponents)
        assertTrue(Iap2Messages.parseIdentification(body+body).hidComponents.isEmpty()) // Duplicate identities are ambiguous.
        assertTrue(Iap2Messages.parseIdentification(Iap2Csm.group(0x12,Iap2Csm.u8(0,1),Iap2Csm.u8(2,1))).hidComponents.isEmpty())
    }
    @Test fun parameterOneIsReportAndComponentOneIsNeverTreatedAsTheKey() {
        val keys=mutableListOf<Int>(); val s=identified(keys)
        assertTrue(s.handle(start()))
        s.handle(report(1));s.handle(report(1));s.handle(report(0));s.handle(report(2))
        assertEquals(listOf(0xb5,0xb6),keys)
    }
    @Test fun registrationAndKeysAreDeferredUntilAuthenticationFinishes() {
        val keys=mutableListOf<Int>(); val s=identified(keys,false)
        s.handle(start());s.handle(report(1));assertTrue(keys.isEmpty());assertEquals(0,s.registeredCount)
        s.authenticated();assertEquals(listOf(0xb5),keys);assertEquals(1,s.registeredCount)
    }
    @Test fun preIdentificationHidCannotEscapeOrInventMediaCapability() {
        val keys=mutableListOf<Int>();val s=Iap2MediaSession({},keys::add)
        s.handle(start());s.handle(report(1));s.identify(emptyList());s.authenticated()
        assertTrue(keys.isEmpty());assertEquals(0,s.registeredCount)
    }
    @Test fun keyboardOrUndeclaredComponentIsNotReinterpretedAsMedia() {
        val keys=mutableListOf<Int>();val s=Iap2MediaSession({},keys::add)
        s.identify(listOf(Iap2Messages.HidComponent(1,0)));s.authenticated()
        assertFalse(s.handle(start()));assertFalse(s.handle(report(1)));assertTrue(keys.isEmpty())
    }
    @Test fun componentsHaveIndependentPressedStateAndStopRequiresNewRegistration() {
        val keys=mutableListOf<Int>();val s=identified(keys)
        s.handle(start(1));s.handle(start(2));s.handle(report(1,1));s.handle(report(1,2))
        s.handle(frame(0x6803,1));s.handle(report(0,1));s.handle(report(1,1));s.handle(report(1,2))
        assertEquals(listOf(0xb5,0xb5),keys)
        s.handle(start(1));s.handle(report(1,1));assertEquals(3,keys.size)
    }
    @Test fun duplicateRegistrationRetainsEdgesButChangedDescriptorResetsThem() {
        val keys=mutableListOf<Int>();val s=identified(keys)
        s.handle(start());s.handle(report(1));s.handle(start());s.handle(report(1))
        assertEquals(listOf(0xb5),keys)
        s.handle(start(descriptor=bitmap));s.handle(report(4));assertEquals(listOf(0xb5,0xb5),keys)
    }
    @Test fun resyncAndReidentificationClearOldRegistrations() {
        val keys=mutableListOf<Int>();val s=identified(keys)
        s.handle(start());s.handle(report(1));s.restartLink();s.handle(report(1));assertEquals(1,keys.size)
        s.handle(start());s.handle(report(1));assertEquals(2,keys.size)
        s.identify(emptyList());assertFalse(s.handle(report(2)));assertEquals(2,keys.size)
        s.reset();s.handle(start());s.handle(report(1));s.authenticated();assertEquals(2,keys.size)
    }
    @Test fun identicalLateIdentityDoesNotDiscardRegistrationOrDuplicateHeldKey() {
        val keys=mutableListOf<Int>();val s=identified(keys)
        s.handle(start());s.handle(report(1))
        s.identify(listOf(Iap2Messages.HidComponent(1,1),Iap2Messages.HidComponent(2,1)))
        s.handle(report(1));s.handle(report(0));s.handle(report(2))
        assertEquals(listOf(0xb5,0xb6),keys)
    }

    @Test fun malformedStartReportAndDescriptorNeverGuessNextTrack() {
        val keys=mutableListOf<Int>();val s=identified(keys)
        s.handle(start());s.handle(frame(0x6802,1,Iap2Csm.param(9,bytes(1))))
        s.handle(frame(0x6802,1,Iap2Csm.param(1,bytes(1)),Iap2Csm.param(1,bytes(2))))
        s.handle(report(4));assertTrue(keys.isEmpty())
        s.handle(start(descriptor=bytes(0xff)));assertEquals(0,s.registeredCount)
        s.handle(Iap2Csm.Frame(0x6802,Iap2Csm.u16(0,1)+bytes(0,1,0,1)))
        assertTrue(keys.isEmpty())
    }
    @Test fun reportIdsPaddingAndLogicalMinimumAreNotKeyValues() {
        val d=bytes(0x05,0x0c,0x09,1,0xa1,1,0x85,7,0x75,8,0x95,1,0x81,3,0x15,1,0x25,2,
            0x19,0xb5,0x29,0xb6,0x75,8,0x95,1,0x81,0,0xc0)
        val p=ConsumerReport.parse(d)!!
        assertTrue(p.report(bytes(6,1,1)).isEmpty());assertTrue(p.report(bytes(7,1)).isEmpty())
        assertEquals(listOf(0xb5),p.report(bytes(7,255,1)))
        assertTrue(p.report(bytes(7,0,1)).isEmpty())
        assertTrue(p.report(bytes(7,0,0)).isEmpty())
        assertEquals(listOf(0xb6),p.report(bytes(7,0,2)))
    }
    @Test fun allDescriptorPrefixesAndMalformedSizesAreBounded() {
        for (size in array.indices) ConsumerReport.parse(array.copyOf(size))
        assertNull(ConsumerReport.parse(bytes(0x75,255,0x95,255,0x81,2)))
        assertNull(ConsumerReport.parse(ByteArray(4097)))
        assertNull(ConsumerReport.parse(bytes(0xfe,255)))
    }
    @Test fun authenticationQueueIsBoundedAndDoesNotDispatchUntilRegistered() {
        val keys=mutableListOf<Int>();val logs=mutableListOf<String>();val s=Iap2MediaSession(logs::add,keys::add)
        s.identify(listOf(Iap2Messages.HidComponent(1,1)))
        repeat(1000){s.handle(report(1))};s.handle(start());s.authenticated()
        assertTrue(keys.isEmpty());assertEquals(0,s.registeredCount);assertTrue(logs.size<=96)
        s.handle(start());s.handle(report(1));assertEquals(listOf(0xb5),keys)
    }
    @Test fun globalPushPopAndUnsupportedAcBackNeverBecomeTrackKeys() {
        val d=bytes(0x05,0x0c,0x09,1,0xa1,1,0x75,1,0x95,1,0xa4,0x05,1,0x09,1,0x81,3,0xb4,
            0x0a,0x24,2,0x81,2,0x09,0xb5,0x81,2,0xc0)
        val p=ConsumerReport.parse(d)!!
        assertTrue(p.report(bytes(2)).isEmpty()) // AC Back only.
        assertEquals(listOf(0xb5),p.report(bytes(6)))
    }
}
