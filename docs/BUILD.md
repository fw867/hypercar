# 编译、安装与发布

更新：2026-11-02。当前默认分支为 `main`，使用 Android 自绘车机 UI 与 CarPlay / AirPlay。
自绘模式需要 Shizuku，无需安装 CarLife、CarWith 或 PC hub；CarWith 模式需要系统已安装并启用 `com.miui.carlink`。

## 构建环境

- JDK 17、Android SDK 35、NDK r27c。
- Rust stable、`aarch64-linux-android` 目标、cargo-ndk。
- 当前 APK 包含 arm64-v8a Rust 库；手机要求 Android 12+。

Windows 已有 `D:\devtools` 工具链时，优先使用仓库脚本：

```powershell
# Rust + 正式签名 APK；N 为本地构建号
pwsh -File tools\build-local.ps1 -Release -BuildNumber <N>

# 仅改 Kotlin / 资源，复用上次编译的 Rust 库
pwsh -File tools\build-local.ps1 -Release -SkipRust -BuildNumber <N>
```

脚本处理中文路径的 ASCII junction 和 Rust 产物目录。签名配置 `keystore.properties` 与密钥文件放在
本地，不提交仓库。正式 APK 为 `app/build/outputs/apk/release/app-release.apk`。

## 手动构建与测试

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
cd rust-core
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
cd ..
./gradlew :app:assembleRelease -Phypercar.buildNumber=<N>
./gradlew :app:testReleaseUnitTest
```

以上手动命令用于已配置好环境的终端。Windows 中文路径优先使用 build-local.ps1；手动运行 Gradle 时使用脚本建立的 ASCII 路径，并将 PowerShell 中的属性参数整体加引号，例如 `'-Phypercar.buildNumber=493'`。

本项目 JNI 层依赖 Unix FD，完整 Rust crate 不能直接当作通用 Windows 程序测试。需要在 Android
执行 Rust 测试时，使用 `cargo ndk -t arm64-v8a test --release --lib --no-run`，将生成的测试可执行文件
推到 `/data/local/tmp/`、赋予执行权限后在手机运行。具体可执行文件路径以 cargo 输出为准。

## 安装与手机要求

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

覆盖安装要求相同签名且 versionCode 不低于已安装版本。CI 使用 Actions run_number，本地使用
BuildNumber；本地构建号高于 CI 时，较低版本的 CI APK 不能直接覆盖本地包。

1. 在首页引导授予必需权限：附近设备、Android 13+ 通知；Android 12/12L 另需定位权限与定位服务。打开手机蓝牙和 Wi-Fi。
2. 选择画面来源：自绘 UI 需安装、启动 Shizuku 并成功授权 HyperCar；未安装入口打开 GitHub 最新发布页。CarWith 需系统已安装并启用 `com.miui.carlink`，不要求 Shizuku。条件未满足时不能启动连接。
3. 自绘 UI 建议安装高德地图并先完成其首次使用提示，以及所需音乐播放器；音乐卡片另需系统“通知使用权”。自动上车需悬浮窗授权，并手动开启「上车自动连接」（新安装默认关闭）。电池与厂商自启动按 ROM 配置，属于建议优化。
4. 车机开启无线 Apple CarPlay。首次在车机侧选择手机并确认配对。自动回连机制已实现，二次上车可靠性仍在实车验证。
5. 增强发现（Android 13+）：在设置的“目标车机”列表直接点击车机，按 Android 系统弹窗授权。
   授权后，“已配对 · 时间”旁显示“增强发现”标签；取消授权仍会保留所选车机。授权可先完成，
   “上车自动连接”开启后才观察并唤醒应用。关闭自动连接会停止观察；切换目标会解除旧车关联。
   此功能补充现有 ACL/Wi-Fi 唤醒，不替代车机的本轮 CarPlay 激活。
6. “蓝牙回连唤醒”默认开启：已配对车机 Wi-Fi、广播与监听就绪 5 秒仍无会话时，通过 Shizuku
   尝试一次最多 30 秒经典可发现窗口。连接开始、停止或到期后清理；无 Shizuku 时跳过。
   详见 [会话控制](SESSION-CONTROL.md)与[验证记录](VALIDATION.md)。

自绘模式连接后 HyperCar 自动创建首页；地图使用安装版高德或百度，音乐从 MediaSession 获取元数据与控制能力。CarWith 模式使用系统 CarWith 的画面与音频。
无需再进入 CarLife 的“无线连接”入口。首页／全屏地图切换保留导航；“返回车机”保持 CarPlay 连接。

## 分辨率与帧率

「拉伸铺满车机屏幕」当前暂时停用，视频按实际编码尺寸发送。CarWith 的源分辨率需在系统 CarWith 中调整，App 提供「打开 CarWith 设置」快捷入口。

优先读取 `/info` 的有效像素尺寸；缺失时使用手机设置页的宽高。使用原生尺寸，仅做偶数向下对齐，
不再应用宽 1920、高 1080 或约 110 万像素上限。默认 60 fps，手动较低帧率仍有效，下次连接生效。
H.264 level 由编码器按尺寸和帧率选择。详见 [推流检查记录](VIDEO-STREAM-REVIEW.md)。

## CI 与 GitHub Release

工作流：`.github/workflows/android-ci.yml`。

- 普通 push：编译 Rust → 正式签名 Release APK → 校验签名及 APK 内 Rust 库 → 上传 Artifact → 单元测试。
- 提交标题末尾加 `[release]` 或 `[publish]`：测试通过后自动创建 GitHub Release，并附 APK。
- 提交标题含 `[skip ci]`：跳过工作流任务；适合仅文档／截图的修改。
- Actions 手动运行并勾选 `publish` 也必须通过测试。
- 版本号：`appVersionName` + `github.run_number + 400`，versionCode 为 `10000 + 构建号`。
- 必需 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；可选
  `EXPECTED_CERT_SHA256`。缺失正式签名材料会失败，不会退回 debug 签名。
- Release 说明记录当前功能、环境要求、适配限制，以及本次提交、APK 大小和 SHA-256。

## 排查

- 在“诊断”页查看和导出会话日志；反馈时附版本、设备、ROM 和 SDK 信息，公开前移除位置及其他隐私。
- 自绘首页无法创建：先检查 Shizuku 是否运行并授权，再看 `CARUI` / `SHELL` 日志。
- 音乐元数据缺失：检查通知使用权，先在手机启动播放器并播放一首。
- 画面比例或触摸位置不匹配：检查 `/info` 像素尺寸、手动兜底尺寸，以及日志中的实际 `stream` 画布。
- `SETUP 403`：检查车机档案、设备身份和系统配对状态。
- 车机未打开 CarPlay 窗口：在原生移动设备菜单选中手机并启用 Apple CarPlay；不要通过反复清除身份解决所有连接问题。
- 红绿斑块：编码后的参考帧不应随机丢弃；已修复该风险，仍需结合实际接收端日志复测。

界面截图与使用方式见 [README](../README.md)；实现分工见 [架构](ARCHITECTURE.md)。
