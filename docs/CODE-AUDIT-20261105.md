# 2026-11-05 代码审计与清理（552）

基线：`37eac8d`。审计起点工作区干净。本轮检查 Android 连接/断开、自动休眠与恢复入口、界面与编码器生命周期、JNI FD 所有权、Rust RTSP 事务与屏幕资源控制，并运行全量 JVM/Rust 测试和 Android Release Lint。静态审查不代替真实车机验证，也不意味着所有模块已穷尽所有状态组合。

## 已修正

| 问题 | 原行为与影响 | 修正 |
| --- | --- | --- |
| P1：旧界面启动与异步销毁互相竞争 | 连接后启动任务未绑定轮次，停止后仍可能创建显示/编码器；旧清理也可能拆新显示 | `SessionResourceLifecycle` 串行创建/销毁；停止同步淘汰旧票据，迟到清理只处理旧所有者；Repo 取消旧启动任务，并校验本轮仍活跃 |
| P1：热点消失没有取消正在协商的任务 | Wi-Fi Joiner 回调直接拆资源，仍在等待端点的连接协程可以继续工作 | 热点回调和 Rust ended 统一走串行断开入口，取消并有界等待连接任务，校验连接轮次 |
| P1：RTSP 超时与取消不完整 | 超时仅覆盖应答，等待写锁/发送无预算；外部取消可遗留 pending；半包发送取消后复用通道会破坏报文边界/加密计数 | 整个事务共享截止时间；RAII 自动移除 pending；半写失败/超时/取消立即标记通道不可复用并取消读任务；已完整发送后的应答取消仍可保留健康连接 |
| P2：编码器启动异常泄漏部分资源 | 配置、创建 Surface、启动回调线程或编码器失败时，资源尚未登记或因 running=false 跳过 stop | 创建后立即登记所有权，失败统一清理；未完全启动也释放 codec/Surface/线程 |
| P2：画面来源可能在同一会话中变化 | Rust 建链时读一次设置，RECORD 回调再读实时设置；中途切换会选错源 | 会话开始固定 CarWith/自绘模式；首次建链、原地重试和界面启动共用该模式，下次连接采用新设置 |
| P2：nativeStart 失败路径丢失控制 FD | JSON/JNI 解析失败发生在接管 FD 之前，Kotlin 已 detach 的 FD 无人释放；旧引擎停止失败仍继续启动 | 进入 native_start_impl 即接管有效 FD，所有失败路径由 RAII 关闭；旧引擎未退出时拒绝重叠启动 |
| P2：单项销毁失败打断后续释放 | 地图或显示释放抛异常可中断编码器和唤醒锁清理 | 各项独立尝试、记录失败，并继续释放余下资源 |

## 已删除的无调用代码

- `SpikeCaster` 的 App 自建 VirtualDisplay、Presentation、动态测试画面及其 start/attachOwnDisplay 入口；生产 D1/D2 仍使用同一编码器和 shell 建屏。
- `nativeDebugTouch` Kotlin/JNI 两端及仅供该测试入口使用的 EngineHandle.touch_tx 字段。
- 已删除调试广播留下的 `debugDumpPairedCars`，过期的探测超时常量和注释。
- 无调用的 `CarProfileGuard.enforceAsync`、`SpikeDisplay.shizukuExec`、`Iap2Csm.u16List`。
- 已由车型档案控制、实际不再读取的全局 `ENABLE_EA_SESSIONS` 常量；保留正在使用的车型 EA 逻辑。
- 不再使用的 CarUiCaster Context/starting 缓存及独立清理线程池；清理由现有 IO 协程和生命周期互斥控制。
- Lint 确认无引用的 `electric_cyan` XML 颜色。

未把“只出现一次的协议字段定义”等同于废弃业务；保留原生协议向量测试、iAP2 导航报文工具、车型兼容分支和 Shizuku 隐藏 API 适配。

## 验证与边界

- JVM：**183 passed / 0 failed**。新增 6 项生命周期测试，覆盖启动中停止、迟到停止、旧轮次启动、连接已失效、初始化异常及取消后的资源清理。
- Rust：**73 passed / 0 failed / 1 ignored**。新增 3 项 RTSP 用例覆盖写锁预算、取消应答等待后的复用、阻塞半写后关闭通道；强化原有 nonce 顺序用例的 pending 清理断言。忽略项需要真实 CarWith 服务。
- Android 完整 `lintRelease` 通过，47 Warning / 2 Information / 0 Error / 0 Fatal；保留的警告主要是既有反射兼容、应用级持有服务对象、目标 SDK 与图标/界面提示，没有为消除警告改变车机兼容策略。
- arm64 原生 Release 重新编译；APK 为 **0.1.0.552 / 10552**。已确认包内原生库与本轮输出一致，签名验证通过，沿用正式证书。
- 551 的手动停止休眠 10 分钟策略及相关测试保留。未修改配对身份、车型连接参数、自动巡航策略或已删除的恢复实验。
- 本轮不自动安装、不提交或推送。尚需实车覆盖：快速停止/启动、自绘与 CarWith 跨会话切换、热点离开/返回、休眠期间自动入口被拦截，以及 BMW 二次上车媒体表现。

产物校验：

- APK SHA-256：`7e789b5ca4520d785e88da51a9efcbf26295ad010a270f173d82cd69a0f3738b`。
- arm64 SO SHA-256：`217134ef1c1e345c578b7bfd854a8c2cf1feef9f1616b597a9bac834fddf30e8`。
- 正式签名证书 SHA-256：`5dd5cc8119f80803fe9933e6f0b7bffff5bf806a6df68d281a901457a9221751`。
