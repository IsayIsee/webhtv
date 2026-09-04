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
| nextlib AAR | local `nextlib-media3ext-1.10.0-0.12.1-fongmi-softload-av3a-ffmpeg901-r2` | A | Both ABIs contain `libarcdav3a`, ALAC/MP3 decoders and the patched `swr_alloc_set_opts2`/`extended_data` symbols. Native codec presence is not enough if extractor MIME is unreachable. |
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

## Checkpoint 3: 2026-09-02 23:02 CST

- E12-3: `CompatFfmpegAudioRenderer` now combines the Media3 sink check with an `AudioTrack.getMinBufferSize()` channel-mask probe before preserving multichannel FFmpeg output. When the target route rejects the source layout, the existing decoder-side stereo downmix path is selected before the first output buffer; unknown sample rates remain conservative and are handled by the normal sink path.
- Verification: `bash .codex/scripts/task_guard.sh check` passed; `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests io.github.anilbeesetti.nextlib.media3ext.ffdecoder.CompatFfmpegAudioRendererTest :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon` passed (73 tasks, 0 failures).
- Device validation remains pending because ADB is currently offline; the next device run must replay ALAC 2.0/5.1 and AV3A fixtures and confirm no `Invalid channel configuration` or `unsupported conversion` logs.

## Checkpoint 4: 2026-09-04 02:05 CST

- E12-4 diagnosis: DASH MPDs use the AVS3 P7 scheme `urn:avs:avs3:p7:2024:audio_channel_configuration`, which the locked `DashManifestParser` treated as unknown. This left `Format.channelCount` at `Format.NO_VALUE`, so `CompatFfmpegAudioRenderer` reported `NO_UNSUPPORTED_SUBTYPE` before creating the `libarcdav3a` decoder.
- Implementation: added `media3-exo-av3a-dash-channel-config.patch` and wired it after the existing AV3A MP4 patch. The parser maps only the observed, specified values `F20101 -> 2`, `F20203 -> 6`, and `F20A04 -> 12`; missing, invalid, or future values remain `Format.NO_VALUE`. Added a focused `DashManifestParserTest` covering all three values and an unknown value.
- Reproducibility: clean Media3 `e3e922d5c01bc0b564849940fe589daf37360d15` worktree accepted the patch with `git apply --check --unidiff-zero`. Patch SHA-256 is `76a1a876c8d8493a54826ad54a789ed487e9d11fe324c701af58821b4539b987`.
- Verification: `:lib-exoplayer-dash:compileDebugJavaWithJavac` and `:lib-exoplayer-dash:publishReleasePublicationToMavenRepository` succeeded; the published Dash AAR/source hashes are `de557f0b91512a1e0726155bd0cbb23879279ce05a819ab27a49bea069225546` and `5462494d6ed12e64b5597070b86206727775c760ebe040ef6d3a3a51244431d4`. `:app:compileMobileArm64_v8aDebugJavaWithJavac` and `:app:assembleMobileArm64_v8aDebug` succeeded; APK SHA-256 is `968d1530db61a1b587b893b5fdfce75be16b52700b369c8e3e1283b026ee1e8f`.
- Unit-test caveat: `DashManifestParserTest` compiled but Robolectric failed before assertions because the host `DefaultSdkProvider` could not provide an Android SDK. This is an environment failure, not a parser assertion failure.
- Device caveat: ADB was initially reachable in the prior run but is empty/offline in this run, including after `adb start-server`; APK installation and the six individual MPD playback checks are therefore not yet verified. The next action is to install this APK when serial `10CF6H1D2L0009S` returns and test each MPD separately via `video/*` intent, recording `supported=YES`, `libarcdav3a`, and actual audio output.

## Checkpoint 5: 2026-09-04 07:30 CST - AV3A 5.1 unknown-layout conversion

