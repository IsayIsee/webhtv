# P9 MPV HDMV Blu-ray 菜单

## Recovery anchor

- 目标：在 WebHTV 的 MPV ISO 播放路径实现 HDMV Blu-ray 菜单画面、按钮高亮、方向键、确认、返回、Popup、章节/设置/特别收录跳转、discontinuity 和 still frame。
- 接受标准：HDMV 菜单从远程/本地 Range ISO 入口可达并可操作；菜单跳转后音视频轨和时间线重建；普通 Blu-ray 最长标题、DVD、非 ISO、双 Surface OSD、硬解/软解和现有 Range 行为不回退。
- BD-J 产品边界：不启动 BD-J runtime，不处理 BD-J ARGB 菜单，不新增提示；遇到 BD-J 菜单时继续按现状选择最长标题播放。
- 用户决定：2026-09-06 明确“实现；遇到 BD-J 菜单时不用提示，就不处理菜单，跟现在一样即可”。
- Lane / task guard：`upstream` / `P9-MPV-BLURAY-MENU`。
- 分支/HEAD：`feature-menu` / `966b3b6747ece447c2f34f7787df7c6af572baa0`。
- 任务开始工作区：干净；外部缓存 `/Users/macbookpro/Desktop/github/webhtv/build/mpv-native/mpv-android` 有预存修改，只读使用，不纳入提交。
- MPV 固定基线：`cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`；构建框架 `99a60ad2141d5ace94453590903c2c6b9a0a2443`；libbluray 1.4.1 tarball SHA-256 `76b5dc40097f28dca4ebb009c98ed51321b2927453f75cc72cf74acd09b9f449`。
- 当前状态：HDMV 适配补丁、MPV App 接线和 native 构建/资产检查接线已落盘；生产 native 编译与真实设备验收仍未完成。
- 回滚锚点：`966b3b6747ece447c2f34f7787df7c6af572baa0`，并成套恢复 MPV patch、JNI header/source、App 接线和双 ABI assets。
- 唯一下一动作：在具备 `libplacebo` 与 Android NDK 的环境中执行一次双 ABI native 构建/资产校验；若环境仍缺失则保留当前源码提交并记录阻塞。

## 1. 授权、范围与排除项

这是已批准的实施阶段，不再等待设计授权。

范围：

- MPV disc navigation state/action、动态 duration/chapter/edition、菜单 OSD、slave demuxer 重开、interactive cache、still/discontinuity。
- WebHTV ISO callback 继续复用 Java Range 数据源；由 MPV/libbluray 菜单栈读取原始 ISO。
- App 仅在实际 HDMV 菜单激活时截获方向、确认、返回和菜单键。
- 两个 ARM ABI 的 coherent native/JNI 构建、ELF/资产校验和最小 App 编译。

明确排除：

- BD-J JVM、JAR、AWT/ARGB runtime、字体/XML 依赖和任何 BD-J 提示。
- AACS/BD+ 解密能力扩展。
- 整体升级 MPV、FFmpeg、libplacebo、mpv-android 或其它播放器。
- 无关渲染、音频、字幕、网络、配置和 UI 重构。

## 2. 决策问题与结论

问题：怎样在不整体升级 FongMi MPV、不中断 WebHTV Java Range ISO、且绝不启动 BD-J 的前提下，实现完整 HDMV 菜单？

假设：把上游完整 discnav 状态机移植为独立 MPV 补丁，并让 `webhtv-dvdiso` 回调提供原始可 seek ISO 字节，由 MPV 的 `stream_iso -> stream_bluray -> libbluray` 路径拥有菜单 VM，是最小完整方案。

反假设：继续让 `iso_dvd.cpp` 自己解复用最长 playlist，再只补 `bd_user_input()` 和一张菜单 bitmap 即可。该方案无法可靠处理 playlist/title hop、slave demuxer 重开、runtime track/list 更新、still、discontinuity 和播放器缓存，已被上游提交依赖关系否定。

结论：采用 WebHTV 适配方案；不整体升级，不做 Java 层自建 Blu-ray VM。

## 3. 当前 WebHTV 调用链

- `IsoSessionManager.create()` 产生 `webhtv-dvdiso://<id>/longest`。
- `MpvPlayer` 用 `loadfile` 打开该 URL。
- `iso_dvd.cpp` 当前在 callback 内创建 libbluray、选择最长 playlist，并把已导航的 M2TS 字节交给 `+disc` demuxer。
- `mpv-stream-cb-disc-controls.patch` 只桥接 duration/current time/seek/chapter/language，没有 NAV command/state/overlay。
- `MpvPlayer` 已有透明 OSD Surface 生命周期，但没有 `disc-menu-active` 观察或 `discnav` 命令接口。
- Leanback `VideoActivity.dispatchKeyEvent()` 会在普通播放状态消费方向键，因此必须在它之前给活动菜单优先权。

