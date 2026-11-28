package io.github.hypercar.iap2
import org.junit.Assert.*
import org.junit.Test
class Iap2PowerEaTest {
 @Test fun subscribedPowerSendsInitialSnapshotAndOnlyChangedRequestedFields() {
  var sample = Iap2Power(false, 0, 70)
  var reads = 0
  val session = Iap2StatusSubscriptions(power = { reads++; sample })
  session.identify("MGU-55-BMW", "BMW Group")
  val request = Iap2Csm.Frame(0xae00, Iap2Csm.param(4, byteArrayOf()) + Iap2Csm.param(6, byteArrayOf()))
  session.handle(request)
  assertTrue(session.drain(0).isEmpty())
  assertEquals(0, reads)
  session.authenticated()
  assertEquals(listOf(4, 6), session.drain(0).flatMap { Iap2Csm.parseParams(it.body) }.map { it.id })
  sample = Iap2Power(false, 1, 71)
  assertTrue(session.drain(999).isEmpty())
  assertEquals(1, reads)
  assertArrayEquals(Iap2Csm.u16(6, 71), session.drain(1000).single().body)
  sample = Iap2Power()
  assertTrue(session.drain(2000).isEmpty())
  sample = Iap2Power(false, 1, 71)
  assertTrue(session.drain(3000).isEmpty())
  session.handle(Iap2Csm.Frame(0xae02, byteArrayOf()))
  sample = sample.copy(level = 72)
  assertTrue(session.drain(4000).isEmpty())
  session.handle(request)
  assertEquals(2, session.drain(4100).size)
  session.restartLink()
  assertTrue(session.drain(5000).isEmpty())
 }
 @Test fun mgu55ReportsEachRequestedPowerFieldSeparatelyAfterAuthentication() {
  var reads = 0
  val s = Iap2StatusSubscriptions(power = { reads++; Iap2Power(true, 2, 73) })
  s.identify("MGU-55-BMW", "BMW Group")
  val request = Iap2Csm.Frame(0xae00, listOf(0,1,4,5,6).fold(byteArrayOf()) { acc, id ->
   acc + Iap2Csm.param(id, byteArrayOf())
  })
  s.handle(request)
  assertTrue(s.drain().isEmpty())
  s.authenticated()
  val replies = s.drain()
  assertEquals(1, reads)
  assertEquals(listOf(0xae01,0xae01,0xae01), replies.map { it.messageId })
  assertArrayEquals(Iap2Csm.u8(4,1), replies[0].body)
  assertArrayEquals(Iap2Csm.u8(5,2), replies[1].body)
  assertArrayEquals(Iap2Csm.u16(6,73), replies[2].body)
  s.reset(); s.authenticated(); s.handle(request)
  assertEquals(1,s.drain().size)
 }
 @Test fun eaRequestUsesNumericSessionId() {
  val p=Iap2Csm.parseParams(Iap2Messages.eaOpenSession(10,258))
  assertArrayEquals(byteArrayOf(1,2),p[1].value)
 }
 @Test fun missingPowerSnapshotDoesNotInventBatteryOrChargeState() {
  val s=Iap2StatusSubscriptions();s.authenticated()
  s.handle(Iap2Csm.Frame(0xae00,Iap2Csm.param(6,byteArrayOf())+Iap2Csm.param(4,byteArrayOf())))
  val replies=s.drain(); assertEquals(1,replies.size); assertTrue(replies.single().body.isEmpty())
 }
 @Test fun eaStatusMustMatchRequestedSessionAndClosedRevokesConfirmation() {
  val s=Iap2EaSessions()
  val request=Iap2Csm.parseParams(s.open(10,"protocol"))
  val id=request[1].value.let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
  fun status(session:Int,state:Int)=Iap2Csm.u16(0,session)+Iap2Csm.u8(1,state)
  assertFalse(s.status(status(id+1,0)));assertFalse(s.allConfirmed)
  assertFalse(s.status(Iap2Csm.u8(0,id)+Iap2Csm.u8(1,0)))
  assertFalse(s.status(status(id,0)+byteArrayOf(0)))
  assertFalse(s.status(status(id,2)))
  assertTrue(s.status(status(id,0)));assertTrue(s.allConfirmed)
  assertTrue(s.status(status(id,1)));assertFalse(s.allConfirmed)
 }
 @Test fun powerReturnsRequestedMeasuredFieldsWithU16LevelOnly() {
  val s=Iap2StatusSubscriptions(power={Iap2Power(true,2,73)});s.authenticated()
  s.handle(Iap2Csm.Frame(0xae00,(0..12).fold(byteArrayOf()){b,id->b+Iap2Csm.param(id,byteArrayOf())}))
  assertArrayEquals(Iap2Csm.u8(4,1)+Iap2Csm.u8(5,2)+Iap2Csm.u16(6,73),s.drain().single().body)
  s.handle(Iap2Csm.Frame(0xae00,Iap2Csm.param(6,byteArrayOf())))
  assertArrayEquals(Iap2Csm.u16(6,73),s.drain().single().body)
  s.handle(Iap2Csm.Frame(0xae00,byteArrayOf(0)))
  assertTrue(s.drain().isEmpty())
 }
 @Test fun distinctProtocolsNeedTheirOwnConfirmationAndRetriesKeepSessionIdentity() {
  val s=Iap2EaSessions()
  fun id(b:ByteArray)=Iap2Csm.parseParams(b)[1].value.let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
  val first=s.open(10,"a");val second=s.open(10,"b")
  assertArrayEquals(first,s.open(10,"a"));assertNotEquals(id(first),id(second))
  s.status(Iap2Csm.u16(0,id(first))+Iap2Csm.u8(1,0));assertFalse(s.allConfirmed)
  s.status(Iap2Csm.u16(0,id(second))+Iap2Csm.u8(1,0));assertTrue(s.allConfirmed)
  assertFalse(s.status(Iap2Csm.u16(0,id(first))+Iap2Csm.u8(1,0)+Iap2Csm.u8(1,1)))
 }
}
