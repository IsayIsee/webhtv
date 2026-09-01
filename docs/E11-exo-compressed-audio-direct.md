# E11 Exo 普通压缩音频硬件直出优先

## Recovery anchor

- Objective: Exo 对设备 Audio HAL/DSP 明确支持的普通压缩音频优先发送原始 access unit 到 AudioTrack，失败后自动回退同一音轨 PCM；播放参数只显示当前实际输出链路。
- Acceptance: AAC/MP3 代表样本在 vivo V2453A 上出现 WebHTV UID 的压缩 `DIRECT/COMPRESS_OFFLOAD` AudioFlinger Track；不支持的声道/采样率保持 PCM；初始化或写入失败只触发 Media3 可恢复回退；pause、seek、flush、切轨和 A/V 同步正常；无双解码、逐帧额外复制或新增线程。
- Branch / baseline: `feature/mpv-audio-fallback-policy` / `d41155f16cd81f1354672a5479743462fc168ed9`.
- Protected pre-existing dirty paths: `app/.cxx/` 下 69 个 task guard 记录的既有生成文件。
- Approved scope: `ExoCompressedAudioDirectPolicy.java`、`ExoUtil.java`、Exo 运行输出快照/映射、聚焦测试、本文和主索引。
- Status: approved and implementation active.
- Rollback anchor: revert the atomic E11 commit or restore the recovery tag created at closure.
- Next action: run the final E11 task-guard closure with the recorded unit-test, build, and V2453A cold-start evidence.

## 用户目标与批准

用户要求解决三播放器“设备有音频硬件能力但实际始终软解 PCM”的完整问题，并已批准全部阶段实施。E11 是第一独立回滚单元，只处理 Exo；MPV native、IJK 和通用能力页分别在后续任务完成。

## 现场根因

- vivo `V2453A` / Android 15 / API 35 的 91 个 MediaCodec decoder 中没有真实硬件音频 decoder；E10 和 P3-4 正确回退 FFmpeg/平台软件 PCM。
- 设备 Audio policy 同时声明 `compress_offload_out`，支持 MP3、AAC LC/HE、FLAC、ALAC、APE、Vorbis，flags 为 `DIRECT|COMPRESS_OFFLOAD|NON_BLOCKING|GAPLESS_OFFLOAD`。
- 标准 offload API 对 AAC/MP3 返回不支持，但 `AudioManager.getDirectPlaybackSupport()` 返回 bitstream support；独立压缩 AudioTrack 即使调用 `setOffloadedPlayback(false)`，AudioFlinger 仍实际打开 AAC LC 的 type 4 OFFLOAD 线程。
- 当前 `ExoUtil.buildAudioSink()` 未启用 audio offload preference；默认 `DefaultAudioOffloadSupportProvider` 只依据标准 offload API，因此不会进入设备已有的厂商 direct-bitstream DSP 路径。

证据目录：`/private/tmp/android-device-test-P3-4-20260901-rootcause/`、`/private/tmp/android-device-test-P3-4-20260901-aac-live2/`；独立探针：`/private/tmp/AudioTrackProbe.java`、`/private/tmp/AudioOffloadInventory.java`。

## 最佳实践证据

访问日期均为 2026-09-01。

