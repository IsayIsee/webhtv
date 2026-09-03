# P3-7 MPV AV3A unknown-channel downmix

## Recovery anchor

- Objective: 修复 MPV 播放 AV3A 5.1 混合内容时 9 声道 PCM 无法下混到 Android stereo AudioTrack 的失败。
- Scope: MPV `f_swresample` 行为、现有 AudioTrack patch、双 ARM ABI native assets、任务文档和 master assessment 索引；不改 Exo、IJK、FFmpeg lock、AVS3 视频 decoder 或 JNI API。
- Baseline: branch `feature/mpv-audio-fallback-policy`, commit `69191f78a37c5d56f87591c857d2d4be0112815d`.
- Protected pre-existing dirty paths: `.gitignore`, `AGENTS.md`, `app/.cxx/**`, `docs/音频DSP整合方案.md`.
- Acceptance: AV3A 5.1 CMAF/DASH/TS entry reaches audible stereo output or a later terminal error; 2.0 and 7.1.4 keep working; known-layout PCM, AAC/MP3 raw output and IEC61937 passthrough remain unchanged; both ABI assets and ELF namespace checks pass.
- Rollback: revert the atomic P3-7 commit and restore both `libmpv.so` assets together; no lock or App JNI rollback is required.
- Next action: when serial `10CF6H1D2L0009S` reappears in ADB, install the Mobile arm64 APK and perform one-by-one playback verification.

## Current evidence

Device: vivo V2453A, Android API 35, serial `10CF6H1D2L0009S`.

Representative command:

```text
adb -s 10CF6H1D2L0009S shell am start -W -a android.intent.action.VIEW \
  -d file:///storage/emulated/0/Download/影音测试库/A09_AVS3A/AVS3A_5.1_TS.ts \
  -t video/mp2t com.fongmi.android.tv
```

Observed MPV log:

```text
SWR: Rematrix is needed between 9 channels and stereo
but there is not enough information to do it
[swresample:fatal] libswresample failed to initialize.
[af:fatal] Cannot convert decoder/filter output to any format supported by the output.
```

`libarcdav3a` successfully emits 9-channel PCM for the 5.1 fixture. AVS3A mixed-content headers describe a 5.1 sound bed plus three object channels; the decoder therefore returns `sound-bed channels + object channels`, not a fixed 9-speaker layout. `libarcdav3a_set_channel_layout()` only has fixed layouts for 5.1.4 (10), 7.1.2 (10), 7.1.4 (12), and HOA order 3 (16); the 9-channel result intentionally falls back to an unknown layout.

In `filters/f_swresample.c`, the existing unknown-layout guard changes both input and output maps to `UNSPEC`. FFmpeg then rejects a channel-count change unless `rematrix_custom` is set. The AudioTrack patch already forces outputs over eight channels to stereo, but that happens after filter configuration and cannot fix this initialization failure.

## Decision-shaped research question

Can WebHTV make an unknown AV3A object/mixed channel stream playable without inventing speaker positions or changing known-layout remix behavior?

Hypothesis: a custom index-ordered matrix is sufficient and is safer than assigning object channels fake speaker identities. Counter-hypothesis: FFmpeg's automatic remixer or a fabricated 9-speaker layout would preserve better quality. The decisive evidence is the `swr_init()` contract plus the actual AVS3A output semantics and device failure log.

## Evidence

