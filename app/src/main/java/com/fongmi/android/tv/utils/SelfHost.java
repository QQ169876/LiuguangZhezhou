package com.fongmi.android.tv.utils;

import android.net.Uri;

import com.fongmi.android.tv.BuildConfig;

import okhttp3.Credentials;

/**
 * 自建更新源：版本文件和安装包都放在马先生自己的网盘上。
 *
 * 为什么要有这一路：GitHub 那套要过公益加速代理，代理会缓存版本文件，
 * 刚发完版常常还拿到旧的（表现就是要点很多次才检测出来）。自己的网盘没有这个问题，
 * 而且下载快、不会哪天突然失效。GitHub 那套留着兜底：自建源不通就原样走老路。
 *
 * 网盘上的东西：
 *   /apk/流光褶皱/latest/leanback.json          版本文件（三端各一份）
 *   /apk/流光褶皱/主线版本/v5.6.70/…apk          安装包（归档时就传好了）
 *
 * 两个域名指向同一份网盘（马先生 2026-10-11 要求备份用）：二级域名一一对应，
 * 都是 www 前缀。主域名用不通就换下一个，换通了这次更新全程用它（版本文件和安装包都换）。
 * OkHttp 里那条 FailoverInterceptor 只在「连不上」时换，这里再兜一层：
 * 连得上但给了 403/5xx 这种答复的，也照样换下一个域名试。
 *
 * 注意：账号是写死在包里的（跟崩溃日志上传那份一个做法）。这是自用 App，
 * 网盘里放的是公开的安装包，泄露也没什么可损失的；真要换密码，改这里两个常量重新发版即可。
 */
public class SelfHost {

    /** 线路名：在 Updater 里跟 GitHub 那几条线路区分开 */
    public static final String ROUTE = "self://";

    /** 网盘域名：主用 169876.xyz，备 12356.cool（二级域名保持一致，都是 www） */
    private static final String[] HOSTS = {"https://www.169876.xyz/dav", "https://www.12356.cool/dav"};
    private static final String ROOT = "/apk/流光褶皱/";
    private static final String USER = "0007";
    private static final String PASS = "00003@";

    /** 当前在用的域名下标：哪个域名探通了就用哪个，下次还从它开始 */
    private static volatile int index;

    private SelfHost() {
    }

    public static boolean is(String route) {
        return ROUTE.equals(route);
    }

    public static int count() {
        return HOSTS.length;
    }

    /** 当前在用的域名下标 */
    public static int index() {
        return index;
    }

    /** 指定用第几个域名（探通了才调） */
    public static void use(int i) {
        if (i >= 0 && i < HOSTS.length) index = i;
    }

    /** 第 i 个域名（打日志用） */
    public static String host(int i) {
        return HOSTS[i];
    }

    /** 网盘上的路径转成能直接请求的地址（中文目录名要编码） */
    public static String url(String path) {
        return url(index, path);
    }

    public static String url(int i, String path) {
        return HOSTS[i] + Uri.encode(ROOT + path, "/");
    }

    /**
     * 把一段完整地址换成当前在用的域名。
     * 版本文件里带的安装包地址写的是主域名，备用域名探通时得跟着换过去，
     * 否则会出现「版本文件读到了、包还是从挂掉的那个域名下」的怪事。
     */
    public static String rewrite(String url) {
        if (url == null || url.isEmpty()) return url;
        String host = HOSTS[index];
        for (String item : HOSTS) {
            if (url.startsWith(item)) return host + url.substring(item.length());
        }
        return url;
    }

    /** 自己这台机器对应的版本文件 */
    public static String json() {
        return url("latest/" + file());
    }

    public static String json(int i) {
        return url(i, "latest/" + file());
    }

    /** 版本文件名：leanback / mobile，兼容版再加 -legacy */
    private static String file() {
        String name = BuildConfig.FLAVOR_mode;
        return BuildConfig.LEGACY ? name + "-legacy" : name;
    }

    /**
     * 版本文件里 apk 那份映射用的键，和 GitHub 上装包的命名保持一致：
     * leanback-arm64_v8a / leanback-legacy-armeabi_v7a / mobile-arm64_v8a …
     */
    public static String key() {
        String name = BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi;
        return BuildConfig.LEGACY ? name.replaceFirst("-", "-legacy-") : name;
    }

    public static String auth() {
        return Credentials.basic(USER, PASS);
    }
}
