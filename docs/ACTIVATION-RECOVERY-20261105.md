# 主动恢复失败后的邀请回退

背景：11 月 5 日 554 宝马实测中，五次无本轮 ctrl 邀请的主动建链均在 RECORD 200 后、初次 modesChanged 等待期间 EOF。用户已确认下午最终能正常显示桌面并出声，本次聚焦视频 TCP 建立前的中断。

## 行为

- 缓存、网关、mDNS、扫描得到的主动候选明确标记为 proactive，不再把扫描地址打印成 0x4301。
- 主动候选控制端口不可用，或媒体引擎在视频 TCP 建立前失败/关闭：同一连接尝试进入 WAITING_INVITATION，只停止旧媒体引擎和对应界面资源。
- 保留手机/车机配对身份、Wi-Fi 绑定、蓝牙会话/监听、ctrl 广播和监听端口。注销的仅是旧引擎 socket 工厂的使用资格。
- 本次尝试剩余等待轮次不再走缓存、网关、mDNS 或网段扫描；等待超时后仍保持这一限制。用户断开或网络失去仍可取消整轮等待。
- 接受真实 ctrl 邀请或之后新收到的 iAP2 会话地址（0x4301）。清理期间用端点邮箱衔接回调，避免新端点落入已关闭的旧等待 Channel。
- 旧引擎的重复终止回调由本轮 ProactiveActivation 收敛，nativeStop 完成后才清除；已建立视频 TCP 的会话仍走原来的断开处理。
- 保留原主动恢复设置和 10 秒延时；不改变设置持久值，不恢复两个已删除的视频重建实验。

## 诊断

原生层在实际收到成功应答时立即发布 RECORD、RESOURCES_ACCEPTED、SCREEN_SETUP，并在实际打开视频 socket 后发布 VIDEO_CHANNEL_READY。四个事实同时进入流水线 JSON/日志，防止几毫秒内发生的 EOF 被周期采样漏掉。

Kotlin 不再从 connected 反推 RECORD：RECORD 对应 ACTIVATING，控制会话对应 CONNECTED，观察到视频帧写出才对应 STREAMING。WAITING_INVITATION 保留原失败原因与之前已完成的里程碑。同一尝试可包含主动失败和随后邀请恢复，ACTIVATION 日志附 attempt/core 标识区分引擎。

首页连接阶段新增紧凑激活行，显示 RECORD、资源、视频 TCP；已完成阶段标绿，中断处标红，未执行阶段保持中性。TCP 建立、帧写出依然不等于显示确认；本次测试最终显示与出声由用户现场确认。

## 验证

- JVM 单元测试 199 项通过，包含重复/迟到回调、视频通道建立后的正常断开、RECORD 成功但资源失败的诊断区分。
- 原生测试通过 Android NDK 编译并在手机运行：74 项通过，1 项忽略。新增真实 TCP 用例复现 RECORD 200 后资源声明期间 EOF，验证 RECORD=true、resources=false、video=false。
- Debug lint 无错误，47 条 Warning、2 条 Information（与改动前记录一致）。签名 release 版本 0.1.0.555。
- 实车尚需验证：主动失败后是否持续广播/保留蓝牙，新邀请到达后能否进入桌面；用户主动断开是否及时结束等待。此次不宣称已解决车机为什么关闭控制通道。
