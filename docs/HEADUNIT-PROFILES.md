# 车机档案（HeadUnitProfile）：新增品牌怎么做

> 2026-10-29 核对：档案是兼容策略，不是车型协议规范；旧文中的品牌绝对结论需要限定样本。握手问题和新增 ID7 档案的判断见 [iAP2 握手审计](IAP2-HANDSHAKE-AUDIT-2026-10-29.md)。以运行时最终合并的档案和源码为准，远程档案可能覆盖内置值。

这套协议在不同车机上的差异**不小**，现有档案主要来自特定型号和固件的实测。配置集中在：

```
app/src/main/java/io/github/hypercar/headunit/HeadUnitProfile.kt   ← 档案定义 + 内置档案表
```

会话层（`Iap2SenderSession`）、连接层（`HyperCarRepo`）读取档案；部分历史能力判断仍包含车型经验规则，尚未全部收口。

## 1. 字段含义

| 字段 | 作用 | 怎么判断该填什么 |
|---|---|---|
| `id` | 持久化键（写进 SharedPreferences） | 小写下划线；**定下就别改**，改了用户的选择会丢 |
| `label` / `model` | 设置页与引导卡显示 | 用户认得出的名字 |
| `notes` | 设置页里这个档案的一句话说明 | 写"凭什么这么配"（实测结论） |
| `verified` | 是否经过**实车首手验证**；`false` 会在设置页打「未实测」角标 | 自己没上过实车就别写 `true` |
| `validatesDeviceId` | 接收端会逐字符比对 AirPlay `SETUP` 的 `deviceID` 与本机蓝牙 MAC | 连不上且日志出现 `403`/`deviceID` 相关拒绝 → `true` |
| `sendCarPlayAvailability` | 是否发 `0x4300` CarPlayAvailability（带本机蓝牙传输标识） | **宝马车机必须 `true`**：2026-10-25 现场 A/B——0.27 发它时车机显示 CarPlay 图标；0.29 起按"真 iPhone 不发"关掉后，车机把手机降级成"只有电话/语音的普通蓝牙设备"，图标消失且不再发起 iAP2 |
| `openEaSessions` | 是否开 `0xEA00` EA 会话 | 宝马车机 `true`（0.27 行为）；真 iPhone 一个都不发，但停发与图标消失同时发生，暂按现场 A/B 保留 |
| `sendTransportIdentifier` | 是否发 `0x4E0E` 传输标识 | 同上，宝马 `true`（与 0x4300 携带同一个传输标识） |
| `replyPowerUpdates` | 车机发 `0xAE00` 时是否回 `0xAE01` | 真 iPhone **必回**（3 条）；默认 `true` |
| `dropProfilesBeforeIap2` | 发 iAP2 前是否主动断开该车机的 HFP/A2DP | 当前内置 BMW_ID85 和通用档案为 `true`，WinPlay 为 `false`。这是兼容开关，不能按品牌推导必选值；需核对远程覆盖及实车 A/B，观察是否连带断开 ACL。历史“宝马必须 false”的结论与当前实现不一致 |
| `abortWhenCarPlayWindowClosed` | 0x1D01 判为「档案 A」（车机没开无线 CarPlay 窗口）时是否主动结束会话 | **一律 `false`**：2026-10-25 现场 A/B——0.27 保持链路时车机显示 CarPlay 图标，0.28 加了「档案 A 立即结束」后图标消失、车机不再发起 iAP2（拆链会让车机收起图标）。空转轮次改由「车机新连入顶替」处理，不靠拆链 |
| `preferMdnsAirplay` | 端口策略：`true` = 先查 mDNS `_airplay._tcp` | 车机广播该服务（抓包/Bonjour 浏览可见）→ `true`；普通接收端（WinPlay）→ `false` |
| `fallbackAirplayPorts` | mDNS 拿不到时的试连顺序 | AirPlay 惯例 `7000`，其次 `7001` / `7100` |
| `touchDisplay` | 接收端 HID 像素空间（`"WxH"`） | 车机 `/info` 报 `0x0` 而坐标总是偏 → 填接收端真实像素 |
| `pairingHint` | 首次连接引导里这台车额外的提示 | 例如"首次要在 iDrive 里选成 CarPlay 手机" |

