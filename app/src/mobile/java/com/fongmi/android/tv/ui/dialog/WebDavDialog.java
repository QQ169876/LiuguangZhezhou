package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.webdav.SyncManager;
import com.fongmi.android.tv.webdav.WebDav;
import com.fongmi.android.tv.webdav.WebDavCode;
import com.fongmi.android.tv.webdav.WebDavSetting;

import java.util.Date;

public class WebDavDialog extends BaseBottomSheetDialog {

    private com.fongmi.android.tv.databinding.DialogWebdavBinding binding;

    public static WebDavDialog create() {
        return new WebDavDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = com.fongmi.android.tv.databinding.DialogWebdavBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
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
        binding.scan.setOnClickListener(this::onScan);
        binding.push.setOnClickListener(this::onPush);
        binding.pull.setOnClickListener(this::onPull);
        binding.qrcode.setOnClickListener(this::onQrCode);
        binding.lan.setOnClickListener(this::onLan);
        binding.enable.setOnCheckedChangeListener((view, checked) -> save(false));
        binding.auto.setOnCheckedChangeListener((view, checked) -> save(false));
        watch(binding.url, binding.user, binding.pass, binding.folder);
    }

    /** 输入框失焦就存一次，不用非得点按钮 */
    private void watch(TextView... views) {
        for (TextView view : views) view.setOnFocusChangeListener((v, focus) -> {
            if (!focus) save(false);
        });
    }

    /** 关掉对话框时兜底保存一次，改完直接退出也不会白改 */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        save(false);
        super.onDismiss(dialog);
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

    private void save(boolean hint) {
        WebDavSetting.putUrl(binding.url.getText().toString());
        WebDavSetting.putUser(binding.user.getText().toString());
        WebDavSetting.putPass(binding.pass.getText().toString());
        WebDavSetting.putFolder(binding.folder.getText().toString());
        WebDavSetting.putEnabled(binding.enable.isChecked());
        WebDavSetting.putAuto(binding.auto.isChecked());
        if (hint) Notify.show(R.string.webdav_saved);
    }

    private void onScan(View view) {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private void onQrCode(View view) {
        save(false);
        Bitmap bitmap = QrHelper.encode(WebDavCode.encode(), 640);
        if (bitmap == null) return;
        binding.image.setVisibility(View.VISIBLE);
        binding.image.setImageBitmap(bitmap);
    }

    private void onTest(View view) {
        save(false);
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
        save(false);
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
        if (!WebDavSetting.isSwitch()) return;
        SyncManager.resetBase();
        WebDavSetting.putConfirm();
    }

    private void onDirection(int direction) {
        checkSwitch();
        Notify.progress(requireActivity());
        if (direction == SyncDirectionDialog.CLOUD) SyncManager.pull(getListener());
        else if (direction == SyncDirectionDialog.LOCAL) SyncManager.push(getListener());
        else SyncManager.sync(getListener());
    }

    private void onPush(View view) {
        save(false);
        checkSwitch();
        Notify.progress(requireActivity());
        SyncManager.push(getListener());
    }

    private void onPull(View view) {
        save(false);
        checkSwitch();
        Notify.progress(requireActivity());
        SyncManager.pull(getListener());
    }

    private SyncManager.Listener getListener() {
        return (success, message) -> {
            Notify.dismiss();
            Notify.show(message);
            setLastText();
        };
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) apply(result.getData().getStringExtra("address"));
    });

    private void apply(String text) {
        if (WebDavCode.decode(text)) {
            Notify.show(R.string.webdav_saved);
            initView();
        } else {
            Notify.show(R.string.webdav_scan_fail);
        }
    }
}
