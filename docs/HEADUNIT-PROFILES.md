# 车机档案

更新：2026-11-02。权威来源是运行代码及有效远程配置，旧注释中的试验建议不作为当前值。

## 内置档案

| ID | 蓝牙音频配置保护 | 主动出站 iAP2 | 等待本轮 AirPlay 激活 | mDNS 优先 | 端口兜底 |
|---|---|---|---|---|---|
| bmw_id85 | true | false | true | true | 7000/7001/7100/17000 |
| generic | true | true | false | true | 7000 |
| winplay | false | true | false | false | 7000 |
| audi_mib3 | true | true | false | true | 7000/7001/7100/17000 |

BMW / generic 启用 sendCarPlayAvailability、openEaSessions、sendTransportIdentifier；WinPlay / Audi 关闭这三项。四项档案均回复电源更新，且不因 CarPlay 窗口关闭立即主动中止。WinPlay 校验 deviceID，并保留历史触摸兜底 2732×794；这不是宝马的固定分辨率。

BMW MGU-55 是当前实车基准。Audi 尚未完成本项目实车验证；generic 的 verified 字段也不代表所有车型均已测试。未知 ID 回退到 BMW。

实现：[HeadUnitProfile](../app/src/main/java/io/github/hypercar/headunit/HeadUnitProfile.kt)。

## 远程配置

配置源为 [headunits.json](../profiles/headunits.json)，解析与合并见 [RemoteProfiles](../app/src/main/java/io/github/hypercar/headunit/RemoteProfiles.kt)。

当前 schema 为 3。达到当前 schema 的同 ID 远程档案覆盖内置档案；旧 schema 仅追加新的 ID。解析器支持的字段未明确提供时，从对应内置档案继承；新 ID 的缺省模板为 BMW。未实现解析的字段另见下文。刷新失败保留已有缓存，因此不能假定失败后一定恢复纯内置值。

目前 JSON 中 BMW 明确设置 dropProfilesBeforeIap2=false，与内置 true 不同，实际值取决于已加载的配置。诊断必须记录有效档案与配置来源，不能只看源码内置表。

当前支持的策略字段包括：validatesDeviceId、sendCarPlayAvailability、openEaSessions、sendTransportIdentifier、replyPowerUpdates、dropProfilesBeforeIap2、allowOutboundIap2、abortWhenCarPlayWindowClosed、preferMdnsAirplay、fallbackAirplayPorts、touchDisplay，以及显示信息与 pairingHint。

requireAirplayActivation 当前没有在远程解析器中读取；远程构造对象使用该字段的默认 false，而不是继承内置 BMW 的 true。因此表中激活门槛是内置值，加载同 ID 远程档案后可能不同；不能把它作为可远程调整的字段。keepIap2AliveOnly 不属于当前解析字段。不得凭 JSON 出现某字段就认定已生效。

新增车型先记录接收端角色、邀请、服务、触摸和日志证据，再修改对应字段；不要因另一车型文档中的固定端口、Session ID 或时间阈值就宣称已适配。
