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
