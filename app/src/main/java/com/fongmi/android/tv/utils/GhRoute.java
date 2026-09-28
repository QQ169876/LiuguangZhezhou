package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 更新线路：直连优先，其次公益 GitHub 加速前缀，最后本地 SOCKS5 代理。
 * 「前缀式」加速的用法就是把完整的 GitHub 地址拼在域名后面。
 */
public class GhRoute {

    /** 自动模式哨兵 */
    public static final String AUTO = "auto://";
    /** 「自定义 SOCKS5」菜单哨兵 */
    public static final String CUSTOM = "custom://";
    /** 本地 SOCKS5 前缀，后面接 host:port */
    public static final String SOCKS5 = "socks5://";

    // 只保留实测能用的（能取到 raw 文件且能对 Release 做断点续传），挂掉的不再占位
    private static final String[] ACCEL = {
            "https://p.169876.us.kg/proxy/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://gh-proxy.org/",
    };

    public static List<String> accel() {
        return Arrays.asList(ACCEL);
    }

    public static boolean isDirect(String route) {
        return route == null || route.isEmpty();
    }

    public static boolean isSocks(String route) {
        return route != null && route.startsWith(SOCKS5);
    }

    public static boolean validSocks(String value) {
        if (value == null) return false;
        int index = value.lastIndexOf(':');
        if (index <= 0 || index >= value.length() - 1) return false;
        try {
            int port = Integer.parseInt(value.substring(index + 1).trim());
            return port > 0 && port < 65536;
        } catch (Exception e) {
            return false;
        }
    }

    public static String wrap(String route, String url) {
        if (isDirect(route) || isSocks(route)) return url;
        return route + url;
    }

    /**
     * 线路显示名：域名形式，语言无关
     */
    public static String host(String route) {
        if (isSocks(route)) return route.substring(SOCKS5.length());
        String host = route.replaceAll("^https?://", "");
        if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        return host;
    }

    /**
     * 用户手动锁定的线路，null 表示自动
     */
    public static String fixed() {
        if (Setting.isRouteAuto()) return null;
        String socks = Setting.getSocks();
        if (validSocks(socks)) return SOCKS5 + socks;
        return Setting.getRoute();
    }

    /**
     * 自动模式下的候选顺序：直连 → 公益加速 → 本地 SOCKS5
     */
    public static List<String> candidates() {
        List<String> result = new ArrayList<>();
        result.add("");
        for (String host : ACCEL) result.add(host);
        String socks = Setting.getSocks();
        if (validSocks(socks)) result.add(SOCKS5 + socks);
        return result;
    }

    /**
     * 探测用客户端：整体有超时，测不通立刻放弃
     */
    public static OkHttpClient probe(String route, int timeout) {
        OkHttpClient.Builder builder = OkHttp.client(timeout).newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS);
        if (isSocks(route)) builder.proxy(socks(route));
        return builder.build();
    }

    /**
     * 下载用客户端：复用全局的 DNS（含 DoH）与 SSL 设置，只放宽单次读写时间
     */
    public static OkHttpClient stream(String route) {
        OkHttpClient.Builder builder = OkHttp.client(TimeUnit.SECONDS.toMillis(60)).newBuilder();
        if (isSocks(route)) builder.proxy(socks(route));
        return builder.build();
    }

    private static Proxy socks(String route) {
        String host = route.substring(SOCKS5.length());
        String name = host.substring(0, host.lastIndexOf(':'));
        int port = Integer.parseInt(host.substring(host.lastIndexOf(':') + 1).trim());
        return new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(name, port));
    }
}
