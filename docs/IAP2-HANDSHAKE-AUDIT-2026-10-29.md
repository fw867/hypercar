# iAP2 握手审计（2026-10-29）

## 范围与证据

审计 HyperCar `52017c7da9fed8d6a266fafc07944a8488820723` 的蓝牙 iAP2 链路、识别、MFi、Wi-Fi 凭据与 CarPlay 端点交接。未改动运行时代码、未新增车型、未发布安装包。

参考的是用户指定的 **shaonialife/cardeck 上游**，固定到 `503ed3730d5a9365272294b79306cea5fcce115b`（0.1.28）。本机另有 fw867/cardeck fork，不能把两者混为同一版本。历史研究文档和上游注释中的实测结论均需要保留适用范围；公开 Apple 资料用于核验流程，其他开源实现用于交叉核对消息结构，不能替代完整的 MFi 规范或本车抓包。

结合用户提供的 ID7 日志：型号 MGU-48-P；用户确认真正的 iPhone 可以正常连接无线 CarPlay。报告不收录车辆名称、蓝牙地址、序列号或原始认证报文。

## 结论

**应先修复失败处理、重连试探和阶段等待，再考虑 ID7 实验档案。** 当前日志的失败点在 `0xAA02` 发出后、`0xAA03` 收到前。`0x4300`、EA 开会话、端口与分辨率都在后面的阶段，改这些档案项不能直接解决这一次失败。

| 优先级 | 发现 | 与上游关系 | 证据强度 |
|---|---|---|---|
| P1 | MFi 挑战期间断链后函数正常返回，调用方继续发送 AA05 | 上游也存在 | 本地临时测试复现 |
| P1 | 所有候选长度均 rejected 后，每轮重连仍从同一候选开始 | 上游也存在 | 本地临时测试复现；符合 ID7 日志 |
| P1 | 5703 最后一段等待耗尽整轮 deadline，4300/4301 回退失去等待预算 | 上游也存在 | 控制流确认；未实车复现 |
| P2 | 非控制会话数据分支发送 ACK，却不推进接收序号 | HyperCar 的丢弃分支与上游不同；上游 EA 分支也需单独审查 | 本地复现旧 ACK；真实接收端后果待验证 |
| P2 | 任意断链被归因为“不接受该长度”，污染持久化记忆 | 上游也采用该推断 | 日志与代码确认；原因未证实 |
| P2 | 以清单数量推断完整、NEW 代际 | 上游也采用该启发式 | 代码确认；公开资料不证明此规则 |
| P2 | 认证收到响应、认证校验通过、兼容放行没有明确区分 | 上游也跳过证书与签名校验 | 与 Apple 公开流程存在差异 |
| P2 | 0xAA06 命名、无线组件判定和档案说明有过度推断 | 部分为本项目新增 | 开源结构与本项目代码交叉核对 |

## 1. 断链必须在实际失败阶段结束

位置：`Iap2SenderSession.kt:221–222, 814–888`。

`runMfiChallenge()` 在 `recvFrame()` 返回 null 且 `channel.isAlive == false` 时，记录 rejected 然后 `return`；调用方无条件发送 `MFI_AUTHENTICATION_SUCCEEDED`。结果是最终错误指向“发送 AA05 时关闭”，掩盖前一个阶段已经断链的事实。不是 AA05 导致了本次 ID7 断链。

建议返回明确结果或抛出带阶段的异常：至少区分收到响应、等待超时、链路关闭、会话取消和异常消息。链路关闭/取消后立即结束本轮，不能沿用“正常返回 = 成功”的约定。错误信息应保留最后收发消息、挑战长度、经过时间和链路关闭原因。

## 2. 重连试探要跨连接前进，不能只在同一连接循环

位置：`MfiLengthMemory.kt:148–170`。

基线为 `32/20/16`；所有候选被记为 rejected 时，`allRejected` 会清空排序用的 rejected 集合，恢复同一基线。如果第一候选每次都立即断链，后续候选永远没有机会执行。ID7 日志已经出现所有长度被标记失败、每轮仍从 32B 开始的形态。

建议把“本次挑战期间断链”单独记录为观察结果，记录上一轮首试候选与试探轮次。重连时让尚未完成的候选获得一次独立连接的首试机会，设置总次数与退避，避免无限循环。已有成功记录应带证据级别和失效条件；单次传输断开不应永久等价于协议拒绝。

此外，同一连接内先后发多个不同挑战，迟到的 AA03 不能仅凭到达时间可靠归属到当前长度。上游注释也记录过该问题。未知设备宜优先采用每条连接一个未决挑战的实验方式，而非用超时后的第二条挑战推定成功长度。

