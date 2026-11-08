# 地图、全屏与输入法

更新：2026-11-08。首次使用先在手机完成地图应用自身的授权和初始化。

| 地图 | 包名 | 已对照的车联入口 |
|---|---|---|
| 高德 | com.autonavi.minimap | UcarSplashActivity |
| 百度 | com.baidu.BaiduMap | HiCarWelcomeScreen / HiCarMapsActivity，UCAR action/category |

入口与具体地图版本有关，不代表所有版本兼容。MapPreview 使用一个持久虚拟屏：卡片接 TextureView，全屏改接编码 Surface，保持 DPI 和地图任务。返回首页不销毁导航；切换提供方会结束旧任务。全屏暂停首页编码，退出恢复首页，触摸目标同步切换。

## 首页卡片触摸

608 起首页地图卡片直接转发原生触摸，不再覆盖点击进入全屏的区域；只有 Dock 地图图标进入全屏。应用列表选择地图后回到首页卡片。TextureView 使用局部像素坐标按实际地图屏尺寸换算，原样保留 DOWN/MOVE/UP/CANCEL、时间及已收到的多指信息，按顺序在后台经 shell 注入，不阻塞UI；车机上游若只提供单指信息，这里不会凭空构造多指。

每次卡片 Surface 重绑都产生新的输入归属，旧手势不能跨越重绑、全屏、换图或断开。资源交接与触摸注入共用锁，切换前向旧目标发送 CANCEL；丢弃旧队列事件，避免把迟到坐标注入新尺寸窗口。地图手势期间禁止父视图拦截；UI收到的CANCEL不会再被改成UP。

## 重连与视角

普通断开只拆除首页、编码器、浮层和地图输出 Surface，保留地图进程、任务及虚拟屏；重新连接先按提供方名称查找 shell 服务持有的地图屏，再绑定新输出。同一布局不重新发送启动 Intent；断开时若为全屏，回到首页卡片时只更新原 Activity 的布局。旧会话清理与重连串行执行，全屏切换也串行处理，避免旧清理销毁新输出。切换提供方或改用 CarWith 时才结束自绘地图任务。输出异常仅回收输出资源，不强停导航。

卡片对照本机 CarWith 的 CardMiniMapView：高德使用 UCAR action、MAP_PREVIEW category、screenMode=2，虚拟屏从第一次启动就使用卡片真实尺寸，由地图原生排版，不能裁切或缩放完整全屏界面冒充卡片。进入全屏才切换 UCAR category、screenMode=1 与编码尺寸，同一 UcarMapActivity 保留导航。百度继续使用已验证的 HiCar 入口及 screenMode=1。

605 修正竖向布局泄漏：本项目复用同一地图 Activity，isUCarNaviUseVerticalScreenLayout 必须始终为 false。高德只在 NewMapActivity.loadMainMap 首次读取该选项，后续 onNewIntent 更新 screenMode 不会重读；照搬 CarWith 卡片的 true 会令横向全屏沿用竖向排版，搜索框/历史地点过大且底部组件越界。原生 MAP_PREVIEW 仍负责卡片布局，不需要竖向导航选项。旧任务若已以 true 初始化，仅再发 false 不会立即改变其内部配置；需下一次地图窗口初始化后验证，不能通过强停正在导航的进程硬改。

全屏静止约 12fps 本身不表示限帧：地图静止少更新，编码器还会每 100ms 补发上一帧；本机实测拖动时升至 60.1fps，保持当前策略，不用重复帧人为填满 60fps。操作中仍低帧率需另行检查来源绘制和编码耗时。

只有卡片/全屏真正切换才发送布局 Intent；同布局的 Surface 重绑不重复发送，相同尺寸也跳过 resize，DPI 在整个保留任务期间保持不变。横屏兼容开关会杀应用，故只在手机版入口回退首次启动时使用，正常 Ucar 不反复开关。

手动 2D/3D 和缩放由仍在运行的地图任务保留，不强制改写未确认的私有配置。地图自身的导航智能缩放仍由地图控制；系统杀进程、Shizuku 重启、APK 升级或重启手机后不能保证原路线和视角仍在。

## 手机端车联提示页的接入边界

用户希望手机高德也进入 CarWith 连接时的车机导航提示页，而不是普通地图。现有 MAP_PREVIEW/UCAR 参数只证明副屏布局可用，不能据此宣称已经接入手机端联动页；LinkSDK 导航数据订阅也不是 UCAR 车联宿主协议。

对照本机高德 17.02.0.2010：UCarMapConnector 通过 kv6 查找 ucar.service.MAP_SERVICE，再经 jv6 验证宿主签名；服务连接后注册 IUCarMapCallback。CarWith 的对应宿主为 com.miui.carlink/com.ucar.map.service.UCarMapService，由 onCarConnect/onCarDisconnect 启停，本次自绘会话现场该组件为 disabled。HyperCar 目前没有这套宿主协议，普通应用自行声明同名 action 也不能证明可通过高德的宿主认证。以上是已发现的协议差异，尚未证明它就是手机提示页的唯一触发条件；不能通过启用 CarWith 的宿主、注入未验证 URI 或改 Intent 参数声称完成原生联动。尚未修改自绘模式的导航行为。

## 返回主页按钮

全屏浮动主页按钮在触摸时显示，空闲约四秒隐藏。用户已确认每次触摸均可出现，这属于自动隐藏行为，不能直接解释为按钮不稳定。返回首页继续显示地图卡片，导航保持运行。

## 键盘

虚拟屏的 OWN_FOCUS 并不保证输入法本地显示。Shizuku UserService 创建受信任屏后、启动 Activity 前，设置并读回 DISPLAY_IME_POLICY_LOCAL；创建日志 ime=local 表示设置成功。未获得 TRUSTED 或 ROM 拒绝时记录 unavailable，保留可用画面，不改主屏及 CarWith 的输入法策略。

2026-11-07 现场原地图屏策略为 FALLBACK_DISPLAY，键盘落在手机。运行中临时切换曾导致输入法未绑定、两端都不显示；565 在新屏启动前设置后，用户确认显示，系统读到输入法所在 display=314、mInputShown=true，地图屏 mIsImeShowing=true。设置读回不能代替显示和触摸验证。

依据：[AOSP 多屏输入法](https://source.android.com/docs/core/display/multi_display/ime-support)。持续回归：搜索输入、收起键盘、卡片/全屏往返、重连及不同输入法。

## 巡航

跟随地图自身设置，不维护独立自动巡航定时器，也不强写未确认 URI/私有配置。检查过的高德17.02.0.2010、百度22.1.20接口不能证明具备通用读写能力。

实现：`MapPreview`、`MapController`、`MapHomeOverlay`、`ShellDisplayIme`。尺寸与触摸原则见[视频](VIDEO-STREAM-REVIEW.md)。
