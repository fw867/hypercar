//! Opt-in, bounded type-107 microphone receive probe; never stores or plays received speech.
use std::{sync::{Arc,atomic::{AtomicBool,Ordering}},time::Duration};
use tokio::{sync::{watch,Notify}};
use crate::{airplay::{AirplayParams,SocketProvider},bplist::{self,Value},rtsp::RtspChannel};

pub struct ProbeControl {tx:watch::Sender<u64>,armed:AtomicBool,pub carwith:Option<Arc<crate::carlife::microphone::Microphone>>}
impl std::fmt::Debug for ProbeControl {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{f.debug_struct("ProbeControl").field("carwith",&self.carwith.is_some()).finish()}}
impl ProbeControl {
    pub fn new()->Self {let(tx,_)=watch::channel(0);Self{tx,armed:AtomicBool::new(false),carwith:None}}
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
    pub fn toggle(&self){self.request(self.generation()&1==0);}
    fn finish(&self,generation:u64){
        self.tx.send_if_modified(|v| {if *v!=generation{return false;}*v=v.wrapping_add(1);if let Some(sink)=self.carwith.as_ref(){sink.reset(*v);}true});
    }
}
struct Armed(Arc<ProbeControl>);
impl Drop for Armed {fn drop(&mut self){self.0.armed.store(false,Ordering::Release);self.0.request(false);}}

