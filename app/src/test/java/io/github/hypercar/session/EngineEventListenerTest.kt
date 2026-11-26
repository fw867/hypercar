package io.github.hypercar.session

import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.iap2.Iap2Csm
import io.github.hypercar.iap2.Iap2NowPlaying
import io.github.hypercar.iap2.Iap2NowPlayingSession
import io.github.hypercar.iap2.asStringOrNull
import io.github.hypercar.net.PendingInvitation
import org.junit.Assert.*
import org.junit.Test

class EngineEventListenerTest {
    @Test fun retiredEngineCanReportItsCallAudioShutdownWithoutRearmingAudio() {
        val gate = EngineCallbackGate()
        val received = mutableListOf<Pair<String, String>>()
        val target = object : RustCoreBridge.EventListener {
            override fun onState(state: String, detail: String) { received += state to detail }
            override fun onLog(line: String) {}
            override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {}
            override fun onNightMode(night: Boolean) {}
        }
        val old = EngineEventListener(gate, gate.begin(), target)
        gate.retire()
        old.onState("call_audio_armed", "7 16000")
        old.onState("call_audio_stopping", "7 offer=3")
        assertEquals(listOf("call_audio_stopping" to "7 offer=3"), received)
    }

    @Test fun lateScreenSnapshotsCannotRestoreBufferedBadgeAfterFallbackOrSamePeerReinvite() {
        val gate = EngineCallbackGate()
        var shown = io.github.hypercar.core.PipelineDiagnostics()
        var deliveries = 0
        val target = object : RustCoreBridge.EventListener {
            override fun onState(state: String, detail: String) {}
            override fun onLog(line: String) {}
            override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {}
            override fun onNightMode(night: Boolean) {}
            override fun onPipeline(json: String) {
                deliveries++
                val next = io.github.hypercar.core.PipelineDiagnostics.parse(json)!!
                if (next.canReplace(shown)) shown = next
            }
        }
        fun snapshot(generation: Int, sequence: Int, type: Int?) =
            """{"generation":$generation,"snapshot_sequence":$sequence,"audio_ready":true,"main_audio_stream_type":$type}"""
        val old = EngineEventListener(gate, gate.begin(), target)
        old.onPipeline(snapshot(1, 4, 103))
        assertTrue(shown.audioChannelLabel.contains("mainBuffered"))
        old.onPipeline(snapshot(1, 6, 102))
        old.onPipeline(snapshot(1, 5, 103))
        assertEquals(102L, shown.mainAudioStreamType)
        gate.retire()
        val current = EngineEventListener(gate, gate.begin(), target)
        current.onPipeline(snapshot(2, 1, null))
        old.onPipeline(snapshot(1, 7, 103))
        assertEquals(4, deliveries)
        assertNull(shown.mainAudioStreamType)
        current.onPipeline(snapshot(2, 2, 103))
        assertTrue(shown.audioChannelLabel.contains("mainBuffered"))
    }

    @Test fun oldSamePeerCallbacksCannotConsumeOrResetNewFirstMediaDefaults() {
        val gate = EngineCallbackGate()
        var linkId = 0L
        var linkProfile = ""
        var state = Iap2NowPlaying(title="real")
        var session: Iap2NowPlayingSession? = null
        val target = object : RustCoreBridge.EventListener {
            override fun onState(state: String, detail: String) {}
            override fun onLog(line: String) {}
            override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {}
            override fun onNightMode(night: Boolean) {}
            override fun onIap2Start(id: Long, deviceId: String, deviceName: String, profileId: String) {
                linkId = id
                linkProfile = profileId
                session = Iap2NowPlayingSession({ state }).also { it.authenticated() }
            }
            // Model the bridge's ID gates. The protocol tests separately exercise actual link framing.
            override fun onIap2Bytes(id: Long, bytes: ByteArray) {
                if (id == linkId) Iap2Csm.Parser().offer(bytes).forEach { session?.handle(it) }
            }
            override fun onIap2Stop(id: Long) { if (id == linkId) session = null }
        }
        val start = Iap2Csm.encode(0x5000,Iap2Csm.group(0,
            Iap2Csm.param(0,byteArrayOf()),Iap2Csm.param(1,byteArrayOf())))
        fun updates(now: Long) = session!!.poll(now,2048,false)
            .filterIsInstance<Iap2NowPlayingSession.Output.Control>()
        fun fields(now: Long) = Iap2Csm.parseParams(Iap2Csm.parseParams(updates(now).single().body).single().value)
            .associateBy { it.id }
        val old = EngineEventListener(gate,gate.begin(),target)
        old.onIap2Start(11,"same-peer","phone", "audi_mib3"); old.onIap2Bytes(11,start)
        assertTrue(fields(0).containsKey(0))
        val current = EngineEventListener(gate,gate.begin(),target)
        current.onIap2Start(12,"same-peer","phone", "bmw_id85")
        old.onIap2Bytes(11,start); old.onIap2Bytes(12,start)
        current.onIap2Bytes(11,start)
        old.onIap2Stop(11)
        assertTrue(updates(1).isEmpty()) // Old subscriptions cannot consume new initialization.
        current.onIap2Bytes(12,start)
        assertTrue(fields(2).containsKey(0))
        old.onIap2Start(11,"same-peer","phone", "audi_mib3")
        assertEquals("bmw_id85", linkProfile)
        old.onIap2Bytes(12,Iap2Csm.encode(0x5002)); old.onIap2Stop(11)
        state = state.copy(title="latest")
        val changed = fields(102)
        assertFalse(changed.containsKey(0)) // Old start/stop cannot reset initialization or stop updates.
        assertEquals("latest",changed.getValue(1).asStringOrNull())
        current.onIap2Bytes(12,start)
        assertFalse(fields(103).containsKey(0))
    }

