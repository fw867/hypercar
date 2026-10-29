# 高德与百度地图

车机「设置 → 导航」在「导航自动巡航」前提供默认地图选择。两款都安装时可选；只有一款时使用已安装的那款；都未安装则提示安装。默认仍为高德，用户选择会持久保存，临时缺少首选 App 时不删除偏好。

主动改选地图后，返回首页会结束旧地图任务并启动新地图。仅在同一地图的首页卡片、更多页面和全屏间切换，不重新启动导航或巡航页。

## 当前接口

| 地图 | 包名 | 初次车联入口 | 巡航入口 |
|---|---|---|---|
| 高德 | `com.autonavi.minimap` | `com.autonavi.map.activity.UcarSplashActivity` | `amapuri://drive/CarCruisePage` |
| 百度 | `com.baidu.BaiduMap` | `HiCarWelcomeScreen` | `baidumap://hicarmap/cruiserPage?src=autoCruise` |

二者初次启动都使用 `com.ucar.intent.action.UCAR` 和 `com.ucar.intent.category.UCAR`。百度入口及参数已对照本机安装 APK 的 manifest 与对应 Activity 核对，并在手机调起；不能用独立「百度地图车机版」的 `com.baidu.naviauto.action.recv` 广播替代手机版内置 UCAR。

`MapPreview` 统一管理虚拟屏、Surface 与选中地图。高德保留既有 UcarMapActivity 布局更新；百度使用完整 HiCarMapsActivity，卡片和全屏只更换 Surface、调整尺寸，保持 density，不重复发启动 Intent。运行日志中已观察到相同 Activity/task 和 display 在多次切换中保留。

百度车联入口不可用时明确报错，不把打开普通手机版当成车机接口成功。首次使用地图弹出的授权、条款或定位确认由用户正常完成。

## 自动巡航（0.1.0.398）

两款地图统一在首页卡片挂接并稳定显示 10 秒后检查巡航条件，冷启动 Intent 不携带巡航请求。离开首页、切换全屏、关闭开关、切换地图或断连会取消计时；回到首页重新计时，不在锁内等待这 10 秒。

百度检查当前活动 Fragment 确认位于普通地图首页，并使用其自身识别的 `src=autoCruise`。该来源在已经巡航、导航或路线规划时拒绝覆盖；查询失败或页面未知时跳过，不根据历史页面判断。

高德移除了强制清空页面栈参数。由于尚无可靠的外部导航状态查询，只有本次新建地图且未收到用户地图操作时才自动请求；操作过地图后状态视为未知，保守跳过，避免覆盖已有导航。返回首页不会重开已有巡航。关闭开关不会退出正在进行的导航或巡航。

## 验证

地图相关 JVM 测试覆盖地图选择、启动命令不提前请求巡航、10 秒统一延时、保留导航保护和百度未知状态跳过。未将含真实位置的验证截图放入仓库。
