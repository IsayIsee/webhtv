# P3-3 MPV audio fallback policy

## Recovery anchor

- Objective: add an MPV non-passthrough policy with stereo compatibility as the default and optional multichannel PCM, retry a failed DTS-HD AudioTrack initialization once with DTS Core passthrough, and expose the actual runtime audio chain in the playback-parameter panel.
- Acceptance: existing default track downgrade remains unchanged; multichannel PCM keeps the selected 5.1/7.1 track when passthrough is unavailable; only a confirmed DTS-HD AudioTrack initialization failure can trigger one runtime `audio-spdif=dts` downgrade; the audio row distinguishes observed passthrough, PCM, offload, hardware/software decode, and automatic downgrade without substituting configuration intent for runtime facts.
- Branch/base: `feature/mpv-audio-fallback-policy` from `ec478b0b697422a7785171c7b51a35b7a526564e`.
- Protected pre-existing path: `app/.cxx/`.
- Rollback: revert the atomic P3-3 implementation commit; no native binary, lock, JNI, Exo, or IJK artifact changes are allowed.
- Next action: close the verified implementation with the task guard commit and annotated recovery tag.

## Authority and scope

- User decision: approved implementation on 2026-08-31.
- Stable task ID: `P3-3-MPV-AUDIO-FALLBACK`.
- Parent work: P3 AudioTrack carrier/mask support was completed by local commit `d82336bde585b62af43771284075a0a94a3d999e`.
- Historical assessment: `docs/upstream-player-dependency-merge-assessment-2026-08-20.md` at repository commit `052206a133640f209177cc84640931bdcf46926e`; the current branch intentionally does not track that large historical ledger, so this branch-local derivative record points to its immutable Git revision instead of restoring unrelated closed tasks.
- Included: MPV App settings and fallback policy, cross-player runtime audio diagnostics, the playback-parameter audio row, and focused JVM tests.
- Excluded: native source/patches, `libmpv.so`, `libplayer.so`, dependency locks, device-specific switches, and automatic fallback for codecs other than DTS-HD.

## Current behavior and gap

- `MpvDirectAudioPolicy` keeps a track when its codec can be passed through. On `mediacodec_embed`, an unsupported multichannel track otherwise changes to same-language stereo, then to a lower-complexity same-language track.
- `MpvAudioCapabilities` publishes route-probed `audio-spdif` codecs. P3 already aligns DTS-HD carrier probes with the native AudioTrack channel-mask rules.
- The Java MPV wrapper records native log lines but does not recover from `AudioTrack Init failed` or `AudioTrack.getState failed` when the selected DTS-HD carrier is rejected by a device after the capability probe.
- Users therefore need two separate policies: the existing low-cost stereo-compatible choice, and an explicit choice to preserve the selected multichannel track for PCM output. DTS-HD runtime rejection must remain automatic rather than becoming another per-codec setting.

## Evidence and best-practice review

