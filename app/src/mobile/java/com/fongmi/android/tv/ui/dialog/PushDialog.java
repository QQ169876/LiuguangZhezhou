package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
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
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Push;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.InputStream;

public class PushDialog extends BaseBottomSheetDialog {

    private com.fongmi.android.tv.databinding.DialogPushBinding binding;

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
        binding.host.setText(Push.getHost());
    }

    @Override
    protected void initEvent() {
        binding.scan.setOnClickListener(this::onScan);
        binding.webdav.setOnClickListener(this::onWebDav);
        binding.config.setOnClickListener(this::onConfig);
        binding.file.setOnClickListener(this::onFile);
    }

    private String target() {
        String host = binding.host.getText().toString().trim();
        Push.putHost(host);
        return Push.fix(host);
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

    private void onScan(View view) {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private void onWebDav(View view) {
        String host = target();
        if (host.isEmpty()) Notify.show(R.string.push_empty);
        else run(() -> Push.webdav(host));
    }

    private void onConfig(View view) {
        String host = target();
        if (host.isEmpty()) {
            Notify.show(R.string.push_empty);
            return;
        }
        String url = VodConfig.getUrl();
        if (url == null || url.isEmpty()) {
            Notify.show(R.string.push_fail);
            return;
        }
        run(() -> Push.config(host, getString(R.string.push_config), url));
    }

    private void onFile(View view) {
        String host = target();
        if (host.isEmpty()) {
            Notify.show(R.string.push_empty);
            return;
        }
        picker.launch("*/*");
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

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) apply(result.getData().getStringExtra("address"));
    });

    private final ActivityResultLauncher<String> picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        String host = target();
        File file = uri == null ? null : copy(uri);
        if (file == null) {
            Notify.show(R.string.push_fail);
            return;
        }
        run(() -> Push.file(host, file));
    });

    private void apply(String text) {
        if (text == null || text.isEmpty()) return;
        if (!text.startsWith("http")) {
            Notify.show(R.string.push_fail);
            return;
        }
        binding.host.setText(Push.fix(text));
        Push.putHost(Push.fix(text));
    }

    private interface Action {
        void run() throws Exception;
    }
}
