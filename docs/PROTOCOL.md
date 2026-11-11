# 当前协议与资源管理

更新：2026-11-07。这里记录本项目代码与已采集数据能够支持的行为，不替代完整协议规范。

## 蓝牙与 iAP2

手机主 RFCOMM UUID 为 `00000000-deca-fade-deca-deafdecacafe`，附件端 UUID 为 `00000000-deca-fade-deca-deafdecacaff`。主监听使用安全 RFCOMM。蓝牙重启或监听异常后按代次恢复，旧回调不能覆盖新监听。

设备显示名使用当前系统蓝牙名，无法读取或为空时回退到机型名，再回退到 `HyperCar`。蓝牙 iAP2 在握手开始时读取名称；AirPlay 建链时读取并将同一快照用于 SETUP `name` 和该会话的 Wi-Fi iAP2 `0x4E09`。这只改变显示名，不改变 MAC、设备 UUID、配对密钥或协议型号；会话期间修改蓝牙名在下次建链生效。

Detect 标记 `FF550200EE10` 与后续 SYN/SYNACK 是不同阶段。蓝牙与 Wi-Fi iAP2 各自协商链路序列、窗口和 Session ID；不能把某次日志的控制 Session ID 固定成所有车机通用值。

CSM 格式为 `40 40 | total_len(u16BE) | message_id(u16BE) | parameters`，总长度包含 6 字节头。参数的长度包含自身 4 字节头。消息 `0xAA06` 是认证序列的一部分；认证失败消息是 `0xAA04`。

当前兼容流程在 AA05 后发送问候及相关能力消息。它没有实现完整 Apple 证书和签名校验，不能把出现 AA05 写成“完整标准认证通过”。能力清单较短不能直接判定未列出的消息均不支持；Wi-Fi 凭据的晚到或重复回调也不能无条件再次入网。

## 控制广播与网络

控制服务为 `_carplay-ctrl._tcp`，实例名 `sender`，TXT 包括真实蓝牙 MAC 的 `id` 与 `srcvers=950.7.1`。API 33+ 可通过 NsdServiceInfo.setNetwork 指定车机 Network，较低版本依赖进程网络绑定。注册请求发出不等于服务就绪，只有当前代次成功回调才能置为 ready。

同一网络久无邀请时可有限刷新注册，保留端口；当前策略是间隔 30 秒、最多两次成功刷新，挂起注册 10 秒超时后允许重试。旧注册回调会被丢弃和清理。

每轮 SessionSocketLease 固定 Network；TCP/UDP 在连接前绑定，DNS 与 IPv6 scope 使用同一网络。失效后终止旧租约，不尝试任意默认网络。IPv4 与 link-local IPv6 同时存在是正常状态，现有日志未证明 IPv6 导致重连失败。

## AirPlay 与 Wi-Fi iAP2

已知宝马流程由本轮控制邀请（如 `/ctrl-int/1/connect`）或当前 iAP2 端点推进。内置档案与当前下发配置均要求激活；远程字段已支持完整解析和导出，见[车机档案](HEADUNIT-PROFILES.md)。发现 AirPlay 服务不等于已收到连接邀请。持久控制身份与接收端配对资料分开存储；本地存在凭据不等于车机仍认可。

Wi-Fi iAP2 的入站是事件通道 `POST /command`，plist 中 `type=iAPSendMessage`、`params.data` 承载链路包；出站经客户端控制通道。必须处理链路 ACK 和 CSM；RTSP 200 不能代替 iAP2 消息完成。盒子样本中视频可早于 Wi-Fi 认证完成，不把后者设为所有视频启动的硬门槛。

资源 take / borrow / return 与 modes 影响屏幕、音频归属。`requestUI` 的方向必须区分：手机主动发出时请求车机原生界面，车机发给手机时请求手机界面；均不等于断连。当前电话状态回应不伪造 SIM、信号或通话能力；不保留未定义的参数 18。

UDP 心跳、feedback 与屏幕保活是不同机制，当前周期分别为 5 秒、1 秒和 1 秒。不能凭断开间隔直接认定某一心跳缺失。

## FD、任务与关闭

ParcelFileDescriptor 从 Java Socket 取得原生 FD 后 detach；Rust 接管该 FD，Java 原 Socket 在 finally 中关闭，不再全局永久保留。手机 loopback 已验证 TCP、UDP 的副本仍能工作，原生 TCP FD 关闭后对端收到 EOF。

手动退出调用 nativeStopGracefully：先终止媒体任务，再向已建立的屏幕流（type 110，使用实际流 UUID）与音频流（type 100，若已协商）发送 TEARDOWN。写入及响应总预算 900ms，其中响应等待 800ms；控制 shutdown 另设 200ms 上限。核心正常退出等待 2 秒，超时后取消并继续有界等待。

故障 nativeStop 不发 TEARDOWN。退出信号持久保存，避免早于等待者时丢失；完成守卫处理正常、取消与异常退出。

当前发送的是流 TEARDOWN，随后关闭控制连接，未实现一个额外的空 streams 整会话 TEARDOWN。因此不能声称获得了车机“整个业务会话已清理”的确认，也尚未证明此改动解决宝马残留状态。未确认通用 iAP2 退出消息，不在每次媒体退出时强发不可用通知。

媒体资源、用户级音频恢复与键盘/方向盘事件分别见[视频](VIDEO-STREAM-REVIEW.md)、[音频](AUDIO.md)、[输入与语音](INPUT-AND-VOICE.md)。语音当前使用手机麦克风，没有车机麦克风上行。
