package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;

/**
 * 硬解自保：同一台设备 codec 初始化/查询反复失败，说明这颗硬解芯片（或它的固件）不靠谱，
 * 再每次起播都去撞硬解只会反复报错换源。累计两次就默认改走软解（ffmpeg 扩展渲染器），
 * 用户手动切回硬解即解除。标记是设备本地的（hwdec_ 前缀，见 WebDavData.localOnly），不同步。
 *
 * 只统计 INIT/QUERY 失败（codec 能力问题），DECODING_FAILED 不算——那多半是片源本身坏了。
 *
 * 2026-11-19 扩充（马先生）：小米这种 32 位老盒子硬解「能出画面但跟不上」，
 * 表现是持续掉帧 + 音画不同步，根本不会走到 INIT/QUERY 失败，守卫完全无感。
 * 于是再开一条判据：硬解下持续丢帧也记一次能力不足，累计两次同样默认软解。
 * 只认「持续丢帧」，网络慢造成的 BUFFERING 不丢帧、不会误判；且只在弱机启用，正常设备一点不动。
 */
public final class DecodeGuard {

    private static final String FAILS = "hwdec_fails";
    private static final String FORCED = "hwdec_soft_forced";
    private static final String LAG = "hwdec_lag";   // 硬解持续掉帧达标次数（同样只存本机）
    private static final int MAX_FAILS = 2;
    // 掉帧滑动窗口：5 秒内累计丢够这么多帧才算「持续跟不上」；单次丢几帧是起播/seek 的正常现象
    private static final long LAG_WINDOW_MS = 5000L;
    private static final int LAG_FRAMES = 90;

    private static long lagWindowStart;
    private static int lagWindowFrames;

    private DecodeGuard() {
    }

    /** codec 初始化/查询失败记一笔；连着撞两次就认定这台机器硬解不靠谱，默认软解 */
    public static void noteInitFail() {
        int n = Prefers.getInt(FAILS, 0) + 1;
        Prefers.put(FAILS, n);
        DebugLog.d("Decode", "硬解初始化失败累计 " + n + " 次");
        if (n >= MAX_FAILS && !softForced()) {
            Prefers.put(FORCED, true);
            Notify.show(R.string.decode_soft_forced);
        }
    }

    /**
     * 硬解下丢帧：滑动窗口内连着丢够一定量，说明这颗硬解码力跟不上码率（卡顿 + 音画不同步的根因）。
     * 网络慢只会 BUFFERING、不会丢帧，所以这条判据不会把网速问题误判成硬解不行。
     * 只在弱机 + 硬解时统计，正常设备直接返回。
     *
     * @param dropped 本次回调丢的帧数
     */
    public static synchronized void noteDroppedFrames(int dropped) {
        if (!isWeakDevice() || softForced() || dropped <= 0) return;
        long now = System.currentTimeMillis();
        if (lagWindowStart == 0 || now - lagWindowStart > LAG_WINDOW_MS) {
            lagWindowStart = now;
            lagWindowFrames = 0;
        }
        lagWindowFrames += dropped;
        if (lagWindowFrames < LAG_FRAMES) return;
        lagWindowStart = 0;
        lagWindowFrames = 0;
        int n = Prefers.getInt(LAG, 0) + 1;
        Prefers.put(LAG, n);
        DebugLog.d("Decode", "弱机硬解持续掉帧，累计 " + n + " 次");
        if (n >= MAX_FAILS) {
            Prefers.put(FORCED, true);
            Notify.show(R.string.decode_soft_forced);
            DebugLog.d("Decode", "硬解跟不上导致卡顿/音画不同步，本机改走软解");
        }
    }

    /** 起播/换源时清掉滑动窗口，别把上一部的丢帧算到这一部 */
    public static synchronized void clearLagWindow() {
        lagWindowStart = 0;
        lagWindowFrames = 0;
    }

    /** 弱机：32 位包就是 32 位 CPU 的电视盒子（模拟器/现代手机都是 64 位包），硬解普遍偏弱 */
    public static boolean isWeakDevice() {
        return !"arm64_v8a".equals(com.fongmi.android.tv.BuildConfig.FLAVOR_abi);
    }

    /** 起播成功一次就清零：上次失败多半是片源的事，不是机器不行 */
    public static void noteReady() {
        if (Prefers.getInt(FAILS, 0) > 0) Prefers.put(FAILS, 0);
    }

    /** 这台设备是否默认走软解 */
    public static boolean softForced() {
        return Prefers.getBoolean(FORCED);
    }

    /** 用户手动切回硬解 = 解除强制并清零（掉帧那条账也一起清，别残留旧计数） */
    public static void unforce() {
        Prefers.put(FORCED, false);
        Prefers.put(FAILS, 0);
        Prefers.put(LAG, 0);
    }
}
