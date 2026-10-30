package com.fongmi.android.tv.cast;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 广播包。局域网里来回的就这一种东西，字段固定���（见下面的常量），
 * 两端都靠 {@link Wire} 打成一段文本再 UTF-8 发出去。
 *
 * ⚠️ 单个包必须控制在 1400 字节以内（以太网 MTU 留点余量），一旦超了会被分片，
 * 丢一片整包就废了。所以这里**只放地址和状态，永远不放视频数据和字幕正文**——
 * 字幕正文走本机 HTTP 服务给个临时地址，包里只带这个地址。
 */
public final class CastPacket {

    /** 协议版本：将来字段改了必须 +1，老版本机器不认的包直接丢，别脑补 */
    public static final int VERSION = 1;
    /** UDP 载荷上限，超了拒绝发送，宁可变小，也不让整体功能挂掉 */
    public static final int MAX_BYTES = 1400;

    /** 接收端在问「这儿有没有人在广播」 */
    public static final String PROBE = "probe";
    /** 广播端的应答：带设备名、正在放的片子、以及校验配对码用的随机串 */
    public static final String HI = "hi";
    /** 接收端报名（带一次性的配对校验值） */
    public static final String JOIN = "join";
    /** 广播端每秒一次的状态包：位置、播放/暂停、倍速 */
    public static final String TICK = "tick";
    /** 立即生效的指令：暂停、续播、拖动、换集、收工 */
    public static final String CMD = "cmd";
    /** 接收端每隔几秒打招呼，超时会被广播端除名 */
    public static final String PING = "ping";
    /** 接收端主动退出 */
    public static final String LEAVE = "leave";
    /** 广播端收工：接收端收到就回待机等着，退出与否由它自己决定 */
    public static final String BYE = "bye";
    /** 配对码不对 / 人数满了 */
    public static final String DENY = "deny";

    /** 视频广播（把正在放的片子同步给别的屏） */
    public static final String KIND_VIDEO = "video";
    /** 屏幕广播（把这台设备的整个屏幕同步给别的屏） */
    public static final String KIND_SCREEN = "screen";

    public static final String STATE_PLAY = "play";
    public static final String STATE_PAUSE = "pause";
    public static final String STATE_BUFFER = "buffer";

    public static final String ACT_PAUSE = "pause";
    public static final String ACT_PLAY = "play";
    public static final String ACT_SEEK = "seek";
    public static final String ACT_STOP = "stop";

    private static final String K_VERSION = "v";
    private static final String K_TYPE = "t";
    private static final String K_UUID = "id";
    private static final String K_NAME = "n";
    private static final String K_SID = "sid";
    private static final String K_NONCE = "nonce";
    private static final String K_CODE = "code";
    private static final String K_KIND = "k";
    private static final String K_TITLE = "tt";
    private static final String K_EP = "ep";
    private static final String K_POS = "pos";
    private static final String K_SPEED = "sp";
    private static final String K_STATE = "st";
    private static final String K_TIME = "ts";
    private static final String K_ACT = "act";
    private static final String K_URL = "u";
    private static final String K_HEADERS = "hh";
    private static final String K_SUBS = "sb";
    private static final String K_MSG = "msg";
    private static final String K_MODE = "md";
    private static final String K_GROUP = "mg";
    private static final String K_MPORT = "mp";
    private static final String K_DPORT = "dp";
    private static final String K_QUALITY = "q";

    private final Map<String, String> items;

    /** 包内构造：协议测试要直接拿一组字段拼出「未来的版本」来验兼容性，不对外暴露 */
    CastPacket(Map<String, String> items) {
        this.items = items;
    }

    public static Builder of(String type) {
        return new Builder(type);
    }

    public static final class Builder {

        private final Map<String, String> items = new LinkedHashMap<>();

        private Builder(String type) {
            items.put(K_VERSION, String.valueOf(VERSION));
            items.put(K_TYPE, type);
        }

        public Builder put(String key, String value) {
            if (key != null && value != null) items.put(key, value);
            return this;
        }

        public Builder put(String key, long value) {
            return put(key, String.valueOf(value));
        }

        public Builder put(String key, Map<String, String> value) {
            if (value == null || value.isEmpty()) return this;
            return put(key, Wire.encode(value));
        }

        /** 设备唯一标识（同一台机器别把自己列进 trouver 清单） */
        public Builder uuid(String value) {
            return put(K_UUID, value);
        }

        public Builder name(String value) {
            return put(K_NAME, value);
        }

        public Builder sid(String value) {
            return put(K_SID, value);
        }

        /** 一次性随机串：接收端用它跟配对码算校验值，避免配对码在网上裸奔 */
        public Builder nonce(String value) {
            return put(K_NONCE, value);
        }

        /** sha1(nonce + 配对码)：广播端按同样的算法算一遍，不等就是配对码不对 */
        public Builder code(String value) {
            return put(K_CODE, value);
        }

        public Builder kind(String value) {
            return put(K_KIND, value);
        }

        public Builder title(String value) {
            return put(K_TITLE, value);
        }

        public Builder ep(String value) {
            return put(K_EP, value);
        }

        public Builder position(long value) {
            return put(K_POS, value);
        }

