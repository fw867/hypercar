# BMW 无画面及短时断连：HyperCar、盒子日志与固件对照

## 范围和结论

基于 HyperCar `67e357f`，分析用户导出的 0.1.0.406 日志。三份导出有大量重叠，合并去重后观察到 7 次 RECORD 成功，不能把每份导出当成独立试验。

当前失败已经越过蓝牙初次握手、入网、pair-verify、会话 SETUP 和 RECORD；至少部分会话的音频已实际播放。主要排查方向应转向会话激活后的 Wi-Fi iAP2 和资源仲裁，而非继续试 MFi 挑战长度或增加车型档案。

**确认缺口不等于确认唯一根因。** 已证实 HyperCar 丢弃 `iAPSendMessage`、`changeModes`；盒子在同一宝马成功建立 Wi-Fi iAP2，固件也包含对应状态机。现有 HyperCar 日志没有记录这些命令的数据或仲裁参数，尚不能证明每个入站 iAP 命令都是探测重传，也不能单凭断连时间断言具体超时来源。

本次仅增加分析报告，未修改运行时代码、未安装 APK、未刷写盒子。原始日志、固件及解包程序保存在本机，不随报告入库；本文不收录网络密码、设备序列号、蓝牙地址和认证原文。

## 输入

- `hypercar-log-20261030-201348.txt`、`201413.txt`、`201617.txt`。
- 盒子两组 `085004`、`090040` 的 `blueware`、`xiap2`、`zlink`、`zsv` 日志。
- `F:/update.img`，110840394 字节，SHA-256：`4b7dc91d4ee353a9f1f1acc6ae2c0e69b94b598355379545362856e6a27dff8e`。
- CatPlay `fc52bac7910a07ff3b6d94712175db6013528aa9`。
- 用户指定的 shaonialife/cardeck 上游 `503ed3730d5a9365272294b79306cea5fcce115b`，重新读取实际实现，未以本地 fw867 fork 的注释作为结论。

盒子本次两组识别信息均确认 BMW MGU-55；此前同名文件曾是 WinPlay 样本的判断不适用于本次文件内容。文件名里的日期不能与手机墙钟直接对齐；以下盒子时间均为各自日志内的相对时间。

## HyperCar 的关键一轮

取 `201617` 导出最后一轮，方便重现查阅：

| 手机时间 | 事实 |
|---|---|
| 20:15:32.330 | 会话 SETUP 成功，事件口 5001、UDP 保活口 5020；本轮已取得显示能力 |
| 20:15:32.343 | 主动 modesChanged 获 200 |
| 20:15:32.387 | RECORD 获 200，进入 connected |
| 20:15:32.479 | 车机要求 forceKeyFrame |
| 20:15:32.499 | iAPSendMessage 被记录为未处理；随后约 1 Hz 重复出现 |
| 20:15:32.736 | Rust 收到首个视频缓冲，NAL 类型含 SPS/PPS/IDR `[7,8,7,8,5]` |
| 20:15:33.343 | feedback 首次获 200 |
| 20:15:35.143、35.199、36.824 | 三次 changeModes 均未处理 |
| 20:15:34–39 | 编码侧约 30 fps，音频约 136/137 包每秒；这些统计不能证明车机已成功解码视频 |
| 20:15:39.728 | 来向 requestUI 未处理；没有参数，不能判断是否由车端操作触发 |
| 20:15:40.484 | 蓝牙 RFCOMM 先关闭 |
| 20:15:40.554–.567 | 控制及事件连接 EOF |
| 20:15:40.605 | 视频写失败上报 ended |

这一轮 RECORD 到断连约 8.2 秒；20:15:08 那轮也是约 8.2 秒。另有重连轮次不足 1.2 秒。说明既有较稳定的短超时形态，也存在车机尚未恢复时快速复连失败，不能统一称为一个固定的 10 秒心跳问题。

## 盒子成功路径

`090040` 的 xiap2 日志：

| 相对秒 | 事实 |
|---|---|
| 32.404 | 宝马蓝牙 iAP2 建链 |
| 37.537–38.314 | pair-setup、pair-verify、auth-setup |
| 38.323–38.459 | SETUP、会话内 info、RECORD |
| 38.340 | 启动 Wi-Fi iAP2 收发 |
| 38.544 | WLAN 收到 `FF 55 02 00 EE 10` 并回探测标记 |
| 39.389 | WLAN SYN，控制会话 ID=1/version=2；零重传/ACK 超时，与蓝牙参数不同 |
| 39.397–39.425 | WLAN 1D00→1D01→1D02，随后 AA00/AA06/AA01/AA02；不能只完成链路 ACK 就停 |
| 39.429 | 视频启用，屏幕资源归 controller，音频资源仍为 accessory |
| 39.434–39.454 | 视频 SETUP 成功，dataPort=6030 |
| 39.459 | 启动 H.264 2732×794@60 |
| 39.960–39.968 | 主动结束蓝牙阶段；CarPlay Wi-Fi 会话继续 |
| 204、232 | 仍有视频/音频操作记录，远长于 HyperCar 当前短时会话 |

