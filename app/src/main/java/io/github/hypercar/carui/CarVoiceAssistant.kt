package io.github.hypercar.carui

import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Self-rendered source only. Use Android's default assistant and its phone microphone. */
object CarVoiceAssistant {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun wake(isCurrent: () -> Boolean) {
        scope.launch {
            if (!isCurrent()) return@launch
            if (!ShizukuTools.isReady) {
                HyperCarRepo.log("VOICE-KEY: 无法唤醒手机助手：Shizuku 未就绪")
                return@launch
            }
            if (!isCurrent()) return@launch
            runCatching { ShizukuTools.exec("input keyevent 219", 3000) }
                .onSuccess { HyperCarRepo.log("VOICE-KEY: 手机默认助手请求 exit=${it.code} err=${it.err.take(200)}（使用手机麦克风，系统首次启用/锁屏限制仍适用；非识别成功确认）") }
                .onFailure { HyperCarRepo.log("VOICE-KEY: 手机助手请求失败：${it.message}") }
        }
    }
}