- User authority: approved implementation after requesting mature implementation and best-practice research for the Exo AV3A 5.1 failure.
- Baseline: branch `feature/mpv-audio-fallback-policy`, HEAD `639a046125c4375685cb96c9ea004b620778bbb9`; protected dirty paths remain `.gitignore`, `AGENTS.md`, `app/.cxx/**`, `docs/音频DSP整合方案.md` and unrelated generated paths.
- Decision-shaped question: how can Exo convert AV3A mixed-content output when the decoder returns an unknown 9-channel layout without assigning object channels fake speaker positions?
- Local evidence: `/private/tmp/exo-av3a-51-cmaf-repro.txt` records `audio/av3a`, declared `channels=6`, `supported=YES`, native `Downmixing FFmpeg audio from 9 to 6 channels`, then `Error in swr initialization: Invalid argument` and `ERROR_CODE_DECODING_FAILED`. The aggregate capture repeats the same failure for 9->6 and 9->2 attempts; 16->12 7.1.4 reaches `audioTrackInit` and `rendererReady=true`.

### Best-practice evidence for this repair

| Claim | Source / revision | Grade | WebHTV applicability | Decision impact |
| --- | --- | --- | --- | --- |
| FFmpeg rejects a channel-count change when either layout is `AV_CHANNEL_ORDER_UNSPEC` unless a custom matrix is supplied | FongMi/FFmpeg `libswresample/swresample.c` at `177f090e0503b7e013922ca903bde14b1c375f18`, `swr_init()` guard around lines 327-332 | A | Exact FFmpeg source locked by `third_party/media-lock.json` | Configure a matrix before `swr_init()` only for the unknown-layout conversion branch. |
| `swr_set_matrix()` is the public pre-init API; coefficients are indexed as `matrix[i + stride * o]` | FFmpeg API docs, https://ffmpeg.org/doxygen/trunk/group__lswr.html, `libswresample/swresample.h` at the same locked source revision | A | Same ABI used by nextlib AAR | Use a bounded row-major matrix with stride equal to the input channel capacity. |
| AVS3A mixed mode appends object channels after the sound-bed channels | FongMi/FFmpeg `dependency/avs3a/src/decoder.c` and `src/avs3_com.c` at `177f090e0503b7e013922ca903bde14b1c375f18`; `numChansOutput = sound-bed channels + numObjsOutput`, interleaved by index | A | Explains the exact 9-channel frame emitted by `libarcdav3a` | Preserve the first six 5.1 bed indices and fold appended objects deterministically; do not invent a 9-speaker layout. |
| Mature player code handles the same unknown-layout failure with an explicit index-ordered stereo matrix | MPV `f_swresample` adaptation in `third_party/patches/mpv-audiotrack-compressed-audio.patch`, based on MPV commit `f5d4d9b029affa4d5b7eb13b28d91a96e6a92280` and current tree `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` | B/A | Same FFmpeg channel-order contract, already device-validated in WebHTV | Reuse the principle, but keep Exo's existing 6-channel output when the sink accepts it. |
| nextlib upstream has no follow-up for unknown AV3A layouts | nextlib `origin/main` at `773c841...` inspected after fetch; last audio change remains `323340522493b871f1d22c0c1c72aa9667b3411e` | B | Confirms no direct upstream patch to cherry-pick | Keep this as a narrow WebHTV patch rather than changing the locked nextlib base. |
| Academic literature is not applicable to this bounded correctness fix | No algorithmic or performance claim is being introduced | N/A | The decision is controlled by the FFmpeg API contract and AVS3A output semantics | Do not expand research into unrelated papers. |

### Alternatives and recommendation

