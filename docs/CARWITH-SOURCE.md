# CarWith 画面与音频源

更新：2026-11-07。可选源依赖已安装且启用的 com.miui.carlink；自绘 UI 使用另一套虚拟屏源。连接、AirPlay资源与输出泵共用，不能通过切来源绕过车机激活。

## 本机七通道

| 通道 | 端口 | 用途 |
|---|---|---|
| CMD | 7240 | 版本、身份、挑战、功能、加密及编码控制 |
| VIDEO | 8240 | H.264 |
| MEDIA / TTS | 9240 / 9241 | 音乐、提示音PCM |
| VR | 9242 | 默认排空；车机麦克风实验开启时加密上行 PCM |
| CTRL | 9340 | 触摸与硬键 |
| DATA | 9440 | CarWith附加数据 |

HyperCar 以车机角色主动拨本机端口，CMD/CTRL/DATA用8字节头，媒体用12字节头。一轮拥有七个socket，部分失败清理本轮再重连。不能把“入口启动请求已发送”当成七通道就绪。

挑战认证、通道准入和加密准备是不同门槛；通过后才发送视频START。CONTENT_ENCRYPTION=1保留；默认VOICE_MIC=1使用手机麦克风，车机麦克风实验开启时声明0，仍需确认CarWith的实际录音源选择。触摸及语音硬键共用CTRL串行加密写入，不跨线程直接拼写socket。

## 视频与触摸

按源轮次重组分片及access unit，缓存有界，完整参数与IDR到位才输出。新轮次清除旧SPS/PPS和片段，不混用参数；reset输出配置不重置已有AirPlay加密帧计数，避免nonce复用。时间戳映射到单调时钟，不能直接把CarWith Unix秒当AirPlay PTS。

CarWith画布请求按接收端比例且最大2560×2560，实际VIDEO_INIT_DONE和SPS仍是来源尺寸证据。触摸用实际源像素，DOWN/MOVE/UP分别封装，不假设同一触摸消息ID。RESET请求走CMD 0x1800B，视频心跳是另一条消息，不能混用。

键盘配置仅作用自绘虚拟屏，不修改CarWith屏幕。媒体与语音键见[输入](INPUT-AND-VOICE.md)，声音来源及归属见[音频](AUDIO.md)。

## 已确认与排查边界

本机曾完成七路、挑战、加密、视频AU及MEDIA接入；实车也有显示和出声反馈，但不等于所有重连轮次都通过。2026-11-03日志去重后，两段均为CarWith，不能把用户之后自绘正常反馈记成其中一段的成功。重连时“有AU而缺有效配置”的转发缺口已修正，与后来资源SETUP被拒不是同一故障。

CarWith入口可能使用手机蓝牙名称显示设备；该显示名不是AirPlay deviceID、控制TXT id或MFi身份，不应为改显示名破坏认证字段。7240持续Connection refused时优先确认实际入口Activity、CarWith版本和启动限制，不先改车机媒体协议。

