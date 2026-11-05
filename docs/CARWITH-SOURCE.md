# 可选 CarWith 画面源

更新：2026-11-03。默认自绘界面继续使用原有路径；选择 CarWith 时才启动本机 CarLife 桥，依赖手机安装的 CarWith 与小米版 CarLife。

## 本次启动画面问题

手机日志显示旧实现收到 MD_AUTHEN_RESULT 后直接初始化编码，未收到并验证 MD_AUTHEN_RESPONSE，也未完成 RSA/AES 协商。发送过少量视频后，本机通道全部关闭。旧日志中的“握手完成”不能证明真正进入 CarWith 界面。

修复后的活动路径为：

1. 统一建立 CMD 7240、VIDEO 8240、MEDIA 9240、TTS 9241、VR 9242、CTRL 9340、DATA 9440 七路连接。
2. 协议匹配后读取 MD_INFO，发送 HU_INFO 与新的 2.0 挑战，按设备 brand/model/sdk 和 nonce 校验应答。
3. 手机准入结果与挑战校验分别记录。只有挑战通过才回复 CONTENT_ENCRYPTION=1。
4. 发布本轮 RSA-2048 公钥，解开手机返回的 AES 种子并确认 ENCRYPT_READY。准入、挑战、加密三个条件齐备后才初始化视频。
5. 控制与视频采用 AES-ECB/PKCS padding；音乐混音 PCM 保持该版本的小米明文路径，音频 INIT 与 TTS 按加密路径处理。不在解密失败后回退明文。
6. 接收合法 VIDEO_INIT_DONE 后发送 START，不再用 LAUNCH_MODE_NORMAL 强行打开 CarLife 落地页。

命令或其他通道结束会取消整轮任务并关闭 Socket，下次重新建立状态和密钥。引擎任务持有 Hub，结束时取消它拥有的拨号任务。移除了会阻止正常重连/画面源切换的全局 30 秒创建冷却。

Kotlin 只发送入口启动请求，不再用临时 TCP 客户端连接 7240 来探测“就绪”，避免占用实际协议连接。诊断分别记录七路连接、挑战、准入、加密和视频 START；入口 Activity 启动不等于协议就绪。

## 验证范围

Android arm64 上新增的认证、RSA/AES 门控及 AES 已知向量测试通过。真实手机源测试连续约 12 秒获得 530 帧解密 H.264，解码抽帧确认进入 CarWith 地图与音乐首页。原始视频与抽帧包含位置和媒体信息，保留在开发机诊断目录，不随仓库提交。

该测试验证本机 CarWith 源，不能替代宝马实车投屏、长时间稳定性、音频听感和车机触摸测试。正式构建 0.1.0.513 用于后续应用链路验证。

## CarWith 专用尺寸与触摸（0.1.0.514）

此路径独立处理尺寸，不修改自绘界面的分辨率选择、编码或输入策略。接收端 /info 有效显示尺寸写入 CarWith 请求画布，按相同比例限制在 2560×2560 内并对齐偶数，例如 2732×794 请求 2560×744。实际画布以 CarWith VIDEO_INIT_DONE 回报为准；不能用请求值代替实际值。

车机 HID 在原有坐标转换中映射到 CarWith 的实际编码尺寸，再经独立的 CarWith 输入分发进入 CTRL 9340。按下、抬起、移动分别使用 0x68002、0x68003、0x68004，载荷字段 1/2 是 x/y，并按本轮密钥加密。手势状态拒绝无按下的移动/抬起，坐标限制在实际画布内；新一轮重置状态。CarWith 触摸不再送给自绘界面 JNI 监听器。

本机测试请求 2560×744 后仍收到 1920×720，因此当前已验证的是尺寸回报后的映射，而非该手机已接受 2560×744。加密点击实测使 CarWith 从首页打开应用列表。39 项 Android arm64 Rust 测试通过，覆盖比例上限、不同源尺寸的 HID 映射和手势消息；版本 0.1.0.514 已覆盖安装并启动。车机实际触摸范围与长按拖动仍需对应接收端验证。

协议对照：[mCarPlay 状态机](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeStateMachine.kt)、[挑战绑定](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeAuthentication.kt)。


## 11 月 3 日宝马实车排查（0.1.0.517）