`085004` 后半段也有相同 WLAN iAP2 和视频启动流程。它前半段经历一次 bt_detected 重启，不能把整段都当作一次无误成功连接。

两组成功路径均记录了 pair-setup，不是第二组仅凭已有身份直接 pair-verify 的纯快重连样本。需要保留这个证据边界，不能仅依据文件顺序修改 HyperCar 的身份凭据逻辑。

zlink/zsv 还有手机进入盒子的 CarLife 会话。`wireless_wait_wifi fail` 等部分失败发生在这一侧，时间上晚于盒子到宝马的 CarPlay 成功；不能归因为宝马 AirPlay 失败。

## 固件只读检查

外层 `RKFW`，内层 `RKAF` 位于 `0x6ea26`，包含 9 个分区项。rootfs 是 UBI/UBIFS（77856768 字节），并非普通 Android APK 包。已按分区表边界检查后解出普通文件，原镜像未修改。

本地目录：`D:/devtools/hypercar-box-firmware-20261030`。Windows 未创建部分 BusyBox 等符号链接，不影响已解出的普通 ELF 文件；没有尝试启动这些 ARM 程序。

关键程序：

- `usr/bin/xiap2`：842272 字节、ARM32 EABI5、保留大量动态函数符号；内嵌 `(Aug  8 2026 11:16:11)` 与日志构建标记相同，但没有设备上原二进制的哈希，不能声称完全同一二进制。
- `usr/lib/libAirPlay.so`、`libAirPlaySupport.so`、`libzjairplay2.so`。
- `usr/bin/zlink_server`、`libzlink.so`、`libzlink-main.so` 处理盒子另一侧业务。
- `etc/init.d/S37CarPlay` 同时启动 xiap2、zlink_server，并设置最小系统日期；可解释日志日期与实际采集日期可能不同，不能据文件名认定录制于八月。

反汇编交叉证据（地址为本固件 ELF 虚拟地址）：

- `eventCommand` `0x45df4`、`eventCommandHandle` `0x4797c` 确有业务命令分派，不是只回 HTTP 200。
- `eventCommand` 的 `0x4719c` 调用 `makeModeChangesFromDictionary`，`0x471a8` 调用 `ResourceManager_AccessoryChangeModes`，`0x471c4` 调用 `AirplayClientSendCmdModesChanged`。证明盒子会处理仲裁并反馈状态；不应无条件用固定字典覆盖车机资源所有权。
- `_iap2_sim_wifi_send_ext_message` `0x6bf70` 调用 `iAP2LinkGetSessionForService`、`iAP2LinkQueueSendData`，说明 Wi-Fi 业务数据经过真正的链路引擎，不是裸 CSM 直接发 RTSP。
- 只有出现函数符号/字符串不能证明某次连接一定执行该分支；WLAN 握手实际执行由日志补证。出站究竟使用控制还是事件通道，应继续由调用链/抓包核验，不仅依据字符串。

## 应修正的实现缺口及证据强度

### 1. Wi-Fi iAP2 完全未接入：最高优先级

当前 `airplay.rs::spawn_event_dispatcher` 把 iAPSendMessage 放入 unknown 分支。RTSP 层自动回 200 只是运输层应答，不等于回应内部 DETECT/SYN/CSM。蓝牙 iAP2 保活不能替代这条独立链路。

应把 `params.data` 接入每轮独立的 Wi-Fi iAP2 引擎，支持分片/粘包、DETECT、协商会话 ID、序号/重复帧、识别、认证交换及订阅回应；禁止把字节送给旧蓝牙会话或硬编码控制 ID=10。参考上游实际入站为事件通道，控制通道具备出站实现；车机特定路径需由现场确认，不能在两个通道无界重复试发。

车机 1 Hz 重复消息与约 8 秒失败相符，但由于原日志没保留 `params.data`，具体重试内容和因果仍需修正后验证。先加安全摘要：字节数、DETECT/SYN/ACK/CSM 类型、控制 ID、阶段和耗时；不打印密码、证书、挑战签名等完整内容。

