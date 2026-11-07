# 文档索引

更新：2026-11-07。文档描述当前代码，分别标注实现、现场证据和未验证范围。逐轮排查、旧构建哈希和已废弃方案从Git历史查询。

| 文档 | 内容 |
|---|---|
| [架构](ARCHITECTURE.md) | Android、shell、虚拟屏与Rust分工 |
| [构建](BUILD.md) | 编译、签名、安装与发布 |
| [会话控制](SESSION-CONTROL.md) | 首次引导、待机、主动Wi-Fi及退出 |
| [协议](PROTOCOL.md) | iAP2、AirPlay、资源及关闭边界 |
| [连接诊断](HANDSHAKE-DIAGNOSTICS.md) | 按阶段、事务与通道定位失败 |
| [车型档案](HEADUNIT-PROFILES.md) | 统一配置、覆盖及各车型证据 |
| [CarWith源](CARWITH-SOURCE.md) | 本机七通道、认证加密及视频入流 |
| [自绘界面](CARUI-REDESIGN.md) | 首页、音乐与应用能力 |
| [地图与键盘](MAP-PROVIDERS.md) | 地图任务、全屏、返回按钮及IME |
| [视频与触摸](VIDEO-STREAM-REVIEW.md) | 真实尺寸、资源及端点生命周期 |
| [音频](AUDIO.md) | 采集、路由、资源申请与无声证据 |
| [方向盘与语音](INPUT-AND-VOICE.md) | HID、播放器派发与助手路由 |
| [日志采集](CAPTURE.md) | ADB、蓝牙记录、时间范围及隐私 |
| [宝马盒子证据](BMW-BOX-CARPLAY-FLOW.md) | 固定样本、时序及结论边界 |
| [参考实现](REFERENCE-REVIEW.md) | 源码版本、可借鉴机制与角色区别 |
| [验证](VALIDATION.md) | 实测、单测与尚未完成的闭环 |
| [滚动审计](AUDIT.md) | 最新审计问题、修正和检查结果 |

## 维护约定

新行为更新所属专题，不再创建日期后缀的功能分析文档。同一次审计只更新AUDIT；测试和实车状态集中在VALIDATION，不把每轮构建追加到多个文件。保留关键原始样本标识、可复核版本及未闭合问题，删除过时操作指引。已实现不等于已实车通过，verified不等于所有功能均可用。
