# BMW 下午未激活：2026-11-02

## 输入与边界

- 应用日志：`hypercar-log-20261102-102945.txt`、`110918.txt`、`181913.txt`。
- 上午两份标记 0.1.0.489，时间有重叠，不能按导出时间重复计算测试。下午标记 0.1.0.491；PackageManager 显示 12:04:39 更新。
- 对照提交 `024b075`、`a705c42389d8bc99551fdfae5a35ac632ba34645`。
- ADB 只读获取 bluetooth_manager、logcat、完整 bugreport。报告内保留了覆盖上午和下午的 btsnoop HCI；原始报告、HCI、密钥及证书不入库。
- 本轮只记录分析，没有修改连接实现、手机配置、配对记录，没有安装或发布。

## 结论

用户补充：上午点击车机 CarPlay 后直接开始连接，没有设置检查报错；下午点击立即显示“连接失败，请检查移动设备蓝牙和 Wi-Fi 设置”，连车机搜索/建链都没有开始。故障应定位为车机启动连接前的拒绝，而非搜索超时。18:03 的 HFP 拒绝是随后从手机设置手动连接产生的另一轮现象，不能用它解释车机点击瞬间的错误。复核所有留存 HCI 的 11:00～18:05：宝马相关 ACL 发起仅有 18:03 两次手机 Create Connection，未见车机 Connection Request。

固定网络代码未执行只能排除其直接 socket 行为，不能排除安装升级重启服务后，与车机记忆的身份、能力或服务端点状态间接失配。下午手机本地监听/广播正常并不等于车机已刷新并接受它们；没有车机侧日志或同期 Wi-Fi 抓包，不能确定具体哪项前置检查失败。

下午故障发生于车机激活入口，尚未进入 AirPlay/媒体 socket 或 Wi-Fi iAP2 通信状态回复。不能根据“升级后失败”认定 `a705c423` 的固定网络 socket 或 4158 回复导致此次失败；两者在下午记录中均未执行。

HCI 能确认：上午由车机主动连接手机、查询手机 iAP2 服务并打开 RFCOMM；下午仅出现手机主动连接车机、查询 HFP 服务，车机拒绝 HFP 数据通道，随后结束 ACL。未见下午车机主动连接手机的 iAP2。车机为什么没有启动 CarPlay 路径，手机侧证据还不能唯一解释，不能改称配对损坏、UUID 缺失或 socket 绑定失败。

## 上午与下午对照

| 时刻 | 来源 | 结果 |
|---|---|---|
| 10:28:34.601 | HCI | 车机发 Connection Request |
| 10:28:34.771 | HCI | 车机 SDP 请求包含手机 iAP2 UUID `00000000-deca-fade-deca-deafdecacafe` |
| 10:28:34.834 | HCI | 车机请求 RFCOMM PSM 0x0003 |
| 10:28:35.659 | 应用 | 收到 ctrl 邀请，随后建立 AirPlay |
| 10:28:44.969 | 应用 | 多条 TCP 同时 os error 103；应用随后清理 |
| 10:29:06.594 | HCI | 车机再次主动连接、查询 iAP2、打开 RFCOMM |
| 10:29:12.407～.473 | 应用 | RFCOMM、控制、事件通道先后 EOF；Wi-Fi iAP2 已经过认证交换 |
| 10:29:36.027 | HCI | 车机再次主动连接手机 |
| 10:29:37.783～10:30:09.537 | 应用 | 第三轮 RECORD 后运行至用户手动停止 |
| 18:00:08～18:04:03 | 应用/HCI | 已有车机 Wi-Fi，始终没有 ctrl 邀请、iAP2 会话或 AirPlay 控制连接 |
| 18:03:01.461 | HCI | 手机发 Create Connection，非车机主动连接 |
| 18:03:03.886 | HCI | ACL 连接成功 |
| 18:03:04.053 | HCI | 手机查询车机 HFP UUID 0x111E；车机返回 RFCOMM server channel 3 |
| 18:03:04.297 | HCI | Authentication Complete，status=0；本次没有认证失败证据 |
| 18:03:04.352～.401 | HCI | 手机发 DLCI 6 SABM；车机回 DM，拒绝 HFP 数据通道 |
| 18:03:04.524 | HCI | Disconnection Complete，reason=0x13 Remote User Terminated Connection |
| 18:03:06.921～18:03:09.791 | HCI | 手机第二次主动连接；同样查询 HFP、认证成功、HFP SABM 被 DM 拒绝，最终 reason=0x13 |

