# 车机档案与验证范围

更新于 2026-11-24。远程档案由 [profiles/headunits.json](../profiles/headunits.json) 下发。

当前编码策略：所有档案默认HEVC优先，保留显式H.264选择；手机缺少硬件HEVC Main/Surface编码器，或本轮会话未明确启用HEVC时使用H.264。ID9、MBUX兼容档案不再按车型强制移除HEVC，其余认证、启动顺序、音频和缓冲能力策略保持。旧章节的“ID9关闭HEVC/默认H.264”为当时验证阶段记录，当前规则见[视频策略](VIDEO-STREAM-REVIEW.md)。这不构成所有车型HEVC实车通过的结论。

当前URI策略：所有车机档案在配对后的最终控制会话中创建一次绝对URI，统一会话SETUP、屏幕SETUP、RECORD、TEARDOWN目标；保留音频SETUP原URI。已删除原BMW专用JSON字段和车型门控，内置、远端或未知档案均走公共会话初始化；旧字段不参与运行。请求正文及其它车型的认证、能力、超时、启动顺序不变。详情见[验证记录](VALIDATION.md)及[盒子URI证据](BMW-BOX-CARPLAY-FLOW.md)。

## 内置策略

| ID | iAP2 前主动断开 HFP/A2DP | 出站 iAP2 | 要求本轮激活 | 连接特点 |
|---|---|---|---|---|
| bmw_id85 | false | false | true | 由车机发起连接，支持恢复已配对车机的热点 |
| bmw_id6 | false | false | true | 校验 NBT Evo 身份，音频和屏幕就绪后激活会话 |
| bmw_id9 | false | true | true | 支持 IPv6，主屏、HID、视频 TCP 在 RECORD 前就绪 |
| generic | true | true | false | 优先通过 mDNS 找端点，找不到时尝试 7000 端口 |
| motorcycle_generic | false | false | true | 沿用 BMW 连接流程，跳过 `disableBluetooth` |
| winplay | false | true | false | 校验 deviceID，优先使用 mDNS 公告端口 |
| audi_mib3 | true | true | false | mDNS 优先，兼容晚到的端点 |
| volkswagen_mib | false | true | true | 在当前目标 Wi-Fi 广播，等待本轮 ctrl 邀请 |
| mercedes_mbux | false | true | true | 使用本轮 iAP2 端点，单次 RECORD 后协商屏幕与音频 |

MGU BMW/generic/Audi 启用0x4300、EA及0x4E0E；大众/奔驰启用0x4300、0x4E0E但不主动打开EA；WinPlay关闭三项。ID6 仅启用0x4300，不套用MGU的EA、传输标识及SAP引导；nbtAudioFirst只允许ID6启用，禁止与ID9/MBUX前置策略混用。全部回复电源更新，不因CarPlay窗口关闭就主动中止。音频配置保护只涉及HFP/A2DP，不能因此提前断掉RFCOMM控制链。

首次使用默认选 BMW。已选档案被移除或受版本限制时，保留选择和配对，等待用户重选或升级后再连接。档案 ID 不同，连接参数不能因同名 Wi-Fi 混用。真实接收端身份与 UI 所选档案需同时记录。

## 配置字段与覆盖

当前发布 schema 为 13。连接策略的覆盖门槛沿用原值：`bmw_id85` 需 12，`bmw_id6` 需 11，`mercedes_mbux` 需 10，`bmw_id9` 需 9，其余内置档案需 8。schema 13 以前的同 ID 远程档案使用当前内置说明，并保留有效的连接参数。新 ID 的连接参数默认继承 BMW 模板。`touchDisplay` 或 `pairingHint` 传空字符串可清除对应说明或兜底值。

| 字段组 | 内容 |
|---|---|
| iAP2 | validatesDeviceId、sendCarPlayAvailability、openEaSessions、sendTransportIdentifier、replyPowerUpdates、dropProfilesBeforeIap2、allowOutboundIap2、abortWhenCarPlayWindowClosed |
| 激活/发现 | preferMdnsAirplay、requireAirplayActivation、allowProactiveWifiSession、advertiseOnCurrentWifi、requireFreshCtrlInvite、fallbackAirplayPorts |
| 画面 | touchDisplay、useScreenResourceLifecycle、mbuxScreenBootstrap、id9ScreenBootstrap |
| 说明 | id、label、model、notes、verified、pairingHint |
| 选择页 | category、brandId、brandName、vehicleModels、platformName、aliases |

