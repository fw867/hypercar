# 参考项目的可用结论

更新：2026-11-02。以下记录已查看的代码或资料及其角色，不把第三方实验结论直接当成本项目实车结论。

| 来源 | 已确认可参考内容 | 本项目适用边界 |
|---|---|---|
| [CatPlay](https://github.com/catplay-labs/catplay) | 手机内容源实现、附加蓝牙 UUID、媒体与 TEARDOWN 的关闭顺序 | 已加入 Android 标记服务；Linux HCI、固定通道及 USB 包装器公告不能直接照搬 |
| [Cardeck 协议笔记](https://github.com/shaonialife/cardeck/blob/main/docs/PROTOCOL-NOTES.md) | iAP2 问候、EA 字段、Wi-Fi iAP2 与激活的排查记录 | 文档随实验修订，早期心跳解释不能与后续修正同时作为定论；奥迪、VW 现象不是宝马规范 |
| [Cardeck 自动连接](https://github.com/shaonialife/cardeck/blob/main/docs/AUTO-CONNECT.md) | 目标车机 ACL 唤醒、前台服务及后台启动条件 | ACL 接收器只能在链路已经发生后工作，不能主动使车机发起连接 |
| [xcertplay](https://github.com/shilapi/xcertplay) | 接收器发现控制服务、发邀请、连接手机 cafe UUID 的流程 | 是车机接收端，其热点与认证要求不能原样放入手机发送端 |
| [CP Bridge](https://github.com/hatak30/cp-bridge) | APK 中的手机发送端待机、Companion 唤醒去重、保留监听 | 0.2.7 并未完整恢复原生协议，反编译存在错误；公开适配车型不能证明 MGU-55 同样适用 |
| [无线盒子逆向](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering) | 固件拆包、硬件与诊断工具 | 常见链路为无线接收后 USB 接车，与本项目方向不同；盒子 AP 禁 IPv6 不证明手机应禁 IPv6 |
| [早期 CarPlay SDK 镜像](https://github.com/maaiika/Carplay) | 接收端服务重解析、失效会话清理、流与整会话 TEARDOWN 的区别 | 仓库自称泄露的旧附件 SDK，源码版本 280.33.8；不能当作现代宝马规范 |
| [Wi-Fi extractor](https://github.com/HaToan/carplay-wifi-extractor) | iAP2 识别与 5702/5703 凭据提取原型 | 未实现完整 AirPlay/自动重连，客户端主动关闭车机入站连接，与已知宝马入站流程冲突 |

## 已用于当前实现

持久待机与单轮会话分离、目标事件过滤、唤醒去重、明确服务注册成功、固定会话网络、独立 Wi-Fi iAP2 状态及有界退出均已体现在当前代码。附加 UUID 也已注册，但收益未获实车验证。

关闭时带非空 streams 的 TEARDOWN 在所查看的旧接收端源码中只拆指定流；空 streams 才进入整会话清理。因此当前“流 TEARDOWN 后关闭控制连接”不能表述为已取得整个车机会话清理确认。

## 可复核版本

- CatPlay：fc52bac7910a07ff3b6d94712175db6013528aa9。
- Cardeck：503ed3730d5a9365272294b79306cea5fcce115b。
- xcertplay：1298fb35e29ee30e7bd849cb0e751b52aa22a13f。
- 无线盒子逆向：b437135316275a5d3c375bdd1ceac2a6bcf1315b。
- SDK 镜像：482d3d7ea289317cc827f9df29734daad49a8e17。
- extractor：42dd48aa801527256963d619579ed40f6b9fd4e2。

来源没有覆盖到的内部逻辑不作肯定描述；单次反编译或看到符号名不等于已经恢复完整重连状态机。
