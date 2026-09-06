# E10 Exo 音频硬件解码优先

## Recovery anchor

- Objective: Exo 在直通不可用且用户没有显式选择“音频软解优先”时，优先使用真实硬件音频 MediaCodec，失败后才使用平台软件 Codec 或 FFmpeg PCM，并让能力页和播放参数分别表达“可用能力”与“实际执行”。
- Acceptance: 不改变视频 decoder、直通、AudioTrack 输出、显式软解覆盖和音频热路径；硬件 decoder 稳定置顶；初始化失败可继续尝试后备；播放参数仍只依据实际 decoder 回调。
- Branch / baseline: `feature/mpv-audio-fallback-policy` / `37995ff14016fd5a26fdae2b482f08470aa6a162`。
- Protected pre-existing dirty paths: `app/.cxx/` 下 69 个 task guard 记录的既有生成文件。
- Approved scope: `ExoAudioCodecSelector.java`、`ExoUtil.java`、`CodecCapabilityInspector.java`、聚焦单测、本文和主索引。
- Status: implemented; focused unit tests and App Java compilation pass; device validation pending because ADB is offline.
- Unresolved risk: API 24-28 的硬件分类依赖 Media3/Android 兼容启发式；当前设备 `192.168.1.9:5555` 拒绝连接，尚未核对实际 decoder 和启动时延。
- Rollback anchor: revert the atomic E10 commit or reset consumers to recovery tag created at closure.
- Next action: close the verified App-only unit with task guard commit and recovery tag.

## 用户目标与批准

用户要求补齐跨播放器的音频硬解能力：硬件支持时必须优先硬解，只有硬件路径不可用或初始化失败后才能考虑同轨降级、平台软件 Codec、FFmpeg PCM 或其他音轨。2026-08-31 的“根据成熟实现的最佳实践方案实现我的需求，注意必须保证性能”批准了既定顺序中的首个独立阶段 E10；MPV/native 和 IJK 仍是后续独立回滚单元。

## 当前实现与根因

- `ExoUtil.FfmpegRenderersFactory.buildAudioRenderers()` 先添加平台 `MediaCodecAudioRenderer`，再按 extension mode 添加 `CompatFfmpegAudioRenderer`，所以 renderer 层级本来已是平台优先。
- 平台 renderer 使用 `MediaCodecSelector.DEFAULT`。Media3 会优先考虑格式功能支持，但不会把多个同样可用的音频 decoder 明确按真实硬件、平台软件排序。
- `PlaybackPerformanceSetting.isDecoderFallbackEnabled()` 同时控制视频和音频初始化 fallback。用户关闭该性能选项时，音频也可能失去必要的 decoder 初始化后备，与“硬件优先、失败再降级”的新契约冲突。
- `CodecCapabilityInspector` 对音频不筛选硬件状态，因此“硬解能力”页面会混入 `c2.android.*`、`OMX.google.*` 等平台软件 decoder。
- `PlaybackAnalyticsListener` 和 `ExoPlayerEngine` 已记录实际音频 decoder 名称；该运行证据应继续作为参数面板唯一事实来源。

## 最佳实践证据

访问日期均为 2026-08-31。

