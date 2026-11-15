# ID8.5 570 基线稳定性与诊断实施记录

用户已授权按现有诊断继续修复、编译并覆盖安装；不提交、不推送。复用当前 checkout，保留配对和应用数据。

基线：发布标签 v0.1.0.570 / f8538c9，已从 origin 快进对齐，手机安装为570/10666。

## 任务及验证

- [x] 对齐570源码，确认工作区干净、手机版本和发布标签一致。
- [x] Wi-Fi iAP2：修正非空NowPlaying字段订阅缺陷并补链路终止摘要；缺陷测试先红后绿，EOF具体因果仍待实车。
- [x] 音频：停顿追发测试先红后绿，Skip加RTP跳时、丢旧队列、marker/同步；保留nonce单调性。
- [x] 方向盘：HID入口/注册/报告/拒绝及会话gate限频累计，保持派发行为。
- [x] 语音：MEDIA/TTS/VR逐通道入口、解密、PCM及转发/丢弃统计；空闲与EOF可见，不记录内容、不猜测VR语义。
- [x] Release JVM 524项、Android原生221项通过，1项现场依赖忽略；完整Lint无错误，正式包构建通过，独立复审无剩余P1/P2。
- [x] 版本571/10667同批只升一次；adb install -r成功、版本核实及启动进程检查完成。

## 约束与风险

保留ID9关闭HEVC的既有策略，不改已通过的RTSP URI、配对身份或主屏协商。软件回归不能代替MGU-55实车复测；iAP2 EOF的完整因果及小爱回应承载通道仍需下一轮日志。保留音频发送时钟同步，避免仅换Delay让RTP时钟长期落后。诊断只包含大小、计数、状态及消息类型。

## 进度

2026-11-15：按用户明确给定顺序实施。并行任务分别拥有Wi-Fi协议、按键诊断、语音通道诊断文件；主任务负责音频节拍、版本、集成与安装。未创建提交。

审查裁定：不在漏拍时销毁/重建AAC编码器，避免重建耗时再次触发漏拍的反馈循环；保留半帧及重采样相位，仅过期混音队列丢旧。真实Android AAC连续漏拍测试已先失败后通过，PCM/AAC441时间戳、nonce/marker及原所有权测试通过。软件验证不代表MGU-55现场故障已消除。

后续：按用户要求追踪固件BMW分支，确认vehicleCfgIsBMW未找到静态调用者、实际品牌位影响蓝牙模块自动重连候选。SAP、固定七项features及资源回调另行记录，不误当ID9专用路径。唯一新增协议对照为ID9屏幕latencyMs 25→75（固件0x3abac明确常量），实际请求测试先红后绿，MBUX100/默认25保持。安装前发现571已被其它工作提交为f7f574c，此延迟改动作为新未提交批次升572/10668；本轮不提交推送，实车400是否变化待验证。

用户随后明确ID8.5为主线、ID9仅附带支持，并选择“自绘HEVC＋真实HEVC输入透传，保留H.264回退”。复用本批572，不再加号。实施范围：MGU资源生命周期及75ms、BMW已实现能力声明、显式codec贯穿编码器/JNI/视频泵、HEVC hvc1/hvcC和加密标志、真实源参数确认与AU解析。未找到CarWith编码选择协议，因此不添加猜测字段/实时转码。自绘默认H264、HEVC硬件启动失败回退；车机未协商HEVC的真实HEVC源明确提示不兼容。验证包括JVM策略、Android真实socket、实际手机Surface编码/解码素材与独立审查；编译通过后覆盖安装，保留数据，不提交推送。

## 573：方向盘与小爱回复链路

当前HEAD已由其它工作推进至80da947（572），本轮再次授权证据差异修复、构建和覆盖安装，不提交推送；版本按新未提交批次升573/10669一次。

证据与实施范围：

