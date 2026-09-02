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
- [ ] Build both MPV ABIs, verify ELF/assets, build Mobile arm64 APK.
- [ ] On V2453A, play AAC/MP3, capture `audio-out-params`, panel text, AudioFlinger track, seek and media replacement; verify one-shot PCM fallback on forced failure.

Current status: the deterministic `硬解`/`软解` wording unit, App-side compressed capability probe, and native raw AAC/MP3 frame bridge are tracked. The bridge preserves IEC61937 formats and is wired into the formal `scripts/build_mpv_native.sh` patch stack; native binaries and device behavior remain pending.

Next action: build both MPV ABIs, verify ELF/assets, then build and install the Mobile ARM64 APK for device playback evidence.

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
