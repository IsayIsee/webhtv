package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class MpvCustomButtonDialog extends DialogFragment {

    private Runnable callback;
    private LinearLayout list;

    public static void show(FragmentManager manager, Runnable callback) {
        MpvCustomButtonDialog dialog = new MpvCustomButtonDialog();
        dialog.callback = callback;
        dialog.show(manager, "mpv-custom-buttons");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LinearLayout root = column(requireContext(), 16);
        root.setBackgroundResource(R.drawable.shape_shell_proxy_dialog);
        LinearLayout header = row(requireContext());
        TextView title = text(requireContext(), getString(R.string.mpv_config_custom_button_title), 18, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        AppCompatImageButton close = new AppCompatImageButton(requireContext());
        close.setBackgroundResource(R.drawable.selector_mpv_icon_button);
        close.setContentDescription(getString(R.string.mpv_config_close));
        close.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10));
        close.setImageResource(R.drawable.ic_dialog_close);
        close.setColorFilter(Color.rgb(95, 99, 104));
        close.setOnClickListener(view -> dismissAllowingStateLoss());
        header.addView(close, new LinearLayout.LayoutParams(ResUtil.dp2px(44), ResUtil.dp2px(40)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(44)));

        ScrollView scroll = new ScrollView(requireContext());
        list = column(requireContext(), 8);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        MaterialButton add = button(requireContext(), getString(R.string.mpv_config_custom_button_add));
        add.setMinHeight(0);
        add.setMinWidth(0);
        add.setOnClickListener(view -> openEditor(null));
        root.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(40)));
        refresh();
        return new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(root).create();
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        List<MpvConfigStore.CustomButton> buttons = MpvConfigStore.customButtons();
        if (buttons.isEmpty()) {
            TextView empty = text(requireContext(), getString(R.string.mpv_config_custom_button_empty), 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, ResUtil.dp2px(32), 0, ResUtil.dp2px(32));
            list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (MpvConfigStore.CustomButton button : buttons) addRow(button);
    }

    private void addRow(MpvConfigStore.CustomButton item) {
        LinearLayout row = column(requireContext(), 2);
        row.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(8), ResUtil.dp2px(8));
        row.setBackgroundResource(R.drawable.selector_mpv_profile_card);
        LinearLayout top = row(requireContext());
        TextView title = text(requireContext(), item.title, 15, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = text(requireContext(), item.enabled ? getString(R.string.mpv_config_custom_button_enabled_short) : getString(R.string.mpv_config_custom_button_disabled_short), 11, false);
        state.setTextColor(item.enabled ? Color.rgb(11, 87, 208) : Color.rgb(128, 134, 139));
        top.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout actions = row(requireContext());
        MaterialButton edit = button(requireContext(), getString(R.string.mpv_config_edit));
        edit.setOnClickListener(view -> openEditor(item));
        MaterialButton delete = button(requireContext(), getString(R.string.mpv_config_delete));
        delete.setOnClickListener(view -> confirmDelete(item));
        actions.addView(edit, new LinearLayout.LayoutParams(0, ResUtil.dp2px(40), 1));
        actions.addView(delete, new LinearLayout.LayoutParams(0, ResUtil.dp2px(40), 1));
        row.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void openEditor(@Nullable MpvConfigStore.CustomButton button) {
        Editor.show(getChildFragmentManager(), button, this::refresh);
    }

    private void confirmDelete(MpvConfigStore.CustomButton button) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mpv_config_custom_button_delete)
                .setMessage(getString(R.string.mpv_config_custom_button_delete_message, button.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mpv_config_delete, (dialog, which) -> {
                    try {
                        MpvConfigStore.deleteCustomButton(button.id);
                        refresh();
                        if (callback != null) callback.run();
                    } catch (Throwable error) {
                        Notify.show(message(error));
                    }
                }).show();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = Math.min((int) (ResUtil.getScreenWidth(requireContext()) * (Util.isLeanback() ? 0.68f : 0.94f)), ResUtil.dp2px(760));
        params.height = (int) (ResUtil.getScreenHeight(requireContext()) * (Util.isLeanback() ? 0.82f : 0.86f));
        params.dimAmount = 0.58f;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }

    private static LinearLayout row(android.content.Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private static LinearLayout column(android.content.Context context, int padding) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(ResUtil.dp2px(padding), ResUtil.dp2px(padding), ResUtil.dp2px(padding), ResUtil.dp2px(padding));
        return view;
    }

    private static TextView text(android.content.Context context, String value, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(Color.rgb(32, 33, 36));
        view.setTextSize(size);
        view.setTypeface(view.getTypeface(), bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return view;
    }

    private static MaterialButton button(android.content.Context context, String value) {
        MaterialButton view = new MaterialButton(context);
        view.setText(value);
        view.setMinHeight(0);
        view.setMinWidth(0);
        return view;
    }

    private static String message(Throwable error) {
        return TextUtils.isEmpty(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
    }

    public static final class Editor extends DialogFragment {

        private MpvConfigStore.CustomButton source;
        private Runnable callback;
        private TextInputEditText title;
        private TextInputEditText shortCode;
        private TextInputEditText longCode;
        private TextInputEditText startupCode;
        private CheckBox enabled;

        static void show(FragmentManager manager, MpvConfigStore.CustomButton source, Runnable callback) {
            Editor dialog = new Editor();
            dialog.source = source;
            dialog.callback = callback;
            dialog.show(manager, "mpv-custom-button-editor");
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            LinearLayout root = column(requireContext(), 18);
            root.setBackgroundResource(R.drawable.shape_shell_proxy_dialog);
            TextView heading = text(requireContext(), getString(source == null ? R.string.mpv_config_custom_button_new : R.string.mpv_config_custom_button_edit), 18, true);
            root.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(42)));
            title = input(requireContext(), getString(R.string.mpv_config_custom_button_name), source == null ? "" : source.title, false);
            root.addView(title, inputParams());
            enabled = new CheckBox(requireContext());
            enabled.setText(R.string.mpv_config_custom_button_enabled);
            enabled.setChecked(source == null || source.enabled);
            root.addView(enabled, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shortCode = input(requireContext(), getString(R.string.mpv_config_custom_button_short), source == null ? "" : source.content, true);
            longCode = input(requireContext(), getString(R.string.mpv_config_custom_button_long), source == null ? "" : source.longPressContent, true);
            startupCode = input(requireContext(), getString(R.string.mpv_config_custom_button_startup), source == null ? "" : source.onStartup, true);
            root.addView(shortCode, inputParams());
            root.addView(longCode, inputParams());
            root.addView(startupCode, inputParams());
            LinearLayout actions = row(requireContext());
            MaterialButton cancel = button(requireContext(), getString(R.string.mpv_config_close));
            cancel.setOnClickListener(view -> dismissAllowingStateLoss());
            MaterialButton save = button(requireContext(), getString(R.string.mpv_config_save));
            save.setOnClickListener(view -> save());
            actions.addView(cancel, new LinearLayout.LayoutParams(0, ResUtil.dp2px(46), 1));
            actions.addView(save, new LinearLayout.LayoutParams(0, ResUtil.dp2px(46), 1));
            root.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(scroll).create();
        }

        private void save() {
            try {
                String id = source == null ? "" : source.id;
                MpvConfigStore.saveCustomButton(id, value(title), value(shortCode), value(longCode), value(startupCode), enabled.isChecked());
                Notify.show(R.string.mpv_config_custom_button_saved);
                if (callback != null) callback.run();
                dismissAllowingStateLoss();
            } catch (Throwable error) {
                Notify.show(message(error));
            }
        }

        private static TextInputEditText input(android.content.Context context, String hint, String value, boolean multiline) {
            TextInputEditText edit = new TextInputEditText(context);
            edit.setHint(hint);
            edit.setText(value);
            edit.setTextSize(14);
            edit.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setSingleLine(!multiline);
            if (multiline) {
                edit.setMinLines(3);
                edit.setGravity(Gravity.TOP | Gravity.START);
            }
            return edit;
        }

        private static LinearLayout.LayoutParams inputParams() {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ResUtil.dp2px(8);
            return params;
        }

        private static String value(EditText edit) {
            return edit.getText() == null ? "" : edit.getText().toString();
        }

        @Override
        public void onStart() {
            super.onStart();
            Dialog dialog = getDialog();
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = Math.min((int) (ResUtil.getScreenWidth(requireContext()) * (Util.isLeanback() ? 0.68f : 0.94f)), ResUtil.dp2px(760));
            params.height = (int) (ResUtil.getScreenHeight(requireContext()) * (Util.isLeanback() ? 0.88f : 0.9f));
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setAttributes(params);
            window.setLayout(params.width, params.height);
        }
    }
}