输入为 `hypercar-log-20261103-103850.txt` 与 `103914.txt`。按完整时间戳/正文去重，共 1651 条唯一时间戳记录、1549 条重复；不能按两次导出计为两次独立测试。对照当前 `1f2ac4f`。本节只分析，未改运行代码、构建或安装。

两段 AirPlay 都明确配置 `carlife_source=true`；用户后续自绘 UI 正常的现场反馈保留，但这两份导出截止 10:39:14，没有覆盖切换后的自绘正常会话，不能把第二段 CarWith 错记为自绘成功。

| 阶段 | 关键时间 | 结果与边界 |
|---|---|---|
| 首段，ATTEMPT#7 | 10:38:08.191 RECORD；09.020 RFCOMM EOF；09.092 控制 EOF；09.106 事件 EOF | 控制连接在 RECORD 后 0.901 秒结束；CarWith 七路握手尚未完成，不能称已出画后断流 |
| 重连，ATTEMPT#8 | 10:38:23.805 RECORD；24.749 本机 CarWith 完成挑战、准入、加密并发 START；57.892 控制 EOF | AirPlay 持续约 34.087 秒；CarWith 有视频输入，但车机发送帧数一直为 0 |

### 第一段仍是认证后车机结束会话

Wi-Fi iAP2 完成 AA06/AA01/AA03，08.956 本地传输屏障 READY；09.033 的 peer ack=117 确认到电源、通话、通信、列表回应。随后收到 5200、10DA、AD00、A101，再出现控制/事件 EOF。4158 正文已是本次校正后的 50 字节，故不能声称此前通信状态修正已消除这一断点；链路 ACK 也不证明业务接受。

本轮没有应用 disableBluetooth 请求，也没有此前的 os error 103。断开时 NET-END 仍显示相同 Network 与接口/地址，validated/internet 仍为 true；没有 onLost 记录。这不证明所有网络层都绝对正常，但不支持把它直接定性为手机换网。

CarWith 入口在 08.302 发出启动请求，此时本地 7240 尚在等待连接，没有七路、认证完成或有效视频记录。车机结束的精确内部原因仍未知；不能只因画面源选了 CarWith 就将 RFCOMM/RTSP 结束归为 CarWith 编码器崩溃。

### 重连后有源视频、无解码配置：明确的转发缺口

- 初始 `/info.modes` 为 screen=2/2，屏幕当前归车机。CarWith 于 24.749 完成源端握手，24.790 起已有视频数据；直到 28.822 requestUI 才取得本地屏幕权限。
- 28.864 首个进入屏幕发送处理的 NAL 类型为 1；持续记录 `config=false`，44.058 即使出现 `idr=true` 仍未就绪。两份日志均没有 `config written`、`first IDR ready`；keepAlive 在 24.851、33.923、44.004、54.084 均为 `frames=0`。因此这里“CarLife 已握手/读取 VIDEO”不能冒充“车机已收到有效画面”。
- `screen.rs::send_video_annexb` 在检查/保存 SPS、PPS **之前**检查屏幕所有权，无权限就直接返回。`engine.rs` 在屏幕 sender 就绪前也直接丢视频。源端在这两个窗口发出的初始化参数没有缓存，恢复权限后如果只来 P/IDR 帧，无法生成 avcC；当前实现还要求同一次调用同时带 SPS 与 PPS，不能积累分开的参数帧。
- `emit_force_keyframe` 最终仅调用 `HyperCarRepo.onForceKeyFrame → CarUiCaster.requestKeyFrame`。CarWith 模式并不使用该自绘编码器，这条调用没有驱动 CarWith 重发参数或 IDR。日志里的“requested sync frame”只证明发出了现有回调，不证明 CarWith 执行了请求。
- 没有解密后的首批 NAL 抓包，尚不能逐包证明 SPS/PPS 究竟在哪一个窗口丢失，或是否分开发送；但上述缓存及关键帧路由缺口与持续 config=false 的现象直接相符。当前日志没有 AES 解密失败、CarWith 命令 EOF 先于车机 EOF 的证据。
- 音频在重连后持续约 136～137 包/秒，但没有视频配置与有效视频帧。反复 requestUI 均 accepted=true、modesChanged=200，仍不足以让车机解码画面。57.892 由对端结束控制通道，随后应用才清理；没有车机内部日志，不能把约 34 秒直接命名为已证实的视频看门狗阈值。

