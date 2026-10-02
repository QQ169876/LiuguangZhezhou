package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;

/**
 * 硬解自保：同一台设备 codec 初始化/查询反复失败，说明这颗硬解芯片（或它的固件）不靠谱，
 * 再每次起播都去撞硬解只会反复报错换源。累计两次就默认改走软解（ffmpeg 扩展渲染器），
 * 用户手动切回硬解即解除。标记是设备本地的（hwdec_ 前缀，见 WebDavData.localOnly），不同步。
 *
 * 只统计 INIT/QUERY 失败（codec 能力问题），DECODING_FAILED 不算——那多半是片源本身坏了。
 */
public final class DecodeGuard {

    private static final String FAILS = "hwdec_fails";
    private static final String FORCED = "hwdec_soft_forced";

    private DecodeGuard() {
    }

    /** codec 初始化/查询失败记一笔；连着撞两次就认定这台机器硬解不靠谱，默认软解 */
    public static void noteInitFail() {
        int n = Prefers.getInt(FAILS, 0) + 1;
        Prefers.put(FAILS, n);
        DebugLog.d("Decode", "硬解初始化失败累计 " + n + " 次");
        if (n >= 2 && !softForced()) {
            Prefers.put(FORCED, true);
            Notify.show(R.string.decode_soft_forced);
        }
    }

    /** 起播成功一次就清零：上次失败多半是片源的事，不是机器不行 */
    public static void noteReady() {
        if (Prefers.getInt(FAILS, 0) > 0) Prefers.put(FAILS, 0);
    }

    /** 这台设备是否默认走软解 */
    public static boolean softForced() {
        return Prefers.getBoolean(FORCED);
    }

    /** 用户手动切回硬解 = 解除强制并清零 */
    public static void unforce() {
        Prefers.put(FORCED, false);
        Prefers.put(FAILS, 0);
    }
}
