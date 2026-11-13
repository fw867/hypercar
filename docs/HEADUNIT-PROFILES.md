# 车机档案与验证范围

更新：2026-11-13。所有车型统一在 [profiles/headunits.json](../profiles/headunits.json) 下发，App 按 ID 选择，不维护独立车型 JSON。

## 内置策略

| ID | 断 HFP/A2DP | 出站 iAP2 | 要求本轮激活 | 特殊门槛 | 验证范围 |
|---|---|---|---|---|---|
| bmw_id85 | false | false | true | 允许用户开启已配对 Wi-Fi 主动恢复 | MGU-55 实车基准；不能代表所有 iDrive |
| bmw_id9 | false | true | true | 新鲜0x4301；主屏/HID/视频TCP在RECORD前就绪；不继承MGU的SAP | ID9实车待验证；AAC48已接通并通过DiPlay出声测试 |
| generic | true | true | false | 通用模板 | verified 不代表所有车型经过测试 |
| winplay | false | true | false | deviceID 校验；7000端口 | PC 联调，不等于真实车机 |
| audi_mib3 | true | true | false | mDNS 优先、兼容晚到端点 | 本项目未完成奥迪实车验证 |
| volkswagen_mib | false | true | true | 当前目标 Wi-Fi 广播；必须新鲜 ctrl 邀请；禁用无邀请捷径 | 用户转述连接正常，未逐项验证功能 |
| mercedes_mbux | false | true | true | 本轮 iAP2 端点；单次 RECORD 后协商屏幕/音频；关闭旧屏幕前置实验 | 已替换为 MB_D-PP-3/4 / 320.17.7 参考策略，未实车验证 |

BMW/generic/Audi 启用0x4300、EA及0x4E0E；大众/奔驰启用0x4300、0x4E0E但不主动打开EA；WinPlay关闭三项。全部回复电源更新，不因CarPlay窗口关闭就主动中止。音频配置保护只涉及HFP/A2DP，不能因此提前断掉RFCOMM控制链。

默认及未知ID回退BMW，新增档案不自动切换用户选择。档案ID不同，连接参数不能因同名Wi-Fi混用。真实接收端身份与UI所选档案需同时记录。

## 配置字段与覆盖

当前发布schema=10。覆盖门槛按档案区分：mercedes_mbux需要schema10，bmw_id9需要schema9，其余内置档案继续接受schema8，避免旧奔驰缓存恢复已移除的屏幕前置策略，同时保留其它档案已有有效覆盖；低于该档案门槛的缓存仅能追加新ID。缺省字段继承同ID内置值，新ID继承BMW模板；显式false是有效覆盖。touchDisplay/pairingHint空字符串可清除。导出保留策略及schema，刷新失败保留缓存并继续执行schema隔离。

| 字段组 | 内容 |
|---|---|
| iAP2 | validatesDeviceId、sendCarPlayAvailability、openEaSessions、sendTransportIdentifier、replyPowerUpdates、dropProfilesBeforeIap2、allowOutboundIap2、abortWhenCarPlayWindowClosed |
| 激活/发现 | preferMdnsAirplay、requireAirplayActivation、allowProactiveWifiSession、advertiseOnCurrentWifi、requireFreshCtrlInvite、fallbackAirplayPorts |
| 画面 | touchDisplay、useScreenResourceLifecycle、mbuxScreenBootstrap、id9ScreenBootstrap |
| 说明 | id、label、model、notes、verified、pairingHint |

useScreenResourceLifecycle 默认false；有效值为档案值 OR 用户开关，不改用户偏好。奔驰为true时设置页显示档案已启用且不可在该行关闭。旧 keepIap2AliveOnly 已移除，不恢复旧行为。

## 大众

参考 mcarplay 的 VW4131/VW4211（ID.7、帕萨特2025）线索。必须明确目标车机才在当前Wi-Fi提前广播，不据此写入成功配对历史。ctrl邀请须属于当前Network且在20秒内，控制连接前后复核；过期候选关闭。20秒是兼容性经验，不是协议常量。失败后保留广播及蓝牙，等待新邀请，不能复用已消耗邀请。

2026-11-07 按用户转述将verified设为true，仅表示已有连接正常反馈，不证明所有大众车型/固件、音频、方向盘和语音都正常。另一个无声反馈接收端自报CarLetter；现象与修正见[音频](AUDIO.md)。

