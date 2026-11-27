# 音频采集、资源与无声排查

更新：2026-11-07。实现与已验证范围分别描述，出声不能只凭 connected 或 RECORD 判断。

## CarWith 与系统 CarLife+ 蜂窝通话

CarWith 会话及[开启车机通话实验的系统 CarLife+ 会话](SYSTEM-CARLIFE-SOURCE.md#使用)在手机进入普通蜂窝通话、Shizuku 已授权、本机支持 Android PSTN 双向接口且车机 `/info` 声明同一条 type100 `telephony` 双向格式时，暂停媒体流并建立通话主音频。支持 24/16 kHz 单声道 PCM、48/44.1 kHz 立体声 PCM 和 24/16 kHz 单声道 Opus。若车机声明 24/16 kHz 单声道 AAC‑ELD，先用 Android MediaCodec 验证双向每包 480 个采样，验证通过才使用。SETUP 的 `dataPort` 用于手机接收车机麦克风，响应的 `dataPort` 用于向车机扬声器发送音频。已有主音频授权时，SETUP 确认后公布 `phoneCall` 并启动端点。车机持有主音频时，按其约束临时借用，在借用确认后建流，通话结束归还。iAP2 通话状态独立上报。

电话 SETUP 校验流级状态及车机回填的音频参数。遇到明确的 RTSP 400、451、461 时，先用同一格式只省略 `controlPort`，再次被拒后再试省略 `ct`、`sr`、`spf`、`supportsRTPPacketRedundancy` 的精简请求，随后按 `/info` 声明尝试下一种双方支持的格式。麦克风 `dataPort` 在重试期间保持一致。车机未声明匹配的延迟时省略 `audioLatencyMs`。应答超时或内容不一致时先清理可能占用的主音频槽，再恢复媒体。

车机通过 `/info` 的 `extendedFeatures` 明确声明 `vocoderInfo` 时，按盒子固件样本发送 `sampleRate=16000.0`，RTP 采样率仍由双方协商的音频格式决定。若 Android 已为当前目标车机建立 HFP 的 SCO 音频，通话继续走系统蓝牙路由。SCO 消失且同一通普通蜂窝电话仍有效时，可以重新尝试 AirPlay 通话。若此前已用尽 AirPlay 重试机会，第一次回切可恢复一次原有的有界重试。导出日志会记录电话流开始清理前的退出原因，并在 500 ms 内独立查询本次通话的手机端关闭记录。

手机端通过 Shizuku UserService 调用 Android 通话下行提取及上行注入接口。某个采样率无法创建、启动或路由端点时会尝试其它支持的采样率，和车机协商结果不同时在桥接中转换。桥接要求 Android 一直处于普通蜂窝通话，Telecom 的通话列表和前台通话也必须确认同一路活跃电话。精确状态监听注册就绪后，若有订阅暂缺角色，等待 500 ms 并要求两次连续的 Telecom 快照确认同一通电话。已报告的响铃、保持、断开或多路活动会阻止兜底，后续缺报也不会清除这些已知状态。重复的角色回调可沿用当前身份。独立的挂断通知、通话角色变化或 Telecom 明确返回无符合条件的前台通话都会立即撤销身份。已结束的通话 ID 取自 Telecom 自身的终止记录，跨监听重注册保留。没有通话 ID 的挂断通知会重新开始等待，并要求之后两次 Telecom 查询确认同一通活跃电话，才恢复桥接。看门狗按下一次续查到期时间唤醒，续查使用当前身份剩余的有效时间，最多等待 400 ms。精确状态完整时，在原有 500 ms 有效期内发起的续查若失败，允许同一已验证身份在最初快照开始后的 980 ms 内重试一次。第二次失败或超出该时限时撤销身份。Telecom 超时或缺少进程退出码时，精确状态和缺报兜底都只给续查开始时已起流的同一端点一次有界重试。挂断或切换的明确证据立即撤销旧身份。缺报兜底的其他失败会撤销身份并重新要求两次快照。新建端点和起流均主动复查同一 Telecom 通话。蓝牙预检结束后重新查询。起流按读取转储的时间判断身份时效，等待退出码耗尽时效时至多重新查询一次。首帧 PCM 和 READY 交付后，Rust 必须成功发送首包车机下行 RTP 并通知手机端，端点才会登记为运行中并获得缺少退出码时的一次重试资格。已建立的通话在首次查询失败后仍可使用有效身份，尚未开始的音频须等重试确认同一通电话。读取转储时，完整的挂断或保持记录、已闭合的通话列表，以及明确的前台切换证据都会立即撤销旧通话。后续输出截断时仍保留这些证据。撤销先关闭旧端点的套接字，再等待已获准的系统音频操作结束。通话下行在写入车机 RTP 套接字前重新检查手机端点。麦克风 RTP 经对端地址校验、认证和重放检查后进入当前蜂窝通话。暂时收不到麦克风包时扬声器继续播放，收到新的有效包后恢复上行。紧急呼叫保持原有路由，手机麦克风静音时不注入车机麦克风。电话流异常中断后，确认电话流清理且媒体恢复后，同一通电话最多按同一采样率重新建流一次。车机暂时持有主音频时，媒体在授权归还后恢复。导出的 `CALL-AUDIO` 日志包含订阅注册结果、通话角色、门控判定、Telecom 查询结果和端点状态。查询诊断包含耗时、超时预算、是否超时和进程退出码。退出码 137 在缺少超时信号证据时记录为 SIGKILL，超时状态记为未知。上行路由诊断记录输入和输出设备类型、电话输出声明的采样率、端点格式、预热写入和实际路由，省略设备标识与异常原文。通话查询只记录不透明的 Telecom 通话 ID，日志不含号码或原始转储。

通话结束后，如果车机仍持有主音频，媒体流等待车机归还。等待期间，语音助手可在车机允许时临时借用主音频，播完后归还给车机。

读完 Telecom 输出后，只在本次查询剩余预算内等待退出码。仍无退出码且没有明确超时证据时，记录 `exit_code=none timed_out=unknown`。只有已经起流的端点可以在一次有界重试期间继续工作。端点关闭会取消缺退出码的续租。新建端点和开始音频均要求 Telecom 成功退出并确认同一个通话 ID。解析器逐行扫描转储和带值的同层字段。无关通话的已挂断记录不计入活跃通话，任意位置出现当前通话结束或切换的明确证据都会立即撤销旧端点。确认新流仍需完整且成功的查询。

车机在本次电话流的控制端口回报近期收到的 RTP 序号，并回显本轮 [RTCP 发送报告](https://www.rfc-editor.org/rfc/rfc3550.html#section-6.4) 的 LSR 后，才有资格临时静音手机内置听筒或扬声器。静音前要求至少 1.3 秒连续递进的有效回报，采样间隔在 80–400 ms 内。每份回执沿用对应 RTP 包发送时确定的 650 ms 截止时间，跨进程交付时保持这个绝对时限。回执中断后，本轮端点恢复手机通话声。解除静音的写入独立于 PCM、路由查询、音量读回和恢复记录落盘。静音前保存通话编号、开机标记、Telecom 进程和恢复时限，持有静音时随有效回报续期。静音请求在完成可能阻塞的授权检查后才落盘，写盘完成后再查当前输出，仍为内置听筒或扬声器时，核验通话身份、传输端点和时限，随后登记派发。确认静音写入且持有 Telecom 进程标识时，六秒恢复窗口从写入实际返回时算起。恢复写入前重新查询 Telecom。对未确认的静音写入，重绑后先独立读回音量。只有观察到实际静音、仍在六秒窗口内且确认同一通电话时，才发出一次补偿。已确认的写入在 Shizuku 慢重绑后也可补偿，前提是 Telecom 进程与通话编号都仍一致。Telecom 确认无通话且手机已恢复正常音频模式时，可在原时限内恢复已确认的写入。重绑后有独立的六秒尝试窗口，最多发出一次补偿写入。音量读回按恢复对象隔离，过期读回不参与空闲计时，也不能清除后续端点的记录。两个读回线程均被占用时暂缓新的静音。仍在系统内执行的静音写入返回后，会重新检查通话身份并处理迟到的静音。按无通话恢复时，要求通话列表没有存活通话，且音频管理器明确报告前台通话为空。新通话接管时，先同步包含候选通话和原恢复记录的备用槽。进程在准备阶段退出时，新服务读取原记录，即使取消时的回滚写入失败也能恢复。派发时在锁定文件的映射中切换一个字节，此后接管进程按候选通话处理未确认写入。确认或续期写盘失败会触发解除静音，并停止本次端点继续静音。日志记录接收确认、静音和恢复。接收报告确认 RTP 收包，实际出声需要实车验证。

依据为 CarPlay R14G17 样例的 Main Audio Telephony 声明、POSIX 接收端的 `input` 处理及 CarPToCarL 的通话档切换与手机输入端口示例。盒子 xiap2 的 `ResourceManager_ControllerPhone` 与 `setPhoneState` 用于对照电话状态和资源切换。CarWith 的 VoIPService 使用独立 DataBus 通话通道，当前 CarLife VR 仍服务于助手，蜂窝通话 PCM 通过 [Android 系统通话接口](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioManager.java) 接入。实车需确认车机出声、远端能听到车机麦克风和挂断后音乐恢复。

### 直接桥接原生服务的前提

检查了测试手机正在使用的 `com.miui.carlink` 4.0.23-20261016 APK。原生双向电话音频走 `DataBusService` 的媒体通道和 `VoIPService`，两个服务均未导出。原生投屏入口 `CarlinkService` 需要 `START_CARLINKSERVICE` 的签名或系统特权。已导出的 `CarlifeConnService` 鉴别 Binder 调用者，只允许该版本的 CarLife 伙伴应用注册 `ISystemAudioCallback`。CarLife VR PCM 则交给助手队列。以上检查没有发现当前第三方 APK 可以直接挂到现有 CarWith 会话上的电话 PCM 入口。

系统 CarLife+ 的车机端协议需要按厂商或引擎验证。现有 CarLife 资料和公开协议实现中的电话控制走 HFP，双向通话音频由车机与手机之间的蓝牙免提承载。它可以服务于电话，但不会向 HyperCar 提供可桥接给 AirPlay 的 PCM。参考[通话传输示例](https://patents.google.com/patent/CN117956061A/zh)和[百度车厂接入流程](https://online.carlife.baidu.com/carlife/caroem/start)。

免 Shizuku 的 AirPlay 电话链路还缺厂商支持的音频生产端。需要能在已授权的原生车机会话内读取电话下行、写入车机麦克风，并收到开始、结束和静音等状态的车机 SDK 或公开接口。ICCOA 提供面向车机的 SDK，公开资料确认语音通话场景，但没有提供可直接接入当前 HyperCar 进程的双向电话 PCM 接口。参考 [ICCOA Carlink SDK 说明](https://www.iccoa.cn/suit_2.html)。

## 两种音源

| 来源 | PCM 输入 | 边界 |
|---|---|---|
| 自绘 UI | Shizuku shell AudioPolicy双sink，MEDIA排除高德UID、导航仅含该UID，PCM16/48k/双声道 | policy不可用时完整退回单一系统混音；不采麦克风 |
| CarWith | 本机 CarLife MEDIA / TTS / VR：媒体、导航提示与助手回复 | 不启动自绘系统播放采集；MGU按能力分配独立流 |

首页音频卡单行显示实际协商的 PCM / AAC 和约每秒更新的发送负载码率，不再添加播放状态文字前缀。统计仅包含成功写入 UDP 或缓冲音频 TCP 的音频有效负载，不含 RTP 头、加密开销或同步包；发送失败、资源被接管后未发出的数据不计入。未协商时不显示编码/码率，停止后不沿用旧会话数值。静音保活也可能有发送码率，不能据此判断车机已出声。

新增 AAC-LC48k 双声道发送：Android NDK MediaCodec 编码，目标 256kbps；type102 / audioFormat0x800000 / spf1024 / ct4 / sr48000。一包一个裸 AAC 帧，不加 ADTS/AU 头、不换字节序；RTP 时间戳和同步包按1024采样推进。编码器配置输出不作音频发送；采集、重采样、TTS 混音仍共用48k PCM输入，CarWith和自绘均可使用。资源被车机接管时停止编码/发包并丢弃待编码数据，重新取得资源时重建编码器，保持密钥/nonce计数连续。

自动或 AAC 优先的加密会话在 SETUP 时提议 `mainBuffered`（NBT 专用音频路径除外）。不使用配对前可能不完整的格式表否决提议；只有会话内 `/info` 的同一条记录声明 type103、media 和 AAC-LC 48 kHz（bit23），且使用支持的非 APAP 封包时，才尝试 TCP 缓冲音频。103 建流或起播失败时，如车机可能已分配 103，须先确认该流已释放，再尝试实时音频；运行时回退可按本轮声明尝试 AAC/type102 或 PCM/type100。日志记录 SETUP、TCP 首包和播放锚点，不代表接收端已经实际出声。

`audioLatencyMs` 下限仅对 type103 设为 1000 ms，给支持该参数的接收端留出抗短时无线抖动的余量。实时主媒体 type100/102 下限为 300 ms；接收端声明更大的对应输出延迟时，仍采用声明值。增大缓冲不是抗干扰或断线重连机制，也可能增加听感延迟；道闸、地库等场景需实测。

`AUDIO-PACING` 每个统计窗口记录各阶段最长的一次，格式为 `起始单调纳秒,结束单调纳秒,Android线程ID`。`wake` 对应节拍期限到音频任务实际执行，`pcmDispatch` 对应 PCM 到达至音频任务取出，`pcmEnqueue` 含重采样、入队和淘汰旧帧，`mix` 是混音，`aac` 是编码调用，`udp` 是主音频 RTP 的非阻塞 socket 写入。`NA` 表示窗口内未执行该阶段。各阶段最大值可以来自不同时刻。若调度轨迹用 BOOTTIME，按 `clockAnchorMonoBootNs` 中的 `bootNs - monoNs` 将日志时间换算到轨迹时钟，再按线程 ID 核对。

主音频泵使用专用线程 `hypercar-audio`，AAC 编码交给 `hypercar-aac`。编码输入和完成队列各有八项上限，实时 AAC 每个混音节拍最多发一个已完成的 AU。编码输入队列满时累计 `aac_queue_dropped_frames` 并推进采样时钟，音频资源转交后淘汰上一次授权的输入和结果。测试时可用 `AUDIO-THREAD` 和 `AUDIO-PACING` 的线程 ID 核对混音、发包与编码是否已分开。

实时 PCM/type100 和 AAC/type102 在只漏一个 352 帧节拍、且媒体队列同时有补拍和当前拍时，最多追回一个块并保留原 RTP 采样位置。补拍以原节拍起算，最长允许 60 ms。这是发送端的保守期限，车机可有自己的播放截止时间。AAC 结果用提交时的采样位置，含过期补拍的整个 1024 帧 AU 会丢弃，后续 AU 仍使用各自原有时间戳。日志 `recovered_source_frames` 统计从 PCM 队列纳入恢复的帧，`expired_media_frames` 和 `expired_aac_frames` 分别统计淘汰的 PCM 和已编码 AAC 帧。

`AUDIO-PACING` 的 `firstMediaMonoNs` 标记当前流首次有媒体 PCM 在混音队列中的时间。此前的漏拍记入 `startup_silence_recoveries`、`startup_silence_skipped_frames` 和 `startup_max_late_us`。此后的漏拍继续用 `recoveries`、`skipped_source_frames` 和 `max_late_us`，即使播放中途队列变空。`wake` 仍保留整个统计窗口的原始唤醒峰值。

设置「画面与声音 → 音频格式」提供自动、PCM、AAC 优先，下次连接生效。通用实时音频自动模式按 PCM 48 kHz、AAC 48 kHz、PCM 44.1 kHz、AAC 44.1 kHz 的顺序尝试本轮 `/info` 已声明的媒体输出格式，适用于 ID9/MBUX 等严格声明路径。PCM 选项只尝试已声明的 PCM48/PCM44.1；AAC 优先先尝试已声明的 AAC48/AAC44.1，再尝试 PCM48/PCM44.1。非严格路径保留最后的未声明 PCM48 历史兼容探测，严格路径不增加该探测。CarWith 继续优先匹配 `MEDIA_INIT` 源采样率；NBT 保留 AAC44.1、AAC48、PCM48 的原顺序并追加已声明的 PCM44.1。缓冲媒体回退顺序和各平台的 RECORD 顺序保留。DiPlay 已实测 AAC 协商成功并确认出声，约 47 包/秒。AAC 裸帧及 RTP 实现参考了 [CarPToCarL](https://github.com/jjuugk/CarPToCarL)。

音频延迟按流类型、角色、采样率、位深、声道数和编码匹配 `audioLatencies`。省略的字段匹配任意值，采用首条有效的匹配记录。实时媒体和缓冲媒体的延迟下限分别为 300 ms 和 1000 ms。独立语音和导航分别按实际协商的 24 kHz 与 48 kHz 单声道 Opus 匹配，缺少有效记录时仍发送 0 ms。

实时媒体绑定本地控制端口后，在 SETUP 声明 `supportsHighAccuracyTimestamps`。接收端拒绝该提议时可保留控制端口并重试原字段。`AUDIO-FEEDBACK` 根据当前会话、流类型、`streamConnectionID` 和当前音频归属关联 `/feedback` 中的 `sampleTime`、`timestampRawNs`。`rateHz` 是接收端采样率估计，`sampleClockOffsetNs` 是相对于本次观测初始样本的偏差，采用接收端原始时钟。两项只用于诊断。

BMW ID7–ID8.5 档案识别到 MGU-103-F 时，主音频归还后若 AAC 重建最终收到 RTSP 400，且同一次重建期间旧流的 RTP 或 RTCP 端口报拒绝，暂停旧发送端。暂停期间归还的换流机会留到恢复播放时处理，本地主动取回音频授权也会单独唤醒恢复。暂停确认会等正在执行的 RTCP 重传写入结束，候选流安装后才启用 RTCP 回包。所有类型 102 清理共用应答校验，收到整个类型的清理确认后才能重新 SETUP。媒体停止期间只清理，待播放恢复后重建；发送泵安装流时再次校验播放轮次。首次替换和后续恢复允许通话或语音在 RTSP 操作之间接管。每轮恢复最多两次 SETUP 和 RECORD，收到新的播放请求后可在 500 ms 退避后再次尝试。见 [#104](https://github.com/fw867/hypercar/issues/104)。

AirPlay PCM 使用 SPF352，约 136.36 包/秒，16-bit 大端样本。TTS 参与闪避混音；持续全零样本不会一直保持“播放中”。

实时主媒体 PCM/type100 和 AAC/type102 在 SETUP 前于会话网络绑定 UDP 端口，并写入 `streams[].controlPort`。车机返回控制端口后，沿用同一个 socket 收 RTCP 和发同步包。车机省略控制端口时，仍保留通告的监听端口，只接收来自本轮数据端点 IP 的请求，回包发往请求源端口。PCM SETUP 遇到 400、451 或 461 时去掉本地端口重试一次。对照日志按 `streamConnectionID` 核对 `advertisedControlPort`、`localPort` 和 `peerControlPort`。支持 8 字节和附带 NTP 时间戳的 16 字节 `0xD5`，长度字段分别为 1 和 3。Apple 接收端参考代码的主动重传路径在 MainHighAudio/type102。PCM/type100 是否会请求补发，仍取决于车机实现。

HyperCar 收到有效 `0xD5` 后，以 16 位 RTP 序号查找本流最近 500 ms 内成功发出的包，最多保留 PCM 72 包或 AAC 32 包。命中时回 `0xD6`，4 字节头后附上原 RTP 数据（含密文、认证标签及原 nonce）；未命中时按盒子 `xiap2` 的 `_audioReceiveRTCP`（0x40500）回八字节的未命中响应，携带原序号。头部长度使用盒子的小端字节数。每请求最多处理 16 个序号，每秒最多尝试回复 32 次。只有当前会话和当前音频授权的有效缓存会用于补发。`AUDIO-RTCP` 的 `d6Sent` 统计实际补发，`d6FutileSent` 统计短回应，两者均不计入正常发包率和码率。

571音频节拍：绝对周期不变，漏拍时跳过已过期的352帧时段，丢弃混音队列中对应旧样本并推进RTP时间戳；不再Burst集中追发。恢复首包置marker并重新发送时间同步，包序号及AEAD nonce不重置。AAC保留编码器半帧和重采样相位，避免每次漏拍同步重建编码器导致持续饥饿；音频资源被接管时仍按原策略清理编码器。`AUDIO-PACING`每5秒累计报告恢复次数、跳过源帧数、丢弃队列帧数和最大延迟。该策略修复本地突发补拍，不表示已消除车机端网络抖动或UDP丢包。

CarWith新增`CARWITH-CHANNEL`诊断：MEDIA、TTS及VR每5秒输出接收字节、服务类型分布、解密API结果、PCM字节/非零样本、转发或丢弃计数；空闲也输出零计数，EOF/错误补终止摘要。573已按本机VRAudioManager实现独立VR回复接收，要求认证、准入、加密及有效PCM16初始化；未知VR服务只统计。日志不记录音频内容或识别文字。

## 573：BMW ID8.5 独立 SIRI / 导航输出

以下路径依据 xiap2 及 libAirPlay 固件实现。盒子的两轮成功日志未测试 SIRI 和导航。独立导航对全部车机档案及画面来源按本轮 `/info` 能力启用：同一条 `audioFormats` 记录须声明 `type=101`、`audioType=default` 且 `audioOutputFormats` 含 bit30（Opus48k 单声道）。独立助手/SIRI 同样不再限制档案，要求同一条记录的 `type=100`、`audioType=speechRecognition`、`audioOutputFormats` bit29（Opus24k 单声道），麦克风输入声明不代替输出声明。只有收到对应 PCM 才申请资源并建立独立流；语音仍经过主音频借用、暂停/关闭、语音流释放、结束资源确认和按原编码/采样率恢复媒体。未声明对应输出格式时沿用媒体混音；SETUP 拒绝时确认清理并恢复媒体后只回退一次，清理未完成则保留有界 PCM 等待，资源归属拒绝不绕过。新增覆盖范围的实车播报、语音和音量恢复仍待复测。

| 输入类别 | AirPlay流 | 编码与时钟 | 生命周期 |
|---|---|---|---|
| 媒体MEDIA | 原media type100/102 | 保留用户选定PCM/AAC；当前PCM不改成AAC | 仍受主音频资源门禁 |
| 助手VR回复 | type100 / speechRecognition，audioFormat bit29 | OPUS24k、mono、SPF480、20ms；RTP每包+480 | Speaking及临时mainAudio借用；暂停/退掉旧媒体，结束后重新SETUP原媒体 |
| 导航TTS | type101 / default，audioFormat bit30 | OPUS48k、mono、SPF960、20ms；RTP每包+960 | 仅导航appState；duck→SETUP；停UDP→unduck→TEARDOWN |

OPUS使用随APK静态链接的libopus，输入仍为48k立体声，编码前下混为单声道，24k路径保留跨调用转换相位；不依赖Android私有`libopus.so`。每条流独立连接ID、流密钥、nonce、序号、采样时钟及同步socket。SETUP不虚构AAC的sr/ct/spf字段，延迟从对应`audioLatencies.outputLatencyMicros`读取。导航命令沿固件`durationMs=100.0`，duck volume=-12.0、unduck volume=0.2。

资源变化共用串行发布锁，ACK和业务成功后才提交。普通回复使用Nice优先级，实际语音按键触发的短期会话意图可使用UserInitiated；不因收到PCM而抢永久音频归属，也不为回复主动抢主屏。receiver重新接管会撤销旧lease；结束仅归还本次借用、清理仍由本段持有的appState，保留途中屏幕、导航和receiver所有权变化。

媒体泵确认暂停并关闭旧socket后才切SIRI。退出先停止独立发送，TEARDOWN和资源归还确认后再恢复原PCM/AAC媒体，使用新连接ID/密钥；恢复被拒有界重试，仍失败则保留待恢复状态，由新媒体开始/新源触发。清理失败保留lease和阶段，避免永远卡Speaking或重复开启冲突type100；结束中的SETUP在关闭预算后进入最终会话TEARDOWN列表。导航不暂停媒体泵，两个辅助worker的RTSP等待不会阻塞媒体节拍。

未声明对应能力或独立SETUP被拒且已确认清理时，可回退原媒体混音；资源拒绝/无法确认清理则不绕过授权播放。音量恢复与流 TEARDOWN 分别保存成功应答，失败重试只补未完成步骤，避免重复关闭已释放的流或重复恢复音量；降低音量阶段失败且未发送 SETUP 时，只补音量恢复，不关闭未分配的流，不将任意 404 当作清理成功。新INIT清旧队列，正常STOP排尾，过期tick只淘汰同一源代次样本，避免旧流丢弃新回复。`AUX-AUDIO`记录每类流建立、清理/恢复、发包和队列计数；本地发送与测试通过仍需实车确认听感和互操作。

### 自绘高德分流与DiPlay验证

实机高德导航使用USAGE_MEDIA，不能仅按USAGE_ASSISTANCE_NAVIGATION_GUIDANCE抓取。573同批新增shell AudioPolicy双LOOP_BACK mix：导航匹配现查的高德UID，MEDIA排除同一UID，分别使用createAudioRecordSink生成的独立地址，禁止同时保留旧address0全局submix。UID隔离的是高德应用的播放，不能宣称识别了它内部所有音频的业务语义。

导航PCM经有界50ms预缓存与400ms尾静音门控，首个非零段发送独立START/DATA，静音结束只发一次STOP，避免零PCM永久duck。两个reader受同一代次约束，单路失效或owner死亡会回收两路与policy，再由父端一次性退回legacy系统混音。split不能建立时不单独抓导航，避免双播；type101被拒且已清理时，已有互斥捕获的导航才混回MEDIA一次。重连复用采集时重发新段INIT，退出地图全屏不停止导航捕获。

2026-11-15 DiPlay实际验证：系统policy已显示互补UID规则和两条不同mix地址；一轮导航播报时NAV非零PCM增加、MEDIA计数不随之增加。修复DiPlay被desktop标记整体禁用辅助流后，23:13:56和23:14:05两次建立type101/OPUS48k/mono/SPF960，分别发送165/450包后排空关闭，主媒体始终PCM48k双声道/SPF352。用户确认导航正常、音乐降低后恢复，未见该轮辅助SETUP/清理异常。这是DiPlay验证，不替代ID8.5上车测试。

## ID6 / NBT Evo 实验路径

独立 ID6 档案只接受 NBT Evo 身份，并按本轮 `/info` 的 media 输出能力依次选择 AAC-LC 44.1 kHz、AAC-LC 48 kHz、PCM 48 kHz、PCM 44.1 kHz；缺失声明不猜测格式。手动 PCM 只尝试声明的 PCM 两采样率。顺序为屏幕 TCP、音频 SETUP、单次成功 RECORD，失败不能伪装激活。

ID6 的采集以 48 kHz 为基准，媒体协商 44.1 kHz 时使用保留相位的流式重采样。MediaCodec、SETUP 与 RTP 样本时钟采用实际协商采样率，AAC 每包 1024 帧、PCM 每包 352 帧。资源接管时丢弃旧缓存，归还时按协商采样率重建编码器。实车音画仍待验证。

## 资源归属与恢复

音频 SETUP 成功仅表示流存在。车机通过 changeModes 接管音频后，本端停止 RTP，保留控制及视频。屏幕归还、requestUI 或持续 PCM 不等于音频已归还。

主动点击音乐卡片播放，以及当前 MediaSession 从暂停/停止经过缓冲恢复播放时，排队用户级音频资源申请。CarLife 新 MEDIA_INIT 只作普通级申请；TTS、持续 PCM、首次附着到已播放会话、播放中的再次缓冲均不冒充用户操作。队列有界，两秒前的意图过期，普通请求不能继承旧操作的用户优先级。

申请遵守 take/unborrow 限制，音频关闭时不申请。准备声明不开放 RTP；收到 RTSP 200 且业务响应未拒绝后才提交。业务拒绝、非法响应、超时或新的音频接管都不能开放旧授权。等待期间仅有屏幕事件时，最多用最新完整 modes 再申请一次，不覆盖新屏幕状态，也不因屏幕变化直接恢复旧声明。

恢复仅针对资源门控，不修复采集全零。持续播放中后来被车机接管，不会因每包 PCM 自动抢回；暂停再播放可触发申请。CarWith 的用户级恢复依赖音乐 App 的状态回调和可用通知监听。

## 采集与路由

自绘采集每五秒检查一次窗口。仅当整窗为零、系统媒体活跃且路由未进入 REMOTE_SUBMIX 时，尝试一次媒体策略修复；已进入采集路由时调整其独立音量。结束时仅还原仍由本次设置的偏好，保留用户后来改动。

peak=0、rms=0 表示输入为零；active=false 优先核实播放器是否真正输出。muted=false、音量非零仍不足以证明存在音乐。手机/车机都无声也不能直接证明两套采集冲突。

## 已观察证据

| 样本 | 确认事实 | 不能据此认定 |
|---|---|---|
| 2026-11-04 Xiaomi M154FF / Android 17 测试音 | 指定其它输出可复现全零；约五秒路由修复后出现非零 PCM，peak=3864 | 当天所有车机静音都由同一路由问题引起 |
| hypercar-log-20261107-175436.txt 下午 | 17:26:12 音频转为车机所有；随后约 95 秒有媒体队列但发包为零，RTP 固定1832，视频仍正常 | 车机接管一定是误操作，或已证明网络丢包 |
| 同文件早上自绘 UI | 连续全零，active=false；07:57:24 才检测有效信号，约半秒后事件通道关闭 | 播放恢复后的稳定采集仍失败 |

该反馈采用大众档案，但接收端自报 Mazda_58b2 / CarLetter，不能单凭档案名称确认大众原厂型号。原件 SHA256：`3a3e5f8ce8900fa4743e6de01ffa5cd6b8637fdbd6449a2d0c2761a8df4e25fc`；原始资料不随仓库分发。

## 诊断顺序

先核实播放器、源幅度与实际路由，再看音频归属、资源应答和 RTP。`AUDIO` 日志的 owner_phone、blocked_samples 与实际发包数区分门控、源静音及发送失败；`AUDIO-RESOURCE` 记录申请、拒绝和过期。RTSP 或 UDP 写入不是扬声器出声确认。语音麦克风见[输入与语音](INPUT-AND-VOICE.md)，测试边界见[验证](VALIDATION.md)。

实现：`ShellPlaybackAudio`、`CarAudioCapture`、`audio_resource.rs`、`audio_out.rs`、`modes.rs`、`engine.rs`。

AAC 后台编码线程使用创建它的引擎退出计数。即使 pump 已退出，编码器的
`stop/delete` 尚未完成时也不能确认旧引擎完全退出。释放 AudioOut 只断开
输入/输出队列，不在实时音频线程执行阻塞 join；编码器析构完成后由工作线程
释放计数。编码异常、满输出队列及 panic 同样按此顺序清理。


## 644 输入收音实验

当前两种画面来源均开启音频转发。CarWith 默认使用车机麦克风，在语音识别请求到达后协商车机输入。自绘 UI 的收音测试和语音字幕保持关闭。以下记录旧版实验行为。自绘 UI 当时可在重连后长按语音键测试收音。只协商已声明的type107/speechRecognition/Opus16k单声道输入，通过车机网络绑定UDP接收、Input密钥认证解密、Android NDK Opus解码，仅统计PCM音量。最长20秒，不保存录音，不送往助手；独立connectionID和单流TEARDOWN隔离媒体输出。设置关闭时不创建收音任务。详见[输入和语音](INPUT-AND-VOICE.md)。


645新增独立的CarWith车机麦克风实验开关，默认关闭。启用后由CarWith识别开始/结束业务请求驱动车机type107收音，将Opus解码输出转换为16k单声道PCM16，经现有CarLife VR加密上行到内置小爱；不改变手机麦克风默认选项、自绘UI和媒体输出候选。详见[CarWith来源](CARWITH-SOURCE.md)。