        public Builder speed(float value) {
            return put(K_SPEED, String.valueOf(value));
        }

        public Builder state(String value) {
            return put(K_STATE, value);
        }

        /** 发送那一刻对方的墙上时间，接收端拿它跟自己的时钟对表 */
        public Builder time() {
            return put(K_TIME, System.currentTimeMillis());
        }

        public Builder act(String value) {
            return put(K_ACT, value);
        }

        /** 播放地址（为了少发一个包，一般只在 HI/TICK 里带上） */
        public Builder url(String value) {
            return put(K_URL, value);
        }

        /** 取流要带的头（Referer/UA 之类的），由上层压成一段 Wire 文本再塞进来 */
        public Builder headers(Map<String, String> value) {
            return put(K_HEADERS, value);
        }

        /** 字幕列表，同上，只放地址和语言，不放正文 */
        public Builder subs(Map<String, String> value) {
            return put(K_SUBS, value);
        }

        public Builder msg(String value) {
            return put(K_MSG, value);
        }

        /** 现在走哪一档链路：中转 / 单播 / 组播，见 {@link CastMode} */
        public Builder mode(int value) {
            return put(K_MODE, value);
        }

        /** 组播地址 + 端口：只有组播档才用得上 */
        public Builder group(String value) {
            return put(K_GROUP, value);
        }

        public Builder mport(int value) {
            return put(K_MPORT, value);
        }

        /** 接收端自己那个收包端口：单播档由接收端主动报上来 */
        public Builder dport(int value) {
            return put(K_DPORT, value);
        }

        /** 接收端给的质量反馈：0 挺好、1 开始卡了、2 根本播不动 */
        public Builder quality(int value) {
            return put(K_QUALITY, value);
        }

        public CastPacket build() {
            return new CastPacket(new LinkedHashMap<>(items));
        }
    }

    public Map<String, String> items() {
        return Collections.unmodifiableMap(items);
    }

    public String type() {
        return get(K_TYPE, "");
    }

    public boolean is(String type) {
        return type.equals(type());
    }

    /** 版本不对的包一律不认：宁可对方这台先连不上，也别按错的规则去猜字段 */
    public boolean supported() {
        return get(K_VERSION, 0) == VERSION;
    }

    public String uuid() {
        return get(K_UUID, "");
    }

    public String name() {
        return get(K_NAME, "");
    }

    public String sid() {
        return get(K_SID, "");
    }

    public String nonce() {
        return get(K_NONCE, "");
    }

    public String code() {
        return get(K_CODE, "");
    }

    public String kind() {
        return get(K_KIND, KIND_VIDEO);
    }

    public String title() {
        return get(K_TITLE, "");
    }

    public String ep() {
        return get(K_EP, "");
    }

    public long position() {
        return get(K_POS, 0L);
    }

    public float speed() {
        try {
            return Float.parseFloat(get(K_SPEED, "1"));
        } catch (Exception e) {
            return 1f;
        }
    }

    public String state() {
        return get(K_STATE, STATE_PAUSE);
    }

    public long time() {
        return get(K_TIME, 0L);
    }

    public String act() {
        return get(K_ACT, "");
    }

    public String url() {
        return get(K_URL, "");
    }

    /** 上层塞进来的头信息，打成 Wire 文本传的，这里解回 Map；没有就是空表 */
    public Map<String, String> headers() {
        return Wire.decode(get(K_HEADERS, ""));
    }

    public Map<String, String> subs() {
        return Wire.decode(get(K_SUBS, ""));
    }

    public String msg() {
        return get(K_MSG, "");
    }

    public int mode() {
        int value = (int) get(K_MODE, 0L);
        return value == CastMode.UNICAST || value == CastMode.MULTICAST ? value : CastMode.HTTP;
    }

    public String group() {
        return get(K_GROUP, "");
    }

    public int mport() {
        return (int) get(K_MPORT, 0L);
    }

    public int dport() {
        return (int) get(K_DPORT, 0L);
    }

    public int quality() {
        return (int) get(K_QUALITY, 0L);
    }

    public String get(String key, String fallback) {
        String value = items.get(key);
        return value == null ? fallback : value;
    }

    public long get(String key, long fallback) {
        try {
            return Long.parseLong(items.get(key));
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 打成要发出去的字节。整包必须小于 {@link #MAX_BYTES}，
     * 超大（比如将来谁手欠往里塞了字幕正文）这里就返回 null，让调用方去降级，
     * 绝不能抱着「UDP 大不了分片」的心态往外发 —— 分片后丢一片，整包就作废了。
     */
    public byte[] bytes() {
        byte[] data = Wire.encode(items).getBytes(StandardCharsets.UTF_8);
        return data.length > MAX_BYTES ? null : data;
    }

    /** 解析失败（乱七八糟的包、不是我们协议的数据）一律返回 null，由调用方丢掉 */
    public static CastPacket parse(byte[] data, int length) {
        if (data == null || length <= 0 || length > MAX_BYTES) return null;
        try {
            Map<String, String> items = Wire.decode(new String(data, 0, length, StandardCharsets.UTF_8));
            if (items.isEmpty()) return null;
            if (!items.containsKey(K_TYPE)) return null;
            return new CastPacket(items);
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return type() + " " + items.toString();
    }
}
