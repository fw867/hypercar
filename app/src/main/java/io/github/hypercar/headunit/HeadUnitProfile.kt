package io.github.hypercar.headunit

import io.github.hypercar.settings.SettingsStore

/**
 * 车机「档案」：把**品牌/型号特有的行为差异**集中到一个对象里。
 *
 * 为什么需要它：这套协议在不同车机上差异不小，而且大多是实测出来的——
 * BMW MGU-55 从不发 0x4301、不回 0xEA03；WinPlay 则校验 deviceID、端口固定 7000。
 * 以前这些差异散落在 `Iap2SenderSession`/`HyperCarRepo` 的常量和 if 里，
 * 每加一个品牌都要动主流程。现在：**新增品牌 = 新增一个档案文件 + 注册表一行**，
 * 会话层与传输层只读档案，不再出现品牌名。
 *
 * 约定：字段一律带默认值（由当前兼容策略决定），
 * 这样新加档案时只需覆盖确实不同的那几项。
 */
data class HeadUnitProfile(
    /** 稳定 id（写进 SharedPreferences，改名会丢设置）。 */
    val id: String,
    /** 显示名（设置页、引导卡）。 */
    val label: String,
    /** 型号补充说明（如 MGU-86 / MGU-55 / AirPlay 1）。 */
    val model: String,
    /** 一句话备注：这个档案凭什么这么配（写进设置页帮助文本）。 */
    val notes: String,

    /**
     * 是否已有本项目实车验证或用户明确反馈的实车验证。
     *
     * false 的档案（如奥迪）只有二手结论支撑，设置页会打「未实测」角标：
     * 免得用户以为是"官方支持"，也提醒后续贡献者优先补实测。
     */
    val verified: Boolean = true,

    /** 接收端会逐字符比对 AirPlay SETUP 的 deviceID 与本机蓝牙 MAC（WinPlay 会，BMW 不会）。 */
    val validatesDeviceId: Boolean = false,

    /** 是否发送 0x4300 CarPlayAvailability。 */
    val sendCarPlayAvailability: Boolean = false,

    /** 是否发起 0xEA00 EA 会话。 */
    val openEaSessions: Boolean = false,

    /** 是否发送 0x4E0E 设备传输标识。 */
    val sendTransportIdentifier: Boolean = false,

    /** 车机发 0xAE00 StartPowerUpdates 时回 0xAE01（真 iPhone 必回 3 条）。 */
    val replyPowerUpdates: Boolean = true,

    /**
     * 是否启用目标车机音频配置保护。仅处理已连接的配置文件，
     * 不调用设备级 disconnect；生效条件与节流由 CarProfileGuard/CarProfilePolicy 决定。
     * 宝马经用户实测未改善二次上车，当前关闭；其它档案按各自配置。
     */
    val dropProfilesBeforeIap2: Boolean = true,

    /** false = 不做主动 iAP2 出站拨号；音频配置保护由独立字段控制。 */
    val allowOutboundIap2: Boolean = true,

    /** 能力识别为 CarPlay 窗口未开放时是否主动结束；当前内置档案均为 false。 */
    val abortWhenCarPlayWindowClosed: Boolean = false,

    /** 端点端口策略：true = 先查 mDNS `_airplay._tcp`，false = 直接用 [fallbackAirplayPorts]。 */
    val preferMdnsAirplay: Boolean = true,

    /** Wait for this connection's ctrl invite or iAP2 endpoint; an open port is not activation. */
    val requireAirplayActivation: Boolean = false,

    /** Receiver-specific opt-out of the global cached/scanned Wi-Fi shortcut. */
    val allowProactiveWifiSession: Boolean = true,

    /** Some receivers invite over the already joined Wi-Fi before supplying 0x5703. Requires an explicit target. */
    val advertiseOnCurrentWifi: Boolean = false,

    /** Require a recent ctrl invitation even if iAP2/mDNS already supplied an address. */
    val requireFreshCtrlInvite: Boolean = false,

    /** Keep control/iAP2 alive if screen SETUP is rejected; applies independently of the user experiment. */
    val useScreenResourceLifecycle: Boolean = false,

    /** MBUX-only authenticated screen-before-RECORD bootstrap. */
    val mbuxScreenBootstrap: Boolean = false,

    /** IDC23 resource/HID/screen bootstrap before RECORD; separate from MGU and MBUX. */
    val id9ScreenBootstrap: Boolean = false,

    /** mDNS 拿不到（或不用 mDNS）时的兜底端口，按顺序试连。 */
    val fallbackAirplayPorts: List<Int> = listOf(7000, 7001, 7100, 17000),

    /** 接收端 HID 像素空间（"WxH"）；null = 用 /info 回报或协商尺寸。 */
    val touchDisplay: String? = null,

    /** 首次连接引导里这个车机额外要提示的一句；null = 不提。 */
    val pairingHint: String? = null,
) {
    fun screenResourceLifecycleEnabled(userEnabled: Boolean): Boolean = useScreenResourceLifecycle || userEnabled
}

