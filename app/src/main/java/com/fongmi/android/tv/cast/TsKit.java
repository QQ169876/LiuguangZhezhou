package com.fongmi.android.tv.cast;

/**
 * MPEG-TS 的几个小工具：找同步位置、读 PCR、按 PCR 控制发送节奏。
 *
 * 这三个都是**极其容易写错又难以肉眼发现**的东西（错一个比特，画面就是马赛克或者直接起不来），
 * 所以单独拎出来当纯 Java 写，能用普通 JVM 跑用例验证。
 *
 * 背景：TS 是一连串固定 188 字节的小包，每包开头是同步字节 0x47；
 * 时间戳不在包头里，而是偶尔出现在「自适应区」的 PCR 字段（27MHz 计数的 42 位数）。
 */
public final class TsKit {

    /** 一个 TS 包 */
    public static final int PACKET = 188;
    /** 一个 UDP 包装几个 TS 包：7×188=1316，加上各种头部也在以太网 MTU 之内，包不会被分片 */
    public static final int UNIT = 7 * PACKET;

    private TsKit() {
    }

    /**
     * 找 TS 流是从第几个字节开始的。
     *
     * 从网上拉下来的头几百字节里可能混着 HTTP 残留、JSON 片段之类的脏东西，
     * 开头不是 0x47 很常见。这里连着找三个相距正好 188 的 0x47，命中率相当高；
     * 找不到就按 0 处理（让接收端自己去重新同步），总比瞎猜一个偏移强。
     */
    public static int align(byte[] data, int len) {
        if (data == null || len < PACKET * 3) return 0;
        if (sync(data, 0) && sync(data, PACKET)) return 0;
        for (int i = 1; i < PACKET; i++) {
            if (i + PACKET * 2 >= len) break;
            if (sync(data, i) && sync(data, i + PACKET) && sync(data, i + PACKET * 2)) return i;
        }
        return 0;
    }

    private static boolean sync(byte[] data, int at) {
        return at >= 0 && at < data.length && (data[at] & 0xFF) == 0x47;
    }

    /**
     * 读一个 TS 包里的 PCR（27MHz 单位）；没有 PCR 返回 -1。
     *
     * PCR 必须按 27MHz 记：33 位的 base 一共走过 26 小时才绕回一圈，
     * 用它算「该等到什么时候发」比直接用包数挨个乘靠谱得多。
     */
    public static long pcr(byte[] data, int off) {
        if (data == null || off < 0 || off + 12 > data.length) return -1L;
        if ((data[off] & 0xFF) != 0x47) return -1L;
        int afc = (data[off + 3] >> 4) & 0x03;
        if (afc != 0x02 && afc != 0x03) return -1L; // 没有自适应区就没有 PCR
        int adaptLen = data[off + 4] & 0xFF;
        if (adaptLen < 7) return -1L;                // 装不下「标志字节 + 6 字节 PCR」
        if ((data[off + 5] & 0x10) == 0) return -1L; // PCR 标志没置位
        return decode(data, off + 6);
    }

    /** 6 个字节 → 42 位 PCR（33 位 base + 保留 + 9 位 ext） */
    public static long decode(byte[] data, int at) {
        long base = ((long) (data[at] & 0xFF) << 25)
                | ((long) (data[at + 1] & 0xFF) << 17)
                | ((long) (data[at + 2] & 0xFF) << 9)
                | ((long) (data[at + 3] & 0xFF) << 1)
                | ((long) (data[at + 4] & 0x80) >> 7);
        long ext = ((long) (data[at + 4] & 0x01) << 8) | (data[at + 5] & 0xFF);
        return base * 300L + ext;
    }

    /** 按位写成 6 个字节，跟 {@link #decode} 严格互逆 */
    public static void encode(byte[] data, int at, long value) {
        long base = (value / 300L) & 0x1FFFFFFFFL;
        long ext = (value % 300L) & 0x1FF;
        data[at] = (byte) (base >>> 25);
        data[at + 1] = (byte) (base >>> 17);
        data[at + 2] = (byte) (base >>> 9);
        data[at + 3] = (byte) (base >>> 1);
        data[at + 4] = (byte) (((base & 1) << 7) | 0x7E | ((ext >>> 8) & 0x01));
        data[at + 5] = (byte) (ext & 0xFF);
    }

    /**
     * 发送节奏控制器：把这个 TS 速率掐到跟源一样快，别把局域网按死。
     *
     * 为什么不能有多少读多少直接发：直播源的话，网卡读到多快取决于对端给多快，
     * 天然就是实时的；但要是源是个静态 .ts 文件，下载能跑满几十兆，
     * 一口气全灌进 UDP 只会把 AP 打爆，接收端反而全丢。
     *
     * 做法就是用 PCR 当节拍器：记下第一个 PCR 对应的墙上时间，
     * 之后每个 PCR 换算成「应该在第几毫秒发出去」，差多少就睡多少。
     */
    public static final class Pacer {

        private boolean ready;
        private long basePcr;
        private long baseWall;

        public boolean ready() {
            return ready;
        }

        /**
         * @return 还要再睡多少毫秒才能发这个 PCR 的包；0 就是立刻发
         */
        public long waitMs(long pcr, long nowMs) {
            if (pcr < 0) return 0L;
            if (!ready) {
                ready = true;
                basePcr = pcr;
                baseWall = nowMs;
                return 0L;
            }
            long passed = pcr - basePcr;
            // 绕回（超过一小时没见着增长）或者倒流：重新对表，别傻等到地老天荒
            if (passed <= 0 || passed > 27_000_000L * 3600L) {
                basePcr = pcr;
                baseWall = nowMs;
                return 0L;
            }
            long wait = baseWall + passed / 27000L - nowMs;
            if (wait <= 0) return 0L;
            return Math.min(wait, 3000L);
        }
    }
}
