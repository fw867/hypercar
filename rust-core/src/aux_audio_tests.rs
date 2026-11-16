//! Loopback receiver tests exercising Controller, resource ACKs and real Opus UDP.
use super::{Controller,Input,MainCommand,Session};
use crate::{airplay::{AirplayParams,AudioCodec,SocketProvider},aux_setup::Kind,bplist::{self,Value},modes::Modes,rtsp::{RtspChannel,RtspMessage},speech_resource::SpeechResource};
use std::{sync::{Arc,atomic::AtomicBool},time::Duration};
use parking_lot::Mutex;
use tokio::{net::{TcpListener,TcpStream,UdpSocket},sync::mpsc,io::{AsyncReadExt,AsyncWriteExt}};

struct Factory;
impl SocketProvider for Factory {
    fn open_tcp(&self,_:&str,_:u16,_:u32)->Option<TcpStream>{None}
    fn open_udp(&self,host:&str,port:u16)->Option<UdpSocket>{
        let socket=std::net::UdpSocket::bind("127.0.0.1:0").ok()?;
        socket.connect((host,port)).ok()?;socket.set_nonblocking(true).ok()?;UdpSocket::from_std(socket).ok()
    }
}
struct Fixture {
    controller:Controller,input:Arc<Input>,peer:TcpStream,data:UdpSocket,sync:UdpSocket,
    commands:mpsc::UnboundedReceiver<&'static str>,fallback:mpsc::UnboundedReceiver<crate::engine::SourceEvent>,
    command_task:tokio::task::JoinHandle<()>,buffer:Vec<u8>,order:Vec<String>,
}
impl Drop for Fixture {fn drop(&mut self){self.command_task.abort();}}
impl Fixture {
    async fn new()->Self {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(peer,_)=listener.accept().await.unwrap();
        let(r,w)=stream.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let modes=Arc::new(Mutex::new(Modes::initial()));
        let resource=Arc::new(SpeechResource::new(control.clone(),modes,Arc::new(AtomicBool::new(true))));
        let params=AirplayParams {
                wifi_mac_address:None,
            car_ip:"127.0.0.1".into(),airplay_port:7000,device_id:"test".into(),device_name:"Test phone".into(),identity_path:"unused".into(),
            receiver_id:None,receiver_previously_connected:true,enable_audio:true,audio_codec:"pcm".into(),video_codec:String::new(),bmw_capabilities:true,microphone_probe:None,keep_alive_stats:false,
            bmw_box_bootstrap:true,mbux_screen_bootstrap:false,id9_screen_bootstrap:false,id9_disable_buffered_audio:false,nbt_audio_first:false,mbux_media_compat:false,desktop_receiver:false,
            width:1920,height:720,touch_display:None,touch_rect:None,encoder_size:Arc::new(Mutex::new((1920,720))),touch_size:Arc::new(Mutex::new((1920,720))),
            carwith_display:None,carwith_stretch:false,screen_resource_lifecycle:true,pipeline:Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
        };
        let info=Value::dict(vec![("audioFormats",Value::array(vec![
            Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("speechRecognition")),("audioOutputFormats",Value::int(1<<29))]),
            Value::dict(vec![("type",Value::int(101)),("audioType",Value::str("default")),("audioOutputFormats",Value::int(1<<30))]),
            Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("media")),("audioOutputFormats",Value::int(1<<15))]),
        ]))]);
        let session=Session{control,factory:Arc::new(Factory),params,shared:[9;32],info,resource,media_codec:Some(AudioCodec::Pcm)};
        let input=Arc::new(Input::default());let(command_tx,mut command_rx)=mpsc::unbounded_channel();
        let(event_tx,commands)=mpsc::unbounded_channel();let(fallback_tx,fallback)=mpsc::unbounded_channel();
        let command_task=tokio::spawn(async move {while let Some(command)=command_rx.recv().await {
            match command {
                MainCommand::Pause(reply)=>{event_tx.send("pause").unwrap();let _=reply.send(());}
                MainCommand::Install(out)=>{assert!(out.is_some(),"working PCM media must be restored");event_tx.send("install").unwrap();drop(out);}
            }
        }});
        let controller=Controller::spawn(session,input.clone(),command_tx,fallback_tx);
        Self{controller,input,peer,data:UdpSocket::bind("127.0.0.1:0").await.unwrap(),sync:UdpSocket::bind("127.0.0.1:0").await.unwrap(),commands,fallback,command_task,buffer:Vec::new(),order:Vec::new()}
    }
    async fn request(&mut self,expected:&str)->(RtspMessage,Value){
        let message=tokio::time::timeout(Duration::from_secs(3),async {loop {
            if let Some(m)=crate::rtsp::parse_rtsp_messages(&mut self.buffer).unwrap().into_iter().next(){return m;}
            let mut bytes=[0;4096];let n=self.peer.read(&mut bytes).await.unwrap();assert!(n>0);self.buffer.extend_from_slice(&bytes[..n]);
        }}).await.unwrap();
        let value=bplist::parse(&message.body).unwrap();
        let actual=if message.first_line.starts_with("POST /command ") {value.get("type").and_then(Value::as_str).unwrap().to_string()} else {
            let stream=&value.get("streams").unwrap().as_array().unwrap()[0];
            if message.first_line.starts_with("SETUP "){format!("setup:{}:{}",stream.get("type").unwrap().as_int().unwrap(),stream.get("audioType").unwrap().as_str().unwrap())}
            else {format!("teardown:{}",stream.get("type").unwrap().as_int().unwrap())}
        };
        assert_eq!(actual,expected,"unexpected request {}",message.first_line);self.order.push(actual);(message,value)
    }
    async fn ack(&mut self,message:&RtspMessage,body:Option<Value>){
        let bytes=body.map(|v|bplist::encode(&v)).unwrap_or_default();
        self.peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",message.cseq().unwrap(),bytes.len()).as_bytes()).await.unwrap();self.peer.write_all(&bytes).await.unwrap();
    }
    async fn reject(&mut self,message:&RtspMessage,status:u16){
        self.peer.write_all(format!("RTSP/1.0 {status} Rejected\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",message.cseq().unwrap()).as_bytes()).await.unwrap();
    }
    async fn setup_reply(&mut self,message:&RtspMessage,value:&Value){
        let request=&value.get("streams").unwrap().as_array().unwrap()[0];
        let mut fields=vec![("type",request.get("type").unwrap().clone()),("dataPort",Value::int(self.data.local_addr().unwrap().port() as i64)),("controlPort",Value::int(self.sync.local_addr().unwrap().port() as i64))];
        for field in ["streamConnectionID","audioFormat","audioType"]{if let Some(value)=request.get(field){fields.push((field,value.clone()));}}
        self.ack(message,Some(Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]))).await;
    }
    fn source(&self,kind:Kind){
        self.input.init(kind,16000,1);
        assert_eq!(self.input.push(kind,16000,1,&4000i16.to_le_bytes().repeat(1600)),super::Route::Independent);
        self.input.stop(kind);
    }
    async fn opus_packet(&self,typ:u8){
        let mut bytes=[0;2048];let n=tokio::time::timeout(Duration::from_secs(2),self.data.recv(&mut bytes)).await.unwrap().unwrap();
        assert!(n>36,"encrypted Opus packet must have payload/tag/counter");assert_eq!(bytes[0],0x80);assert_eq!(bytes[1]&127,typ);
    }
    async fn stopped(&mut self){
        assert!(self.controller.shutdown().await.is_empty());assert!(self.fallback.try_recv().is_err());
    }
}

