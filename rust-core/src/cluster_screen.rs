//! Receiver-requested instrument map. The source, socket, cipher and cancellation belong to type 111.
use crate::{
    airplay::{self, uuid_text},
    bplist::{self, Value},
    screen::{ScreenCipher, ScreenSender},
    screen_session::{setup_stream, ScreenSession},
};
use jni::objects::{GlobalRef, JByteArray};
use std::{sync::{Arc, atomic::{AtomicBool, Ordering}}, time::Duration};
use tokio::sync::watch;

pub(crate) const MAP_URL: &str = "maps:/car/instrumentcluster/map";

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Display {
    uuid: String,
    width: i32,
    height: i32,
    safe: [i32; 4],
    encoder_fps: i32,
    initial_map: bool,
}

fn kind(d: &Value) -> Option<i64> { d.get("type")?.as_int() }
fn same_uuid(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b) || uuid::Uuid::parse_str(a).ok()
        .zip(uuid::Uuid::parse_str(b).ok()).is_some_and(|(a, b)| a == b)
}

impl Display {
    pub(crate) fn declared(info: &Value) -> Option<Self> {
        let displays = info.get("displays")?.as_array()?;
        let mut auxiliary = displays.iter().filter(|d| kind(d) == Some(111));
        let d = auxiliary.next()?;
        let uuid = uuid_text(d.get("uuid"))?;
        if auxiliary.next().is_some() || uuid::Uuid::parse_str(&uuid).ok()?.is_nil()
            || !displays.iter().any(|d| matches!(kind(d), Some(110 | 0)) && uuid_text(d.get("uuid")).is_some())
            || displays.iter().filter(|d| uuid_text(d.get("uuid")).is_some_and(|s| same_uuid(&s, &uuid))).count() != 1 {
            return None;
        }
        let (w, h) = (d.get("widthPixels")?.as_int()?, d.get("heightPixels")?.as_int()?);
        if !(64..=16384).contains(&w) || !(48..=16384).contains(&h) { return None; }
        // The map has a single stable viewport; dynamically retargeted instrument panels need a separate contract.
        if d.get("viewAreas").is_some_and(|areas| areas.as_array().is_none_or(|a| a.len() > 1))
            || d.get("initialViewArea").is_some_and(|index| index.as_int() != Some(0)) { return None; }
        let rect = |r: &Value| -> Option<[i64; 4]> {
            let coordinate = |field| match r.get(field) { None => Some(0), Some(v) => v.as_int() };
            let box_ = [coordinate("originXPixels")?, coordinate("originYPixels")?,
                r.get("widthPixels")?.as_int()?, r.get("heightPixels")?.as_int()?];
            let [x,y,rw,rh] = box_;
            (x >= 0 && y >= 0 && rw > 0 && rh > 0 && rw <= w && rh <= h && x <= w-rw && y <= h-rh).then_some(box_)
        };
        let mut effective = [0,0,w,h];
        if let Some(view) = d.get("viewAreas").and_then(Value::as_array).and_then(|areas| areas.first()) {
            if view.get("displayUUID").is_some() && !uuid_text(view.get("displayUUID")).is_some_and(|s| same_uuid(&s,&uuid)) { return None; }
            effective = rect(view)?;
            if let Some(raw) = view.get("safeArea") {
                let safe = rect(raw)?;
                if safe[0] < effective[0] || safe[1] < effective[1] || safe[0]+safe[2] > effective[0]+effective[2]
                    || safe[1]+safe[3] > effective[1]+effective[3] { return None; }
                effective = safe;
            }
        }
        // Scale both axes proportionally, aligning the small independent AVC canvas for hardware encoders.
        let scale = (960.0 / w as f64).min(540.0 / h as f64).min(1.0);
        let width = ((w as f64 * scale) as i32 / 16) * 16;
        let height = ((h as f64 * scale) as i32 / 16) * 16;
        let [x,y,rw,rh] = effective;
        let left = ((x * width as i64 + w - 1)/w) as i32;
        let top = ((y * height as i64 + h - 1)/h) as i32;
        let safe = [left,top,((x+rw)*width as i64/w) as i32-left,((y+rh)*height as i64/h) as i32-top];
        let mut urls = d.as_dict()?.iter().filter(|(key, _)| key.as_str() == Some("initialURL"));
        let initial_map = urls.next().and_then(|(_, value)| value.as_str()) == Some(MAP_URL)
            && urls.next().is_none();
        (safe[2]>=64 && safe[3]>=48).then_some(Self {
            uuid, width, height, safe, encoder_fps: airplay::display_max_fps(d).unwrap_or(2).min(2) as i32,
            initial_map,
        })
    }

