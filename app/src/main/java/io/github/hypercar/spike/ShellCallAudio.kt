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
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Live PSTN downlink/extraction and uplink/injection owned by the authorized shell service. */
internal class ShellCallAudio(private val callIdentity: ShellCallIdentity) {
    private class Session(
        val token: Long, val callId: String, val owner: IBinder, val death: IBinder.DeathRecipient,
        val socket: ParcelFileDescriptor, val record: AudioRecord, val track: AudioTrack,
    ) {
        val live = AtomicBoolean(true)
    }

    @Volatile private var session: Session? = null

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
    fun open(context: Context, owner: IBinder, token: Long, callId: String, rate: Int): ParcelFileDescriptor? {
        if (token <= 0 || rate !in setOf(16000, 24000, 44100, 48000) || session != null) return null
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
            check(safeCall() && callIdentity.current() == callId) { "regular cellular call unavailable" }
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
            val ownerDeath = IBinder.DeathRecipient { close(token) }
            death = ownerDeath
            owner.linkToDeath(ownerDeath, 0)
            check(owner.isBinderAlive && safeCall() && callIdentity.current() == callId)
            val active = Session(token, callId, owner, ownerDeath, pair[0], downlink, uplink)
            session = active
            val watchdog = Thread({
                try {
                    while (active.live.get() && safeCall() && callIdentity.current() == callId) {
                        SystemClock.sleep(200)
                    }
                } catch (error: Exception) { if (active.live.get()) logFailure("call state", error) }
                finally { close(active) }
            }, "hypercar-call-state")
            val transport = Thread({
                try { transfer(active, rate, manager) { safeCall() && callIdentity.matchesCached(callId) } }
                catch (error: Exception) { if (active.live.get()) logFailure("transfer", error) }
                finally { close(active) }
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
            logFailure("$stage rate=$rate", error)
            null
        }
    }

    private fun transfer(active: Session, rate: Int, manager: AudioManager, safeCall: () -> Boolean) {
        val fd = active.socket.fileDescriptor
        val input = DataInputStream(FileInputStream(fd))
        val hangup = OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL
        val socketState = arrayOf(StructPollfd().apply { this.fd = fd; events = 0 })
        fun ownsEndpoint(): Boolean = active.live.get() && safeCall() &&
            (Os.poll(socketState, 0) == 0 || (socketState[0].revents.toInt() and hangup) == 0)
        // Rust sends this byte only after the active session accepts the call stream.
        check(input.read() == 1)
        synchronized(this) {
            check(session === active && safeCall())
            active.record.startRecording()
            active.track.play()
            check(active.record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            check(active.track.playState == AudioTrack.PLAYSTATE_PLAYING)
        }
        // Some devices report the actual route only once playback has started. Prime it with silence.
        val silence = ByteArray(rate / 50 * 2)
        active.track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
        val routeDeadline = SystemClock.elapsedRealtime() + 300
        while (active.track.routedDevice?.type != AudioDeviceInfo.TYPE_TELEPHONY &&
            SystemClock.elapsedRealtime() < routeDeadline && active.live.get()
        ) {
            SystemClock.sleep(10)
        }
        val routedType = active.track.routedDevice?.type
        if (routedType != AudioDeviceInfo.TYPE_TELEPHONY) {
            Log.w("HyperCar", "CALL-AUDIO: shell uplink route rejected actualType=$routedType rate=$rate")
            error("telephony uplink route unavailable")
        }
        Log.i("HyperCar", "CALL-AUDIO: shell uplink routed to telephony rate=$rate")
        Thread({
            try {
                val output = FileOutputStream(fd)
                val frames = if (rate >= 44100) 352 else rate / 50
                val buffer = ByteArray(frames * 2)
                var ready = false
                while (active.live.get()) {
                    val count = active.record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    check(count > 0 && count % 2 == 0) { "call record read=$count" }
                    if (!active.live.get() || !safeCall()) break
                    if (!ready) {
                        output.write(1)
                        ready = true
                        if (!active.live.get() || !safeCall()) break
                    }
                    output.write(buffer, 0, count)
                }
            } catch (error: Exception) {
                if (active.live.get()) logFailure("downlink", error)
            } finally { close(active) }
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
                check(active.track.routedDevice?.type == AudioDeviceInfo.TYPE_TELEPHONY) { "telephony uplink lost its route" }
                if (!ownsEndpoint()) return
                val written = active.track.write(buffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                check(written >= 0) { "call track write=$written" }
                if (written == 0) SystemClock.sleep(5)
                offset += written
            }
        }
    }

    fun revoke(callId: String) {
        val active = session?.takeIf { it.callId == callId } ?: return
        // Wake the PCM workers immediately. Their finally blocks release the audio endpoints.
        active.live.set(false)
        runCatching { Os.shutdown(active.socket.fileDescriptor, OsConstants.SHUT_RDWR) }
    }

    @Synchronized
    fun close(token: Long) {
        session?.takeIf { it.token == token }?.let { close(it) }
    }

    @Synchronized
    private fun close(active: Session) {
        if (session !== active) return
        session = null
        active.live.set(false)
        runCatching { Os.shutdown(active.socket.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { active.socket.close() }
        runCatching { active.record.stop() }
        runCatching { active.track.pause() }
        runCatching { active.track.flush() }
        runCatching { active.record.release() }
        runCatching { active.track.release() }
        runCatching { active.owner.unlinkToDeath(active.death, 0) }
    }

    private fun logFailure(stage: String, error: Exception) {
        Log.i("HyperCar", "CALL-AUDIO: shell $stage failed ${(error.cause ?: error).javaClass.simpleName}")
    }
}
