# Showcase 对照记录

日期：2026-11-04；amineross/showcase 提交 `34980e930a0b3245ffb6b49f22187630dac525ab`。
本地只读参考在 `D:\devtools\hypercar-diagnostics\showcase-reference`，未运行其安装/部署脚本。
它是越狱 iOS 设备上的无线 CarPlay 接收端，HyperCar 是 Android 发送端，注意命令方向。

## 已确认的可借鉴点

- `source/carplay_services.m:3537`：接收端退后台发送 changeModes Take 主屏，前台恢复发送 requestUI。
  新 decoder 接入时先缓存配置，再 Take，100ms 后 requestUI（2949 起）；媒体依赖链损坏时请求 forceKeyFrame。
  这是 538/539 屏幕恢复的独立对照，不证明宝马必须采用同样的重新建流行为或等待时间。
- 4748 起：RECORD 应答前进行 timing negotiation；4352 起：用 generation 确保 re-RECORD 替换原计时维护任务。
  HyperCar 已有 timing server 与共同单调时钟，但尚未将收到/响应计时请求的计数与最近时间纳入 539 阶段摘要。
  值得新增计时观测；不能因对端角色不同而把接收端主动发 NTP 的代码直接移植到发送端。
- 2684 起、3410 起：读取期间与完整帧到达后分别记录 TCP 状态、到达间隔、解密、IPC 等待耗时，
  避免只用完整帧到达时间推断发送端饥饿。HyperCar 可在发送 socket 上补 Android/Linux 的 TCP_INFO、
  发送队列积压、未确认段及重传等观测。TCP 确认也不是车机应用解码或显示确认。
- 5680 起：发现可能是缓存的 _carplay-ctrl 服务后短时探测；连接拒绝时重新解析，
  每轮刷新端点，防止旧会话端口阻塞新连接。适合借鉴缓存失效思路，但这里连接的是发送端 ctrl 服务，
  不等于应更改宝马 AirPlay 7000 端口或删除等待真实邀请的路径。
- 3979 起、4038 起与 source/carplay_pair.c:1015：可独立交叉检查 VideoConfig、128B header AAD、
  ChaCha nonce、streamConnectionID 的 HKDF；音频 3198 起使用 RTP timestamp+SSRC 做 AAD、包尾 8B nonce。
  当前 HyperCar 这些关键约定已经一致，适合补跨实现测试，不是已发现的新协议缺口。

## 不适合直接移植的部分

当前源码 `handle_command_dictionary`（5010 起）处理 modesChanged、disableBluetooth 等，
没有 iAPSendMessage / changeUIContext 的业务专用分支；对这些命令仍走未处理和通用 200。
源码中也未找到 type 130 Wi-Fi iAP2 数据隧道的完整实现，因此不能把 README 的蓝牙 iAP2/Wi-Fi handoff
描述等同于宝马 Wi-Fi iAP2 全流程。

解码器抖动缓冲、过期帧显示抑制及 iOS BTstack 接管主要属于接收端。
不能在发送端照搬丢弃 H.264 P 帧的策略，破坏引用链后需要等 IDR。
其型号/内存决定分辨率上限的策略也不适合代替宝马实际上报的能力。

建议后续优先级：计时阶段观测、TCP 发送端诊断、跨实现报文兼容测试。
原生宝马接收端行为仍以用户成功日志/固件与实车验证为准。

## 540 已落实的改进

- timing server 记录有效请求、成功回包、无效包、收发失败、最近请求/回包时间及本地应答耗时。
  RECORD/资源声明前后记录一次快照，会话阶段摘要持续报告最近交互距今时间。
  本地应答耗时不是网络往返时延；没有请求也不直接判定会话失败。
- 主屏 TCP 每秒独立采样 TCP_INFO 与 TIOCOUTQ，即使视频 write_all 等待也继续采样。
  记录连接 ID、状态、RTT、未确认段、累计重传、累计确认字节、未发送字节、发送队列与采样年龄。
  读取字段以系统实际返回长度为界，旧内核没有的字段为未知。采样失败只影响诊断。
  停止 sender 时先撤销受锁保护的描述符借用并停止任务，再关闭 socket；不复制 socket 延长连接寿命。
  新流通过连接 ID 隔离，旧流的采样不能覆盖新流。
