# P3-5 MPV 压缩音频 AudioTrack/offload

## Recovery anchor

- Objective: 在 MPV 的 Android AudioTrack 路径上，对设备真实支持的 AAC/MP3 等压缩音频优先使用原生 compressed AudioTrack/direct/offload；初始化或运行写入失败时回到同轨 FFmpeg PCM，且播放参数只显示当前实际路径。
- Acceptance: 不双解码、不增加逐包热路径探测；IEC61937 直通（AC3/EAC3/DTS/TrueHD）和 DTS-HD -> DTS 一次性回退保持不变；压缩音频的 access-unit、时钟、seek/flush、切集和 AudioTrack 重建正确；失败后有界回退 PCM。
- User decision: approved by the user's request to complete the previously approved audio hardware-first work.
- Branch / baseline: `feature/mpv-audio-fallback-policy` / `3fdf9f82f37843699a2545ed97d4a2dd17b8ead5`.
- Protected pre-existing dirty paths: `AGENTS.md`, `app/.cxx/`.
- Scope: this document, master assessment entry, audio diagnostics wording, focused JVM tests, and the MPV AudioTrack/SPDIF/format sources needed for a narrow compressed-output implementation. No dependency lock upgrade, no Exo/IJK changes, and no new user setting.
- Rollback: revert the atomic P3-5 commit and restore native assets as one unit if native assets are changed.
- Cheapest decisive verification: diagnostics unit test; then native source/build validation and one device AudioFlinger observation for AAC/MP3.

## Current gap and evidence

- `audio/out/ao_audiotrack.c` maps every `AF_FORMAT_S_*` value to `AudioFormat.ENCODING_IEC61937`, writes `short[]`, and calculates timing from IEC carrier frames.
- `audio/decode/ad_spdif.c` wraps AAC/MP3 packets in IEC61937 and emits a fixed-stride mp_aframe. It does not expose raw access units to the AO.
- Android API source (`/Users/macbookpro/Downloads/bizhi/android-sdk/sources/android-36.1/android/media/AudioTrack.java`, `AudioManager.java`) documents compressed encodings (`ENCODING_MP3=9`, `ENCODING_AAC_LC=10`), byte-oriented encoded writes, and direct/offload support checks. `setOffloadedPlayback(true)` validates the offload path at construction and requires `USAGE_MEDIA`.
- AndroidX Media3 `AudioTrackAudioOutputProvider.java` and `DefaultAudioSink.java` (current `main`, accessed 2026-09-02) select output encoding from MIME, configure `AudioTrack.Builder`, account `framesPerEncodedSample` separately from byte count, and fall back after initialization/write failures.
- The V2453A probe showed AAC/MP3 direct support and an AudioFlinger compressed/offload track, while WebHTV MPV/Exo/IJK currently create PCM tracks. This proves the device path exists but is not connected to MPV.
- Kodi/VLC Android sink references were queried; their common AudioTrack path is PCM/IEC-oriented and does not provide a drop-in raw AAC/MP3 mpv adapter. No current upstream mpv commit adds native compressed AudioTrack support.

## Alternatives and decision

| Option | Result | Decision |
| --- | --- | --- |
| No change | Keeps stable PCM/IEC behavior but fails the device's available compressed path | Reject |
| Set `ENCODING_AAC_LC`/`ENCODING_MP3` for existing `AF_FORMAT_S_*` | Treats IEC frames as raw access units; breaks variable packet sizes, frame accounting, PTS and fallback | Reject |
| Run compressed and PCM outputs in parallel | Faster fallback in theory, but doubles decoder/output resources and violates performance contract | Reject |
| Copy Media3 sink wholesale | Correct model but incompatible with mpv's packet/aframe pipeline and ownership | Reject |
| Narrow MPV adaptation | Add an explicit raw compressed frame representation, preserve IEC formats, configure AudioTrack with real encoding/offload, account encoded frames, and let AO failure trigger existing PCM reinitialization | Adopt if source/build review confirms all lifecycle cases |

## Implementation boundary

1. Keep `AF_FORMAT_S_*` as IEC61937-only formats for existing passthrough.
2. Introduce only the minimum raw-access-unit representation needed for AAC/MP3; do not claim FLAC/Opus/Vorbis/DTS-HD without packet/CSD/seek evidence.
3. Carry byte length independently from PCM `sstride`; never derive compressed write length from channel count or fixed sample alignment.
4. Configure AudioTrack with the codec encoding, `USAGE_MEDIA`, direct/offload request only when the platform query permits it, and a bounded buffer. Initialization/write failure must release the track and request the existing PCM path once.
5. Keep encoded frame count for `ao_get_delay()` and playback position using codec-specific samples-per-access-unit, following Media3's accounting model.
6. Expose output mode from the observed MPV `audio-out-params/format`/runtime state, not from settings or capability inventory.

