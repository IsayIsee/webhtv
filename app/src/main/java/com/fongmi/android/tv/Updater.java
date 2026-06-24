package com.fongmi.android.tv;

import android.view.View;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;

public class Updater implements Download.Callback, UpdateListener {
    private boolean isForceUpdate = false;
    private Download download;
    private UpdateDialog dialog;
    public static final String TAG = "Updater";

    private Updater() {
        // download 延迟初始化，等拿到 apkUrl 再创建
        // this.download = Download.create(getApk(), getFile());
    }

    public static Updater create() {
        return new Updater();
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
        Notify.show(R.string.update_check);
        this.isForceUpdate = true;
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity));
    }

    private void doInBackground(FragmentActivity activity) {
        final String url = getJson();

        try {
            String jsonResponse = OkHttp.string(url);
            JSONObject object = new JSONObject(jsonResponse);

            String name = object.optString("name");
            String desc = object.optString("desc");
            int code = object.optInt("code");

            // 获取下载 url，url 为空时使用原方法兜底
            String apkUrl = null;
            JSONObject urls = object.optJSONObject("urls");
            if (urls != null) {
                apkUrl = urls.optString(BuildConfig.FLAVOR_abi);
            }

            if (apkUrl == null || apkUrl.isEmpty()) {
                apkUrl = getApk();
            }

            // 拿到地址后再创建 Download
            download = Download.create(apkUrl, getFile());

            // 检查版本并切回主线程显示 UI
            if (code > BuildConfig.VERSION_CODE)
                App.post(() -> show(activity, name, desc));
            else
            if (this.isForceUpdate) {
                App.post(() -> Notify.show(R.string.update_islatest));
            }
        } catch (Exception e) {
            if (this.isForceUpdate) {
                App.post(() -> Notify.show(ResUtil.getString(R.string.update_error, url)));
            }
            Log.e(TAG, ResUtil.getString(R.string.update_error, url), e);
        }
    }

    private void show(FragmentActivity activity, String version, String desc) {
        dismiss();
        dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, version)).desc(desc).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        Setting.putUpdate(false);
        download.cancel();
        dismiss();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        FileUtil.openFile(file);
        dismiss();
    }
}
