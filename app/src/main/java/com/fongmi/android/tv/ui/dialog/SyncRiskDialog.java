package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;

import com.fongmi.android.tv.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 改动可能影响收藏、观看记录的设置时（换点播源、改同步地址或账号），
 * 先把风险讲清楚，让用户自己判断数据以哪边为准，而不是闷头就同步。
 */
public class SyncRiskDialog {

    public static void show(Activity activity, int message, Runnable onAcknowledge) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.sync_risk_title)
                    .setMessage(message)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.sync_risk_confirm, (dialog, which) -> {
                        if (onAcknowledge != null) onAcknowledge.run();
                    })
                    .show();
        } catch (Exception ignored) {
            // 页面已经切走或正在重建，这次就不弹了
        }
    }
}