## 4. 方案比较

| 方案 | 正确性 | 兼容/风险 | 决定 |
| --- | --- | --- | --- |
| 保持现状 | 普通最长标题稳定；没有菜单 | 不满足需求 | 拒绝 |
| 整体升级到 `FongMi/mpv@13eafa069366edb54606637b323b0d10efd05fa3` | 包含最终菜单链 | 同时引入 130 个分叉提交和渲染/音频/格式变化，覆盖本地补丁风险高 | 拒绝 |
| 只拣 `3a8b6995e925f2b1a6836c8e48d1fd210f4ed7ea` | 能生成部分 HDMV overlay | 缺少 demux reopen、OSD state、输入、cache、still 和后续跳转修复 | 拒绝 |
| 独立移植完整 discnav 链并适配 WebHTV raw Range callback | 上游状态机完整，保留当前二进制基线和 Range 所有权 | 补丁较大，必须做双 ABI、生命周期和真实 ISO 验证 | 采用 |

## 5. 上游提交台账

访问日期：2026-09-06；网络使用用户指定代理。仓库：`https://github.com/FongMi/mpv.git`。以下是本阶段实际判断的菜单链；DVD-only 代码只有在提供共用 discnav/still/cache 契约时才作为依赖吸收，产品验收仍以 HDMV 为准。

| 完整 commit ID | 作用 | P9 disposition |
| --- | --- | --- |
| `5db1db2852db3b43bfcc0fe1b611b4c30caa45cb` | runtime duration/chapter/edition 更新 | 实施依赖 |
| `e2b7a983fa22795b903fbcb266c275ad612c372b` | disc-nav action/state contract | 实施 |
| `fbd0e047b1c025cdfa8d517dcd98e3d7f029f33d` | `disc-menu` option | 实施，App 仅对 ISO 启用 |
| `b17feb1f7910fffb4e4dcb9dec603c30c8b7414b` | DVD menu implementation | HDMV 不直接采用；仅提取共用 lavf/disc contract（若最终补丁需要） |
| `3a8b6995e925f2b1a6836c8e48d1fd210f4ed7ea` | HDMV overlay/navigation | 实施核心 |
| `af9a11a8e5b0d06cd48a591ad22563b5ca3ed0f6` | disc hop 后重开 slave demuxer | 实施核心 |
| `e204c1cfd96835d0e24564e0d55c9b0a2206bb46` | synthetic Disc Menu edition | 实施 |
| `8d36904e053ba78c7242dae70ee11e5727250c87` | `OSDTYPE_DISC_MENU` | 实施 |
| `652ca81af9dad106d60e9cc5855cfd4ed7813c5b` | player disc-menu state/overlay | 实施 |
| `816eca1fef78077f406b5d30d12a88eb4a35b642` | runtime 新轨自动选择 | 实施依赖 |
| `5713c301603fec36352a49505e113bec1e568985` | player 响应动态 chapter/edition | 实施依赖 |
| `a5682eb2adf359b982c7b8227d34c9fe5cd28bd5` | `disc-menu-active` property | 实施 |
| `216e26c87130683724702ad4a75a6b7a48529b2d` | `discnav` command/default keys | 实施命令；App 自行映射 Android 键 |
| `4725c2e492e4fb405a32f6bec5fbcf79b2be3dd5` | partially seekable | 实施 |
| `70174945a4b7302613030d5877e572183dccdfad` | VM 与音轨/字幕选择同步 | 实施 |
| `c625405ddcdf9d40cdda2ffe3708865c105ed965` | BD-J ARGB overlay/menu | 明确排除 |
| `f0bc30aaf12c5e04e2a1f8caf19f8924b8d4d3cf` | interactive disc cache/read-ahead | 实施 |
| `5f192099531e9eaec11210d26766a434d9ded552` | Blu-ray angle 统一为 1-based | 实施兼容修正 |
| `fbcced5bf068afcb4bc0a2bf1dea80ba9eac2169` | cached disc state controls 加锁 | 实施线程安全修正 |
| `3897b3a579014b5bb293180f6e2a24fe3e6fb9a5` | player/discnav 共用 still contract | 提取共用部分 |
| `4084e7609fa06a9e40b36d689ba5cd80a6ca9c17` | Blu-ray still frame | 实施 |
| `af53a9dc4b378568f0760afdf7c5e7ec9de7ed30` | 以 `bd_read_ext()` 驱动 VM/event | 实施 |
| `dbe496e6bec0332fb169818fe0826e982f9a30e2` | drop buffers 时清 sticky AVIO EOF | 实施跳转修正 |
| `2c8d954d2111b045842110151e414edc49fecb71` | 仅有菜单支持时添加 menu edition | 实施 |
| `c7859fe5b62c35c1e9cdeda70fdec72e8c6cd1a6` | OSD image overlay 命名/所有权整理 | 采用最终语义 |
| `c9422ff88fafef2d4b7c28f6c614099ef5dbfde5` | highlight contract 整理 | 采用最终语义 |
| `bb19b0fc3696c31438adc8aba8ef1e3b6997e2fb` | 移除不可靠 popup/menu event 判定 | 实施最终语义 |
| `4a110f39cfaf062d88d84887294dbb4eca71fe28` | 每流 timeline generation | 实施跳转正确性 |
| `abbbffe0dd24f3e3221e41ebaa55bac2ef7965bb` | 保持 BD jump boundary 供 player resync | 实施 |
| `15750e12123b9cb39f44f5cb4432f4c264097deb` | still 前排空 queued events | 实施 |
| `0c87e46c83f31a8b20db0f1301134a6e28af0d3d` | buffered data 存在时延后 slave reopen | 实施 |
| `a47452aac02fca2226edf4cd306b09d4aacc58bf` | 未预告 reposition 视为 jump | 实施 |
| `6f6bedec72287d070e71a0b7d5567e2e889ea382` | BD time seek 原位 reset slave | 实施 |
| `79186bd167b295227988e14d42401dc49a024a47` | idle 时轮询 disc VM | 实施 |
| `795cd15438639e7aa1dc1b2519d5584a098f10b6` | demux 标记当前节目缺失轨 | 实施依赖 |
| `93451ef64f6267e176f5da05724282462e1ba21d` | disc program track 可达性 | 实施 |
| `c318236b8882af860f16f936225430ad053a2179` | 无 EL 时移除 enhancement pairing | 实施邻接回归修正 |

