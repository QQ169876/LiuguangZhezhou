package com.github.catvod.net.interceptor;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 隐性备用域名（界面上不显示，用户也不需要知道）。
 *
 * 同一批服务挂在两个域名下：169876.us.kg / 169876.xyz 和 12356.cool，
 * 子域前缀是一一对应的（比如 ys.169876.us.kg 对应 ys.12356.cool）。
 * 主域名连不上时，静默换成同前缀的另一个域名再试一次，通了就照常用；
 * 通不了就跟以前一样把错误抛上去。对上层所有请求生效：更新、点播源、订阅、
 * 影视站同步、日志上传……凡是走这个客户端的都算。
 *
 * 只换域名，不换路径，也不碰请求体；一次请求最多换一次，不会来回打转。
 */
public class FailoverInterceptor implements Interceptor {

    /** 主域名 → 备用域名：二级域名（子域前缀）保持不变 */
    private static final String[] PAIRS = {
            "169876.us.kg", "12356.cool",
            "169876.xyz", "12356.cool",
            "12356.cool", "169876.xyz",
    };

    /** 打在重试请求上的标记：已经换过一次域名了，别再换 */
    private static final class Tried {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (request.tag(Tried.class) != null) return chain.proceed(request);
        HttpUrl url = request.url();
        String host = alt(url.host());
        if (host == null) return chain.proceed(request);
        try {
            return chain.proceed(request);
        } catch (IOException e) {
            // 连不上才换：DNS 挂了、连不上、超时都算。HTTP 404/500 那是服务端的答复，不换
            Request next = request.newBuilder().url(url.newBuilder().host(host).build()).tag(Tried.class, new Tried()).build();
            return chain.proceed(next);
        }
    }

    /**
     * 换个域名：二级域名（子域前缀）原样保留，只换尾巴。
     * p.169876.us.kg → p.12356.cool；169876.us.kg → 12356.cool。
     * 不属于这批域名的返回 null，表示不用管。
     */
    private static String alt(String host) {
        if (host == null || host.isEmpty()) return null;
        String lower = host.toLowerCase();
        for (int i = 0; i < PAIRS.length; i += 2) {
            String from = PAIRS[i];
            if (lower.equals(from)) return PAIRS[i + 1];
            String suffix = "." + from;
            if (lower.endsWith(suffix)) return host.substring(0, host.length() - suffix.length()) + "." + PAIRS[i + 1];
        }
        return null;
    }
}