建议修复顺序：先在 CarWith 源侧缓存本轮 SPS/PPS，覆盖 AirPlay 未就绪和屏幕未授权窗口；恢复权限或换尺寸后使用匹配本轮画布的配置，再等新 IDR 发送。支持分开的 SPS/PPS，隔离新旧源/会话/尺寸，不能跨轮复用旧参数。将关键帧请求路由到活动画面源；CarWith 请求指令应先核对协议和实际实现，不用自绘编码器回调冒充源端已处理，也不无限重复 START。保留现有资源仲裁与自绘路径。第一段的认证后结束另行核验，不能承诺画面缓存修正会同时解决它。

### ADB 采集

手机重新接入后只读采集 bluetooth_manager、全缓冲 logcat 及 bugreport，原始资料保存在开发机 `D:/devtools/hypercar-diagnostics/20261103/`，不随文档提交。

蓝牙服务历史与应用时间对应：

- 首段 ACL 由车机发起，10:38:02.952 建立，09.127 结束，原因 `REMOTE_USER_TERMINATED_CONNECTION (0x13)`，与应用 RFCOMM EOF 和 RTSP EOF 相邻；说明最终 ACL 终止来自远端，不能证明它先于所有 Wi-Fi 业务异常。
- 重连 ACL 同样由车机发起，22.155 建立，10:38:59.507 结束，原因 `CONNECTION_TERMINATED_BY_LOCAL_HOST (0x16)`；比 57.892 控制 EOF 晚约 1.6 秒。不能把后来的本地蓝牙关闭说成这次视频缺失/RTSP EOF 的先因。
- 系统事件记录显示第二段 CarlinkService 于 23.938 启动、58.081 停止，CarWith 进程 58.234 结束，均晚于控制 EOF；当前没有源端进程先崩再拖断 AirPlay 的证据。
- 前面另有多次未达到 RECORD 的 ACL/认证失败，以及 HFP/A2DP 连接后保护逻辑实际断 profile 的事件，应单独保留，不当作相同的投屏中断样本。

完整 HCI 是否覆盖此时段，以 bugreport 中实际文件的首尾记录为准；服务历史不能冒充逐包 HCI。


完整 HCI 复核：bugreport 已成功保存。`btsnoop_hci_20261103_103519.log` 实际覆盖 10:35:19.716～11:48:06.553（北京时间），65536 条完整记录、无尾部残片，报告的 dropped 为 0；它覆盖两段宝马失败，不是仅凭文件名判断。相关逐包结果：

| 时间 | 方向与动作 | 对结论的意义 |
|---|---|---|
| 10:38:09.018 | 车机→手机，RFCOMM DISC，DLCI=50 | 比应用 RFCOMM EOF 早约 2 ms，确认这一条由车机发起拆除 |
| 09.029～.035 | 车机 DISC DLCI=0、车机请求 L2CAP 断开，手机回应 | 不是 HyperCar 先清理蓝牙；也未在此前看到本机对该 ACL 的 HCI Disconnect |
| 09.092 / .106 | 应用记录控制/事件 EOF | Bluetooth 拆链在前，Wi-Fi EOF 在后；车机为何决定拆链仍未知 |
| 10:38:57.933 | 手机→车机，RFCOMM DISC，DLCI=50 | 晚于 57.892 / .902 的控制/事件 EOF，属于应用停止后的收尾 |
| 10:38:58.120～59.367 | 车机重新建立 RFCOMM/HFP，之后手机拆 HFP | 与服务历史中的 HFP 回连和保护逻辑一致，不能倒置为此前的视频故障原因 |
| 10:38:59.417 / .506 | 手机 HCI Disconnect；完成事件 reason=0x16 | 手机确实后来主动结束 ACL，但不是本轮 RTSP 结束的先因 |

因此第一段不能定性为手机主动拆蓝牙；第二段不能因最后看见本机断开就认定是 profile guard 先打断投屏。原始 HCI 和 bugreport 保存在本地诊断目录；本轮未修改手机设置、切换画面源、安装软件或开启新的 CarWith 会话。


## 对照 mCarPlay 的流程补齐（2026-11-03）

