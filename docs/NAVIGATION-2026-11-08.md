# HUD / 仪表导航 —— 2026-11-08 Link SDK 实测记录

> 关联文档：[NAVIGATION.md](NAVIGATION.md)（完整方案与历史）、[NAVIGATION.md#CarWith](NAVIGATION.md)。

## 2026-11-08 二次上车前 Link SDK 复测与三条绑定路径对照

本节记录当天在本机（Xiaomi M154FF / Android 17 / HyperOS 4，高德 17.02.0.2010）上的实测，
只写观察到的事实与结论，不写推测性承诺。

### 一、先确认入口本身是通的（0.1.0.577）

| 时刻 | 日志 | 说明 |
|---|---|---|
| 16:23:44.755 | `HUD-LINK: callback=1/2/3 {bizType:113, datas:String(...)}` | 注册后立刻收到 |
| 16:25:07.905 | `HUD-LINK: callback=256` | |
| 16:26:32.228 | `HUD-LINK: callback=512` | 85 s 内 +256 → **3.01 次/秒** |
| 16:29:22.296 | `HUD-LINK: callback=1024` | 170 s 内 +512 → **3.01 次/秒** |

- 绑定组件：`com.autonavi.minimap/.wearable.connect.glass.AmapLinkService`，
  组件 action `com.autonavi.minimap.service.NAVIGATION_SERVICE`，权限
  `com.autonavi.minimap.permission.NAVIGATION_SERVICE`（`prot=normal`）；
- AIDL（与高德 dex 内定义一致）：
  - `com.amap.linksdk.aidldefine.ILinkSdkControl`：`authenticate / execute / ping / registerListener / unregisterListener`
  - `com.amap.linksdk.aidldefine.ILinkSdkCallback`：`onAmapCallback(String)` / `onAmapCallbackWithFd(FileDescriptor)`
- 本次实测用的是 transaction `1`（authenticate）、`3`（registerListener）、`4`（unregisterListener）；
- 回调全部为 `bizType:113`，`datas` 为 366–1782 字节字符串，`LinkEnvelope.decode`（base64 → id+key+nonce → AES-GCM）持续解密成功，
  无 `envelope invalid` / `FD read` / `disconnected` 日志；
- `authenticate` / `registered` 两行日志已被环形缓冲冲掉，但**持续收到回调本身**即证明二者成功。

结论：**这个入口确实能拿到高德导航数据**，此前“一定需要 API Key”的概括不成立。

### 二、覆盖安装后绑定失败：MIUI 的关联启动拦截

同一天多次覆盖安装 APK（577 → 592）之后，App 自身 `bindService` 全部失败：

```
W WakePathChecker: MIUILog-AutoStart, Service/Provider/Broadcast Reject userId=0
  caller=null callee=com.autonavi.minimap
  classname=com.amap.bundle.wearable.connect.glass.AmapLinkService
  action=com.autonavi.minimap.service.NAVIGATION_SERVICE wakeType=8
HUD-LINK: bind=false attempt=1..37
```

排查过的事实：

| 尝试 | 结果 |
|---|---|
| `cmd appops set io.github.hypercar 10022 allow`（关联启动） | 仍拒绝 |
| 对 callee（高德）也设 10022 allow | 仍拒绝 |
| 10021（自启动）/10008（后台弹界面）allow | 仍拒绝 |
| 把 App 切到前台（MainActivity resumed） | 仍拒绝 |
| 打开 MIUI「HyperCar → 关联启动」页 | **「允许链式启动」是开的**；「始终允许启动」白名单只有 Edge、Shizuku，**没有高德地图** |

→ MIUI 按“目标应用白名单”放行，高德不在名单里就**静默拒绝、不弹窗**；
覆盖安装 APK 会重置该项，这正是 577 能绑、之后不能绑的原因（代码未变）。
触发一次 bind 时反复截屏（8 张）+ 窗口列表均无任何权限弹窗。

### 三、为绕开 MIUI 新增的 shell 通道（已实现，未打通）

新增文件与接口：

| 位置 | 内容 |
|---|---|
| `app/src/main/aidl/io/github/hypercar/spike/IAmapLinkSink.aidl` | shell → App 回调：`onCallback(code,payload)` / `onState(state)` |
| `ISpikeShell.aidl` | 新增 `connectAmapLink(in IAmapLinkSink)` / `disconnectAmapLink()` / `amapLinkStatus()` |
| `spike/ShellAmapLink.kt` | 在 Shizuku UserService（shell uid）里绑定 AmapLinkService、authenticate、registerListener、转发回调（含 FD 变体） |
| `spike/ShellActivityBind.kt` | 以 shell 身份直接调 `IActivityManager.bindService`（手写 Parcel，4 种参数布局探测） |
| `debug/AmapLinkDebugReceiver.kt` + Manifest | 受 `android.permission.DUMP` 保护的调试入口，无车机会话也能触发 |

三条绑定路径的实测结果：

| 路径 | 实现方式 | 结果 |
|---|---|---|
| A. App 自绑 | `context.bindService(...)` | ❌ 被 MIUI `WakePathChecker` 拒绝（见上） |
| B. shell 进程 Context 绑 | UserService 内 `createPackageContext("com.android.shell").bindService(...)` | ❌ `resolve=com.autonavi.minimap/...AmapLinkService` 成功、`ctx=ContextImpl pkg=com.android.shell` 正确、**AMS 无任何拒绝日志**，但 `bindService` 返回 `false`（该进程没有可用的 `IApplicationThread`） |
| C. IActivityManager 直连 | `ShizukuBinderWrapper` + `SystemServiceHelper.getTransactionCode`（App 侧）/ 反射 `ServiceManager.getService("activity")` + `TRANSACTION_bindService`（shell 侧） | ❌ 两条都在取事务码处失败：`am-bind=ERR no-transaction-code`（Android 17 上反射取不到 `IActivityManager$Stub` 字段） |

另外：`PackageOverrideContext` 这类「只覆盖 getPackageName」的 wrapper 对 `bindService` 无效
（AMS 用的是 base `ContextImpl` 的包身份，会报 `SecurityException: io.github.hypercar does not belong to uid 2000`）。

### 四、当前结论

1. **数据入口存在且可用**（A 路径在高德运行、MIUI 授权未重置时实测 1024 条回调、3 次/秒）；
2. **阻塞点不是协议而是 MIUI 的关联启动白名单**，且每次覆盖安装会重置，需要在系统侧重新授权；
3. shell 代绑与 IActivityManager 直连在当前固件/SDK 上均未打通，代码保留但不作为主路径；
4. 回调里的 `datas` 字段内容**仍未确认**（采样被 `debugLogEnabled` 开关挡住，未落盘）；
   HUD 端到端（0x5202/0x5201 → 车机显示）**未验证**。

### 五、复现与排查命令

```bash
# 1. 手动触发探针（DUMP 权限；不需要车机会话）
adb shell am broadcast -n io.github.hypercar/.debug.AmapLinkDebugReceiver \
  -a io.github.hypercar.DEBUG_AMAP_LINK --ez start true
adb shell am broadcast -n io.github.hypercar/.debug.AmapLinkDebugReceiver \
  -a io.github.hypercar.DEBUG_AMAP_LINK --ez start false

# 2. 看绑定与回调（HUD-LINK: / shell: / am-bind=）
adb shell logcat -d | grep -E 'HUD-LINK|HUD-AMBIND|WakePathChecker'

# 3. 确认 MIUI 是否在拦
adb shell logcat -d | grep WakePathChecker | tail
adb shell cmd appops get io.github.hypercar 10022

# 4. 打开 MIUI 的关联启动页（HyperCar）
adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:io.github.hypercar
```

### 六、后续待办（未实施）

- 采样 `bizType:113` 的 `datas` 字段（当前被 `SettingsStore.debugLogEnabled=false` 挡住，采样文件
  `Android/data/io.github.hypercar/files/diagnostics/hud-link-capture.json` 不会生成）；
- 若 MIUI 授权恢复，A 路径会自动恢复（`AmapLinkProbe.bind()` 已改为 **A 优先、shell 兜底**）；
- 若要彻底摆脱 MIUI 重置问题，需要 shell 进程里拿到可用的事务码或 `IApplicationThread`，
  当前两条都未成功，不建议继续在这条路上投入。
