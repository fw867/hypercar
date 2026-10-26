# P1：自绘 D1 车机首页 + 音乐卡片

> 记录 P1 的架构、当前实现与验证状态。未 commit、未 push。
> 截图（离屏渲染）：`.preview/p1-home.png`（1920×720）。

## 0. 结论（0.1.0.166，2026-10-26 23:50）

| 项 | 状态 | 证据 |
|---|---|---|
| D1 自绘首页 v2（左 Dock 底部「更多」切应用页；音乐卡大封面/数字时间/底部控制） | ✅ | `.preview/p1-home.png`、`.preview/p1-apps.png` |
| 独立显示组 + 免锁屏（cardeck 修复移植） | ✅ | `flags=0x5c4b`、`displayGroupId=2`；`KEYCODE_SLEEP` 后 `mWakefulness=Dozing`，D1 仍 **60fps**；默认不关面板，触摸唤醒正常 |
| MediaSession 音乐联动（封面/曲名/进度/控制） | ✅ | QQ音乐会话；`playpause` → `TransportControls` |
| Rust HID 触摸 | ✅ | `rusttap` 全链：`nativeDebugTouch` → 引擎 → `TouchListener` → Compose 地图全屏/返回 |
| 会话自动启停 | ✅ | `RECORD 完成` → `CARUI: 启动 ok display=470`；断开 → `已停止`，`hypercar-d1` 归零 |
| 设置入口 | ✅ | 自绘首页 / 连接时关闭手机屏幕 / 音乐通知使用权 |

## 1. 架构

```
AirPlay RECORD（现有引擎）
   └─ CarUiCaster（P1 新增）
        ├─ MediaCodec(avc 1920×720@60) 的 InputSurface
        ├─ Shizuku shell 建 D1 副屏（PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|TRUSTED|OWN_FOCUS）
        ├─ shell `am start --display D1` 启动 CarHomeActivity（Compose，自绘首页）
        ├─ 编码输出 → RustCoreBridge.nativePushVideoFrame（现有视频泵）
        └─ Rust HID → CarHomeActivity.dispatchCarTouch（D1 零权限直派）
              └─（D2 的高德触摸才走 Shizuku 注入，P2）
```

- **D1 首页**：Compose（`CarHomeActivity` + `CarHomeScreen`）。
  - 顶部：时钟/日期 + CarPlay 在线 + 1920×720/60fps 徽章；
  - **左侧竖向 Dock**（按用户要求，宝马带鱼屏底部横条不好看）：地图/音乐/电话/设置 + 底部 CarPlay 标识；
  - 左侧大地图卡片（网格/道路占位，点击进全屏占位页）；
  - 右侧音乐卡片（封面/曲名/歌手/进度/上一首/播放暂停/下一首）。
- **音乐**：`MusicNotificationListener`（NotificationListenerService）+ `MediaSessionManager.getActiveSessions`
  → `MusicBridge.state`（StateFlow）。标准 MediaSession，不依赖各家私有 SDK。
  歌词不做（标准 MediaSession 没有歌词字段）。
- **编码/生命周期**：`CarUiService`（前台服务 + 亮屏锁）目前手动启动；后续接进会话层
  （RECORD 完成 → start，断开 → stop）。

## 2. 当前验证（0.1.0.166，DiPlay 接收端）

- ✅ 首页离屏渲染截图正常：**左侧竖向 Dock**（按要求）、地图卡片、音乐卡片、时钟；
- ✅ **实时 60fps**：手机解锁亮屏时，编码器统计 `fps=60`、编码延迟 p50 ~13.5ms、码率 ~2Mbps；
  Rust 侧 `RECORD 完成`、视频泵正常（DiPlay 侧画面由用户确认）；
- ✅ 音乐卡片读到 **QQ音乐** 会话：`如果天有情 / 刘德华`，封面、时长 4:47、进度 2:30；
  `playpause` 已调用 `MediaController.TransportControls`（日志 true），播放状态由 QQ音乐自身决定；
