# 协商层对比分析：参考文档 vs cardeck 实测协议

> 对比对象：
>  - **参考文档**：`E:\核心层rust实现.txt`（Kotlin 交 FD + 密钥、Rust 只做数据面转发的方案）
>  - **原项目**：fw867/cardeck（真车逐字节实测修正后的协议实现，含 Audi MIB3 /
>    BMW iD8.5 MGU-86 / WinPlay 的实测记录）
>
> 结论先行：参考文档的架构方向（FD 移交 + Rust 零拷贝数据面）是对的，但它把
> AirPlay 协商整体留在了 Kotlin，且数据面的**线格式多处与真车实测不符**。本项目
> （hypercar）在采纳其骨架的同时做了两件事：① 协商整体下沉 Rust；② 按 cardeck
> 实测协议逐项修正线格式与消息 ID。

## 1. 架构级差异：协商放哪边

| | 参考文档 | cardeck 实测事实 | hypercar 决策 |
|---|---|---|---|
| AirPlay 协商 | Kotlin 完成（pair-setup/verify、RTSP SETUP），Rust 只接「已连通 FD + 已派生密钥」 | 协商状态机极复杂：/info、pair-verify、modesChanged、两级 SETUP、RECORD、事件通道、三条心跳，跨语言传密钥要序列化 80B+ 且方向易错 | **协商下沉 Rust**（`airplay.rs`）；Kotlin 只提供「绑定车机网络的 socket 供给器」（`SocketProvider` 回调 openTcpFd/openUdpFd）。协商状态机只有一份实现，秒连路径少一次跨语言往返 |
| 密钥派生 | Kotlin 派生好交给 Rust（video_key/video_iv/audio_key） | 流密钥 = HKDF(shared, "DataStream-Salt<connId>")，connId 每次 SETUP 随机；「先派生后移交」意味着 Kotlin 必须完整跑完 SETUP | Rust 内自派生（`airplay::stream_key`），Kotlin 完全不接触密钥 |
| CarLife 端口 | 监听 7240~7244 连续端口 | 标准车机端口是 7240/8240/9240/9241/9242/9340，**没有 9440，也没有 7241~7244**；且方向取决于入口：Android 同机由 HU 监听，盒子角色（CarWith 车辆热点）由 MD 监听、HU 拨号 | 只实现 6 个标准端口；`CarLifeHub::start`（监听）/ `connect`（主动拨 MD）两种方向，见 `docs/CARLIFE-PROTOCOL.md` |

## 2. 视频线格式：参考文档按此实现会「真车黑屏」

参考文档的 `video_pipe.rs`：128B 头写 `payload_len + payload_type(0/1) + pts + w/h`，
SPS/PPS 当「明文 type=1」直发，帧用**固定 IV 的 ChaCha20 流密码**。

cardeck 实测（`VideoSender.kt` + docs §9e/§9g）的真车要求：

| 项 | 参考文档 | 真车协议（已实测） |
|---|---|---|
| 帧类型字段 | payload_type 0/1 | **opcode**：0=VideoFrame、1=VideoConfig、2=KeepAlive、5=KeepAliveWithBody |
| VideoConfig | 无（SPS/PPS 当明文帧发） | **必须**单独发 avcC body 的 VideoConfig 帧，且头里 `small_param[1] \|= 0x04`（Encrypted 声明）+ `params[1]=(width,height)` 两个 f32 LE——**缺这两项 Audi 4xxx 整屏黑（会话/触摸/心跳全部正常，日志无任何错误）** |
| 视频帧加密 | ChaCha20 流密码、固定 IV、无 tag | **ChaCha20-Poly1305**：AAD=128B 头本身、nonce=帧计数、body 长度含 16B tag；未配对会话是 AES-128-CTR（密钥流跨帧连续），不是裸流密码 |
| 存活信号 | 无 | **屏幕流内 keepAlive 帧每秒一发**（明文）；缺它 Audi MMI 3315 在 RECORD 后 10.2s 整轮被 reset（与 UDP 心跳、/feedback 相互独立，缺一不可） |
| SPS/PPS 处理 | 粘连包切分后明文发 | 粘连包按 NAL 类型切分，SPS/PPS 变化时转 avcC 发 VideoConfig，VCL 打成「4B BE 长度前缀序列」密封发 |

hypercar 实现：`screen.rs` 完整实现上表右列（128B 头布局、VideoConfig 双字段、
Poly1305 AAD=头、帧计数 nonce、1s keepAlive 节流、avcC 转换）。

## 3. 音频线格式：参考文档按此实现会「真车无声/沙沙声」

| 项 | 参考文档 | 真车协议（已实测） |
|---|---|---|
| RTP PT | 写死 96 | **PT=流类型**（100=PCM 通路；catplay `payload_type = stream_type`） |
| SSRC | 0x424D5738（"BMW8"） | **必须 0**——部分车机按 SSRC 关联 SETUP，随机/自定义值实测无声（2026-10-17） |
| AAD | 整个 12B RTP 头 | **头字节 4..12**（timestamp+SSRC 的 BE 表示，catplay "modern AAD"；DiPlay 解密取同一范围） |
| nonce | 用 RTP 头里的 timestamp+SSRC 拼 | **独立递增计数**（4B 零 + 8B LE 计数，与 seq/ts 无关）；尾部再附同样的 8B |
| PCM 字节序 | 小端 | **大端 s16**（接收端 byteSwap 回小端喂 AudioTrack；漏换序实测「沙沙声」2026-10-10；AAC 通路不换序） |
| 每包样本 | 512 帧 | **352 帧**（AirPlay 标准 SPF；512 在奥迪实测无声） |
| RTP↔NTP 锚点 | 无 | 每秒向流的 controlPort 发**同步包**（PT=0xD4，首包置 X 位），否则媒体时钟漂移 |
| 双路混音 | media/TTS 两个固定 512 帧队列直接相加 | CarLife 的媒体/TTS 格式先归一（采样率/通道数在 0x00020001/0x00030001 里声明），重采样到 48k 立体声再按 352 帧出包；TTS 触发 ducking 需带 ~400ms 尾音保持 |

hypercar 实现：`audio_out.rs`（PT=100、SSRC=0、AAD=4..12、独立 nonce、352 SPF、
大端样本、同步包）+ `engine.rs` 的重采样与闪避混音。

## 4. 控制/事件通道：参考文档完全缺失的部分

1. **控制通道密文帧 MTU**：每帧 `[2B LE len][密文][16B tag]`，单帧明文上限 **1422**
   （1440-2-16）。车机解码侧遇 len>1422 直接判协议违规。
2. **事件通道是双向 RTSP**：车机每个来向请求必须**立即**回 200 OK（echo CSeq），
   不应答 → 车机塞 3 条就断通道（表象「能滑一下」）。命令（hidSendReport、
   iAPSendMessage、modesChanged…）都走事件通道的 `POST /command`，RTSP 控制连接上的
   200 是假确认。
3. **三条相互独立的心跳**：UDP keepAlive（5s，4B，间隔必须**小于**车机 10s 看门狗，
   等长必输——sleep 抖动让心跳永远晚到 deadline 1ms）+ RTSP /feedback（1s）+
   屏幕流 keepAlive 帧（1s）。只做前两条在 MMI 3315 上活不过 10 秒。
4. **modesChanged**：SETUP 前必须在控制通道声明持有主屏(1)/主音频(2)资源
   （entity=permanentEntity=Controller），否则 Audi 对屏幕流 SETUP 直接 400
   （资源仲裁未通过）。
5. **/info 必须带空 bplist 体 + plist Content-Type**；缺体的 GET /info 被部分固件
   回 400/455，此后 SETUP 全灭。
6. **会话级 SETUP 的 deviceID** 必须是本机**真实蓝牙 MAC**（WinPlay 等接收端按它
   过滤会话，对不上直接 403；占位 02:00:00:00:00:00 必失败）。
7. **pair-verify 的 Apple 标识头**：`Content-Type: application/pairing+tlv8` +
   `X-Apple-HKP: 2`（pair-verify 再加 `X-Apple-PD: 1`），缺了 403。
8. **流 SETUP 路径**：初始 SETUP 用 `/setup`（相对路径，老固件对绝对 URI 敏感），
   流 SETUP/RECORD 用 `/`；features 必须发完整 14 项清单（接收端算交集，漏项整个
   能力被关——只提 videoPlayback 时 enabledFeatures=[]、屏幕流 400）。

hypercar 实现：`rtsp.rs`（1422 MTU、CSeq 异步派发、入向请求自动 200）+
`airplay.rs`（上列 1-8 全部内化；三条心跳分别在 `spawn_keepalive_loops` 与
视频泵的 keepAlive 节流里）。

## 5. CarLife 消息层：参考文档与实测协议的差异

> 详见 **[docs/CARLIFE-PROTOCOL.md](CARLIFE-PROTOCOL.md)**（14 步握手 / 两种帧头 / 载荷格式）。
> 本节只列与参考文档冲突、且已按 CarLifeWirelessBox 实测实现纠正的项。

| 项 | 参考文档 | hypercar（按 CarLifeWirelessBox/apollo-DuerOS 纠正） |
|---|---|---|
| 帧头 | 全通道 12 字节 `[len][ts][type]` | **CMD(7240)/CTRL(9340) 是 8 字节** `[u16 len][u16 rsv][u32 type]`；仅 VIDEO/MEDIA/TTS/VR 是 12 字节。写成 12 字节 → MD 侧把握手消息当垃圾 |
| 端口 | 7240~7244 + 9440 | 标准 6 口：7240/8240/9240/9241/9242/9340。**无 9440，无 7241~7244** |
| 握手 | 无（直接发 encoder init） | **14 步事件驱动**：版本→统计→设备信息→特性配置→鉴权(4 段)→编码器 init/done/start |
| 鉴权 ID | 0x00018004 / 0x0001000B | **0x00018048 / 0x00010049 / 0x0001804A / 0x0001004B**（请求/响应/结果/结果） |
| 编码器 ID | 0x00010007 + 0x00010008 | init **0x00018007**(HU→MD)、init_done **0x00010008**(MD→HU)、start **0x00018009**(HU→MD)；**必须等 done 再发 start** |
| 触摸消息 ID | 0x00040001 | **0x68001**；硬按键 **0x68008** |
| 触摸载荷 | protobuf varint | **裸 BE 结构** `[u32 action][u32 x][u32 y]`，action 0=DOWN/1=UP/2=MOVE |
| 音频/TTS 域 | 0x00020001 / 0x00030001 | MEDIA 域 **0x0003xxxx**、TTS 域 **0x0004xxxx**（0x0002xxxx 是 VIDEO） |
| 通道健壮性 | 单次 accept、单客户端 | 每通道单客户端但新连接顶替旧连接；缓冲上限防 OOM |
| 触摸坐标 | 直接透传车机坐标 | 车机显示空间 → CarLife 编码空间缩放（HID 报告按 /info displays 宽高归一） |

## 6. iAP2（蓝牙协商）侧：cardeck 实测教训全部继承

hypercar 的 Kotlin iAP2 层（`iap2/`）按 cardeck docs §3/§4/§5/§9h/§9j 移植，重点：

- **BMW iD8.5（MGU-86）§9j 三修复**：0xAA05 之后**立即**突发设备状态问候
  （0x4E0C/0x4E0A/0x4E0B/0x4E09——车机在等问候、我们若等 0x5703 会 5.4s 被拆链）；
  0xEA00 的 param1 必须是 **EA 协议名字符串**（发整数车机不回 0xEA03）；
  0x5703 分段等待（1.5s 强制重发问候 → 1.3s 重发 0x5702）。
- **§9h SYN-ACK 序号修复**：每条纯 SYN 都回 SYN|ACK 且消耗新序号；同一 SYN 的
  字节级重发幂等；主机路径只回 SYN|ACK（裸 ACK 会与 SYN|ACK 撞号）。
- **MFi 挑战长度阶梯** 32B(3s)→20B(6s)→16B(3s) + 按接收端身份记忆（不同年款偏好相反）。
- **0x5703/0x4301 的地址字段是定长 NUL 填充**：一律 rstrip 全部尾部 0x00（只去一个
  会得到 `10.174.189.1\0\0\0`，能过所有「是否 IPv4」判断但 getaddrinfo 解不了）。
- **0x4301 是生命周期事件**：重复到达都回调端点，推流中的冗余端点由上层忽略
  （防拆健康会话循环）。
- **0x5703 与 0x4301 是两条互补消息，不能取「第一个端点回调」当终点**：0x5703 只有
  热点 ssid/psk（没有 IP/端口），0x4301 才带 AirPlay 端口与车机地址，先后顺序由车机
  决定。WinPlay 实测 0x5703 先到、0x4301 晚 143ms（真车同理）；上层必须**合并两次
  回调并等到有 IP** 再继续，否则会立刻误判「端点无可用 IP」（2026-10-23 19:46:57 现场）。

## 7. 参考文档中直接可用的部分

- FD 移交模型（`ParcelFileDescriptor.detachFd` → `from_raw_fd` → tokio）——保留，
  范围扩大到「协商用的控制 FD + 按需新开的所有端口」。
- iAP2 0x5200 HUD 导航报文构造器（TLV 布局）——保留（`iap2_nav.rs`）。
- 引擎生命周期（全局 tokio runtime、Notify 停止、JNI 常量表）——保留。
- Kotlin 薄壳的「蓝牙触发唤醒 → 绑网 → 移交 FD → 唤醒本机 CarLife」时序——保留并
  补全（Shizuku 静默入网、ACL 冷启动 FGS、目标设备过滤、冗余端点去重）。

## 8. 仍未定谳 / 需实车校准

1. ~~**BMW iD8.5 的 HID 触摸报告格式**~~ → **已定谳（2026-10-24）**：不是奥迪的 5B
   `[tip][X u16LE][Y u16LE]`，而是**每指 6 字节** `[index][touch][X u16LE][Y u16LE]`，
   最多 2 指；载荷在事件报文的**顶层 `hidReport`** 字段（不是 `params.report`）。
   CarLife 侧也不是 12B 裸结构，而是 `CarlifeTouchAction` **protobuf**。
   实现见 `airplay::handle_hid_report` / `carlife::touch_proto`。
2. ~~**CarLife 唤醒广播**~~ → **已实测（HyperOS + CarWith）**：
   `com.baidu.carlife.Action.StartWireless` 对 `com.baidu.carlife` 有效，但小米上
   **没有这个包**（只有 `com.baidu.carlife.xiaomi` 与 `com.miui.carlink`），不带包名
   的广播被静默丢弃 → 表现为「一直 Waiting」。现行做法：包名自动探测 + 三级唤醒
   （应用内广播 → Shizuku shell 广播 → monkey），设置里也可手选。
3. **CarLife 握手已按 CarLifeWirelessBox 重构**（14 步 + 8 字节 CMD 头 + 正确 ID）。
   剩余不确定点收敛为：MEDIA/TTS 初始化载荷的 protobuf 字段号、MD 是否跳过
   MD_AUTHEN_RESPONSE（两者都已做兼容），详见 docs/CARLIFE-PROTOCOL.md §6。
4. **0x5702 是否必须带参数**（BMW 空体接受性）——cardeck 同样未定谳，需要一次
   真 iPhone 同车抓包。

