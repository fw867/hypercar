package io.github.hypercar.spike

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray

/** Two complementary, address-tagged policy sinks. Never opens the global submix at address 0. */
internal class SplitPlaybackAudio {
    private class Session(val cleanup: PlaybackCaptureCleanup, val uid: Int) {
        val live = AtomicBoolean(true)
        val bytes = AtomicLongArray(2)
        val nonzero = AtomicLongArray(2)
    }
    private var session: Session? = null
    @Volatile var status = "split stopped"
        private set

    @Synchronized
    fun start(context: Context, owner: IBinder, navigationUid: Int): Array<ParcelFileDescriptor>? {
        stop()
        val cleanup = PlaybackCaptureCleanup()
        val current = Session(cleanup, navigationUid)
        return try {
            val rules = playbackUidRules(navigationUid)
            val shell = PlaybackAudioContext(context)
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
            val loopBack = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            val format = AudioFormat.Builder().setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val builder = policyBuilderClass.getConstructor(Context::class.java).newInstance(shell)
            val mixes = rules.map { rule ->
                val rb = ruleBuilderClass.getConstructor().newInstance()
                ruleBuilderClass.getMethod(if (rule.exclude) "excludeMixRule" else "addMixRule",
                    Int::class.javaPrimitiveType, Any::class.java).invoke(rb, matchUid, rule.uid)
                val mb = mixBuilderClass.getConstructor(ruleClass).newInstance(ruleBuilderClass.getMethod("build").invoke(rb))
                mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mb, loopBack)
                mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mb, format)
                mixBuilderClass.getMethod("build").invoke(mb).also { policyBuilderClass.getMethod("addMix", mixClass).invoke(builder, it) }
            }
            val policy = policyBuilderClass.getMethod("build").invoke(builder)
            val audio = AudioManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(shell)
            // Own rollback before invoking registration: Binder failure may follow remote allocation.
            cleanup.own { AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(audio, policy) }
            val registered = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(audio, policy) as Int
            check(registered == 0) { "registerAudioPolicy=$registered" }
            val contextualSink = try {
                policyClass.getMethod("createAudioRecordSink", mixClass, AudioRecord.Builder::class.java)
            } catch (_: NoSuchMethodException) { null }
            val records = mixes.map { mix ->
                val sink = if (contextualSink != null) {
                    val recordBuilder = AudioRecord.Builder()
                    AudioRecord.Builder::class.java.getMethod("setContext", Context::class.java).invoke(recordBuilder, shell)
                    contextualSink.invoke(policy, mix, recordBuilder)
                } else policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix)
                val record = sink as? AudioRecord
                    ?: error("policy sink unavailable")
                cleanup.own { record.release() }
                check(record.state == AudioRecord.STATE_INITIALIZED) { "policy sink not initialized" }
                check(record.sampleRate == 48000 && record.channelCount == 2) { "policy sink format mismatch" }
                record.startRecording()
                cleanup.own { record.stop() }
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "policy sink not recording" }
                record
            }
            val pipes = records.map {
                ParcelFileDescriptor.createPipe().also { pipe -> pipe.forEach { fd -> cleanup.own { fd.close() } } }
            }
            val death = IBinder.DeathRecipient { end(current, "owner died") }
            cleanup.own { owner.unlinkToDeath(death, 0) }
            owner.linkToDeath(death, 0)
            check(owner.isBinderAlive && current.live.get()) { "capture owner no longer alive" }
            session = current
            status = "split capturing 48000Hz stereo PCM16; MEDIA excludes UID=$navigationUid NAV includes UID=$navigationUid; policy LOOP_BACK"
            records.forEachIndexed { lane, record ->
                Thread({ read(current, lane, record, pipes[lane][1]) },
                    if (lane == 0) "hypercar-media-policy" else "hypercar-nav-policy")
                    .apply { isDaemon = true; start() }
            }
            pipes.map { it[0] }.toTypedArray()
        } catch (error: Exception) {
            current.live.set(false)
            cleanup.close()
            if (session === current) session = null
            status = "split capture failed: ${error.cause?.message ?: error.message}; policy and both lanes released"
            android.util.Log.w("HyperCar", "CARUI-AUDIO: $status")
            null
        }
    }

    private fun read(current: Session, lane: Int, record: AudioRecord, output: ParcelFileDescriptor) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                val buffer = ByteArray(3840)
                var lastLog = SystemClock.elapsedRealtime()
                while (current.live.get()) {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    check(count > 0) { "policy read=$count" }
                    var nonzero = 0L
                    for (i in 0 until count - 1 step 2) if (buffer[i] != 0.toByte() || buffer[i + 1] != 0.toByte()) nonzero++
                    current.bytes.addAndGet(lane, count.toLong())
                    current.nonzero.addAndGet(lane, nonzero)
                    stream.write(buffer, 0, count)
                    val now = SystemClock.elapsedRealtime()
                    if (lane == 0 && now - lastLog >= 5000) {
                        val detail = "split UID=${current.uid} MEDIA bytes=${current.bytes.get(0)} nonzero=${current.nonzero.get(0)} NAV bytes=${current.bytes.get(1)} nonzero=${current.nonzero.get(1)}; cumulative, no content"
                        synchronized(this) { if (session === current) status = detail }
                        android.util.Log.i("HyperCar", "CARUI-AUDIO: $detail")
                        lastLog = now
                    }
                }
            }
        } catch (error: Exception) {
            if (current.live.get()) end(current, "${if (lane == 0) "MEDIA" else "NAV"} reader failed: ${error.cause?.message ?: error.message}")
        }
    }

    private fun end(current: Session, reason: String) {
        if (!current.live.getAndSet(false)) return
        current.cleanup.close()
        synchronized(this) {
            if (session === current) {
                session = null
                status = "split stopped: $reason; both lanes and policy released"
                android.util.Log.i("HyperCar", "CARUI-AUDIO: $status")
            }
        }
    }

    @Synchronized fun hasActiveCapture(): Boolean = session != null

    @Synchronized fun stop() {
        session?.let { end(it, "requested") }
    }
}
