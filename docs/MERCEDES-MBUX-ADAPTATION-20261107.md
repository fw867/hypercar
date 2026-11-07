# 奔驰 MBUX 试验配置与 2026-11-07 日志分析

## 同步基线

从 `51807dd` 快进到 `a222dad`，含车机激活策略、奥迪/大众档案校正及远程字段支持。未回滚这些策略。当前添加 `mercedes_mbux`，显示为“奔驰 Mercedes-Benz MBUX”，目标样本为 `MB_E-P-4 / MBUX 00081`，`verified=false`，不代表所有奔驰车型通用。

## 三份日志的关系与结论

来源为 Downloads 下的 `hypercar-log-20261107-062534.txt`、`062736.txt`、`063056.txt`。三份均标注 App 0.1.0.512、Xiaomi 24031PN0DC、Android 17、Shizuku=false；版本号不能直接对应本机独立构建编号。前两份的带时间戳行完全包含于最后一份，不能当成三次独立测试累加。

最后一份去重后有 8 次引擎配置、8 次初始资源声明 200、7 次屏幕 SETUP 400、3 次后继重试在资源声明应答前关闭；无 ctrl GET 邀请、无 AUDIO_SETUP 事务。引擎内部重启会增加事务数，不能把 3 次后继 EOF 当成另外 3 次上车。

确定事实：

- 蓝牙日志识别 Mercedes-Benz / MB_E-P-4。主要为车机连入 BT-S，也有一次 BT-C 成功；可以保留出站能力，但无须主动断系统 HFP/A2DP。
- 车机清单声明会发 0x4301、可收 0x4300/0x4E0E，未声明旧式 0x5703。真实 0x4301 内包含热点与 IPv6 地址/端口；日志里“先拿到 0x5703”的上层文案不能证明该消息真的出现。
- pair-verify、会话 SETUP、/info、RECORD、初始资源声明都可完成。资源应答完整解析为 `Dict([])`，只表示 RTSP 200，未提供业务拒绝码。
- 7 份屏幕请求均由分段 hex 按 offset 还原、验证字节数与 SHA-256，再用独立 plistlib 解码。请求行都是 `SETUP / RTSP/1.0`，参数均为 streams/type=110、正数 streamConnectionID、相同 /info 显示器 UUID、latencyMs=25。7 次应答都是 400、空体，因此没有业务理由可继续解析。
- screen_resource_lifecycle=false 时屏幕失败会退出引擎并原地重启。Wi-Fi iAP2 此时通常仍在 LINK/IDENTIFICATION/CERTIFICATE，随引擎退出被取消；后继连接有时在资源声明阶段 EOF。这种退出掩盖后续业务，不代表已证明车机必须等到 Wi-Fi iAP2 READY 才接受屏幕。
- 本轮从未进入音频 SETUP，不能用它验证昨天修复的 IPv6 音频 URI 是否解决了音频 400。不能将今日失败继续归咎于同一个已修 URI。
- 0x4301 后多次出现 Wi-Fi 加入降级/超时，控制启动可被延迟约 60 秒；Shizuku=false 和系统连接权限降级需要单独现场确认。未在本次配置里加入强制切换网络或反复重置配对身份。

## 配置行为

| 项目 | 奔驰配置 | 依据/边界 |
| --- | --- | --- |
| 启动依据 | 等待本轮 ctrl 或新 iAP2 端点；不强制 ctrl | 样本有 0x4301，但没有 GET 邀请；不可照搬大众邀请门槛 |
| 无邀请的已知 Wi-Fi 主动捷径 | 此试验配置禁用 | 暂无 MBUX 无本轮参数的成功证据；宝马原主动恢复保持不变 |
| 蓝牙会话 | 保留入站及出站能力，不主动断 HFP/A2DP | 样本两种方向均能进入识别；不引入 generic 的音频配置保护 |
| iAP2 可用性/传输标识 | 发 0x4300 和 0x4E0E | 车机明示可收；不把 WinPlay 的省略策略当成奔驰要求 |
| EA | 不主动打开 | 不引入 BMW 通用 EA 探测 |
| 电源应答 | 保留 | 按现有协议应答 |
| 屏幕生命周期 | 此配置启用 | 屏幕协商失败时只回收屏幕资源，保留控制和 Wi-Fi iAP2 |
| PC/BMW 特例 | 均不启用 | 不带 WinPlay 的 PC 触摸兜底，也不进入 BMW auth/bootstrap |
| 分辨率、触摸 | 由本轮 /info 与实际视频源协商 | 不固定为日志中的 1624×1278 或 PC 的 2732×794 |
| 控制端口 | 优先真实端点；兜底 7000 | 日志明示端口 |

`useScreenResourceLifecycle` 是新增档案字段，远程解析、导出和内置列表均支持；未写时继承同 id 内置配置，新 id 保持默认 false。实际引擎值为“档案启用 OR 用户实验开关”，不改写用户保存的实验开关。设置页在档案强制启用时显示已开启及说明，切回其他车型恢复原有开关状态。未修改远程 schema：新增字段可省略并继承默认，既有 schema 7 配置仍可按原规则覆盖。