## 奥迪

对照 CarDeck `503ed3730d5a9365272294b79306cea5fcce115b`。0x4300/0x4E0E只发送、不等ACK，EA仅对车机声明的协议尝试，缺EA回应不阻断。MMI6318可能只给热点凭据、端点靠mDNS；MMI9310的0x4301可能晚到2分35秒。保留两条发现路径，不套用大众新鲜邀请门槛。

不按品牌固定网关、控制Session ID、触摸尺寸或MFi挑战长度。3315首次403后等待新邀请成功的第三方记录，不能推广为明文降级安全或本项目已完成验证。

## 奔驰 MB_D-PP

参考 [mcarplay 提交 2b2ac6b](https://github.com/wangxso/mcarplay/commit/2b2ac6b67d09d5b9656fab28c8ce75495d9a1d02) 与 [issue #55](https://github.com/wangxso/mcarplay/issues/55)：同一台 2023 S 级在 iAP2 自报 MB_D-PP-3、/info 自报 MB_D-PP-4，AirPlay srcvers=320.17.7。报告显示仅视频会话正常，音频 SETUP 200 后第二次 RECORD 与随后 16–559ms 拆链相关。提交新增厂商为空或 Mercedes-Benz 时可手动开启的单次 RECORD 预设；不是奔驰全品牌认证、MFi 或蓝牙拆链策略。

直接替换原奔驰档案内容，保留 `mercedes_mbux` ID，已保存的奔驰选择自动使用新策略，列表只保留一个奔驰条目。使用现有全局单次 RECORD 路径：初始激活 → 资源声明 → 屏幕 SETUP/TCP → 音频 SETUP；音频加入、屏幕借出归还均不补发 RECORD。HyperCar 原有音频路径已满足这一点，无需新增无实际行为差异的布尔开关。MB_E-P-4 的配对后 SAP、最小能力集、屏幕在 RECORD 前建立等实验不由 MB_D-PP 继承。

保留蓝牙、本轮端点门槛和屏幕资源生命周期；尺寸及 UUID 使用协商结果，不固定为报告的 2570×1080。不引入奇瑞专用 MFi 或 disableBluetooth 行为。未引入字段，发布内容 version 与 schema 均升10；仅奔驰提高覆盖门槛，schema8/9 旧缓存不能恢复旧 bootstrap。以上是替换未跑通的奔驰实验并复用既有单次激活行为，尚无本项目 MB_D-PP 音画实测，也不能解释 MB_E-P-4 既有 SETUP 400。

## 旧奔驰 MBUX 实验记录

以下为 MB_E-P-4 的历史排查。原档案已被上述 MB_D-PP 参考策略替换，`mbuxScreenBootstrap` 当前关闭；这些记录不代表当前默认流程。

11月9日14:29导出日志（版本524）头部五次尝试均报BSSID辅助读取正则异常，正文后两轮也复现；阶段止于IAP2_PARAMETERS，没有/auth-setup、屏幕SETUP或RECORD。蓝牙已给出热点与端点，后续RFCOMM local_cancel=true由本地失败清理造成，不是本轮车机拒绝媒体的证据。路径对应610新增复用检查。611去掉该路径正则，用ASCII/标签扫描解析并把辅助读取失败降级为正常入网；保留取消，不降低网络复用的身份/地址校验。

样本MB_E-P-4 / MBUX00081，verified=false。三份06:25/06:27/06:30导出存在包含关系，去重后8次引擎配置、7次屏幕SETUP空体400；能完成pair-verify、会话SETUP、RECORD及初始资源声明，不能用后续EOF掩盖最早明确400。车机提供0x4301热点和IPv6端点，无须强等ctrl GET。

更新后的winplay.txt虽选择winplay档案，实际身份仍为奔驰，不是Windows接收端独立成功样本。06:36:35那轮屏幕SETUP400、音频SETUP400，CarWith本地7240持续拒绝；即使Wi-Fi iAP2传输READY，直到导出也无视频帧及音频包。修正IPv6音频URI仍不足以消除该轮400。试验档案保留控制和iAP2等待后续资源，不宣称解决屏幕拒绝。

复测使用奔驰档案，先确认CarWith七通道源就绪，再观察屏幕/音频SETUP应答及后续changeModes/requestUI。不要把“会话已连接”当成投屏成功，也不要因为档案名叫winplay就推断接收端为PC。

代码：[HeadUnitProfile](../app/src/main/java/io/github/hypercar/headunit/HeadUnitProfile.kt)、[RemoteProfiles](../app/src/main/java/io/github/hypercar/headunit/RemoteProfiles.kt)。


### 15:50 导出日志的新证据（2026-11-08 复核）

同步基线 `9ca7ae0`。输入 `/mnt/e/Downloads/hypercar-log-20261107-155000.txt`，导出版本0.1.0.514，上午历史仍在文件中；本次以4757行开始的下午两轮为准。两轮都实际选择mercedes_mbux，desktop_receiver=false、screen_resource_lifecycle=true，已排除仅因误用WinPlay档案导致失败的解释。

| 阶段 | 第一轮 15:46 | 第二轮 15:48 |
|---|---|---|
| 控制与资源 | pair-verify、会话SETUP、/info、RECORD和资源声明成功 | 同样完成 |
| 手机视频源 | 七通道握手及加密准入成功，实际1280×720，持续输出AU；最后source_units=1110 | 7240持续Connection refused，source_units=0 |
| Wi-Fi iAP2 | 15:46:09.419传输READY，不能等同证书/签名验证 | 15:48:26.749传输READY |
| 车机UI请求 | 15:46:17.813收到真实requestUI并回200，本地screen切到1/1 | 未收到requestUI，初始已为1/1 |
| 屏幕SETUP | 15:46:17.834发出，.869收到400空体（CSeq52）；没有视频socket/写帧 | 15:48:25.843发出，.908收到400空体（CSeq14） |
| 音频SETUP | 带方括号IPv6、desktop=false、latency=0，仍400空体 | 相同类别参数仍400空体 |

第一轮说明：视频源已就绪，真实UI请求也已到达，且屏幕SETUP发生在Wi-Fi iAP2传输READY约8秒后；仍失败。因此“只需等源就绪/传输READY/补requestUI”不是这轮的完整解释。第二轮额外存在本地CarWith启动未落实的问题，不能把两轮都归为源未启动。两轮画面未出现的直接阻断仍是屏幕SETUP被拒，控制存活不是推流成功。


2026-11-08 修复资源声明中的负数编码：原speechMode=-1被写为单字节FF、按Apple规则成为255，现固定8字节保留-1。该补丁是基础plist正确性修正，正数编码及其它车型策略不变；尚未获得奔驰修复后实车反馈，不更新verified标记。


### BOX-OFF 屏幕路径校准（2026-11-09）

用户确认 BOX-OFF 在奔驰有画面、可操作，仅无声音。静态差异见[参考分析](REFERENCE-REVIEW.md)。当时仅 mercedes_mbux 内置档案启用 mbux_screen_bootstrap；本轮替换后已关闭，也不启用BMW盒子交接逻辑。

旧奔驰实验要求pair-verify成功后尝试/auth-setup；已配对时仅404/501按接口不存在继续，400/403、超时或损坏响应均保留失败。SAP复用现有X25519/AES交换解析，不宣称验证证书链或签名；支持有界可变签名长度，未启用SAP-only legacy AES媒体。初始SETUP能力缩为viewAreas，macAddress采用本轮deviceID；不修改配对身份、iPhone声明及既有屏幕UUID来源。

初始资源保留车机音频归属，后续播放仍经独立音频资源申请，保留/info声明的约束。屏幕按100ms延迟执行资源声明→SETUP→连接dataPort→RECORD；只有RECORD成功后才向视频泵安装发送器，安装时再次核对屏幕代次。SETUP拒绝不会再仅凭控制存活显示已连接；RECORD拒绝或资源收回释放本轮屏幕通道。后续资源变化继续走已有串行生命周期，不重复全局RECORD。暂未修改音频格式/URI和全局RTSP Session头处理。

该分支是根据成功样本静态路径校准的奔驰实验，不代表已在奔驰验证消除400；多个校准项同时启用，不能据一次成功反推唯一根因。回归及安装状态见VALIDATION。

ID9当前路径：配对 → 会话SETUP → /info主屏UUID → 初始资源声明 → 按约束申请主屏 → HID mode0 → 主屏SETUP/TCP → RECORD → 资源版本仍有效才送帧。只对bmw_id9启用；不支持的PCM媒体档不尝试；声明AAC-LC48媒体输出时可使用AAC，ID9实车仍待验证。
