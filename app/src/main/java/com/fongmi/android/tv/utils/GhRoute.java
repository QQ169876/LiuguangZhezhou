package com.fongmi.android.tv.utils;
import java.util.Collections;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 更新线路：直连优先，其次公益 GitHub 加速前缀，最后本地 SOCKS5 代理。
 * 「前缀式」加速的用法就是把完整的 GitHub 地址拼在域名后面。
 * SOCKS5 的保存格式：[用户名:密码@]地址:端口，免认证的只填地址端口。
 */
public class GhRoute {

    /** 自动模式哨兵 */
    public static final String AUTO = "auto://";
    /** 「自定义 SOCKS5」菜单哨兵 */
    public static final String CUSTOM = "custom://";
    /** 本地 SOCKS5 前缀 */
    public static final String SOCKS5 = "socks5://";

    // 只保留实测能用的（能取到 raw 文件且能对 Release 做断点续传），挂掉的不再占位
    private static final String[] ACCEL = {
            "https://p.169876.us.kg/proxy/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://gh-proxy.org/",
    };

    static {
        // SOCKS5 的用户名密码由 java.net.Authenticator 提供，只认我们配的那台代理
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (!"SOCKS5".equalsIgnoreCase(getRequestingScheme())) return null;
                String socks = Setting.getSocks();
                if (!validSocks(socks)) return null;
                String user = user(socks);
                if (user.isEmpty()) return null;
                String host = getRequestingHost();
                if (host != null && !host.equalsIgnoreCase(socksHost(socks))) return null;
                return new PasswordAuthentication(user, pass(socks).toCharArray());
            }
        });
    }

    /** 内置公益加速（不可删除） + 用户自己存的自定义代理（可删除） */
    public static List<String> accel() {
        List<String> result = new ArrayList<>(Arrays.asList(ACCEL));
        for (String socks : Setting.getRouteCustoms()) if (validSocks(socks)) result.add(SOCKS5 + socks);
        return result;
    }

    public static void removeCustom(String route) {
        if (!isSocks(route)) return;
        Setting.removeRouteCustom(route.substring(SOCKS5.length()));
    }

    public static boolean isDirect(String route) {
        return route == null || route.isEmpty();
    }

    public static boolean isSocks(String route) {
        return route != null && route.startsWith(SOCKS5);
    }

    /** 用地址、端口、账号拼出存储格式 */
    public static String socks(String host, String port, String user, String pass) {
        String h = host == null ? "" : host.trim();
        String p = port == null ? "" : port.trim();
        String u = user == null ? "" : user.trim();
        String w = pass == null ? "" : pass.trim();
        StringBuilder sb = new StringBuilder();
        if (!u.isEmpty()) sb.append(u).append(':').append(w).append('@');
        return sb.append(h).append(':').append(p).toString();
    }

    /** 粘贴进来的一整串 socks5://user:pwd@host:port 转成存储格式 */
    public static String parse(String text) {
        String value = text == null ? "" : text.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value;
    }

    /** 去掉账号部分，得到 地址:端口 */
    public static String address(String socks) {
        String value = socks == null ? "" : socks.trim();
        int at = value.lastIndexOf('@');
        return at < 0 ? value : value.substring(at + 1);
    }

    /** 只要地址，不带端口 */
    public static String socksHost(String socks) {
        String address = address(socks);
        int index = address.lastIndexOf(':');
        return index <= 0 ? address : address.substring(0, index);
    }

    public static int port(String socks) {
        try {
            String address = address(socks);
            return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static String user(String socks) {
        String value = socks == null ? "" : socks.trim();
        int at = value.lastIndexOf('@');
        if (at < 0) return "";
        String info = value.substring(0, at);
        int colon = info.indexOf(':');
        return colon < 0 ? info : info.substring(0, colon);
    }

    public static String pass(String socks) {
        String value = socks == null ? "" : socks.trim();
        int at = value.lastIndexOf('@');
        if (at < 0) return "";
        String info = value.substring(0, at);
        int colon = info.indexOf(':');
        return colon < 0 ? "" : info.substring(colon + 1);
    }

    public static boolean validSocks(String value) {
        if (value == null) return false;
        String address = address(value);
        int index = address.lastIndexOf(':');
        if (index <= 0 || index >= address.length() - 1) return false;
        if (socksHost(value).trim().isEmpty()) return false;
        int port = port(value);
        return port > 0 && port < 65536;
    }

    public static String wrap(String route, String url) {
        if (isDirect(route) || isSocks(route)) return url;
        return route + url;
    }

    /**
     * 线路显示名：加速源显示域名，SOCKS5 显示 地址:端口（不泄露账号）
     */
    public static String host(String route) {
        if (isSocks(route)) return address(route.substring(SOCKS5.length()));
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
        return String.valueOf(Setting.getRoute());
    }

    /**
     * 自动模式下的候选顺序：直连 → 公益加速 → 本地 SOCKS5
     */
    public static List<String> candidates() {
        List<String> result = new ArrayList<>();
        result.add("");
        for (String host : ACCEL) result.add(host);
        for (String socks : Setting.getRouteCustoms()) if (validSocks(socks)) result.add(SOCKS5 + socks);
        String socks = Setting.getSocks();
        if (validSocks(socks)) result.add(SOCKS5 + socks);
        return result;
    }

    /**
     * 隐藏开关打开时：App 内所有请求（点播、直播、图片、同步…）都走这台 SOCKS5
     */
    public static void applyGlobal() {
        OkHttp.selector().setGlobal(Setting.isIPv6() ? wildcard() : null);
    }

    /** 当前这台代理，没有就退到自定义列表里第一台能用的 */
    private static com.github.catvod.bean.Proxy wildcard() {
        String socks = Setting.getSocks();
        if (!validSocks(socks)) for (String item : Setting.getRouteCustoms()) if (validSocks(item)) {
            socks = item;
            break;
        }
        if (!validSocks(socks)) return null;
        com.github.catvod.bean.Proxy proxy = new com.github.catvod.bean.Proxy();
        proxy.setHosts(Collections.singletonList("*"));
        proxy.setUrls(Collections.singletonList("socks://" + socks));
        return proxy;
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
        String socks = route.substring(SOCKS5.length());
        return new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(socksHost(socks), port(socks)));
    }
}