参考：[mcarplay固定版本](https://github.com/wangxso/mcarplay/tree/a3a4117ed60b864324d681e45086be594ed7233a) 的状态机、挑战绑定、七路连接、视频分片和入流门控；后续大众策略另参考 `f1ec3ffaa1f9c11b548ec1a8d8f7afff087d8627`。第三方成功不替代本项目验证。

实现：`carlife.rs`、`carlife/session.rs`、`carlife/video.rs`。不再维护重复的通用回环监听/发现旧方案。


## 2026-11-13 645：车机麦克风接入（实验）

在CarWith模式的设置→音频新增“CarWith 使用车机麦克风（实验）”，默认关闭，重连生效。开启后VOICE_MIC=0；关闭仍为1，沿用手机收音。用户还需关闭CarWith/CarLife自身强制使用手机麦克风的选项，后者会覆盖车机声明。CarWith语音键/界面仍走原VR_START控制链，由已完成认证、准入、加密并开始视频的会话收到CMD 0x10024（MIC_RECORD_RECOG_START）才启动输入；0x10023结束。不响应0x10022持续唤醒词收音。

沿用644的已声明type107/speechRecognition/Opus16k输入协商、Input密钥认证与Opus解码；将实际输出采样率按跨缓冲区连续相位的面积平均转换为16k单声道PCM16，按1024字节分包。VR使用12字节媒体头、service_type=0x58001，正文沿用当前CarLife AES密钥和PKCS7加密（1024明文→1040密文）；不会把媒体输出0x50003当输入。原7通道中的VR由只读排空改为单写入者；返回数据仍排空，其他音视频处理不变。

队列最多8包，250ms以上旧音频丢弃，写入预算500ms。请求重复开始幂等，结束/新轮次更新代号并清空缓存；迟到输入和旧捕获任务的清理不能取消下一次识别。单次最长20秒、无认证RTP5秒和中断3秒退出，RTSP仅清理107，VR发送0x58009输入结束标记。写入任务取消、源重建和整个会话停止均退休输入。输入故障不会主动拆AirPlay音乐或画面流；VR socket失效依旧遵循原7通道一轮重建策略。

诊断查看CARWITH-MIC（RECOG_START、VR_DATA包数和VR_AUDIO_DONE）与MIC-PROBE（输入SETUP、认证/解码和PCM电平）。这些计数不等于小爱识别成功；需要真实车机说话测试确认CarWith选用了HuMicRecorder、能显示正确识别文本并执行操作。保持自绘UI的独立收音测试和手机助手路由不变，不用该方案启动CarWith给自绘UI供识别。

## CarWith 接口继续核对：录音源选择与外部文件入口

本轮重新提取手机已安装 CarWith 4.0.23-20261016（104000023）核对，避免使用本地旧4.0.16目录推断当前混淆类名。当前源码与APK在工作区外 `D:/devtools/hypercar-diagnostics/carwith-interface-20261113`；jadx整体报告46项错误，以下引用的目标方法可见，不能把未反编译出的部分当作不存在。CarLife 8.8.5（274）本地APK与手机安装包SHA256一致，为 `eec3649a038bcf75fa7b538bb33e2ad6314fa89bd1780b02cfcd0d7d2573c8b6`。

**确定的消费接口链**：CarLife `NormalVehicleRecordToolImpl.record`从9242读取12字节头、按头部长度读正文、AES解密；`ReadCarRecordDataThread.writeAudioData`在 `isVrWakeUp && isDingDongFinish && isMix && getUsePhoneVr` 时调用 `MixScreenManager.onReceiveAudioStream(pcm,16000,4,2)`。这里4/2分别是Android单声道输入/PCM16枚举，不是4声道或2位音频。CarWith `CarlifeAudioListener.onReceiveAudioStream`加入 `h1.a/AssistantHuMic` 队列；`ae.j/HuMicRecorder`从 `DataBusClient.readAssistantRecorderData`读取，交给 `ae.f/AudioRecordTask`，最后进入 `SpeechEngineTask/EngineBridge`。我们现有1024字节PCM加密为1040字节、12字节头的封装与该读取路径相符，未发现需要为了“启动识别”盲加VR初始化包的依据。

**两个独立选择不能混淆**：

- `usePhoneVr`选择混合模式使用的小爱/手机助手还是CarLife自身引擎；它不是“使用手机麦克风”。若未处于相应语音会话或提示音尚未结束，收到的PCM不会沿上述分支进入CarWith队列。
- 实际麦克风由CarWith `o1.a.d → e2.a.p`决定，结果true选 `ae.k/NativeRecorder`，false选 `ae.j/HuMicRecorder`。`e2.a.u`还读取私有偏好 `ucar_settings_data_bg/carlife_is_use_phone_mic`（NONE/TRUE/FALSE三态）以及内部回调查询 `carlife_vr_use_phone_mic → is_use_phone_mic`、`carlife_vr_hu_wake_up_enable → hu_wake_up_enable`。CarLife返回值又受VOICE_MIC和用户麦克风开关/车型名单影响。故VOICE_MIC=0只是输入声明，不能直接当成HuMicRecorder已启用的回执。

`HuMicRecorder.startRecording`还调用 `setVoiceAssistantActive(true)` 与 `startAssistantRecorder(true)`；后者经内部CarLife混合回调产生语音状态请求。CarLife `sendStartRecogMsgToVehicle`先确保读音频线程运行，再发CMD 0x10024；结束0x10023。当前HyperCar按这两条控制收音的方向正确。应按以下分界核实现场：VR_START写出 → 小爱会话/提示音结束 → 收到10024 → 107认证RTP/解码PCM → 加密VR写出 → CarlifeAudio收到字节 → HuMicRecorder实际读取 → ASR结果；任何前一阶段成功都不能替代后续阶段。

**新找到的导出文件入口**：当前manifest中 `com.xiaomi.voiceassistant.VoiceService` exported=true。其语音动作接收 `source_type`、`is_for_evaluate`、`evaluate_wav_path`、`is_vad`、`need_tts` 等参数；`SpeechEngineTask.J → EngineBridge.S0 → AudioRecordTask`的评测分支强制使用 `ae.i/FileRecorder`和指定文件路径，后者实际是FileInputStream读取原始字节，没有在该类中自动解析WAV头。`source_type=4`另选StreamRecorder，但目前未发现可由第三方持续投喂其私有队列的导出接口，不能仅凭类名声称可实时接入。

本轮以1秒全零合成PCM验证VoiceService文件入口：ADB普通startservice在Android后台服务限制处返回 `app is in background`，尚未进入文件打开/识别。没有使用手机麦克风或真实说话音频；临时手机文件已删除。此结果只说明当前后台调用方式失败，既不证明文件不可读，也不证明文件已被识别。未用前台服务强起替代普通Service、未修改第三方私有设置或包。DataBusService仍exported=false，未发现可供HyperCar直接传实时PCM的公开Binder入口。

当前判断：CarWith模式优先完善并验证现有已认证VR传输；文件评测入口只能作为受控离线输入实验候选，不能替代实时车机语音，也不把其存在等同自绘模式已打通小爱。下一轮实车需同时观察CarLife的micStatus/usePhoneVr以及CarWith的is_use_phone_mic、HuMicRecorder/NativeRecorder、CarlifeAudio和AssistantHuMic日志，先确定卡在哪一段，再改动实现。
