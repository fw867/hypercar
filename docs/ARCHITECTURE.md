# 当前架构：Android 自绘车机 UI → CarPlay / AirPlay

更新：2026-10-29。对应当前默认分支 `feat/carplay-only-ui`。

手机通过蓝牙 iAP2 与车机协商无线 CarPlay，再通过车机 Wi-Fi 网络建立 AirPlay 会话。
HyperCar 自己绘制车机首页并编码视频，使用系统播放采集投送音乐。当前实现不依赖 CarLife、
CarWith 或 PC hub，也不是将其他应用已有的 H.264 直接转发的“零转码网关”。

## 数据路径

```text
Android 手机
 ├─ Kotlin：iAP2 / Wi-Fi / 车机档案 / 网络绑定 socket
 ├─ D1：CarHomeActivity（Compose 首页、设置、更多应用、关于）
 │    ├─ 地图卡片：TextureView 接收 D2 高德画面
 │    └─ 音乐卡片：MusicBridge / MediaSession
 │         └─ MediaCodec Surface → H.264 Annex B → JNI
 ├─ D2：安装版高德的同一个车联任务
 │    ├─ 卡片态 → D1 的地图 TextureView
 │    └─ 全屏态 → 地图编码器 Surface → H.264 Annex B → JNI
 └─ Shizuku AudioRecord(REMOTE_SUBMIX) → PCM16 / 48kHz / 双声道 → JNI
                         ↓
 Rust：AirPlay 协商 / 屏幕与音频发送 / 事件接收
                         ↓
 车机：无线 CarPlay 接收端
```

首页与全屏地图切换时，新源等待关键帧再发送，旧源先停止推帧。全屏地图期间首页编码器暂停，
避免同时持续编码两份画面。切回首页时保留高德任务和导航引擎，只调整窗口尺寸与输出 Surface，
同一会话的地图 DPI 保持不变。

## Kotlin 与 Rust 的分工

| 层 | 主要职责 |
|---|---|
| `session/HyperCarRepo.kt` | 会话状态、连接与拆除、重连、日志、Rust 回调 |
| `carui/CarUiCaster.kt` | 确定本次画布、启动 D1、首页编码器、触摸监听、音频采集 |
| `carui/AmapPreview.kt` / `MapController.kt` | 保留高德任务、卡片／全屏输出切换、地图触摸 |
| `carui/CarUiSettings.kt` / `MusicBridge.kt` | 主题壁纸、巡航偏好、播放器选择、断连暂停与重连恢复 |
| `spike/SpikeShellService.kt` | Shizuku shell 身份建屏、更新 Surface、输入注入、系统播放采集 |
| `spike/SpikeCaster.kt` | MediaCodec Surface 编码、输入端限帧、关键帧门控 |
| `rust-core/src/airplay.rs` / `rtsp.rs` | `/info`、配对、SETUP、RECORD、加密控制通道、心跳与事件 |
| `rust-core/src/engine.rs` | JNI 数据事件分发、视频泵、音频泵、原生系统 UI 请求 |
| `rust-core/src/screen.rs` / `audio_out.rs` | H.264 配置和帧封装、PCM 音频发送 |

## 显示尺寸、编码尺寸与触摸空间

1. 车机 `/info` 的 `widthPixels/heightPixels` 优先，兼容旧 `width/height`。
2. 缺失或无效时使用手机设置的 `videoWidth/videoHeight`；每次新会话清除上一接收端的临时上报值。
3. `VideoEncodingPlan` 使用该原生尺寸，仅做偶数向下对齐；不再应用宽 1920、高 1080 或约 110 万像素的缩放上限。
4. 默认 60 fps，保留用户手动选择的较低帧率。尺寸与帧率在下次连接生效，运行中不把新设置冒充为实际编码尺寸。
5. `nativeSetVideoSize` 回报实际编码尺寸，触摸从接收端显示空间映射到该画布。
6. `/info.viewAreas[].safeArea` 通过 `onDisplayLayout` 传入会话内布局状态；可视区域与安全区域先裁切到主屏，
   缺失/无效时回退到完整可视区域。换接收端时清除上一会话布局。
7. 编码画布不因安全区域缩小；首页、设置、更多、关于、Dock 和卡片使用安全区域的实际坐标。
   背景仅在 `drawUIOutsideSafeArea=true` 时扩展到可视区域；地图全屏返回按钮及触摸命中框使用同一安全区域。


MediaCodec 在编码输入端限帧，输出端完整保留预测帧参考链。接收端能否稳定解码、持续温度和
偶发花屏仍需实测，不能仅凭硬件能力声明或降低画质认定问题解决。

## 原生系统 UI 与断开连接

“返回车机”通过加密 AirPlay 控制通道发送 `POST /command`、`type=requestUI`，由接收端切回原生 UI。
该操作保持 CarPlay 会话与音频通道，不调用断开，也不触发音乐暂停。WinPlay 已验证，宝马实车行为需复验。

真正断连时先暂停正在播放的 MediaSession，再释放音频采集和路由。重连后等采集通道就绪再恢复。
连接自动播放为独立偏好；手动播放控制优先。采集使用系统播放路由，不打开麦克风。

## 实现边界与历史文档

- Shizuku 是车机虚拟屏、地图触摸注入和应用启动的必要条件；通知使用权用于音乐卡片。
- 高德使用安装版应用的车联入口，并在不可用时回退；高德升级后需重新验证入口与窗口行为。
- 车机档案包含 BMW、WinPlay、Audi 等适配差异，提供档案不等于所有车型均已实测。
- `P1-CARUI.md`、`P2-MAP.md` 与早期 CarLife 协议记录保留历史排查过程；当前用户功能见
  [README](../README.md)、[车机界面说明](CARUI-REDESIGN.md) 和 [推流检查记录](VIDEO-STREAM-REVIEW.md)。