- 诊断页增加计时请求/回包、TCP 确认/积压/重传与采样年龄。首页视频小卡保持原布局。
  TCP 累计确认包含配置、帧、保活等流数据与内核计数基线，不等于已显示的视频帧或视频净负载。
- `rust-core/testdata/showcase-wire.json` 是独立实现生成的固定向量；`showcase_wire.rs`
  对真实 TCP/UDP sender 输出逐字节校验 HKDF、VideoConfig、两帧视频和两包 PCM。
  生成输入和来源见同目录 README；未引入 Showcase 的生产依赖。

Android arm64 上 61 项原生测试通过、1 项需要实际 CarWith 会话的测试忽略；152 项 Kotlin 测试通过。
新增测试覆盖计时 UDP 的有效/无效包、TCP 确认增量、旧版 TCP_INFO 短结构、
视频写入阻塞时仍采样积压及 sender 释放后停止采样。原有屏幕重建与音频保留测试仍通过。
本轮不改媒体报文格式和资源恢复策略；宝马“有连接图标但不能进入桌面”的原因仍需实车新日志定位。

540 签名 release 已成功覆盖安装手机，versionName=0.1.0.540、versionCode=10540。
应用进程已启动，原生库加载与初始化成功，启动日志未见 FATAL/链接异常。
APK 内原生库 SHA256：`6df116537e12cc4b289124c334064923d5ccf75a7673b38d3ab8732e75aebb1a`，
与本地本轮构建逐字节一致。未修改用户的实验开关、画面来源或连接偏好。

## 541 旧端点恢复闭环

首次控制连接与协商重试共用 AirplayEndpointConnector：

1. 连接失败立即清除与当前 SSID、IP、端口匹配的缓存，只清端点，保留配对身份。
2. 先接收本轮 iAP2 新参数与新 ctrl 邀请；没有更新时在当前 SessionSocketLease 对应的
   Network 上重新查询 `_airplay._tcp`，单次最多 1500ms。绑定该网络失败直接跳过发现，不走默认网络。
3. 新端点替换旧候选，重新打开的实际控制 FD 直接移交引擎；不额外做成功后立即断开的 TCP 预探测。
   缓存路径此前仍有一次这种预探测，本轮已去掉。
4. 发现不返回服务时保留当前广播/iAP2，在既有 8 秒连接重试窗口内等待新参数或服务开始监听，
   每次 socket 连接另有超时；窗口耗尽回到既有失败/待机策略。每个窗口最多一次 mDNS 查询。
   不因 mDNS 发现就绕过首次邀请要求或 Wi-Fi 主动连接开关。
5. 本轮标识、socket lease 或绑定 Network 已变化时，丢弃过期结果并关闭已取得的 FD。
   晚到 iAP2 回调用本轮标识与 coordinator 校验；主机与端口通过单个 AtomicReference 发布。
   新参数在旧 TCP 恰好连接成功时到达，也会关闭旧 FD 再连接新目标。

修复两处妨碍恢复的问题：初始连接尊重车机明确报告的端口；切换 IP 后 engineConfigJson 使用
实际成功的新主机与端口（之前仍可能使用旧 apHost，造成控制 FD 与后续媒体目标不一致）。

mDNS 解析移入可测试的 AirplayDnsRecords：SRV 跳过 6 字节固定前缀后读取 target；
处理 answer/authority/additional、压缩名字、跨数据包补齐、TTL=0 删除、TTL 到期。
只接收合法 AirPlay 服务实例，截断或压缩循环的数据包不发布部分解析结果。

验证：161 项 Kotlin 测试通过。其中新增 9 项覆盖真实本地旧端口拒连/新监听端口连接、
新 iAP2 更新抢先、发现期间新邀请、成功连接后端点竞争、限时与一次性发现、会话过期 FD 关闭、
SRV 前缀与 additional A、分包/换端口/过期/告别，以及压缩名字与异常包。
原生协议本轮未变，复用 540 已验证的原生库；实车恢复效果需新一轮测试确认。

源码：
[会话与屏幕服务](https://github.com/amineross/showcase/blob/34980e930a0b3245ffb6b49f22187630dac525ab/source/carplay_services.m)、
[蓝牙交接](https://github.com/amineross/showcase/blob/34980e930a0b3245ffb6b49f22187630dac525ab/source/carplay_bt.m)、
[密钥派生](https://github.com/amineross/showcase/blob/34980e930a0b3245ffb6b49f22187630dac525ab/source/carplay_pair.c)。
