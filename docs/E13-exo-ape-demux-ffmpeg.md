# E13 Exo APE demux/FFmpeg decode support

- User decision: approved 2026-09-04; implementation in progress.
- Objective: make Exo recognize and play the APE fixture `A13_APE/APE_2.0_44.1kHz_sh3.ape`, including PCM output, progress, seek, source switching, and release.
- Lane: upstream; one atomic Exo stage with an App extractor and the coupled nextlib FFmpeg decoder/build patch.
- Baseline: WebHTV `feature/mpv-audio-fallback-policy` at `047ad74f76c859862b39b64d0545ec2dc83dd865`.
- Protected pre-existing dirty paths: `.gitignore`, `AGENTS.md`, `app/.cxx/`, `docs/音频DSP整合方案.md`, and other paths recorded by `task_guard.sh start`.

## Current gap and evidence

- Device evidence: `/tmp/exo-ape-monitor-20260904.log` shows `UnrecognizedInputFormatException` and `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` before renderer creation.
- `app/src/main/java/com/fongmi/android/tv/player/exo/MediaSourceFactory.java` currently supplies `DefaultExtractorsFactory`; Media3 has no APE extractor or `audio/ape` file-type route.
- FongMi FFmpeg already contains the APE demuxer and decoder at `libavformat/ape.c` and `libavcodec/apedec.c` in commit `177f090e0503b7e013922ca903bde14b1c375f18`.
- The locked nextlib build omits the `ape` decoder from `ENABLED_DECODERS`; `FfmpegLibrary` has no `audio/ape` mapping; JNI context setup does not pass APE sample rate/channel count/bits-per-sample.

## Sources and research

| Claim | Source | Grade | WebHTV applicability and decision impact |
| --- | --- | --- | --- |
| APE header, seek table, frame/block boundaries and six-byte extradata contract | FongMi FFmpeg `libavformat/ape.c`, `libavcodec/apedec.c` at `177f090e0503b7e013922ca903bde14b1c375f18` | A | Direct contract for the extractor packet format and decoder initialization. |
| APE regression coverage includes legacy, high-compression and 24-bit samples | FFmpeg FATE `tests/fate/monkeysaudio.mak` at current FFmpeg tree | A | Requires malformed/truncated and non-default bit-depth parser tests. |
| Media3 `DefaultExtractorsFactory` has no APE extractor/file-type mapping | AndroidX Media3 extractor source at `e3e922d5c01bc0b564849940fe589daf37360d15` | A | Confirms the failure is an input/demux gap, not AudioTrack or renderer fallback. |
| Mature downstream APE implementations preserve frame/block seek semantics | `skhara/andless` Monkey's Audio decoder source (historical related implementation) | B | Corroborates frame-oriented parsing; not used as a binary/code dependency. |
| Parser safety and reproducible native provenance | FFmpeg developer guide, FATE, OSS-Fuzz guidance | A/B | Bounds checks, deterministic fixtures, pinned source and ABI artifact records are acceptance requirements. |

## Alternatives and choice

1. No change: rejected. Exo continues to fail at input sniffing.
2. Unmodified upstream Media3/nextlib: unavailable because neither upstream component currently provides an APE extractor and the locked nextlib FFmpeg configuration does not expose APE.
3. Route APE to MPV: rejected. It changes player selection semantics and would not fix Exo's requested capability.
4. WebHTV-adapted implementation: selected. Add a narrow Java `ApeExtractor` that emits FFmpeg-compatible packets and metadata, register it ahead of defaults, and add a separate nextlib patch that enables/maps APE and passes raw bit depth to native FFmpeg. Keep all existing extractor, renderer, AV3A, ALAC, AAC and MP3 behavior unchanged.

## Contracts, risks and rollback