## 6. WebHTV 适配设计

1. `webhtv-dvdiso` callback 增加 raw ISO 模式，只负责 Java Range read/seek/size，不在 JNI 内抢先选择最长 playlist。
2. MPV `stream_iso` 识别该 callback scheme，并让 `stream_bluray` 明确通过 `stream_info_cb` 打开嵌套原始流，避免递归和 FFmpeg URL handler 绕行。
3. 只有当 libbluray 报告 `top_menu_supported` 且 top menu 是 HDMV 时进入菜单；使用 `bd_play_title(..., BLURAY_TITLE_TOP_MENU)`，不注册 BD-J ARGB callback，不调用 BD-J runtime。
4. 其它情况（包括 BD-J top menu、无 HDMV top menu、menu boot 失败）无提示回退 `bd_get_main_title()`/最长标题语义。
5. App 对 ISO load 传入 `disc-menu=yes`；MPV 暴露 `disc-menu-active`，只有 true 时 Android Activity 截获 DPAD/ENTER/BACK/MENU 并发送 `discnav`。
6. 菜单 bitmap 继续走 MPV OSD renderer，复用现有 Android OSD Surface；不在 Java 额外复制整帧 bitmap。

## 7. 证据

| 证据 | 等级 | 支持的结论 | WebHTV 影响 |
| --- | --- | --- | --- |
| FongMi MPV 上述完整 source commits 和最终 tree | A | 菜单是 stream/demux/player/OSD/input 联动链 | 不能只移植单 commit |
| libbluray 1.4.1 `bluray.h`、`overlay.h`、`keys.h` | A | HDMV 使用 RLE overlay、`bd_user_input()`、`bd_read_ext()`；BD-J 状态可区分 | 可明确禁用 BD-J 并选 HDMV top menu |
| mpv PR #18080 及合并链 | B | 上游维护者采用相同分层和 demux reopen/still/cache 设计 | 支持 adapted upstream 方案 |
| VLC/Kodi libbluray 集成源码 | B | 成熟播放器同样让 libbluray VM 拥有导航并把 overlay 接入播放器 | 反对 Java 层伪造菜单状态机 |
| 论文 | 不适用 | 本需求是既有 Blu-ray VM/API 工程集成，不含新的算法、调度或性能主张 | 不以论文替代源码/实测 |
| 博文/论坛 | C/D，仅作现场线索 | 不同盘的 HDMV/BD-J 外观无法仅凭截图判断 | 运行时必须读取 disc info |

## 8. 风险、验收与回滚

风险：

- 菜单补丁跨 stream/demux/player/OSD，编译通过不能证明真实盘跳转正确。
- 远程 ISO 菜单随机读取会放大 Range 延迟，interactive cache 必须与现有页面缓存共同验证。
- Surface direct 的菜单依赖 OSD Surface；快速 Surface 重建和字幕关闭场景必须保留菜单 OSD。
- 某些盘混合 HDMV first-play 与 BD-J top menu；P9 以 top menu 类型为准，BD-J top menu 直接回退最长标题。

