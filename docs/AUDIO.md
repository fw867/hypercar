# 音频采集、资源与无声排查

更新：2026-11-07。实现与已验证范围分别描述，出声不能只凭 connected 或 RECORD 判断。

## 两种音源

| 来源 | PCM 输入 | 边界 |
|---|---|---|
| 自绘 UI | Shizuku shell AudioRecord / REMOTE_SUBMIX，PCM16、48kHz、双声道 | 采集系统播放，不是麦克风或电话桥接 |
| CarWith | 本机 CarLife MEDIA / TTS 通道，重采样与混音后进入公共 AirPlay 音频泵 | 不启动自绘系统播放采集 |

新增 AAC-LC48k 双声道发送：Android NDK MediaCodec 编码，目标 256kbps；type102 / audioFormat0x800000 / spf1024 / ct4 / sr48000。一包一个裸 AAC 帧，不加 ADTS/AU 头、不换字节序；RTP 时间戳和同步包按1024采样推进。编码器配置输出不作音频发送；采集、重采样、TTS 混音仍共用48k PCM输入，CarWith和自绘均可使用。资源被车机接管时停止编码/发包并丢弃待编码数据，重新取得资源时重建编码器，保持密钥/nonce计数连续。

设置「画面与声音 → 音频格式」提供自动、PCM、AAC优先，下次连接生效。自动保持旧车型的PCM48k首选；ID9只尝试/info明确声明的媒体输出格式，PCM48不可用但声明AAC48时用AAC。AAC优先只在type102/media/output声明AAC48时尝试，否则保留可用PCM路径；仅AAC44的车机本次仍不支持。DiPlay已实测AAC协商成功、约47包/秒，用户确认出声；不等于ID9实车验证。参考[CarPToCarL](https://github.com/jjuugk/CarPToCarL)的AAC裸帧和RTP约定，编码/发送实现位于Rust核心。

AirPlay PCM 使用 SPF352，约 136.36 包/秒，16-bit 大端样本。TTS 参与闪避混音；持续全零样本不会一直保持“播放中”。PC 联调的 audioLatencyMs=250 不应自动套给真实车机。

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

自绘UI可在设置开启默认关闭的车机麦克风测试，重连后长按语音键触发。只协商已声明的type107/speechRecognition/Opus16k单声道输入，通过车机网络绑定UDP接收、Input密钥认证解密、Android NDK Opus解码，仅统计PCM音量。最长20秒，不保存录音，不送往助手；独立connectionID和单流TEARDOWN隔离媒体输出。设置关闭时不创建收音任务。详见[输入和语音](INPUT-AND-VOICE.md)。


645新增独立的CarWith车机麦克风实验开关，默认关闭。启用后由CarWith识别开始/结束业务请求驱动车机type107收音，将Opus解码输出转换为16k单声道PCM16，经现有CarLife VR加密上行到内置小爱；不改变手机麦克风默认选项、自绘UI和媒体输出候选。详见[CarWith来源](CARWITH-SOURCE.md)。