    pub(crate) fn source_available(&self) -> bool {
        let Some(bridge) = crate::TRANSCODER_BRIDGE.read().clone() else { return false; };
        crate::with_env(|env| env.call_method(bridge.as_obj(),"clusterMapAvailable","(II)Z",
            &[self.width.into(), self.height.into()])?.z()).unwrap_or(false)
    }
}

/// The initial SETUP advertises what the phone can produce. Only the receiver's
/// authenticated, post-SETUP declaration can identify a stream to open.
pub(crate) enum Offer {
    AwaitingDisplays,
    Declared(Display),
}

impl Offer {
    pub(crate) fn prepare(info: &Value, encrypted: bool, minimal_profile: bool) -> Option<Self> {
        if !encrypted || minimal_profile { return None; }
        if let Some(display) = Display::declared(info) {
            return display.source_available().then_some(Self::Declared(display));
        }
        // Some receivers return 455 or omit the complete display list at this stage.
        // A malformed or ambiguous explicit auxiliary declaration is never an invitation.
        if let Some(value) = info.get("displays") {
            if value.as_array()?.iter().any(|d| kind(d) == Some(111)) { return None; }
        }
        let bridge = crate::TRANSCODER_BRIDGE.read().clone()?;
        let capable = crate::with_env(|env| env.call_method(bridge.as_obj(), "clusterMapCapable", "()Z", &[])?.z());
        (capable == Some(true)).then_some(Self::AwaitingDisplays)
    }

    pub(crate) fn resolve(&self, info: &Value, reply: &Value) -> Option<Display> {
        let enabled = reply.get("enabledFeatures")?.as_array()?.iter().any(|f| f.as_str() == Some("altScreen"));
        if !enabled { return None; }
        let display = Display::declared(info)?;
        if let Self::Declared(early) = self {
            if !same_uuid(&early.uuid, &display.uuid) || (early.width, early.height, early.safe)
                != (display.width, display.height, display.safe) { return None; }
        }
        Some(display)
    }
}

struct Encoder(GlobalRef);
enum Sample { First, Next(bool), Clear }
trait MapSource: Send + Sync + 'static {
    fn sample(&self, request: Sample) -> Option<Vec<u8>>;
}
impl Drop for Encoder {
    fn drop(&mut self) { crate::with_env(|env| env.call_method(self.0.as_obj(),"close","()V",&[]).map(|_|())); }
}
impl Encoder {
    fn open(d: &Display) -> Option<Self> {
        let bridge = crate::TRANSCODER_BRIDGE.read().clone()?;
        let [x,y,w,h] = d.safe;
        crate::with_env(|env| env.with_local_frame(4,|env| {
            let object = env.call_method(bridge.as_obj(),"openClusterMap","(IIIIIII)Lio/github/hypercar/core/ClusterMapEncoder;",
                &[d.width.into(),d.height.into(),x.into(),y.into(),w.into(),h.into(),d.encoder_fps.into()])?.l()?;
            if object.is_null() { return Ok(None); }
            Ok(Some(Self(env.new_global_ref(object)?)))
        })).flatten()
    }
}
impl MapSource for Encoder {
    fn sample(&self, request: Sample) -> Option<Vec<u8>> {
        crate::with_env(|env| env.with_local_frame(4,|env| {
            let bytes = match request {
                Sample::First => env.call_method(self.0.as_obj(),"firstSample","()[B",&[])?,
                Sample::Next(sync) => env.call_method(self.0.as_obj(),"nextSample","(Z)[B",&[sync.into()])?,
                Sample::Clear => env.call_method(self.0.as_obj(),"clearSample","()[B",&[])?,
            }.l()?;
            if bytes.is_null() { return Ok(None); }
            let array = JByteArray::from(bytes);
            if !(1..=4*1024*1024).contains(&env.get_array_length(&array)?) { return Ok(None); }
            Ok(Some(env.convert_byte_array(array)?))
        })).flatten()
    }
}

#[derive(Default, Clone)]
struct Request { grant: Option<Arc<AtomicBool>>, keyframe: u64 }

