package io.github.hypercar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.github.hypercar.carui.CarUiSettings
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore

class HyperCarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        // 兼容已安装的临时排查版：恢复被禁用的组件，将旧停用状态迁移至自动连接开关。
        SettingsStore.migrateLegacyListeningMode(this)
        CarUiSettings.init(this)
        createNotificationChannel()
        // 会话总管：注入 ACL 唤醒 handler + Rust 回调监听（秒连链路的第一环）
        HyperCarRepo.init(this)
        if (SettingsStore.autoConnect) HyperCarRepo.startAutomaticListening(this)
        // 触发 RustCoreBridge 类加载（loadLibrary + nativeInitLogger）。
        // 注意：这里绝不能再 setListener(null)——Repo 刚注册的监听会被清掉。
        RustCoreBridge.nativeIsRunning()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_LINK,
                getString(R.string.notification_channel_link),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "车联会话前台服务通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_LINK = "hypercar_link"
    }
}
