//! BMW assistant/navigation output negotiation; resource ownership is managed by the caller.
use std::{sync::Arc,time::Duration};
use crate::{airplay::{AirplayParams,SocketProvider},audio_out::AudioOut,bplist::{self,Value},rtsp::{RtspChannel,RtspMessage}};

#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Kind { Speech, Navigation }
#[derive(Debug)]
pub struct OpenError {
    pub message:String,
    /// True only when no receiver mutation occurred, or all required cleanup ACKs arrived.
    pub cleanup_confirmed:bool,
}
impl std::fmt::Display for OpenError {
    fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result {f.write_str(&self.message)}
}
impl std::error::Error for OpenError {}
impl Kind {
    pub fn stream_type(self)->i64 {match self {Self::Speech=>100,Self::Navigation=>101}}
    pub fn rate(self)->u32 {match self {Self::Speech=>24000,Self::Navigation=>48000}}
    pub fn spf(self)->usize {match self {Self::Speech=>480,Self::Navigation=>960}}
    pub fn label(self)->&'static str {match self {Self::Speech=>"speechRecognition",Self::Navigation=>"default"}}
    fn format(self)->i64 {match self {Self::Speech=>1<<29,Self::Navigation=>1<<30}}
}
pub fn supports(info:&Value,kind:Kind)->bool {
    info.get("audioFormats").and_then(Value::as_array).into_iter().flatten().any(|f|
        f.get("type").and_then(Value::as_int)==Some(kind.stream_type()) &&
        f.get("audioType").and_then(Value::as_str)==Some(kind.label()) &&
        f.get("audioOutputFormats").and_then(Value::as_int).is_some_and(|bits|bits>=0 && bits & kind.format()!=0))
}
pub async fn open(control:&Arc<RtspChannel>,factory:&Arc<dyn SocketProvider>,params:&AirplayParams,shared:&[u8;32],info:&Value,kind:Kind)->Result<AudioOut,OpenError> {
    open_at(control,factory,&params.car_ip,params.airplay_port,shared,info,kind).await
}
// xiap2 parseAudioFormats reads outputLatencyMicros (0x20254); its SETUP writes
// audioLatencyMs (0x41960). Match the receiver's stream/audio type, not a car model.
fn latency_ms(info:&Value,kind:Kind)->i64 {
    info.get("audioLatencies").and_then(Value::as_array).into_iter().flatten().find_map(|v| {
        let typ=v.get("type").or_else(||v.get("streamType")).and_then(Value::as_int);
        if typ!=Some(kind.stream_type()) || v.get("audioType").and_then(Value::as_str)!=Some(kind.label()){return None;}
        v.get("outputLatencyMicros").and_then(Value::as_int).filter(|n|*n>=0).map(|n|n/1000)
    }).unwrap_or(0)
}
fn setup(info:&Value,kind:Kind,id:i64)->Vec<u8> {
    bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
        ("type",Value::int(kind.stream_type())),("streamConnectionID",Value::int(id)),
        ("audioFormat",Value::int(kind.format())),("audioLatencyMs",Value::int(latency_ms(info,kind))),
        ("audioType",Value::str(kind.label())),
    ])]))]))
}
fn ack(reply:&RtspMessage)->Result<(),String> {
    crate::resource_trace::validate_ack("AUX_AUDIO",reply)
}
fn check_status(v:&Value)->Result<(),String>{
    match v.get("status"){None|Some(Value::Int(0))=>Ok(()),_=>Err("invalid/rejected stream business status".into())}
}
fn response(reply:&RtspMessage,kind:Kind,id:i64)->Result<(u16,Option<u16>),String> {
    ack(reply)?;
    let value=bplist::parse(&reply.body).map_err(|e|format!("invalid SETUP plist: {e:?}"))?;
    let stream=crate::screen_session::setup_stream::select(&value,kind.stream_type(),false)?;
    check_status(stream)?;
    for (field,expected) in [("streamConnectionID",id),("audioFormat",kind.format())] {
        if let Some(actual)=stream.get(field){if actual.as_int()!=Some(expected){return Err(format!("SETUP {field} mismatch"));}}
    }
    if let Some(actual)=stream.get("audioType"){if actual.as_str()!=Some(kind.label()){return Err("SETUP audioType mismatch".into());}}
    let data=stream.get("dataPort").and_then(Value::as_int).filter(|n|(1..=65535).contains(n)).ok_or("SETUP missing/invalid dataPort")? as u16;
    let control=if let Some(port)=stream.get("controlPort") {
        match port.as_int(){Some(0)=>None,Some(n) if (1..=65535).contains(&n)=>Some(n as u16),_=>return Err("SETUP invalid controlPort".into())}
    }else{None};
    Ok((data,control))
}
async fn duck(control:&Arc<RtspChannel>,enabled:bool)->Result<(),String> {
    // xiap2 0x41bc0/0x42188: values are command parameters, not RTSP headers.
    let body=bplist::encode(&Value::dict(vec![("type",Value::str(if enabled{"duckAudio"}else{"unduckAudio"})),
        ("params",Value::dict(vec![("durationMs",Value::real(100.0)),("volume",Value::real(if enabled{-12.0}else{0.2}))]))]));
    let reply=control.request("POST","/command",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(3)).await.map_err(|e|e.to_string())?;
    ack(&reply)
}
async fn open_at(control:&Arc<RtspChannel>,factory:&Arc<dyn SocketProvider>,host:&str,port:u16,shared:&[u8;32],info:&Value,kind:Kind)->Result<AudioOut,OpenError> {
    if !supports(info,kind){return Err(OpenError{message:format!("{} output format unsupported",kind.label()),cleanup_confirmed:true});}
    // Fail before muting the receiver or allocating its stream if local Opus is unavailable.
    let _preflight=crate::opus_encode::OpusEncoder::new(kind.rate(),kind.spf()).map_err(|e|OpenError{message:format!("Opus preflight: {e}"),cleanup_confirmed:true})?;
    if kind==Kind::Navigation {
        if let Err(error)=duck(control,true).await {
            let cleanup=duck(control,false).await;
            return Err(OpenError{message:format!("duck failed: {error}; unduck cleanup={cleanup:?}"),cleanup_confirmed:cleanup.is_ok()});
        }
    }
    let id=crate::airplay::random_positive_i64();
    let body=setup(info,kind,id);
    let result=async {
        let reply=control.request("SETUP",&crate::rtsp_uri::audio_uri(host,port),&body,"application/x-apple-binary-plist",&[],Duration::from_secs(8)).await.map_err(|e|e.to_string())?;
        let (data_port,control_port)=response(&reply,kind,id)?;
        control.udp_media.set(kind.stream_type(), true);
        let data=factory.open_udp(host,data_port).ok_or("cannot open audio data socket")?;
        let sync=match control_port {Some(port)=>Some(factory.open_udp(host,port).ok_or("cannot open audio control socket")?),None=>None};
        AudioOut::new_opus(data,sync,crate::airplay::stream_key(shared,id as u64),kind.rate(),kind.spf(),kind.stream_type() as u8).map_err(|e|e.to_string())
    }.await;
    match result {
        Ok(out)=>Ok(out),
        // Even a malformed 200 or lost reply may have allocated a receiver stream.
        Err(error)=>{let cleanup=close(control,kind).await;Err(OpenError{message:format!("{} SETUP failed: {error}; cleanup={cleanup:?}",kind.label()),cleanup_confirmed:cleanup.is_ok()})}
    }
}
pub async fn close(control:&Arc<RtspChannel>,kind:Kind)->Result<(),String> {
    // Caller drops/stops AudioOut first. Firmware restores duck before retiring AltAudio.
    let unduck=if kind==Kind::Navigation {duck(control,false).await}else{Ok(())};
    let body=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(kind.stream_type()))])]))]));
    control.udp_media.set(kind.stream_type(), false);
    let teardown=match control.request("TEARDOWN","/",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(3)).await {
        Ok(reply)=>ack(&reply),Err(error)=>Err(error.to_string()),
    };
    match (unduck,teardown){(Ok(()),Ok(()))=>Ok(()),(a,b)=>Err(format!("unduck={a:?}; teardown={b:?}"))}
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{net::{TcpListener,TcpStream,UdpSocket},io::{AsyncReadExt,AsyncWriteExt}};
    struct Factory {fail:bool}
    impl SocketProvider for Factory {
        fn open_tcp(&self,_:&str,_:u16,_:u32)->Option<TcpStream>{None}
        fn open_udp(&self,host:&str,port:u16)->Option<UdpSocket>{
            if self.fail{return None;}
            let s=std::net::UdpSocket::bind("127.0.0.1:0").ok()?;s.connect((host,port)).ok()?;s.set_nonblocking(true).ok()?;UdpSocket::from_std(s).ok()
        }
    }
    fn info(kind:Kind)->Value {Value::dict(vec![
        ("audioFormats",Value::array(vec![Value::dict(vec![("type",Value::int(kind.stream_type())),("audioType",Value::str(kind.label())),("audioOutputFormats",Value::int(kind.format()))])])),
        ("audioLatencies",Value::array(vec![Value::dict(vec![("type",Value::int(kind.stream_type())),("audioType",Value::str(kind.label())),("outputLatencyMicros",Value::int(98000))])]))])}
    async fn next(peer:&mut TcpStream,buf:&mut Vec<u8>)->RtspMessage {
        tokio::time::timeout(Duration::from_secs(2),async {loop {
            if let Some(m)=crate::rtsp::parse_rtsp_messages(buf).unwrap().into_iter().next(){return m;}
            let mut b=[0;4096];let n=peer.read(&mut b).await.unwrap();assert!(n>0);buf.extend_from_slice(&b[..n]);
        }}).await.unwrap()
    }
    async fn reply(peer:&mut TcpStream,m:&RtspMessage,value:Option<Value>){
        let body=value.map(|v|bplist::encode(&v)).unwrap_or_default();
        peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",m.cseq().unwrap(),body.len()).as_bytes()).await.unwrap();peer.write_all(&body).await.unwrap();
    }
    async fn channel()->(Arc<RtspChannel>,TcpStream){
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let stream=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(peer,_)=listener.accept().await.unwrap();let(r,w)=stream.into_split();let(c,_)=RtspChannel::spawn(r,w,None);(c,peer)
    }
    #[test] fn capability_requires_matching_output_type_audio_type_and_mask(){
        for k in [Kind::Speech,Kind::Navigation]{assert!(supports(&info(k),k));assert!(!supports(&info(k),if k==Kind::Speech{Kind::Navigation}else{Kind::Speech}));}
        assert!(!supports(&Value::dict(vec![]),Kind::Speech));
        for (typ,label,mask,key) in [(100,"media",1<<29,"audioOutputFormats"),(100,"speechRecognition",1<<28,"audioOutputFormats"),(100,"speechRecognition",1<<29,"audioInputFormats")] {
            let v=Value::dict(vec![("audioFormats",Value::array(vec![Value::dict(vec![("type",Value::int(typ)),("audioType",Value::str(label)),(key,Value::int(mask))])]))]);
            assert!(!supports(&v,Kind::Speech));
        }
    }
    #[test] fn setup_response_rejects_bad_business_type_echo_format_and_ports(){
        let make=|root_fields:Vec<(&str,Value)>,fields:Vec<(&str,Value)>| {
            let mut body=root_fields;body.push(("streams",Value::array(vec![Value::dict(fields)])));
            let mut wire=format!("RTSP/1.0 200 OK\r\nContent-Length: {}\r\n\r\n",bplist::encode(&Value::dict(body.clone())).len()).into_bytes();
            wire.extend(bplist::encode(&Value::dict(body)));crate::rtsp::parse_rtsp_messages(&mut wire).unwrap().remove(0)
        };
        let valid=||vec![("type",Value::int(100)),("dataPort",Value::int(5555))];
        assert_eq!(response(&make(vec![],valid()),Kind::Speech,42).unwrap(),(5555,None));
        for status in [Value::int(1),Value::str("0"),Value::bool(false)] {assert!(response(&make(vec![("status",status)],valid()),Kind::Speech,42).is_err());}
        for (field,bad) in [("type",Value::int(101)),("streamConnectionID",Value::int(43)),("streamConnectionID",Value::str("42")),("audioFormat",Value::int(1<<30)),("audioType",Value::str("default")),("dataPort",Value::int(0)),("dataPort",Value::int(65536)),("controlPort",Value::str("5555")),("status",Value::int(2))] {
            let mut fields=valid();fields.retain(|(key,_)|*key!=field);fields.push((field,bad));
            assert!(response(&make(vec![],fields),Kind::Speech,42).is_err(),"must reject {field}");
        }
    }
    #[test] fn receiver_latency_matches_type_and_output_and_converts_microseconds(){
        let entry=|typ,label,field,n|Value::dict(vec![("type",Value::int(typ)),("audioType",Value::str(label)),(field,Value::int(n))]);
        let info=Value::dict(vec![("audioLatencies",Value::array(vec![
            entry(101,"default","outputLatencyMicros",98000),entry(100,"speechRecognition","inputLatencyMicros",98000),
            entry(100,"speechRecognition","outputLatencyMicros",72555)]))]);
        assert_eq!(latency_ms(&info,Kind::Speech),72);assert_eq!(latency_ms(&info,Kind::Navigation),98);
        assert_eq!(latency_ms(&Value::dict(vec![]),Kind::Speech),0);
    }
    #[tokio::test] async fn rejected_duck_is_undone_without_setup_and_close_still_tears_down_on_unduck_error(){
        let(c,mut peer)=channel().await;let cc=c.clone();let mut buf=Vec::new();
        let task=tokio::spawn(async move{let f:Arc<dyn SocketProvider>=Arc::new(Factory{fail:false});open_at(&cc,&f,"127.0.0.1",7000,&[1;32],&info(Kind::Navigation),Kind::Navigation).await});
        let m=next(&mut peer,&mut buf).await;reply(&mut peer,&m,Some(Value::dict(vec![("status",Value::int(1))]))).await;
        let m=next(&mut peer,&mut buf).await;assert_eq!(bplist::parse(&m.body).unwrap().get("type").and_then(Value::as_str),Some("unduckAudio"));reply(&mut peer,&m,None).await;
        assert!(task.await.unwrap().err().unwrap().cleanup_confirmed);
        let task=tokio::spawn(async move{close(&c,Kind::Navigation).await});
        let m=next(&mut peer,&mut buf).await;reply(&mut peer,&m,Some(Value::dict(vec![("status",Value::int(1))]))).await;
        let m=next(&mut peer,&mut buf).await;assert!(m.first_line.starts_with("TEARDOWN / "));reply(&mut peer,&m,None).await;
        assert!(task.await.unwrap().is_err());
    }
    #[tokio::test] async fn firmware_setup_duck_lifecycle_and_accepted_failure_cleanup(){
        for (kind,fail,bad_echo,cleanup_failure) in [(Kind::Speech,false,false,false),(Kind::Navigation,false,false,false),(Kind::Navigation,true,false,false),(Kind::Speech,false,true,false),(Kind::Speech,true,false,true)] {
            let(c,mut peer)=channel().await;let task_c=c.clone();
            let factory:Arc<dyn SocketProvider>=Arc::new(Factory{fail});
            let mut task=Some(tokio::spawn(async move{open_at(&task_c,&factory,"127.0.0.1",7000,&[7;32],&info(kind),kind).await}));
            let mut buf=Vec::new();
            if kind==Kind::Navigation {
                let m=next(&mut peer,&mut buf).await;assert!(m.first_line.starts_with("POST /command "));
                let v=bplist::parse(&m.body).unwrap();assert_eq!(v.get("type").and_then(Value::as_str),Some("duckAudio"));reply(&mut peer,&m,None).await;
            }
            let m=next(&mut peer,&mut buf).await;assert!(m.first_line.starts_with("SETUP rtsp://127.0.0.1:7000/ "));
            let v=bplist::parse(&m.body).unwrap();let s=&v.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(s.get("type").and_then(Value::as_int),Some(kind.stream_type()));assert_eq!(s.get("audioLatencyMs").and_then(Value::as_int),Some(98));
            assert_eq!(s.get("audioType").and_then(Value::as_str),Some(kind.label()));
            for absent in ["spf","sr","ct"] {assert!(s.get(absent).is_none());}
            let mut fields=vec![("type",Value::int(kind.stream_type())),("dataPort",Value::int(5555))];
            if bad_echo{fields.push(("streamConnectionID",Value::int(-1)));}
            reply(&mut peer,&m,Some(Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]))).await;
            let close_task=if fail||bad_echo {None}else {
                let out=task.take().unwrap().await.unwrap().unwrap();drop(out);let cc=c.clone();Some(tokio::spawn(async move{close(&cc,kind).await}))
            };
            if kind==Kind::Navigation {let m=next(&mut peer,&mut buf).await;let v=bplist::parse(&m.body).unwrap();assert_eq!(v.get("type").and_then(Value::as_str),Some("unduckAudio"));reply(&mut peer,&m,None).await;}
            let m=next(&mut peer,&mut buf).await;assert!(m.first_line.starts_with("TEARDOWN / "));reply(&mut peer,&m,cleanup_failure.then(||Value::dict(vec![("status",Value::int(1))]))).await;
            if let Some(t)=close_task {t.await.unwrap().unwrap();} else {assert_eq!(task.take().unwrap().await.unwrap().err().unwrap().cleanup_confirmed,!cleanup_failure);}
        }
    }
}
