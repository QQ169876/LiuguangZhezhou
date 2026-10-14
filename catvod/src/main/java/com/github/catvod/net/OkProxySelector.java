package com.github.catvod.net;
import java.util.ArrayList;
import java.util.Collections;

import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class OkProxySelector extends ProxySelector {

    private final List<Proxy> proxy;
    private final ProxySelector system;
    private volatile Proxy global;
    private boolean authSet;

    public OkProxySelector() {
        proxy = new CopyOnWriteArrayList<>();
        system = ProxySelector.getDefault();
        Authenticator.setDefault(new ProxyAuthenticator(this));
    }

    public synchronized void addAll(List<Proxy> items) {
        if (items.isEmpty()) return;
        items.forEach(Proxy::init);
        proxy.addAll(items);
        proxy.sort(null);
    }

    /** 全局代理：匹配所有域名，传 null 表示取消 */
    public synchronized void setGlobal(Proxy item) {
        if (global != null) proxy.remove(global);
        global = null;
        if (item == null) return;
        item.init();
        if (item.getProxies().isEmpty()) return;
        global = item;
        proxy.add(item);
    }

    public synchronized void clear() {
        Authenticator.setDefault(null);
        proxy.clear();
        global = null;
    }

    public List<Proxy> getProxy() {
        return proxy;
    }

    private List<java.net.Proxy> fallback(URI uri) {
        return system != null ? system.select(uri) : Collections.singletonList(java.net.Proxy.NO_PROXY);
    }

    @Override
    public List<java.net.Proxy> select(URI uri) {
        String host = uri.getHost();
        if (proxy.isEmpty() || host == null || isLocal(host)) return fallback(uri);
        for (Proxy item : proxy) {
            if (item.getProxies().isEmpty()) continue;
            for (String pattern : item.getHosts()) {
                if (!match(host, pattern)) continue;
                // 通配代理不该连局域网：本机服务、局域网推送这些走代理只会连不上
                if (isWildcard(pattern) && isPrivate(host)) return fallback(uri);
                return item.getProxies();
            }
        }
        return fallback(uri);
    }

    /**
     * ⭐ 原来直接拿 `Util.containOrMatch(host, host规则)` 匹配，而规则写的是 `*` ——
     * `"*"` 不是合法正则（"Dangling meta character"），`matches()` 抛异常被吞掉，
     * `contains("*")` 又是 false → **通配代理永远匹配不上任何域名**，
     * 「全局走自定义 SOCKS5」那个开关勾了等于没勾（一个请求都没走代理）。
     * 所以 `*` 单独处理成"匹配所有"，带星号的按通配转成正则。
     */
    private static boolean match(String host, String pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        String rule = pattern.trim();
        if ("*".equals(rule)) return true;
        if (rule.indexOf('*') >= 0) {
            // 规则里可能夹着 [ + ( 这类正则元字符，matches() 会抛 PatternSyntaxException，
            // 这里必须自己吞掉 —— 站源给的域名规则千奇百怪，不能因为一条烂规则就把请求打崩
            try {
                return host.matches(rule.replace(".", "\\.").replace("*", ".*"));
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return Util.containOrMatch(host, rule);
    }

    private static boolean isWildcard(String pattern) {
        return pattern != null && pattern.indexOf('*') >= 0;
    }

    /** 本机/局域网地址，不该被代理（127、10、192.168、172.16~31、localhost、*.local） */
    private static boolean isLocal(String host) {
        return isPrivate(host) || "::1".equals(host);
    }

    private static boolean isPrivate(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(value) || value.endsWith(".local")) return true;
        if (value.startsWith("127.") || value.startsWith("10.") || value.startsWith("192.168.")) return true;
        if (value.startsWith("172.")) {
            String[] parts = value.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
        if (system != null) system.connectFailed(uri, socketAddress, e);
    }
}
