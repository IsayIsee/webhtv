# P7：MPV 脚本自定义按钮

## Recovery anchor

- Objective: 将 MPV 配置管理的 `scripts` 改造成可维护的自定义播放按钮入口，并接入移动端/电视端 MPV 控制条。
- Acceptance: 普通 `.lua/.js` 管理语义不变；自定义按钮支持标题、短按 Lua、长按 Lua、启动 Lua、启用状态和固定最多 8 个按钮；按钮通过 `script-message` 调用；非 MPV 播放不显示；桥接脚本不触发 GPU 视频处理判定；Java 编译和核心 JSON 解析验证通过。
- Current stage: implementation complete; local Java compilation and device UI smoke test passed.
- Next action: 完成 task guard 原子提交并创建恢复 tag。

## 任务与范围

- 任务 ID：`P7-MPV-SCRIPT-BUTTONS`
- 分类：MPV App 层功能，依赖现有 libmpv 脚本 API，不修改 native/FFmpeg。
- 用户能力：在“高级设置 → MPV 配置管理 → scripts”中维护自定义按钮；播放时按钮出现在现有操作条，短按/长按分别执行用户 Lua。
- 明确排除：Anime4K 不并入本阶段；不改变普通脚本导入、编辑、重命名、删除和自动加载行为；不允许按钮脚本成为硬件解码自动回退机制。

## 当前 WebHTV 覆盖与缺口

- `MpvConfigStore` 已管理 `files/mpv/scripts` 下的 `.lua/.js` 文件，并提供文本、文件、URL 三种入口。
- `MpvConfigDialog` 已有 scripts Tab，但没有按钮模型、按钮编辑器或播放控制桥。
- `MpvPlayer` 内部已有安全的同步/异步 MPV 命令封装，但没有向 App 控制层公开的脚本消息 API。
- 两端 `VideoActivity.setupActionButtons()` 已向现有横向操作条注册内置按钮，TV 端已有焦点导航。
- `MpvConfigStore.hasGpuVideoProcessing()` 当前按脚本文件计数；新增桥接 Lua 必须排除，否则会错误禁用 surface-direct。

## 上游与成熟实现证据

| 来源 | 完整 revision/路径 | 证据与 WebHTV 适用性 |
| --- | --- | --- |
| mpvRex | `https://github.com/sfsakhawat999/mpvRex@1acb3397299af81e4b8c956f599eac741f9adfa8` | 首次自定义 Lua 按钮；使用 `mp.register_script_message` 和 `MPVLib.command("script-message", ...)`。可复用消息模型。 |
| mpvRex | `https://github.com/sfsakhawat999/mpvRex@73dc46442122e7a7152f88b9819101fab9c7b96c` | 固定 8 slot，限制控制条规模，适合 WebHTV TV/移动端。 |
| mpvRex | `https://github.com/sfsakhawat999/mpvRex@eccfb6009ca441fe73dc30f3d3ffda1f1d44d58d` | 抽取 `CustomButtonManager`，确认生成脚本与 UI 状态可分离。 |
| mpvRex | `https://github.com/sfsakhawat999/mpvRex@223822a5dab12790ab63f1956b24ee5f1a6920d9` | Lua 编辑器 UI 重构，证明短按、长按、启动代码分栏是成熟交互。 |
| MPV manual | `https://mpv.io/manual/stable/`（访问日 2026-09-05） | `script-message`、`script-message-to`、`load-script` 和 Lua `mp.register_script_message` 是稳定公开 API。 |
| 本地 MPV 源码 | `build/mpv-native/mpv-android/buildscripts/deps/mpv@cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` | `player/command.c` 实现脚本消息和运行时加载；`player/lua/osc.lua` 提供按钮命令配置范式。 |

MPV 官方 Anime4K 资料在本阶段未作为实现依据：网络获取官方 raw README 超时；已有 mpvRex 实现可用于价值评估，但不足以改变本阶段范围。

## 方案比较

