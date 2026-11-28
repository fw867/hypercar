//! Local media consumer for the Yadea instrument, independent of the AirPlay engine.
use crate::{
    airplay::TouchInput, carlife::{ReceiverDisplay, SystemCanvasHint}, carwith_source,
    engine::{CarLifeEvent, EngineConfig}, video_format::VideoCodec,
    with_env, TOKIO_RT,
};
use jni::{
    objects::{GlobalRef, JClass, JObject, JString, JValue},
    sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use std::sync::Arc;

fn callback(
    listener: &GlobalRef,
    method: &str,
    signature: &str,
    args: &[JValue<'_, '_>],
) -> Option<()> {
    with_env(|env| env.call_method(listener.as_obj(), method, signature, args).map(|_| ()))
}

fn video(listener: &GlobalRef, frame: &[u8], pts_us: u64, codec: VideoCodec) {
    let codec: jint = if codec == VideoCodec::Hevc { 1 } else { 0 };
    with_env(|env| env.with_local_frame(4, |env| {
        let bytes = JObject::from(env.byte_array_from_slice(frame)?);
        env.call_method(listener.as_obj(), "onVideo", "([BJI)V",
            &[(&bytes).into(), (pts_us as jlong).into(), codec.into()]).map(|_| ())
    }));
}

async fn receive(
    mut events: tokio::sync::mpsc::UnboundedReceiver<(CarLifeEvent, crate::video_format::FrameOrigin)>,
    listener: GlobalRef,
) {
    while let Some((event, _)) = events.recv().await {
        match event {
            CarLifeEvent::VideoFrame { pts_us, data } => video(&listener, &data, pts_us, VideoCodec::H264),
            CarLifeEvent::VideoFrameTagged { pts_us, data, codec } => video(&listener, &data, pts_us, codec),
            CarLifeEvent::EncoderResolution { width, height } => {
                callback(&listener, "onSourceSize", "(II)V", &[(width as jint).into(), (height as jint).into()]);
            }
            CarLifeEvent::SourceReset { .. } => { callback(&listener, "onSourceReset", "()V", &[]); }
            CarLifeEvent::ClientLeft { video: true } => { callback(&listener, "onVideoDisconnected", "()V", &[]); }
            CarLifeEvent::AudioInit { tts, rate, channels } => {
                callback(&listener, "onAudioFormat", "(ZII)V", &[jboolean::from(tts).into(), (rate as jint).into(), jint::from(channels).into()]);
            }
            CarLifeEvent::AudioStop { tts } => {
                callback(&listener, "onAudioStop", "(Z)V", &[jboolean::from(tts).into()]);
            }
            CarLifeEvent::AudioData { tts, rate, channels, data, received_at } if received_at.elapsed().as_millis() < 500 => {
                with_env(|env| env.with_local_frame(4, |env| {
                    let bytes = JObject::from(env.byte_array_from_slice(&data)?);
                    env.call_method(listener.as_obj(), "onAudio", "(ZII[B)V",
                        &[jboolean::from(tts).into(), (rate as jint).into(), jint::from(channels).into(), (&bytes).into()]).map(|_| ())
                }));
            }
            _ => {}
        }
    }
    callback(&listener, "onSourceClosed", "()V", &[]);
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeStartYadeaSource(
    mut env: JNIEnv, _class: JClass, json: JString, owner: jlong,
    width: jint, height: jint, fps: jint, confirmed: jboolean, listener: JObject,
) -> jboolean {
    if owner <= 0 || !(2..=2560).contains(&width) || !(2..=2560).contains(&height) || !(1..=120).contains(&fps) {
        return JNI_FALSE;
    }
    let result = (|| -> Result<(), String> {
        let json: String = env.get_string(&json).map_err(|error| error.to_string())?.into();
        let config: EngineConfig = serde_json::from_str(&json).map_err(|error| error.to_string())?;
        let listener = env.new_global_ref(listener).map_err(|error| error.to_string())?;
        let reopen_listener = listener.clone();
        let width_height = (width as u32, height as u32);
        let owner = owner as u64;
        let system_canvas = config.system_carlife_source.then(|| SystemCanvasHint {
            size: width_height,
            is_current: Arc::new(move || carwith_source::diagnostic_phase(owner).is_some()),
        });
        let display = ReceiverDisplay { size: width_height, max_fps: Some(fps as u32), confirmed: confirmed != JNI_FALSE };
        let _entered = TOKIO_RT.enter();
        carwith_source::prepare_for(owner, &config, system_canvas, Some(display), move |startup| {
            callback(&reopen_listener, "onReopen", "(Z)V", &[jboolean::from(startup).into()]);
        })?;
        let source = carwith_source::attach(owner)?;
        tokio::spawn(receive(source.events, listener));
        Ok(())
    })();
    match result {
        Ok(()) => JNI_TRUE,
        Err(error) => {
            crate::emit_log(&format!("YADEA-SOURCE: {error}"));
            carwith_source::stop(owner as u64);
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeYadeaTouch(
    _env: JNIEnv, _class: JClass, owner: jlong, action: jint, x: jint, y: jint,
) -> jboolean {
    if owner <= 0 || !(0..=3).contains(&action) || x < 0 || y < 0 { return JNI_FALSE; }
    carwith_source::with_hub(owner as u64, |hub| {
        hub.inject_touch_report(&TouchInput {
            action: action as u32, x: x as u32, y: y as u32, pointers: Vec::new(), grant: None,
        });
    }).into()
}

#[no_mangle]
pub extern "system" fn Java_io_github_hypercar_core_RustCoreBridge_nativeYadeaRequestKeyFrame(
    _env: JNIEnv, _class: JClass, owner: jlong,
) {
    if owner > 0 { carwith_source::request_fresh_video(owner as u64); }
}
