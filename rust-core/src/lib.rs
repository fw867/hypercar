//! HyperCar 核心层 —— JNI 入口、Tokio 运行时、引擎生命周期。
//!
//! 与「参考文档」的架构差异（docs/NEGOTIATION-GAPS.md）：
//!  参考文档里 AirPlay 协商留在 Kotlin，Rust 只接「已连通 FD + 已派生密钥」；
//!  本项目把协商整体下沉到 Rust（/info → pair-verify → SETUP → RECORD → 心跳），
//!  Kotlin 只提供「绑定车机网络的 socket FD 供给器」——协商状态机只有一份实现。
//!
//! JNI 命名对应 io.github.hypercar.core.RustCoreBridge（下划线式导出名）。
//!
//! # dead-code lint 说明
//!
//! 本 crate 是 `crate-type = ["cdylib"]`，dead-code lint 只把「从 8 个 JNI 导出
//! 可达」的代码算作活跃。但协议实现刻意保留了完整覆盖面（如 pair-setup M1..M6
//! 完整链路，主路径只走 pair-verify）与一批诊断/统计访问器（`frames_sent`、
//! `packets_sent`、`resolution` 等，供后续 UI 或日志接线）。这些不是遗留垃圾，
//! 而是库的 API 面——故 crate 级 allow，避免用 `#[allow(dead_code)]` 逐个标注
//! 淹没真正的意图注释。新增代码若确属误删引用，仍应靠 code review 拦截。
#![allow(dead_code)]

mod airplay;
mod audio_out;
mod bplist;
mod crypto;
mod engine;
mod iap2_nav;
mod pairing;
mod rtsp;
mod screen;
mod srp6a;
mod tlv8;
mod timing;

use std::os::unix::io::{FromRawFd, RawFd};
use std::sync::Arc;

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JShortArray, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use tokio::net::{TcpStream, UdpSocket};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::{mpsc, Notify};

use crate::airplay::{SocketProvider, TouchInput};

static TOKIO_RT: Lazy<Runtime> = Lazy::new(|| {
    Builder::new_multi_thread()
        .worker_threads(4)
        .thread_name("hypercar-core")
        .enable_all()
        .build()
        .expect("tokio runtime 创建失败")
});

static JAVA_VM: RwLock<Option<jni::JavaVM>> = RwLock::new(None);
static LISTENER: RwLock<Option<GlobalRef>> = RwLock::new(None);

struct EngineHandle {
    stop: Arc<Notify>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    /// 自绘界面投喂给引擎的视频帧 / 音频 PCM（nativePushVideoFrame / nativePushAudioPcm）
    source_tx: mpsc::UnboundedSender<engine::SourceEvent>,
}

static ENGINE: Lazy<parking_lot::Mutex<Option<EngineHandle>>> =
    Lazy::new(|| parking_lot::Mutex::new(None));

// ---------------------------------------------------------------- 回调工具

/// Rust → Kotlin 日志（经 EventListener.onLog；同时走 logcat）。
pub(crate) fn emit_log(line: &str) {
    log::info!("{line}");
    call_listener("onLog", |env, obj| {
        let jline = env.new_string(line)?;
        env.call_method(obj, "onLog", "(Ljava/lang/String;)V", &[(&jline).into()])
            .map(|_| ())
    });
}

