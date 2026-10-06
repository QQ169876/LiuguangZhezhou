package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 局域网推过来一个普通文件时，接收端问一句「要不要打开」。
 *
 * 以前收到就弹一句「已收到文件」然后什么都不做，站在电视前的人压根不知道文件去哪了；
 * 反过来要是直接替他打开也很突然 —— 推个文本未必是想现在看。所以收到先问。
 */
public class ReceiveFileDialog {

    public static void show(File file) {
        Activity activity = App.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Notify.show(ResUtil.getString(R.string.push_received_saved, file.getAbsolutePath()));
            return;
        }
        try {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.push_received)
                    .setMessage(ResUtil.getString(R.string.push_open_ask, file.getName(), FileUtil.byteCountToDisplaySize(file.length())))
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.push_open, (dialog, which) -> FileUtil.openFile(file, true))
                    .show();
        } catch (Throwable e) {
            Notify.show(ResUtil.getString(R.string.push_received_saved, file.getAbsolutePath()));
        }
    }
}
