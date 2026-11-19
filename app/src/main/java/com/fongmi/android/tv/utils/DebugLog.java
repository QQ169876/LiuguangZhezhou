package com.fongmi.android.tv.utils;

import android.content.Context;
import android.os.Build;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 调试日志：开关打开后，把 App 一路跑过来的关键动作逐条写进文件。
 *
 * 为什么要有它：有些设备（小米盒子那类）一播放整个进程就没了，Java 层根本来不及留堆栈，
 * 崩溃日志网盘自然一直是空的。有了这条流水账，就算崩在 .so 里，
 * 下次启动也能把「崩之前都干了什么」传上去——至少知道死在哪一步。
 *
 * 每条都立刻 flush：进程随时可能被干掉，攒在缓冲区里等于白记。
 * 文件超过 2MB 滚一份旧的（debug_old.log），不占满存储。
 * 本地只留 3 天：启动时 prune() 把超龄的旧文件清掉。
 *
 * 2026-11-20 全局默认规则（马先生，所有端/所有版本一致）：调试模式**只在开启当天有效**，
 * 跨过当天一律视为关闭，避免有人开了调试日志忘了关、长期在后台写文件耗资源。
 * 判据用「开启时的本地自然日」，纯算术不 new 对象（isEnabled 是心跳/播放都调的热路径）。
 * 老版本升级上来若只有 debug_trace=true、没有日期记录，按"默认关闭"处理，重新点一次开关即可。
 */
public final class DebugLog {

    private static final String DIR = "debug";
    private static final String NAME = "debug.log";
    private static final String OLD = "debug_old.log";
    private static final long MAX = 2 * 1024 * 1024L;
    private static final long KEEP = 3 * 24 * 60 * 60 * 1000L; // 本地日志只留 3 天
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final String KEY_ON = "debug_trace";
    private static final String KEY_DAY = "debug_trace_day"; // 开启当天（本地自然日序号）；-1 = 没记过

    private static PrintWriter writer;
    private static File file;
    // 开关缓存起来：日志默认关，却处处调用（心跳、播放、生命周期…），
    // 原来每打一行都要加一次锁再读一次 SharedPreferences，纯属白烧。
    // null = 还没读过，等首次用到再读，避开 Application 初始化时序。
    private static volatile Boolean enabled;
    /** 开启那天的本地自然日序号，跟 enabled 一起缓存；跨天后 isEnabled 自动变 false */
    private static volatile long enabledDay = -1;

    private DebugLog() {
    }

    /** 本地时区的自然日序号：跨过当天午夜就 +1，纯算术不 new 对象（热路径） */
    private static long todayIndex() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    /**
     * 调试模式是否生效：开关为开**且**是开启当天。跨过当天一律按关闭处理（全局默认规则）。
     * 缓存命中后仍要校验自然日，所以不需要定时器去「到点关闭」——时间一过自然就false。
     */
    public static boolean isEnabled() {
        Boolean value = enabled;
        if (value == null) {
            synchronized (DebugLog.class) {
                if (enabled == null) {
                    try {
                        enabled = Prefers.getBoolean(KEY_ON, false);
                        enabledDay = Prefers.getLong(KEY_DAY, -1);
                    } catch (Throwable e) {
                        return false; // Prefers 还没就绪，别把 false 缓存死，等下次真正用到再读
                    }
                }
                value = enabled;
            }
        }
        // 老版本只存了 true 没存日期（-1）：按默认关闭，重新点一次开关即可
        return value && enabledDay == todayIndex();
    }

    public static synchronized void setEnabled(boolean on) {
        Prefers.put(KEY_ON, on);
        Prefers.put(KEY_DAY, on ? todayIndex() : -1);
        enabled = on;
        enabledDay = on ? todayIndex() : -1;
        if (on) d("Debug", "---- 调试日志已开启（当天有效）----");
        else close();
    }

    public static synchronized void d(String tag, String msg) {
        if (!isEnabled()) return;
        try {
            PrintWriter out = writer();
            if (out == null) return;
            out.println(FMT.format(new Date()) + " [" + tag + "] " + msg);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    /** 日志文件的当前大小（字节），设置页上给个数心里有底 */
    public static synchronized long size() {
        try {
            File f = file();
            return f == null ? 0 : f.length();
        } catch (Throwable e) {
            return 0;
        }
    }

    public static synchronized File file() {
        try {
            if (file != null) return file;
            Context ctx = App.get();
            File base = ctx.getExternalFilesDir(null);
            if (base == null) base = ctx.getFilesDir();
            File dir = new File(base, DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            file = new File(dir, NAME);
            return file;
        } catch (Throwable e) {
            return null;
        }
    }

    public static synchronized void clear() {
        close();
        try {
            File f = file();
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 本地只留 3 天：超龄的滚动旧档直接删；主文件 3 天没新写入（很久没开过）也重起一份 */
    public static synchronized void prune() {
        try {
            File f = file();
            if (f == null) return;
            long now = System.currentTimeMillis();
            File old = new File(f.getParentFile(), OLD);
            if (old.exists() && now - old.lastModified() > KEEP) old.delete();
            if (f.exists() && now - f.lastModified() > KEEP) {
                close();
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 写日志前记一笔设备信息，方便一眼看出这份日志是谁的 */
    public static synchronized void header() {
        d("Device", Build.MANUFACTURER + " " + Build.MODEL + " api" + Build.VERSION.SDK_INT + " abi=" + Build.CPU_ABI);
    }

    private static synchronized PrintWriter writer() {
        try {
            File f = file();
            if (f == null) return null;
            if (writer != null && f.length() > MAX) roll(f);
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
                header();
            }
            return writer;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void roll(File f) {
        try {
            close();
            File old = new File(f.getParentFile(), OLD);
            if (old.exists()) old.delete();
            f.renameTo(old);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void close() {
        try {
            if (writer != null) writer.close();
        } catch (Throwable ignored) {
        }
        writer = null;
    }
}
