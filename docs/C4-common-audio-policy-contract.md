# C4：公共音频决策与运行时诊断契约

## Recovery anchor

- 目标：把 Exo、MPV、IJK 的音频诊断统一为“六级决策 + 独立运行时状态”，先不改变播放器的选轨、解码器或 AudioTrack 回退行为。
- 范围：`AudioPlaybackDiagnostics`、Exo/MPV/IJK 诊断接线、播放参数展示和聚焦 JVM 测试；本阶段不改 Media3/nextlib AAR、FFmpeg、MPV native、锁文件或用户选轨规则。
- 状态：**已完成并提交**。
- 回滚锚点：`43fba18a8d074268c26a6ddbd30fe348324732a0`（实施前 HEAD）。
- 下一动作：无；后续仅需按既有验收计划做真实设备观察，不扩大 C4 范围。

## 任务信息

- 任务 ID：`C4`
- 分类：通用 App 音频契约
- 用户决定：**实施**（用户于 2026-09-04 明确批准 `开始实施 C4`）
- 决策问题：如何在不改变现有播放器行为的前提下，让“解码待确认/输出待确认”能够区分尚未初始化、已激活和已失败，并让 Exo、MPV、IJK 使用同一套可解释的六级策略语义？
- 当前假设：当前实现已经能报告部分实际输出，但 `UNKNOWN` 同时表示缺少轨道、尚未创建 AudioTrack、等待 mpv 运行时属性、以及失败后没有可用输出；这会让诊断面板无法区分等待和失败。
- 反假设：仅增加日志或继续沿用字符串 `downgradeReason` 就足以解释所有状态；若外部规范证明运行时事件不能稳定映射到统一模型，则本阶段应退回只做文档规范。
- 能够区分两者的证据：同一播放会话中轨道已知、AudioTrack 初始化事件、`audio-out-params`/`AudioOutputSnapshot`、可恢复错误和最终 `PlaybackException` 的顺序与事实来源。

## 当前 WebHTV 基线

- `app/src/main/java/com/fongmi/android/tv/player/AudioPlaybackDiagnostics.java` 已有 `DecodeMode`、`OutputMode`、原始/有效轨道、输出声道数、采样率、隧道和降级原因。
- Exo 在 `ExoPlayerEngine.getAudioPlaybackDiagnostics()` 中读取 `PlaybackAnalyticsListener.AudioOutputSnapshot`；只有 AudioTrack 已初始化时才能确定 PCM、直通或卸载。AudioSink 创建/写入失败可在上层恢复为 PCM。
- MPV 在 `MpvPlayer.getAudioPlaybackDiagnostics()` 中区分 `audio-params/*` 和 `audio-out-params/*`，并记录 DTS-HD -> DTS Core、同语言 stereo/低复杂度换轨。当前没有运行时失败状态字段。
- IJK 只能确认 native 解码器和 PCM 路径，无法确认最终 AudioTrack 声道布局，因此不能伪造输出声道事实。
- `PlayerOsdController` 直接渲染 `AudioPlaybackDiagnostics.format()`；当前 `UNKNOWN` 会显示“解码待确认”或“输出待确认”。
- 既有行为合同：AAC/MP3/ALAC/AV3A 的已验证路径、Exo 直出失败后同轨 PCM 回退、MPV DTS-HD Core 回退、MPV 同语言 stereo/低复杂度策略、手动换轨不标记为自动降级，均保持不变。

## 规范化模型

### 六级决策级别

`DecisionLevel` 只描述这次播放最终采用的策略级别，不代表设备能力探测结果：

