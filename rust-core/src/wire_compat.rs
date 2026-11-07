//! Independent fixtures: Showcase's Monocypher AEAD + Python stdlib HKDF, not our seal/open.
//! These validate wire conventions, not a BMW decoder or live CarPlay UI.
use serde_json::Value;
use tokio::{io::AsyncReadExt,net::{TcpListener,TcpStream,UdpSocket}};

fn fixture()->Value {serde_json::from_str(include_str!("../testdata/showcase-wire.json")).unwrap()}
fn bytes(s:&Value)->Vec<u8> {
    let h=s.as_str().unwrap().as_bytes();
    h.chunks_exact(2).map(|b|u8::from_str_radix(std::str::from_utf8(b).unwrap(),16).unwrap()).collect()
}
fn key(f:&Value)->[u8;32] {bytes(&f["key"]).try_into().unwrap()}

#[test]
fn stream_key_matches_independent_hkdf_sha512() {
    let f=fixture();let shared:[u8;32]=bytes(&f["shared"]).try_into().unwrap();
    assert_eq!(crate::airplay::stream_key(&shared,f["connection_id"].as_u64().unwrap()),key(&f));
}

#[tokio::test]
async fn video_config_and_consecutive_frames_match_showcase_monocypher() {
    use crate::screen::{ScreenSender,ScreenCipher,build_avcc};
    let f=fixture();let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
    let phone=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let (mut car,_)=listener.accept().await.unwrap();let (_,write)=phone.into_split();
    let mut sender=ScreenSender::new(write,ScreenCipher::ChaCha(key(&f)),1920,720,false);
    sender.send_config(&build_avcc(&[0x67,0x42,0,0x1f],&[0x68,1])).await.unwrap();
    sender.send_video_annexb(&[0,0,0,1,0x65,2],2000).await.unwrap();
    sender.send_video_annexb(&[0,0,0,1,0x41,3],3000).await.unwrap();
    let mut expected=bytes(&f["video_config"]);
    for frame in f["video_frames"].as_array().unwrap() {expected.extend_from_slice(&bytes(frame));}
    let mut actual=vec![0;expected.len()];
    tokio::time::timeout(std::time::Duration::from_secs(1),car.read_exact(&mut actual)).await.unwrap().unwrap();
    assert_eq!(actual,expected,"config dimensions, AAD, PTS, nonce progression and AEAD must match independently");
}

#[tokio::test]
async fn pcm_packets_match_showcase_monocypher_with_timestamp_and_nonce_progression() {
    let f=fixture();let car=UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let phone=UdpSocket::bind("127.0.0.1:0").await.unwrap();phone.connect(car.local_addr().unwrap()).await.unwrap();
    let mut sender=crate::audio_out::AudioOut::new(phone,None,key(&f),100);
    let samples:Vec<i16>=(0..704).map(|i|(i%2000-1000) as i16).collect();
    for expected in f["audio_packets"].as_array().unwrap() {
        sender.send_pcm(&samples).await.unwrap();let mut actual=[0u8;2048];
        let len=tokio::time::timeout(std::time::Duration::from_secs(1),car.recv(&mut actual)).await.unwrap().unwrap();
        assert_eq!(&actual[..len],bytes(expected),"RTP marker, PCM byte order, AAD and nonce must match independently");
    }
}
