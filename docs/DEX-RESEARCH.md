# Samsung DeX 上车可行性研究（分支 dex-research）

更新：2026-11-24。目标：评估「用 Samsung DeX 把画面送到车机」是否可行、值不值得做。

## 结论先行

| 路径 | 做法 | 判断 |
|---|---|---|
| A. DeX → 无线显示（Miracast/WFD） | 手机当 WFD **source**，车机必须是 **sink** | **大概率不通**：ZLink5 这类 CarPlay/CarLife 大屏通常没有 Miracast 接收端 |
| B. DeX → USB-C DP | 手机当 DP source，车机要有 DP 输入 | 几乎不可能，车机 USB 口只做 CarPlay 有线数据 |
| C. DeX/手机屏幕 → 抓屏 → AirPlay | 沿用现有链路，把画面来源换成"手机屏幕" | **技术可行**，但 DeX 画面常带 FLAG_SECURE，抓屏会黑 |

推荐：**不要直接投 DeX**；把目标改写为「把手机屏幕（或指定 App）作为 HyperCar 的画面来源」，DeX 只是它的特例。

## 为什么 A 基本不通

- DeX 无线 = Miracast（Wi-Fi Display）。手机侧能力齐备：`com.samsung.android.smartmirroring`、`persist.debug.wfd.enable=1`、`com.sec.android.app.launcher/com.honeyspace.dexservice.DesktopModeTile`（无线 DeX 磁贴）、`com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher`（DeX 桌面）。
- 但 ZLink5 车机侧只实现了 **CarPlay（iAP2 + AirPlay）** 与 **CarLife**。它没有 WFD sink，也不会出现在手机的"无线显示"设备列表里。
- 就算某个车机带了"无线投屏"App，Miracast 也只是单向视频：方向盘键、车机麦克风、音量、触摸回传这些 AirPlay 才有的整车集成全部丢失。

## 为什么 B 基本不可能

车机的 USB 口在有线 CarPlay 场景里是 **USB host**，只跑 iAP2/AOA 数据；它没有 DP alt-mode 输入。手机插上去不会进入 DeX 的显示器模式。

## C 的技术细节（真正值得做的）

HyperCar 已有两种画面来源：自绘 UI（Shizuku 虚拟屏 + MediaCodec）与 CarWith（CarLife 码流）。第三种「手机屏幕」需要：

1. `MediaProjection` 申请录屏授权（用户点一次"开始投屏"），拿到 `Surface`；
2. `MediaCodec` 以该 Surface 为输入编码 H.264 —— 与 `spike/SpikeCaster.kt` 现有链路一致；
3. 触摸仍由 AirPlay HID 回调映射回手机坐标（自绘 UI 已经这么做）。

风险：

- **FLAG_SECURE**：DeX 桌面、部分系统界面、银行/视频 App 会抓成黑屏。
- 分辨率/宽高比：手机竖屏 1440×3120 投到车机 1920×1080 横屏，需要等比 + 黑边，或强制横屏布局。
- 授权可被系统随时撤销（录屏弹窗）。
- 后台运行时 MediaProjection 在部分 ROM 上会被限制。

## 车内 5 分钟验证清单（决定 A 还是 C）

1. 车机上翻应用列表，找有没有「无线投屏 / 手机投屏 / Miracast / Screen Mirroring」入口。
2. 手机：设置 → 已连接设备 → 无线 DeX（或 Smart View）→ 扫描；观察列表里是否出现车机名字。
3. 若出现：走 A 的可行性成立，但仍要先接受"失去整车集成"。
4. 若不出现（最可能）：A/B 排除，回到 C —— 验证 `adb shell screencap` 能否抓到想投的画面，抓得到就说明可以走 MediaProjection。

## 与主线的关系

这一支**不影响** `system-carlife` 分支（三星车联作为画面来源已经跑通：七端口监听、HU_INFO 上报 mainline、完整握手、H.264 出帧）。DeX 属于"车机 UI 形态"的另一条探索线，等主线稳定后再评估。
