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
 * 注意：账号是写死在包里的（跟崩溃日志上传那份一个做法）。这是自用 App，
 * 网盘里放的是公开的安装包，泄露也没什么可损失的；真要换密码，改这里两个常量重新发版即可。
 */
public class SelfHost {

    /** 线路名：在 Updater 里跟 GitHub 那几条线路区分开 */
    public static final String ROUTE = "self://";

    private static final String HOST = "https://www.169876.xyz/dav";
    private static final String ROOT = "/apk/流光褶皱/";
    private static final String USER = "0007";
    private static final String PASS = "00003@";

    private SelfHost() {
    }

    public static boolean is(String route) {
        return ROUTE.equals(route);
    }

    /** 网盘上的路径转成能直接请求的地址（中文目录名要编码） */
    public static String url(String path) {
        return HOST + Uri.encode(ROOT + path, "/");
    }

    /** 自己这台机器对应的版本文件 */
    public static String json() {
        return url("latest/" + file());
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