pub(crate) fn emit_state(state: &str, detail: &str) {
    log::info!("STATE {state}: {detail}");
    call_listener("onState", |env, obj| {
        let js = env.new_string(state)?;
        let jd = env.new_string(detail)?;
        env.call_method(
            obj,
            "onState",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[(&js).into(), (&jd).into()],
        )
        .map(|_| ())
    });
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn emit_stats(
    fps: i32,
    frames: i64,
    kbps: i64,
    audio_playing: bool,
    ducking: bool,
) {
    call_listener("onStats", |env, obj| {
        env.call_method(
            obj,
            "onStats",
            // Kotlin: onStats(fps:Int, frames:Long, kbps:Long, audioPlaying:Boolean, ducking:Boolean)
            // → I J J Z Z（5 个参数）。**必须严格 5 位**：旧值多写了一个 I（"(IJJIZZ)V"）
            // 导致 GetMethodID 直接失败、onStats 一次都没调到——首页帧率/码率/音频芯片全空
            // （2026-10-25 定谳）。
            "(IJJZZ)V",
            &[
                fps.into(),
                frames.into(),
                kbps.into(),
                jni::sys::jboolean::from(audio_playing).into(),
                jni::sys::jboolean::from(ducking).into(),
            ],
        )
        .map(|_| ())
    });
}

fn with_env<T>(
    f: impl FnOnce(&mut JNIEnv) -> jni::errors::Result<T>,
) -> Option<T> {
    let vm_guard = JAVA_VM.read();
    let vm = vm_guard.as_ref()?;
    // 已 attach 的线程（JNI 调用栈内 / 已挂载 worker）→ 直接用；
    // tokio worker 线程 → daemon 挂载（进程退出不阻塞）。
    // jni 0.21：attach_current_thread_as_daemon 返回 JNIEnv（非 AttachGuard），无需 unsafe。
    let (mut env, owned) = match vm.get_env() {
        Ok(env) => (env, false),
        Err(_) => (vm.attach_current_thread_as_daemon().ok()?, true),
    };
    let result = f(&mut env);
    // **必须清理 Pending Java Exception**：JNI 调用失败（如方法签名不对 / 业务异常）后，
    // 异常会挂在这个线程上，下一次该线程的任何 JNI 调用（emit_log / openTcpFd …）都会
    // 立即失败甚至崩——必须 exception_clear 掉（2026-10-25 加固）。
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
    // 本地引用只在本次 f 内使用；线程 attach 生命周期由 daemon 管理，无需 detach。
    let _ = owned;
    result.ok()
}

fn call_listener(method: &str, f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<()>) {
    let guard = LISTENER.read();
    let Some(global) = guard.as_ref() else { return };
    // JObject 不实现 Clone；as_obj() 返回 &JObject，直接按引用传给 f。
    let obj = global.as_obj();
    let _ = with_env(|env| f(env, obj));
    let _ = method;
}

// ---------------------------------------------------------------- socket 供给器

/// Kotlin SocketProvider（carNetwork 绑定的 socket 工厂）→ Rust trait 实现。
struct JniSocketProvider {
    vm: jni::JavaVM,
    obj: GlobalRef,
}

impl JniSocketProvider {
    fn call_fd(&self, method: &str, host: &str, port: u16, timeout_ms: u32) -> Option<RawFd> {
        let mut env = self.vm.attach_current_thread_as_daemon().ok()?;
        let jhost = env.new_string(host).ok()?;
        let fd: jint = env
            .call_method(
                self.obj.as_obj(),
                method,
                "(Ljava/lang/String;II)I",
                &[
                    (&jhost).into(),
                    (port as jint).into(),
                    (timeout_ms as jint).into(),
                ],
            )
            .ok()?
            .i()
            .ok()?;
        if fd < 0 {
            None
        } else {
            Some(fd as RawFd)
        }
    }
}

impl SocketProvider for JniSocketProvider {
    fn open_tcp(&self, host: &str, port: u16, timeout_ms: u32) -> Option<TcpStream> {
        let fd = self.call_fd("openTcpFd", host, port, timeout_ms)?;
        unsafe {
            let std_stream = std::net::TcpStream::from_raw_fd(fd);
            let _ = std_stream.set_nonblocking(true);
            let _ = std_stream.set_nodelay(true);
            TcpStream::from_std(std_stream).ok()
        }
    }

    fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket> {
        // 注意 Kotlin 侧 openUdpFd(host, port) 只有两个参数，签名为 (Ljava/lang/String;I)I
        let mut env = self.vm.attach_current_thread_as_daemon().ok()?;
        let jhost = env.new_string(host).ok()?;
        let fd: jint = env
            .call_method(
                self.obj.as_obj(),
                "openUdpFd",
                "(Ljava/lang/String;I)I",
                &[(&jhost).into(), (port as jint).into()],
            )
            .ok()?
            .i()
            .ok()?;
        if fd < 0 {
            return None;
        }
        unsafe {
            let std_udp = std::net::UdpSocket::from_raw_fd(fd as RawFd);
            let _ = std_udp.set_nonblocking(true);
            UdpSocket::from_std(std_udp).ok()
        }
    }
}

// ---------------------------------------------------------------- JNI 导出

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeInitLogger(
    env: JNIEnv,
    _class: JClass,
) {
    *JAVA_VM.write() = Some(env.get_java_vm().expect("JavaVM 获取失败"));
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("HyperCar-Core"),
    );
    // 崩溃取证：默认 panic hook 写 stderr，Android 应用进程的 stderr 基本看不到。
    // 换成打到 logcat（tag HyperCar-Core）后，App 侧用 Shizuku 跑 `logcat -s HyperCar-Core`
    // 就能把「哪一行 panic 的」抓进导出日志（现场 2026-10-23 20:30 崩得无声无息）。
    // 这里只用 `log`（liblog），不碰 JNI——panic 钩子里调 JNI 有死锁风险。
    std::panic::set_hook(Box::new(|info| {
        log::error!("PANIC: {info}");
    }));
    log::info!("HyperCar Rust 核心层已初始化（协商下沉版）");
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSetListener(
    env: JNIEnv,
    _class: JClass,
    listener: JObject,
) {
    let mut guard = LISTENER.write();
    *guard = if listener.is_null() {
        None
    } else {
        Some(env.new_global_ref(listener).expect("GlobalRef 创建失败"))
    };
}

/// 启动全链路网关：controlFd（已连车机 AirPlay 端口）+ socket 供给器 + 引擎配置 JSON。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeStart(
    env: JNIEnv,
    _class: JClass,
    config_json: JString,
    control_fd: jint,
    socket_provider: JObject,
) -> jboolean {
    // 任何 panic 都不得越过 JNI 边界：一旦越过（哪怕 panic=unwind）Rust 会 abort 整个进程，
    // 现场只剩「进程消失 + 内存日志全丢」。这里兜住，翻译成 JNI_FALSE + 一条日志。
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        native_start_impl(env, config_json, control_fd, socket_provider)
    }));
    match outcome {
        Ok(value) => value,
        Err(payload) => {
            let detail = payload
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "未知 panic".to_string());
            emit_log(&format!("nativeStart 内部 panic（已兜住，进程未退出）：{detail}"));
            emit_state("failed", "核心层启动时 panic（详见日志）");
            JNI_FALSE
        }
    }
}

