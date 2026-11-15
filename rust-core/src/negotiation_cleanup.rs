//! Cleanup ownership exists only until a successfully negotiated session is handed off.
use std::sync::Arc;
use crate::rtsp::RtspChannel;

pub(crate) struct NegotiationCleanup { channel:Arc<RtspChannel>, enabled:bool, accepted:bool, transferred:bool }
impl NegotiationCleanup {
    pub fn new(channel:Arc<RtspChannel>,enabled:bool)->Self {Self{channel,enabled,accepted:false,transferred:false}}
    pub fn accepted(&mut self) { self.accepted=true; }
    pub fn transfer(&mut self) { self.transferred=true; }
    fn dispatch(&mut self)->Option<tokio::task::JoinHandle<()>> {
        if !self.enabled || self.transferred {return None;}
        self.transferred=true;
        let channel=self.channel.clone();let accepted=self.accepted;
        match tokio::runtime::Handle::try_current() {
            Ok(runtime)=>Some(runtime.spawn(async move {
                crate::emit_log(&format!("NEGOTIATION-CLOSE: accepted={accepted}; bounded session cleanup; no fabricated media streams"));
                // A parseable empty stream list tears down the session, including a SETUP accepted before parse failure.
                let body=crate::bplist::encode(&crate::bplist::Value::dict(vec![("streams",crate::bplist::Value::Array(vec![]))]));
                crate::airplay::finish_session(&channel,&body,accepted).await;
            })),
            Err(_)=>{channel.close();None}
        }
    }
    pub async fn finish(&mut self) {if let Some(task)=self.dispatch(){let _=task.await;}}
}
impl Drop for NegotiationCleanup {
    fn drop(&mut self) {let _=self.dispatch();}
}
#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{net::{TcpStream,TcpListener},io::{AsyncReadExt,AsyncWriteExt}};
    use std::time::Duration;
    async fn pair()->(Arc<RtspChannel>,TcpStream) {
        let l=TcpListener::bind("127.0.0.1:0").await.unwrap();let s=TcpStream::connect(l.local_addr().unwrap()).await.unwrap();
        let(car,_)=l.accept().await.unwrap();let(r,w)=s.into_split();(RtspChannel::spawn(r,w,None).0,car)
    }
    async fn read(car:&mut TcpStream)->crate::rtsp::RtspMessage {
        tokio::time::timeout(Duration::from_secs(2),async {
            let mut bytes=vec![];let mut buffer=[0;4096];
            loop {let n=car.read(&mut buffer).await.unwrap();assert!(n>0);bytes.extend_from_slice(&buffer[..n]);
                if let Some(message)=crate::rtsp::parse_rtsp_messages(&mut bytes).unwrap().pop(){return message;}}
        }).await.expect("accepted session must receive one global TEARDOWN")
    }
    #[tokio::test] async fn accepted_failure_sends_one_global_teardown_then_closes() {
        for absolute in [false,true] {
        let(c,mut car)=pair().await;let mut guard=NegotiationCleanup::new(c.clone(),true);guard.accepted();
        if absolute {c.set_session_uri(crate::rtsp_uri::SessionUri::new("fe80::1%wlan0",77).unwrap()).unwrap();}
        let task=tokio::spawn(async move{guard.finish().await;guard.finish().await;});
        let m=read(&mut car).await;assert_eq!(m.first_line,if absolute {"TEARDOWN rtsp://[fe80::1%25wlan0]/77 RTSP/1.0"}else{"TEARDOWN / RTSP/1.0"});assert_eq!(crate::bplist::parse(&m.body).unwrap().get("streams"), Some(&crate::bplist::Value::Array(vec![])));
        car.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",m.cseq().unwrap()).as_bytes()).await.unwrap();
        task.await.unwrap();assert!(c.is_closed());
        }
    }
    #[tokio::test] async fn caller_cancel_during_cleanup_does_not_abandon_the_old_socket() {
        let(c,mut car)=pair().await;let mut guard=NegotiationCleanup::new(c.clone(),true);guard.accepted();
        let task=tokio::spawn(async move{guard.finish().await;});
        let _=read(&mut car).await;task.abort();
        tokio::time::timeout(Duration::from_secs(2),async {while !c.is_closed(){tokio::task::yield_now().await;}}).await.unwrap();
    }
    #[tokio::test] async fn unaccepted_failure_never_invents_streams_and_success_hands_off() {
        let(c,mut car)=pair().await;let mut guard=NegotiationCleanup::new(c.clone(),true);guard.finish().await;
        assert!(c.is_closed());assert_eq!(car.read(&mut [0]).await.unwrap(),0);
        for enabled in [false,true] {
            let(c,mut car)=pair().await;let mut guard=NegotiationCleanup::new(c.clone(),enabled);guard.accepted();guard.transfer();drop(guard);
            assert!(!c.is_closed());assert!(tokio::time::timeout(Duration::from_millis(30),car.read(&mut [0])).await.is_err());
        }
    }
    #[tokio::test] async fn cancelled_negotiation_cleans_only_its_own_channel_with_bounded_silent_peer() {
        let(old,mut car)=pair().await;let(new,mut new_car)=pair().await;
        let mut guard=NegotiationCleanup::new(old.clone(),true);guard.accepted();drop(guard);
        let _=read(&mut car).await;
        tokio::time::timeout(Duration::from_secs(2),async {while !old.is_closed(){tokio::task::yield_now().await;}}).await.unwrap();
        assert!(!new.is_closed());assert!(tokio::time::timeout(Duration::from_millis(30),new_car.read(&mut [0])).await.is_err());
    }
}
