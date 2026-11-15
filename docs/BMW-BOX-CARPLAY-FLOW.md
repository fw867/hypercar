# 宝马盒子流程证据

## BMW标记调用链与ID9参数对照（2026-11-15）

本节继续使用下文同一固件哈希，静态地址仅适用于该样本。逐文件扫描rootfs的1993文件/611个ELF，只有xiap2包含`vehicleCfgIsBMW`符号；xiap2可执行节ARM B/BL、重定位、函数地址取用模式均未找到该导出函数的静态调用者。0x55f28虽然字面值为0x8cac0，实际由0x55ef8与PC相加得到全局地址0xe29c0，不是函数指针。不能因导出函数存在便认定它控制BMW认证/SETUP；动态/其它版本行为未被这次扫描排除。

| 实际路径 | 确定行为 | ID9处理结论 |
|---|---|---|
| vehicleCfgSave：0x8d614/0x8d774→0x8d650 | 根据Mini/BMW名称分别写0x8200/0x8100至记录+134；vehicleCfgIsBMW的算式只接受这两个值并比较12位蓝牙地址 | 是品牌记录，不区分ID9/IDC23代际 |
| 0x63730→vehicleCfgIsAccConnectOnly(0x8cba8)→bt_succ(0x4f0f0) | 按匹配地址检查记录+134的符号位；true时先AT-LP再AT-NL | 是连接策略分支，未查到它调度认证或SETUP |
| 蓝牙模块fscsdkbw123：NL handler 0x35720→app_disallow_rec(0x3bc3c) | 配对记录+0x1d置1；自动linker的0x3bebe及扫描完成0x3c7cc读取，禁止该设备进入自动重连候选；显式地址btAPI_IAP_Connect仍可调用lib_serial_port_connect | 不等价于Android从启动起关闭所有出站iAP2。当前ID9的allowOutboundIap2不能仅据此改false；更精确对应是已成功连接后的按设备自动重连抑制 |
| pair-verify成功→0x2c8c4 SAP→0x257a0会话SETUP | 已确认配对成功后继续auth-setup；SAP入口看会话状态，未见此路径按BMW或ID9分流 | 与ID9不发SAP有差异，但ID9通用BMW档案已有SAP成功后仍媒体400的反证，不能宣称补SAP即修复 |
| 0x25b38–0x25bcc能力构造 | 固定uiContext/viewAreas/cornerMasks/focusTransfer/altScreen/enhancedSiri/hevc七项；enabledFeatures/hevc在0x2d830驱动会话标记，再于0x39e28传入视频对象 | 与本项目能力列表不同；不因此恢复ID9 HEVC或同时删能力 |
| /info完成回调0x33490→RECORD 0x2a9e4 | RECORD无正文；屏幕由独立资源回调carplay_video_start→0x3ad34→0x3a998触发 | 不能由主链静态调度认定所有会话屏幕必晚于RECORD，也不足以推翻ID9已有独立顺序 |
| 屏幕SETUP 0x3ab7c–0x3abb8 | type110、显示UUID、连接ID与latencyMs四字段；0x3abac明确mov r2,#75后写latencyMs | 当前仅ID9将25ms对齐75ms作单变量参数对照；默认25ms及旧MBUX实验100ms保持，URI/认证/HEVC/资源与RECORD顺序不变。尚未证明能消除IDC23的400 |

此次实际修改是ID9屏幕延迟单变量，非“恢复盒子ID9专用分支”。原生实际请求测试先观察25!=75失败，修改后验证75及四字段、type110、正连接ID，保留其它档案25/100和失败清理回归。准备安装时检测到既有571已由其它工作提交为f7f574c，本轮延迟改动仍未提交，故按项目约定升级572/10668；本轮不提交、不推送。

## 方向盘与Now Playing固件复核（2026-11-15）

对照原固件SHA-256 `4b7dc91d4ee353a9f1f1acc6ae2c0e69b94b598355379545362856e6a27dff8e`中的xiap2，结合已留存成功日志。以下地址为该固件虚拟地址，不能套用其它版本；原日志每包只保留前16字节，未恢复完整描述符/订阅正文。

