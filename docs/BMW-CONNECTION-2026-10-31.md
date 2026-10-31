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
