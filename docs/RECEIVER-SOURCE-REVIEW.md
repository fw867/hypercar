# LIVI 与 headunit-carplay 源码对照

分析时间：2026-11-02。只进行源码与文档分析，未运行两个项目，未修改 HyperCar 的连接行为。

| 项目 | 固定分析版本 | 角色 |
|---|---|---|
| [LIVI](https://github.com/f-io/LIVI) | 739c889c92929babd0cce4e697672a440291e4bf | Linux / macOS 原生 CarPlay 接收端 |
| [headunit-carplay](https://github.com/riazosama/headunit-carplay) | ec76e30a164cbfb9734be11b0383a9780500e6d2 | Autochips Android 主机已有接收栈的启用工具、显示应用及无线 iAP2 桥 |

两者都位于附件/车机一侧；HyperCar 位于手机内容源一侧。它们可以用于核对对端行为，不能把发往手机的流程直接搬进 HyperCar。

## LIVI

### 公开实现范围

除界面外，代码包含 iap2-link、iap2-csm、iap2-mfi、livi-cp 协议栈。/info 构造主屏 type 110 和可选副屏 type 111；车辆协议包含 vehicleStateProtocolInfo、服务与特征描述、OPACK 消息，以及 type 130 数据通道。另有主题、仪表内容、空调、车辆与媒体状态模块。

这些是其 Ultra 声明的源码依据，不是本文完成了真机互操作验证，也不能据此认为现有宝马固件会接受 HyperCar 主动扩展仪表或空调功能。

来源：[显示能力](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-cp/src/info.rs)、[车辆协议](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-cp/src/vehicle_state.rs)、[数据流](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-cp/src/stack/data_streams.rs)。

### 二次连接与邀请

Linux 重连任务轮询已配置目标，调用 BlueZ ConnectProfile 或 Connect。循环节拍 1 秒，失败 15 次后为对应目标延后 30 秒；目标较多时轮询会影响每个目标的实际间隔。仅在 AP 有 link-local 地址时开始未连接目标的尝试。

系统蓝牙显示 Connected、但没有 link_active 时，超过 10 秒调用设备级 Disconnect。这里 link_active 在附件 iAP2 处理任务开始/结束时设置，不是以 AirPlay RECORD 或实际画面判定。不能照搬成手机端“没有画面就拆 ACL”。

来源：[重连](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-runtime/src/reconnect.rs)、[任务标记](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-helperd/src/linux_main.rs)。

Bonjour 发现手机 _carplay-ctrl._tcp 后发 GET /ctrl-int/1/connect，使用服务端口与接收器身份。对传输失败最多尝试 7 次、间隔 1.5 秒；成功读到响应首行即结束该重试，并非所有业务失败都重试。其浏览器按手机 id 与端点去重，同端点重复发现可被忽略。因此不能假定重新公告相同服务必然导致任意车机再次邀请。

支持 IPv6 fe80 地址并补接口 scope。4301 也可以携带 link-local 地址。这说明 IPv6 是该实现主动支持的路径，不能作为禁用手机 IPv6 的依据。

来源：[Bonjour](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-runtime/src/bonjour.rs)、[iAP2 启动](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-runtime/src/bringup.rs)。

### 退出与蓝牙切换

带 streams 的 TEARDOWN 拆指定流；空/缺失流列表进入会话清理。控制处理循环退出后也调用 teardown。该实现支持“流退出后控制 EOF 仍清理”的路径，但不能代替宝马上的证据，也不证明必须额外发空列表 TEARDOWN。

disableBluetooth 命令可触发接收端断开指定手机蓝牙，让 iAP2 转入 Wi-Fi tunnel；这不同于整个投屏会话失败。requestUI 则有独立的主机界面事件。

来源：[TEARDOWN](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-cp/src/stack/teardown.rs)、[控制退出](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-cp/src/stack.rs)、[命令](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-cp/src/stack/commands.rs)、[会话管理](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-cp/src/manager.rs)。

接收端注册 caff 服务并以客户端连接手机 cafe；另公布 ec884348-cd41-40a2-9727-575d50bf1fd3。这是接收端身份，不据此给 HyperCar 增加同一身份广播。其原生接收路径需要可用的 MFi 硬件/桥接后端，不能宣称裸 PC 下载安装即可完整联调。

来源：[蓝牙](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/native/crates/livi-runtime/src/bt.rs)、[项目说明](https://github.com/f-io/LIVI/blob/739c889c92929babd0cce4e697672a440291e4bf/README.md)。

## headunit-carplay

### 架构与实际依赖

carplay-screen 的 Activity 创建 SurfaceView，调用厂商 CarplayManager.startVideo(surface)，并转发触摸。没有独立复现 AirPlay 接收核心，也不是 HyperCar 的 Shizuku 虚拟屏技术。

无线 client 实现 Bluetooth iAP2、识别、认证请求与 Wi-Fi 凭据下发；视频和 AirPlay 控制仍交给设备预装服务。authd 以 root 加载厂商 libCoreUtils 的 MFiPlatform 接口，使用设备真实芯片。它不是任意 Android 手机安装就可变成通用接收器的 APK。

来源：[显示 Activity](https://github.com/riazosama/headunit-carplay/blob/ec76e30a164cbfb9734be11b0383a9780500e6d2/carplay-screen/src/app/carplayscreen/MainActivity.java)、[认证后端](https://github.com/riazosama/headunit-carplay/blob/ec76e30a164cbfb9734be11b0383a9780500e6d2/authd/README.md)。

### 可参考的健壮性处理

BridgeService 主动连接手机 cafe，另监听 caff；SDP/连接有超时和通道回退。拨号前等待认证服务，已有链路时不并发拨号；链路关闭后清空 Socket、Session 和重组状态，再唤醒重试。下一轮重新创建链路序号和 ACK 状态。

Iap2Session 对前后阶段凭据重发分别限次。4301 需要厂商接收端真实公钥，缺失则跳过，不能用任意公钥替代。5703 的当前代码参数为 0:BSSID、1:SSID、2:密码、3:安全、4:信道；PHASE_B.md 仍含早期不同的编号和跳过认证说明，应以当前实现及实际日志为准。

来源：[连接管理](https://github.com/riazosama/headunit-carplay/blob/ec76e30a164cbfb9734be11b0383a9780500e6d2/client/src/com/cpbridge/BridgeService.java)、[iAP2 会话](https://github.com/riazosama/headunit-carplay/blob/ec76e30a164cbfb9734be11b0383a9780500e6d2/client/src/com/cpbridge/Iap2Session.java)。

作者将无线标为实验状态，声音、Siri 与电话目前通过车机蓝牙绕行；不能把它作为完整无线音频已解决的参考。它也不是此前分析的 hatak30/cp-bridge，包名含 cpbridge 不表示两者同源。

来源：[当前状态](https://github.com/riazosama/headunit-carplay/blob/ec76e30a164cbfb9734be11b0383a9780500e6d2/README.md)。

## 对 HyperCar 的使用优先级

1. 优先把 LIVI 作为接收端协议和测试用例参考：邀请、IPv6 scope、Wi-Fi iAP2、资源切换、流退出与控制 EOF 分开验证。
2. 借鉴 headunit-carplay 的依赖就绪检查、有限重发与单轮状态清理，先核对 HyperCar 已有机制，避免重复加入拨号和重试。
3. Ultra 仪表与车辆协议只保留为研究材料；当前任务仍是稳定现有接收端的首次与二次连接。

尚未从这两个项目得到宝马下午不发邀请的确定原因。本文不把第三方的主动拨号、强制拆 ACL、固定通道、厂商 root/HIDL 或接收端身份广播并入手机发送端。
