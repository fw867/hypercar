//! Explicit, bounded microphone capture; never stores or plays received speech.
use std::{sync::{Arc,atomic::{AtomicBool,Ordering}},time::Duration};
use tokio::{sync::{watch,Notify}};
use crate::{airplay::{AirplayParams,SocketProvider},bplist::{self,Value},rtsp::{RtspChannel, RtspMessage}};

pub struct ProbeControl {tx:watch::Sender<u64>,armed:AtomicBool,self_asr:bool,owner:u64,asr_token:std::sync::atomic::AtomicU64,pub carwith:Option<Arc<crate::carlife::microphone::Microphone>>}
impl std::fmt::Debug for ProbeControl {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{f.debug_struct("ProbeControl").field("carwith",&self.carwith.is_some()).finish()}}
impl ProbeControl {
    pub fn new()->Self {let(tx,_)=watch::channel(0);Self{tx,armed:AtomicBool::new(false),self_asr:false,owner:0,asr_token:std::sync::atomic::AtomicU64::new(0),carwith:None}}
    pub fn for_self_asr(owner:u64)->Self {Self{self_asr:true,owner,..Self::new()}}
    pub fn for_carwith()->Self {Self{carwith:Some(Arc::new(Default::default())),..Self::new()}}
    #[cfg(test)] pub(crate) fn arm_test(&self){self.armed.store(true,Ordering::Release);}
    pub fn generation(&self)->u64 {*self.tx.borrow()}
    pub fn request(&self,active:bool){
        if active && !self.armed.load(Ordering::Acquire){crate::emit_log("MIC-PROBE: unavailable/not ready; no assistant or phone microphone started");return;}
        self.tx.send_if_modified(|v| {
            if (*v&1!=0)==active {return false;}
            *v=((*v>>1).wrapping_add(1)<<1)|u64::from(active);
            if let Some(sink)=self.carwith.as_ref(){sink.reset(*v);}true
        });
    }
    pub fn cancel_asr(&self,id:u64) {
        self.tx.send_if_modified(|v| {
            if !self.self_asr || self.asr_token.load(Ordering::Acquire)!=id || *v&1==0 {return false;}
            *v=v.wrapping_add(1);true
        });
    }
    pub fn toggle(&self){self.request(self.generation()&1==0);}
    pub(crate) fn finish(&self,generation:u64){
        self.tx.send_if_modified(|v| {if *v!=generation{return false;}*v=v.wrapping_add(1);if let Some(sink)=self.carwith.as_ref(){sink.reset(*v);}true});
    }
    pub(crate) fn arm(self: &Arc<Self>) -> (Armed, watch::Receiver<u64>) {
        let rx = self.tx.subscribe();
        self.armed.store(true, Ordering::Release);
        (Armed(self.clone()), rx)
    }
}
struct AsrLease(Option<(Arc<ProbeControl>,u64)>);
impl Drop for AsrLease {fn drop(&mut self){if let Some((control,id))=&self.0 {
    let _=control.asr_token.compare_exchange(*id,0,Ordering::AcqRel,Ordering::Acquire);
    crate::emit_microphone_pcm(control.owner,*id,&[],16000,true);
}}}
pub(crate) struct Armed(Arc<ProbeControl>);
impl Drop for Armed {fn drop(&mut self){self.0.armed.store(false,Ordering::Release);self.0.request(false);}}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum InputFormat { Opus16, Pcm16 }

impl InputFormat {
    fn mask(self) -> i64 { match self { Self::Opus16 => 1 << 28, Self::Pcm16 => 1 << 4 } }
}

fn declares_input(info: &Value, kind: i64, format: InputFormat) -> bool {
    info.get("audioFormats").and_then(Value::as_array).into_iter().flatten().any(|entry| {
        let declares = |key| entry.get(key).and_then(Value::as_int)
            .is_some_and(|mask| mask >= 0 && mask & format.mask() != 0);
        entry.get("type").and_then(Value::as_int) == Some(kind)
            && entry.get("audioType").and_then(Value::as_str) == Some("speechRecognition")
            && declares("audioInputFormats") && (kind != 100 || declares("audioOutputFormats"))
    })
}

pub fn supported(info:&Value)->bool { declares_input(info, 107, InputFormat::Opus16) }

pub(crate) fn shared_format(info: &Value) -> Option<InputFormat> {
    if supported(info) { return None; }
    [InputFormat::Opus16, InputFormat::Pcm16].into_iter().find(|&format| declares_input(info, 100, format))
}

#[derive(Clone, Copy)]
pub(crate) enum InputStream { Independent, Shared(InputFormat) }

impl InputStream {
    pub(crate) fn kind(self) -> i64 { match self { Self::Independent => 107, Self::Shared(_) => 100 } }
    fn format(self) -> InputFormat { match self { Self::Independent => InputFormat::Opus16, Self::Shared(format) => format } }
}

