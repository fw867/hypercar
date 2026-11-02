# CarPlay 重连前置条件交叉核对：2026-11-02

## 范围与结论

重新读取盒子固件、既有二次上车提取记录、CatPlay、Cardeck、xcertplay、CP Bridge APK，与 11 月 2 日上午成功激活/下午点击立即失败的应用日志及手机 HCI 对照。仅分析，不移植第三方实现、不修改运行代码或手机设置。

尚未找到“补上某个必选字段即可解释全部现象”的证据。最重要的边界：广播/监听已在手机注册，与车机已刷新手机服务、选择 CarPlay 并发起 iAP2，不是同一件事。下午缺失的是后者。已连接车机 Wi-Fi、蓝牙配对成功、短暂可发现，都不能替代这一步。

源码角色必须区分：HyperCar、Cardeck、CP Bridge 扮演手机发送端；xcertplay 是车机接收端，主动拨手机；CatPlay 同时有发送端、接收端及 USB 等不同路径。不可把接收端的 MFi 芯片、热点创建、ConnectProfile 操作直接当成 HyperCar 缺失的手机前置条件。

## 样本固定

- CatPlay：本地 `D:/devtools/catplay-review`，提交 `fc52bac7910a07ff3b6d94712175db6013528aa9`。
- Cardeck：本地 `D:/软件开发/cardeck`，提交 `af98832a8bccd19008f55bef5d70dde83184851f`。本轮只读，未操作其工作区。
- xcertplay：通过 GitHub 读取公开提交 `1298fb35e29ee30e7bd849cb0e751b52aa22a13f`。
- CP Bridge：此前提取的 0.2.7 APK，便读副本 `D:/devtools/cp-bridge-review/readable`；部分方法反编译不完整，以明确调用为证据。公开 README 只确认 BMW i8 / iDrive 6，不能等同 MGU/iDrive 8.5。
- 盒子：固件 `D:/devtools/hypercar-box-firmware-20261030/rootfs/798772960/rootfs`；二次连接元数据 `iap2-flow-second-entry-20261031.json` 及此前已核对原始日志的文档。原始 085004 下载日志本轮不在原路径，不能把本轮文档/元数据复核冒称重新读到了原始 blueware 全文。

## 对照表

