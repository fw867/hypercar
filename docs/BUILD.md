# 编译指南

## 0. 环境

- JDK 17、Android SDK（compileSdk 35）、NDK（仅 cargo-ndk 需要）
- Rust：`rustup`（stable，≥1.75；let-else/async 闭包等语法用到）
- 目标机：arm64-v8a（宝马场景只出 64 位包）

## 1. 编译 Rust 核心层

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk

cd rust-core
# 产出 libhypercar_core.so 到 app/src/main/jniLibs/arm64-v8a/
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
```

宿主机快速自检（不装 NDK 也能编译检查，注意 JNI/android_logger 相关目标差异）：

```bash
cd rust-core && cargo check
cargo test   # 纯函数（bplist/tlv8/srp/封帧）可在宿主跑单测
```

### 1.1 一键构建（Windows，已装 JDK17 / SDK35 / NDK / rust）

```powershell
pwsh -File tools\build-local.ps1 -Release -BuildNumber <N>   # 全量：Rust + APK
pwsh -File tools\build-local.ps1 -Release -SkipRust          # 只改 Kotlin 时
```

脚本自动处理：非 ASCII 路径（建 ASCII junction 后再构建）、`CARGO_TARGET_DIR`
外置、NDK 环境变量，并把 `.so` 拷到 `app/src/main/jniLibs`。

## 2. 编译 Android 应用

```bash
# 首次：生成 wrapper（仓库未带 gradle wrapper jar 时）
gradle wrapper --gradle-version 8.7

./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 正式包（需 keystore.properties）
```

安装（先杀进程再装，避免确认弹窗落到后台）：

```bash
adb shell am force-stop io.github.hypercar
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. 前置条件（手机端）

1. **Shizuku**：已安装并启动、已授权给 HyperCar（静默入网/真实蓝牙 MAC 都靠它）。
2. **权限**：通知、附近设备（蓝牙）、（可选）悬浮窗——悬浮窗是 Android 12+ 后台
   FGS 启动的豁免来源，**不给则上车自动连接会被系统拦**（手动打开 App 连接一次不受影响）。
3. **本机 CarLife 系 app**：百度 CarLife（≤6.7.2 或 CarWith 内置的 CarLife 连接）、
   小米 CarWith、荣耀车联任一；在 HyperCar 设置里选对唤醒包名。
4. **车机侧**：2023 宝马 X6 ID8.5 与手机完成系统级蓝牙配对；车机开启无线 CarPlay/投屏。

## 4. 首次连接（手动）

1. 手机连上车机热点（或让 iAP2 流程下发 0x5703 自动连）。
2. 打开 HyperCar → 「启动连接」→ 观察状态卡逐步推进。
3. Rust 日志出现 `✓ RECORD 完成` 后会自动广播唤醒 CarLife；CarLife 里选择
   「无线连接/车机连接」入口（不同 app 入口名不同）。
4. 车机屏出现 CarLife 画面即全链路打通；触摸/旋钮应有响应。

## 5. CI 与发布（.github/workflows/android-ci.yml）

每次 push 自动出**正式签名**包（CarDeck 同款机制 + Rust 环节）：

- 构建顺序：判定发布 → JDK 17 → **Rust 交叉编译**（dtolnay/rust-toolchain +
  nttld/setup-ndk r27c + cargo-ndk → `app/src/main/jniLibs/arm64-v8a/libhypercar_core.so`，
  target 与 cargo 目录均有缓存）→ 签名配置 → Gradle Release → 签名校验
  （并核对 APK 内确有 .so）→ 单测 →（可选）发布 Release。
- 版本号自动累计：`appVersionName`（app/build.gradle.kts，发布时只手改这一行）+
  run_number → `0.1.0.<N>`、`versionCode = 1*10000 + N`，每次推送 +1 可覆盖安装。
- **发布条件**：提交标题**末尾**带 `[release]` 或 `[publish]`（或 Actions 页手动
  Run workflow 勾选 publish）→ 构建并跑完单测后自动发 GitHub Release（tag `v<版本>`）。
- 必需 Secrets：`KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`
  （可选 `EXPECTED_CERT_SHA256` 指纹比对）。**没配 KEYSTORE_BASE64 流水线直接失败**，
  绝不退回 debug 签名；Gradle 侧第二道闸：传了 `-Phypercar.buildNumber` 却没有
  keystore.properties 也直接构建失败。