#[derive(Default)]
pub(crate) struct Commands { ids: Vec<String>, active: Option<(String, watch::Sender<Request>)> }
impl Drop for Commands {
    fn drop(&mut self) {
        if let Some((_,tx)) = &self.active {
            if let Some(grant) = &tx.borrow().grant { grant.store(false,Ordering::Release); }
        }
    }
}
impl Commands {
    pub(crate) fn new(info: &Value, reply: &Value, session: Arc<ScreenSession>, tasks: &mut Vec<tokio::task::JoinHandle<()>>) -> Self {
        Self::with_source(info, reply, session, tasks, Display::source_available, Encoder::open)
    }

    fn with_source<S: MapSource>(info: &Value, reply: &Value, session: Arc<ScreenSession>,
        tasks: &mut Vec<tokio::task::JoinHandle<()>>, available: fn(&Display) -> bool,
        open_source: fn(&Display) -> Option<S>,
    ) -> Self {
        let ids: Vec<_> = info.get("displays").and_then(Value::as_array).into_iter().flatten()
            .filter(|d| kind(d)==Some(111)).filter_map(|d| uuid_text(d.get("uuid")))
            .filter(|id| !same_uuid(id, &session.uuid)).collect();
        let enabled = reply.get("enabledFeatures").and_then(Value::as_array)
            .is_some_and(|f| f.iter().any(|v| v.as_str()==Some("altScreen")));
        let display = session.control.cluster_display.get().filter(|d|
            enabled && ids.iter().any(|id| same_uuid(id, &d.uuid)) && available(d)
        ).cloned();
        crate::emit_log(&format!("CLUSTER: /info type111_ids={} peer_enabled={} request_listener={} initial_map={} source=AMap_MAP_PREVIEW",
            ids.len(),enabled,display.is_some(),display.as_ref().is_some_and(|d| d.initial_map)));
        let active = display.map(|d| {
            // Seed once, before control events are dispatched. A later stop or
            // content selection revokes the same grant as an explicit showUI.
            let (tx,rx) = watch::channel(Request {
                grant: d.initial_map.then(|| Arc::new(AtomicBool::new(true))),
                ..Default::default()
            });
            let id = d.uuid.clone();
            tasks.push(crate::callback_context::spawn(run(d,session,rx,open_source)));
            (id,tx)
        });
        Self {ids,active}
    }
    pub(crate) fn handle(&self, command: &str, body: &Value) -> bool {
        if !matches!(command,"showUI"|"stopUI"|"forceKeyFrame") { return false; }
        let params = body.get("params").unwrap_or(body);
        let Ok(Some(id)) = crate::receiver_ui::display_target(body) else { return false; };
        if !self.ids.iter().any(|s| same_uuid(s,&id)) { return false; }
        if let Some((_,tx)) = self.active.as_ref().filter(|(s,_)| same_uuid(s,&id)) {
            let map_requested = params.get("url").and_then(Value::as_str)==Some(MAP_URL);
            tx.send_if_modified(|request| match command {
                "showUI" if map_requested && request.grant.as_ref().is_none_or(|g| !g.load(Ordering::Acquire)) => {
                    crate::emit_log("CLUSTER: showUI map requested; waiting for main video, active maneuver and native map card");
                    request.grant = Some(Arc::new(AtomicBool::new(true))); true
                }
                "showUI" if map_requested => false,
                "stopUI" | "showUI" => {
                    if let Some(grant)=request.grant.take() {
                        grant.store(false,Ordering::Release);
                        crate::emit_log("CLUSTER: map request withdrawn; retiring auxiliary stream");
                        true
                    } else { false }
                }
                "forceKeyFrame" if request.grant.is_some() => { request.keyframe = request.keyframe.wrapping_add(1); true }
                _ => false,
            });
        }
        true
    }
}

fn stream_body(id: &str, connection: Option<i64>) -> Vec<u8> {
    let mut fields = vec![("type",Value::int(111)),("uuid",Value::str(id))];
    if let Some(conn) = connection { fields.push(("streamConnectionID",Value::int(conn))); }
    bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]))
}

fn first_idr(data: &[u8], d: &Display) -> bool {
    let nals = crate::screen::annexb_nals(data);
    let Some(sps) = nals.iter().find(|n| crate::screen::nal_type(n)==7) else { return false; };
    crate::h264::sps_dimensions(sps)==Some((d.width as u32,d.height as u32))
        && nals.iter().any(|n| crate::screen::nal_type(n)==8 && n.len()>1)
        && nals.iter().any(|n| crate::screen::nal_type(n)==5 && n.len()>1)
}