固定参考 `wangxso/mcarplay@a3a4117ed60b864324d681e45086be594ed7233a`（本轮读取时 main），对照 HyperCar `1f2ac4f` 及本轮工作区。参考仓库的 Kotlin 实现用于核验步骤、消息方向与边界；本项目采用独立 Rust 实现，没有整体移植其 Android/AirPlay 生命周期。参考项目公开的接收端/奇瑞测试也不是宝马兼容性证明。

### 分阶段对照

| 阶段 | mCarPlay 行为 | HyperCar 修改前 | 本轮结果 / 剩余范围 |
|---|---|---|---|
| 入口、预热和附着 | 允许预热本机 CarLife 源，再为实际 CarPlay 输出建立 JoinGate；有独立会话租约 | 原生引擎开始拨本机端口，但在 AirPlay connected 后才请求 CarWith Activity；没有独立正式/诊断入口租约 | 启动空窗仍有差异，未因本轮重写入口；当前仅有一条正式引擎源，不复制全局冷却。以后增加独立自测入口时再共用互斥租约 |
| 七路连接 | 7240/8240/9240/9241/9242/9340/9440；部分成功即清理重试；整体等待有截止时间 | 七路、部分失败清理和取消已经存在；不断重试直到本轮被取消 | 主干已对齐；本机监听长期未就绪时的整体超时/用户状态仍待补充，不把一次 Connection refused 当认证失败 |
| 4.1 协议、身份、挑战 | 协议匹配→MD_INFO→HU_INFO/2.0 nonce→绑定 brand/model/sdk 的应答验证 | 已有独立会话状态与绑定验证，今天第二段真实通过 | 保留；不以 foreground 或 MD_AUTHEN_RESULT 替代挑战验证 |
| 准入、功能、密钥 | 分开跟踪准入与 proof；CONTENT_ENCRYPTION 后 RSA/AES，ENCRYPT_READY 后才初始化视频 | 已有三条件门控、每轮新挑战/密钥、非法应答失败 | 保留；无需为黑屏放宽认证或直接发 START |
| 视频 INIT/START | 以 INIT_DONE 的有效回报进入 VIDEO_RUNNING，再发一次 START | 已按该顺序执行，实际收到 2560×744 | 保留，RESET 不重新走准入、不反复发 START |
| 媒体包与 H.264 帧 | VideoParser 跨 CarLife 包保存尾 NAL，用 AUD/SEI/参数集/first_mb_in_slice 重组完整 access unit | 每条 VIDEO_DATA 直接当完整帧；包尾可能只是半个 NAL，也可能含多个画面 | **已补** CarWith 专用有界解析器，支持拆分 start code/NAL、多 slice 及同包多帧，不把同一 Unix 秒当一帧 |
| SPS/PPS 与首帧门控 | 解析器缓存参数；JoinGate 等 IDR，并把缺少的 SPS/PPS 前置到该 IDR | ScreenSender 缺权限就提前丢输入；需要同次输入同时带 SPS/PPS，初始化参数易错过 | **已补** 每个 CarWith 源轮次独立缓存；每个 IDR 补齐当前参数，早期丢过的帧不影响随后可解码接入。没有屏幕权限仍不发视频 |
| 请求关键帧 | VideoSender 转 ExternalVideoSource→CarLifeSession.requestKeyFrame；仅 VIDEO_RUNNING 时发 CMD `0x1800B` RESET | 只回调 Kotlin 自绘 MediaCodec，CarWith 没接到请求 | **已补** 根据引擎实际画面源分发，CarWith 经 CMD 写 RESET，待 START 后处理；Notify 合并、最多每秒一次，不经触摸 CTRL 发送。自绘仍用原回调 |
| 时间戳 | CarLife 头按 Unix 秒解释；VideoSender 用本机 nanoTime 构造 AirPlay 帧时间 | 把 CarWith 视频头按毫秒乘 1000 后直接作为 AirPlay PTS；视频心跳也用毫秒低 32 位 | **已补** CarWith AU 的 AirPlay PTS 用本机单调微秒；CarWith VIDEO 心跳头用 Unix 秒。非 CarWith 旧监听分支的时间处理不改 |
| 源重连/格式重置 | 新会话重建解析器/状态，输出附着时重新等可解码起点 | 七路故障重拨会重建密钥，但 AirPlay sender 可能保留旧 avcC | **已补** 新七路轮次先发 SourceReset，清配置/SPS/PPS/IDR 状态，随后再处理新 INIT_DONE 和新码流；不会跨轮沿用旧参数 |
| 画布 | 长边上限 1920；还会从 SPS 校验实际尺寸 | CarWith 专用上限 2560；按 INIT_DONE 回报实际尺寸 | 不机械降到 1920。当前手机已回报 2560×744；从 SPS 再校验画布及运行时格式变更仍是后续项 |
| 音频保护 | 小米 MEDIA_DATA 明文，INIT/TTS 加密；有效 INIT 后才收 PCM，校验格式及通道 | 包级加密/明文例外已匹配；仍有默认 44.1k/stereo，格式与停止状态校验不完整 | 本轮保留音频线格式；有效 INIT 前丢 PCM、8/16-bit 和 channel mask、PAUSE/STOP 状态列为待完善，不把出包率当音质证明 |
| 关闭策略 | CMD/VIDEO 关键通道失败结束源；非关键通道关闭只记日志；输出队列失败重新等 IDR | 七路中任一子任务结束就销毁源轮次、重新拨七路；源离线向引擎主要记日志 | 策略不同，不直接复制“非关键可忽略”。需要补充分通道原因/源在线状态和断线对输出的影响；本轮新源缓存隔离已落地 |