fn description(input:InputStream,id:i64,data:u16,control:Option<u16>)->Vec<u8>{
    let mut fields = vec![
        ("type",Value::int(input.kind())),("streamConnectionID",Value::int(id)),("audioFormat",Value::int(input.format().mask())),
        ("audioType",Value::str("speechRecognition")),("input",Value::bool(true)),("audioLatencyMs",Value::int(0)),
        ("dataPort",Value::int(data.into())),("spf",Value::int(320)),("supportsRTPPacketRedundancy",Value::bool(false)),
    ];
    if let Some(port) = control { fields.push(("controlPort",Value::int(port.into()))); }
    bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]))
}

fn teardown(kind:i64,id:i64)->Vec<u8>{bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
    ("type",Value::int(kind)),("streamConnectionID",Value::int(id)),
])]))]))}
pub fn input_key(shared:&[u8;32],id:u64)->[u8;32]{
    crate::crypto::hkdf_sha512(shared,format!("DataStream-Salt{id}").as_bytes(),b"DataStream-Input-Encryption-Key",32).try_into().unwrap()
}
fn normalized(ip:std::net::IpAddr)->std::net::IpAddr {match ip {std::net::IpAddr::V6(v)=>v.to_ipv4_mapped().map(std::net::IpAddr::V4).unwrap_or(ip),_=>ip}}

// Only allowlisted scalar facts: never dump a response plist (keys, UUIDs or speech).
fn scalar(value:Option<&Value>)->String {match value {
    None=>"absent".into(), Some(Value::Int(n))=>n.to_string(),
    Some(Value::Bool(b))=>b.to_string(), Some(_)=>"invalid-type".into(),
}}
fn setup_summary(root:&Value,id:i64)->String {
    let streams=root.get("streams");
    let shape=match streams {None=>"absent",Some(Value::Array(_))=>"array",Some(_)=>"invalid-type"};
    let entries=streams.and_then(Value::as_array);
    let details=entries.into_iter().flatten().take(8).enumerate().map(|(i,s)| {
        let cid=match s.get("streamConnectionID") {None=>"absent",Some(Value::Int(n)) if *n==id=>"match",Some(Value::Int(_))=>"mismatch",Some(_)=>"invalid-type"};
        let audio_type=match s.get("audioType") {None=>"absent",Some(Value::String(v)) if v=="speechRecognition"=>"speechRecognition",Some(_)=>"other"};
        format!("#{i}[type={} status={} input={} audioType={audio_type} audioFormat={} id={cid} dataPort={} controlPort={} spf={}]",
            scalar(s.get("type")),scalar(s.get("status")),scalar(s.get("input")),scalar(s.get("audioFormat")),
            scalar(s.get("dataPort")),scalar(s.get("controlPort")),scalar(s.get("spf")))
    }).collect::<Vec<_>>().join(" ");
    format!("root={} status={} streams={shape} count={} entries=[{details}] (max8; IDs/keys omitted)",
        if matches!(root,Value::Dict(_)){"dict"}else{"invalid-type"},scalar(root.get("status")),entries.map_or(0,Vec::len))
}
fn accepted_stream(root:&Value,id:i64,input:InputStream)->Result<&Value,String> {
    if !matches!(root,Value::Dict(_)){return Err("SETUP root is not a dictionary".into());}
    if root.get("status").is_some_and(|s|s.as_int()!=Some(0)){return Err("SETUP root business rejection/invalid status".into());}
    let streams=root.get("streams").and_then(Value::as_array).ok_or("SETUP streams absent/not array")?;
    let mut matches=streams.iter().filter(|s|s.get("type").and_then(Value::as_int)==Some(input.kind()));
    let stream=matches.next().ok_or_else(|| format!("SETUP omitted type{} stream", input.kind()))?;
    if matches.next().is_some(){return Err("SETUP contains duplicate input streams".into());}
    if stream.get("status").is_some_and(|s|s.as_int()!=Some(0)){return Err("SETUP stream business rejection/invalid status".into());}
    if stream.get("streamConnectionID").is_some_and(|s|s.as_int()!=Some(id)){return Err("SETUP connection ID mismatch/invalid type".into());}
    if stream.get("input").is_some_and(|v|v.as_bool()!=Some(true)){return Err("SETUP input direction mismatch".into());}
    if stream.get("audioFormat").is_some_and(|v|v.as_int()!=Some(input.format().mask())){return Err("SETUP audio format mismatch".into());}
    if stream.get("audioType").is_some_and(|v|v.as_str()!=Some("speechRecognition")){return Err("SETUP audio type mismatch".into());}
    Ok(stream)
}

// Preserve the existing timestamp/SSRC AAD and explicit trailing nonce. Only the
// payload offset varies when a receiver includes RTP CSRCs or an extension.
fn input_rtp_header(kind:u8,wire:&[u8])->Option<usize> {
    if wire.len()<36 || wire.len()>8192 || wire[0]&0xe0!=0x80 || wire[1]&127!=kind {return None;}
    let mut header=12+usize::from(wire[0]&15)*4;
    if wire[0]&0x10!=0 {
        let extension=wire.get(header..header+4)?;
        header+=4+usize::from(u16::from_be_bytes([extension[2],extension[3]]))*4;
    }
    let body=wire.len().checked_sub(header+8)?;
    (16..=4112).contains(&body).then_some(header)
}