async fn speech_handoff(fail_first_restore:bool){
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,v)=f.request("modesChanged").await;
    let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();assert_eq!(apps[0].get("speechMode").and_then(Value::as_int),Some(1));
    f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:speechRecognition").await;f.setup_reply(&m,&v).await;
    f.opus_packet(100).await;
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,v)=f.request("modesChanged").await;let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();
    assert_eq!(apps[0].get("entity").and_then(Value::as_int),Some(0));assert_eq!(apps[0].get("speechMode").and_then(Value::as_int),Some(-1));f.ack(&m,None).await;
    if fail_first_restore {
        let(m,_)=f.request("setup:100:media").await;
        f.peer.write_all(format!("RTSP/1.0 400 Rejected\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",m.cseq().unwrap()).as_bytes()).await.unwrap();
    }
    let(m,v)=f.request("setup:100:media").await;assert_eq!(v.get("streams").unwrap().as_array().unwrap()[0].get("audioFormat").and_then(Value::as_int),Some(1<<15));
    assert!(f.commands.try_recv().is_err(),"media install must wait for SETUP ACK");f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    f.stopped().await;
    let mut expected=vec!["modesChanged","teardown:100","setup:100:speechRecognition","teardown:100","modesChanged","setup:100:media"];
    if fail_first_restore {expected.push("setup:100:media");}
    assert_eq!(f.order,expected);
}
#[tokio::test] async fn siri_main_stream_handoff_restores_pcm_only_after_speech_teardown_and_end_ack(){speech_handoff(false).await;}
#[tokio::test] async fn rejected_first_media_restore_retries_without_installing_an_empty_sender(){speech_handoff(true).await;}

