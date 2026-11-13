package io.github.hypercar.core

import org.json.JSONObject

enum class PipelineStatus { NEUTRAL, SUCCESS, ERROR }

/** Observed sender facts; receiver display and speaker output have no acknowledgement here. */
data class PipelineDiagnostics(
    val generation: Long = 0,
    val active: Boolean = false,
    val controlReady: Boolean = false,
    val recordAccepted: Boolean = false,
    val resourcesAccepted: Boolean = false,
    val screenSetupAccepted: Boolean = false,
    val videoChannelReady: Boolean = false,
    val activationFailure: String? = null,
    val wifiIap2: String = "WAIT_SESSION",
    val screenOwned: Boolean? = null,
    val videoStream: String = "WAIT_SETUP",
    val sourceUnits: Long = 0,
    val writtenFrames: Long = 0,
    val audioReady: Boolean = false,
    val audioPackets: Long = 0,
    val signalPackets: Long = 0,
    val uiRequests: Long = 0,
    val rebuilds: Long = 0,
    val timingRequests: Long = 0,
    val timingReplies: Long = 0,
    val timingReplyAgeMs: Long? = null,
    val tcpAckedBytes: Long? = null,
    val tcpQueuedBytes: Long? = null,
    val tcpRetransmissions: Long? = null,
    val tcpSampleAgeMs: Long? = null,
    val timingSendErrors: Long = 0,
    val timingReceiveErrors: Long = 0,
    val tcpState: Int? = null,
    val tcpInfoError: Long? = null,
) {
    // Wi-Fi iAP2 is an independent observation; some receivers do not use it.
    // A sent frame never proves that a receiver has opened its CarPlay desktop.
    val activationLabel: String get() = when {
        !active -> "会话已结束"
        !recordAccepted -> "等待 RECORD"
        !resourcesAccepted -> "等待资源协商"
        !controlReady -> "等待控制就绪"
        videoStream == "PAUSED" -> "主屏已交还车机"
        screenOwned != true -> "等待车机交出主屏"
        !screenSetupAccepted -> "等待视频 SETUP"
        !videoChannelReady -> "等待视频通道"
        writtenFrames == 0L -> "等待首帧写出"
        else -> "视频已写出 · 车机显示待确认"
    }
    private fun status(success: Boolean, error: Boolean = false) = when {
        !active || generation <= 0 -> PipelineStatus.NEUTRAL
        error -> PipelineStatus.ERROR
        success -> PipelineStatus.SUCCESS
        else -> PipelineStatus.NEUTRAL
    }
    val controlStatus get() = status(controlReady)
    val wifiStatus get() = status(wifiIap2 == "READY_TRANSPORT", wifiIap2 in setOf("FAILED", "STOPPED"))
    val videoStatus get() = status(videoStream == "READY", videoStream == "FAILED")
    val audioStatus get() = status(audioReady)
    val timingStatus get() = status(timingRequests > 0 && timingReplies == timingRequests,
        timingSendErrors > 0 || timingReceiveErrors > 0)
    // Queue depth and cumulative retransmits are ordinary TCP observations, not proof of failure.
    val tcpStatus get() = status((tcpAckedBytes ?: 0) > 1,
        tcpInfoError != null || tcpState in setOf(7, 8, 9, 11))
    val timingLabel: String get() = "计时：请求 $timingRequests / 回包 $timingReplies　最近 ${timingReplyAgeMs?.let { "${it / 1000}s前" } ?: "无"}"
    val tcpLabel: String get() = "TCP：确认 ${tcpAckedBytes?.let { "${it / 1024}KB" } ?: "未知"}　队列 ${tcpQueuedBytes?.let { "${it}B" } ?: "未知"}　重传 ${tcpRetransmissions ?: "未知"}"
    val tcpAgeLabel: String get() = "TCP采样：${tcpSampleAgeMs?.let { "${it / 1000}s前" } ?: "暂无"}；确认仅表示对端网络接收"
    val wifiLabel: String get() = when (wifiIap2) {
        "WAIT_SESSION" -> "等待会话"
        "WAIT_PEER" -> "等待车机首包"
        "WAIT_PEER_LONG" -> "尚无车机首包"
        "LINK" -> "链路协商"
        "IDENTIFICATION" -> "设备识别"
        "CERTIFICATE", "CHALLENGE" -> "认证交互"
        "READY_PENDING" -> "等待发送完成"
        "READY_TRANSPORT" -> "传输就绪"
        "FAILED" -> "握手失败"
        "STOPPED" -> "已停止"
        else -> "未知"
    }
    val videoLabel: String get() = when (videoStream) {
        "READY" -> "通道就绪"
        "REBUILDING" -> "重建中"
        "FAILED" -> "通道失败"
        "PAUSED" -> "等待画面归还"
        "WAIT_OWNERSHIP" -> "等待主屏使用权"
        else -> "等待通道"
    }
    companion object {
        fun parse(json: String): PipelineDiagnostics? = runCatching {
            val j = JSONObject(json)
            val generation = j.getLong("generation")
            require(generation > 0)
            val timing = j.optJSONObject("timing")
            val tcp = j.optJSONObject("video_tcp")
            fun JSONObject?.number(key: String): Long? =
                if (this == null || isNull(key)) null else optLong(key).takeIf { it >= 0 }
            PipelineDiagnostics(
                generation = generation, active = true,
                recordAccepted = j.optBoolean("record_accepted"),
                resourcesAccepted = j.optBoolean("resources_accepted"),
                screenSetupAccepted = j.optBoolean("screen_setup_accepted"),
                videoChannelReady = j.optBoolean("video_channel_ready"),
                controlReady = j.optBoolean("control_ready"), wifiIap2 = j.optString("wifi_iap2", "WAIT_SESSION"),
                screenOwned = if (j.isNull("screen_owned")) null else j.getBoolean("screen_owned"),
                videoStream = j.optString("video_stream", "WAIT_SETUP"),
                sourceUnits = j.optLong("video_source_units").coerceAtLeast(0),
                writtenFrames = j.optLong("video_frames_written").coerceAtLeast(0),
                audioReady = j.optBoolean("audio_ready"), audioPackets = j.optLong("audio_packets_written").coerceAtLeast(0),
                signalPackets = j.optLong("audio_signal_packets").coerceAtLeast(0),
                uiRequests = j.optLong("ui_requests").coerceAtLeast(0), rebuilds = j.optLong("screen_rebuilds").coerceAtLeast(0),
                timingRequests = timing.number("requests") ?: 0, timingReplies = timing.number("replies") ?: 0,
                timingReplyAgeMs = timing.number("reply_age_ms"),
                tcpAckedBytes = tcp.number("acked_bytes"), tcpQueuedBytes = tcp.number("outq_bytes"),
                tcpRetransmissions = tcp.number("total_retrans"), tcpSampleAgeMs = tcp.number("age_ms"),
                timingSendErrors = timing.number("send_errors") ?: 0,
                timingReceiveErrors = timing.number("receive_errors") ?: 0,
                tcpState = tcp.number("state")?.takeIf { it <= 255 }?.toInt(),
                tcpInfoError = tcp.number("info_errno"),
            )
        }.getOrNull()
    }
}