pub fn supported(info:&Value)->bool {
    info.get("audioFormats").and_then(Value::as_array).into_iter().flatten().any(|f|
        f.get("type").and_then(Value::as_int)==Some(107)&&
        f.get("audioType").and_then(Value::as_str)==Some("speechRecognition")&&
        f.get("audioInputFormats").and_then(Value::as_int).is_some_and(|b|b&(1<<28)!=0))
}
fn description(id:i64,data:u16,control:u16)->Vec<u8>{bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
    ("type",Value::int(107)),("streamConnectionID",Value::int(id)),("audioFormat",Value::int(1<<28)),
    ("audioType",Value::str("speechRecognition")),("input",Value::bool(true)),("audioLatencyMs",Value::int(0)),
    ("dataPort",Value::int(data as _)),("controlPort",Value::int(control as _)),("spf",Value::int(320)),
    ("supportsRTPPacketRedundancy",Value::bool(false)),
])]))]))}
fn teardown(id:i64)->Vec<u8>{bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
    ("type",Value::int(107)),("streamConnectionID",Value::int(id)),
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
fn accepted_stream(root:&Value,id:i64)->Result<&Value,String> {
    if !matches!(root,Value::Dict(_)){return Err("SETUP root is not a dictionary".into());}
    if root.get("status").is_some_and(|s|s.as_int()!=Some(0)){return Err("SETUP root business rejection/invalid status".into());}
    let streams=root.get("streams").and_then(Value::as_array).ok_or("SETUP streams absent/not array")?;
    let mut matches=streams.iter().filter(|s|s.get("type").and_then(Value::as_int)==Some(107));
    let stream=matches.next().ok_or("SETUP omitted type107 stream")?;
    if matches.next().is_some(){return Err("SETUP ambiguous duplicate type107 streams".into());}
    if stream.get("status").is_some_and(|s|s.as_int()!=Some(0)){return Err("SETUP stream business rejection/invalid status".into());}
    if stream.get("streamConnectionID").is_some_and(|s|s.as_int()!=Some(id)){return Err("SETUP connection ID mismatch/invalid type".into());}
    if stream.get("input").is_some_and(|v|v.as_bool()!=Some(true)){return Err("SETUP input direction mismatch".into());}
    if stream.get("audioFormat").is_some_and(|v|v.as_int()!=Some(1<<28)){return Err("SETUP audio format mismatch".into());}
    if stream.get("audioType").is_some_and(|v|v.as_str()!=Some("speechRecognition")){return Err("SETUP audio type mismatch".into());}
    Ok(stream)
}

#[derive(Default,Debug)]
pub struct Stats {pub packets:u64,pub authenticated:u64,pub samples:u64,pub peak:u32,pub lost:u64,pub duplicates:u64,pub reordered:u64,pub rejected:u64,pub decode_errors:u64,sum:f64,last_nonce:Option<u64>,last_seq:Option<u16>}
impl Stats {
    fn open(&mut self,wire:&[u8],key:&[u8;32])->Result<Vec<u8>,&'static str>{
        self.packets+=1;
        if wire.len()<36||wire.len()>4132||wire[0]!=0x80||wire[1]&127!=107 {self.rejected+=1;return Err("invalid RTP framing/type");}
        let n=wire.len();let nonce=u64::from_le_bytes(wire[n-8..].try_into().unwrap());
        let payload=crate::crypto::chacha_open(key,&crate::crypto::nonce64(nonce),&wire[12..n-8],&wire[4..12]).map_err(|_|{self.rejected+=1;"authentication failed"})?;
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
    if !supported(info){crate::emit_log("MIC-PROBE: no declared type107 speechRecognition Opus16k input; no type100 fallback");return None;}
    let mut rx=trigger.tx.subscribe();let guard=Armed(trigger.clone());
    trigger.armed.store(true,Ordering::Release);
    let host=params.car_ip.clone();let port=params.airplay_port;
    crate::emit_log(if trigger.carwith.is_some(){"CARWITH-MIC: armed declared type107; await authenticated RECOG_START, no background wake-word capture"}else{"MIC-PROBE: armed type107 Opus16k mono; long voice press toggles <=20s sample; phone assistant disabled only in this opt-in mode"});
    Some(tokio::spawn(async move{
        let _guard=guard;let mut handled=0;
        loop {
            let generation=*rx.borrow_and_update();
            if generation&1==0 || generation==handled {
                tokio::select!{biased;_=stop.notified()=>{trigger.request(false);return;},change=rx.changed()=>if change.is_err(){return;}};continue;
            }
            handled=generation;
            match sample(control.clone(),factory.clone(),shared,&host,port,&mut rx,stop.clone(),Duration::from_secs(20),Some((trigger.clone(),generation))).await {
                Ok(stats)=>crate::emit_log(&format!("MIC-PROBE: stopped packets={} decoded_samples={}",stats.packets,stats.samples)),
                Err(error)=>{crate::emit_log(&format!("MIC-PROBE: stopped error={error}; media/control retained"));if error.starts_with("session stopped"){return;}},
            }
            trigger.finish(generation);
            if control.is_closed(){return;}
        }
    }))
}

async fn sample(control:Arc<RtspChannel>,factory:Arc<dyn SocketProvider>,shared:[u8;32],host:&str,port:u16,rx:&mut watch::Receiver<u64>,stop:Arc<Notify>,limit:Duration,sink:Option<(Arc<ProbeControl>,u64)>)->Result<Stats,String>{
    let peer=host.trim_matches(['[',']']).split('%').next().unwrap_or("").parse::<std::net::IpAddr>().map(normalized).map_err(|_|"probe requires numeric car IP")?;
    let mut decoder=crate::opus::OpusDecoder::new(16000).map_err(|e|e.to_string())?;
    let data=factory.open_udp(host,0).ok_or("network-bound receive socket unavailable")?;
    let ctrl=factory.open_udp(host,0).ok_or("network-bound control socket unavailable")?;
    let data_port=data.local_addr().map_err(|e|e.to_string())?.port();let control_port=ctrl.local_addr().map_err(|e|e.to_string())?.port();
    if data_port==0||control_port==0{return Err("unbound receive port".into());}
    let id=(rand::random::<u64>()&i64::MAX as u64).max(1) as i64;let key=input_key(&shared,id as u64);
    let uri=crate::rtsp_uri::audio_uri(host,port);
    let started=std::time::Instant::now();
    crate::emit_log(&format!("MIC-PROBE: RTSP#{} SETUP type107 input=true Opus16k mono dataPort={data_port} controlPort={control_port}",control.trace_id()));
    // Every attempted allocation is retired by type AND connection ID, including timeout/bad replies.
    let setup_body=description(id,data_port,control_port);
    let result=async {
        let reply=tokio::select!{biased;_=stop.notified()=>return Err("session stopped during SETUP".into()),_=rx.changed()=>{rx.borrow_and_update();return Err("cancelled during SETUP".into());},r=control.request("SETUP",&uri,&setup_body,"application/x-apple-binary-plist",&[],Duration::from_secs(3))=>r?};
        let parsed=bplist::parse(&reply.body);
        crate::emit_log(&format!("MIC-PROBE: RTSP#{} CSeq={:?} SETUP response status={} bytes={} {}; RTSP success alone is not input acceptance",
            control.trace_id(),reply.cseq(),reply.status(),reply.body.len(),parsed.as_ref().map(|root|setup_summary(root,id)).unwrap_or_else(|_|"body=empty-or-invalid-bplist".into())));
        if reply.status()!=200{return Err(format!("input SETUP rejected status={}",reply.status()));}
        let root=parsed.map_err(|_|"SETUP empty/invalid binary plist")?;
        accepted_stream(&root,id)?;
        crate::emit_log("MIC-PROBE: SETUP accepted; awaiting authenticated RTP, not recording confirmation");
        let mut stats=Stats::default();let mut wire=[0u8;8192];let mut feedback=[0u8;2048];
        let mut tick=tokio::time::interval(Duration::from_secs(1));tick.tick().await;
        let mut last_packet=std::time::Instant::now();
        loop {tokio::select!{biased;
            _=stop.notified()=>{crate::emit_log(&format!("MIC-PROBE: final {}",stats.line(decoder.rate)));return Err("session stopped".into());},
            _=rx.changed()=>{rx.borrow_and_update();break;},
            _=tick.tick()=>{
                crate::emit_log(&format!("MIC-PROBE: {}",stats.line(decoder.rate)));
                if started.elapsed()>=limit {break;}
                if stats.authenticated==0&&started.elapsed()>=Duration::from_secs(5){return Err("no authenticated microphone RTP within 5s".into());}
                if stats.authenticated>0&&last_packet.elapsed()>=Duration::from_secs(3){return Err("microphone RTP idle 3s".into());}
            },
            packet=ctrl.recv_from(&mut feedback)=>{if packet.is_err(){return Err("control UDP closed".into());}},
            packet=data.recv_from(&mut wire)=>{
                let(n,source)=packet.map_err(|e|e.to_string())?;
                if normalized(source.ip())!=peer {continue;}
                let before=stats.authenticated;
                let Ok(payload)=stats.open(&wire[..n],&key) else{continue;};
                if before==0 {crate::emit_log("MIC-PROBE: first authenticated RTP; decoder/recognition not yet confirmed");}
                last_packet=std::time::Instant::now();
                match decoder.decode(&payload){Ok(pcm)=>{
                    if stats.samples==0 && !pcm.is_empty(){crate::emit_log("MIC-PROBE: first decoded PCM; assistant recognition and audible output remain unconfirmed");}
                    stats.pcm(&pcm);
                    if let Some((control,generation))=sink.as_ref(){
                        if control.generation()!=*generation {return Err("capture superseded".into());}
                        if let Some(target)=control.carwith.as_ref(){target.push(*generation,&pcm,decoder.rate)?;}
                    }
                },Err(e)=>{stats.decode_errors+=1;if stats.decode_errors>=8{return Err(format!("Opus decode failed: {e}"));}}}
            }
        }}
        crate::emit_log(&format!("MIC-PROBE: final {}",stats.line(decoder.rate)));Ok(stats)
    }.await;
    drop(data);drop(ctrl);drop(decoder);
    if !control.is_closed(){
        match tokio::time::timeout(Duration::from_secs(2),control.request("TEARDOWN","/",&teardown(id),"application/x-apple-binary-plist",&[],Duration::from_secs(1))).await{
            Ok(Ok(reply))=>crate::emit_log(&format!("MIC-PROBE: selective type107 cleanup RTSP_status={}; request excludes type100/102/110; receiver release unconfirmed",reply.status())),
            _=>crate::emit_log("MIC-PROBE: type107 cleanup timeout/error; no whole-session teardown"),
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn setup_200_without_input_stream_is_not_acceptance() {
        for root in [Value::dict(vec![]),Value::dict(vec![("streams",Value::array(vec![]))]),
            Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("dataPort",Value::int(6000))])]))]),
            Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(110))])]))])] {
            assert!(accepted_stream(&root,42).is_err());
            let summary=setup_summary(&root,42);
            assert!(summary.contains("streams="));assert!(!summary.contains("id=match"));
        }
    }
    #[test] fn input_reply_checks_business_direction_format_and_id() {
        let response=|fields|Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]);
        assert!(accepted_stream(&response(vec![("type",Value::int(107))]),42).is_ok());
        for (field,value) in [("status",Value::int(-6722)),("status",Value::str("0")),
            ("streamConnectionID",Value::int(43)),("streamConnectionID",Value::str("42")),
            ("input",Value::bool(false)),("audioFormat",Value::int(1<<29)),("audioType",Value::str("media"))] {
            assert!(accepted_stream(&response(vec![("type",Value::int(107)),(field,value)]),42).is_err(),"{field}");
        }
        let mut root=response(vec![("type",Value::int(107)),("streamConnectionID",Value::int(42))]);
        assert!(accepted_stream(&root,42).is_ok());
        if let Value::Dict(fields)=&mut root {fields.push((Value::str("status"),Value::int(-1)));}
        assert!(accepted_stream(&root,42).unwrap_err().contains("root business"));
        assert!(accepted_stream(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(107))]);2]))]),42).is_err());
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
        let setup=bplist::parse(&description(42,1234,5678)).unwrap();
        let streams=setup.get("streams").unwrap().as_array().unwrap();assert_eq!(streams.len(),1);
        assert_eq!(streams[0].get("type").unwrap().as_int(),Some(107));
        assert_eq!(streams[0].get("dataPort").unwrap().as_int(),Some(1234));
        let cleanup=bplist::parse(&teardown(42)).unwrap();let streams=cleanup.get("streams").unwrap().as_array().unwrap();
        assert_eq!(streams.len(),1);assert_eq!(streams[0].get("type").unwrap().as_int(),Some(107));
        assert_eq!(streams[0].get("streamConnectionID").unwrap().as_int(),Some(42));
    }
    #[test] fn authenticated_input_rejects_corruption_replay_and_accounts_for_loss(){
        let key=input_key(&[7;32],42);
        let output=crate::crypto::hkdf_sha512(&[7;32],b"DataStream-Salt42",b"DataStream-Output-Encryption-Key",32);
        assert_ne!(key.as_slice(),output.as_slice());
        let mut stats=Stats::default();
        assert_eq!(stats.open(&packet(&key,65535,0),&key).unwrap(),vec![0xf8,0xff,0xfe]);
        assert!(stats.open(&packet(&key,65535,0),&key).is_err());
        let mut bad=packet(&key,0,1);bad[12]^=1;assert!(stats.open(&bad,&key).is_err());
        assert!(stats.open(&packet(&key,0,1),&key).is_ok());
        assert!(stats.open(&packet(&key,3,2),&key).is_ok());assert_eq!(stats.lost,2);
        assert!(stats.open(&packet(&key,2,3),&key).is_err());
        assert!(stats.open(&packet(&key,4,3),&key).is_ok());
        assert!(stats.open(&[0;12],&key).is_err());
        stats.pcm(&[i16::MIN,i16::MAX]);assert_eq!(stats.peak,32768);assert_eq!(stats.samples,2);
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
                if let Some(request)=crate::rtsp::parse_rtsp_messages(&mut bytes).pop(){return request;}
            }
        }
        async fn reply(peer:&mut tokio::net::TcpStream,cseq:i32,status:u16,body:&[u8]){
            peer.write_all(format!("RTSP/1.0 {status} Response\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n",body.len()).as_bytes()).await.unwrap();peer.write_all(body).await.unwrap();
        }
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let(mut peer,_)=listener.accept().await.unwrap();let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let client=control.clone();let(_tx,mut rx)=watch::channel(0);
        let probe=Arc::new(ProbeControl::for_carwith());probe.arm_test();probe.request(true);
        let input=probe.clone();let generation=input.generation();
        let capture=tokio::spawn(async move{sample(client,Arc::new(Factory),[7;32],"127.0.0.1",7000,&mut rx,Arc::new(Notify::new()),Duration::from_millis(250),Some((input,generation))).await});
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
