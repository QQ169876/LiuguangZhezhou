package com.fongmi.android.tv.ui.dialog;

import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.View;

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
            SyncDirectionDialog.show(requireActivity(), this::onDirection);
        } else {
            Notify.progress(requireActivity());
            MoonSync.sync(getListener());
        }
    }

    /** 换过站点或账号：作废旧基线，并把当前目标标记为已确认 */
    private void checkSwitch() {
        if (!MoonSetting.isSwitch()) return;
        MoonSync.resetBase();
        MoonSetting.putConfirm();
    }

    private void onDirection(int direction) {
        checkSwitch();
        Notify.progress(requireActivity());
        if (direction == SyncDirectionDialog.CLOUD) MoonSync.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) MoonSync.push(getListener());
        else MoonSync.sync(getListener());
    }

    private MoonSync.Listener getListener() {
        return (success, message) -> {
            Notify.dismiss();
            Notify.show(message);
            setLastText();
        };
    }
}
