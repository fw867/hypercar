# 车机首页、持续导航与音频投送

更新：2026-10-29。界面截图来自手机本地构建 0.1.0.377，使用 WinPlay 联调；发布版本以 GitHub Release 为准。
安装包：`app/build/outputs/apk/release/app-release.apk`。

## 当前界面与操作

- 首页由高德地图卡片、音乐卡片和左侧 Dock 组成。超宽屏适当收窄音乐卡片；Dock 使用较大的图标和触摸区域。
- 左下角在首页显示更多应用入口，在设置／更多／关于页显示返回首页。
- 设置支持导航自动巡航、默认音乐 App、连接自动播放、白天／暗夜／自动主题及三套日夜壁纸。
- 更多应用复用音乐卡片的受支持音乐 App 列表，只显示已安装且有启动入口的应用；无重复的通用音乐入口。
- “返回车机”在应用列表最后，通过 AirPlay `requestUI` 请求原生系统接管显示，保持 CarPlay 连接和音频通道。

首页截图的地图区域已做文档隐私遮罩，隐藏真实地理信息。

![首页：地图位置已隐藏与音乐卡片](images/carui-home-redacted.png)

![设置：巡航与音乐偏好](images/carui-settings.png)

![设置：主题与壁纸](images/carui-settings-theme.png)

![更多应用：已安装的音乐 App 与返回车机](images/carui-apps.png)

## 分辨率、帧率与花屏修复

- `/info` 优先读取 `widthPixels/heightPixels`，兼容 `width/height`。缺失或无效时使用手机设置的宽高，不会删除兜底配置。
- 当前 `VideoEncodingPlan` 使用车机上报原生尺寸，仅做偶数对齐；没有有效上报时用设置分辨率，不再以 1920 宽或约 110 万像素强制缩小。
- 默认 60 fps，手动选较低帧率仍会传给首页和全屏地图编码器，下次连接生效。
- 编码输入端按所选帧率限流，不再在输出端抽掉 H.264 P 帧；切源时先停旧源，新源等待关键帧后发送。
- WinPlay 已验证持续推流和卡片／全屏切换；红绿斑块仍需实车长期复测，不能只通过降低分辨率宣告问题解决。

详见 [推流检查记录](VIDEO-STREAM-REVIEW.md)。

## 首页与地图切换

日夜主题背景、侧栏、圆角地图和音乐双卡片。左下角负责应用/主页切换。音乐卡片沿用 MusicBridge 的真实封面、进度和播放控制。

地图由同一块 Shizuku 虚拟屏承载，首页 TextureView 与全屏编码器之间切换输出 Surface，保留高德任务与导航引擎。

初版用固定画布避免导航重置，因此卡片存在留边。进一步检查安装版高德的清单发现：它声明处理 CONFIG_SCREEN_SIZE / CONFIG_SMALLEST_SCREEN_SIZE，却未声明 CONFIG_DENSITY（0x1000）。此前卡片 160dpi、全屏 240dpi 的变化会触发界面重建；初始 Intent 上的巡航 URI 又会被重新处理，覆盖已有导航。

新版两处一并修正：

- 每次会话确定统一的地图 DPI，卡片和全屏保持不变；切换时仅改变窗口宽高和输出 Surface，高德按实际卡片尺寸重新布局。
- 初始启动优先使用安装版高德的 `UcarSplashActivity` 车联入口，卡片／全屏采用对应布局；后续布局切换保留同一高德任务。
- 开启自动巡航时，仅初次启动额外发送巡航 URI。车联入口不可用时回退手机模式入口，避免每次切换都重新打开巡航。
- 不再使用缩放留边，也不通过裁切隐藏导航提示。

首页初次进入巡航使用安装版高德的入口：

`amapuri://drive/CarCruisePage?sourceApplication=io.github.hypercar&clearStack=1`

用户已确认：导航切换不会打断，地图卡片完整填满。此实现依赖当前安装版高德，升级后需复验其窗口配置与页面入口；实际道路上的定位与播报仍需路测。

## 音量偏小的原因与修复

播放采集链路：

`Shizuku AudioRecord(REMOTE_SUBMIX) → PCM16 管道 → CarAudioCapture → nativePushAudioPcm → Rust mixer → AirPlay → WinPlay`

采集为 48kHz 双声道，20ms 数据块，不打开麦克风。Rust 发送通道未发现固定衰减；实际衰减发生在手机系统的远程输出设备上。

### 实机证据

- 远程采集媒体音量为 100/150，AudioFlinger 活跃 QQ 音乐音轨的 PortVol / G 为约 -15dB。
- 原采集音乐峰值通常约 5900–6400 / 32768，RMS 约 1800–2100。
- Android `fixedVolume` 采集标记能被此 ROM 接受，但混音器仍保留原来的 -15dB，不能单靠该标记解决。
- 将远程设备的音量索引设为 150/150 后，音轨衰减降至约 -0.3dB，音乐采样峰值恢复到约 32700，实测 RMS 约 7540。
- 用户已确认：音量正常、无破音。

### 实现

ShellPlaybackAudio 在远程捕获启动后检查当前媒体输出设备。只有输出设备确认为独占的 REMOTE_SUBMIX（0x8000）时，才保存该设备的原音量并临时设置到最大。

停止采集时，若输出仍是该远程设备且音量仍为本应用设置的值，恢复原值，再释放捕获。设备未切到远程采集时不会调整音量。修复去掉的是源头衰减，没有给 PCM 加额外增益。

断连恢复实测：QQ 音乐先进入 PAUSED；远程音量恢复 100，手机扬声器仍为 30/150；重连后远程设备重新使用满幅。手机扬声器音量未被拉高。

相关 Android 机制可查 [AudioRecord](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioRecord.java)。

## 断连暂停与重连续播

- 实际断连、主动断开或 AirPlay 失败时，先向播放器发送暂停，再释放音频路由。
- 仅记录断开前正在播放的播放器；重连且音频捕获就绪后才恢复。
- 原本暂停的音乐保持暂停；手动暂停意图优先，避免与断连消息接近时误恢复。
- 恢复记录持久保存并在消费后清除，支持进程重建。

已在 WinPlay 验证播放状态的断连暂停、重连续播，以及原本暂停时重连仍保持暂停。历史截图使用 PixelCopy；当前文档截图直接采集虚拟屏，均包含真实 TextureView 地图。

本页截图保存在 `docs/images/carui-*.png`，与 README 共用；使用 Android `screencap -d <SurfaceFlinger display ID>` 直接采集车机虚拟屏，包含真实地图 TextureView。