5. **BMW MGU-55（iD8.5 实车，2026-10-24）不下发无线 CarPlay 凭据**：这台车机的
   0x1D01 清单里**没有** 0x5702/0x5703（WiFi 配置）、也没有 0x4300/0x4301（CarPlay
   会话启动），只声明了 iPod/BMW Apps 那一套（0x4C00/02/03/05/07 发送、
   0x4C01/04 接收、0x5000/01、0x6800/01、0xAE00/01、0xEA00/01/03，加 7 条
   `de.bmw.a4a.*` EA 协议），识别里也没有 WirelessCarPlayTransportComponent。

   **与 cardeck（fw867/cardeck）逐项对照后定谳**：cardeck PROTOCOL-NOTES §9j 记录
   BMW iD8.5（MGU-86）有 **A/B 两套能力档案**，本车当前这两轮全是 **档案 A**：

   | 档案 | 发送清单 | EA 协议 | 0x5702/0x5703 |
   |---|---|---|---|
   | **A**（本车现状，14 项） | 无 0x5703 | `de.bmw.a4a.bt` protocol=7 | 收=否 / 发=否 |
   | **B**（真车正常无线 CarPlay，16 项） | 含 0x5703、0xaa06 | `com.bmw.carplay` protocol=8 | **收=是 / 发=是** |

   档案 A = 车机**没开无线 CarPlay 窗口**（真 iPhone 此时也不会被当成 CarPlay 设备）；
   档案 B 才是能跑通的那一档。对照 cardeck 后本仓补齐了四处缺失（旧实现正是因为这四处，
   即使车机进了档案 B 也走不通）：

   1. **0x4E0D WirelessCarPlayUpdate（u8=1）**：真 iPhone 在 0xAA05 之后会发这条
      「本机支持无线 CarPlay」的可用性通知；旧实现只发 0x4E0C/0x4E0A/0x4E0B/0x4E09。
      本车可收清单里**恰有 0x4E0D**——车机很可能就是靠它判断对方是不是 CarPlay 设备。
   2. **0x4E0E DeviceTransportIdentifier（蓝牙 MAC）**：真机顺序是 0x4301 之后回它，
      车机按它把 iAP2 设备与 RFCOMM 对端关联（发后不管，Audi 实测从不 ACK）。
   3. **`_carplay-ctrl._tcp` mDNS 广播 + `GET /ctrl-int/1/connect` 应答**
      （`net/CarPlayCtrlAdvertiser.kt`）：真车是**邀请驱动**——车机在 WiFi 上发现该服务后
      反连手机发邀请，**邀请成功才下发 0x4301**。旧实现完全没有 mDNS，而且「等 0x4301 才
      入网」= 双方互等死锁（cardeck 在奥迪上实测过同一死锁）。现在 0x5703 一到就
      入网 + 广播（TXT `id`=真实蓝牙 MAC、`srcvers`=950.7.1，两者改动需先真车复测）。
   4. **0x6800 StartHID → 0x6801 应答**（`[u16 0][u8 0]`）与**EA 协议逐条开**（修掉
      同 id 重复参数被 `associateBy` 压成一条的 bug，BMW 的 7 条 EA 协议里
      `de.bmw.a4a.pairing` 从未被打开过）。

   现场处置（已实现）：
   - **档案 B**：拿不到凭据时不再拆链（旧行为 throw → 关 RFCOMM → 车机断 ACL → 上层拆会话 →
     3s 后重连，形成连-断死循环）；链路保持 + 5s/30s 节奏重探 0x5702/0x4300、补开未确认的
     0xEA00，等车机下发 0x5703。
   - **档案 A**：快速退场（见下），不握着会话挡车机下一轮连入。
   - 未知消息体（尤其 0x4Cxx）与非控制会话数据帧的原始体各 dump 一次；档案 A/B 直接判读。

   **cardeck Issues 里两份 iD8.5 日志的对照（2026-10-24）**：

   | 日志 | 车机 | 轮次与档案 | 0x5703 | 车机拆链时刻 |
   |---|---|---|---|---|
   | `CarDeck-log-20261021-202434`（0.1.12） | BMW 31726 / MGU-86-BMW | 20:22:39 **A**(14 项) → 20:23:07 **B**(16 项) → 20:23:20 **A** → 20:23:43 **B** | **一轮都没到** | 每轮在 0x5702 之后 **5.4~6.5s** |
   | `CarDeck-log-20261022-135133`（0.1.17-debug） | BMW 17713 / MGU-86 | 5 轮**全是 B**(16 项) | **一轮都没到** | 每轮在 0x5702 之后 **5.37s** |
   | 本仓 2026-10-24（BMW ***** / MGU-55-BMW） | 11:01:21、11:01:31 两轮**全是 A**(14 项) | — | 无 | 我们自己在 1.3s 内关的链 |

   三条结论：
   ① **A/B 由车机单方面决定，且会来回切**（同一台车 27 秒内 A→B→A→B，与手机发什么无关）；
      判据（四条，任一命中即 B）：发送清单含 `0x5703`、可收清单含 `0x5702`、
      多一条 EA `com.bmw.carplay`(protocol=8)、多一个 **WirelessCarPlayTransportComponent**
      （参数 id=0x18=24，component 名形如 `BMW 31726 Wifi`）。识别体大小约
      565B(A) vs 646~650B(B)；本仓 `Iap2Messages.Identification.hasWirelessCarPlayComponent`
      已解析该组件。
   ② **这两份 cardeck 日志其实也是失败的**（都没拿到 0x5703）：0.1.12/0.1.17 把设备状态
      问候排在 0x5702 超时之后才发，车机在 0x5702 后 5.4~6.5s 判「accessory 未就绪」拆链——
      正是 §9j 修复前的签名。本仓现在的顺序（0xAA05 之后立刻发 0x4E0D/0x4E0C/0x4E0A/
      0x4E0B/0x4E09，然后才 EA → 0x5702）已经是修好的那一版。
   ③ 所以对本车要做的不是「握着一轮等」，而是**在档案 A 时快速退场、让车机开下一轮**——
      而且 `Iap2Bluetooth` 的 `sessionActive` 门闸会让「握着 A 会话」直接挡掉车机下一次
      连入（它会回 `已有 iAP2 会话在跑，忽略新的连入`），B 轮永远轮不到。
      现行实现：能确认是 BMW 形态且档案 A → 抛 `NoCarPlayWindowException`，只关这条
      RFCOMM、不拆上层会话，车机下一次连入即接住；档案 B 才进入「保活 + 等 0x5703」。`

   仍需现场确认：a) 多轮重连里是否出现**档案 B**（现在档案 A 会快速退场换下一轮，
   日志里看 `无线 CarPlay 档案=B`）；b) 档案 B 下 0x5703 是否下发、ctrl 邀请是否到达
   （日志 `CTRL: ✓ 车机 ctrl 邀请`）；c) 若多轮始终只有档案 A，说明车机认的是「iPhone
   蓝牙 EIR 里的 CarPlay UUID」（`2D8D2466-E14D-451C-88BC-7301ABEA291A`，Android 侧
   做不出来）——那就只能走手动入网 + ctrl 广播 + 网关 7000 兜底这条非 iAP2 路径。

6. **从 cardeck 0.1.14 / PR #19 借鉴过来的三条（2026-10-24，已实现）**：

   - **`wifiGen` 分派取代「清单硬门禁」**（`iap2/ReceiverProfile.kt` + `Iap2SenderSession.run`）：
     旧实现 `legacyWifiExchange = declaresReceive != false && declaresSend != false`，
     车机清单没写 0x5702 就跳过旧协议。cardeck 在奥迪 MMI 3315 上定谳这是错的：
     **清单只能证明「有」，不能证明「没有」**——奥迪稀疏清单（发 2 / 收 5 项）不含
     0x5702，但它完整支持 0x5702→0x5703，且**要靠收到 0x5702 才把 AirPlay 服务拉起来**
     （0 次 0x5702 的那版：端口 7000 全程 ECONNREFUSED、mDNS 不广播、4 轮全灭）。
     现行：只有「两清单合计 ≥10 项仍无 0x57xx」（BMW iD9 形态）才判 NEW 跳过 0x5702；
     稀疏/未声明一律发（`0x5703` 有超时兜底）。同理 **0x4E0E 也不再按清单拦**
     （同日日志里 0x4E0D/0x4E0B/0x4E0E 不在清单里，车机逐条 ACK 到位）。
   - **新协议车机「声明会发 0x4301 但窗口内未到」不再拆链**（PR #19，凯迪拉克 CT5）：
     0x4301 是活事件，GM/Cinemo 与 BMW iD9 同族车机会晚发或等前置条件（奥迪 MMI 9310
     晚 2 分 35 秒）。超时无端点时**保持链路**、等晚到 0x4301 重新回调；端点等待上限
     180s → **300s**；同时确认「空端点不得缓存空 SSID」（否则保活期 `wifi ?: fromSession`
     会用空凭据盖掉晚到 0x4301 自带的 AP 信息——本仓本来就没缓存空值，保持不动）。
   - **ctrl 广播端口固定**：重注册只换 mDNS 注册、复用 socket 与 accept 线程
     （cardeck 实测旧实现每次换端口 43857→33383→…，中间最长 1.2s 服务空窗，依赖邀请的
     车机会连到已关闭的 socket；`acceptLoop` 的循环条件也不能绑 `registered`，
     否则重注册瞬间线程退出、之后再没人接邀请）。
   - 顺带移植 **MFi 记忆「硬拒绝」**（cardeck 0.1.25 / commit 1cd903c）：挑战期间被对端
     断链是强证据，即使该长度等于已记「成功」也要推翻（迟到应答会把上一档的应答记到
     当前档头上，记忆一旦锁死就再也试不到正确长度）。
   - **静默网关试探（用户 2026-10-24 要求）**：等端点期间每 5s 看一次手机当前 WiFi；
     **等待期间出现新 SSID 时**（或该 SSID 正是 iAP2 给的车机热点）就静默把该 WiFi 的
     默认网关当车机热点试一次 `网关:7000`：
     成功 → 直接合成端点继续（`ssid` 用当前 SSID、端口 7000、进程网络已绑定，不再等 0x4301）；
     失败 → 记一行日志继续等（保活链路不受影响）。每个 SSID 只试一次；手机上原本就有的
     WiFi 不会被误试（家里可能有 Apple TV 占着 7000）。对应 cardeck PR #19 的
     `watchForPendingStartSession`，只是把「弹窗确认」换成了静默试一次。



## 9. 真车实测：BMW MGU-55（2026-10-24）

一次完整的「连不上」日志（`hypercar-log-20261024-181315.txt`）逐段判读：

| 阶段 | 现象 | 结论 |
|---|---|---|
| iAP2 建链 | `0x1d00/0x1d01` → `0x1d02`，`0xaa00/0xaa01` MFi → `0xaa02/0xaa03` → `0xaa05` | 正常，车机 `BMW ***** / MGU-55-BMW` |
| EA 会话 | 连续 `0xea00`（8 个 a4a 协议），**`0xea03` 始终未到** | 车机不确认也不影响后续，先忽略 |
| 热点凭据 | `0x5702` → `0x5703`：`ssid=DIRECT-BMW ***** channel=157 security=2` | 正常 |
| 入网 | 静默加入 + 进程级绑定成功 | 正常 |
| ctrl 广播 | `_carplay-ctrl._tcp` 注册成功，车机 `172.16.*.*` 主动 `GET /ctrl-int/1/connect` | **车机已开 AirPlay 窗口** |
| 端点地址 | `0x4301` **永远不来**；网关兜底也失败（DIRECT- 链路无默认网关，`0.0.0.0:7000`） | **卡点** |
| 结果 | 15s 超时 → 主动拆会话 → 车机 3s 后重连 → 循环 | 表现为「连不上」 |

**根因**：这台车在 `0x1d01` 的报文清单里明确声明 `CarPlayStartSession(0x4301)=不发`。
我们的端点来源只有「0x4301 里的地址」和「热点网关」，两条都不成立 → 死等。

**解法（已实现）**：车机来邀请时的 **TCP 对端地址就是它在热点网段的地址**，
收到 `GET /ctrl-int/1/connect` 即视为端点可用，取 `对端IP:7000` 继续：

```
CTRL: 172.16.*.* -> [GET /ctrl-int/1/connect ...]   ← 邀请
SESSION: ✓ 车机已邀请（172.16.*.*）——用它当 AirPlay 端点 :7000
```

> 端口 7000 是 AirPlay 惯例值；若某车型不是 7000，下一步改成浏览 `_airplay._tcp`
> 取准确端口（车机一般会广播该服务）。

### 9.1 连接阶段日志格式（便于对照排查）

自 10012 起，连接链路的每行都带「阶段 + 相对本轮起点的毫秒」：

```
CONNECT +0ms     端点确定：<ip>:<port> ssid=… 有密码=… ips=[…] gateway=… devId=… srcvers=…
WIFI +820ms      ✓ 已绑定网络（进程级）：if=wlan0 ipv4=[172.16.*.x/24] ipv6=1 gw=- dns=[…] validated=false
NET  +1100ms     ✓ <ip>:7000 已连通（Wi-Fi 网络 #N，耗时 63ms，fd=57）
CORE +1150ms     引擎配置：{"width":2560,…}
```

排查时直接看**最后一条 +Nms** 就知道停在哪一步：停在 `WIFI` = 入网/绑网问题，
停在 `NET` = 端口/路由问题（每行都带失败原因与耗时），进到 `CORE` 之后才轮到
Rust 侧的 `AIRPLAY:` 日志。

## 10. 真 iPhone HCI 对照（2026-10-24，同一台 BMW MGU-55）

### 10.1 数据来源与解析方式

iPhone 上抓 `sysdiagnose`，取出 `logs/Bluetooth/bluetoothd-hci-latest.pklg`（Apple PacketLogger 格式）。
**抓取步骤（描述文件怎么装、按键怎么按、文件在哪）见
[docs/IPHONE-HCI-CAPTURE.md](IPHONE-HCI-CAPTURE.md)。**
报文是**明文**，可用如下方式定界：

- 帧同步 `0x40 0x40` 之后是 iAP2 负载；本项目的记录布局是
  `40 40 | len(u16 BE) | msgID(u16 BE) | params…`（`len` 只覆盖 msgID+params 之后的部分）。
- 记录容器是 `[len u32][ts_sec u32][ts_usec u32][type u8][data…]`；其中
  `ts` 存的是**本地时间当 UTC**，换算回本地要减时区。
- 参数按 `[len u16][id u16][value]` 或 TLV8（`<type><len><value>`）解析。

> 隐私：本文只保留协议结构。设备名、热点 SSID、PSK、蓝牙 MAC、传输 UUID、
> 车机标识串、IP 末段一律以 `*` 掩码；抓包文件本身不入库。

### 10.2 同一台车机对真 iPhone 的报文清单

`0x1D01` 的发送/接收清单与对我们的**逐字节一致**，关键声明也一致：

```
CarPlayStartSession(0x4301) = 不发        ← 这台车永远不主动给 AirPlay 端点地址
StartHID(0x6800)            = 会发
可收：0x4C01 0x4C04 0x4E09 0x4E0A 0x4E0B 0x4E0C 0x4E0D 0x5001 0x5702 0x6801 0xAE01 0xEA00 0xEA01
       （注意：**没有** 0x4300，也没有 0x4E0E）
