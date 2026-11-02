# 地图集成

更新：2026-11-02。

HyperCar 使用已安装地图的车联界面；默认地图从可用的高德、百度中选择。首次使用先在手机完成地图应用自身的授权和初始化。

| 地图 | 包名 | 当前车联入口 |
|---|---|---|
| 高德 | com.autonavi.minimap | UcarSplashActivity |
| 百度 | com.baidu.BaiduMap | HiCarWelcomeScreen / HiCarMapsActivity，附带 UCAR action/category |

入口是特定应用版本的集成方式，升级地图后需要复核，不能据此宣称所有版本均兼容。

MapPreview 使用共享虚拟屏：卡片显示 TextureView 输出，全屏转向编码输出，保留地图任务和 DPI。返回卡片不销毁导航任务；切换地图提供方会结束旧任务并启动新任务。全屏期间暂停首页画面编码，不让两个编码输出持续争抢。

## 巡航

当前版本不再自行延时触发自动巡航，也不通过未确认的 URI 或私有配置强写巡航选项。界面说明表示跟随地图应用自身设置，不代表 HyperCar 能读取或同步其开关。

曾检查的高德 17.02.0.2010 配置回调没有可用读写实现；百度 22.1.20 的相关接口受厂商、签名或能力限制。不能把反编译发现的名称当作已可调用接口。

实现：[MapPreview](../app/src/main/java/io/github/hypercar/carui/MapPreview.kt)。尺寸、触摸与画面切换见[推流说明](VIDEO-STREAM-REVIEW.md)。