| 项目 | 实际日志与静态路径 | 当前571对照 |
|---|---|---|
| 方向盘媒体HID已启用 | 52.894收到WLAN 6800，解析3个Consumer变量键B0/B5/B6，创建虚拟HID，分别对应播放/下一首/上一首 | 已按组件、描述符及变量位解码相同Usage；当前MGU-55日志组件1明确通过function=1过滤并成功注册，未发现被该过滤拒绝 |
| StartHID注册 | 0x626d8–0x62740读取参数0组件、1/2的16位值、4描述符；0x64f94–0x64fe0调用HidSim_Create并直接返回 | 参数0/4一致。该固件路径无6801通用ACK、无额外媒体启用命令；不应为此补造DeviceHIDReport |
| 按键报告 | 0x64504–0x64558取6802参数0/1，0x654b0转HidSim_Input；0x8a6a8按描述符位偏移/宽度取值 | 当前按组件0、报告1及描述符解析相符；盒子该段成功日志未见实际6802边沿，不能据注册宣称方向盘切歌实测成功 |
| Now Playing已启用且有收发 | 52.896收到5000、52.897发5001；0x6293c置订阅标志，0x62fcc发送初次回复，0x5c3d4持续更新，0x6457c停止订阅 | 当前认证后订阅/更新/停止机制一致；571已修正非空订阅的逐组字段筛选 |
| 核心媒体字段 | 固件组0：title=1/string、duration=4/u32、album=6/string、artist=12/string、artworkId=26/u8；组1：status=0/u8、elapsed=1/u32、appName=7/string | 与571一致。getSubPara按BE长度+ID嵌套TLV读取，字符串strlen+1含NUL；未找到需再次改字段或编码的依据 |
| 封面文件会话 | 52.898起setup→start→首尾块→success；0x5acc0构造id/命令4/u64大小/u16类型2 | 当前布局及会话内发送一致。该日志封面大小为0，只能证明文件握手，不能证明有效图片展示；不照搬发送空封面 |

结论：两项在盒子中确有启用与处理路径。571现有协议实现与以上可核实路径一致，本轮不追加未经证实的启用报文、不放宽组件过滤、不改大众已验证媒体路由。盒子通过Linux虚拟输入注入按键，本项目通过Android MediaSession/AudioManager；方向盘实际报告是否进入手机及播放器是否执行，仍须用571新诊断做端到端验证。此前571的NowPlaying订阅修正保留；固件对照不构成本次EOF原因或实车修复成功的证明。

## ID9 成功样本对照（2026-11-12）

依据：用户提供的 `CarPToCarL-log-20261112-122836.txt`，CarPToCarL **1.2.37**，接收端 `IDC23-G68-BMW-26-07`，用户确认可以连接。另核对上游当前源码提交 `7c55ac0a15a59dae322d16fed8b07dc50f8c3011`（发布1.2.38，晚于日志版本），源码用于解释实现，日志用于确认本次真实执行；不把上游当前源码等同于生成日志的精确源码。HyperCar以当前工作区为准，`airplay.rs` 的主屏选择调整是既有未提交修改，本轮没有修改协议或安装新包。

### 已证实的成功路径

12:20:41.680收到新鲜0x4301：自带热点、IPv6及7000端口；12:20:42.255已绑定车机网络，端点完整便直接配对，不另外等HTTP ctrl邀请。12:20:43.175 pair-setup及verify完成；会话SETUP及会话内/info后，得到主屏2880×960/type110，另两块type111为附屏。初始modesChanged后申请主屏，三个hidDevices分别设置mode0；12:20:43.304主屏SETUP成功，43.319视频TCP连接，43.396才RECORD。Wi-Fi iAP2继续识别与MFi交换，44.902控制会话就绪后协商音频，44.970 type102/AAC-LC成功，45.023为后加入音频再RECORD。后续有持续视频和音频发包，12:21:17由用户主动停止。用户报告显示成功；本地发送计数和rfps只作分层证据，不直接等同于解码或实际出声。

