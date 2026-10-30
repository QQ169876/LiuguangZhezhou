package com.fongmi.android.tv.cast;

import com.github.catvod.utils.Prefers;

/**
 * 广播功能的设置项（跟 AI 那套一个写法，都落在 Prefers 里）。
 *
 * 这里只放**用户改得到、关了 App 也该记住**的东西：
 * 配对码、最大接收数、要不要自动加入、屏幕画质、传输模式。
 * 至于「现在是不是正在广播」这种每次重开都要回归默认的状态，一律不进这里。
 */
public class CastSetting {

    /** 默认配对码：马先生 2026-10-31 定的，同屋的人点两下就能连上 */
    public static final String DEFAULT_CODE = "1234";

    /** 传输模式：有什么用什么，能组播就组播（推荐，默认） */
    public static final int TRANSPORT_AUTO = 0;
    /** 能用组播的时候组播优先，凑不齐就退回下一档（现阶段只在 TS 源上生效） */
    public static final int TRANSPORT_MULTICAST = 1;
    /** 不走组播，逐个接收端单播 UDP 转发（上行还是 N 份，但比 HTTP 少一层栈） */
    public static final int TRANSPORT_UNICAST = 2;
    /** 只走 HTTP 中转：万源可用，支持拖动/字幕/换集，最稳但上行是 N 份 */
    public static final int TRANSPORT_HTTP = 3;

    /** 屏幕画质：默认 720p30（马先生 2026-10-31 选的：稳、发热小） */
    public static final int QUALITY_720P30 = 0;
    public static final int QUALITY_1080P30 = 1;
    public static final int QUALITY_AUTO = 2;

    /** 最大接收数的「不限制」标记：0 或留空都算不限制（马先生 2026-10-31：家里 2.5G 有线，不用替他省） */
    public static final int MAX_UNLIMITED = 0;
    /** 手动填数的天花板：超过这个数没意义，也顺手挡住误输入一大串数字 */
    public static final int MAX_CEILING = 999;
    /** 默认值：不限制。以前是 5，但那是在替带宽不够的用户做决定，现在改成用户说了算 */
    public static final int DEFAULT_MAX = MAX_UNLIMITED;

    public static String getCode() {
        String value = Prefers.getString("cast_code", DEFAULT_CODE).trim();
        if (value.isEmpty()) return DEFAULT_CODE;
        if (value.length() > 16) return value.substring(0, 16); // 太长的口令没必要，也防止 key 被玩坏
        return value;
    }

    public static void putCode(String code) {
        Prefers.put("cast_code", code == null ? "" : code.trim());
    }

    /**
     * 最大接收数：0 = 不限制，1~999 = 手动上限。
     *
     * 这个数只用来保护「发送端上行撑不住」的场合；家里是有线 2.5G / Wi-Fi 6 的话，
     * 直接留 0（默认）就是最合适的。以前写死上限 8，对带宽足的用户纯属添堵。
     */
    public static int getMaxClients() {
        return clean(Prefers.getInt("cast_max", DEFAULT_MAX));
    }

    public static void putMaxClients(int value) {
        Prefers.put("cast_max", clean(value));
    }

    /**
     * 界面上是个文本框，用户可能留空、带空格、或者乱填一通。
     * 空 / 0 / 负数 / 非数字一律按「不限制」处理，不让用户输入一个错字就把功能搞坏。
     */
    public static void putMaxClients(String text) {
        putMaxClients(parse(text));
    }

    /** 给总线用的值：0 换成 Integer.MAX_VALUE，这样内部不用到处判断「是不是不限制」 */
    public static int maxClientsLimit() {
        int value = getMaxClients();
        return value <= 0 ? Integer.MAX_VALUE : value;
    }

    /** 界面回填用：不限制显示成空，让用户一看就知道「留空 = 不限」 */
    public static String maxText() {
        int value = getMaxClients();
        return value <= 0 ? "" : String.valueOf(value);
    }

    /** 列表展示用：不限制就显示成 0 以外的说法交给界面自己处理，这里只负责给数 */
    public static int parse(String text) {
        if (text == null) return MAX_UNLIMITED;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return MAX_UNLIMITED;
        try {
            return clean(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return MAX_UNLIMITED; // 乱填的不猜它的意思，按不限制算
        }
    }

    private static int clean(int value) {
        if (value <= 0) return MAX_UNLIMITED;
        return Math.min(MAX_CEILING, value);
    }

    /** 扫描到只有一台在广播时，要不要接收端直接进去（不用弹列表选） */
    public static boolean isAutoJoin() {
        return Prefers.getBoolean("cast_auto_join", true);
    }

    public static void putAutoJoin(boolean enabled) {
        Prefers.put("cast_auto_join", enabled);
    }

    public static int getTransport() {
        int value = Prefers.getInt("cast_transport", TRANSPORT_AUTO);
        return value < TRANSPORT_AUTO || value > TRANSPORT_HTTP ? TRANSPORT_AUTO : value;
    }

    public static void putTransport(int value) {
        Prefers.put("cast_transport", value);
    }

    public static int getQuality() {
        int value = Prefers.getInt("cast_quality", QUALITY_720P30);
        return value < QUALITY_720P30 || value > QUALITY_AUTO ? QUALITY_720P30 : value;
    }

    public static void putQuality(int value) {
        Prefers.put("cast_quality", value);
    }

    /** 屏幕广播：0=关、1=仅取不到原流时顶上、2=总是开（马先生 2026-10-31：默认第二种） */
    public static int getScreen() {
        int value = Prefers.getInt("cast_screen", 1);
        return Math.min(2, Math.max(0, value));
    }

    public static void putScreen(int value) {
        Prefers.put("cast_screen", Math.min(2, Math.max(0, value)));
    }

    /** 屏幕广播的目标高度：给编码参数用 */
    public static int targetHeight() {
        return getQuality() == QUALITY_1080P30 ? 1080 : 720;
    }
}