fn response_port(body: &Value, display: &Display, conn: i64) -> Result<u16,String> {
    let reply = setup_stream::select(body,111,false)?;
    if reply.get("streamConnectionID").is_some_and(|id| id.as_int()!=Some(conn))
        || reply.get("uuid").is_some() && !uuid_text(reply.get("uuid")).is_some_and(|u|same_uuid(&u,&display.uuid)) {
        return Err("mismatched type111 stream identity".into());
    }
    reply.get("dataPort").and_then(Value::as_int).and_then(|p|u16::try_from(p).ok()).filter(|p|*p>0)
        .ok_or_else(||"invalid type111 dataPort".into())
}

async fn run<S: MapSource>(display: Display, session: Arc<ScreenSession>, mut rx: watch::Receiver<Request>,
                           open_source: fn(&Display) -> Option<S>) {
    loop {
        let desired = rx.borrow_and_update().clone();
        if let Some(grant) = desired.grant {
            if let Some(main_connection) = active_main_connection(&session, &grant) {
                let d = display.clone();
                let source = tokio::task::spawn_blocking(move || {
                    let encoder = Arc::new(open_source(&d)?);
                    let frame = encoder.sample(Sample::First)?;
                    first_idr(&frame,&d).then_some(encoder)
                }).await.ok().flatten();
                if let Some(encoder) = source {
                    if let Err(e) = stream(&display,&session,&rx,&grant,main_connection,encoder).await {
                        grant.store(false,Ordering::Release); crate::emit_log(&format!("CLUSTER: type111 stopped: {e}"));
                    }
                }
            }
        }
        tokio::select! {
            changed = rx.changed() => if changed.is_err() { return; },
            _ = tokio::time::sleep(Duration::from_secs(1)), if rx.borrow().grant.as_ref().is_some_and(|g| g.load(Ordering::Acquire)) => (),
        }
    }
}

pub(crate) fn active_main_connection(session: &ScreenSession, grant: &AtomicBool) -> Option<u64> {
    if !grant.load(Ordering::Acquire) || !session.owned.load(Ordering::Acquire) || !session.session_is_live() {
        return None;
    }
    // Release the pipeline lock before inspecting the same modes lock used by the pump's install path.
    let main = session.params.pipeline.lock().main_video_connection.clone()?;
    if let Some((modes, revision)) = main.resource_grant {
        if !modes.lock().can_restore_screen(revision) { return None; }
    }
    Some(main.id)
}