- Preserve `DefaultExtractorsFactory` ordering, Dolby Vision wrapper behavior, cache/data-source ownership, existing FFmpeg output channel probing, AV3A/ALAC patches, and both `arm64-v8a` and `armeabi-v7a` artifacts.
- Reject truncated headers, unreasonable frame counts, overflowed seek-table offsets/sizes, invalid block counts, unsupported channel counts, and unsupported bit depths without crashing.
- APE decoder currently supports at most two channels and 8/16/24 coded bits; the extractor must expose those constraints and fail clearly for other inputs.
- Native ABI rollback is the coupled nextlib AAR/version/lock plus App extractor change; restore the pre-stage commit/tag and remove the E13 patch/version in one revert.

## Expected files and artifacts

- `app/src/main/java/com/fongmi/android/tv/player/exo/ApeExtractor.java`
- `app/src/main/java/com/fongmi/android/tv/player/exo/MediaSourceFactory.java`
- `app/src/test/java/com/fongmi/android/tv/player/exo/ApeExtractorTest.java`
- `third_party/patches/nextlib-ape-support.patch`
- `scripts/build_media_deps.sh`, `third_party/media-lock.json`
- versioned `third_party/maven/io/github/anilbeesetti/nextlib-media3ext` AAR/POM/module and its SHA-256 records

## Acceptance and verification

- Extractor unit tests cover `MAC ` sniffing, legacy/current headers, three-frame positions/sizes/duration, seek, truncated input, malformed seek table and overflow guards.
- nextlib AAR verification confirms both ARM ABIs contain the APE decoder and `libmedia3ext.so`; source/lock/patch/version/artifact hashes agree.
- App compile/test passes for the focused extractor/renderer contract.
- On the connected phone, Exo logs `audio/ape` and `ffmpeg...-ape`, renderer reaches READY, an AudioTrack/PCM output is established, position advances, seek works, and switching/exit produce no decoder or native lifecycle errors.

## Checkpoint 1: 2026-09-04 18:37 CST - approved implementation start

- Completed: baseline recovery, source review, approval, task guard start.
- Source identities: FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18`; nextlib `6ff6cf9d0820382b3c233d018c52e4163b09d345`; Media3 `e3e922d5c01bc0b564849940fe589daf37360d15`.
- Workspace: branch `feature/mpv-audio-fallback-policy`; protected pre-existing dirty paths preserved.
- Validation: APE failure reproduced and retained in `/tmp/exo-ape-monitor-20260904.log`.
- Rollback anchor: `047ad74f76c859862b39b64d0545ec2dc83dd865`.
- Unresolved: extractor implementation, nextlib patch, build and device playback.
- Next action: implement the bounded extractor and add the coupled nextlib patch.

## Checkpoint 2: 2026-09-04 22:36 CST - build and host verification

- Completed: nextlib r3 dual-ABI publication, App Java compilation, focused extractor tests, and arm64 debug APK build.
- Native evidence: `bash scripts/build_media_deps.sh --nextlib-only` completed successfully; FFmpeg configuration listed `ape` under enabled decoders and demuxers for both `armeabi-v7a` and `arm64-v8a`.
- Artifact evidence: r3 AAR/POM/module/sources/javadoc were installed under `third_party/maven`; AAR contains `libmedia3ext.so`, `libavcodec.so`, and the `Monkey's Audio` decoder marker. APK `app-mobile-arm64_v8a-debug.apk` contains the expected arm64 native libraries.
- Java evidence: `bash ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon` and `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.exo.ApeExtractorTest --no-daemon` passed; the focused suite reports 3 passing tests. `bash ./gradlew :app:assembleMobileArm64_v8aDebug --no-daemon` passed.
- Workspace: E13 task guard remains active; no commit or recovery tag created yet. Existing protected dirty paths remain untouched.
- Blocker: `adb devices -l` currently returns no connected device, so install and real-device APE playback evidence are still pending.
- Next action: after ADB authorization returns, install `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`, clear logcat, and play the APE fixture by command to verify `audio/ape`, FFmpeg decoder creation, PCM output, position progress, seek, switch, and release.
