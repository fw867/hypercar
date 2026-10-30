# BMW 黑屏与断开后保活排查（2026-10-30）

## 提交范围

10 月 28 日的 `2b92591`、`16d895c` 主要是自绘首页、地图和音频，`99d5e38` 是说明更新。
首次连接保护、LE ACL 过滤、禁止设备级断开等改动见 10 月 29 日的 `df94e1a` 和
10 月 30 日零点的 `0bc9c10`。本次基于当前 HEAD `6172064` 排查，不回退这些保护。

## 证据

用户提供 `hypercar-log-20261030-104609.txt` 和 `hypercar-log-20261030-111800.txt`，
应用均为 0.1.0.398。第二份是同一轮连接的延续，不能算两次独立复现。

- 10:45:42 收到 0x5703 热点凭据，10:45:44 已入网。
- 10:45:45.798 收到车机 ctrl 邀请，已知道车机地址；随后仍进入 mDNS 和 /20 扫描，
  到 10:45:58.930 才确定端点，耗费约 13 秒。
- pair-setup / pair-verify 成功；/info 配对前后均为 455，未获取显示 UUID/能力。
- 10:45:59.304 前置 modesChanged 被 454 Session Not Found 拒绝。原代码未补发。
- 会话和屏幕流 SETUP 均成功；10:45:59.387 发出 RECORD。
- 10:46:00.791–.792 车机同时关闭事件、控制通道，RECORD 未收到应答，尚未启动自绘推流。
- ADB 系统蓝牙历史：10:46:00.944 经典 ACL 断开原因是 Remote Terminated Connection。
- ADB 读取现存服务：HyperCarService 仍有 isForeground=true、startRequested=true、通知 ID=1，
  与用户已点断开后仍显示运行中一致。UI disconnect()/fail() 原本不负责停止此服务。

## 修改

1. 会话释放共用路径停止前台服务；重复断开/失败后再点断开，也会清理遗留服务。
   使用 stopService，不通过带车机参数的 ACTION_STOP 再回灌 ACL 事件。
   onDestroy 明确移除通知并释放 CPU/Wi-Fi 锁。
2. 区分用户断开和真实 ACL 断开。用户断开后同轮重复 ACL 连上不再重新拉起连接；
   手动点连接或真实 ACL 断开后的下一次上车恢复自动连接。
3. fail() 清除 sessionLive，避免失败清理后仍被判定为活跃会话。
4. 对明确返回 454 的前置 modesChanged，在会话 SETUP 和事件通道建立后、屏幕流 SETUP 前补发。
   已返回 200 的接收端不重复发送；补发未获确认时明确报错，停止流激活。
5. 在网关探测、mDNS 返回后再次检查 ctrl 邀请，避免错过刚到达的邀请后继续全段扫描。

以上修正已知状态机缺口，尚不能证明 RECORD 断连根因已完全解决。需要真车观察补发结果、
RECORD 应答和首帧；/info 无能力/UUID 时的兼容性仍未验证。

## 数字钥匙

用户确认：车机提示钥匙识别异常，贴近 NFC 仍可用；10:57 是离开车辆范围。

ADB 的 GATT 历史显示小米数字钥匙 BLE 从 10:42:36.829 保持至 10:57:45.364，
跨过 HyperCar 10:46 黑屏断连；末次 BLE 断开与用户离开范围相符，不作为异常掉线证据。
小米数字钥匙服务仍在运行，UWB 启用且芯片 READY。
10:42:40 开始的钥匙 UWB 会话记录 rangingCount=0、validRangingCount=0、startNoValidReportCount=2；
这早于所提供 HyperCar 日志中 10:45:01 的监听启动。

因此目前不能证明 HyperCar 关闭了钥匙 BLE/UWB，也不能仅凭 BLE 已连接就确认钥匙认证、
UWB 测距和车机提示全部正常。没有删除钥匙、重置钱包或清除系统蓝牙配对。
完整系统转储含隐私，仅保留本地临时诊断目录，不写入仓库。

## 验证

- 正式签名 Release 构建成功，版本 0.1.0.399 / versionCode 10399。
- Android 单元测试 27 项通过。
- Rust Android 测试二进制推到 USB 手机执行，5 项通过，包括 454 后按顺序补发的模拟 RTSP 测试。
- 通过 ADB 保留应用数据覆盖安装成功。
- 真车 RECORD 激活、实际前台服务随自动连接退出、钥匙车机提示恢复仍需车旁复测。

## 后续：钱包蓝牙钥匙置灰与公开实现对照

用户补充：UWB 异常时以往仍可在钱包点蓝牙钥匙开门，目前站在车旁按钮也是灰色禁用。
这不能解释为仅车机误报。NFC 可用、BLE 底层连接存在，都不能证明蓝牙钥匙业务认证及控车命令可用。
用户目前不方便进行车旁强行停止 HyperCar 的 A/B，因此保留因果关系未确认的结论。

ADB 读取的当前组件版本/最后更新时间：

