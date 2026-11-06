# 奥迪档案对照记录

参考 [CarDeck 503ed37](https://github.com/shaonialife/cardeck/tree/503ed3730d5a9365272294b79306cea5fcce115b)，2026-11-06 核对。参考项目的成功记录不等于 HyperCar 已通过实车验证，因此 `verified=false`。

## 本次修正

- `sendCarPlayAvailability=true`：与参考项目一致发送 0x4300，不以车机能力清单缺项为由跳过，也不等待 ACK。
- `sendTransportIdentifier=true`：发送真实蓝牙地址的 0x4E0E；奥迪可能接受但不 ACK，沿用异步发送。
- `openEaSessions=true`：仅对车机声明的 EA 协议发送打开请求；现有实现仅短时等待，缺少 EA03 仍继续建链。
- 更新内置档案与统一下发文件 `profiles/headunits.json`，schema 升至 7，隔离旧配置缓存。

源码依据：[Iap2SenderSession.kt](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/app/src/main/java/io/github/shaonialife/cardeck/iap2/Iap2SenderSession.kt)。

## 保留的连接策略

参考项目的协议记录分别描述了 MMI 6318 的凭据加 mDNS 路径、MMI 9310 延迟发送 0x4301 的路径。因此不强制奥迪必须先收到新鲜 ctrl 邀请；优先 mDNS，继续接收晚到的 iAP2 通知。保留蓝牙音频配置保护，RFCOMM 控制链路不能因为获取 Wi-Fi 凭据便主动关闭。端点不能固定为网关 .1，屏幕和触摸以协商结果为准。

资料依据：[PROTOCOL-NOTES.md](https://github.com/shaonialife/cardeck/blob/503ed3730d5a9365272294b79306cea5fcce115b/docs/PROTOCOL-NOTES.md)。

## 验证范围与后续实车检查

本次只修正有直接源码依据的档案策略，未改动宝马、大众、WinPlay 的媒体协议，也未恢复已删除的视频重建方案。

奥迪不同固件在 MFi 挑战长度、旧版 AirPlay 参数和配对拒绝后的时序上存在差异，不能仅凭车型统一强制参数。尤其参考记录中的 3315 首次 403 后等待新邀请成功，不能推导成可安全降级明文；HyperCar 的通用配对失败回退尚需独立验证，本次没有宣称覆盖该情形。

实车需核对：0x4300 / 0x4E0E 是否发出、EA 是否由车机声明、Wi-Fi 凭据与实际端点来源、晚到邀请能否继续激活、音视频和触摸是否完整建立。覆盖首次配对及二次上车后才能提升验证状态。
