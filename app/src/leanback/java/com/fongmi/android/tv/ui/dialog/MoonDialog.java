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
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Date;

public class MoonDialog extends BaseAlertDialog {

    private DialogMoontvBinding binding;
    private boolean asked;
    private String snapshot; // 打开对话框那一刻的设置：关掉时比对，改过就该问一句以哪边为准

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
        if (snapshot == null) snapshot = snap(); // 只记第一次：扫码导入配置也走这里，别把改动记没了
        setLastText();
    }

    /** 当前设置的指纹：站点地址/账号/密码 + 两个开关 */
    private String snap() {
        return MoonSetting.getUrl() + "|" + MoonSetting.getUser() + "|" + MoonSetting.getPass() + "|" + MoonSetting.isEnabled() + "|" + MoonSetting.isAuto();
    }

    private boolean changed() {
        return snapshot != null && !snapshot.equals(snap());
    }

    /** 同步开着、并且这次确实动了设置 → 关掉前必须问清楚以哪边为准（跟 WebDAV 同一个口径） */
    private boolean needAsk() {
        return MoonSetting.isSyncable() && (MoonSetting.isSwitch() || changed());
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
        // ⚠️ 对话框一关 Fragment 就被摘了，getActivity() 随时变 null，先把 Activity 抓在手上
        FragmentActivity activity = getActivity();
        super.onDismiss(dialog);
        askOnClose(activity, R.string.sync_risk_moon, needAsk());
    }

    /**
     * 遥控器改完参数就退出时，只要同步是开着的、这次又动了设置，
     * 直接问清楚以哪边为准，免得下一次自动同步闷头合并（马先生 2026-10-14 定的：这个口子要堵死）。
     */
    private void askOnClose(FragmentActivity activity, int message, boolean need) {
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
        // 用 App 的上下文取串：同步结果回来得晚，那时候对话框可能已经关了，Fragment 已分离，getString() 会直接崩
        else binding.last.setText(App.get().getString(R.string.moontv_last, DateFormat.format("yyyy-MM-dd HH:mm", new Date(time))));
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
        save();
        confirm(R.string.moontv_pull, R.string.moontv_confirm_pull, () -> {
            checkSwitch();
            Notify.progress(this);
            MoonSync.pull(getListener());
        });
    }

    private void onPush(View view) {
        save();
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
        save();
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

    /**
     * 选完方向：换过站点或账号就作废旧基线；不管换没换，当前目标都记成「已确认」，
     * 免得只是改了个开关也一直卡在「待选择同步方向」上。
     */
    private void checkSwitch() {
        asked = true;
        if (MoonSetting.isSwitch()) MoonSync.resetBase();
        MoonSetting.putConfirm();
    }

    private void onDirection(int direction) {
        checkSwitch();
        // 关掉对话框后才弹的方向选择，到这里 Fragment 多半已分离，转圈框也要跟着 Fragment 走，没附加就别弹
        if (isAdded()) Notify.progress(this);
        if (direction == SyncDirectionDialog.CLOUD) MoonSync.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) MoonSync.push(getListener());
        else MoonSync.sync(getListener());
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
            DebugLog.d("Sync", "结果回调 关转圈框");
            Notify.dismiss();
            DebugLog.d("Sync", "结果回调 弹提示 " + message);
            Notify.show(message);
            DebugLog.d("Sync", "结果回调 提示已弹");
            // 对话框可能已经关了：binding 还在不代表 Fragment 还挂着，两个都要看
            if (isAdded() && binding != null) setLastText();
            DebugLog.d("Sync", "结果回调 收尾完成");
        };
    }
}