- 签名是不可逆决定：首个公开版本就必须用正式 keystore（debug 证书发出后换证书
  需要所有用户卸载重装）；keystore 与密码只存本地/密码管理器。

## 6. 排查

- App 内「诊断」页有 Rust/iAP2/WiFi 全量日志（等价于 cardeck 的 main_debug.log）。
- 「诊断」页有 **反馈到 GitHub**：预填 issues/new 标题/环境/日志尾部并打开浏览器，完整日志同时进剪贴板（不内嵌 token；用户点 Submit 才发布）。
- 导出的日志头与启动第一行都带 **设备型号 / 系统版本（ROM 显示号 + 编译号）/ SDK / HyperCar 版本号**（ENV: 行），反馈问题请连同这几行一起贴。
- 常见失败：
  - `Shizuku 未就绪` → 启动 Shizuku 并重新授权。
  - `0x5702 后无 0x5703` → 车机未开无线 CarPlay 窗口；在车机上点一次手机图标。
  - 日志里出现 `车机既未声明 0x5702/0x5703，也未声明 0x4301` + `仍在等车机下发……`
    （典型：BMW MGU-55 实车）→ 车机只提供了 iPod/BMW Apps 那套 iAP2 能力，
    **还没把本机当 CarPlay 设备**（cardeck 称「档案 A」）。处置顺序：
    ① 在 iDrive「移动设备」里选中本机、点 Apple CarPlay（链路会一直保持，不必重连）；
       车机重开一轮 iAP2 后日志会出现 `iap2 无线 CarPlay 档案=B`；
    ② 看日志里有没有 `0x1d01（重新识别）`、`0x4c00 原始体`、`会话 N 数据 …`——
       这些行把车机私有通道的报文结构记下来了，是下一步定谳的依据；
    ③ 拿到 0x5703 后 App 会自动入网并广播 `_carplay-ctrl._tcp`，等车机
       `GET /ctrl-int/1/connect` 邀请（日志 `CTRL: ✓ 车机 ctrl 邀请`）后才会有 0x4301；
    ④ 若已知车机热点密码，先手动把手机连上该热点，App 会走「网关兜底 `网关:7000`」。
  - `会话 SETUP 被拒 403` → 车机按 deviceID 过滤；确认设置里的目标车机与本机蓝牙 MAC 匹配。
  - `CarLife 一直 Waiting` → 先确认唤醒包名：HyperOS 上是 `com.baidu.carlife.xiaomi`
    （不是 `com.baidu.carlife`），App 会自动纠错并在日志里写明替换结果，仍不行就在设置里手选。
    若用 PC 侧 hub（盒子角色），这不是"唤醒"问题，而是 **MD 在监听、需要 hub 主动拨过去**
    （`--md-host`），且 CarWith 必须先走一次「车辆热点 → 选车机蓝牙 → 开始连接」。
  - `CarLife CMD 7240 绑定失败: Address already in use` → 7240 被 CarLife（MD）占着。
    这个端口只能有一个主人：要么 MD 监听（盒子角色，HU 拨号），要么 HU 监听（本机 HU，
    需要 MD 有连回环的通道）。两边同时抢就会报这个错。
    **2026-10-25 起自动处理**：连接时先唤醒 CarLife，再探 `127.0.0.1:7240` 最多 2.5s——
    有人监听就把 `md_host=127.0.0.1` 交给引擎走**主动拨号**（我们当车机），没人监听才自己
    bind 监听。日志看 `CARLIFE: ✓ 探测到…主动拨号` / `CARLIFE: 7240 无人监听 → 由 HU 监听`。
    若 CarLife 只在收到 **UDP 7999 广播**后才肯连：**2026-10-25 起也会发**（HU 监听模式下
    每 1s→5s 一条文本 `127.0.0.1:7240`，发往 `127.0.0.1:7999` 与 `255.255.255.255:7999`，
    同时尽力收 7999 的包并打 hex）。注意这是「尽力而为」——公开资料里无线 CarLife 并没有
    发现协议；如果日志里能看到 `CARLIFE: UDP 7999 收到 …`，把那一行贴出来就能按真格式改。
  - 语音助手「麦克风有问题 / 不响应」 → 见 `docs/CARLIFE-PROTOCOL.md`：CarLife 期望
    「车机」供麦（VR `VR_MIC_DATA 0x58001`），或把车机端设置切到「手机麦克风录音」；
    MIUI 会把 CarWith 的后台录音静音（appops 锁在 foreground），此时只能靠本 App 供麦。



