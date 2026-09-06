# P6-1 MPV P8.1 HDR10 fallback

Status: implementation in progress (2026-09-05, Asia/Shanghai)

## Objective

When MPV sees a Dolby Vision Profile 8.1 stream, preserve native Dolby Vision
when the device advertises a hardware DV decoder. If that capability is
unsupported but a regular HEVC Main10 decoder accepts the same dimensions and
rate, explicitly select an HDR10 base-layer path. The native path must remove
P8.1 RPU NAL units before the decoder, synchronize filtered codec parameters,
and expose `降级HDR10` in diagnostics. Unknown capabilities keep software
decoding. Profile 7 behavior is unchanged.

## Evidence and decision

- FFmpeg `FongMi/FFmpeg` revision
  `177f090e0503b7e013922ca903bde14b1c375f18`, `libavcodec/bsf/dovi_split.c`
  (source: https://github.com/FongMi/FFmpeg/blob/177f090e0503b7e013922ca903bde14b1c375f18/libavcodec/bsf/dovi_split.c,
  accessed 2026-09-05). `mode=bl` keeps ordinary HEVC NAL units, drops
  NAL type 62 (RPU) and 63 (EL), and clears DOVI present flags in `par_out`.
  This is the correct primitive for P8.1 base-layer output; the existing
  `dovi_rpu convert=p81` path is for the opposite P7 -> P8.1 conversion.
- Android `MediaCodecInfo.CodecCapabilities.isFormatSupported()` and
  `VideoCapabilities` (official API reference:
  https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities,
  accessed 2026-09-05) evaluate the supplied MIME/profile/size/rate. A DV
  MIME query cannot prove ordinary HEVC Main10 support, so the app must perform
  a second `video/hevc` query.
- Local MPV patch
  `third_party/patches/mpv-dovi-profile7-hdr10-base-layer.patch` is the
  validated WebHTV implementation for Profile 7 `mode=bl`, including packet
  length checks, safe ownership, `par_out` synchronization, and direct
  MediaCodec output. It is deliberately not reused as the P8.1 policy patch.
- Local MPV source revision is
  `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`; mpv-android builder is
  `99a60ad2141d5ace94453590903c2c6b9a0a2443`. No upstream commit in the
  assessed tree adds this App capability split or P8.1 base-only option.

### Alternatives

| Approach | Decision |
| --- | --- |
| No change | Reject: unsupported P8.1 remains on software/GPU despite a usable HEVC decoder. |
| Force `hwdec=mediacodec` | Reject: leaves RPU/DV metadata in the access unit and can fail or mislabel output. |
| Reuse DV7 `p81` conversion patch | Reject: converts Profile 7 to P8.1 and does not strip native P8.1 RPU. |
| WebHTV-adapted separate profile8 option and base-only filter | Recommend: preserves DV7 semantics, has explicit diagnostics, and is reversible. |

## Implementation plan and acceptance criteria

1. Add a regular HEVC/HDR10 capability query and pass it into
   `MpvAutoOutputPolicy` for source Profile 8.1 only.
2. Add an independent `demuxer-dovi-profile8=preserve|hdr10` option and lock
   its value for the playback session; a capability change causes one rebuild.
3. Add a separate native patch that enables `dovi_split=mode=bl` for
   `dv_profile == 8`, clears stale DV metadata/codec parameters, and emits
   `P8.1 HDR10 fallback: stripping RPU before decoder.`
4. Rebuild `arm64-v8a` and `armeabi-v7a`, verify ELF/native markers, compile
   App tests, install through ADB, and play the P8.1 samples by direct command.

Acceptance: native DV P8.1 remains preserve; unsupported P8.1 + HEVC supported
selects direct MediaCodec with `降级HDR10`, `hwdec-current=mediacodec`, no black
or garbled frames, and the P8.1 RPU stripping marker in logs. Unsupported or
unknown HEVC capability remains software. Seek, replay, and source replacement
must not change the selected output mode. DV7 tests and behavior remain green.

Rollback: revert the single P6-1 commit and restore the prior MPV native assets;
the existing Profile 7 patches and options remain intact.

## Progress

- [x] Baseline and protected dirty paths recorded.
- [x] Focused FFmpeg/Android/local implementation research recorded.
- [x] App policy, capability query, and independent Profile 8 option.
- [x] Native P8.1 base-only patch and build integration.
- [x] arm64-v8a native build completed; armeabi-v7a build in progress.
- [ ] JVM, native asset, APK, and device verification.

Recovery anchor: objective and acceptance are above. Java policy/configuration
and the separate native P8.1 base-layer patch are implemented. arm64-v8a has
linked successfully; the armeabi-v7a build is still running. Current next
action is to finish the dual-ABI build, verify assets, then package/install and
run direct ADB P8.1 playback checks.
