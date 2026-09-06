# P3-4 MPV 音频硬件解码优先

## Recovery anchor

- Objective: MPV 在非直通音频上优先尝试真实硬件 MediaCodec，初始化或运行失败后才回退同格式 FFmpeg 软件解码；不把平台软件 Codec 伪装成硬解。
- Acceptance: 直通仍优先于本任务的 PCM 解码；AAC、MP3、AMR-NB、AMR-WB 的真实硬件 decoder 优先；不存在或初始化失败时 mpv 自动继续软件 decoder；不增加逐包、逐帧或 AudioTrack 写入热路径工作；播放参数继续显示实际 decoder。
- Branch / baseline: `feature/mpv-audio-fallback-policy` / `cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8`。
- Protected pre-existing dirty paths: `app/.cxx/` 下 task guard 记录的 69 个既有生成路径。
- Approved scope: MPV App `ad` 优先级、一个 FFmpeg 硬件筛选补丁、原生构建/资产校验、双 ARM ABI MPV assets、聚焦测试、本文和主索引。
- Excluded: FFmpeg/mpv/Media3 lock 升级，MPV JNI，Exo，IJK，新增设置，AC3/EAC3/DTS/TrueHD/FLAC/Opus/Vorbis MediaCodec wrapper，AudioTrack 输出策略。
- Status: App/native implementation、focused JVM/static checks、双 ABI native rebuild、ELF/marker 校验和 Mobile arm64 APK 资产一致性均通过；vivo V2453A 真机确认没有任何硬件音频 MediaCodec，AAC 的 `aac_mediacodec` 初始化失败后正确回退 FFmpeg PCM。该机另有 Audio HAL 压缩直出/offload 能力，但不属于本任务已批准的 MediaCodec 范围，因此 P3-4 不能单独满足“所有可用音频硬件路径优先”。
- Rollback anchor: revert the atomic P3-4 commit and restore both ABI asset directories as one unit.
- Next action: 向用户说明 MediaCodec 与 Audio HAL offload 是两套独立硬件路径，并在获得新阶段批准后为压缩 AudioTrack/offload 单独建任务；P3-4 暂不扩大 AudioTrack scope，也不以本机软件回退冒充硬解通过。

## 用户目标与批准

用户要求硬件支持音频解码时必须优先硬解，只有硬件路径不可用或失败后才考虑软件解码或降级，并明确要求参考成熟实现且保证性能。Exo 阶段 E10 已独立完成；P3-4 是后续 MPV/native 可回滚阶段。用户没有要求为具体 codec 增加选项，默认策略直接生效，现有 mpv.conf 优先模式仍保留用户的显式高级覆盖。

## 根因与当前调用链

1. `MpvPlayer.applyPreInitOptions()` 设置视频 `hwdec` 和 `audio-spdif`，但没有设置音频 `ad`。mpv 因而按 FFmpeg 默认 decoder 顺序选择音频软件解码器。
2. 锁定 FFmpeg `mediacodec_dec_get_audio_codec()` 使用 `ff_AMediaCodec_createDecoderByType()`。Android 可以为该调用返回 `c2.android.*`、`OMX.google.*` 等软件实现，所以仅启用 `aac_mediacodec` 等 decoder 也不能证明运行的是硬件。
3. 同一锁定 FFmpeg 的视频路径已经使用 `ff_AMediaCodecList_getCodecNameByType()` 枚举并过滤软件 codec，再通过 `ff_AMediaCodec_createCodecByName()` 创建确定的硬件实现。API 29+ 使用 `MediaCodecInfo.isSoftwareOnly()`；旧系统使用 codec 名称启发式。
4. mpv `--ad` 是优先级而不是排他白名单：显式 decoder 初始化失败后，会继续尝试未列出的同格式 decoder。该机制正好满足“硬解优先，失败再软解”。
5. 当前 FFmpeg 实际只注册 AAC、MP3、AMR-NB、AMR-WB 四个音频 MediaCodec decoder。其他格式没有完整 wrapper/CSD/时间戳适配，不能只加名字宣称支持。

## 最佳实践证据

访问日期为 2026-09-01。

