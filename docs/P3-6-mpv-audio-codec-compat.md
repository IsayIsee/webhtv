# P3-6：MPV MP3/AV3A 音轨兼容性

- User decision: approved for implementation.
- Objective: 修复 MPV 轨道模型对 AV3A 的 MIME 映射缺口，保证 MP3（含 MJPEG 封面流）和 AV3A 测试文件进入正确的音频轨道/诊断路径。
- Baseline: `5c104a199fa07ad5f33c575deb6e0b91eea6668a`, branch `feature/mpv-audio-fallback-policy`.
- Protected pre-existing paths: `.gitignore`, `AGENTS.md`, `app/.cxx/**`, `docs/音频DSP整合方案.md` and unrelated dirty files.
- Scope: `MpvPlayer` track MIME mapping and focused tests/documentation only. No MPV native binary or FFmpeg lock change.

## Best-practice review

| Evidence | Revision/path | Grade | Decision impact |
| --- | --- | --- | --- |
| MPV native build | `7e37f6d9b9faf93d984f96d30e82ee265af684de`, `scripts/build_mpv_native.sh`, `third_party/mpv-native-lock.json` | A | FFmpeg AV3A is already statically linked as `libarcdav3a`; native rebuild is unnecessary for this Java-side MIME reachability fix. |
| FFmpeg AV3A | `9cf9b48e9ec5150d73cae6af177e53ccc07b5262` and locked FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18` | A | AV3A software decode is available; there is no corresponding Android `av3a_mediacodec` contract to add to the hardware-first list. |
| MPV Java track model | `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`, `sampleMimeType`/`TrackInfo.toFormat` | A | MP3 is mapped to `audio/mpeg`, album-art video tracks are filtered; AV3A currently falls through to `audio/av3a`-less synthetic MIME, weakening track selection and diagnostics. |
| Test library | `/Users/macbookpro/Downloads/影音测试库/A11_MP3`, `A09_AVS3A` | A | MP3 includes an attached MJPEG stream; AV3A includes MP4/CMAF/DASH/TS and 2.0/5.1/7.1.4 fixtures. |

### Alternatives

- No change: leaves AV3A tracks represented as an unknown audio MIME and can make the current-track/diagnostic contract inconsistent.
- Add `av3a_mediacodec` to `ad`: rejected; no Android decoder capability or native wrapper exists, so this would cause failed initialization and waste fallback time.
- Narrow WebHTV adaptation: map codec strings containing `av3a`/`avs3a` to `MimeTypes.AUDIO_AV3A`, preserve existing MP3 mapping and album-art filtering, and keep AV3A on the existing FFmpeg decoder path. This adds no per-packet work, threads, copies, or native assets.

## Acceptance and rollback

1. MPV `TrackInfo.toFormat()` reports `audio/av3a` for AV3A codec strings from MP4/CMAF/DASH/TS.
2. MP3 remains `audio/mpeg`; an attached MJPEG stream is not exposed as the primary video track.
3. Existing `aac_mediacodec,mp3_mediacodec,amrnb_mediacodec,amrwb_mediacodec` ordering is unchanged.
4. Focused unit tests and mobile ARM64 Java compilation pass.
5. Real-device playback remains a follow-up when ADB is online; this task does not claim device playback from static checks alone.

Rollback is the baseline commit above; reverting the single P3-6 commit removes the MIME mapping without touching native assets or Exo artifacts.

## Implementation checkpoint

- 2026-09-02: E12-1 Exo implementation completed and tagged. P3-6 guard opened for the MPV-only MIME reachability correction.
- 2026-09-02: `MpvPlayer.audioSampleMimeType` now delegates to a no-state `MpvAudioMimeTypes` mapper that maps `av3a`/`avs3a` to `audio/av3a`; MP3 remains `audio/mpeg`. Added focused mapping tests without loading native/player static state. No native or decoder-list changes.
- Verification: `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests androidx.media3.mpvplayer.MpvAudioMimeTypesTest --tests androidx.media3.mpvplayer.MpvAudioDecoderPolicyTest :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon` passed (73 tasks, 40s). The initial direct `MpvPlayerTest` attempt was rejected as a JVM Android mock limitation (`SparseBooleanArray.append` during `MpvPlayer` static initialization), then replaced by the no-state mapper test.
- Test-library evidence: `A11_MP3/MP3_2.0_44.1kHz_128kbps.mp3` is MP3 stereo with an MJPEG cover stream; `A09_AVS3A` contains MP4/CMAF/DASH/TS AV3A 2.0/5.1/7.1.4 fixtures. No ADB device was online, so runtime playback remains unverified.
- P3-6 status: implementation and static/unit validation complete; native assets and FFmpeg lock remain unchanged.