最小自动门槛：

- 独立补丁按构建脚本顺序干净应用。
- MPV native 双 ABI 编译；`libplayer.so` 双 ABI 重建。
- `verify_mpv_native_assets.sh --require-elf` 通过，无 `libav*`/`libsw*` namespace 回退。
- Mobile arm64 与 Leanback arm64/armv7 Java/资源编译通过。

设备验收：

- 至少一份确认的 HDMV ISO：菜单背景/视频、按钮高亮、四方向、确认、返回、Popup、章节/设置/特别收录跳转。
- 菜单与正片间反复切换，含 still、seek、音轨/字幕选择、Surface 前后台和退出重开。
- 一份 BD-J 菜单盘：无新增提示，仍播放最长标题，不进入菜单。
- 普通 Blu-ray、DVD 和非 ISO MPV 回归。

回滚：revert P9 原子提交并恢复同一提交前的 patch、JNI、App 接线和双 ABI assets；不单独保留新 `libmpv.so` 或 `libplayer.so`。

## Checkpoint 1：2026-09-06 方案冻结

- 完成：任务 ID、用户授权、BD-J 无提示边界、基线、替代方案、提交台账、适配架构和验收矩阵已落盘。
- Source identities：WebHTV `966b3b6747ece447c2f34f7787df7c6af572baa0`；MPV baseline `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`；FongMi review head `13eafa069366edb54606637b323b0d10efd05fa3`。
- Files changed：仅本任务文档和恢复索引。
- Validation：待补丁生成后执行；当前没有把研究结论误记为构建/设备结果。
- Unresolved：尚无任务内真实 HDMV/BD-J 样片设备结果。
- Rollback anchor：`966b3b6747ece447c2f34f7787df7c6af572baa0`。
- Next action：在隔离 MPV 源码副本中生成 adapted HDMV patch，并验证它可与现有 WebHTV patch 序列共同应用。

## Checkpoint 2：2026-09-07 适配补丁与 App 接线完成

- 完成：将隔离仓库生成的 `/tmp/p9-final-source.patch`（相对 MPV `p9-local`）替换为生产 `third_party/patches/mpv-discnav.patch`；补丁覆盖 demux/cache、discnav 命令、HDMV YUV/RLE overlay、OSD、slave demux reopen、still/discontinuity、动态 chapter/edition 和 Blu-ray stream callback。
- 完成：`MpvPlayer` 设置 `disc-menu=yes`、观察 `disc-menu-active`、为 ISO 请求 OSD Surface，并暴露 `isDiscMenuActive()`/`sendDiscNav()`；`PlaybackActivity` 仅在 HDMV 菜单激活时转发 DPAD/ENTER/BACK/MENU。
- 完成：`scripts/build_mpv_native.sh` 与 `scripts/verify_mpv_native_assets.sh` 增加补丁应用和 `disc-menu-active`/`discnav` marker 校验。
- BD-J 边界：`stream_bluray.c` 根据 `BLURAY_DISC_INFO.bdj_detected` 静默回到 `BLURAY_DEFAULT_TITLE`；未注册 `bd_register_argb_overlay_proc`，未启动 JVM/JAR/BD-J runtime。PG 字幕不触发菜单激活。
- 取舍：未吸收 BD-J 提交 `c625405ddcdf9d40cdda2ffe3708865c105ed965`、DVD-Audio 代码、会删除 WebHTV 本地 DOVI/Vulkan/OSD/proxy/live/albumart 行为的整棵父树，以及 `4a110f39cfaf062d88d84887294dbb4eca71fe28`/`70174945a4b7302613030d5877e572183dccdfad` 的不兼容完整父树；仅保留其 HDMV 所需契约并作 WebHTV 适配。
- 验证：完整现有 WebHTV MPV patch chain 加 P9 补丁在固定基线应用成功；临时树 `git diff --check` 通过；`bash ./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac --offline` 通过；生产脚本 `bash -n` 和 Task Guard `check` 通过。
- 未完成：MPV Meson 探针因环境缺少 `libplacebo` 失败；当前环境无可用 Android NDK/clang，因此双 ABI native、ELF/asset、`libplayer.so` 重建和真实 HDMV/BD-J 设备验收未执行，不能视为通过。
- Files changed：`third_party/patches/mpv-discnav.patch`、`app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`、`app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java`、`scripts/build_mpv_native.sh`、`scripts/verify_mpv_native_assets.sh`、本任务文档。
- Rollback anchor：`966b3b6747ece447c2f34f7787df7c6af572baa0`。
- 唯一下一动作：具备依赖时执行一次双 ABI native 构建和资产校验；否则以当前未完成 native 验证的状态进入用户可见交接，不虚报设备支持。
