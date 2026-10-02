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
 */
public final class DebugLog {

    private static final String DIR = "debug";
    private static final String NAME = "debug.log";
    private static final String OLD = "debug_old.log";
    private static final long MAX = 2 * 1024 * 1024L;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static PrintWriter writer;
    private static File file;

    private DebugLog() {
    }

    public static synchronized boolean isEnabled() {
        return Prefers.getBoolean("debug_trace");
    }

    public static synchronized void setEnabled(boolean on) {
        Prefers.put("debug_trace", on);
        if (on) d("Debug", "---- 调试日志已开启 ----");
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