| 来源 | revision / URL | 等级 | 支持的结论与 WebHTV 影响 |
| --- | --- | --- | --- |
| WebHTV 锁定 FongMi FFmpeg | `177f090e0503b7e013922ca903bde14b1c375f18`；`libavcodec/mediacodecdec_common.c`、`mediacodec_wrapper.c`、`allcodecs.c` | A，实际构建源码 | 音频按 MIME 创建会误选软件 codec；既有 codec-list helper 已具备硬件过滤和按名称创建能力；实际 wrapper 仅四种格式。 |
| WebHTV 锁定 FongMi mpv | `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`；`common/codecs.c`、`filters/f_decoder_wrapper.c`、`DOCS/man/options.rst` | A，实际构建源码/文档 | `--ad` 将指定 decoder 放在前面并保留其余 decoder fallback；每次 decoder 初始化按顺序尝试，失败后继续，不需要 App 重建播放器。 |
| Android `MediaCodecInfo` / `MediaCodecList` | `https://developer.android.com/reference/android/media/MediaCodecInfo#isSoftwareOnly()`、`https://developer.android.com/reference/android/media/MediaCodecList` | A，官方 API | codec 能力枚举不等于当前成功初始化；必须过滤 software-only，按名称创建，并保留初始化失败回退。 |
| FFmpeg 当前上游实现 | `FFmpeg/FFmpeg` `libavcodec/mediacodecdec_common.c`，2026-09-01 复核 | A，上游源码 | 当前上游同样让 audio MediaCodec 走 `createDecoderByType()`；没有可直接合并、同时保证真实硬件选择的已完成修复。GitHub issue 搜索 `mediacodec audio decoder` 无直接对应项。 |
| mpvRex | `52477d85f578547288081ee35fc80e0e3e28a446`；`MPVView.kt`、`CodecInformationScreen.kt` | B，成熟 libmpv App | 它明确配置视频 `hwdec=mediacodec,no` 并枚举音视频硬件能力，但没有设置音频 `ad`；说明“能力页有硬件”与“libmpv 实际使用硬件音频 decoder”是两个独立事实。 |
| SaltPlayerSource | `48b2fde247d6b5529fee14ebf0d4198c9436cd3d` | C，成熟音乐播放器公开资料 | 重点是 AudioTrack/AAudio/OpenSL ES 输出、稳定性和耗电取舍，不包含视频容器内 FFmpeg 音频 MediaCodec decoder 选择，不能直接移植；其输出性能经验支持避免双解码和热路径探测。 |
| WebHTV Exo E10 | `cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8` | A，本项目已验证实现 | 能力页与实际 decoder 必须分离；真实硬件置顶、初始化失败回退、运行回调作为面板事实来源是跨播放器一致契约。 |

### PR、issue、revert 与讨论

FFmpeg 当前上游和锁定 FongMi tree 都保留 audio `createDecoderByType()`；未找到一个已合并且覆盖“过滤平台软件 codec、按名称创建、保留 mpv 软件回退”的直接修复。因当前缺陷可由实际源代码完整解释，本阶段采用与同文件视频路径一致的窄适配，不扩大为 FFmpeg 通用 MediaCodec 重构。

### 论文、基准与现场资料

本阶段不修改编解码算法、DSP、缓冲大小、AudioTrack write 或同步时钟，学术论文不适用。性能风险来自 decoder 初始化而非稳态播放：硬件列表枚举只发生在 decoder 初始化/轨道重建，和现有视频 MediaCodec 路径相同；正常播放没有新增分支、线程、复制或内存分配。设备性能验收关注 prepare-to-audio、实际 decoder、CPU/温度趋势、seek 和切集，不以单次构建结果替代。

## 方案比较

| 方案 | 正确性 | 性能 | 风险 | 决策 |
| --- | --- | --- | --- | --- |
| 不改 | 能力页可显示硬件，但 MPV 继续默认软件解码 | CPU/功耗维持现状 | 不满足用户目标 | 拒绝 |
| 只设置 `ad=aac_mediacodec,...` | 会尝试 MediaCodec，但 `createDecoderByType()` 仍可能选平台软件 codec | 初始化成本低 | 面板仍可能显示软解，形成假硬解 | 拒绝 |
| 同时启动硬件和软件 decoder，首个成功者胜出 | 回退快 | 双倍初始化、内存和功耗，易破坏时间戳/生命周期 | 与性能硬约束冲突 | 拒绝 |
| 增加所有 Android 声称支持的音频格式 wrapper | 目标覆盖广 | 未知 | CSD、profile、pre-skip、seek-preroll 和设备行为未验证 | 暂缓，拆分后续阶段 |
| 严格硬件 selector + mpv 原生 decoder 顺序回退 | 真实硬件优先，失败后同格式软件 decoder 接管 | 只增加一次初始化枚举；稳态零开销 | 老系统硬件分类依赖既有启发式 | 采用 |

