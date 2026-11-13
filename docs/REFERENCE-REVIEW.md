# 参考实现与证据边界

更新：2026-11-09。仅记录已查看的源码/样本；接收端和手机发送端的角色不能互换，第三方成功不代表 HyperCar 已实车通过。

| 来源 | 可借鉴内容 | 边界 |
|---|---|---|
| [CarDeck](https://github.com/shaonialife/cardeck/tree/503ed3730d5a9365272294b79306cea5fcce115b) | iAP2 问候、EA、可变Session ID及奥迪记录 | 历史实验解释需按后续修正理解，不能当宝马规范 |
| [mcarplay](https://github.com/wangxso/mcarplay/tree/a3a4117ed60b864324d681e45086be594ed7233a) | CarWith七路连接、认证、加密、视频重组与IDR门控 | VW后续对照版本f1ec3ffaa1f9c11b548ec1a8d8f7afff087d8627；不照搬所有品牌特例 |
| [CarPToCarL](https://github.com/jjuugk/CarPToCarL/tree/6346bf7b63b052b2764eb19f1495891bc833c152) | requestSiri、HID语音入口及CarLife硬键 | 参考的1.5秒PTT经验未用于当前默认结束逻辑 |
| [CatPlay](https://github.com/catplay-labs/catplay/tree/fc52bac7910a07ff3b6d94712175db6013528aa9) | 资源仲裁、流生命周期、关闭顺序、附加蓝牙UUID | Linux HCI及USB包装器公告不能直接搬到Android |
| [Showcase](https://github.com/amineross/showcase/tree/34980e930a0b3245ffb6b49f22187630dac525ab) | 多流资源、媒体加密及计时/TCP诊断 | 越狱iOS接收端；不能证明宝马需同样重建时序 |
| [LIVI](https://github.com/f-io/LIVI/tree/739c889c92929babd0cce4e697672a440291e4bf) | 接收端邀请、IPv6、多屏及车辆协议 | 其主动拨号/拆ACL不应复制为手机“无画面就断蓝牙” |
| [headunit-carplay](https://github.com/riazosama/headunit-carplay/tree/ec76e30a164cbfb9734be11b0383a9780500e6d2) | Android车机栈桥接、依赖就绪及清理 | 依赖Autochips预装栈、MFi及root，不是独立手机发送端 |
| [carplayd](https://github.com/lvalen91/carplayd) | 接收端每轮邀请、Wi-Fi交接保留BT及EOF清理 | main与legacy无线实验不能混作成熟实现 |
| [xcertplay](https://github.com/shilapi/xcertplay) | 查找ctrl、发邀请和连接cafe服务 | 接收端热点/认证要求不原样套到发送端 |
| [CP Bridge](https://github.com/hatak30/cp-bridge) | 手机待机、Companion唤醒去重及保留监听 | 0.2.7原生协议未完整恢复，不能以适配列表证明宝马兼容 |
| [盒子逆向](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering/tree/b437135316275a5d3c375bdd1ceac2a6bcf1315b) | 固件、Wi-Fi Medium及内部桥接线索 | 无线接收转USB方向不同；AP禁IPv6不证明手机应禁IPv6 |
| [旧SDK镜像](https://github.com/maaiika/Carplay) | 会话失效、服务重解析及流/整会话TEARDOWN区别 | 旧附件SDK280.33.8，不能作现代宝马规范 |
| [Wi-Fi extractor](https://github.com/HaToan/carplay-wifi-extractor) | 5702/5703凭据提取原型 | 无完整AirPlay/重连；主动关闭车机入站与宝马证据冲突 |

## 已吸收的机制

持久待机与单轮会话分离、目标过滤、唤醒去重、NSD代次、固定Network租约、独立Wi-Fi iAP2、有界关闭、旧端点失效与TCP采样已落地。TAKE/BORROW不能只按布尔量处理；发送队列写完、对端TCP确认和车机显示必须分别记录。

Showcase的source/carplay_services.m可对照资源变化及VideoConfig，carplay_pair.c可对照HKDF/AAD/nonce。其接收侧丢帧、抖动缓冲与BTstack不能照搬手机；当前源码没有完整iAPSendMessage/type130实现，通用200不等于对应业务已实现。HyperCar不为追赶参考项目恢复已删除的视频重建实验。

## 固件线索与宝马成功样本

解密ARMiPhoneIAP2有CHudiAP2Medium_WiFi、BT/USB Medium、Switch to Wi-Fi iAP2、wirelessCarPlayTransportComponent等实体。AppleCarPlay接收侧有iAPSendMessage和内部transport通知接口。它们证明存在无线iAP2对象，不证明可跳过本轮邀请；内部消息编号102不能当作CarPlay stream type。

固件只作静态分析，未运行或刷写。其二进制/符号/哈希保存在原始证据目录，关键宝马样本及时间线单独见[盒子证据](BMW-BOX-CARPLAY-FLOW.md)。没有找到可直接认定宝马缺失的“二次上车专用iAP2报文”。

## 使用原则

先确定角色、版本、实际报文及验证范围，再移植有限机制。看到符号名不等于恢复完整调用链；接收端发往手机的命令不能反向照搬；未获授权的第三方接口名称不等于可调用能力。修改配对、分辨率、加密或重连门槛必须有本项目证据。

## 可复核版本

- CatPlay：fc52bac7910a07ff3b6d94712175db6013528aa9。
- Cardeck：503ed3730d5a9365272294b79306cea5fcce115b。
- xcertplay：1298fb35e29ee30e7bd849cb0e751b52aa22a13f。
- 无线盒子逆向：b437135316275a5d3c375bdd1ceac2a6bcf1315b。
- SDK 镜像：482d3d7ea289317cc827f9df29734daad49a8e17。
- extractor：42dd48aa801527256963d619579ed40f6b9fd4e2。

来源没有覆盖到的内部逻辑不作肯定描述；单次反编译或看到符号名不等于已经恢复完整重连状态机。

## BOX-OFF 8.1.1 APK 与奔驰媒体拒绝对照（2026-11-09）

样本：用户提供的 `BOX-OFF-8.1.1-carplay.C-100022-debugfalse-r8.apk`，SHA256 `e9c03b4de4ddde00b6a7ce4602a6212e9b28af4f41635866bf581703644ef462`。BuildConfig 为 `APPLICATION_ID=com.baidu.carlife.xiaomi`、versionCode=100022、VERSION_NAME=8.1.1-carplay.C；文件名含 debugfalse，但 BuildConfig.DEBUG=true、BUILD_TYPE=debug，不能由文件名判断运行行为。该样本是 APK 静态分析，尚无它在同一奔驰上的 RTSP 抓包。用户已确认网友实测车机有画面、可操作，仅无声音：可作为用户反馈支持的奔驰屏幕/触控成功样本，不能作为音频成功基线；具体启用的档案、认证分支及实际出站报文仍待日志确认。

反编译材料保存在仓库外 `/mnt/d/devtools/hypercar-diagnostics/box-off-8.1.1/`。Java/Kotlin 主实现位于 `decompiled/sources/io/github/iamr0s/carplay/`，主要协议不在 native 库。JADX 1.5.6 全量反编译报330项错误，其中主会话协程未能结构化还原；另以 simple 模式输出 `TestScreenSession-simple.java`，按实际调用、分支及诊断字符串交叉核对下表，不把反编译的 throw 占位视为 APK 的真实失败逻辑。未将 APK 或第三方源码加入仓库，未安装该 APK。

以下对照的 HyperCar 基线为 `e9773bb`，实施状态见[奔驰档案](HEADUNIT-PROFILES.md)。

| 项目 | APK 中的可复核行为 | 基线 HyperCar / 本轮奔驰日志 | 价值与限制 |
|---|---|---|---|
| 配对后的认证 | `TestScreenSession-simple.java:6525` 调用 `MfiAuthSetup.authenticate`，发生在初始SETUP前；有pairing时仍尝试POST `/auth-setup`；仅已配对且404/501允许跳过 | `rust-core/src/airplay.rs` 仅box_bootstrap开启时执行auth_setup；奔驰本轮false，日志没有该请求 | 值得隔离验证的认证阶段差异；pair-verify成功不证明另一个阶段必然无用，也不能凭静态分析断言奔驰强制要求 |
| 初始能力 | `Ct5NegotiationProfile.initialSetupFeatures()` 默认空数组，modern-base仅`viewAreas` | HyperCar声明14项，含hevc、mainBuffered、altScreen、sessionManagement等 | 可比较实际协商出的能力；不能直接认定某项是400原因，不应全车型统一删减 |
| 建链顺序 | `TestScreenSession-simple.java:5872/5964/5974/6026/6028`：声明modesChanged → 屏幕SETUP → 连接dataPort → RECORD | HyperCar先RECORD，再声明资源和屏幕SETUP；本轮媒体请求被400拒绝 | 直接相关的顺序差异；APK type130失败有继续屏幕流程分支，不能将type130一概列为硬前提 |
| 初始资源 | SessionModes默认screen=1/1、audio=2/2，snapshot含permanentEntity；主路径使用该snapshot | 本轮HyperCar声明screen=1/1、audio=1/1 | APK先保留车机音频归属，后续独立申请音频；不是“完全没有音频实现” |
| 屏幕参数 | type=110、非零正连接ID、latencyMs=100；显示uuid仅在车机返回String时加入 | 本轮type=110、latencyMs=25，uuid来自/info；请求字段其余基本相同 | 延迟是明确差异；当前样本已取得String UUID，不能声称APK不发uuid，也不能据此补造分辨率字段 |
| 音频参数 | RtspAudioControl.setup使用相对URI `/`，输出latency=100、spf=采样率/200、sr=采样率、ct=1；按/info的type/audioType/audioOutputFormats选PCM格式 | HyperCar使用带方括号IPv6绝对URI，固定PCM48k双声道、spf352、latency0，无sr/ct；两轮均400 | 存在独立音频兼容性差异。APK在48k时spf240；是否被奔驰接受必须由APK日志确认，不能因其“能连接”便宣称音频参数正确 |
| Session头 | RtspConnection保存成功响应Session头（去掉分号参数），后续请求带回 | HyperCar RtspChannel未自动保存/带回该头 | 可完善的协议兼容边界；现有HyperCar日志没有足够响应头证据，不能断言本车确实要求 |
| UI请求 | 有处理车机requestUI/changeModes的路径；初始主链在屏幕SETUP之前没有无条件主动requestUI | 本轮requestUI计数0，屏幕SETUP400 | 不能把APK的成功解释为“补一个最高优先级requestUI”；命令方向和实际路径需分清 |

### 与11月9日奔驰日志的约束

输入 `hypercar-log-20261109-080432.txt`，导出版本0.1.0.523，实际车机为MBUX00081、所选档案mercedes_mbux。08:02:45.566及08:03:29.668屏幕SETUP400空体；08:02:45.672及08:03:29.725音频SETUP400空体。会话内/info已返回1624×1278和显示UUID，RECORD成功、feedback200；CarWith后续实际有SPS/PPS/IDR，但最后PIPELINE视频TCP帧0、音频包0。首次控制连接在RECORD附近被车机关闭，与后面控制保持但媒体均拒绝的两轮分开看。

本次从完整TX_PREPARED_PLAINTEXT分段重组四个资源报文，用Python plistlib独立解码，确认出站speechMode均为-1；因此此前负数编码修复已实际进入这个样本，不能再将同一个编码错误当作本次400的解释。资源回复是200加空字典，没有明确status；本地resources=true、screen_phone=true不等于车机已授权或显示。Shizuku=false是独立环境限制，不能覆盖已明确收到的屏幕/音频拒绝原因。

### 建议验证顺序（尚未实施）

1. 用户已确认APK在奔驰有实际画面和触控，优先对照屏幕成功路径；继续取得同一车机的APK日志，以确认实际profile/认证路径、屏幕SETUP应答、Session头及出站报文。静态代码中的可选分支不能直接等同于该成功会话实际执行的分支。
2. 优先在奔驰试验档案单独验证配对后auth-setup。已有HyperCar解析/解密交换代码不等于APK的签名验证与legacy AES密钥生命周期；不能为复用而全局打开BMW box bootstrap，它还改变其它业务分支。
3. 再隔离验证“媒体SETUP在RECORD前”的顺序；最小能力声明、屏幕100ms延迟分别验证，避免同时更改而无法归因。保留原车型默认行为及当前资源代次/取消边界。
4. 画面建链成功后再独立校准音频：能力选择、URI、sr/ct、spf与延迟。该APK无声反馈没有日志，无法判断是资源、SETUP、音源采集、RTP或车机播放问题。
5. 如补Session头支持，按当前连接保存并清除，禁止旧连接Session泄漏到重连；先补充响应头诊断，不能凭缺少日志就推断它是本轮根因。

### BOX-OFF 再次上车与宝马重连复核

进一步沿接入层回调检查，不能只看ConnectionService单轮退出就认定没有自动重连：APK的Provider模式有上层重试。证据仍为同一APK静态代码，未取得宝马实车成功记录。

- `provider/ProviderBluetoothReceiver.java:24`：监听ACL_CONNECTED，要求Provider已启用且CarlifeConnectService实例仍存在；随后按已选peer筛选，不是单凭广播冷启动整个接入层。
- `com/baidu/carlife/service/CarlifeConnectService.java:699/725/739`：连接结束回调scheduleRetry；保留host Surface，按2/5/10/30/60秒安排最多五次重试；目标车蓝牙重新连接时触发restart。`provider/ProviderRetry.java`在ready后持续至少120秒再遇失败时清零失败次数，避免短暂成功无限重置重试预算。该机制不等同于休眠120秒才重连。
- 同文件`connect():597`再次启动ConnectionService，传peer、wirelessExperiment=true、continuousWait=true。底层既可主动RFCOMM，也可持续等车机入站；同步实验的“第二轮”明确等待NEW car-initiated RFCOMM，是同步测试，不是已验证的宝马二次上车协议。
- `ConnectionService.java:973/988`的无线入口仍先有BluetoothSocket及WirelessBootstrap，等待本轮无线参数（120秒超时），随后CarNetwork.join。`network/Ct5ExistingWifi.java:110`按本轮SSID、receiverId/BSSID、本机IPv4和到车机地址的路由匹配复用已有Wi-Fi，避免重复requestNetwork；这发生在已有本轮参数之后，不是“只连上保存Wi-Fi便主动Wi-Fi iAP2”的独立入口。
- Wi-Fi丢失时`ConnectionService$runWireless$2$lost$1.java:67`关闭屏幕、发现和蓝牙socket并结束本轮，再由Provider决定重试。稳定PhoneIdentity持久化且不自动轮换配对密钥。运行期接管就绪后该APK也有主动关闭引导RFCOMM的分支，不代表必须断HFP/A2DP。

对HyperCar的价值：复用正确车机网络、目标ACL唤醒、有界退避、稳定身份、旧会话资源清理均可对照，目前这些机制已有对应实现；其中HyperCar还存在已配对已知Wi-Fi主动建链入口和按车/Network隔离的失败退避。APK没有提供已验证的“宝马无蓝牙邀请时激活HMI”专用报文。不能因奔驰有画面就把609的奔驰认证、能力及屏幕顺序策略全局套到宝马，也不恢复已删除且实测无效的视频恢复实验。后续宝马无画面仍需比较相同车机上的真实SETUP/RECORD/资源回应和视频写帧，判断认证/顺序差异是否适用。

## CarPToCarL 麦克风与按键复核（2026-11-13）

固定源码：[7c55ac0a15a59dae322d16fed8b07dc50f8c3011](https://github.com/jjuugk/CarPToCarL/tree/7c55ac0a15a59dae322d16fed8b07dc50f8c3011)，本轮读取 main 并与当前 HyperCar 对照；仅静态审查，未改运行逻辑、未构建或安装，也未新增实车成功结论。

### 麦克风：两条不同输入机制

CarPToCarL 的旧 `probeMicrophoneInput(input=true)` 已由 `MIC_PROBE_ENABLED=false` 停用，不能把它当成当前全部实现。当前 `MIC_UPLINK_ENABLED=true`：SessionController 在主音频 SETUP 前绑定 MicUplink UDP，把本地 dataPort 附在现有音频流中，仅在该候选 audioInputFormats 非零时声明（零或缺失不声明）。runAudioStream 对应 AirPlayClient.kt:2527；MicUplink 从相同 streamConnectionID 派生 Input 密钥，接收 RTP、认证解密，把负载按大端 PCM16 换为小端并允许导出最多20秒 WAV；音频流重建时 updateKey 重置格式探测。未发现该 PCM 写入 CarLife VR 的实现，CarLifeHuClient 的语音硬键仍注明使用手机麦克风。

HyperCar 当前仅接受 /info 明确声明的 type107/speechRecognition/Opus16k 输入，独立 SETUP、独立连接 ID、绑定车机网络、认证解密、Opus 解码；CarWith 实验已进一步将其转换为16k单声道PCM16，经受限队列和加密 VR 0x58001 上行，结束0x58009。两条机制不能凭静态代码断言哪条适用所有实车。最有价值的候选是：针对明确声明主音频输入能力的接收端增加共享音频流收音策略，复用现有 CarWith PCM sink；保留 type107 路径，先取得目标车机 SETUP/RTP/格式证据，再决定是否启用。不能将“输入位非零”直接等同某个采样率/声道/PCM 格式已获支持，也不能把接收端 xcertplay 的规则上升为所有原厂车机规范。

MicUplink 的 RTP 头解析支持 CSRC/扩展头；HyperCar Stats::open 固定 wire[0]==0x80、12字节头，若实际107回传包含扩展会被丢弃。这是值得完善的兼容及诊断点，目前没有目标车机发送扩展头的证据。格式探测虽使用 Poly1305 认证确认，但它的多种 nonce/AAD 候选和默认 PCM 假设不能直接套到 Opus 流。保持我们现有来源校验、重放/序号限制、会话代际隔离和按需收音；不照搬自动收音/WAV落盘。

### 按键：明确缺口与已具备能力

1. **iAP2 语音键缺口**：CarPToCarL 将 0x6802 的 Consumer 0xCF 与 requestSiri 接入同一 VoiceKeyRouter。HyperCar Rust AirPlay Consumer HID 已支持0xCF按下/释放及共享去重，但 Kotlin Iap2MediaSession.ConsumerReport.supported 不含0xCF，且 HyperCarRepo.onIap2MediaKey 仅派发 MusicBridge。应补齐 iAP2 语音边沿到已有统一语音路由，保留组件声明/认证/Report ID/传输代际校验，避免媒体和语音各自唤醒一次。
2. **识别确认与状态诊断**：CarPToCarL 解析 MD_MODULE_STATUS=65574（0x10026），观察 module4/VR 的状态变化。HyperCar 当前通过 RECOG_START/STOP 控制收音，但未解析该模块状态；可用于区分“VR_START已排队/写出”“引擎进入语音态”“开始接收车机PCM”“实际识别”，模块状态本身也不是识别成功证明。其源码把VR=0直接归因硬键无效过强，不能照搬这个断言。
3. **保持现有严格HID解析**：HyperCar 已具备按组件、Report ID、位偏移、Usage范围和Push/Pop解码。CarPToCarL 的 VirtualIap2Link 仍以“大于8字节的首参数”猜 StartHID 描述符，缺报告参数时回退任意短参数，并保存单个描述符；ConsumerKeys也较简化。我们应借鉴双通道入口和阶段诊断，不回退为任意参数/字节猜键，不把0x04固定当下一曲。
4. **不照搬长按结束与旋钮假设**：参考实现按1.5秒长按释放发VR_STOP，这只是其策略；HyperCar 已有按业务识别结束及20秒收音上限，不改成按按键时长结束。源码的 KNOB_UUID_HEX 常量及“排除旋钮误判媒体键”不等于已完成旋钮到焦点导航的通用映射，不能作为现成方案移植。

建议实施顺序：先补 iAP2 0xCF 与跨入口去重回归，再加 CarWith VR 状态观测，随后按目标车机证据实现共享音频流输入候选及必要的 RTP 头支持。保持其它车型默认媒体建链与收音开关不变。

后续实施：已按用户要求补齐 iAP2 0xCF 到统一原生语音入口，并完善 type107 RTP 的 CSRC/扩展头定位。上文缺口为本轮实施前审查结论；主音频流共享输入及 VR 模块状态观测仍未引入。实现与验证见 INPUT-AND-VOICE、VALIDATION。
