package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

/**
 * Gitee 发行仓：默认的版本检测与安装包下载通道（马先生 2026-10-31 定）。
 *
 * 仓库是公开的，里面只有各端的版本文件和打好包的安装包，不放源码：
 *   https://gitee.com/big_head_mk/liuguang-release
 *   master/leanback.json               版本文件（ moblie / leanback / leanback-legacy 各一份）
 *   releases/download/{tag}/{key}.apk  安装包（发行版附件）
 *
 * 两条注意实测出来的坑：
 *   1. 安装包一定不能走 raw —— Gitee 的 raw 对大文件会回一句
 *      「large file require login for access」，匿名取不到。
 *      发行版附件走的是独立 CDN（foruda.gitee.com），实测匿名直下没问题。
 *   2. 附件那条 CDN 不认 Range 请求（要 bytes=0-0 它会给 200 + 整包），
 *      所以这上面不支持断点续传，下到一半断了得从头再来；
 *      线路没断时 Download 自己那套「大小够不够、开头是不是 zip 头」的校验照旧做。
 */
public class Gitee {

    /** 线路名：在 Updater 里跟 GitHub 那几条线路区分开（跟自建源一样，都是直连） */
    public static final String ROUTE = "gitee://";

    public static final String OWNER = "big_head_mk";
    public static final String REPO = "liuguang-release";
    public static final String BRANCH = "master";

    private static final String RAW = "https://gitee.com/" + OWNER + "/" + REPO + "/raw/" + BRANCH + "/";
    private static final String RELEASE = "https://gitee.com/" + OWNER + "/" + REPO + "/releases/download/";

    private Gitee() {
    }

    public static boolean is(String route) {
        return ROUTE.equals(route);
    }

    /** 本机对应的版本文件：leanback / mobile，兼容版多一个 -legacy */
    public static String json() {
        String name = BuildConfig.FLAVOR_mode;
        return RAW + file(name) + ".json";
    }

    /**
     * 版本文件带时间戳再取。CDN 和 Gitee 自己都会缓存，
     * 不带这个参数改了更新说明还可能拿到旧的。
     */
    public static String jsonUrl() {
        return json() + "?_t=" + System.currentTimeMillis();
    }

    /** 安装包地址：发行版附件直链，匿名可下 */
    public static String apk(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        return RELEASE + tag + "/" + key() + ".apk";
    }

    private static String file(String name) {
        return BuildConfig.LEGACY ? name + "-legacy" : name;
    }

    /** 安装包文件名（不带后缀）：leanback-arm64_v8a / leanback-legacy-armeabi_v7a / mobile-arm64_v8a … */
    private static String key() {
        String name = BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi;
        return BuildConfig.LEGACY ? name.replaceFirst("-", "-legacy-") : name;
    }

    /** 打日志用 */
    public static String host() {
        return "gitee.com";
    }
}
