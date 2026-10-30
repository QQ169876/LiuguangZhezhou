package com.fongmi.android.tv.cast;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 传输方式：同一份广播内容，用哪种链路发给接收端。
 *
 * 三档的选择与降级都放在这儿（纯 Java，好测），因为这是整套功能里唯一一个
 * 「会自己改主意」的地方，最容易写出「卡住了就再也不换回来」这种毛病。
 *
 * ┌ 组播：发送端只发一份，路由器 / 交换机负责复制给所有加入组的机器。
 * │        上行是真的省，这也是做 UDP 这套东西的初衷。
 * │        代价：家庭 AP 的组播能力参差不齐，有的房间可能收不到。
 * ├ 单播 UDP：挨个发给每台接收端。上行还是 N 份（跟中转一样），
 * │        但少了 HTTP 那一层，抖动小一点，也不受 AP 组播策略影响。
 * └ HTTP 中转：接收端从发送端本机 /cast 拉流。万源可用、能拖动、能挂字幕，
 *          代价是发送端上行 N 份。
 *
 * ⚠️ 组播 / 单播有个硬前提：**源必须是 MPEG-TS**。
 * 因为这两种方式是把字节原样撒出去的，不碰编码，所以只有本身就是 TS 的流
 * 才能一路通到接收端；mp4 / mkv / m4s 这类容器要是也这么发，接收端没法定界，
 * 那就得重新封装（不是重新编码），属于另一件活儿，当前没做。
 */
public final class CastMode {

    /** 万源可用的一档 */
    public static final int HTTP = 0;
    /** UDP 单播：挨个发 */
    public static final int UNICAST = 1;
    /** UDP 组播：发一次，全组都收到 */
    public static final int MULTICAST = 2;
    /** 没有更低的一档了 */
    public static final int NONE = -1;

    /**
     * 用户在设置里能挑的几种偏好（数值必须跟 {@link CastSetting} 里的 TRANSPORT_* 保持一致，
     * 之所以在这儿又写一遍：本类要留在纯 Java 这边能跑 JVM 单测，不能去依赖存设置的那套东西）。
     */
    public static final int PREF_AUTO = 0;
    public static final int PREF_MULTICAST = 1;
    public static final int PREF_UNICAST = 2;
    public static final int PREF_HTTP = 3;

    private CastMode() {
    }

    public static boolean isUdp(int mode) {
        return mode == UNICAST || mode == MULTICAST;
    }

    /**
     * 这个源能不能原样转发。
     *
     * 只看地址的话会漏判 —— 有些直播间给的就是 {@code http://x/y.ts?token=...}，
     * 有些看着像 .ts，其实是个 mp4 披着 ts 的皮。所以这里宁可保守：
     * 认不出来就按「不能」处理，走万无一失的中转，反正降级链条是往下走的。
     */
    public static boolean isTs(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        int cut = lower.indexOf('?');
        String path = cut > 0 ? lower.substring(0, cut) : lower;
        int hash = path.lastIndexOf('#');
        if (hash > 0) path = path.substring(0, hash);
        return path.endsWith(".ts") || path.endsWith(".tsv")
                || path.endsWith("/ts") || path.endsWith(".m2ts");
    }

    /**
     * 开播那一刻该走哪一档。
     *
     * @param setting  用户在设置里挑的偏好
     * @param tsSource 源是不是 TS（不是 TS 就只能用中转，这个结论不因偏好而变）
     */
    public static int initial(int setting, boolean tsSource) {
        if (!tsSource) return HTTP;
        switch (setting) {
            case PREF_MULTICAST:
                return MULTICAST;
            case PREF_UNICAST:
                return UNICAST;
            case PREF_HTTP:
                return HTTP;
            default:
                return MULTICAST; // AUTO：能组播就组播
        }
    }

    /** 下推一档；已经到底了返回 {@link #NONE} */
    public static int fallback(int mode) {
        if (mode == MULTICAST) return UNICAST;
        if (mode == UNICAST) return HTTP;
        return NONE;
    }

    /**
     * 挑一个组播地址。
     *
     * 用 239.x.x.x 这一段（本地管理域，不会跑到公网去），并且**按设备 + 会话种子算**：
     * 家里要是同时有两台在广播（手机放电影、盒子放直播），地址得岔开，
     * 不然两台往一个组里发，接收端拿到的是两份交错的字节，两边都废。
     */
    public static String group(String seed) {
        String text = seed == null ? "" : seed;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811C9DC5;
        for (byte item : data) {
            hash ^= item & 0xFF;
            hash *= 0x01000193;
        }
        int a = 100 + (Math.abs(hash) % 56);        // 239.100 ~ 239.155
        int b = 1 + (Math.abs(hash >>> 8) % 254);
        int c = 1 + (Math.abs(hash >>> 16) % 254);
        return "239." + a + "." + b + "." + c;
    }

    public static String text(int mode) {
        if (mode == MULTICAST) return "组播";
        if (mode == UNICAST) return "单播";
        return "中转";
    }
}