> 默认值混合了历史实测与兼容措施，不应统称为真 iPhone 的标准行为。新档案只覆盖有证据的差异；未经实车验证保持 `verified=false`。

## 2. 新增一个品牌：四步

**① 加档案**

在 `HeadUnitProfiles` 里加一个 `val`：

```kotlin
/** 某车机（型号 X）：一句话说明凭什么这么配。 */
val AUDI_MIB3 = HeadUnitProfile(
    id = "audi_mib3",
    label = "Audi MIB3",
    model = "MIB3 / 8W",
    notes = "车机声明会发 0x4301 但可能晚到 2 分钟以上；端口 7000。",
    validatesDeviceId = false,
    sendCarPlayAvailability = true,   // 这台车声明可收 0x4300（示例）
    openEaSessions = false,
    sendTransportIdentifier = false,
    replyPowerUpdates = true,
    preferMdnsAirplay = true,
    fallbackAirplayPorts = listOf(7000, 7001, 7100),
    touchDisplay = null,
    pairingHint = "首次要在车机的 CarPlay 列表里确认这台手机",
)
```

**② 注册**

```kotlin
val all: List<HeadUnitProfile> = listOf(BMW_ID85, GENERIC, WINPLAY, AUDI_MIB3)
```

设置页与引导卡会自动多出一项（UI 遍历 `all`，不需要改 UI）。

**③ 实测一轮并抓日志**

在设置里切到新档案 → 连接 → 导出日志。重点看这几行判断字段对不对：

| 日志 | 说明 |
|---|---|
| `iap2 跳过 0x4300 … 档案 <id> 不要求` | `sendCarPlayAvailability=false` 生效 |
| `iap2 tx=0x4300 … 档案 <id> 要求且车机可收` | 打开了且车机声明可收 |
| `iap2 跳过 0xea00 EA 会话（档案 <id> …）` | `openEaSessions=false` 生效 |
| `iap2 rx=0xae00 StartPowerUpdates → 回 3 条 0xae01` | `replyPowerUpdates=true` 生效 |
| `MDNS: ✓ … → <ip>:<port>` / `候选端口=[…]` | `preferMdnsAirplay` / `fallbackAirplayPorts` 生效 |
| `AIRPLAY: ✗ GET /info → [RTSP/1.0 455 …]` | 端口或 `/info` 方法不对（先换端口，再看是否需要 POST） |

**③.5 内置的未验证档案**

`Audi MIB3`（`id=audi_mib3`）目前 `verified=false`：只有二手结论支撑

- 车机会发 `0x4301`，但可能晚到 2 分 35 秒（MIB3 3310 实测）；
- 控制消息会话 id = 1；`0x4E0E` 接受但从不 ACK；`0x4300` 会被停止 ACK；
- 触摸报告是 5 字节单指（Rust 侧按长度自适应，无需配置）；
- `0x1D01` 可收清单可能只有 5 项、不含 `0x5702`。

未知项：EA 会话是否需要、`/info` 是否只认 POST、AirPlay 端口、是否校验 deviceID、
是否广播 `_airplay._tcp`。首次上车按第 3 节逐项校准后把 `verified` 改 `true`。

**③.6 通用档案（`id=generic`）**

