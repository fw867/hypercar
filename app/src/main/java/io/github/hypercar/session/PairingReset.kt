package io.github.hypercar.session

import android.content.Context
import io.github.hypercar.settings.SettingsStore

/** Reset one receiver; the session owner serializes this against connections/listeners. */
object PairingReset {
    fun runAll(context: Context, log: (String) -> Unit): String {
        PairingIdentityFiles.resetAll(context.filesDir)
        SettingsStore.forgetAllCarPairings()
        io.github.hypercar.iap2.MfiLengthMemory.clearAll(context)
        log("PAIR-RESET: 用户明确清除全部身份、备份、配对标记、Wi-Fi 关联与 MFi 记忆")
        return "已清除全部配对身份与记录，目标车机已设为未指定"
    }

    fun run(context: Context, mac: String, log: (String) -> Unit): String {
        PairingIdentityFiles.resetReceiver(context.filesDir, mac)
        SettingsStore.forgetCarPairing(mac)
        val summary = "已清除该车机的配对身份与记录"
        log("PAIR-RESET: receiver=$mac；仅清理本车，保留共享身份与其它车机记录")
        log("PAIR-RESET: 本车下次连接生成独立身份；请在该车机端删除旧手机记录后重新配对")
        return summary
    }
}