#[tokio::test] async fn navigation_owns_only_alt_stream_and_never_pauses_or_replaces_media(){
    let mut f=Fixture::new().await;f.source(Kind::Navigation);
    let(m,v)=f.request("modesChanged").await;let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();assert_eq!(apps[2].get("entity").and_then(Value::as_int),Some(1));f.ack(&m,None).await;
    let(m,_)=f.request("duckAudio").await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:101:default").await;f.setup_reply(&m,&v).await;f.opus_packet(101).await;
    let(m,_)=f.request("unduckAudio").await;f.ack(&m,None).await;
    let(m,_)=f.request("teardown:101").await;f.ack(&m,None).await;
    let(m,v)=f.request("modesChanged").await;let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();assert_eq!(apps[2].get("entity").and_then(Value::as_int),Some(0));f.ack(&m,None).await;
    f.stopped().await;assert!(f.commands.try_recv().is_err());
    assert_eq!(f.order,["modesChanged","duckAudio","setup:101:default","unduckAudio","teardown:101","modesChanged"]);
}

#[tokio::test] async fn accepted_unusable_speech_setup_cleans_once_then_restores_media(){
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,_)=f.request("setup:100:speechRecognition").await;
    f.ack(&m,Some(Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(100)),("dataPort",Value::int(0))])]))]))).await;
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    // A second TEARDOWN would be a 404 on a real receiver: expect end modes now.
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:media").await;f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    assert!(matches!(tokio::time::timeout(Duration::from_secs(1),f.fallback.recv()).await.unwrap(),Some(crate::engine::SourceEvent::AuxFallback{kind:Kind::Speech,..})));
    f.stopped().await;
}

async fn completed_speech_before_end_ack()->Fixture {
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:speechRecognition").await;f.setup_reply(&m,&v).await;f.opus_packet(100).await;
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    f
}

#[tokio::test] async fn rejected_speech_end_retries_before_releasing_the_paused_media_sender(){
    let mut f=completed_speech_before_end_ack().await;
    let(m,v)=f.request("modesChanged").await;
    let first=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap()[0].clone();
    assert_eq!(first.get("entity").and_then(Value::as_int),Some(0));
    f.reject(&m,403).await;
    let(m,v)=f.request("modesChanged").await;
    assert!(f.commands.try_recv().is_err(),"end refusal must keep media paused");
    let retried=&v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap()[0];
    assert_eq!(retried,&first,"end retry must release speech, never republish Speaking");
    f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:media").await;f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    f.stopped().await;
    assert_eq!(f.order,["modesChanged","teardown:100","setup:100:speechRecognition","teardown:100","modesChanged","modesChanged","setup:100:media"]);
}

#[tokio::test] async fn exhausted_restore_retries_resume_on_fresh_media_restart_without_replaying_speech(){
    let mut f=completed_speech_before_end_ack().await;
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    let mut refusals=0;
    loop {
        // Allow all bounded automatic recovery attempts to finish, then prove that
        // a new source media event wakes the pending recovery independently.
        if refusals>=2 && tokio::time::timeout(Duration::from_millis(1600),f.peer.readable()).await.is_err(){break;}
        let(m,_)=f.request("setup:100:media").await;f.reject(&m,400).await;refusals+=1;
        assert!(refusals<=12,"media recovery must remain bounded");
        assert!(f.commands.try_recv().is_err(),"refused restore cannot install an empty sender");
    }
    assert!(refusals>=2);
    f.input.media_restart();
    let(m,v)=f.request("setup:100:media").await;f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    assert_eq!(f.order.iter().filter(|s|s.as_str()=="setup:100:speechRecognition").count(),1,"media restart cannot replay the completed speech epoch");
    assert_eq!(f.order.iter().filter(|s|s.as_str()=="modesChanged").count(),2,"media-only recovery cannot reclaim old speech resources");
    f.stopped().await;
}

#[tokio::test] async fn shutdown_during_unanswered_speech_setup_reports_main_stream_for_final_teardown(){
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(_unanswered,_)=f.request("setup:100:speechRecognition").await;
    let start=tokio::time::Instant::now();
    let pending=tokio::time::timeout(Duration::from_secs(5),f.controller.shutdown()).await.unwrap();
    assert_eq!(pending,vec![100],"in-flight receiver allocation must survive worker cancellation into final TEARDOWN");
    assert!(start.elapsed()>=Duration::from_secs(3));
    assert!(f.commands.try_recv().is_err(),"shutdown must not install a replacement media sender");
}
