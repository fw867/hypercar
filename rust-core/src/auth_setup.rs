//! BMW box MFi-SAP client exchange. SAP keys never replace HomeKit control/video keys.
//! Wire format cross-checked against CatPlay auth_setup.rs and xiap2 firmware.
use crate::{crypto::{X25519KeyPair, Aes128CtrStream}, rtsp::RtspChannel};
use sha1::{Digest, Sha1};
use std::{sync::Arc, time::{Duration, Instant}};

fn decode(key: &X25519KeyPair, body: &[u8]) -> Result<(usize, Vec<u8>), String> {
    if body.len() < 40 || body.len() > 65536 { return Err("invalid SAP response size".into()); }
    let peer: [u8;32] = body[..32].try_into().unwrap();
    let cert_len = u32::from_be_bytes(body[32..36].try_into().unwrap()) as usize;
    let sig_offset = 36usize.checked_add(cert_len).filter(|n| *n <= body.len()-4)
        .ok_or("invalid SAP certificate length")?;
    let sig_len = u32::from_be_bytes(body[sig_offset..sig_offset+4].try_into().unwrap()) as usize;
    if cert_len == 0 || sig_len != 64 || sig_offset.checked_add(4+sig_len) != Some(body.len()) {
        return Err("invalid SAP signature envelope".into());
    }
    let shared = key.shared(&peer);
    if shared == [0;32] { return Err("invalid SAP peer public key".into()); }
    let derive = |salt: &[u8]| -> [u8;16] {
        let mut hash = Sha1::new(); hash.update(salt); hash.update(shared);
        hash.finalize()[..16].try_into().unwrap()
    };
    let mut signature = body[sig_offset+4..].to_vec();
    Aes128CtrStream::new(&derive(b"AES-KEY"), &derive(b"AES-IV")).apply_keystream(&mut signature);
    // As in CatPlay client, this parses/decrypts the exchange; no certificate-chain validation.
    Ok((cert_len, signature))
}

pub async fn run(channel: &Arc<RtspChannel>) -> Result<(), String> {
    let key = X25519KeyPair::generate();
    let mut request = vec![1]; request.extend_from_slice(&key.public);
    let started = Instant::now();
    crate::emit_log("AUTH-SETUP: start version=1 request=33B (fresh ephemeral key)");
    let reply = channel.request("POST", "/auth-setup", &request, "application/octet-stream", &[], Duration::from_secs(10))
        .await.map_err(|e| format!("auth-setup transport: {e}"))?;
    if reply.status() != 200 { return Err(format!("auth-setup rejected status={}", reply.status())); }
    let (cert, signature) = decode(&key, &reply.body).map_err(|e| format!("auth-setup: {e}"))?;
    crate::emit_log(&format!("AUTH-SETUP: exchange completed cert={cert}B signature={}B elapsed={}ms; certificate trust not validated", signature.len(), started.elapsed().as_millis()));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sap_roundtrip_and_invalid_envelopes() {
        let client = X25519KeyPair::generate(); let server = X25519KeyPair::generate();
        let shared = server.shared(&client.public);
        let derive = |salt: &[u8]| -> [u8;16] { let mut h=Sha1::new(); h.update(salt);h.update(shared);h.finalize()[..16].try_into().unwrap() };
        let signature=vec![0x37;64]; let mut encrypted=signature.clone();
        Aes128CtrStream::new(&derive(b"AES-KEY"), &derive(b"AES-IV")).apply_keystream(&mut encrypted);
        let mut body=server.public.to_vec();body.extend(3u32.to_be_bytes());body.extend([1,2,3]);body.extend(64u32.to_be_bytes());body.extend(encrypted);
        assert_eq!(decode(&client,&body).unwrap(),(3,signature));
        for n in 0..body.len() { assert!(decode(&client,&body[..n]).is_err()); }
        let mut extra=body.clone(); extra.push(0);assert!(decode(&client,&extra).is_err());
        body[..32].fill(0);assert!(decode(&client,&body).is_err());
    }
}
