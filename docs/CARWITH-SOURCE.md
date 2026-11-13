# CarWith 画面与音频源

更新：2026-11-07。可选源依赖已安装且启用的 com.miui.carlink；自绘 UI 使用另一套虚拟屏源。连接、AirPlay资源与输出泵共用，不能通过切来源绕过车机激活。

## 本机七通道

| 通道 | 端口 | 用途 |
|---|---|---|
| CMD | 7240 | 版本、身份、挑战、功能、加密及编码控制 |
| VIDEO | 8240 | H.264 |
| MEDIA / TTS | 9240 / 9241 | 音乐、提示音PCM |
| VR | 9242 | 连接并消费；没有实现车机麦克风上行 |
| CTRL | 9340 | 触摸与硬键 |
| DATA | 9440 | CarWith附加数据 |

HyperCar 以车机角色主动拨本机端口，CMD/CTRL/DATA用8字节头，媒体用12字节头。一轮拥有七个socket，部分失败清理本轮再重连。不能把“入口启动请求已发送”当成七通道就绪。

挑战认证、通道准入和加密准备是不同门槛；通过后才发送视频START。CONTENT_ENCRYPTION=1保留，语音功能声明VOICE_MIC=1使用手机麦克风。触摸及语音硬键共用CTRL串行加密写入，不跨线程直接拼写socket。

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
