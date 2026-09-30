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
import com.github.catvod.utils.Prefers;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogWebdavBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.webdav.SyncManager;
import com.fongmi.android.tv.webdav.WebDav;
import com.fongmi.android.tv.webdav.WebDavCode;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Date;

public class WebDavDialog extends BaseAlertDialog {

    private DialogWebdavBinding binding;
    private static final String KNOWN = "webdav_remote_known";
    private boolean asked;

    public static WebDavDialog create() {
        return new WebDavDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogWebdavBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.webdav_title).setView(getBinding().getRoot()).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        setWidth(0.5f);
        binding.scan.setVisibility(View.GONE);
        binding.url.setText(WebDavSetting.getUrl());
        binding.user.setText(WebDavSetting.getUser());
        binding.pass.setText(WebDavSetting.getPass());
        binding.folder.setText(WebDavSetting.getFolder());
        binding.enable.setChecked(WebDavSetting.isEnabled());
        binding.auto.setChecked(WebDavSetting.isAuto());
        setLastText();
    }

    @Override
    protected void initEvent() {
        binding.sync.setOnClickListener(this::onSync);
        binding.test.setOnClickListener(this::onTest);
        binding.push.setOnClickListener(this::onPush);
        binding.pull.setOnClickListener(this::onPull);
        binding.qrcode.setOnClickListener(this::onQrCode);
        binding.lan.setOnClickListener(this::onLan);
        binding.enable.setOnCheckedChangeListener((view, checked) -> save());
        binding.auto.setOnCheckedChangeListener((view, checked) -> save());
        watch(binding.url, binding.user, binding.pass, binding.folder);
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
        checkRemote(() -> askOnClose(R.string.sync_risk_dav, WebDavSetting.isEnabled() && WebDavSetting.isSyncable() && WebDavSetting.isSwitch()));
    }

    /**
     * 远端这份同步目录里已经有数据（可能是别的设备、别的账号留下的）：
     * 先把话说清楚，别稀里糊涂把两边的观看记录、收藏混在一起。
     */
    private void checkRemote(Runnable next) {
        if (!WebDavSetting.isValid()) {
            next.run();
            return;
        }
        String scope = WebDavSetting.getScope();
        if (Prefers.getString(KNOWN).equals(scope)) {
            next.run();
            return;
        }
        Task.execute(() -> {
            boolean found = false;
            try {
                found = WebDav.exists(WebDavSetting.getFolderUrl());
            } catch (Exception ignored) {
            }
            boolean exist = found;
            App.post(() -> onRemote(exist, scope, next));
        });
    }

    private void onRemote(boolean found, String scope, Runnable next) {
        Prefers.put(KNOWN, scope);
        if (!found) {
            next.run();
            return;
        }
        FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        asked = false;
        try {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.webdav_remote_title)
                    .setMessage(R.string.webdav_remote_exists)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (dialog, which) -> SyncRiskDialog.show(activity, R.string.sync_risk_dav, () -> SyncDirectionDialog.show(activity, direction -> onDirection(activity, direction))))
                    .show();
        } catch (Exception ignored) {
        }
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

    private void onLan(View view) {
        Server.get().start();
        Bitmap bitmap = QrHelper.encode(Server.get().getAddress(6), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
        Notify.show(R.string.webdav_lan_tip);
    }

    private void setLastText() {
        if (WebDavSetting.isSyncable() && WebDavSetting.isSwitch()) {
            binding.last.setText(R.string.sync_direction_wait);
            return;
        }
        long time = WebDavSetting.getLast();
        if (time == 0) binding.last.setText(R.string.webdav_off);
        else binding.last.setText(getString(R.string.webdav_last, DateFormat.format("yyyy-MM-dd HH:mm", new Date(time))));
    }

    private void save() {
        WebDavSetting.putUrl(binding.url.getText().toString());
        WebDavSetting.putUser(binding.user.getText().toString());
        WebDavSetting.putPass(binding.pass.getText().toString());
        WebDavSetting.putFolder(binding.folder.getText().toString());
        WebDavSetting.putEnabled(binding.enable.isChecked());
        WebDavSetting.putAuto(binding.auto.isChecked());
    }

    private void onQrCode(View view) {
        save();
        Bitmap bitmap = QrHelper.encode(WebDavCode.encode(), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
    }

    private void onTest(View view) {
        save();
        Notify.progress(requireActivity());
        Task.execute(() -> {
            boolean ok = WebDav.test();
            App.post(() -> {
                Notify.dismiss();
                Notify.show(ok ? R.string.webdav_test_ok : R.string.webdav_test_fail);
            });
        });
    }

    private void onSync(View view) {
        save();
        if (WebDavSetting.isSwitch()) {
            // 目标变过：先讲风险，确认后再选 合并/本机/云端
            SyncRiskDialog.show(requireActivity(), R.string.sync_risk_dav, () -> SyncDirectionDialog.show(requireActivity(), this::onDirection));
        } else {
            Notify.progress(requireActivity());
            SyncManager.sync(getListener());
        }
    }

    /** 换过地址、目录或账号：作废旧基线，并把当前目标标记为已确认 */
    private void checkSwitch() {
        asked = true;
        if (!WebDavSetting.isSwitch()) return;
        SyncManager.resetBase();
        WebDavSetting.putConfirm();
    }

    private void onDirection(int direction) {
        onDirection(requireActivity(), direction);
    }

    private void onDirection(FragmentActivity activity, int direction) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        checkSwitch();
        Notify.progress(activity);
        if (direction == SyncDirectionDialog.CLOUD) SyncManager.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) SyncManager.push(getListener());
        else SyncManager.sync(getListener());
    }

    private void onPush(View view) {
        save();
        checkSwitch();
        Notify.progress(requireActivity());
        SyncManager.push(getListener());
    }

    private void onPull(View view) {
        save();
        checkSwitch();
        Notify.progress(requireActivity());
        SyncManager.pull(getListener());
    }

    private SyncManager.Listener getListener() {
        return (success, message) -> {
            Notify.dismiss();
            Notify.show(message);
            if (binding != null) setLastText();
        };
    }
}
