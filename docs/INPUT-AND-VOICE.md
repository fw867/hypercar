# 方向盘媒体键与语音

更新：2026-11-07。媒体键和语音键共用会话输入入口，但分别路由。

## 媒体键

会话内 /info 的 hidDevices（兼容 hids/hid）按 UUID 读取 HID 描述符。Consumer 页按设备、Report ID 和 Input 位偏移解析，区分数组索引、位图、Usage 范围与常量补齐，支持全局 Push/Pop。非法、截断和超限报告不生成按键。

按设备及 Report ID 只转发新按下沿；重复按下不重复切歌，松开清除状态。无描述符时仅兼容明确的直接 Usage ID，不把 04 固定猜成下一曲；未识别短报告有限记录。媒体设备与既有触摸解析分离。

支持播放、暂停、播放/暂停、上一曲、下一曲、停止及基础快进/快退；未实现按住连续快进/快退。Rust 接收任务随引擎关闭取消，经 JNI 进入 MusicBridge 主线程，优先派发到所选 MediaController；不存在、拒绝或异常时回退 Android 系统活跃媒体会话。CarWith 和自绘 UI 共用，不要求音乐卡片可见。

`HID-KEY` 记录描述符注册、原始候选及按下 Usage；`MEDIA-KEY` 记录 Android 键码、目标和路由。派发成功不表示播放器实际执行。历史日志中的一字节 04/00 已证明原有解析及回调链有缺口，但该用户车机的新描述符及实际切歌仍待复测。

## 语音入口与释放

支持 AirPlay requestSiri 的 siriAction=1/2/3，以及 Consumer HID 0xCF。预热不启动；两条入口共享去重，重复按下不重复唤醒；释放只清除物理按键状态，不按长按时长猜测“结束识别”。无效动作忽略，十五秒无释放令旧状态失效，源重建清除状态。

568 曾参考第三方记录，在按住达到1.5秒后松开自动发送 VR_STOP。审计确认这不能区分“按住等提示、松手再说”和 PTT，本项目没有足够实车证据，因此已移除该推断。收尾交给助手的静音检测、超时或用户取消；当前不提供按住说话模式。

## 按来源路由

| 会话来源 | 唤醒方式 | 麦克风与边界 |
|---|---|---|
| CarWith | 既有加密 CTRL 通道，0x68008，protobuf field1=0x21 VR_START | 功能配置 VOICE_MIC=1，使用手机麦克风；未就绪或过期命令丢弃 |
| 自绘 UI | 后台 Shizuku 执行系统 KEYCODE_VOICE_ASSIST=219，由 Android 选择默认助手 | 使用手机麦克风；遵守首次启用、锁屏与权限限制，助手界面不保证显示在车机 |

CarWith 保留 CONTENT_ENCRYPTION=1，配置项总数为2。语音命令两秒过期、重连清空，和触摸共用串行加密写入。自绘请求执行前检查当前连接标识；CarWith 失败不改为唤醒另一个助手。

没有实现车机麦克风的 AirPlay 输入流协商及上行，不宣称完整 CarPlay Siri 或车载电话能力。物理麦克风属于 CarWith/默认助手，HyperCar 的系统播放采集不录麦克风。

## 证据与复测

本机系统语音键已使默认小爱服务显示会话（dumpsys voiceinteraction：mShown=true）。同机 cmd voiceinteraction show 被权限拒绝，故未使用。CarWith 硬键及 VOICE_MIC 值已对照本地 CarLife CommonParams / FeatureConfigManager，并参考 [CarPToCarL 固定版本](https://github.com/jjuugk/CarPToCarL/tree/6346bf7b63b052b2764eb19f1495891bc833c152)；该项目的时长经验不视为本项目协议要求。

需要实车分别验证：媒体键按一次/按住/松开，CarWith 语音界面与手机取音，自绘默认助手唤醒、取消及再次唤醒。`VOICE-KEY` 的命令排队/写入成功不等于识别成功。最新测试结果统一记录在[验证](VALIDATION.md)。
