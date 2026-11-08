# Showcase independent wire vectors

`showcase-wire.json` uses synthetic test data only. Source revision:
`amineross/showcase@34980e930a0b3245ffb6b49f22187630dac525ab`.
The fixture records the SHA256 of `source/vendor/monocypher/monocypher.c`.

Generation was independent of HyperCar's Rust HKDF, AEAD, and packet builders:

1. Python stdlib `hmac` + `hashlib.sha512`: shared secret is bytes 0..31,
   streamConnectionID is decimal 1234567890123456. HKDF extract uses
   `DataStream-Salt1234567890123456`; expand uses
   `DataStream-Output-Encryption-Key` followed by byte 1, truncated to 32 bytes.
2. An Android arm64 C probe was compiled with NDK 27.2 against the pinned
   Monocypher C source. For each packet it calls `crypto_aead_init_ietf`,
   `crypto_aead_write`, then initializes again and uses `crypto_aead_read`
   to verify the result and plaintext. Outputs are ciphertext followed by tag.
3. VideoConfig: 128-byte zero header; body size LE at 0, opcode 1 at 4,
   flag 4 at 6, f32 LE width 1920 and height 720 at 16 and 20;
   avcC `0142001fffe100046742001f0100026801` follows unencrypted.
   The intentionally truncated SPS tests source-size fallback, not decoding.
4. Video frames: 128-byte header as AAD, opcode 0, ciphertext+tag size at 0,
   NTP 32.32 LE timestamp at 8 for 2000 and 3000 microseconds.
   Plain NAL payloads are `000000026502` and `000000024103`.
   Nonces are four zero bytes followed by LE64 0 and 1; config consumes no nonce.
5. PCM: 704 interleaved i16 samples `(i % 2000) - 1000`, serialized BE,
   repeated for two packets. RTP uses PT 100, first-packet marker, sequence
   0 then 1, timestamp 0 then 352, SSRC zero. AAD is header bytes 4..12.
   Nonces are as above; append LE64 0 then 1 after each ciphertext and tag.

The local probe and executable are retained in
`D:\devtools\hypercar-diagnostics\showcase-wire-probe.c` and
`D:\devtools\hypercar-diagnostics\showcase-wire-probe`.
An additional C `offsetof(struct tcp_info, ...)` probe on Android confirmed
unacked=24, rtt=68, total_retrans=100, bytes_acked=120, notsent_bytes=144.
The Rust reader also bounds each field by getsockopt's actual returned length.

Tests compare the actual sender socket output to these fixed bytes; they do
not validate playback on BMW, H.264 picture decoding, or physical audio output.


## Binary plist integer fixtures

`bplist-negative-integers.json` is generated independently with Python stdlib `plistlib.dumps(value, fmt=plistlib.FMT_BINARY)`. Its nine scalar negative values include -1, a negative business status and i64::MIN. The Rust regression compares complete encoded bytes, not only a local round trip. Apple CoreFoundation CFBinaryPList.c documents that bplist00 1/2/4-byte integers are unsigned and negative values use 8-byte signed integers. Positive widths are deliberately kept unchanged for existing receiver compatibility.
