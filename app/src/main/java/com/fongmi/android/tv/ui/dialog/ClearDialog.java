package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Clear;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 清除数据：先让用户挑清哪一部分，再把后果讲清楚让他确认，两步都确认了才动手。
 */
public class ClearDialog {

    public static void show(Activity activity) {
        showChoice(activity);
    }

    private static void showChoice(Activity activity) {
        if (!alive(activity)) return;
        try {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.clear_title)
                    .setItems(new CharSequence[]{
                            text(activity, R.string.clear_option_history),
                            text(activity, R.string.clear_option_all)
                    }, (dialog, which) -> App.post(() -> showConfirm(activity, which), 150))
                    .setNegativeButton(R.string.dialog_negative, null)
                    .show();
        } catch (Exception ignored) {
            // 页面正在切换或被回收，这次就不弹了
        }
    }

    private static void showConfirm(Activity activity, int option) {
        if (!alive(activity)) return;
        boolean all = option == 1;
        try {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(all ? R.string.clear_option_all : R.string.clear_option_history)
                    .setMessage(all ? R.string.clear_confirm_all : R.string.clear_confirm_history)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.clear_confirm, (dialog, which) -> run(activity, all))
                    .show();
        } catch (Exception ignored) {
        }
    }

    private static void run(Activity activity, boolean all) {
        if (all) {
            Clear.all(new com.fongmi.android.tv.impl.Callback() {
                @Override
                public void success() {
                    Notify.show(R.string.clear_done_all);
                    App.post(() -> Clear.restart(activity), 800);
                }
            });
        } else {
            Clear.history(new com.fongmi.android.tv.impl.Callback() {
                @Override
                public void success() {
                    Notify.show(R.string.clear_done_history);
                }
            });
        }
    }

    private static String text(Activity activity, int resId) {
        return activity.getString(resId);
    }

    private static boolean alive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
}