### 改善优先级

| 优先级 | HyperCar当前行为 | 样本差异与建议 |
| --- | --- | --- |
| P1：能力驱动音频 | `airplay.rs::build_audio_setup`固定type100、media、PCM48k/0x8000、spf352；`audio_out.rs`仅支持PCM | 日志audioFormat[4]明确只有type102/media/0x800000的AAC-LC，列出的type100均无PCM48k。应读取audioFormats/audioLatencies，增加AAC-LC 48k立体声、spf1024的编码与RTP封装，保留已声明PCM的既有接收端路径。这个缺口有能力报文支持，不能用改延迟或重试补足。 |
| P1：按实际接收端选择启动顺序 | BMW路径RECORD→资源声明→屏幕SETUP/TCP；屏幕先建仅在奔驰分支 | ID9本轮为资源声明/主屏申请→屏幕SETUP/TCP→RECORD→首帧；适合为IDC23能力/型号增加独立启动策略，不能因统一显示名BMW就把MGU和IDC23强制走同一序列。先验证控制和视频阶段，再验证音频，不能由成功样本反推当前失败一定由顺序导致。 |
| P1：认证阶段与音频激活边界 | 宝马bootstrap在会话SETUP前必发/auth-setup；视频就绪后立即音频SETUP，不等Wi-Fi iAP2认证阶段；新音频流后不补RECORD | 样本成功链路未见/auth-setup，使用HomeKit配对及随后Wi-Fi iAP2/MFi；建议区分SAP与iAP2认证，不把额外SAP套到所有宝马。音频可在iAP2就绪后有界等待启动，不能阻塞首帧。后加入音频的RECORD是否必需应按接收端验证，本样本接受；上游另有重复RECORD敏感接收端策略，不全品牌强加。 |
| P2：多屏和安全区域 | 工作区优先type0否则第一项；display_layout仅读取根级viewAreas | 本车主屏type110，副屏type111；若列表顺序变化会有选错风险。应优先识别主屏，兼容type0旧口径，并校验有效尺寸/UUID。读取选中display内嵌viewAreas和safeArea：画布2880×960、视区2880×840、安全区2860×836、原点10,4，不能把画布和可绘区域混为一谈。 |
| P2：HID启动 | 能解码输入，未找到主动hidSetInputMode发送 | 样本对/info列出的触屏、按钮、旋钮UUID分别发送hidSetInputMode(mode0)。补实际声明设备的输入初始化，不能用蓝牙0x6800是否出现判断ID9有没有触摸能力。它是输入兼容改进，尚不能认定为无视频的根因。 |
| P2：接收统计与冻结诊断 | /feedback主要检查RTSP200，未解析statsDictionary逐流rfps；没有明确的进程心跳长间隔恢复入口 | 本车返回statsDictionary下按streamConnectionID索引的rfps、stty、uuid、ltnc、网络RTT。建议区分本地出帧、TCP发送、车机前级收帧与显示状态，并补音频播放进度字段；进程长时间停顿后核实网络及通道再恢复，不能只有重试。 |

### 已有能力与不应照搬的部分

- HyperCar已有新鲜0x4301直接推进、链路本地IPv6 scope、网络绑定、配对验证、会话内/info、UDP与屏幕keepAlive、按车机keepAliveSendStatsAsBody切换统计、Wi-Fi iAP2业务回应及重试预算；不把这些写成“完全缺失”。
- 本轮主屏UUID来自/info的做法已具备；工作区已有显示选择修改不能冒充已经安装验证的修复。更换配对身份不是本次音频能力缺口的解决方式。
- 同文件后续12:25–12:28多次恢复仍失败，有多次长时间心跳停顿日志，且12:26:11出现bindProcessToNetwork=false却继续宣称Wi-Fi就位。因此不能据此认为“连续两次失败就清配对记忆”、自动重连或忽略绑网失败已证明可靠。原有手动停止10分钟暂停与身份保持要求继续保留。
- 手填真实蓝牙MAC是该样本前提，不能据此恢复A2DP强断、主动清配对、所有型号统一补RECORD或盲目并发蓝牙/Wi-Fi会话。