- ✅ 地图卡片点击 → 全屏占位页、左上返回 → 首页；**Rust 通路实测**：
  `CarUiService --es action rusttap` → `nativeDebugTouch` → 引擎触摸通道 → `TouchListener`
  → `CarHomeActivity.dispatchCarTouch` → Compose 切到全屏/返回（截图验证）；
- ✅ **会话自动启停**：AirPlay `RECORD 完成` 的 `onState("connected")` → `CarUiCaster.start`
  （日志 `CARUI: 启动 ok display=466/467`）；断开/拆除 → `releaseSessionResources` 里
  `CarUiCaster.stop()`（`dumpsys display` 里 `hypercar-d1` 归零）；
- ✅ 设置页新增「自绘车机首页」「连接时关闭手机屏幕（默认关）」「音乐卡片通知使用权」；
- ✅ 为强制 60fps 出帧，地图卡片加了轻微脉冲动画（否则静态 Compose 只在时钟跳秒时出帧，
  会掉到 1fps 并触发待机卡片插帧）；
- ⚠ 音乐 `playpause` 调用已发到 QQ音乐（日志 true），但 QQ音乐当时是 `PAUSED(2)`、2:30，
  `play()` 没有真正恢复播放——需要一次“QQ音乐在前台播放中”的人工确认；接口本身是标准
  `TransportControls`，不依赖 App 私有实现；
- ✅ **锁屏后 D1 继续 60fps（默认不关面板）**：`KEYCODE_SLEEP` → `mWakefulness=Dozing`，
  编码器仍 `fps=60`；靠独立显示组 + `ALWAYS_UNLOCKED` + partial wake lock 存活。
  面板没有被强制断电，所以电源键/触摸唤醒都正常（不存在按两次电源键的问题）；
- ⚠ 23:10 现场 DiPlay 主动断开过一次（`LINK closed` → `屏幕流写失败（车机侧断开）`），随后重连 RECORD；
  这是接收端行为，重连即可。

## 3. 调试命令（当前）

```bash
# 启动 D1 首页（前提：Shizuku 在线；AirPlay 会话可选；锁屏后 D1 仍继续出帧）
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action start
# 离屏截图（不依赖屏幕状态）：存 Download/HyperCar/spike/carui-home-*.png
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action shot
# 直接给 D1 派发一次点按（验证 Compose 交互）
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action tap --ef x 600 --ef y 350
# 播放/暂停
adb shell am start-foreground-service -n io.github.hypercar/.carui.CarUiService --es action playpause
# 通知使用权（音乐卡片）
adb shell cmd notification allow_listener io.github.hypercar/.carui.MusicNotificationListener
```

## 4. 锁屏/息屏冻结：cardeck 的修复（已移植）

问题根因（cardeck 2026-10-23 实测）：

- 虚拟屏默认落在**默认 DisplayGroup**：物理主屏休眠/断电时整组跟着 OFF，车机侧黑屏/冻结；
- 锁屏/Doze 还会让 Keyguard 盖住虚拟屏、CPU 降频。

cardeck 的做法（`a4feec8` / `af98832` / `d926aac` / `255bbfa`，文档 `cardeck/docs/PHONE-SCREEN-OFF.md`）：

1. 建屏 flags 加两个 @hide 位：`OWN_DISPLAY_GROUP = 1<<11 = 0x800`（独立显示组）、
   `ALWAYS_UNLOCKED = 1<<12 = 0x1000`（锁屏不画 Keyguard）；**三级降级**，
   缺 `ADD_ALWAYS_UNLOCKED_DISPLAY` 权限时退到「仅独立组」，再退回基础 flags；
2. 连接时「真息屏」（scrcpy `-S` 同款）：`SurfaceControl.setDisplayPowerMode(物理屏 token, 0)`
   只断面板电源，系统逻辑上仍亮屏、不锁屏/不进 Doze；SDK≥34 用 `DisplayControl` 类加载器取 token；
