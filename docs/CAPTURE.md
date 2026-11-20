# 日志采集与解释

更新：2026-11-11。

## Android

只读采集示例；SERIAL 替换为目标手机序列号，输出目录提前创建：

~~~powershell
adb devices -l
adb -s SERIAL shell dumpsys bluetooth_manager
adb -s SERIAL logcat -b all -d -v threadtime
adb -s SERIAL bugreport D:\captures\hypercar-bugreport.zip
~~~

unauthorized 需要手机系统确认 USB 调试授权。详细蓝牙日志需在系统开发者选项预先开启；采集后检查文件实际覆盖的时间，不能用文件名推断完整时段。

bugreport 可能包含 FS/data/misc/bluetooth/logs 下轮转的 btsnoop_hci 日志。不同 ROM 路径、保留时间及截断策略不同，未出现某个数据包前应先确认采集范围与完整性。

应用导出含近期日志及上次尾部，连续导出可能重叠。对齐版本、会话序号、通道、CSeq 与时间，再去重统计。记录车机点击、用户主动停止和手机系统操作的时间。

## 二次上车的发现与邀请诊断（598）

保留车机和手机原有配对，先启动连接等待，再在车机点 CarPlay。出现失败后先导出应用日志，记录点击及报错时间，再决定是否重新适配；重新适配后的握手不能当作原配对回连成功。

- `MDNS-OBS`：在已选择的车机 Network/接口被动接收 IPv4/IPv6 组播，记录查询的名称/类型/QU、响应的 PTR/SRV/TXT/A/AAAA、端口/地址、TTL/flush、报文来源及累计计数。TXT只保留id/srcvers；每族每15秒最多24条报文日志，超出保留suppressed计数，每报文最多列12项。ready/unavailable标明各族监听是否成功。
- `CTRL-DIAG`：NSD注册代次、请求/实际服务实例名、端口、网络/接口/本地地址和监听线程状态；等待邀请时约15秒一次。健康注册复用不会因诊断重注册。监听失败同网络至多启动3次、重试间隔至少15秒。
- `CTRL-TCP`：TCP已建立后的accept、来源/本地端口、请求头COMPLETE/EOF/TIMEOUT/IO/LIMIT、邀请发布/等待本地地址确认、HTTP200回应和关闭。连接号关联同一条TCP的各阶段；网络过期拒绝单独记录。

观察器只绑定具体组播目的地址，不使用通配UDP5353，以免争抢系统NSD的单播流量；不发送DNS查询、不主动拨号。普通应用的该路径不能观察TCP SYN、发往旧端口的连接，或保证收到NSD单播响应；缺少记录不等于车机没发。NSD注册回调也不证明车机已收到线上公告。若仍无法分辨，应保留现场并补Wi-Fi空口/路由侧抓包。

## 其他已用数据

新车型的 iPhone 蓝牙抓取步骤见 [iPhone HCI](IPHONE-HCI-CAPTURE.md)。样本按记录边界解析：len(u32)、ts_sec(u32)、ts_usec(u32)、type(u8)、data；下一条偏移是当前偏移 + 4 + len。不要用搜索字节串代替结构解析。按原始时间与相对事件对齐，不固定套用时区偏移。蓝牙 HCI 记录不能证明 Wi-Fi 全部数据内容。

盒子 iAP2 摘要工具：[summarize-box-iap2.py](../tools/summarize-box-iap2.py)。工具输出与[盒子流程](BMW-BOX-CARPLAY-FLOW.md)应保留原始样本来源及角色信息；被截断的数据不能作为完整字段比对依据。

## 分享范围

公开文档只保留必要的阶段、相对时间、错误码与样本指纹。bugreport、固件、APK 和日志可能包含 MAC、位置、SSID、凭据或私有数据，不随文档提交。原始资料仍由持有人保管，不因整理文档删除。
