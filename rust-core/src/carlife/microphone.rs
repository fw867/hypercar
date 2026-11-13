//! Bounded, generation-scoped car microphone PCM -> authenticated CarLife VR input.
use super::*;
use std::collections::VecDeque;
use std::time::Instant;
struct Packet { generation:u64, born:Instant, bytes:Vec<u8> }
#[derive(Default)]
struct Queue { generation:u64, rate:u32, phase:u32, sum:i64, partial:Vec<u8>, packets:VecDeque<Packet>, dropped:u64 }
#[derive(Default)]
pub struct Microphone { queue:Mutex<Queue> }
impl Microphone {
    pub fn reset(&self,generation:u64) { *self.queue.lock()=Queue{generation,..Default::default()}; }
    pub fn push(&self,generation:u64,pcm:&[i16],rate:u32)->Result<(),String> {
        if !(8000..=48000).contains(&rate) {return Err("invalid decoder sample rate".into());}
        let mut q=self.queue.lock();
        if q.generation!=generation || generation&1==0 {return Err("stale microphone generation".into());}
        if q.rate!=rate {q.rate=rate;q.phase=0;q.sum=0;q.partial.clear();}
        // Area averaging preserves phase across codec buffers, including 24k/48k -> 16k.
        for &sample in pcm {
            let mut width=16000;
            while width>0 {
                let weight=width.min(rate-q.phase);q.sum+=i64::from(sample)*i64::from(weight);q.phase+=weight;width-=weight;
                if q.phase==rate {
                    let output=(q.sum/i64::from(rate)) as i16;q.partial.extend(output.to_le_bytes());q.phase=0;q.sum=0;
                    if q.partial.len()==1024 {
                        let bytes=std::mem::take(&mut q.partial);
                        if q.packets.len()>=8 {q.packets.pop_front();q.dropped+=1;}
                        q.packets.push_back(Packet{generation,born:Instant::now(),bytes});
                    }
                }
            }
        }
        Ok(())
    }
    #[cfg(test)] pub(crate) fn queued_bytes(&self)->usize {self.queue.lock().packets.iter().map(|p|p.bytes.len()).sum()}
    fn pop(&self,generation:u64)->Option<Vec<u8>> {
        let mut q=self.queue.lock();
        while let Some(packet)=q.packets.pop_front() {
            if packet.generation==generation && packet.born.elapsed()<Duration::from_millis(250) {return Some(packet.bytes);}
            q.dropped+=1;
        }
        None
    }
}
struct CaptureLease(Arc<crate::mic_probe::ProbeControl>);
impl Drop for CaptureLease {fn drop(&mut self){self.0.request(false);}}

pub(super) async fn run(stream:TcpStream,state:session::Shared,control:Arc<crate::mic_probe::ProbeControl>,stop:Arc<Notify>) {
    let _lease=CaptureLease(control.clone());
    let (mut rd,mut wr)=stream.into_split();let mut discard=[0;4096];
    let mut tick=tokio::time::interval(Duration::from_millis(8));
    let mut previous=0;let mut packets=0u64;
    loop {tokio::select! {biased;
        _=stop.notified()=>{control.request(false);return;},
        received=rd.read(&mut discard)=>{if !matches!(received,Ok(n) if n>0) {control.request(false);return;}},
        _=tick.tick()=>{
            let generation=control.generation();
            if previous!=0 && previous!=generation {
                if !matches!(tokio::time::timeout(Duration::from_secs(1),wr.write_all(&build_media_frame(0x58009,now_ms(),&[]))).await,Ok(Ok(()))) {control.request(false);return;}
                emit_log(&format!("CARWITH-MIC: VR_AUDIO_DONE packets={packets}; input epoch closed"));
                previous=0;packets=0;
            }
            if generation&1==0 {continue;}
            if previous==0 { previous=generation; }
            let Some(sink)=control.carwith.as_ref() else {return;};
            let Some(pcm)=sink.pop(generation) else {continue;};
            let body={let s=state.lock();if !s.started || !s.navigation_ready(){continue;}match s.encrypt(&pcm){Ok(v)=>v,Err(_)=>return}};
            if control.generation()!=generation {continue;}
            let wire=build_media_frame(0x58001,now_ms(),&body);
            match tokio::time::timeout(Duration::from_millis(500),wr.write_all(&wire)).await {
                Ok(Ok(()))=>{},_=>{control.request(false);emit_log("CARWITH-MIC: VR write failed/timed out; stop capture");return;}
            }
            previous=generation;packets+=1;
            if packets==1 || packets%50==0 {emit_log(&format!("CARWITH-MIC: encrypted VR_DATA packets={packets} pcmBytes={} format=16000/mono/PCM16; not recognition confirmation",packets*1024));}
        }
    }}
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn resampling_keeps_phase_levels_and_rate_across_buffer_boundaries() {
        for rate in [8000,16000,24000,48000] {
            let mic=Microphone::default();mic.reset(1);
            let samples=vec![1200;rate as usize/10];
            for chunk in samples.chunks(173) {mic.push(1,chunk,rate).unwrap();}
            let mut count=0;
            while let Some(bytes)=mic.pop(1) {count+=bytes.len()/2;assert!(bytes.chunks_exact(2).all(|b|i16::from_le_bytes([b[0],b[1]])==1200));}
            assert_eq!(count,1536);assert_eq!(mic.queue.lock().partial.len(),128);
        }
    }
    #[test] fn stale_audio_and_overflow_cannot_cross_recognition_epochs() {
        let mic=Microphone::default();mic.reset(1);mic.push(1,&vec![1;16000],16000).unwrap();
        assert_eq!(mic.queue.lock().packets.len(),8);assert!(mic.queue.lock().dropped>0);
        mic.reset(2);assert!(mic.pop(2).is_none());assert!(mic.push(1,&[1;512],16000).is_err());
        mic.reset(3);mic.push(3,&[2;512],16000).unwrap();assert!(mic.pop(3).unwrap().chunks_exact(2).all(|b|b==[2,0]));
    }
}

