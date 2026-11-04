# 宝马盒子流程证据

更新：2026-11-02。这里的盒子是日志样本中的手机内容源；不要与“手机连盒子 Wi-Fi、盒子 USB 连车机”的无线接收器混为一谈。

## 样本

分析过 blueware、xiap2、zlink、zsv 的两组完整连接及一次二次上车日志。文件时间与手机墙钟不直接等同，比较相对时序。

原固件样本大小 110840394 字节，SHA-256：
`4b7dc91d4ee353a9f1f1acc6ae2c0e69b94b598355379545362856e6a27dff8e`。

二次上车 xiap2 样本 SHA-256：
`bec8f90cf70e8e37376ea43d67521706e14135d57ad2249ba8d43b2352a0707c`。
原始文件不随仓库分发，不能依赖开发机下载目录作为长期文档链接。

## 二次上车已观察顺序

| 相对时间（秒） | 事件 |
|---|---|
| 48.695 | 车机发起蓝牙连接，使用已有配对 link key |
| 49.419 → 49.470 | 5702 请求 → 5703 凭据 |
| 49.772 | Wi-Fi 连接 |
| 49.801 | 控制服务发布 |
| 49.990 | 发现 AirPlay 服务 |
| 51.598 | 收到控制连接邀请 |
| 51.606 | pair-verify，本次没有重新 pair-setup |
| 52.102 → 52.231 | SETUP → RECORD |
| 52.327 | videoSetup |

因此该样本的二次上车仍有车机发起的蓝牙与新邀请，不能推导成“只要自动连 Wi-Fi 就直接恢复旧 AirPlay 会话”。

## 协议证据

蓝牙 Detect、链路协商、认证、识别、Wi-Fi 凭据与 AirPlay 依次推进；Wi-Fi iAP2 建立独立链路。两次完整样本的 40 条非 ACK WLAN 消息 ID 顺序一致，但部分日志仅打印前 16 字节，不能声称所有字段完全一致。

抓到的 Wi-Fi iAP2 承载为事件 /command 的 iAPSendMessage；出站走控制通道。资源变化经过模式处理再回报 modes，屏幕和音频归属可变化。某样本 videoSetup 在 39.605 秒、Wi-Fi 认证 AA03 在 39.853 秒，证明该样本没有把后者作为视频的硬前置。

独立链路参数、认证顺序和显示尺寸只能证明样本行为；不得把某次 Session ID、端口或 2732×794 尺寸硬编码成所有宝马规范。auth-setup 在样本出现也不证明所有接收端强制需要它。

## 2026-11-04：与 PlayPort type 130 隧道复核

用户重新提供了二次上车的 blueware、xiap2、zlink、zsv 四份原始文本日志。已独立复制备份，
逐文件核对 SHA-256，并保存备份清单。xiap2 文件为 50632 字节，哈希与上面的二次上车样本一致。
重新执行 `tools/summarize-box-iap2.py` 得到 76 条链路记录，其中 45 条 WLAN 记录；部分报文仅有
16 字节前缀，不补齐、不重放。以下时间均为该盒子日志的相对秒数。

| 时间 | 原始 xiap2 日志观察 |
|---|---|
| 52.102 → 52.117 | SETUP；返回 eventPort=5001、timingPort=6020、alivePort=5020 |
| 52.118 → 52.120 | 启动 WLAN iAP2 接收与 HTTP event 客户端 |
| 52.226 → 52.231 | RECORD 完成 |
| 52.327 | 主屏视频 SETUP 完成，返回 dataPort=6030 |
| 52.351 | 车机先发 Detect `FF550200EE10`，盒子回应 Detect |
| 52.356 → 52.361 | 车机发 SYN，盒子发 SYN+ACK，车机回 ACK |
| 52.362 → 52.382 | 盒子发 1D00，收到 1D01；再进行 AA00/AA06/AA00/AA01/AA02 |
| 52.404 | 已开始发送视频；此时 WLAN 挑战响应尚未返回 |
| 52.804 | 收到 AA03，发送 AA05，随后开始状态问候和订阅回应 |