#[derive(Default,Debug)]
pub struct Stats {pub packets:u64,pub authenticated:u64,pub samples:u64,pub peak:u32,pub lost:u64,pub duplicates:u64,pub reordered:u64,pub rejected:u64,pub decode_errors:u64,sum:f64,last_nonce:Option<u64>,last_seq:Option<u16>}
impl Stats {
    fn open(&mut self,kind:u8,wire:&[u8],key:&[u8;32])->Result<Vec<u8>,&'static str>{
        self.packets+=1;
        let header = input_rtp_header(kind,wire).ok_or_else(|| { self.rejected+=1; "invalid RTP framing/type" })?;
        let n=wire.len();let nonce=u64::from_le_bytes(wire[n-8..].try_into().unwrap());
        let payload=crate::crypto::chacha_open(key,&crate::crypto::nonce64(nonce),&wire[header..n-8],&wire[4..12]).map_err(|_|{self.rejected+=1;"authentication failed"})?;
        self.authenticated+=1;
        if self.last_nonce.is_some_and(|v|nonce<=v){self.duplicates+=1;return Err("replayed/reordered nonce");}
        let seq=u16::from_be_bytes(wire[2..4].try_into().unwrap());
        if let Some(last)=self.last_seq {let delta=seq.wrapping_sub(last);if delta==0||delta>=32768 {self.reordered+=1;return Err("old sequence");}self.lost+=u64::from(delta-1);}
        self.last_seq=Some(seq);self.last_nonce=Some(nonce);Ok(payload)
    }
    fn pcm(&mut self,pcm:&[i16]){for s in pcm {let v=i32::from(*s);self.peak=self.peak.max(v.unsigned_abs());self.sum+=f64::from(v)*f64::from(v);}self.samples+=pcm.len() as u64;}
    fn line(&self,rate:u32)->String {format!("packets={} authenticated={} samples={} rate={} peak={} rms={:.1} lost={} duplicate={} reordered={} rejected={} decodeErrors={}; PCM not saved, level is not recognition confirmation",self.packets,self.authenticated,self.samples,rate,self.peak,if self.samples==0 {0.0}else{(self.sum/self.samples as f64).sqrt()},self.lost,self.duplicates,self.reordered,self.rejected,self.decode_errors)}
}

pub fn spawn(control:Arc<RtspChannel>,factory:Arc<dyn SocketProvider>,shared:Option<[u8;32]>,params:&AirplayParams,info:&Value,stop:Arc<Notify>)->Option<tokio::task::JoinHandle<()>>{
    let trigger=params.microphone_probe.clone()?;
    let Some(shared)=shared else{crate::emit_log("MIC-PROBE: requires encrypted paired session");return None;};
    if !supported(info){
        if shared_format(info).is_none() { crate::emit_log("MIC-PROBE: no declared compatible car microphone stream"); }
        return None;
    }
    let (guard, mut rx) = trigger.arm();
    let host=params.car_ip.clone();let port=params.airplay_port;
    crate::emit_log(if trigger.carwith.is_some(){"CARWITH-MIC: armed declared type107; await authenticated RECOG_START, no background wake-word capture"}else{"MIC-PROBE: armed type107 Opus16k mono; long voice press toggles <=20s sample; phone assistant disabled only in this opt-in mode"});
    Some(crate::callback_context::spawn(async move{
        let _guard=guard;let mut handled=0;
        loop {
            let generation=*rx.borrow_and_update();
            if generation&1==0 || generation==handled {
                tokio::select!{biased;_=stop.notified()=>{trigger.request(false);return;},change=rx.changed()=>if change.is_err(){return;}};continue;
            }
            handled=generation;
            let session = InputSession { control: &control, factory: factory.as_ref(), shared: &shared, host: &host, port };
            let mut id = None;
            let result = session.sample(InputStream::Independent, &mut rx, stop.notified(), &trigger, generation, Duration::from_secs(20), false, || true, |stream| id = stream).await;
            if let Some(id) = id {
                if let Err(error) = close(&control, InputStream::Independent, id).await {
                    crate::emit_log(&format!("MIC-PROBE: type107 cleanup failed: {error}"));
                }
            }
            match result {
                Ok(stats)=>crate::emit_log(&format!("MIC-PROBE: stopped packets={} decoded_samples={}",stats.packets,stats.samples)),
                Err(error)=>{crate::emit_log(&format!("MIC-PROBE: stopped error={error}; media/control retained"));if error.starts_with("session stopped"){return;}},
            }
            trigger.finish(generation);
            if control.is_closed(){return;}
        }
    }))
}