/**
 * 内置档案表。新增品牌：在这里加一个 `val`，并在 [all] 里注册。
 */
object HeadUnitProfiles {

    /** 宝马 iD8.5（MGU-86 / MGU-55）：真车实测过的那一套。 */
    val BMW_ID85 = HeadUnitProfile(
        id = "bmw_id85",
        label = "BMW",
        model = "ID7-ID9",
        notes = "兼容 MGU-86 / MGU-55；由车机发起 CarPlay，保留系统蓝牙音频连接。首次连接请在 iDrive 确认授权。",
        validatesDeviceId = false,
        // ↓ 这三项 2026-10-25 现场 A/B 恢复为 0.27 值：0.27 有 CarPlay 图标，停发后图标消失
        sendCarPlayAvailability = true,
        openEaSessions = true,
        sendTransportIdentifier = true,
        replyPowerUpdates = true,
        // 2026-11-03 用户实测二次上车无改善，关闭 HFP/A2DP 主动断开以继续对照。
        dropProfilesBeforeIap2 = false,
        allowOutboundIap2 = false,
        abortWhenCarPlayWindowClosed = false,
        preferMdnsAirplay = true,
        requireAirplayActivation = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100, 17000),
        touchDisplay = null,
        pairingHint = "首次要在 iDrive 里把这个设备选成 CarPlay 手机（车机会弹确认）",
    )

    /** IDC23 is kept separate from the existing MGU policy; media compatibility remains experimental. */
    val BMW_ID9 = HeadUnitProfile(
        id = "bmw_id9",
        label = "BMW ID9",
        model = "IDC23（ID9）",
        notes = "IDC23 系列试验配置：保留系统蓝牙音频，接受本轮车机会话参数和 IPv6 端点。按主屏声明、HID、屏幕建链后再激活；AAC 音频仍在适配，尚未实车验证。",
        verified = false,
        validatesDeviceId = false,
        sendCarPlayAvailability = true,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        dropProfilesBeforeIap2 = false,
        allowOutboundIap2 = true,
        preferMdnsAirplay = true,
        requireAirplayActivation = true,
        allowProactiveWifiSession = false,
        advertiseOnCurrentWifi = false,
        requireFreshCtrlInvite = false,
        useScreenResourceLifecycle = true,
        id9ScreenBootstrap = true,
        fallbackAirplayPorts = listOf(7000),
        pairingHint = "先在车机确认 Apple CarPlay 授权；此配置仍在适配中，尚未验证音视频正常。",
    )

    /**
     * 通用（0.27 基线 / cardeck 同款）：不认识的车型先用它。
     *
     * 内容 = 0.27 在宝马 MGU-55 上跑通的序列（`0x4300` + `0xEA00` + `0x4E0E`、
     * 发 iAP2 前断 HFP/A2DP、先给车机 8s 主动连入的机会），与
     * `shaonialife/cardeck`（0.1.28）的通用流程逐项对照后取**并集**：
     *
     * - 两边都发的三条消息 → 全开；
     * - 两边都在出站前断 HFP/A2DP（cardeck `Iap2BluetoothClient` 的「只配对、不连接」）→ `true`；
     * - 0.27 有「档案 A 主动结束会话」、cardeck 没有 → 不保留（本仓库 0.28 起的现场结论也是
     *   拆链会让车机收起 CarPlay 图标，cardeck 同样从不拆链）；
     * - 0.27 端口写死 7000、cardeck 走 mDNS 并退 7000 → 取 cardeck 的：mDNS 优先、兜底只留
     *   `7000`（0.27 写死 7000 时老车机只回 `455 Method Not Valid In This State`）；
     * - 0xAE00 → 0xAE01 两边历史版本都不回，通用按真 iPhone 行为补上（3 条原样重放）。
     *
     * 与 0.27 的差异：档案 A 不拆链、端点改为 mDNS 优先（兜底仍是 7000）、补上 `0xAE01`。
     * 要严格复刻 0.27：`abortWhenCarPlayWindowClosed = true`、`preferMdnsAirplay = false`、
     * `replyPowerUpdates = false`，代码不用动。
     */
    val GENERIC = HeadUnitProfile(
        id = "generic",
        label = "通用（0.27）",
        model = "任意车机 / 未知车型",
        notes = "0.27 基线 + cardeck 通用序列：0x4300/0xEA00/0x4E0E 全发，发 iAP2 前先断 HFP/A2DP，" +
            "不主动拆链；端点先查 mDNS `_airplay._tcp`，拿不到退 7000。" +
            "与 0.27 只差两处：档案 A 不拆链（拆链会丢 CarPlay 图标）、mDNS 优先（0.27 写死 7000，遇 455 连不上）。",
        verified = true,
        sendCarPlayAvailability = true,
        openEaSessions = true,
        sendTransportIdentifier = true,
        replyPowerUpdates = true,
        dropProfilesBeforeIap2 = true,
        abortWhenCarPlayWindowClosed = false,
        preferMdnsAirplay = true,
        fallbackAirplayPorts = listOf(7000),
        pairingHint = "首次要在车机里把本机选成 CarPlay 手机（部分车机会弹确认）",
    )

    /** WinPlay（Windows 上的 AirPlay 接收端，联调/盒子用）。 */
    val WINPLAY = HeadUnitProfile(
        id = "winplay",
        label = "WinPlay",
        model = "PC AirPlay 接收端",
        notes = "接收端会校验 deviceID（SETUP 必须带本机真实蓝牙 MAC）；控制面优先采用 mDNS 公告端口，1.25 使用 17000，旧版 7000 兜底。",
        validatesDeviceId = true,
        sendCarPlayAvailability = false,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        preferMdnsAirplay = true,
        fallbackAirplayPorts = listOf(17000, 7000),
        dropProfilesBeforeIap2 = false,
        // 历史 WinPlay 联调的触摸兜底；优先使用 /info 的 widthPixels/heightPixels，非宝马规格。
        touchDisplay = "2732x794",
        pairingHint = "先在 WinPlay 点「开始接收」，再让手机连（它不监听时连不上）",
    )

    /**
     * 奥迪基础档案：对照 CarDeck 503ed37；尚未完成本项目实车验证。
     * 6318 可能只给 Wi-Fi 凭据，端点由 mDNS 发现；9310 的 0x4301 可能晚到。
     * 0x4300 / 0x4E0E 只发送，不等待 ACK；EA 仅打开车机声明的协议。
     * 音频配置保护不等于断开 RFCOMM，控制链路需保留以接收后续通知。
     * MFi 长度、触摸尺寸及端点继续按协商结果自适应，不按品牌固定。
     */
    val AUDI_MIB3 = HeadUnitProfile(
        id = "audi_mib3",
        label = "Audi MIB3",
        model = "MMI 6318 / 9310 / 3315（参考记录）",
        notes = "未实车验证：保留蓝牙控制链路，兼容仅 mDNS 发现端点及晚到的 0x4301；按车机声明打开 EA 会话。",
        verified = false,
        validatesDeviceId = false,
        sendCarPlayAvailability = true,
        openEaSessions = true,
        sendTransportIdentifier = true,
        replyPowerUpdates = true,
        preferMdnsAirplay = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100, 17000),
        touchDisplay = null,
        pairingHint = "在 MMI 中选择 Apple CarPlay 并确认手机；接受蓝牙与 Wi-Fi 提示，等待车机完成邀请",
    )
    /** VW4131/VW4211 baseline; confirmed connecting by community feedback on 2026-11-07. */
    val VOLKSWAGEN = HeadUnitProfile(
        id = "volkswagen_mib",
        label = "大众 Volkswagen",
        model = "VW4131 / VW4211（ID.7 / 帕萨特 2025）",
        notes = "网友实车反馈连接正常：先选定目标车机并连接车机 Wi-Fi，保持蓝牙与广播，等待车机本轮邀请；不使用无邀请的主动直连。",
        verified = true,
        sendCarPlayAvailability = true,
        openEaSessions = false,
        sendTransportIdentifier = true,
        replyPowerUpdates = true,
        dropProfilesBeforeIap2 = false,
        allowOutboundIap2 = true,
        abortWhenCarPlayWindowClosed = false,
        preferMdnsAirplay = true,
        requireAirplayActivation = true,
        allowProactiveWifiSession = false,
        advertiseOnCurrentWifi = true,
        requireFreshCtrlInvite = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100, 17000),
        pairingHint = "先完成系统蓝牙配对，在「目标车机」选定这台大众车机；首次请手动连接车机 Wi-Fi，并在车机确认 Apple CarPlay。",
    )

    /** MBUX MB_E-P-4: real 0x4301 includes AP and IPv6 endpoint; media still needs validation. */
    val MERCEDES_MBUX = HeadUnitProfile(
        id = "mercedes_mbux",
        label = "奔驰 Mercedes-Benz MBUX",
        model = "MB_E-P-4（MBUX 00081 日志样本）",
        notes = "试验配置：接受本轮 0x4301 的热点和 IPv6 端点，不强等 ctrl 邀请；保留蓝牙音频，配对后认证、主屏建链后激活。音视频尚待实车验证。",
        verified = false,
        validatesDeviceId = false,
        sendCarPlayAvailability = true,
        openEaSessions = false,
        sendTransportIdentifier = true,
        replyPowerUpdates = true,
        dropProfilesBeforeIap2 = false,
        allowOutboundIap2 = true,
        abortWhenCarPlayWindowClosed = false,
        preferMdnsAirplay = true,
        requireAirplayActivation = true,
        allowProactiveWifiSession = false,
        advertiseOnCurrentWifi = false,
        requireFreshCtrlInvite = false,
        useScreenResourceLifecycle = true,
        mbuxScreenBootstrap = true,
        fallbackAirplayPorts = listOf(7000),
        touchDisplay = null,
        pairingHint = "在 MBUX 中选择 Apple CarPlay 并确认手机，接受 Wi-Fi 连接提示；屏幕尺寸按本轮协商获取。此配置尚未验证音视频正常。",
    )

    /** 内置档案（出厂就有的，断网也一定在）。宝马排第一：它是默认档案。 */
    val builtin: List<HeadUnitProfile> = listOf(BMW_ID85, BMW_ID9, GENERIC, WINPLAY, AUDI_MIB3, VOLKSWAGEN, MERCEDES_MBUX)

    /**
     * 全部可用档案 = 内置 + 远程（GitHub 下发的 `profiles/headunits.json`）。
     *
     * 远程同 id 会**覆盖**内置（修内置的错不用发版），远程独有的追加在后面。
     * 没拉过/拉失败就是纯 [builtin]，行为与以前完全一致。
     */
    val all: List<HeadUnitProfile>
        get() = RemoteProfiles.merge(builtin, RemoteProfiles.cached(), RemoteProfiles.cachedSchema())

    /** 按 id 取档案；未知/空值回落到宝马（默认车型）。 */
    fun byId(id: String?): HeadUnitProfile =
        all.firstOrNull { it.id == id } ?: BMW_ID85

    /** 当前设置里选中的档案。 */
    fun current(): HeadUnitProfile = byId(SettingsStore.headUnitProfileId)
}






