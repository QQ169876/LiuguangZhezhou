package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
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
     * 更新框用的是无参构造，title/desc/listener 都是事后塞进来的。App 退到后台被回收、
     * 转屏这类情况下系统会按无参构造把 Fragment 重建出来，那份是空的：listener 是 null，
     * 点『更新』时 listener.getClass() 直接空指针崩（手机上实测到过）。
     * 所以认出自己是「重建的空壳」就不显示了 —— 下次进首页还会重新弹，不影响更新。
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
        return builder().setTitle(title).setView(getBinding().getRoot()).setPositiveButton(R.string.update_confirm, null).setNegativeButton(R.string.dialog_negative, null).setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.desc.setText(desc);
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (negative != null) negative.setOnClickListener(view -> { if (listener != null) listener.onCancel(view); });
        if (positive != null) positive.setOnClickListener(view -> { if (listener != null) listener.onConfirm(view); });
    }

    public void setProgress(int progress) {
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) positive.setText(String.format(Locale.getDefault(), "%1$d%%", progress));
    }
}