| Claim | Source | Grade | WebHTV applicability | Decision impact |
| --- | --- | --- | --- | --- |
| `audio-spdif=dts` means DTS Core; `dts-hd` means DTS-HD MA; listing both behaves as `dts-hd`. | MPV `DOCS/man/options.rst` at locked source family `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`, reviewed 2026-08-31 | A | Directly defines the shipped option contract. | Set the runtime value exactly to `dts`; adding `dts` to a list that still contains `dts-hd` would not downgrade. |
| Runtime `audio-spdif` changes carry `UPDATE_AD` and reinitialize the audio decoder chain. | MPV `filters/f_decoder_wrapper.c` and `player/command.c`, reviewed 2026-08-31 | A | The shipped Java API already supports runtime string properties. | Use one runtime property change; do not rebuild the whole player or seek/reload the media. |
| The DTS-HD decoder output format is `spdif-dtshd`. | MPV `audio/format.c` and `audio-params/format` property documentation, reviewed 2026-08-31 | A | MPV publishes the decoder format before AudioTrack output initialization. | Observe and cache the format so MPV's subsequent built-in PCM fallback cannot erase the evidence before Java handles the failure log. |
| Android AudioTrack constructor/state failures are logged as `AudioTrack Init failed` and `AudioTrack.getState failed`. | MPV `audio/out/ao_audiotrack.c`, reviewed 2026-08-31 | A | These are the two initialization failure exits before playback begins. | Match only these initialization failures; write-time failures retain existing handling. |
| The locked FFmpeg SPDIF muxer can send only the DTS core payload when HD mode is not requested. | FongMi FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18`, `libavformat/spdifenc.c`, reviewed 2026-08-31 | A | This is the FFmpeg revision linked into the shipped MPV assets. | The runtime option downgrade is sufficient; no native patch or separate DTS extraction feature is required. |
| HDMI supports uncompressed multichannel PCM and MPV can decode DTS-HD losslessly. | MPV `DOCS/man/options.rst`, reviewed 2026-08-31 | A | Supports a user-selectable quality policy where the route accepts multichannel PCM. | Present the setting as output policy, not as a claim that PCM is a separate decoder or universally supported route. |

Research categories:

- Exact source/history/tests: applicable and reviewed above, plus existing WebHTV P3 unit/device evidence.
- Official platform/project documentation: MPV option/property documentation and Android AudioTrack behavior are applicable; no new Android API is introduced.
- PR/issues/reverts/maintainer discussion: no unresolved upstream design question remains after the exact shipped source established the option-update and failure contracts.
- Mature related-project implementations: inapplicable to the narrow Java policy because the shipped MPV runtime already owns decoder/AO reinitialization.
- Papers/benchmarks/field reports: inapplicable; this is a bounded fallback state transition, not a new codec algorithm or a performance claim. Real HDMI/ARC/eARC receiver validation remains hardware-dependent.

## Alternatives

1. No change: preserves current compatibility but cannot keep multichannel PCM by user choice and leaves DTS-HD runtime capability false positives unrecovered.
2. Rebuild/reload the whole MPV player after failure: can change startup position, tracks, surfaces, cache state, and lifecycle ownership; it is broader than the runtime option contract requires.
3. Device-specific DTS-HD toggle or model blacklist: hides one observed device family but creates stale product policy and does not cover equivalent AudioTrack failures elsewhere.
4. Adapted design, selected: expose one general non-passthrough output policy and make DTS-HD Core recovery automatic, format-gated, and one-shot.

## Final design

- Add `非直通多声道` under MPV audio settings.
- `立体声兼容` is the default and preserves current same-language downgrade behavior.
- `多声道 PCM` keeps the selected multichannel track when it cannot be passed through; MPV decodes it and negotiates PCM with the route.
- Add a pure DTS-HD fallback policy that:
  - requires configured `dts-hd` passthrough;
  - requires active `audio-params/format=spdif-dtshd` or the selected track's parsed DTS-HD profile;
  - requires `AudioTrack Init failed` or `AudioTrack.getState failed`;
  - refuses a second attempt;
  - sets the current media's runtime option exactly to `audio-spdif=dts`.
- Apply the fallback through the runtime `audio-spdif` property. MPV's `UPDATE_AD` owns audio-chain reinitialization.
- Observe `audio-params/format` and use the ordered pre-failure `spdif-dtshd` value rather than depending only on a post-failure synchronous read.
- Reset the one-shot state for each new media item and stop/reset cycle.

## Risks and controls

- Some routes may reject multichannel PCM or downmix outside the App. The setting is optional and defaults to the proven stereo-compatible behavior.
- Software audio decode consumes more CPU than selecting an existing stereo track. The user must opt in; no claim of zero performance cost is made.
- Log matching can be too broad. The policy also requires the active MPV sample format and configured codec, preventing unrelated AudioTrack failures from triggering.
- A failed DTS Core retry must not loop. Attempt state is set before changing the property and is not cleared by the resulting audio reinitialization.
- A later track switch in the same item still uses the intentional `audio-spdif=dts` retry value. Starting a new media item restores the route-probed original codec list.

## Verification plan

- Unit-test existing/default stereo downgrade and optional multichannel PCM preservation.
- Unit-test DTS-HD init and state failures, unrelated AO logs, non-DTS-HD formats, missing `dts-hd`, codec-list transformation, and repeated failure refusal.
- Run existing MPV direct-audio/capability/UI policy tests plus the new policy tests.
- Compile the affected App Java variant once after tests.
- Do not claim receiver/AVR behavior without matching HDMI/ARC/eARC hardware; native binaries are unchanged.

## Checkpoint 1: implementation and verification

- Completed: added the default `立体声兼容` / optional `多声道 PCM` setting, passed it into the MPV direct-audio selection policy, and added an automatic one-shot DTS-HD AudioTrack initialization fallback to runtime `audio-spdif=dts`.
- Failure isolation: the retry requires configured DTS-HD passthrough, an active/cached `spdif-dtshd` format or parsed DTS-HD profile, and an AudioTrack constructor/state initialization failure. The attempt flag is set before changing the runtime property and resets only for a new media item.
- Runtime behavior: MPV's `UPDATE_AD` reinitializes the audio decoder chain in place. The player/media position, video output, cache, and native context are not rebuilt. A new media item restores the original route-probed codec list.
- Verification: `bash gradlew :app:testMobileArm64_v8aDebugUnitTest --tests 'androidx.media3.mpvplayer.MpvDtsHdFallbackPolicyTest' --tests 'com.fongmi.android.tv.player.mpv.MpvDirectAudioPolicyTest' --tests 'com.fongmi.android.tv.player.engine.MpvAudioCapabilitiesTest' --tests 'com.fongmi.android.tv.setting.PlaybackPerformanceUiPolicyTest' --no-daemon` passed on 2026-08-31; the target also compiled `:app:compileMobileArm64_v8aDebugJavaWithJavac`. Final run: `BUILD SUCCESSFUL in 48s`, 73 tasks, 6 executed and 67 up-to-date.
- Existing warnings: Gradle reported the repository's existing 32-bit native-library warning and deprecation notices; no new compilation error or test failure occurred.
- Files/artifacts: Java source, JVM tests, and this task record only. No lock, patch, AAR, APK, `.so`, JNI, Exo, or IJK artifact changed.
- Hardware limitation: no matching HDMI/ARC/eARC receiver was available, so AVR format display and the reported Phicomm device behavior remain device-validation work rather than a claimed result.
- Rollback anchor: base `ec478b0b697422a7785171c7b51a35b7a526564e`; revert the forthcoming atomic task commit.
- Unresolved risk: vendor AudioTrack implementations can still fail DTS Core or multichannel PCM; repeated failure intentionally follows MPV's normal error/fallback path without another App retry.
- Next action: run the final task/document safety pass, then commit and create the annotated recovery tag.

## Checkpoint 2: runtime audio diagnostics design

- Follow-up authority: on 2026-08-31 the user approved implementation of an audio row that shows the currently executing audio chain, not whether passthrough or PCM is allowed by settings.
- Display contract: render `original track/降级effective track` only for an App-initiated automatic downgrade. Manual track selection is never labelled as downgrade. The remaining segments describe observed decode and output, for example `DTS-HD MA 5.1/降级DTS Core 5.1 · 直通` or `DTS-HD MA 5.1/降级AAC 2.0 · 软件解码 · PCM 2.0`.
- MPV evidence: locked MPV `DOCS/man/input.rst` defines `audio-params` as decoder output and `audio-out-params` as the format written to the audio API. `current-tracks/audio/decoder` is the active short decoder name. Locked `player/lua/stats.lua` displays current-track codec/decoder together with decoder and AO input/output formats. These are runtime properties and are the source of truth for MPV diagnostics.
- Exo evidence: the locked Media3 `AnalyticsListener.onAudioTrackInitialized` callback supplies the configuration of the initialized Android `AudioTrack`; `AudioSink.AudioTrackConfig` contains its actual encoding, sample rate, channel mask, tunneling, and offload state. `onAudioDecoderInitialized` supplies the active decoder name. The existing listener already receives both callbacks but previously discarded AudioTrack configuration after logging.
- IJK evidence: the shipped Java wrapper exposes the current audio codec information from native IJK. This path is FFmpeg audio decode to Android audio output and has no App passthrough control. Because it does not expose the final AudioTrack channel mask, diagnostics may report observed software decode and PCM but must not invent an output channel layout.
- Decoder classification: encoded AO formats are `直通` and do not receive a misleading App-side hard/software decoder label. Exo offload is labelled `硬件卸载`, not HDMI passthrough. PCM uses the observed decoder name: platform/vendor MediaCodec is hardware when confirmed, FFmpeg/platform software names are software, raw PCM needs no decoder, and unresolved cases remain `解码待确认`.
- Alternatives rejected: showing `audio-spdif` or the global passthrough setting reports capability intent rather than current execution; inferring PCM channels from source-track metadata can hide AudioTrack downmixing; treating every non-PCM AudioTrack as hardware decode conflates passthrough and offload.
- Research categories: exact shipped MPV and Media3 source/docs and the current WebHTV call path were reviewed. Separate PR/issue research is not decision-changing because the APIs explicitly define the runtime values. MPV's own stats implementation is the mature reference UI. Papers and benchmarks are inapplicable because this is diagnostic state mapping, not a codec or performance change.
- Acceptance tests: cover normal passthrough, PCM software/hardware/no-decode, Exo offload, DTS-HD-to-DTS Core same-track downgrade, multichannel-to-stereo track downgrade, manual/no downgrade, channel labels, and unknown observations. Compile the affected Mobile Arm64 Java variant once after focused tests.
- Rollback: revert the follow-up diagnostics commit independently; no native artifact or playback selection/output behavior changes.
- Next action: implement the structured runtime snapshot and replace the audio row's configuration-derived text with its formatted observed state.

## Checkpoint 3: runtime diagnostics implementation complete

- Completed: the audio row now prefers observed runtime diagnostics from MPV, Exo, and IJK. It reports the effective codec/channel layout, automatic downgrade chain, passthrough, PCM, offload, and confirmed hardware/software decode state; configuration-only passthrough text was removed from the player summary.
- Manual track changes clear automatic-downgrade attribution; only the App's direct-audio selection or confirmed DTS-HD-to-DTS Core recovery can produce the `原音轨/降级实际音轨` form.
- Verification: `bash gradlew :app:testMobileArm64_v8aDebugUnitTest --tests 'com.fongmi.android.tv.player.AudioPlaybackDiagnosticsTest' --tests 'androidx.media3.mpvplayer.MpvDtsHdFallbackPolicyTest' --tests 'com.fongmi.android.tv.player.mpv.MpvDirectAudioPolicyTest' --no-daemon` passed on 2026-08-31; `:app:compileMobileArm64_v8aDebugJavaWithJavac` completed in the same run (`BUILD SUCCESSFUL`, 57s).
- Cleanup: removed the unused `dtsHdCoreFallbackApplied` state; no native source, binary, lock, JNI, or playback-policy artifact changed in this diagnostics follow-up.
- Remaining limitation: receiver-specific HDMI/ARC/eARC behavior and vendor decoder classification still require matching hardware validation; unknown runtime facts remain labelled as pending/omitted.
- Next action: run the final task/document safety pass, then commit and create the annotated recovery tag.
