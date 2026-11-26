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
     * 是否已有对应接收端的连接实测记录或明确反馈。
     *
     * 尚无实测的档案在设置页显示「未实测」角标。
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

    /** NBT Evo only: negotiate audio before the single successful RECORD. */
    val nbtAudioFirst: Boolean = false,

    /** mDNS 拿不到（或不用 mDNS）时的兜底端口，按顺序试连。 */
    val fallbackAirplayPorts: List<Int> = listOf(7000, 7001, 7100, 17000),

    /** 接收端 HID 像素空间（"WxH"）；null = 用 /info 回报或协商尺寸。 */
    val touchDisplay: String? = null,

    /** 首次连接引导里这个车机额外要提示的一句；null = 不提。 */
    val pairingHint: String? = null,

    /** 远程档案生效所需的最低 App versionCode，含本版本。 */
    val minVersionCode: Int? = null,

    val listing: HeadUnitListing = HeadUnitListing(),
) {
    /** Pin ID6 to its own audio-first bootstrap; stale caches cannot mix receiver strategies.
     * Mercedes keeps the MB_D-PP reference policy, including MB_E-P models. */
    fun connectionPolicy(): HeadUnitProfile = when (id) {
        "bmw_id6" -> copy(nbtAudioFirst = true, mbuxScreenBootstrap = false,
            id9ScreenBootstrap = false, useScreenResourceLifecycle = true)
        "mercedes_mbux" -> copy(nbtAudioFirst = false, mbuxScreenBootstrap = false,
            id9ScreenBootstrap = false, useScreenResourceLifecycle = true)
        else -> if (nbtAudioFirst) copy(nbtAudioFirst = false) else this
    }

    /** Current Mercedes media proposals reflect the implemented H.264/UDP sender. */
    val mbuxMediaCompat: Boolean get() = id == "mercedes_mbux"

    val protocolProfileId: String get() = if (id == HeadUnitProfiles.MOTORCYCLE_ID) "bmw_id85" else id
    val skipBluetoothHandoff: Boolean get() = id == HeadUnitProfiles.MOTORCYCLE_ID

    /** Issue #115: isolate the M2PRO audio SETUP rejection without changing screen arbitration. */
    val airplayAudioEnabled: Boolean get() = id != HeadUnitProfiles.MOTORCYCLE_ID

    fun screenResourceLifecycleEnabled(userEnabled: Boolean): Boolean =
        id == "mercedes_mbux" || useScreenResourceLifecycle || userEnabled
}

/**
 * 内置档案表。新增品牌：在这里加一个 `val`，并在 [all] 里注册。
 */
object HeadUnitProfiles {
    internal const val MOTORCYCLE_ID = "motorcycle_generic"