| 来源 | revision / URL | 等级 | 支持的结论与 WebHTV 影响 |
| --- | --- | --- | --- |
| Android `AudioManager.getDirectPlaybackSupport` | `https://developer.android.com/reference/android/media/AudioManager#getDirectPlaybackSupport(android.media.AudioFormat,%20android.media.AudioAttributes)` | A，官方 API | direct support 必须按实际 encoding、sample rate、channel mask 和 audio attributes 查询；不能把 MIME 或 policy 白名单单独当作运行能力。 |
| Android `AudioTrack.Builder.setOffloadedPlayback` | `https://developer.android.com/reference/android/media/AudioTrack.Builder#setOffloadedPlayback(boolean)` | A，官方 API | 标准 offload 是 AudioTrack 构建属性；厂商可能在普通 direct bitstream 请求上隐式选择 DSP，因此必须以实际压缩 Track/运行回调验收。 |
| WebHTV 锁定 Media3 | `1.11.0-alpha01-fongmi` AAR；`DefaultAudioSink`、`AudioTrackAudioOutputProvider`、`MediaCodecAudioRenderer`、`TrackSelectionParameters.AudioOffloadPreferences` | A，实际发货二进制 | 已实现压缩 access-unit 帧数、非阻塞写入、播放头、pause/seek/flush、路由回调和 offload 初始化/写入可恢复错误；WebHTV 只需补能力判定与选择策略，不应重写热路径。 |
| AndroidX Media issue #2258 | `https://github.com/androidx/media/issues/2258` | B，上游设备问题 | 路由/显示切换可能使压缩输出退回 PCM；参数面板必须取当前 AudioTrack 配置，不能取预先能力或设置值。 |
| vivo V2453A Audio HAL 与独立探针 | 上述本地证据，设备 build/API 35 | A，目标设备实测 | 标准 offload false 与实际 DSP direct/offload 可同时存在；厂商适配应以官方 direct support 为门禁，并让 AudioTrack 初始化作为最终裁决。 |
| P3-4 / E10 本项目实现 | `d41155f16cd81f1354672a5479743462fc168ed9`、`cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8` | A，本项目代码与真机证据 | MediaCodec 能力与 Audio HAL 能力是两个维度；实际输出失败必须回退，能力页和运行面板必须分离。 |

### 证据类别说明

- PR/issue/revert：issue #2258 证明输出路由可在运行时改变；未发现可直接覆盖 vivo 非标准 direct-bitstream offload 的通用上游修复。
- 成熟相关实现：Media3 本身是本阶段采用的成熟实现；mpvRex、SaltPlayerSource 没有比 Media3 更完整的视频容器 compressed AudioTrack 时钟/seek 合同，因此不复制其输出层。
- 论文/基准：本阶段不改变编解码算法；学术论文不适用。性能以零双解码、零新增热路径复制和真机 CPU/AudioFlinger 证据验收。

## 方案比较与决定

| 方案 | 正确性 | 性能 | 风险 | 决定 |
| --- | --- | --- | --- | --- |
| 不改 | 所有普通压缩音频继续软件 PCM | CPU/功耗较高 | 不满足目标 | 拒绝 |
| 仅启用 Media3 默认 offload | 标准 API 正常设备有效，vivo 仍被判不支持 | 成熟稳定 | 覆盖不足 | 拒绝 |
| App 自建 packet/AudioTrack 管线 | 可完全控制 | 容易重复复制、时钟和生命周期代码 | 回归面大 | 拒绝 |
| Media3 管线 + 标准 offload/direct 双证据 provider | 复用成熟 encoded 管线，设备明确支持才启用，失败可恢复 PCM | 稳态无额外解码/复制 | 厂商 direct 模式需真机严验 | 采用 |

## 实施设计

1. `DefaultTrackSelector` 保留 `AUDIO_OFFLOAD_MODE_ENABLED`，仅供标准 Media3 offload 使用；厂商 direct 不再伪装成 offload。
2. 新 provider 先保留标准 passthrough/offload 判断；标准输出不支持时，仅对 Media3 已能计算 encoded sample frames、Android encoding 有效、系统 direct playback 明确支持的格式返回 `FORMAT_SUPPORTED_DIRECTLY`，使 `MediaCodecAudioRenderer` 走 encoded bypass。
3. direct 查询使用真实 sample rate/channel mask/audio attributes；未知声道、超出 policy 的 AAC 5.1 等不强行启用。
4. 厂商 direct 输出使用 encoded、non-offload `AudioOutputProvider.OutputConfig`，AudioTrack builder 最后固定 256 KiB 缓冲并设置 `setOffloadedPlayback(false)`；vivo HAL 可隐式路由 DSP，同时该路径不受 Media3“仅纯音频允许 offload”的限制。
5. 厂商 direct AudioTrack 初始化或写入失败时，在本次 player 会话中拉黑该 encoding/sample-rate/channel-mask；Media3 重新选择同轨 decoder + PCM，不持久化错误状态。
6. 运行面板以 AudioTrack 初始化快照中的实际 encoding/output mode 为事实；能力或设置只用于选择，不能直接生成“硬件直出”文案。

## 性能与兼容合同

