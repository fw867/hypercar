//! Traffic class for main CarPlay video sockets.

use std::{io, mem, os::fd::AsRawFd};
use tokio::net::TcpStream;

fn traffic_class_option(socket: &TcpStream) -> io::Result<(libc::c_int, libc::c_int)> {
    Ok(if socket.peer_addr()?.ip().to_canonical().is_ipv4() {
        (libc::IPPROTO_IP, libc::IP_TOS)
    } else {
        (libc::IPPROTO_IPV6, libc::IPV6_TCLASS)
    })
}

fn read_traffic_class(socket: &TcpStream) -> io::Result<libc::c_int> {
    let (level, option) = traffic_class_option(socket)?;
    let mut value: libc::c_int = 0;
    let mut length = mem::size_of_val(&value) as libc::socklen_t;
    let status = unsafe {
        libc::getsockopt(socket.as_raw_fd(), level, option, (&mut value as *mut libc::c_int).cast(), &mut length)
    };
    if status == 0 { Ok(value) } else { Err(io::Error::last_os_error()) }
}

pub(crate) fn configure_cs4(socket: &TcpStream) -> io::Result<libc::c_int> {
    let (level, option) = traffic_class_option(socket)?;
    let value = (32 << 2) | (read_traffic_class(socket)? & 0b11);
    let status = unsafe {
        libc::setsockopt(socket.as_raw_fd(), level, option, (&value as *const libc::c_int).cast(),
            mem::size_of_val(&value) as libc::socklen_t)
    };
    if status != 0 { return Err(io::Error::last_os_error()); }
    read_traffic_class(socket)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::{TcpListener, TcpStream};

    #[tokio::test]
    async fn cs4_is_applied_and_read_back_only_on_the_selected_ipv4_and_ipv6_video_socket() {
        for address in ["127.0.0.1:0", "[::1]:0"] {
            let listener = TcpListener::bind(address).await.unwrap();
            let address = listener.local_addr().unwrap();
            let baseline = TcpStream::connect(address).await.unwrap();
            let (_baseline_peer, _) = listener.accept().await.unwrap();
            let video = TcpStream::connect(address).await.unwrap();
            let (_video_peer, _) = listener.accept().await.unwrap();
            assert_eq!(read_traffic_class(&baseline).unwrap() >> 2, 0);
            assert_eq!(configure_cs4(&video).unwrap() >> 2, 32);
            assert_eq!(read_traffic_class(&video).unwrap() >> 2, 32);
            assert_eq!(read_traffic_class(&baseline).unwrap() >> 2, 0);
        }
    }
}
