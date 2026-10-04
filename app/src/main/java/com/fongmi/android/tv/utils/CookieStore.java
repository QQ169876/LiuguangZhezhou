package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.os.Looper;
import android.webkit.CookieManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网盘、影视站扫码登录后留下的 Cookie 都存在系统 WebView 里，换台设备就没了，又得重新扫一次。
 * 这里按域名把 Cookie 抄一份出来放进同步数据，到别的设备上再写回 WebView，
 * 这样「扫一次码，几台设备都能用」。
 *
 * 只记真正带内容的域名，不搬整个 Cookie 库；超过 64 个域名就不再新增，免得同步文件越滚越大。
 */
public class CookieStore {

    private static final String KEY = "webdav_cookie_jar";
    /** 白名单有二十来个域名，上限放宽一点，免得捞到的网盘登录态挤不进来 */
    private static final int LIMIT = 96;
    private static final AtomicBoolean restored = new AtomicBoolean(false);

    /**
     * 要主动去捞的站点：主流网盘 + B 站。
     *
     * 这些站点的扫码登录都是视频源（jar）自己干的，它不跟 App 打招呼，
     * 只能按这份名单去系统 WebView 里挨个问：这家的登录态留下了吗。
     * 有新的站点要支持，往这里加域名就行。
     */
    private static final String[] HOSTS = {
            // bilibili：登录态（SESSDATA 等）写在 .bilibili.com 父域，主域和各子域都问一遍
            "bilibili.com", "www.bilibili.com", "passport.bilibili.com", "account.bilibili.com",
            "api.bilibili.com", "m.bilibili.com", "live.bilibili.com", "space.bilibili.com",
            // 网盘
            "pan.baidu.com", "passport.baidu.com", "yun.baidu.com", "baidu.com",
            "pan.quark.cn", "quark.cn",
            "drive.uc.cn", "uc.cn",
            "www.aliyundrive.com", "aliyundrive.com",
            "pan.115.com", "115.com",
            "pan.xunlei.com", "xunlei.com",
            "cloud.189.cn", "189.cn",
            "yun.139.com", "caiyun.139.com", "139.com",
            "www.123pan.com", "123pan.com", "vip.123pan.cn", "123pan.cn",
            "www.jianguoyun.com", "jianguoyun.com",
            "pan.pikpak.com", "mypikpak.com", "pikpak.com",
            "www.terabox.com", "terabox.com",
            "pan.hao123.com",
    };

    private CookieStore() {
    }

    /**
     * 主动捞一遍网盘 / B 站这类站点的登录态。
     *
     * 这些站点的扫码登录不是 App 的活儿，是视频源（jar）干的：它要么自己开网页，要么直接往系统 WebView 里
     * 写 Cookie，从头到尾不会通知 App 一声。而 CookieStore 只会记"有人告诉过它"的域名，
     * 于是这些登录态一个都没记下来 —— 同步没东西可传，局域网推送也推了个空，表现就是"扫完码换台设备还得重扫"。
     *
     * 这里按白名单挨个去系统 WebView 里问一次，捞到就记下来，后面的同步和推送自然就带上了。
     */
    private static void harvest() {
        try {
            Map<String, String> map = read();
            boolean dirty = false;
            for (String host : HOSTS) {
                if (map.containsKey(host)) continue; // 记过的交给 all()/snapshot() 去刷新
                String value = get(url(host));
                if (value.isEmpty()) continue;
                if (map.size() >= LIMIT) break;
                map.put(host, value);
                dirty = true;
                DebugLog.d("Cookie", "捞到站点登录态 host=" + host + " len=" + value.length());
            }
            if (dirty) write(map);
        } catch (Throwable ignored) {
        }
    }

    /** 交给同步上传：先把网盘登录态捞一遍，再用系统 WebView 里最新的值刷新已记录的 */
    public static Map<String, String> all() {
        if (!WebDavSetting.isCookie()) return new LinkedHashMap<>();
        harvest();
        Map<String, String> map = read();
        boolean dirty = false;
        for (String host : new ArrayList<>(map.keySet())) {
            String value = get(url(host));
            if (value.isEmpty() || value.equals(map.get(host))) continue;
            map.put(host, value);
            dirty = true;
        }
        if (dirty) write(map);
        return map;
    }

