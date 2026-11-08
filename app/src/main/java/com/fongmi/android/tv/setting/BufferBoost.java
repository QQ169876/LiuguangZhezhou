package com.fongmi.android.tv.setting;

/**
 * 播放设置选「自动」时的缓冲微调（马先生 2026-11-09）。
 *
 * 只活在内存里，绝不写回 Prefers：用户设的缓冲时间原样不动。
 * 真实卡顿累计到 2 次，就只给「当前这个视频」临时加大一档缓冲；
 * 换了视频（URL 变了）或退出播放页自动清零，不影响下一部。
 */
public final class BufferBoost {

    private static final int STALLS_PER_STEP = 2;
    private static final int STEP = 2;
    private static final int MAX_BONUS = 6;

    private static String currentUrl = "";
    private static int stalls;
    private static int bonus;

    private BufferBoost() {
    }

    /** 每次起播都过一遍：URL 变了才算换了视频；同一视频重建播放会话时不归零 */
    public static synchronized void begin(String url) {
        String key = url == null ? "" : url;
        if (key.equals(currentUrl)) return;
        currentUrl = key;
        stalls = 0;
        bonus = 0;
    }

    /** 一次真实卡顿（READY → BUFFERING）。返回 true 表示这次该抬一档缓冲并重建播放会话 */
    public static synchronized boolean onStall() {
        if (!PlayerSetting.isBufferAuto() || bonus >= MAX_BONUS) return false;
        if (++stalls < STALLS_PER_STEP) return false;
        stalls = 0;
        bonus = Math.min(bonus + STEP, MAX_BONUS);
        return true;
    }

    public static synchronized int bonus() {
        return PlayerSetting.isBufferAuto() ? bonus : 0;
    }

    /** 退出播放页时归零 */
    public static synchronized void reset() {
        currentUrl = "";
        stalls = 0;
        bonus = 0;
    }
}
