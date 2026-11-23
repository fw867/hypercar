//! ACK-committed temporary speech ownership; navigation only updates its app state.
use std::sync::{Arc,atomic::AtomicBool};
#[cfg(test)]
use std::sync::atomic::Ordering;
use parking_lot::Mutex;
use crate::{bplist,modes::{AuxTransition,Modes},rtsp::RtspChannel};
pub use crate::modes::{SpeechLease,NavigationLease};

pub struct SpeechResource {
    pub control:Arc<RtspChannel>,
    pub modes:Arc<Mutex<Modes>>,
    pub owned:Arc<AtomicBool>,
    serial:Arc<tokio::sync::Mutex<()>>,
}
impl SpeechResource {
    pub fn new(control:Arc<RtspChannel>,modes:Arc<Mutex<Modes>>,owned:Arc<AtomicBool>)->Self {
        Self{control,modes,owned,serial:Arc::new(tokio::sync::Mutex::new(()))}
    }
    pub fn serial(&self)->Arc<tokio::sync::Mutex<()>> {self.serial.clone()}
    pub fn speech_valid(&self,lease:&SpeechLease)->bool {lease.valid(&self.modes.lock())}
    pub fn navigation_valid(&self,lease:&NavigationLease)->bool {lease.valid(&self.modes.lock())}
    pub async fn begin_speech(&self,user_initiated:bool)->Option<SpeechLease> {
        let _serial=self.serial.lock().await;
        let (proposal,lease)=self.modes.lock().speech_begin(user_initiated)?;
        if self.publish(proposal).await {Some(lease)} else {None}
    }
    pub async fn end_speech(&self,lease:SpeechLease)->bool {
        let _serial=self.serial.lock().await;
        let proposal=self.modes.lock().speech_end(&lease);
        match proposal {
            Some(proposal)=>self.publish(proposal).await || self.modes.lock().speech_end(&lease).is_none(),
            None=>true,
        }
    }
    pub async fn begin_navigation(&self)->Option<NavigationLease> {
        let _serial=self.serial.lock().await;
        let (proposal,lease)=self.modes.lock().navigation_begin()?;
        if self.publish(proposal).await {Some(lease)} else {None}
    }
    pub async fn end_navigation(&self,lease:NavigationLease)->bool {
        let _serial=self.serial.lock().await;
        let proposal=self.modes.lock().navigation_end(&lease);
        match proposal {
            Some(proposal)=>self.publish(proposal).await || self.modes.lock().navigation_end(&lease).is_none(),
            None=>true,
        }
    }
    async fn publish(&self,proposal:AuxTransition)->bool {
        let result=self.control.request("POST","/command",&proposal.declaration(),
            "application/x-apple-binary-plist",&[],std::time::Duration::from_secs(3)).await;
        let accepted=result.map(|reply|response_accepted(reply.status(),&reply.body)).unwrap_or(false);
        if !accepted {crate::emit_log("SPEECH-RESOURCE: declaration rejected/timed out; gate unchanged");return false;}
        let mut modes=self.modes.lock();
        let committed=modes.commit_aux(&proposal);
        self.control.buffered_ownership.publish_with_gate(&self.owned, modes.audio_owned());
        crate::emit_log(&format!("SPEECH-RESOURCE: acknowledged committed={committed}; receiver audibility unconfirmed"));
        committed
    }
}
fn response_accepted(status:u16,body:&[u8])->bool {
    if status!=200 {return false;}
    if body.is_empty() {return true;}
    let Ok(value)=bplist::parse(body) else {return false;};
    value.as_dict().is_some() && match value.get("status") {None=>true,Some(status)=>status.as_int()==Some(0)}
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bplist::Value;
    use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream}};
    async fn request(peer:&mut TcpStream)->crate::rtsp::RtspMessage {
        let mut bytes=Vec::new();
        tokio::time::timeout(std::time::Duration::from_secs(1),async {
            loop {let mut chunk=[0;4096];let n=peer.read(&mut chunk).await.unwrap();assert!(n>0);
                bytes.extend_from_slice(&chunk[..n]);
                if let Some(message)=crate::rtsp::parse_rtsp_messages(&mut bytes).unwrap().into_iter().next(){return message;}
            }
        }).await.unwrap()
    }
    async fn ack(peer:&mut TcpStream,message:&crate::rtsp::RtspMessage,status:u16) {
        let header=format!("RTSP/1.0 {status} Response\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",message.cseq().unwrap());
        peer.write_all(header.as_bytes()).await.unwrap();
    }
    #[derive(Clone)]
    enum TestLease {Speech(SpeechLease),Navigation(NavigationLease)}
    impl TestLease {
        async fn end(self,resource:&SpeechResource)->bool {match self {
            Self::Speech(lease)=>resource.end_speech(lease).await,
            Self::Navigation(lease)=>resource.end_navigation(lease).await,
        }}
        fn valid(&self,resource:&SpeechResource)->bool {match self {
            Self::Speech(lease)=>resource.speech_valid(lease),Self::Navigation(lease)=>resource.navigation_valid(lease),
        }}
    }
    #[tokio::test]
    async fn unrelated_screen_update_merges_after_ack_and_rejected_end_is_retryable() {
        for navigation in [false,true] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
            let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
            let resource=Arc::new(SpeechResource::new(control,Arc::new(Mutex::new(Modes::box_initial())),Arc::new(AtomicBool::new(false))));
            let worker=resource.clone();let begin=tokio::spawn(async move {
                if navigation {worker.begin_navigation().await.map(TestLease::Navigation)}else{worker.begin_speech(false).await.map(TestLease::Speech)}
            });
            let incoming=request(&mut peer).await;
            assert!(resource.modes.lock().change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
                ("resourceID",Value::int(1)),("transferType",Value::int(1)),("transferPriority",Value::int(500)),
                ("takeConstraint",Value::int(1000)),("borrowConstraint",Value::int(1000)),
            ])]))])));
            ack(&mut peer,&incoming,200).await;let lease=begin.await.unwrap().unwrap();
            assert!(lease.valid(&resource));assert!(!resource.modes.lock().screen_owned());
            let worker=resource.clone();let retry=lease.clone();let end=tokio::spawn(async move {retry.end(&worker).await});
            let incoming=request(&mut peer).await;ack(&mut peer,&incoming,403).await;
            assert!(!end.await.unwrap(),"refused cleanup must retain a retryable lease");assert!(lease.valid(&resource));
            let worker=resource.clone();let retry=lease.clone();let end=tokio::spawn(async move {retry.end(&worker).await});
            let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;
            assert!(end.await.unwrap());assert!(!lease.valid(&resource));assert!(!resource.modes.lock().screen_owned());
            // Cleanup was already completed; callers may safely retire their retained lease.
            assert!(lease.end(&resource).await);
        }
    }
    #[tokio::test]
    async fn concurrent_navigation_waits_and_declares_committed_speech_state() {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
        let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let resource=Arc::new(SpeechResource::new(control,Arc::new(Mutex::new(Modes::box_initial())),Arc::new(AtomicBool::new(false))));
        let worker=resource.clone();let speech=tokio::spawn(async move {worker.begin_speech(false).await});
        let first=request(&mut peer).await;
        let worker=resource.clone();let navigation=tokio::spawn(async move {worker.begin_navigation().await});
        let mut byte=[0];
        assert!(tokio::time::timeout(std::time::Duration::from_millis(25),peer.read(&mut byte)).await.is_err());
        ack(&mut peer,&first,200).await;let speech=speech.await.unwrap().unwrap();
        let second=request(&mut peer).await;let declaration=bplist::parse(&second.body).unwrap();
        let apps=declaration.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();
        assert_eq!(apps[0].get("entity").and_then(Value::as_int),Some(1));
        assert_eq!(apps[0].get("speechMode").and_then(Value::as_int),Some(1));
        assert_eq!(apps[2].get("entity").and_then(Value::as_int),Some(1));
        ack(&mut peer,&second,200).await;let navigation=navigation.await.unwrap().unwrap();
        assert!(resource.speech_valid(&speech));assert!(resource.navigation_valid(&navigation));
    }
    #[tokio::test]
    async fn speech_end_waits_for_ack_and_cannot_restore_over_receiver_take() {
        for retake in [false,true] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
            let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
            let modes=Arc::new(Mutex::new(Modes::box_initial()));let owned=Arc::new(AtomicBool::new(false));
            let resource=Arc::new(SpeechResource::new(control,modes.clone(),owned.clone()));
            let worker=resource.clone();let task=tokio::spawn(async move {worker.begin_speech(false).await});
            let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;
            let lease=task.await.unwrap().unwrap();assert!(resource.speech_valid(&lease));assert!(owned.load(Ordering::Acquire));
            let worker=resource.clone();let task=tokio::spawn(async move {worker.end_speech(lease).await});
            let incoming=request(&mut peer).await;
            assert!(owned.load(Ordering::Acquire),"unacknowledged end must not change the gate");
            if retake {assert!(modes.lock().change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
                ("resourceID",Value::int(2)),("transferType",Value::int(1)),("transferPriority",Value::int(500)),
                ("takeConstraint",Value::int(1000)),("borrowConstraint",Value::int(1000)),
            ])]))])));}
            ack(&mut peer,&incoming,200).await;task.await.unwrap();
            assert!(!owned.load(Ordering::Acquire));assert!(!modes.lock().audio_owned());
            let speech=modes.lock().snapshot().get("appStates").unwrap().as_array().unwrap()[0].clone();
            assert_eq!(speech.get("entity").and_then(Value::as_int),Some(if retake {1}else{0}));
        }
    }
    #[tokio::test]
    async fn end_after_receiver_audio_take_clears_only_our_speech_and_allows_next_reply() {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
        let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let modes=Arc::new(Mutex::new(Modes::box_initial()));
        let resource=Arc::new(SpeechResource::new(control,modes.clone(),Arc::new(AtomicBool::new(false))));
        let worker=resource.clone();let task=tokio::spawn(async move {worker.begin_speech(false).await});
        let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;let lease=task.await.unwrap().unwrap();
        assert!(modes.lock().change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
            ("resourceID",Value::int(2)),("transferType",Value::int(1)),("transferPriority",Value::int(500)),
            ("takeConstraint",Value::int(1000)),("borrowConstraint",Value::int(100)),
        ])]))])));assert!(!resource.speech_valid(&lease));
        let worker=resource.clone();let task=tokio::spawn(async move {worker.end_speech(lease).await});
        let incoming=request(&mut peer).await;let declaration=bplist::parse(&incoming.body).unwrap();
        let params=declaration.get("params").unwrap();
        assert_eq!(params.get("resources").unwrap().as_array().unwrap()[1].get("entity").and_then(Value::as_int),Some(2));
        assert_eq!(params.get("appStates").unwrap().as_array().unwrap()[0].get("entity").and_then(Value::as_int),Some(0));
        ack(&mut peer,&incoming,200).await;task.await.unwrap();assert!(!modes.lock().audio_owned());
        let worker=resource.clone();let task=tokio::spawn(async move {worker.begin_speech(false).await});
        let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;
        assert!(resource.speech_valid(&task.await.unwrap().unwrap()));
    }
    #[tokio::test]
    async fn speech_and_navigation_wait_for_business_ack_and_receiver_epochs() {
        for navigation in [false,true] {for (status,retake,business) in [(200,false,0),(403,false,0),(200,false,1),(200,true,0)] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
            let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
            let modes=Arc::new(Mutex::new(Modes::box_initial()));let owned=Arc::new(AtomicBool::new(false));
            let resource=Arc::new(SpeechResource::new(control,modes.clone(),owned.clone()));
            let worker=resource.clone();let task=tokio::spawn(async move {
                if navigation {worker.begin_navigation().await.map(|_|())} else {worker.begin_speech(false).await.map(|_|())}
            });
            let incoming=request(&mut peer).await;
            assert!(!owned.load(Ordering::Acquire));assert!(!modes.lock().audio_owned());
            let declaration=bplist::parse(&incoming.body).unwrap();assert_eq!(declaration.get("type").and_then(Value::as_str),Some("modesChanged"));
            if retake {assert!(modes.lock().change(&Value::dict(vec![("appStates",Value::array(vec![Value::dict(vec![
                ("appStateID",Value::int(if navigation{3}else{1})),
                (if navigation{"state"}else{"speechMode"},if navigation{Value::Bool(true)}else{Value::int(2)}),
            ])]))])));}
            let body=bplist::encode(&Value::dict(vec![("status",Value::int(business))]));
            let header=format!("RTSP/1.0 {status} Response\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",incoming.cseq().unwrap(),body.len());
            peer.write_all(header.as_bytes()).await.unwrap();peer.write_all(&body).await.unwrap();
            let allowed=status==200 && business==0 && !retake;
            assert_eq!(task.await.unwrap().is_some(),allowed);
            assert_eq!(owned.load(Ordering::Acquire),allowed && !navigation);
            assert_eq!(modes.lock().audio_owned(),allowed && !navigation);
        }}
    }
    #[test] fn malformed_and_refused_business_replies_cannot_commit() {
        assert!(response_accepted(200,&[]));assert!(!response_accepted(500,&[]));
        assert!(!response_accepted(200,b"not a plist"));
        assert!(!response_accepted(200,&bplist::encode(&Value::dict(vec![("status",Value::str("0"))]))));
    }
}
