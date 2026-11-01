# 已配对车机 Wi-Fi 上的短暂蓝牙可发现兜底

## 依据与范围

用户确认该方案后实现。此前二次上车在蓝牙设置/车机重新搜索之后才收到连入，存在相关性但尚不能确定 HyperOS 或车机为唯一根因。

已交叉核对原始 HCI Read_BD_ADDR、ADB secure bluetooth_address 与应用协议身份，三者均为同一手机地址；不能将应用读取到的隐私占位值理解为手机实际对外使用了另一个经典蓝牙地址。MAC 不一致不是当前优先修复方向。

## 触发条件

设置 → 行为 → “蓝牙回连唤醒”，本次默认开启，可随时关闭。仅用于当前手动或自动连接任务：

1. 当前网络已通过已有 SSID/目标车机/本车配对凭据检查，并成功绑定车机 Wi-Fi。
2. RFCOMM 服务已实际注册、ctrl mDNS 广播已启动。
3. 上述条件持续 5 秒，还没有新 iAP2 会话、活跃 iAP2 或本轮 ctrl 邀请。
4. Shizuku 已授权且可用；绑定 shell 后再次检查网络、服务、连接状态与开关。

满足条件后，本轮连接最多尝试一次 30 秒经典可发现。端点等待的 300 秒外层重试不会重置机会。换网络/服务未就绪/关闭开关时重新计算触发前的 5 秒宽限，不会在无关 Wi-Fi 或首配未知网络上触发。

不启用 BLE 广播、不扫描其他设备、不重启蓝牙、不改地址或配对身份，也不跳过本轮 AirPlay 激活门槛。

## 清理与兼容

- 收到 iAP2 连入/本轮邀请、网络变化、关闭开关、停止/取消当前连接时结束。
- 蓝牙原本已可发现或不处于可连接状态时跳过，不接管原有窗口。
- 每个请求使用独立 token，旧清理不能关闭新轮次窗口；用户/系统修改可发现超时后不再接管该状态。
- 仅在 shell 身份中调用隐藏蓝牙 API，清除 Binder 调用者身份并使用 com.android.shell attribution。失败只记录 BT-DISCOVERY，不取消连接任务，也不弹出后台系统确认。
- HyperOS 当前隐藏 createAdapter 接受 Context，与部分 Android 版本的 AttributionSource 签名不同；两者分别适配。headless shell 还需要初始化 BluetoothServiceManager，否则适配器为 null。
- 统一串行绑定 SpikeShell，避免兜底与自绘 UI 同时绑定；正在绑定时 UI 不执行旧进程清理。

## HyperOS 实测：不能仅依赖系统超时

通过临时 Java 探针验证：初始 Android scanMode=21，可发现超时=60s。setDiscoverableTimeout(3s) 与 setScanMode(23) 均返回成功（0），但约 4 秒后仍为 mode=23。探针 finally 已恢复 mode=21、timeout=60s。

因此实际实现使用独立的 BluetoothDiscoveryWatchdog shell 进程：

- 自己持有快照与计时器，到期主动结束窗口并恢复超时。
- 父 UserService 保持其 stdin 管道；显式结束时关闭管道，父服务死亡时内核关闭管道，同样触发 EOF 清理。
- App 的 Binder owner 死亡也会要求父服务关闭管道。
- 清理结果经 stdout 回执给 App 日志，shell 同时写 HyperCar-BT-Discovery 日志。

Android 框架参考：
https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothAdapter.java

## 验证

使用本次编译的 APK 作为 shell classpath，未覆盖安装手机上的正式应用，直接运行同一 Watchdog 类：

- `--probe`：初始 mode=21，timeout=60s。
- 3 秒窗口，stdin 保持 5 秒：返回开启成功、到期清理成功；随后探针确认 mode=21、timeout=60s。
- 30 秒窗口，stdin 在约 1 秒后关闭：提前清理成功；随后探针确认 mode=21、timeout=60s。验证的是管道关闭恢复路径，不是车机已成功自动重连。
- Release 混淆后的 APK 同样通过 2 秒到期清理测试，随后确认 mode=21、timeout=60s；app_process 入口没有被 R8 移除。手机上的临时探针与测试 APK 已删除，正式应用未覆盖安装。

单元测试共 84 项通过（0 失败、0 错误）。覆盖宽限计时、一次性机会、网络/开关变化、会话开始取消、到期恢复、提前结束、旧 token、用户修改状态、API 拒绝回滚及非法窗口长度。Debug、Release 编译及 Release vital lint 通过，git diff --check 通过。

完整实车验收仍待进行：安装新版本，不打开系统蓝牙设置、不让车机手动搜索，上车接入已配对 Wi-Fi，观察 BT-DISCOVERY、SDP 注册、BT-S 连入和 ctrl 邀请顺序，并确认建立后持续推流。尤其需要区分“临时可发现调用成功”与“确实能让该 MGU 车机恢复二次会话”。