| 级别 | 语义 | 典型事实 |
| --- | --- | --- |
| `EXACT_PASSTHROUGH` | 原编码直接交给输出设备 | Exo passthrough、MPV `spdif-*` |
| `COMPRESSED_OFFLOAD` | 压缩帧交给 DSP/AudioTrack offload | Exo `AudioTrackConfig.offload=true`、MPV AO offload |
| `HARDWARE_PCM` | 原音轨经硬件 MediaCodec 解码后输出 PCM | Exo 硬件音频 decoder + PCM AudioTrack、MPV `*_mediacodec` + PCM |
| `SAME_TRACK_COMPATIBLE` | 当前音轨保留同一内容但采用兼容编码/核心 | DTS-HD -> DTS Core；后续 Exo 同轨兼容回退也归入此级 |
| `SOFTWARE_PCM` | 原音轨经软件解码输出 PCM | FFmpeg/软件 decoder + PCM |
| `LANGUAGE_OR_STEREO_FALLBACK` | 为可播放性换到同语言兼容或 stereo/低复杂度音轨 | MPV 当前自动同语言 stereo/低复杂度换轨 |

规则：`COMPRESSED_DIRECT` 属于 `EXACT_PASSTHROUGH` 的输出表现；`OutputMode` 继续保留以描述具体输出形态，不能用 `OutputMode` 反推 `DecisionLevel`。

### 独立运行时状态

`RuntimeState` 与决策级别正交：

| 状态 | 进入条件 | 展示/诊断要求 |
| --- | --- | --- |
| `UNKNOWN` | 尚未识别音频轨道或播放器会话不存在 | 不宣称正在解码，也不宣称失败 |
| `PENDING` | 音频轨道已知，但 AudioTrack/音频输出或 decoder 运行时事实尚未发布 | 可显示“输出初始化中”，保留原始轨道；不得显示已确认的硬解/PCM |
| `ACTIVE` | 已收到有效 decoder/output 运行时事实 | 输出实际 codec、声道、采样率和输出模式 |
| `FAILED` | 初始化/写入/解码错误未恢复，或所有声明的回退均失败 | 显示失败原因和最后尝试级别；不得继续显示“输出待确认” |

能力查询（`AudioManager.getDirectPlaybackSupport`、`isOffloadedPlaybackSupported`、Media3 format support）只能影响候选选择，不能直接把状态置为 `ACTIVE`。

### 失败原因

保留现有可读字符串以兼容日志，但新增有限枚举/常量映射，至少覆盖：

- `direct-output-init`
- `direct-output-write`
- `offload-init`
- `offload-write`
- `decoder-init`
- `decoder-runtime`
- `channel-layout-unsupported`
- `same-track-compatible`
- `same-language-stereo`
- `manual-track-selection`（仅用于内部清除自动降级归因，不在 UI 宣称为降级）

未知上游错误必须落到 `unknown`，不能拼接异常类名作为稳定协议。

## 证据与最佳实践审查

访问日期：2026-09-04（Asia/Shanghai）；网络访问未遇到代理或源不可达限制。

