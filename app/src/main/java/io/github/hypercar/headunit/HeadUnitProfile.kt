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
     * 是否经过**实车首手验证**。
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

    /** mDNS 拿不到（或不用 mDNS）时的兜底端口，按顺序试连。 */
    val fallbackAirplayPorts: List<Int> = listOf(7000, 7001, 7100, 17000),

    /** 接收端 HID 像素空间（"WxH"）；null = 用 /info 回报或协商尺寸。 */
    val touchDisplay: String? = null,

    /** 首次连接引导里这个车机额外要提示的一句；null = 不提。 */
    val pairingHint: String? = null,
)

/**
 * 内置档案表。新增品牌：在这里加一个 `val`，并在 [all] 里注册。
 */
object HeadUnitProfiles {

    /** 宝马 iD8.5（MGU-86 / MGU-55）：真车实测过的那一套。 */
    val BMW_ID85 = HeadUnitProfile(
        id = "bmw_id85",
        label = "BMW iD8.5",
        model = "MGU-86 / MGU-55",
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
        notes = "接收端会校验 deviceID（SETUP 必须带本机真实蓝牙 MAC）；控制面固定 7000，/info 只认 GET。",
        validatesDeviceId = true,
        sendCarPlayAvailability = false,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        preferMdnsAirplay = false,
        fallbackAirplayPorts = listOf(7000),
        dropProfilesBeforeIap2 = false,
        // 历史 WinPlay 联调的触摸兜底；优先使用 /info 的 widthPixels/heightPixels，非宝马规格。
        touchDisplay = "2732x794",
        pairingHint = "先在 WinPlay 点「开始接收」，再让手机连（它不监听时连不上）",
    )

    /**
     * 奥迪 MIB3 / MIB3 3315 —— **未实车验证**（只有 cardeck 与历史日志的二手结论）。
     *
     * 已知（均来自代码注释里的实测记录）：
     *  - 车机**会发** 0x4301，但可能晚到 2 分 35 秒（MIB3 3310 实测）；
     *  - 控制消息会话 id = 1；0x4E0E 接受但从不 ACK；
     *  - 触摸报告是 5 字节单指（Rust 侧已按长度自适应，无需配置）；
     *  - 0x1D01 可收清单可能只有 5 项、不含 0x5702（清单只能证明"有"）。
     *
     * 未知：是否需要 EA 会话、`/info` 是否只认 POST、AirPlay 端口、
     * 是否校验 deviceID、是否广播 `_airplay._tcp`。首次上车请按
     * `docs/HEADUNIT-PROFILES.md` 的「症状 → 字段」表逐项校准后把 verified 改 true。
     */
    val AUDI_MIB3 = HeadUnitProfile(
        id = "audi_mib3",
        label = "Audi MIB3",
        model = "MIB3 / 3315",
        notes = "未实车验证：车机会发 0x4301（可能晚到 2 分钟以上），控制会话 id=1，触摸为 5 字节单指。",
        verified = false,
        validatesDeviceId = false,
        sendCarPlayAvailability = false,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        preferMdnsAirplay = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100, 17000),
        touchDisplay = null,
        pairingHint = "首次要在 MMI 里选「Apple CarPlay」并确认这台手机（车机会弹提示）",
    )
    /** 内置档案（出厂就有的，断网也一定在）。宝马排第一：它是默认档案。 */
    val builtin: List<HeadUnitProfile> = listOf(BMW_ID85, GENERIC, WINPLAY, AUDI_MIB3)

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






