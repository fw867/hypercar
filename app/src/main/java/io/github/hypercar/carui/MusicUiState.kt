package io.github.hypercar.carui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 音乐卡的「显示音乐 App」开关（2026-11-01）。
 *
 * 之前这个状态是 MusicCard 内部的 remember，Dock 的音乐按钮没法触发它，
 * 结果点 Dock 会把音乐 App 拉到别的地方（甚至占住地图的画面）。
 * 提到模块级状态后，Dock、卡片右上角 ⤢、关闭按钮共用同一个开关。
 */
internal object MusicUiState {
    /** true = 音乐卡里显示音乐 App 画面；false = 常规音乐卡。 */
    var appView by mutableStateOf(false)
}
