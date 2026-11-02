# 重连入口加固与 CarPlay 手机身份标记

## 本轮实现

依据 `CARPLAY-PRECONDITIONS-2026-11-02.md` 和 Cardeck 协议笔记实施，不把参考实现差异当成已证实根因。

### 待机监听与媒体会话分离

- 自动模式下，网络丢失、引擎 ended、连接失败的默认清理结束当前蓝牙会话及媒体/Wi-Fi 资源，但保留 Iap2Coordinator 和主 SDP/RFCOMM listener，不再无条件 stop 后重新注册。
- 清理后恢复待机回调和 guard；下一轮仍需要本轮车机邀请/有效端点。
- 关闭自动模式时保留原完整释放行为，手动连接仍可用；真正的蓝牙栈故障继续由 RecoveringListener 重新注册。
- 首配认证、问候内容、真实 MAC、sender / srcvers=950.7.1 和媒体协议未改，未开启宝马主动 iAP2 拨号。

### 明确广播网络与注册状态

- Android 13+ 使用 `NsdServiceInfo.setNetwork` 绑定所选车机 Network；较旧版本保留进程绑定路径。此前认为必须到 API 36 才可指定注册 Network 不准确，已修正源码说明。
- `isAdvertising` 仅在当前注册收到 onServiceRegistered 后为 true；提交中与失败不再冒充就绪。
- ServiceRegistrationState 使用代次屏蔽旧成功、失败和注销回调。停止后迟到的成功回调只尝试清理旧 listener，不能重启当前广播。
- 同网络等待邀请期间，成功注册 30 秒后可有限刷新，最多两次；保留同一 TCP 监听端口。收到邀请后停止这种刷新，不实现每秒撤销重注册。
- 注册超时 10 秒允许重新申请；日志记录 Network、端口和申请/成功/失败阶段。本地注册成功仍不等于车机已收到，效果要用车机邀请或同期抓包确认。

### 额外 CarPlay 手机标记

- UUID：`2d8d2466-e14d-451c-88bc-7301abea291a`。
- CatPlay 的 Linux 方法通过 BlueZ/HCI MGMT 添加 EIR UUID；本项目 Android 版本通过系统 `listenUsingRfcommWithServiceRecord` 注册额外服务，由系统蓝牙栈维护 SDP/EIR，不直接写 HCI、不改蓝牙全局开关、类别、配对信息或钥匙组件。
- 这不是“Linux 原样注入 EIR”的实现：Android 会分配独立 RFCOMM channel，并在 SDP 增加服务。该服务的连接接入同一 iAP2 发送端，不是无人处理的空监听。
- 主 `…cafe` listener 先就绪，标记服务再注册；标记失败独立节流重试，不关闭主服务。两个入口共用目标车机过滤和单会话 CAS；标记入口不能替换已在建立/运行的 iAP2 会话。
- 标记随主蓝牙生命周期停止、恢复；设置新增“CarPlay 蓝牙身份广播”，默认开启，可单独切换且不重建主服务。日志区分系统服务已注册和仍待确认的实际 EIR 输出。
- 尚未在本次最终 APK 上读取真实 HCI/EIR 或验证宝马回连；不得把代码注册成功等同于已实车修复。

## 验证

- `:app:testReleaseUnitTest :app:assembleRelease -Phypercar.buildNumber=492` 成功；100 项测试，0 失败、0 错误、0 跳过。Release R8、vital lint 均通过。
- 新增回归覆盖注册申请与就绪区分、切网后旧回调、停止后迟到成功、失败后旧成功，以及额外监听失败/停止不影响主监听。
- 正式签名验证通过，证书 CN=HyperCar；版本 `0.1.0.492 / 10492`。
- APK：`app/build/outputs/apk/release/app-release.apk`。
- APK SHA-256：`2358CC77371F47E45F2F538135B1F7039FEB7D37D28FDE6B563AA242F395D370`。
- 本轮未安装到手机、未提交、未推送。未清除应用/蓝牙/数字钥匙数据。

## 实车观察

保留已有车机配对，分别记录首次连接与二次上车。检查 BT-MARKER 注册、HCI EIR 是否包含标记、车机点击后是否出现入站 ACL/SDP、ctrl 注册的 Network 与邀请。无改善时仅关闭新增标记开关作单变量对照；不要把互删配对作为默认验收步骤。

参考：[Android NSD Network](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#setNetwork(android.net.Network))、[Android RFCOMM 服务注册](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#listenUsingRfcommWithServiceRecord(java.lang.String,%20java.util.UUID))。

## 493：业务退出、FD 所有权及装机

用户随后授权补充退出清理并编译安装。本节取代上面的 492 交付状态，前述发现/广播修改均保留。

- 主动停止调用 nativeStopGracefully，先停止媒体任务，再在仍可用的加密 RTSP 控制连接上发 `TEARDOWN /`。body 的 streams 只包含已经协商成功的屏幕（110，实际 uuid）和音频（100）。收到应答或超时后关闭控制通道，然后 Kotlin 才结束蓝牙旧会话及解绑网络。
- TEARDOWN 整段限时 900ms（包括写锁、写入和应答），旧引擎完成最多等 2s；超时强制取消并最多再等 300ms。网络丢失/EOF/重试走强制路径，不等待或编造 iAP2 退出消息；蓝牙向 Wi-Fi 交接不走整轮停止。
- 停止请求具有持久状态，早于最终 await 到达也不会丢失；完成信号由独立 guard 在正常返回、失败或取消时发出。旧引擎主动停止后的错误不再作为新失败上报。引擎的媒体/UI/统计任务按本轮所有权取消并等待，不再仅遗留 detached task。
- 移除只增不减的 Java Socket 强引用列表。SessionSocketLease 成功导出重复 FD 后也关闭原 Java Socket；Rust 独立拥有重复 FD，失败/取消时原对象同样释放。此行为依据 API29+ 文档并经过本机 HyperOS 真实回环测试，不套用旧 Android 的共享 FD 假设。

验证和交付：

- 手机 31a37894 上执行 TCP/UDP FD 回环探针：关闭原 Socket 后重复 FD 仍能收发；最终关闭重复 TCP FD 后对端收到 EOF。
- 新编译 Android arm64 Rust 测试程序，在手机执行全部 32 项通过。覆盖 TEARDOWN 请求/应答后关闭、静默接收端限时退出、强制停止不发协议消息、停止先于等待、完成与超时区分，以及既有配对/媒体/资源测试。
- JVM 100 项通过；重新构建 Rust Release 与正式 APK，Release R8/vital lint、签名检查通过。确认 APK 中 `.so` 与本次重建文件一致。
- 版本 `0.1.0.493 / 10493`；SHA-256 `fa76a084691172f31cce805c1886852b9f7901698569b6f762a3edca41b1470f`。
- 2026-11-02 21:30 通过 `adb install -r` 覆盖安装成功并启动 App，系统确认上述版本。没有清除应用/蓝牙/数字钥匙数据，未提交或推送。
- 尚未以宝马实车确认 TEARDOWN 接受和二次上车改善；Android 回环和协议模拟测试不能代替这两项。
