package io.github.hypercar.core

/**
 * 会话状态模型 —— UI（Compose）与 Orchestrator 之间的唯一契约。
 *
 * 本项目只保留 CarPlay 一条链路：蓝牙 iAP2 协商 → AirPlay 会话 → 屏幕/音频推流。
 * 车机上的画面由本机自绘界面（自采自编）产生。
 */
sealed interface LinkState {
    /** 未连接（首页大卡：待机态）。 */
    data object Idle : LinkState

    /** 协商进行中，[step] 为当前步骤的人话描述（秒连链路的可视化）。 */
    data class Negotiating(val step: String) : LinkState

    /** 全链路已建立：[carName] 车机名，[sinceMillis] 建立时刻。 */
    data class Connected(val carName: String, val sinceMillis: Long) : LinkState

    /** 失败：[reason] 人话原因，[recoverable] 是否可自动重试。 */
    data class Failed(val reason: String, val recoverable: Boolean = true) : LinkState

    data object Stopping : LinkState
}

/** 视频通路统计（自绘界面自采自编，帧率 = 我们的编码帧率）。 */
data class VideoStats(
    val fps: Int = 0,
    val frames: Long = 0,
    val kbps: Long = 0,
    val resolution: String = "",
)

/** 音频通路统计。 */
data class AudioStats(
    val playing: Boolean = false,
    val ducking: Boolean = false,
)

/** UI 快照：Repo 每次变更推送给 Compose。 */
data class SessionSnapshot(
    val link: LinkState = LinkState.Idle,
    val video: VideoStats = VideoStats(),
    val audio: AudioStats = AudioStats(),
    val shizukuReady: Boolean = false,
    val targetCar: String? = null,

    // ---- 无 Shizuku 降级模式的引导状态（shizukuReady=true 时全部忽略）----
    /** 降级模式下连车机热点所需的运行时权限是否已授予（12/12L=定位，13+=附近设备）。 */
    val degradedPermissionGranted: Boolean = true,
    /** 降级模式下系统定位服务是否开启（仅 12/12L 相关；13+ 恒 true）。 */
    val degradedLocationOn: Boolean = true,
    /** 降级模式下是否已完成过一次前台首连（系统确认框已批准）。 */
    val degradedFirstConnectDone: Boolean = false,

    /** 调试日志记录开关（关 = 暂停写入环形缓冲，不丢既有内容）。 */
    val logRecording: Boolean = true,
    /** 最近一次导出的绝对路径（null = 尚未导出）。 */
    val lastExportPath: String? = null,
    /** 最近日志（环形缓冲，供「诊断」页展示，无需 adb）。 */
    val recentLogs: List<String> = emptyList(),
)
