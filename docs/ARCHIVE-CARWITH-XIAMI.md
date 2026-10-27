# 存档：小米 CarWith / 小米定制版 CarLife 路线（已放弃）

> **状态**：2026-10-28 正式放弃「小米定制版 CarLife + CarWith 宿主」这条路线。
> 当前版本只支持 **公版 `com.baidu.carlife`（自渲染）**，见 `docs/NEGOTIATION-GAPS.md` §27。
> 本文件只做**知识存档**：万一将来出现更好的 CarWith 集成方式（例如小米开放宿主接口、
> 或拿到签名权限），可以照着这里少走弯路，不必从零反编译。

---

## 1. 两版 CarLife 的角色分工（反编译结论）

| 包 | `isCarLifeApp()` | `makeScreenImpl()` 结果 | 谁画车机界面 |
|---|---|---|---|
| `com.baidu.carlife`（公版） | `true` | `ScreenCarlifeImpl` | **CarLife 自己**（百度地图/音乐/Dock + 触摸） |
| `com.baidu.carlife.xiaomi`（小米定制版） | `false` | `BaseScreenImpl` / `ScreenRealImpl` | **宿主**：CarWith，或冒充宿主的我们 |

决策点在 `MixPhoneStrategy.makeScreenImpl()`：

```java
needReal() = openglRenderType() == 0 || openglRenderType() == 2
if (needReal()) return new ScreenRealImpl();        // 把编码器输入面交给宿主
if (isCarLifeApp()) return new ScreenCarlifeImpl(); // 自己渲染
```

而 `openglRenderType()` 读的是宿主的回答：

- `use_opengl_type = 1`（OPENGL_OPEN）→ 公版走 `ScreenCarlifeImpl`（自渲染）
- `use_opengl_type = 0`（OPENGL_CLOSE）→ 走 `ScreenRealImpl`，**编码面交给宿主**

> 这条"0/1"曾经让我们绕了很久：答 1 时小米版不出画面，答 0 时公版不自渲染。
> 正确组合是 **公版 + 答 1**。

---

## 2. 小米定制版为什么"没有原生界面"

它把 `CastConfig.surface`（编码器输入面）交给宿主，宿主负责画。真实场景里宿主是 CarWith：

```
CarWith Launcher ──画到──▶ VirtualDisplay ← sink = CarLife 编码器输入面
                                   → CarLife 编码 → 8240 → 车机
```

所以用小米版时，车机界面 = **CarWith 的 Launcher**，不是 CarLife 的。它一旦不在，
画面就只能由我们自己画（`CastDisplay` + `Presentation` 那条路，已弃用）。

---

## 3. 冒充 CarWith 宿主的关键（当时确实打通了）

```kotlin
// 让 CarLife 来 bind 我们（等价于 CarWith 的行为）
val it = Intent().setClassName(carLifePkg, "com.baidu.carlife.service.CarlifeConnectService")
it.putExtra("bindPackageName", ourPkg)          // ← 我们自己
it.putExtra("bindClassName", "io.github.hypercar.carlife.CastHostService")
it.putExtra("usb_type", 0)                      // 0=WIFI_HOTSPOT 1=P2P 2=WIFI_BOX 11=AOA 12=ADB 13=NONE
context.startService(it)
```

`ICarLifeCastManager` 的 AIDL 事务号（我们手写 `Binder.onTransact` 实现）：

```
1 = prepareCast   parcel: writeInt(present=1) 然后 int width, int height, int dpi, Surface
2 = stopCast
3 = setSystemAudioCallback
4 = setCastCallback
5 = invokeMethod(Intent) → Bundle
1598968902 = INTERFACE_TRANSACTION → "com.baidu.carlife.mixing.aidl.ICarLifeCastManager"
```

CarLife 会问宿主的动作（`invokeMethod` 的 action）：

