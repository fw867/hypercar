# 连接与媒体诊断

更新：2026-11-07。先找最后完成的阶段，再判断哪些代码实际执行过。

| 证据 | 可确认 | 尚不能确认 |
|---|---|---|
| 系统配对、ACL或入网 | 系统链路/网络存在 | iAP2、邀请和媒体可用 |
| CTRL_REGISTERED | 当前注册回调成功 | 车机已发现或已邀请 |
| BT-ACCEPT / RFCOMM首次读写 | socket已交付、开始字节交互 | Detect/SYN/识别完成 |
| ctrl GET或本轮iAP2端点 | 车机开始推进AirPlay | pair-verify通过 |
| RECORD 200 | 控制启动完成 | 屏幕/音频SETUP、真实音画 |
| video_tcp_ready / tcp_frames增长 | 数据socket和写出有进展 | 车机解码、显示 |
| audio_ready / rtp_packets增长 | 已建立音频流/有实际发包 | 非零音源和扬声器出声 |

导出头的ATTEMPT按source/profile/record/invite/milestones/reason看；PIPELINE区分资源、视频源、屏幕SETUP、TCP和音频。控制存活但媒体失败时，首页“会话已连接”不能解释为已投屏。

## 蓝牙和邀请

BT-ACCEPT在门控前记录；BT-REJECT附旧代次、暂停、handoff、非目标、重复及worker未退出等原因。BT-GATE/HANDOFF记录前后状态，BT-WORKER记录真正执行资格，RFCOMM-IO记录字节数与本地取消，BT-HEALTH约15秒显示监听、线程和接入状态。

accepted=0时调查接入上游；ACCEPT后REJECT按原因排App门控。SDP registered或ACL加密不证明RFCOMM字节到达。HFP被拒不等于CarPlay RFCOMM失败；未抓到ACL也不能排除Wi-Fi活动。

核对真实蓝牙MAC、控制TXT id、长期AirPlay控制身份、接收端配对资料和当前Network。共享Wi-Fi名称不能作车机身份。主动候选与当前0x4301分别标记；WAITING_PROACTIVE_RETRY/RETRYING区分退避和纯邀请等待。

## 媒体与应答

RESOURCE、SCREEN_SETUP、AUDIO_SETUP以RTSP通道及CSeq关联，完整出站hex按offset/total重组并校验SHA256后解plist。分别看TX_PREPARED、TX_WRITTEN、RTSP状态、业务status和应答体。空体/缺status不是业务成功确认；本地EOF/超时不能反推一个未收到的车机拒绝码。

音频看源peak/rms、active、路由、owner_phone、blocked_samples及RTP；按键看HID-KEY/MEDIA-KEY/VOICE-KEY。详细行为分别见[音频](AUDIO.md)、[输入](INPUT-AND-VOICE.md)。

## 日志范围

连续导出可能重叠，先核对日期、版本、会话和时间，再去重统计；不能把内部引擎重试当成多次上车。文件名或profile名称不能证明实际接收端型号。原始采集与隐私说明见[CAPTURE](CAPTURE.md)。