## 最终设计

1. 新增 FFmpeg patch：audio MediaCodec 调用 `ff_AMediaCodecList_getCodecNameByType(mime, -1, ...)`，只接受该 helper 筛出的非软件 decoder，再用 `ff_AMediaCodec_createCodecByName()` 创建；找不到真实硬件时返回初始化失败。
2. 不使用 `createDecoderByType()` 作为 audio fallback，避免 NDK/JNI 再次自动选中平台软件 codec。软件后备由 mpv 的 decoder 列表负责，而不是伪装成 MediaCodec。
3. App 默认设置 `ad=aac_mediacodec,mp3_mediacodec,amrnb_mediacodec,amrwb_mediacodec`。不加末尾 `-`，因此普通 FFmpeg decoder 自动保留。
4. 将 `ad` 纳入现有“播放性能优先”覆盖列表；默认性能优先保证硬解顺序，用户显式选择 mpv.conf 优先时允许其自主管理 `ad`。
5. 直通路径仍先由 `audio-spdif`/`ad_spdif` 选择；只有未直通或直通失败后的 PCM decode 才进入本任务 decoder 列表。
6. 增加 source marker 和 installed ELF marker 校验，确保两个 ABI 的 `libmvcodec.so` 确实包含硬件筛选补丁。
7. 不改变播放参数映射。它继续读取 `current-tracks/audio/decoder`；真正选中 `aac_mediacodec` 等时显示硬解，回退 FFmpeg 时显示软解。

## 性能契约

- 音频 packet receive、output buffer、重采样、AudioTrack write、时钟、seek 和倍速路径零新增逻辑。
- 每次 decoder 初始化最多执行一次既有 `MediaCodecList` 枚举；成功后不重复查询。
- 不预热、不并行创建、不双解码、不增加线程，不改变 PCM 格式和缓冲策略。
- 正常硬解路径减少 CPU 软件解码负担；没有硬件或初始化失败的设备仅承担一次有界初始化尝试，然后使用现有软件路径。
- 新增格式必须单独证明正确 CSD/profile/timestamp/seek 行为，不能以 Android 能力枚举替代。

## 验收与验证

1. JVM 策略测试：四个硬件 decoder 顺序固定；列表不带 `-`；性能优先覆盖 `ad`，mpv.conf 优先保留用户配置。
2. Patch/source：对锁定 FFmpeg patch stack 执行 `git apply --check` 和 source marker 检查。
3. 双 ABI native rebuild/install；`scripts/verify_mpv_native_assets.sh --require-elf` 检查版本、SONAME/DT_NEEDED、两 ABI、既有 marker 和新增硬件音频 marker。
4. App Java 编译及 Mobile arm64 Debug 打包，核对 APK 内 MPV assets 与工作区 hash 一致。
5. 设备：AAC/MP3 至少各一次，记录 `current-tracks/audio/decoder`、参数面板、起播、seek、切集、CPU/温度和回退日志；AMR 仅在有代表样片时验证。
6. 失败路径：设备无相应硬件或硬件 configure/start 失败时，mpv 必须继续普通 FFmpeg decoder，不循环重建播放器。

## 回滚

P3-4 的 App 选项、FFmpeg patch、构建/验证契约和双 ABI native assets 必须作为同一提交回滚。locks 和 `libplayer.so` 不变；回滚该提交即可恢复 P3/P3-3 的直通、DTS-HD Core retry、PCM/立体声策略和旧 decoder 顺序。

## 2026-09-01 真机根因复核与架构纠偏

### 设备与 MediaCodec 事实

- 设备：vivo `V2453A` / Android 15 / API 35 / QTI SM8735，ADB `10CF6H1D2L0009S`。
- 通过设备内 `MediaCodecList` API 枚举 91 个 decoder：`hardware=21`，但 `hardwareAudio=0`；21 个真实硬件 decoder 全部为视频。
- 所有音频 MediaCodec，包括 `c2.android.*`、`OMX.google.*`、`c2.vivo.*` 和 `OMX.vivo.*`，均报告 `isHardwareAccelerated=false`、`isSoftwareOnly=true`。这些实现不能作为音频硬解显示，也不能为满足目标而改名或放宽筛选。
- 当前“硬解能力”页面的设备列表使用 `isHardwareAccelerated() && !isSoftwareOnly()` 过滤，因此其 MediaCodec 维度判断是正确的；但该页面没有展示 Audio HAL/DSP offload，作为“整机音频硬件能力”视图仍不完整。

