# 观看历史封面时间进度

## Recovery anchor

- 用户需求：在观看历史的封面显示上次观看时间，方便确认看到第几分钟。手机观看历史页与电视首页历史卡片同时覆盖。
- 本轮基线：`feature-menu` / `d4657ae879c32f2f93f30dd832735a02d0999487`。guard `HISTORY-COVER-PROGRESS`，`standard`；保护既有 `app/.cxx/` 35个文件。
- 范围：两端HistoryAdapter/HistoryPresenter与adapter_vod布局；共用HistoryProgressFormatter及测试；History内容比较及测试；默认英文/简中/繁中文案；本文。不改播放器、原生库、数据库schema、历史保存/同步、收藏行为或设置。
- 完成条件：封面显示“已看 12:34”，超过一小时为“已看 1:02:34”；保留集数，未知/无效进度及删除状态隐藏；未知总时长仍显示有效时间，超出已知时长时只在展示层钳制；复用卡片不残留旧时间，单独进度变更会刷新；两端编译和定向测试通过，原子提交及本地恢复tag，不push。
- 当前状态：显示及列表刷新已实现，14项定向测试通过，Mobile arm64与Leanback armv7 Java/资源编译通过；未改原生资产。设备未连接，未打新APK、未安装或完成实机视觉验收。
- 唯一下一动作：紧接本记录由guard finish原子提交并创建本地恢复tag；之后等待用户的显示/实测反馈，不重复已通过的检查。

## 展示设计与证据（2026-09-10）

这是已有观看进度的展示补全，复用原卡片标签，不是播放器/上游依赖整合；不占用P9或上游任务编号。

| 来源 | 证据与决定 |
| --- | --- |
| 当前基线 `History.java`、Mobile `HistoryAdapter`/`HistoryActivity`、Leanback `HistoryPresenter`/`HomeActivity` | A：position/duration为毫秒，未设置值为负数；手机已有进度条，两端均从同一History读取。已有刷新事件及Diffable链可复用；isSameContent尚未比较position/duration/remarks，补齐会影响封面内容的字段即可，不改保存或读取。 |
| 当前基线两端 `adapter_vod.xml`、`KeepAdapter`、`shape_vod_remark.xml` | A：历史和收藏共用卡片，原标签/删除层/图片尺寸是保留契约。只增加默认GONE的时间标签，历史绑定时显式设置文本和可见性；将原集数与时间垂直排列避免互相覆盖。收藏仍无时间标签，不改变图片或卡片高度。 |
| [AOSP DateUtils.java，android-15.0.0_r1](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/text/format/DateUtils.java#L429)，经用户代理读取；快照 `/tmp/history-cover-progress-20260910.CMrH7W/DateUtils.java` | A：formatElapsedTime使用MM:SS/H:MM:SS及累计小时，不将时长当日期/时区处理。采用相同时间形式，在纯Java小格式化器中先过滤未知位置及钳制有效总长，按完整秒展示，不向下一秒四舍五入；英文/简繁中文案走Android资源。 |
| 上游PR/issues/reverts、论文、博客和基准 | 本次无依赖更新、未解决的平台争议、新算法或性能改善主张；不适用新的合并/性能结论。成熟实现与官方契约已覆盖本决策，额外检索不改变数值展示方案。 |

- 不改：只能看手机比例条，无法得知具体分钟数。
- 原样平台方案：直接DateUtils.formatElapsedTime可格式化，但不能决定无效历史、删除态、总时长钳制及列表刷新；仍需本地适配。
- 采用窄适配：每次绑定从现有position/duration生成短时长，原集数标签之下显示一行“已看 …”；无效时设置空文本并GONE，删除态同样GONE。总时长未知不伪装成0，已知时长仅限制显示值，不写回数据、不改变续播位置。
- 风险与对策：共享布局新增标签默认隐藏；保留原备注样式与位置、卡片外部尺寸和电视焦点；单行窄卡片从前方省略标签前缀以优先保留时间。格式化与边界逻辑纯Java，不依赖Android资源初始化；不新增网络、持久化、权限、依赖、ABI或后台任务。
- 验证：小于一分钟/一分钟/一小时/跨24小时、毫秒边界、0/负数/未知、未知总时长、超出总长和大于int范围；进度/总长/集数单独变化影响Diffable而相同副本不变。运行Mobile arm64定向单测与Leanback armv7 Java/资源编译，不做原生或全构建矩阵。当前无设备，实机截图不作为已完成结果。
- 回滚：原子revert本任务提交；上述基线已包含之前MPV修复，本任务不修改其代码/资产。

## 验证记录

- 已实现：默认GONE的封面时间标签，仅由两端历史绑定显示；原集数仍独立显示，删除态隐藏。共用格式化处理无效/未知/超界/长时长，只计算显示值；History内容比较增加position/duration/remarks，保证单独进度变化能刷新。
- 2026-09-10：使用独立JDK21运行 `JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home LC_ALL=C bash ./gradlew --offline :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.utils.HistoryProgressFormatterTest --tests com.fongmi.android.tv.bean.HistoryTest :app:compileLeanbackArmeabi_v7aDebugJavaWithJavac --console=plain`，`BUILD SUCCESSFUL in 2m 3s`，96 tasks、24 executed。
- JUnit XML确认 `HistoryProgressFormatterTest` 7项、`HistoryTest` 7项，均0 failures/0 errors/0 skipped；覆盖时间格式及毫秒/小时/24小时/long边界、无效数据、未知总长、超界钳制，以及相同副本和独立进度/时长/集数变化。
- 首次命令错误指定了不存在的Android Studio JDK目录，Gradle未开始编译；读取仓库已有命令后改用独立JDK21，一次完成实际测试/编译。不是代码回归，也未放宽验证。
- 证据目录 `/tmp/history-cover-progress-20260910.CMrH7W/`；日志 `tests-and-compile-jdk21.log`，原始环境失败保留在 `tests-and-compile.log`。设备列表为空，无截图/安装结果；此次不触发原生/CMake或APK打包，不把编译通过等同实机视觉通过。
- 收口：只提交guard内本任务文件，保留35个预存dirty文件；本地注释恢复tag由guard finish在提交后立即生成，不push。