pub(crate) struct InputSession<'a> {
    pub(crate) control: &'a RtspChannel,
    pub(crate) factory: &'a dyn SocketProvider,
    pub(crate) shared: &'a [u8;32],
    pub(crate) host: &'a str,
    pub(crate) port: u16,
}

enum Decoder { Opus(crate::opus::OpusDecoder), Pcm16 }

impl Decoder {
    fn new(format: InputFormat) -> std::io::Result<Self> {
        match format {
            InputFormat::Opus16 => crate::opus::OpusDecoder::new(16_000).map(Self::Opus),
            InputFormat::Pcm16 => Ok(Self::Pcm16),
        }
    }

    fn rate(&self) -> u32 { match self { Self::Opus(decoder) => decoder.rate, Self::Pcm16 => 16_000 } }

    fn decode(&mut self, payload: &[u8]) -> std::io::Result<Vec<i16>> {
        match self {
            Self::Opus(decoder) => decoder.decode(payload),
            Self::Pcm16 if payload.is_empty() || payload.len() % 2 != 0 => Err(std::io::Error::other("invalid PCM16 payload")),
            Self::Pcm16 => Ok(payload.chunks_exact(2).map(|sample| i16::from_be_bytes([sample[0], sample[1]])).collect()),
        }
    }
}