## Performance and compatibility contract

- No parallel decoder, no pre-warm, no extra resident thread, no per-packet capability query, and no new allocation in the steady-state write path beyond the existing bounded buffer.
- Preserve AC3/EAC3/DTS/TrueHD IEC61937 and DTS-HD fallback behavior, PCM fallback, pause/resume, seek/flush, AudioTrack recreation, and route changes.
- Android API levels without encoded AudioTrack support continue using current PCM/IEC behavior.
- The implementation remains experimental until a real AAC and MP3 sample is observed as compressed/direct/offload by AudioFlinger and seek/切集 passes.

## 文案修复（同一任务的低风险单元）

播放参数的真实来源是 `AudioPlaybackDiagnostics.decodeText()`。为与视频字段保持一致，`HARDWARE` 显示 `硬解`，`SOFTWARE` 显示 `软解`；`UNKNOWN` 的“解码待确认”保留。对应 JVM 断言同步更新。

## Verification and next action

- [x] Best-practice review recorded from Android API, Media3, local mpv source and device probe.
- [x] `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.AudioPlaybackDiagnosticsTest --no-daemon` passed (`BUILD SUCCESSFUL`, 4 tests).
- [x] App-side compressed capability probing now keeps a `Set<String>` until the final MPV option join; the affected Mobile arm64 Java compile and diagnostics test passed after fixing the type mismatch.
- [x] Raw compressed frame bridge is tracked in `third_party/patches/mpv-audiotrack-compressed-audio.patch`; the complete patch stack now applies to locked MPV source.
- [x] Build both MPV ABIs, verify ELF/assets, build Mobile arm64 APK.
- [ ] On V2453A, play AAC/MP3, capture `audio-out-params`, panel text, AudioFlinger track, seek and media replacement; verify one-shot PCM fallback on forced failure.

Current status: the deterministic `硬解`/`软解` wording unit, App-side compressed capability probe, and native raw AAC/MP3 frame bridge are tracked. The bridge preserves IEC61937 formats and is wired into the formal `scripts/build_mpv_native.sh` patch stack. The AV3A channel-layout fallback is now included; dual-ABI build, ELF/assets verification, and Mobile ARM64 APK packaging have passed. Device acceptance remains open because the authorized V2453A endpoint is currently absent from ADB and USB enumeration.

Next action: reconnect V2453A, install the APK, then run one continuous MPV session and one continuous Exo session with per-file completion evidence for every requested fixture.

## Checkpoint 2026-09-05 14:30 CST: TV internal-speaker route correction

-现场日志 `/private/tmp/webhtv-tv-online-log-current-20260905.txt` confirmed `devices=speaker:* route=false`, while `audio-spdif` still contained `aac,mp3` and the selected decoder was `spdif_raw_aac`.
- This is an App capability-gating bug: `getAudioCompressedCodecs()` reports platform direct support, but that support is not a usable passthrough route on a TV with only its built-in speaker. The failed compressed path stalls the audio clock; the resulting video timestamps are then about 960 ms late and are dropped by `vo_mediacodec_embed`.
- `MpvAudioCapabilities` now adds AAC/MP3 compressed codecs only when an HDMI/ARC/eARC/USB sink is present. With `route=false`, `audio-spdif` is empty and MPV uses its existing same-track PCM fallback. Existing IEC61937 codec probing is unchanged.
- Native `mpv-mediacodec-embed-timed-release.patch` is intentionally unchanged in this checkpoint. Revisit it only if the same sample still shows late drops after the audio output is confirmed PCM and advancing.
- Added focused route-gating tests for both no-route and routed cases. Next action: run the tests/compile once, install the APK, then re-capture TV logs to confirm `audio-spdif` is empty, `audio-out-params` is PCM, and `displayFps` is non-zero with drops no longer increasing rapidly.

## Checkpoint 2026-09-03 14:15 CST: build complete, device unavailable

