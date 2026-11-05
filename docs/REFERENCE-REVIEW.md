# 参考项目的可用结论

更新：2026-11-03。以下记录已查看的代码或资料及其角色，不把第三方实验结论直接当成本项目实车结论。

| 来源 | 已确认可参考内容 | 本项目适用边界 |
|---|---|---|
| [LIVI](https://github.com/f-io/LIVI) | 原生接收栈、主动回连、控制邀请、IPv6、多屏与车辆协议 | 接收端角色；其主动拨号和拆 ACL 策略不能直接搬到手机，详见[源码对照](RECEIVER-SOURCE-REVIEW.md) |
| [headunit-carplay](https://github.com/riazosama/headunit-carplay) | Android 车机 iAP2 桥、依赖就绪与链路清理 | 依赖 Autochips 预装接收栈、真实 MFi 与 root，无线音频仍绕行；与 hatak30/cp-bridge 是不同项目 |
| [CatPlay](https://github.com/catplay-labs/catplay) | 手机内容源实现、附加蓝牙 UUID、媒体与 TEARDOWN 的关闭顺序 | 已加入 Android 标记服务；Linux HCI、固定通道及 USB 包装器公告不能直接照搬 |
| [Cardeck 协议笔记](https://github.com/shaonialife/cardeck/blob/main/docs/PROTOCOL-NOTES.md) | iAP2 问候、EA 字段、Wi-Fi iAP2 与激活的排查记录 | 文档随实验修订，早期心跳解释不能与后续修正同时作为定论；奥迪、VW 现象不是宝马规范 |
| [Cardeck 自动连接](https://github.com/shaonialife/cardeck/blob/main/docs/AUTO-CONNECT.md) | 目标车机 ACL 唤醒、前台服务及后台启动条件 | ACL 接收器只能在链路已经发生后工作，不能主动使车机发起连接 |
| [xcertplay](https://github.com/shilapi/xcertplay) | 接收器发现控制服务、发邀请、连接手机 cafe UUID 的流程 | 是车机接收端，其热点与认证要求不能原样放入手机发送端 |
| [CP Bridge](https://github.com/hatak30/cp-bridge) | APK 中的手机发送端待机、Companion 唤醒去重、保留监听 | 0.2.7 并未完整恢复原生协议，反编译存在错误；公开适配车型不能证明 MGU-55 同样适用 |
| [无线盒子逆向](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering) | 固件拆包、硬件与诊断工具 | 常见链路为无线接收后 USB 接车，与本项目方向不同；盒子 AP 禁 IPv6 不证明手机应禁 IPv6 |
| [早期 CarPlay SDK 镜像](https://github.com/maaiika/Carplay) | 接收端服务重解析、失效会话清理、流与整会话 TEARDOWN 的区别 | 仓库自称泄露的旧附件 SDK，源码版本 280.33.8；不能当作现代宝马规范 |
| [Wi-Fi extractor](https://github.com/HaToan/carplay-wifi-extractor) | iAP2 识别与 5702/5703 凭据提取原型 | 未实现完整 AirPlay/自动重连，客户端主动关闭车机入站连接，与已知宝马入站流程冲突 |
| [carplayd](https://github.com/lvalen91/carplayd) | 每轮重新邀请、Wi-Fi 交接时保留 BT、TEARDOWN/EOF 对等清理 | 车机接收端；main 无线 Wi-Fi 交接尚未接入，legacy 无线实验与宝马复连不能等同 |

## 已用于当前实现

持久待机与单轮会话分离、目标事件过滤、唤醒去重、明确服务注册成功、固定会话网络、独立 Wi-Fi iAP2 状态及有界退出均已体现在当前代码。附加 UUID 也已注册，但收益未获实车验证。

关闭时带非空 streams 的 TEARDOWN 在所查看的旧接收端源码中只拆指定流；空 streams 才进入整会话清理。因此当前“流 TEARDOWN 后关闭控制连接”不能表述为已取得整个车机会话清理确认。

## 可复核版本

- CatPlay：fc52bac7910a07ff3b6d94712175db6013528aa9。
- Cardeck：503ed3730d5a9365272294b79306cea5fcce115b。
- xcertplay：1298fb35e29ee30e7bd849cb0e751b52aa22a13f。
- 无线盒子逆向：b437135316275a5d3c375bdd1ceac2a6bcf1315b。
- SDK 镜像：482d3d7ea289317cc827f9df29734daad49a8e17。
- extractor：42dd48aa801527256963d619579ed40f6b9fd4e2。

来源没有覆盖到的内部逻辑不作肯定描述；单次反编译或看到符号名不等于已经恢复完整重连状态机。


## carplayd：二次连接专项对照（2026-11-03）

固定源码：main `4eb254499aa00f456aea91d00755cf150c7d18a4`，legacy `e4d1f6a1dae0ee73377ae0f7c743b19a92d60151`。参考文件只读存放开发机 `D:/devtools/references/carplayd-4eb2544/` 与 `carplayd-legacy-e4d1f6a/`，没有构建、部署或运行该接收器，也没有把参考文档中的项目指令作为 HyperCar 的操作要求。

### 成熟度和角色

main README 明确以 Pi 有线车机为已验证实现；无线 RFCOMM 认证/识别已有独立实验，但 [wifi_handoff.rs](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/rust/carplayd/crates/wireless/src/wifi_handoff.rs#L39-L49) 仍将消息分发与 AP 接入标为未集成。旧无线/macOS 工作在 legacy；不能将 main 的有线重插验证当成宝马无线二次上车结论。项目运行在配件/车机接收端，HyperCar 是手机发送端，消息方向与主动连接职责相反。

### 可落到源码的线索

1. **同一地址的新连接轮次必须重新允许邀请。** main [rx_connect.rs](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/vendor/ncm_carplayd/receiver/src/bin/rx_connect.rs#L151-L192) 记录曾把 host:port 放入进程级拨过集合，导致重插后同地址不再触发。当前代码改为每目标 30 秒冷却，存在 RTSP 连接时不拨；仅成功邀请后才进入冷却。注意实际代码由 ServiceResolved 驱动，并不保证定时主动重试；不能只凭注释声称断开后一定自动再拨。
2. **legacy 有明确的重试与重新装载逻辑。** [carplay_connect_out.sh](https://github.com/lvalen91/carplayd/blob/e4d1f6a1dae0ee73377ae0f7c743b19a92d60151/src/airplay/carplay_connect_out.sh#L154-L219) 默认每 2 秒轮询，单轮上限 30 次；看到 RTSP TCP ESTABLISHED 就停止邀请并清零计数，记录消失后清掉旧轮状态。TCP ESTABLISHED 只是该脚本的近似判据，不等于完成 RECORD 或影音健康。对 HyperCar 的启发是每轮恢复 _carplay-ctrl._tcp 的可见性并接受新邀请，而不是让手机反向调用自己的 /ctrl-int/1/connect。
3. **Wi-Fi 交接中的蓝牙静默与最终退场可属正常。** legacy [iap2_link.c](https://github.com/lvalen91/carplayd/blob/e4d1f6a1dae0ee73377ae0f7c743b19a92d60151/src/iap2_bt/iap2_link.c#L490-L507) 在认证及请求 Wi-Fi 配置后容忍读超时；[握手结束段](https://github.com/lvalen91/carplayd/blob/e4d1f6a1dae0ee73377ae0f7c743b19a92d60151/src/iap2_bt/iap2_link.c#L589-L599) 不主动提前关闭 BT，等手机完成交接。它将之后 EOF 当 handover success，是该实验实现的判据，不能原样作为宝马 AirPlay 必定健康的证据。daemon 每次 accept 都创建新的 iap2_result/link 状态，不能把上一轮识别/认证完成标志复用到下一条 Socket。
4. **整会话退出与流退出不同。** main [AvSession::teardown](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/vendor/ncm_carplayd/receiver_core/crates/receiver/src/session.rs#L359-L374) 以是否包含 streams 字段区分：存在该字段是 partial（该实现甚至暂不逐流清理），没有该字段才 full reset。不能把空 streams 数组与省略字段混同。连接 EOF/异常触发 [Drop](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/vendor/ncm_carplayd/receiver_core/crates/receiver/src/session.rs#L476-L483)，同样清理活动标志、event、麦克风和 A/V sinks；因此仅流 TEARDOWN=200 不证明全会话结束，后续控制 Socket 真正关闭仍很关键。
5. **超时必须区分链路死与媒体静默。** [net.rs](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/vendor/ncm_carplayd/receiver_core/crates/receiver/src/net.rs#L30-L63) 区分 WouldBlock 与 TimedOut：控制面安静但仍有 A/V 时继续；传输保活耗尽则退出。其 30 秒 A/V 空闲阈值和 Linux keepalive 时长是该接收器策略，不是宝马规定，不应原样改 HyperCar 定时器。
6. **参考实现也有尚未解决的恢复问题。** [无线 A2 文档](https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/docs/22_WIRELESS_PHASE_A2.md) 记录 SDP 客户端读阻塞、超时设置未检查、旧 arbiter 连接 EOF 可能清除新持有者等问题。其进程/线程恢复只能作为核查线索，不能整体照搬成已验证方案。

### 对 HyperCar 当前实现的结论

- `_carplay-ctrl` 每个正常连接轮次新建 advertiser，重复邀请仍响应，等待时有限刷新；没有发现对应的进程级“相同 IP 永不再邀请”黑名单。需实车确认服务注册完成、正确 Network/端口、车机发现并再次发送 GET，而不只看本机 start 已调用。
- 已实现保持蓝牙等待凭据、独立 Wi-Fi iAP2、收到 disableBluetooth 相关交接后的状态区分。手机发送 disableBluetooth 的条件为 Wi-Fi iAP2 就绪及控制 feedback 健康；此项目没有给出应绕过这些条件的宝马证据。车机 Wi-Fi 自动重连后仍须新一轮邀请/协商，既不能把已入网直接当会话成功，也不能由此推出宝马必定无需再做蓝牙 iAP2。
- 当前主动停止为携带 streams 的 TEARDOWN 后关闭控制连接，属于“先拆流，再靠关闭退出整会话”。本项目已做有界 shutdown 和每轮资源隔离，但是否让宝马释放上一轮状态仍需真实时序佐证。可作为下一次重点 A/B 项的，是比较完整会话结束与现有关闭序列；本次不直接修改 TEARDOWN 载荷。
- 长期配对身份与临时会话必须分开。525 已修正共享 Wi-Fi 把 YX-PC 凭证状态套到 DiPlay 的问题；该仓库未提供“二次上车应清空配对/重做 pair-setup”的依据。不能为每次复连清掉正确本车凭证。

结论：有连接生命周期层面的对照价值，没有发现可直接认定为宝马缺失的“二次上车专用 iAP2 报文”。优先在 526 的宝马音频主动断开已关闭基线上观测：上一轮 stop/EOF 是否完成全部 Socket 清理；下一轮 _carplay-ctrl 注册后是否收到新邀请。未收到邀请时先排发现/激活；已经 /info、verify 成功才在 SETUP 失败时检查身份、会话残留与业务回应。只更新分析文档，不改运行流程、不编译或安装新版本。
