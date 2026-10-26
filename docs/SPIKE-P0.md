# SPIKE-P0 实施记录（S1 副屏 / S2 注入 / S3 编码）

> 本文是 P0 三个 spike 的**现场记录 + 结论**，随测试推进更新。
> 代码全部在 `io.github.hypercar.spike`（调试用，P0 后按用户决定去留）。
> 未 commit、未 push。

## P0 结论（2026-10-26 22:53）

| Spike | 判定 | 关键证据 |
|---|---|---|
| S1 副屏跑高德 | ✅ | shell 建屏 `0x444b`（touch/trust/focus），高德 1920×720 全屏横屏（`am compat`），可注入 |
| S2 Shizuku 注入 | ✅ | tap 30/30，DOWN 延迟 p50 3.2ms；swipe/pinch；高德界面响应 |
| S3 编码 → AirPlay | ✅ | 60fps、Rust `fps=60/kbps≈633`、音频 136/s、DiPlay 画面正常（用户确认） |
| 触摸回调 JNI | ✅ | DiPlay 的 50 条真实 HID 全部回调到 Kotlin |

## 0. 环境

| 项 | 值 |
|---|---|
| 设备 | Xiaomi M154FF（madrid），HyperOS 4 / Android 17（SDK 37），build `CP2A.260605.016 / OS4.0.27.0.XEOCNXM` |
| 无线 adb | `192.168.1.242:42581`（Wi-Fi `YK`，5320MHz） |
| Shizuku | 13.6.0（服务端 `shizuku_server` 以 shell 运行；本应用已授权） |
| 构建 | `tools\build-local.ps1 -Release -BuildNumber N`（PowerShell 5.1） |
| 高德 | `com.autonavi.minimap` 17.01.0.2012（targetSdk 35），**已装**；百度地图未装 |
| DiPlay | 文档里是 `192.168.1.208:7000`；本轮测试时该地址 ping 不通（待用户启动/确认 IP） |

## 1. 已确认的 Android 17 限制（S1 第一轮，真机）

### 1.1 普通 App 建副屏：只有 `OWN_CONTENT_ONLY` 一种合法组合

`DisplayManager.createVirtualDisplay(..., flags=...)` 在应用进程（uid 10327）里：

```
PUBLIC                    → SecurityException: Requires ADD_MIRROR_DISPLAY,
                            CAPTURE_VIDEO_OUTPUT or CAPTURE_SECURE_VIDEO_OUTPUT
                            permission, or an appropriate MediaProjection token
                            in order to create a screen sharing virtual display.
                            In order to create a virtual display that does not
                            perform screen sharing (mirroring), please use the flag
                            VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY.
PUBLIC|AUTO_MIRROR        → 同上
PUBLIC|PRESENTATION       → 同上
PUBLIC|OWN_CONTENT_ONLY   → 成功（id=54/55，1920x720@240，state ON，#canHostTasks=true）
```

即：**非 root、无 MediaProjection token 时，D2 只能建成“自有内容”屏**。要托管别人的 App，
建屏这一步必须在 **shell UID**（Shizuku UserService）里做——shell 已确认持有
`CAPTURE_VIDEO_OUTPUT` / `ADD_MIRROR_DISPLAY` / `INTERNAL_SYSTEM_WINDOW`。

### 1.2 应用进程不能把 Activity 放进副屏；shell 可以

应用进程（同一个 App 自己的 Activity）也放不上去：

```
startActivity(SpikeProbeActivity, launchDisplayId=54) →
SecurityException: Permission Denial: starting Intent … from ProcessRecord{… io.github.hypercar}
(pid=…, uid=10327) with launchDisplayId=54
```

改用 Shizuku shell：

```
am start -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity --display 54
→ exit=0，高德窗口出现在 display 54
```

截图（App 侧 ImageReader 直接读 display 54）：186,816 B，**非黑占比 0.307**，画面即高德启动页。
`dumpsys window displays` 里：

```
Display: mDisplayId=54
  mCurrentFocus=null
  mFocusedApp=null
...
1: name=… com.autonavi.minimap/com.autonavi.map.activity.SplashActivity, displayId=54,
   inputConfig=NOT_FOCUSABLE, frame=[665,0][1255,720]
```

