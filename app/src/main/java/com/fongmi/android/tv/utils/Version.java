package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

/**
 * 版本号那一行的完整说明：是手机还是 TV、32 位还是 64 位、版本号、是不是兼容版。
 * 一台机器上可能装着好几个包，光看版本号分不清，所以一次说全。
 */
public class Version {

    private Version() {
    }

    public static String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("leanback".equals(BuildConfig.FLAVOR_mode) ? "TV" : "手机平板");
        builder.append(" · ");
        builder.append("arm64_v8a".equals(BuildConfig.FLAVOR_abi) ? "64位" : "32位");
        builder.append(" · ");
        builder.append(BuildConfig.VERSION_NAME);
        if (BuildConfig.LEGACY) builder.append(" · 兼容版");
        return builder.toString();
    }
}
