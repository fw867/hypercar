# 方向盘媒体键与语音

更新：2026-11-14。媒体键和语音键共用会话输入入口，但分别路由。

## 媒体键

会话内 /info 的 hidDevices（兼容 hids/hid）按 UUID 读取 HID 描述符。Consumer 页按设备、Report ID 和 Input 位偏移解析，区分数组索引、位图、Usage 范围与常量补齐，支持全局 Push/Pop。非法、截断和超限报告不生成按键。

按设备及 Report ID 只转发新按下沿；重复按下不重复切歌，松开清除状态。无描述符时仅兼容明确的直接 Usage ID，不把 04 固定猜成下一曲；未识别短报告有限记录。媒体设备与既有触摸解析分离。

支持播放、暂停、播放/暂停、上一曲、下一曲、停止及基础快进/快退；未实现按住连续快进/快退。Rust 接收任务随引擎关闭取消，经 JNI 进入 MusicBridge 主线程，优先派发到所选 MediaController；不存在、拒绝或异常时回退 Android 系统活跃媒体会话。CarWith 和自绘 UI 共用，不要求音乐卡片可见。

`HID-KEY` 记录描述符注册、原始候选及按下 Usage；`MEDIA-KEY` 记录 Android 键码、目标和路由。派发成功不表示播放器实际执行。历史日志中的一字节 04/00 已证明原有解析及回调链有缺口，但该用户车机的新描述符及实际切歌仍待复测。

## 独立 iAP2 媒体通道（643）

蓝牙与Wi-Fi CSM都解析IdentificationInformation的参数0x12，按组件ID隔离MediaPlaybackRemote(function=1)，只消费本轮明确声明的媒体组件。StartHID(6800)从参数4读取描述符；AccessoryHIDReport(6802)参数0定位组件、参数1取真实报告；StopHID(6803)释放注册。6801是描述符定义的设备输出报告，不作为通用ACK，不再向硬编码组件0发00报告。

媒体组件会话覆盖认证等待、初始凭据交换及持续保活，认证阶段完成前有界暂存。蓝牙按CSM线序处理，包括同次读取中的身份→注册，以及凭据等待期间晚到的重新识别；相同身份/相同描述符幂等，组件声明改变、停止、重置及传输重建清理旧状态。Wi-Fi的重新识别和链路重置同样需要重新登记组件。每一传输独立保存Report ID、按下/释放状态。

描述符解析支持数组/位图、Usage范围、逻辑最小值、常量补齐、Report ID和全局Push/Pop，受4096B描述符/输入大小、64字段/认证等待队列及组件数上限约束。未知组件、键盘/触摸、缺失注册、畸形或截断报告不猜Usage，04也不固定映射为下一曲。新按下沿通过既有MusicBridge派发到所选播放器，后台排队时再次核对连接尝试和蓝牙票据/Wi-Fi worker，已结束会话不能控制新会话。

AirPlay hidSendReport解码和大众已验证路径保持原行为。没有新增DETECT、强制HID启用或A2DP操作；仅具备组件声明不代表车机已发送StartHID，更不代表实车切歌已成功。诊断使用IAP2-MEDIA观察声明/注册/报告边沿，随后用MEDIA-KEY确认派发。

宝马本轮持续音视频连接中，Wi-Fi iAP2 未收到对端初始化，未见媒体 StartHID/AccessoryHIDReport；AirPlay HID 只声明返回键，未见媒体报告或 MEDIA-KEY 派发。经典蓝牙随后由车机结束，系统 AVRCP 也未建链。该轮方向盘切歌仍未验证成功，收到 requestSiri 仅证明独立语音入口可达。应继续核对媒体控制通道的交接与激活。

## iAP2 媒体卡（563）

曲目信息与媒体按键分别处理：`5000` 订阅、`5001` 更新、`5002` 停止，不能作为切歌指令解释。蓝牙和 Wi-Fi 共用 Iap2NowPlayingSession；来源为 MusicBridge 的缓存快照，订阅后先回当前状态，稍后元数据到达继续补推，停止或重建后不继续旧订阅。