两个结论：
1. **`OWN_CONTENT_ONLY` 不阻止别人的 App 渲染**（它限制的是“不镜像默认屏”，不是按 uid 过滤内容）；
2. 但这块屏上的窗口是 `NOT_FOCUSABLE`、`mCurrentFocus=null` → **当前状态下触摸注入无处可去**。
   需要隐藏 flag `SUPPORTS_TOUCH(1<<6) | TRUSTED(1<<10) | OWN_FOCUS(1<<14)`（`OWN_FOCUS` 要求 `TRUSTED`），
   这些 flag 由 **shell 进程**带着建屏才有意义（见 `docs/../headless-android` 参考实现）。

### 1.3 高德在该屏上是竖屏 letterbox

`frame=[665,0][1255,720]`（宽 590 / 高 720，居中）——高德启动页是竖屏 Activity，被放在 1920×720
横向副屏上做成了竖屏竖条。**“高德能不能横屏铺满”是 S1 还要继续验证的产品级问题**；
如果手机版高德全流程都竖屏，D2 全屏地图要么换车机版/横屏版 App，要么重新设计布局。

已取回的截图（App 侧 ImageReader，1920×720）：

- `s1-probe-*.png`：我们自己的 `SpikeProbeActivity` 在 display 57 上**完整铺满 1920×720**（`display=57 view=1920x720`），
  说明「自建屏 + 自己的 Activity」这条渲染链路是通的；
- `s1-amap-shell-*.png`：高德首页被放在中间 590×720 的竖条里，两侧留白（非黑占比 0.307），
  确认竖屏 letterbox。

### 1.4 shell 建屏的第二个坑：`packageName must match the owner uid`

把建屏搬到 Shizuku UserService（shell uid=2000）后，`DisplayManager.createVirtualDisplay` 仍然失败：

```
java.lang.SecurityException: packageName must match the owner uid
  at io.github.hypercar.spike.SpikeShellService.createDisplay(...)
```

排查过程（都写进 logcat `HyperCar`）：

1. `DisplayManagerGlobal.createVirtualDisplay` 把 `context.getPackageName()` 当 `packageName` 传给系统；
   UserService 里的 Context 是宿主 App 的 Application（`io.github.hypercar`），而调用 uid 是 shell(2000) → 不匹配；
2. 用「包名返回 null」的 Context（`NullPackageContext`）构造 `DisplayManager`：已确认
   `nullCtx.package=null`，但系统仍拒绝（`surface=null` 建屏也一样）——说明 HyperOS 不接受 null 包名；
3. **0.1.0.141 起验证成功**：`PackageOverrideContext` 把包名覆盖成 **`com.android.shell`** 后建屏成功：
   `createDisplay OK id=444 flags=0x444b package=com.android.shell`
   —— `0x444b` = `PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|SUPPORTS_TOUCH(0x40)|TRUSTED(0x400)|OWN_FOCUS(0x4000)`。
   140 同时修了控制台 `onNewIntent` 与 shell `am start` 重试；141 新增 `SpikeRunService`
   （前台服务跑 spike + 亮屏锁），解决“手机一息屏/切后台进程被冻结”。

> **测试注意**：Shizuku UserService 进程（`io.github.hypercar:spike`，uid shell）是常驻的，
> 重装 APK 后它仍在跑**旧代码**。每轮测试前必须
> `adb shell ps -A | grep 'io.github.hypercar:spike'` 找到 pid 并 `kill -9`，否则看到的还是上一版行为。

### 1.5 测试环境注意事项

- 控制台是普通 Activity，**手机息屏/被别的 App 顶到后台/下拉通知栏**时进程可能被 HyperOS 冻结；
  现在用 `SpikeRunService`（前台服务 + `SCREEN_BRIGHT_WAKE_LOCK`）在后台跑，不占主屏。
- **虚拟屏只在屏幕亮着时合成**。不持亮屏锁时，D2 里的窗口不会 layout，`mCurrentFocus=null`，
  注入的事件全部丢掉（实测 probe 收到 0 条）。所以 spike 服务必须持锁。
