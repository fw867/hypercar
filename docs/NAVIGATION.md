# HUD / 仪表导航

## 首版范围（2026-11-08）

572支持两类入口：BMW iD8.5档案下的“自绘UI＋默认高德通知”和“CarWith结构化导航消息”。仅当前连接会话启用；其它车型不发送本功能的iAP2导航更新。CarWith协议不标识地图包，来源标为CarWith，不借用自绘UI的默认地图设置判断其地图来源。视频投屏与HUD导航是两条独立链路。

当前为**实验版**，不是完整高德导航SDK集成，也未完成宝马实车HUD验证。自绘UI的通知入口需要已有“通知使用权”，以及高德实际发布包含距离和转向的持续导航通知；CarWith入口消费经认证解密的0x10030，不依赖通知文字。未收到、内容不可识别或高德前台模式不发布此类通知时，无法输出导航箭头；不能把 iAP2 订阅成功或 TX 日志视为 HUD 显示成功。

## 数据入口与已核实的限制

核对手机安装的 com.autonavi.minimap 17.02.0.2010：DEX 中未找到 Auto 标准广播 SEND/RECV 的字符串实现，因此未把车机版广播协议直接套用到手机版。

已导出的 UCarMapAppService 提供连接/状态接口；真正的导航回调由高德向 ucar.service.MAP_SERVICE 服务发起，服务发现会校验系统/合作方签名。AmapLinkService 的导航数据收发还受 API Key 校验、令牌及加密格式约束。普通 App 仅绑定服务不能证明可以获取导航；本版不接管这些接口，不修改高德应用。

首版复用 MusicNotificationListener 的现有授权和生命周期，不另建常驻服务。仅处理高德包的持续通知，识别明确的“300米后右转…”、“1.2公里后靠左行驶…”等提示。普通“导航中”、巡航、广告、总里程和无法识别的内容不会推断为转向。支持明确的到达与重规划文字，取消/移除/替换通知后清理。当前**不保证覆盖高德所有文案、定制通知 RemoteViews 或其它语言**。

只输出通知里确实存在的距离、方向和指令。道路结构字段、剩余全程、ETA、车道、环岛出口角度、交通侧等未取得的数据不补零、不猜测。未实现完整多路口预览；列表只提供一个当前路口。解析器与结构化模型分离，后续拿到受支持的高德结构化出口后可替换采集层。

## 车机订阅及发送

接收 0x5200 / 0x5203 作为开始/停止订阅，严格检查 TLV 完整性与组件 ID；保留车机提供的 u16 组件编号，最多四个订阅。Wi-Fi 在认证 READY 前只保存订阅，随后由拥有链路引擎的工作线程发送，Android 通知线程不操作 iAP2 socket。蓝牙保活泵复用同一导航协议模块。

有当前转向时先发送 0x5202 路口，再发送 0x5201 状态及路口索引引用。状态使用实际到达/重规划语义；来源及支持标志只在车机请求时发送。不使用旧 Rust 辅助函数中错误的手机出站 0x5200，已删除该无调用构造器及 JNI 声明/导出。

最多每秒发布一次变化，不重复发送相同提示。断开、开始新连接、切换默认地图清理数据；关闭适用范围、通知移除或超过 90 秒没有新通知时发布一次无路线状态清除旧箭头。90 秒是本地保守失效策略，不是高德或宝马规范要求，静止时高德若长期不刷新通知也可能使 HUD 提示暂时消失。每轮链路重建订阅，旧回调不能写回新会话。

HUD 日志只记录来源、状态、方向、距离、组件与排队数量，不记录道路原文。QUEUED 说明进入发送链路，不代表车机已经接受业务或显示到 HUD。

## 验证

纯 JVM 回归覆盖：明确转向/单位换算、模糊通知拒绝、到达/重规划、非法字段边界、过期/未来时间、通知移除及旧代次回调拒绝；协议覆盖组件编号、5202 在引用前发送、取消/停订阅/范围关闭、限速/去重、畸形 TLV、可选字段及认证前订阅通过真实链路引擎延后发送。