async fn stream(display: &Display, session: &ScreenSession, rx: &watch::Receiver<Request>, grant: &Arc<AtomicBool>,
                main_connection: u64, encoder: Arc<impl MapSource>) -> Result<(),String> {
    let authorized = || active_main_connection(session, grant) == Some(main_connection);
    if !authorized() { return Ok(()); }
    let shared = session.shared.as_ref().ok_or("pair-verify unavailable")?;
    let conn = airplay::random_positive_i64();
    crate::emit_log(&format!("CLUSTER: active map+encoded IDR ready, SETUP type111 source={}x{}",display.width,display.height));
    let response = session.control.screen_setup(&stream_body(&display.uuid,Some(conn))).await?;
    if response.status()!=200 { return Err(format!("SETUP status {}",response.status())); }
    let result = async {
        let body = bplist::parse(&response.body).map_err(|e|e.to_string())?;
        crate::resource_trace::validate_ack("CLUSTER_SETUP",&response)?;
        let port = response_port(&body, display, conn)?;
        if !authorized() { return Ok(()); }
        let factory=session.factory.clone(); let host=session.params.car_ip.clone();
        let socket=tokio::task::spawn_blocking(move ||factory.open_tcp(&host,port,3000)).await.map_err(|e|e.to_string())?.ok_or("connect type111 TCP failed")?;
        let (_,write) = socket.into_split();
        let mut sender = ScreenSender::new(write,ScreenCipher::ChaCha(airplay::stream_key(shared,conn as u64)),display.width as u32,display.height as u32,false);
        sender.mark_instrument_map();
        sender.set_ownership(grant.clone());
        // SETUP and connecting the socket may have taken seconds. Re-sample the live map before the first write.
        tokio::time::sleep(Duration::from_millis(1000 / display.encoder_fps as u64 + 10)).await;
        if !authorized() { return Ok(()); }
        let source = encoder.clone();
        let fresh = tokio::task::spawn_blocking(move ||source.sample(Sample::Next(true))).await.map_err(|e|e.to_string())?;
        let Some(mut sample) = fresh else { return Ok(()); };
        if !first_idr(&sample, display) { return Err("encoder failed to refresh the navigation IDR".into()); }
        let mut keyframe = rx.borrow().keyframe;
        let mut interval = tokio::time::interval(Duration::from_secs(1));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        interval.tick().await;
        while authorized() {
            let pts = crate::timing::monotonic_nanos()/1000;
            tokio::time::timeout(Duration::from_millis(750),sender.send_video_annexb_from(&sample,pts,crate::video_format::FrameOrigin::Live))
                .await.map_err(|_|"type111 write timeout")?.map_err(|e|e.to_string())?;
            interval.tick().await;
            if !authorized() { break; }
            let next_key = rx.borrow().keyframe;
            let source = encoder.clone();
            let next = tokio::task::spawn_blocking(move || source.sample(Sample::Next(next_key!=keyframe))).await.map_err(|e|e.to_string())?;
            keyframe = next_key;
            match next { Some(bytes)=>sample=bytes, None=>{
                crate::emit_log("CLUSTER: map, maneuver or encoder unavailable; closing auxiliary output");
                if authorized() {
                    let source = encoder.clone();
                    let clear = tokio::task::spawn_blocking(move ||source.sample(Sample::Clear)).await.ok().flatten();
                    if let Some(blank) = clear.filter(|frame|authorized() && first_idr(frame,display)) {
                        let sent = sender.send_video_annexb_from(&blank,crate::timing::monotonic_nanos()/1000,crate::video_format::FrameOrigin::Live);
                        let _ = tokio::time::timeout(Duration::from_millis(500),sent).await;
                    }
                }
                break
            } }
        }
        Ok(())
    }.await;
    // Only the exact accepted secondary stream is released. Drop the video socket before RTSP.
    let teardown = session.control.request("TEARDOWN","/",&stream_body(&display.uuid,None),"application/x-apple-binary-plist",&[],Duration::from_secs(3)).await?;
    if teardown.status()!=200 { return Err(format!("TEARDOWN status {}",teardown.status())); }
    crate::emit_log("CLUSTER: type111 TCP closed and stream TEARDOWN accepted");
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    const MAIN: &str = "ab4f0144-bd7e-4bfe-8f81-76bc29117c57";
    const AUX: &str = "acabed80-3883-435c-9260-19128a4c1a79";

    fn declared(d: Vec<Value>) -> Option<Display> {
        Display::declared(&Value::dict(vec![("displays",Value::array(d))]))
    }
    fn display(kind: i64, id: &str) -> Value {
        Value::dict(vec![("type",Value::int(kind)),("uuid",Value::str(id)),
            ("widthPixels",Value::int(1280)),("heightPixels",Value::int(720))])
    }
    fn aux() -> Display { declared(vec![display(111,AUX),display(110,MAIN)]).unwrap() }
    #[test]
    fn final_auxiliary_rate_and_uuid_letter_case_do_not_invalidate_an_early_offer() {
        let reply = Value::dict(vec![("enabledFeatures", Value::array(vec![Value::str("altScreen")]))]);
        let mut late = display(111, &AUX.to_uppercase());
        if let Value::Dict(fields) = &mut late { fields.push((Value::str("maxFPS"), Value::int(1))); }
        let info = Value::dict(vec![("displays", Value::array(vec![display(110, MAIN), late]))]);
        assert_eq!(Offer::Declared(aux()).resolve(&info, &reply).unwrap().encoder_fps, 1);
        let other = Display { uuid: "ba0f617b-aa63-4f5a-97ee-fdb1d85a7170".into(), ..aux() };
        assert!(Offer::Declared(other).resolve(&info, &reply).is_none());
    }
    fn command(id: &str, url: &str) -> Value {
        Value::dict(vec![("params",Value::dict(vec![("uuid",Value::str(id)),("url",Value::str(url))]))])
    }
    #[tokio::test]
    async fn duplicate_auxiliary_uuid_leaves_main_keyframe_dispatch_available() {
        use crate::screen_resource::tests::Fixture;
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
        let info = Value::dict(vec![("displays", Value::array(vec![
            display(110, MAIN), display(111, &MAIN.to_uppercase()), display(111, AUX),
        ]))]);
        let commands = Commands::new(&info, &Value::Null, f.session.clone(), &mut Vec::new());
        let main = command(MAIN, "");
        assert!(!commands.handle("forceKeyFrame", &main));
        assert!(crate::receiver_ui::main_target(&main, Some(MAIN)));
        assert!(commands.handle("forceKeyFrame", &command(AUX, "")));
    }

    // A 64x48, single-frame x264 baseline encode, with the optional SEI removed.
    const SAMPLE: &[u8] = &[0,0,0,1,103,66,192,10,217,4,123,1,16,0,0,3,0,16,0,0,3,0,40,241,34,100,128,
        0,0,0,1,104,203,131,203,32,0,0,1,101,136,132,4,188,152,160,0,56,163,39,39,39,93,117,215,93,117,215,128];
    struct FixtureMap;
    impl MapSource for FixtureMap {
        fn sample(&self, _: Sample) -> Option<Vec<u8>> { Some(SAMPLE.to_vec()) }
    }

    fn startup_info(url: Option<&str>) -> Value {
        let mut instrument = vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)),
            ("widthPixels", Value::int(64)), ("heightPixels", Value::int(48)),
        ];
        if let Some(url) = url { instrument.push(("initialURL", Value::str(url))); }
        Value::dict(vec![("displays", Value::array(vec![display(110, MAIN), Value::dict(instrument)]))])
    }

    fn accepted_alt_screen() -> Value {
        Value::dict(vec![("enabledFeatures", Value::array(vec![Value::str("altScreen")]))])
    }

    #[tokio::test]
    async fn initial_map_starts_after_current_main_idr_without_show_ui() {
        exercise_initial_map(false).await;
    }

    #[tokio::test]
    async fn early_stop_ui_cancels_the_initial_map_until_an_explicit_show_ui() {
        exercise_initial_map(true).await;
    }

    async fn exercise_initial_map(stop_before_main: bool) {
        use crate::screen_resource::tests::{Fixture, read, reply};
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}, time::timeout};
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
        let info = startup_info(Some(MAP_URL));
        let session_reply = accepted_alt_screen();
        let selected = Offer::AwaitingDisplays.resolve(&info, &session_reply).unwrap();
        f.session.control.cluster_display.set(selected).unwrap();
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &session_reply, f.session.clone(), &mut workers,
            |_| true, |_| Some(FixtureMap));
        if stop_before_main {
            assert!(commands.handle("stopUI", &command(AUX, MAP_URL)));
        }
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (_peer, _) = listener.accept().await.unwrap();
        let mut main = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1;32]), 64, 48, false);
        main.attach_diagnostics(f.session.params.pipeline.clone(), 47);
        main.send_video_annexb(&[0,0,1,0x41,9], 1000).await.unwrap();
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        main.send_video_annexb(SAMPLE, 2000).await.unwrap();
        if stop_before_main {
            assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
            assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        }
        let setup = timeout(Duration::from_secs(2), read(&mut f.car)).await.unwrap();
        assert!(setup.first_line.starts_with("SETUP "));
        let body = bplist::parse(&setup.body).unwrap();
        let aux = setup_stream::select(&body, 111, false).unwrap();
        assert_eq!(aux.get("uuid").and_then(Value::as_str), Some(AUX));
        reply(&mut f.car, &setup, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn only_negotiated_available_auxiliary_map_can_seed_the_initial_request() {
        use crate::screen_resource::tests::Fixture;
        for (url, accepted, available, expected) in [
            (None, true, true, Some(false)),
            (Some("maps:/car/instrumentcluster/map?x=1"), true, true, Some(false)),
            (Some(MAP_URL), false, true, None),
            (Some(MAP_URL), true, false, None),
            (Some(MAP_URL), true, true, Some(true)),
        ] {
            let mut f = Fixture::new(crate::modes::Modes::initial()).await;
            Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
            let info = startup_info(url);
            let reply = if accepted { accepted_alt_screen() } else { Value::Null };
            f.session.control.cluster_display.set(Display::declared(&info).unwrap()).unwrap();
            let mut workers = Vec::new();
            let available: fn(&Display) -> bool = if available { |_| true } else { |_| false };
            let commands = Commands::with_source(&info, &reply, f.session.clone(), &mut workers,
                available, |_| Some(FixtureMap));
            assert_eq!(commands.active.as_ref().map(|(_, tx)| tx.borrow().grant.is_some()), expected);
            drop(commands);
            for worker in workers { worker.await.unwrap(); }
        }
    }

    #[test]
    fn ambiguous_initial_url_does_not_grant_the_map() {
        let mut instrument = display(111, AUX);
        let Value::Dict(fields) = &mut instrument else { unreachable!() };
        fields.push((Value::str("initialURL"), Value::str(MAP_URL)));
        fields.push((Value::str("initialURL"), Value::str("")));
        assert!(!declared(vec![display(110, MAIN), instrument]).unwrap().initial_map);
    }
    #[tokio::test]
    async fn requested_cluster_setup_waits_for_a_frame_on_the_current_main_connection() {
        use crate::screen_resource::tests::{Fixture, read, reply};
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}, time::timeout};
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        let display = Display { width:64, height:48, safe:[0,0,64,48], uuid:AUX.into(), encoder_fps:2, initial_map:false };
        let (tx,rx) = watch::channel(Request::default());
        let commands = Commands { ids:vec![AUX.into()], active:Some((AUX.into(),tx)) };
        let worker = tokio::spawn(run(display, f.session.clone(), rx, |_| Some(FixtureMap)));
        let request = command(AUX,MAP_URL);
        let pipeline = &f.session.params.pipeline;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let main_sender = async |id| {
            let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (peer,_) = listener.accept().await.unwrap();
            let mut sender = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1;32]), 64, 48, false);
            sender.attach_diagnostics(pipeline.clone(), id);
            (sender,peer)
        };
        let (mut main, _peer) = main_sender(47).await;
        commands.handle("showUI", &request);
        main.send_video_annexb(&[0,0,1,0x41,9],1000).await.unwrap();
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        main.send_video_annexb(SAMPLE,2000).await.unwrap();
        commands.handle("forceKeyFrame", &request);
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        let body = bplist::parse(&setup.body).unwrap();
        let aux = setup_stream::select(&body,111,false).unwrap();
        assert_eq!(aux.get("uuid").and_then(Value::as_str), Some(AUX));
        reply(&mut f.car, &setup, 400, &[]).await;

        // A late write on the old connection must not release SETUP for its successor.
        let (mut replacement, _peer2) = main_sender(48).await;
        main.reset_source();
        main.send_video_annexb(SAMPLE,3000).await.unwrap();
        commands.handle("stopUI", &request);
        commands.handle("showUI", &request);
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        replacement.send_video_annexb(SAMPLE,4000).await.unwrap();
        drop(main);
        commands.handle("forceKeyFrame", &request);
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 400, &[]).await;

        f.session.install(replacement).await.unwrap();
        let (ack, paused) = tokio::sync::oneshot::channel();
        f.session.commands.send(crate::screen_session::ScreenCommand::Pause(ack)).await.unwrap();
        paused.await.unwrap();
        commands.handle("stopUI", &request);
        commands.handle("showUI", &request);
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        drop(commands);
        worker.await.unwrap();
    }
    #[tokio::test]
    async fn independent_avc_frame_is_written_with_its_own_connection_key() {
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}};
        let sample = SAMPLE;
        let display = Display { width:64, height:48, safe:[0,0,64,48], uuid:AUX.into(), encoder_fps:2, initial_map:false };
        assert!(first_idr(sample, &display));
        assert!(!first_idr(sample, &aux()));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let shared = [9;32];
        let key = airplay::stream_key(&shared, 11101);
        let mut sender = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha(key), 64, 48, false);
        sender.mark_instrument_map();
        sender.set_ownership(Arc::new(AtomicBool::new(true)));
        sender.send_video_annexb(sample, 1000).await.unwrap();
        drop(sender);
        for expected_opcode in [1,0] {
            let mut header = [0u8;128];
            peer.read_exact(&mut header).await.unwrap();
            assert_eq!(header[4], expected_opcode);
            let mut body = vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
            peer.read_exact(&mut body).await.unwrap();
            if expected_opcode == 1 {
                assert_eq!(crate::h264::avcc_dimensions(&body), Some((64,48)));
            } else {
                let nonce = crate::crypto::nonce64(0);
                let plain = crate::crypto::chacha_open(&key, &nonce, &body, &header).unwrap();
                let idr = crate::screen::annexb_nals(sample).pop().unwrap();
                assert_eq!(&plain[4..], idr);
                assert!(crate::crypto::chacha_open(&airplay::stream_key(&shared, 11001), &nonce, &body, &header).is_err());
            }
        }
    }
    #[test]
    fn declaration_requires_unique_separate_instruments_and_valid_dimensions() {
        let selected = aux();
        assert_eq!(selected.uuid,AUX);
        assert_eq!(selected.safe,[0,0,960,528]);
        assert_eq!(selected.encoder_fps,2);
        let mut limited = display(111,AUX);
        if let Value::Dict(fields) = &mut limited { fields.push((Value::str("maxFPS"),Value::int(1))); }
        assert_eq!(declared(vec![display(110,MAIN), limited]).unwrap().encoder_fps,1);
        for entries in [vec![display(110,MAIN)],vec![display(111,AUX)],
            vec![display(110,MAIN),display(111,MAIN)],vec![display(110,MAIN),display(111,"no uuid")],
            vec![display(110,MAIN),display(111,&MAIN.replace('-', ""))],
            vec![display(110,MAIN),display(111,AUX),display(111,AUX)]] { assert!(declared(entries).is_none()); }
        let area = Value::dict(vec![("widthPixels",Value::int(900)),("heightPixels",Value::int(600)),
            ("safeArea",Value::dict(vec![("originXPixels",Value::int(40)),("originYPixels",Value::int(60)),
                ("widthPixels",Value::int(800)),("heightPixels",Value::int(480))]))]);
        let mut screen=display(111,AUX);
        if let Value::Dict(fields)=&mut screen { fields.push((Value::str("viewAreas"),Value::array(vec![area]))); }
        assert_eq!(declared(vec![display(110,MAIN),screen]).unwrap().safe,[30,44,600,352]);
    }
    #[test]
    fn only_navigation_url_grants_the_auxiliary_uuid_and_stop_revokes_all_old_work() {
        let (tx,rx)=watch::channel(Request::default());
        let commands=Commands { ids:vec![AUX.into()],active:Some((AUX.into(),tx)) };
        assert!(!commands.handle("showUI",&command(MAIN,MAP_URL)));
        assert!(!commands.handle("forceKeyFrame",&command(MAIN,"")));
        assert!(commands.handle("showUI",&command(AUX,"unrelated")));
        assert!(rx.borrow().grant.is_none());
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        let old=rx.borrow().grant.clone().unwrap();
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        assert!(Arc::ptr_eq(&old,rx.borrow().grant.as_ref().unwrap()));
        assert!(commands.handle("forceKeyFrame",&command(AUX,"")));
        assert_eq!(rx.borrow().keyframe,1);
        assert!(commands.handle("stopUI",&command(AUX,MAP_URL)));
        assert!(!old.load(Ordering::Acquire));
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        let next=rx.borrow().grant.clone().unwrap();
        assert!(!Arc::ptr_eq(&old,&next));
        next.store(false,Ordering::Release);
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        let retry=rx.borrow().grant.clone().unwrap();
        drop(commands);
        assert!(!retry.load(Ordering::Acquire));
        assert!(!Commands { ids:vec![],active:None }.handle("stopUI",&command(MAIN,"")));
        assert!(Commands { ids:vec![AUX.into()],active:None }.handle("forceKeyFrame",&command(AUX,"")));
    }
    #[test]
    fn response_port_and_teardown_can_only_select_the_addressed_type111_stream() {
        let d=aux();
        let body=|fields| Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]);
        let decode=|fields| response_port(&body(fields),&d,123);
        assert_eq!(decode(vec![("type",Value::int(111)),("dataPort",Value::int(4400))]),Ok(4400));
        for extra in [("uuid",Value::str(MAIN)),("streamConnectionID",Value::int(124))] {
            assert!(decode(vec![("type",Value::int(111)),("dataPort",Value::int(4400)),extra]).is_err());
        }
        for port in [-1,0,65536] { assert!(decode(vec![("type",Value::int(111)),("dataPort",Value::int(port))]).is_err()); }
        assert!(decode(vec![("type",Value::int(110)),("dataPort",Value::int(4400))]).is_err());
        assert!(decode(vec![("dataPort",Value::int(4400))]).is_err());
        for connection in [None,Some(123)] {
            let wire=bplist::parse(&stream_body(AUX,connection)).unwrap();
            let streams=wire.get("streams").unwrap().as_array().unwrap();
            assert_eq!(streams.len(),1);
            assert_eq!(streams[0].get("type").and_then(Value::as_int),Some(111));
            assert_eq!(streams[0].get("uuid").and_then(Value::as_str),Some(AUX));
            assert_eq!(streams[0].get("streamConnectionID").and_then(Value::as_int),connection);
        }
        assert!(!first_idr(&[0,0,1,0x67,0,0,1,0x68,0,0,1,0x65],&d));
    }
}
