//! Bound a complete write without cancelling a frame while the peer is still acknowledging it.
use std::{future::Future, io, time::Duration};

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
