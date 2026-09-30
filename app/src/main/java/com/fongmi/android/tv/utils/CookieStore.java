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
    private static final int LIMIT = 64;
    private static final AtomicBoolean restored = new AtomicBoolean(false);

    private CookieStore() {
    }

    /** 交给同步上传：顺手用系统 WebView 里最新的值刷新一遍 */
    public static Map<String, String> all() {
        if (!WebDavSetting.isCookie()) return new LinkedHashMap<>();
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

    /** 局域网推送出去：不看同步开关，本机记过的就推 */
    public static Map<String, String> snapshot() {
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
