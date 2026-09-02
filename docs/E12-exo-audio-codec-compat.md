# E12: Exo 音频编解码兼容性

- User decision: approved for implementation.
- Objective: 修复 Exo 的 ALAC/AV3A 解码异常，并保证 MP3 在切轨、seek 和不同容器下稳定输出 PCM。
- Baseline: `d00aa5737980d976cbae491948bf65dab906bf68`, branch `feature/mpv-audio-fallback-policy`.
- Protected pre-existing paths: `.gitignore`, `AGENTS.md`, `app/.cxx/**`, `docs/音频DSP整合方案.md` and unrelated dirty files.
- Cheapest decisive verification: Media3/renderer unit tests plus `:app:compileDebugJavaWithJavac`; representative test-library metadata and decoder-path checks.

## Best-practice review

### Evidence

| Source | Revision/path | Grade | Claim and WebHTV impact |
| --- | --- | --- | --- |
| nextlib | `323340522493b871f1d22c0c1c72aa9667b3411e`, `media3ext/src/main/cpp/ffaudio.cpp` | A | FFmpeg 7 requires `AVChannelLayout`/`av_opt_set_chlayout`; the local AV3A patch already incorporates this semantic fix. |
| FongMi/media | `d7083781e629ad1c4683a687261374065fb38925` and `66762a253b12652f3c28420ed0f4f7507a1451cd` | A | AV3A MIME/TS reader support exists, but the shipped 1.11 extractor tree must also recognize MP4 `av3a` sample entries. |
| Media3 source | `libraries/extractor/.../BoxParser.java`, `Mp4Box.java` at locked artifact | A | Locked `media3-extractor` contains `Av3aReader`/TS support but no `TYPE_av3a` MP4 audio branch; CMAF/DASH samples therefore cannot reach `audio/av3a`. |
| nextlib AAR | local `nextlib-media3ext-1.10.0-0.12.1-fongmi-softload-av3a-ffmpeg901-r1` | A | Both ABIs contain `libarcdav3a`, ALAC/MP3 decoders and the patched `swr_alloc_set_opts2`/`extended_data` symbols. Native codec presence is not enough if extractor MIME is unreachable. |
| FFmpeg/Media3 ALAC contract | Media3 `FfmpegAudioDecoder#getAlacExtraData`, `CodecSpecificDataUtil.parseAlacAudioSpecificConfig` | A | ALAC magic cookie must be wrapped as an `alac` atom; sample rate/channel/bit depth come from the cookie and must agree with the PCM sink. |
| Test library | `/Users/macbookpro/Downloads/影音测试库` | A | ALAC samples are 24-bit stereo and 16-bit 5.1; MP3 includes an attached MJPEG cover stream; AV3A includes MP4/CMAF/DASH/TS and 2.0/5.1/7.1.4. |

### Alternatives and decision

- No change: reject. It leaves MP4/CMAF AV3A unreachable and does not provide a falsifiable fix for the reported failures.
- Unmodified upstream nextlib patch: insufficient. It fixes FFmpeg channel-layout API use but does not add Media3 MP4 AV3A extraction and does not validate the App's output-channel policy.
- WebHTV-adapted design: add the narrow MP4 AV3A extractor hunk, preserve the existing nextlib native patch, normalize AV3A/ALAC formats in `CompatFfmpegAudioRenderer`, and add focused tests for MIME, channel fallback and ALAC/MP3 input contracts. This keeps one FFmpeg/AAR producer and avoids extra decoder threads or per-packet work.

### Acceptance criteria

1. MP4/CMAF/DASH `av3a` produces a Media3 `audio/av3a` track and reaches `libarcdav3a`.
2. ALAC 24-bit stereo and 16-bit 5.1 decode to a sink-supported PCM format without `swr_init`/channel-layout failures.
3. MP3 with attached MJPEG metadata selects the MP3 audio stream and emits PCM after cold start, seek and media replacement.
4. Unsupported multichannel output is explicitly downmixed only when the sink rejects the source layout; no silent format rejection.
5. Existing DTS/TrueHD/AAC and direct/offload behavior remains unchanged.

### Rollback and status

- Rollback anchor: `d00aa5737980d976cbae491948bf65dab906bf68` and recovery tag `recovery/P3-5-aac-compressed-fallback/20260902201621-d00aa5737980`.
- E12 research identifies the missing Media3 MP4 AV3A branch as the concrete first implementation action.
- Next action: implement E12-1 with Media3 extractor patch, rebuild/publish the coupled artifacts, then compile and run representative tests.

## Checkpoint 1: 2026-09-02 20:52 CST

- E12-1 implementation: added the reproducible Media3 MP4/CMAF `av3a` sample-entry and RFC 6381 MIME mapping patch; wired it into `scripts/build_media_deps.sh` and `third_party/media-lock.json`.
- App adapter: `CompatFfmpegAudioRenderer` now normalizes `audio/mp4 + av3a.*` and fills missing ALAC sample rate/channel count from the magic cookie before sink capability checks.
- Validation so far: `git -C third_party/sources/media apply --check --unidiff-zero third_party/patches/media3-exo-av3a-mp4.patch` passes; source AAR inspection confirms both ABIs contain AV3A/ALAC/MP3 decoders and patched swresample symbols.
- Media3 publication: rebuilt and retained only the affected `media3-common`, `media3-container`, and `media3-extractor` coordinates. AAR/source SHA-256 values are recorded in `third_party/media-lock.json`; unaffected modules were restored to the baseline publication.
- Verification: `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests io.github.anilbeesetti.nextlib.media3ext.ffdecoder.CompatFfmpegAudioRendererTest --tests androidx.media3.mpvplayer.MpvAudioDecoderPolicyTest --tests com.fongmi.android.tv.player.AudioPlaybackDiagnosticsTest :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon` passed (73 tasks, 46s). The test-library metadata confirms ALAC/MP3/AV3A fixtures are present; no ADB device was online for playback.
- E12-1 status: Exo source, patch, publication and App compile contracts are complete. MPV AV3A track mapping remains a separate P3-6 unit and is not claimed here.

## Checkpoint 2: 2026-09-02 22:45 CST

- E12-2: `ExoCompressedAudioDirectPolicy` now converts runtime failures escaping from the locked Media3 `AudioTrackAudioOutputProvider.getOutputConfig()` (notably `AudioTrack.getMinBufferSize()` invalid channel masks) into `AudioOutputProvider.ConfigurationException`. This preserves the Media3 `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` contract and prevents `ERROR_CODE_FAILED_RUNTIME_CHECK` from bypassing the engine's audio recovery path.
- Verification: `bash .codex/scripts/task_guard.sh check` passed; `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.exo.ExoCompressedAudioDirectPolicyTest --no-daemon` passed (73 tasks, 0 failures).
- Remaining gap: ALAC/AV3A PCM renderer still trusts `AudioSink.supportsFormat()` for multichannel PCM, while the target HAL can reject the corresponding channel mask. The next narrow fix must probe `AudioTrack.getMinBufferSize()` before selecting the FFmpeg decoder output channel count and downmix only when the route rejects the source layout.
