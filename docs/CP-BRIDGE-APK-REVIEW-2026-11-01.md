# CP Bridge 0.2.7 公开 APK 静态分析

## 样本和方法

- 仓库：https://github.com/hatak30/cp-bridge
- 发布包：https://github.com/hatak30/cp-bridge/releases/tag/v0.2.7
- APK：`D:\devtools\cp-bridge-review\CarPlayBridge-0.2.7.apk`
- SHA-256：`F062859FAA4F845B834E51C43CFFD337743E1CEE9C9FF10B5B1EE688F72357A8`
- 包名 `dev.hatak.carplayprobe.w`，versionCode 207，minSdk 31，targetSdk 35。
- 使用本地 JADX 及 NDK llvm-strings / llvm-nm，仅静态读取，未安装/运行 APK，未修改其功能。原始反编译结果及去掉 Kotlin Metadata 注解行的便读副本在该分析目录。
- JADX 结束时报告 53 个错误；部分方法明确标记反编译不完整。以下以可辨认的 API 调用、常量、调用关系为证据；日志文案作为辅助，不将反编译伪代码当作完整源码。
- Java/Kotlin 应用层可见；原生库 `lib/arm64-v8a/libcarplay_engine64.so` 为 547344 字节。本轮只读取 JNI/动态符号和字符串，未恢复原生状态机。

## 与二次上车相关的发现

### 1. 自动唤醒使用 CompanionDeviceManager

`CarCompanion.java` 按车机 MAC 建立 BluetoothDeviceFilter，调用系统 association，并启动设备出现观察。`CarCompanionService.onDeviceAppeared` 转给 `AutoStart.trigger`。

`AutoStart.trigger` 检查自动开关与 serverRunning，并以 15 秒间隔去重，然后启动前台 ProbeService。该服务的 AUTO_START 分支尝试拉起 MainActivity，再进入启动流程。并不是另一个完整的无线 CarPlay 重连协议。

参考文件（便读副本）：
- `CarCompanion.java`：associate / startObserving。
- `CarCompanionService.java`：onDeviceAppeared。
- `AutoStart.java`：trigger。
- `ProbeService.java`：AUTO_START 分支。

对 HyperCar 的意义：可作为进程未运行、后台受限时的补充唤醒入口；我们下午样本中进程与蓝牙监听已运行数分钟，单独引入该机制不能证明可以解决那段车机未连入的等待。

### 2. 被动 RFCOMM 与蓝牙重注册

`DebugActivity` 中默认服务为 Wireless iAP，UUID 为 `00000000-deca-fade-deca-deafdecacafe`。

`MainActivity.startServerNow` 启动 ProbeService，默认 insecure=true，启动 SYN/ACK 与 iAP2 驱动。`ProbeService.serverLoop` 调用 `listenUsingInsecureRfcommWithServiceRecord`，随后 accept 车机连入；也存在调试用的主动 connectClient 入口，不能据此认定正常流程会主动拨车机。

监听循环具备重新创建 server socket 的路径，日志明确区分蓝牙关闭/启动与普通服务错误。其重试分支部分反编译不完整，应继续通过字节码核对后才移植具体时序。

### 3. 首配与已配对回连的动作不同

可辨认的 MainActivity 流程：
- 首配路径允许用户确认关闭蓝牙，启动服务，再请求打开蓝牙及 60 秒可发现。
- 已记录配对车机的普通启动路径不主动进入可发现状态。
- 自动启动路径直接跳过交互式可发现操作。

因此“每次上车都重启蓝牙/变为可发现”不是该 APK 可见的正常回连策略。也不能把它首次配对的服务注册顺序直接作为我们二次连接的修复方案。

### 4. Wi-Fi 仍在收到凭据后建立

`ProbeService.launchWirelessBringupRootfree` 创建 WifiJoin。后者通过 WifiNetworkSpecifier 请求车机 SSID，onAvailable 中绑定进程网络，然后启动 CpEngineSession。

`CpEngineSession.start` 广播 `_carplay-ctrl._tcp` 并浏览车机服务，再调用原生引擎连接。存在独立 Wi-Fi iAP2 检测、驱动及原生通知接口；仅看到接口不足以确认与 HyperCar 的握手顺序完全相同。

### 5. HFP/A2DP 与 disableBluetooth 应区分

本轮检查到的应用层未发现与 HyperCar CarProfileGuard 对应的 HFP/A2DP profile 强制断开逻辑。原生接口中确有 nativeSetDisableBt，CpEngineSession 有相应发送开关；不能把该 CarPlay 控制选项等同于 Android 的 BluetoothHeadset / BluetoothA2dp.disconnect。

不能因此推断 HyperCar 的 guard 有效或无效，也不能据此证明下午 socket abort 的原因。

## 显示技术

其 MirrorSource 使用 MediaProjection + VirtualDisplay 采集手机主屏；MainActivity 在 API 34+ 请求默认物理屏幕的录制配置，触控由无障碍服务处理。Android Auto 模式则有独立的本机 head-unit server 桥接。

这与 HyperCar 通过 Shizuku 建立独立显示、运行自绘首页及地图 App 的架构不同。两者共享无线 CarPlay 对车机的链路思路，显示层并不相同。

## 可借鉴的顺序与限制

