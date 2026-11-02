# HyperCar

让 Android 手机通过经典蓝牙 iAP2 与 Wi-Fi AirPlay，向无线 CarPlay 车机传送自绘界面和系统音频。当前主线不依赖 CarLife、CarWith 或 PC hub，不需要 root。
 
 小米手机可以直接设置CarWith，可不用安装Shizuku。
 
目前以 BMW MGU-55 / iD8.5 为实车开发基准。首次连接已获用户反馈稳定推流；二次上车的自动唤醒。WinPlay 用于桌面联调，Audi MIB3 档案尚未完成本项目实车验证。

## 界面

以下截图来自 2026-10-29 的本地构建 0.1.0.377，属于历史外观示例。首页地图已做隐私遮挡；截图不代表当前全部按钮与设置。

![首页：地图隐私占位与音乐卡片](docs/images/carui-home-redacted.png)

![历史设置界面](docs/images/carui-settings.png)

![主题与壁纸](docs/images/carui-settings-theme.png)

![更多应用](docs/images/carui-apps.png)

当前音乐卡片占安全显示区域宽度的 25%，专辑封面填满卡片背景。卡片支持播放器实际提供的播放、收藏、队列及循环模式能力，也可展开播放器真实界面。QQ 音乐专用服务尚无应用授权，不保证专用列表和模式控制可用。

地图支持高德、百度；卡片与全屏共享地图任务，切换保留导航。巡航跟随地图应用自身设置。支持日夜主题、壁纸、默认音乐和连接自动播放。“返回车机”通过 requestUI 请求原生界面，接收端效果需分别验证。

## 架构与要求

Android / Kotlin 管理蓝牙、网络、会话和 Compose 首页；Shizuku 创建虚拟屏、注入触摸并采集系统播放；Rust 管理 AirPlay、H.264 / PCM、事件与触摸通道。

- Android 12+，当前构建包含 arm64-v8a 库。
- Shizuku：用于虚拟屏、应用启动、触摸和系统音频采集；无授权时无法创建完整自绘车机界面。
- 附近设备、通知等系统权限；音乐元数据与控制还需通知使用权。
- 安装所需地图、音乐应用，并先在手机完成各自初始化。
- 接收端支持无线 CarPlay；首次在车机侧选中手机并确认配对。

自动连接通过目标车机经典 ACL、已知 Wi-Fi 和可选增强发现恢复待机。手机加入车机 Wi-Fi 不等于车机已激活 CarPlay；成功样本仍经过本轮邀请。详细设置见[会话控制](docs/SESSION-CONTROL.md)。

## 画面来源：自绘 UI 与 CarWith 模式

设置里可切换两种来源：

- **自绘车机 UI（默认）**：本 App 自己渲染地图卡 / 音乐卡 / Dock，经 Shizuku 建虚显并编码推送；需要 Shizuku。
- **CarWith 模式**：拉起小米 CarWith 的 `CarlifeAdbActivity`，由 CarWith 渲染与编码，本 App 只做 CarLife → AirPlay 转发；需要系统自带 `com.miui.carlink`。


| 项 | 取值 / 说明 |
| --- | --- |
| CarLife 协议版本 | **4.1**（`pbInt(1,4)+pbInt(2,1)`；沿用公版的 3.2 会被 CarWith 直接断开连接） |
| 通道 | CMD 7240 / VIDEO 8240 / MEDIA 9240 / TTS 9241 / VR 9242 / CTRL 9340 / **DATA 9440** |
| 认证 | 收 `MD_AUTH_RESULT` 回 `HU_MD_AUTH_RESULT_ACK (0x1804C)` |
| 内容加密 | 特征声明 `CONTENT_ENCRYPTION=1` → 发 `HU_RSA_PUBLIC_KEY (0x1806B)`（RSA-2048，base64 DER）→ 解 `MD_AES_SEED` 回 `HU_AES_ACK (0x1806D)` → 收到 `MD_ENCRYPT_READY` 之后才发 `VIDEO_ENCODER_INIT` |
| 启动浮层 | `LAUNCH_MODE_NORMAL` 之后补发 `HU_GO_TO_FOREGROUND (0x18025)`，并在收到 `FOREGROUND` 时再次回应，CarWith 才会收起「启动中」浮层 |

入口拉起后需轮询 7240 是否监听（CarWith 的端口通常只开约 1~2 秒），失败会重试两轮，并提示检查 MIUI「后台弹出界面」权限。


## 构建

~~~powershell
# 完整构建，N 替换为构建号
pwsh -File tools\build-local.ps1 -Release -BuildNumber <N>

# 仅 Kotlin / 资源变化时复用已构建 Rust 库
pwsh -File tools\build-local.ps1 -Release -SkipRust -BuildNumber <N>
~~~

产物为 app/build/outputs/apk/release/app-release.apk。工具链、签名和覆盖安装见[构建说明](docs/BUILD.md)。

## 文档与目录

从[文档索引](docs/README.md)进入当前说明；连接排查先看[诊断](docs/HANDSHAKE-DIAGNOSTICS.md)，实测范围见[验证记录](docs/VALIDATION.md)，参考项目结论见[参考资料](docs/REFERENCE-REVIEW.md)。已过时的排查稿保留在 Git 历史中。

| 目录 | 内容 |
|---|---|
| app/ | Kotlin / Compose、会话、蓝牙、网络、虚拟屏与系统音频 |
| rust-core/ | AirPlay 控制与媒体核心 |
| tools/ | 构建和诊断工具 |
| profiles/ | 远程车机档案 |
| docs/ | 当前说明与验证证据 |

## 适用范围

接收端未提供有效像素尺寸时使用设置兜底，应与实际触摸空间一致。音频依赖系统 REMOTE_SUBMIX，ROM 和播放器能力会影响使用。其他车机与 ROM 的表现需单独验证。

仅个人研究用途，与宝马、百度、高德无隶属关系。CarPlay、AirPlay 为 Apple 商标。