### 时间与分帧的直接证据

今天第二段源头首批 VIDEO 数据的头部时间字段为 `0x6AE94920`，十进制 `1793673504`，按 Unix 秒转换正好是北京时间 **2026-11-03 10:38:24**，与日志吻合。它不能按毫秒使用，也不能将其同秒重复值作为 access-unit 边界。mCarPlay 的解析器明确使用 H.264 语义分帧，其发送器使用本机单调钟；本轮分别对齐这两层，而不是简单把旧 PTS 乘数再放大。

### 本轮实现边界与验证

新代码位于 `rust-core/src/carlife/video.rs`，只接入带 CarWith 会话加密状态的主动源分支。缓冲与 access unit 有 4 MiB 上限，超限结束本轮源而不是继续积压或拼接可疑码流。参数变化时避免拿新 SPS 配旧 PPS；跨源/轮次的参数不继承。格式切换额外覆盖首个画面前 SPS 变化不能带出旧 PPS；ScreenSender 重置只清配置，保留 AirPlay 加密帧计数，避免重用 nonce。没有引入关闭资源仲裁、伪造 MFi READY、随机丢参考帧、重复 START 或解密失败回退明文。

`engine.rs` 新源事件清理 ScreenSender 配置；`lib.rs::emit_force_keyframe` 按 EngineHandle 的实际源路由，避免设置切换或 Java 回调使 CarWith 请求落到自绘编码器。`session.rs` 在命令通道、视频已 START 后节流发送 RESET。源包时间只用于诊断，真正 AirPlay 视频时间与现有 timing 单调钟同源。

新增 8 项 Android arm64 Rust 回归：跨包字节拆分、分开的参数集、多 slice/多帧、丢失首轮输出后新 IDR 补参数、新轮缓存隔离、超限清理；另有真实 loopback TCP 验证无权限时不发帧、恢复后先 VideoConfig 再加密 IDR、reset 后拒用旧 avcC，以及 RESET 握手前不发送/握手后走 CMD/短时间请求节流。最终手机运行 **47 项通过、0 失败、1 项真实 CarWith 场景测试保持 ignored**。这些验证不等于新的宝马实车成功，也不证明第一段认证后车机主动断开已解决。

### 固定版本源码入口

