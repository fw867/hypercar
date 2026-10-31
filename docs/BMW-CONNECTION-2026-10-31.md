# 宝马 0.1.0.412：短时出画面与 Wi-Fi 重连排查

## 输入及范围

分析用户提供的 `hypercar-log-20261031-105933.txt`、`hypercar-log-20261031-110044.txt`，对照当前提交 `f1c94fc` 和昨晚的盒子流程报告。两份导出有重叠；第一份还包含昨晚进程的尾部，不能将昨晚持续播放统计计入今天宝马会话。本轮仅检查日志及代码，未修改运行代码、构建或安装。

今天去重后共有 5 次 RECORD 成功。其中 3 次进入持续视频、2 次在 RECORD 后几毫秒即被关闭，不能把它们算作 5 次相同的稳定失败。

## 三轮持续视频的共同断点

| RECORD 成功 | Wi-Fi iAP2 最后业务 | 控制/事件 EOF | RECORD 到 EOF |
|---|---|---|---|
| 10:59:07.038 | 07.158，AA00，正文 4 字节 | 10:59:15.211 / .213 | 约 8.17 秒 |
| 10:59:38.577 | 38.720，AA00，正文 4 字节 | 10:59:46.685 / .687 | 约 8.11 秒 |
| 11:00:31.107 | 31.256，AA00，正文 4 字节 | 11:00:39.268 / .269 | 约 8.16 秒 |

已确认：

- 五轮 pair-verify、auth-setup、会话 SETUP、会话内 `/info` 和 RECORD 均成功。会话内 info 返回约 104 KB，显示能力为 2736×800；协商前的 455 不是这次未取到能力的证据。
- 三轮持续视频均实际写出 VideoConfig 和首个 IDR；编码约 60 fps，事件通道还能收到 HID 触摸。用户也已确认实际出画面。原先的首次适配、首帧和 RECORD 问题不应重新改动。
- 三轮 Wi-Fi iAP2 都完成 DETECT、SYN、控制会话 ID=1 协商和 1D00→1D01→1D02，随后发送 AA00；未记录 AA06、AA01、AA02、AA03 或 READY。
- 第一轮及最后一轮明确是 RFCOMM 先 EOF，约 50–80 ms 后控制/事件 EOF。应用在事件 EOF 后才清理。不能把后续 Broken pipe 认作最初故障，也不能把后来的 ACL 广播认作本轮先由应用拆掉正常会话。
- feedback 首次响应为 200，已发 UDP 首包及屏幕 stats 保活；无本地解密错误。但日志没有连续成功反馈明细，不能声称每个后续保活都已被确认。
- 未出现成功交接日志，不能将这次蓝牙断开解释为已经完成 Wi-Fi 接管后的正常退场。

最强怀疑是 **Wi-Fi iAP2 认证阶段未推进，车机在约 8 秒后结束整场会话**。这是三个重复样本的时序关联，日志没有车机内部终止原因，尚不能断言为唯一根因。

## AA00 的具体实现差异及诊断缺口

`WifiIap2Protocol.kt` 首次 AA00 带参数 0 的空 TLV（4 字节），意图请求序列号；仅收到 AA06 才补发不带参数的 AA00，证书阶段超时为 10 秒。本次车机约 8 秒已拆链，补发和阶段失败日志均未发生。

