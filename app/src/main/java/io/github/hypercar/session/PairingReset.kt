package io.github.hypercar.session

import android.content.Context
import io.github.hypercar.iap2.MfiLengthMemory
import io.github.hypercar.settings.SettingsStore

/**
 * **重置配对身份**：把"我们是谁"和"我们记得这台车机"两边的本机状态一起清掉。
 *
 * 用途（2026-10-29 用户提问后新增）：车机端或手机端手动删除连接后，如果两边的配对记录
 * 彻底不一致（车机仍认为 MFi 已信任、我们这边身份文件也还在），会出现
 * `pair-verify` 失败 + `pair-setup` 被 403 的僵局；以前只能清应用数据重来。
 *
 * 清理项（**只碰配对相关，不动分辨率/车机型号等设置**）：
 *  1. AirPlay 长期身份文件 `filesDir/controller_identity.txt`；
 *  2. 每台接收端的"已配对"标记 `<身份文件>.paired-<接收端公钥指纹>`；
 *  3. 设置里的身份备份 `pairing_identity`（不清的话下次启动会被"自愈"写回，等于没重置）；
 *  4. 记住的 AirPlay 端点 `last_airplay_endpoint`；
 *  5. MFi 挑战长度记忆 `mfi_length_memory`（换新配对后口味可能变）。
 *
 * 重置后下一次连接会**重新生成身份**并走 `pair-setup` 注册成一台"新设备"——
 * 所以车机端也要把本机删掉，否则车机会出现两条同名的旧记录。
 */
object PairingReset {
    /** 身份文件名（与 [HyperCarRepo] / Rust 引擎使用的一致）。 */
    private const val IDENTITY_FILE = "controller_identity.txt"

    /**
     * 执行重置。返回给用户看的一句话摘要。
     * @param log 日志回调（会话层传入，便于把过程记进 App 日志）
     */
    fun run(context: Context, log: (String) -> Unit): String {
        val dir = context.filesDir
        val deleted = mutableListOf<String>()

        val identity = dir.resolve(IDENTITY_FILE)
        if (identity.exists() && identity.delete()) deleted += "身份文件"

        // 标记文件的命名在 Rust 侧是 `{identity_path}.paired-{receiverTag}`
        runCatching {
            dir.listFiles()
                ?.filter { it.name.startsWith("$IDENTITY_FILE.paired-") }
                ?.forEach { if (it.delete()) deleted += it.name }
        }

        val hadBackup = SettingsStore.pairingIdentityBackup.isNotBlank()
        if (hadBackup) {
            SettingsStore.pairingIdentityBackup = ""
            deleted += "身份备份"
        }

        val hadEndpoint = SettingsStore.lastAirplayEndpoint.isNotBlank()
        if (hadEndpoint) {
            SettingsStore.lastAirplayEndpoint = ""
            deleted += "上次端点"
        }

        val mfiCleared = MfiLengthMemory.clearAll(context)
        if (mfiCleared) deleted += "MFi 长度记忆"

        // 「目标车机」列表里的「已配对」标记也要一起清：身份都换了，旧标记会误导。
        val hadPairedCars = SettingsStore.pairedCars.isNotEmpty()
        if (hadPairedCars) {
            SettingsStore.clearPairedCars()
            deleted += "已配对车机标记"
        }

        val summary =
            if (deleted.isEmpty()) {
                "没有需要清理的配对记录（已经是干净状态）"
            } else {
                "已清除：" + deleted.joinToString("、")
            }
        log("PAIR-RESET: $summary")
        log("PAIR-RESET: 下次连接会重新生成身份并走 pair-setup —— 记得在车机端也删除本机后重新配对")
        return summary
    }
}
