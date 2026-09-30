package com.fongmi.android.tv.utils;

import android.os.Build;

import com.fongmi.android.tv.BuildConfig;

/**
 * 版本号那一行的完整说明：跑在什么系统上、32 位还是 64 位、版本号、是不是兼容版。
 * 一台机器上可能装着好几个包，光看版本号分不清，所以一次说全。
 */
public class Version {

    private Version() {
    }

    /**
     * 是不是华为鸿蒙。鸿蒙不只有手机，平板、智慧屏也都是，所以只认系统不认机型。
     */
    public static boolean isHarmony() {
        if (match(Build.DISPLAY)) return true;
        if (match(Build.VERSION.INCREMENTAL)) return true;
        if (match(System.getProperty("os.name"))) return true;
        try {
            Class<?> clz = Class.forName("com.huawei.system.BuildEx");
            Object brand = clz.getMethod("getOsBrand").invoke(clz);
            return "harmony".equalsIgnoreCase(String.valueOf(brand));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean match(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("harmony") || lower.contains("鸿蒙") || lower.contains("ohos");
    }

    public static String summary() {
        boolean tv = "leanback".equals(BuildConfig.FLAVOR_mode);
        boolean harmony = isHarmony();
        StringBuilder builder = new StringBuilder();
        if (tv) builder.append(harmony ? "鸿蒙智慧屏" : "TV");
        else builder.append(harmony ? "鸿蒙" : "手机平板");
        builder.append(" · ");
        builder.append("arm64_v8a".equals(BuildConfig.FLAVOR_abi) ? "64位" : "32位");
        builder.append(" · ");
        builder.append(BuildConfig.VERSION_NAME);
        if (BuildConfig.LEGACY) builder.append(" · 兼容版");
        return builder.toString();
    }
}