| 方案 | 结果 | 结论 |
| --- | --- | --- |
| 不改现状 | 只能编辑脚本文件，无法在操作条维护/调用按钮 | 不满足需求 |
| 原样照搬 mpvRex 独立 preference | 功能完整，但形成第二套脚本配置，和现有 scripts 脱节 | 拒绝 |
| 在任意 Lua 文件中解析注释元数据 | 无第二文件，但导入脚本兼容性差，编辑和校验脆弱 | 拒绝 |
| WebHTV 适配：scripts 下受管 JSON + 生成桥接 Lua | 复用现有文件目录与管理入口；按钮数据和普通脚本隔离；消息命名空间可控 | 推荐并实施 |

## 最终设计

- 数据文件：`files/mpv/scripts/custombuttons.json`，每项含 `id`、`title`、`content`、`longPressContent`、`onStartup`、`enabled`、`order`；ID 使用 UUID/安全字符，最多保留 8 项。
- 生成文件：`files/mpv/scripts/webhtv-custom-buttons.lua`。只注册 `webhtv-custom-button` 消息并执行对应 Lua；错误使用 `pcall` 记录，不影响播放器主流程。
- 配置 UI：scripts 页复用 MPV 配置管理顶部已有的“新建”按钮；切换到 scripts 后点击“新建”进入自定义按钮管理界面，编辑器提供标题、启用、短按、长按、启动代码；列表只显示普通 Lua/JS 脚本。
- 播放桥：`MpvPlayer` 暴露窄范围 `sendScriptMessage`；`MpvPlayerEngine` 和 `PlayerManager` 提供 MPV 专用调用，UI 不直接依赖全局 `MPVLib`。
- 播放控制：两端复用既有 action container，最多插入 8 个启用按钮；Android 长按触发 `long`，短按触发 `short`；非 MPV 播放隐藏。
- 生效时机：按钮文件在下次 MPV 实例创建时自动加载；配置变更不强制重建当前会话，避免打断播放。后续如需热重载，使用独立任务扩展。
- GPU 输出：`hasGpuVideoProcessing()` 忽略 `webhtv-custom-buttons.lua`，避免桥接文件误判为视频处理。

## 合同、风险与防护

- 普通 `.lua/.js`、现有 `mpv.conf`/`input.conf` 结构、PlayerButtonSetting 内置按钮顺序均保持。
- JSON 损坏、字段缺失、重复/非法 ID、超长文本时丢弃无效项或返回可见错误，不删除普通脚本；写文件采用现有有界 UTF-8 校验。
- Lua 是用户代码，首版不做沙箱；只做 ID/文件名安全校验、函数调用 `pcall` 和错误日志。用户可执行任意 MPV Lua API，这是功能本身的权限边界。
- 动态按钮不写入 `PlayerButtonSetting` 全局顺序，避免污染内置按钮偏好和 TV 焦点链；按 JSON 顺序追加到现有操作条。
- 桥接脚本重复加载、当前会话重载、脚本语法错误是主要生命周期风险；通过“下次实例生效”、唯一文件名和加载失败日志规避。
- APK 体积无新增 shader/native 资产；运行时只有少量按钮 View 和 Lua 表，性能影响可忽略。

## 验收标准

1. `MpvConfigStore` JSON round-trip、非法 JSON、重复/非法 ID、最多 8 项和桥接脚本生成测试通过。
2. `:app:compileDebugJavaWithJavac` 通过；`git diff --check` 通过。
3. 移动端和电视端 MPV 播放时启用按钮可见，短按/长按分别发送消息；Exo/IJK 播放时按钮隐藏。
4. scripts tab 顶部“新建”可打开自定义按钮管理；新建、编辑、删除、禁用、重新进入配置页后数据保持；普通 Lua/JS 脚本仍可导入和运行。
5. MPV 日志能看到桥接脚本加载/用户脚本错误，且不影响播放；surface-direct 判定不因桥接脚本改变。
6. TV 遥控器可聚焦、确认、长按按钮，横向操作条不发生布局跳变。

## Anime4K 独立评估

