package com.fongmi.android.tv;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class Updater implements Download.Callback, UpdateListener {

    public static final String TAG = "Updater";

    private static final String DEFAULT_RELEASE_NOTES = "手动触发 GitHub Actions 构建发布。";
    private static final Updater INSTANCE = new Updater();

    private final LifecycleEventObserver lifecycleObserver = (source, event) -> {
        if (!(source instanceof FragmentActivity activity)) return;
        if (event == Lifecycle.Event.ON_DESTROY) unbind(activity);
    };

    private WeakReference<FragmentActivity> activityRef;
    private UpdateDialog dialog;
    private Download download;
    private Update stable;
    private Update selected;
    private boolean isForceUpdate;
    private boolean downloading;
    private boolean canceled;
    private int lastProgress = -1;
    private long lastBytes;
    private long lastTotal;
    private long lastSpeed;
    private long lastElapsed;

    private Updater() {
    }

    public static Updater create() {
        return INSTANCE;
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getJson() {
        return Github.getJson(BuildConfig.FLAVOR_mode);
    }

    private String getApk() {
        return Github.getApk(BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi);
    }

    public Updater force() {
        isForceUpdate = true;
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        bind(activity);
        if (downloading) {
            restoreDialog(activity);
            return;
        }
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity));
    }

    public void resume(FragmentActivity activity) {
        bind(activity);
        restoreDialog(activity);
    }

    private void doInBackground(FragmentActivity activity) {
        stable = fetchUpdate();
        if (!stable.hasUpdate()) {
            if (isForceUpdate) {
                App.post(() -> {
                    if (stable.error != null) {
                        Notify.show(ResUtil.getString(R.string.update_error, getJson()));
                    } else {
                        Notify.show(R.string.update_islatest);
                    }
                });
            }
            return;
        }
        selected = stable;
        App.post(() -> show(activity));
    }

    private Update fetchUpdate() {
        Update update = Update.empty(Update.CHANNEL_STABLE);
        String url = getJson();
        try {
            String text = OkHttp.string(url);
            if (TextUtils.isEmpty(text)) throw new IllegalStateException("Empty manifest: " + url);
            JSONObject object = new JSONObject(text);
            update.name    = object.optString("name");
            update.desc    = object.optString("desc");
            update.channel = Update.CHANNEL_STABLE;
            update.code    = object.optInt("code");
            update.size    = object.optLong("size");
            update.sha256  = object.optString("sha256");

            // notes 优先用 desc，desc 为空再尝试 json 里的 notes 字段
            String notes = object.optString("desc");
            if (TextUtils.isEmpty(notes)) notes = object.optString("notes");
            if (isDefaultReleaseNotes(notes)) notes = "";
            update.notes = notes;

            // 按 ABI 取专属下载地址，兜底用通用 apk 地址
            String apkUrl = null;
            JSONObject urls = object.optJSONObject("urls");
            if (urls != null) apkUrl = urls.optString(BuildConfig.FLAVOR_abi);
            update.apkUrl = (apkUrl != null && !apkUrl.isEmpty()) ? apkUrl : getApk();

            if (update.code <= BuildConfig.VERSION_CODE) update.code = 0;
        } catch (Exception e) {
            Log.e(TAG, "fetchUpdate error: " + url, e);
            update.error = e.getMessage();
        }
        return update;
    }

    private boolean isDefaultReleaseNotes(String notes) {
        return !TextUtils.isEmpty(notes) && DEFAULT_RELEASE_NOTES.equals(notes.trim());
    }

    private void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        bind(activity);
        dismiss();
        dialog = UpdateDialog.create()
                .stable(stable)
                .beta(null)
                .selected(Update.CHANNEL_STABLE)
                .listener(this)
                .show(activity);
    }

    @Override
    public void onConfirm(View view) {
        if (selected == null || !selected.hasUpdate()) {
            Notify.show(R.string.update_latest);
            return;
        }
        view.setEnabled(false);
        downloading = true;
        canceled = false;
        resetProgress();
        Path.clear(getFile());
        setDialogProgress(0, 0, selected.size, 0, 0);
        download = Download.create(selected.apkUrl, getFile()).tag(selected.apkUrl);
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        if (downloading) {
            canceled = true;
            downloading = false;
            if (download != null) download.cancel();
            download = null;
            resetProgress();
            Notify.show(R.string.update_canceled);
            dismiss();
            return;
        }
        Setting.putUpdate(false);
        if (download != null) download.cancel();
        dismiss();
    }

    @Override
    public void onClose() {
        Setting.putUpdate(false);
        dialog = null;
    }

    @Override
    public void onChannel(String channel) {
        // 单通道，无需切换
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismissAllowingStateLoss();
        } catch (Exception ignored) {
        } finally {
            dialog = null;
        }
    }

    @Override
    public void progress(int progress) {
        setDialogProgress(progress, 0, 0, 0, 0);
    }

    @Override
    public void progress(int progress, long bytes, long total, long speed, long elapsed) {
        setDialogProgress(progress, bytes, total, speed, elapsed);
    }

    private void setDialogProgress(int progress, long bytes, long total, long speed, long elapsed) {
        if (canceled || !downloading) return;
        long manifestSize = selected == null ? 0 : selected.size;
        if (total <= 0 && manifestSize > 0) total = manifestSize;
        if (progress < 0 && total > 0 && bytes > 0) progress = (int) (bytes * 100.0 / total);
        lastProgress = progress;
        lastBytes = bytes;
        lastTotal = total;
        lastSpeed = speed;
        lastElapsed = elapsed;
        if (dialog == null) return;
        if (!dialog.setProgress(progress, bytes, total, speed, elapsed)) dialog = null;
    }

    @Override
    public void error(String msg) {
        if (canceled) return;
        downloading = false;
        download = null;
        resetProgress();
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        if (canceled) return;
        download = null;
        Update target = selected;
        Task.execute(() -> {
            String error = validate(file, target);
            App.post(() -> {
                if (canceled) return;
                downloading = false;
                resetProgress();
                if (!TextUtils.isEmpty(error)) {
                    Path.clear(file);
                    Notify.show(error);
                    dismiss();
                    return;
                }
                FileUtil.openFile(file);
                dismiss();
            });
        });
    }

    private void restoreDialog(FragmentActivity activity) {
        if (!downloading || selected == null) return;
        show(activity);
        setDialogProgress(lastProgress, lastBytes, lastTotal, lastSpeed, lastElapsed);
    }

    private String validate(File file, Update update) {
        if (file == null || !file.exists() || file.length() <= 0) return ResUtil.getString(R.string.update_download_invalid);
        if (update != null && update.size > 0 && file.length() != update.size) return ResUtil.getString(R.string.update_download_incomplete);
        if (update != null && !TextUtils.isEmpty(update.sha256) && !update.sha256.equalsIgnoreCase(sha256(file))) return ResUtil.getString(R.string.update_download_checksum);
        if (!validatePackage(file, update)) return ResUtil.getString(R.string.update_download_identity);
        return "";
    }

    private boolean validatePackage(File file, Update update) {
        try {
            PackageManager manager = App.get().getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            PackageInfo installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, flags);
            if (archive == null || installed == null || !BuildConfig.APPLICATION_ID.equals(archive.packageName)) return false;
            long archiveCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? archive.getLongVersionCode() : archive.versionCode;
            if (update != null && update.code > 0 && archiveCode != update.code) return false;
            if (update != null && !TextUtils.isEmpty(update.versionName) && !update.versionName.equals(archive.versionName)) return false;
            return signaturesMatch(installed, archive);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean signaturesMatch(PackageInfo installed, PackageInfo archive) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (installed.signingInfo == null || archive.signingInfo == null) return false;
            if (installed.signingInfo.hasMultipleSigners() || archive.signingInfo.hasMultipleSigners()) {
                return fingerprints(installed.signingInfo.getApkContentsSigners()).equals(fingerprints(archive.signingInfo.getApkContentsSigners()));
            }
            Set<String> current = fingerprints(installed.signingInfo.getApkContentsSigners());
            Set<String> candidateHistory = fingerprints(archive.signingInfo.getSigningCertificateHistory());
            return !current.isEmpty() && candidateHistory.containsAll(current);
        }
        return fingerprints(installed.signatures).equals(fingerprints(archive.signatures));
    }

    private Set<String> fingerprints(Signature[] signatures) {
        Set<String> values = new HashSet<>();
        if (signatures == null) return values;
        for (Signature signature : signatures) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                values.add(Arrays.toString(digest.digest(signature.toByteArray())));
            } catch (Exception ignored) {
            }
        }
        return values;
    }

    private String sha256(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) builder.append(String.format(Locale.ROOT, "%02x", value));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void bind(FragmentActivity activity) {
        if (activity == null) return;
        FragmentActivity old = activityRef == null ? null : activityRef.get();
        if (old == activity) return;
        if (old != null) old.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(lifecycleObserver);
    }

    private void unbind(FragmentActivity activity) {
        FragmentActivity current = activityRef == null ? null : activityRef.get();
        if (current != activity) return;
        activity.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = null;
        if (!downloading) dialog = null;
    }

    private void resetProgress() {
        lastProgress = -1;
        lastBytes = 0;
        lastTotal = 0;
        lastSpeed = 0;
        lastElapsed = 0;
    }
}