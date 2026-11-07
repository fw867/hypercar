# 地图、全屏与输入法

更新：2026-11-07。首次使用先在手机完成地图应用自身的授权和初始化。

| 地图 | 包名 | 已对照的车联入口 |
|---|---|---|
| 高德 | com.autonavi.minimap | UcarSplashActivity |
| 百度 | com.baidu.BaiduMap | HiCarWelcomeScreen / HiCarMapsActivity，UCAR action/category |

入口与具体地图版本有关，不代表所有版本兼容。MapPreview 使用一个持久虚拟屏：卡片接 TextureView，全屏改接编码 Surface，保持 DPI 和地图任务。返回首页不销毁导航；切换提供方会结束旧任务。全屏暂停首页编码，退出恢复首页，触摸目标同步切换。

## 返回主页

全屏浮动主页按钮在触摸时显示，空闲约四秒隐藏。用户已确认每次触摸均可出现，这属于自动隐藏行为，不能直接解释为按钮不稳定。返回首页继续显示地图卡片，导航保持运行。

## 键盘

虚拟屏的 OWN_FOCUS 并不保证输入法本地显示。Shizuku UserService 创建受信任屏后、启动 Activity 前，设置并读回 DISPLAY_IME_POLICY_LOCAL；创建日志 ime=local 表示设置成功。未获得 TRUSTED 或 ROM 拒绝时记录 unavailable，保留可用画面，不改主屏及 CarWith 的输入法策略。

2026-11-07 现场原地图屏策略为 FALLBACK_DISPLAY，键盘落在手机。运行中临时切换曾导致输入法未绑定、两端都不显示；565 在新屏启动前设置后，用户确认显示，系统读到输入法所在 display=314、mInputShown=true，地图屏 mIsImeShowing=true。设置读回不能代替显示和触摸验证。

依据：[AOSP 多屏输入法](https://source.android.com/docs/core/display/multi_display/ime-support)。持续回归：搜索输入、收起键盘、卡片/全屏往返、重连及不同输入法。

## 巡航

跟随地图自身设置，不维护独立自动巡航定时器，也不强写未确认 URI/私有配置。检查过的高德17.02.0.2010、百度22.1.20接口不能证明具备通用读写能力。

实现：`MapPreview`、`MapController`、`MapHomeOverlay`、`ShellDisplayIme`。尺寸与触摸原则见[视频](VIDEO-STREAM-REVIEW.md)。
