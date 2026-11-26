package io.github.hypercar.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class CallAudioOfferEnd {
    WITHDRAWN, IDENTITY_UNCONFIRMED, OTHER;

    companion object {
        fun fromStatus(status: String?): CallAudioOfferEnd {
            val endpoint = status?.substringAfter(" endpoint=", "") ?: return OTHER
            return when {
                endpoint == ShellCallAudio.OPEN_IDENTITY_UNCONFIRMED -> IDENTITY_UNCONFIRMED
                endpoint == ShellCallAudio.CONTROLLER_REVOKED ||
                    endpoint == ShellCallAudio.CALL_NO_LONGER_ELIGIBLE ||
                    endpoint.startsWith(ShellCallAudio.IDENTITY_REVOKED) ||
                    endpoint.startsWith(ShellCallAudio.IDENTITY_LOST) -> WITHDRAWN
                else -> OTHER
            }
        }
    }
}

internal class ShellCallEndpoint(private val identity: ShellCallIdentity, private val lease: String) {
    private val live = AtomicBoolean(true)
    private val stateLock = Any()
    private val audioLock = Any()

    fun isLive(): Boolean = live.get()

    fun <T : Any> runWhileAuthorized(startBefore: Long? = null, action: () -> T): T? = synchronized(stateLock) {
        if (live.get()) identity.withCurrentLease(lease, startBefore, action) else null
    }

    fun <T : Any> runAudioWhileAuthorized(startBefore: Long? = null, action: () -> T): T? = synchronized(audioLock) {
        if (runWhileAuthorized(startBefore) { true } == true && live.get()) action() else null
    }

    fun retire(stopTransport: () -> Unit = {}): Boolean {
        // Cancel new admissions and wake the socket without waiting for Android audio.
        val wasLive = synchronized(stateLock) {
            live.getAndSet(false).also { if (it) stopTransport() }
        }
        // An admitted native call must leave the media lock before revocation completes.
        synchronized(audioLock) { return wasLive }
    }
}

/** Live PSTN downlink/extraction and uplink/injection owned by the authorized shell service. */
internal class ShellCallAudio(private val callIdentity: ShellCallIdentity) {
    private class UnconfirmedCallIdentity : IllegalStateException()

    private class Session(
        val token: Long, val offer: Long, val callId: String, val owner: IBinder, val death: IBinder.DeathRecipient,
        val socket: ParcelFileDescriptor, val record: AudioRecord, val track: AudioTrack,
        val endpoint: ShellCallEndpoint,
    ) {
        val endReason = AtomicReference<String?>()
    }

    private data class ClosedOffer(val token: Long, val offer: Long, val status: String)

    @Volatile private var session: Session? = null
    @Volatile private var status = "idle"
    private val closedOffers = ArrayDeque<ClosedOffer>()

    fun diagnosticStatus(): String = session?.endReason?.get() ?: status

    fun diagnosticStatus(token: Long, offer: Long): String? {
        val active = session
        if (active?.token == token && active.offer == offer) {
            active.endReason.get()?.let { return "identity=${callIdentity.diagnosticStatus()} endpoint=$it" }
        }
        return synchronized(closedOffers) {
            closedOffers.lastOrNull { it.token == token && it.offer == offer }?.status
        }
    }

