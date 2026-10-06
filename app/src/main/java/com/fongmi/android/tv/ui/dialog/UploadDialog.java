package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUploadBinding;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 局域网推送文件时接收端的进度框。
 * 没有它的话，发送端一会就显示「推送成功」，接收端这边却一声不响，
 * 站在接收设备前完全分不清是还在传、传了一半断了、还是已经收完了。
 */
public class UploadDialog extends BaseAlertDialog {

    private final String name;

    private DialogUploadBinding binding;

    public static UploadDialog create(String name) {
        return new UploadDialog(name);
    }

    private UploadDialog(String name) {
        this.name = name;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof UploadDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUploadBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.upload_receiving).setView(getBinding().getRoot()).setCancelable(false);
    }

    @Override
    protected void initView() {
        // 文件名从创建时就带上：这里再 set 一次是为了那种「还没来得及显示就改名」的情况
        if (name != null && !name.isEmpty()) binding.name.setText(name);
    }

    /** percent 传负数表示总量未知（分块上传），这时只显示已收到的体积 */
    public void setProgress(int percent, long done, long total) {
        App.post(() -> {
            if (binding == null) return;
            if (percent >= 0) binding.progress.setProgress(percent);
            binding.text.setText(ResUtil.getString(R.string.upload_size, percent < 0 ? 0 : percent, FileUtil.byteCountToDisplaySize(done), FileUtil.byteCountToDisplaySize(total)));
        });
    }
}
