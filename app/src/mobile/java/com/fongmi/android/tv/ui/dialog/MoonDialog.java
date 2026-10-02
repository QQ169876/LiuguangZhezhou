package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
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
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.fongmi.android.tv.utils.Task;

import java.util.Date;

public class MoonDialog extends BaseBottomSheetDialog {

    private com.fongmi.android.tv.databinding.DialogMoontvBinding binding;
    private boolean asked;

    public static MoonDialog create() {
        return new MoonDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = com.fongmi.android.tv.databinding.DialogMoontvBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
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
        binding.scan.setOnClickListener(this::onScan);
        binding.qrcode.setOnClickListener(this::onQrCode);
        binding.moonLan.setOnClickListener(this::onLan);
        binding.enable.setOnCheckedChangeListener((view, checked) -> save(false));
        binding.auto.setOnCheckedChangeListener((view, checked) -> save(false));
        watch(binding.url, binding.user, binding.pass);
    }

    /** 输入框失焦就存一次，不用非得点按钮 */
    /** 输入框失焦就存一次；拿到焦点时把光标挪到内容最右边 */
    private void watch(EditText... views) {
        for (EditText view : views) view.setOnFocusChangeListener((v, focus) -> {
            if (focus) view.setSelection(view.getText().length());
            else save(false);
        });
    }

    /** 关掉对话框时兜底保存一次，改完直接退出也不会白改 */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        save(false);
        super.onDismiss(dialog);
        askOnClose(R.string.sync_risk_moon, MoonSetting.isEnabled() && MoonSetting.isSyncable() && MoonSetting.isSwitch());
    }

    /**
     * 改完同步参数直接关掉对话框时，如果同步是开着的、目标又变过，
     * 直接问清楚以哪边为准，免得下一次自动同步闷头合并。
     */
    private void askOnClose(int message, boolean need) {
        if (asked || !need) return;
        if (!isAdded() || isRemoving()) return;
        FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        asked = true;
        SyncRiskDialog.show(activity, message, () -> SyncDirectionDialog.show(activity, direction -> onDirection(direction)));
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

    private void save(boolean hint) {
        MoonSetting.putUrl(binding.url.getText().toString());
        MoonSetting.putUser(binding.user.getText().toString());
        MoonSetting.putPass(binding.pass.getText().toString());
        MoonSetting.putEnabled(binding.enable.isChecked());
        MoonSetting.putAuto(binding.auto.isChecked());
        MoonApi.reset();
        MoonSync.touch();
        if (hint) Notify.show(R.string.moontv_saved);
    }

    private void onLan(View view) {
        save(false);
        Server.get().start();
        Bitmap bitmap = QrHelper.encode(Server.get().getAddress(7), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
        Notify.show(R.string.moontv_lan_tip);
    }

    private void onScan(View view) {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private void onQrCode(View view) {
        save(false);
        Bitmap bitmap = QrHelper.encode(MoonCode.encode(), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
    }

    private void onTest(View view) {
        save(false);
        Notify.progress(this);
        Task.execute(() -> {
            boolean ok = MoonApi.test();
            App.post(() -> {
                Notify.dismiss();
                Notify.show(ok ? R.string.moontv_test_ok : R.string.moontv_test_fail);
            });
        });
    }

    private void onPull(View view) {
        save(false);
        confirm(R.string.moontv_pull, R.string.moontv_confirm_pull, () -> {
            checkSwitch();
            Notify.progress(this);
            MoonSync.pull(getListener());
        });
    }

    private void onPush(View view) {
        save(false);
        confirm(R.string.moontv_push, R.string.moontv_confirm_push, () -> {
            checkSwitch();
            Notify.progress(this);
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
        save(false);
        if (MoonSetting.isSwitch()) {
            // 目标变过：先讲风险，确认后再选 合并/本机/云端
            FragmentActivity activity = requireActivity();
            // 确认风险的回调是延迟执行的，那时候 Fragment 可能已经分离，先把 Activity 抓在手上
            SyncRiskDialog.show(activity, R.string.sync_risk_moon, () -> SyncDirectionDialog.show(activity, this::onDirection));
        } else {
            Notify.progress(this);
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
        checkSwitch();
        // 关掉对话框后才弹的方向选择，到这里 Fragment 多半已分离，requireActivity() 会直接崩
        if (isAdded()) Notify.progress(this);
        if (direction == SyncDirectionDialog.CLOUD) MoonSync.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) MoonSync.push(getListener());
        else MoonSync.sync(getListener());
    }

    private MoonSync.Listener getListener() {
        return (success, message) -> {
            DebugLog.d("Sync", "结果回调 关转圈框");
            Notify.dismiss();
            DebugLog.d("Sync", "结果回调 弹提示 " + message);
            Notify.show(message);
            DebugLog.d("Sync", "结果回调 提示已弹");
            setLastText();
            DebugLog.d("Sync", "结果回调 收尾完成");
        };
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) apply(result.getData().getStringExtra("address"));
    });

    private void apply(String text) {
        if (MoonCode.decode(text)) {
            Notify.show(R.string.moontv_lan_saved);
            initView();
            confirm(R.string.moontv_pull, R.string.moontv_confirm_pull, () -> {
                Notify.progress(this);
                MoonSync.pull(getListener());
            });
        } else {
            Notify.show(R.string.moontv_scan_fail);
        }
    }
}