- `am start` 的 `--es auto s1` 在 Activity 已存在时可能不会重建实例；控制台已加 `onNewIntent`，
  也可以直接用 `am start-foreground-service ... --es what s1|s2|s3` 触发。

### 1.6 S1/S2 实测结果（0.1.0.144/146）

- shell 建屏：`id=446/447/449`，`flags=0x444b`；probe Activity 在 D2 上 `mCurrentFocus` 正常。
- 注入路径：应用进程 `SystemServiceHelper.getTransactionCode` 被 hidden API 挡住（`-1`），
  改走 **shell UserService 内**的 `InputManager.injectInputEvent` + `InputEvent.setDisplayId`，
  由 AIDL `tap/swipe/pinch` 暴露；应用侧仍保留 shell `input -d` 基线。
- 30 次 tap：**30/30 成功**；注入→App 收到 DOWN：min 1.0ms / **p50 3.2ms** / p90 9.1ms / max 11.1ms。
- swipe：7 个事件（5 个 MOVE）；pinch：14 个事件。
- 打高德 (960,110)：前后截图差异 **13.9%**（首页 → “我的”页），证明事件真的进了第三方 App。
- shell `input -d` 基线：10/10，p50 约 58ms（含进程启动），只适合兜底。

### 1.7 高德横屏：cardeck 的 `am compat` 方案（已实测可用）

手机版高德默认竖屏锁定，在 1920×720 上是 590×720 竖条 + 两侧留白。cardeck 的
`AppOrientationCompat` 用两个 AOSP 标准 ChangeId 强制改写 orientation：

```
am compat enable 265451093 com.autonavi.minimap   # OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR
am compat enable 265464455 com.autonavi.minimap   # OVERRIDE_ANY_ORIENTATION
```

本机（Android 17 / HyperOS 4，shell 身份）实测：

- 两条命令都成功（`dumpsys platform_compat` 里 `com.autonavi.minimap=true`）；
- 高德从 590×720 竖条变成 **1920×720 全屏横屏**（非黑占比 0.307 → 0.942）；
- 窗口 `mCurrentFocus=…SplashActivity`，触摸注入可用。

注意事项（与 cardeck 一致）：

- `am compat` 是**持久系统覆盖**，会立刻杀掉目标应用；启用后必须 force-stop 再启动才生效；
- **不用时必须 disable**，否则手机上的高德也会一直横屏；cardeck 是「会话结束统一复原」；
- cardeck 注释提到 user build + 非 debuggable 应用可能被拒（`Cannot override a change on a
  non-debuggable app and user build.`）；本机实测放行，但代码里仍要把原始输出写进日志；
- 已封装为 `spike/MapCompat.kt`：S1 在启动高德前 enable、截完图后 disable。
  P2 应改成「进入地图全屏/切到 D2 时 enable，退出时 disable」。

> 另：cardeck 还会优先启动**高德车机版** `com.autonavi.amapauto/.MainMapActivity --windowingMode 1`
> （天生横屏，引导页会丢 display 参数），未安装才回退手机版。本机未装车机版，故走 compat 方案。

## 2. 本次已落地的代码（P0 调试件）

| 文件 | 作用 |
|---|---|
| `app/src/main/java/io/github/hypercar/spike/SpikeConsoleActivity.kt` | 调试控制台（`adb shell am start -n io.github.hypercar/.spike.SpikeConsoleActivity [--es auto s1|s2|s3]`） |
| `.../spike/SpikeRunner.kt` | S1/S2/S3 编排、证据写盘 |
| `.../spike/SpikeDisplay.kt` | 建屏（app / shell 两种）、截图、`am start --display`、dumpsys 取证 |
| `.../spike/SpikeShellService.kt` | **Shizuku UserService**：shell 身份建屏（带隐藏 flag 逐级回退） |
| `.../spike/SpikeShell.kt` | 绑定 user service / 调用建屏 |
| `.../spike/SpikeInput.kt` | S2 注入：shell `input -d` 基线 + `IInputManager.injectInputEvent`（Shizuku binder 包裹） |
| `.../spike/SpikeProbeActivity.kt` | 受控注入目标：全屏计数 + DOWN/MOVE/UP 记录 |
| `.../spike/SpikeCaster.kt` | S3：MediaCodec 1920×720@60 → `nativePushVideoFrame` |
| `.../spike/SpikeReport.kt` | 报告/截图写 `Download/HyperCar/spike/` |
| `app/src/main/aidl/io/github/hypercar/spike/ISpikeShell.aidl` | 建屏服务接口 |
| `rust-core/src/lib.rs` | 新增 JNI：`nativeSetTouchListener`（车机 HID → Kotlin）、`nativeDebugTouch`（回调链自检） |

