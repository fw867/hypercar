//! Explicit source codec; wire syntax must never be guessed from NAL bytes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VideoCodec { H264, Hevc }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FrameOrigin { Live, Cached, Unknown }

impl FrameOrigin {
    pub fn as_str(self) -> &'static str {
        match self { Self::Live => "live", Self::Cached => "cached", Self::Unknown => "unknown" }
    }
}

impl VideoCodec {
    pub fn from_id(id:i32)->Option<Self>{match id {0=>Some(Self::H264),1=>Some(Self::Hevc),_=>None}}
    pub fn as_str(self)->&'static str {match self {Self::H264=>"h264",Self::Hevc=>"hevc"}}
}