### AAC 实际播放证据

- 样本：`/storage/emulated/0/Download/声道测试/AAC-5.1-soundcheck.mp4`。
- `2026-09-01 12:54:34` 日志：`aac_mediacodec: MediaCodec 0x0 failed to start`、`Decoder init failed for aac_mediacodec`，随后 `file-loaded` 和 `playback-restart`，证明 P3-4 的负路径会继续软件播放而不是失败或循环重建。
- 同一播放时刻参数面板：`AAC 5.1 · 软件解码 · PCM 5.1 · 44.1kHz · 159kbps`。
- 同一播放时刻 AudioFlinger：WebHTV UID `10464` / PID `28813` 的活动 Track `3185` 为 format `0x5`（PCM float）、channel mask `0x3F`（5.1）、44100 Hz、flags `0x000`，进入 `DEEP_BUFFER` mixer；HAL 输出为 PCM 32-bit 立体声。现场不存在 AAC bitstream、DIRECT 或 COMPRESS_OFFLOAD Track。
- 证据目录：`/private/tmp/android-device-test-P3-4-20260901-aac-live2/`；设备能力与独立探针证据：`/private/tmp/android-device-test-P3-4-20260901-rootcause/`。

### 设备真实音频硬件路径

- Audio policy 声明 `compress_offload_out`，flags 为 `AUDIO_OUTPUT_FLAG_DIRECT|AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD|AUDIO_OUTPUT_FLAG_NON_BLOCKING|AUDIO_OUTPUT_FLAG_GAPLESS_OFFLOAD`，支持 MP3、AAC LC/HE、FLAC、ALAC、APE、Vorbis 等压缩格式。
- 标准 API 对 MP3/AAC 返回 `getDirectPlaybackSupport=4`（bitstream supported），但 `getPlaybackOffloadSupport=0`、`isOffloadedPlaybackSupported=false`。独立 AudioTrack 探针仍使 AudioFlinger 打开 AAC LC 的 type 4 OFFLOAD 线程，说明该厂商通过 direct-bitstream 请求隐式路由到 DSP offload，不能只依赖标准 offload 布尔 API。
- MPV、Exo 和 IJK 当前都把普通 AAC/MP3 解码为 PCM 后交给 AudioTrack；它们没有把容器中的压缩 access unit 直接送入该设备的普通压缩 AudioTrack/offload 路径，所以三种播放器都显示软解并不矛盾。

### 阶段边界结论

- P3-4 解决的是“设备确实暴露硬件音频 MediaCodec 时，MPV 优先选真实硬件 decoder，并保留 FFmpeg 回退”。本机验证了无硬件时的正确回退，但无法提供正向硬件 MediaCodec 样本。
- 用户的完整目标还需要一个独立的 MPV 普通压缩音频直出/offload 阶段。该阶段会改变 AudioTrack 输出、access-unit、时钟、seek/flush、路由和运行失败回退合同，属于当前明确排除的 `AudioTrack 输出策略`，不能作为 P3-4 的顺手补丁。
- 成熟参考应以 Media3 offload sink 为主：直接写压缩 access unit、正确计算每个 access unit 的采样帧数、非阻塞写入、以实际 AudioTrack 初始化/写入为准、路由或运行失败立即回退 FFmpeg PCM；不得双解码、逐帧额外复制或新增无界常驻线程。
- 播放参数的“硬解/offload”必须来自当前实际 AudioTrack/AudioFlinger 路径，而不是 MediaCodec 能力页、设置开关或预先探测结果。

## 实施记录

