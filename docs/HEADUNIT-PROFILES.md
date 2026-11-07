# 车机档案与验证范围

更新：2026-11-07。所有车型统一在 [profiles/headunits.json](../profiles/headunits.json) 下发，App 按 ID 选择，不维护独立车型 JSON。

## 内置策略

| ID | 断 HFP/A2DP | 出站 iAP2 | 要求本轮激活 | 特殊门槛 | 验证范围 |
|---|---|---|---|---|---|
| bmw_id85 | false | false | true | 允许用户开启已配对 Wi-Fi 主动恢复 | MGU-55 实车基准；不能代表所有 iDrive |
| generic | true | true | false | 通用模板 | verified 不代表所有车型经过测试 |
| winplay | false | true | false | deviceID 校验；7000端口 | PC 联调，不等于真实车机 |
| audi_mib3 | true | true | false | mDNS 优先、兼容晚到端点 | 本项目未完成奥迪实车验证 |
| volkswagen_mib | false | true | true | 当前目标 Wi-Fi 广播；必须新鲜 ctrl 邀请；禁用无邀请捷径 | 用户转述连接正常，未逐项验证功能 |
| mercedes_mbux | false | true | true | 接受本轮 iAP2 端点；强制屏幕资源联动；禁用无邀请捷径 | MBUX 试验档案，未验证音视频正常 |

BMW/generic/Audi 启用0x4300、EA及0x4E0E；大众/奔驰启用0x4300、0x4E0E但不主动打开EA；WinPlay关闭三项。全部回复电源更新，不因CarPlay窗口关闭就主动中止。音频配置保护只涉及HFP/A2DP，不能因此提前断掉RFCOMM控制链。

默认及未知ID回退BMW，新增档案不自动切换用户选择。档案ID不同，连接参数不能因同名Wi-Fi混用。真实接收端身份与UI所选档案需同时记录。

## 配置字段与覆盖

当前 schema=8。达到当前schema的同ID远程条目可覆盖内置；旧schema只追加新ID，不能覆盖内置修复。缺省字段继承同ID内置值，新ID继承BMW模板；显式false是有效覆盖。touchDisplay/pairingHint空字符串可清除。导出保留策略及schema，刷新失败保留缓存并继续执行schema隔离。

| 字段组 | 内容 |
|---|---|
| iAP2 | validatesDeviceId、sendCarPlayAvailability、openEaSessions、sendTransportIdentifier、replyPowerUpdates、dropProfilesBeforeIap2、allowOutboundIap2、abortWhenCarPlayWindowClosed |
| 激活/发现 | preferMdnsAirplay、requireAirplayActivation、allowProactiveWifiSession、advertiseOnCurrentWifi、requireFreshCtrlInvite、fallbackAirplayPorts |
| 画面 | touchDisplay、useScreenResourceLifecycle |
| 说明 | id、label、model、notes、verified、pairingHint |

useScreenResourceLifecycle 默认false；有效值为档案值 OR 用户开关，不改用户偏好。奔驰为true时设置页显示档案已启用且不可在该行关闭。旧 keepIap2AliveOnly 已移除，不恢复旧行为。

## 大众

参考 mcarplay 的 VW4131/VW4211（ID.7、帕萨特2025）线索。必须明确目标车机才在当前Wi-Fi提前广播，不据此写入成功配对历史。ctrl邀请须属于当前Network且在20秒内，控制连接前后复核；过期候选关闭。20秒是兼容性经验，不是协议常量。失败后保留广播及蓝牙，等待新邀请，不能复用已消耗邀请。

2026-11-07 按用户转述将verified设为true，仅表示已有连接正常反馈，不证明所有大众车型/固件、音频、方向盘和语音都正常。另一个无声反馈接收端自报CarLetter；现象与修正见[音频](AUDIO.md)。

## 奥迪

对照 CarDeck `503ed3730d5a9365272294b79306cea5fcce115b`。0x4300/0x4E0E只发送、不等ACK，EA仅对车机声明的协议尝试，缺EA回应不阻断。MMI6318可能只给热点凭据、端点靠mDNS；MMI9310的0x4301可能晚到2分35秒。保留两条发现路径，不套用大众新鲜邀请门槛。

不按品牌固定网关、控制Session ID、触摸尺寸或MFi挑战长度。3315首次403后等待新邀请成功的第三方记录，不能推广为明文降级安全或本项目已完成验证。

## 奔驰 MBUX

样本MB_E-P-4 / MBUX00081，verified=false。三份06:25/06:27/06:30导出存在包含关系，去重后8次引擎配置、7次屏幕SETUP空体400；能完成pair-verify、会话SETUP、RECORD及初始资源声明，不能用后续EOF掩盖最早明确400。车机提供0x4301热点和IPv6端点，无须强等ctrl GET。

更新后的winplay.txt虽选择winplay档案，实际身份仍为奔驰，不是Windows接收端独立成功样本。06:36:35那轮屏幕SETUP400、音频SETUP400，CarWith本地7240持续拒绝；即使Wi-Fi iAP2传输READY，直到导出也无视频帧及音频包。修正IPv6音频URI仍不足以消除该轮400。试验档案保留控制和iAP2等待后续资源，不宣称解决屏幕拒绝。

复测使用奔驰档案，先确认CarWith七通道源就绪，再观察屏幕/音频SETUP应答及后续changeModes/requestUI。不要把“会话已连接”当成投屏成功，也不要因为档案名叫winplay就推断接收端为PC。

代码：[HeadUnitProfile](../app/src/main/java/io/github/hypercar/headunit/HeadUnitProfile.kt)、[RemoteProfiles](../app/src/main/java/io/github/hypercar/headunit/RemoteProfiles.kt)。