- `scripts/build_mpv_native.sh --abi all --install` completed successfully; arm64-v8a and armeabi-v7a `libmpv.so` assets were regenerated from the locked MPV/FFmpeg source and patched stack.
- `scripts/verify_mpv_native_assets.sh --require-elf` passed for the stable native asset contract.
- `bash ./gradlew :app:assembleMobileArm64_v8aDebug --no-daemon` passed (`BUILD SUCCESSFUL`, 103 tasks); APK SHA-256 is `6b1139c49540a52a699616343aa71811fd1eb2cf2ad11380cf0122e69ab3ba07`.
- Device checks at 14:08-14:15 CST: `adb devices -l`, ADB restart, `adb mdns services`, and `system_profiler SPUSBDataType` found no V2453A endpoint. No install or runtime mutation was attempted after disconnect.
- Exo ALAC/AV3A source fixes remain covered by the committed E12-1/E12-2/E12-3 implementation and focused tests; no new Exo source change is required in this MPV task.
- Required per-file phone playback matrix is unverified and must not be reported as pass. The task remains open until the phone is reachable and each file reaches `audio-playable`, `first-frame`, or a terminal error with captured logs.
- Post-build patch integrity: `scripts/build_mpv_native.sh --abi arm64-v8a --prepare-only --incremental --work-dir build/mpv-native` passed after the hunk-format correction; `git -C build/mpv-native/mpv-android/buildscripts/deps/mpv apply --check --recount` and `git diff --check` both pass. The correction only changes nested patch blank-line encoding and does not alter compiled code or native asset contents.

## Device test library and fixed paths

The canonical device root is `/storage/emulated/0/Download/影音测试库/`. Do not rediscover or rename these fixtures in later device runs.

- MPV AAC: every file under `/storage/emulated/0/Download/影音测试库/A01_AAC/`:
  `AAC_LC_2.0_48kHz.mp4`, `AAC_HE_V1_2.0_44.1kHz.aac`, `AAC_HE_V2_2.0_44.1kHz.aac`, `AAC_5.1_声道.mp4`, `AAC_7.1_声道.mp4`.
- MPV/Exo AV3A: every file under `/storage/emulated/0/Download/影音测试库/A09_AVS3A/`. The `.mpd` files are playback entries; `.cmfa`/`.mp4` companions are also exercised directly for decoder/container isolation. The directory contains the 2.0, 5.1 and 7.1.4 CMAF/DASH/TS fixtures plus `AVS3_4K50_CMAF.cmfv` and `AVS3_4K50_DASH.mp4`.
- MPV MP3: `/storage/emulated/0/Download/影音测试库/A11_MP3/MP3_2.0_44.1kHz_128kbps.mp3`.
- Exo ALAC: `/storage/emulated/0/Download/影音测试库/A12_ALAC/ALAC_2.0_48kHz.mov` and `/storage/emulated/0/Download/影音测试库/A12_ALAC/ALAC_5.1_48kHz.mov`.

## Checkpoint 2026-09-03 11:14 CST: deadline device matrix

- Device: vivo V2453A, serial `10CF6H1D2L0009S`.
- Installed candidate: `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`, SHA-256 `80a13a3324587407ef3f304c15a1b79bc34b379ba17c217cd83b29b247028f1c`.
- Raw evidence root: `/private/tmp/webhtv-p3-5-deadline2/`.
- Exact issued paths are recorded in `/private/tmp/webhtv-p3-5-deadline2/mpv.paths` (23 entries) and `/private/tmp/webhtv-p3-5-deadline2/exo.paths` (19 entries). Raw application logs, logcat and AudioFlinger dumps are the corresponding `mpv.*` and `exo.*` files in that directory.
- The final matrix used one player-process start per intended kernel and continuous `ACTION_VIEW` source replacement inside that session. Earlier `/private/tmp/webhtv-p3-5-deadline/` evidence used an invalid per-file process restart and must not be used for lifecycle or source-switch acceptance.
- Confirmed MPV defect: AV3A 7.1.4 reaches AudioTrack creation, then FFmpeg reports `Rematrix is needed between 9 channels and 7 channels (FL+FR+FC+LFE+BL+BR+FLC) but there is not enough information to do it`; mpv follows with `Cannot open Libavresample context` and `libswresample failed to initialize`. This is a channel-layout/output-map bug, not a generic decoder capability failure.
- MPD entry limitation remains visible: some local DASH manifests report failure to open the first fragment and `unknown_format`; direct companion media results must not be conflated with MPD entry results.
- Exo result is invalid: the external preference rewrite used for the deadline batch did not switch the running kernel, and the captured `exo.logcat` identifies `kernel=mpv`/`start mpv`. Therefore no Exo AV3A/ALAC pass or fail is claimed from this batch.
- The 0.35-second dwell was sufficient to expose initialization failures but not sufficient to claim audible playback for every successfully opened file. A later acceptance run must wait for a per-file `audio-playable`/`first-frame` or a terminal error before advancing.
- Device ADB disconnected after evidence capture. No further device mutation was attempted.
- Next action: inspect and correct the AV3A 9-channel-to-device-output channel map in the MPV FFmpeg/mpv path before rebuilding; after installation, switch kernels through the App-supported runtime path or verify the loaded kernel before starting the Exo batch.

