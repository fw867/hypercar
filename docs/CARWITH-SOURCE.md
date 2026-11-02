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