- 不并行创建软件 decoder，不双解码，不预热，不增加线程。
- 不进入 PCM buffer 内容处理，也不复制数据；encoded write 只增加异常边界，正常成功路径没有额外分配；能力查询只发生在格式支持/AudioTrack 配置阶段。
- 标准 Media3 offload 设备保持上游行为；厂商 direct 适配只在标准 provider 不支持且系统 direct support 明确成立时生效。
- 倍速、音频处理器、karaoke/音效等需要 PCM 的场景由 Media3 support/selection 约束自动保留 PCM。
- HDMI 原码直通和普通压缩 DSP 直出是不同语义；E11 不改变既有 AC3/DTS/TrueHD passthrough 决策。

## 验收、发布与回滚

1. 聚焦单测覆盖标准支持优先、direct fallback、未知格式拒绝、encoding 白名单和同配置失败后不重试。
2. App Java 编译与 Mobile arm64 Debug APK。
3. V2453A 播放 AAC/MP3 stereo：AudioFlinger 必须显示 WebHTV UID 的压缩 DIRECT/OFFLOAD Track，面板显示实际硬件直出；AAC 5.1 必须保持 PCM。
4. pause/resume、seek、切轨/切集、路由变化和至少一次失败回退；不得循环重建或 A/V 漂移。
5. 比较同一输入 CPU/温度和 prepare-to-audio；稳态不得比 PCM 路径增加可见负担。
6. E11 为 App-only 单提交，不更新 Media3 AAR/native；异常时回滚该提交即可恢复默认 PCM/直通行为。

## 实施记录

- 2026-09-01 13:18 CST：恢复 `feature/mpv-audio-fallback-policy@d41155f16cd81f1354672a5479743462fc168ed9`，确认仅 `app/.cxx/` 为既有受保护生成目录，V2453A USB 在线。
- 2026-09-01：完成锁定 Media3 二进制 API/字节码、Android 官方 API、目标设备 Audio policy/AudioFlinger/独立探针和 issue #2258 复核；用户已批准实施。
- 2026-09-01 16:41 CST：ADB 直接启动 `AAC_LC_2.0_48kHz.mp4`，provider 多次判定 `vendor-direct`，但 Media3 仍创建 `c2.android.aac.decoder` 和 PCM AudioTrack。锁定 Media3 字节码确认 `DefaultTrackSelector.maybeConfigureRendererForOffload` 在任何非音频 renderer 被选中时禁止 offload，因此原实现只可能覆盖纯音频，不能满足视频播放目标。
- 2026-09-01 16:49 CST：ADB 直接启动 MP3 纯音频，Media3 选中 vendor offload 后 AudioTrack 初始化失败并自动回退 `c2.android.mp3.decoder` + PCM；独立 `app_process` 探针确认同一 AAC/MP3 配置采用 non-offload direct AudioTrack 和 256 KiB 缓冲均能成功打开。实施设计据此修正为 encoded bypass + non-offload direct AudioTrack。
- 2026-09-01 18:04 CST：使用手机测试库 AAC 视频连续完成 v4/v5 命令行回放。Media3 provider 在 encoding=10、48 kHz、stereo、256 KiB、session=0、non-tunneling、non-offload 下仍返回 `status=-38`；同一设备、同一 WebHTV UID、同进程名的独立探针在完整 AudioAttributes、重复 builder setter、32 次 direct-support 查询后均成功创建 AAC OFFLOAD 线程。已否定 tunneling、session、virtual-device Context、扩展 AudioAttributes、setter 顺序及 capability-query 竞争假设，下一步仅替换 vendor-direct 的建轨动作，仍由 Media3 AudioTrackAudioOutput 管理时钟与生命周期。
- 2026-09-01 18:59 CST：连续播放失败根因进一步确认：vendor-direct 建轨异常后虽然配置已加入失败集合，但 provider 仍透传底层标准 passthrough/direct 支持，Media3 没有重新选择 PCM decoder。采用同一 Exo 引擎内复用 direct policy、由 `ExoPlayerEngine.handleError()` 原地重启当前 item 一次、并对失败配置强制返回 encoded 不支持的方案，避免新增播放器实例和重试循环。
- 2026-09-01 22:39 CST：V2453A 冷启动播放手机测试库 `A11_MP3/MP3_2.0_44.1kHz_128kbps.mp3`。首次 vendor-direct 初始化返回 `status=-38` 并上报 `ERROR_CODE_AUDIO_TRACK_INIT_FAILED`；同一 PID 随后创建 `c2.android.mp3.decoder`，未发生用户二次点击。
- 2026-09-01 23:00 CST：为避免 3.7 秒 AAC 样本在恢复窗口结束前自然结束，临时生成 59 秒 AAC LC 2.0 副本（仅 `/private/tmp`，未进入测试库/Git）。首次播放在 `mediaPos=0.00` 报 direct 初始化失败，随后同一 PID 创建 `c2.android.aac.decoder`；AudioFlinger 记录 PID 23905、Track 3367、PCM 16-bit、48 kHz、stereo，播放器保持 `VideoActivity`。
- 2026-09-01 23:02 CST：V2453A 冷启动播放 `A01_AAC/AAC_5.1_声道.mp4`。AudioFlinger 记录 PID 24878、Track 3368、PCM 16-bit、44.1 kHz、channel mask `0x3F`（6 声道）；该声道组合未进入 vendor-direct，直接走 decoder + PCM。
- 2026-09-01 23:06 CST：确认短 AAC 样本的首次 2 秒抓取不作为验收证据；其后长样本已闭合首次自动回退、解码器初始化和 PCM 输出链路。