useScreenResourceLifecycle 默认false；有效值为档案值 OR 用户开关，不改用户偏好。奔驰为true时设置页显示档案已启用且不可在该行关闭。旧 keepIap2AliveOnly 已移除，不恢复旧行为。

「设置 → 我的车机 → 车机档案」按品牌浏览，也可以搜索车型、车机系统和别名。多个车型可以共用一个档案 ID，按 `vehicleModels` 填写即可。同一汽车品牌的条目共用 `brandId` 和 `brandName`。其他分类使用 `category` 的 `motorcycle`、`general` 或 `receiver`。新条目缺少品牌信息时仍可搜索，也会列在「未分类」。

### 远程档案的最低 App 版本

把带版本门槛的条目放在顶层 `versionedProfiles` 数组，每条填写正整数 `minVersionCode`。App 使用安装编号比较，`versionCode >= minVersionCode` 时生效。例如「通用摩托车」的远程条目要求 `10716`（`0.1.0.620`）及以上。

旧版 App 仍读取 `profiles`，因此这个数组用于无需版本门槛的条目。新版从缓存加载时也会检查版本。未达门槛时，远程新增条目不会出现，同 ID 的远程覆盖会回退到已有条目。能否覆盖内置档案仍需同时满足 `_schema`。同 ID 有多个符合条件的条目时，采用最后一条，`versionedProfiles` 排在 `profiles` 之后。

## 大众

参考 mcarplay 的 VW4131/VW4211（ID.7、帕萨特2025）线索。必须明确目标车机才在当前Wi-Fi提前广播，不据此写入成功配对历史。ctrl邀请须属于当前Network且在20秒内，控制连接前后复核；过期候选关闭。20秒是兼容性经验，不是协议常量。失败后保留广播及蓝牙，等待新邀请，不能复用已消耗邀请。

CarLetter 接收端的音频处理见[音频](AUDIO.md)。

## 奥迪

对照 CarDeck `503ed3730d5a9365272294b79306cea5fcce115b`。0x4300/0x4E0E只发送、不等ACK，EA仅对车机声明的协议尝试，缺EA回应不阻断。MMI6318可能只给热点凭据、端点靠mDNS；MMI9310的0x4301可能晚到2分35秒。保留两条发现路径，不套用大众新鲜邀请门槛。

不按品牌固定网关、控制Session ID、触摸尺寸或MFi挑战长度。3315首次403后等待新邀请成功的第三方记录，不能推广为明文降级安全或本项目已完成验证。

## 奔驰统一路径（以 MB_D-PP 为准）