| 来源 | revision / URL | 证据等级 | 支持的结论与 WebHTV 影响 |
| --- | --- | --- | --- |
| Android `MediaCodecInfo` | `https://developer.android.com/reference/android/media/MediaCodecInfo#isHardwareAccelerated()`、`#isSoftwareOnly()` | A，官方 API | API 29+ 可区分硬件加速与软件实现；两项必须联合判断，Codec 名称只能作为旧系统兼容证据。 |
| Android `MediaCodecList` | `https://developer.android.com/reference/android/media/MediaCodecList` | A，官方 API | 能力枚举表示设备声明支持，不保证资源当前可用；必须保留初始化失败 fallback 和实际运行回调。 |
| WebHTV Media3 fork | `e3e922d5c01bc0b564849940fe589daf37360d15`，`MediaCodecSelector` / `MediaCodecAudioRenderer` / `DefaultRenderersFactory` | A，锁定源码 | 平台 renderer 已存在；正确改动点是 audio selector 和 audio-only init fallback，不需要更换 renderer 或进入 buffer 热路径。 |
| AndroidX Media 当前树 | `0f4054759c7579d6d5ae19275af5de7b096f62c5` | A，上游源码 | decoder 列表按格式支持排序，但没有硬件音频优先产品策略；offload 偏好也不是普通视频播放的通用硬解替代。 |
| FFmpeg audio MediaCodec | `0a780d3076fe44fe6d641e84e291b567fca29999`、`a7425f712aeed6e18204a68810529895fdbdb1be`、`4a2b643646a63e2be3b85c418de2fc60750011d3` | A，上游源码/修复历史 | 仅“启用 MediaCodec”不足以保证硬件；按类型创建可能选中平台软件 Codec，必须显式分类和验证实际 decoder。 |
| mpvRex | `52477d85f578547288081ee35fc80e0e3e28a446`；`mpvRex-libmpv` tag `1eda60a6f84f9506a32910562b1136464171a675` | B，成熟项目源码 | 它展示硬件 Codec 并启用 FFmpeg MediaCodec，但 App 只设置视频 `hwdec`，没有音频 `--ad` 优先级；这是“能力存在但运行未使用”的反例。 |
| VLC | `d22301a881428c5bdc86141ff05d039c9a2885a9` | B，成熟项目源码 | 音频 MediaCodec 属于实验能力且默认关闭，说明必须保留失败隔离和软件后备，不能把厂商声明当作稳定运行保证。 |
| Kodi | `1108aed90e83f03775186dc239797d2c60d7184c` | B，成熟项目源码 | 直通和本地解码是不同路径；DTS-HD/Core 降级不能与 AAC/MP3 的 MediaCodec 选择混为一个开关。 |
| Just Player | `fb436e14a5cc03998e69a166f00401ddbc71a138` | B，Media3 应用源码 | 使用常规 Media3 renderer，没有额外硬件音频排序；不能直接满足本项目的新契约。 |
| Salt Player 公开仓库 | `48b2fde247d6b5529fee14ebf0d4198c9436cd3d` | C，产品文案；当前完整源码不可得 | 文案采用“内置不支持时才用系统 decoder”，是 fallback-only 策略，不能作为硬件优先实现证据。 |

### PR、issue、revert 与讨论

没有发现一个可直接移植、同时解决普通视频播放音频硬件优先和真实硬件分类的 Media3 上游 PR。上游 offload 限制、VLC 的实验默认和 FFmpeg 后续硬件过滤修复共同说明：选择策略必须是 WebHTV 的窄适配，且运行失败不能被能力枚举掩盖。

### 论文、基准与现场资料

此阶段不改变编解码算法、DSP 或音频数据拷贝，学术编解码论文不适用。性能判断以调用位置和设备验证为主：Codec 枚举/排序只在 renderer 查询与初始化时执行，缓存命中后为常数时间；不得加入 `render()`、`processOutputBuffer()` 或 AudioTrack 写入路径。

## 方案比较

| 方案 | 正确性 | 性能 | 风险 | 决策 |
| --- | --- | --- | --- | --- |
| 不改 | 设备可能有硬件 decoder 但运行选择平台软件或 FFmpeg | 当前性能不变 | 不满足需求，能力页继续误导 | 拒绝 |
| 原样依赖 `MediaCodecSelector.DEFAULT` 或只开启 offload | 保留上游默认，不能保证硬件优先；offload 主要适用于音频独占/音频-only 条件 | 低 | 普通视频仍可能软解，概念混淆 | 拒绝 |
| 全局修改 Media3 fork/AAR | 可以在框架层统一策略 | 需要重发全部 AAR | 回滚面大，影响所有 App 和视频 selector | 暂不采用 |
| WebHTV App audio-only selector | 真实硬件稳定置顶，平台软件和 FFmpeg 保留；实际执行继续由 callback 判定 | 查询列表按 MIME/secure/tunnel 缓存；不进热路径 | API 24-28 分类仍依赖上游启发式 | 采用 |

## 实施设计

