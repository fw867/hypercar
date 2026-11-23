//! Bound a complete write without cancelling a frame while the peer is still acknowledging it.
use std::{future::Future, io, time::Duration};

#[derive(Default)]
pub(crate) struct SlowWriteTrace {
    last_report: Option<(Option<u64>, tokio::time::Instant)>,
}

impl SlowWriteTrace {
    async fn observe<F>(&mut self, connection: Option<u64>, write: F,
        mut report: impl FnMut(&'static str, Duration, Option<&io::Result<()>>)) -> io::Result<()>
    where F: Future<Output = io::Result<()>> {
        let start = tokio::time::Instant::now();
        tokio::pin!(write);
        let can_report = self.last_report.is_none_or(|(id, at)| id != connection || start - at >= Duration::from_secs(10));
        let result = tokio::select! {
            biased;
            result = &mut write => result,
            _ = tokio::time::sleep(Duration::from_millis(500)), if can_report => {
                self.last_report = Some((connection, start));
                report("video_write_slow", start.elapsed(), None);
                let result = write.await;
                report("video_write_end", start.elapsed(), Some(&result));
                return result;
            }
        };
        if result.is_err() { report("video_write_end", start.elapsed(), Some(&result)); }
        result
    }

    pub(crate) async fn write<F>(&mut self, connection: Option<u64>, operation: &str,
        pipeline: &parking_lot::Mutex<crate::pipeline::Pipeline>, guarded: bool, write: F) -> io::Result<()>
    where F: Future<Output = io::Result<()>> {
        let guarded_write = async {
            if guarded {
                with_progress(write, || pipeline.lock().video_tcp.as_ref()
                    .filter(|s| Some(s.connection_id) == connection).and_then(|s| s.acked_bytes)).await
            } else {
                write.await
            }
        };
        self.observe(connection, guarded_write, |event, elapsed, result| {
            let sample = pipeline.lock().video_tcp.clone().filter(|s| Some(s.connection_id) == connection);
            let outcome = match result {
                None => "pending".into(),
                Some(Ok(())) => "complete".into(),
                Some(Err(e)) => format!("error({e})"),
            };
            let now = crate::timing::monotonic_nanos() / 1_000_000;
            let connection = connection.map(|id| id.to_string()).unwrap_or_else(|| "unknown".into());
            let detail = sample.map(|s| format!("ack={:?} outq={:?} retrans={:?} sampleAgeMs={}",
                s.acked_bytes, s.outq_bytes, s.total_retrans, now.saturating_sub(s.sampled_ms)))
                .unwrap_or_else(|| "tcp_sample=unavailable".into());
            crate::emit_media_trace(now, event, &format!("conn={connection} op={operation} elapsedMs={} result={outcome} {detail}", elapsed.as_millis()));
        }).await
    }
}

pub async fn with_progress<F, P>(write: F, acknowledged: P) -> io::Result<()>
where F: Future<Output = io::Result<()>>, P: Fn() -> Option<u64> {
    let idle = tokio::time::sleep(Duration::from_secs(2));
    let hard_limit = tokio::time::sleep(Duration::from_secs(8));
    tokio::pin!(write, idle, hard_limit);
    let mut last_ack = acknowledged();
    let mut poll = tokio::time::interval(Duration::from_millis(500));
    poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! {
            biased;
            result = &mut write => return result,
            _ = &mut hard_limit => return Err(io::Error::new(io::ErrorKind::TimedOut,"video TCP write exceeded 8s total limit")),
            _ = &mut idle => {
                // The independent 1Hz TCP sample can advance between the last poll and
                // this deadline. Check it once more before cancelling a partially written frame.
                let next = acknowledged();
                if let Some(next) = next.filter(|next|last_ack.is_some_and(|previous|*next > previous)) {
                    last_ack = Some(next);
                    idle.as_mut().reset(tokio::time::Instant::now() + Duration::from_secs(2));
                } else {
                    return Err(io::Error::new(io::ErrorKind::TimedOut,"video TCP write stalled: no ACK progress for 2s"));
                }
            },
            _ = poll.tick() => {
                if let Some(next) = acknowledged() {
                    if last_ack.is_some_and(|previous| next > previous) {
                        idle.as_mut().reset(tokio::time::Instant::now() + Duration::from_secs(2));
                    }
                    if last_ack.is_none_or(|previous| next > previous) { last_ack = Some(next); }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, atomic::{AtomicU64, Ordering}};

    #[tokio::test(start_paused = true)]
    async fn slow_write_is_polled_to_completion_once_and_traces_follow_connection() {
        let mut trace = SlowWriteTrace::default();
        let mut events = Vec::new();
        for (connection, delay) in [(8,10), (8,600), (8,700), (9,800)] {
            let mut calls = 0;
            let write = async {
                calls += 1;
                tokio::time::sleep(Duration::from_millis(delay)).await;
                Ok(())
            };
            trace.observe(Some(connection), write, |stage, _, _| events.push((connection, stage))).await.unwrap();
            assert_eq!(calls, 1);
        }
        assert_eq!(events, [(8,"video_write_slow"), (8,"video_write_end"),
                             (9,"video_write_slow"), (9,"video_write_end")]);
    }

    #[tokio::test(start_paused = true)]
    async fn a_terminal_write_error_is_retained_inside_the_slow_log_cooldown() {
        let mut trace = SlowWriteTrace::default();
        let wait = async { tokio::time::sleep(Duration::from_secs(1)).await; Ok(()) };
        trace.observe(Some(1), wait, |_,_,_| {}).await.unwrap();
        let mut failures = Vec::new();
        for id in [1,2] {
            let write = async { Err(io::Error::from(io::ErrorKind::BrokenPipe)) };
            let result = trace.observe(Some(id), write, |event, _, result| {
                failures.push((id, event, result.unwrap().as_ref().unwrap_err().kind()));
            }).await;
            assert!(result.is_err());
        }
        assert_eq!(failures, [(1,"video_write_end",io::ErrorKind::BrokenPipe),
                              (2,"video_write_end",io::ErrorKind::BrokenPipe)]);
    }

    #[tokio::test]
    async fn real_slow_reader_keeps_a_progressing_tcp_write_alive() {
        use std::os::fd::AsRawFd;
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut phone = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let buffer = 8192i32;
        for (fd, option) in [(phone.as_raw_fd(), libc::SO_SNDBUF), (car.as_raw_fd(), libc::SO_RCVBUF)] {
            assert_eq!(unsafe {libc::setsockopt(fd,libc::SOL_SOCKET,option,
                (&buffer as *const i32).cast(),std::mem::size_of_val(&buffer) as libc::socklen_t)},0);
        }
        let payload = vec![7;512*1024];
        let reader = tokio::spawn(async move {
            let mut total = 0;
            let mut chunk = [0;8192];
            while total < 512*1024 {
                tokio::time::sleep(Duration::from_millis(50)).await;
                let n = car.read(&mut chunk).await.unwrap();
                assert!(n>0);
                assert!(chunk[..n].iter().all(|v|*v==7));
                total += n;
            }
            total
        });
        let fd = phone.as_raw_fd();
        let start = tokio::time::Instant::now();
        with_progress(phone.write_all(&payload), ||
            crate::tcp_diagnostics::TcpSnapshot::capture(fd,1).acked_bytes).await.unwrap();
        assert!(start.elapsed()>Duration::from_secs(2),"must exercise the old whole-frame timeout");
        assert_eq!(reader.await.unwrap(),payload.len());
    }

    #[tokio::test(start_paused = true)]
    async fn slow_but_acknowledged_frame_finishes_without_cancelling_the_connection() {
        let ack = Arc::new(AtomicU64::new(10));
        let progress = ack.clone();
        let writer = async move {
            for _ in 0..6 {
                tokio::time::sleep(Duration::from_millis(500)).await;
                progress.fetch_add(100, Ordering::Relaxed);
            }
            Ok(())
        };
        assert!(with_progress(writer, || Some(ack.load(Ordering::Relaxed))).await.is_ok());
    }

    #[tokio::test(start_paused = true)]
    async fn acknowledgement_just_before_idle_deadline_is_checked_before_cancelling() {
        let ack = AtomicU64::new(10);
        let writer = async {
            tokio::time::sleep(Duration::from_millis(1900)).await;
            ack.store(110, Ordering::Relaxed);
            tokio::time::sleep(Duration::from_millis(200)).await;
            Ok(())
        };
        assert!(with_progress(writer, ||Some(ack.load(Ordering::Relaxed))).await.is_ok());
    }

    #[tokio::test(start_paused = true)]
    async fn frozen_or_unavailable_tcp_progress_still_times_out_in_two_seconds() {
        for progress in [None, Some(100)] {
            let start = tokio::time::Instant::now();
            let result = with_progress(std::future::pending(), || progress).await;
            assert_eq!(result.unwrap_err().kind(), io::ErrorKind::TimedOut);
            assert_eq!(start.elapsed(), Duration::from_secs(2));
        }
    }

    #[tokio::test(start_paused = true)]
    async fn continuous_acknowledgements_cannot_extend_a_write_past_eight_seconds() {
        let ack = AtomicU64::new(0);
        let start = tokio::time::Instant::now();
        let result = with_progress(std::future::pending(), || Some(ack.fetch_add(1, Ordering::Relaxed))).await;
        assert_eq!(result.unwrap_err().kind(), io::ErrorKind::TimedOut);
        assert_eq!(start.elapsed(), Duration::from_secs(8));
    }
}
