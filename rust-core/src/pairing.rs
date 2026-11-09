//! AirPlay 配对客户端（cardeck `PairingClients.kt` 的 Rust 移植）：
//! `/pair-setup`（SRP 交换长期 ed25519 公钥）与 `/pair-verify`（每次会话的
//! 临时 X25519 + 长期签名，派生控制通道 ChaCha20 密钥）。
//!
//! 传输抽象为 `TlvTransport`：真实实现走 RTSP POST（请求体/应答体都是 TLV8），
//! 测试用 mock 接收端直连。所有 HKDF 标签串、nonce 标签（"PS-Msg05"/"PV-Msg02"
//! 等）与消息流逐字对齐 PairingClients.kt——**一个字都不能差**，接收端按
//! 完全相同的字符串派生密钥。
//!
//! pair-setup 消息流（M1..M6）：
//!  - M1 → M2：Method=None + State=1 请求 SRP 参数（salt、B）
//!  - M3 → M4：State=3 + 公钥 A + 证明 M1；M4 回服务器证明 HAMK（互验）
//!  - M5 → M6：State=5 + ChaCha 信封（nonce "PS-Msg05"）提交控制器长期公钥；
//!    M6 信封（nonce "PS-Msg06"）里是接收端长期公钥 + 签名
//!
//! pair-verify 消息流（M1..M4）：
//!  - M1 → M2：State=1 + 临时 X25519 公钥；M2 回接收端临时公钥 + 信封（"PV-Msg02"）
//!  - M3 → M4：State=3 + 信封（"PV-Msg03"）提交控制器签名；完成后派生控制密钥

use std::fs;
use std::io;

use crate::crypto::{self, Ed25519KeyPair, X25519KeyPair};
use crate::srp6a::Srp6aClient;
use crate::tlv8::{self, Item};

const HEX_CHARS: &[u8; 16] = b"0123456789abcdef";

/// 手机（控制器）长期身份：ed25519 密钥对 + 配对 ID，首次使用时生成并落盘。
#[derive(Debug, Clone)]
pub struct ControllerIdentity {
    pub pairing_id: String,
    pub private_key: [u8; 32],
    pub public_key: [u8; 32],
}

impl ControllerIdentity {
    /// 从文件加载身份；不存在则生成新 ed25519 密钥对与 UUID 配对 ID 并落盘。
    /// 文件格式：一行 `pairing_id 空格 hex私钥`（64 个 hex 字符）。
    pub fn load_or_create(path: &std::path::Path) -> std::io::Result<Self> {
        match fs::read_to_string(path) {
            Ok(text) => {
                let line = text.lines().next().unwrap_or("").trim();
                let mut parts = line.split_whitespace();
                let pairing_id = parts
                    .next()
                    .ok_or_else(|| {
                        io::Error::new(io::ErrorKind::InvalidData, "身份文件缺少 pairing_id")
                    })?
                    .to_string();
                let hex = parts.next().ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidData, "身份文件缺少私钥 hex")
                })?;
                let raw = hex_to_bytes(hex).ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidData, "身份文件私钥 hex 非法")
                })?;
                let private_key: [u8; 32] = raw.try_into().map_err(|raw: Vec<u8>| {
                    io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!("身份文件私钥长度必须 32 字节（当前 {}）", raw.len()),
                    )
                })?;
                let public_key = Ed25519KeyPair::public_key(&private_key);
                Ok(ControllerIdentity {
                    pairing_id,
                    private_key,
                    public_key,
                })
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let pair = Ed25519KeyPair::generate();
                let pairing_id = uuid::Uuid::new_v4().to_string();
                if let Some(parent) = path.parent() {
                    if !parent.as_os_str().is_empty() {
                        fs::create_dir_all(parent)?;
                    }
                }
                let content = format!("{} {}\n", pairing_id, bytes_to_hex(&pair.private));
                fs::write(path, content)?;
                Ok(ControllerIdentity {
                    pairing_id,
                    private_key: pair.private,
                    public_key: pair.public,
                })
            }
            Err(error) => Err(error),
        }
    }
}

/// TLV8 请求/应答传输抽象：真实实现走 RTSP POST，测试用 mock 接收端直连。
pub trait TlvTransport {
    /// 发送一个 TLV8 请求体，返回接收端的 TLV8 应答体。
    fn exchange(&mut self, path: &str, body: &[u8]) -> Result<Vec<u8>, String>;
}

/// AirPlay `/pair-setup` 客户端（M1/M3/M5）：固定码 SRP-6a
/// （"Pair-Setup"/"3939"）之后在加密信封里交换长期 ed25519 公钥。
pub struct PairSetupClient;

