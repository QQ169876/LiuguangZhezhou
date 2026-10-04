package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private String title;
    private String desc;

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog title(String title) {
        this.title = title;
        return this;
    }

    public UpdateDialog desc(String desc) {
        this.desc = desc;
        return this;
    }

    public UpdateDialog listener(UpdateListener listener) {
        this.listener = listener;
        return this;
    }

    public UpdateDialog show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        return this;
    }

    /**
     * 更新框用无参构造，title/desc/listener 都是事后塞进来的。App 被系统回收后重建、转屏时
     * 系统会按无参构造重建 Fragment，那份 instance 里 listener 是 null，点『更新』就空指针崩。
     * 认出是这种空壳就不显示，下次检查还会重新弹。
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) setShowsDialog(false);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot()).setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.version.setText(title);
        binding.desc.setText(desc);
    }

    @Override
    protected void initEvent() {
        binding.confirm.setOnClickListener(this::onConfirm);
        binding.cancel.setOnClickListener(this::onCancel);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (binding != null) binding.confirm.requestFocus();
    }

    public void setProgress(int progress) {
        if (binding != null) binding.confirm.setText(String.format(Locale.getDefault(), "%1$d%%", progress));
    }

    private void onConfirm(View view) {
        if (listener != null) listener.onConfirm(view);
    }

    private void onCancel(View view) {
        if (listener != null) listener.onCancel(view);
    }
}
