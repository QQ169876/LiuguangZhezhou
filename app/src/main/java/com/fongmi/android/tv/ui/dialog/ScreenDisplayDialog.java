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
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.function.Consumer;

/**
 * 显示设置：**画面上能看见的每一样东西，这里都有一个开关**（马先生 2026-10-15）。
 *
 * 左上：视频名称、当前集数、分辨率
 * 右上：年月日、当前时间、已播时长、剩余时长
 * 最底部：进度条（不显示 / 迷你细线 / 完整进度条）
 *
 * 各管各的，打开哪个就多露哪个，各占一行并列排着，不互相覆盖。
 * 入口在设置里（设置首页一项，播放设置页里也有一项），不在播放页。
 */
public final class ScreenDisplayDialog extends BaseAlertDialog {

    private DialogScreenDisplayBinding binding;
    private Runnable onChanged;

    public static void show(FragmentActivity activity) {
        show(activity, null);
    }

    /** 从播放页进来时带上回调：改完关窗口，播放页立刻按新开关重刷一遍 */
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
        binding.sizeSwitch.setChecked(ScreenSetting.isShowSize());
        binding.dateSwitch.setChecked(ScreenSetting.isShowDate());
        binding.clockSwitch.setChecked(ScreenSetting.isShowClock());
        binding.playedSwitch.setChecked(ScreenSetting.isShowPlayed());
        binding.leftSwitch.setChecked(ScreenSetting.isShowLeft());
        binding.progressText.setText(progressName(ScreenSetting.getProgressStyle()));
    }

    @Override
    protected void initEvent() {
        binding.titleRow.setOnClickListener(view -> toggle(binding.titleSwitch, ScreenSetting::putShowTitle));
        binding.episodeRow.setOnClickListener(view -> toggle(binding.episodeSwitch, ScreenSetting::putShowEpisode));
        binding.sizeRow.setOnClickListener(view -> toggle(binding.sizeSwitch, ScreenSetting::putShowSize));
        binding.dateRow.setOnClickListener(view -> toggle(binding.dateSwitch, ScreenSetting::putShowDate));
        binding.clockRow.setOnClickListener(view -> toggle(binding.clockSwitch, ScreenSetting::putShowClock));
        binding.playedRow.setOnClickListener(view -> toggle(binding.playedSwitch, ScreenSetting::putShowPlayed));
        binding.leftRow.setOnClickListener(view -> toggle(binding.leftSwitch, ScreenSetting::putShowLeft));
        binding.progressRow.setOnClickListener(view -> onProgressRow());
    }

    /** 底部进度条：不显示 / 迷你（一道细线）/ 完整（常规的那种） */
    private void onProgressRow() {
        int[] names = {R.string.screen_time_off, R.string.screen_progress_mini, R.string.screen_progress_full};
        int[] styles = {ScreenSetting.PROGRESS_OFF, ScreenSetting.PROGRESS_MINI, ScreenSetting.PROGRESS_FULL};
        CharSequence[] items = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) items[i] = ResUtil.getString(names[i]);
        int checked = 0;
        int style = ScreenSetting.getProgressStyle();
        for (int i = 0; i < styles.length; i++) if (styles[i] == style) checked = i;
        builder().setTitle(R.string.screen_progress_style)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    ScreenSetting.putProgressStyle(styles[which]);
                    binding.progressText.setText(progressName(styles[which]));
                    dialog.dismiss();
                }).show();
    }

    private static String progressName(int style) {
        return switch (style) {
            case ScreenSetting.PROGRESS_OFF -> ResUtil.getString(R.string.screen_time_off);
            case ScreenSetting.PROGRESS_FULL -> ResUtil.getString(R.string.screen_progress_full);
            default -> ResUtil.getString(R.string.screen_progress_mini);
        };
    }

    /** 点整行也能切：先把开关掰过去，再按掰过去的状态存（开关自己 clickable=false，不会吃掉点击） */
    private void toggle(MaterialSwitch view, Consumer<Boolean> setter) {
        boolean on = !view.isChecked();
        view.setChecked(on);
        setter.accept(on);
    }
}
