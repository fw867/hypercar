# Real encoder fixture

`csd0.annexb` is an Android HEVC encoder's Annex B VPS/SPS/PPS output.
`keyframe.annexb` is its independently decoded Main-profile IDR (NAL type 20).
The input was a synthetic solid-color probe, with no captured screen content.
The SPS reports a cropped picture size of 2732 × 794.

These fixtures verify real encoder SPS parsing, hvcC/hvc1 configuration,
screen-stream codec flags, sync gating, timestamps, and authenticated NAL framing.
They establish local wire handling; they do not establish vehicle interoperability.
