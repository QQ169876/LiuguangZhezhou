package com.fongmi.android.tv.ui.dialog;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.adapter.LanAdapter;
import com.fongmi.android.tv.utils.LanScanner;
import com.fongmi.android.tv.utils.LocalNetwork;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Push;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 局域网推送配置：自动扫描网段列出设备，选一台后勾选要推送的内容（可多选）。
 */
public class PushDialog extends BaseBottomSheetDialog implements LanScanner.Callback, LanAdapter.OnClickListener {

    private com.fongmi.android.tv.databinding.DialogPushBinding binding;
    private LanAdapter adapter;
    private LanScanner scanner;
    private Device target;
    /** 等待本地网络权限授权后要执行的动作（扫描或弹手动输入框） */
    private Runnable pendingAction;

    public static PushDialog create() {
        return new PushDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = com.fongmi.android.tv.databinding.DialogPushBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        adapter = new LanAdapter(this);
        binding.recycler.setAdapter(adapter);
        binding.info.setText(R.string.lan_hint);
    }

    @Override
    protected void initEvent() {
        binding.rescan.setOnClickListener(this::onRescan);
        binding.manual.setOnClickListener(this::onManual);
        // Android 17+ 局域网访问需本地网络权限：先申请，授权后再扫描，否则全是超时
        withPermission(this::scan);
    }

    /** 有本地网络权限就直接执行；没有就把动作存起来、弹系统权限框，授权后接着做 */
    private void withPermission(Runnable action) {
        if (LocalNetwork.granted()) action.run();
        else {
            pendingAction = action;
            LocalNetwork.request(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == LocalNetwork.REQUEST_CODE) {
            if (gone()) return;
            Runnable action = pendingAction;
            pendingAction = null;
            if (LocalNetwork.isGranted(requestCode, grantResults)) {
                if (action != null) action.run();
            } else {
                binding.info.setText(R.string.lan_permission_denied);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void onRescan(View view) {
        withPermission(this::scan);
    }

    /** 手动指定目标 IP：扫不到时的兜底，直接对单个 IP 探测 /device */
    private void onManual(View view) {
        withPermission(() -> IpInputDialog.show(requireActivity(), this::connectIp));
    }

    private void connectIp(String ip) {
        Server.get().start();
        binding.info.setText(ResUtil.getString(R.string.lan_connecting, ip));
        Task.execute(() -> {
            LanScanner probe = new LanScanner(this);
            Device device = probe.probeOnce(ip);
            App.post(() -> {
                if (gone()) return;
                if (device == null) {
                    Notify.show(R.string.lan_connect_fail);
                    binding.info.setText(R.string.lan_hint);
                    return;
                }
                if (!adapter.getItems().contains(device)) adapter.add(device);
                binding.empty.setVisibility(View.GONE);
                onItemClick(device);
            });
        });
    }

    private void scan() {
        Server.get().start();
        if (scanner != null) scanner.cancel();
        adapter.clear();
        binding.empty.setVisibility(View.GONE);
        binding.progress.setVisibility(View.VISIBLE);
        binding.info.setText(R.string.lan_hint);
        scanner = new LanScanner(this);
        scanner.start();
    }

    /** 扫描是在后台跑的，结果 post 回来时对话框可能已经关了，那时候就别再动界面了 */
    private boolean gone() {
        return binding == null || !isAdded();
    }

    @Override
    public void onFound(Device item) {
        if (gone()) return;
        adapter.add(item);
        binding.empty.setVisibility(View.GONE);
        binding.info.setText(ResUtil.getString(R.string.lan_found, adapter.getItemCount()));
    }

    @Override
    public void onProgress(int done, int total) {
        if (gone()) return;
        binding.info.setText(ResUtil.getString(R.string.lan_scanning, done, total));
    }

    @Override
    public void onEnd(int count) {
        if (gone()) return;
        binding.progress.setVisibility(View.GONE);
        if (adapter.getItemCount() == 0) {
            binding.empty.setVisibility(View.VISIBLE);
            binding.info.setText(R.string.lan_hint);
        } else {
            binding.info.setText(ResUtil.getString(R.string.lan_found, adapter.getItemCount()));
        }
    }

    @Override
    public void onItemClick(Device item) {
        target = item;
        Push.putHost(item.getIp());
        PushChoice.show(requireActivity(), item.getName(), false, keys -> push(item, keys));
    }

    private void push(Device device, List<String> keys) {
        List<String> data = new ArrayList<>(keys);
        boolean file = data.remove(Push.FILE);
        data.remove(Push.APK);
        if (data.isEmpty() && !file) return;
        if (data.isEmpty()) {
            picker.launch("*/*");
        } else {
            run(() -> Push.run(device.getIp(), data), () -> {
                if (file) picker.launch("*/*");
            });
        }
    }

    private void run(Action action, Runnable next) {
        Notify.progress(this);
        Task.execute(() -> {
            try {
                action.run();
                App.post(() -> {
                    Notify.dismiss();
                    Notify.show(R.string.push_done);
                    if (next != null) next.run();
                });
            } catch (Throwable e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                App.post(() -> {
                    Notify.dismiss();
                    Notify.show(ResUtil.getString(R.string.push_fail) + " " + message);
                });
            }
        });
    }

    private File copy(Uri uri) {
        try (InputStream is = App.get().getContentResolver().openInputStream(uri)) {
            String name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "push.file";
            if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            File file = Path.cache(name);
            Path.copy(is, file);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private final ActivityResultLauncher<String> picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        File file = uri == null ? null : copy(uri);
        Device device = target;
        if (device == null || file == null) {
            Notify.show(R.string.push_fail);
            return;
        }
        run(() -> Push.file(device.getIp(), file), null);
    });

    @Override
    public void onDestroyView() {
        if (scanner != null) scanner.cancel();
        super.onDestroyView();
    }

    private interface Action {
        void run() throws Exception;
    }
}