| action | 该回什么 |
|---|---|
| `use_carlife_opengl` | 公版回 `use_opengl_type=1`；小米版回 0 |
| `carlife_request_device_id` | `Bundle{ device_id = 蓝牙 MAC 后 8 位十六进制 }` |
| `carlife_encode_format` | `Bundle{ width, height, i-frame-interval=2 }` |
| `carlife_notify_vehicle_channel` | CarWith 只记录，返回 null 可接受 |
| `carlife_encode_soft` / `notify_init_frame` / `hu_bluetooth_mac` / `connect_audio_type` | 通知类，返回 null |

宿主画布（小米版 + 答 0 时）：

```kotlin
val vd = displayManager.createVirtualDisplay(
    "hypercar-cast", w, h, dpi, castConfigSurface,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,  // PUBLIC 需要 ADD_MIRROR_DISPLAY（签名级）
    null, null,
)
// 私有虚显**不能** startActivity(launchDisplayId=…)，只能挂 Presentation，
// 且 Presentation 必须在有 Looper 的线程创建（binder 回调线程没有 Looper）。
```

---

## 4. 为什么放弃：CarWith 路线的四道门（非 root 无解）

| 门 | 证据 |
|---|---|
| 启动 `CarlinkService` 要签名权限 | `permission="miui.carlink.permission.START_CARLINKSERVICE"`，`protectionLevel=0x3`（signatureOrSystem） |
| `CarlifeConnService` 校验调用方 UID | 只认 `com.baidu.carlife(.xiaomi)`，否则 `SecurityException("Caller is not an authorized CarLife application")` |
| 会开始投屏的界面全非导出 | `CastingActivity` / `TransparentActivity` / `CarLifeConnectActivity` … `exported=false` |
| 私有虚显不能放别人的 Activity | `createVirtualDisplay(PUBLIC)` 要 `ADD_MIRROR_DISPLAY`；`startActivity(launchDisplayId=私有屏)` 抛 SecurityException |

**唯一成功过的一步**（记录在此，将来有用）：CarLife 确实能 bind CarWith 的宿主服务——

```
ActivityManager: Start proc …:com.miui.carlink … for bound-service
                 {com.miui.carlink/com.xiaomi.ucar.carlife.CarlifeConnService}
```

但它被绑上后只注册触摸/音频监听、存设备信息，**不会**去启 `CarlinkService`；
真正让 CarWith 建虚显的是它自己的连接流程（非导出 + 签名权限），外部无法触发。

---

## 5. 还有一个坑：CarWith 会不停把小米版拉回来

`com.miui.carlink/…CarlifeKeepAliveService` 会持续拉起 `com.baidu.carlife.xiaomi`，
它一旦活着就**占住 7240**，把公版的会话挤掉 → 表现为"公版连上了却一帧不出/只出母帧"。

`am force-stop` 挡不住（CarWith 会重启它），只有：

```bash
pm disable-user --user 0 com.baidu.carlife.xiaomi   # 冻结（恢复：pm enable --user 0 …）
```

---

## 6. 如果将来重启这条路，建议的顺序

1. 先确认能否**合法**拿到 `miui.carlink.permission.START_CARLINKSERVICE`（root / 官方开放 / 系统签名）。
   拿不到就不用往下走——四道门的其余三道都只是它的补充。
2. 若 CarWith 能启动 `CarlinkService`，则 CarLife 用**小米定制版 + 答 `use_opengl_type=0`**，
   让 CarWith 画界面，我们从 8240 取帧转发 AirPlay。
3. `prepareCast` 的 `CastConfig` 尺寸会被 CarWith 夹取（`e2.h.a(...)`），别假设用户设的值生效。
4. 触摸走 CarWith 自己的 UITouch 链路，我们不需要注入虚显。

> 当前公版路线（自渲染）在这些点上更简单：**不需要宿主、不需要虚显、不需要注入触摸**，
> 所以除非第 1 条有突破，否则不建议回头。
