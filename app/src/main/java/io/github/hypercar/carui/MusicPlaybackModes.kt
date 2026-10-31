package io.github.hypercar.carui

enum class MusicPlaybackMode(val label: String) {
    SEQUENTIAL("顺序播放"), ALL("列表循环"), ONE("单曲循环"), SHUFFLE("随机播放")
}

internal object MusicPlaybackModes {
    fun current(repeat: Int, shuffle: Int): MusicPlaybackMode? = when {
        shuffle > 0 -> MusicPlaybackMode.SHUFFLE
        repeat == 1 -> MusicPlaybackMode.ONE
        repeat == 2 || repeat == 3 -> MusicPlaybackMode.ALL
        repeat == 0 -> MusicPlaybackMode.SEQUENTIAL
        else -> null
    }

    fun available(repeatSupported: Boolean, shuffleSupported: Boolean, repeat: Int, shuffle: Int): List<MusicPlaybackMode> = buildList {
        // A repeat-only session with shuffle already enabled cannot switch to a non-shuffle mode.
        if ((shuffle <= 0 || shuffleSupported) && repeatSupported) {
            add(MusicPlaybackMode.SEQUENTIAL); add(MusicPlaybackMode.ALL); add(MusicPlaybackMode.ONE)
        }
        if (shuffleSupported) {
            if (!repeatSupported) add(current(repeat, 0) ?: MusicPlaybackMode.SEQUENTIAL)
            add(MusicPlaybackMode.SHUFFLE)
        }
    }
}