原始日志没有 `clientTypeUUID`、`seed`、`DataStream`、iAP DataStream UUID 或可识别的 type 130
流协商记录。zsv 中的数字 130 属于蓝牙状态行，不是 AirPlay 流类型。这些是应用文本日志，
并非完整解密的 RTSP/TCP 抓包，不能仅凭关键词缺失断言网络上绝对没有其他流。

固件交叉证据（本地 `xiap2-annotated.txt`）：`eventCommand` 在 0x45fa8 比较
`iAPSendMessage`，从 `params.data` 取字节，并在 0x46074 调用 `pushWirelessData`；
`AirPlaySendiAPMessage` 在 0x49274 构造同名命令，由 `_AirplayClientSendCmd` 发送
控制通道 `POST /command`。这与现有 HyperCar 的事件入站、控制出站承载方向一致。
固件虽含 DataStream 加密常量，但它们位于通用 `simGetStreamSecurityKeys`，亦由音视频路径调用，
不能把这些字符串当作本轮使用 type 130 iAP 隧道的证据。

对照 [PlayPort 的隧道适配](https://github.com/youcci/playport/blob/9a0882dd0ffe48e467b59d58b12d81391df55ade/protocol/src/main/java/com/shilapi/xcertplay/airplay/AirPlayIapTunnelStream.kt)，
其手机到车机方向使用 type 130 DataStream，反向使用事件命令；这是另一种承载实现。
不能据此把当前宝马故障定性为缺少 type 130，也不应直接让手机端抢先发 SYN。
盒子成功日志明确支持车机先发 Detect/SYN、手机侧应答的顺序。下一步差异应继续检查本轮蓝牙
交互、新控制邀请及资源状态，而非未经协商改用另一套承载。

完整格式说明见[协议](PROTOCOL.md)。这些资料证明了一条可工作的顺序，未证明 HyperCar 下午未被邀请的唯一原因。
## 2026-11-04：按“点击图标无法进入 CarPlay”重新核对

用户澄清：有 CarPlay 已连接图标，中午有音频，但点击图标不能跳转到 CarPlay 桌面；
这不是已进入 CarPlay 后观察到的黑屏。历史结论中的“黑屏”应按此修正，不能据此认定解码失败。

分段依据：`hypercar-log-20261104-193658.txt` 第 13806 行的 535 / 10:36:54 启动之后为当天数据；
`hypercar-log-20261104-104125.txt` 第 23626 行的 526 / 16:18:29 启动之后、527 启动之前为前一天实车成功对照。
两份导出含重叠历史，不合并重复计数，也不将前一天 18 点与当天 18 点混用。

| 阶段 | 前一天成功对照（526） | 当天不能进入界面（535） |
| --- | --- | --- |
| 蓝牙及邀请 | 18:05:31.152 车机 RFCOMM 连入；18:05:31.896 新 ctrl 邀请，后续两轮也有新邀请 | 当天段未记录新 ctrl 邀请，主动 Wi-Fi 会话 `invite=false` |
| 请求进入 | 18:07:24.523 收到 requestUI | 10:38:48.348、18:05:57.549、18:11:23.633 等均收到 requestUI；当天段共 30 次 |
| 手机本地资源与反馈 | requestUI 本地 claim accepted=true，modesChanged 收到 RTSP 200 | 同样有本地 claim 和 RTSP 200；不证明车机已经切换界面 |
| 视频 | 18:07:25.790 配置 2560x744 和首 IDR | 10:38:50.579 拉伸配置/首 IDR；18:05:58.474 关闭拉伸的 2560x744；18:11:23.644 自绘 2736x800 |
| 后续交互 | 18:07:29.299 起收到 HID，18:07:55.983 到报告 #20 | 当天段未记录 HID；与用户无法进入界面的描述一致，但单靠缺少 HID 不能证明显示状态 |

当天 18:11:23.633 本地 claim → 23.638 modesChanged RTSP 200 → 23.644 视频配置/首 IDR，
28.678 累计写出 293 帧、38.758 写出 885 帧。23.633 之后仍在 25.481、25.798、34.842 等时刻重复 requestUI。
说明进入请求已到达手机、视频已经向 socket 写出，尚不能定位车机接收、解码或界面切换哪一环未完成。
无法把所有 requestUI 一一对应到用户点击，车机也可能自动重试。

资源方向没有一律反转：当天 18:11:47.043 接受车机借屏为 screen=2/1，48.539 接受车机接管为 2/2，
49.648 requestUI 后重新取得本地画面资源并等待 IDR，49.691 首 IDR 恢复。
10:40:16.262、18:12:15.421 确有拒绝借屏，但都出现在更早的多次 requestUI 和写视频之后，
不能据此解释所有首次无法进入。526 至 535 的模式仲裁代码没有行为变更。

另外发现当天有未处理的 changeUIContext（10:39:54.674 起、18:11:37.160 起），
但前一天成功对照 18:05:37.712 也出现过，不能仅凭此定为根因。
现有代码对未知命令也尝试回 RTSP 200；“未处理”表示缺少业务处理，不表示没有运输层应答。

下一次定向采集需记录：requestUI 的 URL/界面类别、changeUIContext 的已知上下文字段、
进入请求的实际应答写出完成时间，以及关联的资源快照。现有空 body 应答只在写失败时记日志，
无法从旧日志复原它与后续 modesChanged 的精确写出顺序；537 尚未补齐这部分。
`UI_STARTED` 仅表示手机 CarUiCaster 已启动，不是车机界面切换成功；
`record=true`、本地 claim accepted、RTSP 200 和 TCP 写帧也都不是显示确认。

目前可确认的主要路径差异仍是新一轮蓝牙业务与 ctrl 邀请；这是优先实车对照的变量，
并非已证明的根因。保留 537 尺寸一致性基线，同时将界面激活作为独立问题继续核查。

### changeUIContext 对照（公开源码，2026-11-04）

核对版本：cardeck `503ed3730d5a9365272294b79306cea5fcce115b`（远端 HEAD 未变）；
mcarplay 旧本地快照为 `a3a4117`，本次 fetch 并通过 git show 核对最新远端
`9dd589413bf1806d6a20f6e1a6b96f848a86e477`，没有改动参考目录的工作树。

- cardeck：AirPlayClient 事件读取先 respondEventRequest，再调用 onCommand。
  应答为空 body 的 RTSP 200，存在请求 CSeq 时回显。SessionController 对 changeUIContext
  无专门分支，走 else 记录命令及参数，不为它额外发送 modesChanged 或重建流。
- 最新 mcarplay：同样先回空 body RTSP 200，再分发；changeUIContext 仍无业务专用分支，
  记录命令参数。其 uiContext 能力声明不等于实现了 changeUIContext 的具体导航行为。
- HyperCar：rtsp.rs 对未知请求也尝试空 body RTSP 200，存在 CSeq 时回显；
  airplay.rs 只记录未知 type，未保留该命令的上下文。因此“未处理”并非没有回应；
  目前缺少的是定向上下文与应答成功日志，不能从这些参考实现推导必须抢屏或发送某种额外确认命令。

更相关的新差异来自最新 mcarplay 的 requestUI，而非 changeUIContext：
ModeArbiter.onUserRequestedUi 在屏幕已经归手机时返回 retake；SessionController.enqueueModesAssert
先 assertModes(releaseScreen=true)，成功后等 200ms，再报告实际手机所有权并补关键帧。
releaseScreen 仅修改主屏报文为 entity/permanentEntity=2，音频和记录的本地所有权不变。
在此前收到车机从手机拿走屏幕的 live changeModes 后，另标记 screenRelinquished，
后续允许的 requestUI 可执行屏幕流 TEARDOWN、重新 SETUP/open、RECORD，保留 CarWith 与音频。
这属于项目针对大众 VW4211 会话记录的恢复方案与单测覆盖，不能当作宝马兼容性已验证。

HyperCar 当前 request_ui 对已经归手机的屏幕仍只声明相同的归属并请求关键帧，
不会制造 2→1 的声明变化，也不会重建屏幕流。当天首个 requestUI 前通常为 2/2，
因此现有逻辑已产生首次 2→1；“重复 requestUI 但本地已是 1”的阶段才对应 mcarplay 的 retake 条件。
该恢复动作比为 changeUIContext 虚构业务含义更值得单独试验，但尚不能解释全部首入失败。

源码：
[cardeck 事件应答](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/airplay/AirPlayClient.kt)、
[cardeck 分发](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/SessionController.kt)、
[mcarplay 仲裁](https://github.com/wangxso/mcarplay/blob/9dd589413bf1806d6a20f6e1a6b96f848a86e477/app/src/main/java/com/mcarplay/app/airplay/ModeArbiter.kt)、
[mcarplay 恢复动作](https://github.com/wangxso/mcarplay/blob/9dd589413bf1806d6a20f6e1a6b96f848a86e477/app/src/main/java/com/mcarplay/app/SessionController.kt)。

### 盒子对 changeUIContext 的实际代码分支

继续核对同一 xiap2 ELF，已找到接收分支，不再仅依据字符串存在判断：

- `eventCommand` 的常量基址为 `0xb4284`；偏移 1108 的 CFL 字符串对象 `0xb46d8`
  内联内容为 `changeUIContext`（对象头 8 字节后是文字）。`0x46f28..0x46f3c` 与请求 type 做 CFEqual。
- 命中后 `0x46f40..0x46f6c` 读取 `params`、`url`、CFStringGetCStringPtr；
  `0x46f80..0x46fa8` 取时间并 fprintf 记录，`0x46fac` 跳通用应答入口 `0x45efc`。
  此分支没有调用 ResourceManager，也没有额外请求接管屏幕。
- 通用 RTSP 分支 `0x45f44..0x45f58` 以 `RTSP/1.0` 和 status=200 调用 HTTPHeader_InitResponse，
  `0x46268` 将正文长度置零，`0x46270..0x46284` 添加 Content-Length 并 HTTPConnectionSendResponse。
  因此可以确认固件会对这个命令回空正文 200；原始成功文本没有逐条记录这类应答，不能声称拿到了它的线上完整报文。
- 与之不同，`requestUI` 在 `0x46560` 取消 videoDelayTimer，`0x46564` 调用 ResourceManager_RequestUi；
  成功日志 `53.076` 收到 request ui，`53.077` 更新 controller/controller，`53.082` modeState changed completion。
  这不能证明盒子采用 mcarplay 那种先报告让出的 200ms 恢复策略。

### 538 的实验恢复与诊断

设置 → 连接与唤醒 → CarPlay 界面恢复（实验），默认关闭，下次会话生效。
只在 requestUI 被本地仲裁接受且之前已持有主屏时尝试额外让出声明，音频字段沿用当前值。
让出应答成功后等待 200ms，再读取最新实际资源状态声明；有车机新的借用/接管时不能强行夺回。
每轮最多每 3 秒尝试一次，控制反馈在独立 worker 执行，不阻塞 iAP/触摸分发。
此版本实现让出/拿回声明和关键帧请求，不含屏幕流 TEARDOWN/重新 SETUP；
其目的为单独验证资源切换能否恢复入口，避免一次试验混入视频流重建变量。

requestUI / changeUIContext 先完成 RTSP 200 写出并记录成功/失败，再分发后续动作。
UI-COMMAND 记录参数结构及 URL scheme，省略路径/查询；changeUIContext 不作为 Android Intent 打开。
原生真机测试 53 通过、1 忽略；Kotlin 测试 148 通过。新增覆盖 200ms 顺序、
车机在恢复间隙借屏、拒绝让出、停止时不发送迟到声明、快速连点冷却，以及可选 CSeq 的空正文应答。