    private val pstnRedirectionMode by lazy { AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null) }

    private fun createDownlink(context: Context, manager: AudioManager, format: AudioFormat): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                .invoke(manager, format) as AudioRecord
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("shell RECORD_AUDIO denied")
        }
        // AudioManager's convenience method builds the endpoint without the manager's context.
        val builder = AudioRecord.Builder().setContext(context)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK).setAudioFormat(format)
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
            .invoke(builder, pstnRedirectionMode)
        return builder.build()
    }

    private fun createUplink(context: Context, manager: AudioManager, format: AudioFormat): AudioTrack {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                .invoke(manager, format) as AudioTrack
        }
        val usage = AudioAttributes::class.java.getField("USAGE_CALL_ASSISTANT").getInt(null)
        val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioAttributes.Builder::class.java.getMethod("setSystemUsage", Int::class.javaPrimitiveType)
            .invoke(attributes, usage)
        val builder = AudioTrack.Builder().setContext(context)
            .setAudioAttributes(attributes.build()).setAudioFormat(format)
        AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
            .invoke(builder, pstnRedirectionMode)
        return builder.build()
    }

    @Synchronized
    fun open(context: Context, owner: IBinder, token: Long, offer: Long, callId: String, rate: Int,
             route: ICallAudioRoute): ParcelFileDescriptor? {
        if (token <= 0 || offer <= 0 || rate !in setOf(16000, 24000, 44100, 48000) || session != null) return null
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var sockets: Array<ParcelFileDescriptor>? = null
        var death: IBinder.DeathRecipient? = null
        var stage = "eligibility"
        return try {
            val shellContext = PlaybackAudioContext(context)
            val manager = AudioManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(shellContext)
            // TelecomManager.isInEmergencyCall() returns false on IPC failure.
            // Query its service directly so a failed or missing reply closes the bridge.
            val telecomBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, Context.TELECOM_SERVICE) as? IBinder ?: error("telecom service unavailable")
            val telecomService = Class.forName("com.android.internal.telecom.ITelecomService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, telecomBinder)
                ?: error("telecom service unavailable")
            val emergency = Class.forName("com.android.internal.telecom.ITelecomService")
                .getMethod("isInEmergencyCall")
            fun safeCall() = manager.mode == AudioManager.MODE_IN_CALL && emergency.invoke(telecomService) == false
            check(safeCall()) { "regular cellular call unavailable" }
            if (!callIdentity.confirmsOpen(callId)) throw UnconfirmedCallIdentity()
            check(AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(manager) == true) {
                "cellular call audio unavailable on this device"
            }
            stage = "select telephony uplink"
            val telephony = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                ?: error("telephony output unavailable")
            val rates = telephony.sampleRates
            check(rates.isEmpty() || rate in rates) { "telephony output does not support $rate" }
            fun format(mask: Int) = AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            stage = "create downlink"
            val downlink = createDownlink(shellContext, manager, format(AudioFormat.CHANNEL_IN_MONO))
            record = downlink
            stage = "create uplink"
            check(safeCall()) { "regular cellular call ended while opening endpoints" }
            val uplink = createUplink(shellContext, manager, format(AudioFormat.CHANNEL_OUT_MONO))
            track = uplink
            stage = "validate endpoints"
            check(downlink.state == AudioRecord.STATE_INITIALIZED && uplink.state == AudioTrack.STATE_INITIALIZED)
            check(downlink.sampleRate == rate && uplink.sampleRate == rate && downlink.channelCount == 1 && uplink.channelCount == 1)
            check(uplink.setPreferredDevice(telephony)) { "telephony uplink route rejected" }
            stage = "attach endpoints"
            val pair = ParcelFileDescriptor.createSocketPair()
            sockets = pair
            val ownerDeath = IBinder.DeathRecipient { close(token, "owner_disconnected") }
            death = ownerDeath
            owner.linkToDeath(ownerDeath, 0)
            check(owner.isBinderAlive && safeCall())
            if (!callIdentity.confirmsOpen(callId)) throw UnconfirmedCallIdentity()
            val endpoint = ShellCallEndpoint(callIdentity, callId)
            val active = Session(token, offer, callId, owner, ownerDeath, pair[0], downlink, uplink, endpoint)
            endpoint.runWhileAuthorized { session = active } ?: throw UnconfirmedCallIdentity()
            status = "endpoints_ready_rate_$rate"
            val watchdog = Thread({
                var reason = CALL_NO_LONGER_ELIGIBLE
                try {
                    while (active.endpoint.isLive() && safeCall()) {
                        if (callIdentity.current(allowFallback = true) != callId) {
                            reason = "$IDENTITY_LOST${callIdentity.diagnosticStatus()}"
                            break
                        }
                        SystemClock.sleep(callIdentity.millisUntilRefresh())
                    }
                } catch (error: Exception) { reason = failureStatus("call_state", error) }
                finally { close(active, reason) }
            }, "hypercar-call-state")
            val transport = Thread({
                var reason = "uplink_closed"
                try {
                    transfer(active, rate, manager, route) {
                        val eligible = safeCall()
                        val authorized = callIdentity.matchesCached(callId)
                        if (!authorized) active.endReason.compareAndSet(null, "$IDENTITY_LOST${callIdentity.diagnosticStatus()}")
                        else if (!eligible) active.endReason.compareAndSet(null, CALL_NO_LONGER_ELIGIBLE)
                        authorized && eligible
                    }
                }
                catch (error: Exception) { reason = failureStatus("transfer", error) }
                finally { close(active, reason) }
            }, "hypercar-call-uplink")
            listOf(watchdog, transport).forEach { it.isDaemon = true; it.start() }
            Log.i("HyperCar", "CALL-AUDIO: shell endpoints ready rate=$rate")
            pair[1]
        } catch (error: Exception) {
            close(token)
            death?.let { runCatching { owner.unlinkToDeath(it, 0) } }
            runCatching { record?.release() }
            runCatching { track?.release() }
            sockets?.forEach { runCatching { it.close() } }
            status = if (error is UnconfirmedCallIdentity) OPEN_IDENTITY_UNCONFIRMED
                else failureStatus("${stage.replace(' ', '_')}_rate_$rate", error)
            Log.i("HyperCar", "CALL-AUDIO: $status")
            null
        }
    }

    private fun transfer(active: Session, rate: Int, manager: AudioManager, route: ICallAudioRoute, safeCall: () -> Boolean) {
        val fd = active.socket.fileDescriptor
        val input = DataInputStream(FileInputStream(fd))
        val socketState = arrayOf(StructPollfd().apply { this.fd = fd; events = 0 })
        fun ownsEndpoint(): Boolean = active.endpoint.isLive() && safeCall() &&
            (Os.poll(socketState, 0) == 0 || (socketState[0].revents.toInt() and SOCKET_HANGUP) == 0)
        // Rust sends this byte only after the active session accepts the call stream.
        check(input.read() == 1)
        fun startAudio(action: () -> Unit): Boolean = callIdentity.confirmsStart(active.callId, authorize = { deadline ->
            active.endpoint.runAudioWhileAuthorized(startBefore = deadline, action = action) != null
        }) preflight@{
            if (!ownsEndpoint()) return@preflight false
            // Check the app's Bluetooth route after any Telecom retry.
            runCatching { route.mayStart() }.getOrDefault(false).also { allowed ->
                if (!allowed) active.endReason.compareAndSet(null, "phone_route_revoked")
            }
        }
        if (!startAudio { active.record.startRecording() }) return recordDeniedLease(active, OPEN_IDENTITY_UNCONFIRMED)
        // Opening the recorder can consume the proof's lifetime. Confirm again before playing into the call.
        if (!startAudio {
            active.track.play()
            check(active.record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            check(active.track.playState == AudioTrack.PLAYSTATE_PLAYING)
        }) return recordDeniedLease(active, OPEN_IDENTITY_UNCONFIRMED)
        // Some devices report the actual route only once playback has started. Prime it with silence.
        val silence = ByteArray(rate / 50 * 2)
        if (!ownsEndpoint()) return
        active.endpoint.runAudioWhileAuthorized {
            active.track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
        } ?: return recordDeniedLease(active)
        val routeDeadline = SystemClock.elapsedRealtime() + 300
        while (active.track.routedDevice?.type != AudioDeviceInfo.TYPE_TELEPHONY &&
            SystemClock.elapsedRealtime() < routeDeadline && active.endpoint.isLive()
        ) {
            SystemClock.sleep(10)
        }
        val routedType = active.track.routedDevice?.type
        if (routedType != AudioDeviceInfo.TYPE_TELEPHONY) {
            active.endReason.compareAndSet(null, "uplink_route_rejected_type_$routedType")
            Log.w("HyperCar", "CALL-AUDIO: shell uplink route rejected actualType=$routedType rate=$rate")
            error("telephony uplink route unavailable")
        }
        Log.i("HyperCar", "CALL-AUDIO: shell uplink routed to telephony rate=$rate")
        Thread({
            var reason = "downlink_call_no_longer_eligible"
            try {
                val writable = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLOUT.toShort() })
                val readyByte = byteArrayOf(1)
                val frames = if (rate >= 44100) 352 else rate / 50
                val buffer = ByteArray(frames * 2)
                var ready = false
                while (active.endpoint.isLive()) {
                    val count = active.record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0 || count % 2 != 0) {
                        reason = "downlink_read_$count"
                        break
                    }
                    if (!ready) {
                        if (!sendDownlink(active, fd, writable, readyByte, 1, safeCall)) break
                        ready = true
                    }
                    if (!sendDownlink(active, fd, writable, buffer, count, safeCall)) break
                }
            } catch (error: Exception) {
                reason = failureStatus("downlink", error)
            } finally { close(active, reason) }
        }, "hypercar-call-downlink").apply { isDaemon = true; start() }
        val buffer = ByteArray(0x7ffe)
        while (ownsEndpoint()) {
            val count = try { input.readUnsignedShort() } catch (_: EOFException) { return }
            check(count in 2..buffer.size && count % 2 == 0) { "invalid call microphone frame" }
            input.readFully(buffer, 0, count)
            // POLLHUP is reported even if the retired peer left unread PCM in the socket.
            if (!ownsEndpoint()) return
            var offset = 0
            while (offset < count && !manager.isMicrophoneMute) {
                val routedType = active.track.routedDevice?.type
                if (routedType != AudioDeviceInfo.TYPE_TELEPHONY) {
                    active.endReason.compareAndSet(null, "uplink_route_lost_type_$routedType")
                    return
                }
                if (!ownsEndpoint()) return
                val written = active.endpoint.runAudioWhileAuthorized {
                    active.track.write(buffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                } ?: return recordDeniedLease(active)
                check(written >= 0) { "call track write=$written" }
                if (written == 0) SystemClock.sleep(5)
                offset += written
            }
        }
    }

    private fun recordDeniedLease(active: Session, fallback: String? = null) {
        val reason = if (!callIdentity.matchesCached(active.callId)) "$IDENTITY_LOST${callIdentity.diagnosticStatus()}"
            else fallback
        if (reason != null) active.endReason.compareAndSet(null, reason)
    }

    private fun sendDownlink(active: Session, fd: FileDescriptor, writable: Array<StructPollfd>,
                             bytes: ByteArray, count: Int, safeCall: () -> Boolean): Boolean {
        var offset = 0
        while (offset < count) {
            if (!active.endpoint.isLive() || !safeCall()) return false
            val sent = active.endpoint.runWhileAuthorized {
                try {
                    Os.sendto(fd, bytes, offset, count - offset,
                        MSG_DONTWAIT or MSG_NOSIGNAL, null, 0)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN) throw error
                    0
                }
            } ?: run {
                recordDeniedLease(active)
                return false
            }
            if (sent == 0 && Os.poll(writable, 50) > 0 &&
                (writable[0].revents.toInt() and SOCKET_HANGUP) != 0
            ) return false
            offset += sent
        }
        return true
    }

    fun revoke(callId: String) {
        val active = session?.takeIf { it.callId == callId } ?: return
        revoke(active, "$IDENTITY_REVOKED${callIdentity.diagnosticStatus()}")
    }

    fun revokeOffer(token: Long, offer: Long) {
        val active = session?.takeIf { it.token == token && it.offer == offer } ?: return
        revoke(active, CONTROLLER_REVOKED)
    }

    private fun revoke(active: Session, reason: String) {
        active.endReason.compareAndSet(null, reason)
        active.endpoint.retire {
            runCatching { Os.shutdown(active.socket.fileDescriptor, OsConstants.SHUT_RDWR) }
        }
    }

    @Synchronized
    fun close(token: Long, reason: String = "controller_closed") {
        session?.takeIf { it.token == token }?.let { close(it, reason) }
    }

    @Synchronized
    private fun close(active: Session, reason: String) {
        if (session !== active) return
        active.endpoint.retire {
            runCatching { Os.shutdown(active.socket.fileDescriptor, OsConstants.SHUT_RDWR) }
        }
        status = active.endReason.get() ?: reason
        val outcome = "identity=${callIdentity.diagnosticStatus()} endpoint=$status"
        synchronized(closedOffers) {
            if (closedOffers.size == 8) closedOffers.removeFirst()
            closedOffers.addLast(ClosedOffer(active.token, active.offer, outcome))
        }
        Log.i("HyperCar", "CALL-AUDIO: shell endpoints stopping token=${active.token} offer=${active.offer} $outcome")
        session = null
        runCatching { active.socket.close() }
        runCatching { active.record.stop() }
        runCatching { active.track.pause() }
        runCatching { active.track.flush() }
        runCatching { active.record.release() }
        runCatching { active.track.release() }
        runCatching { active.owner.unlinkToDeath(active.death, 0) }
    }

    private fun failureStatus(stage: String, error: Exception) =
        "${stage}_failed_${(error.cause ?: error).javaClass.simpleName}"

    companion object {
        // Linux send flags that Android's public OsConstants does not expose.
        private const val MSG_DONTWAIT = 0x40
        private const val MSG_NOSIGNAL = 0x4000
        private val SOCKET_HANGUP = OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL
        const val OPEN_IDENTITY_UNCONFIRMED = "open_identity_unconfirmed"
        const val IDENTITY_REVOKED = "identity_revoked_"
        const val IDENTITY_LOST = "identity_lost_"
        const val CONTROLLER_REVOKED = "controller_revoked"
        const val CALL_NO_LONGER_ELIGIBLE = "call_no_longer_eligible"
    }
}