impl PairSetupClient {
    /// 完整 M1..M6；成功返回接收端长期 ed25519 公钥（32B，M6 信封里带出）。
    pub fn run<T: TlvTransport>(
        identity: &ControllerIdentity,
        transport: &mut T,
        log: &dyn Fn(&str),
    ) -> Result<[u8; 32], String> {
        // M1: 请求 SRP 参数（Method=None + State=1，对照 catplay PairSetup::start）
        let request = tlv8::encode(&[
            Item {
                t: tlv8::TYPE_METHOD,
                v: vec![0],
            },
            Item {
                t: tlv8::TYPE_STATE,
                v: vec![1],
            },
        ]);
        let m2 = tlv8::decode(&transport.exchange("/pair-setup", &request)?);
        let salt = m2
            .get(&tlv8::TYPE_SALT)
            .ok_or_else(|| "pair-setup M2 缺少 salt".to_string())?;
        let server_public = m2
            .get(&tlv8::TYPE_PUBLIC_KEY)
            .ok_or_else(|| "pair-setup M2 缺少 publicKey".to_string())?;
        log(&format!(
            "pair-setup: M2 收到 SRP 参数 salt={}B B={}B",
            salt.len(),
            server_public.len()
        ));

        // M3: 客户端公钥 A + 证明 M1
        let srp = Srp6aClient::start(salt, server_public)?;
        let client_proof = srp
            .client_proof()
            .ok_or_else(|| "pair-setup: SRP B 非法".to_string())?;
        let request = tlv8::encode(&[
            Item {
                t: tlv8::TYPE_STATE,
                v: vec![3],
            },
            Item {
                t: tlv8::TYPE_PUBLIC_KEY,
                v: srp.public_key_a.clone(),
            },
            Item {
                t: tlv8::TYPE_PROOF,
                v: client_proof,
            },
        ]);
        let m4 = tlv8::decode(&transport.exchange("/pair-setup", &request)?);
        let server_proof = m4
            .get(&tlv8::TYPE_PROOF)
            .ok_or_else(|| "pair-setup M4 缺少 proof".to_string())?;
        if !srp.server_proof_valid(server_proof) {
            return Err("pair-setup: 服务器证明校验失败".to_string());
        }
        let session_key = srp
            .session_key
            .as_ref()
            .ok_or_else(|| "pair-setup: 会话密钥缺失".to_string())?;
        log("pair-setup: M4 SRP 互验通过");

        // M5: 加密信封提交长期公钥（信封密钥与签名密钥都从 SRP 会话密钥 HKDF 派生）
        let encryption_key = crypto::hkdf_sha512(
            session_key,
            b"Pair-Setup-Encrypt-Salt",
            b"Pair-Setup-Encrypt-Info",
            32,
        );
        let controller_sign_key = crypto::hkdf_sha512(
            session_key,
            b"Pair-Setup-Controller-Sign-Salt",
            b"Pair-Setup-Controller-Sign-Info",
            32,
        );
        let identifier_bytes = identity.pairing_id.as_bytes();
        let mut signed = Vec::with_capacity(
            controller_sign_key.len() + identifier_bytes.len() + identity.public_key.len(),
        );
        signed.extend_from_slice(&controller_sign_key);
        signed.extend_from_slice(identifier_bytes);
        signed.extend_from_slice(&identity.public_key);
        let signature = Ed25519KeyPair::from_private_bytes(identity.private_key).sign(&signed);
        let sub = tlv8::encode(&[
            Item {
                t: tlv8::TYPE_IDENTIFIER,
                v: identifier_bytes.to_vec(),
            },
            Item {
                t: tlv8::TYPE_PUBLIC_KEY,
                v: identity.public_key.to_vec(),
            },
            Item {
                t: tlv8::TYPE_SIGNATURE,
                v: signature.to_vec(),
            },
        ]);
        let request = tlv8::encode(&[
            Item {
                t: tlv8::TYPE_STATE,
                v: vec![5],
            },
            Item {
                t: tlv8::TYPE_ENCRYPTED_DATA,
                v: crypto::chacha_seal(
                    &encryption_key,
                    &crypto::nonce_label("PS-Msg05"),
                    &sub,
                    &[],
                ),
            },
        ]);
        let m6 = tlv8::decode(&transport.exchange("/pair-setup", &request)?);
        let state = m6.get(&tlv8::TYPE_STATE).and_then(|v| v.first().copied());
        if state != Some(6) {
            let error = m6.get(&tlv8::TYPE_ERROR).and_then(|v| v.first().copied());
            return Err(format!("pair-setup M6 状态非 6：{:?}", error));
        }
        log("pair-setup: ✓ M6 完成，接收端已保存我们的长期公钥");

        // 解析 M6 加密信封里的接收端身份（长期公钥是 pair-verify 的前置输入，缺失即失败）
        let encrypted = m6
            .get(&tlv8::TYPE_ENCRYPTED_DATA)
            .ok_or_else(|| "pair-setup M6 缺少 encryptedData（无法获得接收端长期公钥）".to_string())?;
        let inner_bytes = crypto::chacha_open(
            &encryption_key,
            &crypto::nonce_label("PS-Msg06"),
            encrypted,
            &[],
        )?;
        let inner = tlv8::decode(&inner_bytes);
        let receiver_id = inner
            .get(&tlv8::TYPE_IDENTIFIER)
            .ok_or_else(|| "pair-setup M6 信封缺少 identifier".to_string())?;
        let receiver_key = inner
            .get(&tlv8::TYPE_PUBLIC_KEY)
            .ok_or_else(|| "pair-setup M6 信封缺少 publicKey".to_string())?;
        let receiver_signature = inner
            .get(&tlv8::TYPE_SIGNATURE)
            .ok_or_else(|| "pair-setup M6 信封缺少 signature".to_string())?;
        let receiver_public: [u8; 32] = receiver_key.as_slice().try_into().map_err(|_| {
            "pair-setup: 接收端长期公钥长度非 32 字节".to_string()
        })?;
        let signature_array: [u8; 64] =
            receiver_signature.as_slice().try_into().map_err(|_| {
                "pair-setup: 接收端签名长度非 64 字节".to_string()
            })?;
        let accessory_sign_key = crypto::hkdf_sha512(
            session_key,
            b"Pair-Setup-Accessory-Sign-Salt",
            b"Pair-Setup-Accessory-Sign-Info",
            32,
        );
        let mut verified = Vec::with_capacity(
            accessory_sign_key.len() + receiver_id.len() + receiver_key.len(),
        );
        verified.extend_from_slice(&accessory_sign_key);
        verified.extend_from_slice(receiver_id);
        verified.extend_from_slice(receiver_key);
        // 与 Kotlin 版一致：签名无效只记日志、不中断（pair-verify 阶段自然会失败）
        let valid = crypto::ed25519_verify(&receiver_public, &verified, &signature_array);
        log(&format!(
            "pair-setup: 接收端身份 id={} pub={}… 签名校验={}",
            String::from_utf8_lossy(receiver_id),
            hex_prefix(receiver_key, 8),
            valid
        ));
        Ok(receiver_public)
    }
}

