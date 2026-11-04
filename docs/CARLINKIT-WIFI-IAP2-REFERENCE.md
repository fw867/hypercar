# Carlinkit 逆向仓库的 Wi-Fi iAP2 线索

核对日期：2026-11-04。仓库 ludwig-v/wireless-carplay-dongle-reverse-engineering，
提交 `b437135316275a5d3c375bdd1ceac2a6bcf1315b`。

该仓库的 U2W 产品是 iPhone 经无线连盒子、盒子 USB 连有线 CarPlay 车机的适配器。
这与 HyperCar 手机直连宝马原生无线 CarPlay 的角色组合不同，不能直接照搬两端流程。
README 主要介绍硬件、固件解包、调试与刷写；协议实现主要以 ELF 而非完整 C++ 源码发布。

## 已检查的文件

常规 Extracted/U2W_2023.12.28.1212 下的 ARMiPhoneIAP2、AppleCarPlay 保留 ELF 外形，
静态字符串没有可用协议内容；随后找到仓库已提供的解密版本：
`Firmware/U2AW/_AUTOKIT/2024.01.10.0953/Decrypted/`。
只静态分析下载文件，没有在手机或本机运行这些程序。

本地证据：`D:\devtools\hypercar-diagnostics\ludwig-evidence`，含二进制、字符串、
动态符号与 AirPlay 反汇编、manifest.json（来源及 SHA256）。

## 明确的 Wi-Fi iAP2 证据

ARMiPhoneIAP2 解密版 470908 字节：

- 存在 RTTI `CHudiAP2Medium_WiFi`，同时存在 BT、USB Medium。
- 日志 `Switch to Wi-Fi iAP2!`、`IAP2 SchedulDone stat`，以及 `StartRecvBroadCastiAP` / `StopRecvBroadCastiAP`。
- 包含 wirelessCarPlayTransportComponent、transportSupportsiAP2Connection、IAP2-Wireless，
  以及 CarPlayAvailability / CarPlayStartSession 等识别实体。
- WiFi Medium 的 RTTI 位于 `0x6c028`，vtable 位于 `0x6c038`（typeinfo 槽 `0x6c03c`），
  方法入口包括 `0x166a8`、`0x16684`。后者通过内部方法 `0x62c40` 写出一类编号 102 的消息，
  前者读取内部队列并检查消息类型 6；尚未完整追踪这些内部消息到线上报文的映射，不能将编号当作 CarPlay stream type。

AppleCarPlay 解密版 549820 字节：

- 是 AirPlay 接收侧程序，可见 AirPlayReceiverSession 等接口与 `AirPlay/320.17`。
- 包含 `iAPSendMessage`、`_AirPlayHandleSessionControl`、`_AirPlayHandleSessionCreated`、
  `gCarplayMiddleManClient`、`/tmp/iap2transportnotify` 和 disableBluetooth 等线索。
- 存在 DataStream 加密相关字符串，但仅凭字符串不能认定 Wi-Fi iAP2 必走 type 130。
- 尚未完成会话控制回调到 WiFi Medium 的双向调用图，不能只凭字符串声称其承载与宝马 xiap2 完全一致。

`script/start_iap2_ncm.sh` 设置 `/sys/class/android_usb/android0/functions` 为 `iap2,ncm`，
属于 USB gadget 配置，不是 Wi-Fi 隧道启动脚本。阅读或下载脚本不代表执行它。

## 对 HyperCar 的价值与边界

可以确认：有真实的 Wi-Fi iAP2 协议处理逆向对象，并非只有蓝牙交换热点凭据的脚本。
继续研究的重点应是蓝牙/无线 Medium 切换条件、AirPlay 会话控制与内部桥接之间的调用关系。
当前检查尚不能证明它支持省略本轮蓝牙邀请，也不能解释宝马“已连接但点击图标不进入”。
对宝马发送侧的时序，用户提供的 xiap2 成功日志及固件仍是更直接的证据。

## 与当前 HyperCar 的改进对照

2026-11-04 再次检查 HyperCar 源码后的建议；下面是本项目的设计建议，
不是已经从该固件完整恢复的算法，也不是宝马故障的根因认定。

1. 将控制会话、Wi-Fi iAP2、画面归属、视频 socket 写出、音频非零采集分别记录。
   当前 RECORD 后即对外 connected/STREAMING，UI_STARTED 是本机编码启动；
   可补统一的阶段摘要，避免将任何一个阶段单独当作车机显示确认。
2. Wi-Fi iAP2 增加 WAIT_PEER 的可观测状态。WifiIap2Protocol 目前在第一批 peer bytes
   到达后才 started=true 并开始 15 秒 LINK 超时；之前可一直等待。应能区分没有首包、
   链路协商超时、认证失败、输出队列阻塞。没有首包只标记未建立/降级，
   不单凭它拆除正常音视频，因为用户之前成功会话同样有 Wi-Fi readiness timeout。
3. 以独立媒体通道为单位恢复。538 已实现重复 requestUI 的资源声明切换；
   可在实车验证后，再单独试验仅重建 screen stream，避免把音频、控制与整轮配对一起重启。
   该具体恢复手段的直接参照是 mcarplay 最新源码；ludwig 固件只提供通道分层/状态转发的进一步研究线索。
4. 保留已有机制：每轮 WifiIap2Bridge 独立 worker、Rust generation 隔离、入出站有界队列、
   FIFO ready barrier、Wi-Fi ready + 新鲜 feedback 后才主动请求 disableBluetooth。
   这些本项目已经具备，不应被列作从仓库新发现的缺失流程。

固件中的 `Wait HUD finish modesChanged`、`InitFakeiPhoneMode`、`Wait FakeiPhone Carplay Init Mode`
反映双端桥接的状态同步线索；需继续还原调用图后再判断可迁移范围。
不应直接把它的 USB 侧初始化等待、内部消息编号、DataStream 字符串或中间桥接流程移植到宝马直连路径。

参考：
[仓库说明](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering/blob/b437135316275a5d3c375bdd1ceac2a6bcf1315b/README.md)、
[已解密协议程序](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering/tree/b437135316275a5d3c375bdd1ceac2a6bcf1315b/Firmware/U2AW/_AUTOKIT/2024.01.10.0953/Decrypted)、
[USB iAP2/NCM 配置](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering/blob/b437135316275a5d3c375bdd1ceac2a6bcf1315b/Extracted/U2W_2023.12.28.1212/script/start_iap2_ncm.sh)。
