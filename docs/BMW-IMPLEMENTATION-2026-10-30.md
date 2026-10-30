# 宝马连接状态机首轮实现：0.1.0.407

根据 [盒子流程](BMW-BOX-CARPLAY-FLOW.md) 的成功样本和固件调用链，开始修改手机运行代码。本轮不引入 CarLife 交互，不改变已有蓝牙主动连接/首次配对保护策略。

## 实现

- 新增独立 Wi-Fi iAP2：事件通道 `params.data` → JNI → Kotlin 链路引擎；生成的链路字节经有界队列回到 Rust，用控制连接的 `POST /command` 发出。出站不等待上一条 RTSP 应答，避免阻塞事件通道上的独立 iAP2 握手。
- 复用 `Iap2LinkEngine`，Wi-Fi 采用被动 DETECT/SYN 形式及协商会话表；不发竞争性主动 SYN，不使用蓝牙裸 ACK 保活定时器。已有蓝牙调用默认行为保持不变，增加了回归测试。
- 识别、AA00/AA06/AA01、32 字节随机挑战和 AA03/AA05 独立于蓝牙；每阶段超时、拒绝及缺失响应均不继续报成功。与原蓝牙路径一样，当前是兼容交换，尚未校验证书链和挑战签名，日志明确区分。
- 增加设备状态、AE00、4154、4157、4170、5000 的有效空态回应；AD00→AD01 参数0/u8=1 根据固件 `0x63cb4..0x63cc8` 实现。A100 只订阅固件已确认支持的参数6；未知车辆/定位正文仅做链路确认，不猜测值，不启用 EA App。
- `changeModes` 增加实际业务应答 `{status,params}`，并回送当前 `modesChanged`。屏幕、音频分开记录，支持 take/untake/borrow/unborrow、嵌套借用和返回后的永久所有权。非法请求不部分更新状态；显式 requestUI 可以接管未分配屏幕。
- 会话 SETUP、info、事件通道和保活就绪后先 RECORD，再声明资源和建立视频流；Wi-Fi iAP2 独立推进，不等待它的认证全部完成才处理屏幕。
- 视频发送器在发 connected、等待音频 SETUP 之前交给视频泵。新流、切源/借屏恢复、尺寸变化后等待有效配置和 IDR；等待时限频请求关键帧。
- 修正原有 forceKeyFrame 日志有回调、实际监听器却没调用编码器的漏接。资源不归手机时暂停视频帧，继续独立屏幕保活。
- Android timing/音频使用 CLOCK_MONOTONIC，与 Java System.nanoTime/MediaCodec 时间原点对齐。
- 新增配置实际写出、首个 IDR、屏幕保活 opcode/正文长度/计数、真实发送帧数及写失败日志。日志不包含证书/签名/密码正文。
- Wi-Fi 传输使用每轮独立 generation ID；旧队列不能写入新连接，断连/协商失败销毁传输和 worker。TimingServer 由会话持有并随会话结束停止。

## 验证

- Release JVM 测试 **35 项通过**。Wi-Fi 测试包含协商控制 ID、分片 CSM、分片/重复 DETECT、认证前后顺序、超时不自报成功、订阅响应和原有蓝牙主动 SYN 路径。
- Rust Android 测试程序经 ADB 在手机执行，**14 项通过**。包括模拟 RTSP 顺序、业务应答/CSeq、借屏归还与非法变更原子性、实际头部 AAD 解密验证、首配置/IDR 门控、独立明文屏幕保活、旧代际/队列满拒绝，以及出站 iAP 不等待 RTSP 应答。
- `tools/build-local.ps1 -Release -BuildNumber 407` 全量重编 Rust 与签名 APK 成功。

这些验证不是宝马实车稳定性验证，不能据此声称已经完全复刻或彻底修复黑屏。本轮没有重置手机身份或车辆配对记录，也没有推送或发布。

## 仍待实车和协议验证

- 宝马是否不再持续重发 DETECT、Wi-Fi 链路是否进入 READY，资源变更是否获状态确认，首个 IDR 后是否实际出画面，以及会话是否超过此前约8秒的断连窗口。
- 未实现所有优先级/约束组合的完整资源仲裁策略；本轮处理已识别的资源操作和基本借用/归还。未完整恢复的车辆私有字段保留安全摘要日志。
- 407 未实现的 auth-setup / 蓝牙退场在 408 接入，具体范围见下文；仍需实车核验接收端行为。
- 必须保留“收到响应”与“完成标准认证”的区别；本轮 AA05 仍是现有兼容策略，不能当成完成密码学验证。

复测日志重点搜索：`WIFI-IAP#`、`MODES:`、`VIDEO: config written`、`VIDEO: first IDR ready`、`VIDEO: keepAlive written`。不要仅凭编码器输出 fps 或 connected 判定车端成功显示。


## 0.1.0.408：按盒子第二轮补齐 auth-setup 和交接