- [状态机与三条件门控](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeStateMachine.kt)
- [七路连接与部分失败清理](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeChannelConnector.kt)
- [CarLife H.264 分片重组与参数缓存](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeVideoParser.kt)
- [IDR 接入与补齐参数](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeJoinGate.kt)
- [RESET、心跳、源关闭](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeSession.kt)
- [消息 ID、视频尺寸策略](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeProtocol.kt)
- [外部源关键帧路由和 AirPlay 时间头](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/video/VideoSender.kt)
- [音频 INIT/PCM 校验](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeAudioParser.kt)
- [正式/诊断会话租约](https://github.com/wangxso/mcarplay/blob/a3a4117ed60b864324d681e45086be594ed7233a/app/src/main/java/com/mcarplay/app/carlife/CarLifeSessionRegistry.kt)


最终构建验证：120 项 Release JVM 测试全部通过；Rust release 库及签名 APK、R8、Release vital lint 构建成功。版本 `0.1.0.518 / 10518`，包内 arm64 核心库与本轮 release 产物逐字节一致，签名证书 CN=HyperCar。APK SHA-256：`5afeef998e3a90668d87380870f93553bbc3fc94a2db8eef77f64f07cbdc7e23`。未安装、提交或推送；本轮只通过 ADB 执行独立回归测试，没有启动真实 CarWith 源测试，也没有变更手机正在使用的画面源。


## 2026-11-05：CarWith 手机设备列表名称核查

从当前手机提取 `com.miui.carlink` 安装 APK 并检查 DEX 与资源，确认：

- HyperCar 的主动源通过 `CarlifeAdbActivity` → `CarlinkService` 的 `CARLINK_STATUS_CASTING_CARLIFE_USB_ADB` 分支启动。
- `CarlinkService.onStartCommand` 在该分支直接调用 `e2.g.A("百度carlife+")` 设置名称；USB AOA CarLife 分支也设置相同字面值。该分支没有从入口 Intent 读取自定义名称。
- 手机连接信息页 `UCarConnectInfoActivity$UCarConnectInfoFragment.j2/Y1` 将该字面值本地化成 `R.string.baidu_carlife_text`（中文“百度CarLife+”）。因此列表里的名称不能靠修改 HU_INFO 的 btname/model 直接替换。
- `CarlifeAdbActivity.c` 创建一个新 Intent，只传 `startType=3` 给服务；向 Activity 添加 `device_name` 并不会转交给该服务路径。连接信息页读取 `device_name` 仅用于页面展示，不等于支持重命名连接。

结论：当前有线/ADB 条目的名称不是蓝牙名，也不是 HU_INFO 的 manufacturer/model/btname。仅修改 HU_INFO 无法解决列表显示，因此已撤回本轮两处名称修改，没有构建或安装名称修复版。不修改 CarWith APK 或私有数据库。

### mcarplay 的其它命名通道

核查远端固定提交 `9dd589413bf1806d6a20f6e1a6b96f848a86e477`：

- [`CarLifeLauncher.kt#L201-L204`](https://github.com/wangxso/mcarplay/blob/9dd589413bf1806d6a20f6e1a6b96f848a86e477/app/src/main/java/com/mcarplay/app/carlife/CarLifeLauncher.kt#L201-L204) 的旧版内部服务启动命令传入 `startType=4`、`carlife_wireless_type=Baidu_ap`、`carlife_wireless_type_name=mcarplay`。这里的名称来自服务启动 Intent，是一条区别于 HU_INFO/蓝牙名的通道。
- 同文件 `requestLegacyCarWithService/runLegacyServiceCommand` 检查 root UID；普通应用和 UID 2000 的 Shizuku shell 不直接启动该受保护服务。
- [`CARWITH-4.0.4-ANALYSIS.md`](https://github.com/wangxso/mcarplay/blob/9dd589413bf1806d6a20f6e1a6b96f848a86e477/docs/CARWITH-4.0.4-ANALYSIS.md) 也说明公开入口转入 `startType=3` 的 USB/ADB 兼容路径。
- 当前手机 APK 的 `CarlinkService.onStartCommand` 在无线服务分支确实读取 `carlife_wireless_type_name`，而 `startType=3` 分支直接写固定名称。公开 Activity 会新建 Intent，只附带 `startType=3`，不会转发额外名称。

这证明有内部命名参数，但不证明向当前公开有线入口加同名 extra 就能改名；它会被丢弃。若需尝试，应单独评估具备真实 root 身份时的内部入口兼容性，而非将普通有线连接伪报为蓝牙连接。本轮没有切换入口或操作该服务。

本机诊断产物：`/mnt/d/devtools/hypercar-diagnostics/carwith-name/carlink.apk`；方法摘录 `/tmp/hypercar-carlink-name-code3.txt`，资源摘录 `/tmp/hypercar-carlink-resources.txt`。第三方 APK 不提交仓库。
