package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QrHelper;
import com.fongmi.android.tv.utils.Task;

import java.util.Date;

public class MoonDialog extends BaseBottomSheetDialog {

    private com.fongmi.android.tv.databinding.DialogMoontvBinding binding;

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
    }

    private void setLastText() {
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
        save(false);
        Notify.progress(requireActivity());
        MoonSync.pull(getListener());
    }

    private void onPush(View view) {
        save(false);
        Notify.progress(requireActivity());
        MoonSync.push(getListener());
    }

    private void onSync(View view) {
        save(false);
        Notify.progress(requireActivity());
        MoonSync.sync(getListener());
    }

    private MoonSync.Listener getListener() {
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
        if (MoonCode.decode(text)) {
            Notify.show(R.string.moontv_saved);
            initView();
            MoonSync.pull(getListener());
        } else {
            Notify.show(R.string.moontv_scan_fail);
        }
    }
}
