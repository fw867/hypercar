# 车机档案

更新：2026-11-07。权威来源是运行代码及有效远程配置，旧注释中的试验建议不作为当前值。

## 内置档案

| ID | 蓝牙音频配置保护 | 主动出站 iAP2 | 等待本轮 AirPlay 激活 | mDNS 优先 | 端口兜底 |
|---|---|---|---|---|---|
| bmw_id85 | false | false | true | true | 7000/7001/7100/17000 |
| generic | true | true | false | true | 7000 |
| winplay | false | true | false | false | 7000 |
| audi_mib3 | true | true | false | true | 7000/7001/7100/17000 |
| volkswagen_mib | false | true | true（且要求新鲜 ctrl 邀请） | true | 7000/7001/7100/17000 |
| mercedes_mbux | false | true | true（接受本轮 0x4301，不强等 ctrl） | true | 7000 |

BMW / generic / Audi 启用 sendCarPlayAvailability、openEaSessions、sendTransportIdentifier；WinPlay 关闭这三项。全部档案均回复电源更新，且不因 CarPlay 窗口关闭立即主动中止。WinPlay 校验 deviceID，并保留历史触摸兜底 2732×794；这不是宝马的固定分辨率。

BMW MGU-55 是当前实车基准。Audi 尚未完成本项目实车验证；generic 的 verified 字段也不代表所有车型均已测试。未知 ID 回退到 BMW。

实现：[HeadUnitProfile](../app/src/main/java/io/github/hypercar/headunit/HeadUnitProfile.kt)。

## 远程配置

所有车型统一保存在一个文件中，App 按档案 ID 读取对应条目。配置源为 [headunits.json](../profiles/headunits.json)，解析与合并见 [RemoteProfiles](../app/src/main/java/io/github/hypercar/headunit/RemoteProfiles.kt)。

当前 schema 为 8。达到当前 schema 的同 ID 远程档案覆盖内置档案；旧 schema 仅追加新的 ID，不覆盖内置值，也不为内置条目显示“远程”角标。省略字段继承对应内置档案；新 ID 使用 BMW 模板。刷新失败保留缓存，但旧缓存仍受 schema 隔离。

仓库下发配置已与内置连接策略对齐：BMW 的 dropProfilesBeforeIap2=false、requireAirplayActivation=true，端口兜底包含 17000。诊断仍应记录有效档案及来源，因为自定义配置可以显式覆盖这些值。

当前支持的策略字段包括：validatesDeviceId、sendCarPlayAvailability、openEaSessions、sendTransportIdentifier、replyPowerUpdates、dropProfilesBeforeIap2、allowOutboundIap2、abortWhenCarPlayWindowClosed、preferMdnsAirplay、requireAirplayActivation、fallbackAirplayPorts、touchDisplay，以及显示信息与 pairingHint。

requireAirplayActivation 已补齐解析与导出：省略时继承，显式 false 可关闭门槛。pairingHint 与 touchDisplay 省略时也继承；显式空字符串可清除。导出携带当前 _schema，保留策略及可选字段的清除状态。已废弃的 keepIap2AliveOnly 从下发配置移除，不恢复其旧行为。

新增车型先记录接收端角色、邀请、服务、触摸和日志证据，再修改对应字段；不要因另一车型文档中的固定端口、Session ID 或时间阈值就宣称已适配。

2026-11-03：用户实测主动断开宝马 HFP/A2DP 未改善二次上车，关闭该策略以继续对照。内置与仓库配置同步关闭，schema 升至 5，阻止旧缓存重新启用；其它内置车型策略沿用。

## 大众档案（2026-11-07，网友实车反馈连接正常）

`volkswagen_mib` 对应 mcarplay 已记录的 VW4131 / VW4211（ID.7 / 帕萨特 2025）线索，不代表所有大众或所有 MIB 车机兼容。它启用 0x4300、0x4E0E 和电源更新，不发 BMW 专用 EA 探测，保留系统蓝牙音频连接。

新增策略字段：

- `allowProactiveWifiSession`：默认 true；大众为 false，防止全局主动 Wi-Fi 开关跳过邀请。
- `advertiseOnCurrentWifi`：默认 false；大众为 true，必须显式选定目标车机，才在已连接 Wi-Fi 上提前绑定并广播 ctrl，解决首次 0x5703 尚未到达的等待环。
- `requireFreshCtrlInvite`：默认 false；大众为 true，只有 20 秒内的新鲜 ctrl 邀请可启动媒体。20 秒来自 mcarplay 的 VW4211 观察，并非协议常量。0x4301 只作为地址/凭据更新，不替代该邀请；连接前后再次检查，失效候选 FD 会被关闭。视频 TCP 建立前失败后保留广播与蓝牙，等待另一条邀请，不复用已消耗邀请。

该次修改中其它四个档案保持默认值，BMW 已配对 Wi-Fi 延时重试继续有效。schema 升至 6，防止旧缓存覆盖新门槛；内置和仓库下发配置一致。配置统一见 [headunits.json](../profiles/headunits.json)，对照与限制见 [VOLKSWAGEN-ADAPTATION-20261106.md](VOLKSWAGEN-ADAPTATION-20261106.md)。

## 奥迪基础档案（2026-11-06，未实车验证）

对照 CarDeck `503ed3730d5a9365272294b79306cea5fcce115b`，启用 0x4300、0x4E0E 和按车机声明的 EA 会话。前两项不等待 ACK，EA 应答缺失不阻断后续流程。6318 可能仅提供热点凭据而不发 0x4301，9310 的 0x4301 可能晚到 2 分 35 秒，因此保留 mDNS 优先、晚到通知处理，不套用大众的新鲜 ctrl 邀请门槛。保留蓝牙音频配置保护与 RFCOMM 控制链路。

不按品牌固定网关、显示尺寸、触摸范围或 MFi 挑战长度；以实际协商和自适应结果为准。schema 升至 7，避免旧缓存重新关闭三项消息。配置统一见 [headunits.json](../profiles/headunits.json)，证据和限制见 [AUDI-ADAPTATION-20261106.md](AUDI-ADAPTATION-20261106.md)。


## 奔驰 MBUX 试验配置（2026-11-07）

新增 `mercedes_mbux`，样本为 Mercedes-Benz MB_E-P-4 / MBUX 00081，`verified=false`。接受本轮 iAP2 下发端点，不把大众新鲜 ctrl 邀请要求套到奔驰；不走无本轮参数的主动 Wi-Fi 捷径，不主动断 HFP/A2DP，不使用 PC 触摸兜底或 BMW bootstrap。启用车机明示可收的 0x4300/0x4E0E，保留电源应答，不主动打开 EA。

新增可选字段 `useScreenResourceLifecycle`：默认 false，奔驰为 true。引擎值为档案值 OR 用户“画面资源联动”开关；不改写用户保存值。用于屏幕协商失败时保留控制链路，不能据此宣称屏幕 400 已解决。远程 JSON 支持继承、显式覆盖和导出，添加奔驰时 schema 为 7；后续大众转正升至 8，防止旧缓存恢复未验证标识。详情见 [奔驰分析与配置](MERCEDES-MBUX-ADAPTATION-20261107.md)。


2026-11-07：按用户转述的大众车主实车反馈，将 volkswagen_mib 标记为 verified=true；仅更新验证标识及说明，连接门槛、消息、端口、广播、重连策略不变。反馈未提供具体车型/固件与各项功能清单，不据此宣称全部大众车型及功能均已验证。奔驰仍为试验配置。