| 条件/行为 | 参考实现 | HyperCar 与下午证据 | 判断 |
|---|---|---|---|
| 正确的手机 iAP2 UUID | Cardeck、CP Bridge、xcertplay 使用 `00000000-deca-fade-deca-deafdecacafe` | HCI EIR 含同一 UUID；系统有监听 socket | 已具备，不是下午服务完全未注册 |
| 已配对身份与认证 | 盒子用既有 link key 恢复；xcertplay 遍历 bondedDevices | 下午手机发起的两轮 Authentication Complete 都为 success；广播使用真实本机蓝牙地址 | 没有本轮 link key 认证失败证据；仍不能证明车机 CarPlay 记录已接受当前状态 |
| 接收端选手机并主动发起 iAP2 | 盒子重连由车机连入；xcertplay 筛 PHONE_SMART 后直连手机 UUID | 上午有车机 Connection Request、SDP iAP2 查询、RFCOMM；下午全部没有 | 当前阻塞点；不能以 HFP connect 代替 |
| 可连接与可发现 | CP Bridge 首配交互开启，已配对/自动启动不强制发现；xcertplay 用已配对设备直连 | 下午 page scan 正常，两个 30s inquiry/page scan 窗口已到控制器 | 可发现不是普适的已配对回连必选条件，也不是充分条件 |
| 手机设备类别 | xcertplay 显式要求 PHONE_SMART | 当前材料没有本机 CoD 的直接读数；成功连接提供间接兼容性证据 | 可以补读，但不能宣称已发现类别错误；宝马也不一定采用同一筛选 |
| 安全 RFCOMM | Cardeck、xcertplay 使用 secure；CP Bridge 默认 insecure；CatPlay profile RequireAuthentication=true | 我们使用 secure，中午已成功；下午车机没有来打开 iAP2 | 实现差异存在，不解释当前入站 ACL 之前的拒绝，不宜直接改 insecure |
| 服务名与固定 channel | CP Bridge 名为 Wireless iAP；Cardeck 名为 iAP2；CatPlay 固定 channel 19 | 我们名为 iAP2，系统动态分配 channel；下午 28 后重建为 29 | 名称不同不是共同必选条件；端口变化是缓存兼容性风险，但下午未见车机拨旧 channel |
| CarPlay 专用 EIR 标记 | CatPlay 可选 HCI tweaks 加 `2d8d2466-e14d-451c-88bc-7301abea291a`；另有 Wireless iAP v2 marker | 我们捕获的上午/下午 EIR 均无这两个额外标记 | 明确兼容性差异，非已证实的宝马必选条件；上午无标记也成功，Cardeck/CP Bridge 可见应用层也未添加 |
| 下车保持监听 | CP Bridge pauseSession 释放会话、保留 BT listener；Cardeck 正常会话结束继续 accept | 我们手动断开可保留；部分错误/网络丢失路径仍 stop 后重建 | 有改进空间，可能造成监听空窗和 channel 变化；不是已确定的下午根因 |
| ctrl 服务与真实身份 | 盒子等 ctrl connect 才连 AirPlay；CatPlay 发送端等邀请；CP Bridge 在 Wi-Fi 就绪后启动引擎广播 | 下午已绑定车机网络并注册 `_carplay-ctrl._tcp`，TXT id 使用真实蓝牙 MAC；无邀请 | 本地注册已确认；对端实际看到的地址、端口、TXT、缓存状态尚缺同期 Wi-Fi 抓包 |
| 广播重发/接口 | CatPlay 的 USB 发送端 wrapper 显式按 iface 建 ctrl server、每秒 force_announce | 我们依赖 Android NSD；同网络注册后 ensureForNetwork 通常直接返回 | 生命周期/可观察性差异，不能说 NSD 完全不重发，也不能把 USB 路径当无线重连强制规范 |
| 热点/MFi | xcertplay 接收端先准备 MFi 与热点，再处理手机 | 我们作为手机连接车机热点、使用已有手机身份 | 角色不同，不是给手机增加 MFi 芯片或自建热点才能回连 |

## 新发现的三个重点

### 1. 可选 EIR 身份标记与 iAP2 SDP 服务不是一回事

CatPlay `bluetooth/catplay_bt/src/iap2.rs` 定义 `CARPLAY_EIR_PHONE`；`catplay_iap2_bt/src/lib.rs::do_hcihacks` 仅在 hcihacks/carplay/手机角色条件成立时添加。`bluez_manager.rs::register_iap2` 还注册 Wireless iAP v2 marker。它们不等同核心 `…cafe` RFCOMM UUID。

因此，“EIR 里有 cafe”不能升级为“与真 iPhone 的所有 CarPlay 身份标记完全相同”。但上午同样没有额外标记且可激活，不能反过来断言少它就是下午失败原因。Android 普通应用也没有与 Linux BlueZ HCI 修改完全对应的公开权限入口；不能直接复制命令或注册空服务冒充完整实现。

### 2. 监听恢复还没有完全做到持续稳定

CP Bridge `pauseSession` 明确保留 listener；Cardeck runSession finally 只关闭当前 channel。HyperCar 的 `runDisconnect` 默认 keepListening=false；若调用方没有显式传保留策略，release 会 stop Iap2Coordinator，随后自动模式再启动。上午两次错误后均出现 stop→重新注册，下午 onLost 后也出现 channel 28→29。

减少这类重建符合参考实现的生命周期，但不能承诺它自动使车机立即报错消失：下午第一次等待从 17:59:03.641 到 18:04:03.414 的监听一直存在，没有连续故障；安装新包本身也会重启进程。

### 3. 需要验证对端所见广播，不能只以注册成功作为验收

CatPlay 的 USB wrapper 可见固定接口、固定 ctrl port 60000 和周期 announce；HyperCar 使用 NSD，CarPlayCtrlAdvertiser 的 network 参数用于内部状态比较，未显式传给注册 API。进程绑定仍是现有网络归属依据，不能据此认定已发错网，也不能认定系统回调成功就已被宝马接收。