### 2. changeModes 未回应：同为最高优先级

最后一轮三次未处理已直接证实。需处理请求的资源变更和状态反馈；当前启动时发一次 modesChanged 并获 200，不覆盖后续车端仲裁请求。需要区分主屏和主音频，不能简单照抄盒子的主动断蓝牙操作或每次把屏幕、音频都抢归 controller。

### 3. 视频时钟原点不一致：确定实现差异，因果待证

`SpikeCaster.kt` 直接传 MediaCodec `presentationTimeUs`；延迟统计把它与 `System.nanoTime()` 比较。`engine.rs` 非零 PTS 原样使用，而 `timing.rs::monotonic_nanos()` 是 Rust 进程内 Lazy Instant 起算的相对时间，音频同步也使用后者。两者原点并不统一。

应统一系统单调时钟，或显式校准映射并处理切源/重启。当前 VideoConfig 只设置 Encrypted，没有 RespectTimestamps；接收端可能忽略视频 PTS，因此不能单凭这个代码差异认定本次黑屏必由排播时钟导致。后续日志应记录 PTS 与 timing 的差值而非密钥/密文。

### 4. 重连丢首个配置/IDR：存在竞态

日志有两轮 Rust 第一帧仅 `[1]`（P 帧），并非每轮都如此。`engine.rs` 在 ScreenSender 尚未交付时直接丢视频；交付又等待音频协商结束。Kotlin 即使在源头等待过 IDR，仍可能在 Rust 未就绪期间丢掉它。`screen.rs` 无 VideoConfig 时会继续静默丢帧。

应在发送器真正就绪时请求 IDR，保留有效 SPS/PPS，并等待 IDR 后才开始新轮视频。需要记录配置实际写出、首个 IDR 实际写出和尚未就绪丢帧数；单凭“编码器 fps=30”无法证实这些步骤。

### 5. 保活与断连诊断不足

盒子声明 keepAliveSendStatsAsBody=1；HyperCar 能从 info 自动采用该模式，但日志只证明 UDP 首包及 feedback 首次 200。屏幕保活写失败被忽略，feedback 后续出错直接退出。需要记录采用的 opcode、累计成功/失败、最近成功时刻和错误，才能区分解码失败、虚拟链路协商超时和实际保活失效。

## 暂不据此改动的内容

- 不新增车型档案：本车就是当前已匹配的 MGU-55/iD8.5，缺的是公共会话业务，不是一个车型名字。
- 不限制 60 fps 或强制原生 2736×800：现有编码侧 30 fps 基本稳定，没有证据证明带宽/编码压力是主要失败点。
- 显示能力分层已被确认：display=2736×800；viewArea=2736×798；safeArea=2732×794、原点(4,4)。此前 2732×794 推测对应安全区，不是 info 没下发能力。安全区适配需要单独做，不能把 4 像素边界等同于当前整屏无画面的已证实原因。
- 不照搬盒子关闭蓝牙/HFP/A2DP：盒子的控制器和手机数字钥匙环境不同。应先完成 Wi-Fi iAP2 接管，再验证蓝牙退场策略。
- auth-setup 是盒子额外差异，但现有 HyperCar 已获 RECORD、音频已播放，不能据接口存在就认定它是唯一必需修复。
- 不重置现有手机配对身份；没有证据显示当前失败发生在 pair-verify。

## 参考源码

- [shaonialife/cardeck VirtualIap2Link](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/airplay/VirtualIap2Link.kt)
- [shaonialife/cardeck 事件处理](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/SessionController.kt)
- [CatPlay 视频帧构造](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/carplay/catplay_carplay/src/screen/screen_frame_ext.rs)
- [CatPlay 视频加密](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/carplay/catplay_carplay/src/screen/screen_frame_codec.rs)
- [Firefly Rockchip 固件封装说明](https://wiki.t-firefly.com/ROC-RK3328-CC/fw_pack_unpack.html)

下一轮验证应分开观察：Wi-Fi iAP2 NORMAL/识别完成、资源仲裁有回应、VideoConfig/IDR 实际写出、连续保活结果、车机实际出画面及稳定存活。尚未实车验证这些修正，不能将本报告标为“已修复”。

用户后续明确仅对照盒子与宝马之间的 CarPlay，会话流程、固件调用链的进一步核对和两组语义序列比对见 [宝马盒子 CarPlay 状态机](BMW-BOX-CARPLAY-FLOW.md)。手机到盒子的 CarLife 不再作为 HyperCar 修复目标。