媒体条目组包含歌名、时长、专辑（参数6）和歌手；播放组包含状态、进度及应用名。正文直接交给 CSM 编码器，避免双重帧头。封面编号只在存在可发送字节及已协商文件会话时声明；文件流独立于 CSM、按对端会话 ID 发送。SETUP 后等待 START，块大小服从对端 maxLength，最后等待 SUCCESS，拒绝或超时保留文字；旧图、旧编号不覆盖新图。一个传输最多使用255个不同编号，不循环污染车机缓存。

图像转换在单独线程完成，原始 Bitmap 仅弱引用缓存，限最长边320像素、256KiB；每次协议轮询最多两个文件块。文件重试最多一次，失败不主动销毁 CarPlay，也不伪造 ACK；HID、导航、认证及控制消息仍使用原有路径。诊断只记录 IAP2-NOWPLAYING 的状态、是否有封面和传输结果，不记录曲名。测试及实车边界见[验证记录](VALIDATION.md)。

## 语音入口与释放

支持 AirPlay requestSiri 的 siriAction=1/2/3，以及 Consumer HID 0xCF。预热不启动；两条入口共享去重，重复按下不重复唤醒；释放只清除物理按键状态，不按长按时长猜测“结束识别”。无效动作忽略，十五秒无释放令旧状态失效，源重建清除状态。

568 曾参考第三方记录，在按住达到1.5秒后松开自动发送 VR_STOP。审计确认这不能区分“按住等提示、松手再说”和 PTT，本项目没有足够实车证据，因此已移除该推断。收尾交给助手的静音检测、超时或用户取消；当前不提供按住说话模式。

## 按来源路由

| 会话来源 | 唤醒方式 | 麦克风与边界 |
|---|---|---|
| CarWith | 既有加密 CTRL 通道，0x68008，protobuf field1=0x21 VR_START | 功能配置 VOICE_MIC=1，使用手机麦克风；未就绪或过期命令丢弃 |
| 自绘 UI | 后台 Shizuku 执行系统 KEYCODE_VOICE_ASSIST=219，由 Android 选择默认助手 | 使用手机麦克风；遵守首次启用、锁屏与权限限制，助手界面不保证显示在车机 |

CarWith 保留 CONTENT_ENCRYPTION=1，配置项总数为2。语音命令两秒过期、重连清空，和触摸共用串行加密写入。自绘请求执行前检查当前连接标识；CarWith 失败不改为唤醒另一个助手。

默认语音路径仍使用手机麦克风；644新增默认关闭的车机麦克风输入实验，范围见下文。不宣称完整CarPlay Siri或车载电话能力，系统播放采集不录麦克风。

## 证据与复测

