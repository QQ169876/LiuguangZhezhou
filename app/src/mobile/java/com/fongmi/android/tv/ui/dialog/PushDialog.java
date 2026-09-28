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
        scan();
    }

    private void onRescan(View view) {
        scan();
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

    @Override
    public void onFound(Device item) {
        adapter.add(item);
        binding.empty.setVisibility(View.GONE);
        binding.info.setText(ResUtil.getString(R.string.lan_found, adapter.getItemCount()));
    }

    @Override
    public void onProgress(int done, int total) {
        binding.info.setText(ResUtil.getString(R.string.lan_scanning, done, total));
    }

    @Override
    public void onEnd(int count) {
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
        Notify.progress(requireActivity());
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