- 宝马档案（profile ID 以 `bmw` 开头）开启 `bmw_box_bootstrap`；其它档案默认保持原有协商。
- 已有身份仍先 pair-verify，失败才回退注册；不复制盒子的私钥、不重置车辆配对。
- pair-verify 后、会话 SETUP 前执行 `POST /auth-setup`：版本 1 + 每次新生成的 X25519 公钥，共 33 字节。
  严格检查响应边界、非空证书、64 字节签名及非零 ECDH，按 SHA1(AES-KEY/AES-IV || SAP shared) 派生 AES-CTR，解密签名。
  和 CatPlay 客户端一样，这里完成的是交换和结构检查，尚不验证证书信任链/签名真实性。SAP 密钥不会覆盖 HomeKit 控制、事件、视频或音频密钥。
- 初始 screen=controller、audio=accessory。开启应用音频时，先发 `changeModes` 申请主音频（take，priority/takeConstraint/borrowConstraint=500），收到 HTTP 200 且业务 status=0 后才声明新状态并 SETUP 音频；拒绝/超时保留视频会话。
  音频和屏幕分别随接收端 changeModes 的资源状态暂停，资源归还后恢复发送。
- RECORD → 初始资源快照 → 屏幕 SETUP / 首配置与 IDR；Wi-Fi iAP2 同时推进，不因等待它的挑战交换阻塞画面。
- 固件 `_AirplayClientSendCmdDisableBT`（0x434a0..0x43558）确认命令为 `{type:disableBluetooth, params:{deviceID:本机ID}}`。
  Android 版在屏幕就绪后，等待本代 Wi-Fi iAP2 READY + 最近 5 秒内成功的 RTSP feedback，再发送一次；最多等待 15 秒，未就绪则不请求退场。
  这是有意比盒子更保守的时机条件，不是逐毫秒重放盒子的定时器。
- ACL 断开时仅在上述 Wi-Fi 条件成立且本场会话仍存活时继续投屏，并结束本应用 RFCOMM、抑制下一轮蓝牙拨号/入站会话，直到下次连接启动恢复。
  保留 SDP 服务、配对记录与系统蓝牙开关，不调用设备级断开。真实 Wi-Fi 丢失、控制保活失败、事件通道关闭和用户断开仍结束会话。
- Wi-Fi 健康/READY 按 generation 区分，旧连接退出不能清除新一轮状态；日志只记录阶段、长度、状态码和耗时，不输出密钥/证书/挑战正文。

验证：Release JVM 测试通过；Rust Android 测试 18 项全部通过，新增 SAP 对称解密/畸形包、auth-setup→SETUP→info→RECORD 顺序、音频授权拒绝/批准、蓝牙退场新鲜度/代际条件。完整签名包构建成功。实车出画面、蓝牙退场后稳定性尚待复测。

参考实现：本地审阅 CatPlay `fc52bac7910a07ff3b6d94712175db6013528aa9` 的 `core/catplay_hap/src/backend/auth_setup.rs`、`pairing_helper_tx.rs` 与 `modes/change_modes.rs`；交叉验证盒子 090040 日志和固件。未实现所有私有字段或全部资源约束仲裁，不宣称完整复刻盒子。


## 0.1.0.409：桌面接收端音频兼容修正

408 现场 DiPlay 日志：保留了宝马档案，`bmw_box_bootstrap=true`；附加认证成功，但 `changeModes` 返回 HTTP 200、无业务 status，被严格宝马路径判为未授权，跳过音频 SETUP，持续 PCM 有信号而发包 0/s。`HANDOFF: Wi-Fi readiness timeout; no Bluetooth retirement requested` 说明这次未请求蓝牙退场。

- 蓝牙识别阶段的 name/model/manufacturer 绑定当前 RFCOMM 对端身份；换连接/换对端时清除，旧对端的识别结果不能覆盖新地址。
- WinPlay/DiPlay 的实际软件身份优先于保留的宝马档案，关闭本轮宝马 bootstrap，恢复原有初始主音频所有权与音频 SETUP。
- Rust 也按配对前和会话内 `/info` 的 name/model/manufacturer 检查，覆盖无蓝牙识别或首次 info 不可用的情况。
- 真实宝马/未知身份保持选定档案行为，未放宽宝马业务 status=0 的授权要求；蓝牙退场继续仅在 Wi-Fi iAP2 READY + 控制保活正常时请求。
- 新增有效 bootstrap、音频启用/资源授权诊断日志。

验证：38 项 JVM 和 19 项手机端 Rust 测试通过，覆盖保留宝马档案连接 DiPlay/WinPlay、换接收端清除身份、info 识别兜底，以及宝马音频授权拒绝仍不得抢占。

409 安装后自动重连当前 DiPlay，已观察到音频实际发包由 0/s 恢复至连续 136–137/s，采集 PCM 有有效信号；接收端可听效果由用户确认。