此功能保留后续协商机会，不会自动证明屏幕 400 已解决；不新增定时假让屏、强制 requestUI 或未验证的延时重试。若车机没有后续 UI/资源请求，画面仍可能停在失败状态，下一步需取得这份配置下的完整日志。

## 代码与验证

- `HeadUnitProfiles.MERCEDES_MBUX` 与 `profiles/headunits.json` 发布条目保持一致。
- 新增 4 项 JVM 测试，覆盖 0x4301 激活策略、媒体/蓝牙特例隔离、生命周期对其它车型与用户开关的影响、远程继承与覆盖/导出。
- 同步发现 `45bbe83` 已删除 Showcase 独立 wire 测试向量，但仍保留引用该文件的测试模块；补清理失效的模块及入口，恢复原生测试编译。未删除其他控制/媒体协议测试。
- 完整 release JVM：220 passed / 0 failed / 0 errors / 0 skipped；release Lint：0 Error / 44 Warning / 2 Information。
- Android arm64 原生 release 编译通过；接入手机后运行完整原生测试，82 passed / 0 failed / 1 ignored。测试二进制已移除。
- APK 最终版本 0.1.0.563 / 10563 构建、签名校验通过，APK 内原生库与最新编译输出一致。SHA-256：`5b7c56ffa67397d9469a4590f4a1922bc1cf31b3eb0170dacbd56641000f6689`。
- 已按用户要求自动覆盖安装到 HyperCar 手机 31a37894。初次 561 因手机已有 562 被系统拒绝，未降级或清除数据；随后将同一份已验证代码重打包为 563，安装 Success 并核对版本。未提交或推送代码。实车仍须验证音视频，配置保持“未实测”标识。

## 下次测试

在奔驰手机中选择“奔驰 Mercedes-Benz MBUX”，在车机确认 Apple CarPlay，接受系统 Wi-Fi 提示。保持控制链路至少几十秒再导出日志，关注屏幕 400 后 Wi-Fi iAP2 能否继续交换、是否出现 requestUI/changeModes，以及后续媒体 SETUP 的应答。不要因为画面未出现就立即反复重启整个引擎。

本机用于安装验证的手机是用户的 HyperCar 手机，不是这位网友的奔驰测试手机；安装成功不能替代 MBUX 实车验证。


## 补充：更新后的 winplay.txt（06:37:38 导出）

2026-11-07 再次读取同一路径，文件已更新为约 506KB / 4681 行，不能复用之前 11 月 5 日版本的结论。新增 06:33:38–06:37:38 这轮日志。虽然用户描述为连接 WinPlay，但原文件 4070 行 iAP2 身份仍为 MBUX 00081 / MB_E-P-4 / Mercedes-Benz，且 4383 行 car_ip 为该奔驰 IPv6；`profile=winplay` 表示所选配置，不是 Windows WinPlay 接收端身份。这份文件没有提供实际 Windows WinPlay 接收端的独立会话证据。

最后一轮的“成功但无音画”由三个独立失败构成：

1. 06:36:35.831 RECORD 成功、.841 初始资源应答 200，但 .871 屏幕 SETUP 400、空体。屏幕-only TEARDOWN 200 后保留控制，screen_setup=false / video_tcp_ready=false / tcp_frames=0。
2. .886 音频 URI 已修正为带方括号的 IPv6，但 .906 音频 SETUP 仍返回空体 400，audio_ready=false / rtp_packets=0。这是“URI 修复不足以消除奔驰音频 400”的直接证据。此处仍为 WinPlay 配置的 desktop=true / audioLatencyMs=250；不能仅凭这点断言换时延就一定成功。
3. .916 只记录已发送 CarWith 入口启动请求，随后直到导出，手机本地 7240 持续 Connection refused，未出现七路连接、认证完成或视频 AU；source_units=0。与 11 月 5 日“CarWith 已持续输出 AU、但媒体被拒”不同，这轮手机端视频源也没有就绪。应另核对 CarWith 版本/入口 Activity 实际启动与后台启动限制，日志不能确定是哪一个原因。Shizuku=false 是环境事实，不代表 CarWith 必须依赖 Shizuku。

06:37:37.913 的最终事实为 control=true、record/resources=true、screen_setup/video_tcp_ready=false、wifi_iap2=READY_TRANSPORT、video=FAILED、source_units/tcp_frames=0、audio_ready=false、rtp_packets=0。Wi-Fi iAP2 传输就绪没有让媒体自动恢复，requestUI 始终为 0。

当前代码 Rust connected 回调映射为 LinkState.Connected，首页显示“会话已连接”；画面资源联动使媒体失败时控制仍可存活。因此这里表示控制层连接，不代表实际投屏或声音成功。原文件头同样是 outcome=CONNECTED，而不是 STREAMING。需要在产品状态中明确展示媒体失败/源未就绪，不能把单个连接状态当作媒体成功。

后续建议：用新 mercedes_mbux 配置排除档案混用，再采集完整 SETUP 与车机能力；并独立解决 CarWith 本地入口未监听的问题。两边都需验证，单纯切换自绘源不会消除车机媒体 SETUP 400。本轮仅检查日志和补充文档，未修改代码或重新安装。