fn native_start_impl(
    mut env: JNIEnv,
    config_json: JString,
    control_fd: jint,
    socket_provider: JObject,
) -> jboolean {
    // 停旧引擎
    stop_engine();

    let config_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let cfg: engine::EngineConfig = match serde_json::from_str(&config_str) {
        Ok(c) => c,
        Err(e) => {
            emit_log(&format!("引擎配置解析失败: {e}"));
            return JNI_FALSE;
        }
    };
    if control_fd < 0 {
        emit_log("controlFd 非法");
        return JNI_FALSE;
    }
    emit_log("nativeStart: 配置已解析，开始接管控制 FD");

    // 控制 FD → 非阻塞 tokio TcpStream（所有权移交 Rust）
    //
    // **必须在 tokio runtime 上下文里做 `from_std`**：tokio 的文档明确写着
    // 「This function panics if thread-local runtime is not set」——而本函数是被 Kotlin
    // 的 IO 线程（Dispatchers.IO）直接调进来的，不是 tokio worker，没有线程局部 runtime。
    // 2026-10-23 20:44 的秒崩就死在这一行：日志停在「CORE: 调用 nativeStart」，之后
    // 一条 CORE 都没有（'there is no reactor running' panic → abort）。这里显式 enter()。
    let control_stream = {
        let std_stream = unsafe {
            let std_stream = std::net::TcpStream::from_raw_fd(control_fd as RawFd);
            let _ = std_stream.set_nonblocking(true);
            let _ = std_stream.set_nodelay(true);
            std_stream
        };
        let _enter = TOKIO_RT.enter();
        match TcpStream::from_std(std_stream) {
            Ok(s) => s,
            Err(e) => {
                emit_log(&format!("控制 FD 转换失败: {e}"));
                return JNI_FALSE;
            }
        }
    };
    emit_log("nativeStart: 控制 FD 已注册到 tokio");

    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return JNI_FALSE,
    };
    let factory_obj = match env.new_global_ref(socket_provider) {
        Ok(g) => g,
        Err(_) => return JNI_FALSE,
    };
    let factory: Arc<dyn SocketProvider> = Arc::new(JniSocketProvider {
        vm,
        obj: factory_obj,
    });

    let (touch_tx, _touch_rx) = mpsc::unbounded_channel::<TouchInput>();
    let (key_tx, _key_rx) = mpsc::unbounded_channel::<u32>();
    // 自绘界面 → 引擎：视频帧 / 音频 PCM 都走这条通道
    let (source_tx, source_rx) = mpsc::unbounded_channel::<engine::SourceEvent>();
    let stop = Arc::new(Notify::new());

    let stop_run = stop.clone();
    let touch_tx_engine = touch_tx.clone();
    let key_tx_engine = key_tx.clone();
    let engine_task = TOKIO_RT.spawn(async move {
        let result = engine::run_engine(
            cfg,
            control_stream,
            factory,
            touch_tx_engine,
            key_tx_engine,
            source_rx,
            stop_run,
        )
        .await;
        if let Err(e) = &result {
            emit_log(&format!("引擎退出: {e}"));
            emit_state("failed", e);
        }
    });
    // panic 隔离看门狗：Cargo 已去掉 panic = "abort"，任务内 panic 由 tokio 捕获在
    // JoinHandle 里。这里把它报成「会话失败」并留日志，而不是让进程无声消失。
    TOKIO_RT.spawn(async move {
        if let Err(join_error) = engine_task.await {
            emit_log(&format!("引擎任务异常退出: {join_error}"));
            emit_state("failed", "核心层内部异常（panic），请导出日志");
        }
    });

    // 发送端留在句柄里（触摸/按键通道 P1 起接给自绘 UI）
    // 发送端留在句柄里，供 nativePushVideoFrame / nativePushAudioPcm 投喂数据
    *ENGINE.lock() = Some(EngineHandle {
        stop,
        touch_tx,
        key_tx,
        source_tx,
    });

    emit_log("nativeStart: 引擎任务已派生");
    emit_state("starting", "核心层引擎已启动");
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_engine();
}

