# 车机首页、持续导航与音频投送

当前版本：0.1.0.318（2026-10-28）。安装包：`app/build/outputs/apk/release/app-release.apk`。

## 首页与地图切换

黑紫背景、窄侧栏、圆角地图和音乐双卡片。左下角负责应用/主页切换。音乐卡片沿用 MusicBridge 的真实封面、进度和播放控制。

地图由同一块 Shizuku 虚拟屏承载，首页 TextureView 与全屏编码器之间切换输出 Surface，保留高德任务与导航引擎。

初版用固定画布避免导航重置，因此卡片存在留边。进一步检查安装版高德的清单发现：它声明处理 CONFIG_SCREEN_SIZE / CONFIG_SMALLEST_SCREEN_SIZE，却未声明 CONFIG_DENSITY（0x1000）。此前卡片 160dpi、全屏 240dpi 的变化会触发界面重建；初始 Intent 上的巡航 URI 又会被重新处理，覆盖已有导航。

新版两处一并修正：

- 卡片和全屏统一为 240dpi，切换时仅改变窗口宽高和输出 Surface，高德按实际卡片尺寸重新布局。
- 初始启动使用普通入口，启动完成后另发巡航 URI，避免把巡航作为每次重建都要执行的初始页面。
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

已在 WinPlay 验证播放状态的断连暂停、重连续播，以及原本暂停时重连仍保持暂停。截图使用 PixelCopy，包含真实 TextureView 地图。

当前首页截图：`app/build/outputs/ui-review/home.png`。
