# HyperCar

让 Android 手机通过 **CarPlay 协议**（蓝牙 iAP2 协商 + Wi-Fi AirPlay 投屏）变成宝马车机等
AirPlay 接收端的内容源：手机自己绘制车机界面、自己编码画面和音频，不依赖 CarLife、CarWith
或 PC 侧 hub，也不需要 root。

> 当前主线只保留 **CarPlay / AirPlay** 一条链路，面向宝马 MGU-55 / iD8.5 实测开发，
> 同时保留 WinPlay、Audi MIB3 等接收端的车机档案。

## 形态

```
手机（Android / HyperOS）
 ├─ D1 自绘车机首页（Shizuku 创建虚拟屏）
 │    ├─ 地图卡片：高德巡航预览
 │    ├─ 音乐卡片：封面 / 歌名 / 进度 / 播放控制 / 切换音乐 App
 │    ├─ 左侧竖向 Dock + 应用页
 │    └─ 实时触摸、左下角悬浮主页按钮
 ├─ D2 高德全屏（同一高德任务，卡片 ↔ 全屏切换不打断导航）
 ├─ Rust 核心：AirPlay 协商 + H.264 / PCM 推流、触摸回传
 └─ 音频：系统播放采集 48kHz 双声道 → AirPlay，不占麦克风
```

车机侧只需要支持 **无线 Apple CarPlay**；首次在车机屏幕确认一次，之后同一台车自动回连。

## 功能

- **自绘车机首页**：深色主题、地图卡片、音乐卡片、左侧 Dock、更多应用页；地图卡片直接显示
  高德巡航画面，卡片和全屏共用同一个高德任务。
- **高德全屏**：D2 独立虚拟屏，高德按车机横屏布局；顶部下滑或左下角主页按钮退出，
  退出后首页和高德任务都保留。
- **音频直通**：`AudioPlaybackCapture`（REMOTE_SUBMIX）采集系统播放的 PCM，
  经 Rust 重采样/混音后通过 AirPlay 推送；支持 QQ 音乐、汽水音乐、网易云、酷狗、酷我、
  Spotify、Apple Music、咪咕音乐等标准 MediaSession 播放器。
- **音乐卡片**：真实封面、歌名、进度和上一首/播放/下一首；左上角图标可切换已安装的音乐 App，
  切换时自动暂停并关闭上一个播放器。
- **实时触摸**：车机 HID → Rust → D1/D2，地图拖动为异步流式注入，跟手不积压。
- **锁屏不冻结**：虚拟屏使用独立显示组 + 常亮/解锁标志，会话期持有 CPU partial wake lock；
  锁屏或手机熄屏后车机 UI 仍保持 60fps（不再提供“连接时关闭手机面板”的开关）。
- **自动连接**：车机蓝牙 ACL 连上手机后冷启动前台服务，完成 iAP2 协商、入热点、AirPlay 建链。

## 环境要求

- Android 12+（HyperOS / MIUI 实测；其他 ROM 未验证）。
- **Shizuku**：创建 D1/D2 虚拟屏、注入触摸、拉起高德/音乐 App 需要 shell 权限。
- **通知使用权**：音乐卡片读取 MediaSession 需要。
- 车机支持无线 CarPlay（BMW MGU-55 / iD8.5 为当前实车基准）。

没有 Shizuku 时应用仍可启动和连接，但自绘车机界面无法创建，属于降级模式。

## 构建

```powershell
# 完整构建（Rust + APK）
powershell -File tools\build-local.ps1 -Release -BuildNumber <N>

# 只改 Kotlin，跳过 Rust
powershell -File tools\build-local.ps1 -Release -SkipRust -BuildNumber <N>
```

产物：`app/build/outputs/apk/release/app-release.apk`。

本仓库不再包含 PC 侧 hub / 抓包工具；联调直接用手机 + 任意 AirPlay 接收端。

## 目录

```
app/             Android 应用（Kotlin + Compose）：会话、蓝牙 iAP2、WiFi、自绘 UI
  carui/         D1 首页、D2 高德、音乐卡片、音频采集、悬浮主页按钮
  spike/         Shizuku 建屏 / 编码器 / 触摸注入的底层封装
rust-core/       Rust 核心（cdylib）：AirPlay 协商、H.264/PCM 推流、触摸通道
tools/           本地构建脚本（build-local）
profiles/        车机档案远程下发配置
docs/            架构、编译、车机档案、P1/P2 说明
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/BUILD.md](docs/BUILD.md) | 编译环境、出包、安装 |
| [docs/P1-CARUI.md](docs/P1-CARUI.md) | D1 自绘首页、触摸、编码器 |
| [docs/P2-MAP.md](docs/P2-MAP.md) | D2 高德、卡片/全屏切换、触摸注入 |
| [docs/CARUI-REDESIGN.md](docs/CARUI-REDESIGN.md) | 首页重构、导航与音频投送 |
| [docs/HEADUNIT-PROFILES.md](docs/HEADUNIT-PROFILES.md) | 车机档案字段与新增车型 |

## 已知限制

- 实车验证以 BMW MGU-55 为主；Audi MIB3 等档案仍属未实测。
- PC 接收端（DiPlay / WinPlay）的 `/info` 可能上报 `0x0`，此时触摸兜底空间由
  `touch_display_w/h` 配置决定；当前 DiPlay 使用 2400×1080。
- 音频采集依赖系统 REMOTE_SUBMIX 路由，部分 ROM 对后台录音有限制。
- 高德为安装版车机模式，升级高德后窗口配置/入口可能需要复验。

## 合规

仅个人研究用途，与宝马、百度、高德无隶属关系。CarPlay、AirPlay 为 Apple 商标。