上游确有 Audi/Delphi/MIB/Harman/Porsche 先试 20B 的品牌启发式，本项目没有复制。**该列表没有 BMW，不能据此推导 ID7 应强制 20B。** 公开 Apple 文档也没有给出这里可用的固定挑战长度判据。

## 3. 凭据与端点交换需要独立阶段预算

位置：`Iap2SenderSession.kt:316–325, 373–375`；整轮预算来自 `Iap2Bluetooth.kt:73`（90 秒）。

5703 的第三段等待直接传入整轮 `deadline`。如果车机不回 5703，这段等待会占满整轮；随后虽然日志说改走 4300/4301，但 4301 等待仍使用同一个已过期的 deadline，通常立即返回。早到的 4301 会先被暂存，也不会终止当前 5703 等待。保活泵可能在稍后补救，因此这是延迟和回退问题，不能一概称为永久无法连接。

建议：凭据阶段等待“5703 或携带凭据的 4301”任一可用结果，给传统路径有限预算，为端点交换预留时间；保留晚到消息处理。对已明确下发的地址/端口优先采用，不必重复等待 Bonjour。

Apple WWDC23 公开确认有两种连接流程：Bonjour 传统流程，以及利用现有 iAP2 交换 IP/端口的简化流程。资料没有公开证明消息数量达到某阈值就能区分两种流程。

## 4. 非控制会话的 ACK 与接收水位不一致

位置：`Iap2LinkEngine.kt:389–404, 467–483`。

控制会话会经 `handleData()` 推进 `lastReceivedInOrder`；其他会话只 dump 并 `sendAcknowledgement()`。但 ACK 的字段使用的仍是旧的 `lastReceivedInOrder`。临时测试以已协商的 EA 会话送入 seq=51，实际输出 ACK 仍为 50。若之后没有控制帧推进累计确认，对端可能持续重传或发送窗口被占满。当前“已 ACK 未处理”的日志不准确。

建议先核验接收端的链路序号规则：上游 EA 分支注释声称与控制会话独立，实际也只是直接回 ACK，没有独立水位；这个注释不能证明当前旧 ACK 正确。在共享链路序号的情形，应把序号检查、去重和接收水位更新放在会话分派之前；若确有独立规则，应显式实现对应状态。同步核对协商得到的会话 ID/kind，不能只硬编码控制/EA 默认 ID。增加混合控制与 EA、重复帧、序号回绕和窗口填满测试。现有探针只证明输出仍是旧 ACK，不证明所有车机都会因此失败；现有 ID7 日志也不足以证明它就是 AA02 后断链的原因。

## 5. 认证行为与协议语义需要如实表述

Apple 公开流程包含证书验证、挑战和签名响应验证。当前 HyperCar 与 CarDeck 均跳过证书/签名校验，并在所有候选无响应时仍允许发送 AA05。这属于现有实现的兼容行为，不能写成“标准认证成功”，也不能以其在某接收端可用推导所有车机都应接受。

建议：认证日志区分“拿到响应”与“完成验证”；保留可审查的结果类型。后续如实现正式验证，应根据证书和认证版本选择对应机制，而非仅凭证书报文长度猜算法。不要把 iAP2 MFi 与后续 AirPlay pair-setup / pair-verify 当成同一认证步骤。

公开开源消息定义将 AA04 标为 AuthenticationFailed；JJTech 的解析器标注其来源为 Device（本项目的手机角色），而 AA06 是 AccessoryAuthenticationSerialNumber。因而代码中“收到 AA04 就是配件拒绝挑战长度”没有充分依据；`MFI_EXTENDED_INITIATE`（AA06）也不应当作某个 BMW 专用认证起始流程来解释。先修正名称与诊断，不能据此增加未知应答。

## 6. 能力判断和文档中需要降低确定性的表述