| 组件 | 版本 | 最后更新 |
|---|---|---|
| 小米钱包 com.mipay.wallet | 6.115.1.5775.2754 | 2026-09-30 01:56 |
| 智能卡 com.miui.tsmclient | 26.10.09.4.f | 2026-10-27 01:27 |
| 数字钥匙框架 com.xiaomi.digitalkey | 13.0.58 | 2026-10-22 21:43 |

这些时间来自当前安装状态，不含旧版本对照，不能据此认定升级回归。
源码检查未找到 HyperCar 调用 BLE 扫描/广播/GATT 或更改系统蓝牙发包参数的路径。
当前 BMW 档案禁用出站 iAP2 和配置文件主动断开；iAP2 保活是经典蓝牙 RFCOMM 会话自身的数据。
共享蓝牙控制器、ROM 协议栈或车机业务状态产生的间接影响仍需 A/B 排除，不能声称绝无干扰。

对照采用当前本地 CarDeck 的实际代码及 CatPlay 公开源码；文档与代码冲突时不直接照抄文档。
例如 CarDeck 注释概括命令走事件通道，但其 assertModes() 实际经控制通道 request()；
CatPlay 的 assert_modes() 也通过 AirPlayRtspClient 控制队列发送，不能据注释把 modesChanged 全部改到事件通道。

| 项目 | 参考实现的实际行为 | HyperCar 0.1.0.399 | 判断 |
|---|---|---|---|
| /info 时机 | CarDeck runSetup() 和 CatPlay do_initial_setup() 均在会话 SETUP 成功后读取 /info | 两轮 /info 都在会话 SETUP 前；失败后选默认显示 UUID | 确定的流程差异，应优先修正；是否导致 BMW RECORD EOF 需实测 |
| 资源声明 | CarDeck 会话 SETUP 后、屏幕 SETUP 前发送 assertModes | 初次在会话 SETUP 前，454 后 .399 已补发 | 已有针对性修正，仍需确认真车补发结果 |
| 保活启动 | CarDeck 在 RECORD 前启用 UDP keepAlive 和 feedback；CatPlay bootstrap 已建立 keepalive，feedback 在屏幕流存在时调度 | RECORD 完成、音频协商结束后才启动 UDP/feedback | 确定存在启动较晚的差异；应提前，尚无证据证明 1.4 秒 EOF 就是心跳超时 |
| 来向业务命令 | CarDeck 处理 changeModes 和 iAPSendMessage | Rust 只自动回 RTSP 200，此两类命令未做业务响应 | 确定能力缺口；附件没有这些命令的记录，不列为已证实黑屏原因 |
| RECORD 请求体 | CatPlay record() 使用无请求体 RTSP 请求 | 发送空字典 bplist（42B） | 可作兼容性变量，优先级低于读取能力时序，不能直接认定空字典必错 |
| /auth-setup | CatPlay 有实现，但当前 bootstrap 默认不调用，源码说明所测接收端不强制 | 没有此步骤 | 仅发现接口存在不等于本车缺少必需认证，不应盲加 |

后续修正优先顺序：会话 SETUP 后读取有效 displays/uuid 并记录 enabledFeatures；
在激活前维持会话保活；确认 modesChanged 成功后立即开屏幕流并 RECORD。
如仍在 RECORD 处约 1.4 秒 EOF，再对照同车正常 iPhone 的 Wi-Fi 控制交互与 timing 收发计数。
现有 iPhone 蓝牙 HCI 记录不包含 Wi-Fi RTSP，不能替代该对照。
本次研究未生成新 APK，已安装的 .399 不包含上述后续差异修正。

参考来源：

