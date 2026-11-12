package com.fongmi.android.tv.sync;

import android.app.Activity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
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
 *
 * ⚠️ 2026-11-12 补的一条（马先生）：**换过同步账号之后，从云端覆盖本机**会把上一个账号的
 * 观看记录 / 收藏 / 关注整批换掉，光看「本机少了一批」就会误判成同账号下批量删除，弹出确认框。
 * 换号是用户刚在「以哪边为准」里点过头的正常操作，不该再问一遍「是你在 App 里删的吗」。
 * 所以两条通道各自记一个「刚切换账号」标记（见 onAccountChanged），首轮真正要弹窗时豁免一次。
 * 豁免是一次性的、带 30 分钟时限，之后同账号下的误删照旧拦 —— 别把它做成永久开关。
 */
public final class SyncGuard {

    /** 一次要删这么多条以上就算批量，得先问 */
    private static final int MIN_COUNT = 10;

    /** 库本身不大时（比如一共就十几条），一次删掉一半以上同样可疑 */
    private static final int MIN_TOTAL = 6;

    /** 拒过之后隔多久才再问一次：太短会烦人，永久不问的话用户后来真想删就没机会点了 */
    private static final long DECLINE_TTL = 30 * 60 * 1000L;

    /** 「刚切换同步账号」标记，按通道（WebDAV / 影视站）各存一份；值 = 新账号 scope|时间戳 */
    private static final String PREF_SWITCH = "sync_guard_switch_";

    /** 换号标记的时效：只罩住切换后那一两轮同步，过期自动恢复防误删 */
    private static final long SWITCH_TTL = 30 * 60 * 1000L;

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
     * 同步目标（同步标识 / 账号 / 站点网址）换过、用户也选完「以哪边为准」时调一次。
     *
     * 接下来用云端覆盖本机，上一个账号的记录 / 收藏 / 关注整批换掉是换号的正常结果，
     * 不是在同一个账号里批量删了数据，所以首轮的批量删除确认要豁免（马先生 2026-11-12）。
     * 两条通道各记各的，WebDAV 换号不影响影视站的防误删。
     *
     * @param channel Owner.DAV / Owner.MOON
     * @param scope   切换后的新账号身份（由调用方给，免得 Setting 反过来依赖 Owner）
     */
    public static void onAccountChanged(int channel, String scope) {
        Prefers.put(PREF_SWITCH + channel, scope.concat("|").concat(String.valueOf(System.currentTimeMillis())));
        DebugLog.d("Sync", "同步账号已切换，首轮批量删除确认豁免");
    }

    /**
     * 这一趟批量删除是否属于「刚换号」。命中即消费 —— 只豁免切换后的第一次判断，
     * 之后同账号下真误删照样拦。期间又换过号、或超过时效，标记作废。
     */
    private static boolean skipForSwitch(int channel) {
        String key = PREF_SWITCH + channel;
        String raw = Prefers.getString(key);
        if (raw.isEmpty()) return false;
        Prefers.put(key, ""); // 一次性：读到就消费，别留着把以后的误删也放过去
        int bar = raw.lastIndexOf('|');
        if (bar <= 0) return false;
        long at;
        try {
            at = Long.parseLong(raw.substring(bar + 1));
        } catch (Exception ignored) {
            return false;
        }
        return raw.substring(0, bar).equals(Owner.scope(channel))
                && System.currentTimeMillis() - at < SWITCH_TTL;
    }

    /**
     * 这笔删除能不能直接做（true = 照做、不弹窗）。用户刚刚对同样规模点过「仍然删除」的照做不再问；
     * 数量没到批量阈值也照做；真到阈值、且是刚切换同步账号造成的整批变化，也照做 —— 不打扰。
     */
    public static synchronized boolean allow(int deletes, int total, int channel) {
        if (approved != null && approved.equals(String.valueOf(deletes))) {
            approved = null;
            return true;
        }
        if (!risky(deletes, total)) return true;
        if (skipForSwitch(channel)) {
            DebugLog.d("Sync", "刚切换同步账号：整批差异属换号正常逻辑，跳过批量删除确认");
            return true;
        }
        return false;
    }

    /**
     * 弹窗问一句：同意就在后台线程执行真正的删除；不同意（或压根没界面弹）就跳过，一条不删。
     *
     * @param action 真正的删除动作，只在用户同意后才跑
     */
    public static void ask(int historyDel, int keepDel, Runnable action) {
        ask(historyDel, keepDel, action, null);
    }

    /**
     * 同上，多一个 onPass：用户拒绝 / 没界面可弹 / 30 分钟内拒过 —— 这几种「不删」的情况都回调它。
     * WebDAV 的守卫要靠它落闸：调用方在后台线程上等这个答案，拿到「不删」才知道要把那批救回来。
     */
    public static void ask(int historyDel, int keepDel, Runnable action, Runnable onPass) {
        String sign = String.valueOf(historyDel + keepDel);
        // 刚才已经为同样的规模拒过一次，别每次同步都来烦人；过了 TTL 再问，真想删还有机会点
        if (sign.equals(declined) && System.currentTimeMillis() - declinedAt < DECLINE_TTL) {
            if (onPass != null) onPass.run();
            return;
        }
        App.post(() -> {
            Activity activity = App.activity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                if (onPass != null) onPass.run();
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
                            if (onPass != null) onPass.run();
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
                if (onPass != null) onPass.run();
                skipped(historyDel, keepDel); // 页面正在重建之类，这次就不弹了，数据保持不动
            }
        });
    }

    /** 这一趟跳过删除：明说一句，别让人以为同步没干活 */
    private static void skipped(int historyDel, int keepDel) {
        Notify.show(ResUtil.getString(R.string.sync_guard_skipped, historyDel + keepDel));
    }
}