另一个诊断缺口：HyperCar 在 registerService 调用返回时就设 registered=true，而不是等 onServiceRegistered；所以 isAdvertising 目前包含“注册请求已提交”的阶段。下午有真实成功回调，不能归因于这个短时状态误差；后续仍应区分申请中、已注册和已收到车机探测。

## 哪些前置条件当前不能确认

1. 宝马设备记录是否仍认可当前手机的 CarPlay 能力、服务与 Wi-Fi/蓝牙关联；手机端无法读取车机内部拒绝理由。
2. 宝马是否看到并采用了当前 ctrl 的 DNS-SD 地址/端口/TXT，而不是旧缓存或其他接口记录；缺少同期 Wi-Fi 包。
3. 若宝马对 EIR marker/SDP 属性/服务启动先后有额外兼容策略，哪一项在此车机上有效；目前只有实现差异，没有失败/成功的单变量验证。

## 建议的修复和验证顺序

- 优先把自动待机下的协议监听与媒体会话资源分开：媒体异常收尾不必立刻撤销可用 SDP listener；蓝牙服务真正失效时再重注册。明确用户停止/关闭自动连接的语义，不能为了常驻强行保留全部服务。
- 广播按实际 Network/interface 跟踪，注册就绪以成功回调为准，旧回调不得影响新注册；等待阶段如需刷新，应保持 TCP 端口，有限重试，不能无条件不停 unregister/register。先用同期抓包核实对端所见内容。
- EIR marker 作为独立、可回退的兼容性试验，先确认 Android/HyperOS 能力和系统影响，不混进已稳定首配或默认全局蓝牙配置。
- 暂不全局启用主动 iAP2、不切 insecure、不恢复端口扫描、不绕过本轮车机邀请；这些均不能由当前参考材料证明能解决车机点击前拒绝。
- 区分三个观测结果：车机点击即报错、出现 ACL/SDP 但没 iAP2、已建立 AirPlay 后掉线。当前上午、下午属于不同阶段，应分别验收。

## 补充：Cardeck PROTOCOL-NOTES 的价值和适用边界

