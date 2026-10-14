package com.fongmi.android.tv.ui.dialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogScreenDisplayBinding;
import com.fongmi.android.tv.setting.ScreenSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 显示设置：挑一挑播放画面上要露哪些东西。
 * ⚠️ 这里是**各自独立的开关**，不是几选一：剧名在左上角、时间在右上角、进度条在最底下，
 * 打开哪个就显示哪个，可以同时全开（马先生 2026-10-15）。
 * 入口在设置里（设置首页一项「显示设置」，播放设置页里也有一项），不在播放页。
 */
public final class ScreenDisplayDialog extends BaseAlertDialog {

    private DialogScreenDisplayBinding binding;
    private Runnable onChanged;

    public static void show(FragmentActivity activity) {
        show(activity, null);
    }

    /** 从播放页的「播放选项」进来时带上回调：改完关窗口，播放页立刻按新开关重刷一遍 */
    public static void show(FragmentActivity activity, @Nullable Runnable onChanged) {
        if (activity == null) return;
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isDestroyed()) return;
        for (Fragment fragment : manager.getFragments()) if (fragment instanceof ScreenDisplayDialog) return;
        ScreenDisplayDialog dialog = new ScreenDisplayDialog();
        dialog.onChanged = onChanged;
        dialog.show(manager, null);
    }

    public static void show(Fragment fragment) {
        if (fragment == null) return;
        show(fragment.getActivity());
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (onChanged != null) onChanged.run();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogScreenDisplayBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.player_display_setting).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, null);
    }

    @Override
    protected void initView() {
        binding.titleSwitch.setChecked(ScreenSetting.isShowTitle());
        binding.episodeSwitch.setChecked(ScreenSetting.isShowEpisode());
        binding.clockSwitch.setChecked(ScreenSetting.isShowClock());
        binding.clockText.setText(clockName(ScreenSetting.getTimeMode()));
        binding.progressSwitch.setChecked(ScreenSetting.isShowProgress());
        refreshClockModeRow();
    }

    @Override
    protected void initEvent() {
        binding.titleRow.setOnClickListener(view -> toggle(binding.titleSwitch, ScreenSetting::putShowTitle));
        binding.episodeRow.setOnClickListener(view -> toggle(binding.episodeSwitch, ScreenSetting::putShowEpisode));
        binding.clockRow.setOnClickListener(view -> onClockRow());
        binding.clockModeRow.setOnClickListener(view -> onClockModeRow());
        binding.progressRow.setOnClickListener(view -> toggle(binding.progressSwitch, ScreenSetting::putShowProgress));
    }

    /** 右上角的时间：整行就是个开关，开了才谈显示什么 */
    private void onClockRow() {
        boolean on = !binding.clockSwitch.isChecked();
        binding.clockSwitch.setChecked(on);
        ScreenSetting.putShowClock(on);
        refreshClockModeRow();
    }

    /** 时间开着时才能挑内容：实时时间（跟着系统时间走）/ 已经播了多久 / 还剩多久 */
    private void onClockModeRow() {
        if (!ScreenSetting.isShowClock()) return;
        int[] names = {R.string.screen_time_now, R.string.screen_time_played, R.string.screen_time_left};
        int[] modes = {ScreenSetting.TIME_NOW, ScreenSetting.TIME_PLAYED, ScreenSetting.TIME_LEFT};
        CharSequence[] items = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) items[i] = ResUtil.getString(names[i]);
        int checked = 0;
        int mode = ScreenSetting.getTimeMode();
        for (int i = 0; i < modes.length; i++) if (modes[i] == mode) checked = i;
        builder().setTitle(R.string.screen_time_content)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    ScreenSetting.putTimeMode(modes[which]);
                    binding.clockText.setText(clockName(modes[which]));
                    dialog.dismiss();
                }).show();
    }

    /** 时间关着的时候，「显示什么」那一行跟着灰掉 */
    private void refreshClockModeRow() {
        boolean on = ScreenSetting.isShowClock();
        binding.clockModeRow.setEnabled(on);
        binding.clockModeRow.setAlpha(on ? 1f : 0.4f);
    }

    private static String clockName(int mode) {
        return switch (mode) {
            case ScreenSetting.TIME_PLAYED -> ResUtil.getString(R.string.screen_time_played);
            case ScreenSetting.TIME_LEFT -> ResUtil.getString(R.string.screen_time_left);
            default -> ResUtil.getString(R.string.screen_time_now);
        };
    }

    /** 点整行也能切：点了先把开关掰过去，再按掰过去的状态存 —— 开关自己会先翻，别再手动翻一次 */
    private void toggle(com.google.android.material.materialswitch.MaterialSwitch view, java.util.function.Consumer<Boolean> setter) {
        boolean on = !view.isChecked();
        view.setChecked(on);
        setter.accept(on);
    }
}