1. 新增 `ExoAudioCodecSelector`，仅处理 `audio/*` MIME。
2. 委托 `MediaCodecSelector.DEFAULT` 获得完整候选，稳定排序为：`hardwareAccelerated && !softwareOnly`、未知/不一致状态、明确软件。
3. 以 MIME、secure、tunneling 为 key 缓存不可变列表；非音频查询原样委托。
4. `FfmpegRenderersFactory.buildAudioRenderers()` 只把 audio selector 传给平台 audio renderer，并为音频强制开启 decoder initialization fallback。视频仍服从现有性能选项。
5. 默认/自动路径保持平台 MediaCodec renderer 在前、FFmpeg renderer 在后；用户显式开启“音频软解优先”时继续作为可解释的覆盖条件。不创建双解码、预热或并行竞争。
6. `CodecCapabilityInspector` 的“硬解能力”音频列表只显示真实硬件候选；格式支持文案区分硬件与系统软件候选。
7. 参数面板不读取设置值或能力页结论，继续展示 `onAudioDecoderInitialized` 实际 decoder，并使用 Android API 分类为“硬解/软解”。

## 性能契约

- 正常音频 buffer、AudioTrack write、时钟、seek、倍速和滤镜路径零新增分支。
- 每个查询 key 至多调用底层 selector 一次；缓存值不可变，避免每次 player rebuild 重复排序。
- 排序对象通常少于十个，复杂度 `O(n log n)` 且只发生在首次初始化。
- 不启用音频 offload、不增加线程、不并行初始化多个 decoder、不改变输出 PCM 格式。
- fallback 只在首选 decoder 初始化失败时发生，正常硬解路径没有额外重试延迟。

## 验收与验证

1. 单测：硬件稳定置顶；多个硬件保持平台原顺序；软件保持后备顺序；非音频不排序；同 key 底层 selector 只查询一次；secure/tunneling key 隔离。
2. App Java 编译，确认当前 Media3 API 和 Android API 兼容。
3. 设备：AAC/MP3/FLAC/Opus 至少各一项，记录实际 decoder 名称、面板“硬解/软解”、首帧/首音时间、seek 和切集。
4. 失败路径：不支持规格或硬件初始化失败时能继续平台软件/FFmpeg，不发生循环重启。
5. 性能：对比同一媒体改动前后 prepare-to-ready、首个音频 decoder 初始化时间；不得出现稳定可感知回退。选择器日志不得按 buffer 重复。

## 回滚

E10 是 App-only 单提交，不更新 Media3 AAR、nextlib、FFmpeg、MPV native 或 ABI。回滚时恢复 `ExoUtil` 的默认 audio selector、删除 E10 selector/测试并恢复能力页筛选；以 closure 时生成的 `recovery/E10/...` annotated tag 为恢复锚点。

## 实施记录

- 2026-08-31 23:33 CST：恢复 `feature/mpv-audio-fallback-policy@37995ff14016fd5a26fdae2b482f08470aa6a162`，确认仅 `app/.cxx/` 为既有未跟踪生成目录。
- 2026-08-31：完成 Android、Media3、FFmpeg、mpvRex、VLC、Kodi、Just Player 和 Salt Player 证据复核；选择 App audio-only cached selector。
- 2026-08-31：用户已批准实施，性能为硬约束。
- 2026-08-31：新增 `ExoAudioCodecSelector`，按真实硬件、未知、明确软件稳定排序，并按 MIME/secure/tunneling 缓存不可变结果。
- 2026-08-31：Exo audio renderer 使用硬件优先 selector，并将音频 decoder initialization fallback 固定开启；视频仍服从原性能设置。
- 2026-08-31：硬解能力页不再把平台软件音频 decoder 列入硬件能力；当前媒体查询分别显示硬件和系统软件候选。
- 2026-08-31：`bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.exo.ExoAudioCodecSelectorTest --no-daemon` 通过，3 tests / 0 failures / 0 errors；同一任务完成 App Java 编译。测试套件用时 0.125 秒，其中缓存场景 0.025 秒；该数字仅证明 JVM 测试无异常，不替代设备启动基准。
- 2026-08-31：`adb connect 192.168.1.9:5555` 返回 `Connection refused`，设备验证未执行；未声称实际硬解或启动时延已在设备确认。