- No change: reject; all AVS3A 5.1 attempts remain terminal decoder failures.
- Fabricate a fixed 9-speaker layout or modify `libarcdav3a` metadata: reject; object channels are not physical speaker positions and this changes decoder semantics for every consumer.
- Force every AV3A multichannel stream to stereo in Java: reject; it would discard Exo's existing 5.1/7.1.4 sink capability and make the known 7.1.4 path unnecessarily lossy.
- Narrow native adaptation: adopt; retain the decoder's unknown input layout, configure `swr_set_matrix()` for unknown input with a different requested output count, preserve the first six sound-bed indices for 9->6, and use equal-gain all-channel fold-down for unknown->stereo. Known layouts and equal-channel conversions remain unchanged.

### Acceptance and rollback

1. 5.1 CMAF, DASH and TS audio reaches `audioDecoderInitialized`, `audioTrackInit` and `rendererReady=true` on the target phone with no `swr initialization: Invalid argument`.
2. 2.0 and 7.1.4 AV3A continue to reach `READY`; known-layout PCM, AAC/MP3, ALAC and passthrough behavior is unchanged.
3. Both ARM native artifacts contain the patched `media3ext` decoder and pass ELF/AAR verification; the output byte count matches the requested output channel count.
4. Rollback is the pre-stage commit `639a046125c4375685cb96c9ea004b620778bbb9`, restoring the prior nextlib patch, lock/version and AAR as one unit.

- Expected paths: `third_party/patches/nextlib-av3a.patch`, `third_party/media-lock.json`, `gradle/libs.versions.toml`, `third_party/maven/io/github/anilbeesetti/nextlib-media3ext/**`, and this task document. No AVS3 video decoder, MPV, JNI API or protected dirty path is in scope.
- Next action: apply the native custom-matrix hunk, run patch/build checks, then rebuild the coupled nextlib AAR and APK for ADB playback verification.

## Checkpoint 6: 2026-09-04 08:02 CST - native AV3A 9-to-6 matrix artifact

- Patch correction: fixed the unified-diff context/counts and changed the AV3A specialization test to the runtime decoder name `libarcdav3a`; this keeps the behavior independent of a compile-time codec enum while preserving the approved AV3A-only matrix.
- Patch verification: clean nextlib worktree at `6ff6cf9d0820382b3c233d018c52e4163b09d345` accepts both nextlib patches; `git apply --check --recount third_party/patches/nextlib-av3a.patch` passes.
- Upstream baseline recorded: nextlib `origin/main` is `773c841f86d9775abd0fe18c1fd0f84e00e355c8`; no later unknown-layout fix exists there. Locked FFmpeg source remains `177f090e0503b7e013922ca903bde14b1c375f18`.
- Native artifact: published `nextlib-media3ext-1.10.0-0.12.1-fongmi-softload-av3a-ffmpeg901-r2`; both ARM ABIs compile against a freshly rebuilt FFmpeg/libarcdav3a from the exact locked source commit and contain `Using AV3A mixed-content 9-to-6 channel matrix.` and `swr_set_matrix`.
- SHA-256: AAR `d22cb1885ef998f203b8b7811f6e8df4e42c3d3b3736825279bb1eb05d76a52f`; sources `043fe28b6c6faeecf58e9168ac92c61b2ed9c0c3010da5a5430af3c59d7741a4`; module `1acb6d7d45d8e5b89a0411c0040250a12da8c310df5c958802d02da94d136b1f`; POM `e38e8be43423ac133ee1e5a7cfbe6746ae4cc00a7b24e3e0b8c68c69760c8375`.
- Correction evidence: the earlier r2 attempt reused FFmpeg 6 headers with FFmpeg 9 libraries and was rejected after device logs showed `channels=118/112 sampleRate=0`; that artifact was replaced before this checkpoint. The current AAR's FFmpeg headers report `LIBAVCODEC_VERSION_MAJOR 63` and `AV_CODEC_ID_AV3A`.
- Verification caveat: host Kotlin daemon could not write `/Users/macbookpro/Library/Application Support/kotlin`, so Gradle used its fallback compiler; CMake/Java/AAR tasks all succeeded. Device playback is still pending and is the single next action.
