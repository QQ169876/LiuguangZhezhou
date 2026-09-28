package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

public class Github {

    public static final String REPO = "QQ169876/LiuguangZhezhou";
    public static final String BRANCH = "webdav-sync";

    private static final String RAW = "https://raw.githubusercontent.com/";
    private static final String RELEASE = "https://github.com/" + REPO + "/releases/download/";

    /**
     * 返回一个「干净」的 GitHub 直链，具体走哪条线路由 GhRoute 决定。
     * 顺序：直连 → 公益加速代理 → 本地 SOCKS5，谁快用谁。
     */
    public static String getJson(String name) {
        // 老盒子兼容版走独立的版本文件，避免被主线版本覆盖
        if (BuildConfig.LEGACY) name = name + "-legacy";
        return RAW + REPO + "/" + BRANCH + "/apk/" + name + ".json";
    }

    public static String getApk(String tag, String name) {
        if (BuildConfig.LEGACY) name = name.replaceFirst("-", "-legacy-");
        return RELEASE + tag + "/" + name + ".apk";
    }
}
