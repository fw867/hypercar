# 参考实现与证据边界

更新：2026-11-07。仅记录已查看的源码/样本；接收端和手机发送端的角色不能互换，第三方成功不代表 HyperCar 已实车通过。

| 来源 | 可借鉴内容 | 边界 |
|---|---|---|
| [CarDeck](https://github.com/shaonialife/cardeck/tree/503ed3730d5a9365272294b79306cea5fcce115b) | iAP2 问候、EA、可变Session ID及奥迪记录 | 历史实验解释需按后续修正理解，不能当宝马规范 |
| [mcarplay](https://github.com/wangxso/mcarplay/tree/a3a4117ed60b864324d681e45086be594ed7233a) | CarWith七路连接、认证、加密、视频重组与IDR门控 | VW后续对照版本f1ec3ffaa1f9c11b548ec1a8d8f7afff087d8627；不照搬所有品牌特例 |
| [CarPToCarL](https://github.com/jjuugk/CarPToCarL/tree/6346bf7b63b052b2764eb19f1495891bc833c152) | requestSiri、HID语音入口及CarLife硬键 | 参考的1.5秒PTT经验未用于当前默认结束逻辑 |
| [CatPlay](https://github.com/catplay-labs/catplay/tree/fc52bac7910a07ff3b6d94712175db6013528aa9) | 资源仲裁、流生命周期、关闭顺序、附加蓝牙UUID | Linux HCI及USB包装器公告不能直接搬到Android |
| [Showcase](https://github.com/amineross/showcase/tree/34980e930a0b3245ffb6b49f22187630dac525ab) | 多流资源、媒体加密及计时/TCP诊断 | 越狱iOS接收端；不能证明宝马需同样重建时序 |
| [LIVI](https://github.com/f-io/LIVI/tree/739c889c92929babd0cce4e697672a440291e4bf) | 接收端邀请、IPv6、多屏及车辆协议 | 其主动拨号/拆ACL不应复制为手机“无画面就断蓝牙” |
| [headunit-carplay](https://github.com/riazosama/headunit-carplay/tree/ec76e30a164cbfb9734be11b0383a9780500e6d2) | Android车机栈桥接、依赖就绪及清理 | 依赖Autochips预装栈、MFi及root，不是独立手机发送端 |
| [carplayd](https://github.com/lvalen91/carplayd) | 接收端每轮邀请、Wi-Fi交接保留BT及EOF清理 | main与legacy无线实验不能混作成熟实现 |
| [xcertplay](https://github.com/shilapi/xcertplay) | 查找ctrl、发邀请和连接cafe服务 | 接收端热点/认证要求不原样套到发送端 |
| [CP Bridge](https://github.com/hatak30/cp-bridge) | 手机待机、Companion唤醒去重及保留监听 | 0.2.7原生协议未完整恢复，不能以适配列表证明宝马兼容 |
| [盒子逆向](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering/tree/b437135316275a5d3c375bdd1ceac2a6bcf1315b) | 固件、Wi-Fi Medium及内部桥接线索 | 无线接收转USB方向不同；AP禁IPv6不证明手机应禁IPv6 |
| [旧SDK镜像](https://github.com/maaiika/Carplay) | 会话失效、服务重解析及流/整会话TEARDOWN区别 | 旧附件SDK280.33.8，不能作现代宝马规范 |
| [Wi-Fi extractor](https://github.com/HaToan/carplay-wifi-extractor) | 5702/5703凭据提取原型 | 无完整AirPlay/重连；主动关闭车机入站与宝马证据冲突 |

## 已吸收的机制

持久待机与单轮会话分离、目标过滤、唤醒去重、NSD代次、固定Network租约、独立Wi-Fi iAP2、有界关闭、旧端点失效与TCP采样已落地。TAKE/BORROW不能只按布尔量处理；发送队列写完、对端TCP确认和车机显示必须分别记录。

Showcase的source/carplay_services.m可对照资源变化及VideoConfig，carplay_pair.c可对照HKDF/AAD/nonce。其接收侧丢帧、抖动缓冲与BTstack不能照搬手机；当前源码没有完整iAPSendMessage/type130实现，通用200不等于对应业务已实现。HyperCar不为追赶参考项目恢复已删除的视频重建实验。

## 固件线索与宝马成功样本

解密ARMiPhoneIAP2有CHudiAP2Medium_WiFi、BT/USB Medium、Switch to Wi-Fi iAP2、wirelessCarPlayTransportComponent等实体。AppleCarPlay接收侧有iAPSendMessage和内部transport通知接口。它们证明存在无线iAP2对象，不证明可跳过本轮邀请；内部消息编号102不能当作CarPlay stream type。

固件只作静态分析，未运行或刷写。其二进制/符号/哈希保存在原始证据目录，关键宝马样本及时间线单独见[盒子证据](BMW-BOX-CARPLAY-FLOW.md)。没有找到可直接认定宝马缺失的“二次上车专用iAP2报文”。

## 使用原则

先确定角色、版本、实际报文及验证范围，再移植有限机制。看到符号名不等于恢复完整调用链；接收端发往手机的命令不能反向照搬；未获授权的第三方接口名称不等于可调用能力。修改配对、分辨率、加密或重连门槛必须有本项目证据。

## 可复核版本

- CatPlay：fc52bac7910a07ff3b6d94712175db6013528aa9。
- Cardeck：503ed3730d5a9365272294b79306cea5fcce115b。
- xcertplay：1298fb35e29ee30e7bd849cb0e751b52aa22a13f。
- 无线盒子逆向：b437135316275a5d3c375bdd1ceac2a6bcf1315b。
- SDK 镜像：482d3d7ea289317cc827f9df29734daad49a8e17。
- extractor：42dd48aa801527256963d619579ed40f6b9fd4e2。

来源没有覆盖到的内部逻辑不作肯定描述；单次反编译或看到符号名不等于已经恢复完整重连状态机。