impl InputSession<'_> {
pub(crate) async fn sample(&self,input:InputStream,rx:&mut watch::Receiver<u64>,stop:impl std::future::Future<Output=()>,trigger:&Arc<ProbeControl>,generation:u64,
    limit:Duration,learn_sender_port:bool,valid:impl Fn()->bool,mut allocated:impl FnMut(Option<i64>))->Result<Stats,String>{
    let Self { control, factory, shared, host, port } = *self;
    tokio::pin!(stop);
    let generation_valid = || trigger.generation() == generation && valid();
    if !generation_valid() { return Err("capture superseded before SETUP".into()); }
    let peer=host.trim_matches(['[',']']).split('%').next().unwrap_or("").parse::<std::net::IpAddr>().map(normalized).map_err(|_|"probe requires numeric car IP")?;
    let mut decoder=Decoder::new(input.format()).map_err(|e|e.to_string())?;
    let data=factory.open_udp(host,0).ok_or("network-bound receive socket unavailable")?;
    let ctrl=if matches!(input, InputStream::Independent) {
        Some(factory.open_udp(host,0).ok_or("network-bound control socket unavailable")?)
    } else { None };
    let data_port=data.local_addr().map_err(|e|e.to_string())?.port();
    let control_port=ctrl.as_ref().map(|socket| socket.local_addr().map(|addr| addr.port())).transpose().map_err(|e| e.to_string())?;
    if data_port==0 || control_port==Some(0) {return Err("unbound receive port".into());}
    let id=crate::airplay::random_positive_i64();let key=input_key(shared,id as u64);
    let _asr_lease=AsrLease(if trigger.self_asr {
        trigger.asr_token.store(id as u64,Ordering::Release);Some((trigger.clone(),id as u64))
    } else {None});
    let uri=crate::rtsp_uri::audio_uri(host,port);
    let started=std::time::Instant::now();
    crate::emit_log(&format!("MIC-PROBE: RTSP#{} SETUP type{} input=true {:?} mono dataPort={data_port} controlPort={control_port:?}",control.trace_id(),input.kind(),input.format()));
    // Every attempted allocation is retired by type AND connection ID, including timeout/bad replies.
    let setup_body=description(input,id,data_port,control_port);
    if !generation_valid() { return Err("capture superseded before SETUP".into()); }
    allocated(Some(id));
        // Let the bounded transaction finish even if recognition stops while
        // writing. Dropping a partial encrypted write would close RTSP itself.
        let reply = control.request("SETUP", &uri, &setup_body, "application/x-apple-binary-plist", &[], Duration::from_secs(3)).await?;
        if matches!(input, InputStream::Shared(_)) && matches!(reply.status(), 400 | 451 | 461) {
            allocated(None); // The sole requested transport was explicitly rejected.
        }
        if !generation_valid() { return Err("capture cancelled during SETUP".into()); }
        let parsed=bplist::parse(&reply.body);
        crate::emit_log(&format!("MIC-PROBE: RTSP#{} CSeq={:?} SETUP response status={} bytes={} {}; RTSP success alone is not input acceptance",
            control.trace_id(),reply.cseq(),reply.status(),reply.body.len(),parsed.as_ref().map(|root|setup_summary(root,id)).unwrap_or_else(|_|"body=empty-or-invalid-bplist".into())));
        if reply.status()!=200{return Err(format!("input SETUP rejected status={}",reply.status()));}
        let root=parsed.map_err(|_|"SETUP empty/invalid binary plist")?;
        let stream = accepted_stream(&root,id,input)?;
        let advertised_port = match input {
            InputStream::Independent => None,
            InputStream::Shared(_) => {
                if stream.get("streamConnectionID").and_then(Value::as_int) != Some(id) {
                    return Err("shared input SETUP did not confirm streamConnectionID".into());
                }
                Some(stream.get("dataPort").and_then(Value::as_int).and_then(|value| u16::try_from(value).ok())
                    .filter(|&port| port != 0).ok_or("shared input SETUP missing peer dataPort")?)
            }
        };
        // DiPlay advertises its downlink listener in SETUP. Its separate uplink
        // socket can be identified only by a valid RTP packet from the car IP.
        let mut sender_port = if learn_sender_port { None } else { advertised_port };
        crate::emit_log("MIC-PROBE: SETUP accepted; awaiting authenticated RTP, not recording confirmation");
        let mut stats=Stats::default();let mut wire=[0u8;8193];let mut feedback=[0u8;2048];
        let mut tick=tokio::time::interval(Duration::from_secs(1));tick.tick().await;
        let mut last_packet=std::time::Instant::now();
        loop {tokio::select!{biased;
            _=&mut stop=>{crate::emit_log(&format!("MIC-PROBE: final {}",stats.line(decoder.rate())));return Err("session stopped".into());},
            _=rx.changed()=>{rx.borrow_and_update();break;},
            _=tick.tick()=>{
                crate::emit_log(&format!("MIC-PROBE: {}",stats.line(decoder.rate())));
                if !generation_valid() { return Err("capture ownership changed".into()); }
                if started.elapsed()>=limit {break;}
                if stats.authenticated==0&&started.elapsed()>=Duration::from_secs(5){return Err("no authenticated microphone RTP within 5s".into());}
                if stats.authenticated>0&&last_packet.elapsed()>=Duration::from_secs(3){return Err("microphone RTP idle 3s".into());}
            },
            packet=async { match &ctrl { Some(socket) => socket.recv_from(&mut feedback).await, None => std::future::pending().await } }=>{if packet.is_err(){return Err("control UDP closed".into());}},
            packet=data.recv_from(&mut wire)=>{
                let(n,source)=packet.map_err(|e|e.to_string())?;
                if !generation_valid() { return Err("capture ownership changed".into()); }
                if normalized(source.ip())!=peer || sender_port.is_some_and(|port| port != source.port()) {continue;}
                let before=stats.authenticated;
                let Ok(payload)=stats.open(input.kind() as u8,&wire[..n],&key) else{continue;};
                if learn_sender_port && sender_port.is_none() {
                    sender_port = Some(source.port());
                    crate::emit_log(&format!("MIC-PROBE: authenticated uplink port={} advertisedDataPort={advertised_port:?}", source.port()));
                }
                if before==0 {crate::emit_log("MIC-PROBE: first authenticated RTP; decoder/recognition not yet confirmed");}
                last_packet=std::time::Instant::now();
                match decoder.decode(&payload){Ok(pcm)=>{
                    if stats.samples==0 && !pcm.is_empty(){crate::emit_log("MIC-PROBE: first decoded PCM; assistant recognition and audible output remain unconfirmed");}
                    stats.pcm(&pcm);
                    if !generation_valid() {return Err("capture superseded".into());}
                    if trigger.self_asr {
                        let bytes:Vec<u8>=pcm.iter().flat_map(|s|s.to_le_bytes()).collect();
                        crate::emit_microphone_pcm(trigger.owner,id as u64,&bytes,decoder.rate(),false);
                    }
                    if let Some(target)=trigger.carwith.as_ref(){target.push(generation,&pcm,decoder.rate())?;}
                },Err(e)=>{stats.decode_errors+=1;if stats.decode_errors>=8{return Err(format!("microphone decode failed: {e}"));}}}
            }
        }}
        crate::emit_log(&format!("MIC-PROBE: final {}",stats.line(decoder.rate())));Ok(stats)
}
}

pub(crate) async fn close(control: &RtspChannel, input: InputStream, id: i64) -> Result<(), String> {
    if control.is_closed() { return Err("control closed".into()); }
    let reply = control.request("TEARDOWN", "/", &teardown(input.kind(), id), "application/x-apple-binary-plist", &[], Duration::from_secs(1)).await?;
    close_ack(&reply, input, id)
}

