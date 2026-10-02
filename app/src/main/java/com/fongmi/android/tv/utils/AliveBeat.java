package com.fongmi.android.tv.utils;

import android.app.ActivityManager;
import android.content.Context;

import com.fongmi.android.tv.App;

/**
 * 存活心跳：只要进程还活着，就隔几秒在日志里记一行。
 *
 * 老盒子/投影（MStar 安卓6 32位）上 App 会「无声无息直接重启」：没有错误页、没有 Java 堆栈、
 * 内存也不紧张——native 层一崩，CrashGuard 的 UncaughtExceptionHandler 根本走不到，
 * 日志里就只剩「某一行之后一片空白」，只知道死在两件事之间，不知道死在什么时刻。
 *
 * 有了这条心跳，空白区间的最后一行就是死亡时刻，再跟用户当时的操作一对照，
 * 是同步收尾后的残留、还是渲染、还是别的，就能分清。
 *
 * 平时 5 秒一行，不吵；做完同步这类高危操作自动切成 200 毫秒一行、持续 15 秒——
 * 几次死亡都发生在收尾后的 2～6 秒里，这段必须细。
 */
public final class AliveBeat {

    private static final long IDLE = 5000L;      // 平时
    private static final long FAST = 200L;       // 高危操作收尾后
    private static final long FAST_TIME = 15000L;
    private static final Runnable BEAT = AliveBeat::beat;

    private static long fastUntil;
    private static long since;

    private AliveBeat() {
    }

    /** 启动后跑起来：跟着 App 一起活，不需要停 */
    public static void start() {
        App.post(BEAT, IDLE);
    }

    /** 高危操作刚收尾：接下来 15 秒细打点，死了好知道死在第几秒 */
    public static synchronized void fast() {
        since = System.currentTimeMillis();
        fastUntil = since + FAST_TIME;
    }

    private static synchronized long beat() {
        try {
            long now = System.currentTimeMillis();
            boolean fast = now < fastUntil;
            DebugLog.d("Alive", (fast ? "存活(细) +" + (now - since) + "ms " : "存活 ") + snapshot());
            App.post(BEAT, fast ? FAST : IDLE);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String snapshot() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;
        return "线程" + Thread.activeCount() + " 堆" + used + "/" + max + "MB 系统可用" + sysAvail() + "MB 页面" + page();
    }

    private static long sysAvail() {
        try {
            ActivityManager am = (ActivityManager) App.get().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem / 1048576L;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String page() {
        try {
            android.app.Activity act = App.activity();
            return act == null ? "none" : act.getClass().getSimpleName();
        } catch (Throwable e) {
            return "?";
        }
    }
}
