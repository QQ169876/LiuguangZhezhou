package com.github.catvod.net.interceptor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.orhanobut.logger.Logger;

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
 * 只换域名，不换路径，也不碰请求体。
 *
 * 2026-10-24 马先生反馈「打不开时换 12356.cool 太慢，甚至不知道它起作用没有」，改了三处：
 * 1. **连接超时收紧到 4 秒**：以前要先把默认的连接超时（十秒级）耗完才轮到换域名，
 *    主域名 DNS 一挂就是十几秒白等，这就是「慢」的由来。换域名这条路上单独掐到 4 秒；
 * 2. **记住走通的那个**：哪边通了就记 10 分钟，这段时间里所有请求直奔它，
 *    不再每次都先撞一次墙再换（第二次往后基本感受不到切换）；
 * 3. **切换留痕**：真换了域名在日志里记一笔（界面依旧不动声色），出问题时一眼能看出走了哪条路。
 */
public class FailoverInterceptor implements Interceptor {

    private static final String TAG = "Failover";

    /** 主域名 → 备用域名：二级域名（子域前缀）保持不变 */
    private static final String[] PAIRS = {
            "169876.us.kg", "12356.cool",
            "169876.xyz", "12356.cool",
            "12356.cool", "169876.xyz",
    };

    /** 这批域名的连接超时：掐短，别让用户在挂掉的域名上干等 */
    private static final int CONNECT_TIMEOUT_MS = 4000;

    /** 走通的那个记多久：这段时间内一律先走它 */
    private static final long STICKY_MS = 10 * 60 * 1000L;

    /** 最近一次走通的域名（只记尾巴，子域前缀各请求各带） */
    private static volatile String good = "";
    private static volatile long goodUntil = 0L;

    /** 打在重试请求上的标记：已经换过一次域名了，别再换 */
    private static final class Tried {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (request.tag(Tried.class) != null) return chain.proceed(request);
        HttpUrl url = request.url();
        String host = url.host();
        if (base(host) == null) return chain.proceed(request); // 不是这批域名，不管

        // 这段时间里哪边通已经试出来了：直奔它，别再撞一次墙
        String from = base(host);
        String altBase = base(alt(host)); // 备用域名的尾巴（子域前缀不参与记忆）
        String first = host;
        if (altBase != null && sticky(altBase) && !altBase.equals(from)) first = swap(host, altBase);

        try {
            Response response = proceed(chain, request, first, !first.equals(host));
            mark(base(first));
            return response;
        } catch (IOException e) {
            String second = first.equals(host) ? (altBase == null ? null : swap(host, altBase)) : host;
            if (second == null || second.equals(first)) throw e;
            try {
                Response response = proceed(chain, request, second, true);
                mark(base(second));
                Logger.t(TAG).d(first + " 连不上，已改走 " + second + "（" + e + "）");
                return response;
            } catch (IOException e2) {
                throw e2;
            }
        }
    }

    /** 换掉域名（需要时才换），并按这批域名的规矩掐短连接超时 */
    private Response proceed(Chain chain, Request request, String host, boolean changed) throws IOException {
        Request next = request;
        HttpUrl url = request.url();
        if (!host.equals(url.host())) {
            next = request.newBuilder().url(url.newBuilder().host(host).build()).tag(Tried.class, new Tried()).build();
        } else if (changed) {
            next = request.newBuilder().tag(Tried.class, new Tried()).build();
        }
        Chain target = chain;
        if (target.connectTimeoutMillis() > CONNECT_TIMEOUT_MS) {
            target = target.withConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        return target.proceed(next);
    }

    /** 记下这个域名走通了，接下来 10 分钟都先走它 */
    private static void mark(String base) {
        if (base == null || base.isEmpty()) return;
        good = base;
        goodUntil = System.currentTimeMillis() + STICKY_MS;
    }

    /** 这个域名是不是「刚走通、还在有效期内」的那个 */
    private static boolean sticky(String base) {
        return base != null && !base.isEmpty() && base.equals(good) && System.currentTimeMillis() < goodUntil;
    }

    /** 换个域名：二级域名（子域前缀）原样保留，只换尾巴。不属于这批域名的返回 null */
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

    /** 这个 host 属于这批域名里的哪一个（返回 PAIRS 里的主域名），不属于返回 null */
    private static String base(String host) {
        if (host == null || host.isEmpty()) return null;
        String lower = host.toLowerCase();
        for (int i = 0; i < PAIRS.length; i += 2) {
            if (lower.equals(PAIRS[i]) || lower.endsWith("." + PAIRS[i])) return PAIRS[i];
        }
        return null;
    }

    /** 把 host 的尾巴换成指定域名，子域前缀照旧。换不了就原样返回 */
    private static String swap(String host, String toBase) {
        String from = base(host);
        if (from == null || toBase == null || toBase.isEmpty()) return host;
        if (host.equalsIgnoreCase(from)) return toBase;
        return host.substring(0, host.length() - from.length()) + toBase;
    }
}
