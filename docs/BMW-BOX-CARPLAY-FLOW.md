# 宝马盒子流程证据

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