- [x] 复核盒子6800/6802及B5/B6到Android MediaSession路由：未发现确定的按键映射差异；570日志有注册无实际报告，不猜测按键值或改CarWith音乐控制。
- [x] NowPlaying：盒子回显式订阅的playback13/14能力和16应用bundle；当前无5003设置能力，回复13/14=false，16由真实packageName提供。保持legacy空订阅wire及字段过滤，未知项只记录ID。新增失败测试后实现，iAP2整包回归。
- [x] CarWith源码追踪证明独立VR下行：VRAudioManager经9242发0x50002 INIT、0x50003 DATA（AES），0x50004 STOP空明文。当前receiver只观察丢弃，确定需要补齐；不把上行0x58001认作下行。
- [x] 在carlife/voice_audio.rs实现受认证、格式和大小约束的VR输入；capture/noCapture共用，保留现有单writer上行。未知FEED_END/INTERRUPT只统计，无证据不新增DONE回执。
- [x] engine.rs增加独立VoiceAudio事件和PCM队列，STOP排尾，下次INIT清旧源；三路混音/闪避保留PCM RTP、nonce和Skip节拍，不新建未经车测的SIRI/OPUS流，不冒充用户操作抢音频资源。
- [x] 设备失败复现→修复回归，独立审查；完整Release JVM/Android原生/Lint/构建，核对APK并adb install -r、版本与启动验证。

复测重点：CARWITH-CHANNEL VR的INIT/DATA/STOP、decryptOk/pcmBytes/nonzeroSamples/forwarded，VOICE-AUDIO及音频队列vr、owner_phone/blocked_samples；IAP2-NOWPLAYING订阅字段和IAP2-MEDIA reports/edges，再到MEDIA-KEY派发。不记录语音内容或播放器元数据，软件闭环不宣称实车已恢复。

573最终验证：Release JVM532、Android原生244通过/1既有现场依赖忽略，包含MEDIA/TTS/VR真实socket路径；完整Lint0错误/69警告，Release构建通过，包内原生库匹配。已adb install -r成功并核实573/10669、主Activity启动及进程，无本进程致命崩溃；保持未提交未推送。

### 同批继续：独立SIRI与导航

用户明确盒子成功日志未测试SIRI/导航，授权继续对照固件实现，复用573/10669。按已证实的type100/speechRecognition/OPUS24k480与type101/default/OPUS48k960设计，分别保留main借还/Speaking与导航appState/duck/unduck；不改媒体PCM或主线到ID9。

- [x] 静态libopus工厂及48k立体声输入转换、20ms mono编码、实际采样时钟RTP/同步/nonce回归；本地/CI CMake构建与许可材料。
- [x] 严格能力过滤、独立SETUP字段和响应校验、duck/unduck及结构化失败清理结果，RTSP实包测试。
- [x] 资源按相关代次合并，串行ACK提交、精确借还；end失败保留lease可重试，receiver接管不抢回。
- [x] 独立worker和有界源队列；主泵Pause物理关闭/取消迟到Pause/恢复媒体，fallback不重播；新代次不被旧tick淘汰。
- [x] 控制器RTSP+UDP验证正常SIRI/导航、重复清理防护、清理拒绝重试、恢复耗尽后fresh start、SETUP中shutdown。
- [x] 最终完整构建与覆盖安装；保持未提交未推送。车机分别验证SIRI回复、导航提示、音乐恢复与原有媒体稳定性。

同批最终：Android281通过/1现场依赖忽略，Release JVM532，完整Lint0错误69警告；静态Opus正式APK构建通过、许可与库校验通过，再次覆盖安装573/10669及启动核实完成。未提交推送。

### 同批继续：自绘高德UID音频分流

用户授权补齐自绘导航type101、PCM MEDIA、duck/unduck及失败回退，先DiPlay验证，不提交推送。

- [x] 只读确认当前高德播放为USAGE_MEDIA；选现查UID包含/排除，避免usage-only漏采。
- [x] shell双AudioPolicy LOOP_BACK sink/owner与故障回收，legacy全局采集互斥回退；AIDL末尾追加保持旧事务号。
- [x] 双reader代次隔离、导航400ms静音门控、有界预缓存、JNI tts START/DATA/STOP；旧reader不能停止新capture。
- [x] 设备确认两policy地址与UID互斥，修复DiPlay desktop禁用辅助流的限制；接收端选择红绿回归。
- [x] DiPlay两段导航建立type101/OPUS48k/SPF960并排空关闭，媒体PCM保持；用户确认导航有声、音乐降低后恢复。
- [x] Release JVM542、Android顺序284通过/1现场依赖忽略，Lint0错误76警告；同批573再次覆盖安装及进程核实，不提交推送。ID8.5实车仍待复测。
