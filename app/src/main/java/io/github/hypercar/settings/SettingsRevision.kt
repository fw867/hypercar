package io.github.hypercar.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 设置项的「变更计数器」，供 Compose 感知**跨区块**的设置变化。
 *
 * 为什么需要：`SettingsStore` 是普通 object（SharedPreferences），不是 Compose 状态。
 * 典型场景：用户在上方「车机型号」里切换了档案，我们顺手清除「目标车机」，
 * 但下方 `TargetCarSection` 不会重新组合（它的输入没变），界面还显示旧车名。
 * 写入方调用 [bump]，读取方在组合里读 [value] 即可触发刷新。
 */
object SettingsRevision {
    var value by mutableIntStateOf(0)
        private set

    /** 通知所有读 [value] 的界面：设置已变，请重新读 `SettingsStore`。 */
    fun bump() {
        value++
    }
}
