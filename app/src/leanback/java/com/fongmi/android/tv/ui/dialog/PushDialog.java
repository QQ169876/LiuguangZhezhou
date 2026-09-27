package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Push;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class PushDialog extends BaseAlertDialog {

    private com.fongmi.android.tv.databinding.DialogPushBinding binding;

    public static PushDialog create() {
        return new PushDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = com.fongmi.android.tv.databinding.DialogPushBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.setting_push).setView(getBinding().getRoot()).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        setWidth(0.5f);
        binding.scan.setVisibility(View.GONE);
        binding.file.setVisibility(View.GONE);
        binding.host.setText(Push.getHost());
    }

    @Override
    protected void initEvent() {
        binding.webdav.setOnClickListener(this::onWebDav);
        binding.config.setOnClickListener(this::onConfig);
    }

    private String target() {
        String host = binding.host.getText().toString().trim();
        Push.putHost(host);
        return Push.fix(host);
    }

    private void run(Action action) {
        Notify.progress(getActivity());
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

    private interface Action {
        void run() throws Exception;
    }
}