    @Test fun oldArtworkCallbacksCannotCompleteNewLinkEvenWhenTransferIdIsReused() {
        val gate=EngineCallbackGate()
        var linkId=0L
        var session: io.github.hypercar.iap2.Iap2NowPlayingSession? = null
        val target=object : RustCoreBridge.EventListener {
            override fun onState(state: String, detail: String) {}
            override fun onLog(line: String) {}
            override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {}
            override fun onNightMode(night: Boolean) {}
            override fun onIap2Start(id: Long, deviceId: String, deviceName: String, profileId: String) {
                linkId=id
                session=io.github.hypercar.iap2.Iap2NowPlayingSession(initializeArtworkWithEmptyFile=true).also {
                    it.authenticated();it.handle(io.github.hypercar.iap2.Iap2Csm.Frame(0x5000,byteArrayOf()))
                }
            }
            // Model the bridge's ID gate; transport framing is tested in WifiIap2ProtocolTest.
            override fun onIap2Bytes(id: Long, bytes: ByteArray) { if(id==linkId) session?.fileResponse(bytes,10) }
            override fun onIap2Stop(id: Long) { if(id==linkId) session=null }
        }
        fun files(now: Long)=session!!.poll(now,2048,true)
            .filterIsInstance<io.github.hypercar.iap2.Iap2NowPlayingSession.Output.File>().map { it.bytes }
        val old=EngineEventListener(gate,gate.begin(),target)
        old.onIap2Start(11,"same-device","phone", "audi_mib3")
        assertEquals(0x80.toByte(),files(0).single()[0])
        val current=EngineEventListener(gate,gate.begin(),target)
        current.onIap2Start(12,"same-device","phone", "audi_mib3")
        assertEquals(0x80.toByte(),files(0).single()[0])
        for(command in listOf(1,5)) {
            val reply=byteArrayOf(0x80.toByte(),command.toByte())
            old.onIap2Bytes(11,reply)
            old.onIap2Bytes(12,reply)
            current.onIap2Bytes(11,reply)
        }
        old.onIap2Stop(11)
        assertTrue(session!!.hasFileWork)
        assertTrue(files(11).isEmpty())
        current.onIap2Bytes(12,byteArrayOf(0x80.toByte(),1))
        assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()),files(12).single())
        current.onIap2Bytes(12,byteArrayOf(0x80.toByte(),5))
        assertFalse(session!!.hasFileWork)
    }

    @Test fun samePeerReinviteStartsNewActivationWhileOldListenerKeepsItsOwner() {
        val gate = EngineCallbackGate()
        var activation = ProactiveActivation()
        var wifiId = 0L
        var starts = 0
        val received = mutableListOf<Long>()
        val screenSetupAccepted = mutableListOf<Long>()
        var stopped = false
        val audioTakes = mutableListOf<Boolean>()
        val receiverUi = mutableListOf<String>()
        var activeViewTargets = emptyList<Int>()
        var pending: (() -> Unit)? = null
        val target = object : RustCoreBridge.EventListener {
            override fun onState(state: String, detail: String) {
                if (state == "failed" || state == "ended") {
                    if (!activation.interceptTerminal(detail)) {
                        val owner = gate.current()
                        pending = { if (gate.isCurrent(owner)) stopped = true }
                    }
                }
                if (state == "stopping") stopped = true
            }
            override fun onReceiverAudioTake(taken: Boolean) { audioTakes += taken }
            override fun onReceiverUi(kind: String, payload: String) { receiverUi += kind }
            override fun onViewAreaChoices(revision: Long, uuid: String, targets: IntArray) {
                activeViewTargets = targets.toList()
            }
            override fun onLog(line: String) {}
            override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) {}
            override fun onNightMode(night: Boolean) {}
            override fun onIap2Start(id: Long, deviceId: String, deviceName: String, profileId: String) { wifiId = id; starts++ }
            override fun onIap2Bytes(id: Long, bytes: ByteArray) { if (wifiId == id) received += id }
            override fun onIap2ScreenSetupAccepted(id: Long) { if (wifiId == id) screenSetupAccepted += id }
            override fun onIap2Stop(id: Long) { if (wifiId == id) wifiId = 0 }
        }
        class Invite(val host: String)
        val invites = PendingInvitation<Invite>()
        val firstInvite = Invite("172.16.222.97")
        invites.set(firstInvite)
        val old = EngineEventListener(gate, gate.begin(), target)
        old.onIap2Start(11, "phone", "phone", "audi_mib3")
        old.onState("failed", "SETUP 400")
        assertTrue(activation.rejected)
        val nextInvite = Invite(firstInvite.host)
        invites.set(nextInvite) // Arrives while the failed engine is still cleaning up.
        gate.retire()
        assertFalse(invites.discard(firstInvite))
        assertSame(nextInvite, invites.get())
        old.onState("ended", "late EOF during wait")
        old.onIap2Stop(11)
        assertEquals(0L, wifiId)
        activation = ProactiveActivation()
        val current = EngineEventListener(gate, gate.begin(), target)
        current.onIap2Start(12, "phone", "phone", "audi_mib3")
        old.onReceiverAudioTake(true)
        old.onReceiverUi("requestUI", """{"url":"maps:"}""")
        current.onReceiverUi("setLimitedUI", """{"limitedUI":true}""")
        old.onReceiverUi("screenOwned", """{"owned":false}""")
        assertEquals(listOf("setLimitedUI"), receiverUi)
        current.onViewAreaChoices(7L, "Main", intArrayOf(1, 3))
        old.onViewAreaChoices(6L, "Main", intArrayOf())
        assertEquals(listOf(1, 3), activeViewTargets)
        current.onReceiverAudioTake(true)
        old.onReceiverAudioTake(false)
        assertEquals(listOf(true), audioTakes)
        current.onReceiverAudioTake(false)
        assertEquals(listOf(true, false), audioTakes)
        old.onState("failed", "late panic watcher")
        old.onState("ended", "late EOF after reinvite")
        old.onState("stopping", "late cleanup")
        old.onIap2Start(11, "phone", "phone", "audi_mib3")
        old.onIap2Stop(11)
        val lateConfiguration = io.github.hypercar.iap2.Iap2Csm.encode(0x5703)
        old.onIap2Bytes(11, lateConfiguration)
        old.onIap2Bytes(12, lateConfiguration) // Even a matching link ID cannot bypass the engine owner.
        assertTrue(received.isEmpty())
        current.onIap2Bytes(11, lateConfiguration) // Current owner still needs the current link ID.
        assertTrue(received.isEmpty())
        current.onIap2Bytes(12, lateConfiguration)
        assertEquals(listOf(12L), received)
        old.onIap2ScreenSetupAccepted(11)
        old.onIap2ScreenSetupAccepted(12)
        current.onIap2ScreenSetupAccepted(11)
        assertTrue(screenSetupAccepted.isEmpty())
        current.onIap2ScreenSetupAccepted(12)
        assertEquals(listOf(12L), screenSetupAccepted)
        assertFalse(activation.rejected)
        assertFalse(stopped)
        assertEquals(12L, wifiId)
        assertEquals(2, starts)
        activation.videoReady()
        current.onState("ended", "current EOF")
        assertNotNull(pending) // Current events must still work.
        gate.retire()
        gate.begin()
        pending!!() // A queued cleanup rechecks ownership after another start.
        assertFalse(stopped)
    }
}