/// AirPlay `/pair-verify` 客户端（M1/M3）：每次会话的临时 X25519 + 长期 ed25519
/// 签名，完成后得到控制通道的 ChaCha20 密钥（write=我们发出的方向）。
pub struct PairVerifyClient;

/// 控制通道密钥：write_key 是我们**发出**方向，read_key 是我们**接收**方向
/// （对照接收端实现：接收端 readKey = "Control-Write-…"，即我们发出的方向）。
#[derive(Debug, Clone)]
pub struct ControlKeys {
    pub write_key: [u8; 32],
    pub read_key: [u8; 32],
    pub shared_secret: [u8; 32],
}

impl PairVerifyClient {
    /// M1..M4；完成后 transport 上层即可切换控制通道加密。
    /// `receiver_static_public`（mDNS/0x4301 提供的接收端长期公钥）有则验签、无则跳过。
    pub fn run<T: TlvTransport>(
        identity: &ControllerIdentity,
        transport: &mut T,
        receiver_static_public: Option<&[u8; 32]>,
        log: &dyn Fn(&str),
    ) -> Result<ControlKeys, String> {
        // M1: 临时 X25519 公钥
        let ephemeral = X25519KeyPair::generate();
        let request = tlv8::encode(&[
            Item {
                t: tlv8::TYPE_STATE,
                v: vec![1],
            },
            Item {
                t: tlv8::TYPE_PUBLIC_KEY,
                v: ephemeral.public.to_vec(),
            },
        ]);
        let m2 = tlv8::decode(&transport.exchange("/pair-verify", &request)?);
        validate_verify_reply(&m2, 2)?;
        let receiver_ephemeral_bytes = m2
            .get(&tlv8::TYPE_PUBLIC_KEY)
            .ok_or_else(|| "pair-verify M2 缺少 publicKey".to_string())?;
        let receiver_ephemeral: [u8; 32] =
            receiver_ephemeral_bytes.as_slice().try_into().map_err(|_| {
                "pair-verify M2 临时公钥长度非 32 字节".to_string()
            })?;
        let encrypted = m2
            .get(&tlv8::TYPE_ENCRYPTED_DATA)
            .ok_or_else(|| "pair-verify M2 缺少 encryptedData".to_string())?;

        let shared = ephemeral.shared(&receiver_ephemeral);
        let encryption_key = crypto::hkdf_sha512(
            &shared,
            b"Pair-Verify-Encrypt-Salt",
            b"Pair-Verify-Encrypt-Info",
            32,
        );
        let inner_bytes = crypto::chacha_open(
            &encryption_key,
            &crypto::nonce_label("PV-Msg02"),
            encrypted,
            &[],
        )?;
        let inner = tlv8::decode(&inner_bytes);
        let receiver_id = inner
            .get(&tlv8::TYPE_IDENTIFIER)
            .ok_or_else(|| "pair-verify M2 信封缺少 identifier".to_string())?;
        let receiver_signature = inner
            .get(&tlv8::TYPE_SIGNATURE)
            .ok_or_else(|| "pair-verify M2 信封缺少 signature".to_string())?;

        // 用 mDNS/0x4301 提供的接收端静态公钥验证（有则验，无则跳过；只记日志）
        if let Some(static_public) = receiver_static_public {
            let signature_array: [u8; 64] = receiver_signature.as_slice().try_into().map_err(
                |_| "pair-verify: 接收端签名长度非 64 字节".to_string(),
            )?;
            let mut verified =
                Vec::with_capacity(receiver_ephemeral.len() + receiver_id.len() + ephemeral.public.len());
            verified.extend_from_slice(&receiver_ephemeral);
            verified.extend_from_slice(receiver_id);
            verified.extend_from_slice(&ephemeral.public);
            let valid = crypto::ed25519_verify(static_public, &verified, &signature_array);
            log(&format!("pair-verify: 接收端签名校验（对照 pk）={}", valid));
        }

        // M3: 我们的长期签名（临时公钥 || 控制器 ID || 接收端临时公钥）
        let identifier_bytes = identity.pairing_id.as_bytes();
        let mut signed = Vec::with_capacity(
            ephemeral.public.len() + identifier_bytes.len() + receiver_ephemeral.len(),
        );
        signed.extend_from_slice(&ephemeral.public);
        signed.extend_from_slice(identifier_bytes);
        signed.extend_from_slice(&receiver_ephemeral);
        let signature = Ed25519KeyPair::from_private_bytes(identity.private_key).sign(&signed);
        let sub = tlv8::encode(&[
            Item {
                t: tlv8::TYPE_IDENTIFIER,
                v: identifier_bytes.to_vec(),
            },
            Item {
                t: tlv8::TYPE_SIGNATURE,
                v: signature.to_vec(),
            },
        ]);
        let request = tlv8::encode(&[
            Item {
                t: tlv8::TYPE_STATE,
                v: vec![3],
            },
            Item {
                t: tlv8::TYPE_ENCRYPTED_DATA,
                v: crypto::chacha_seal(
                    &encryption_key,
                    &crypto::nonce_label("PV-Msg03"),
                    &sub,
                    &[],
                ),
            },
        ]);
        let m4 = tlv8::decode(&transport.exchange("/pair-verify", &request)?);
        validate_verify_reply(&m4, 4)?;
        log("pair-verify: ✓ 完成，派生控制通道密钥");

        // 方向对照接收端实现：接收端 readKey="Control-Write-…"（即我们发出的方向）
        let write_key: [u8; 32] = crypto::hkdf_sha512(
            &shared,
            b"Control-Salt",
            b"Control-Write-Encryption-Key",
            32,
        )
        .try_into()
        .map_err(|_| "控制通道写密钥派生长度异常".to_string())?;
        let read_key: [u8; 32] = crypto::hkdf_sha512(
            &shared,
            b"Control-Salt",
            b"Control-Read-Encryption-Key",
            32,
        )
        .try_into()
        .map_err(|_| "控制通道读密钥派生长度异常".to_string())?;
        Ok(ControlKeys {
            write_key,
            read_key,
            shared_secret: shared,
        })
    }
}