fn close_ack(reply: &RtspMessage, input: InputStream, id: i64) -> Result<(), String> {
    const AIRPLAY_NOT_FOUND: i64 = -6727; // R14G17 CommonServices kNotFoundErr.
    if matches!(input, InputStream::Shared(_)) {
        if let Some(root) = bplist::parse(&reply.body).ok().filter(|root| root.get("streams").is_some()) {
            let [row] = root.get("streams").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]) else {
                return Err("MICROPHONE_TEARDOWN unscoped stream results".into());
            };
            if row.get("type").and_then(Value::as_int) != Some(input.kind()) ||
                row.get("streamConnectionID").and_then(Value::as_int) != Some(id) {
                return Err("MICROPHONE_TEARDOWN stream result does not identify the requested input".into());
            }
            let status = row.get("status");
            if status.and_then(Value::as_int) == Some(AIRPLAY_NOT_FOUND) && matches!(reply.status(), 200 | 404) &&
                root.get("status").is_none_or(|value| matches!(value.as_int(), Some(0 | AIRPLAY_NOT_FOUND))) {
                crate::emit_log("MIC-PROBE: shared microphone stream already absent by connection ID");
                return Ok(());
            }
            if status.is_some_and(|value| value.as_int() != Some(0)) {
                return Err("MICROPHONE_TEARDOWN rejected the requested input stream".into());
            }
        }
    }
    crate::resource_trace::validate_ack("MICROPHONE_TEARDOWN", reply)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_main_input_uses_the_same_declared_bidirectional_format_and_prefers_type107() {
        let row = |kind, role, input, output| Value::dict(vec![
            ("type", Value::int(kind)), ("audioType", Value::str(role)),
            ("audioInputFormats", Value::int(input)), ("audioOutputFormats", Value::int(output)),
        ]);
        let info = |rows| Value::dict(vec![("audioFormats", Value::array(rows))]);
        let pcm = row(100, "speechRecognition", 16, 16);
        assert_eq!(shared_format(&info(vec![pcm.clone()])), Some(InputFormat::Pcm16));
        assert_eq!(shared_format(&info(vec![pcm, row(107, "speechRecognition", 1 << 28, 0)])), None);
        assert_eq!(shared_format(&info(vec![row(100, "speechRecognition", 1 << 28, 1 << 28)])), Some(InputFormat::Opus16));
        for rejected in [
            row(100, "media", 16, 16), row(100, "speechRecognition", 16, 1 << 15),
            row(100, "speechRecognition", 1 << 26, 1 << 26), row(100, "speechRecognition", -1, -1),
        ] {
            assert_eq!(shared_format(&info(vec![rejected])), None);
        }
    }
    #[test] fn setup_200_without_input_stream_is_not_acceptance() {
        for root in [Value::dict(vec![]),Value::dict(vec![("streams",Value::array(vec![]))]),
            Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("dataPort",Value::int(6000))])]))]),
            Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(110))])]))])] {
            assert!(accepted_stream(&root,42,InputStream::Independent).is_err());
            let summary=setup_summary(&root,42);
            assert!(summary.contains("streams="));assert!(!summary.contains("id=match"));
        }
    }
    #[test] fn input_reply_checks_business_direction_format_and_id() {
        let response=|fields|Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]);
        assert!(accepted_stream(&response(vec![("type",Value::int(107))]),42,InputStream::Independent).is_ok());
        for (field,value) in [("status",Value::int(-6722)),("status",Value::str("0")),
            ("streamConnectionID",Value::int(43)),("streamConnectionID",Value::str("42")),
            ("input",Value::bool(false)),("audioFormat",Value::int(1<<29)),("audioType",Value::str("media"))] {
            assert!(accepted_stream(&response(vec![("type",Value::int(107)),(field,value)]),42,InputStream::Independent).is_err(),"{field}");
        }
        let mut root=response(vec![("type",Value::int(107)),("streamConnectionID",Value::int(42))]);
        assert!(accepted_stream(&root,42,InputStream::Independent).is_ok());
        if let Value::Dict(fields)=&mut root {fields.push((Value::str("status"),Value::int(-1)));}
        assert!(accepted_stream(&root,42,InputStream::Independent).unwrap_err().contains("root business"));
        assert!(accepted_stream(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(107))]);2]))]),42,InputStream::Independent).is_err());
    }
    #[test] fn response_summary_is_bounded_and_does_not_expose_secrets() {
        let root=Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
            ("type",Value::int(107)),("streamConnectionID",Value::int(987654321)),
            ("audioType",Value::str("SECRET")),("shk",Value::str("SECRET")),("uuid",Value::str("SECRET")),
        ]);20]))]);
        let summary=setup_summary(&root,42);
        assert!(summary.contains("count=20"));assert!(summary.contains("id=mismatch"));
        assert!(summary.contains("#7["));assert!(!summary.contains("#8["));
        assert!(!summary.contains("SECRET"));assert!(!summary.contains("987654321"));
    }
    fn packet(key:&[u8;32],seq:u16,nonce:u64)->Vec<u8>{
        let mut header=vec![0x80,107];header.extend(seq.to_be_bytes());header.extend([0,0,0,0,0,0,0,1]);
        let cipher=crate::crypto::chacha_seal(key,&crate::crypto::nonce64(nonce),&[0xf8,0xff,0xfe],&header[4..12]);
        header.extend(cipher);header.extend(nonce.to_le_bytes());header
    }
    #[test] fn input_only_capabilities_and_selective_cleanup(){
        for (kind,format,expected) in [(100,1<<28,false),(107,1<<29,false),(107,1<<28,true)] {
            let info=Value::dict(vec![("audioFormats",Value::array(vec![Value::dict(vec![
                ("type",Value::int(kind)),("audioType",Value::str("speechRecognition")),("audioInputFormats",Value::int(format)),
            ])]))]);assert_eq!(supported(&info),expected);
        }
        let setup=bplist::parse(&description(InputStream::Independent,42,1234,Some(5678))).unwrap();
        let streams=setup.get("streams").unwrap().as_array().unwrap();assert_eq!(streams.len(),1);
        assert_eq!(streams[0].get("type").unwrap().as_int(),Some(107));
        assert_eq!(streams[0].get("dataPort").unwrap().as_int(),Some(1234));
        let cleanup=bplist::parse(&teardown(107,42)).unwrap();let streams=cleanup.get("streams").unwrap().as_array().unwrap();
        assert_eq!(streams.len(),1);assert_eq!(streams[0].get("type").unwrap().as_int(),Some(107));
        assert_eq!(streams[0].get("streamConnectionID").unwrap().as_int(),Some(42));
    }
    #[test] fn authenticated_input_rejects_corruption_replay_and_accounts_for_loss(){
        let key=input_key(&[7;32],42);
        let output=crate::crypto::hkdf_sha512(&[7;32],b"DataStream-Salt42",b"DataStream-Output-Encryption-Key",32);
        assert_ne!(key.as_slice(),output.as_slice());
        let mut stats=Stats::default();
        assert_eq!(stats.open(107,&packet(&key,65535,0),&key).unwrap(),vec![0xf8,0xff,0xfe]);
        assert!(stats.open(107,&packet(&key,65535,0),&key).is_err());
        let mut bad=packet(&key,0,1);bad[12]^=1;assert!(stats.open(107,&bad,&key).is_err());
        assert!(stats.open(107,&packet(&key,0,1),&key).is_ok());
        assert!(stats.open(107,&packet(&key,3,2),&key).is_ok());assert_eq!(stats.lost,2);
        assert!(stats.open(107,&packet(&key,2,3),&key).is_err());
        assert!(stats.open(107,&packet(&key,4,3),&key).is_ok());
        assert!(stats.open(107,&[0;12],&key).is_err());
        stats.pcm(&[i16::MIN,i16::MAX]);assert_eq!(stats.peak,32768);assert_eq!(stats.samples,2);
    }
    #[test] fn input_rtp_extensions_and_csrc_keep_authenticated_payload_and_replay_checks() {
        let key=input_key(&[7;32],42);
        let fixed=packet(&key,1,0);
        let mut extended=fixed[..12].to_vec();extended[0]=0x91; // one CSRC plus extension
        extended.extend([0,0,0,2, 0xbe,0xde,0,1, 1,2,3,4]);
        extended.extend_from_slice(&fixed[12..]);
        let mut stats=Stats::default();
        assert_eq!(stats.open(107,&extended,&key).unwrap(),vec![0xf8,0xff,0xfe]);
        assert!(stats.open(107,&extended,&key).is_err());
        let mut corrupt=extended.clone();corrupt[24]^=1;
        assert!(Stats::default().open(107,&corrupt,&key).is_err());
        for end in 0..extended.len() {let _=Stats::default().open(107,&extended[..end],&key);}
        extended[18]=255;extended[19]=255;
        assert!(Stats::default().open(107,&extended,&key).is_err());
        let mut padded=fixed;padded[0]|=0x20;
        assert!(Stats::default().open(107,&padded,&key).is_err());
    }
    #[test] fn self_asr_cancellation_is_capture_scoped_and_separate_from_carwith() {
        let control=ProbeControl::for_self_asr(0);control.arm_test();control.request(true);
        control.asr_token.store(42,Ordering::Release);let generation=control.generation();
        control.cancel_asr(41);assert_eq!(control.generation(),generation);
        control.cancel_asr(42);assert_eq!(control.generation()&1,0);
        control.request(true);control.asr_token.store(43,Ordering::Release);
        let next=control.generation();control.cancel_asr(42);assert_eq!(control.generation(),next);
        let carwith=ProbeControl::for_carwith();carwith.arm_test();carwith.request(true);
        carwith.asr_token.store(42,Ordering::Release);let generation=carwith.generation();
        carwith.cancel_asr(42);assert_eq!(carwith.generation(),generation);
    }
    #[test] fn late_capture_cleanup_does_not_cancel_a_new_recognition_request(){
        let control=ProbeControl::for_carwith();control.arm_test();control.request(true);let old=control.generation();
        control.request(false);control.request(true);let new=control.generation();assert_ne!(old,new);
        control.finish(old);assert_eq!(control.generation(),new);control.finish(new);assert_eq!(control.generation()&1,0);
    }
    #[test] fn unarmed_voice_does_not_queue_a_probe(){
        let control=ProbeControl::new();let rx=control.tx.subscribe();control.toggle();assert!(!rx.has_changed().unwrap());
    }
    #[cfg(target_os="android")]
    struct Factory;
    #[cfg(target_os="android")]
    impl SocketProvider for Factory {
        fn open_tcp(&self,_:&str,_:u16,_:u32)->Option<tokio::net::TcpStream>{None}
        fn open_udp(&self,_:&str,_:u16)->Option<tokio::net::UdpSocket>{
            let socket=std::net::UdpSocket::bind("127.0.0.1:0").ok()?;socket.set_nonblocking(true).ok()?;
            tokio::net::UdpSocket::from_std(socket).ok()
        }
    }
    #[cfg(target_os="android")]
    async fn fixture(send_audio:bool,reply_kind:u8)->Result<Stats,String>{
        use tokio::io::{AsyncReadExt,AsyncWriteExt};
        async fn read(peer:&mut tokio::net::TcpStream)->crate::rtsp::RtspMessage {
            let mut bytes=Vec::new();let mut buf=[0;4096];loop {
                let n=peer.read(&mut buf).await.unwrap();assert!(n>0);bytes.extend(&buf[..n]);
                if let Some(request)=crate::rtsp::parse_rtsp_messages(&mut bytes).unwrap().pop(){return request;}
            }
        }
        async fn reply(peer:&mut tokio::net::TcpStream,cseq:i32,status:u16,body:&[u8]){
            peer.write_all(format!("RTSP/1.0 {status} Response\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n",body.len()).as_bytes()).await.unwrap();peer.write_all(body).await.unwrap();
        }
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let(mut peer,_)=listener.accept().await.unwrap();let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let client=control.clone();
        let probe=Arc::new(ProbeControl::for_carwith());let (_armed,mut rx)=probe.arm();probe.request(true);
        rx.borrow_and_update();
        let input=probe.clone();let generation=input.generation();
        let capture=crate::callback_context::spawn(async move {
            let session = InputSession { control: &client, factory: &Factory, shared: &[7;32], host: "127.0.0.1", port: 7000 };
            let mut id = None;
            let result = session.sample(InputStream::Independent, &mut rx, std::future::pending(), &input, generation, Duration::from_millis(250), false, || true, |stream| id = stream).await;
            if let Some(id) = id { close(&client, InputStream::Independent, id).await.unwrap(); }
            result
        });
        let request=read(&mut peer).await;assert!(request.first_line.starts_with("SETUP "));
        let root=bplist::parse(&request.body).unwrap();let stream=&root.get("streams").unwrap().as_array().unwrap()[0];
        let id=stream.get("streamConnectionID").unwrap().as_int().unwrap();let port=stream.get("dataPort").unwrap().as_int().unwrap() as u16;
        let body=bplist::encode(&match reply_kind {
            2=>Value::dict(vec![("streams",Value::array(vec![]))]),
            3=>Value::dict(vec![("status",Value::int(-6722))]),
            _=>Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(107)),("streamConnectionID",Value::int(id))])]))]),
        });
        reply(&mut peer,request.cseq().unwrap(),if reply_kind==1{400}else{200},&body).await;
        if send_audio {
            let udp=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let key=input_key(&[7;32],id as u64);
            for i in 0..12 {udp.send_to(&packet(&key,i,i as u64),("127.0.0.1",port)).await.unwrap();tokio::time::sleep(Duration::from_millis(20)).await;}
        }
        let request=tokio::time::timeout(Duration::from_secs(3),read(&mut peer)).await.unwrap();assert!(request.first_line.starts_with("TEARDOWN "));
        let root=bplist::parse(&request.body).unwrap();let streams=root.get("streams").unwrap().as_array().unwrap();assert_eq!(streams.len(),1);
        assert_eq!(streams[0].get("type").unwrap().as_int(),Some(107));assert_eq!(streams[0].get("streamConnectionID").unwrap().as_int(),Some(id));
        reply(&mut peer,request.cseq().unwrap(),200,&[]).await;
        let result=capture.await.unwrap();if send_audio {assert!(probe.carwith.as_ref().unwrap().queued_bytes()>0);}
        assert!(!control.is_closed());control.close();result
    }
    #[cfg(target_os="android")]
    #[tokio::test] async fn encrypted_rtp_decodes_and_only_input_stream_is_retired(){
        let stats=fixture(true,0).await.unwrap();assert_eq!(stats.authenticated,12);assert!(stats.samples>0);
    }
    #[cfg(target_os="android")]
    #[tokio::test] async fn rejected_setup_still_retires_only_input_stream(){assert!(fixture(false,1).await.unwrap_err().contains("400"));}
    #[cfg(target_os="android")]
    #[tokio::test] async fn bounded_silent_probe_does_not_stop_control_session(){let stats=fixture(false,0).await.unwrap();assert_eq!(stats.samples,0);}
    #[cfg(target_os="android")]
    #[tokio::test] async fn missing_input_despite_200_only_retires_input_stream(){assert!(fixture(false,2).await.unwrap_err().contains("omitted type107"));}
    #[cfg(target_os="android")]
    #[tokio::test] async fn business_rejection_despite_200_only_retires_input_stream(){assert!(fixture(false,3).await.unwrap_err().contains("root business"));}
}
