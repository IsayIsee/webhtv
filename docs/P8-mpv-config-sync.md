# P8：MPV 配置管理一键同步

## Recovery anchor

- Objective: 在现有一键同步中可选同步 MPV 配置管理的 `mpv.conf`、`input.conf`、`scripts/` 和维持配置列表所需的私有 profile 元数据/快照。
- Acceptance: 不同步整个 `files/mpv`；接收端路径不可穿越；普通同步选项保持兼容；开启 MPV 选项后直连、管理页和远程托管三条链路均能导出/恢复；恢复后 MPV 配置管理列表、选中项、脚本及自定义按钮数据一致。
- Current stage: implementation complete; local compile and focused sync tests passed.
- Next action: task guard finish, atomic commit and recovery tag.

## 任务与范围

- 任务 ID：`P8-MPV-CONFIG-SYNC`
- 分类：MPV App/common sync contract；不修改 MPV native、FFmpeg 或播放策略。
- 允许路径：`SyncOptions`、`Backup`、`MpvConfigStore`、新增 `MpvConfigSync`、一键同步 UI、`Action`、`Manage`、`RemoteSyncTransfer`、对应 strings/layout、聚焦测试和本文件/主 assessment 索引。
- 明确排除：`files/mpv/script-opts`、`fonts`、`shaders`、缓存、native assets、其它应用私有目录；不改变默认同步选项语义。

## 当前代码证据

- `MpvConfigStore` 的三个管理目标固定为 `mpv.conf`、`input.conf`、`scripts`，实际位于 `<filesDir>/mpv`；scripts 中还包含 `custombuttons.json` 与生成的 `webhtv-custom-buttons.lua`。
- profile 列表由 `mpv_config_profiles*`、`mpv_config_selected*`、`mpv_config_name/source/type/history*` 和 `<filesDir>/mpv/profiles/<target>/*.conf` 共同决定；只同步三个运行文件会留下 stale UI 状态或缺少快照。
- `SyncFiles` 只能访问 `Path.root()` 下的外置目录；`AppBackup` 对 `mpv` 私有目录采用最小白名单，不能直接复用。
- 直连 `OneKeySyncDialog`、HTTP `Manage`、设备端 `Action` 和远程托管 `RemoteSyncTransfer` 都通过 `Backup + multipart part` 传输。

## 方案与最佳实践决策

| 方案 | 结论 |
| --- | --- |
| 不改/只同步三个运行文件 | 拒绝；播放可能更新但配置列表、选中 profile、历史和快照不一致。 |
| 复用 `SyncFiles` 或整个 `files/mpv` | 拒绝；前者越过 app-private 边界，后者会泄漏字体、shader、script-opts 和缓存。 |
| 独立 allowlist archive + `mpv_config_*` prefs | 采用；最小权限、可单独勾选、与现有 multipart/Backup 兼容，恢复后 UI 数据可重建。 |

设计依据：Android app-private 文件不应通过外置目录同步；ZIP 恢复必须 canonical-path 校验并限制允许前缀；配置数据与运行文件需要同一事务批次恢复。上述原则与现有 `SyncFiles`/`LoginStateSync` 的路径校验和 part 设计一致，本任务不引入新的依赖或 native 行为。

## 最终设计

- `SyncOptions` 增加默认关闭的 `mpvConfig` 字段；一键同步增加中文复选项“MPV 配置管理”。
- `Backup.include()` 仅在 `options.isMpvConfig()` 时包含 `mpv_config_` 前缀的 profile/name/source/type/history/selected/migrated 偏好。
- 新增 `MpvConfigSync`：创建独立 zip part `mpvConfigFiles`，只收集两个 conf、`scripts/` 全部文件、`profiles/` 下两个 target 的快照；恢复只接受这些前缀，canonical-path/条目大小/总量受限，并在完成后调用 `MpvConfigStore.ensureCustomButtonScript()`。
- 三条发送链路在 `options.isMpvConfig()` 时生成/上传该 part；接收链路恢复后再执行 `Backup.restore(options, force)`，保证偏好与文件同批落地。
- 结果摘要增加 MPV 文件计数；缺少该 part 时不删除接收端已有 MPV 文件，保持向后兼容。

## 合同、风险与回滚

- 普通同步选项、外置 `syncFiles`、登录态 part 和远程 relay 语义保持不变。
- 归档路径使用固定前缀和文件名检查，拒绝 `..`、绝对路径、符号链接逃逸和超大条目；恢复写入临时文件后替换，异常不影响现有配置。
- profile 快照是配置管理 UI 的内部实现细节；只同步受管目标，不带入其它 mpv 私有内容。恢复后重新打开管理页即可看到新列表，当前打开的旧 Dialog 不强制热刷新。
- 回滚：删除本任务 Java/UI/资源改动即可；旧版本会忽略 `mpvConfig` JSON 字段及 `mpvConfigFiles` multipart part，已有文件保留。

## 验收与验证

1. JVM 测试覆盖 `SyncOptions` JSON round-trip、allowlist、路径穿越/超限拒绝、脚本和自定义按钮文件、profile 快照恢复。
2. `compileMobileArm64_v8aDebugJavaWithJavac` 与 `compileLeanbackArm64_v8aDebugJavaWithJavac` 通过，`git diff --check` 通过。
3. 直连/Manage/RemoteSyncTransfer 的请求均能携带 `mpvConfigFiles`；接收端恢复后 `MpvConfigStore` 三个 target 的列表、selected、custombuttons 与源端一致。

## 实施记录

- 用户已授权实施（2026-09-06）。
- `SyncOptions` 增加默认关闭的 `mpvConfig`，一键同步 UI 增加“MPV 配置管理”复选项并纳入全选/取消全选。
- `Backup` 仅在该选项开启时携带 `mpv_config_*` 偏好，并在恢复前清除接收端旧的 MPV 状态键；`Action`、`Manage`、`RemoteSyncTransfer` 统一发送/恢复 `mpvConfigFiles`。
- `MpvConfigSync` 使用临时目录和固定 allowlist 归档/恢复 `mpv.conf`、`input.conf`、`scripts/`、两个 profile snapshot 目录；限制条目数/单文件/总大小，拒绝绝对路径、`..` 和 canonical-path 逃逸；恢复后重建自定义按钮桥接脚本。
- 状态：implementation complete，待 task guard 原子提交和恢复 tag。

## 验证记录

- `bash .codex/scripts/task_guard.sh check`：通过，scope/分支/保护脏路径安全。
- `git diff --check`：通过。
- `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.bean.BackupPreferenceFilterTest --tests com.fongmi.android.tv.utils.SyncFilesOptionsTest :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac --no-daemon`：通过（BUILD SUCCESSFUL）。
- 设备联调：当前无连接设备；multipart 三条链路和归档恢复逻辑已完成静态/编译验证，待后续连接设备做跨设备实际传输回归。