fn bytes_to_hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX_CHARS[(byte >> 4) as usize] as char);
        out.push(HEX_CHARS[(byte & 0x0F) as usize] as char);
    }
    out
}

/// 日志用前缀 hex（如取公钥前 8 字节 → 16 个 hex 字符，同 Kotlin toHexString().take(16)）。
fn hex_prefix(bytes: &[u8], max_bytes: usize) -> String {
    bytes_to_hex(&bytes[..bytes.len().min(max_bytes)])
}

fn hex_to_bytes(hex: &str) -> Option<Vec<u8>> {
    let raw = hex.as_bytes();
    if raw.len() % 2 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(raw.len() / 2);
    for pair in raw.chunks(2) {
        let high = (pair[0] as char).to_digit(16)?;
        let low = (pair[1] as char).to_digit(16)?;
        out.push(((high << 4) | low) as u8);
    }
    Some(out)
}


// A state number identifies the reply step, including replies that reject that step.
// WinPlay returns State=4, Error=2 when the controller is unknown or its signature fails.
fn validate_verify_reply(reply: &std::collections::HashMap<u8, Vec<u8>>, expected: u8) -> Result<(), String> {
    if let Some(error) = reply.get(&tlv8::TYPE_ERROR) {
        return Err(format!("pair-verify M{expected} 接收端拒绝：error={:?}", error));
    }
    if reply.get(&tlv8::TYPE_STATE).map(Vec::as_slice) != Some(&[expected][..]) {
        return Err(format!("pair-verify M{expected} 状态无效"));
    }
    Ok(())
}