## Checkpoint 2026-09-03 05:54 CST

- V2453A command-line playback reproduced the AAC failure after compressed AudioTrack initialization was rejected: `ao/audiotrack` reported unsupported offload/direct creation, then OpenSLES treated the encoded frame as float and swresample failed with `unsupported conversion: aac -> float`.
- Root cause is localized to the OpenSLES fallback boundary: OpenSLES only accepts PCM, but its existing non-integer branch unconditionally rewrites unknown formats to `AF_FORMAT_FLOAT`.
- Updated `third_party/patches/mpv-audiotrack-compressed-audio.patch` to reject `af_fmt_is_encoded()` at `ao_opensles:init()` and return `-1`; MPV's existing compressed-output recovery can then rebuild the same track as PCM instead of attempting an encoded-to-float conversion.
- No changes were made to Exo, MPV AV3A MIME mapping, IEC61937 passthrough, or protected pre-existing dirty paths.
- Next action: validate patch application and rebuild both native ABIs, then build/install the APK and run every file in `A01_AAC`, `A09_AVS3A`, `A11_MP3`, and `A12_ALAC` through the Exo/MPV command-line playback matrix.

## Checkpoint 2026-09-02 20:14 CST

- Repaired the tracked compressed AudioTrack patch hunk against mpv `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`; the queue suspend/reset/resume path is now represented by a valid unified diff.
- `git diff --check` passed, and `git apply --check --recount --verbose third_party/patches/mpv-audiotrack-compressed-audio.patch` passed for every patched file before source preparation.
- `bash scripts/build_mpv_native.sh --abi arm64-v8a --prepare-only --incremental --work-dir build/mpv-native` passed; all locked sources were downloaded/pinned and the patch stack prepared successfully.
- Device playback, native compilation, and packaged asset verification remain pending; no claim is made yet about AAC/MP3 runtime behavior.
- Next action: build both MPV ABIs and verify the packaged assets before device playback.

## Checkpoint 2026-09-02 23:35 CST

- Corrected the `reload_audio_output()` patch hunk by restoring the blank context line required by MPV `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`.
- `bash .codex/scripts/task_guard.sh check` passed before the build.
- `scripts/build_mpv_native.sh --abi arm64-v8a --prepare-only --incremental --work-dir build/mpv-native` passed; all locked sources and the full patch stack prepared successfully.
- No production source or dependency lock was changed; only the task-owned patch and this record are in scope.
- Unresolved: native compilation, packaged asset identity, and V2453A playback remain unverified because ADB still reports no device.
- Next action: run the dual-ABI native build/install, then build and install the Mobile ARM64 APK.

## Checkpoint 2026-09-03 00:49 CST

- `scripts/build_mpv_native.sh --abi all --install --work-dir build/mpv-native` completed successfully; both `arm64-v8a` and `armeabi-v7a` outputs are ready.
- `bash scripts/verify_mpv_native_assets.sh --require-elf` passed for both ABIs and confirmed the locked asset contract.
- `bash ./gradlew :app:assembleMobileArm64_v8aDebug --no-daemon` passed (`BUILD SUCCESSFUL`); APK: `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`.
- Device verification is still blocked: after restarting ADB, `adb devices -l` is empty; `system_profiler SPUSBDataType` reports no Android/vivo USB endpoint, and `adb connect 192.168.1.9:5555` is refused.
- No claim is made yet about installation, AudioFlinger mode, panel text, or AAC/MP3 runtime playback.
- Next action: once the authorized USB or wireless ADB endpoint appears, install the APK and run the AAC/ALAC/AV3A/MP3 first-play and media-switch checks.

## Checkpoint 2026-09-03 00:58 CST

- Added the generated native asset directories to the active P3-5 scope because the rebuilt MPV/FFmpeg binaries are the runtime payload for this patch.
- Removed an unnecessary blank context line from the `reload_audio_output()` hunk; the prepared source had already been patched by the native build, so a second `git apply --check` against that non-clean tree is not meaningful.
- `bash .codex/scripts/task_guard.sh check` passes with the expanded scope. Device installation and runtime playback remain unresolved because USB enumeration is absent and `192.168.1.9:5555` refuses connections.
- Next action: commit/tag the locally verified native and patch state, then install and run device playback as soon as ADB exposes the phone.
