# 日志采集与解释

更新：2026-11-02。

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

## 其他已用数据

iPhone PKLG 样本按记录边界解析：len(u32)、ts_sec(u32)、ts_usec(u32)、type(u8)、data；下一条偏移是当前偏移 + 4 + len。不要用搜索字节串代替结构解析。按原始时间与相对事件对齐，不固定套用时区偏移。蓝牙 HCI 记录不能证明 Wi-Fi 全部数据内容。

盒子 iAP2 摘要工具：[summarize-box-iap2.py](../tools/summarize-box-iap2.py)。工具输出与[盒子流程](BMW-BOX-CARPLAY-FLOW.md)应保留原始样本来源及角色信息；被截断的数据不能作为完整字段比对依据。

## 分享范围

公开文档只保留必要的阶段、相对时间、错误码与样本指纹。bugreport、固件、APK 和日志可能包含 MAC、位置、SSID、凭据或私有数据，不随文档提交。原始资料仍由持有人保管，不因整理文档删除。