## 测试库扩展（2026-09-01）

依据 V2453A 的 `compress_offload_out` profile（MP3、AAC-LC/HE、FLAC、ALAC、APE、Vorbis），在本机与手机 `/storage/emulated/0/Download/影音测试库/` 各补充以下 6 个可追溯样本。每个文件已用 `ffprobe` 验证编码/采样率/声道，并完成手机回读 SHA-256 比对；这些样本用于后续 Exo/MPV/IJK 能力覆盖和回退测试，当前 E11 的 Exo 直出验收仍限定 AAC/MP3。

| 文件 | 实际格式 | 来源 | SHA-256 |
| --- | --- | --- | --- |
| `A01_AAC/AAC_HE_V1_2.0_44.1kHz.aac` | HE-AAC v1，44.1 kHz，2.0，ADTS | `https://samples.ffmpeg.org/A-codecs/suite/AAC+/WishI-48kSBR.aac` | `5326480b94f1828fdfce398a6bf528308e7152dbf44b9d4838f4633efaf5a414` |
| `A01_AAC/AAC_HE_V2_2.0_44.1kHz.aac` | HE-AAC v2，44.1 kHz，2.0，ADTS | `https://samples.ffmpeg.org/A-codecs/suite/AAC+/WishI-48kSBRPS.aac` | `91f9eba3b402b755d05dfc503220ba4b85ca877c95f16464117241b7852bbd46` |
| `A12_ALAC/ALAC_2.0_48kHz.mov` | ALAC，48 kHz，2.0，MOV | `https://samples.ffmpeg.org/A-codecs/lossless/ALAC/ALAC_24bits2.mov` | `3ddb9d38a4ec51cde8be6c5840cb622f77d20aa7a4652b613d7b49ab181e9e55` |
| `A12_ALAC/ALAC_5.1_48kHz.mov` | ALAC，48 kHz，5.1，MOV | `https://samples.ffmpeg.org/A-codecs/lossless/ALAC/ALAC_6ch.mov` | `944a78472074f6c0a4df74d0126eede3581f70cd171943dfe45c93a3c0e8dc35` |
| `A13_APE/APE_2.0_44.1kHz_sh3.ape` | Monkey's Audio，44.1 kHz，2.0 | `https://samples.ffmpeg.org/monkeyaudio/sh3.ape` | `9b8e89b81a87001648d58dc9ef440a5b9b8c214a4df07bd22776da1ff6e32004` |
| `A14_Vorbis/Vorbis_2.0_44.1kHz_160kbps.ogg` | Vorbis，44.1 kHz，2.0，160 kbps，Ogg | AndroidX Media3 `2bc207851df311340767e913931ca7b28cab1794` `media.exolist.json`; `https://storage.googleapis.com/exoplayer-test-media-1/ogg/play.ogg` | `d5bdb7257d6b9bb2d22c005685e4fa0984db32ac1963b792414916ee79352f62` |

`samples.ffmpeg.org` 是 MPlayer/FFmpeg 测试样本集合，新增样本仅用于本地/测试设备验证，不对外重新分发。