- 2026-09-01 06:34 CST：恢复 `feature/mpv-audio-fallback-policy@cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8`；只有 `app/.cxx/` 为既有未跟踪生成路径。
- 2026-09-01：复核锁定 FFmpeg/mpv、上游 FFmpeg、Android API、mpvRex、SaltPlayerSource 和 E10；结论为严格硬件 selector + mpv 原生顺序回退。
- 2026-09-01：用户已批准按成熟最佳实践实施，并将性能作为硬约束。
- 2026-09-01：App 设置非排他的 AAC/MP3/AMR MediaCodec decoder 优先级，并把 `ad` 纳入默认“播放性能优先”覆盖；mpv.conf 优先模式仍可显式覆盖。
- 2026-09-01：FFmpeg patch 为 codec-list helper 增加 audio-only `hardware_only` 条件；API 29+ 要求 `isHardwareAccelerated()`，同时继续排除 `isSoftwareOnly()` 和旧系统软件名称；视频调用明确传 `hardware_only=0`，行为不变。
- 2026-09-01：`git apply --check` 对锁定且已应用既有 WebHTV patch 的 FFmpeg source 通过；`bash -n` 两个 native 脚本、`git diff --check` 和 task guard scope 检查通过。
- 2026-09-01：`bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests androidx.media3.mpvplayer.MpvAudioDecoderPolicyTest --no-daemon` 通过，并在同一任务完成 `compileMobileArm64_v8aDebugJavaWithJavac`；`BUILD SUCCESSFUL in 50s`，2 tests / 0 failures。
- 2026-09-01：`scripts/build_mpv_native.sh --abi all --install --jobs 8` 完成同锁、同 patch stack 的 `arm64-v8a`/`armeabi-v7a` 全量重建和安装；两个 ABI 的 `libplayer.so` 与基线 HEAD 完全一致。
- 2026-09-01：`bash scripts/verify_mpv_native_assets.sh --require-elf` 一次通过，确认两 ABI 版本、ELF、SONAME/DT_NEEDED、命名隔离、既有 marker 和新增硬件音频 marker。
- 2026-09-01：`bash gradlew :app:assembleMobileArm64_v8aDebug` 一次通过，`BUILD SUCCESSFUL in 2m 7s`；APK 内 10 个 MPV 资产与工作区逐文件 SHA-256 一致。

### 已安装 native 资产 SHA-256

| 文件 | `arm64-v8a` | `armeabi-v7a` |
| --- | --- | --- |
| `libc++_shared.so` | `c4c2fe5cbcb1fba0003a31fc7ab29a9bb12df6cc187ec45a806462540e83d93b` | `af383654daf4cf0829615460419a180f84edd9d8bf51aa0f81ed0db811bf8491` |
| `libmpv.so` | `939a982f9f8cc15db4e8733dfe65fb7ec90519638a1a280099224bc68a70839b` | `fb1f0e5ad8e62c9c840e24267398efd9fb677cffaa815dc4082a45769e3e2825` |
| `libmvcodec.so` | `80fc4734eca70ba429e7bca7be09234dc4a5254be7bba4f616db185bfc6ea1bb` | `5ddfb43b8f89dc7f513536247829de3ca73e7e4d47abe72760f648d5b1971536` |
| `libmvdevice.so` | `f1254e49bd6055822030d87163903c43e87b2d466f86f2c57909a2250ee6b170` | `b79651000bcbc5f6e8bb5e0a2f05e12785c0178f0a277fdff43d0956d38d32d1` |
| `libmvfilter.so` | `77d171a941ed79b49d912dc70b57cf61cfe82f246e4f34d96e10574abb52213c` | `a43ab2345a9e08238f3d6ae9c124336968da22bb1f240a99d2c3e77fca673486` |
| `libmvformat.so` | `b516b98cb29212880cfe3ed545defc5670cdf269d1f0c9c78c39295650993070` | `0573908990fef09cc292b36a76dfc43a8e813fd92be5f41227161b2f8f5a93ff` |
| `libmvutil.so` | `6ad326f06037c4a538ed3ca0e5703ff5f642858c949cc34820224a5b4b5827ab` | `ac5234ff7c6b017da4cee0a68fe2c1cba8df0a0f3197a9ee38d918ae0a0f0611` |
| `libmwresample.so` | `000fffb524dd05f510b65edba9566a352b53e73a74452b8f9db13b803c247ea8` | `dafec9add8197cc2a171e9ade2cd3f0563938cb7de800231b765555c8bc0bc35` |
| `libmwscale.so` | `776554bab8797474f749bbbc4e5b65eaf3ffc7a9d0884391f5d0545526aa4a7f` | `9c488d85cfa8a8d83039bcba8a55b70a1e22446183589b848725777cfd233e63` |
| `libplayer.so` | `977f78ed786ed8b305f5625929e7e042ae03fa55eb6aaf1bb24e27086400ea41` | `6bb52e833b94e930068ad1f9f678d691a1b60ca99ee99658a11d42134df687f1` |

