//! Read-only Linux/Android TCP telemetry. ACKs confirm the peer kernel, not its decoder/UI.
use std::os::fd::RawFd;

/// A borrowed descriptor sampled independently of pending writes. The owner must drop this
/// monitor BEFORE closing its socket. Clearing the descriptor under the same lock as capture
/// prevents the background task from observing a subsequently reused descriptor.
pub struct TcpMonitor {
    descriptor:std::sync::Arc<parking_lot::Mutex<Option<RawFd>>>,
    task:tokio::task::JoinHandle<()>,
}
impl TcpMonitor {
    pub fn start(fd:RawFd,connection_id:u64,pipeline:std::sync::Arc<parking_lot::Mutex<crate::pipeline::Pipeline>>)->Self {
        let descriptor=std::sync::Arc::new(parking_lot::Mutex::new(Some(fd)));
        let descriptor_task=descriptor.clone();
        let task=tokio::spawn(async move {
            let mut ticks=tokio::time::interval(std::time::Duration::from_secs(1));
            ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                ticks.tick().await;
                let sample={let d=descriptor_task.lock();d.map(|fd|TcpSnapshot::capture(fd,connection_id))};
                let Some(sample)=sample else {break;};
                let mut p=pipeline.lock();
                if p.video_tcp.as_ref().map(|s|s.connection_id)==Some(connection_id) {p.video_tcp=Some(sample);}
            }
        });
        Self {descriptor,task}
    }
}
impl Drop for TcpMonitor {
    fn drop(&mut self) {
        *self.descriptor.lock()=None;
        self.task.abort();
    }
}

