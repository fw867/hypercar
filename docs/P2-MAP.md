# P2：D2 高德地图 + D1↔D2 切源

> 记录 P2 的实现与验证。未 commit、未 push。构建 0.1.0.170。
> 依赖 P0 的 shell 建屏/注入与 P1 的 D1 首页。

## 1. 结构

```
D1 1920×720（shell 副屏 #1）
  └─ CarHomeActivity（自绘首页）→ 首页编码器 A（常驻）

D2 1920×720（shell 副屏 #2，独立显示组 + ALWAYS_UNLOCKED）
  └─ 高德（am start --display D2，MapCompat 横屏）→ 地图编码器 B（按需创建）

两个编码器都往 RustCoreBridge.nativePushVideoFrame 推，但同一时刻只有一个
`pushing=true`；切源 = 暂停 A / 恢复 B（或反之）+ requestSyncFrame()，
不重建显示、不重建编码器，车机侧只看到一次 IDR 关键帧。
```

触摸：

- 首页态：Rust HID → `CarHomeActivity.dispatchCarTouch`（零权限直派）；
- 地图态：Rust HID → `MapController.onTouch` → 累积 DOWN/MOVE/UP →
  `SpikeShell.tap/swipe(displayId=D2)`（Shizuku 注入）；
- **顶部下滑退出地图**：起手 y<80 且下滑 >120 → `MapController.requestExit()`，不注入给高德。

## 2. 关键改动

| 文件 | 内容 |
|---|---|
| `spike/SpikeShellService.kt` | 支持多块副屏（`displays` map）、`releaseDisplayById`；建屏 flags 带 `OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED` |
| `spike/SpikeCaster.kt` | `setPushing()` 切源门控、`requestSyncFrame()`；自适应 60fps 抽帧 |
| `carui/MapController.kt` | 新增：MapCompat、D2 建屏、高德启动、双编码器切源、触摸注入、顶部下滑退出 |
| `carui/CarUiCaster.kt` | 启动清残留副屏；初始化 MapController；HID 先走地图路由；停止时先收地图 |
| `carui/CarHomeScreen.kt` | 地图卡片/应用页“地图”改为 `MapController.requestEnter()`（删掉 P1 占位全屏页） |
| `carui/CarUiService.kt` | 调试 action：`mapenter/mapexit/rustswipe` |

## 3. 验证（0.1.0.170）

- ✅ 进图：`MapCompat enable` → `am force-stop` 高德 → D2 `id=489/492` 创建 →
  `am start --display D2` 高德 → 1.8s 后切源；D2 `flags=0x5c4b`、`displayGroupId=2`；
- ✅ 高德窗口：`mBounds=Rect(60, 0 - 1860, 720)`、配置 `land w1200dp h480dp`
  —— 1920×720 横屏，左右各 60px 安全边（P0 截图非黑 0.94 同源，看起来是高德自身的
  最大宽高比/cutout 安全区，不是竖屏 letterbox）；
- ✅ 切源：地图编码器 `fps≈60`（未连 AirPlay 时 60；连 AirPlay + 双编码器时 20~58），
  首页编码器暂停推帧（无 fps 输出）；退出后首页 `fps≈60` 恢复；
- ✅ 退出：顶部下滑 → 地图编码器停止、D2 释放（`hypercar-d2` 计数 0）、
  `am compat disable` 两条均成功、首页恢复；
- ✅ `MapController.stop()` 只在真正进过地图时才 force-stop 高德/复原 compat，
  不会误杀用户手机上正常使用的高德；
- ✅ `CarUiCaster.start` 会先 `releaseDisplay()` 清掉上一进程残留的副屏
  （shell UserService 不随 App 进程退出）。

## 4. 已知限制 / 下一步

1. **两个编码器常驻**：地图态首页编码器仍在编码（只是不推帧），1080p60×2 + AirPlay 时
   地图 fps 会掉到 20~58；P2.1 可改成“地图态停首页编码器/保留显示”或降地图帧率。
2. 高德左右 60px 安全边未消除；若要真正 1920 满宽，需要试 `wm` cutout/宽高比覆盖。
3. 退出只有“顶部下滑”手势，没有屏幕上的返回按钮；P2.1 可加悬浮返回条或车机返回键。
4. 触摸注入只做 tap/swipe，未做双指缩放（高德地图缩放）。
5. 进程崩溃/强杀时 D2 与 compat 可能残留；下次 `CarUiCaster.start` 清屏，但 compat
   需要手动/下次退出时复原。
6. 高德首次启动的隐私弹窗需要人工点一次；点过之后不再出现。

## 5. 调试命令

```bash
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action start
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action mapenter
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action mapexit
# 顶部下滑退出（模拟 Rust 触摸序列）
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService \
  --es action rustswipe --ef x1 960 --ef y1 40 --ef x2 960 --ef y2 300
```

## 6. 触摸/断开修复（0.1.0.177）

- **触摸坐标根因**：`/info` 的车机显示尺寸 2560×744 会写入 `encoder_size`，而实际编码画布是
  1920×720；Rust 之前拿 `encoder_size` 当触摸目标，HID 坐标按 2560×744 缩放后落到画布外
  （例如 y=743 > 719），所以车机上点不动。
- **修复**：Rust `AirplayParams` 新增独立的 `touch_size`，由 `nativeSetVideoSize` JNI 直接写入；
  `encoder_size` 继续由 `/info` + 编码器回报驱动（车机屏幕参数/VideoConfig 保持原行为）。
  实测 `TOUCH: 车机(1900,941) 空间 2560x744 → 画布 1920x720 = (1425,719)`。
- **断开卡死**：`releaseSessionResources` 里同步调用 `CarUiCaster.stop()`，其 shell binder/
  编码器释放可能阻塞，导致 UI 停在“正在断开”。改为 `CarUiCaster.stopAsync()`（daemon 线程），
  断开流程不再等清理；且只有真正断过面板时才调用 `setPhysicalScreenPower(true)`。
  实测点断开后 `SESSION: 已断开` 立即回 Idle，`CARUI: 已停止` 随后异步完成。
- 车机屏幕参数（`/info`）的覆盖逻辑已恢复；不再强制把引擎配置改成 1920。