证据目录：`/sdcard/Download/HyperCar/spike/`。

## 3. 各 spike 状态

### S1（副屏跑高德）— ✅ 通过

- ✅ 建屏权限矩阵（§1.1）
- ✅ shell UserService 建屏成功，`flags=0x444b`（SUPPORTS_TOUCH|TRUSTED|OWN_FOCUS 全生效）
- ✅ probe Activity 在 D2 上正常渲染（1920×720）且有焦点
- ✅ shell `am start --display` 能把高德放到 D2，画面真实渲染
- ✅ 触摸注入能打到高德（截图差异 13.9%）
- ✅ **横屏问题已解决**：`am compat` 两个 ChangeId → 高德 1920×720 全屏（非黑占比 0.307→0.942），
  详见 §1.7；`MapCompat` 已封装，S1 结束会自动 disable 复原

### S2（Shizuku 注入）— ✅ 通过

- tap 30/30，注入→收到 DOWN 的 p50 **3.2ms**（p90 9.1ms）
- swipe / pinch 均成功（事件数与手势一致）
- 主屏无误触（事件按 displayId 定向）
- 注入在 **shell UserService** 内完成（应用进程拿不到 IInputManager 事务码）

### S3（编码 → AirPlay）— ✅ 通过（DiPlay 实测）

- ✅ 编码器：`1920×720@60 8Mbps`，`csd-0=21B / csd-1=8B / codec-config=29B`，surface 有效
- ✅ 22.8s 共 **1470 帧 / 2.12MB**，平均 **64.4fps**（稳定段 60.0~60.5）
- ✅ Rust 侧统计 **fps=60 / kbps=633**，音频 `136~137/s` 不掉
- ✅ 编码管线延迟（合成→编码输出）p50 **15.1ms** / p90 20.4ms；帧间隔 p50 16.3ms
- ✅ 画布 `2560x744 → 1920x720`（`nativeSetVideoSize` 生效）
- ✅ 修复画布竞态后复测：首帧前 `1280x372 → 1920x720` 正确切回，config 不再带错宽高
  （12s 复测：808 帧 / 1.1MB，stabilized 60fps，编码延迟 p50 13.2ms）
- ✅ **触摸回调 JNI 验证**：DiPlay 发来 50 条 HID（`action=0/1/2`），Rust→Kotlin `onTouch` 全部收到
- ⚠ 虚拟屏跑在手机 90Hz 上，编码输出会出现 90fps 段（Rust 统计 fps=90）；建议 P1 在
  `Surface.setFrameRate(60f)` 或丢帧上限里钉死 60，避免多耗带宽
- 接收端：`192.168.1.132:7000`（0x4301 给的端点，RECORD 成功；`.208:7000` 也可达）。
  **需要你在 DiPlay 窗口里确认画面内容与主观延迟。**

### 触摸回调 JNI（第 10/11 个）— ✅ 已验证

- `RustCoreBridge.setTouchListener(TouchListener)` → Rust `onTouch(action,x,y)`（0=DOWN/1=UP/2=MOVE，画布坐标）
- DiPlay 实战收到 **50 条** HID（`[TOUCH] HID action=…`），回调链打通
- `nativeDebugTouch(...)` 保留用于无接收端自检

## 4. 下一步

1. 请在 DiPlay 窗口确认 S3 的画面/延迟（是否有拉伸、抖动）；画布竞态已修，重跑后应无拉伸；
2. 高德横屏问题定方案（高德车机版 / 百度 / 改布局）；
3. 把 S1/S2/S3 从 spike 落到 P1/P2（D1 首页、D2 托管与注入、卡片拖动）。
