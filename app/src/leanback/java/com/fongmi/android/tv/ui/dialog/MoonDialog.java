package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogMoontvBinding;
import com.fongmi.android.tv.moontv.MoonApi;
import com.fongmi.android.tv.moontv.MoonCode;
import com.fongmi.android.tv.moontv.MoonSetting;
import com.fongmi.android.tv.moontv.MoonSync;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Date;

public class MoonDialog extends BaseAlertDialog {

    private DialogMoontvBinding binding;
    private boolean asked;

    public static MoonDialog create() {
        return new MoonDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogMoontvBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.moontv_title).setView(getBinding().getRoot()).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        setWidth(0.5f);
        binding.scan.setVisibility(View.GONE);
        binding.url.setText(MoonSetting.getUrl());
        binding.url.setHint(MoonSetting.DEFAULT_URL);
        binding.user.setText(MoonSetting.getUser());
        binding.pass.setText(MoonSetting.getPass());
        binding.enable.setChecked(MoonSetting.isEnabled());
        binding.auto.setChecked(MoonSetting.isAuto());
        setLastText();
    }

    @Override
    protected void initEvent() {
        binding.pull.setOnClickListener(this::onPull);
        binding.push.setOnClickListener(this::onPush);
        binding.sync.setOnClickListener(this::onSync);
        binding.test.setOnClickListener(this::onTest);
        binding.qrcode.setOnClickListener(this::onQrCode);
        binding.moonLan.setOnClickListener(this::onLan);
        binding.enable.setOnCheckedChangeListener((view, checked) -> save());
        binding.auto.setOnCheckedChangeListener((view, checked) -> save());
        watch(binding.url, binding.user, binding.pass);
    }

    /** 输入框失焦就存一次，不用非得点按钮 */
    /** 输入框失焦就存一次；拿到焦点时把光标挪到内容最右边 */
    private void watch(EditText... views) {
        for (EditText view : views) view.setOnFocusChangeListener((v, focus) -> {
            if (focus) view.setSelection(view.getText().length());
            else save();
        });
    }

    /** 关掉对话框时兜底保存一次，改完直接退出也不会白改 */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        save();
        super.onDismiss(dialog);
        askOnClose(R.string.sync_risk_moon, MoonSetting.isEnabled() && MoonSetting.isSyncable() && MoonSetting.isSwitch());
    }

    /**
     * 遥控器改完参数就退出时，如果同步是开着的、目标又变过，
     * 直接问清楚以哪边为准，免得下一次自动同步闷头合并。
     */
    private void askOnClose(int message, boolean need) {
        FragmentActivity activity = getActivity();
        if (asked || !need) return;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        asked = true;
        SyncRiskDialog.show(activity, message, () -> SyncDirectionDialog.show(activity, direction -> onDirection(activity, direction)));
    }

    private void setLastText() {
        if (MoonSetting.isSyncable() && MoonSetting.isSwitch()) {
            binding.last.setText(R.string.sync_direction_wait);
            return;
        }
        long time = MoonSetting.getLast();
        if (time == 0) binding.last.setText(R.string.moontv_off);
        else binding.last.setText(getString(R.string.moontv_last, DateFormat.format("yyyy-MM-dd HH:mm", new Date(time))));
    }

    private void save() {
        MoonSetting.putUrl(binding.url.getText().toString());
        MoonSetting.putUser(binding.user.getText().toString());
        MoonSetting.putPass(binding.pass.getText().toString());
        MoonSetting.putEnabled(binding.enable.isChecked());
        MoonSetting.putAuto(binding.auto.isChecked());
        MoonApi.reset();
        MoonSync.touch();
    }

    private void onLan(View view) {
        Server.get().start();
        Bitmap bitmap = QrHelper.encode(Server.get().getAddress(7), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
        Notify.show(R.string.moontv_lan_tip);
    }

    private void onQrCode(View view) {
        save();
        Bitmap bitmap = QrHelper.encode(MoonCode.encode(), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
    }

    private void onTest(View view) {
        save();
        Notify.progress(requireActivity());
        Task.execute(() -> {
            boolean ok = MoonApi.test();
            App.post(() -> {
                Notify.dismiss();
                Notify.show(ok ? R.string.moontv_test_ok : R.string.moontv_test_fail);
            });
        });
    }

    private void onPull(View view) {
        save();
        confirm(R.string.moontv_pull, R.string.moontv_confirm_pull, () -> {
            checkSwitch();
            Notify.progress(requireActivity());
            MoonSync.pull(getListener());
        });
    }

    private void onPush(View view) {
        save();
        confirm(R.string.moontv_push, R.string.moontv_confirm_push, () -> {
            checkSwitch();
            Notify.progress(requireActivity());
            MoonSync.push(getListener());
        });
    }

    private void confirm(int title, int message, Runnable ok) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> ok.run())
                .show();
    }

    private void onSync(View view) {
        save();
        if (MoonSetting.isSwitch()) {
            // 目标变过：先讲风险，确认后再选 合并/本机/云端
            SyncRiskDialog.show(requireActivity(), R.string.sync_risk_moon, () -> SyncDirectionDialog.show(requireActivity(), this::onDirection));
        } else {
            Notify.progress(requireActivity());
            MoonSync.sync(getListener());
        }
    }

    /** 换过站点或账号：作废旧基线，并把当前目标标记为已确认 */
    private void checkSwitch() {
        asked = true;
        if (!MoonSetting.isSwitch()) return;
        MoonSync.resetBase();
        MoonSetting.putConfirm();
    }

    private void onDirection(int direction) {
        onDirection(requireActivity(), direction);
    }

    private void onDirection(FragmentActivity activity, int direction) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        checkSwitch();
        Notify.progress(activity);
        if (direction == SyncDirectionDialog.CLOUD) MoonSync.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) MoonSync.push(getListener());
        else MoonSync.sync(getListener());
    }

    private MoonSync.Listener getListener() {
        return (success, message) -> {
            Notify.dismiss();
            Notify.show(message);
            if (binding != null) setLastText();
        };
    }
}