- Mobile arm64 Debug APK：`app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`，SHA-256 `e7c60388d449211902502dca81ff3c30ccf21d2447224bf44ab10ebf157dad2a`。
- 设备状态：`adb devices -l` 无设备，`adb mdns services` 无服务，`adb connect 192.168.1.9:5555` 返回 `Connection refused`；因此未安装 APK，也未把构建/marker 结果表述为实际 decoder 或性能通过。

## Checkpoint 1：双 ABI 构建前

- Workspace: `feature/mpv-audio-fallback-policy@cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8`；P3-4 scope 内 source/doc edits 未提交；`app/.cxx/` 仍为受保护既有路径。
- Completed evidence: 设计、代码、patch apply、脚本语法、scope/diff 和聚焦 JVM/Java 编译均通过。
- Performance reasoning: 新增 JNI codec 枚举只在 decoder 初始化函数执行；没有新增 packet receive、output-buffer 或 AudioTrack write 调用。
- Unverified: 双 ABI C 编译/链接、ELF marker、asset identity、APK packaging 和设备实际 decoder。
- Rollback: 当前仍可直接丢弃 P3-4 scope 内未提交编辑；构建安装后两套 asset 必须与 source 同提交回滚。
- Next action: `scripts/build_mpv_native.sh --abi all --install`。

## Checkpoint 2：双 ABI 与 APK 验证完成，待设备

- Completed: 双 ABI 全量 native build/install、ELF/marker/命名隔离验证、Mobile arm64 Debug APK 构建和 APK 内外逐文件哈希一致性均通过。
- Source identities: FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18`；mpv `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`；本地基线 `cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8`，locks 未改。
- Workspace: `feature/mpv-audio-fallback-policy@cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8`；P3-4 task guard active，`app/.cxx/` 仍为受保护既有路径。
- Performance evidence: source/diff 显示只在 decoder 初始化时执行一次硬件 codec 枚举；未改 packet receive、output buffer、resample、AudioTrack write、线程或双解码路径。双 ABI 编译链接和产品 APK 打包通过。
- Unverified: 设备实际选择 `*_mediacodec`、无硬件/初始化失败后的 FFmpeg 回退、AAC/MP3 起播、seek/切轨、CPU/温度趋势；这部分是阶段提交前的剩余门禁。
- Rollback: P3-4 App 策略、FFmpeg patch、构建/校验脚本和双 ABI assets 必须作为一个原子提交回滚；`libplayer.so` 和 locks 不变。
- Next action: 设备恢复 ADB 后安装 APK，并执行一次聚焦 AAC/MP3/回退/seek/切轨/性能验收。

## Checkpoint 3：vivo 真机负路径与 Audio HAL 根因

- Completed: AAC 真机播放、MPV 初始化/回退日志、参数面板、AudioFlinger 活动 Track、Audio policy 和独立 direct/offload 探针已对齐。
- Source identities: FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18`；mpv `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`；本地基线 `cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8`，locks 未改。
- Decisions/evidence: 本机没有硬件音频 MediaCodec；P3-4 的 AAC 硬件尝试失败后正确回退软件 PCM。设备具备独立的 Audio HAL 压缩 offload，但当前播放器未接入，因此原 MediaCodec-only 方案不足以覆盖用户的完整目标。
- Workspace: `feature/mpv-audio-fallback-policy@cf0a5dabc77fb2bbdc3e0f2cc867a9eac86060b8`；P3-4 task guard active，`app/.cxx/` 和既有 P3-4 代码/双 ABI 资产均保留。
- Validation: `/private/tmp/android-device-test-P3-4-20260901-aac-live2/` 中 AAC 面板为软件解码/PCM 5.1；日志记录 `aac_mediacodec` 初始化失败；AudioFlinger Track `3185` 为 PCM float 5.1/44100、flags 0、DEEP_BUFFER mixer。
- Rollback anchor: P3-4 仍按 App 策略、FFmpeg patch、构建/验证脚本和双 ABI assets 整体回滚；尚未产生 AudioTrack/offload 代码。
- Unresolved: 缺少一台暴露硬件音频 MediaCodec 的设备验证 P3-4 正路径；普通压缩 AudioTrack/offload 必须作为新批准阶段设计和实现。
- Next action: 提交新阶段决策包，明确 P3-4 保留价值与压缩 AudioTrack/offload 的独立 scope，等待用户批准后再变更输出链路。
