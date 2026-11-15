package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class NavigationPcmGateTest {
    private class Sink : NavigationPcmGate.Sink {
        val events = mutableListOf<String>()
        val packets = mutableListOf<ShortArray>()
        override fun start() { events += "start" }
        override fun pcm(samples: ShortArray) { events += "pcm"; packets += samples.copyOf() }
        override fun stop() { events += "stop" }
    }
    @Test fun zerosDoNotOpenAnUtteranceAndFirstSoundStartsBeforePcm() {
        val sink=Sink();val gate=NavigationPcmGate(sink)
        repeat(20) {gate.accept(shortArrayOf(0,0),it*20L)}
        assertTrue(sink.events.isEmpty())
        gate.accept(shortArrayOf(1,-1),400)
        assertEquals("start",sink.events.first())
        assertArrayEquals(shortArrayOf(1,-1),sink.packets.last())
        assertEquals(1,sink.packets.count {it.any {sample->sample.toInt()!=0}})
        assertTrue("pre-roll must retain only the most recent 50ms",sink.packets.size<=4)
    }
    @Test fun silenceTailStopsExactlyOnceAndQuietBuffersStayClosed() {
        val sink=Sink();val gate=NavigationPcmGate(sink)
        gate.accept(shortArrayOf(7),0);gate.accept(shortArrayOf(0),100);gate.accept(shortArrayOf(0),399)
        gate.accept(shortArrayOf(0),400);gate.accept(shortArrayOf(0),800)
        assertEquals(listOf("start","pcm","pcm","pcm","stop"),sink.events)
        gate.close();gate.close();assertEquals(1,sink.events.count {it=="stop"})
    }
    @Test fun quickPhrasesShareOneUtteranceAndExpiredPhraseRestarts() {
        val sink=Sink();val gate=NavigationPcmGate(sink)
        gate.accept(shortArrayOf(1),0);gate.accept(shortArrayOf(0),200);gate.accept(shortArrayOf(2),300)
        gate.accept(shortArrayOf(0),699);gate.accept(shortArrayOf(0),700)
        gate.accept(shortArrayOf(3),720)
        assertEquals(2,sink.events.count {it=="start"});assertEquals(1,sink.events.count {it=="stop"})
        assertEquals(listOf<Short>(1,2,3),sink.packets.flatMap {it.toList()}.filter {it.toInt()!=0})
    }
    @Test fun closeIsTerminalAndResetStartsFreshForAReusedCapture() {
        val sink=Sink();val gate=NavigationPcmGate(sink)
        gate.accept(shortArrayOf(1),10);gate.close();gate.accept(shortArrayOf(2),20);gate.close()
        assertEquals(listOf("start","pcm","stop"),sink.events)
        gate.reset();gate.accept(shortArrayOf(3),30);gate.reset();gate.accept(shortArrayOf(4),40)
        assertEquals(3,sink.events.count {it=="start"});assertEquals(2,sink.events.count {it=="stop"})
        assertEquals(listOf<Short>(1,3,4),sink.packets.flatMap {it.toList()})
    }
    @Test fun backwardClockAndLargeGapsCannotHoldAnOldUtteranceOpen() {
        val sink=Sink();val gate=NavigationPcmGate(sink)
        gate.accept(shortArrayOf(1),1000);gate.accept(shortArrayOf(0),900)
        gate.accept(shortArrayOf(2),901);gate.accept(shortArrayOf(3),1401)
        assertEquals(3,sink.events.count {it=="start"});assertEquals(2,sink.events.count {it=="stop"})
        assertEquals(listOf<Short>(1,2,3),sink.packets.flatMap {it.toList()}.filter {it.toInt()!=0})
    }
    @Test fun prerollIsBoundedAndCopiesReusedCaptureBuffers() {
        val sink=Sink();val gate=NavigationPcmGate(sink)
        val zeros=ShortArray(20000);gate.accept(zeros,0);zeros.fill(123)
        gate.accept(shortArrayOf(9),1)
        assertTrue(sink.packets.sumOf {it.size}<=11521)
        assertEquals(1,sink.packets.flatMap {it.toList()}.count {it.toInt()!=0})
        gate.accept(shortArrayOf(),1000);gate.close();assertEquals(1,sink.events.count {it=="stop"})
    }
}
