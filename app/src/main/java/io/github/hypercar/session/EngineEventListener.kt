package io.github.hypercar.session

import io.github.hypercar.core.RustCoreBridge

/** Rust retains this exact delegate for the engine and all its child tasks. */
internal class EngineEventListener(
    private val gate: EngineCallbackGate,
    private val owner: Long,
    private val target: RustCoreBridge.EventListener,
) : RustCoreBridge.EventListener {
    private val ignoredStates = java.util.concurrent.atomic.AtomicInteger()
    private fun deliver(action: () -> Unit) { gate.deliver(owner, action) }
    override fun onState(state: String, detail: String) {
        if (!gate.deliver(owner) { target.onState(state, detail) } && ignoredStates.incrementAndGet() <= 8)
            target.onLog("CORE-CALLBACK: 忽略旧引擎状态 owner=$owner current=${gate.current()} state=$state")
    }
    override fun onLog(line: String) = target.onLog(line) // Keep old cleanup diagnostics.
    override fun onCarWithReopen(attempt: Long, startup: Boolean) = target.onCarWithReopen(attempt, startup) // The source belongs to the connection attempt.
    override fun onPipeline(json: String) = deliver { target.onPipeline(json) }
    // The journal checks the captured attempt and engine, including during late teardown.
    override fun onEventChannel(rtsp: Long, uptime: Long, cause: String, videoTcp: String) =
        target.onEventChannel(rtsp, uptime, cause, videoTcp)
    override fun onCarWithNavigation(token: Long, json: String) = deliver { target.onCarWithNavigation(token, json) }
    override fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean) =
        deliver { target.onStats(fps, frames, kbps, audioPlaying, ducking) }
    override fun onNightMode(night: Boolean) = deliver { target.onNightMode(night) }
    override fun onReceiverUi(kind: String, payload: String) = deliver { target.onReceiverUi(kind, payload) }
    override fun onMediaKey(usage: Int) = deliver { target.onMediaKey(usage) }
    // Native holds its modes lock. The target orders this event with pause/retirement on the
    // separate audio lock and rechecks owner there; never wait for general callback delivery here.
    override fun onReceiverAudioTake(taken: Boolean) {
        if (gate.isCurrent(owner)) target.onReceiverAudioTake(taken)
    }
    override fun onVoiceAssistant() = deliver { target.onVoiceAssistant() }
    override fun onMicrophonePcm(owner: Long, token: Long, pcm: ByteArray, rate: Int, end: Boolean) {
        // End carries its own capture token; it must still close the old capture.
        if (end) target.onMicrophonePcm(owner, token, pcm, rate, end)
        else deliver { target.onMicrophonePcm(owner, token, pcm, rate, end) }
    }
    override fun onForceKeyFrame() = deliver { target.onForceKeyFrame() }
    override fun onVideoCapabilities(hevcAllowed: Boolean) = deliver { target.onVideoCapabilities(hevcAllowed) }
    override fun onDisplaySize(width: Int, height: Int, maxFps: Int) = deliver { target.onDisplaySize(width, height, maxFps) }
    override fun onViewAreaChoices(revision: Long, uuid: String, targets: IntArray) =
        deliver { target.onViewAreaChoices(revision, uuid, targets) }
    override fun onDisplayLayout(width: Int, height: Int, viewX: Int, viewY: Int, viewWidth: Int, viewHeight: Int,
        safeX: Int, safeY: Int, safeWidth: Int, safeHeight: Int, drawOutside: Boolean,
        statusBarEdge: Int, rightHandDrive: Boolean, allowsFocus: Boolean) = deliver {
        target.onDisplayLayout(width, height, viewX, viewY, viewWidth, viewHeight,
            safeX, safeY, safeWidth, safeHeight, drawOutside, statusBarEdge, rightHandDrive, allowsFocus)
    }
    override fun onIap2Start(id: Long, deviceId: String, deviceName: String, profileId: String) =
        deliver { target.onIap2Start(id, deviceId, deviceName, profileId) }
    override fun onIap2Bytes(id: Long, bytes: ByteArray) = deliver { target.onIap2Bytes(id, bytes) }
    override fun onIap2ScreenSetupAccepted(id: Long) = deliver { target.onIap2ScreenSetupAccepted(id) }
    // Stop is already scoped by the Wi-Fi iAP2 ID. Retire the old worker even while waiting.
    override fun onIap2Stop(id: Long) = target.onIap2Stop(id)
}