本地 Cardeck `VirtualIap2Link` 的首次 AA00 不带参数；公开 [wiomoc/iap2 authentication.py](https://github.com/wiomoc/iap2/blob/master/iap2/control_session_message/authentication.py) 也定义无参数证书请求。昨晚盒子日志只证明 AA00→AA06→AA00→AA01 的消息顺序，报告明确注明原始 dump 被截断、AA00 完整 TLV 尚未还原。因此现有“先发空 TLV”的细节不能视为已经由盒子字节级验证，也不能仅凭多 4 字节就断言报文非法。

此外，Wi-Fi iAP2 出站使用 `post_fire_and_forget`；RTSP 读循环对没有 pending waiter 的响应直接丢弃。`control POST written` 只证明本地 TCP 写入，不能证明车机回 200，更不能证明内部 AA00 被接受。现有 TX 业务日志也不包含链路实际发送/对端 ACK 的摘要，无法区分业务不响应、链路包被丢弃或 RTSP 命令被拒绝。

修复应优先核验首次 AA00 的构造、记录相应 CSeq 的非成功应答和链路序号/ACK，并在车机拆链窗口内做有界的无参数证书请求兼容回退。不得通过强制设置 READY 或直接发送 AA05 绕过尚未收到的证书/挑战交换。

## 音频另有明确失败

三轮 `changeModes` 主音频申请分别于 10:59:07.134、10:59:38.653、11:00:31.183 返回 HTTP/RTSP **422**。随后均为 `resource_granted=false`，今日 24 条音频发包统计全部为 `0/s`，因此当前日志没有证明这三轮 HyperCar 主音频流成功输出。

用户后续明确了两次实车现象：昨天下午有声音、无画面；今天有画面、无声音。今日三轮 `0/s` 与现场无声音一致。最后一轮 PCM 采集 peak/rms 非零只证明手机捕获到了音频，不等于已向车机发出。第一份文件头部昨晚的 136–137/s 属于另一轮历史会话，不能代入今天宝马实车。

当前命令字段含 resourceID=2、transferType=1、transferPriority/takeConstraint/borrowConstraint=500。这些键值在 [CatPlay change_modes.rs](https://github.com/catplay-labs/catplay/blob/master/carplay/catplay_carplay/src/modes/change_modes.rs) 均有对应定义，不能直接归咎为 500 非法。422 的确切原因（时机、初始资源状态、字段要求）仍需对照成功请求或车机日志。不要忽略 422 强行宣告资源已获准；也没有证据证明这一个 422 就必然导致整场断连。

## 第二次上车只连接 Wi-Fi 的路径

用户的方向成立：对于已配对、已接入已知车机 Wi-Fi 的重连，不应强制等待车机再次打开蓝牙 RFCOMM。当前实现的 Wi-Fi iAP2 是经 AirPlay `iAPSendMessage` 承载的虚拟链路，并非在热点上另找一个蓝牙式 iAP2 端口。顺序应为：

1. 在已知车机 Wi-Fi 上绑定正确 Network，恢复 `_carplay-ctrl._tcp` 广播/邀请接收。
2. 尝试本车记忆的 AirPlay 端点；失效时通过邀请、mDNS 等重新发现，校验身份后复用本车配对凭证。
3. 完成 pair-verify、会话 SETUP、事件通道、RECORD，处理随后到来的 Wi-Fi iAP2 DETECT/SYN/业务认证。
4. Wi-Fi 会话已成立时按它本身的控制、事件及网络健康判断存活，不能仅因没有蓝牙 ACL 就拒绝连接。

代码已有部分能力：10:59:21.774 等重连明确记录“上次成功的端点仍可连，直接使用”。所以问题不是完全没有 Wi-Fi 直连，而是 **启动/发现入口仍依赖蓝牙**：

- `startStandbyListening` 仅启动蓝牙协调器，由蓝牙端点回调触发自动连接；没有 Wi-Fi 接入触发入口，也没有待机的 ctrl 广播。
- 连接流程里，若记忆端点不适用或失效，而启动前已在车机 Wi-Fi 上，`observedSsid` 初值为空、`lastSeen` 没有新蓝牙凭据；首个 SSID 既不满足“发生变化”，也不满足“本轮凭据的热点”，发现分支可能一直不执行。
- 记忆端点直达分支也未在返回前恢复 ctrl 广播；此广播目前主要在本轮收到蓝牙凭据、完成 join 后启动。
- 10:57 的“车机没有发起 iAP2，请删除重新配对”提示缺少 Wi-Fi 复连状态判断；已有 Wi-Fi 会话入口时该提示可能误导，不应据此要求用户反复删配对。

自动模式开启时应增加受本车网络身份约束的 Wi-Fi 触发和发现；关闭时不后台自动连接，仍允许首页手动执行相同 Wi-Fi 恢复流程。网络回调及发现必须随自动模式/会话生命周期收尾，保留已校正的手动启动/停止语义。不能对任意 Wi-Fi 的开放 7000 端口直接当作本车。

## 下一轮的最小改动范围

优先处理 Wi-Fi iAP2 的 AA00 无响应及可观察性，再核对 422 音频申请，补齐已知车机 Wi-Fi 的自动/手动恢复入口。保留已经实车成功的初次蓝牙适配、pair-verify、auth-setup、SETUP/info/RECORD、视频配置/IDR 及触摸路径。仍需实车确认 AA01/AA03/READY、有效音频发包及超过约 8 秒断连窗口；不能用离线测试代替这些结论。


## 0.1.0.413：CatPlay 核验后的实际修改

本节更新前文检查阶段的结论。固定参考 CatPlay `fc52bac7910a07ff3b6d94712175db6013528aa9`，读取实际发送端及资源控制代码，不只依据协议类型定义推测行为。

### 证书请求

- `catplay_carplay_tx_gadget/src/carplay_client_session.rs` 收到 IdentificationInformation 后发送 `RequestAuthenticationCertificate::default()`。
- `core/catplay_csm/src/decoder/prim/flag.rs` 的 CsmFlag 默认 No，不编码对应参数。因此 CatPlay 首次 AA00 正文为空。
- 先前的 4 字节空 TLV 是“请求认证证书序列号”的合法 flag 编码，不能称为报文格式错误；但本次宝马没有给它响应。HyperCar 现改为直接请求证书，仍支持收到 AA06 后再发一次无参数 AA00，且只有收到证书和挑战回应才进入 READY。
- 加入有上限的链路元数据摘要（序号、确认号、会话号和长度），不记录证书/挑战/签名正文。
- Wi-Fi iAP2 出站记录 CSeq；异步响应记录前 16 次成功及所有非 200 状态。仍保持出站不等待上一条 RTSP 应答，避免握手阻塞。

### 音频方向纠正

CatPlay `carplay/catplay_carplay/src/msg/commands/command_types.rs` 明确：changeModes 为 FromAccessory，modesChanged 为 FromDevice。`carplay_tx/client.rs::assert_modes` 发送 modesChanged，`c2a/.../car_manager.rs::flush_modes` 也是先发布状态再 SETUP，而不是反向向车机发 changeModes。

因此移除 HyperCar 主动向宝马发 changeModes 的路径。开启应用音频时，以手机资源仲裁者角色初始化本地音频所有权，随初始 modesChanged 发布，再进行音频 SETUP；关闭应用音频的宝马路径保留车机音频所有权。来向 changeModes 仍可借用、占用、归还音频，实际发包继续服从所有权门控，不覆盖车机后续仲裁请求。这纠正了 408 的反向申请设计，不是忽略 422 后伪造一次资源批准。

### 已知 Wi-Fi 恢复

- 独立保存成功会话的 SSID 与车机 MAC 关联，旧 IP/端口过期不会丢掉网络归属。重置配对时一并清除关联。
- 自动模式开启时注册被动 Wi-Fi NetworkCallback；识别到匹配目标且本地持有本车凭证的已知网络后，才触发自动连接。普通 Wi-Fi、未知/歧义网络和另一台目标均不触发。
- 升级时允许以旧记忆端点的 SSID 和已配对的指定目标提供兼容线索；改变指定目标时清掉无车机归属的旧端点，避免沿用错误关联。
- 手动启动同样检查已知车机 Wi-Fi，绑定对应 Network、恢复 ctrl 广播，先试记忆端点，失效后允许邀请/mDNS/现有网段发现路径，不再要求本轮先收到蓝牙凭据。
- 引擎使用该网络关联的车机 MAC 选择本车配对凭证，避免误用已断开的旧蓝牙对端。无蓝牙 ACL 不阻断 Wi-Fi 恢复；真实网络丢失、事件/控制连接结束仍清理。
- 自动开关关闭时注销后台 Wi-Fi 重连监听，手动功能继续可用；当前手动会话所需的网络丢失监听随会话独立保留，停止后释放并解除进程网络绑定。

CatPlay 提供了证书默认编码、资源命令方向及状态发布顺序的直接依据；Android 已知 Wi-Fi 触发和生命周期管理是本项目补齐，不声称是 CatPlay 已有的宝马专用重连修复。

参考固定版本源码：

- [发送端证书请求](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/catplay_carplay_tx_gadget/src/carplay_client_session.rs)
- [CSM flag 编码](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/core/catplay_csm/src/decoder/prim/flag.rs)
- [命令方向](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/carplay/catplay_carplay/src/msg/commands/command_types.rs)
- [发送端 assert_modes](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/carplay/catplay_carplay/src/carplay_tx/client.rs)

最终验证：46 项 Release JVM 测试全部通过；最终 Rust 测试程序通过 ADB 在手机运行，22 项全部通过。Rust release 库、Release APK 和 lintVital 构建成功，已安装到 HyperCar 手机，确认版本 0.1.0.413 / 10413。

普通 Wi-Fi 下的实机检查：自动关闭时首页手动启动进入协商；手动停止后 iAP2 与会话前台服务关闭。开启自动模式时实际注册 Wi-Fi LISTEN 网络回调、保留待机前台服务，普通 Wi-Fi 没有触发 AirPlay；关闭后回调注销且会话前台服务退出，应用进程保持。最终恢复测试前的自动关闭状态。自动开启后手动停止保留后台能力仍有既有决策测试覆盖，本轮未取得额外的完整 UI 实测记录。

本次不在宝马网络，Wi-Fi 断开收尾、Wi-Fi 单独复连、证书交换是否继续到 READY、实际音频输出及是否消除约 8 秒断连，仍需实车验证。


## 0.1.0.414：两次实车现象与业务应答复核

### 证据边界与对比

用户明确的现象应分别记录为 **昨天下午有声无画、今天有画无声**。昨日的原始导出当前不在下载目录；本轮逐行复核的是今日两份原始文件，昨日遗漏命令按已有晚间排查报告回查，不能把其中 20:15 的记录冒称为下午现场的原始时间戳。

| 项目 | 昨日既有排查记录 / 用户现场确认 | 今日 0.412 原始日志 | 当前修正 |
|---|---|---|---|
| 视频 | 用户确认无画面；旧实现存在首配置/IDR 门控及资源事件缺口 | 配置/IDR 实际写出，有 HID 操作，用户确认有画面 | 保留已经成功的首帧、SETUP、RECORD、视频传输 |
| 音频 | 用户确认有声，旧会话不以反向 changeModes 为音频前提 | 三次 422 后 resource_granted=false，24 条统计全部 0/s | 413 去掉反向申请；414 补齐初始状态与动态归还恢复 |
| Wi-Fi iAP2 | 既有报告记录 iAPSendMessage 未处理 | 已回应 DETECT/SYN/1D01，停于出站 AA00 后 | 413 改直接证书请求；414 补早到订阅队列和诊断 |
| 资源业务 | 既有报告记录三次 changeModes、一条 requestUI 未处理 | 今日日志没有这两类入站命令 | 保留已接入处理，补初始状态、约束、异步反馈及应答顺序 |

### 今日逐项应答清单

| 收到的内容 | 核验结果 | 结论 |
|---|---|---|
| RTSP forceKeyFrame | 记录了实际 requestSyncFrame，随后有配置/IDR 写出 | 已处理 |
| RTSP hidSendReport | 解析并注入车机触摸，包含 down/move/up | 已处理 |
| Wi-Fi DETECT/SYN | 回复 DETECT 和 SYN/ACK，随后车机发送 1D01 | 已处理，控制会话 ID=1 已通 |
| Wi-Fi 1D01 | 回复 1D02，发出 AA00 | 已处理识别；后续是车机未回 AA06/AA01，并非手机漏回收到的证书 |
| 蓝牙 AE00 | 两份合并去重共 4 次；每次后续都有 3 条 AE01，共 12 条 | “暂存待后续消费”不是丢弃；现有蓝牙实现保留 |
| 蓝牙 AE02 | StopPowerUpdates；无独立 CSM 业务响应要求，链路 ACK 已存在 | 不补造无定义的回复 |
| EA03 / 6800 未到 | 它们是期望从车机收到的可选消息；今日仍能进入视频会话 | 不把车机没发送的消息算作 HyperCar 漏答，不改初次适配 |
| /feedback | 首次 200；没有连续响应明细 | 不能声称全部后续保活均已由车机确认；也没有本轮 500 的证据 |

今天三轮 Wi-Fi iAP2 都停在证书请求之前的响应等待，故当前日志无法验证 AA01 后的挑战、READY、订阅与蓝牙交接是否实车通过。离线补齐路径不等于已经验证根因消失。

### 本轮进一步修正

1. **采用 `/info.modes`**。CatPlay `ModesArbiter::new` 从接收端初始模式构造资源管理器。HyperCar 之前读取显示能力却忽略该字段。现在解析 initialPermanentEntity、初始 Take/Borrow 及应用状态，保留接收端约束，再按本地屏幕/已启用音频的需求作允许的本地资源决定。缺失/非法时明确记录兼容回退。屏幕门控的初值也由解析出的所有权决定。
2. **完整检查本轮使用的资源参数**。Take/Borrow 的优先级和 take/borrow/unborrow 约束按 CatPlay 的值域处理，支持嵌套借用、禁止打断的借用及释放字段校验；非法批次不部分修改状态。拒绝时返回 status=1，不附带成功快照。requestUI 可以在约束允许时重新取得车机持有的屏幕，而不再只处理未分配屏幕。
3. **释放后恢复本地播放**。车机 Untake 后先用业务响应确认资源已释放，再通过 modesChanged 发布允许的本地重新取得，避免音频长久停在 unclaimed 而持续 0/s。关闭应用音频时不会自动取得该音频资源。
4. **状态反馈不阻塞事件**。changeModes/requestUI 的 modesChanged 进入独立任务，等待车机应答时不堵住 Wi-Fi iAP2、触摸和关键帧事件；积压只保留最新快照，与 CatPlay 的 dirty-state/tx_queue 思路一致。changeModes 的业务应答先实际写入，再允许派发后续状态反馈；HID/iAP 的立即投递路径保留。
5. **补回早到订阅**。Wi-Fi iAP2 的 AE00、4154、4157、4170、AD00、5000 若在认证完成前到达，按消息 ID 有界合并暂存，认证后补回；AE02 会撤销暂存的电源订阅。不会因早到请求跳过认证，也不会用假 READY 绕过证书交换。
6. **可核对的回应日志**。记录资源请求的 ID、操作、优先级和约束；记录业务响应实际写入及失败；修正未处理 Wi-Fi 消息只记 RX、却漏掉 ACK-only 摘要的日志问题。日志不含证书、签名、挑战或密码正文。

参考固定 CatPlay 源码：

- [初始模式与本地资源决定](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/c2a/catplay_c2a/src/proxy_rpc/modes_arbiter.rs)
- [资源约束与业务响应](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/carplay/catplay_carplay/src/modes/resource_manager.rs)
- [事件处理与异步状态发布](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/c2a/catplay_c2a/src/proxy_rpc/car_manager.rs)

### 验证与剩余范围

47 项 Release JVM 测试通过，新增认证前订阅延迟回应、撤销及 ACK-only 摘要覆盖。手机运行最终 Rust 测试程序，26 项全部通过，包括初始约束、嵌套借还、不可打断资源、拒绝原子性、音频释放恢复，以及“故意不给第一条 modesChanged 应答时，iAP 输出仍能到达且只补发最新状态”的 TCP 模拟验证。完整 Rust release、签名 APK 与 lintVital 构建通过。

该实现仍不是整个 CatPlay 的完整移植：动态借屏时当前保留已有屏幕连接、暂停帧并维持保活，不是 CatPlay 的完整按资源重建/TEARDOWN 流管理；Siri、limitedUI 也没有完整手机业务接线。这些命令未在今日日志出现，不据此宣称为本次约 8 秒断连根因。蓝牙初配、配对身份、auth-setup、工作中的视频线格式和音频 PCM/RTP 格式未因本轮重写。

实车仍需分别观察：Wi-Fi AA01/AA03/READY、音频 SETUP/dataPort 与实际 136–137 包/秒、视频持续输出，以及是否超过原约 8 秒断连窗口。

最终签名包已覆盖安装到 HyperCar 手机，版本 0.1.0.414 / 10414；启动正常，自动模式保持原先关闭状态，未启动会话前台服务。未在宝马热点进行本轮实车复测。


## 当前 HyperCar 与 CatPlay 的 iAP2 流程对照（0.1.0.415）

### 对照版本与结论边界

本节按当前工作区源码复核，基线为 HyperCar `1ebe00c`（0.1.0.415）；415 的地图自动巡航调整没有改变 iAP2。CatPlay 固定为 `fc52bac7910a07ff3b6d94712175db6013528aa9`，避免用后续 master 的行为解释当前差异。用户所称 ipa2 在本节统一写为 iAP2。

**两者的识别和证书交换主干相似，但并非完整流程对齐。** HyperCar 已有更多宝马无线引导、已知 Wi-Fi 恢复及 Android 生命周期处理；CatPlay 通用链路实现的乱序、恢复与多会话能力更广。CatPlay 手机模拟端仍有无线会话 TODO，不能作为完整宝马连接成功方案。今天的旧版日志只验证到 Wi-Fi AA00，后面的代码差异应当作为待验证项，不能全部归为约 8 秒断连原因。

必须分开使用 CatPlay 的四类参考：

| CatPlay 部分 | 实际角色 | 本次可以参考什么 |
|---|---|---|
| `core/catplay_iap2_link`、`async/catplay_iap2_client` | 通用链路、CSM 与异步传输框架 | DETECT/SYN、序号、窗口、重传、业务分发；不是完整手机业务 |
| `catplay_carplay_tx_gadget/.../carplay_client_session.rs` | 手机模拟端；gadget 入口走 USB | 1D00/AA00 默认编码、认证后通知及订阅回应；不能据此推定宝马无线复连已实现 |
| `carplay_rx/sink/receiver.rs` | AirPlay 接收端的可选 Wi-Fi iAP2 隧道 | `iAPSendMessage` 与独立异步链路的组合；收发角色与 HyperCar 手机端不同 |
| `c2a/.../rx_bridge.rs` | c2a 接收桥 | 此版本 `open_iap2()` 直接返回 `None`，不能把它当作正在运行 Wi-Fi iAP2 的成功范例 |

此外，`tokio/session_helper.rs::handle_auth` 是配件侧应答助手：有 MFi 芯片时读取证书、签署挑战，无芯片时还允许 mock 数据。它不是手机侧验证证书/签名的实现。此前对比的实体盒子固件也是另一份证据，不能把盒子抓包与 CatPlay 的所有代码行为画等号。

### 两条 HyperCar 链路的实际顺序

蓝牙入口（`Iap2Bluetooth.kt`、`Iap2SenderSession.kt`）：

1. 宝马档案等待车机建立 RFCOMM；接入后手机链路引擎主动开始 DETECT/SYN。**被动接蓝牙 socket 不等于被动进行 iAP2 握手。**
2. 链路 NORMAL → 发 1D00 → 收 1D01 并解析身份/能力 → 发 1D02。
3. 发无参数 AA00 → 收 AA01 → AA02 挑战候选默认 32/20/16 字节，依据车机记忆调整顺序与等待时间 → 收 AA03 后发 AA05；另保留“链路仍活着、挑战全部静默后发 AA05”的历史兼容分支。断链或取消不走该分支。
4. 立即发设备问候：4E0D → 4E0C → 4E0A → 4E0B → 4E09；再按档案/能力执行可选 EA、HID。
5. 按协议与档案尝试 5702/5703、4300/4301、4E0E，取得或更新热点和 AirPlay 端点；允许凭据、端点及重新识别晚到，不因第一轮没有拿到它们就关闭蓝牙。
6. 保持消息循环，处理晚到 4301、5703、EA03、1D01 和电源请求；AirPlay 连接与它并行运行。

Wi-Fi 入口（`WifiIap2Protocol.kt`、`WifiIap2Bridge.kt`、`rust-core/src/wifi_iap2.rs`）：

1. AirPlay SETUP、事件通道建立后启动本代 Wi-Fi 桥；收到 `iAPSendMessage` 字节才被动回应 DETECT、等待车机 SYN。它是在已有 AirPlay 通道内建立另一条 iAP2 链路，不是在热点上直接搜索独立 iAP2 服务端口。
2. 链路 NORMAL → 1D00 → 1D01 → 1D02 → **无参数 AA00**；收到 AA06 可再补一次无参数 AA00。
3. AA01 的参数 0 非空 → 发随机 **32 字节** AA02 → AA03 的参数 0 非空 → 发 AA05 → 本地标记 READY。无 20/16 字节回退，无认证超时放行；拒绝或超时结束本条虚拟链路。链路/识别各有 15 秒阶段预算，证书/挑战各 10 秒。
4. 发 4E0C → A100 → 4E0A → 4E09 → 4E0B，再补发认证前暂存的支持订阅。这里没有重新索取热点，也没有执行蓝牙那套 EA/HID 引导。
5. 屏幕流可先于 iAP2 READY 工作。宝马路径在屏幕流启动后，等待本代 READY 且最近 5 秒内有成功 feedback，才请求车机 `disableBluetooth`；约 15 秒未满足条件便不请求退场。该命令不是关闭手机整个蓝牙适配器。

AirPlay 的 pair-verify、`auth-setup` 与这里 AA00～AA05 的交换属于不同步骤。前者和 `/info`、RECORD 成功，不能证明后者已完成；出现画面也不能证明 Wi-Fi iAP2 READY。

### 逐阶段差异及影响

| 阶段 | 当前 HyperCar | 固定版本 CatPlay | 判断与处置 |
|---|---|---|---|
| 链路同步参数 | 使用简化引擎；采纳对端会话表并覆盖声明窗口，蓝牙 127、Wi-Fi 32；Wi-Fi 被动握手保留宝马观测到的参数行为 | 通用库有分别配置的 Bluetooth/USB/AirPlay LSP 预设；例如蓝牙窗口 5、最大包 2048、重传 1500 ms，AirPlay 窗口 5、最大包 65535、重传 2000 ms | 预设不同不等于必须复制。宝马已实际进入 session=1 的识别阶段，应保留已工作的同步路径，再对原始 SYN 核验 |
| 发送窗口 | `drainSendWindow` 按本方 `declaredMaxOutgoing` 限制未确认包数 | `is_window_writable` 使用 `peer_lsp.max_outgoing`；其字段说明也按接收方声明约束对方发送来解释 | **存在实质语义冲突。** HyperCar 注释与 CatPlay 相反，不能只凭本方注释认定正确；应比较双方 SYN、连续发送量与 ACK 后再修正，不能直接把 127 改为 5 |
| 序号与恢复 | 在容许范围内接受前进序号并推进水位；重复包 ACK；没有对应的乱序缓存/EAK 恢复；只向上分发控制会话 | 有乱序缓存、EAK、Recovery 状态及多会话机制，仍存在 TODO，不能称为完整规范实现 | HyperCar 针对可靠字节流做了兼容简化，但对端逻辑缺帧仍可能暴露差异；没有日志证明它导致本次 AA00 静默 |
| 重传与 ACK | 本地重传默认 4 秒/4 次；对端声明 maxRetransmissions=0 时不追踪重传；指定消息还能 fire-and-forget；Wi-Fi 关闭空闲裸 ACK 定时器 | 通用链路有独立 ACK/重传/恢复调度，依据配置和协商处理 | “可靠传输”不代表 HyperCar 所有情况下都禁止重传；需区分对端零重传参数与本地消息豁免 |
| 控制消息分片 | 蓝牙 `sendOrThrow` 按对端最大控制载荷切分；Wi-Fi 直接交引擎，过大的单条 CSM 不能靠同一路径自动分片 | 框架含 CSM、文件与会话分层支持 | 当前短握手消息未显示触及限制；未来扩展长元数据时须单独补齐 Wi-Fi 出站分片，不能误称蓝牙也完全没有分片 |
| 识别 | 两条链路均 1D00→1D01→1D02；蓝牙解析能力并支持晚到重新识别；Wi-Fi 未用能力表过滤问候 | 手机模拟端保存 IdentificationInformation，并用 `wants_rx()` 决定部分问候是否发送 | 相同主干，能力使用不同。不能因模拟端按清单过滤就删掉宝马档案明确要求的探测 |
| 首次证书请求 | 两条链路现均为无参数 AA00；Wi-Fi 还支持 AA06 后补发 | 模拟端 `RequestAuthenticationCertificate::default()` 不编码序列号请求 flag | 当前已对齐；今天 0.412 的 4 字节 flag 请求属于修正前行为，不能再当作当前实现 |
| 挑战与校验 | 蓝牙随机 32/20/16 字节加车机记忆；Wi-Fi 随机 32 字节；均未做证书链及签名密码学验证 | 模拟端固定 `0x42 × 20` 字节挑战，收到 AuthenticationResponse 后即发成功；未实现完整验证 | CatPlay 也是模拟流程。Wi-Fi 与它的 32/20 字节差异值得后续验证，但今天尚未收到 AA01、尚未发 AA02，**不能解释今日已经发生的断点** |
| 认证超时 | 蓝牙保留静默后 AA05 兼容；Wi-Fi 必须收到非空证书和回应，超时失败 | 模拟端在收到 AuthenticationResponse 后发送 AA05，没有对应的多长度静默放行分支 | 蓝牙与 Wi-Fi 的成功语义不同，是现有技术债；本次仅记录，不动已工作的初配 |
| 认证后问候 | 蓝牙发无线可用性、真实 MAC 派生 UUID、语言、时间、设备名；Wi-Fi 用 deviceId 派生 UUID，另发 A100，名称固定 iPhone，不发蓝牙那组全部通知 | 模拟端按能力发 UUID/语言/设备名/传输标识，包含测试固定值；时间和 WirelessCarPlayUpdate 被注释 | 三套顺序与字段均不相同。应核验蓝牙与 Wi-Fi 的 UUID/传输身份关联，不能复制模拟端的固定 UUID/MAC，也不能推定每条通知都必须跨传输重复 |
| 热点与启动会话 | 有 5702/5703、4300/4301、档案探测、晚到端点及已知车机 Wi-Fi 恢复 | 模拟端对热点信息主要记日志，无线启动相关代码仍有 TODO/注释 | HyperCar 已做得更多；CatPlay 不能直接回答宝马二次上车如何完整重连 |
| 蓝牙交接 | READY + feedback 新鲜度门控；本地还有已知 Wi-Fi 会话的独立存活策略 | 上述框架/模拟端没有可直接对应的 Android 宝马交接状态机 | 此处依据实际宝马与盒子行为验证，不声称 CatPlay 已提供同款修复 |

### 订阅回应并非完全相同

| 消息/业务 | 当前 HyperCar | CatPlay 手机模拟端 | 本次证据与限制 |
|---|---|---|---|
| AE00 电源 | 蓝牙每次回复 3 条原始抓包 AE01；Wi-Fi 回复 3 条结构化 AE01（参数 4、5、6） | 回复 5 条独立参数，含最大电流、充电意愿、外接电源、充电状态及电量 | **相同条数不代表相同内容：蓝牙原始字节与 Wi-Fi 参数编码也不一致。** 蓝牙字节的现有注释还保留语义未定说明，需按 TLV 和成功抓包重验；不能拿 CatPlay 的测试电量代替手机真实状态 |
| AE02 停止电源 | Wi-Fi 撤销早到的 AE00；没有独立 CSM 回包，链路 ACK 正常 | 不能仅因缺少业务回包判失败 | 今日蓝牙 AE00 四次均得到后续三条 AE01；没有证据要求先改这条已应答路径 |
| 4154/4157/4170 | Wi-Fi 在 READY 后回空通话、通信状态及空列表；认证前按 ID 合并暂存；蓝牙保活循环没有对应的完整业务回应 | 有类型化通话/通信/列表回应，部分为模拟运营商等测试状态 | Wi-Fi 比原先 ACK-only 完整，但未对接真实手机电话业务；蓝牙与 Wi-Fi 覆盖仍不统一 |
| 5000 NowPlaying | Wi-Fi 回结构化空媒体/停止状态；蓝牙持续循环主要记录这类消息 | `send_now_playing` 构造更广的媒体状态并使用文件传输接口 | 都不能据此宣称接好了 Android 真实音乐元数据、控制及封面；这与投屏音频 RTP 是否发出是两件事 |
| AD00 | Wi-Fi 按既有盒子证据回 AD01 参数 0=1 | 本次没有把模拟端当作该回复的直接依据 | 明确保留证据来源，不能称为所有回应都从 CatPlay 移植 |
| HID/位置/车辆等 | 蓝牙在可选 HID 阶段处理 6800→6801；Wi-Fi 未实现完整 CSM 业务；未支持内容主要链路 ACK | 模拟端认证后发 StartLocationInformation 紧接 StopLocationInformation，另有通用类型/会话能力 | 不等于必须照抄一次开关位置订阅。今日 AirPlay hidSendReport 已正常，不能与 CSM HID 支持混为一谈 |

消息分发还有一个阶段差异：蓝牙 `expectAnyOrNull` 会暂存非目标消息，但 `runMfiChallenge` 对 AA03/AA04 之外的消息仅记日志，没有统一重新入队。如果 AE00 等恰在这个挑战窗口到达，存在只记不答的可能；进入保活后才到达的 AE00 则会回应。Wi-Fi 对上述六种支持订阅已做认证前暂存。**这是代码发现的潜在时序缺口，不是今天四次 AE00 漏答的证据**，那四次已有明确回复记录。后续应优先补阶段交错用例，再决定统一分发方式。

### READY 与“确实交接成功”的证据差距

当前 Wi-Fi READY 是本地业务状态：收到非空 AA03，生成 AA05 和问候后即可置位。桥接线程先 `nativeMarkWifiIap2Ready`，再把本轮输出提交给 native 队列；随后才由异步任务写 RTSP。即使之后失败会清 READY，仍存在本地状态领先于实际写出、车机 RTSP 应答及链路 ACK 的窗口。

因此必须区分：生成 AA05 → 入队/写出 → 车机对应 CSeq 返回 200 → 链路 ACK → 后续业务/持续存活。前面的任一步都不能单独证明后面的步骤，RTSP 200 也不等于密码学认证通过。现有 READY + feedback 门控比只看“出画面”更严格，但仍不是对 AA05/问候交付的完整确认。CatPlay 的异步框架可以参考职责拆分，不能替代本项目补上这一证据链。

### 对当前故障的优先级判断

1. **P0：实车验证已做的最小修正。** 首先看 413～415 是否以无参数 AA00 收到 AA01，再看 AA02/AA03/AA05、业务回应、交接及持续播放。今天三轮 0.412 都未走到挑战，不能继续把认证后的每个差异当作同等可能的根因。没有新日志前，不声称约 8 秒断连已解决。
2. **P1：核对链路窗口与交付证据。** 对双方 SYN、未确认包数、序号/ACK、CSeq 逐项关联；优先确认窗口语义冲突、本地 READY 领先于实际发送的问题。保留宝马已经跑通的参数路径，不在没有抓包依据时整体替换引擎。
3. **P1：统一两条链路的阶段语义。** 核验 Wi-Fi 32 字节与模拟端 20 字节的兼容性、跨传输身份、设备问候的能力约束、电源 TLV，以及挑战期间旁路消息的暂存。蓝牙静默放行只能称兼容分支，不能称标准认证成功；任何调整都应有新的成功/失败样本支撑。
4. **P2：按实际请求补业务。** 真实电源状态、音乐/封面、电话、位置、车辆及文件会话尚非完整移植；有请求且能确认语义再接线。没有出现在本次断点之前的业务，不作为先改动稳定初配的理由。

今日“有画无声”仍应沿已记录的反向 changeModes/422、资源所有权与音频 SETUP/实际发包排查；不能用 iAP2 握手对照替代音频证据，也不能把“无声音”与“随后断连”未经证明合并为同一个原因。

### 源码定位与复核方式

HyperCar 本地定位：

- `app/src/main/java/io/github/hypercar/iap2/Iap2Bluetooth.kt`：RFCOMM 与链路启动。
- `app/src/main/java/io/github/hypercar/iap2/Iap2LinkEngine.kt`：`ownSyncOf`、`drainSendWindow`、`handleData` 的接收处理、重传及控制会话分发。
- `app/src/main/java/io/github/hypercar/iap2/Iap2SenderSession.kt`：`run`、`runMfiChallenge`、`sendDeviceStateGreetings`、`pumpIncomingForever`、`sendOrThrow`。
- `app/src/main/java/io/github/hypercar/iap2/Iap2Messages.kt::powerUpdates`：蓝牙原始电源字节；`WifiIap2Protocol.kt::handle/greetings`：Wi-Fi 状态机与结构化回应。
- `app/src/main/java/io/github/hypercar/iap2/WifiIap2Bridge.kt`、`rust-core/src/wifi_iap2.rs`：队列、代际隔离、READY 和 feedback 状态。
- `rust-core/src/airplay.rs`、`app/src/main/java/io/github/hypercar/session/HyperCarRepo.kt`：AirPlay 内的 iAP 隧道、交接与已知 Wi-Fi 恢复。

CatPlay 固定版本原始依据：

- [手机模拟端的识别、认证与订阅](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/catplay_carplay_tx_gadget/src/carplay_client_session.rs)
- [通用链路窗口、序号、ACK 与恢复](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/core/catplay_iap2_link/src/link_layer.rs)
- [LSP 字段语义、会话表与传输预设](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/core/catplay_iap2_link/src/payload/lsp_payload.rs)
- [异步链路与 CSM 分发](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/async/catplay_iap2_client/src/tokio/async_client.rs)
- [配件侧 MFi 与 mock 应答助手](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/async/catplay_iap2_client/src/tokio/session_helper.rs)
- [接收端可选 Wi-Fi iAP2 隧道](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/carplay/catplay_carplay/src/carplay_rx/sink/receiver.rs)
- [c2a 的 open_iap2 返回 None](https://github.com/catplay-labs/catplay/blob/fc52bac7910a07ff3b6d94712175db6013528aa9/c2a/catplay_c2a/src/proxy_rpc/rx_bridge.rs)

本节仅做当前源码与既有日志的对照分析，未修改运行代码、构建或安装，也没有新增实车认证成功的结论。


## 18:04～18:06 新实车日志：415 仍未完成 Wi-Fi iAP2

输入：`hypercar-log-20261031-180411.txt`（28 行，仅待机/音乐历史）、`180541`、`180650`。
后两份有重叠，按完整时间戳和正文去重；不能把同一轮重复计数。当前依据为今天 main 的
14751b1、1ebe00c、da4ef2b，保留其本地资源仲裁、已知 Wi-Fi 恢复和取消自动巡航的行为。

### 会话与断开时序

| 控制连接 | RECORD 发出 | 控制 EOF | 时长 | Wi-Fi iAP2 |
|---|---|---|---|---|
| RTSP#3 | 18:05:12.381 | 18:05:20.521 | 8.140 s | #2，最后 AA00，无后续 RX |
| RTSP#5 | 18:05:27.478 | 18:05:27.489 | 0.011 s | 快速复连，尚未建立完整业务 |
| RTSP#7 | 18:05:47.204 | 18:05:55.341 | 8.137 s | #4，最后 AA00，无后续 RX |
| RTSP#9 | 18:05:57.721 | 18:05:57.730 | 0.009 s | 快速复连，尚未建立完整业务 |
| RTSP#11 | 18:06:11.341 | 18:06:19.477 | 8.136 s | #6，最后 AA00，无后续 RX |
| RTSP#13 | 18:06:23.410 | 18:06:46.003 | 22.593 s | 无 DETECT/SYN/认证进展，15 s 交接等待失败 |
| RTSP#15 | 18:06:47.273 | 18:06:47.282 | 0.009 s | 快速复连，尚未建立完整业务 |

- 415 的音频 SETUP 已成功，车机 dataPort=6000；有持续实际 PCM 发包。先前反向 changeModes/422 导致 0/s 的问题不能继续当作本批无声音根因。
- 主视频会话有 VideoConfig/IDR 与实际发送；#13 初始屏幕资源归车机，前 15 秒只保活，requestUI 后才出帧，不能把 frames=0 的整个区间直接判为编码器故障。
- 三轮均在最后 AA00 后失去业务进展，约 8 秒后车机结束会话。RTSP/事件 EOF 后才触发 App 清理，Broken pipe 是后果。
- 新的已知 Wi-Fi 恢复入口多次触发，能复用 Network、ctrl 广播和本车身份并走到 pair-verify/auth-setup/RECORD；这证明入口有效，**不证明 Wi-Fi iAP2 认证成功**。本批没有 AA01/AA03/READY，也没有成功蓝牙退场。
- #13 与毫秒级失败的复连不能混入三轮相同约 8 秒样本。尚无车机内部断开理由，不能宣称已经唯一确定看门狗的判断依据。

### 逐帧交付差异与 416 修正

以 #2 为例：1D01 于 18:05:12.722 收到，12.724 产生三个完整链路包：裸 ACK（seq=101，9B）、1D02（seq=102，16B）、无参数 AA00（seq=103，16B）。
Bridge 把 `takeOutput()` 的整个 41B 数组作为一个 `iAPSendMessage`；只有一次 CSeq=16 写出，12.732 收到 HTTP/RTSP 200、42B 空字典。
随后没有 RX 链路 ACK 确认 seq=102/103，也没有证书消息。

已保存的盒子 090040 摘要显示：39.409 的 1D02 和 39.410 的 AA00 分别是独立 send(wlan)，随后收到 AA06、AA01、AA03；不是将裸 ACK 和两条业务合并成一条命令。
**合并交付是代码与成功盒子的明确差异，但当前日志没有接收端内部解析结果，因此“车机只处理第一帧”仍属待实车验证的解释。**

416 将 Wi-Fi 出站按完整 FF55 DETECT/FF5A 链路包拆分，每条 iAPSendMessage 只带一帧；保留原始字节、顺序和序号，不修改蓝牙协议引擎、不再改 AA00 正文、不伪造 READY。
链路长度异常不发送半帧；队列失败时不报告就绪。READY 通知推迟到本批帧全部成功入发送队列之后，但入队仍不能冒充车机认证接受。
新增 `split output`、CSeq/frameBytes 与 CERTIFICATE/CHALLENGE 每 2 秒等待摘要。复测要检查证书/挑战/READY 及超过原来 8 秒窗口，而非只看 RTSP 200。

### 音乐背景图

旧 MusicBridge 只读取 MediaMetadata 的 ALBUM_ART/ART/DISPLAY_ICON Bitmap，没有 URI 或通知大图回退。
本批日志没有封面来源字段，尚不能断言汽水音乐究竟缺少哪个 Bitmap，也不是宝马要求额外发送封面才能显示本机自绘卡片。
416 增加 ALBUM_ART_URI/ART_URI/DISPLAY_ICON_URI/description.iconUri 异步加载，并在不可用时从当前播放器媒体通知大图读取。
按包名和曲目元数据隔离缓存与异步结果；检查通知曲名，避免上一首通知封面串到新曲目。读取有连接/读取超时、4MB 和 1024 像素解码边界，不阻塞 UI 或握手。
新增 `MUSIC-ART` 来源和位图尺寸日志，不输出 URI 或曲目信息。卡片已有的图片绘制和媒体控制保持原路径。

验证：新增独立帧拆分、顺序/长度/截断拒绝，以及完整 Wi-Fi 模拟握手经逐帧命令的回归；51 项 JVM、27 项手机端 Rust 测试通过。
实际宝马认证持续成功和通知/URI 封面来源仍需新版本实车日志确认。没有删除配对、重置车机凭证、推送或发布。


## 0.1.0.417：借鉴 CatPlay 的 Wi-Fi 窗口与交付语义

固定对照仍为 `fc52bac7910a07ff3b6d94712175db6013528aa9`，本轮直接核对：

- `core/catplay_iap2_link/src/payload/lsp_payload.rs` 中 max_outgoing 的说明：双方可分别声明，发送方不得超过接收方提议的未确认窗口。
- `core/catplay_iap2_link/src/link_layer.rs::is_window_writable` 使用 peer_lsp.max_outgoing。CatPlay own_seq 指向下一发送序号，而 HyperCar sentSequence 是最后已发序号，不能不加转换地照抄边界比较符号。
- CatPlay 的 Link/CSM 状态和实际输出分层，与 HyperCar “业务进入 READY 即通知 JNI”存在语义差异。

### 已落实

1. **只修 Wi-Fi 出站窗口**：reliableTransport 路径按对端 maxOutgoing 限流，本方声明窗口 32 继续表达本端接收能力；蓝牙保留原有实车兼容策略。没有机械把本方窗口改成 CatPlay 默认 5。
2. **不重传不是已确认**：对端 maxRetransmissions=0 时不重传，但 Wi-Fi 不再以本地发送序号伪造对端 ACK；窗口满后须真实 ACK 推进才发下一包。未修改宝马的零超时/零重传协商值。
3. **区分排队与写出**：Wi-Fi 业务日志改为 QUEUED，包含 pending/outstanding；链路帧 TX 和 Rust CSeq/frameBytes 是独立证据。SYN 摘要增加 peerWindow/localWindow/maxLength/maxRetries，便于下一次实车确认窗口。
4. **READY 不越过未发数据**：认证完成后若问候仍在链路窗口积压，不报告传输 READY；待全部进入 Rust FIFO 后插入屏障，之前各帧本地写出成功才启用交接就绪位。
   token + generation 防止关闭/失败或旧 worker 的屏障重新激活新会话；写失败不会越过屏障。仍不宣称 RTSP 写出等于车机接受认证，更不宣称证书链已验证。

### 暂不照搬

- 蓝牙初配的 SYN、窗口及保活：已有宝马成功路径，不能为统一代码而改掉。
- CatPlay 手机模拟端固定 20 字节挑战、固定 UUID/MAC 和模拟电量：不是宝马成功样本，也不能解释尚未收到证书的断点。
- 重传、乱序/EAK 的完整实现：当前无实车证据证明本轮需要整套替换；先观察逐帧交付和真实 ACK。
- 热点发现/第二次上车及蓝牙退场：继续以实际宝马/盒子为依据，CatPlay 对此没有完整的 Android 同款状态机。
- 认证后长 CSM 的链路分片：当前短握手消息未超过协商上限；保留后续扩展项，不把它当成当前 AA00 静默根因。

核验结果：两条 HyperCar 问候的设备 UUID 均由本机蓝牙 MAC 的相同字节派生；本轮未发现需要复制 CatPlay 固定 UUID 的依据。

验证：53 项 JVM 测试通过，包含逐帧完整握手、1 包窗口/零重传仍等待真实 ACK、认证问候积压时不提前 READY；27 项手机端 Rust 测试通过，含 FIFO 屏障顺序、取消屏障、旧 generation 和健康新鲜度。
本轮保留 416 封面回退及今天资源/音频修复；宝马实车稳定性依旧需要复测。