不认识的车型选它。它是 0.27 与 [`shaonialife/cardeck`](https://github.com/shaonialife/cardeck)
（0.1.28）两份「已跑通」流程的并集。两者之间真正不一致的只有 **档案 A 拆不拆链**；
端点与电源这两项按统一约定取值（0.27 与 cardeck 的方案都归到这一条，不再有「写死 7000」
或「不回电源」的变体）：**AirPlay 端点 mDNS `_airplay._tcp` 优先、拿不到退 7000**；
**`0xAE00` 一律回 3 条 `0xAE01`**。

| 项 | 0.27 | cardeck | 通用档案 |
|---|---|---|---|
| `0x4300` / `0xEA00` / `0x4E0E` | 全发 | 全发 | **全发**（`sendCarPlayAvailability` / `openEaSessions` / `sendTransportIdentifier` 都 `true`） |
| 出站前断 HFP/A2DP | 断，先等 8s 让车机连入 | 断（「只配对、不连接」） | **断**（`dropProfilesBeforeIap2=true`，保留 8s 等待） |
| 档案 A（车机没开无线窗口） | 主动结束会话 | 不拆链 | **不拆**（`abortWhenCarPlayWindowClosed=false`；拆链会丢 CarPlay 图标） |
| AirPlay 端点 | 写死 7000+网关兜底 | mDNS `_airplay._tcp` + 7000 兜底 | **mDNS 优先，拿不到退 7000**（`preferMdnsAirplay=true`、`fallbackAirplayPorts=[7000]`；0.27 写死 7000 遇 `455` 连不上） |
| `0xAE00` → `0xAE01` | 不处理 | 不处理 | **回 3 条真机字节**（`replyPowerUpdates=true`，按真 iPhone 行为补上） |

`verified=true` 的依据：0.27 在宝马 MGU-55 实车跑通（用户现场 A/B），cardeck 在奥迪 MIB3
实测；mDNS 端点发现自 0.29 起在宝马/奥迪路径上实测。未知车型仍建议先抓一轮日志确认
`0x4300` / `0xEA00` 有没有发、`MDNS:` 有没有解析到端口，再决定要不要按第 3 节改字段。

> 要严格复刻 0.27：把 `abortWhenCarPlayWindowClosed` 改 `true`、`preferMdnsAirplay` 改 `false`、
> `replyPowerUpdates` 改 `false` 即可（`fallbackAirplayPorts` 已是 `[7000]`，正好等于 0.27 的写死端口），
> 代码不用动。

**④ 把结论写回 `notes`**

`notes` 是给下一个开发者/用户看的，写清"为什么是这个值"，避免以后再踩一遍。

## 3. 症状 → 该改哪个字段

| 现象 | 先查 |
|---|---|
| 车机上**CarPlay 图标消失**、手机只剩"电话/语音" | ① `sendCarPlayAvailability` 必须 `true`（`0x4300` 带传输标识，车机靠它把设备认成 CarPlay）；② `abortWhenCarPlayWindowClosed` 必须 `false`（拆链会让车机收起图标）；③ **宝马**的 `dropProfilesBeforeIap2` 必须 `false`（断了 HFP/A2DP 会把 ACL 一起拽掉；通用/奥迪档案相反，要 `true`）；④ `0x4301` 等不到**不等于**设备身份有问题——先按上面三条恢复，再让车机重新配对一次 |
| 换了一台**没有对应档案**的车机 | 设置里先选 `通用（0.27）`：0.27 的消息集 + cardeck 的端点发现。仍不通再按本表逐项调，调好后按 §2 加一个专属档案 |
| 车机**根本不发起 iAP2**（日志 `XXXs 内车机一次都没发起 iAP2`） | 就是上一行的①②③之一造成的车机侧降级；代码修复后需要**在 iDrive 里删掉本机重新配对**才会重新认可 |
| 能连上但**触摸点不准** | `touchDisplay`（接收端 `_info` 报 0×0 时必须给） |
| 连不上且日志提示 `403` | `validatesDeviceId` + 设置里手动填真实蓝牙 MAC |
| 车机从不发 `0x4301`、一直等端点 | `preferMdnsAirplay=true`（用 ctrl 邀请 / mDNS 找端点） |
| 车机不回 `0xEA03`、EA 会话无意义 | `openEaSessions=false` |
| 连接后车机反复问电源状态 | `replyPowerUpdates=true` |
| 端口 7000 回 `455` | `fallbackAirplayPorts` 加 `7001`/`7100`，并确认 mDNS 是否可用 |

## 4. 约定

- **不要**在 `Iap2SenderSession` / `HyperCarRepo` 里写 `if (brand == "xxx")`——差异属于档案。
- 真要引入**结构不同**的流程（例如某车机不走 AirPlay 而走 EA 隧道），
  再加一个 `HeadUnitFlow` 接口实现，并让档案引用它；不要推翻现有档案字段。
- 档案默认值 = 真 iPhone 行为。偏离默认值时，只写"确实不同"的那几项，其余留空。
- 新增档案不需要改 Rust 侧；除非差异涉及数据面（例如视频封装格式）。

## 5. 远程下发：新增车型不发版

内置档案（`BMW_ID85` / `GENERIC` / `WINPLAY` / `AUDI_MIB3`）改一次就要重新出包、装 APK。
为了让「加一台车机」变成纯配置动作，App 支持从 GitHub 拉一份 JSON：

```
仓库 profiles/headunits.json
        │  HTTPS（raw.githubusercontent.com，可在设置里改成镜像/私有分支）
        ▼
App「车机型号 → 从 GitHub 更新车型列表」
        │  原文存 SharedPreferences，解析失败就保持旧缓存
        ▼
HeadUnitProfiles.all = 内置 ∪ 远程（同 id 覆盖，远程独有追加）
```

### 5.1 文件格式

顶层可以是 `[ {…}, {…} ]`，也可以（推荐）是带元信息的对象：

```json
{
  "version": 2,
  "_schema": 2,
  "updated": "2026-10-25",
  "profiles": [
    {
      "id": "bmw_id85",
      "label": "BMW iD8.5",
      "model": "MGU-86 / MGU-55",
      "notes": "真车实测：车机从不发 0x4301、从不回 0xEA03…",
      "verified": true,
      "validatesDeviceId": false,
      "sendCarPlayAvailability": true,
      "openEaSessions": true,
      "sendTransportIdentifier": true,
      "replyPowerUpdates": true,
      "dropProfilesBeforeIap2": false,
      "abortWhenCarPlayWindowClosed": false,
      "preferMdnsAirplay": true,
      "fallbackAirplayPorts": [7000, 7001, 7100],
      "pairingHint": "首次要在 iDrive 里把这个设备选成 CarPlay 手机"
    }
  ]
}
```

字段与 §1 的表格一一对应，**除 `id`、`label` 外都可省略**——省略就取**同 id 内置档案**的值
（新车型没有内置条目时才取宝马档案的默认值）。省略 `verified` 视为 `false`（会显示「未实测」角标），
这样远程新增的车型不会被误当成已验证。

> 顶层请带 `"_schema": 2`（当前 `RemoteProfiles.SCHEMA`）。**`_schema` 低于 App 端常量的远程条目
> 只能新增车型、不能覆盖内置档案**——这是 2026-10-25 踩过的坑：内置已把宝马的
> `sendCarPlayAvailability` 等三项改回 `true`（CarPlay 图标修复），但设备上缓存的 v1 JSON
> 仍是 `false`，覆盖后图标继续消失。要修内置档案，必须显式升 `_schema`。

### 5.2 三条覆盖规则

| 情况 | 行为 |
|---|---|
| 远程 id 与内置相同 | **`_schema` ≥ 2 时远程覆盖内置**（修内置的错不用发版，用户点一下就好）；`_schema` 更旧则忽略覆盖，只保留内置值 |
| 远程 id 是新的 | 追加到列表末尾，条目上标「（远程）」 |
| 远程配置拉取失败 / 解析失败 | 保持上次缓存；从没拉过就等于只有内置档案 |

### 5.3 加一台新车机的完整动作

1. 按 §2 的四步在 `HeadUnitProfile.kt` 里加档案（本地先调通）。
2. 把同样的字段补进 `profiles/headunits.json` 的 `profiles` 数组（`verified` 先给 `false`）。
   **改数据类字段时三处必须同步**：`HeadUnitProfile` 定义、`RemoteProfiles.readProfile`（含默认值取
   同 id 内置档案）、`RemoteProfiles.encode`——漏一处就会「内置改好、远程一同步又变回去」。
3. push（纯数据改动，可带 `[skip ci]` 跳过构建）。
4. 用户在手机「车机型号 → 从 GitHub 更新车型列表」点一下，车型就出现了。

实测校准通过后，把该条目的 `verified` 改成 `true` 再 push 一次即可（同样不发版）。

### 5.4 注意事项

- 下载地址可在 `SettingsStore.remoteProfilesUrl` 覆盖（镜像、私有分支、内网 HTTP 测试都行）；
  空值 = `https://raw.githubusercontent.com/fw867/hypercar/main/profiles/headunits.json`。
- **只认配置，不执行代码**：远程档案只是布尔/端口/字符串，不含任何可执行内容。
- `profiles/headunits.json` 里的 `updated` 字段供人看，App 不解析（避免时钟不准导致误判）。
- 远程档案同样写进设置页的下拉列表，顺序：内置优先（保持稳定），远程独有在后。
