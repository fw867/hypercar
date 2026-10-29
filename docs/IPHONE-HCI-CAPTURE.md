# iPhone HCI 蓝牙抓包（sysdiagnose）操作手册

真机联调时，「我们的实现对不对」只有**同一个车机对真 iPhone 的行为**能回答。
iPhone 不开放 HCI 口给第三方，但可以用苹果官方的蓝牙调试描述文件让 `bluetoothd`
把 HCI 缓冲池写进 `sysdiagnose`，取出 `bluetoothd-hci-latest.pklg`
（Apple PacketLogger 格式）后用 Wireshark 逐帧看。

本文是**从零到拿到 `.pklg`** 的完整步骤；抓完之后怎么读，见
[NEGOTIATION-GAPS.md §10](NEGOTIATION-GAPS.md#10-真-iphone-hci-对照2026-10-24同一台-bmw-mgu-55)。

---

## 步骤 1：安装苹果官方蓝牙调试描述文件

必须在 **iPhone 自带的 Safari** 里打开（Chrome / 微信内置浏览器不行：`.mobileconfig`
的下载和「已下载描述文件」入口只有 Safari 会正确接管）。

1. 用 Safari 访问苹果官方页面：
   <https://developer.apple.com/bug-reporting/profiles-and-logs/>
   登录**普通 Apple ID 即可**，不需要付费开发者账号。
2. 找到 **Bluetooth** 那一项（标题类似 *Bluetooth for iOS*），点下载 `.mobileconfig`。
   页面打不开时，也可以在 GitHub 搜镜像仓库 `apple-profiles` 取同一个文件。
3. 下载完成手机会提示「**已下载描述文件**」。
4. 打开 **设置** → 顶部「**已下载描述文件**」→ 右上角「**安装**」→ 输入锁屏密码。
   装完**不需要重启**，也不会出现新的 App 图标。

> 装完在 **设置 → 通用 → VPN 与设备管理** 里能看到这条描述文件，
> 名字里带 *Bluetooth*。联调结束后建议在同一位置「移除描述文件」——
> 它会持续记录 HCI 缓冲，多少占空间。

## 步骤 2：上车复现握手，并记下时间点

为了抓到**最完整的 iAP2 + pair-setup 初次握手**，建议先清掉旧配对：

1. iPhone：**设置 → 通用 → CarPlay** → 选中那台车 → 「**忽略这辆车**」。
2. 车机（宝马）：在 iD8.5 的移动设备列表里**删除**这台 iPhone 的配对记录。
3. 重新发起无线 CarPlay 连接，等车机带鱼屏点亮、进入 CarPlay 界面。
4. **记下进入 CarPlay 那一刻的精确时间（精确到分钟，例如 14:25）**——
   后面在时间轴里对齐「哪一段是我们关心的握手」全靠它。

> 只做「重新连接」不清配对，抓到的会是 `pair-verify` 而不是 `pair-setup`；
> 两者报文完全不同，排查「车机为什么发 0x4301 / 为什么回 0xEA03」时前者没用。

## 步骤 3：按键触发 sysdiagnose 打包

进入 CarPlay 界面后再等约 **2~3 分钟**，让缓冲池积累（太早抓会缺少后续心跳）。

同时按住 **音量+、音量−、侧边电源键** 约 **1~1.5 秒**：

- 感觉到**明显震动一下**即可松手；
- 若弹出「滑动来关机」，说明按久了，点**取消**重来——取消不影响已触发的打包。

震动意味着系统已在后台打包**系统级诊断数据 + 蓝牙 HCI 缓冲池**。

## 步骤 4：导出 `.pklg` 到电脑

1. 等 **2~3 分钟**让打包完成（文件有几十到几百 MB，别急着找）。
2. iPhone：**设置 → 隐私与安全性 → 分析与改进 → 分析数据**。
3. 顶部搜索框输入 `sysdiagnose`，找到当天日期命名的
   `sysdiagnose_YYYY.MM.DD_...tar.gz`（时间戳最近的即刚才那次）。
4. 点右上角**分享**，用 **AirDrop / 微信文件传输助手 / 数据线**传到 Windows 解压。
5. 解压后进入 **`logs/Bluetooth/`**，能看到：

```
logs/Bluetooth/bluetoothd-hci-latest.pklg          ← 就是它（可能有带时间戳的同名文件）
logs/Bluetooth/bluetoothd-hci-YYYY-MM-DD-*.pklg    ← 同一次抓包的滚动分片
```

## 步骤 5：用 Wireshark 打开

直接 `File → Open` 打开 `.pklg`（Wireshark 原生支持 Apple PacketLogger 格式），
过滤建议：

| 目的 | 显示过滤器 |
|---|---|
| 只看 iAP2 报文 | `btl2cap` + 按 `frame contains 40:40` 逐条看，或按 CID 过滤 |
| 看链路建立/断开 | `btl2cap.cid` / `btstack` 事件（视版本） |
| 看某条具体报文 | `btl2cap.payload contains 40:40:00:0e`（例：`0x4301` 邀请） |

> macOS 上更顺手的是苹果自家 **PacketLogger**（Additional Tools for Xcode 里），
> 它会把 iAP2 解成结构化参数；Wireshark 只能看原始字节。

## 本项目解析这文件的要点

`.pklg` 不是 pcap，记录容器是：

```
[len u32][ts_sec u32][ts_usec u32][type u8][data…]
```

- `type` 决定 `data` 的语义（HCI 命令/ACL/SCO 事件等）；
- `len`（容器第一个 u32）包含后面的时间戳 8 字节、type 1 字节和 data，**下一条记录位置是 `offset + 4 + len`**，不能用 `offset + 12 + len`。2026-10-29 严格解析现有文件得到 19,886 条记录，恰好消费全部 868,966 字节；旧脚本的跳字节扫描会漏帧或错误归属时间。
- iAP2 CSM 在 ACL 中的形态是 `40 40 | total_len(u16 BE) | msgID(u16 BE) | params…`，`total_len` **包含 6 字节 CSM 头**。实际还要处理 ACL/L2CAP/RFCOMM 和链路分片，不能把一次字节搜索当成完整的协议解码。
- `ts_sec/ts_usec` 应按原始时间戳处理；跨设备时钟未校准时，先比较相对握手起点的间隔，不能先假定它被错误加减过时区。
- 参数按 `[len u16][id u16][value]` 或 TLV8（`<type><len><value>`）解析。

2026-10-29 的重新核对与后台蓝牙干扰分析见 [当日连接排查](BMW-CONNECTION-2026-10-29.md)。旧表位于 [NEGOTIATION-GAPS.md §10.1](NEGOTIATION-GAPS.md#101-数据来源与解析方式)，历史结论需按上述边界重新校验。

## 隐私与合规

- `sysdiagnose` 包裹整个系统：**包含设备名、WiFi 历史、位置、进程列表、账号标识**。
  只把 `logs/Bluetooth/*.pklg` 取出分析，原包分析完就删。
- **`.tar.gz` 与 `.pklg` 一律不入库**（`.gitignore` 已排除 `*.pklg`、`sysdiagnose*`）。
- 写进文档/issue 的片段必须掩码：设备名、SSID、PSK、蓝牙 MAC、传输 UUID、
  车机标识串、IP 末段——本项目文档里这些一律是 `*`。

---

## 进阶：一次抓包同时拿到 Bluetooth + Wi-Fi + CarPlay

苹果的描述文件可以**叠加安装**（互不冲突）：装几个就多几类日志，`sysdiagnose` 会一次性全打包。
所以理想做法是**把三个都装上、只按一次 sysdiagnose**。

| 描述文件 | 装完生效的日志 | 对我们要查的事有用吗 |
|---|---|---|
| **Bluetooth**（`bluetoothd`） | `logs/Bluetooth/bluetoothd-hci-*.pklg`——iAP2 全量字节 | ★★★ 已有结论全靠它 |
| **Wi-Fi** | `logs/WiFi/`（关联/漫游/吞吐/RSSI 细节、`wifi.log`） | ★★ 能看接入车机热点的过程、DHCP 结果、掉线原因 |
| **CarPlay**（若列表中提供） | CarPlay 会话日志（启动、AirPlay 连接、错误码） | ★★★ 能看它拿到的 AirPlay 端点与失败原因 |

> 列表按字母序排：`Bluetooth`、`CarPlay` 在 B/C 段，`Wi-Fi` 在最后。
> 页面很长，直接用浏览器 Ctrl+F 搜名字即可。

### 装多个描述文件的顺序

1. 先装 **Bluetooth**（必须，iAP2 只能靠它）；
2. 再装 **Wi-Fi**（同一个 Safari 页面，下载第二个 `.mobileconfig`）；
3. 若页面里有 **CarPlay**，一并装上；
4. 每个都走「设置 → 已下载描述文件 → 安装」，装完**不需要重启**；
5. 全部装完再上车复现，**只按一次** sysdiagnose（流程同本文步骤 2~4）。

### sysdiagnose 里值得先翻的目录

解压 `sysdiagnose_*.tar.gz` 后，按这个顺序看：

| 路径 | 内容 | 怎么用 |
|---|---|---|
| `logs/Bluetooth/*.pklg` | HCI 全量（iAP2 明文） | 按本文「本项目解析这文件的要点」逐帧看 |
| `logs/WiFi/`（`wifi.log`、`*.log`） | 关联哪个 SSID、DHCP 拿到什么、RSSI/漫游 | 看车机热点到底给了什么地址与网关 |
| `logs/CarPlay/`（若存在） | CarPlay 会话状态机与 AirPlay 连接 | 找 `AirPlay`、`endpoint`、`connect` 关键字 |
| `logs/Networking/` | 路由/接口快照 | 确认手机在车机网段里的 IP/掩码/网关 |
| `system_logs.logarchive` | 统一日志（需 `log show` 或在 Mac 上用 Console 打开） | 按子系统过滤：`com.apple.carplay`、`com.apple.airplay` |

搜索技巧（解压目录里）：

```bash
# CarPlay 相关文件名
find . -iname '*carplay*'
# WiFi 日志里找车机热点与拿到的 IP
grep -ril -e 'DIRECT-' -e 'dhcp' logs/WiFi | head
# AirPlay 端点线索（RTSP/7000/端口）
grep -ril -e 'airplay' -e ':7000' logs | head
```

### 一个必须说清的限制

**iPhone 拿不到 802.11 空口抓包**：
Wi-Fi 描述文件给的是**日志**（关联/吞吐/错误统计），不是 `monitor mode` 的帧抓包。
所以「AirPlay 报文原文」在 iPhone 侧只能从 `pklg`（蓝牙侧）和统一日志里间接看。

若确实需要空口抓 `_airplay._tcp` / RTSP 明文，只有两条路：

1. **Mac + 无线诊断**（Wireless Diagnostics → 窗口 → 嗅探，选信道 157 等），导出 `.wcap`，
   在 Wireshark 里用已知 PSK 解密（`Preferences → Protocols → IEEE 802.11 → Decryption Keys`，
   填 `wpa-pwd` 或 `wpa-psk`）；
2. 支持 monitor 模式的 USB 网卡 + Linux（`airmon-ng`）。

> 顺带：我们自己的 App 每次连接都会从车机 `0x5703` 拿到这个热点的 PSK，
> 所以"抓到了但解不开"这件事不存在——只要能抓到，就一定能解。