#[cfg(test)]
mod wire_tests {
    use super::*;
    #[tokio::test]
    async fn encrypted_vr_pcm_and_done_are_isolated_from_other_channels() {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
        let control=Arc::new(crate::mic_probe::ProbeControl::for_carwith());control.arm_test();control.request(true);
        let generation=control.generation();let state=session::microphone_test_session();let stop=Arc::new(Notify::new());
        let worker=tokio::spawn(run(client,state.clone(),control.clone(),stop.clone()));
        control.carwith.as_ref().unwrap().push(generation,&[1234;512],16000).unwrap();
        let mut header=[0;12];tokio::time::timeout(Duration::from_secs(2),peer.read_exact(&mut header)).await.unwrap().unwrap();
        assert_eq!(u32::from_be_bytes(header[8..].try_into().unwrap()),0x58001);
        let mut encrypted=vec![0;u32::from_be_bytes(header[..4].try_into().unwrap()) as usize];peer.read_exact(&mut encrypted).await.unwrap();
        assert_eq!(encrypted.len(),1040);let plain=state.lock().decrypt(&encrypted).unwrap();
        assert_eq!(plain.len(),1024);assert!(plain.chunks_exact(2).all(|b|i16::from_le_bytes([b[0],b[1]])==1234));
        control.request(false);tokio::time::timeout(Duration::from_secs(2),peer.read_exact(&mut header)).await.unwrap().unwrap();
        assert_eq!(u32::from_be_bytes(header[..4].try_into().unwrap()),0);assert_eq!(u32::from_be_bytes(header[8..].try_into().unwrap()),0x58009);
        assert!(state.lock().started);assert!(control.carwith.as_ref().unwrap().push(generation,&[1;512],16000).is_err());
        stop.notify_waiters();tokio::time::timeout(Duration::from_secs(2),worker).await.unwrap().unwrap();
    }
}

#[cfg(test)]
mod cancellation_tests {
    use super::*;
    #[tokio::test] async fn aborted_vr_writer_retires_capture_and_queued_audio() {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(_peer,_)=listener.accept().await.unwrap();
        let control=Arc::new(crate::mic_probe::ProbeControl::for_carwith());control.arm_test();control.request(true);
        let generation=control.generation();control.carwith.as_ref().unwrap().push(generation,&[1;512],16000).unwrap();
        let task=tokio::spawn(run(client,session::microphone_test_session(),control.clone(),Arc::new(Notify::new())));
        tokio::task::yield_now().await;task.abort();assert!(task.await.unwrap_err().is_cancelled());
        assert_eq!(control.generation()&1,0);assert_eq!(control.carwith.as_ref().unwrap().queued_bytes(),0);
    }
}