#[cfg(test)]
mod verify_tests {
    use super::*;

    struct Receiver {
        reject: bool,
        ephemeral: X25519KeyPair,
        shared: Option<[u8; 32]>,
    }

    impl TlvTransport for Receiver {
        fn exchange(&mut self, path: &str, body: &[u8]) -> Result<Vec<u8>, String> {
            assert_eq!(path, "/pair-verify");
            let request = tlv8::decode(body);
            if request[&tlv8::TYPE_STATE] == [1] {
                let peer: [u8; 32] = request[&tlv8::TYPE_PUBLIC_KEY].as_slice().try_into().unwrap();
                let shared = self.ephemeral.shared(&peer);
                self.shared = Some(shared);
                let key = crypto::hkdf_sha512(&shared, b"Pair-Verify-Encrypt-Salt", b"Pair-Verify-Encrypt-Info", 32);
                let inner = tlv8::encode(&[
                    Item { t: tlv8::TYPE_IDENTIFIER, v: b"test-receiver".to_vec() },
                    Item { t: tlv8::TYPE_SIGNATURE, v: vec![0; 64] },
                ]);
                Ok(tlv8::encode(&[
                    Item { t: tlv8::TYPE_STATE, v: vec![2] },
                    Item { t: tlv8::TYPE_PUBLIC_KEY, v: self.ephemeral.public.to_vec() },
                    Item { t: tlv8::TYPE_ENCRYPTED_DATA, v: crypto::chacha_seal(&key, &crypto::nonce_label("PV-Msg02"), &inner, &[]) },
                ]))
            } else {
                assert_eq!(request[&tlv8::TYPE_STATE], [3]);
                let mut reply = vec![Item { t: tlv8::TYPE_STATE, v: vec![4] }];
                if self.reject {
                    reply.push(Item { t: tlv8::TYPE_ERROR, v: vec![2] });
                }
                Ok(tlv8::encode(&reply))
            }
        }
    }

    #[test]
    fn rejected_m4_never_produces_control_keys() {
        let pair = Ed25519KeyPair::generate();
        let identity = ControllerIdentity { pairing_id: "test-controller".into(), private_key: pair.private, public_key: pair.public };
        for reject in [true, false] {
            let mut receiver = Receiver { reject, ephemeral: X25519KeyPair::generate(), shared: None };
            let result = PairVerifyClient::run(&identity, &mut receiver, None, &|_| {});
            if reject {
                let error = result.err().expect("State=4 plus Error=2 is a refusal");
                assert!(error.contains("M4") && error.contains("[2]"));
            } else {
                let keys = result.ok().expect("successful M4 still derives keys");
                assert_eq!(keys.shared_secret, receiver.shared.unwrap());
                assert_eq!(keys.write_key.to_vec(), crypto::hkdf_sha512(&keys.shared_secret, b"Control-Salt", b"Control-Write-Encryption-Key", 32));
            }
        }
    }

    #[test]
    fn verify_requires_exact_state_and_no_error_tlv() {
        for step in [2, 4] {
            let good = tlv8::decode(&[6, 1, step]);
            assert!(validate_verify_reply(&good, step).is_ok());
            for wire in [vec![6, 1, step, 7, 1, 2], vec![6, 1, step, 7, 0], vec![6, 1, step + 1], vec![6, 2, step, 0], vec![]] {
                assert!(validate_verify_reply(&tlv8::decode(&wire), step).is_err());
            }
        }
    }
}