- [CarDeck 协议笔记](https://github.com/fw867/cardeck/blob/main/docs/PROTOCOL-NOTES.md)
- [CatPlay bootstrap / SETUP 后 info](https://github.com/catplay-labs/catplay/blob/master/carplay/catplay_carplay/src/carplay_tx/transmitter.rs)
- [CatPlay RTSP client / RECORD / assert_modes](https://github.com/catplay-labs/catplay/blob/master/carplay/catplay_carplay/src/carplay_tx/client.rs)
- [CatPlay feedback 调度](https://github.com/catplay-labs/catplay/blob/master/carplay/catplay_carplay/src/carplay_tx/transmitter_impl.rs)
- [BMW 数字钥匙故障排查](https://faq.bmwusa.com/s/article/FAQ-Digital-Key-Not-Working-BMW-USA-gg7Op?language=en_US)

公开资料没有找到能直接解释本机“小米钱包宝马蓝牙钥匙按钮置灰”的已确认版本缺陷。
BMW 官方建议先核对蓝牙、钱包钥匙设置、重启手机及车辆唤醒；这些是排查建议，不是本机故障结论。

## CatPlay 时序校正实现（0.1.0.400）

保持本轮日志已确认成功的 iAP2、热点入网、pair-setup / pair-verify 和 SETUP 参数不变。
仅校正 AirPlay 激活路径：

1. 保留配对前的 best-effort 身份读取，以兼容已有配对公钥索引；移除配对后、SETUP 前的重复 /info。
2. 会话 SETUP 成功后读取 /info，再据其显示 UUID、像素尺寸与 keepAliveSendStatsAsBody 建立屏幕流。
   会话内 /info 仍失败时保留旧能力/设置兜底，日志明确标记默认 UUID 来源。
3. modesChanged 统一在会话 SETUP 后、屏幕流 SETUP 前发送并检查应答，不再先发已知会被 454 拒绝的声明。
4. 事件通道建立后启动 UDP / feedback，保证 RECORD 应答等待、后续音频协商期间也能保活。
   UDP 对齐 CatPlay 的首包立即发送；后续保留原先 5 秒间隔，feedback 保留 1 秒间隔。
5. RECORD 对齐 CatPlay 的无请求体形式，继续使用现有加密控制连接和 CSeq 应答派发。
6. 保活任务由协商结果/引擎拥有，失败、取消和结束时自动 abort，避免早启保活残留到下轮。
7. 因 feedback 与 RECORD 开始并发，RTSP 各写入口统一先取得 socket 写锁，再分配加密 nonce 并写出。
   这只调整并发写入的原子性，不改变配对密钥、加密算法、帧格式或已验证的 SETUP 载荷。

验证使用严格状态模拟接收端，检查 SETUP → /info → modesChanged 顺序、车机返回的显示 UUID、
RECORD 空体、RECORD 暂不应答期间仍能收到 UDP/feedback，以及取消排队写入不消耗加密 nonce。
真车是否接受会话内 /info 并完成 RECORD，仍待新日志确认。

0.1.0.400 验证完成：正式签名 Release 构建成功，Android 单元测试 27 项通过；
最终 Rust Android 测试二进制在 USB 手机执行，7 项通过（含上述时序/保活/加密排队回归）。
确认 APK 内 arm64 Rust 库与此次构建产物一致；ADB 保留应用数据覆盖安装成功，
手机已报告 versionName=0.1.0.400 / versionCode=10400。尚无真车复测结果。

## DiPlay 接收端 500 根因确认（2026-10-30 16:22）

USB ADB 接入 Redmi 21091116AC（evergo），包 com.shihab.diplay，版本 0.2.22 / versionCode 41。
读取系统 logcat 后确认 /feedback 500 是接收端构造反馈响应时的异常：

```text
airplay rx POST /feedback cseq=25 body=0
java.lang.ArrayIndexOutOfBoundsException: src.length=6 srcPos=-2 dst.length=8 dstPos=0 length=8
  at com.shilapi.xcertplay.airplay.BplistCodec.encodeUnsignedInt(BplistCodec.kt:259)
  at com.shilapi.xcertplay.airplay.BplistCodec.encode(BplistCodec.kt:85)
  at com.shilapi.xcertplay.airplay.AirPlaySession.handle(AirPlaySession.kt:619)
airplay tx status=500 cseq=25 body=0
```

16:21:27 起每秒重现。公开源码同位置为：
`raw.copyOfRange(raw.size - size, raw.size)`；本次数值的 BigInteger 原始表示为 6 字节，
plist 根据位数选择 8 字节，导致起始下标变为 -2，抛异常后被控制处理器转成 500。
接收端应将较短的正整数大端表示左侧补零到目标宽度，而不是从负下标截取。
尚未修改或安装 DiPlay；HyperCar 的反馈请求格式无需为此故障添加特殊载荷。
完整接收端日志保存在本机临时诊断目录，不提交仓库。

## 上车自动连接开关立即生效（0.1.0.402）

按用户确认的范围，仅用设置原有“上车自动连接”开关控制 HyperCar 自身蓝牙会话。
关闭时持久保存偏好，取消在途连接/重连，关闭当前 iAP2 RFCOMM 和监听 socket，清理当前
CarPlay 会话与保活通知；新 ACL 广播、服务启动和手动连接也受同一开关约束。
开启时恢复被动监听。快速切换串行处理，避免旧关闭任务清理新连接。

不结束 App 进程、不禁用通知音乐服务，不修改系统蓝牙开关或数字钥匙。
独立的“关闭监听并退出/恢复监听”卡片及其进程退出代码已移除；旧临时版持久停用状态
迁移至原开关，并恢复旧版禁用的组件，避免升级后仍无法唤醒。
首页关闭态明确显示蓝牙会话已暂停。

验证：正式签名构建与 27 项 Android 单元测试通过。USB 真机先关闭、再开启、再关闭：
日志确认 iAP2 被动监听恢复与停止，关闭时无自动重连；前后 App PID 均为 31312。
最终保留开关关闭状态，供用户进行钥匙 A/B。数字钥匙恢复与否仍需车旁观察。

## 手动 / 自动控制语义校正（0.1.0.403）

0.1.0.402 将自动开关同时限制手动连接的做法已撤销。
自动模式仅控制待机监听与自动重连，手动启动始终可用；手动停止时根据自动模式保留或关闭
后台监听。正在进行的会话不因开关切换被拆除，待机监听也不再占用“正在协商”状态。
具体状态表与验证见 [会话控制](SESSION-CONTROL.md)。