    /** 把系统 WebView 里这个地址现在的 Cookie 抄一份，返回是否有变化 */
    public static boolean capture(String url) {
        return capture(url, get(url));
    }

    public static boolean capture(String url, String cookies) {
        return put(url, cookies);
    }

    public static boolean put(String url, String cookies) {
        if (!WebDavSetting.isCookie()) return false;
        String host = host(url);
        if (host.isEmpty() || cookies == null || cookies.trim().isEmpty()) return false;
        Map<String, String> map = read();
        if (cookies.trim().equals(map.get(host))) return false;
        if (!map.containsKey(host) && map.size() >= LIMIT) return false;
        map.put(host, cookies.trim());
        write(map);
        return true;
    }

    /** 局域网推送出去：不看同步开关，本机有的就推（同样先把网盘登录态捞一遍） */
    public static Map<String, String> snapshot() {
        harvest();
        Map<String, String> map = read();
        boolean dirty = false;
        for (String host : new ArrayList<>(map.keySet())) {
            String value = get(url(host));
            if (value.isEmpty() || value.equals(map.get(host))) continue;
            map.put(host, value);
            dirty = true;
        }
        if (dirty) write(map);
        return map;
    }

    /** 同步拿回来的 Cookie：记下来，同时写回系统 WebView，下次请求直接带上 */
    public static void apply(Map<String, String> cookies) {
        if (!WebDavSetting.isCookie()) return;
        merge(cookies);
    }

    /** 合并一份 Cookie 进来（局域网推送收到时用，不看开关） */
    public static void merge(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return;
        Map<String, String> map = read();
        Map<String, String> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            String host = key(entry.getKey());
            String value = entry.getValue();
            if (host.isEmpty() || value == null || value.trim().isEmpty()) continue;
            if (value.trim().equals(map.get(host))) continue;
            if (!map.containsKey(host) && map.size() >= LIMIT) continue;
            map.put(host, value.trim());
            changed.put(host, value.trim());
        }
        if (changed.isEmpty()) return;
        write(map);
        push(changed);
    }

    /** 本机记过的 Cookie 重新灌回 WebView（每个进程只灌一次，之后靠 apply 增量补） */
    public static void restore() {
        if (!WebDavSetting.isCookie()) return;
        if (!restored.compareAndSet(false, true)) return;
        push(read());
    }

    /** 清除数据：连 WebView 里的登录态一起清掉，回到刚装好的状态 */
    public static void clear() {
        Prefers.remove(KEY);
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.removeAllCookie();
            manager.flush();
        } catch (Throwable ignored) {
        }
    }

    public static int size() {
        return read().size();
    }

    private static void push(Map<String, String> map) {
        if (map.isEmpty()) return;
        Runnable task = () -> {
            try {
                CookieManager manager = CookieManager.getInstance();
                manager.setAcceptCookie(true);
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String url = url(key(entry.getKey()));
                    if (url.isEmpty()) continue;
                    for (String item : entry.getValue().split(";")) {
                        String cookie = item.trim();
                        if (!cookie.isEmpty()) manager.setCookie(url, cookie);
                    }
                }
                manager.flush();
            } catch (Throwable ignored) {
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else App.post(task);
    }

    private static String get(String url) {
        if (url.isEmpty()) return "";
        try {
            String value = CookieManager.getInstance().getCookie(url);
            return value == null ? "" : value.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static Map<String, String> read() {
        Map<String, String> map = new LinkedHashMap<>();
        String json = Prefers.getString(KEY);
        if (json.isEmpty()) return map;
        try {
            Type type = TypeToken.getParameterized(LinkedHashMap.class, String.class, String.class).getType();
            LinkedHashMap<String, String> data = App.gson().fromJson(json, type);
            if (data != null) map.putAll(data);
        } catch (Exception ignored) {
        }
        return map;
    }

    private static void write(Map<String, String> map) {
        Prefers.put(KEY, App.gson().toJson(map));
    }

    private static String host(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private static String url(String host) {
        return host.isEmpty() ? "" : "https://".concat(host).concat("/");
    }

    /** 存进来的键一律是域名；万一带了协议头，也给它剥出来 */
    private static String key(String value) {
        if (value == null) return "";
        String host = value.trim().toLowerCase();
        if (host.contains("://")) host = host(host);
        return host;
    }
}
