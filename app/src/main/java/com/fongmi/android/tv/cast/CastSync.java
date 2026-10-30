package com.fongmi.android.tv.cast;

/**
 * 接收端怎么追上发送端：这是整套功能里唯一一个「算法」，单独放这儿就是为了能单测。
 *
 * 思路：发送端每秒给一次「我放到第几毫秒了 + 我发包那一刻的墙上时间」，
 * 接收端拿本地时间算出「对方现在应该放到哪」，再看自己差多少：
 *
 * - 差得少（300ms 内）：不动。网络本来就有抖动，来回追会更抖。
 * - 差了几百毫秒到 1.5 秒：悄悄加/减一点速度追上去，用户看不出来。
 * - 差得多（5 秒以上，比如刚跳了集、刚卡过）：直接对位。
 *
 * ⚠️ 时间戳是拿来「对表」的，不是拿来算绝对时间的：
 * 两台机器的墙上时间差个几秒很正常，但我们只关心**相对流逝**，所以用 (本地现在 - 对方发包时刻) 这段差值来推算。
 */
public final class CastSync {

    /** 允许的自由偏差：小于这个值不折腾 */
    public static final long IGNORE_MS = 300L;
    /** 超过这个偏差就用变速追 */
    public static final long NUDGE_MS = 1500L;
    /** 超过这个偏差就直接对位 */
    public static final long SEEK_MS = 5000L;
    /** 变速幅度：小到看不出来，几十秒里能追回好几秒 */
    public static final float NUDGE_RATE = 0.05f;

    public enum Action {
        /** 差得不远，什么都不做 */
        NONE,
        /** 直接用 seek 对位 */
        SEEK,
        /** 落后了：稍微快一点点追 */
        FASTER,
        /** 超前了：稍微慢一点点等 */
        SLOWER
    }

    private CastSync() {
    }

    /** 对方现在应该播到第几毫秒 */
    public static long expect(long localNow, long senderTime, long remotePosition, float speed) {
        long elapsed = senderTime > 0 ? Math.max(0L, localNow - senderTime) : 0L;
        double rate = speed > 0 ? speed : 1f;
        return Math.max(0L, remotePosition + Math.round(elapsed * rate));
    }

    /**
     * @param localNow   接收端此刻的墙上时间
     * @param senderTime 发送端发包时的墙上时间（包里的 ts）
     * @param remotePos  发送端包里的位置
     * @param localPos   本机播放器的位置，-1 表示还没起播
     * @param speed      发送端的倍速
     * @return 这一轮该干什么
     */
    public static Result plan(long localNow, long senderTime, long remotePos, long localPos, float speed) {
        long target = expect(localNow, senderTime, remotePos, speed);
        if (localPos < 0) return Result.of(Action.SEEK, target, speed); // 还没起播，直接对位
        long diff = localPos - target;
        long abs = Math.abs(diff);
        if (abs <= IGNORE_MS) return Result.of(Action.NONE, target, speed);
        if (abs >= SEEK_MS) return Result.of(Action.SEEK, target, speed);
        if (abs >= NUDGE_MS) {
            float rate = speed > 0 ? speed : 1f;
            return diff < 0 ? Result.of(Action.FASTER, target, rate * (1f + NUDGE_RATE))
                    : Result.of(Action.SLOWER, target, rate * (1f - NUDGE_RATE));
        }
        return Result.of(Action.NONE, target, speed);
    }

    public static final class Result {

        public final Action action;
        public final long target;
        public final float speed;

        private Result(Action action, long target, float speed) {
            this.action = action;
            this.target = target;
            this.speed = speed;
        }

        private static Result of(Action action, long target, float speed) {
            return new Result(action, target, speed);
        }

        @Override
        public String toString() {
            return action + " target=" + target + " speed=" + speed;
        }
    }
}