- 价值：480p/720p 动画放大到 1080p/4K 时可改善线条锐度；原生 4K/8K 或真人视频价值有限。
- 代价：成熟实现通常包含 3–5 个 GLSL pass；GPU、功耗、温度和掉帧风险明显，可能与 HDR/DV、字幕、LUT、Vulkan/gpu-next 冲突；shader 资产约增加 956 KB，另有维护和许可证成本。
- 建议：不与 P7 混合；若后续实施，默认关闭、实验性标识、限制高分辨率输入、先做单一保守 preset，并用手机/电视实际掉帧和 GPU 负载验收。

## 回滚与状态

- 回滚锚点：`ea9587dcb7feb187cc91f5f958f35696fea2996a`。
- 回滚方式：恢复本任务提交中的 Java/UI/资源/文档改动；用户已有的 `files/mpv/scripts` 数据文件可保留，旧版本会忽略 `custombuttons.json` 和桥接 Lua。
- 用户决策：已授权实施（2026-09-05）。

## 实施记录

- `MpvConfigStore` 增加最多 8 项按钮的 JSON 读写、ID/文本校验、生成式 `webhtv-custom-buttons.lua`、启动/短按/长按代码和受管 scripts 条目。
- `MpvPlayer.sendScriptMessage()`、`MpvPlayerEngine.sendScriptMessage()`、`PlayerManager.sendMpvCustomButton()` 建立窄范围调用链，UI 不直接依赖 `MPVLib`。
- `MpvCustomButtonDialog` 提供列表、新建、编辑、启用/禁用、删除；`MpvConfigDialog` 在 scripts 目标复用顶部“新建”按钮打开管理界面，`MpvConfigCreateDialog` 保持普通配置创建流程。
- 移动端/电视端 `VideoActivity` 将启用按钮追加到既有 action container，支持短按/长按和 MPV-only 可见性；TV 按钮可聚焦。
- `hasGpuVideoProcessing()`、普通脚本列表和脚本清理排除受管桥接 Lua；普通 Lua/JS 语义保持不变。

## 验证记录

- `bash ./gradlew compileMobileArm64_v8aDebugJavaWithJavac compileLeanbackArm64_v8aDebugJavaWithJavac --no-daemon`：通过（BUILD SUCCESSFUL，1m43s）。
- `git diff --check`：通过。
- `bash .codex/skills/upstream-integration-governor/scripts/verify_upstream_checkpoint.sh docs/upstream-player-dependency-merge-assessment-2026-08-20.md`：通过（文档阶段验证）。
- `app-mobile-arm64_v8a-debug.apk` 已安装至 `V2453A`（serial `10CF6H1D2L0009S`）；切换 scripts tab 后确认列表不含独立自定义按钮条目，顶部“新建”存在并能打开中文自定义按钮管理界面；继续打开“添加按钮”进入中文编辑页；该流程无 `FATAL EXCEPTION`。

## 当前状态

- 状态：implementation complete, local compilation and mobile UI smoke test passed。
- 已知限制：配置修改在下次 MPV 实例创建时生效；当前播放会话不热重载桥接脚本。Lua 代码按用户权限执行，不提供沙箱。

## 修复记录：2026-09-05

- 首版设备崩溃原因：`MpvCustomButtonDialog.button()` 使用 `App.get()` 创建 `MaterialButton`，Material 主题校验失败；已改为使用当前 Dialog 的 themed `Context`，编辑器输入框同样修正。
- 交互调整：移除 `MpvConfigCreateDialog` 中额外的自定义按钮卡片和 scripts 列表中的伪造 profile；自定义按钮改为 scripts tab 顶部“新建”按钮的专用行为，列表只保留真实脚本，管理界面沿用配置管理/新建配置的标题栏、关闭按钮和对话框外壳。
- 文案调整：新增按钮相关 strings 已补齐简体中文和繁体中文，状态文案不再硬编码英文 `ON/OFF`。
- 修复后验证：打开 MPV 配置管理、进入 scripts、确认无独立自定义按钮条目、点击顶部“新建”打开中文自定义按钮管理，再进入按钮编辑页；未复现崩溃。
