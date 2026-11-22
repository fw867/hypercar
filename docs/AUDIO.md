# 音频采集、资源与无声排查

更新：2026-11-07。实现与已验证范围分别描述，出声不能只凭 connected 或 RECORD 判断。

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

设置「画面与声音 → 音频格式」提供自动、PCM、AAC优先，下次连接生效。自动保持旧车型的PCM48k首选；ID9只尝试/info明确声明的媒体输出格式，PCM48不可用但声明AAC48时用AAC。AAC优先只在type102/media/output声明AAC48时尝试，否则保留可用PCM路径；仅AAC44的车机本次仍不支持。DiPlay已实测AAC协商成功、约47包/秒，用户确认出声；不等于ID9实车验证。参考[CarPToCarL](https://github.com/jjuugk/CarPToCarL)的AAC裸帧和RTP约定，编码/发送实现位于Rust核心。

AirPlay PCM 使用 SPF352，约 136.36 包/秒，16-bit 大端样本。TTS 参与闪避混音；持续全零样本不会一直保持“播放中”。

实时主媒体 PCM/type100 和 AAC/type102 在 SETUP 前于会话网络绑定 UDP 端口，并写入 `streams[].controlPort`。车机返回控制端口后，沿用同一个 socket 收 RTCP 和发同步包。车机省略控制端口时，仍保留通告的监听端口，只接收来自本轮数据端点 IP 的请求，回包发往请求源端口。PCM SETUP 遇到 400、451 或 461 时去掉本地端口重试一次。对照日志按 `streamConnectionID` 核对 `advertisedControlPort`、`localPort` 和 `peerControlPort`。支持 8 字节和附带 NTP 时间戳的 16 字节 `0xD5`，长度字段分别为 1 和 3。Apple 接收端参考代码的主动重传路径在 MainHighAudio/type102。PCM/type100 是否会请求补发，仍取决于车机实现。

HyperCar 收到有效 `0xD5` 后，以 16 位 RTP 序号查找本流最近 500 ms 内成功发出的包，最多保留 PCM 72 包或 AAC 32 包。命中时回 `0xD6`，4 字节头后附上原 RTP 数据（含密文、认证标签及原 nonce）；未命中时按盒子 `xiap2` 的 `_audioReceiveRTCP`（0x40500）回八字节的未命中响应，携带原序号。头部长度使用盒子的小端字节数。每请求最多处理 16 个序号，每秒最多尝试回复 32 次。只有当前会话和当前音频授权的有效缓存会用于补发。`AUDIO-RTCP` 的 `d6Sent` 统计实际补发，`d6FutileSent` 统计短回应，两者均不计入正常发包率和码率。

571音频节拍：绝对周期不变，漏拍时跳过已过期的352帧时段，丢弃混音队列中对应旧样本并推进RTP时间戳；不再Burst集中追发。恢复首包置marker并重新发送时间同步，包序号及AEAD nonce不重置。AAC保留编码器半帧和重采样相位，避免每次漏拍同步重建编码器导致持续饥饿；音频资源被接管时仍按原策略清理编码器。`AUDIO-PACING`每5秒累计报告恢复次数、跳过源帧数、丢弃队列帧数和最大延迟。该策略修复本地突发补拍，不表示已消除车机端网络抖动或UDP丢包。

CarWith新增`CARWITH-CHANNEL`诊断：MEDIA、TTS及VR每5秒输出接收字节、服务类型分布、解密API结果、PCM字节/非零样本、转发或丢弃计数；空闲也输出零计数，EOF/错误补终止摘要。573已按本机VRAudioManager实现独立VR回复接收，要求认证、准入、加密及有效PCM16初始化；未知VR服务只统计。日志不记录音频内容或识别文字。

## 573：BMW ID8.5 独立 SIRI / 导航输出

以下路径依据 xiap2 及 libAirPlay 固件实现。盒子的两轮成功日志未测试 SIRI 和导航。MGU/BMW ID8.5 及 DiPlay 按本轮 `/info` 声明启用对应的辅助流。BMW ID9 使用 CarWith 时，仅当车机声明 101/default 输出支持 bit30，且 9241 已收到导航 PCM，才申请导航状态并建立独立流。ID9 的实车播报和音量恢复待复测。

| 输入类别 | AirPlay流 | 编码与时钟 | 生命周期 |
|---|---|---|---|
| 媒体MEDIA | 原media type100/102 | 保留用户选定PCM/AAC；当前PCM不改成AAC | 仍受主音频资源门禁 |
| 助手VR回复 | type100 / speechRecognition，audioFormat bit29 | OPUS24k、mono、SPF480、20ms；RTP每包+480 | Speaking及临时mainAudio借用；暂停/退掉旧媒体，结束后重新SETUP原媒体 |
| 导航TTS | type101 / default，audioFormat bit30 | OPUS48k、mono、SPF960、20ms；RTP每包+960 | 仅导航appState；duck→SETUP；停UDP→unduck→TEARDOWN |

OPUS使用随APK静态链接的libopus，输入仍为48k立体声，编码前下混为单声道，24k路径保留跨调用转换相位；不依赖Android私有`libopus.so`。每条流独立连接ID、流密钥、nonce、序号、采样时钟及同步socket。SETUP不虚构AAC的sr/ct/spf字段，延迟从对应`audioLatencies.outputLatencyMicros`读取。导航命令沿固件`durationMs=100.0`，duck volume=-12.0、unduck volume=0.2。

资源变化共用串行发布锁，ACK和业务成功后才提交。普通回复使用Nice优先级，实际语音按键触发的短期会话意图可使用UserInitiated；不因收到PCM而抢永久音频归属，也不为回复主动抢主屏。receiver重新接管会撤销旧lease；结束仅归还本次借用、清理仍由本段持有的appState，保留途中屏幕、导航和receiver所有权变化。

媒体泵确认暂停并关闭旧socket后才切SIRI。退出先停止独立发送，TEARDOWN和资源归还确认后再恢复原PCM/AAC媒体，使用新连接ID/密钥；恢复被拒有界重试，仍失败则保留待恢复状态，由新媒体开始/新源触发。清理失败保留lease和阶段，避免永远卡Speaking或重复开启冲突type100；结束中的SETUP在关闭预算后进入最终会话TEARDOWN列表。导航不暂停媒体泵，两个辅助worker的RTSP等待不会阻塞媒体节拍。

未声明对应能力或独立SETUP被拒且已确认清理时，可回退原媒体混音；资源拒绝/无法确认清理则不绕过授权播放。新INIT清旧队列，正常STOP排尾，过期tick只淘汰同一源代次样本，避免旧流丢弃新回复。`AUX-AUDIO`记录每类流建立、清理/恢复、发包和队列计数；本地发送与测试通过仍需实车确认听感和互操作。

### 自绘高德分流与DiPlay验证

实机高德导航使用USAGE_MEDIA，不能仅按USAGE_ASSISTANCE_NAVIGATION_GUIDANCE抓取。573同批新增shell AudioPolicy双LOOP_BACK mix：导航匹配现查的高德UID，MEDIA排除同一UID，分别使用createAudioRecordSink生成的独立地址，禁止同时保留旧address0全局submix。UID隔离的是高德应用的播放，不能宣称识别了它内部所有音频的业务语义。

导航PCM经有界50ms预缓存与400ms尾静音门控，首个非零段发送独立START/DATA，静音结束只发一次STOP，避免零PCM永久duck。两个reader受同一代次约束，单路失效或owner死亡会回收两路与policy，再由父端一次性退回legacy系统混音。split不能建立时不单独抓导航，避免双播；type101被拒且已清理时，已有互斥捕获的导航才混回MEDIA一次。重连复用采集时重发新段INIT，退出地图全屏不停止导航捕获。

2026-11-15 DiPlay实际验证：系统policy已显示互补UID规则和两条不同mix地址；一轮导航播报时NAV非零PCM增加、MEDIA计数不随之增加。修复DiPlay被desktop标记整体禁用辅助流后，23:13:56和23:14:05两次建立type101/OPUS48k/mono/SPF960，分别发送165/450包后排空关闭，主媒体始终PCM48k双声道/SPF352。用户确认导航正常、音乐降低后恢复，未见该轮辅助SETUP/清理异常。这是DiPlay验证，不替代ID8.5上车测试。

## ID6 / NBT Evo 实验路径

独立 ID6 档案只接受 NBT Evo 身份，并按本轮 `/info` 的 media 输出能力选择 AAC-LC 44.1 kHz、48 kHz 或 PCM 48 kHz；缺失声明不猜测格式。手动 PCM 选择仍受输出能力限制。顺序为屏幕 TCP、音频 SETUP、单次成功 RECORD，失败不能伪装激活。

采集/混音保持 48 kHz；仅 AAC 44.1 kHz 发送端做保留相位的流式重采样。MediaCodec、SETUP 与 RTP 样本时钟采用实际协商采样率，AAC 每包 1024 帧；资源接管丢弃旧缓存，归还时保留采样率重建编码器。该分支不改变 MGU/ID9 的默认格式和激活顺序；实车音画仍待验证。

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


## 644 输入收音实验

当前两种画面来源均开启音频转发。CarWith 默认使用车机麦克风，在语音识别请求到达后协商车机输入。自绘 UI 的收音测试和语音字幕保持关闭。以下记录旧版实验行为。自绘 UI 当时可在重连后长按语音键测试收音。只协商已声明的type107/speechRecognition/Opus16k单声道输入，通过车机网络绑定UDP接收、Input密钥认证解密、Android NDK Opus解码，仅统计PCM音量。最长20秒，不保存录音，不送往助手；独立connectionID和单流TEARDOWN隔离媒体输出。设置关闭时不创建收音任务。详见[输入和语音](INPUT-AND-VOICE.md)。


645新增独立的CarWith车机麦克风实验开关，默认关闭。启用后由CarWith识别开始/结束业务请求驱动车机type107收音，将Opus解码输出转换为16k单声道PCM16，经现有CarLife VR加密上行到内置小爱；不改变手机麦克风默认选项、自绘UI和媒体输出候选。详见[CarWith来源](CARWITH-SOURCE.md)。