3. 会话期 `PARTIAL_WAKE_LOCK` + `screen_off_timeout` 拉满，结束后恢复。

HyperCar 的移植：

- `SpikeShellService.createDisplay` 候选 flags 增加 `0x800|0x1000`（带降级），日志里看 `flags=`；
- AIDL 新增 `setPhysicalScreenPower(boolean)`；`SpikeShellService` 用 `DisplayControl`（SDK≥34：
  `ClassLoaderFactory` + `libandroid_servers`）取物理屏 token，再 `SurfaceControl.setDisplayPowerMode`；
- `CarUiCaster` 启动后：面板断电 + `PARTIAL_WAKE_LOCK` + `screen_off_timeout=2147483647`；
  停止/断开时恢复面板、超时和锁；
- 设置页新增「连接时关闭手机屏幕」开关（默认开）。

实测（0.1.0.166）：

- `create=OK id=472 flags=0x5c4b`，`displayGroupId=2`，DisplayInfo 里含
  `FLAG_OWN_DISPLAY_GROUP` / `FLAG_ALWAYS_UNLOCKED`；
- `setPhysicalScreenPower(false) ok=true` → `mWakefulness=Awake`（**不是 Dozing**），
  面板全黑时 D1 源 ~90fps；发送端按源帧率自适应抽帧（>75fps 每 3 帧丢 1，>100fps 每 2 帧丢 1）到 ~60fps（实测 61±1）；
- 停止 → `setPhysicalScreenPower(true) ok=true`，`hypercar-d1` 释放，屏幕超时恢复 15s；\n- 锁屏实测（0.1.0.166，默认不关面板）：`input keyevent KEYCODE_SLEEP` → `mWakefulness=Dozing`，\n  编码器仍 `fps=60`（D1 靠 OWN_DISPLAY_GROUP + ALWAYS_UNLOCKED + partial wake lock 存活）；\n  唤醒用正常电源键/触摸即可（面板没被强制断电，不存在「按两次电源键」问题）。\n- 可选的「连接时关闭手机屏幕」仍保留（默认关）：开启后才是 scrcpy 式真息屏，面板黑、按电源键两次恢复。

> 23:22 的 AirPlay 实测只跑了 2 秒就被 DiPlay 断开（`屏幕流写失败（车机侧断开）`），
> 已加 60fps 发送上限（编码输入 90~110fps 时丢弃非关键帧），降低接收端压力；
> DiPlay 自身的会话稳定性另算（23:10 也主动断过一次）。

## 5. cardeck 后续提交（0.1.0.166 采用）

- `c1de35d`「真·假息屏 + 虚拟屏免锁屏豁免」确认：`ALWAYS_UNLOCKED` 必须与
  `OWN_DISPLAY_GROUP` 同时声明，否则会被系统剥掉；我们的 `0x5c4b` 已同时具备。
- 该提交的「真息屏」是可选特性；HyperCar 默认**不在连接时断电面板**，只保留
  独立显示组 + 免锁屏 + CPU 唤醒锁，让锁屏后继续 60fps，同时保留触摸/电源键正常唤醒。

## 6. 下一步

1. 人工确认：QQ音乐/汽水**正在播放**时，车机音乐卡片的播放/暂停/上下曲是否真的生效；
2. DiPlay 稳定会话下的完整验收（面板全黑、60fps、触摸、音乐卡）；
3. 接进会话层已完成（RECORD 自动 start、断开 stop）；补「首页启动失败」的降级提示（Shizuku 缺失）；
4. 地图卡片：P2 已落地（D2 高德 + `MapCompat` + 双编码器切源 + 顶部下滑退出），见 `docs/P2-MAP.md`；下一步 P2.1 优化（双编码器/60px 边）与 P3 拖动缩放。