    /** 宝马 iD8.5（MGU-86 / MGU-55）：真车实测过的那一套。 */
    val BMW_ID85 = HeadUnitProfile(
        id = "bmw_id85",
        label = "BMW",
        model = "ID7-ID8.5",
        listing = HeadUnitListing(brandId = "bmw", brandName = "宝马 BMW", platformName = "MGU",
            aliases = listOf("MGU-55", "MGU-86", "iDrive 7", "iDrive 8", "iDrive 8.5", "ID7", "ID8", "ID8.5")),
        notes = "由车机发起无线 CarPlay，按 MGU 流程从蓝牙交接到 Wi-Fi。",
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
        // Box video callbacks follow resource ownership independently of RECORD.
        useScreenResourceLifecycle = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100, 17000),
        touchDisplay = null,
        pairingHint = "首次要在 iDrive 里把这个设备选成 CarPlay 手机（车机会弹确认）",
    )

    val MOTORCYCLE = BMW_ID85.copy(
        id = MOTORCYCLE_ID,
        label = "通用摩托车",
        model = "突破者 M2PRO",
        listing = HeadUnitListing(category = HeadUnitCategory.MOTORCYCLE, brandId = "alienrider",
            brandName = "突破者 AlienRider", vehicleModels = listOf("M2PRO"), aliases = listOf("BREAK OUT")),
        notes = "沿用 BMW 连接流程，保留蓝牙并跳过车机蓝牙交接命令。当前测试版不传输声音，用于验证 M2PRO 连接稳定性。",
        pairingHint = "在车机上选择 Apple CarPlay，使用已有的手机配对连接。",
    )

    /** NBT Evo has its own stream order; never apply this to MGU or IDC23. */
    val BMW_ID6 = HeadUnitProfile(
        id = "bmw_id6", label = "BMW ID6", model = "NBT Evo",
        listing = HeadUnitListing(brandId = "bmw", brandName = "宝马 BMW", platformName = "NBT Evo",
            aliases = listOf("iDrive 6")),
        notes = "按 NBT Evo 的声明协商音频，音频与主屏就绪后激活会话。",
        sendCarPlayAvailability = true,
        openEaSessions = false, sendTransportIdentifier = false,
        dropProfilesBeforeIap2 = false, allowOutboundIap2 = false,
        requireAirplayActivation = true,
        useScreenResourceLifecycle = true, nbtAudioFirst = true,
        pairingHint = "适用于 NBT Evo，先在车机确认 Apple CarPlay 授权；MGU 和 IDC23 请使用各自配置。",
    )

    /** IDC23 uses its own handshake and screen setup order. */
    val BMW_ID9 = HeadUnitProfile(
        id = "bmw_id9",
        label = "BMW ID9",
        model = "IDC23（ID9）",
        listing = HeadUnitListing(brandId = "bmw", brandName = "宝马 BMW", platformName = "IDC23",
            aliases = listOf("iDrive 9")),
        notes = "支持 IPv6 端点，主屏与 HID 就绪后激活会话。",
        validatesDeviceId = false,
        sendCarPlayAvailability = true,
        openEaSessions = false,
        sendTransportIdentifier = false,
        replyPowerUpdates = true,
        dropProfilesBeforeIap2 = false,
        allowOutboundIap2 = true,
        preferMdnsAirplay = true,
        requireAirplayActivation = true,
        advertiseOnCurrentWifi = false,
        requireFreshCtrlInvite = false,
        useScreenResourceLifecycle = true,
        id9ScreenBootstrap = true,
        fallbackAirplayPorts = listOf(7000),
        pairingHint = "先在车机确认 Apple CarPlay 授权，等待车机发起连接。",
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
        listing = HeadUnitListing(category = HeadUnitCategory.GENERAL),
        notes = "适用于未单独适配的车型。优先通过 mDNS 发现端点，找不到时尝试 7000 端口。",
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
        listing = HeadUnitListing(category = HeadUnitCategory.RECEIVER, aliases = listOf("Windows", "DiPlay")),
        notes = "控制端口优先采用 mDNS 公告，接收端校验手机真实蓝牙 MAC。",
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
     * 奥迪 MIB3 档案：有实车连接反馈，兼容 mDNS 和晚到的端点。
     * 6318 可能只给 Wi-Fi 凭据，端点由 mDNS 发现；9310 的 0x4301 可能晚到。
     * 0x4300 / 0x4E0E 只发送，不等待 ACK；EA 仅打开车机声明的协议。
     * 音频配置保护不等于断开 RFCOMM，控制链路需保留以接收后续通知。
     * MFi 长度、触摸尺寸及端点继续按协商结果自适应，不按品牌固定。
     */
    val AUDI_MIB3 = HeadUnitProfile(
        id = "audi_mib3",
        label = "Audi MIB3",
        model = "MMI 6318 / 9310 / 3315",
        listing = HeadUnitListing(brandId = "audi", brandName = "奥迪 Audi", platformName = "MIB3"),
        notes = "保留蓝牙控制链路，支持通过 mDNS 发现端点和处理晚到的 0x4301。",
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
        listing = HeadUnitListing(brandId = "volkswagen", brandName = "大众 Volkswagen", platformName = "MIB",
            vehicleModels = listOf("ID.7", "帕萨特 2025"), aliases = listOf("Passat 2025")),
        notes = "选定目标车机后，保持蓝牙和车机 Wi-Fi，等待本轮 CarPlay 邀请。",
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
        advertiseOnCurrentWifi = true,
        requireFreshCtrlInvite = true,
        fallbackAirplayPorts = listOf(7000, 7001, 7100, 17000),
        pairingHint = "先完成系统蓝牙配对，在「目标车机」选定这台大众车机；首次请手动连接车机 Wi-Fi，并在车机确认 Apple CarPlay。",
    )

    /** Issue #122 observed the C5 X Marelli receiver using 0x5703, a ctrl invite, and TCP 7000. */
    val CITROEN = HeadUnitProfile(
        id = "citroen_generic",
        label = "雪铁龙 Citroën 通用",
        model = "无线 CarPlay",
        listing = HeadUnitListing(
            brandId = "citroen", brandName = "雪铁龙 Citroën", platformName = "My Citroën / Marelli",
            vehicleModels = listOf("凡尔赛 C5 X"), aliases = listOf("Citroen", "C5X", "CARLINE_1"),
        ),
        notes = "参考凡尔赛 C5 X 连接日志。保留蓝牙，等待车机激活；未提供控制端口时尝试 7000。",
        verified = false,
        sendCarPlayAvailability = true,
        sendTransportIdentifier = true,
        dropProfilesBeforeIap2 = false,
        requireAirplayActivation = true,
        fallbackAirplayPorts = listOf(7000),
        pairingHint = "先完成手机蓝牙配对并在 HyperCar 启动连接，再从车机的 Apple CarPlay 或 Mirror Screen 中选择这台手机。",
    )

    /** MB_D-PP reference policy replaces the unsuccessful MB_E-P-4 experiment; keep the saved ID. */
    val MERCEDES_MBUX = HeadUnitProfile(
        id = "mercedes_mbux",
        label = "奔驰 Mercedes-Benz",
        model = "MBUX（MB_D-PP / MB_E-P）",
        listing = HeadUnitListing(brandId = "mercedes", brandName = "奔驰 Mercedes-Benz", platformName = "MBUX"),
        notes = "保留蓝牙，画面和音频按车机声明协商，整轮会话只发送一次 RECORD。",
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
        advertiseOnCurrentWifi = false,
        requireFreshCtrlInvite = false,
        useScreenResourceLifecycle = true,
        mbuxScreenBootstrap = false,
        fallbackAirplayPorts = listOf(7000),
        touchDisplay = null,
        pairingHint = "在车机确认 Apple CarPlay，并按提示连接车机 Wi-Fi。",
    )

    /** 内置档案（出厂就有的，断网也一定在）。宝马排第一：它是默认档案。 */
    val builtin: List<HeadUnitProfile> =
        listOf(BMW_ID85, BMW_ID6, BMW_ID9, GENERIC, MOTORCYCLE, WINPLAY, AUDI_MIB3, VOLKSWAGEN, CITROEN, MERCEDES_MBUX)

    /**
     * 全部可用档案 = 内置 + 远程（GitHub 下发的 `profiles/headunits.json`）。
     *
     * 远程同 id 会**覆盖**内置（修内置的错不用发版），远程独有的追加在后面。
     * 没拉过/拉失败就是纯 [builtin]，行为与以前完全一致。
     */
    val all: List<HeadUnitProfile>
        get() = catalog().profiles

    internal fun catalog(): RemoteProfiles.Catalog = RemoteProfiles.mergeCatalog(builtin, RemoteProfiles.cached())

    internal fun selection(): RemoteProfiles.Selection =
        catalog().select(SettingsStore.headUnitProfileId.ifBlank { BMW_ID85.id })

    /** 按 id 取档案；未知/空值回落到宝马（默认车型）。 */
    fun byId(id: String?): HeadUnitProfile =
        all.firstOrNull { it.id == id } ?: BMW_ID85

    /** 当前设置里选中的档案。 */
    fun current(): HeadUnitProfile = byId(SettingsStore.headUnitProfileId)
}