#[derive(Clone,Debug,Default,serde::Serialize)]
pub struct TcpSnapshot {
    pub connection_id:u64,
    pub sampled_ms:u64,
    pub age_ms:u64,
    pub info_errno:Option<i32>,
    pub state:Option<u8>,
    pub rtt_us:Option<u32>,
    pub unacked_segments:Option<u32>,
    pub total_retrans:Option<u32>,
    pub acked_bytes:Option<u64>,
    pub notsent_bytes:Option<u32>,
    pub outq_bytes:Option<u32>,
}
impl TcpSnapshot {
    pub fn capture(fd:RawFd,connection_id:u64)->Self {
        let mut s=Self { connection_id,sampled_ms:crate::timing::monotonic_nanos()/1_000_000,..Self::default() };
        // Linux UAPI tcp_info has an append-only layout. Read only returned bytes: older kernels
        // may provide the original 104B prefix without bytes_acked/notsent_bytes.
        let mut bytes=[0u8;256];let mut len=bytes.len() as libc::socklen_t;
        let result=unsafe {libc::getsockopt(fd,libc::IPPROTO_TCP,libc::TCP_INFO,bytes.as_mut_ptr().cast(),&mut len)};
        if result==0 { s.read_info(&bytes[..(len as usize).min(bytes.len())]); }
        else { s.info_errno=std::io::Error::last_os_error().raw_os_error(); }
        let mut queued=0i32;
        // TIOCOUTQ is SIOCOUTQ on Linux; includes unsent and transmitted-but-unacknowledged data.
        if unsafe {libc::ioctl(fd,libc::TIOCOUTQ,&mut queued)}==0 && queued>=0 {s.outq_bytes=Some(queued as u32);}
        s
    }
    fn read_info(&mut self,b:&[u8]) {
        let u32_at=|i:usize|b.get(i..i+4).map(|v|u32::from_ne_bytes(v.try_into().unwrap()));
        self.state=b.first().copied();self.unacked_segments=u32_at(24);self.rtt_us=u32_at(68);
        self.total_retrans=u32_at(100);
        self.acked_bytes=b.get(120..128).map(|v|u64::from_ne_bytes(v.try_into().unwrap()));
        self.notsent_bytes=u32_at(144);
    }
    pub fn summary(&self)->String {
        format!("conn={} state={:?} rtt_us={:?} unacked_segments={:?} retrans_total={:?} acked_bytes={:?} notsent_bytes={:?} outq_bytes={:?} info_errno={:?}; peer_kernel_ACK_is_not_display_confirmation",
            self.connection_id,self.state,self.rtt_us,self.unacked_segments,self.total_retrans,self.acked_bytes,self.notsent_bytes,self.outq_bytes,self.info_errno)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;
    #[test]
    fn short_info_does_not_invent_extended_fields() {
        let mut s=TcpSnapshot::default();let mut b=[0u8;104];b[0]=1;
        b[24..28].copy_from_slice(&3u32.to_ne_bytes());b[68..72].copy_from_slice(&123u32.to_ne_bytes());
        s.read_info(&b);assert_eq!(s.unacked_segments,Some(3));assert_eq!(s.rtt_us,Some(123));
        assert_eq!(s.acked_bytes,None);assert_eq!(s.notsent_bytes,None);
        assert!(TcpSnapshot::capture(-1,9).state.is_none());
    }
    #[tokio::test]
    async fn kernel_ack_counter_advances_after_loopback_transfer() {
        use tokio::io::{AsyncReadExt,AsyncWriteExt};
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut phone=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car,_)=listener.accept().await.unwrap();let before=TcpSnapshot::capture(phone.as_raw_fd(),123);
        phone.write_all(&[7;4096]).await.unwrap();let mut body=[0;4096];car.read_exact(&mut body).await.unwrap();
        let after=tokio::time::timeout(std::time::Duration::from_secs(1),async {
            loop {let s=TcpSnapshot::capture(phone.as_raw_fd(),123);
                if s.acked_bytes.unwrap_or(0)>=before.acked_bytes.unwrap_or(0)+4096 {break s;}
                tokio::time::sleep(std::time::Duration::from_millis(10)).await;
            }
        }).await.unwrap();
        assert_eq!(after.state,Some(1));assert!(after.rtt_us.is_some());assert_eq!(after.notsent_bytes,Some(0));
    }
    #[tokio::test]
    async fn monitor_updates_while_video_write_is_blocked_and_stops_on_drop() {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (car,_)=listener.accept().await.unwrap();
        let buffer=65536i32;
        for (fd,option) in [(phone.as_raw_fd(),libc::SO_SNDBUF),(car.as_raw_fd(),libc::SO_RCVBUF)] {
            assert_eq!(unsafe {libc::setsockopt(fd,libc::SOL_SOCKET,option,(&buffer as *const i32).cast(),4)},0);
        }
        let (_,write)=phone.into_split();
        let mut sender=crate::screen::ScreenSender::new(write,crate::screen::ScreenCipher::ChaCha([1;32]),1920,720,false);
        let p=std::sync::Arc::new(parking_lot::Mutex::new(crate::pipeline::Pipeline::new()));
        sender.attach_diagnostics(p.clone(),987);
        sender.send_config(&crate::screen::build_avcc(&[0x67,0x42,0,0x1f],&[0x68,1])).await.unwrap();
        let initial=p.lock().video_tcp.as_ref().unwrap().sampled_ms;
        let mut nal=vec![2u8;8*1024*1024];nal[..5].copy_from_slice(&[0,0,0,1,0x65]);
        // Debug encryption of 8 MiB can itself take longer than one timer period on a host.
        // Wait for observed independent sampling/backlog, rather than timing only write_all.
        let writer=tokio::spawn(async move {sender.send_video_annexb(&nal,2000).await});
        let sample=tokio::time::timeout(std::time::Duration::from_secs(6),async {
            loop {
                let sample=p.lock().video_tcp.clone().unwrap();
                if sample.sampled_ms>=initial+900 && sample.outq_bytes.unwrap_or(0)>0 {break sample;}
                tokio::time::sleep(std::time::Duration::from_millis(25)).await;
            }
        }).await.unwrap();
        assert!(!writer.is_finished(),"non-reading peer must still block the video write");
        assert!(sample.sampled_ms>=initial+900,"periodic telemetry must run during a pending write");
        assert!(sample.outq_bytes.unwrap()>0,"car is not reading; kernel queue must show backlog");
        writer.abort();assert!(writer.await.unwrap_err().is_cancelled());
        let stopped=p.lock().video_tcp.as_ref().unwrap().sampled_ms;
        tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
        assert_eq!(p.lock().video_tcp.as_ref().unwrap().sampled_ms,stopped);
        drop(car);
    }
}
