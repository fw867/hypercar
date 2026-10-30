package io.github.hypercar.core

/**
 * Rust 核心层 JNI 桥（libhypercar_core.so）。
 *
 * 与「参考文档」的分工差异（详见 docs/NEGOTIATION-GAPS.md）：
 *  - 参考文档：Kotlin 完成 AirPlay 全部协商后把「已连通的 FD + 密钥」交给 Rust；
 *  - 本项目：协商整体下沉 Rust（/info、pair-verify、RTSP SETUP、事件通道、三条心跳），
 *    Kotlin 只负责「拿到绑定车机网络的 socket FD」并通过 [SocketProvider] 按需供给。
 *    这样协商状态机只有一份实现，秒连路径少一次跨语言往返。
 */
object RustCoreBridge {

    init {
        System.loadLibrary("hypercar_core")
        nativeInitLogger()
    }

    /**
     * socket 供给器：Rust 协商过程中需要新开「绑定车机网络」的套接字
     * （事件通道、视频 dataPort、音频 dataPort/controlPort、keepAlive 端口）。
     * Kotlin 实现必须走 carNetwork.socketFactory / carNetwork.bindSocket，
     * 保证不走手机流量；返回 Linux FD（已 detach），失败返回 -1。
     */
    interface SocketProvider {
        fun openTcpFd(host: String, port: Int, timeoutMs: Int): Int
        fun openUdpFd(host: String, port: Int): Int
    }

    /** Rust → Kotlin 状态/日志回调（在 Rust 工作线程调用，实现方需自行切主线程）。 */
    interface EventListener {
        /** 状态迁移：[state] 为机器码，[detail] 为人话（见 engine.rs 的 emit 调用）。 */
        fun onState(state: String, detail: String)

        fun onLog(line: String)

        /** 统计心跳（约 1s 一次）：fps/frames/kbps/音频闪避。 */
        fun onStats(fps: Int, frames: Long, kbps: Long, audioPlaying: Boolean, ducking: Boolean)

        /** 车机日夜模式（AirPlay setNightMode）：true = 夜间。 */
        fun onNightMode(night: Boolean)

        /** 车机 forceKeyFrame：请求编码器立刻产出 IDR。 */
        fun onForceKeyFrame() {}

        fun onDisplaySize(width: Int, height: Int) {}
        fun onDisplayLayout(width: Int, height: Int, viewX: Int, viewY: Int, viewWidth: Int, viewHeight: Int,
                            safeX: Int, safeY: Int, safeWidth: Int, safeHeight: Int, drawOutside: Boolean) {}
        fun onIap2Start(id: Long, deviceId: String) { io.github.hypercar.iap2.WifiIap2Bridge.start(id, deviceId) }
        fun onIap2Bytes(id: Long, bytes: ByteArray) { io.github.hypercar.iap2.WifiIap2Bridge.receive(id, bytes) }
        fun onIap2Stop(id: Long) { io.github.hypercar.iap2.WifiIap2Bridge.stop(id) }
    }

    /**
     * 车机 HID 触摸回调（Rust 工作线程调用，实现方需自行切主线程）。
     *
     * action：0=DOWN、1=UP、2=MOVE；x/y 已换算到编码画布像素（与 VideoConfig 画布一致）。
     * 与 [EventListener] 分开注册：触摸是高频事件，且 UI 层可能在会话中途重建。
     */
    interface TouchListener {
        fun onTouch(action: Int, x: Int, y: Int)
    }

    @Volatile
    private var listener: EventListener? = null

    fun setListener(l: EventListener?) {
        listener = l
        nativeSetListener(l)
    }

    fun currentListener(): EventListener? = listener

    @Volatile
    private var touchListener: TouchListener? = null

    /** 装/卸车机触摸监听器（null = 卸载）。会话开始前装一次即可。 */
    fun setTouchListener(l: TouchListener?) {
        touchListener = l
        nativeSetTouchListener(l)
    }

    fun currentTouchListener(): TouchListener? = touchListener

    external fun nativeInitLogger()

    external fun nativeSetListener(listener: EventListener?)

    external fun nativeSetTouchListener(listener: TouchListener?)

    /**
     * 启动全链路网关。
     *
     * @param configJson 引擎配置（见 rust-core/src/engine.rs 的 EngineConfig::from_json）：
     *   device_id（本机真实蓝牙 MAC）、car_ip、airplay_port、width/height/fps（屏幕推送分辨率）、
     *   identity_path（ed25519 长期身份落盘路径）、enable_audio、keep_alive_stats…
     * @param controlFd Kotlin 已 connect 到 车机 AirPlay 端口（carNetwork 绑定）的 TCP FD（已 detach）
     * @param socketProvider 后续新端口的 socket 供给
     */
    external fun nativeStart(configJson: String, controlFd: Int, socketProvider: SocketProvider): Boolean

    external fun nativeStop()
    external fun nativeMarkWifiIap2Ready(id: Long, ready: Boolean)
    external fun nativeCanKeepWifiSession(): Boolean
    external fun nativeSendIap2(id: Long, bytes: ByteArray): Boolean

    /** Request native head-unit UI without tearing down CarPlay or its audio route. */
    external fun nativeRequestHostUi(): Boolean

    /** 投喂一帧 H.264（Annex B，本机 MediaCodec 编码输出）。[ptsUs] 为微秒时间戳。 */
    external fun nativePushVideoFrame(data: ByteArray, ptsUs: Long)

    /** 投喂画面新尺寸（对齐 AirPlay VideoConfig）。 */
    external fun nativeSetVideoSize(width: Int, height: Int)

    /** 投喂一包 PCM（16-bit 小端交错；本机采集的音频）。 */
    external fun nativePushAudioPcm(pcm: ShortArray, rate: Int, channels: Int)

    /**
     * **SPIKE-P0 调试专用**：直接向引擎触摸通道注入一条事件。
     *
     * 没有车机/接收端发 HID 时，用它验证「Rust 触摸通道 → JNI → Kotlin onTouch」。
     * 生产路径不调用；P0 结束后可删。
     */
    external fun nativeDebugTouch(action: Int, x: Int, y: Int)

    /** Rust 侧是否在运行（诊断）。 */
    external fun nativeIsRunning(): Boolean

    /**
     * 构造宝马 HUD/仪表导航报文（iAP2 0x5200 TLV），返回完整报文字节。
     * 由 iAP2 会话层在事件通道 iAPSendMessage 或蓝牙控制会话里发送。
     */
    external fun nativeBuildIap2NavPacket(
        maneuverType: Int,
        currentStreet: String,
        nextStreet: String,
        distToManeuverM: Int,
        remainTimeS: Int,
        remainDistM: Int
    ): ByteArray
}