HFP DLCI=6 对应 server channel 3；不是手机 iAP2 的监听通道。下午没有车机尝试旧 iAP2 channel 的记录，因此不能用“更新后 RFCOMM channel 改变/车机缓存旧端口”解释已观察到的两轮失败。

## 手机广播和监听是否实际生效

- 系统 Socket Events：17:59:03.641 注册 iAP2 RFCOMM 监听，channel 28；18:04:03.414 才关闭。不是等待期间监听已停止。
- HCI Write Extended Inquiry Response：17:59:03.641 的 128 位 UUID 列表包含手机 iAP2 UUID；与上午服务存在时一致。没有发现下午丢失 iAP2 广播。
- HCI Write Scan Enable：18:00:15.239→18:00:45.247，18:02:14.455→18:02:44.462，实际执行 0x03（inquiry/page scan）→0x02（page scan）。两次 30 秒发现窗口确实到达控制器；不是只打印成功日志。
- 18:02:58.975 系统设置还打开了可发现；18:03:03.009 又恢复 page scan。随后出现的是手机主动 HFP 连接。可发现仅允许车机发现手机，不保证车机启动 CarPlay。
- 两轮 BT-GUARD 都记录“断开 无”；系统 profile 状态只到 CONNECTING，未到 CONNECTED，HCI 也没有手机发 Disconnect command 的记录。不能把对端拒绝 HFP 归因于 guard 实际断连接。
- 车机 HFP/A2DP connection policy 均为 ALLOWED。当前 guard 仅在 profile 已 CONNECTED 时断开，不能阻止系统设置发起的 CONNECTING/HFP 流程；“已开始拦截”不能解释为系统 HFP 被永久禁止。

## Wi-Fi 与提交边界

下午应用恢复 Network #120，wlan0 地址位于车机网段，ctrl mDNS 注册成功。validated 的变化不代表车机已批准 CarPlay，也不证明车机已接收广播。18:04:03.334 才收到 onLost；两次蓝牙拒绝在此之前，不能倒因为网络 onLost 清理。

`git diff 024b075 a705c423` 在 Iap2Bluetooth、bluetooth、autowake、Manifest 均为空。该提交对激活前路径新增的 NET-WATCH capabilities/linkProperties 回调只记录状态；onLost 新增 lease 失效，但下午丢网之前没有 AirPlay lease。

SessionSocketLease 创建位于端点就绪、joinAndBind 后；openControlFd/媒体 SocketProvider 依赖它。下午始终等不到端点，未到这一段。4158 仅在 Wi-Fi iAP2 READY 且收到 4157 后回复，下午同样未到。当前证据不支持回退网络加固以解决此次前置故障。

## 上午第一轮的新线索

bugreport 的 GNSS 历史在 10:28:44.930 记录 `Default Network: 0`，之前 10:28:18.747 为 `Default Network: 1`；距离应用 TCP abort 约 39ms。这增加了调查系统默认网络变化的依据，但这是 GNSS 的网络观测历史，并非 netd socket 销毁或 Wi-Fi 物理掉线的直接证据，不能单独判定因果。固定 socket Network 的方向仍合理，效果尚无下午已建链会话可验证。

上午第二轮的认证后断开仍需独立核对业务/链路证据，不因第一轮网络线索而合并根因。

## 后续修复边界

1. 保留 socket 固定网络加固，不改已经过验证的首次认证、逐帧发送和 Wi-Fi iAP2 身份。
2. 优先调查车机为什么未启动激活：同步车机点击动作时刻、手机是否收到入站 ACL/SDP/iAP2、Wi-Fi mDNS/ctrl 流量。手机广播存在与车机接受是不同证据。
3. 如增加主动 iAP2 兜底，必须限定已配对且明确的目标车机、确认配件 iAP2 SDP 服务、等待入站宽限且无现有会话；不能用普通 HFP connect 替代，也不应在没有验证前把所有宝马连接改为主动拨号。
4. 当前“已知车机 Wi-Fi 恢复会话不依赖蓝牙 ACL”日志在尚未收到本轮邀请时也会出现，只表示不立即拆等待任务，不表示已完成激活。后续应区分“已连 Wi-Fi”“等待激活”“会话已建立”，避免误导归因。
