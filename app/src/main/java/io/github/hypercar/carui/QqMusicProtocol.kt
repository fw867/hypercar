package io.github.hypercar.carui

internal object QqMusicProtocol {
    fun mode(value: Int): MusicPlaybackMode? = when (value) {
        0 -> MusicPlaybackMode.ALL
        1 -> MusicPlaybackMode.ONE
        2 -> MusicPlaybackMode.SHUFFLE
        else -> null
    }
    fun modeValue(mode: MusicPlaybackMode): Int? = when (mode) {
        MusicPlaybackMode.ALL -> 0
        MusicPlaybackMode.ONE -> 1
        MusicPlaybackMode.SHUFFLE -> 2
        MusicPlaybackMode.SEQUENTIAL -> null
    }
    fun error(code: Int): String = when (code) {
        5, 7, 203, 204 -> "QQ音乐尚未授权此功能"
        2, 9, 11 -> "QQ音乐服务尚未就绪，请稍后重试"
        3 -> "当前QQ音乐版本未开放此功能"
        else -> "QQ音乐接口调用失败（$code）"
    }
}
