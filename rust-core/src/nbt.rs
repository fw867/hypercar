//! NBT Evo's success-only activation latch is scoped to one RTSP connection.
use std::sync::Arc;
use crate::rtsp::RtspChannel;
pub(crate) async fn record_once(channel: &Arc<RtspChannel>) -> Result<(),String> {
    let mut recorded=channel.nbt_recorded.lock().await;
    if *recorded { return Ok(()); }
    crate::airplay::record_session(channel).await?;
    *recorded=true;
    Ok(())
}
pub(crate) fn matches(info: &crate::bplist::Value) -> bool {
    ["name","model","manufacturer"].iter().filter_map(|key| info.get(key).and_then(crate::bplist::Value::as_str))
        .any(|value| { let value=value.to_ascii_lowercase(); value.contains("nbtevo") || value.contains("nbt-evo") })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{bplist::Value,rtsp::{RtspMessage,parse_rtsp_messages}};
    use tokio::{net::{TcpListener,TcpStream},io::{AsyncReadExt,AsyncWriteExt}};
    use std::time::Duration;
    async fn pair()->(Arc<RtspChannel>,TcpStream) {
        let l=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(l.local_addr().unwrap()).await.unwrap();
        let (car,_)=l.accept().await.unwrap();let(r,w)=socket.into_split();
        (RtspChannel::spawn(r,w,None).0,car)
    }
    async fn request(car:&mut TcpStream)->RtspMessage {
        tokio::time::timeout(Duration::from_secs(2),async {
            let mut buffer=Vec::new();let mut chunk=[0;4096];
            loop {let n=car.read(&mut chunk).await.unwrap();assert!(n>0);buffer.extend_from_slice(&chunk[..n]);
                if let Some(m)=parse_rtsp_messages(&mut buffer).unwrap().pop(){return m;}}
        }).await.unwrap()
    }
    #[test] fn identity_rule_excludes_mgu_and_idc23() {
        for model in ["NBTEvo","BMW NBT-EVO"] {assert!(matches(&Value::dict(vec![("model",Value::str(model))])));}
        for model in ["MGU-55","IDC23","NBT","WinPlay"] {assert!(!matches(&Value::dict(vec![("model",Value::str(model))])));}
    }
    #[tokio::test] async fn concurrent_activation_sends_one_record_and_new_channel_gets_fresh_budget() {
        for _ in 0..2 {
            let (channel,mut car)=pair().await;
            let c=channel.clone();let a=tokio::spawn(async move{record_once(&c).await});
            let c=channel.clone();let b=tokio::spawn(async move{record_once(&c).await});
            let m=request(&mut car).await;assert_eq!(m.first_line,"RECORD / RTSP/1.0");
            car.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",m.cseq().unwrap()).as_bytes()).await.unwrap();
            a.await.unwrap().unwrap();b.await.unwrap().unwrap();
            let mut byte=[0];assert!(tokio::time::timeout(Duration::from_millis(30),car.read(&mut byte)).await.is_err());
        }
    }
    #[tokio::test] async fn rejected_record_is_never_latched_as_success() {
        let (channel,mut car)=pair().await;
        for rejected in [true,false] {
            let c=channel.clone();let a=tokio::spawn(async move{record_once(&c).await});
            let m=request(&mut car).await;
            let body=if rejected {crate::bplist::encode(&Value::dict(vec![("status",Value::int(-1))]))}else{vec![]};
            car.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",m.cseq().unwrap(),body.len()).as_bytes()).await.unwrap();
            car.write_all(&body).await.unwrap();assert_eq!(a.await.unwrap().is_err(),rejected);
        }
    }
}
