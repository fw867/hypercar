# 大众车机基础连接档案

## 对照来源

HyperCar 同步至 `51807dd` 后实施。参考只读克隆的 mcarplay main `f1ec3ffaa1f9c11b548ec1a8d8f7afff087d8627`，未修改参考仓库。

- [初始帕萨特适配 d11b012](https://github.com/wangxso/mcarplay/commit/d11b01272466461549b1573c5a19ca3ed49a6005)：记录 VW4211 的 SDP 服务隐藏、初次热点凭据延后、未获邀请提前连入被关闭等现象。提交本身说明新方案未完成实车验证。
- [CtrlInviteGate](https://github.com/wangxso/mcarplay/blob/f1ec3ffaa1f9c11b548ec1a8d8f7afff087d8627/app/src/main/java/com/mcarplay/app/net/CtrlInviteGate.kt)：区分新鲜邀请与旧邀请，20 秒为该车观察值；其 mDNS 兜底宽限策略没有原样移植，本档案使用更保守的邀请门槛。
- [资源仲裁 f74be71](https://github.com/wangxso/mcarplay/commit/f74be714a9cf1bc8ac0cdcf08095bf34d2b34af2)：声明实际归属，根据 requestUI/changeModes 管理画面权；HyperCar 已有对应资源仲裁，本次不再加入假让屏或定时重建。
- [最新策略注册](https://github.com/wangxso/mcarplay/blob/f1ec3ffaa1f9c11b548ec1a8d8f7afff087d8627/app/src/main/java/com/mcarplay/app/compat/ReceiverCompatibility.kt)：VW4131/VW4211 还有推流中邀请重连策略。该策略及最新 PassatReturnStrategy 实验未在本次移植；参考项目的 changelog 同样注明验证边界。

## 已实现

新增内置 `volkswagen_mib`，统一由 `profiles/headunits.json` 下发，初版标记 verified=false；2026-11-07 根据用户转述的网友实车反馈更新为 verified=true。不更改用户当前选择及其它车型策略。

1. 用户先完成系统蓝牙配对，并在目标车机里选定车辆。现有明确目标出站逻辑已不依赖 UUID 清单过滤，因此无需扩大对所有已配对设备的拨号范围。
2. 大众档案允许在用户当前已加入的 Wi-Fi 上绑定并广播 ctrl；要求明确目标且当前 iAP2 对端未与目标冲突。并发 Wi-Fi 无法唯一匹配时不猜选网络。此步骤不写入已配对证据。
3. 保留蓝牙监听和出站尝试、0x4300、0x4E0E、电源更新；不启用 HFP/A2DP 主动断开或无 CarPlay 窗口即拆链。
4. 禁止全局主动 Wi-Fi 直连在该档案生效，必须等本轮新鲜 ctrl 邀请。过期邀请原子丢弃，保留并发到达的新邀请；失效后允许现有广播刷新机制继续工作。
5. 选取端点、打开控制 socket 前及移交原生引擎前检查新鲜度。连接期间失效会关闭候选 FD，继续等邀请。
6. 复用激活失败回退：视频 TCP 尚未建立就失败时，仅停止媒体引擎，保留身份、网络与蓝牙，消耗旧邀请并等下一条。不会套用宝马的已配对 Wi-Fi 主动重试。

新字段参与远程 JSON 解析、继承、导出与 schema 6 缓存隔离。其它车型三个新字段默认 false/false/true（分别为 requireFreshCtrlInvite / advertiseOnCurrentWifi / allowProactiveWifiSession），保持已有行为。

## 使用与限制

选择「大众 Volkswagen」，在「目标车机」里明确选中车辆；首次按车机提示完成系统配对、CarPlay 授权，并手动加入车机提供的 Wi-Fi。该档案不会默认替换用户原有 BMW 档案。

此为连接基线，不等于大众实车兼容性认证。以下没有在本次补齐：

- 推流中新的 ctrl 邀请主动退役并重建整个会话。
- 帕萨特三秒无触控/无要帧判据、假让屏、自动断开和点图标重建实验。
- CarWith 视频的尺寸/帧率转码。仍使用现有真实 SPS/尺寸诊断；车机画幅与 CarWith 源不一致时，需要在 CarWith 设置中适配，不能把报头改大当成缩放成功。
- 全部大众车型、固件及第一次配对方式均未实测。保留 /info 上报尺寸，不写死某一车型的分辨率或触摸空间。

日志关注 PROFILE 的三个策略值、WIFI-INVITE、CTRL 过期邀请、ACTIVATION 的真实阶段，以及已有 RESOURCE/MEDIA_SETUP 报文记录。若出现 SETUP 400、资源拒绝或频繁请求关键帧，应按实际阶段继续分析。

## 本次验证

215 项 JVM 测试通过，包含大众策略、20 秒边界/未来时间/跨网络邀请拒绝、明确目标与对端匹配、schema 5 缓存隔离、配置与内置档案一致性，以及已有宝马已配对 Wi-Fi 重试回归。Debug lint 无错误，44 条 Warning、2 条 Information。

完整 NDK release 构建包含同步后的 RTSP URI 修复及资源报文追踪，APK 内原生库哈希与当前编译输出一致。0.1.0.561 / 10561 已覆盖安装到手机并启动成功；保留当前设置和配对，未自动切换为大众档案。上述验证不替代大众实车测试。


## 2026-11-07 转正

用户明确转述大众车机网友反馈可以正常连接，据此将内置与远程 volkswagen_mib 标记为 verified=true，说明标明“网友实车反馈连接正常”。本次不修改其连接流程或默认策略，也不将奔驰的资源生命周期默认值应用到大众。远程 schema 升至 8，防止 schema 7 缓存的 verified=false 覆盖转正结果。

反馈未给出具体测试车型、固件、首次配对/二次上车及音画触控的逐项结果，因此本记录确认的是网友已报告的连接正常；上述初版未实现的恢复实验和适配范围限制仍然成立，不能据此视为全部大众车型认证。
