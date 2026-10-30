package com.fongmi.android.tv.cast;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中转地址的「短牌」。
 *
 * 中转地址是把源地址 + 请求头都拼在网址里的（见 {@code CastVideo#relayUrl()}），
 * 带上签名、防盗链的源，拼出来轻轻松松超过 UDP 包的上限 1400 字节 ——
 * 一超就整包发不出去，接收端拿不到地址，整套同步直接哑火。
 *
 * 所以这里给它发一块短牌：地址留在发送端本机，包里只走 {@code /cast?id=3} 这么短一段。
 * 值只在内存里，**关掉广播就失效**，不落盘。
 */
public final class CastRelay {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Map<String, String> URLS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> HEADERS = new ConcurrentHashMap<>();

    private CastRelay() {
    }

    /** 存下一份地址，拿到号码 */
    public static String put(String url, Map<String, String> headers) {
        String id = String.valueOf(SEQ.incrementAndGet());
        URLS.put(id, url == null ? "" : url);
        HEADERS.put(id, headers == null ? Collections.emptyMap() : new java.util.LinkedHashMap<>(headers));
        return id;
    }

    public static String url(String id) {
        String value = id == null ? null : URLS.get(id);
        return value == null ? "" : value;
    }

    public static Map<String, String> headers(String id) {
        Map<String, String> value = id == null ? null : HEADERS.get(id);
        return value == null ? Collections.emptyMap() : value;
    }
}
