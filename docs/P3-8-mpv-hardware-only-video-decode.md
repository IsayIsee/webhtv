# P3-8 MPV 硬解失败禁止自动切换软件视频解码

## Recovery anchor

- Objective: MPV 默认硬解路径在 MediaCodec 不可用、初始化失败或运行失败时直接报告视频解码失败，不自动改用 FFmpeg 软件视频解码；软件解码只能由用户手动切换。
- Acceptance: 硬解配置始终传 `hwdec-software-fallback=no`；正常硬解不改变；硬解失败日志不再进入软件 decoder；手动软件模式仍使用 `hwdec=no`；不改 Exo/IJK、native ABI、FFmpeg lock 或输出路径。
- Scope: `MpvPlayerEngine` 的 MPV option 接线、对应 JVM policy test、本任务文档和主 assessment index。
- Protected pre-existing dirty paths: `.gitignore`、`AGENTS.md`、`app/.cxx/**`、`docs/音频DSP整合方案.md` 及其他 task-guard 记录的初始 dirty 路径。
- Rollback: revert the atomic P3-8 commit; no native asset or lock rollback is required.
- Status: implementation and focused Java verification complete; device acceptance remains pending because this turn has no connected TV endpoint.
- Next action: close the task guard with the recorded verification.

## 用户问题与现场证据

电视在线日志 `/private/tmp/webhtv-tv-online-log-latest.txt` 显示：

- `h264_mediacodec` 因 MediaCodec input/output ports unavailable 连续失败。
- MPV 随后尝试 `h264_mediacodec-copy`，再出现普通 `h264` 软件 decoder 日志。
- 失败前的配置是硬解，播放器最终出现 `hwdec=no`；这符合 MPV 默认 `hwdec-software-fallback=3` 的行为。

该问题与 Surface Direct/GPU 或 Vulkan/OpenGL 输出恢复不同：这些恢复仍保持 `decode=HARD`，本任务不删除它们。

## 当前代码路径

- `app/src/main/java/com/fongmi/android/tv/player/engine/MpvPlayerEngine.java` 根据用户选择把硬解模式设置为 `mediacodec`、`mediacodec-copy` 或二者，软件模式设置为 `no`。
- MPV 锁定源码 `FongMi/mpv` commit `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` 的 `video/decode/vd_lavc.c` 将 `hwdec-software-fallback` 默认设为 `3`。当硬解连续帧失败时，`force_fallback()` 会重建 decoder；当值为 `INT_MAX`（配置值 `no`）时，`select_and_set_hwdec()` 设置 `force_eof`，不会继续普通软件解码。

## 最佳实践证据

访问日期：2026-09-05。

| 来源 | revision / URL | 等级 | 结论与本项目影响 |
| --- | --- | --- | --- |
| WebHTV 锁定 MPV | `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`；`video/decode/vd_lavc.c` | A | `hwdec-software-fallback=no` 是 MPV 原生的“硬解失败即结束”开关；无需改 decoder wrapper 或 native ABI。 |
| MPV 官方选项文档 | `DOCS/man/options.rst`；`--hwdec-software-fallback=<yes|no|N>` | A | 默认值为 3；`no` 明确禁止硬件解码失败后的软件回退。 |
| MPV 官方 decoder wrapper | `filters/f_decoder_wrapper.c`；`mp_select_decoders()` | A | `--vd` 是候选优先/排除列表，不能单独替代硬解失败策略；不采用写死 decoder 名称的脆弱白名单。 |
| 当前电视日志 | `/private/tmp/webhtv-tv-online-log-latest.txt` | A，本项目现场 | 已观察到 MediaCodec 失败后进入普通 `h264` decoder，证明默认回退确实触发。 |
| 论文/博客/论坛 | 不适用 | - | 本任务只改变播放器失败策略，不改变编解码算法、码流格式或性能模型；源码和官方选项定义足以决定方案。 |

## 方案比较

| 方案 | 结果 | 决策 |
| --- | --- | --- |
| 不改 | 硬解失败后仍可能高 CPU 软解，掩盖设备/输出故障 | 拒绝 |
| 只设置 `vd` 硬件 decoder 名称 | 名称依赖 FFmpeg/厂商注册，且不能覆盖运行时 hwdec failure fallback；维护和兼容性风险高 | 拒绝 |
| 修改 native wrapper，在每次失败处直接退出 | 侵入 FFmpeg/mpv native 热路径，需要双 ABI 重建，且容易破坏既有硬件尝试顺序 | 拒绝 |
| App 在 MPV 初始化时设置 `hwdec-software-fallback=no` | 使用 MPV 官方语义，覆盖初始化失败和运行失败；硬解路径零额外稳态开销；手动 `hwdec=no` 仍可软件解码 | 采用 |

## 实施与验收

1. `MpvPlayerEngine.buildConfig()` 固定传 `hwdec-software-fallback=no`，并用聚焦 JVM test 锁定选项名和值。
2. 正常硬解仍使用现有 `mediacodec`/`mediacodec-copy` 顺序；不改 Surface Direct、Vulkan/OpenGL 恢复和 DV fallback。
3. 用户手动切换软件时仍构建 `hwdec=no`；`hwdec-software-fallback` 对该路径不生效，不阻止软件播放。
4. 运行聚焦 JVM test 和 Mobile Arm64 Java 编译。由于没有 native/lock/ABI 变化，不触发双 ABI native rebuild。
5. 设备验收时应看到硬解失败后报错或结束，日志不再出现从硬件 decoder 转入普通 `h264`/`hevc` 软件 decoder；切换软件后仍能播放。

## 验证记录

- `git diff --check`: passed.
- `bash .codex/scripts/task_guard.sh check`: passed; all initial dirty paths remained protected.
- `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.engine.MpvHardwareDecodePolicyTest :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon`: passed; `BUILD SUCCESSFUL in 38s`.
- Native/ABI verification intentionally not run: this change only adds an App MPV option and does not modify native source, patches, locks, or assets.

## 回滚

回滚 P3-8 原子提交即可恢复 MPV 默认的软件回退策略；不涉及 `libmpv.so`、FFmpeg、JNI、Exo、IJK 或输出恢复代码。
