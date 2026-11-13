package io.github.hypercar.carui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental transcription only: no assistant launch, command execution, or recorded files. */
object CarSpeechValidation {
    data class State(val message: String = "", val text: String = "", val bytesWritten: Long = 0,
                     val recognizer: String = "", val active: Boolean = false, val final: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private class Capture(val token: Long, val current: () -> Boolean) {
        val audio = ExternalPcmQueue()
        val closed = AtomicBoolean(false)
        var read: ParcelFileDescriptor? = null
        var write: ParcelFileDescriptor? = null
        var recognizer: SpeechRecognizer? = null
        var worker: Thread? = null
    }
    @Volatile private var capture: Capture? = null
    private val retired = ArrayDeque<Long>()

    private fun component(context: Context): ComponentName {
        check(Build.VERSION.SDK_INT >= 33) { "外部音频识别验证需要 Android 13 或以上" }
        val candidates = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }.filter {
                it.enabled && it.exported && it.permission == "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"
            }.map { ComponentName(it.packageName, it.name) }
        val selected = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)
        return selected?.takeIf { it in candidates } ?: candidates.singleOrNull()
            ?: error(if (candidates.isEmpty()) "未找到可用的系统识别服务，请先安装或启用兼容识别器"
                     else "存在多个识别服务，请先在系统设置选择默认识别器")
    }

    fun sendFinalText(context: Context) {
        main.post {
            val result = mutable.value
            if (!result.final || result.active || result.text.isBlank()) return@post
            runCatching { XiaoAiTextBridge.send(context, result.text) }
                .onSuccess { mutable.value = result.copy(message = "已交给手机小爱；执行结果以小爱为准", final = false) }
                .onFailure { mutable.value = result.copy(message = it.message ?: "无法打开小爱文本入口") }
        }
    }

    fun inspect(context: Context) {
        main.post {
            if (capture != null) return@post
            mutable.value = runCatching { component(context) }.fold(
                { State("找到识别服务；仍需验证它是否采用外部音频", recognizer = it.flattenToShortString()) },
                { State(it.message ?: "无法检查识别服务") })
        }
    }

    @Synchronized fun receive(context: Context, token: Long, pcm: ByteArray, rate: Int, end: Boolean,
                              current: () -> Boolean) {
        if (!current() || !SettingsStore.carVoiceRecognition || token in retired) return
        var c = capture
        if (c?.token != token) {
            if (end) {
                if (c == null) main.post {
                    if (current() && capture == null) mutable.value = State("未收到可识别的车机音频；请检查车机输入协商")
                }
                return
            }
            if (pcm.isEmpty()) return
            c?.let { previous -> retire(previous); main.post { close(previous) } }
            c = Capture(token, current)
            capture = c
            val first = c
            main.post { start(context.applicationContext, first) }
        }
        if (end) c.audio.end()
        else if (!c.audio.offer(pcm, rate)) {
            val failed = c
            main.post { fail(failed, "外部音频格式异常或队列积压，已停止本次验证") }
        }
    }

    private fun valid(c: Capture) = capture === c && !c.closed.get() && c.current()
    private fun start(context: Context, c: Capture) {
        if (!valid(c)) { finish(c); return }
        mutable.value = State("正在检查识别服务", active = true)
        runCatching {
            val service = component(context)
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "请先授予 HyperCar 录音权限，再重试验证"
            }
            val pipe = ParcelFileDescriptor.createPipe()
            c.read = pipe[0]; c.write = pipe[1]
            Os.fcntlInt(pipe[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context, service)
            c.recognizer = recognizer
            io.github.hypercar.session.HyperCarRepo.log("ASR-VERIFY: recognizer=${service.flattenToShortString()} externalPCM=16000/mono/PCM16; source unconfirmed")
            mutable.value = State("正在连接识别器；音频来源尚未确认", recognizer = service.flattenToShortString(), active = true)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (valid(c)) mutable.value = mutable.value.copy(message = "请说话；外部音频来源待对照验证")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (valid(c)) stopInput(c) }
                override fun onError(error: Int) { fail(c, "识别失败：${errorLabel(error)}") }
                override fun onResults(results: Bundle?) {
                    if (!valid(c)) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().take(300)
                    mutable.value = mutable.value.copy(message = if (text.isEmpty()) "未识别到文字；音频来源未确认" else "识别结束；外部音频来源仍待对照验证", text = text, active = false, final = text.isNotEmpty())
                    io.github.hypercar.session.HyperCarRepo.log("ASR-VERIFY: finalTextChars=${text.length}; source unconfirmed; no automatic action")
                    finish(c)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!valid(c)) return
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().take(300)
                    mutable.value = mutable.value.copy(message = "识别中；音频来源待验证", text = text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            recognizer.startListening(request)
            c.worker = Thread({ write(c, pipe[1]) }, "car-asr-pcm").apply { isDaemon = true; start() }
            main.postDelayed({ if (valid(c)) fail(c, "识别超时，已关闭输入") }, 28_000)
        }.onFailure { fail(c, it.message ?: "识别服务启动失败") }
    }

    private fun write(c: Capture, output: ParcelFileDescriptor) {
        var written = 0L
        try {
            while (!c.closed.get() && c.current() && !c.audio.drained()) {
                val packet = c.audio.poll() ?: continue
                var offset = 0
                val deadline = android.os.SystemClock.elapsedRealtime() + 250
                while (offset < packet.size && !c.closed.get()) {
                    try { offset += Os.write(output.fileDescriptor, packet, offset, packet.size - offset) }
                    catch (e: ErrnoException) {
                        if (e.errno != OsConstants.EAGAIN || android.os.SystemClock.elapsedRealtime() >= deadline) throw e
                        Thread.sleep(8)
                    }
                }
                written += offset
                val bytes = written
                main.post { if (valid(c)) mutable.value = mutable.value.copy(bytesWritten = bytes) }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Exception) { main.post { fail(c, "识别器未及时读取外部音频或管道已关闭") } }
        finally { runCatching { output.close() } }
    }
    private fun stopInput(c: Capture) {
        c.audio.end()
        runCatching { RustCoreBridge.nativeCancelMicrophone(c.token) }
    }
    private fun fail(c: Capture, message: String) {
        if (!valid(c)) { close(c); return }
        mutable.value = mutable.value.copy(message = message, active = false)
        io.github.hypercar.session.HyperCarRepo.log("ASR-VERIFY: stopped: $message; transcript not logged")
        finish(c)
    }
    @Synchronized private fun retire(c: Capture) {
        if (c.token !in retired) retired.addLast(c.token)
        while (retired.size > 16) retired.removeFirst()
    }
    private fun finish(c: Capture) {
        retire(c)
        stopInput(c)
        close(c)
        if (capture === c) capture = null
    }
    private fun close(c: Capture) {
        if (!c.closed.compareAndSet(false, true)) return
        c.audio.cancel(); c.worker?.interrupt()
        runCatching { c.read?.close() }; runCatching { c.write?.close() }
        runCatching { c.recognizer?.cancel() }; runCatching { c.recognizer?.destroy() }
    }
    @Synchronized fun cancel() {
        val old = capture
        if (old != null) { retire(old); capture = null }
        main.post { old?.let { stopInput(it); close(it) }; mutable.value = State() }
    }
    private fun errorLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有匹配的文字"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务网络失败"
        SpeechRecognizer.ERROR_AUDIO -> "识别器音频输入失败"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "识别服务不支持当前语言或模型未就绪"
        else -> "服务错误 $code"
    }
}
