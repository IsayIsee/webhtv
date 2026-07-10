package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

public final class PlaybackPerformanceDialog extends DialogFragment {

    private Runnable callback;
    private Dialog helpDialog;
    private LinearLayout list;

    public static void show(Fragment fragment, Runnable callback) {
        PlaybackPerformanceDialog dialog = new PlaybackPerformanceDialog();
        dialog.callback = callback;
        dialog.show(fragment.getChildFragmentManager(), PlaybackPerformanceDialog.class.getSimpleName());
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        PlaybackPerformanceDialog dialog = new PlaybackPerformanceDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), PlaybackPerformanceDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        PlaybackPerformanceSetting.ensureInitialized();
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(createView(LayoutInflater.from(requireContext()))).create();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(requireContext()) * (ResUtil.isLand(requireContext()) ? 0.58f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }

    private View createView(LayoutInflater inflater) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.shape_shell_proxy_dialog);
        root.setPadding(dp(22), dp(22), dp(22), dp(18));

        LinearLayout titleBar = new LinearLayout(requireContext());
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);

        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(R.string.player_performance);
        title.setTextColor(Color.parseColor("#202124"));
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        MaterialButton help = actionButton(R.string.player_performance_help, view -> showHelpDialog());
        help.setTextSize(13);
        help.setContentDescription(getString(R.string.player_performance_help_title));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(dp(72), dp(38));
        helpParams.leftMargin = dp(12);
        titleBar.addView(help, helpParams);
        root.addView(titleBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.addView(actionButton(R.string.player_performance_recommended, view -> apply(true)), actionParams(true));
        actions.addView(actionButton(R.string.player_performance_compatible, view -> apply(false)), actionParams(false));
        actions.addView(actionButton(R.string.dialog_reset, view -> apply(PlaybackPerformanceSetting.isCompatible() ? false : true)), actionParams(false));
        LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        actionLayout.topMargin = dp(14);
        root.addView(actions, actionLayout);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(460), Math.max(dp(300), ResUtil.getScreenHeight(requireContext()) * 2 / 3)));
        scrollParams.topMargin = dp(16);
        root.addView(scroll, scrollParams);
        refreshRows();
        return root;
    }

    private void showHelpDialog() {
        if (helpDialog != null && helpDialog.isShowing()) return;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.shape_shell_proxy_dialog);
        root.setPadding(dp(22), dp(20), dp(22), dp(18));

        LinearLayout titleBar = new LinearLayout(requireContext());
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);

        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(R.string.player_performance_help_title);
        title.setTextColor(Color.parseColor("#202124"));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        MaterialButton close = actionButton(R.string.player_performance_help_close, view -> {
            if (helpDialog != null) helpDialog.dismiss();
        });
        close.setTextSize(13);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(72), dp(38));
        closeParams.leftMargin = dp(12);
        titleBar.addView(close, closeParams);
        root.addView(titleBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(12), dp(4), dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(560), ResUtil.getScreenHeight(requireContext()) * 3 / 5));
        scrollParams.topMargin = dp(10);
        root.addView(scroll, scrollParams);

        addHelpIntro(content, "这些选项主要影响 EXO / Media3 播放，部分缓存选项也会作用于 MPV。通常先使用“推荐”；遇到黑屏、无声、卡顿、发热或老设备兼容问题时，再参考下方逐项调整。多数选项需重新进入播放后生效。\n\n“推荐”偏向画质、流畅度和预载能力；“兼容”减少缓存和实验特性，适合性能较弱或解码器不稳定的设备；“重置”会覆盖自定义值，兼容配置下恢复兼容默认，其余情况恢复推荐默认。");

        addHelpSection(content, "基础性能");
        addHelpItem(content, "性能配置", "显示当前预设。推荐：功能更完整、画质和抗卡顿能力更强，但占用更多内存、网络与存储；兼容：资源占用更低、设备适配更稳，但启动、画质或拖动体验可能下降；自定义：表示已有参数被单独修改。");
        addHelpItem(content, "渲染方式", "SurfaceView 直接交给系统合成，通常更省电、更适合高分辨率和隧道模式，但界面叠加、截图及部分机型切换画面可能受限。TextureView 更灵活、兼容动画和缩放，但会增加 GPU 开销，并会关闭隧道模式。");
        addHelpItem(content, "视频轨道限制", "开启后按屏幕和硬件解码能力限制分辨率、帧率与码率，可避免选到设备带不动的轨道；代价是可能不会播放源中最高画质。关闭后优先最高支持码率，画质上限更高，但更容易卡顿、掉帧或解码失败。");
        addHelpItem(content, "自适应降级", "开启后遇到重缓冲、连续掉帧或带宽不足时，会在本次播放中逐级降低视频规格，稳定性更好；代价是画质可能下降，且不会自动升回。关闭可保持选定画质，但网络或性能不足时更容易持续卡顿。");
        addHelpItem(content, "带宽估算", "开启后根据当前网络速度辅助选轨和自适应降级，不会额外产生测速流量；网络波动时可能因估算偏差提前降画质。关闭可减少自动干预，但带宽判断和降级依据会变少。");
        addHelpItem(content, "隧道模式", "开启后让音视频尽量走硬件直通管线，可降低 CPU 占用、功耗并改善音画同步；要求 SurfaceView 且依赖设备支持，部分机型可能黑屏、无声，LUT 和部分画面处理也不可用。出现异常时建议关闭。");

        addHelpSection(content, "缓冲与缓存");
        addHelpItem(content, "缓冲时间", "数值越高，播放器预留的前向缓冲越多，弱网下更不易停顿；但起播、拖动恢复更慢，也会占用更多内存和流量。数值低响应更快，网络波动时更容易卡顿。");
        addHelpItem(content, "缓冲容量", "限制内存缓冲的目标容量。容量大更适合高码率和不稳定网络，但会增加内存占用，低内存设备可能被系统回收；容量小更省内存，但可能频繁等待数据。“自动”由播放器按默认策略决定。");
        addHelpItem(content, "回退缓冲", "保留已播放的最近一段内容，向后拖动时可更快恢复、减少重新下载；保留时间越长越占内存。关闭最省内存，但回退时通常需要重新读取网络或缓存。");
        addHelpItem(content, "播放缓存", "设置 MPV / HLS 磁盘播放缓存上限。容量大可减少重复下载并改善回看、拖动和弱网播放，但占用更多存储空间；容量小更省空间，缓存命中率和弱网容错会降低。");
        addHelpItem(content, "只加载选中轨道", "开启后只加载当前选中的音视频轨道，可节省带宽、内存和缓存；切换清晰度、音轨或字幕时可能需要重新请求，响应较慢。关闭切轨更灵活，但可能加载更多暂时不用的数据。");

        addHelpSection(content, "预载");
        addHelpItem(content, "预载", "开启后会提前缓存当前播放位置之后的内容，可改善拖动和网络波动时的连续播放；会增加网络流量、磁盘写入、耗电和后台连接。移动网络流量有限或存储紧张时可关闭。");
        addHelpItem(content, "预载线程", "线程越多，预载填充通常越快，但会同时占用更多网络连接、CPU 和服务器资源，也可能挤占正在播放的数据；线程少更稳、更省资源，但预载速度较慢。");
        addHelpItem(content, "预载容量", "限制预载缓存可使用的磁盘空间。容量大可保存更多内容、提高缓存命中率，但更占存储；容量小更省空间，但旧缓存会更快被清理。重新启动应用后可确保新的容量上限完全生效。");
        addHelpItem(content, "预载时间", "控制每次向前预载多长的内容。时间长更能抵抗较长网络波动，也会下载更多数据并占用更多缓存；时间短流量和存储消耗较低，但保护范围更小。");

        addHelpSection(content, "解码与渲染");
        addHelpItem(content, "MediaCodec 异步队列", "开启后使用异步方式向系统硬件解码器送取数据，现代设备上通常吞吐更高、阻塞更少；少数旧机或厂商解码器实现不稳定，可能出现花屏、卡死或无画面，此时关闭更兼容。");
        addHelpItem(content, "Media3 动态调度", "开启 Media3 的动态渲染调度，可根据播放状态更灵活地安排解码与渲染，通常有助于流畅度和能效；这是较新的调度路径，个别设备可能出现时序或兼容问题。异常时关闭可回到传统调度。");
        addHelpItem(content, "解码耗时推进", "让视频渲染器把解码队列耗时计入播放进度判断，有助于高码率或解码较慢时及时推进和同步；部分异常解码器可能因此产生时间判断偏差、跳帧或画面不稳，关闭会采用更保守的行为。");
        addHelpItem(content, "输入丢帧阈值", "开启后当送入解码器的帧已经明显迟到时会更早丢弃，以快速追上音频和播放进度，减少越播越慢；代价是卡顿时可能看到跳帧。关闭会尽量保留帧，但延迟可能持续累积。");
        addHelpItem(content, "Surface 固定尺寸", "开启后按视频和设备能力设置 Surface 的实际缓冲尺寸，可减少超高分辨率合成压力并提升部分设备的稳定性；少数机型在切清晰度、旋转或比例变化时可能黑屏、尺寸异常。关闭时跟随界面尺寸，兼容性通常更高但缩放开销可能增加。");
        addHelpItem(content, "解码器兜底", "开启后首选解码器失败时继续尝试其他系统或软件解码器，播放成功率更高；可能增加起播等待，并在不知情时使用更慢、更耗电的解码器。关闭失败更直接，但不会自动换用性能较弱的方案。");
        addHelpItem(content, "软解降负载", "仅在 EXO 视频软解时生效。开启后会通过跳过部分非参考帧、减少滤波和降低解码分辨率来减轻 CPU 压力，减少发热与卡顿；画面细节和流畅度会下降。关闭可保留完整质量，但弱设备可能无法实时软解。");

        addHelpSection(content, "音频");
        addHelpItem(content, "音频直通", "开启后把 Dolby、DTS 等压缩音频直接交给电视、功放或回音壁解码，可保留多声道并降低 CPU 占用；设备或连接不支持时可能无声，音量控制也可能受限。关闭后转为 PCM，兼容性更好但高级音频格式可能降级。");
        addHelpItem(content, "AAC 优先", "开启后存在 AAC 音轨时优先选择它，AAC 设备兼容性广，可规避部分 AC3、EAC3、DTS 无声问题；代价是可能放弃质量更高或声道更多的音轨。关闭由播放器按语言、能力和默认轨道选择。");
        addHelpItem(content, "音频软解优先", "开启后优先使用 FFmpeg 软件音频解码，可支持更多冷门格式并绕过系统解码器故障；会增加 CPU、功耗和一定延迟。关闭优先系统解码，通常更省电，但某些格式可能无法播放或无声。");
        addHelpItem(content, "视频软解优先", "开启后优先使用 FFmpeg 视频软解，可绕过损坏或不兼容的硬件解码器；CPU 占用、发热和耗电会显著增加，高分辨率可能掉帧，同时隧道等硬件路径受限。仅建议在硬解花屏、黑屏或不支持格式时开启。");

        Dialog dialog = new Dialog(requireContext(), R.style.Theme_WebHTV_LightDialog);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnShowListener(ignored -> resizeHelpDialog(dialog));
        dialog.setOnDismissListener(ignored -> {
            if (helpDialog == dialog) helpDialog = null;
        });
        helpDialog = dialog;
        dialog.show();
    }

    private void resizeHelpDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(requireContext()) * (ResUtil.isLand(requireContext()) ? 0.66f : 0.94f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.dimAmount = 0.6f;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }

    @Override
    public void onDestroyView() {
        if (helpDialog != null) helpDialog.dismiss();
        helpDialog = null;
        super.onDestroyView();
    }

    private void addHelpIntro(LinearLayout content, String text) {
        MaterialTextView intro = new MaterialTextView(requireContext());
        intro.setText(text);
        intro.setTextColor(Color.parseColor("#3C4043"));
        intro.setTextSize(13);
        intro.setLineSpacing(dp(3), 1f);
        content.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addHelpSection(LinearLayout content, String text) {
        MaterialTextView section = new MaterialTextView(requireContext());
        section.setText(text);
        section.setTextColor(Color.parseColor("#174EA6"));
        section.setTextSize(15);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(18);
        params.bottomMargin = dp(6);
        content.addView(section, params);
    }

    private void addHelpItem(LinearLayout content, String title, String description) {
        MaterialTextView name = new MaterialTextView(requireContext());
        name.setText(title);
        name.setTextColor(Color.parseColor("#202124"));
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialTextView detail = new MaterialTextView(requireContext());
        detail.setText(description);
        detail.setTextColor(Color.parseColor("#5F6368"));
        detail.setTextSize(13);
        detail.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        params.bottomMargin = dp(10);
        content.addView(detail, params);
    }

    private MaterialButton actionButton(int text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setAllCaps(false);
        button.setText(text);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(38));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setCornerRadius(dp(6));
        button.setTextColor(ColorStateList.valueOf(Color.parseColor("#174EA6")));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
        button.setStrokeColor(ColorStateList.valueOf(Color.parseColor("#8AB4F8")));
        button.setStrokeWidth(dp(1));
        button.setOnFocusChangeListener((view, hasFocus) -> styleAction(button, hasFocus));
        button.setOnClickListener(listener);
        return button;
    }

    private void styleAction(MaterialButton button, boolean focused) {
        button.setTextColor(ColorStateList.valueOf(Color.parseColor(focused ? "#FFFFFF" : "#174EA6")));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(focused ? "#1A73E8" : "#FFFFFF")));
        button.setStrokeColor(ColorStateList.valueOf(Color.parseColor(focused ? "#1A73E8" : "#8AB4F8")));
        button.setStrokeWidth(dp(1));
    }

    private LinearLayout.LayoutParams actionParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = first ? 0 : dp(8);
        return params;
    }

    private void apply(boolean recommended) {
        if (recommended) PlaybackPerformanceSetting.applyRecommended();
        else PlaybackPerformanceSetting.applyCompatible();
        refresh();
    }

    private void refresh() {
        refreshRows();
        if (callback != null) callback.run();
    }

    private void refreshRows() {
        if (list == null) return;
        list.removeAllViews();
        addHeader("基础性能");
        addRow("性能配置", PlaybackPerformanceSetting.getProfileName(), null);
        addRow("渲染方式", renderText(), this::toggleRender);
        addRow("视频轨道限制", onOff(PlaybackPerformanceSetting.isTrackLimitEnabled()), () -> {
            PlaybackPerformanceSetting.putTrackLimitEnabled(!PlaybackPerformanceSetting.isTrackLimitEnabled());
            refresh();
        });
        addRow("自适应降级", onOff(PlaybackPerformanceSetting.isAdaptiveDowngradeEnabled()), () -> {
            PlaybackPerformanceSetting.putAdaptiveDowngradeEnabled(!PlaybackPerformanceSetting.isAdaptiveDowngradeEnabled());
            refresh();
        });
        addRow("带宽估算", onOff(PlaybackPerformanceSetting.isBandwidthMeterEnabled()), () -> {
            PlaybackPerformanceSetting.putBandwidthMeterEnabled(!PlaybackPerformanceSetting.isBandwidthMeterEnabled());
            refresh();
        });
        addRow("隧道模式", onOff(PlayerSetting.isTunnel()), () -> {
            PlayerSetting.putTunnel(!PlayerSetting.isTunnel());
            PlaybackPerformanceSetting.markCustom();
            refresh();
        });

        addHeader("缓冲与缓存");
        addRow("缓冲时间", PlayerSetting.getBuffer() + "/10", this::cycleBuffer);
        addRow("缓冲容量", bufferBytesText(), this::cycleBufferBytes);
        addRow("回退缓冲", backBufferText(), this::cycleBackBuffer);
        addRow("播放缓存", playCacheText(), this::cyclePlayCache);
        addRow("只加载选中轨道", onOff(PlaybackPerformanceSetting.isLoadOnlySelectedTracksEnabled()), () -> {
            PlaybackPerformanceSetting.putLoadOnlySelectedTracksEnabled(!PlaybackPerformanceSetting.isLoadOnlySelectedTracksEnabled());
            refresh();
        });

        addHeader("预载");
        addRow("预载", onOff(PreloadSetting.isPreload()), () -> {
            PreloadSetting.putPreload(!PreloadSetting.isPreload());
            PlaybackPerformanceSetting.markCustom();
            refresh();
        });
        addRow("预载线程", PreloadSetting.getPreloadThreads() + " 条", this::cyclePreloadThreads);
        addRow("预载容量", PreloadSetting.getPreloadSizeMb() + "MB", this::cyclePreloadSize);
        addRow("预载时间", PreloadSetting.getPreloadTimeSeconds() + " 秒", this::cyclePreloadTime);

        addHeader("解码与渲染");
        addRow("MediaCodec 异步队列", onOff(PlaybackPerformanceSetting.isCodecAsyncQueueingEnabled()), () -> {
            PlaybackPerformanceSetting.putCodecAsyncQueueingEnabled(!PlaybackPerformanceSetting.isCodecAsyncQueueingEnabled());
            refresh();
        });
        addRow("Media3 动态调度", onOff(PlaybackPerformanceSetting.isDynamicSchedulingEnabled()), () -> {
            PlaybackPerformanceSetting.putDynamicSchedulingEnabled(!PlaybackPerformanceSetting.isDynamicSchedulingEnabled());
            refresh();
        });
        addRow("解码耗时推进", onOff(PlaybackPerformanceSetting.isVideoDurationProgressEnabled()), () -> {
            PlaybackPerformanceSetting.putVideoDurationProgressEnabled(!PlaybackPerformanceSetting.isVideoDurationProgressEnabled());
            refresh();
        });
        addRow("输入丢帧阈值", onOff(PlaybackPerformanceSetting.isLateDropInputEnabled()), () -> {
            PlaybackPerformanceSetting.putLateDropInputEnabled(!PlaybackPerformanceSetting.isLateDropInputEnabled());
            refresh();
        });
        addRow("Surface 固定尺寸", onOff(PlaybackPerformanceSetting.isSurfaceFixedSizeEnabled()), () -> {
            PlaybackPerformanceSetting.putSurfaceFixedSizeEnabled(!PlaybackPerformanceSetting.isSurfaceFixedSizeEnabled());
            refresh();
        });
        addRow("解码器兜底", onOff(PlaybackPerformanceSetting.isDecoderFallbackEnabled()), () -> {
            PlaybackPerformanceSetting.putDecoderFallbackEnabled(!PlaybackPerformanceSetting.isDecoderFallbackEnabled());
            refresh();
        });
        addRow("软解降负载", onOff(PlaybackPerformanceSetting.isSoftVideoTuneEnabled()), () -> {
            PlaybackPerformanceSetting.putSoftVideoTuneEnabled(!PlaybackPerformanceSetting.isSoftVideoTuneEnabled());
            refresh();
        });

        addHeader("音频");
        addRow("音频直通", onOff(PlayerSetting.isAudioPassThrough()), () -> {
            PlayerSetting.putAudioPassThrough(!PlayerSetting.isAudioPassThrough());
            PlaybackPerformanceSetting.markCustom();
            refresh();
        });
        addRow("AAC 优先", onOff(PlayerSetting.isPreferAAC()), () -> {
            PlayerSetting.putPreferAAC(!PlayerSetting.isPreferAAC());
            PlaybackPerformanceSetting.markCustom();
            refresh();
        });
        addRow("音频软解优先", onOff(PlayerSetting.isAudioPrefer()), () -> {
            PlayerSetting.putAudioPrefer(!PlayerSetting.isAudioPrefer());
            PlaybackPerformanceSetting.markCustom();
            refresh();
        });
        addRow("视频软解优先", onOff(PlayerSetting.isVideoPrefer()), () -> {
            PlayerSetting.putVideoPrefer(!PlayerSetting.isVideoPrefer());
            PlaybackPerformanceSetting.markCustom();
            refresh();
        });
    }

    private void addHeader(String text) {
        MaterialTextView header = new MaterialTextView(requireContext());
        header.setText(text);
        header.setTextColor(Color.parseColor("#5F6368"));
        header.setTextSize(13);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        params.topMargin = list.getChildCount() == 0 ? 0 : dp(8);
        list.addView(header, params);
    }

    private void addRow(String label, String value, Runnable action) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setSingleLine(false);
        button.setMinHeight(dp(46));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setText(label + "    " + value);
        button.setTextSize(14);
        button.setTextColor(ColorStateList.valueOf(Color.parseColor("#202124")));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
        button.setCornerRadius(dp(6));
        button.setStrokeColor(ColorStateList.valueOf(Color.parseColor("#C4C7C5")));
        button.setStrokeWidth(dp(1));
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setEnabled(action != null);
        button.setOnFocusChangeListener((view, hasFocus) -> styleRow(button, action != null, hasFocus));
        if (action != null) button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.bottomMargin = dp(7);
        list.addView(button, params);
    }

    private void styleRow(MaterialButton button, boolean enabled, boolean focused) {
        int text = focused ? Color.WHITE : enabled ? Color.parseColor("#202124") : Color.parseColor("#5F6368");
        int bg = focused ? Color.parseColor("#1A73E8") : Color.WHITE;
        int stroke = focused ? Color.parseColor("#1A73E8") : Color.parseColor("#C4C7C5");
        button.setTextColor(ColorStateList.valueOf(text));
        button.setBackgroundTintList(ColorStateList.valueOf(bg));
        button.setStrokeColor(ColorStateList.valueOf(stroke));
        button.setStrokeWidth(dp(focused ? 2 : 1));
    }

    private void toggleRender() {
        PlayerSetting.putRender(PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE ? PlayerSetting.RENDER_TEXTURE : PlayerSetting.RENDER_SURFACE);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private void cycleBuffer() {
        PlayerSetting.putBuffer(PlayerSetting.getBuffer() >= 10 ? 1 : PlayerSetting.getBuffer() + 1);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private void cycleBufferBytes() {
        PlayerSetting.putBufferBytesOption((PlayerSetting.getBufferBytesOption() + 1) % 4);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private void cycleBackBuffer() {
        PlayerSetting.putBackBufferOption((PlayerSetting.getBackBufferOption() + 1) % 4);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private void cyclePlayCache() {
        PlayerSetting.putPlayCacheOption((PlayerSetting.getPlayCacheOption() + 1) % 5);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private void cyclePreloadThreads() {
        int value = PreloadSetting.getPreloadThreads() + 1;
        if (value > PreloadSetting.MAX_THREADS) value = PreloadSetting.MIN_THREADS;
        PreloadSetting.putPreloadThreads(value);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private void cyclePreloadSize() {
        int value = PreloadSetting.getPreloadSizeMb() + PreloadSetting.STEP_SIZE_MB;
        if (value > PreloadSetting.MAX_SIZE_MB) value = PreloadSetting.MIN_SIZE_MB;
        PreloadSetting.putPreloadSizeMb(value);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private void cyclePreloadTime() {
        int value = PreloadSetting.getPreloadTimeSeconds() + PreloadSetting.STEP_TIME_SECONDS;
        if (value > PreloadSetting.MAX_TIME_SECONDS) value = PreloadSetting.MIN_TIME_SECONDS;
        PreloadSetting.putPreloadTimeSeconds(value);
        PlaybackPerformanceSetting.markCustom();
        refresh();
    }

    private String renderText() {
        return PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE ? "SurfaceView" : "TextureView";
    }

    private String bufferBytesText() {
        return switch (PlayerSetting.getBufferBytesOption()) {
            case 1 -> "64MB";
            case 2 -> "128MB";
            case 3 -> "256MB";
            default -> "自动";
        };
    }

    private String backBufferText() {
        return switch (PlayerSetting.getBackBufferOption()) {
            case 1 -> "15秒";
            case 2 -> "30秒";
            case 3 -> "60秒";
            default -> "关闭";
        };
    }

    private String playCacheText() {
        return switch (PlayerSetting.getPlayCacheOption()) {
            case 1 -> "256MB";
            case 2 -> "512MB";
            case 3 -> "1GB";
            case 4 -> "2GB";
            default -> "128MB";
        };
    }

    private String onOff(boolean value) {
        return value ? "开" : "关";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
