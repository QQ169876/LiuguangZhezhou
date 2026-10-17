package com.fongmi.android.tv.sync;

import android.app.Activity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 同步里的「批量删除」兜底（马先生 2026-10-17 提的）。
 *
 * 要防的是这一件事：**在 App 里误删了观看记录 / 收藏（长按再长按就是整批清空），
 * 一同步，网盘和影视站上的那份也跟着没了** —— 数据两头都没了，找不回来。
 *
 * 所以闸只加在「本机少了 → 要删云端」这一个方向上：
 *   · 自动同步时本机比上次少了挺多记录 → 弹窗问一句，同步的时候弹，不是删除的时候弹；
 *   · 用户说「仍然删除」才真删；没界面弹 / 用户不表态 / 点了「先不删」→ 一条都不删，
 *     网盘那份原样留着，本机误删的那批还会被补回来（等于救回来了）。
 *
 * 反过来那两种不算误删，别拦：
 *   · 云端覆盖本机（换账号、从云端拉） → 那本来就是账号自己的数据，整体替换正常；
 *   · 云端少了 → 本机跟着删 → 同步的正常结果。
 */
public final class SyncGuard {

    /** 一次要删这么多条以上就算批量，得先问 */
    private static final int MIN_COUNT = 10;

    /** 库本身不大时（比如一共就十几条），一次删掉一半以上同样可疑 */
    private static final int MIN_TOTAL = 6;

    /** 拒过之后隔多久才再问一次：太短会烦人，永久不问的话用户后来真想删就没机会点了 */
    private static final long DECLINE_TTL = 30 * 60 * 1000L;

    private static String declined;   // 上次被拒的规模，一样的话不再反复弹，免得每次同步都来一遍
    private static long declinedAt;
    private static String approved;   // 用户刚点过「仍然删除」的规模，下一次同步照删不问

    private SyncGuard() {
    }

    /** 这个删除量算不算「批量」 */
    public static boolean risky(int deletes, int total) {
        if (deletes <= 0) return false;
        if (deletes >= MIN_COUNT) return true;
        return total >= MIN_TOTAL && deletes * 2 >= total;
    }

    /**
     * 这笔删除能不能直接做。用户刚刚对同样规模点过「仍然删除」的，这次照做不再问。
     */
    public static synchronized boolean allow(int deletes, int total) {
        if (approved != null && approved.equals(String.valueOf(deletes))) {
            approved = null;
            return true;
        }
        return !risky(deletes, total);
    }

    /**
     * 弹窗问一句：同意就在后台线程执行真正的删除；不同意（或压根没界面弹）就跳过，一条不删。
     *
     * @param action 真正的删除动作，只在用户同意后才跑
     */
    public static void ask(int historyDel, int keepDel, Runnable action) {
        String sign = String.valueOf(historyDel + keepDel);
        // 刚才已经为同样的规模拒过一次，别每次同步都来烦人；过了 TTL 再问，真想删还有机会点
        if (sign.equals(declined) && System.currentTimeMillis() - declinedAt < DECLINE_TTL) return;
        App.post(() -> {
            Activity activity = App.activity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                skipped(historyDel, keepDel);
                return;
            }
            try {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.sync_guard_title)
                        .setMessage(ResUtil.getString(R.string.sync_guard_message, historyDel, keepDel))
                        .setNegativeButton(R.string.sync_guard_keep, (dialog, which) -> {
                            declined = sign;
                            declinedAt = System.currentTimeMillis();
                            skipped(historyDel, keepDel);
                        })
                        .setPositiveButton(R.string.sync_guard_delete, (dialog, which) -> {
                            approved = sign;
                            declined = null;
                            DebugLog.d("Sync", "用户确认批量删除：" + sign + " 条");
                            Task.execute(action);
                        })
                        .show();
            } catch (Exception e) {
                skipped(historyDel, keepDel); // 页面正在重建之类，这次就不弹了，数据保持不动
            }
        });
    }

    /** 这一趟跳过删除：明说一句，别让人以为同步没干活 */
    private static void skipped(int historyDel, int keepDel) {
        Notify.show(ResUtil.getString(R.string.sync_guard_skipped, historyDel + keepDel));
    }
}