- `ReceiverProfile.classifyWifiGen()` 实际判断收发清单**合计 ≥10**，不是“两份清单分别 10+”，甚至没有要求两份都存在。这只能是上游的经验分类，不能证明清单完整，也不能据此认定 ID7 只能使用简化流程。建议结合实际凭据来源学习结果，保留另一条路径的有界回退。
- `Iap2Messages.parseIdentification()` 只判断顶层 tag24 是否存在，未解析其中的 supportsCarPlay 子标记。公开开源结构将该子标记列为 id4。建议分别记录组件存在、支持标记和消息清单，避免把“组件存在”直接等同于车机已开放当前设备的 CarPlay 窗口。
- “缺少能力声明 = 用户没开 CarPlay 窗口”不能作硬结论。iPhone 能连说明车机有功能，不能证明 Android 设备的配对/授权状态一致；也不能反过来把此次 AA02 断链归罪于未开通 CarPlay。
- `HEADUNIT-PROFILES.md` 曾称宝马 `dropProfilesBeforeIap2=false`，但当前内置 BMW_ID85 实际是 **true**；远程档案又可能覆盖内置值。后续诊断须记录最终合并后的档案来源与值。不要根据相互冲突的历史文字全局翻转此开关。
- “宝马永不发 4301”“默认全部等于真 iPhone 行为”“通用档案与 CarDeck 完全相同”均超出已验证范围。应限定型号、固件、配对状态、版本及样本。

Apple WWDC17 描述的传统流程是在 CarPlay 和网络 iAP2 建立后进行蓝牙交接，不能用来证明所有车机都应在蓝牙握手前断开 HFP/A2DP。现有开关是车型兼容措施，需要独立 A/B，避免把配对 ACL 一起拆掉。

## 建议实施顺序与 ID7 档案

1. 修复断链返回路径、准确记录关闭原因；把正常停止时的中断归为取消，不报告成新的握手失败。
2. 修复跨连接候选轮换和失败记忆语义；已有成功车型保持原首选，避免全局改挑战长度。
3. 核验并修正非控制数据 ACK 水位；补链路回归测试。
4. 重构凭据/端点阶段预算与任一结果等待；同时验证传统与简化流程。
5. 在 ID7 上采集一轮完整新日志，观察不同独立连接首试下是否收到 AA03，以及断链由本地还是对端触发。若能取得同车、相同配对入口的 iPhone HCI 时序，只比较消息顺序、参数结构与时刻，不复制设备身份或密钥。

**ID7 可以后续新增“实验档案”，但不是当前故障的充分修复。** 现有 HeadUnitProfile 没有跨连接挑战策略字段，只复制 ID8.5 的后半段开关再换名字不会改变本次故障。需要先有可配置策略和可观测结果，再以实测确定 ID7 默认值；不能标记已验证。

## 本次验证

对当前实际 Kotlin 类运行了三项临时 JUnit 探针，均复现所述现状：断链后尝试 AA05；全 rejected 时基线不轮换；EA 帧的 ACK 水位不前进。三项执行成功意味着**缺陷复现成功，不代表修复通过**。

命令：`:app:testReleaseUnitTest --tests io.github.hypercar.iap2.HandshakeAuditProbeTest`。探针与 XML 结果保存在本机 `D:/devtools/cardeck-upstream-review/`；测试断言的是缺陷现状，已从项目测试目录移除，避免作为预期行为长期固化。其余发现来自源码控制流与公开资料核对；没有 ID7 实车修复后的验证。

## 公开来源

- [CarDeck 上游固定版本](https://github.com/shaonialife/cardeck/tree/503ed3730d5a9365272294b79306cea5fcce115b)：以此版本源码为比较基线。
- [上游 Iap2SenderSession](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/iap2/Iap2SenderSession.kt)：MFi 阶梯、失败路径、Wi-Fi 等待与设备问候。
- [上游 MfiLengthMemory](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/iap2/MfiLengthMemory.kt)：候选排序及失败记忆。
- [上游 ReceiverProfile](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/iap2/ReceiverProfile.kt)：能力代际启发式。
- [Apple Platform Security：Verifying accessories](https://support.apple.com/en-au/guide/security/sec70a4f377d/web)：认证的公开高层语义；不是这里的具体长度、TLV 和重试规范。
- [Apple WWDC17：Developing Wireless CarPlay Systems](https://developer.apple.com/videos/play/wwdc2017/717/)：配对、设备标识、无线可用性、传统连接和蓝牙交接流程。
- [Apple WWDC23：Optimize CarPlay for vehicle systems](https://developer.apple.com/videos/play/wwdc2023/10150/)：简化连接流程在现有 iAP2 上交换地址与端口，支持 iOS14+。
- [wiomoc/iap2 authentication.py](https://github.com/wiomoc/iap2/blob/master/iap2/control_session_message/authentication.py) 与 [identification.py](https://github.com/wiomoc/iap2/blob/master/iap2/control_session_message/identification.py)：独立开源实现的消息和组件结构交叉核对。
- [JJTech iAP2 Wireshark dissector](https://gist.github.com/JJTech0130/78527c600f7b4d0a7aeb294eab06d8ba)：AA04/AA06 名称与来源角色的交叉证据；不是 Apple 官方规范。