参考 [mcarplay 提交 2b2ac6b](https://github.com/wangxso/mcarplay/commit/2b2ac6b67d09d5b9656fab28c8ce75495d9a1d02) 与 [issue #55](https://github.com/wangxso/mcarplay/issues/55)：同一台 2023 S 级在 iAP2 自报 MB_D-PP-3、/info 自报 MB_D-PP-4，AirPlay srcvers=320.17.7。报告显示仅视频会话正常，音频 SETUP 200 后第二次 RECORD 与随后 16–559ms 拆链相关。提交新增厂商为空或 Mercedes-Benz 时可手动开启的单次 RECORD 预设；不是奔驰全品牌认证、MFi 或蓝牙拆链策略。

直接替换原奔驰档案内容，保留 `mercedes_mbux` ID，已保存的奔驰选择自动使用新策略，列表只保留一个奔驰条目。使用现有全局单次 RECORD 路径：初始激活 → 资源声明 → 屏幕 SETUP/TCP → 音频 SETUP；音频加入、屏幕借出归还均不补发 RECORD。HyperCar 原有音频路径已满足这一点，无需新增无实际行为差异的布尔开关。MB_E-P-4 的配对后 SAP、最小能力集、屏幕在 RECORD 前建立等实验不由 MB_D-PP 继承。

保留蓝牙、本轮端点门槛和屏幕资源生命周期；尺寸及 UUID 使用协商结果，不固定为报告的 2570×1080。不引入奇瑞专用 MFi 或 disableBluetooth 行为。未引入字段，发布内容 version 与 schema 均升10；仅奔驰提高覆盖门槛，schema8/9 旧缓存不能恢复旧 bootstrap。以上是替换未跑通的奔驰实验并复用既有单次激活行为，尚无本项目 MB_D-PP 音画实测，也不能解释 MB_E-P-4 既有 SETUP 400。

### 当前奔驰媒体提议校准

`mbux_media_compat` 由奔驰档案 ID 派生。所有 MBUX 在自动或 AAC 优先时均可提议 mainBuffered，会话 SETUP 明确拒绝后可撤回。仅在本轮会话内 /info 明确声明 103/media/AAC48 且封包兼容时尝试 TCP；103 失败时确认清理后回退可用的实时 AAC。整个会话只激活一次 RECORD。

初始媒体失败且发送时尚未 READY，可等待最多 3 秒并在本轮传输就绪后对失败流重试一次。屏幕首次 SETUP、READY 重试和归还后重建共用同一个绝对会话 URI。UDP 保活立即发首包，空闲每 10 秒发送，有媒体流时停止计时。

## 旧奔驰 MBUX 实验记录

以下为 MB_E-P-4 的失败实验历史。后续 588 日志记录了该车型屏幕 SETUP 200、PCM48 SETUP 200 和持续媒体写出。该样本采用单次 RECORD、绝对会话 URI 和空闲 UDP 保活。`mbuxScreenBootstrap` 固定关闭，远程配置或缓存不能重开。

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

572固件对照以ID8.5为主线：MGU启用已实现的屏幕资源生命周期，屏幕latencyMs校准为75；远端schema12保护新默认。BMW能力声明移除未实现的处理能力，MGU/NBT移除MainBuffered声明；HEVC为可选的自绘硬编与真实输入透传，默认H.264。ID9仅保留已有75ms对照和关闭HEVC策略，认证/URI/启动顺序保持。不从固件BMW自动重连AT命令推导全局禁用ID9出站。尚未实车证明稳定性或400修复，详见[盒子调用链](BMW-BOX-CARPLAY-FLOW.md)。

573同批补充MGU能力：按`/info`明确输出声明，为VR助手回复建立type100/speechRecognition/OPUS24k主流，为导航TTS建立type101/default/OPUS48k辅助流，并实现对应资源/appState、duck/unduck及媒体切换恢复。无对应能力或已确认清理的SETUP拒绝可回退原媒体混音；资源拒绝不能绕过。不是所有车型统一套用，ID9/NBT/桌面保留原策略；本地RTSP/UDP及编解码回归通过，实车听感和互操作待验证，详见[音频](AUDIO.md)。

同批后续为明确识别的DiPlay开放能力受控辅助音频验证（WinPlay及其它桌面接收端仍不开放），并补自绘高德按UID双sink采集。DiPlay已实测两段导航独立type101，用户确认音乐降低后恢复，媒体仍PCM；该结果不更新BMW实车verified结论。

2026-11-14 ID9单变量验证：仅当 `id9_screen_bootstrap` 启用时从会话 features 移除 `hevc`，完整能力列表由14项变为13项；日志增加 `hevc_proposed=false`。保留 `mainBuffered` 等其余能力、屏幕四字段与25ms延迟、会话 `/setup` / 屏幕 `/` / 音频绝对URI及启动顺序。ID8.5和其它档案保留原能力列表，原MBUX最小能力路径仍只提议viewAreas。此变更用于验证HEVC声明是否参与400，尚未证明根因或实车修复。

本地验证：Android arm64原生测试184通过、0失败、1忽略，包含ID9只移除HEVC及其余会话字段/其它能力列表保持的回归；正式APK构建通过。0.1.0.567（10663）已通过adb install -r覆盖安装到已连接M154FF，安装后核实版本一致，保留应用数据。尚未获得本版ID9实车连接日志。

2026-11-14 656：保留mercedes_mbux ID，MB_D-PP/MB_E-P不按接收端型号分流；统一按11月13日MB_D-PP单次RECORD策略。新增connectionPolicy在远端解析、合并和引擎JSON入口规范化旧开关，保留其它远端可调参数和其它车机档案策略。schema仍为10，没有新增字段；即使schema10及之后的缓存包含旧实验开关也会规范化。11月14日MB_E日志实际也已经走默认单次RECORD，不能把其失败归因为按型号回退旧实验。
