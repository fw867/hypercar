# 文档索引

更新：2026-11-02。本文档集描述当前主线；实现事实、实测结果与验证边界分别记录。旧排查稿中的试验顺序、被后续修正的解释和计划不再作为使用说明，历史可从 Git 查询。

| 文档 | 内容 |
|---|---|
| [架构](ARCHITECTURE.md) | Android、虚拟屏与 Rust 的分工 |
| [可选 CarWith 画面源](CARWITH-SOURCE.md) | 本机七通道、认证/加密门控与验证范围 |
| [构建](BUILD.md) | 编译、签名、安装与发布 |
| [会话控制](SESSION-CONTROL.md) | 首次连接、自动唤醒、增强发现与退出 |
| [协议](PROTOCOL.md) | 当前 iAP2、AirPlay、网络与关闭行为 |
| [连接诊断](HANDSHAKE-DIAGNOSTICS.md) | 按实际到达阶段分析失败 |
| [车机档案](HEADUNIT-PROFILES.md) | 内置值、远程覆盖及适用范围 |
| [界面](CARUI-REDESIGN.md) | 首页、音乐卡片与操作能力 |
| [地图](MAP-PROVIDERS.md) | 地图入口、卡片与全屏 |
| [视频与音频](VIDEO-STREAM-REVIEW.md) | 尺寸、编码、触摸与系统音频 |
| [日志采集](CAPTURE.md) | Android 蓝牙日志、导出与数据解释 |
| [盒子流程](BMW-BOX-CARPLAY-FLOW.md) | 已采集的宝马盒子连接证据 |
| [参考项目](REFERENCE-REVIEW.md) | 可复核的参考内容与角色区别 |
| [LIVI 与 Android 主机源码对照](RECEIVER-SOURCE-REVIEW.md) | 重连、邀请、退出、多屏与厂商栈依赖 |
| [验证记录](VALIDATION.md) | 实车、手机及测试各自证明了什么 |

当前已确认首次连接稳定；二次上车的最新发现与退出改动尚未完成实车闭环验证。已实现某项机制不等于已证明它能修复二次上车。