实车待验证：用高德真实路线导航，对照 HUD 日志的“通知更新 → subscribe → QUEUED → 车机 HUD/仪表显示”。同时验证取消导航、重新规划、二次上车及手动断开不会残留箭头。若没有“通知更新”，先核查高德实际通知内容及通知使用权；若有更新但无订阅，则不能主动伪造车机订阅；有订阅并发送仍不显示时，再对照实车接受字段及路口容量，不靠增加虚构字段补齐。

## 协议对照资料

以下是开源接收端/逆向资料，不是宝马官方兼容保证：

- [mhi2-carplay-cluster 的导航 TLV 定义](https://github.com/maxim-smirnov/mhi2-carplay-cluster/blob/main/hook/routeguidance/rgd_tlv.h)：核对消息方向、组件字段及路线状态。
- [CPC200 消息目录](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/iap2_message_catalog.md)：交叉核对状态/路口字段；没有搬用其盒子包装帧的特殊长度与尾字节。
- [dotnet/macios CarPlay 绑定](https://github.com/dotnet/macios/blob/main/src/carplay.cs)：核对基础 CPManeuverType 数值。未知转向不映射成任意默认箭头。

## CarWith 接入排查（2026-11-08）

本节保留571时的调查结果。572的接入实现见文末；排查未修改CarWith设置。

### 手机及同版本APK证据

ADB读取安装版com.miui.carlink为4.0.23-20261016（104000023），本机留存APK的包名、版本一致。高德当前没有活动导航通知，近期logcat没有可用于验证导航输出的CarWith导航记录，因此不能据此判断CarWith导航时是否仍会发布高德通知。

同版本APK的assets/settings_config.json中有“显示设置 → HUD显示”，说明文字为“打开开关后，显示导航、音乐信息”；设置键为prefer_hud，读取默认值为false。这里只确认默认值，**未取得手机当前开关值**，也未验证各设备/渠道下该设置的实际可见性。

静态追踪：LauncherDataBusClient.G接收UCarMapInfo，能取得方向图标、距离及单位、动作、道路和两行标题；F在HUD开关打开且有方向图标等条件满足时，生成carlife_show_hud_info命令，包含开始/更新动作、navi_turn、remain_distance、road_name及路段距离。结束路径发出清理动作。命令经CarlifeController的ICarLifeCastCallback交给CarLife实现，不是已证实的Android对外广播，不能仅注册同名BroadcastReceiver冒充接入。

另一路NavigationInfo送入DataBusService/ControlChannel的UCarProto.NotifyNavigationInfo。该路径属于CarWith数据总线，不能因为名称有ControlChannel就认定它与HyperCar当前7240/9440端口是同一传输。

### HyperCar与参考实现缺口

- rust-core/src/carlife/session.rs命令循环已经解密并记录消息号/长度，但Session.receive没有导航分支，未识别的消息不产生导航事件；CarLifeEvent/SourceEvent也没有导航引导类型。
- rust-core/src/carlife.rs连接七通道后，对9440 DATA使用drain_stream，读取后丢弃，没有业务解析或按类型诊断。**这只是观察盲区，不证明HUD一定走9440**。
- 对照本地CarLifeWirelessBox参考实现，0x10030/0x10047分别命名为下一转向/辅助引导消息，属于候选协议线索。不能用其枚举和proto直接证明当前CarWith版本的实际出站布局；尚缺本机真实导航报文。
- 检查[mcarplay命令路由](https://github.com/wangxso/mcarplay/blob/ab0cb93878ced6143d45a775c33e699882421304/app/src/main/java/com/mcarplay/app/carlife/CarLifeCommandRouter.kt)及[协议定义](https://github.com/wangxso/mcarplay/blob/ab0cb93878ced6143d45a775c33e699882421304/app/src/main/java/com/mcarplay/app/carlife/CarLifeProtocol.kt)：没有现成HUD导航转换/发送实现；NAVI_TTS是播报音频，不能当作转向结构化数据。未识别命令的字段形状诊断可作为后续采样思路。
- 用户此前提供的hypercar-log-20261107-155000.txt中，可找到CarWith握手及后续命令，但未找到0x10030/0x10047对应RX；该样本没有覆盖确认开启HUD后的真实导航，不能据此认定CarWith不支持。

### 下一步实现依据

优先核实CarWith的“HUD显示”开关，然后在CarWith内启动高德真实路线，观察解密后的CMD及DATA消息（只记录编号、长度、字段类型、受限枚举，避免记录道路原文/图标大块数据）。对照开始、转向变化、重规划和取消四类事件，确认实际发送通道、字段及动作含义后，再将结构化数据归一化接入现有iAP2订阅发送模块。

该方案能复用CarWith已经取得的地图车联回调，有望比通知文字解析提供更稳定的信息。现阶段结论为“存在明确结构化数据及HUD发送路径，HyperCar尚未接入，需导航采样确认线格式”，不能宣称CarWith HUD已可用；无需为此先扩大571通知方案的适用范围。


### 本机真实导航补充（12:22，用户正在CarWith中导航）

ADB确认CarWith HomeActivity与高德UcarMapActivity处于各自显示上的前台，UCarMapService已有高德客户端绑定。用户提供截图显示音乐卡片上方已自动出现转向图标、距离和道路信息；这证实CarWith桌面导航卡片已取得地图数据，不等同于确认外部HUD协议消息已发出。用户说明卡片随导航自动出现，不应再要求用户手动开启该卡片作为采样前提。

本次高德持续通知为category=navigation，标题“高德地图”、正文“正在导航”，extras另有com.autonavi.minimap.navigating=true，但没有转向/距离/道路字段，contentView为空。故571的通知文字适配在此次CarWith导航中无法提供引导数据；不能靠取消模式限制解决。

12:22:55.009的蓝牙HS#2已收到导航订阅：component=42、source=true、support=true。采集到的CarWith CMD日志未出现0x10030或0x10047；部分会话被用户打断重连，日志窗口有限，且9440仍无业务诊断，因此该结论仅限已保存的CMD记录。

从手机实际拉取配套com.baidu.carlife.xiaomi APK后，静态确认CarLifeMixActionProcessor将carlife_show_hud_info交给RGEventHUDCollection.recvMixHUDInfo。后者在HUD支持条件及图标有效时构造CarlifeNaviNextTurnInfo，通过sendMsgToHU发送serviceType=65584（0x10030）。字段为action=1、nextTurn=2、roadName=3、totalDistance=4、remainDistance=5、turnIconData=6；action开始/更新/结束分别为1/2/3，结束路径明确发送3。总距离为路段信息，不可直接当成剩余全程。

该实现另有isSubSubscribeHud支持门闸，由渠道允许列表或强制HUD配置决定；桌面卡片可见不能证明此门闸已通过。卡片显示开关、CarWith外发路径条件、CarLife SDK渠道门闸和宝马iAP2订阅是不同状态，应在后续诊断中分别记录。已确认0x10030的手机侧构造路径及字段，但仍不能将静态构造代码宣称为本轮抓到的出站报文。

下一步可据此实现受限的0x10030解码与导航事件转发，补CMD/DATA按类型诊断，并对照真实回调验证；需要覆盖开始、更新、取消及重连。不会通过OCR识别画面，也不会将“正在导航”扩展为猜测的转向。


## 572：CarWith结构化导航接入

实现0x10030严格protobuf解码，检查长度、字段类型、重复字段、整数范围及UTF-8，跳过图标而不拷贝到Kotlin；0x10047仅作辅助提示通道诊断，不映射成导航转向。CMD与DATA均可接收帧，认证及加密就绪后才处理导航，DATA支持分片重组；未知消息只作限量元数据诊断，不猜测业务回复或修改握手能力。

导航经CarLifeEvent及专用JNI回调传到Kotlin，再进入已有iAP2订阅模块。每次nativeStart生成新token，旧引擎回调不能写回新会话；CarWith轮次关闭、源重置、应用断开、native重试及action=3均清理旧导航。action=3表示隐藏/停止，不推断为已到达；未知turn不绘制猜测箭头。跨CarLife/CarPlay枚举显式转换，不能直接透传数字。道路名仅进入导航报文，不写日志；CarWith的路段总距离不填入剩余全程字段。574中CarWith结构化导航以明确取消、源断开、会话重置作为有效期边界；静止且提示不变时保留。90秒过期仅用于通知文字入口。

宝马自绘高德与CarWith选用各自数据入口，通知回调不覆盖CarWith数据。iAP2仍只在车机订阅后发送0x5202/0x5201，动态组件ID保持不变，其它车型仅能使用新通道诊断，不启用导航TX。

验证覆盖真实TCP分片/AES解密、认证门闸、无效报文清理、未知辅助提示不误映射、映射后的道路/距离/来源进入iAP2报文，以及跨引擎token隔离、取消和过期。实际收到手机0x10030及宝马HUD显示的结果另行记录，不以软件回归代替实车验证。


## DiPlay现场验证与573订阅修正

572安装后，12:34:38已完成DiPlay的RECORD、资源接受、SCREEN_SETUP及视频通道就绪；12:34:39 CarWith认证/加密及视频START完成。蓝牙iAP2订阅component=42/source=true/support=true；日志仅出现state=0的无路线更新，CMD/DATA未记录0x10030，不能宣称导航链路成功。

继续追踪手机配套CarLife SDK：内置KEY_OPEN_HUD渠道列表不包含HyperCar当前使用的20352101，但存在显式订阅流程。CarDataSubscribeManager处理0x18043能力查询，并经0x10044返回模块支持情况；0x18045启动模块订阅。CarlifeDataSubscribe的模块0为turn-by-turn，收到START后调用setSubSubscribeHud(true)。这提供了不改变现有渠道号的正式业务路径。

573在宝马CarWith模式的视频启动后发送一次0x18043，仅查询模块0；严格检查0x10044的计数、模块号及supportFlag，确认支持后才发送一次0x18045。未支持、畸形、未认证或无对应查询的响应都不启动订阅，重复响应不重复发送START。源连接关闭时由SDK断连处理撤销订阅。其它车型不主动查询/启动导航订阅。补充的原生回归使用独立期望字节校验能力查询及START包，并覆盖拒绝、畸形及重复响应。

该修正只补齐来源订阅；CarWith桌面卡片可见、CarWith外发条件及宝马HUD实际显示仍须按实时日志分别确认，不能由静态源码推断已在本轮发送。

573同时修正初始能力上报：当前没有路线时仍保持SupportsGuidance=true，路线状态单独为0；不把“尚无导航数据”误报成“不支持导航”。范围关闭时撤销能力，并把能力变化纳入去重判断。


## 573实测成功链路及574静止场景修正

本机连接DiPlay、设置使用BMW档案的实时记录：

- 12:40:36.351，蓝牙iAP2订阅组件42。
- 12:40:38.017，发出CarLife能力查询0x18043；38.020收到0x10044，确认模块0支持；38.021发出0x18045开始订阅。
- 12:40:40.851，CMD收到0x10030（解密后7909字节，包含图标，图标不转给Kotlin）；action=1、turn=5、distance=36米，随后收到action=2更新。
- Kotlin收到结构化提示，映射成CarPlay maneuver=4（掉头）。
- 12:40:41.422，组件42的转向报文0x5202交给iAP2发送；41.426交付状态报文0x5201。未记录发送异常。

这确认了“手机CarWith来源订阅→真实导航消息→解析→JNI→iAP2报文发送”的软件链路，不代表DiPlay显示了HUD，也不代表宝马实车已接受/显示。

实测提示在静止时可能仅发送开始和变化事件，因此574将CarWith提示有效期改为明确停止/源断开/重置，而不沿用通知文字入口的90秒过期；回归验证静止十分钟仍保留、取消立即清除、旧引擎回调拒绝。通知入口仍保留原90秒保护。


## 自绘UI现场检查（2026-11-08 12:43）

当前574、BMW档案、连接DiPlay。12:43:17.828的配置carlife_source=false，确认已切换自绘UI；12:43:18.029 AirPlay连接就绪。12:43:17.641收到组件42的导航订阅，仅发布state=0的无路线状态；本轮未见有效0x5202转向发送，也未混入上一轮CarWith提示。

12:43:20.321记录“高德通知没有可识别的导航提示”。ADB读取真实通知：标题“高德地图”、正文“正在导航”，com.autonavi.minimap.navigating=true，contentView=null，未提供转向、距离、道路等结构字段。因此当前高德17.02在此次自绘导航中的通知入口不可用；通知解析代码及iAP2协议模块存在不等于自绘HUD已实现。

结论：CarWith结构化来源已验证，自绘UI来源尚未接通。需另接高德真正的结构化导航数据出口；不能扩大通知正则、把“正在导航”猜成箭头，也不能后台启动CarWith音视频流程冒充独立自绘入口。本轮仅诊断和记录，未修改生产代码或编译安装。


## 手机版车机UI与独立Auto接口边界核查

用户指出当前手机版已使用车联导航UI，应优先核查现有车机模式的数据出口，而非直接建议换独立车机版。补充扫描整个安装APK（含DEX、原生库、资源及assets），未找到AUTONAVI_STANDARD_BROADCAST、SEG_REMAIN_DIS、CUR_ROAD_NAME、NEXT_ROAD_NAME的字面实现；当前系统广播记录亦未观察到AUTONAVI_STANDARD动作。此结果不等于证明任何动态下载模块/启用条件下都不存在该协议，不可单凭UI外观或缺少静态字符串下定论。

已找到的车机模式结构化出口是UCarMapConnector调用IUCarMapService.showNavInfo，携带DIRECTION_ICON、OPERATION、WHERE、DISTANCE、DISTANCE_UNIT等字段。数据已存在且曾在CarWith端验证，核心障碍是接收方服务发现的签名检查，而不是导航引擎没有数据。地图引擎、车联UI与外部通信协议应分别验证；现有证据不足以把UCar入口当作独立Auto标准广播入口使用。尚未获得不启动CarWith、不修改高德、无需额外授权的直接结构化出口，自绘HUD修复仍未完成。


## Link SDK独立入口实测（后续排查）

进一步核查发现，AmapLinkService的ApiKeyAuthManager有策略开关及兼容客户端路径：服务自己的策略关闭或允许兼容客户端时会接受无API Key参数的连接；策略要求验证时仍会拒绝。此前将该入口概括为“一定需要API Key”过于绝对，不能以静态导出的服务名或authenticate成功代替实际权限判断。

575以HyperCar真实应用UID 10434绑定该服务，实测bind=true、authenticate=success、registered=true，收到7条真实回调，外层字段为bizType与datas。没有伪装包名、修改签名/白名单、注入高德或启动CarWith。回调解密使用消息自带的传输封装信息，和API授权凭证分开，不记录token、封装密钥或道路原文。此阶段仅确认独立回调入口可达，尚未确认datas中的导航字段。

16:10:46地图旧任务清理执行force-stop，导致刚绑定的Link服务断开。576将绑定挪到自绘地图启动后，并在地图进程重启时作最多3次有界重新绑定；地图任务释放及会话退出均解绑。继续解析嵌套datas的字段形状后，才能确认是否可以作为HUD数据入口。

## 实车失败与当前绑定修正

11月8日下午实车：自绘来源的Link绑定持续失败；CarWith来源已收到真实转向和距离，但对应宝马蓝牙会话清单没有0x5200/0x5201，也没有实际导航订阅，Wi-Fi iAP2保持WAIT_PEER。未记录该轮导航报文发送，不能宣称HUD已工作，也不能由蓝牙清单推断车机所有承载均不支持导航。

真机探针确认Android 17的bindService参数与原来的手写Parcel变体不一致；shell没有AMS登记的应用线程，caller=null调用即使找到服务也会被SecurityException拒绝。删除ShellActivityBind、ShellAmapLink及专用AIDL，统一使用App自己的ServiceConnection；不再以shell绕过“关联启动”。系统拒绝启动时仍需等待地图正常启动或用户授予关联启动权限。

正式Reader按每次绑定隔离ServiceConnection、远程Binder和回调；绑定/授权/注册有超时，连续失败最多8次，间隔2至15秒；成功注册后重置预算，地图进程重启后重新绑定。旧回调不能写入新连接，退出会注销、解绑并关闭待处理FD。DUMP诊断入口复用同一实现，不额外启动地图或伪造路线。

594手机实测21:57:57绑定/授权/注册成功并收到真实bizType=113回调；21:57:58地图服务断开，21:58:00重新绑定后回调恢复。后续再次绑定持续收到真实数据，包含type5.maneuverID。采样改为按业务类型有界保留，排除重复定位/GPS/缩放，避免首批定位记录占满导航样本。

594—596阶段仅修复绑定，尚未转换Link业务；以下数据接入取代该阶段结论。CarWith结构化入口保持独立。


## Link结构化导航接入

598核对手机真实AIDL发现onAmapCallbackWithFd参数为metadata字符串、再到FD，旧实现漏读字符串并把数据截在64KiB，导致大包无法接收。已按真实布局接收，加入4MiB上限及按代次关闭FD，补齐有界分块重组；仅在授权和注册成功后使用服务签发的token请求导航关键数据刷新/结构查询，不记录token，不修改地图授权策略。协议依据见[高德Android Link接口](https://lbs.amap.com/api/aiot-sdk/guide/strong-ties/amaplinkclient-api)。

实测收到160620字符大包，解封装后有完整路线数据，以及每秒type4导航更新。真实字段curManeuverID、segmentRemainDist、nextRouteName转换为NavigationCue；routeRemainDist是到终点距离，不用于下个路口倒计时。映射参考[高德ManeuverIconID](https://a.amap.com/amapauto/static/bl/500/android/api/classcom_1_1autonavi_1_1gbl_1_1guide_1_1model_1_1_maneuver_icon_i_d.html)，不混用CarLife枚举。未知图标无箭头，新方向先到时不沿用上个路口的距离/道路。

自绘优先采用Link结构化状态，通知只作无结构化来源时的兜底；CarWith继续使用自己的token和数据。type100取消、type200到达；type7导航模式快照不能清除同批type4提示。旧绑定不能更新/清空新绑定，15秒无更新时清除箭头，显式停止不回退到旧通知。类型及生命周期依据[高德数据类型说明](https://lbs.amap.com/api/aiot-sdk/faq-old)。实际启动地图会恢复已耗尽的绑定重试预算，普通失败仍有界，不靠永久后台轮询。

599现场可见左转38米、右转22米等真实更新，Wi-Fi iAP2消费者显示source_ready_wait_subscription。当前WinPlay空体0x5200不是有效组件订阅，未伪造组件或直接声称上报/显示成功；此前宝马车样本未有导航订阅、Wi-Fi iAP2停在WAIT_PEER仍是独立阻塞点。手机数据接收/转换已验证，宝马HUD显示需下次实车确认。