| Claim | Source / revision | Grade | WebHTV applicability | Decision impact |
| --- | --- | --- | --- | --- |
| Unknown input/output layouts with differing channel counts require a custom matrix | FFmpeg `libswresample/swresample.c`, local FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18`, `swr_init()` check; `libswresample/swresample.h` `swr_set_matrix()` contract | A | Exact binary source used by MPV | Set `rematrix_custom` only for the narrow unknown-to-stereo case. |
| `swr_set_matrix()` accepts an uninitialized context and uses `matrix[input + stride * output]` | https://ffmpeg.org/doxygen/trunk/group__lswr.html; local `libswresample/rematrix.c` | A | Same FFmpeg API and ABI | Build a two-row matrix before `swr_init()`, with stride `MP_NUM_CHANNELS`. |
| MPV preserves input order through a custom AV layout for known maps but leaves unknown maps `UNSPEC` | MPV commit `f5d4d9b029affa4d5b7eb13b28d91a96e6a92280`, current tree `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`, `audio/chmap_avchannel.c` | B/A | Current locked MPV source | Do not replace the upstream channel-order logic; supplement only its unknown-layout gap. |
| AVS3A mixed content is sound bed plus object channels and total output count is additive | local `dependency/avs3a/src/decoder.c`, `avs3_init_dec.c`, FFmpeg wrapper `libarcdav3a.c` | A | Exact decoder producing the failing 9-channel frame | Do not assign fake fixed speaker positions to object channels. |
| The failure is reproducible on the target phone and occurs after AV3A decoding | WebHTV ADB/logcat evidence above | A | Exact user-visible regression | Audio filter initialization, not codec availability, is the repair boundary. |
| Mature Android sinks use explicit channel masks and do not provide a generic AV3A object downmix | Kodi `AESinkAUDIOTRACK.cpp`, VLC Android AudioTrack paths (reviewed 2026-09-03) | B | Confirms no drop-in implementation exists | Keep the adaptation MPV-local and avoid copying unrelated sink code. |

The academic/paper category is not applicable to this bounded correctness fix: the decision is governed by a public FFmpeg API contract and codec output semantics, not a new algorithm or performance claim. No paper can change the required `swr_set_matrix()` initialization rule.

## Alternatives

| Option | Assessment | Decision |
| --- | --- | --- |
| No change | Preserves existing behavior but leaves all unknown 9-channel AV3A 5.1 files unplayable on stereo devices. | Reject. |
| Fabricate a fixed 9-speaker AV layout in `libarcdav3a` | Lets FFmpeg auto-remix, but labels object channels as physical speakers and can misroute positional objects; it also changes FFmpeg output metadata for every consumer. | Reject. |
| Use FFmpeg automatic `swr_build_matrix2()` on a fabricated layout | Same semantic problem, plus behavior depends on guessed speaker identities and default coefficients. | Reject. |
| Copy an Android/Media3 sink downmix implementation | Those sinks operate on already-decoded PCM/output masks and do not solve MPV's unknown `AVChannelLayout` initialization contract. | Reject. |
| Narrow WebHTV adaptation | Keep the decoder's unknown layout; preserve the target stereo map; set a bounded, index-ordered custom matrix only when input is unknown and has more than two channels. | Adopt. |

The adopted matrix gives every input channel a deterministic contribution to both stereo outputs with per-row gain normalized to at most 1.0. This guarantees a playable fold-down without claiming that object positions were reconstructed. Known channel layouts continue through FFmpeg's existing remix logic.

## Implementation and contracts

1. In `filters/f_swresample.c`, detect `mp_chmap_is_unknown(&map_in) && map_in.num > 2 && mp_chmap_is_stereo(&map_out)` before the existing unknown-map shortcut.
2. Keep the output map as stereo for this case; leave the input map unknown so its channel count and order remain intact.
3. Fill a two-output matrix using input index order, then normalize each output row if its absolute coefficient sum exceeds 1.0.
4. Call `swr_set_matrix()` before `swr_init()` and fail through the existing filter error path if it returns an error. Emit one diagnostic marker identifying the fallback and input channel count.
5. Do not change `libarcdav3a`, `chmap_avchannel.c`, AudioTrack masks, encoded AAC/MP3 frame handling, IEC61937, decoder selection, locks, JNI or App behavior.

Quality limitation: an unknown AV3A object stream cannot be spatially rendered from channel indices alone. The accepted behavior is safe stereo audibility and deterministic energy preservation, not semantic object placement.

## AVS3 video assessment

The same TS sample also logs `Failed to initialize a decoder for codec 'avs3'`. FFmpeg already contains `AV_CODEC_ID_AVS3`, parser/demux mappings and a `libuavs3d` wrapper in the source tree, but the current MPV native build does not expose a working AVS3 decoder on the device. Supporting the test-library AVS3 video samples may require enabling/packaging `libuavs3d`, validating extradata/SPS probing, and adding a separate decoder/native asset verification matrix. That is independent of the AV3A audio filter fix and changes native dependency ownership/package size, so it is explicitly deferred to a separately approved MPV stage; it is not silently included in P3-7.

## Verification

- Cheapest decisive check: patch applies with `git apply --check --recount` to the locked MPV source and the modified C file compiles in the native build.
- Native: rebuild `arm64-v8a` and `armeabi-v7a` from the locked graph with the existing patch order; run `scripts/verify_mpv_native_assets.sh --require-elf`; record artifact hashes, SONAME and DT_NEEDED.
- App: build Mobile arm64 debug and confirm packaged `libmpv.so` matches the rebuilt asset.
- Device: use direct ADB commands, no UI and no batch script; test each AV3A entry separately: 2.0 CMAF MPD, 2.0 DASH MPD, 2.0 TS, 5.1 CMAF MPD, 5.1 DASH MPD, 5.1 TS, 7.1.4 CMAF MPD and 7.1.4 DASH MPD. Record `audio-playable`/`first-frame` or terminal error and the relevant MPV log for each. Companion `.cmfa`/`.mp4` files are isolation inputs, not MPD entry replacements.
- Regression neighbors: one known 2.0 and one known 7.1.4 sample must still start; AAC/MP3 and IEC61937 are covered by the previous P3-5/P3 work and are not re-tested unless the native build reports a related regression.

## Checkpoint 2026-09-03 18:33 CST

- Completed: baseline recovery, source review, FFmpeg/MPV/AVS3A evidence review, and design decision.
- Workspace: branch `feature/mpv-audio-fallback-policy`, HEAD `69191f78a37c5d56f87591c857d2d4be0112815d`; protected dirty paths unchanged.
- Files changed: this task record and master assessment entry pending; code patch not yet applied.
- Validation: target log reproduces 9-channel unknown-layout `swr_init()` failure.
- Rollback anchor: `69191f78a37c5d56f87591c857d2d4be0112815d`.
- Unresolved: source patch, dual-ABI rebuild, APK installation and per-file device acceptance.
- Next action: apply the narrow `f_swresample` patch and run the patch-application check.

## Checkpoint 2026-09-03 19:28 CST

- Completed: narrow `f_swresample` custom-matrix patch, dual-ABI native rebuild, ELF/native asset verification, and Mobile arm64 Debug APK packaging.
- Source/build evidence: `bash scripts/build_mpv_native.sh --abi all --install --work-dir build/mpv-native` completed successfully; `bash scripts/verify_mpv_native_assets.sh --require-elf` passed for `arm64-v8a` and `armeabi-v7a`.
- Artifact evidence: APK `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk` SHA-256 `799ec48f0681db69c97863b1bc8a2d974aded4b4959011a3804ccdcf7ad45865`; packaged `assets/mpv-libs/arm64-v8a/libmpv.so` SHA-256 `140879199388416782253c7c24e1da9003c2b3425918586aae246c0410dd47c4`, identical to `build/mpv-native/output/arm64-v8a/libmpv.so`.
- Workspace: branch `feature/mpv-audio-fallback-policy`, HEAD `69191f78a37c5d56f87591c857d2d4be0112815d`; protected dirty paths unchanged.
- Validation: `git diff --check` passed; APK build completed with `BUILD SUCCESSFUL in 55s`.
- Device result: user confirmed the rebuilt APK is installed and AV3A 5.1 playback is now audible on vivo V2453A. The previous `libswresample failed to initialize` failure is no longer observed for the tested 5.1 path.
- Volume note: 5.1 can sound quieter than stereo because the fallback intentionally applies equal gain (`1 / input_channels`) to each of the nine unknown channels to prevent clipping while preserving deterministic audibility. This is expected for the conservative fallback; semantic/spatial loudness matching remains a separate quality improvement.
- Unresolved: the current acceptance is based on the user's confirmed playback result; AVS3 video decoding remains separately deferred as documented.
- Rollback anchor: `69191f78a37c5d56f87591c857d2d4be0112815d`; revert the eventual atomic P3-7 commit and restore both MPV ABI assets together.
- Next action: when serial `10CF6H1D2L0009S` reappears, install the APK with installer assist and test each AV3A entry individually via direct ADB commands.