| 结论 | 来源 | 等级 | 对 WebHTV 的适用性与决策影响 |
| --- | --- | --- | --- |
| 能力查询不等于当前资源一定可用；direct playback 还可能被系统重新编码/混音 | [Android `AudioTrack.isDirectPlaybackSupported`](https://developer.android.com/reference/android/media/AudioTrack#isDirectPlaybackSupported(android.media.AudioFormat,android.media.AudioAttributes)) | A | 必须把“能力/候选”与“实际初始化/写入”分成两个状态，不能仅凭探测结果展示硬解或直通 |
| API 33 起应使用 `AudioManager.getDirectPlaybackSupport` 的 bitfield；direct、bitstream、offload 是不同事实 | [Android `AudioManager.getDirectPlaybackSupport`](https://developer.android.com/reference/android/media/AudioManager#getDirectPlaybackSupport(android.media.AudioFormat,android.media.AudioAttributes)) | A | `OutputMode` 继续区分 passthrough、compressed direct、offload，`DecisionLevel` 不应复用平台 bitfield |
| Android 位置声道 mask 只为常见布局提供 canonical 映射，未知/高声道数不应被当作标准布局 | [Android `AudioFormat` channel mask](https://developer.android.com/reference/android/media/AudioFormat#channelMask) | A | 5.1/7.1 可保留原布局；AV3A、超过 8 声道和未知布局必须通过实际输出或显式下混结果确认 |
| AudioSink 的错误回调可能可恢复，致命错误才由 Player error 暴露；AudioTrack 初始化事件是实际事实 | `third_party/sources/media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioSink.java`、`DefaultAudioSink.java`，本地 fork `e3e922d5c01bc0b564849940fe589daf37360d15` | A | 需要 `PENDING`/`ACTIVE`/`FAILED`，且不能把一次可恢复错误立即显示为最终失败 |
| AudioOutput 写入失败后对象不可复用，需要重建；offload 失败可被禁用并重试 | 同上 `AudioOutput.java`、`DefaultAudioSink.java` | A | 记录“尝试级别”和“最终级别”，避免把能力声明或第一次失败误当作最终输出 |
| `audio-params` 是 decoder 输出，`audio-out-params` 是写入音频 API 的格式 | [mpv `input.rst`](https://github.com/mpv-player/mpv/blob/cca559b41ceb0bb7731cf6ef2e1f33276cd30c42/DOCS/man/input.rst#L2727-L2767)；锁定 MPV commit `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` | A | MPV 必须继续以 `audio-out-params` 判定最终 PCM/直通事实，不能提前把 decoder 参数当作 AudioTrack 输出 |
| `audio-spdif`、`audio-channels` 和 decoder downmix 的决策时机不同；系统 mixer 也可能再次下混 | [mpv `options.rst`](https://github.com/mpv-player/mpv/blob/cca559b41ceb0bb7731cf6ef2e1f33276cd30c42/DOCS/man/options.rst#L2131-L2149) | A | 输出声道数只表示播放器写出的布局，不宣称电视扬声器/功放最终物理声道；未知布局需显式记录 |
| 高声道 AC-4 没有匹配 channel mask 时，Media3 维护者讨论采用 stereo mask 才能到达 offloaded decoder | [AndroidX Media PR #2734](https://github.com/androidx/media/pull/2734) | B | 说明“文件声道数”和“可创建 AudioTrack 的 mask”可能冲突；支持 C4 保留 layout-confirmed 与 output-confirmed 两层事实，不直接移植未合并 PR |

### 不适用的证据类别

- 学术论文：本阶段是状态/事实建模，不改变重采样、混音、调度或解码算法；论文不会改变接口契约或验收标准，故不作为设计依据。
- 性能基准：本阶段不增加线程、缓冲、解码或输出调用；只有在批准实施后，若诊断接线导致启动/切换开销，才测量启动与换集时延。
- native/ABI 供应链：本阶段不修改 FFmpeg、MPV、nextlib、AAR、`.so` 或锁文件；不产生新的二进制 provenance 问题。

## 方案比较

### 不变更

继续用 `UNKNOWN` 和自由字符串。优点是零代码风险；缺点是用户仍无法区分等待、失败和未识别，Exo/MPV 的诊断语义继续漂移。**不推荐。**

### 原样采用上游

仅复用 Media3 `AudioSink` 事件或 mpv 的属性名。优点是贴近上游；缺点是 Exo、MPV、IJK 的事件模型不同，无法形成跨播放器的决策级别，也会把能力查询误当运行时成功。**不采用。**

### WebHTV 窄适配（推荐）

在现有 `AudioPlaybackDiagnostics` 增加 `DecisionLevel`、`RuntimeState` 和受控失败原因；Exo/MPV/IJK 只负责把已经观察到的事实映射到模型，播放器实际选轨/回退逻辑不变。格式化层仅对 `PENDING`/`FAILED` 改用明确文案，已确认路径维持现有输出。

### 直接统一选轨/回退策略

同时改 Exo 同轨兼容回退、MPV 各 VO 路径和多声道布局策略。该方案会改变用户可见行为、涉及多播放器生命周期和真实设备矩阵，回滚面过大；拆为后续 `E13`/`P3-8` 等独立任务。**本阶段拒绝。**

## 实施边界（需批准）

预期修改：

- `app/src/main/java/com/fongmi/android/tv/player/AudioPlaybackDiagnostics.java`
- `app/src/main/java/com/fongmi/android/tv/player/engine/ExoPlayerEngine.java`
- `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`
- `app/src/main/java/com/fongmi/android/tv/player/engine/IjkPlayerEngine.java`
- `app/src/test/java/com/fongmi/android/tv/player/AudioPlaybackDiagnosticsTest.java`
- `app/src/test/java/com/fongmi/android/tv/player/exo/ExoPlaybackDiagnosticsTest.java`
- `app/src/test/java/com/fongmi/android/tv/player/mpv/MpvDirectAudioPolicyTest.java`（仅在需要补模型映射时）

明确排除：

- Exo/MPV 选轨算法、`ExoCompressedAudioDirectPolicy`、`MpvDirectAudioPolicy` 的行为变更；
- `third_party/sources/media/**`、`third_party/nextlib`、FFmpeg/MPV native、AAR、锁文件、设置项和网络协议；
- AVS3 视频解码、AV3A 新 decoder、DSP 效果链和物理功放/扬声器路由控制。

## 实施记录

- `AudioPlaybackDiagnostics.Snapshot` 保留原 9 参数构造器；新增 `DecisionLevel`、`RuntimeState`、`FailureReason` 字段及稳定 code 映射。
- Exo 只在 `AudioOutputSnapshot` 已初始化时报告 `ACTIVE`；AudioTrack/音频 renderer 最终错误报告 `FAILED`，其余已知音轨但无输出事实报告 `PENDING`。
- MPV 继续将 `audio-params/*` 视为 decoder 侧事实，只用 `audio-out-params/*` 填充实际输出声道/采样率；最终音频相关错误报告 `FAILED`。
- IJK 使用已有 `ErrorSnapshot` 的 component/prepared 阶段判定 decoder 初始化/运行时失败，不虚构最终 AudioTrack 声道布局。
- 未改选轨、解码器选择、AudioTrack 回退、native 依赖、AAR、锁文件或设置行为。
- 变更文件：
  - `app/src/main/java/com/fongmi/android/tv/player/AudioPlaybackDiagnostics.java`
  - `app/src/main/java/com/fongmi/android/tv/player/engine/ExoPlayerEngine.java`
  - `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`
  - `app/src/main/java/com/fongmi/android/tv/player/engine/IjkPlayerEngine.java`
  - `app/src/test/java/com/fongmi/android/tv/player/AudioPlaybackDiagnosticsTest.java`

## Checkpoint 2：代码实施与聚焦验证（2026-09-04 15:12 CST）

- 完成：C4 公共诊断模型、Exo/MPV/IJK 事实映射和 JVM 回归测试。
- 验证：
  - `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.AudioPlaybackDiagnosticsTest --tests com.fongmi.android.tv.player.exo.ExoPlaybackDiagnosticsTest --tests com.fongmi.android.tv.player.mpv.MpvDirectAudioPolicyTest --no-daemon` 通过。
  - `bash ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon` 通过。
- 失败修正：首次编译发现嵌套 record 未限定外部静态方法，已直接修正后同一聚焦单测通过。
- 未执行：真实 V2453A 冒烟；本阶段只统一诊断契约，不新增播放能力，设备验证保留为后续观察项。
- 当前工作区：`feature/mpv-audio-fallback-policy`；guard 基线 `43fba18a8d074268c26a6ddbd30fe348324732a0`；`.gitignore`、`AGENTS.md`、`app/.cxx/`、`docs/音频DSP整合方案.md` 为保护的既有脏路径。
- 实施提交：`0a31951e3c923154b2ef8218d1a3811a96fa446b`。
- 恢复 tag：`recovery/C4/20260904155551-0a31951e3c92`。
- 回滚：对实施提交执行 `git revert 0a31951e3c923154b2ef8218d1a3811a96fa446b`，不涉及 native/AAR/锁文件。
- 最终状态：代码、测试和文档已由 guard 原子提交；保护的既有脏路径未纳入提交。
- 下一动作：无；若后续设备观察发现状态映射问题，追加到本任务文档并另开修正 guard。

## 验收标准

1. 已知轨道但尚未收到 AudioTrack/`audio-out-params` 的快照为 `PENDING`，不显示已确认硬解或 PCM 声道。
2. 已收到有效输出事实的快照为 `ACTIVE`；Exo、MPV、IJK 的已确认格式化结果与当前成功路径一致。
3. 初始化/写入/解码最终失败的快照为 `FAILED`，保留最后尝试级别和稳定失败原因，不再落入模糊“输出待确认”。
4. DTS-HD -> DTS Core、同语言 stereo/低复杂度换轨仍分别标记 `SAME_TRACK_COMPATIBLE` 和 `LANGUAGE_OR_STEREO_FALLBACK`；手动换轨不产生自动降级链。
5. 未知布局、超过 8 声道和 AV3A 不被声道数单独宣称为原始多声道；只有观察到实际输出声道才填充 `outputChannels`。
6. 聚焦 JVM 测试覆盖空快照、四种运行时状态、六级决策级别、Exo offload/PCM/直通、MPV `audio-out-params` 和 IJK 受限事实。
7. `:app:compileMobileArm64_v8aDebugJavaWithJavac` 通过；不修改 native 资产和锁文件。

## 验证计划

本阶段已按一次性聚焦顺序执行：

```text
bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest \
  --tests com.fongmi.android.tv.player.AudioPlaybackDiagnosticsTest \
  --tests com.fongmi.android.tv.player.exo.ExoPlaybackDiagnosticsTest \
  --tests com.fongmi.android.tv.player.mpv.MpvDirectAudioPolicyTest \
  --no-daemon

bash ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon
```

若 JVM/编译通过，再在已连接的 V2453A 上使用既有测试库做最小冒烟：AAC 2.0、AAC 5.1、ALAC 2.0/5.1、AV3A 5.1 各一条，记录 Exo 与 MPV 的 `DecisionLevel`、`RuntimeState`、decoder、output mode、实际声道数和回退原因。该设备验证只确认诊断事实，不扩大为新的编解码器支持承诺。

## 回滚、观察与后续任务

- 回滚：对 C4 单一提交执行 `git revert <C4-commit>`；无需替换 AAR、native assets 或锁文件。若诊断字段与 UI 同时回退，必须作为一个原子提交撤销。
- 观察：保留现有 `PlaybackTrace`/`SpiderDebug` 日志字段；新增字段只在调试日志中输出，不改变默认播放参数文案以外的设置行为。
- 后续：C4 通过后，再分别分配任务评估 Exo 同轨兼容格式和 MPV 所有视频输出路径的一致降级；不得把这两项作为 C4 顺手改动。

## 决策记录

- 推荐：**实施 WebHTV 窄适配，已完成。**
- 置信度：高（平台/Media3/mpv 的事实边界一致，当前代码已有足够观测点）。
- 未解决问题：真实设备在 AudioTrack 创建失败与 Exo 自动重启之间的瞬时窗口尚未做专项冒烟；这不影响本阶段 JVM/编译验收，后续观察不得改变已提交的播放策略。

## Checkpoint 1：评估文档闭环（2026-09-04 14:48 CST）

- 完成：C4 决策包、证据审查、方案比较、实施边界、验收与回滚路径。
- 变更：新增本文件；更新主评估索引和《音频解码与多声道策略评估》；未修改运行时源码、AAR、native 资产或锁文件。
- 验证：`git diff --check`、`bash .codex/scripts/task_guard.sh check`、文档文件/任务索引引用检查通过。
- 评估提交：`ebb5285238aa19eeab11ec4595985d496550ced2`。
- 恢复 tag：`recovery/C4/20260904144859-ebb5285238aa`。
- 回滚：对评估提交执行 `git revert ebb5285238aa19eeab11ec4595985d496550ced2`；运行时无耦合变更。
- 当前状态：等待用户明确批准 C4 代码阶段。
- 下一动作：收到批准后，仅按“实施边界（需批准）”修改 `AudioPlaybackDiagnostics` 及三条播放器诊断接线，并先跑聚焦 JVM 测试。