```

### 10.3 真机的 iAP2 时间轴（相对首条报文）

| 时刻 | 方向 | 报文 | 说明 |
|---|---|---|---|
| +0.00s | 手机→车机 | `0x1D00` StartIDPS | |
| +0.16s | 车机→手机 | `0x1D01` IDPS | 清单见上 |
| +0.30s | 手机→车机 | `0x1D02` IDPSAccepted | |
| +0.30s | 手机→车机 | `0xAA00` DevAuthInfo | |
| +0.51s | 车机→手机 | `0xAA06` DevAuthFailed | 第一次签名被拒（随后重试成功） |
| +0.51s | 手机→车机 | `0xAA02` DevAuthChallenge | |
| +1.07s | 车机→手机 | `0xAA03` DevAuthSignature | |
| +1.14s | 手机→车机 | `0xAA05` DevAuthSucceeded | |
| +1.14s | 手机→车机 | `0x4E0C` TransportComponent | 载荷含一个 UUID（本文掩码） |
| +1.15s | 手机→车机 | `0x4E0A` | |
| **+1.32s** | 车机→手机 | **`0xAE00` StartPowerUpdates** | 车机要"电源状态上报" |
| +1.51s | 手机→车机 | `0x4E0B`、`0x4E0D` | |
| **+1.51s** | 手机→车机 | **`0xAE01` PowerUpdate ×3** | **真机会回，我们此前从不回** |
| +1.57s | 车机→手机 | `0x5703` WiFiConfigResp | ssid/psk/channel/security |
| +2.45s | 手机→车机 | `0x5702` 再请求一次 | |
| +2.47s | 车机→手机 | `0x5703` 第二次 | |
| — | — | （此后无 iAP2 报文） | AirPlay 全部走 Wi-Fi |

真机**从不发送**：`0x4300`、`0xEA00`、`0x4E0E`；
真机**从不接收**：`0x4301`、`0xEA03`。

### 10.4 链路存活：抓包里能证明什么、不能证明什么

时间对齐后（pklg 时钟 = 本地时间当 UTC）：

- iAP2 交换发生在抓取结束前约 34 秒；
- **这 34 秒内没有任何 `DisconnectionComplete`**，且最后一条记录正好是
  sysdiagnose 抓取结束的时刻 → **日志结束是抓取结束，不是链路断开**；
- 这 34 秒里真机**一条 iAP2 报文都没发**（连电源更新/keep-alive 都没有）。

由此可得的下限结论：**拿完 0x5703 之后至少 34 秒，真机不会主动拆 iAP2**，
且保活**不需要周期性发消息**。至于更长时间是否拆，这份抓包无法回答（需要一次完整会话的 sysdiagnose）。

> 反例检查：若"车机在 34 秒后自动关机"，ACL 必然先断，日志里应有
> `DisconnectionComplete`——实际一条都没有，故该解释不成立。

### 10.5 由对照得出的实现决策

| 行为 | 旧实现 | 决策 | 依据 |
|---|---|---|---|
| `0x4300` CarPlayAvailability | 无条件发送 | **仅当车机声明可收才发**（MGU-55 声明里没有 → 不发） | 真机从不发；硬发可能把车机推进"会话已请求"态 |
| `0xEA00` EA 会话 | 逐条开 8 个 | **默认关闭**（`ENABLE_EA_SESSIONS=false`） | 真机一条不发，车机也从不回 `0xEA03` |
| `0x4E0E` 传输标识 | 发送 | **默认关闭**（`SEND_TRANSPORT_IDENTIFIER=false`） | 真机不发 |
| `0xAE01` PowerUpdate | **从不回** | **收到 `0xAE00` 立即回 3 条**（原样重放真机字节） | 真机必回；参数语义未定谳，故只重放不解释 |
| AirPlay 失败后的 iAP2 | 立即拆除（~5s） | **原地重试 6×2.5s，不拆链路** | 真机 ≥34s 不拆；拆除会让车机收起 CarPlay 图标 |
| AirPlay 端点端口 | 写死 7000 | **mDNS `_airplay._tcp` 优先，失败退 7000/7001/7100** | 车机确实广播该服务（unified log 可见）；写死 7000 收到 `455` |



## 11. 2026-10-25 现场：CarPlay 图标消失（"车机不把手机当 CarPlay 设备"）

日志 `hypercar-log-20261025-114131.txt`（约 10 分钟）：ACL 反复连上/断开，但
**一次 iAP2 建链都没有**——没有 `BT-S: 车机已连入`、没有 `LINK tx/rx`、没有
`0x1d01`；出站 `BT-C: 连接 BMW 53636 失败` 每 3s 一次。用户现场描述更关键：
**车机的 CarPlay 图标消失**，手机在 iDrive 里只剩"电话/语音"，被当成普通蓝牙设备；
而 **0.27 版本图标是出现的**。图标不出现，车机就不会主动来连 iAP2，形成死锁。

### 11.1 版本 A/B：0.27 与之后的差别

| 版本（提交） | 会话里发什么 | 链路行为 | 现场结果 |
|---|---|---|---|
| 0.27（`d69ab5a`） | `0x4300` + `0xEA00`（7 条 EA）+ `0x4E0E` + 问候五连（含 `0x4E0D`） | **保活不拆链** | **CarPlay 图标出现** |
| 0.28（`d44c81e`） | 同上 | **档案 A 2.5s 主动结束会话** | 图标消失风险（§10.5：拆链会让车机收起图标） |
| 0.29（`a194910`） | **停发 `0x4300`/`0xEA00`/`0x4E0E`**（按真 iPhone HCI 对照） | 同上 | 车机把手机降级成"只有电话/语音的普通蓝牙设备" |
| 0.31+（`c00e6df`/`0d2aa4d`） | 同上 + 档案化 | 端点 300s 超时后 `fail()` 拆链 | 10 分钟 0 次 iAP2 发起（本日志） |

结论：**图标是被我们自己弄丢的**，两条独立原因叠加：

1. **停发 `0x4300`（与 `0x4E0E`）**。0x4300 的载荷就是"无线组件 + 本机蓝牙传输标识"，
   车机靠它把"CarPlay 设备"与已配对的那台手机对上。真 iPhone 的 HCI 里看不到它，
   并不等于车机不需要它（抓包那次车机已经认识这台 iPhone）——现场 A/B 比抓包缺失更强。
2. **主动拆 iAP2**（档案 A 快退 + 端点超时 `fail()`）。§10.5 已经写明"拆除会让车机
   收起 CarPlay 图标"，这两处正好在拆。

### 11.2 处理（已实现）

- `HeadUnitProfile` 新增两个字段，宝马档案取 0.27 值：
  - `sendCarPlayAvailability = true`（并**不再看可收清单**——清单只能证明"有"）、
  - `sendTransportIdentifier = true`、`openEaSessions = true`；
  - `abortWhenCarPlayWindowClosed = false`：档案 A 只记日志、**不结束会话**；
  - `dropProfilesBeforeIap2 = false`：不主动断车机 HFP/A2DP。
- 等端点超时不再 `fail()` 拆 iAP2，改为提示 + 继续下一轮等（直到用户/ACL 断开）。
- 「空转轮次」允许被车机的新连入顶替（`sessionProductive`）：既不拆链，也不把车机
  下一轮（可能是档案 B）挡在 `sessionActive` 门外。
- iAP2 身份一律用真实 MAC（`force=true`）：0x4300/0x4E0E 里的传输标识必须与已配对
  手机一致，伪 MAC 会让车机对不上任何设备；伪 MAC 时打显式警报。
- iAP2 目标锁定为本轮 ACL 连上的车机，不再去拨 HI-BOX 旧 dongle；`sessionStartCount`
  为 0 时日志直接指出"车机没发起 iAP2（车机侧状态）"。
- **与车机档案配置的冲突（同步修掉）**：`profiles/headunits.json` 里宝马条目原本写着
  `sendCarPlayAvailability/openEaSessions/sendTransportIdentifier = false`，而
  `RemoteProfiles.merge` 是"远程同 id 覆盖内置"——**设备上缓存的旧 JSON 会把上面的修复
  原样顶回去**，图标继续不出现。另外新加的两个档案字段没进 `readProfile/encode`，
  远程条目会拿到数据类默认值（`dropProfilesBeforeIap2=true`，正好是宝马的反例）。
  三处一起收口：① JSON 升 `_schema: 2` 并改回正确值；② 解析默认值改为「同 id 内置档案」；
  ③ `_schema < RemoteProfiles.SCHEMA(2)` 的远程条目**只能新增车型、不能覆盖内置**
  （要修内置必须显式升 `_schema`）。运行期还会打一行 `PROFILE: <id>（<label>）…` 自述
  当前档案与关键开关，避免再靠日志反推。

### 11.3 恢复步骤（必须做，代码只能保证不再丢）

图标消失后车机不会主动连 iAP2，我们也发不出 0x4300，所以**需要一次重新配对**让车机
重新评估：

1. 装新包，保持 App 运行（监听器在，SDP 里才有 iAP2 服务）；
2. iDrive →「移动设备」→ 删除本机；手机蓝牙设置 → 忽略 BMW；
3. 重新配对，**车机弹提示时选 Apple CarPlay**；
4. 看日志应出现：`AUTO: 本轮 iAP2 目标锁定…` → `BT-S: 车机已连入` → `iap2 rx=0x1d01`
   （`档案=A/B`）→ `iap2 tx=0x4300 CarPlayAvailability（…mac=真实MAC）`；
5. 图标应恢复；之后会话即使停在档案 A 也不会再拆链，图标不会再被我们弄丢。

若重新配对后仍然 0 次 iAP2：先用真 iPhone 在该车上确认车机 CarPlay 本身正常（排除
车机侧被其它设置关掉），再导出日志看第 4 步停在哪里。

## 12. 2026-10-25 车友日志：卡在 AirPlay `/info` 455（BMW 17713 / MGU-86）

日志 `hypercar-log-20261025-105328.txt`（Xiaomi 23127PN0CC / Android 16，0.32 版）。
**这次不是"图标不出现"**——整条链路都通了，只差 AirPlay 第一步：

| 阶段 | 实测 | 结论 |
|---|---|---|
| iAP2 识别 | 16 项清单、含 `0x5703`/`0xaa06`、EA 有 `com.bmw.carplay`、有 WirelessCarPlay 组件 | **档案 B**，车机很配合 |
| 0x5702 → 0x5703 | 600ms 内回 `DIRECT-BMW 17713` ch157 | 正常 |
| 入网 | 342ms 静默加入（172.16.222.105/20，**gw=0.0.0.0** 的 Direct AP） | 正常（网关兜底对 Direct AP 无效，靠邀请 IP） |
| ctrl 邀请 | 车机 172.16.222.97 `GET /ctrl-int/1/connect`（2s 后） | 正常 |
| 端口 | 7000 开（12ms），7001/7100 关；`_airplay._tcp` PTR/SRV/A 全 0 | 车机不广播 mDNS |
| **AirPlay `/info`** | `GET`/`POST`/`GET` 三次全 **`455 Method Not Valid In This State`**（`server=AirTunes/450.14`） | **卡点**，我们把它当致命错误退出引擎 |
| 6 次重试 | 每轮都是同样三条 455 | 全部无效 |
| 车机行为 | 邀请后 **21s**（10:52:55 → 10:53:16）拆 RFCOMM；重连后给**档案 A** | 窗口很短 |

时间账：`0x5703`(10:52:52.8) → 拆链(10:53:16) 共 **23.3s**；我们从 0x5703 到连上 7000
花了 **8.4s**（其中 **3.1s** 白等 5s tick 才把邀请变成端点、**3s** 白查 mDNS），
只剩 ~15s 给 AirPlay 协商——而 `/info` 一次就 3 连败。

### 12.1 修了什么

1. **Rust `/info` 改 best-effort**（`airplay.rs`）：455/403 只记日志，**继续
   pair-setup → pair-verify**，配对成功后再补查一次 `/info`；仍拿不到就用设置里的
   分辨率与默认能力跑 SETUP。依据：cardeck 真车记录「车机对**未配对连接**上的 /info
   应答 455」——正确顺序是先配对再查，/info 只是"增强"（拿显示尺寸/features）。
2. **邀请不再等 5s tick**：`ENDPOINT_TICK_MILLIS` 5s → 1s（邀请→端点最坏 1s）。
3. **邀请来的端点跳过 mDNS**：`CarPlayEndpoint.fromInvite`，地址/端口是车机亲口给的
   （这台车不广播 `_airplay._tcp`，browse 只会白花 3s）。
4. 档案 A 不再主动退场（本日志那一版还是旧行为：重连后 2.5s 就把会话结束）。

改完「凭据 → 连上 7000」的理论耗时从 8.4s 降到 **~2s**，把 23s 窗口基本都留给 AirPlay。

### 12.2 仍未验证 / 下一步

- **pair-verify 能否通过**：`/info` 455 之后我们没走到过配对，下一轮日志看
  `PAIR: pair-setup …` / `pair-verify …` 与 `SETUP` 的状态码即可定谳。
- 若 pair-verify 也回 455/403：真机成功路径是 **IPv6 link-local:7000**（cardeck 两车
  实录），而我们目前只连邀请里的 IPv4。下一轮可把「邀请 IPv4」与「该接口的 IPv6
  link-local」作为双候选逐个试（`::1` 之类不能算，必须从 `boundNetwork` 的 LinkProperties 取）。
- 这台车的 Direct AP 没有默认网关（gw=0.0.0.0），所以「网关兜底」对它天然无效——
  端点必须来自 ctrl 邀请（或 0x4301，但该车声明不发）。

## 13. 2026-10-25 现场：宝马 DIRECT 热点**没有网关**，端点发现三条路全断

日志（app 0.1.0.x，本地构建）：两次连接都是同一死法，iAP2 侧一切正常。

```
0x5703 ssid=DIRECT-BMW 53636 psk=***        ← 只有凭据，没有 IP
0x4301 未到（车机声明「不发」）
WIFI +9124ms ✓ 已绑定：ipv4=172.16.222.105/20 gw=0.0.0.0 dns=[] validated=false
SESSION: 端点缺 IP —— 继续等 0x4301
仍在等车机地址（port=0 ips=[]，已等 30s）
LINK closed: bt socket closed, read return: -1   ← 车机 30s 后自己拆 iAP2
```

### 13.1 为什么三条兜底同时失效

| 兜底路径 | 失效原因 |
|---|---|
| 车机 ctrl 邀请（`lastInvitePeerIp`） | 车机从未连我们的 `_carplay-ctrl` 端口（日志只有「已监听/注册成功」） |
| 网关当车机（`probeGatewayAsCarHotspot`） | **宝马 DIRECT AP 不回默认路由的网关**：`gw=0.0.0.0` → 取不到 `defaultRoute.gateway` 就 `continue`，连探测都没发生 |
| mDNS `_airplay._tcp` | 只在**端点确定之后**才调用（`openControlFd` 之前那一段）；等端点时正好没查，形成死锁 |

车机侧的行为是明确的：给完凭据后等 30s，手机不来 AirPlay 就拆 RFCOMM。

### 13.2 修法：入网后立刻"主动找"

在 `awaitUsableEndpoint` 的等待循环里，凡是「当前 SSID == 0x5703 给的热点」且还没试过，就按顺序做：

1. **mDNS**：在该 `Network` 上 browse `_airplay._tcp`（车机若广播，这一步就定端点）；
2. **本网段扫描**（网关为空时的唯一出路）：
   - `/proc/net/arp` 里已出现的邻居（车机回过包就一定在）；
   - 自己 /24 的 `.1`/`.2`/`.254`；
   - 掩码 < /24 时（宝马 /20）每个 /24 的 `.1`/`.254`，上限 48 个；
   - 并行 TCP 探测 `7000/7001/7100`，单个 250ms，命中即合成端点交给后续 pair-verify。

日志会明确打出命中或落空：

```
WIFI: 网关为空——在 172.16.222/20 扫 AirPlay（候选 48 个地址 × 端口 [7000, 7001, 7100]）
WIFI: ✓ 网段扫描命中车机 AirPlay 端点 172.16.x.x:7000（420ms）
```

> 教训：**不要假设热点一定有网关**。iPhone 之所以能连上，是因为它用 Bonjour 找到 AP 里的
> `_airplay._tcp`（或直接用保存的地址），而不是"网关 + 7000"。我们之前把"网关"当成等价物，
> 在这台车上等于零候选。

## 14. 2026-10-25 对照：宝马专用 CarPlay 盒子连 WinPlay 的真实交互

手上有一台 RK3528A 的「宝马专用 CarPlay 盒子」（伪装成 iPhone：插电脑枚举为
`VID_05AC&PID_12A8`，MTP 设备，**无 ADB**）。它除了连车机，也能直接推给 WinPlay，
所以我们在 PC 侧用系统自带 `pktmon` 抓了一次（`tools/capture-airplay.ps1`）。
**这是"能用的发送端"长什么样的第一份实证。**

### 14.1 抓到的关键事实

| 观察 | 数据 |
|---|---|
| 盒子 IP / 主机名 | `192.168.137.188`（PC 的移动热点网段），mDNS 自报 `rk3528-box.local` |
| 接收端 | `192.168.137.1:17000`（WinPlay，热点网关） |
| 控制面 | **`POST /pair-verify RTSP/1.0`**，头 `X-Apple-HKP: 2`、`X-Apple-PD: 1`、`User-Agent: AirPlay…`；WinPlay 回 `RTSP/1.0 200 OK` + `Content-Type: application/pairing+tlv8` |
| 是否 pair-setup | **完全没有**（只做 verify —— 说明它把配对密钥持久化了，或者接收端不校验） |
| 重试 | pair-verify 连发 **4 次**（前几次被拒也会继续，不放弃） |
| 数据面 | 盒子**主动连**接收端另外两个端口 `50768`/`50769`（RTSP 里协商出来的），视频走 TCP `50769`，120s 内 1.28 MB |
| 保活 | 与 `137.1:60165` 之间 32 字节 UDP 心跳（双向，~1s 一次） |
| 发现方式 | 本次窗口内**没有** `_airplay._tcp` 查询（盒子只查了自己的 `rk3528-box.local` 和 `_zj_mirror._tcp`）——抓到的是**已经在连**的会话，端点来自上一次配对/配置，不是本次发现 |

### 14.2 对我们的意义

1. **它也走 pair-verify**（和我们一样），并且**没有 pair-setup 也能成功**——说明
   "每次重建密钥"这条路可行，不必依赖持久身份。
2. **它重试 4 次**才成功；我们目前 pair-verify 失败就 fail。BMW 上可以照抄这个"重试"策略。
3. 数据面端口是**接收端在 RTSP 里给的**（`50768/50769` 这种临时端口），不是固定的
   7000/7100——这正是我们 `fallbackAirplayPorts` 思路的补充：**端口要听对方的**。
4. 关键结论：**本次抓包没能回答"盒子怎么找到车机"**，因为抓的是已连接会话。
   要回答那个问题，必须：抓包开始 → 再让盒子连接；或者抓盒子 ↔ 车机那一段（需 monitor 模式）。

### 14.3 第二次抓包（先抓包再让盒子连）：它**不做发现**

`-BoxIp 192.168.137.188` 重抓一次「抓包开始 → 盒子重新连」，结果非常干净：

```
+10.63s  盒子 192.168.137.188:50800 -> 192.168.137.1:17000  SYN        ← 直接连记住的地址
+10.75s  盒子 192.168.137.188:55772 -> 192.168.137.1:49860  SYN        ← 数据通道（接收端在 RTSP 里给的）
+67.72s  盒子 -> 224.0.0.251:5353  QR=1 通告 rk3528-box.local          ← 连上之后才发 mDNS 通告
```

**整个连接过程没有任何 mDNS 查询**（0 条 `_airplay._tcp` / `_ravi` / `_raop` 询问），
也没有广播发现；它**直接拨记忆里的 `IP:17000`**，连上之后才把自己以 `rk3528-box.local` 通告出去。

> 这条比 §14.1 更重要：它说明「稳定连上」的关键不是更强的发现算法，而是**记住端点**。

### 14.4 由此落地的实现（0.1.0.56）

`SettingsStore.lastAirplayEndpoint`（格式 `host:port|ssid`）：

- **成功即记**：控制端口连通那一刻就写盘（`$apHost:$port|当前SSID`）；
- **下次优先**：`awaitUsableEndpoint` 开头先读它，**SSID 一致**且 600ms 内 TCP 可连就直接用，
  完全跳过 `0x5703/0x4301/mDNS/扫网段`；
- **失败回退**：连不上（换网段/车机换地址）就打一行日志，回到原来的发现流程，不影响首次配对。

日志：

```
SESSION: ✓ 上次成功的端点 172.16.x.x:7000 仍然可连——直接用（跳过发现）
SESSION: 上次的端点 172.16.x.x:7000 连不上（换了网段/车机换了地址）——回退到发现流程
```

## 15. CarLife（com.baidu.carlife.xiaomi）实测拓扑与卡点（2026-10-26）

### 15.1 谁监听、谁连接——**CarLife 是服务端，车机/盒子是客户端**

盒子正常出画面时的实测（`ss -lntu` / `ss -ntu`，手机 10.242.24.159，盒子 10.242.24.214）：

```
LISTEN  *:7240  *:8240  *:9240  *:9241  *:9340          ← CarLife 在监听
ESTAB   10.242.24.159:7240 ← 10.242.24.214:56734        ← 盒子连进来
ESTAB   10.242.24.159:8240 ← 10.242.24.214:38280
ESTAB   10.242.24.159:9240 ← 10.242.24.214:36434
ESTAB   10.242.24.159:9340 ← 10.242.24.214:36386
```

所以 `CarLifeHub::connect(md_host)`（我们当 HU 去连 CarLife）方向是对的，
`CARLIFE_BIND` 的 HU 监听模式只适用于"CarLife 反过来连我们"的实现，不是小米这套的形态。

### 15.2 CarWith 在**副屏**上跑

```
input_focus: [displayId :10, Focus receive :… com.miui.carlink/com.carwith.launcher.home.HomeActivity]
```

车机画面来自一块**虚拟显示器**（display 10），不是手机主屏。这解释了"为什么必须让
CarLife 处于它自己的工作状态"：只有 CarWith 的会话真正建立后，那块虚拟显示器才存在、
才有内容可编码。

### 15.3 两种拉起方式的行为差异

| 拉起方式 | 7240 | Phase 1~7 | 存活 | 8240 视频帧 |
|---|---|---|---|---|
| `am start -n com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity` | ✅ | ✅ 全过 | ❌ 第 34~35 秒 `ProcessUtil.killAllProcess` 自杀 | ❌ 一帧都没有 |
| `am start-foreground-service -n …/com.baidu.carlife.service.CarlifeConnectService` | ✅ | ❌ 卡在 Phase 1（不回版本匹配） | ✅ 45 秒+ | ❌ 一帧都没有 |

自杀现场（两次间隔都是 34~35 秒，非常稳定）：

```
Process(12204): at com.baidu.carlife.core.utils.ProcessUtil.killAllProcess(SearchBox:105)
ActivityManager: Process com.baidu.carlife.xiaomi (pid 12204) has died: fg
ActivityManager: Force stopping com.baidu.carlife.xiaomi … from process:com.miui.carlink
```

`CARLIFE: VIDEO 通道断开，1s 后重连` 说明**我们连上 8240 后立刻被对端关闭**，
这是"收不到视频"的直接原因；而 CarLife 的编码器（`MediaCodec [video-debug-enc] … STARTED`）
在同一时期确实在跑，说明编码在发生、但帧没发给我们这条连接。

### 15.4 待验证的两个假设

1. **会话状态假设**：CarLife 只有在自己判定"已连接车机"（CarWith 状态机）后才把虚拟显示器
   的画面编码并发给 HU。我们只做了 TCP 层的 Phase 1~7，没触发它的会话状态。
2. **握手内容假设**：盒子的 `HU_FEATURE_CONFIG_RESPONSE` 里带了视频相关字段
   （码率/GOP/分辨率/编码类型），我们发的不全 → CarLife 认为 HU 不需要视频。

判定方法：抓一次盒子与手机之间的 7240/8240 报文（盒子为客户端，手机为服务端）。
非 root 可在手机上用 VpnService 抓本地回环不成立（流量走 WiFi），
可行路径是让盒子的流量经过一台可控的中间设备，或拿盒子的调试口。

## 16. 与官方 CarLife 车机端源码逐项对照（2026-10-26，镜像 fgrcwp/apollo-DuerOS）

`ApolloAuto/apollo-DuerOS` 在 GitHub 上已下架，社区镜像 **`fgrcwp/apollo-DuerOS`** 仍在，
里面有完整的车机端 C++ 库（`CarLife-Vehicle-Lib/LibSource`）+ protobuf 定义。逐项对照结果：

| 项 | 官方实现 | 我们的实现 | 结论 |
|---|---|---|---|
| CMD/CTRL 帧头 | 8B：`[u16 BE len][2B 保留][u32 BE type]`（`packageHeadAnalysis`） | `read_cmd_frame` 同布局 | 一致 |
| VIDEO/MEDIA/TTS 帧头 | **12B**：`[u32 BE len][u32 BE ts][u32 BE type]` | `read_media_frame` 同布局 | 一致 |
| `VIDEO_ENCODER_INIT` | protobuf `CarlifeVideoEncoderInfo{1:width,2:height,3:frameRate}` | 同字段号 | 一致 |
| 握手顺序 | Phase1 版本 → 1.5 统计 → 2 设备信息 → 3 特性配置 → 4 鉴权 → 6 编码器 INIT → **等 INIT_DONE** → 7 START | 完全一致 | 一致 |
| FeatureConfig | `CarlifeFeatureConfigList{cnt, repeated{key,value}}`，共 9 个键：语音唤醒/麦克风/焦点 UI/媒体采样率/蓝牙自动配对/蓝牙内部 UI/音频传输模式/内容加密/引擎类型 | 未发/不全 | **与视频无关**，不影响 |
| 连接顺序 | `connectionSetup()`：CMD → VIDEO → MEDIA → TTS → VR → CTRL，**握手之前全部建好** | 0.1.0.107 起一致 | 一致 |
| 视频通道方向 | HU 只收（`readVideoData`），不往 VIDEO 通道发任何东西 | 一致 | 一致 |

### 16.1 仍未解决的点：CarLife 编码器启动了，但 8240 上一个字节都没有

0.1.0.107 实测（`hypercar-log-20261026-013703.txt` + logcat）：

```
01:36:43.212  ✓ VIDEO 已连上 MD 127.0.0.1:8240        ← 握手前建好，且不再被关
01:36:43.364  [Phase 7] → VIDEO_ENCODER_START
01:36:43.760  MediaCodec(6566): [video-debug-enc] setState: STARTING   ← CarLife 编码器真的启动了
01:36:43.773  [avcE_599] KPI_L1: start: ms=12
01:37:08.208  MediaCodec(6566): [video-debug-enc] stop                ← 约 4.4s 后停
（期间我们侧没有任何 `VIDEO 读到`）
```

也就是说：**握手对、连接稳、编码器确实在编码，但帧没有写到我们这条 socket 上。**

### 16.2 剩余假设

1. **写目标不是我们的连接**：CarLife 可能把帧写到它另外持有的一条连接（例如它自己 accept 的第一个），
   而我们的连接只是"挂着"。可试：在 START 之后**再开一条** 8240 连接，两条同时读，看哪条有数据。
2. **需要 CarWith 的会话状态**：CarLife 用虚拟显示器（logcat 里
   `isVirtualDisplayActive caller=com.android.shell target=com.baidu.carlife.xiaomi`）出画面，
   可能只有 CarWith 认为"已连接车机"时才把虚拟显示器的内容送去 HU。
3. **CTRL 通道遗漏**：`CCtrlChannelModule` 里 HU 只发触摸/硬按键，没有"开始视频"的命令；
   但 MD→HU 方向可能有 `video focus` 之类的通知需要我们回应。

### 16.3 下一步优先级

1. 双连接实验（成本最低，改 10 行）：START 后再连一条 8240，两条都读并各自打日志
2. 抓 CarLife 的 xlog（`/sdcard/Android/data/com.baidu.carlife.xiaomi/files/xLog/carlife_YYYYMMDD.xlog`）
   —— 目前是加密的，若能在 CarLife 设置里打开明文日志，就能看到它把视频写到哪
3. 手机 root 后 `tcpdump -i any port 8240`，直接看有没有字节从 CarLife 发出

### 16.4 双连接实验结果（0.1.0.108，2026-10-26 01:41）

```
01:41:05.252  ✓ VIDEO#0 已连上 MD 127.0.0.1:8240
01:41:05.252  ✓ VIDEO#1 已连上 MD 127.0.0.1:8240     ← 同时开两条都被接受
01:41:05.423  [Phase 7] → VIDEO_ENCODER_START
01:41:07.906  VIDEO#0 通道断开，1500ms 后重连          ← CarLife 约 2.6s 后关掉多余的一条
（VIDEO#1 保持连接，但同样一个字节都没有）
```

结论：

1. CarLife **允许多条 8240 连接**，但只保留一条（单消费者语义），说明"写目标"就是它保留的那条；
2. 无论保留哪条，**都没有数据**——所以不是"连错连接"的问题；
3. 帧在 CarLife 内部就没有被投递到 HU 侧的视频 socket。

下一处可疑点：手机上有 CarWith ↔ CarLife 的本地 IPC（实测 `127.0.0.1:57999 ↔ 34562` 等多条 ESTAB）。
CarLife 可能把画面送进"CarWith 会话"的那条通路，而不是直连 HU 的 8240。
`/sdcard/Android/data/com.baidu.carlife.xiaomi/files/xLog/*.xlog` 是加密的，
要看清只能 root 后 `tcpdump -i any port 8240 or port 57999`。

## 17. 拉起 CarLife 但不想占主屏（2026-10-26）

### 17.1 「无界面服务」启动不可用（已实测）

`am start-foreground-service -n com.baidu.carlife.xiaomi/com.baidu.carlife.service.CarlifeConnectService`
能让 7240/8240 起来、编码器也 `STARTED`，但 CarLife **不回 Phase 1**（版本匹配），握手永远
停在第一步，8240 一帧没有（见 §15.3 的表与 100101 现场）。根因是它的会话/编码状态绑定在
`CarlifeActivity` 上，Activity 不在"可见前台"时：

- 它自带的空闲看门狗 34~35s 必杀进程（`ProcessUtil.killAllProcess`）；
- 视频帧不会投递给 HU 的 8240 连接。

所以"完全不显示窗口"在**非 root** 条件下没有干净方案。

### 17.2 已加：把 Activity 投到副屏（`am start --display N`）

设置页「CarLife 启动屏（-1 = 手机主屏）」：

- `-1`（默认）：维持现状，窗口显示在手机主屏；
- `>=0`：`am start -n <CarLifeActivity> --display N`，把 Activity 投到副屏/虚拟屏。
  只有设备上**真的存在**这块屏（如 CarWith 的 display 10）才有效；`am start` 失败或 3s 内
  7240 没起来时，会自动回退主屏重试，不会把 CarLife 弄没。
- 开启该开关时，唤醒日志会先打印 `WAKE: 显示设备 …`（`dumpsys display` 摘要），
  用来确认填哪个 id。
- **不用连电脑查**：诊断页新增「显示器」按钮 → `HyperCarRepo.dumpDisplays()` 把
  `dumpsys display` 的 display id / 名称 / 尺寸打进日志（`DISP: …`），照着填设置里的启动屏即可。

判定成功的标准：日志出现 `WAKE: 拉起 CarLife 引擎页（display=N）` 后 7240 在监听、
`CARLIFE: [Phase 1] ← 版本匹配` 正常推进，且**手机主屏不再被 CarLife 占据**。

### 17.3 为什么不建议的其他做法

- **分屏 / 画中画**：窗口还在，只是变小；CarLife 不支持 PiP 时直接无效。
- **亮度 0 / 息屏保活**：Activity 仍算前台，但手机等于不可用（驾驶时取导航还得恢复亮度）。
- **禁用看门狗**：需要改 CarLife APK（LSPatch/Xposed）或 root，超出"装个 App 就能用"的范围。

## 18. 2026-10-26 12:47 桌面联调日志（接收端 = DiPlay / xcertplay）

日志 `hypercar-log-20261026-124902.txt`（0.1.0.49）。这是一次**桌面联调**，不是真车：
接收端是 `com.shilapi.xcertplay`（DiPlay）跑在 192.168.1.208:7000，热点 SSID=YK。

链路本身没问题：iAP2 识别 → 0x5703 拿凭据 → 入网（192.168.1.111/24）→ `_carplay-ctrl`
广播 → 车机 `GET /ctrl-int/1/connect` 邀请 → 0x4301 给出 `192.168.1.208:7000`。
失败点在**连 AirPlay 控制端口**：

```
12:47:54.362  PROBE 车机端口 192.168.1.208 → 7000=开(79ms) 7001=关 7100=关 5000=关 62078=关
12:47:56.423  SESSION 保活结束（链路断开）      ← 接收端把 iAP2 关了
12:47:57.311  ✗ 经 Wi-Fi 网络 #231 连 192.168.1.208:7000 失败（ECONNREFUSED）
12:47:57.392  SESSION 失败: AirPlay 控制端口连接失败
```

### 18.1 三个真问题（已修）

1. **预探测把接收端的 AirPlay 服务"探没了"**：老的 `probeCarPorts()` 会对
   7000/7001/7100/5000/62078 各 `connect()` 一次再立刻 close（只为打日志）。
   DiPlay 这类单控制器实现会把它当成"控制器连上又掉线"，随即关掉 AirPlay 服务——
   日志里 54.3s 探测 7000 还开着、57.3s 真连就全 refused。**已删除该预探测**
   （连接尝试本身就会把开/关打进日志）。
2. **端口连接只有一轮，失败即放弃**：接收端/车机常在 ctrl 邀请之后才把 7000 打开，
   或中途重启 AirPlay 服务。现在有 **8 秒重试窗口**（首轮 2.5s 超时，重试轮 1.2s），
   每 400ms 一轮，期间若 0x4301 晚到就立刻改用它的地址/端口。
3. **"拉起 CarLife"与"失败清理"赛跑**：AirPlay 端口失败 → `releaseSessionResources()`
   force-stop CarLife（收窗口），但唤醒协程还在 3s 等待里，随后又把
   `CarlifeConnectService` 拉起来 → 留下"7240 在听但不回 Phase 1"的空壳
   （日志 57.6s 清理、00.4s 又拉起服务，就是这个）。现在 `releaseSessionResources()`
   会先 `wakeJob?.cancel()`，唤醒协程自己在每步前后也检查 `sessionLive`。

### 18.2 这次失败不全是我们的问题

接收端在 12:47:56.4 主动关掉了 RFCOMM，并在之后拒绝 7000 连接；如果 DiPlay 只在
iAP2 链路存活期间提供 AirPlay 服务，那就必须**在 BT 断开之前**把 AirPlay 控制连接建好。
这次的时间线是：0x4301 在 12:47:54.17 就给出了 `192.168.1.208:7000`，我们却还去等
3 秒 mDNS（54.26 → 57.26），等真正去连时接收端已经把 7000 关了。

**已修**：`0x4301` 已给出地址+端口时也跳过 mDNS（之前只跳过 ctrl 邀请来的端点）。
现在这种情况会在 54.2s 就直接连 7000，比 BT 被拆早 2 秒多——这是这类"接收端只在
iAP2 期间开 AirPlay"的实现能否连上的关键差别。

## 19. 2026-10-26 13:03 联调（0.1.0.50）：AirPlay 全通、CarLife 握手全过，但 8240 仍无帧

日志 `hypercar-log-20261026-130400.txt`，接收端仍是 DiPlay（192.168.1.208:7000）。

### 19.1 这一版修好的部分（上一轮的 4 处改动生效）

```
13:03:40.008  MDNS: 0x4301 已给出端点 [192.168.1.208]:7000——跳过 mDNS 查询   ← 省掉 3s
13:03:40.128  NET ✓ 192.168.1.208:7000 已连通（Wi-Fi 网络 #233，112ms）      ← 一次就连上
13:03:40.169  CARLIFE: 第 1 次零接触探测命中 127.0.0.1:7240（CarLife 在监听）  ← 不再抢 accept
13:03:40.362  CARLIFE: [Phase 1] ← 版本匹配 … [Phase 7] 40.424 握手完成      ← 14 步全过（62ms）
13:03:40.811  PAIR: ✓ pair-setup 完成 / 40.930 pair-verify 完成
13:03:40.930  AIRPLAY: /info displays[0].uuid=… 0x0 features=0x615653aee2   ← 0x0 → 用设置值
13:03:41.042  AIRPLAY: ✓ 会话 SETUP eventPort=46747 keepAlive=37557
13:03:41.085  AIRPLAY: ✓ 主屏流就绪 1920x720 / 41.120 RECORD 完成——会话已激活
13:03:41.264→42.269  AUDIO 14/s → 136/s（应 136/s）
```

AirPlay 侧（控制通道、配对、SETUP、屏幕流、音频流、待机卡片）已经完整跑通；
CarLife 侧 14 步握手也全过。**唯一还缺的就是 8240 的视频帧**：
`首帧视频未到——第 1…12 次补发 VIDEO_ENCODER_START`，一直没等到 `VIDEO_DATA`。

另外两条信息：

- CarLife 把我们请求的 `2560x752` **压到 1920x720**（`手机实际编码 1920x720`），
  说明这台手机的 CarLife 编码器上限就是 1080p；画布已跟随实际值，不再是问题。
- `0x00010022` 查官方定义是 `MSG_CMD_MIC_RECORD_WAKEUP_START`（语音唤醒开始），
  不是视频相关，不需要处理。

### 19.2 新发现：官方 HU 每秒发 `MSG_VIDEO_HEARTBEAT (0x00020002)`

对照 `fgrcwp/apollo-DuerOS` 的车机端 SDK（`CarLife-Android-Vehicle-V2.0`）：

```kotlin
// ConnectionEstablishHandler.kt
private val heartBeatTask = Runnable {
    if (System.currentTimeMillis() - lastReceiveMessageTime > 8000) {
        Logger.e(Constants.TAG, "ConnectionEstablishHandler heartbeat timeout")
        context.terminate()
    } else {
        context.postMessage(MSG_CHANNEL_VIDEO, ServiceTypes.MSG_VIDEO_HEARTBEAT)  // 0x20002
    }
}
// 收到版本匹配后：TimerUtils.schedule(heartBeatTask, 10, 1000) → 每 1s 一次
```

**我们只读 VIDEO、从不发心跳**。CarLife 很可能是靠这条心跳确认"HU 还在看视频"，
没有它就一直让编码器空转（正好对应 `MediaCodec [video-debug-enc] STARTED` 但 8240 零字节）。

已实现：`handle_media_client` 里 VIDEO 通道每 1s 写一条 `0x00020002`（空载荷、12 字节头），
前 3 次和每 30 次打一行 `CARLIFE: → VIDEO 心跳 #n`，写失败即退出该通道。

### 19.3 还没处理的

- `EVENT: 未处理的车机命令 type=setNightMode` / `type=forceKeyFrame`：DiPlay 请求夜间模式与
  关键帧，我们都没回。`forceKeyFrame` 对解码端可能重要（它要求立刻来一个 IDR），
  但待机卡片循环本身就是 60 帧 IDR 素材，先观察。
- 若加了 VIDEO 心跳仍无帧，下一步按 §16.3：抓 CarLife 的 xlog（需明文日志开关）或
  root 后 `tcpdump -i any port 8240`，确认它到底把帧写去了哪条连接。

### 19.4 0.1.0.51 复测（`hypercar-log-20261026-130928.txt`）：心跳发了，帧还是没有

- `CARLIFE: → VIDEO 心跳 #2/#3（0x20002）` 正常发出 → 心跳不是缺的那一环。
- **音频是通的**：`AUDIO: 发包 136/s（应 136/s）`，而音频数据来自 CarLife 的 9240 通道
  ——同一个进程、同一套 TCP、同样的"我们拨过去"。所以不是网络/通道映射问题，
  是 CarLife 的视频编码/投递这一路自己没吐数据。
- 界面确实被推到前台过（上一轮日志 `已把 CarLife 推回前台 exit=0`），也不是 Activity 没起来。
- 另外 DiPlay 侧仍在 AirPlay RECORD 后约 2s 拆 iAP2（13:09:04.787 → 06.677），
  随后又重连一次；这与视频无关，是接收端行为。

结论："握手全过 + 编码器 STARTED + 通道全连上 + 心跳在发 + 音频在走，唯独 8240 零字节"，
范围已经收敛到 CarLife 自己的视频输出路径。下一步只能取证：

### 19.5 新增：诊断页「CarLife」取证按钮

`HyperCarRepo.dumpCarLifeDiag()`（需 Shizuku）一次抓三样东西写进日志：

```
DIAG: ==== CarLife 现场开始 ====
DIAG-ACT: exit=0，2 行        ← dumpsys activity：CarLife 是否 resume / 是否 top
DIAG-SF: exit=0，1 行         ← dumpsys SurfaceFlinger --list：有没有它的 Surface
DIAG-LOG …                    ← logcat 里 carlife / video-debug / avc 编码器 / MediaCodec 的最后 30 行
DIAG: ==== CarLife 现场结束 ====
```

判读方法：
- `DIAG-ACT` 里没有 CarLife 的 resume 记录 → 它其实不在前台（编码没有输入）；
- `DIAG-SF` 里没有它的 Surface → 渲染/编码输入源不存在；
- `DIAG-LOG` 里若有 `[video-debug-enc] … 输出/queueInputBuffer` 之类 → 编码器在出帧，
  那就是"帧没写到我们这条 8240 连接"（转 §16.3 抓包方向）。

### 19.6 0.1.0.52 取证结果（`hypercar-log-20261026-131348.txt`）：画面缺帧是 CarLife 实例被顶掉

诊断按钮抓到的现场（13:13:38.99）：

```
DIAG-ACT topResumedActivity=io.github.hypercar/.ui.MainActivity
DIAG-ACT * Task{…#3064 A=10345:com.baidu.carlife.xiaomi … visible=false visibleRequested=false}
DIAG-ACT mLastPausedActivity: …com.baidu.carlife.CarlifeActivity
DIAG-SF  RequestedLayerState{…CarlifeActivity…}          ← Surface 还在，但 Activity 已 pause
DIAG-LOG PowerInsight_…: handleAppBackground newBgApp:com.baidu.carlife.xiaomi
```

两类问题，都是"我们自己的操作"造成的：

1. **CarLife 被重复拉起，已握手的实例被顶掉（主因）**
   ```
   13:13:31.741  WAKE: 拉起 CarLife 引擎页 exit=0
   13:13:31.910  [Phase 7] 握手完成
   13:13:32.056  CARLIFE: 已把 CarLife 推回前台（防 35s 空闲自杀） exit=0   ← 300ms 后又 am start 一次
   13:13:32.059  CARLIFE: ✓ CMD 已连上 MD 127.0.0.1:7240                    ← 新实例重新连
   13:13:32.062  CARLIFE: [Phase 1] → HU_PROTOCOL_VERSION
   13:13:32.665  CARLIFE: ✓ VIDEO 已连上 8240                                ← 这条属于新实例
   …            （新实例永远不回版本匹配 → 我们的 8240 是"空壳"的连接，当然没有帧）
   ```
   CarLife 有单实例逻辑（`ProcessUtil.killAllProcess`）：短时间内两次 `am start` 会让它重置会话。
   **已修**：`carlife_active` 里若距上次拉起 < 25s，就跳过 `am start`（只记一行日志）。

2. **CarLife 必须保持"可见前台"才有画面**
   DIAG-ACT 显示点按钮那一刻 CarLife 已是 `visible=false`（用户切回了 HyperCar 去点诊断页）。
   音频不需要 Surface 所以一直正常；视频需要它的 Surface/Activity 处于 resumed。
   这也正式回答了"能不能不显示 CarLife 窗口"：**主屏上不行**，只能投到副屏/虚拟屏
   （设置页「CarLife 启动屏」，见 §17.2）。

3. 另加 **握手看门狗**：引擎起来 12s 内没等到 `carlife_active`，判定空壳，
   force-stop 后重拉一次（每轮最多一次），日志 `CARLIFE: 12s 内没走完握手…判定空壳`。

### 19.7 0.1.0.53 复测（`hypercar-log-20261026-131704.txt`）：编码器输入是 0

看门狗生效了（`173ms 前刚拉起过——跳过重复 am start`），但视频依旧为 0，这次拿到了**决定性的数字**：

```
13:16:56.741  MediaCodec: [mId: 0] [video-debug-enc] state STARTED mFlags 2336
              Stats 5006ms:Client:com.baidu.carlife.xiaomi,Component:c2.qti.avc.encoder,
              Qin:0, DQin:0/0, Render:0, …
```

`Qin:0` = **CarLife 从来没往编码器塞过一帧**（queueInputBuffer 次数 0），`Render:0` 同理。
也就是说不是"帧没写到我们的 socket"，而是 **CarLife 自己没有渲染/没有编码输入**。

原因在同一份诊断里：

```
DIAG-ACT topResumedActivity=io.github.hypercar/.ui.MainActivity
DIAG-ACT * Task{…#3066 A=10345:com.baidu.carlife.xiaomi … visible=false visibleRequested=false}
DIAG-ACT mLastPausedActivity: …CarlifeActivity
```

CarLife 的 Activity 处于 paused、它的 Window 不可见 → 客户端 Surface 不渲染 → 编码器空转。
而这一次的 paused 是**取证动作自己造成的**：要点「CarLife」按钮必须先切回 HyperCar。

### 19.8 自动取证（0.1.0.54）

为避免"取证把现场弄没"，新增自动取证：引擎启动后 **12s 仍无视频帧**（`onStats.frames==0`）
就自己跑一次 `dumpCarLifeDiag()`，不需要用户切界面。日志标记为
`DIAG: 12s 内视频帧仍为 0——自动取证（不用碰手机）`。

同时 `dumpCarLifeDiag()` 增加一段 `DIAG-ENC`：`logcat -d | grep video-debug | tail -6`，
直接给编码器的 `Qin/DQin/Render` 计数。

判读：
- 若自动取证的 `DIAG-ACT` 里 `topResumedActivity` 是 **CarLife**、而 `Qin` 仍为 0
  → 问题在 CarLife 内部（它没渲染/没投递），与前台无关；
- 若 `topResumedActivity` 是 **HyperCar** → 前台被抢，属"手机必须停在 CarLife 界面"的
  产品约束（§17.2 的副屏方案才有意义）；
- 若 `Qin > 0` 而我们的 `8240` 仍 0 字节 → 帧写错了连接，转 §16.3 抓包。

### 19.9 0.1.0.54 复测（`…-134358` / `…-134452`）：CarLife 在前台也是 Qin:0 → 视频靠录屏授权

这两次连接里自动取证终于抓住了"CarLife 在前台"的现场（13:44:22）：

```
DIAG-ACT topResumedActivity=com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity t3069
DIAG-ACT * Task{…#3069 … visible=true visibleRequested=true …}
DIAG-ENC  MediaCodec [video-debug-enc] Stats 5026ms … Qin:0,DQin:0/0,Render:0
```

**Activity 是 top、可见、resumed，编码器输入仍然是 0。** 所以"CarLife 必须在可见前台"
这个假设被否掉了（之前的 paused 只是取证手法造成的）。

把 CarLife 的 APK 拉下来查（`base.apk`，44 MB）：

```
classes.dex  VirtualDisplay 5   MediaProjection 1   createVirtualDisplay 2   carlink 6
classes2.dex VirtualDisplay 10  MediaProjection 18  createVirtualDisplay 2   createInputSurface 2   carlink 3
classes3.dex VirtualDisplay 4   createVirtualDisplay 1   carlink 1
```

说明它的视频是 **VirtualDisplay + MediaProjection（录屏）→ createInputSurface 编码** 这条路。
而设备上：

```
appops get com.baidu.carlife.xiaomi PROJECT_MEDIA → 默认 ignore（从没授权过）
dumpsys media_projection → null（没有进行中的录屏会话）
dumpsys display → 只有 0/1 两块物理屏，没有 VirtualDisplay
```

**结论：CarLife 从来没拿到录屏授权，所以它没有可编码的内容 → `Qin:0` → 8240 零字节。**
音频不走录屏，所以一直正常——完全对得上。

处置：
1. 已执行 `appops set com.baidu.carlife.xiaomi PROJECT_MEDIA allow`（App 里也会在 12s 无帧时
   打一行提示）。若连接后手机弹出「开始录制/投放」对话框，**点允许**。
2. 若 appops 仍被 Android 14+ 的"每次会话都要确认"挡住，下一步可以考虑：让 CarWith
   （`com.miui.carlink`）发起会话——CarWith 是系统应用，录屏授权走它的既有权限，
   这也解释了"为什么 CarWith 能用而直接拉 CarLife 不行"。

补充：这两份日志里"母屏画面出现几秒"看到的是我们的**待机卡片**（母版 1280x372，60 帧 IDR），
"断连"是 13:44:50 的 `SESSION: 已断开（用户/系统断开）`（手动/系统断开），不是崩溃；
另外 DiPlay 每次都在 RECORD 后约 2s 拆 iAP2，这是接收端行为。

### 19.10 0.1.0.55 复测（`hypercar-log-20261026-135448.txt`）：又踩到"自己把 CarLife 重启掉"

这次连 14 步握手都没跑完：`握手进行中（Phase 1: 等版本匹配）` 一直到看门狗（12s）触发。时间线：

```
13:54:22.758  CARLIFE: ✓ VR 已连上 MD 127.0.0.1:9242          ← 先连上了"正在跑的那份" CarLife
13:54:22.767  CARLIFE: CMD/MEDIA/VIDEO 通道断开                 ← 它被杀了（单实例逻辑）
13:54:22.869  WAKE: 拉起 CarLife 引擎页 exit=0                  ← 我们这次 am start 才是元凶
13:54:23.772  CARLIFE: ✓ CMD 已连上 7240 / [Phase 1] →
13:54:32.773  CARLIFE: 握手进行中（Phase 1: 等版本匹配）         ← 新实例永远不回
13:54:34.772  CARLIFE: 12s 内没走完握手——判定空壳，force-stop 后重拉
```

根因：**CarLife 已经在监听 7240 时，我们仍然无条件 `wakeLocalCarLife()`**（内部就是
`am start CarlifeActivity`）→ 触发 CarLife 单实例重启 → 新实例只 bind 端口、不回握手。

已修（0.1.0.56）：连接开始时先 `isLocalPortListening(7240)`：
- 已在监听 → **跳过拉起**，日志 `WAKE: CarLife 已在监听 7240——跳过拉起（避免触发单实例重启）`；
- 没在监听 → 才 `wakeLocalCarLife()`（再走原来的探测/拨号流程）。

这样"上一轮残留的 CarLife 还活着"时不会再被我们打断；只有真的没起来才拉。

### 19.11 没有录屏弹窗是正常的——CarLife 走的是特权 CAPTURE_VIDEO_OUTPUT

查 `dumpsys package com.baidu.carlife.xiaomi`：

```
android.permission.CAPTURE_VIDEO_OUTPUT        ← signature|privileged，预装应用才有
```

所以它**不需要** MediaProjection 的每次确认弹窗，就能 `createVirtualDisplay` 直接抓画面。
"没弹窗"≠"没问题"；反过来说，之前"给 PROJECT_MEDIA appop"的猜测不成立（留着也无害）。

### 19.12 又找到两个我们自己的协议 bug（已修，0.1.0.57）

对照官方 proto（`apollo-DuerOS/CarLife-Vehicle-Lib/LibSource/protobuf/proto/`）：

1. **`HU_INFO` 字段全错位**：我们按"1=device_type(int)、2=os_type(int)、9=screen_width…"
   自己编了一套；官方 `CarlifeDeviceInfoProto` 是
   `1=os(string) 2=board 3=bootloader 4=brand … 21=sdk_int(int32) 24=carlifeversion`。
   我们给字段 1/2 发的是 **varint**，而 proto 声明是 **string** → 手机按 protobuf 解析这条
   消息会失败。已按官方 proto 重写（全部字符串字段 + sdk_int/carlifeversion）。
2. **忽略了 MD 的特性配置请求**：官方 HU（`FeaturesHandler.handleFeatureRequest`）是
   `setFeature(key,value)` 记录 MD 请求的每一条，再把整个列表回给 MD；我们以前直接回
   自己写死的 6 个 key，等于把 MD 想要的能力（可能包含投屏/焦点/仪表盘等解锁项）丢掉。
   现在会解析 MD 的请求、**把 key=value 打进日志**、并原样回显。

下一次连接的日志里请重点看这一行：

```
CARLIFE: MD 请求特性 N 项：key1=1, key2=1, …
```

这份清单就是"CarLife 认为车机该具备什么"的原始答案——如果里面有我们没实现的能力
（例如和投屏/虚拟屏相关的 key），顺着它补就能解锁视频。

### 19.13 B1 结论：CarWith 的投屏会话**非 root 无法驱动**（证据）

把 `/product/app/CarWith/CarWith.apk` 拉下来（56 MB，8 个 dex）并 dump 清单：

```
Service  com.miui.carlink.castfwk.CarlinkService
         exported=true，但 permission=miui.carlink.permission.START_CARLINKSERVICE
         该权限 protectionLevel=0x3（signatureOrSystem）
Activity com.miui.carlink.castfwk.CastingActivity      exported=false
Activity com.carwith.launcher.activity.ReceiverActivity exported=?（可起，但不建虚拟屏）
Receiver com.carwith.launcher.screenshot.CaptureScreenReceiver
         exported=true，action=com.miui.carlink.action.CONNECT_CLICK_ACTION_CAR / CAPTURE_SCREEN_ACTION
```

实测（adb shell = uid 2000，等价于 Shizuku）：

```
$ am start-foreground-service -n com.miui.carlink/com.miui.carlink.castfwk.CarlinkService
  Error: Requires permission miui.carlink.permission.START_CARLINKSERVICE
$ am start -n com.miui.carlink/com.miui.carlink.castfwk.CastingActivity
  java.lang.SecurityException: … not exported from uid 10156
$ am broadcast -a com.miui.carlink.action.CONNECT_CLICK_ACTION_CAR -n …/CaptureScreenReceiver
  Broadcast completed（无效果：CarWith 进程都没起来）
$ su -c id   →  /system/bin/sh: su: inaccessible or not found
  ro.build.type=user / ro.secure=1  → 无 root
```

结论：CarWith 的投屏会话由**系统侧（蓝牙/CarService 等特权调用者）**启动，普通应用或 shell 都进不去。
路线 B1 在**非 root 前提下到此为止**——不是协议问题，是权限边界。

后续只有三条：
1. **root/Magisk**（模块里给我们的 app 签系统权限，或 hook CarWith 的启动）——改变产品前提；
2. **自建车机端 UI**（我们自己渲染横屏卡片界面并用 MediaCodec 编码，走现有 AirPlay 视频泵）——
   完全绕过 CarLife/CarWith，非 root 可行，但要自己做 UI；
3. **屏幕镜像**（cardeck 同款）——用户已明确认为重复造轮子。

### 19.14 找到了 CarWith 驱动 CarLife 投屏的 AIDL（0.1.0.58/59）

探测结果（`hypercar-log-20261026-144302.txt`）：

```
CAST: ✓ 绑定 com.baidu.carlife.service.CarlifeConnectService → descriptor=（空）
CAST: ✓ 绑定 com.baidu.carlife.complex.RemotePresentationService
      → descriptor=com.baidu.carlifelink.listener.ICarLifeManager
```

`RemotePresentationService` 是**导出的**、无需权限，它提供的正是 `ICarLifeManager`。
从 CarLife dex 的 Stub 常量里抄出完整 transact 码：

```
com.baidu.carlifelink.listener.ICarLifeManager
    register(String):boolean           code 1
    unregister(String):boolean         code 2
    getDisplayController(String):IBinder code 3
    getTTSController(4) / getMusicController(5) / getInduceMessageController(6)
    setOnTouchListener(7) / removeOnTouchListener(8)
    addLifecycleListener(9) …  addDayNightListener(17) / removeDayNightListener(18)
com.baidu.carlifelink.listener.IDisplayController
    startDisplay(String)   code 1
    stopDisplay(String)    code 2
    currentDisplayPage(String):String code 3
com.baidu.carlifelink.listener.ILifecycleListener（回调，我们实现）
    onDisplayAdded(1) / onDisplayActivated(2) / onDisplayStop(3) / onDisplayRemove(4)
    onHeartBeat(5) / onConnectToHuChanged(6) / backToBackground(7) / backToForeground(8)
    isForeground(9) / onDisplayDestroy(10) / getAppState(11)
```

另外 CarLife 还有一套 `com.baidu.carlife.mixing.aidl.ICarLifeCastManager`：
`prepareCast(CastConfig{width,height,dpi,surface})` —— **调用方给 Surface、CarLife 往里渲染**，
但那个 binder 不在上面两个导出服务上（`CarlifeConnectService` 的 descriptor 是空的），
所以先用 `ICarLifeManager.startDisplay()` 这条。

0.1.0.59 的行为：`carlife_active`（14 步握手完成）后 600ms 自动

```
register("hypercar") → getDisplayController("hypercar") → startDisplay("hypercar")
```

日志标记 `CAST: ICarLifeManager.register(hypercar) → 1` / `CAST: getDisplayController(…) = …` /
`CAST: IDisplayController.startDisplay(hypercar) 已调用`。
如果 `startDisplay` 就是"开始投屏"的开关，那么 8240 应该开始出帧（`Qin` 从 0 变正、
`VIDEO: 首帧 …` 出现真正的 CarLife 画面）。

### 19.15 `startDisplay` 第一次尝试：register 返回 0（0.1.0.59）

```
14:59:35.204  CAST: ICarLifeManager.register(hypercar) → 0
14:59:35.205  CAST: getDisplayController(hypercar) = null
```

`register(String)` 不认 "hypercar" 这个 tag（很可能它拿 tag 当"客户端标识"校验，
或者内部要求先建立某种会话）。0.1.0.60 改成**候选 tag 轮试**：

```
io.github.hypercar / com.miui.carlink / com.baidu.carlife / carlife / hypercar
```

每个都打 `CAST: register("…") → x`，命中（返回 1）才继续 `getDisplayController` + `startDisplay`。

若全部失败，会继续 **枚举 CarLife 的导出服务**（`CAST-SVC:` 行，每个打印
`interfaceDescriptor`），目的是找出到底哪个组件暴露了
`com.baidu.carlife.mixing.aidl.ICarLifeCastManager`（带 `prepareCast(CastConfig{surface})` 的那个，
能把 CarLife 画面渲染进我们给的 Surface）。

### 19.16 `register` 为什么全是 0：它只认 RemoteClientConfig 里预置的 client key

反编译 `com.baidu.carlife.complex.binder.RemoteGroupBinder.register(String pkgName)`：

```
log("register pkgName:" + pkgName)
clients = RemoteClientConfig.getClients(pkgName)   // 返回 List<RemoteClient>
if (clients.isNotEmpty()) {  new RemoteBinder(pkgName) … return true }
else                      {  return false }
```

再看 `RemoteClientConfig` 的静态初始化（dex 常量原文）：

```
"map"                  → video_client.dev / com.chen.client.ui.MainActivity / …BaiduMapActivity
"mini_map"             → video_client.dev / com.chen.client.ui.PlayActivity / …VideoDemoActivity
"baidu_map"            → com.baidu.BaiduMap / com.baidu.baidumaps.WelcomeScreen / com.baidu.carlife.CarlifeActivity
"baidu_mini_map"       → com.baidu.BaiduMap / com.baidu.carlife.minimap.MiniMapActivity
"carlife_rcc_display"  → com.szats.breakthrough / …NaviCarlifeActivity / com.baidu.carlife.CarlifeActivity
```

所以 `register("io.github.hypercar")` / `"com.miui.carlink"` 之类**必然**返回 0——它要的是
这几个"远程客户端"key（把某个 App 的界面渲染到车机屏），不是任意包名。
`getClients()` 还会按**该 App 是否装上**过滤。

0.1.0.61 起按这几个 key 轮试：
`baidu_map / mini_map / baidu_mini_map / carlife_rcc_display / map` + 原来的包名候选。
哪个返回 1，就跟进 `getDisplayController(key)` → `startDisplay(key)`，
看 CarLife 是否开始投屏（8240 出帧 / `Qin` 变正）。

若全 0，大概率是缺少对应 App（`com.baidu.BaiduMap` 等）——装一个百度地图再试即可验证机制。

### 19.17 更正：`register` 比的是 pkgName，而且不检查是否安装（0.1.0.62）

`RemoteClientConfig.getClients(pkgName)` 的字节码只有一句话：

```
for (client in supportRemoteClients) if (client.pkgName == pkgName) result.add(client)
return result
```

**没有"是否已安装"的过滤**。而 `supportRemoteClients` 里的 pkgName 只有三个：

```
video_client.dev
com.baidu.BaiduMap
com.szats.breakthrough
```

（对应的 key/显示名是 map / mini_map / baidu_map / baidu_mini_map / carlife_rcc_display）

所以之前传 "baidu_map"（key）或 "com.miui.carlink" 都是错的——要传 **pkgName**。
0.1.0.62 起依次尝试：

```
com.baidu.BaiduMap   → video_client.dev   → com.szats.breakthrough
（再兜底 key 与包名，便于日志对照）
```

命中（返回 1）就 `getDisplayController(pkgName)` → `startDisplay(pkgName)`。

**关键**：即使没装百度地图，`register("com.baidu.BaiduMap")` 也应该返回 1，
`startDisplay` 会尝试把这个"远程客户端"渲染到车机屏上——如果它的虚拟屏/编码管线因此启动，
`Qin` 就会从 0 变正，我们就能看到 CarLife 的投屏数据流。

另外：CarLife 里出现的 `com.autonavi.amapauto` / `com.autonavi.amapautolite` / `com.autonavi.minimap`
是**导航源/流转**用的，不在这份"远程客户端"名单里，所以高德装不装都不影响这条链路。

### 19.18 突破：`register`/`getDisplayController`/`startDisplay` 全部成功（0.1.0.63/64）

```
15:18:44.280  CAST: register("com.baidu.BaiduMap") → 1
15:18:44.281  CAST: getDisplayController("com.baidu.BaiduMap") = android.os.BinderProxy@4ccbd4b
15:18:44.281  CAST: startDisplay("com.baidu.BaiduMap") = true
```

参数与 19.17 的判断一致：**要传 pkgName**（`RemoteClient.pkgName`），不是 key，
而且 `getClients()` 不检查安装 → 没装百度地图也能注册成功。

但 `Qin` 仍然是 0：`startDisplay` 的语义是"把该远程客户端的 Activity 渲染到车机屏"，
`com.baidu.BaiduMap` 没安装 → 没有界面可渲染 → 编码器还是空转。
（设备上没有任何 `RemoteGroupBinder`/`startDisplay` 的 logcat，CarLife 是 release 版、日志被裁了。）

### 19.19 调试加速器：adb 直接触发探测（0.1.0.64）

```
adb shell am broadcast -a io.github.hypercar.PROBE_CAST -p io.github.hypercar
sleep 8
adb shell cat /sdcard/Android/data/io.github.hypercar/files/cast_probe.txt
```

输出就是上面那三行 `CAST:`。不用连车机、不用点界面，方便快速验证参数。

### 19.20 下一步：装百度地图验证视频链路

`RemoteClientConfig.supportRemoteClients` 只有三个 pkgName：

```
com.baidu.BaiduMap   ← 最现实（免费 App）
video_client.dev     ← 百度内部 demo，拿不到
com.szats.breakthrough ← 第三方 App
```

装百度地图后 `startDisplay("com.baidu.BaiduMap")` 才有东西可渲染，届时 `Qin` 应该从 0 变正、
8240 出帧——那就是"CarLife 把第三方界面投到车机屏"的完整链路。
高德（`com.autonavi.*`）不在这个名单里，所以用不上。

### 19.21 远程客户端 API 走到底：调用成功但 CarLife 不建屏（0.1.0.65~68）

百度地图装上后（`package:com.baidu.BaiduMap` 已存在），adb 探针结果：

```
CAST: register("com.baidu.BaiduMap") → 1
CAST: getDisplayController("com.baidu.BaiduMap") = android.os.BinderProxy@…
CAST: startDisplay("com.baidu.BaiduMap") = true，displays=0:内置屏幕, 1:内置屏幕
CAST: startDisplay("baidu_map") = true，displays=0:内置屏幕, 1:内置屏幕
```

关键问题：

1. **`startDisplay` 的返回值没有意义**：传 pkgName、传 key、传任意串都返回 true；
2. **没有创建任何 VirtualDisplay**（displays 始终只有 0/1 两块物理屏），`Qin` 也一直是 0；
3. **CarLife 配置里的客户端 Activity 直接拉不起来**：
   `am start -n com.baidu.BaiduMap/com.baidu.baidumaps.WelcomeScreen` → `Error type 3`
   （真正的入口是 `…WelcomeScreen.Alias0`），所以 CarLife 拿这个写死的组件去启动时也起不来，
   自然没有界面可渲染、没有帧可编码；
4. **同一时刻只接受一个客户端**：先 register 了 `com.szats.breakthrough` 之后，
   再 register `com.baidu.BaiduMap`，`getDisplayController` 直接返回 null。

结论：`ICarLifeManager.register/startDisplay` 是 CarLife **自家"地图卡片"功能**的接口
（把指定 App 的界面画到车机屏上），不是通用的"开始投屏"开关。它依赖 CarLife 内部
对客户端的启动/捕获逻辑，而这条逻辑在我们（外部 HU）手上不可控。

非 root 的三条入口至此全部确认不可用：
1. 协议握手（已完成 14 步）→ 视频源在 CarLife 内部，不给我们；
2. CarWith 的 `CarlinkService` → signatureOrSystem 权限挡住；
3. CarLife 的 `ICarLifeManager` → 调用可用但没有实际渲染，且客户端 Activity 起不来。

### 19.22 现状与决策点

已经确定能跑通的：iAP2 识别/配对、无线 CarPlay 凭据、AirPlay 连接与配对、SETUP/RECORD、
屏幕流（待机卡片）、音频 136/s、CarLife 14 步握手/音频/触摸通道。

唯一缺的：**从 CarLife 拿到真实画面**（`Qin:0`）。非 root 没有可用入口，剩下的选择：

| 方案 | 代价 | 备注 |
|---|---|---|
| ① root（Magisk 模块给权限 / hook CarWith） | 改变产品前提 | 可直接复用 CarWith 的整条投屏管线 |
| ② 自建车机端 UI + 自己编码 | 中等工作量 | 完全绕开 CarLife/Xiaomi，非 root 可行 |
| ③ 屏幕镜像（cardeck 同款） | 最小 | 用户已明确否掉"重复造轮子" |

## 20. 方案②：自绘车机界面 + 自己编码（2026-10-26，0.1.0.69 起）

CarLife 视频源在非 root 下确认拿不到（§19.21），改由我们自己产出画面：

```
MediaCodec(avc).createInputSurface()
  → DisplayManager.createVirtualDisplay(1920x720@240dpi, surface, PUBLIC|OWN_CONTENT_ONLY)
  → Presentation(context, virtualDisplay.display).setContentView(CarUiView)
  → MediaCodec 输出 AnnexB（含 SPS/PPS/IDR）
  → JNI nativePushVideoFrame(bytes, ptsUs)
  → engine 视频泵（与 CarLife 帧同一条路径）→ ScreenSender → 车机
```

- **不需要 root**，也**不需要 MediaProjection 授权**：虚拟屏装的是我们自己的内容，
  `createVirtualDisplay` 只要一个 Surface。
- Rust 侧新增：`EngineHandle.video_tx / size_tx`、`nativePushVideoFrame`、`nativeSetVideoSize`；
  视频泵 `select!` 里多两路输入（外部帧 / 外部画布尺寸），帧到就刷新 `last_real_frame`
  （自动停掉待机卡片循环）。
- Kotlin 侧新增：`CarUiCaster`（编码 + 虚拟屏 + Presentation）、`CarUiView`（Canvas 画的
  车机界面：大时钟 + 地图卡片 + 音乐卡片；`navigationActive=true` 时地图铺满整屏）。

#

自检命令（不需要车机）：

```
adb shell am broadcast -a io.github.hypercar.PROBE_UI -p io.github.hypercar
adb shell cat /sdcard/Android/data/io.github.hypercar/files/carui_probe.txt
```


## 21. 官方接入文档（V2.1.1）核对结果 —— 找到 Qin:0 的真凶

文档：`E:\Downloads\百度CarLife车机手机互联方案接入详细指南V2.1.1.pdf`（157 页，车机端规范）

### 21.1 与本文档逐条核对，我们**对了**的部分

| 项 | 文档 | 我们 |
|---|---|---|
| 控制消息头 | 8B：length(u16) / reserved(u16) / serviceType(u32) | ✅ 一致 |
| 视频消息头 | 12B：length / timestamp / serviceType | ✅ 一致 |
| 视频握手 | HU 发 `VIDEO_ENCODER_INIT` → MD 回 `INIT_DONE` → HU 发 `START`，之后 MD 开始出流 | ✅ 一致 |
| 心跳 | 收到 `PROTOCOL_VERSION_MATCH_STATUS` 后 HU 必须**每秒**在视频通道发 `0x20002`，3s 没有 MD 会断开 | ✅ 已实现 |
| `MSG_CMD_HU_INFO`(0x18003) PB | `CarlifeDeviceInfo{ os=1, manufacturer=13, model=14, release=19, **btaddress=23(required)**, **btname=24(required)** }` | ❌ **之前发的是手机侧全套字段，还把 24 号当 carlifeversion 塞了 "7.0.0"，缺 required 的 btaddress** → 已按文档重写（0.1.0.80） |

### 21.2 真凶：`MSG_CMD_LAUNCH_MODE_NORMAL`（0x1801D）

文档 §10.2.2 原表（我按行读出来的原文）：

```
MSG_CMD_LAUNCH_MODE_NORMAL  0x0001801D  无  HU->MD  车机启动移动设备 CarLife 进入 Home 界面
MSG_CMD_LAUNCH_MODE_PHONE   0x0001801E  无  HU->MD  车机启动移动设备 CarLife 进入 Phone 界面
MSG_CMD_LAUNCH_MODE_MAP     0x0001801F  无  HU->MD  车机启动移动设备 CarLife 进入 Map 界面
MSG_CMD_LAUNCH_MODE_MUSIC   0x00018020  无  HU->MD  车机启动移动设备 CarLife 进入 Music 界面
MSG_CMD_GO_TO_FOREGROUND    0x00018025  无  HU->MD  把 CarLife 切回前台（AOA 场景用）
```

我们**从来没发过这四条**。所以手机 CarLife 一直停在自己的落地页（"无线转换盒"），
编码器空转（`Qin:0`）——这和"CarWith 在驱动它"的现象完全吻合：CarWith 每次连上都发 LAUNCH_MODE。

0.1.0.80 起：

```
[Phase 7] → VIDEO_ENCODER_START
[Phase 8] → LAUNCH_MODE_NORMAL（让 CarLife 进入投屏界面）
```

并且首帧未到时，1.5s 一次的补发包里同时带 `LAUNCH_MODE_NORMAL + VIDEO_ENCODER_START`。

### 21.3 还值得继续挖的

- `MSG_CMD_MODULE_CONTROL(0x18028)` / `MSG_CMD_MODULE_STATUS(0x10026)`：模块级控制（导航/音乐/VR），
  `enum ModuleId{CARLIFE_PHONE_MODULE_ID=1, NAVI=2, MUSIC=3, VR=4, CONNECT=5, MIC=6, MEDIAPCM=7, EDOG=8, CRUISE=9}`
- `MSG_CMD_GO_TO_FOREGROUND(0x18025)`：AOA 场景用；无线场景如果 CarLife 被系统压到后台，可以试它。
- `MSG_CMD_VIDEO_ENCODER_RESET(0x1800B)`、`MSG_CMD_PAUSE_MEDIA(0x1800E)`。
- PBVersion 官方最新是 `(2,0)`，我们发的是 `(3,2)`——如果 LAUNCH_MODE 不奏效，下一个实验就换 `(2,0)`。

## 22. 方案 A：先保 CarPlay 稳定（0.1.0.82/83）

### 22.1 事故：CarLife 一激活就把 CarPlay 顶掉（0.1.0.81 回归）

```
16:31:33.675  CORE: [connected] AirPlay 会话已激活
16:31:35.825  CARLIFE: 视频仍为 0——第 1 次强制把 CarLife 推到前台 exit=0
16:31:38.398  CARLIFE: 视频仍为 0——第 2 次强制把 CarLife 推到前台 exit=0
16:31:39.090  BT-S: 车机已连入 name=Redmi Note 11 5G，开始 iAP2 发送端会话
16:31:49.968  IAP2: 会话结束（链路断开）
16:31:49.970  SESSION: 已断开（用户/系统断开）
```

0.1.0.81 为"视频救援"每 2.5s 把 CarLife 拉前台，CarLife 起来后会去动蓝牙
（车机互联要抢 BT 通道），把 iAP2 的 ACL 顶断 → 车机侧表现为 CarPlay 断连。
**两者不能同时活着**，且 CarLife 是系统特权应用，我们拦不住它动蓝牙。

处置：
- 0.1.0.82 删除"视频救援"里的强制 `am start`（恢复 0.1.0.78 的稳定行为）；
- 0.1.0.82 修「链路已断、界面还显示已连接」：`runDisconnect` 的幂等提前返回分支
  现在也会把 `link/carLife` 刷回 `Idle`；
- 0.1.0.83 新增设置 **「启用 CarLife 联动」默认关**（`SettingsStore.carlifeEnabled`）：
  关着时不拉起 CarLife、不探测、不做 AIDL 投屏探测。

### 22.2 保留的协议修正（只在开启联动时生效）

- `MSG_CMD_LAUNCH_MODE_NORMAL(0x1801D)`：握手后立刻发，让手机 CarLife 进 Home 投屏界面；
- `MSG_CMD_HU_INFO(0x18003)`：按官方 proto 重写（os=1 / manufacturer=13 / model=14 /
  release=19 / **btaddress=23 required** / **btname=24 required**）。

### 22.3 下一阶段（方案 A 内）

1. 待机卡片的稳定性与观感（车机不黑屏、分辨率跟随 /info）；
2. 触摸/硬按键通道的准确性（iAP2 HID → 坐标换算）；
3. iAP2 断线自动重连（车机重连后无需人工干预）；
4. 诊断日志保持可导出，便于下次上车取证。

### 22.4 掉线自动重连（0.1.0.84 起）

问题：链路断开后 `releaseSessionResources()` 会把 iAP2 监听一起停掉；
如果此时车机的 ACL **一直没断**，就再也不会产生 `ACL_CONNECTED` 广播，
表现为「断了之后再也连不上，必须手动点连接」。

实现（`HyperCarRepo.scheduleAutoReconnect`）：

| 条件 | 行为 |
|---|---|
| 本次会话**建立过链路**（`sessionHadLink`） | 才重连；从没连上过不算掉线，不重连 |
| 用户点了「断开」 | `userDisconnectRequested=true` → 不重连 |
| 「上车自动连接」关着 | 不重连 |
| 次数 | 最多 6 次，退避 1s→2s→4s→8s→15s→15s |
| 成功进 `connected` | `reconnectAttempts` 清零 |
| 重连前发现 `streaming/connecting` | 取消（车机自己已经连回来了） |

触发点两处：`runDisconnect()`（正常拆除）和 `fail()`（协商失败/对端关闭控制通道）。

日志：

```
RECONNECT: 链路断开（…）——1000ms 后自动重连（第 1/6 次）
RECONNECT: 用户主动断开——不自动重连
RECONNECT: 本次会话没建立过链路（…）——不自动重连
RECONNECT: 已连续失败 6 次——停止自动重连，等车机重新连入
```

### 22.5 待机卡片与触摸（0.1.0.86）

**待机卡片**

| 项 | 之前 | 现在 |
|---|---|---|
| 母版选择 | 固定阈值 `ratio > 2.6` 用宽屏 | 按**最接近的宽高比**选（3.44:1 宽屏 / 1.78:1 16:9），并记一行 `AIRPLAY: 待机母版选择 WxH（R:1）→ 母版 …` |
| 发送节奏 | tick 16ms 一帧 = **60fps 常驻** | 前 2s 60fps 让接收端锁流，之后 **15fps**（每 4 拍一帧） |
| 自愈 | 靠循环回绕到 IDR（1s 一次） | 每 **5s 强制从 IDR 重放**一次，迟到/卡住的解码器能恢复 |

日志：

```
AIRPLAY: 待机母版选择 2560x744（3.44:1）→ 母版 1280x372（3.44:1）
AIRPLAY: 待机循环开始（母版 1280x372，60 帧；前 2s 60fps，之后 15fps，每 5s 补 IDR）
```

**触摸换算**

1. 修下溢：`.min(target.0 - 1)` 在 `target=0` 时 release 会回绕、debug 会 panic（编码器还没回报尺寸时就是 0），
   改成 `saturating_mul` + `max(1)` + `saturating_sub`；
2. 每次换算打诊断（前 5 次 + 每 200 次一行），一眼看出空间是否给错：

```
TOUCH: 车机(1280,372) 空间 2560x744 → 画布 2560x744 = (1280,372)（第 1 次）
```

3. 首次换算时校验宽高比，不一致就告警（接收端加黑边时边缘会偏）：

```
TOUCH: ⚠ 触摸空间 2560x744（3.44:1）与画布 1920x720（2.67:1）宽高比不一致——接收端拉伸显示时线性映射正确；若它加了黑边，边缘会偏
```

### 22.6 「关掉联动就起不来」的真因（0.1.0.87）

0.1.0.86 现场（`hypercar-log-20261026-164826.txt`）：

```
16:48:10.561  iap2 Phase 1 完成：拿到热点凭据与（可能的）AirPlay 端点
16:48:11.201  CARLIFE: 仍在等 CarLife 监听 7240（第 1s），补发唤醒广播
16:48:11.202  CARLIFE: 联动已关闭（设置里关着）——不拉起 CarLife…
   … 一直到第 16s …
16:48:24.934  LINK closed
16:48:24.936  SESSION: 已断开（用户/系统断开）
```

原因：`runConnect` 里在启动 Rust 引擎**之前**有一段
`probeLocalCarLifeMd(timeoutMillis = 30_000L)`——最多等 30 秒看 CarLife 有没有在 7240 监听
（联动开着时合理：要判断用"主动拨号"还是"HU 监听"）。关掉联动后 CarLife 永远不会起来，
这段就变成**整整 30 秒的空等**，而车机侧 CarPlay 只等十几秒就拆链路 → AirPlay 根本没开始。

修复（0.1.0.87）：联动关闭时**跳过这段探测**，直接按 HU 身份监听并立即协商：

```
CARLIFE: 联动已关闭——跳过 7240 探测，引擎直接以 HU 身份监听并马上协商 AirPlay
```

### 22.7 调试直连（0.1.0.88）

```
adb shell am broadcast -a io.github.hypercar.PROBE_CONNECT    -p io.github.hypercar   # 触发连接
adb shell am broadcast -a io.github.hypercar.PROBE_DISCONNECT -p io.github.hypercar   # 断连
adb shell am broadcast -a io.github.hypercar.PROBE_CAST       -p io.github.hypercar   # CarLife AIDL 探测
```

### 22.8 待机卡片原生分辨率（0.1.0.89）

原来待机母版只有 1280x372（宽屏）和 1280x720（16:9），车机 2560x744 时要把 1280 放大 2 倍 —— 字发虚。
新增 `assets/standby-wide-2x.h264`（2560x744，由 1280x372 用 ffmpeg lanczos 放大后重编，
baseline/level 5.1，60 帧 1 秒，36KB）：

```
ffmpeg -r 60 -i standby-wide.h264 -vf scale=2560:744:flags=lanczos \
  -c:v libx264 -profile:v baseline -level 5.1 -pix_fmt yuv420p \
  -g 60 -keyint_min 60 -sc_threshold 0 -b:v 700k -f h264 standby-wide-2x.h264
```

选择规则：

| 车机宽高比 | 车机宽度 | 用哪套 |
|---|---|---|
| 接近 3.44:1 | ≥ 2000 | **2560x744 原生**（本机 Desk 接收端实测 config/idr 都成功） |
| 接近 3.44:1 | < 2000 | 1280x372 |
| 其余 | — | 1280x720（16:9） |

同时修掉上一版的日志刷屏：`待机母版选择 …` 原来是**每帧**打一次（日志折叠器 10 秒压了 60 多条），
现在只在切换母版时由 `待机循环开始（母版 …）` 一行说明。

本机自测（Desk 接收端 192.168.1.208:7000）：

```
AIRPLAY: 已发送待机卡片母版 2560x744（config=true idr=true，60 帧素材）——车机不再黑屏
AIRPLAY: ✓ RECORD 完成——会话已激活
AIRPLAY: 待机循环开始（母版 2560x744，60 帧；前 2s 60fps，之后 15fps，每 5s 补 IDR）
AUDIO: 发包 136/s（应 136/s）
```

## 23. 实验：CarLife 优先启动（0.1.0.90）

依据（2015 版《CarLife 车机互联项目数据通信协议》"视频生成策略"原文）：

> 当移动设备的 CarLife 应用在前台时，移动设备会一直为 CarLife 应用生成视频流……；
> 当移动设备的 CarLife 应用不在前台时，移动设备将暂停生产视频数据，但仍然发送 length 为 0 的心跳包。

→ 这解释了 `Qin:0`：手机端 CarLife 不在前台 = 只发心跳。
而 CarLife 一起来就抢蓝牙、顶断 iAP2（见 §22.1）。

**假设**：它抢蓝牙只是"启动阶段的一次性动作"→ 把顺序反过来（先 CarLife、后 iAP2）两者可能共存。

开关：设置 →「CarLife 优先启动（实验）」（`SettingsStore.carlifeFirst`，默认关）。

打开后 `runConnect` 的第一件事：

```
EXPERIMENT: 先拉 CarLife 到前台——等它做完蓝牙初始化，再起 iAP2
EXPERIMENT: am start exit=0 Starting: Intent { cmp=… }
EXPERIMENT: 第 1500ms —— BT enabled=true 已配对=8
EXPERIMENT: 第 3000ms —— BT enabled=true 已配对=8
EXPERIMENT: 第 4500ms —— BT enabled=true 已配对=8
EXPERIMENT: 开始启动 iAP2（若紧接着出现 BT: 启动… 说明共存尝试开始）
```

之后照常：iAP2 → AirPlay → 若 CarLife 在 7240 监听则引擎切"主动拨号"，CarLife 出帧则视频直接通车机。

判定：
- 若 iAP2 链路能保持（不再 `LINK closed`）且 `Qin` 变正 → 共存成功，CarLife 视频可用；
- 若 iAP2 仍被顶断 → 说明 CarLife 是持续占用蓝牙，非 root 下无解，回到方案 A。

### 23.1 实验开关的一键触发（0.1.0.91）

```
adb shell am broadcast -a io.github.hypercar.PROBE_EXPERIMENT -p io.github.hypercar
```

它会把 `SettingsStore.carlifeFirst` 置 true 并立刻发起连接，省得手动翻设置页。

本机自测（接收端当时没开，只验证到钩子生效）：

```
EXPERIMENT: 先拉 CarLife 到前台——等它做完蓝牙初始化，再起 iAP2
EXPERIMENT: am start exit=0 Starting: Intent { cmp=com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity }
EXPERIMENT: 第 1500ms —— BT enabled=true 已配对=10
EXPERIMENT: 第 3000ms —— BT enabled=true 已配对=10
EXPERIMENT: 第 4500ms —— BT enabled=true 已配对=10
EXPERIMENT: 开始启动 iAP2（若紧接着出现 BT: 启动… 说明共存尝试开始）
```

### 23.2 17:15 那份日志的判读

- **没有 EXPERIMENT 行** → 那次实验开关是关的，跑的是常规路径；
- `17:15:44.079 LINK closed: bt socket closed` → iAP2 链路确实掉了一次，但 `17:15:48.850` 车机又连回来（新 iAP2 会话），
  且 `AUDIO: 136/s` 全程没断（音频走 WiFi，不受 BT 影响）——说明单次 BT 抖动不致命；
- `DIAG-ENC: exit=0，0 行` → 当时**连一个 MediaCodec 实例都没有**，即 CarLife 的编码器根本没起来
  （它不在投屏态），所以没有 `Qin` 可看，也不可能有视频。

### 23.3 实验结论（2026-10-26 17:20 现场，桌面接收端 192.168.1.208）

**共存：成立。**

```
EXPERIMENT: 先拉 CarLife 到前台——等它做完蓝牙初始化，再起 iAP2
EXPERIMENT: am start exit=0 …
EXPERIMENT: 第 4500ms —— BT enabled=true 已配对=10
SESSION: 端点就绪 ips=[192.168.1.208] port=7000
CORE: [carlife_waiting] CarLife 主动连接模式：目标 MD 127.0.0.1   ← CarLife 起来后自己占 7240（MD 角色）
CARLIFE: [Phase 1..8] 全部完成（含 LAUNCH_MODE_NORMAL）
CARLIFE: ← FOREGROUND（手机进入前台/亮屏）
AIRPLAY: ✓ 主会话 SETUP / 主屏流 1920x720 / RECORD 完成
AUDIO: 发包 136/s（应 136/s）
topResumedActivity=com.baidu.carlife.xiaomi/com.baidu.carlife.CarlifeActivity   ← CarLife 确实在前台
```

链路中途掉过 1 次（`LINK closed: bt socket closed`），但车机自己连回来，最终状态稳定：
CarLife 前台 + CarPlay 会话活着 + CarLife 协议握手完成 + 音频满速。**顺序反转确实解决了"一开 CarLife 就断"**。

**视频：仍然没有。**

```
[mId: 0] [video-debug-enc] state STARTED …
    Client:com.baidu.carlife.xiaomi,Component:c2.qti.avc.encoder,
    Qin:0, DQin:0/0, Render:0, encode:0, DQout:0/99
CARLIFE: 首帧视频未到——第 9 次补发 LAUNCH_MODE_NORMAL + VIDEO_ENCODER_START
```

编码器 STARTED 但输入队列恒空。结合 §19 的 AIDL 发现（`mixing.aidl.ICarLifeCastManager.prepareCast(CastConfig{surface})`），
可以定性：**CarLife 的画面是"由客户端提供 Surface 后渲染进去"的**，没有 CarWith 就没人给它 surface，
它自己的落地页也不会被采集成帧。所以 `Qin:0` 的真正原因不是协议、不是前后台，而是**缺客户端 surface 宿主**。

→ 结论：**CarLife 视频源在非 root 下无解**（三条根因各自独立：协议缺 LAUNCH_MODE、需要前台、需要客户端 surface；
前两条已解决，第三条必须由 CarWith 那种系统应用提供）。
实验开关保持默认关闭；方案 A（CarPlay 稳定）继续。

---

> ⚠ **本节结论已作废**（2026-10-27 反编译定谳）：Qin:0 不是因为"缺客户端 Surface"。
> 编码器的 input surface 由 CarLife 自己 createInputSurface() 持有，真正被短路的是
> CarlifeSurfaceWrapper.bindEncodeSurface() 里的 openglRenderType()==0 早退。
> 详见 §24。

---

## 24. 视频源攻坚：反编译定谳（2026-10-27 凌晨）

本节把 §19~§23 关于「CarLife 为什么不出画面」的猜测全部收敛成**有代码位置的结论**，
并记录 2026-10-27 凌晨做过的四次实测（协议加固、公版换装、重签补丁、超级投屏）。

### 24.1 一句话结论

> `Qin:0` 与协议无关、与 Surface 归属无关、与前后台无关。
> **CarLife 的 GL 渲染线程被一个"只有 CarWith 会应答的远端动作"挡住了**，
> 没有 CarWith 它根本不会把画面渲染进编码器的 input surface。

### 24.2 完整证据链（jadx 反编译 `com.baidu.carlife.xiaomi` 8.8.5）

工具与产物见 24.9。逐环如下（行号为反编译产物行号）：

```java
// ① 本 APK 把自己钉死成"小米定制版"
CarLifeApplication.onCreate():47
    CarlifeCoreSDK.getInstance().setPhoneSdk("sdk.build.xiaomi");
CarlifeCoreSDK.setPhoneSdk():433-435
    MixConfig.getInstance().setSdkBuild(str);
MixConfig.isMix4Xiaomi():92-93
    return "sdk.build.xiaomi".equalsIgnoreCase(mSdkBuild);      // → 恒 true

// ② 编码器起来了，input surface 也由它自己持有（不是外部给的）
Recorder.initMediaCodec():1239
    this.mEncSurface = this.mEncoder.createInputSurface();
Recorder.handleConnectEstablished():1273-1279
    CarlifeSurfaceWrapper.getInstance().bindEncodeSurface(w, h, mEncSurface);
    MixConnectManager.getInstance().onConnectEstablished();
    mEncoder.start();                                          // ← 日志 STARTED 的来源

// ③ 闸门：GL 渲染线程被一行 return 短路
CarlifeSurfaceWrapper.bindEncodeSurface():82-91
    if (getInstance().openglRenderType() == 0) {
        return;                                                // ← 什么都不做
    }
    mTextureSufaceHelper.initRender(surface, w, h);
    mTextureSufaceHelper.start();

CarlifeSurfaceWrapper.openglRenderType():49-80
    if (!isMix4Vivo() && !isMix4OPPO() && !isMix4SamsungSdk()
            && !isMix4Honor() && !isMix4Xiaomi()) {
        return 1;                                              // 非定制包：直接 OPENGL_OPEN
    }
    Bundle b = MixActionDispatcher.getInstance()
                   .dispatcherAction(Actions.CarLife.CARLIFE_USE_OPENGL);
    ...
    if (!isMix4SamsungSdk() && !isMix4Honor()) {
        return 0;                                              // ← 我们落这里：OPENGL_CLOSE
    }

// ④ 该 action 只有一个"远端"处理者
Actions.CarLife.CARLIFE_USE_OPENGL = "use_carlife_opengl"
处理者：RemoteActionProcessorProxy（注册在 CarLifeApplication:87）
    invokeMethod():80-91
        return MixPhoneStrategy.getInstance().getMixCastManager().invokeMethod(intent);
        // getMixCastManager() 为 CarWith 侧 Binder；没有它就 return null
其余两个处理器（DcsActionProcessor / CarLifeMixActionProcessor）都不认这个 action
（CarLifeMixActionProcessor.MAIN_ACTIONS 里没有 CARLIFE_USE_OPENGL）。
```

**因果链**：GL 没起来 → `TextureSufaceHelper` 未初始化 →
`ScreenCarlifeImpl.prepareCarLifeCast()` 里 `createCarlifeSurface()` 拿不到可用 Surface →
`CarLifePresentationController.showCarlifePresentaion()` 不渲染 →
编码器 input surface 永远收不到帧 → **`Qin:0`**。

### 24.3 由此修正的三条旧认知

| 旧认知 | 实际情况 |
|---|---|
| "CarLife 缺客户端 Surface 才会有 Qin:0" | ❌ 编码器自己持有 input surface，不需要外部 Surface |
| "小米把 VirtualDisplay 代码阉割了" | ❌ 代码都在（`DisplayUtils.createVirtualDisplay`、`TextureSufaceHelper`、`ScreenCarlifeImpl`），只是被 24.2③ 短路 |
| "盒子当 HU 能出画面是因为盒子做对了什么" | ⚠ 是因为 **CarWith 在驱动**（CarWith 应答 `use_carlife_opengl`），CarLife 才肯渲染 |

### 24.4 实测一：协议侧加固（提交 `60c783a`）

改动（`rust-core/src/carlife.rs`）：
1. `MD_FEATURE_CONFIG_REQUEST` 回显时把值为 0 的项提升为 1；
2. 首帧未到时除 `LAUNCH_MODE_NORMAL + VIDEO_ENCODER_START` 外，追加
   `MSG_CMD_GO_TO_FOREGROUND (0x18025)` 与
   `MSG_CMD_MODULE_CONTROL (0x18028, CarlifeModuleStatusList{cnt=1, moduleStatus{moduleID=1, statusID=1}})`。

结果（0.1.0.200，02:04~02:05）：

```
[Phase 1..8] 全部完成（含 LAUNCH_MODE_NORMAL）
MD 特性请求解析为空（0B）——回默认集合        ← 新发现：CarLife 发的是空请求
[video-debug-enc] STARTED … Qin:0, DQin:0/0, Render:0, encode:0, DQout:0/99
首帧视频未到——第 N 次补发 …（Phase 1 在 18s 内被重发 17 次）
```

**结论**：协议侧不是闸门。附带两个事实：
- `MD_FEATURE_CONFIG_REQUEST` 的 payload 是 **0 字节**——不是我们回错，是它不问；
- Phase 8 之后 CarLife 会关掉 CMD 连接，我们的"断了就重连"策略让它反复重来（后续应改成"握手完成后不盲目重连"）。

### 24.5 实测二：换装公版 `com.baidu.carlife` 8.9.5

假设：公版 `SDK_BUILD=main` → `isMix4Xiaomi()==false` → `openglRenderType()` 直接返回 1 → 不再需要 CarWith。

实测（02:21~02:26，小米版已 `pm disable-user` 隔离，确保只有公版在跑）：

```
com.baidu.carlife 8.9.5 启动后即监听 7240/8240/9340
我们发 HU_PROTOCOL_VERSION(3.2) → 公版不回版本匹配
Phase 1 卡死，每 ~2s 重发；公版进程无 video-debug-enc
```

**结论**：公版**同样不接受**我们的 HU 握手。结合 §24.3 的第三条，
可以判定：**历史上那些"Phase 1~8 走完"的会话，很可能是 CarWith 把 CarLife 推进去的，我们只是搭便车**。
这一点尚未用抓包坐实（见 24.11 B）。

### 24.6 实测三：重签补丁（改 `sdk.build.main` + 改包名）

做法：
- `apktool d` → 改 `CarLifeApplication.smali:270` 的 `"sdk.build.xiaomi"` → `"sdk.build.main"`；
- manifest `package` 与 12 处 `com.baidu.carlife.xiaomi*`（含 6 个 authorities、进程名、自定义权限）→ `com.hypercar.carlife`；
- `zipalign` + `apksigner`（自签 keystore）→ 安装。

结果：
- 包能装、能启动、也能自己监听 7240/8240/9340（说明补丁本身生效）；
- **但应用启动/运行时提示"签名不对"** —— 自校验失败，功能不可用。
- Java 层没找到显式校验（`GET_SIGNATURES` / `signingInfo` 只命中 androidx 与百度登录 SDK），
  判断校验在 native（`libcarlife*.so`）或混淆类中。

**结论**：**重签这条路不可行**，除非同时逆向掉 native 校验。产物保留在
`D:\devtools\carlife-patched2-signed.apk`（仅供研究，不要装到主力机）。

### 24.7 实测四：超级投屏（SuperCast）

公版界面里有「投屏」入口，用户已在 PC 上通过百度网页完成解锁（需要 adb）。
解锁后点击投屏 → 应用提示 **"需要连接车机"**。

**结论**：超级投屏依赖已有 CarLife 车机连接，不能作为绕过握手的手段。
即"先有连接，才有画面"，与 24.2 的结论一致（连接 → CarWith 应答 → GL 起来 → 才有渲染）。

### 24.8 CarLife 日志：xlog 加密，读不到明文

- 路径：`/sdcard/Android/data/com.baidu.carlife/files/xLog/carlife_YYYYMMDD.xlog`
- 文件头 `09 01 00 02 02 55 01 00 00 ...`，随后是二进制；
- 扫描全文件 **找不到任何 zlib 块**（78 01/9C/DA），判断为加密写盘。

**结论**：不能靠读 CarLife 自己的日志来定位问题，只能靠反编译 + 抓包。

### 24.9 复现清单（工具与产物）

| 用途 | 路径 |
|---|---|
| jadx CLI（1.5.0） | `D:\devtools\jadx\bin\jadx.bat`（配 `JAVA_HOME=D:\devtools\jdk17`） |
| 反编译产物（8.8.5，12207 个 java） | `D:\devtools\jadx-out\sources\` |
| apktool（2.9.3） | `D:\devtools\apktool\apktool.jar` |
| 解包目录（打过补丁） | `D:\devtools\carlife-patch\` |
| 补丁 APK（含自签） | `D:\devtools\carlife-patched2-signed.apk` |
| 原始小米版 APK（还原用） | `D:\devtools\carlife-apk\base.apk`（8.8.5/274） |
| CarWith APK | `D:\devtools\carlink-apk\base.apk`（8.9.5 时代） |
| 自签 keystore | `D:\devtools\hypercar-sign\carlife.keystore`（口令 hypercar123） |

设备侧状态（2026-10-27 02:40 收尾时）：
`com.baidu.carlife.xiaomi` 8.8.5（已 `pm enable`，CarWith 可正常用）、
`com.baidu.carlife` 8.9.5（公版，共存）、HyperCar 0.1.0.200。

### 24.10 一句话给未来的自己

> 别再试着"给 CarLife 喂 Surface"或"改装公版"了。
> 它要的是 **CarWith 那个远端应答 `use_carlife_opengl`**；没有 CarWith，
> 它连自己的落地页都不会去渲染。判断一个方向是否值得做，只看一条：
> **能不能让 `openglRenderType()` 返回 1。**

### 24.11 下一步（按成本排序）

**A. 反编译公版 8.9.5，查它为什么不应答我们的 CMD 握手**
重点：
- `ConnectClient` 在 TCP accept 之后的分发前置条件（是否需要先收到 `MSG_PORT_ESTABLISH(52480)`）；
- `setBoxAuthorized(0/1/2/3)` 的触发点（`BoxConnectClient:114-123`、`BleBluetoothDiscover:235`、`FeatureConfigManager:301`）
  —— 谁是"已授权的盒子"，靠什么动作授权（UDP 7999 发现？热点直连？扫码？）。

**B. 用真盒子 + PC 中间人抓一次成功握手**
把手机与盒子之间的 7240 流量经 PC 代理（盒子→PC→手机），拿到真实握手的**字节序**，
再照着复刻。这是最可能一次成功的路线。

**C. 收手，回到自绘界面**
分支 `feat/carplay-only-ui`（提交 `2f328bb`：删除 CarLife 与 hub，只留 AirPlay 链路）已构建验证。
`nativePushVideoFrame` / `nativeSetVideoSize` / `nativePushAudioPcm` 三个 JNI 已就位，
接上"自绘界面 + 自编码"即可出画面，**不依赖 CarLife 的任何行为**。

> 判断标准：若 A 在 1 小时内拿不到"握手前置条件"的明确线索，直接转 B；
> 若 B 也无法在当晚拿到抓包，转 C。
---

## 25. 冒充 CarWith：让 CarLife 真正出画面（2026-10-27 晚，0.1.0.211~217）

> 本节是整条 CarLife 路线**第一次拿到真 H.264 帧**的完整记录。
> 前面 §24 的结论（GL 被 `use_carlife_opengl` 挡住）在这里被证实，并给出了绕过的办法：**我们自己当宿主**。

### 25.1 一句话结论

> CarLife 不是"MD 服务端 + 自己渲染"的独立应用，它把**宿主（正常情况下是 CarWith）**当成了必需的一环：
> 没有宿主，它照样监听 7240/8240，但**不会建渲染源**，媒体通道 accept 后 15~30ms 就关掉。
> 我们自己实现宿主（`ICarLifeCastManager`）并回答 `use_carlife_opengl`，它就正常渲染并往 8240 推帧了。

### 25.2 入场券：`CarlifeConnectService` 需要两个 extra

```java
// com/baidu/carlife/service/CarlifeConnectService.java:38-53
private void connectStart(Intent intent) {
    if (intent == null || !intent.hasExtra("bindPackageName")
            || !intent.hasExtra(MixConstants.CONNECT_KEY_USB_TYPE)) { return; }   // ← 少一个就直接 return
    if (MixPhoneStrategy.getInstance().getMixCastManager() == null) {
        MixConnectManager.getInstance().bindManufacturerService(intent);          // ← bind 我们
    }
    MixConnectManager.getInstance().onConnectStart(intent);                       // ← 会话开始
}
```

```java
// MixPhoneStrategy.connectCastManager():31-46
String pkg = intent.getStringExtra("bindPackageName");
String cls = intent.getStringExtra("bindClassName");
Intent i2 = new Intent(); i2.setClassName(pkg, cls);
CarLifeCastManagerProxy.bindService(AppContext.getInstance(), i2);                // ← bind 到我们的 Service
```

所以我们发过去的是：

```kotlin
Intent().apply {
    setClassName(carLifePackage, "com.baidu.carlife.service.CarlifeConnectService")
    putExtra("bindPackageName", context.packageName)      // 我们自己的包
    putExtra("bindClassName",   CastHostService::class.java.name)   // 我们的宿主 Service
    putExtra("usb_type", 0)                               // MixConstants：0=WIFI_HOTSPOT 1=P2P 2=WIFI_BOX 11=AOA 12=ADB
}
```

> 这也解释了 §19.14 那次探测为什么拿到空 binder：当时只 `setComponent`，两个 extra 都没有。

### 25.3 宿主 AIDL 的字节级布局（无需引入对方 SDK）

`com.baidu.carlife.mixing.aidl.ICarLifeCastManager`，DESCRIPTOR 固定为
`com.baidu.carlife.mixing.aidl.ICarLifeCastManager`，事务号：

| code | 方法 | 载荷 |
|---|---|---|
| 1 | `prepareCast` | `writeInt(存在标记)` + `int width, int height, int dpi, Surface`（`CastConfig.writeToParcel`） |
| 2 | `stopCast` | 无 |
| 3 | `setSystemAudioCallback` | `IBinder` |
| 4 | `setCastCallback` | `IBinder` |
| 5 | `invokeMethod` | `writeInt(存在标记)` + `Intent.writeToParcel`；**返回值** `writeInt(1)+Bundle` 或 `writeInt(0)` |
| 1598968902 | INTERFACE_TRANSACTION | 回写 DESCRIPTOR |

实现见 `app/src/main/java/io/github/hypercar/carlife/CarLifeCastHostBinder.kt`（纯 `Binder.onTransact`，不复制对方 26 KB 的 AIDL 生成类）。

**⚠ 坑 1**：`prepareCast` 的第一个 int 是**存在标记**，不是 width。第一版漏了它，日志读成 `1x1920@720`（应为 `1920x720@320`）。

### 25.4 关键回答：`use_carlife_opengl`

CarLife 会通过 `invokeMethod` 问宿主要一堆动作，其中一条决定它到底渲不渲染（§24.2③）：

```kotlin
"use_carlife_opengl" -> Bundle().apply {
    putInt("use_opengl_type", 1)     // CarlifeSurfaceWrapper.openglRenderType() 读这个
    putBoolean("use_opengl", true)   // 兼容另一条读取分支
}
```

返回 1 之后：`bindEncodeSurface()` 不再早退 → `TextureSufaceHelper.initRender/start` → GL 起来 →
`createCarlifeSurface` + `showCarlifePresentaion` → 车机界面开始渲染进编码器。

实测这条询问只在**编码器初始化那一刻**发生，所以顺序必须是"先绑宿主，再发 `VIDEO_ENCODER_INIT`"。

### 25.5 实测：第一次拿到真帧（2026-10-27 20:55）

```
CASTHOST: 会话开始前先绑定宿主 target=com.baidu.carlife.xiaomi
CASTHOST: ← setCastCallback(true) / setSystemAudioCallback(true)
CASTHOST: ← invokeMethod action=carlife_notify_vehicle_channel        → null（不需要回答）
CARLIFE: [Phase 1] ← 版本匹配
CASTHOST: ← invokeMethod action=use_carlife_opengl → use_opengl,use_opengl_type
CASTHOST: ← prepareCast 1920x720@320dpi surface=true                  ← 画布交过来了
CARLIFE: [Phase 7] → VIDEO_ENCODER_START —— 握手完成
CARLIFE: VIDEO 读到 12B    ：00 00 40 1A 6A E0 9F 56 00 02 00 01
CARLIFE: VIDEO 读到 16410B ：00 00 00 01 67 42 80 29 DA 01 E0 16 …   ← SPS(67)+PPS(68)+IDR
CARLIFE: VIDEO 读到 414B   ：00 00 00 01 41 E2 09 FC …               ← P 帧
```

`67 42 80 29` 解出来是 **1920×720**，与我们请求的画布一致。

### 25.6 三个必须遵守的时序/状态约束

1. **先绑宿主，再握手**：CarLife 只在编码器初始化时问 `use_carlife_opengl`；编码器先起来（比如它自己预热）我们就永远等不到这条询问 → 表现为 `Qin:0`。
2. **CarLife 每进程只 bind 一次**：`MixConnectManager.bindManufacturerService` 开头
   `if (this.mMixCastManager != null || intent == null) return;`。改代码后必须 `am force-stop` 冷启动，否则它不理你。
3. **`CarlifeConnectService` 有 3 秒节流**：`startServiceTime` 内重复 start 会被 "repeated startup service" 丢掉。

另外：`am force-stop` 之后包处于 stopped 状态，**必须先 `am start` 它的 Activity** 才能收到我们的 `startService`
（否则连 `Connection refused`）。同一约束也适用于我们的触发广播：宿主 App 需在前台，`startService` 才不被 Android 8+ 拦。

### 25.7 已实现的代码（未提交）

| 文件 | 作用 |
|---|---|
| `app/src/main/java/io/github/hypercar/carlife/CarLifeCastHostBinder.kt` | ICarLifeCastManager 的 Binder 实现（事务 1~5 + INTERFACE），回答 `use_carlife_opengl` |
| `app/src/main/java/io/github/hypercar/carlife/CastHostService.kt` | 宿主 Service + `startCarLifeSession(pkg, usbType)` |
| `AndroidManifest.xml` | 声明 `.carlife.CastHostService`（`exported=true`，CarLife 才能 bind） |
| `HyperCarRepo.kt` | ① `init()` 接线日志与画布回调；② **握手前预绑定宿主**（`resolveCastHostCarLifePackage()` 只认 `com.baidu.carlife.xiaomi` / `com.baidu.carlife`，**不包括 CarWith**） |
| `DebugProbeReceiver.kt` | `PROBE_CAST` 广播：`--es pkg <包名> --ei usbType 0|1|2` |

### 25.8 尚未解决的问题

- **帧到了 8240，但车机上仍是待机卡片（母帧）**：说明 AirPlay 屏幕流没接上这批帧，或 CarLife 会话在 Phase 8 后停掉。
  下一步排查点：
  1. `engine.rs` 视频泵在 `ScreenSender` 未就绪时丢帧（日志"屏幕流未就绪"）——需要确认帧是否在 SETUP/RECORD 之后才到；
  2. CarLife 在我们 Phase 8 关 CMD 后是否停止编码（`VIDEO 读到` 是否持续增长）；
  3. 画布 1920x720 与车机 `VideoConfig` 是否一致（车机侧 `/info` 报的尺寸）。
---

## 26. 打通：CarLife 画面真的上到车机（2026-10-27 22:00，0.1.0.238）

> **结论先行**：这一节记录整条链路第一次**完整成功** ——
> CarLife 编码 → 8240 → 我们转发 → AirPlay → 车机屏上出现**我们自己画的内容**（验证页大字 + 跳动时钟 + 每秒换底色）。

### 26.1 CarWith 的宿主实现（反编译 `CarWith.apk`，53.5 MB / 8 dex）

```java
// com/xiaomi/ucar/carlife/CarlifeConnService.java:48
public class CarLifeCastManagerImpl extends ICarLifeCastManager.Stub {
    public void prepareCast(CastConfig cfg) {                       // :60
        e2.g.i().t(new e2.b(cfg.width, cfg.height, cfg.dpi, cfg.surface));   // 交给 e2.g 单例
    }
    public Bundle invokeMethod(Intent it) {                          // :50
        return com.xiaomi.ucar.carlife.k.p().r(it);                  // 所有动作应答都在 k(33KB) 里
    }
}
// e2/g.t() → 分发给监听者
// com/miui/carlink/castfwk/CarlinkService.java:534  public void b(e2.b v) → 状态机
// com/miui/carlink/castfwk/CarlinkStateMachine.java:1166
public VirtualDisplay N0(Surface s, int w, int h, int dpi) {
    return displayConnected(s, w, h, 0, dpi, 30, 2);                 // ← 关键是 VirtualDisplay(surface)
}
```

**CarWith 拿到 `CastConfig.surface` 后，就是建一个 `VirtualDisplay`，把界面挂上去。**
我们的实现与它同构，只是把"挂什么"换成了自己的 `Presentation`。

### 26.2 我们的实现

| 文件 | 作用 |
|---|---|
| `carlife/CastHostService.kt` | 向 `CarlifeConnectService` 发带 `bindPackageName`/`bindClassName`/`usb_type` 的 Intent（= 冒充 CarWith） |
| `carlife/CarLifeCastHostBinder.kt` | `ICarLifeCastManager` 的字节级实现；**回答 `use_opengl_type=0`** |
| `carlife/CastDisplay.kt` | `prepareCast` → `VirtualDisplay(OWN_CONTENT_ONLY, surface)` → `Presentation` 挂验证页 |

### 26.3 五个坑（按踩到的顺序，全部有现场日志）

**坑 1：先绑宿主，再握手。**
CarLife 只在**编码器初始化那一刻**问宿主 `use_carlife_opengl`；编码器先起来就永远等不到这条询问。
→ `HyperCarRepo` 在 CarLife 探测/唤醒之前就 `startCarLifeSession()`。

**坑 2：`use_opengl_type` 必须答 0（OPENGL_CLOSE）。**
答 1 时 CarLife 走自己的 GL 合成线程，我们 VirtualDisplay 画的内容**进不去** —— 表现为帧大小恒 `475B` 一动不动；
答 0 时走 `ScreenRealImpl.getVirtualSurface()`，直接把**编码器输入面**交给宿主，像素才会真的变成我们的画面。
（答 1 时帧大小 768/1157/851/1034 来回跳，答 0 时同样跳但内容真的进去了 —— 先用"每秒换底色"验证的。）

**坑 3：`VIRTUAL_DISPLAY_FLAG_PUBLIC` 要签名权限。**
`createVirtualDisplay(..., PUBLIC, ...)` 抛
`Requires ADD_MIRROR_DISPLAY, CAPTURE_VIDEO_OUTPUT or CAPTURE_SECURE_VIDEO_OUTPUT permission`。
→ 改用 `OWN_CONTENT_ONLY`（display 变 `FLAG_PRIVATE`），但**私有虚显不能再 `startActivity(launchDisplayId=…)`**
（`SecurityException: Permission Denial … with launchDisplayId=29`）→ 改用 `Presentation` 挂窗口（`PRIVATE_PRESENTATION`），
且必须在**有 Looper 的线程**创建（binder 回调线程直接 new Presentation 会 `Can't create handler inside thread`）。

**坑 4：待机卡片会把画布改成母版尺寸，和真帧尺寸打架。**
屏幕流 SETUP 后立刻发母版卡片（1280x720）是为了"车机不黑屏"，但 CarLife 实际编码是 1920x1080：
```
CASTHOST: prepareCast 画布 1920x1080@320dpi surface=true
VIDEO: 画布 1920x1080 → 1280x720（母版卡片）
CARLIFE: VIDEO 读到 768B …            ← 真帧是 1920x1080
```
车机按 1280x720 配解码器、却收到 1920x1080 的流 → **解码器起不来，永远停在自己的母帧**。
→ 待机卡片只在"从未收到过真帧"时插；真帧来过之后不再插。

**坑 5（决定性）：真帧到达时必须按它自己的 SPS/PPS 重发一次 VideoConfig。**
```rust
// engine.rs 视频泵，首个真帧：
if !real_config_sent {
    let sps = …nal_type==7; let pps = …nal_type==8;
    s.set_size(real_size);                    // CarLife 实际编码尺寸（1920x1080）
    s.send_config(build_avcc(sps, pps));      // 车机按真帧重配解码器
    real_config_sent = true;
}
```
**加完这一条，画面立刻出来了**（`VIDEO: 按真帧重发 VideoConfig（画布 Some((1920, 1080))）`）。

另外两个配套修复：
- **单实例保证**：`nativeStart` 先取出旧 `ENGINE` 句柄并 `notify` 停止 —— 否则两个视频泵同时改画布尺寸（日志里能看到两个不同 TID 在互相覆盖）。
- **CMD 重连策略**：重连时**不再重发 `VIDEO_ENCODER_INIT`**（会重置编码器），但**要补发 `VIDEO_ENCODER_START`**（否则 CarLife 不再编码）。

### 26.4 分辨率：车机不下发就用自定义

- DiPlay 这类接收端**连接时不下发分辨率** → 用设置里的自定义值（本次设为 **2400×1080**，`PROBE_RESET_RES` 广播可随时改）；
- CarLife 收到 `VIDEO_ENCODER_INIT 2400x1080` 后**夹到 1920×1080**（`prepareCast 1920x1080@320dpi`），这属正常；
- 我们最终以 **CarLife 报告的实际编码尺寸**为准重发 VideoConfig，所以夹取不影响出画。

### 26.5 成功现场（21:57:35）

```
10-27 21:57:35.733  VIDEO: 按真帧重发 VideoConfig（画布 Some((1920, 1080))）
10-27 21:57:35.733  VIDEO: 画布 1280x720 → 1920x1080（跟随手机实际编码分辨率）
                    → 车机屏出现：黑底 + 青色 "HyperCar CAST OK" + 跳动时钟 + 每秒换一次的底色
```

### 26.6 下一步

1. **把验证页换成真正的车机界面**：`CastDisplay.buildProbeView()` → 正式首页（Dock + 地图卡片 + 音乐卡片）；
2. 触摸回传：`PRIVATE_PRESENTATION` 窗口收不到车机触摸，需要把 AirPlay HID 事件注入到这块虚显
   （普通应用没有 `INJECT_EVENTS`，计划走 Shizuku 的 `IInputManager.injectInputEvent(displayId=虚显)`）；
3. 音频：CarLife 的 9240/9241 已经在读，确认能进 AirPlay 的音频流。

### 26.7 代码清单（截至 0.1.0.238，**均未提交**）

`carlife/CastHostService.kt`、`carlife/CarLifeCastHostBinder.kt`、`carlife/CastDisplay.kt`、
`carlife/CastProbeActivity.kt`（保留未用）、`session/HyperCarRepo.kt`、`session/DebugProbeReceiver.kt`、
`settings/SettingsStore.kt`、`AndroidManifest.xml`、`rust-core/src/engine.rs`、`rust-core/src/carlife.rs`、`rust-core/src/lib.rs`。