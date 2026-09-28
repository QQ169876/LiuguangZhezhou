package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

public class Github {

    /**
     * GitHub 加速代理。所有访问 GitHub 的地址都拼在它后面，
     * 国内网络直连 raw.githubusercontent.com / github.com 经常超时。
     */
    public static final String PROXY = "https://p.169876.us.kg/proxy/";

    public static final String REPO = "QQ169876/LiuguangZhezhou";
    public static final String BRANCH = "webdav-sync";

    private static final String RAW = "https://raw.githubusercontent.com/";
    private static final String RELEASE = "https://github.com/" + REPO + "/releases/download/";

    private static String raw(String path) {
        return PROXY + RAW + REPO + "/" + BRANCH + "/" + path;
    }

    public static String getJson(String name) {
        // 老盒子兼容版走独立的版本文件，避免被主线版本覆盖
        if (BuildConfig.LEGACY) name = name + "-legacy";
        return raw("apk/" + name + ".json");
    }

    public static String getApk(String tag, String name) {
        if (BuildConfig.LEGACY) name = name.replaceFirst("-", "-legacy-");
        return PROXY + RELEASE + tag + "/" + name + ".apk";
    }
}
