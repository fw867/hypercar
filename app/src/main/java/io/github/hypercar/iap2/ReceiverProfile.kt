package io.github.hypercar.iap2

/**
 * 车机画像（目前只收口「WiFi 凭据交换代际」wifiGen）。
 *
 * 来源与依据：cardeck 0.1.14（commit cbaffc4，2026-10-22 奥迪 MMI 3315 真车定谳）——
 *
 *  - **清单只能证明「有」，不能证明「没有」**：奥迪 3315 的可收清单只有 5 项、不含
 *    `0x5702`，但它实际完整支持 `0x5702 → 0x5703`，而且**要靠收到 0x5702 才把 AirPlay
 *    服务拉起来**（按「清单没声明就跳过」的那版：0 次 0x5702 → 端口 7000 全程
 *    ECONNREFUSED、`_airplay._tcp` 不广播、4 轮会话全灭）。
 *  - 不能按清单数量推断“完整”：2026-10-29 MGU-55 合计 26 项却未声明无线消息，
 *    并不能证明应跳过传统凭据交换。只有明确声明 0x4301 且未声明 0x57xx 时优先新路径。
 *    其它情况保持 UNKNOWN，给传统请求有限窗口，不因未声明就永久禁用。
 */
object ReceiverProfile {
    /** WiFi 凭据交换协议代际。 */
    enum class WifiGen { LEGACY, NEW, UNKNOWN }

    /**
     * 纯函数：按 0x1D01 的收/发消息清单判定凭据交换代际。
     *
     * @param sendIds 车机声明「会发送」的消息（null = 未提供清单）
     * @param receiveIds 车机声明「可接收」的消息（null = 未提供清单）
     */
    fun classifyWifiGen(
        sendIds: Set<Int>?,
        receiveIds: Set<Int>?,
    ): WifiGen =
        when {
            sendIds == null && receiveIds == null -> WifiGen.UNKNOWN

            sendIds?.contains(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true ||
                receiveIds?.contains(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true -> WifiGen.LEGACY

            sendIds?.contains(Iap2MessageIds.CARPLAY_START_SESSION) == true -> WifiGen.NEW

            // 稀疏清单不构成「新协议」证据：保守按旧协议（0x5703 有超时兜底）
            else -> WifiGen.UNKNOWN
        }

    fun describe(gen: WifiGen): String =
        when (gen) {
            WifiGen.LEGACY -> "LEGACY（清单明写 0x5702/0x5703）"
            WifiGen.NEW -> "NEW（声明 0x4301，未声明 0x57xx）——优先等待会话参数"
            WifiGen.UNKNOWN -> "UNKNOWN（没有足够的无线能力证据）——限时尝试 0x5702（不按清单数量猜代际）"
        }
}