fn stop_engine() {
    if let Some(handle) = ENGINE.lock().take() {
        // notify_waiters 唤醒当前所有等待者；notify_one 存一个 permit，
        // 防止「停止信号早于最终 await 注册」时引擎挂死（Notify 不记忆 waiters）。
        handle.stop.notify_waiters();
        handle.stop.notify_one();
        emit_log("引擎已停止");
    }
}

/// 投喂一帧 H.264（Annex B，来自本机 MediaCodec 的编码输出）。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativePushVideoFrame(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    pts_us: jlong,
) {
    let Ok(len) = env.get_array_length(&data) else {
        return;
    };
    if len <= 0 {
        return;
    }
    let mut buf = vec![0i8; len as usize];
    if env.get_byte_array_region(&data, 0, &mut buf).is_err() {
        return;
    }
    let bytes: Vec<u8> = buf.into_iter().map(|b| b as u8).collect();
    if let Some(handle) = ENGINE.lock().as_ref() {
        let _ = handle.source_tx.send(engine::SourceEvent::VideoFrame {
            pts_us: pts_us.max(0) as u64,
            data: bytes,
        });
    }
}

/// 投喂编码画面的新尺寸（对齐 AirPlay VideoConfig）。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeSetVideoSize(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
) {
    if let Some(handle) = ENGINE.lock().as_ref() {
        let _ = handle.source_tx.send(engine::SourceEvent::EncoderResolution {
            width: width.max(2) as u32,
            height: height.max(2) as u32,
        });
    }
}

/// 投喂一包 PCM（16-bit 小端交错；用于把手机音频送进 AirPlay 音频流）。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativePushAudioPcm(
    mut env: JNIEnv,
    _class: JClass,
    pcm: JShortArray,
    rate: jint,
    channels: jint,
) {
    let Ok(len) = env.get_array_length(&pcm) else {
        return;
    };
    if len <= 0 {
        return;
    }
    let mut buf = vec![0i16; len as usize];
    if env.get_short_array_region(&pcm, 0, &mut buf).is_err() {
        return;
    }
    let bytes: Vec<u8> = buf.iter().flat_map(|s| s.to_le_bytes()).collect();
    if let Some(handle) = ENGINE.lock().as_ref() {
        let _ = handle.source_tx.send(engine::SourceEvent::AudioData {
            tts: false,
            rate: rate.max(8000) as u32,
            channels: channels.clamp(1, 2) as u8,
            data: bytes,
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if ENGINE.lock().is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// 构造宝马 HUD 导航报文（iAP2 0x5200）；返回 jbyteArray（Rust 分配、JNI 拷贝）。
#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeBuildIap2NavPacket<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    maneuver_type: jint,
    current_street: JString<'local>,
    next_street: JString<'local>,
    dist_to_maneuver_m: jint,
    remain_time_s: jint,
    remain_dist_m: jint,
) -> jni::sys::jbyteArray {
    let cur: String = env
        .get_string(&current_street)
        .map(|s| s.into())
        .unwrap_or_default();
    let nxt: String = env
        .get_string(&next_street)
        .map(|s| s.into())
        .unwrap_or_default();
    let packet = iap2_nav::build_route_guidance_packet(
        maneuver_type as u8,
        &cur,
        &nxt,
        dist_to_maneuver_m.max(0) as u32,
        remain_time_s.max(0) as u32,
        remain_dist_m.max(0) as u32,
    );
    env.byte_array_from_slice(&packet)
        .expect("jbyteArray 创建失败")
        .into_raw()
}