源码对照：[上游AirPlayClient](https://github.com/jjuugk/CarPToCarL/blob/7c55ac0a15a59dae322d16fed8b07dc50f8c3011/app/src/main/java/io/github/shaonialife/carptocar/airplay/AirPlayClient.kt)、[上游SessionController](https://github.com/jjuugk/CarPToCarL/blob/7c55ac0a15a59dae322d16fed8b07dc50f8c3011/app/src/main/java/io/github/shaonialife/carptocar/SessionController.kt)、[上游CarDisplayGeometry](https://github.com/jjuugk/CarPToCarL/blob/7c55ac0a15a59dae322d16fed8b07dc50f8c3011/app/src/main/java/io/github/shaonialife/carptocar/airplay/CarDisplayGeometry.kt)。上游文件保存于工作区外hypercar-diagnostics/id9-20261112，仅作静态对照，没有执行。

更新：2026-11-07。这里的盒子是日志样本中的手机内容源；不要与“手机连盒子 Wi-Fi、盒子 USB 连车机”的无线接收器混为一谈。

## 样本

分析过 blueware、xiap2、zlink、zsv 的两组完整连接及一次二次上车日志。文件时间与手机墙钟不直接等同，比较相对时序。

原固件样本大小 110840394 字节，SHA-256：
`4b7dc91d4ee353a9f1f1acc6ae2c0e69b94b598355379545362856e6a27dff8e`。

二次上车 xiap2 样本 SHA-256：
`bec8f90cf70e8e37376ea43d67521706e14135d57ad2249ba8d43b2352a0707c`。
原始文件不随仓库分发，不能依赖开发机下载目录作为长期文档链接。

## 二次上车已观察顺序

| 相对时间（秒） | 事件 |
|---|---|
| 48.695 | 车机发起蓝牙连接，使用已有配对 link key |
| 49.419 → 49.470 | 5702 请求 → 5703 凭据 |
| 49.772 | Wi-Fi 连接 |
| 49.801 | 控制服务发布 |
| 49.990 | 发现 AirPlay 服务 |
| 51.598 | 收到控制连接邀请 |
| 51.606 | pair-verify，本次没有重新 pair-setup |
| 52.102 → 52.231 | SETUP → RECORD |
| 52.327 | videoSetup |

因此该样本的二次上车仍有车机发起的蓝牙与新邀请，不能推导成“只要自动连 Wi-Fi 就直接恢复旧 AirPlay 会话”。

## 协议证据

蓝牙 Detect、链路协商、认证、识别、Wi-Fi 凭据与 AirPlay 依次推进；Wi-Fi iAP2 建立独立链路。两次完整样本的 40 条非 ACK WLAN 消息 ID 顺序一致，但部分日志仅打印前 16 字节，不能声称所有字段完全一致。

抓到的 Wi-Fi iAP2 承载为事件 /command 的 iAPSendMessage；出站走控制通道。资源变化经过模式处理再回报 modes，屏幕和音频归属可变化。某样本 videoSetup 在 39.605 秒、Wi-Fi 认证 AA03 在 39.853 秒，证明该样本没有把后者作为视频的硬前置。

独立链路参数、认证顺序和显示尺寸只能证明样本行为；不得把某次 Session ID、端口或 2732×794 尺寸硬编码成所有宝马规范。auth-setup 在样本出现也不证明所有接收端强制需要它。

## 隧道与界面请求

Wi-Fi链路通过事件 /command 的 iAPSendMessage 交换Detect/SYN与CSM，不能把本地RTSP200当成iAP2完成。样本Wi-Fi链路约52.356秒发SYN、52.361秒应答；它支持等待车机发起，不能据USB/iAP2-NCM脚本推导手机必须主动开type130。PlayPort的type130是特定承载线索，不能当作宝马此样本缺失的必要步骤。

xiap2固件中changeUIContext分支位于0x46f28起：读取params.url、记录日志，随后跳通用应答，未调用ResourceManager。通用分支回空体RTSP200（0x45f44起、0x46268置长度）。requestUI则在0x46564调用ResourceManager_RequestUi；成功文本53.076收到request ui、53.077更新controller/controller。固件分析证明有应答实现，但原文本没有逐条线上应答，不能声称捕获了完整报文。

CarDeck与mcarplay也先应答事件，再分发命令；未处理changeUIContext不等于没回RTSP200。当前HyperCar观察其上下文，不为任意URL虚构业务或自动抢屏。

## “已连接但点击不进入”证据边界

2026-11-03成功样本有新一轮蓝牙和ctrl邀请；11月4日若干主动Wi-Fi会话无新邀请。失败期间仍可看到本地claim、RTSP200、视频配置及持续TCP写帧，不能写成手机从未推流，也不能凭此证明车机已解码显示。更改拉伸、自绘来源仍出现同类入口问题，不能单独归因于拉伸。

requestUI可能由用户点击或车机重试产生；后续借屏拒绝不能解释此前已出现的入口失败。两项旧恢复实验测试无效已删除，当前画面资源联动及诊断见[视频](VIDEO-STREAM-REVIEW.md)，不保留旧实验操作指引。

按同一轮比较：首次车机激活/邀请、资源声明、屏幕SETUP、真实源配置与IDR、后续业务和断开。盒子成功不代表本项目已经复现实车全部行为。

## 572：以 ID8.5 为主线的档案与 HEVC 校准

商家声称适配 ID6–ID9.5，用户实测盒子在 ID8.5 较稳定；本轮以 MGU/ID8.5 成功日志和固件为依据，ID9 仅兼容支持，不把全系销售声明当作各代流程一致的证据。

- MGU 启用已有屏幕资源生命周期：收屏暂停并释放旧发送器，允许的 requestUI 按资源代次重建，保留认证和单次 RECORD 顺序。远端档案 schema 升12，旧缓存不能覆盖新默认；NBT/ID9 的启动顺序保持。
- MGU 屏幕 SETUP 的四字段不变，latencyMs 从25校准为固件明确的75（xiap2 `0x3abac`）；NBT与通用路径仍25，MBUX仍100。ID9沿用本批已有75ms对照。
- BMW 移除无处理器的 sessionManagement/logTransfer/vehicleStateProtocol；MGU/NBT 不再把现有 type100/type102 UDP 音频声明成 type103 MainBuffered。ID9原有缓冲音频独立开关保留。PCM节拍修复不依赖 AAC。
- HEVC 默认关闭，设置可选“HEVC优先”。只有本轮 SETUP 明确接受 hevc 才允许 HEVC 帧；ID9继续关闭该声明。自绘以手机硬件 Main/Surface 编码为前提，启动失败重建 H.264；首页与地图逐帧带实际 codec，跨会话能力失效，编码偏好改变重建显示编码器。
- 固件 `0x3bb80`、`0x3bca0..0x3bd88` 支持 VPS/SPS/PPS→hvcC→94字节 hvc1 前缀；HEVC config 标志 `header[6]=0x1e,[7]=1`，frame `[5]=0x10`。复用既有帧加密和持续 nonce。hvcC时间层字段依据真实 SPS，不照抄盒子的固定0x0b；H.264既有线上格式保持。
- CarWith 只透传真正的 HEVC：通过完整参数序列确认格式、按 HEVC 图片边界组帧、同步19..21补齐当前参数。没有找到请求 CarWith 产 HEVC 的协议字段，因此未添加猜测字段，也不做实时转码。接收端未允许HEVC却收到该源时明确结束并提示选择支持的源/会话，避免静默黑屏。

真实手机探针已用2732×794 Surface硬编产出 Main HEVC，并将同份参数/关键帧交硬件解码器得到一帧。脱敏的纯色测试输出保存在 `rust-core/tests/fixtures/hevc-phone/`，用于参数解析、hvc1、真实AAD/nonce和IDR实包回归。该验证是短片编码/解码，不证明持续60fps吞吐或车机显示成功。运行期 MediaCodec onError 仍为诊断，不宣称已有动态 Surface 重建恢复。
