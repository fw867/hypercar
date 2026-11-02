# 当前架构

更新：2026-11-02。

HyperCar 是无线 CarPlay 的手机内容源：通过经典蓝牙 iAP2 协商车机 Wi-Fi，再通过 AirPlay 传送自绘界面与系统音频。默认链路不依赖 CarLife、CarWith 或 PC hub，不要求 root。可选的 [CarWith 画面源](CARWITH-SOURCE.md) 依赖手机上对应小米组件，使用独立的本机 CarLife 桥。

## 分工

| 层 | 职责 |
|---|---|
| Kotlin 会话层 | 目标车机、蓝牙监听、iAP2、Wi-Fi、NSD、会话生命周期 |
| Shizuku / shell | 虚拟屏、触摸注入、应用启动、系统播放采集及受控发现窗口 |
| Compose D1 | 首页、Dock、音乐与设置 |
| 地图 D2 | 已安装地图应用的车联界面；卡片与全屏共享任务 |
| Rust 核心 | AirPlay 控制、配对验证、媒体、事件和触摸通道 |

Android 最低 API 31；当前构建为 arm64。没有 Shizuku 时应用可启动，但无法创建完整的自绘车机界面。音乐卡片通过通知使用权获取 MediaSession；播放器实际暴露的能力决定哪些控制可用。

## 生命周期

自动模式维持待机蓝牙监听；一轮媒体会话有独立的网络租约、Socket 和任务。媒体结束与待机监听关闭是两件事。自动模式下结束当前会话后可继续等待车机；关闭自动模式并停止连接会释放监听。

控制、事件、音视频与 Wi-Fi iAP2 各有独立任务和状态。网络失效会终止旧租约，不能继续使用旧 Network 的 Socket。Rust 持有转交后的原生 FD，Java 原始 Socket 在转交后关闭。

实现入口：[蓝牙](../app/src/main/java/io/github/hypercar/iap2/Iap2Bluetooth.kt)、[网络租约](../app/src/main/java/io/github/hypercar/net/SessionSocketLease.kt)、[JNI](../app/src/main/java/io/github/hypercar/core/RustCoreBridge.kt)、[Rust](../rust-core/src/lib.rs)。

交互与协议细节分别见[界面](CARUI-REDESIGN.md)、[协议](PROTOCOL.md)和[会话控制](SESSION-CONTROL.md)。验证范围见[验证记录](VALIDATION.md)。
