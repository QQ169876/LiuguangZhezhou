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
import com.fongmi.android.tv.api.config.VodConfig;
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

/**
 * 局域网同步：自动扫描网段列出设备，选一台后一键推送配置或文件。
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
        String[] items = new String[]{getString(R.string.push_all), getString(R.string.push_config), getString(R.string.push_moon), getString(R.string.push_webdav), getString(R.string.push_file)};
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(item.getName()).setItems(items, (dialog, which) -> {
            Device device = target;
            switch (which) {
                case 0 -> run(() -> Push.all(device.getIp(), getString(R.string.push_config), VodConfig.getUrl()));
                case 1 -> run(() -> Push.config(device.getIp(), getString(R.string.push_config), VodConfig.getUrl()));
                case 2 -> run(() -> Push.moontv(device.getIp()));
                case 3 -> run(() -> Push.webdav(device.getIp()));
                case 4 -> picker.launch("*/*");
            }
        }).show();
    }

    private void run(Action action) {
        Notify.progress(requireActivity());
        Task.execute(() -> {
            try {
                action.run();
                App.post(() -> {
                    Notify.dismiss();
                    Notify.show(R.string.push_done);
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
        run(() -> Push.file(device.getIp(), file));
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
