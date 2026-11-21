package com.fongmi.android.tv.utils;

/**
 * 原先的「硬解自保」已在 5.6.185 移除：软硬解只认播放页的手动选择，
 * 不再根据 codec 初始化失败、持续丢帧累计自动改默认解码方式，也不再报错后自动来回切。
 * 这里只保留弱机判定，供音频直通的默认值使用（与解码方式的选择无关）。
 */
public final class DecodeGuard {

    private DecodeGuard() {
    }

    /** 弱机：32 位包就是 32 位 CPU 的电视盒子（模拟器/现代手机都是 64 位包），硬解普遍偏弱 */
    public static boolean isWeakDevice() {
        return !"arm64_v8a".equals(com.fongmi.android.tv.BuildConfig.FLAVOR_abi);
    }
}
