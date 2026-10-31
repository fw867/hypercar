# 高德与百度地图

车机「设置 → 导航」提供默认地图选择和原生自动巡航设置说明。两款都安装时可选；只有一款时使用已安装的那款；都未安装则提示安装。默认仍为高德，用户选择会持久保存，临时缺少首选 App 时不删除偏好。

主动改选地图后，返回首页会结束旧地图任务并启动新地图。仅在同一地图的首页卡片、更多页面和全屏间切换，不重新启动导航或巡航页。

## 当前接口

| 地图 | 包名 | 初次车联入口 |
|---|---|---|
| 高德 | `com.autonavi.minimap` | `com.autonavi.map.activity.UcarSplashActivity` |
| 百度 | `com.baidu.BaiduMap` | `HiCarWelcomeScreen` |

二者初次启动都使用 `com.ucar.intent.action.UCAR` 和 `com.ucar.intent.category.UCAR`。百度入口及参数已对照本机安装 APK 的 manifest 与对应 Activity 核对，并在手机调起；不能用独立「百度地图车机版」的 `com.baidu.naviauto.action.recv` 广播替代手机版内置 UCAR。

`MapPreview` 统一管理虚拟屏、Surface 与选中地图。高德保留既有 UcarMapActivity 布局更新；百度使用完整 HiCarMapsActivity，卡片和全屏只更换 Surface、调整尺寸，保持 density，不重复发启动 Intent。运行日志中已观察到相同 Activity/task 和 display 在多次切换中保留。

百度车联入口不可用时明确报错，不把打开普通手机版当成车机接口成功。首次使用地图弹出的授权、条款或定位确认由用户正常完成。

## 自动巡航（0.1.0.415 起）

自动巡航完全跟随各地图的原生设置。HyperCar 不再保留独立巡航开关，不启动首页 10 秒计时器，也不向高德或百度发送巡航 URI。地图首次进入车联界面时，由地图自己判断是否自动巡航；首页/更多/全屏之间切换仍保留原有地图任务。

车机设置的导航区显示“自动巡航跟随地图”，提示在高德或百度地图的车机设置中调整“投屏后自动巡航”。此说明不表示已经读取或同步地图的开关值。旧版 HyperCar 保存的 auto_cruise 偏好不再读取，不会修改地图本身的设置。

当前安装版外部接口限制及用户确认的取舍见 [原生巡航接口核查](MAP-AUTO-CRUISE-INTEROP.md)。不再以固定延时重新进入巡航，因此已有导航或用户手动退出巡航后不会被 HyperCar 的计时器覆盖。

## 验证

保留地图选择、车联启动和布局切换测试，删除已移除的延时/巡航 URI 行为断言。检查运行代码中不存在 autoCruise、AutoCruisePolicy、cruiseCommand、巡航 URI 或巡航定时器引用；构建及设备验证结果见接口核查记录。