本机系统语音键已使默认小爱服务显示会话（dumpsys voiceinteraction：mShown=true）。同机 cmd voiceinteraction show 被权限拒绝，故未使用。CarWith 硬键及 VOICE_MIC 值已对照本地 CarLife CommonParams / FeatureConfigManager，并参考 [CarPToCarL 固定版本](https://github.com/jjuugk/CarPToCarL/tree/6346bf7b63b052b2764eb19f1495891bc833c152)；该项目的时长经验不视为本项目协议要求。

需要实车分别验证：媒体键按一次/按住/松开，CarWith 语音界面与手机取音，自绘默认助手唤醒、取消及再次唤醒。`VOICE-KEY` 的命令排队/写入成功不等于识别成功。最新测试结果统一记录在[验证](VALIDATION.md)。


## 2026-11-13 宝马媒体控制通道审查（643之前的证据）

大众车主已反馈切歌成功，应保留现有AirPlay hidSendReport→Consumer HID→JNI→MusicBridge链路，不因宝马问题整体改写或按04猜下一曲。

今早642的MGU-55三次0x1D01中，顶层参数0x12均有独立iAP2 HID组件：子参数0=0001（组件ID1）、1=CinemoHIDComponent、2=01（MediaPlaybackRemote）。它与AirPlay /info中device0的AC Back、device1的触摸是不同接口。前一轮只看AirPlay HID得出“未声明媒体键”的表述不完整；正确结论是宝马在iAP2声明了媒体遥控组件，而今日尚未观察到其StartHID/实际按键报告。

源码缺口：Iap2Messages.parseIdentification只提取EA/无线组件，遗漏0x12 HID组件。Iap2SenderSession仅在短暂awaitOptionalHid窗口处理0x6800，不保存参数4描述符，0x6801输出报告硬编码组件ID0，与本车声明的ID1不一致；0x6801本身是DeviceHIDReport，不能普遍称为StartHID业务ACK。保活泵没有持续处理0x6800/0x6802/0x6803，晚到报告只日志化。WifiIap2Protocol对这几条消息同样ACK-only，认证前也没有HID订阅暂存；没有将iAP2参数1中的HID报告按参数0的组件ID和对应描述符交给媒体键派发。链路ACK不能替代上述业务注册、报告解码与回调。

参考[CatPlay固定版本的CSM定义](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/core/catplay_csm/src/msg/iap2.rs)：iAP2HIDComponent.function=1为MediaPlaybackRemote；StartHID参数0组件ID/4描述符，AccessoryHIDReport参数0组件ID/1实际报告，StopHID参数0组件ID。不能把组件号或任意短参数当报告。CarPToCarL本地已核对版本的VirtualIap2Link另有0x6800/0x6802注册/解码路径，支持这是一条独立媒体通道，但本田/奥迪初始化时序不能直接认定适用MGU。

初始化边界：本轮Wi-Fi iAP2一直WAIT_PEER_LONG，未收到iAPSendMessage；SETUP enabledFeatures也未出现iAPChannel。因此不能仅凭等待状态强行加主动DETECT或无限重试，更不能保证补上0x6802解码就立即恢复宝马切歌。应先按组件能力补全数据消费，再核实本车使用的蓝牙/Wi-Fi载体及启动条件。大众已成功的AirPlay报告格式、握手和音频路径保持原行为；任何新的主动初始化策略必须独立受控并有收发证据。


## 2026-11-13 车机麦克风第一阶段（644）

设置→音频→“车机麦克风收音测试（实验）”，默认关闭，下次连接生效，仅自绘UI可启用。开启后长按方向盘语音键开始测试，再次长按停止；最多20秒，松开按键不结束。本模式不唤醒手机助手，不写录音文件、不播放PCM、不接入语音识别。关闭实验并重连恢复原语音路径，CarWith路径不变。

只接受会话内/info明确声明的type107、speechRecognition、audioInputFormats含Opus16k单声道bit28，且须已有配对共享密钥。不尝试占用媒体type100。SETUP带独立streamConnectionID、input=true、spf320、本机dataPort/controlPort；接收UDP使用现有车机网络绑定工厂。采用DataStream-Input-Encryption-Key派生密钥，校验ChaCha20-Poly1305认证、RTP格式与重放，再由Android Opus解码器输出PCM16；按实际解码采样率统计包数、样本数、峰值/RMS、丢包及解码错误。日志标签MIC-PROBE，不记录音频内容。

SETUP预算3秒，无认证音频5秒或音频中断3秒退出；用户停止、超时、拒绝和异常均关闭输入socket/codec，并在控制连接仍可用时按type107+streamConnectionID发单流TEARDOWN，不主动拆type100/102/110。断开整个会话时原有任务容器关闭输入任务。

这是能力受控的接收试验，不是已验证的宝马收音功能。参考CatPlay固定版本fc52bac7910a07ff3b6d94712175db6013528aa9的rtp_receiver、rtp_cipher_chacha、cipher/chacha、stream_desc；独立107来自本车/info声明，参考枚举对107亦未完整实现。真实车机是否接受SETUP、是否还需语音资源借用、是否发送该格式，以及实际说话能否形成有效PCM仍需实车日志验证。不接入小爱，不等同于助手可使用车机麦克风。


## 2026-11-13 小爱外部音频调查：优先CarWith链路

本机实查：手机小爱com.miui.voiceassist 8.2.70.8421、语音引擎com.xiaomi.mibrain.speech 1.6.5；CarWith com.miui.carlink 4.0.23-20261016与小米CarLife com.baidu.carlife.xiaomi 8.8.5的已安装版本与本地分析APK版本一致。分析文件保存在D:/devtools/hypercar-diagnostics/xiaoai-input和carwith-mic，未打包进应用。

手机小爱与CarWith内置小爱是不同应用里的引擎。前者AsrService使用自定义签名权限BIND_SPEECH_RECOGNITION_SERVICE；OSbotSpeechService使用签名或系统特权权限BIND_SPEECH_SERVICE，getCapabilities仅声明audio_source=internal_mic；ExternalAsrService使用EXTERNAL_AGENT签名或系统特权权限。ADB shell尝试启动三项服务均在服务权限检查处被拒绝，尚未传入音频FD，不能称为已完成识别测试。Android EXTRA_AUDIO_SOURCE是可选外部ParcelFileDescriptor，识别器不支持时会改用自己的麦克风，因此不能把弹出小爱或返回识别结果作为使用车机麦克风的证明。参考：https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE 。

CarWith链路找到了更具体的源码证据：

1. CarLife IAudioRecordToolImpl.onReceiveVrData解密VR音频；ReadCarRecordDataThread.writeAudioData在VR唤醒、提示音结束、混投屏和usePhoneVr等条件下，通过MixScreenManager.onReceiveAudioStream传出16000/4/2（16k单声道PCM16）。usePhoneVr与usePhoneMic是不同状态，不混淆。
2. CarWith CarlifeAudioListener.onReceiveAudioStream将音频写入h1.a（AssistantHuMic）的队列；PhoneAudioCast.B在CarLife路径从该队列读取，经DataBusService.readAssistantRecorderData交给ae.j（HuMicRecorder）。
3. AudioRecordTask通过ae.l（Recorder工厂）选择HuMicRecorder或ae.k（NativeRecorder）；前者读取上述队列，后者直接AudioRecord录手机。HuMicRecorder.f还请求setVoiceAssistantActive和startAssistantRecorder。SpeechEngineTask使用AsrRequest.DATA_INPUT_MODE_BUFFER交给内部识别引擎，存在完整外部音频消费路径，但不是对第三方导出的通用PCM接口。
4. LauncherDataBusClient.onAwakenVoiceAssistant(byte[],16000,4,2)是另一条入口，经ConversationManager.h→af.d→bf.c进入唤醒词检测。它不能单独证明完整ASR接入，不能把唤醒词回调误当作助手实时音频写入接口。

现有HyperCar缺口：carlife/session.rs固定VOICE_MIC=1；CarLife VrManager.isUsePhoneMic在micStatus=1或用户“使用手机麦克风”开关打开时为true。carlife.rs的VR socket仅drain_stream，没有输入写入者。644麦克风实验仅自绘UI、只统计PCM，也尚未向CarLife输出。这解释了已有VR_START按键通路为何不等于车机麦克风上行。

CarWith内部DataBusService清单exported=false；本机adb am startservice明确返回Requires permission not exported from uid 10156。公开VoiceService主要接受启动/停止/文本请求，尚未找到供HyperCar直接传入实时PCM的导出入口。不为取用该接口修改第三方包、替换签名或默认启动整个CarWith；仅凭安装了手机小爱也无法解除上述限制。

可行的下一阶段候选是CarWith模式走既有已认证CarLife传输：车机type107解码PCM→按实际解码采样率转换为16k/mono/PCM16→受限队列→CarLife VR上行→CarWith HuMicRecorder→内置小爱。需同时校正VOICE_MIC、用户麦克风选择与VR_START/停止业务状态，沿用连接加密及取消清理，并验证识别期间读队列而非NativeRecorder。不能只把VOICE_MIC改0或把PCM塞进唤醒词回调；自绘UI独立复用手机小爱仍未找到可用入口。本次只完成接口与源代码调查，未更改644运行逻辑、未新增构建，尚无真实车机PCM到ASR的成功证据。


## 645 CarWith车机麦克风实现

已把前节的CarWith候选链路接成默认关闭的实验：独立carwith_microphone设置仅CarWith模式下传，原microphone_probe仍仅自绘UI。语音键继续请求CarWith VR_START；CarLife认证就绪后0x10024触发捕获，0x10023停止，避免一开连接便持续收音。启用时VOICE_MIC=0，关闭保留1；上游type107解码PCM转换成16k/mono/PCM16，经有界队列和现有AES加密发VR 0x58001。输入结束发0x58009；最长20秒及异常退出，不保存录音。下一步实车验证输入包、CarWith选择车机麦克风与实际识别，未宣称已成功识别。详见[CarWith来源](CARWITH-SOURCE.md)。


646设置可见性修正：音频页始终显示CarWith麦克风开关，自绘模式置灰并提示切换画面来源。AudioSection显式接收startup.source对应的模式参数，避免直接读取非Compose状态的SharedPreferences造成跨区块切换后显示滞后。仅修改设置呈现，原生音频链路保持645版本。

## iAP2 语音键与收音路径完善（2026-11-13）

蓝牙与 Wi-Fi iAP2 的 MediaPlaybackRemote HID 现支持 Consumer 0xCF。按组件和 Report ID 聚合按下状态；重复报告不重复启动，最后一个持键组件释放才产生内部释放边沿。StopHID、描述符替换、重新识别或链路重置会释放旧状态，仍要求本轮组件声明、认证和 StartHID 注册，非法报告不推测按键。HyperCarRepo 将这两个语音边沿经 JNI 送到原生同一个 VoiceKey 队列，与 AirPlay requestSiri/hidSendReport 共用去重、CarWith VR_START、自绘手机助手或显式开启的麦克风测试路由。释放只清物理按键状态，不停止识别。原生输入携带引擎 token；停止中、无引擎、旧 token 或非按下/释放动作均不接收，旧传输仍受现有 connectionAttempt/归属检查。

收音继续使用 /info 声明的独立 type107/speechRecognition/Opus16k 输入及已有 CarWith PCM 上行，不更换为主音频流附带端口的方案。两种入口各有接收端适用范围，尚无同车对照能证明任一方式普遍更好。完善现有107 RTP解析：按 CSRC 和扩展头长度定位密文，保持 Input 密钥、timestamp/SSRC AAD、尾部 nonce、来源检查及重放/序号保护；超限、截断、错误类型及当前未支持的 padding 报文拒绝。收音仍为显式实验、按识别开始请求启动、有界时长、不保存录音，未新增自动收音或格式盲试。

## 自绘 UI 外部音频字幕验证（2026-11-14）

新增默认关闭的“车机语音字幕验证（实验）”，与仅统计音量的收音测试在设置页互斥；仅自绘模式下传 self_microphone_asr，CarWith 的独立开关不变。重连后用语音键开始/再次按下停止，单次车机输入最多20秒。复用已声明的107/Opus16k收音与认证解码，PCM经捕获ID和原生引擎代号校验回传Kotlin；不保存音频，不调用手机默认助手作为降级。

验证后端使用现成系统SpeechRecognizer，Android13+以ParcelFileDescriptor音频管道传入16000/mono/PCM16，申请中间结果并处理最终结果。只选择已启用、导出且使用平台识别绑定权限的服务；多个候选而没有有效默认值时要求先选择默认识别器。部分服务会忽略EXTRA_AUDIO_SOURCE并改用自身麦克风，因此识别结果始终标注“来源待验证”，写入管道的时长也不作为读取/采用音频的证明。包队列有界，管道非阻塞写入且有250ms预算，识别失败/超时/取消、旧会话和设置关闭都会清理任务；按捕获ID停止107，迟到取消不能停止后续识别。代码不自研语音模型，当前未比较各引擎识别准确率。

D1自绘界面顶部新增提示框，显示状态、实际partial/final文字与取消按钮；设置音频区也能查看结果及检查识别服务。不自动执行指令，中间文字不提交助手。仅最终非空结果提供“交给手机小爱”，通过用户点击启动手机主屏上的文本入口，保留D1字幕。接口为当前已安装小爱8.2.70的导出ShareReceiverActivity，action=com.miui.voiceassist.action.PUSH_QUERY、type=text/plain、extra assist_query；没有调用受签名/特权保护的SpeechQueryService。不假设这是跨版本稳定SDK，也没有回传小爱回复或执行成功状态。

手机核对：系统默认voice_recognition_service为空；查询仅发现com.xiaomi.mibrain.speech/.asr.AsrService，绑定权限为小米自定义signature权限，不能作为本应用可直接使用的标准后端。独立现成识别器仍需安装/启用并验证实际采用外部PCM；当前没有端到端语音字幕成功结论。用ASCII无害文本1+1调用小爱文本入口，Activity启动成功，手机日志确认handlePushQuery和VoiceService input_content收到相同文本，只证明文本送达引擎，不证明所有指令已执行。

Android app_process检查中，PCM队列字节/EOF检查通过；该辅助进程的识别服务检查受到system-context归属校验影响，不能当作HyperCar正常进程下的识别器启动结果。实际服务可用性结论来自独立package查询/权限及默认设置读取。本轮未安装替换HyperCar；临时测试文件已清理。

## 直接核对手机超级小爱的外部 ASR（2026-11-14）

对象为本机已安装的 `com.miui.voiceassist` / 超级小爱8.2.70.8421（508002070），不是CarWith内置小爱。本轮直接对已提取APK的ExternalAsrService、OSbotSpeechService和PublicAsrProvider进行定点反编译，并核对当前manifest及手机权限拒绝结果。

**发现真正的外部PCM入口**：`com.aios.osbot.external.ExternalAsrService`，action=`com.aios.osbot.action.EXTERNAL_ASR`，Binder接口`com.aios.apptoolsdk.aidl.IExternalAsrService`。`startAsr(AsrAudioConfig,IAsrCallback)`创建外部PCM识别会话并返回sessionId；`feedAudio(sessionId,byte[])`向ExternalAudioSource投喂；`stopAsr`结束识别，`cancelAsr`取消并释放。配置检查明确要求sampleRate=16000、channels=1、bitDepth=16、encoding=pcm（大小写不敏感），与我们解码后的目标PCM规格相符。回调含onPartialResult、onFinalResult、onSentenceEnd和onError；服务以continuous=true、enableCloudVad=true创建会话。由此确认“超级小爱内部没有外部音频识别接口”的说法不成立；正确结论是它有此接口，但当前应用没有调用授权。

**当前授权限制已实测**：服务exported=true，但manifest要求`com.aios.osbot.permission.EXTERNAL_AGENT`，系统定义为signature|privileged。其startAsr内部还会按Binder调用方UID再次checkPermission，权限不满足返回`error:PERMISSION_DENIED`，onBind另有CallerVerifier检查：非调试超级小爱会明确拒绝uid2000的ADB shell，并校验调用方是否为系统应用或通过指定签名校验。普通HyperCar签名无法仅靠新增uses-permission或用户录音授权取得该权限。手机ADB shell直接尝试进入服务被系统以Requires permission拒绝，尚未执行startAsr/feedAudio，因此没有识别成功证据，也不能用Shizuku的shell身份当作已取得此授权。

另外两路也已区分：`OSbotSpeechService`要求`com.xiaomi.speech.permission.BIND_SPEECH_SERVICE`（signature|privileged），getCapabilities的audio_source只声明internal_mic，不能当作外部PCM入口。`PublicAsrProvider`（content://com.miui.voiceassist.speech.api）要求`miui.permission.USE_INTERNAL_GENERAL_API`（本机同样为signature|privileged），start/stop/cancel/status由内部AsrProxyService处理；openFile提供的是闹钟TTS/新闻/BGM等输出文件，没有外部收音写入分支。shell访问该Provider同样在权限检查处被拒绝。

完整定点源码和manifest保存在工作区外 `D:/devtools/hypercar-diagnostics/self-voice-20261114`。当前未改运行代码、未申请或绕过签名权限、未更改小爱包。后续若具备合法接口授权，可直接把现有type107解码后的PCM接到feedAudio，再将partial/final回调接到已经实现的顶部语音框，无须重新实现语音模型；在此之前继续区分可用的文本入口、受限的原生ASR入口和尚未实车完成的CarWith链路。