用户补充的 [PROTOCOL-NOTES.md](https://github.com/shaonialife/cardeck/blob/main/docs/PROTOCOL-NOTES.md) 已完整读取（本次公开文件 865 行）。它不只覆盖奥迪，还包含 §9h BMW SYN 序号问题、§9j BMW iD8.5/MGU-86 问候时序及 A/B 身份档案。

- **§9i 的广播身份回归**：同时改实例名与 srcvers 后车机不再邀请，回退后恢复。可支持“手机身份字段必须保持稳定、不能凭参考值替换”，但两变量一起改不能证明单独哪一项导致；HyperCar 当前已经使用 sender / 950.7.1 / 真实 MAC。该案例已经完成蓝牙 iAP2，不能直接等同本次下午没有入站 ACL。
- **§9j 的 BMW 时序**：AA05 后问候不能排到等待 5703 之后；EA 请求格式正确与能否依赖 EA 是两件事。HyperCar 已提前发送问候，EA 请求已有正确构造，且由档案开关控制；不能再次把它们当作当前漏做的新修复。报告自己也把 5702 参数、空闲保活列为未定项，不应升级成事实。
- **§13b 的大众原生 iPhone 对照**：手动加入 WLAN 与经过蓝牙供应链进入 CarPlay 可出现不同结果，支持“已连 Wi-Fi 不等于已激活”的诊断原则；是大众特定观测，不能推成宝马必须删除已保存网络或禁止 Wi-Fi 先连。
- **§12 的 Wi-Fi iAP2/资源重申**：可用于另行核对上午已建链后的异常，但执行阶段晚于下午点击即失败。HyperCar 已实现 Wi-Fi iAP2；不能只因相近的 8～12 秒表象复制奥迪定时重申。
- **§9b 的并发连接线程**：旧 connect 线程和新会话争用能造成难以解释的失败，适用于生命周期审查；当前下午 HCI 只有手机系统 HFP 两轮，没有 HyperCar 主动 iAP2 拨号或多 iAP2 连接的证据。

阅读时要按时间和证据权重处理修订：早期 §3 的固定 session 10/11/12，被后面真车动态表要求限定；§9e 将短时掉线归为心跳，后续 §12 又指出某些样本还有 Wi-Fi iAP2 原因；§9f 改为 catplay 广播字段，§9i 又记录实车回归并撤销。它是持续演进的实验记录，不是所有条目都同时成立的协议规范。真正有价值的是同车成功/失败对照及阶段边界。

本次没有发现可直接修复下午入口失败的新增必选消息。优先项仍是持续监听、广播/身份恢复及车机实际所见状态；对已进入会话后的异常再核验问候、Wi-Fi iAP2、资源和保活。

## 补充：主动退出是否遗漏会话清理

用户提出“上次没有正常结束导致车机状态异常”。静态复核发现两个具体缺口，尚未包含在本轮 492 APK 中：

- `nativeStop` 仅 Notify 引擎停止，不等待结束；engine 收到通知后返回。Rust 侧当前没有主动发送 RTSP TEARDOWN 的实现。CatPlay 的 teardown_task 则在释放媒体传输后，通过仍可用的控制连接发送带流信息的 TEARDOWN 并等待应答。不能把 generic RTSP 规范直接当成全部 CarPlay 关闭细节，但参考实现明确存在业务层退出。
- `HyperCarRepo.socketKeepAlive` 全局只增不减，TCP/UDP Socket 移交 FD 后一直保留且没有 close。代码注释基于“fromSocket 不复制 FD”的旧行为；[Android 文档](https://developer.android.com/reference/android/os/ParcelFileDescriptor#fromSocket(java.net.Socket)) 明确 API 29 起 fromSocket/fromDatagramSocket 持有重复 FD，原 Socket 与 PFD 都要关闭。本项目 minSdk=31，旧的永久强引用策略存在资源所有权错误。应核验并修复原 Java FD 与 Rust FD 的独立释放，不能简单从列表移除或直接把未经测试的关闭顺序带进稳定流程。

这比猜测某个 iAP2 通用退出消息更具体。现有参考材料没有证明每次关闭 RFCOMM 前必须发一个通用 iAP2 logout。4E0D Unavailable 表达功能不可用，不能默认把每次媒体停止都当成撤销 CarPlay 能力；蓝牙向 Wi-Fi 交接也不等于结束整轮 CarPlay。

主动断开且控制通道健康时，应尽力完成有超时上限的业务 TEARDOWN，再结束旧会话和本轮网络；网络已丢失/对端已关闭时跳过无法发送的消息，仍保证本地资源完成释放。保持自动模式下的待机 SDP listener，不混淆旧业务会话与下一轮发现。

缺口可能造成资源残留及下一轮状态不同，但尚无车机内部状态证据证明它是下午立即拒绝的原因。下午在 12:04 覆盖安装后是新进程，不能把上午 Java FD 原样存活到下午；车机侧是否因先前异常保留状态仍需验证。本次补充为诊断记录，未修改关闭实现。

## 原始实现链接

- [xcertplay 接收端筛选/直连手机](https://github.com/shilapi/xcertplay/blob/1298fb35e29ee30e7bd849cb0e751b52aa22a13f/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L1604)
- [xcertplay Bonjour 角色及控制邀请](https://github.com/shilapi/xcertplay/blob/1298fb35e29ee30e7bd849cb0e751b52aa22a13f/shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt)
- [CatPlay UUID/EIR 常量](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/bluetooth/catplay_bt/src/iap2.rs)
- [CatPlay HCI tweaks](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/bluetooth/catplay_iap2_bt/src/lib.rs)
- [CatPlay profile 注册](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/bluetooth/catplay_bt/src/bluez_manager.rs)
- [CatPlay USB 发送端 ctrl 生命周期](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/catplay_carplay_tx_gadget/src/client.rs)
- [CP Bridge 使用说明与兼容范围](https://github.com/hatak30/cp-bridge/blob/main/README.md)
- 本地 CP Bridge 静态证据：`readable/MainActivity.java`、`ProbeService.java`、`DebugActivity.java`；此前分析见 `CP-BRIDGE-APK-REVIEW-2026-11-01.md`。
- 盒子记录见 `BMW-RECONNECT-2026-10-31-2017.md`；本次 HCI 对照见 `BMW-RECONNECT-2026-11-02-EVENING.md`。
