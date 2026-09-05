# P7：MPV 脚本按钮列表与播放器覆盖层

## Recovery anchor

- Objective: 让新建脚本按钮出现在 `scripts` 列表，并按 mpvRex 的播放器覆盖层方式显示。
- Acceptance: 列表同时显示普通 Lua/JS 与脚本按钮；按钮编辑/删除可用；MPV 控制层显示按钮，非 MPV 或控制层隐藏时不显示；点击/长按消息保持不变；移动端和电视端编译通过。
- Current stage: implementation complete; device verification pending reconnect。
- Next action: 设备重新连接后安装 `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`，确认脚本创建弹窗同屏显示设置与三种创建方式，且文本编辑在同一 Dialog 内展开。

## 设计依据

- 本地参考 `../mpvRex/app/src/main/kotlin/xyz/mpv/rex/ui/player/controls/PlayerControls.kt` 与 `CustomButtonManager.kt`（2026-09-05）：按钮属于播放器控制覆盖层，按左右区域排列，控制层隐藏时一并隐藏；点击和长按分别发送脚本消息。
- MPV 脚本消息协议继续使用 WebHTV 已有的 `webhtv-custom-button`，不改变 Lua 数据格式或播放器桥接接口。

## WebHTV 适配

- `MpvConfigStore.scriptProfiles()` 将 `custombuttons.json` 中的按钮投影为 `custom_button` 类型的 `ConfigProfile`，普通脚本文件仍保持原有 profile 语义。
- `MpvConfigDialog` 对按钮 profile 打开现有中文按钮编辑器，删除复用 `deleteCustomButton()`；普通脚本的编辑、重命名、导入不变。
- scripts 页沿用原有“新建”弹窗和三种创建方式；“新建脚本按钮”文本入口在当前 Dialog 内展开原始 MPV 文本编辑器样式，启用状态与执行时机属于同一创建流程，不嵌套第二个脚本按钮弹窗。
- 移动端/电视端 `VideoActivity` 在 `mBinding.video` 顶层创建左右按钮组。前四个按钮放左侧，其余放右侧；不再加入 `control.action.container`。
- 按钮仅在 MPV 且播放器控制层可见时显示，点击/长按调用现有 `PlayerManager.sendMpvCustomButton()`。

## 回滚

回滚本任务提交即可恢复仅 action bar 显示的上一版行为；不触碰用户已有 `custombuttons.json` 和普通脚本文件。
