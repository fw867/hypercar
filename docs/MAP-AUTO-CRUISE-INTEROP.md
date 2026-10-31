# 地图原生自动巡航开关联动核查（2026-10-31）

用户希望 HyperCar 的自动巡航开关联动两款地图的“投屏后自动巡航”，消除现有首页稳定 10 秒后再次发送巡航 Intent 的重复控制。用户后续已确认“跟随地图原生设置”。实现与验证进度见本文末尾。

## 当前安装版本

- 高德 `com.autonavi.minimap`：17.02.0.2010 / 170200。
- 百度 `com.baidu.BaiduMap`：22.1.20 / 1665。

只读提取已安装 APK，检查对应实现，未读取、修改地图私有数据，也未修改地图 APK。临时 APK 和分析输出保存在系统临时目录，不纳入仓库。

## 高德

`CarProjectionHomePage` 读取 AJX 的 `cruise_storage_namespace` 中 `auto_enter_cruise_after_projection_mode`，值为字符串 `1` 时允许自动进入巡航，还受 AutoCruiseMode 能力条件等限制。

外部 `com.ucar.map.IUCarMapCallback` 虽然声明 getValue/setValue，但安装版 `com.ucar.map.connect.UCarMapConnector` 的实际实现是：getValue 始终返回 null，setValue 方法体为空。不能仅依据接口名字宣称可读写该设置。

地图的设置页面使用 AJX 渲染。本次车联界面 UI hierarchy 没有可可靠操作的文字或开关节点；不采用固定屏幕坐标盲点，也不将命令正常返回等同于设置已经改变。

## 百度

`com.baidu.hicar.ucar.map.connect.UCarMapConnector` 的回调实现支持：

| 操作 | key | Bundle 字段 |
|---|---|---|
| 查询总能力 | 1001 | auto_cruise_total_enable |
| 查询设置 | 1002 | auto_cruise_setting_enable |
| 写设置 | 1003 | auto_cruise_setting_enable |

读写受到 `CarlinkCommonMaterial` 的同步能力和总开关限制，能力不满足会拒绝。地图还需要先绑定 `ucar.service.MAP_SERVICE`，注册回调；`p10.c` 对候选服务进行厂商包名/签名资格检查。不能用普通广播或伪造一个服务就宣称同步成功。

## 可实施替代

删除 HyperCar 自己的 10 秒巡航定时请求，自动巡航交由各地图原生设置控制；HyperCar 提供说明或进入地图设置的引导。此方案能消除两个控制源，但不等于一个开关同步改动两家地图。用户已确认此行为取舍。

以后若地图新增受支持的设置接口，应以“查询能力 → 写入 → 读回确认”为成功条件，缺少能力时明确显示未同步。不能通过直接写私有文件或接口返回 exit=0 来模拟成功。


## 0.1.0.415 实现

- 删除 MapPreview 的 10 秒任务、巡航请求与交互状态判断，首页重挂接、恢复卡片和全屏切换均不再安排 App 巡航计时。
- 删除未被其他业务使用的巡航 URI/命令、AutoCruisePolicy 及旧偏好读写。
- 导航设置移除独立开关，改为“自动巡航跟随地图”的说明和原生设置位置提示。
- 保留地图选择、任务保持、正常启动/布局适配和导航触摸；不修改地图内原生开关，不主动退出地图当前导航或巡航。

验证完成：Release JVM 现有测试 46 项通过（移除 1 项属于已删除巡航策略的测试）；签名 APK 和 lintVital 构建成功，0.1.0.415 / 10415 已安装。实机在手机本地预览启动高德车联窗口成功，记录“自动巡航跟随高德地图原生设置”；持续观察超过 10 秒，没有 HyperCar 计时/巡航请求日志。验证创建的 display 59 及本次 Shizuku 预览进程已清理，高德临时横屏兼容覆盖已恢复。

设备处于锁屏，未直接核验设置页显示，也未实车切换两家地图原生开关；设置区通过源码差异及编译核对。地图自身是否自动进入巡航仍由它的原生设置、能力条件及当前导航状态决定。