1. 在独立实现中研究 CompanionDeviceManager 作为补充后台唤醒入口；不能用它替代已运行会话的蓝牙激活诊断。
2. 对照被动 RFCOMM 服务的注册和蓝牙状态变化处理，重点验证服务在整个二次上车等待中是否确实对车机可见。
3. 区分首次配对、已配对手动连接、后台自动启动三个入口，保留各自日志。

公开 README 只声明 BMW i8 / iDrive 6 NBT Evo 已验证，较新 iDrive 未验证。HyperCar 的 MGU 样本不能直接沿用其兼容性结论。

静态分析阶段只新增本文档，未将反编译源码或第三方二进制加入 HyperCar。原生协议精确时序、部分失败方法的控制流，以及实车效果仍未验证。

## 后续独立实现与集成

用户要求先集成有价值的机制后，使用 Android 官方 SDK 独立实现以下内容：

- 新增设置页“后台唤醒增强”，Android 13+ 可按已选择的车机 MAC 发起系统关联。用户通过系统对话框确认；不会自动开启上车自动连接，也不会重新配对或清除车机身份。
- CompanionDeviceService 收到目标设备出现后校验关联与当前目标，经现有前台服务请求自动会话。15 秒去重、已有连接/协商不重复启动。设备消失只记日志，不当作 ACL 断开，不拆 Wi-Fi 会话。
- 启动应用或更新自动模式时恢复/停止系统观察；切换目标清除旧关联；设置页可解除关联。Android 12/12L 继续使用已有 ACL/Wi-Fi 路径。
- 确认 HyperCar 原有 accept 循环在 IOException 后直接退出而 running 保持 true 的缺口。新建每轮独立拥有资源的 RecoveringListener，注册/accept 失败后关闭旧 socket，间隔 1 秒重试，蓝牙原本关闭时也可等待恢复。停止或旧 generation 失效后不再重试，晚返回的监听 socket 或连接会关闭。
- 保留原来的安全 RFCOMM 服务、BMW 被动模式及本轮邀请门槛；没有移植首配蓝牙开关、持续可发现或第三方原生库。

新增测试覆盖：自动关闭/未知目标/非目标不唤醒、已有会话不替换、重复出现节流；蓝牙启动时不可用后恢复、accept 失败重注册、停止期间注册/accept 返回的资源释放、旧轮次不能关闭新轮次资源。Debug 单元测试共 73 项通过（0 失败、0 错误）。仍需实机验证系统关联对话框、后台回调与实车二次上车；静态分析不能代替这些验收。

## 系统关联提示与安装版本核对

用户反馈设置时没有提示后，ADB 确认连接的手机仍为 0.1.0.486（versionCode 10486），此前新增功能尚未覆盖安装。旧版选择“目标车机”只是设置连接过滤目标，不是向 Android 请求伴生设备关联。

同时补齐新入口反馈：点击立即显示请求进度；系统提供确认入口、用户取消、确认返回、成功/失败及解除关联均有可见反馈和 COMPANION 日志。等待系统结果 30 秒无响应会给出提示、恢复重试按钮；系统对话框打开期间不启动该超时。全局设置刷新不再清空成功/失败文案。

构建 0.1.0.487（versionCode 10487）通过 Release 编译、vital lint 与 84 项 Release 单元测试。签名 SHA-256 与从手机读取的 486 安装包一致。签名包保存在 `D:\devtools\hypercar-packages\HyperCar-0.1.0.487-reconnect.apk`；随后按用户反馈已通过 ADB 覆盖安装并确认版本。

## 目标车机列表统一入口

按用户要求移除“行为”区独立的后台唤醒增强卡片。现在点击目标车机列表中的设备，即保存目标并请求系统增强发现授权；手动输入有效目标也使用同一入口。已授权设备不重复请求系统授权。

列表直接读取系统关联记录，在“已配对 · 时间”后显示“增强发现”标签。取消授权保留目标但不显示该标签。原“自动发现”改成“未指定车机”/“不指定车机”，避免将目标过滤设置误解为主动扫描；目标名称缺失时显示已设置的 MAC。

授权与自动连接开关分开：可以在关闭自动连接时完成授权，但系统观察/唤醒仍严格受自动连接开关控制。保留请求、取消、失败和超时反馈，不新增第二个设置入口。

0.1.0.488 通过 Release 构建、vital lint 和 84 项 Release 单元测试，并通过 ADB 覆盖安装，确认 versionCode=10488。实机设置页确认独立增强卡片已移除，目标车机说明改为点击授权；随后诊断日志已出现系统提供关联确认入口和“增强发现已授权”。未代替用户点击批准系统权限。

## 授权回调去重

用户反馈疑似两次授权/日志。488 的同一次成功会被业务层、界面结果处理、ActivityResult 返回分别记录，并触发重复提示；仅凭这些成功日志不能认定系统 associate 被调用多次。

统一 ActivityResult 与 onAssociationCreated 的完成出口，业务层只记录一次终态，界面只显示一次结果。新增单次申请闸门，同一时刻仅一个系统申请，重复 Pending 只打开一次对话框；取消、超时、失败与成功均只交付一次结果。每次申请有独立 ID，旧回调不能结束新申请。已授权车机不再显示“正在请求授权”的进度提示。

新增测试覆盖双完成回调的两种到达顺序、重复 Pending、重复申请以及取消/超时后旧回调不能干扰重试。

0.1.0.489 通过 Release 构建、vital lint 及 88 项单元测试（0 失败、0 错误），已 ADB 覆盖安装并确认 versionCode=10489。未为了验证重复回调而撤销用户已批准的关联。
