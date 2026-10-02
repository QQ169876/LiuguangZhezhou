package com.fongmi.android.tv.utils;

import android.content.Context;
import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
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
 * 播放看门狗：专治「播放时 App 直接没了，没有错误页、也没有 Java 堆栈」这种情况。
 *
 * native 崩（解码器 / mpv 内核这类 .so 里的段错误）走不到 UncaughtExceptionHandler，
 * CrashGuard 抓不到，日志网盘自然一直空着——这就是小米盒子那种「一播放就重启」的样子。
 *
 * 办法很土但管用：起播时在本地留一个标记，播放过程中每 3 秒刷一次时间戳；
 * 正常退出播放页（onDestroy）就把标记删掉。进程要是被崩掉/杀掉，标记来不及删，
 * 下次启动一比对时间戳，就知道「上次是在播放中没的」，据此
 *   ① 生成一份日志回传（写清内核、地址、页面、设备）；
 *   ② 如果当时用的是 MPV 内核，本机自动降级回 Exo，同步也带不回来（mpv_blocked 是设备本地标记）。
 *
 * 只在「还在播放」时刷时间戳：暂停/退到后台就停刷，时间戳变旧，
 * 这样后台被系统回收几小时后再启动不会误判成崩溃。
 */
public final class PlayWatchdog {

    private static final String NAME = "last_play.txt";
    private static final long BEAT = 3000L;      // 心跳间隔
    private static final long FRESH = 60000L;    // 时间戳 1 分钟内才算「播着播着就没了」
    private static final Runnable BEATER = PlayWatchdog::beat;

    private static String engine = "";
    private static String url = "";
    private static String page = "";
    private static long start;
    private static long seen;
    private static boolean playing;
    private static boolean active;

    private PlayWatchdog() {
    }

    /** 起播：留下标记并开心跳 */
    public static synchronized void start(String engineName, String playUrl) {
        engine = engineName == null ? "" : engineName;
        url = playUrl == null ? "" : playUrl;
        page = page();
        start = System.currentTimeMillis();
        seen = start;
        playing = true;
        active = true;
        write();
        DebugLog.d("Watchdog", "起播留痕 engine=" + engine + " url=" + url);
        App.post(BEATER, BEAT);
    }

    /** 播放真的在走才刷时间戳，暂停/后台就让它变旧 */
    public static synchronized void setPlaying(boolean value) {
        playing = value;
    }

    /** 正常离开播放页：删标记，别把自己记成崩溃 */
    public static synchronized void stop() {
        active = false;
        playing = false;
        App.removeCallbacks(BEATER);
        File f = file();
        if (f != null && f.exists()) f.delete();
        DebugLog.d("Watchdog", "正常离开播放页，撤掉标记");
    }

    /** 启动自检：上次是不是播着播着就没了 */
    public static synchronized void check() {
        try {
            File f = file();
            if (f == null || !f.exists()) return;
            String[] lines = read(f);
            f.delete(); // 先看后删，无论判定结果如何都不留
            if (lines == null || lines.length < 2) return;
            long last = Long.parseLong(lines[0]);
            String what = lines[1];
            if (System.currentTimeMillis() - last > FRESH) return; // 太旧，说明不是播着播着没的
            String text = lines.length > 2 ? lines[2] : "";
            String where = lines.length > 3 ? lines[3] : "";
            report(last, what, text, where);
        } catch (Throwable ignored) {
        }
    }

    private static void beat() {
        try {
            synchronized (PlayWatchdog.class) {
                if (!active) return;
                if (playing) {
                    seen = System.currentTimeMillis();
                    write();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (active) App.post(BEATER, BEAT);
        }
    }

    private static void report(long time, String what, String text, String where) {
        try {
            Context ctx = App.get();
            File base = ctx.getExternalFilesDir(null);
            if (base == null) base = ctx.getFilesDir();
            File dir = new File(base, "crash");
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, "crash_" + System.currentTimeMillis() + ".txt");
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
            pw.println("suspect: 播放中进程没了（没有 Java 堆栈，多半是内核 native 崩或被系统杀）");
            pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(time)));
            pw.println("engine: " + what);
            pw.println("url: " + text);
            pw.println("page: " + where);
            pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + " api" + Build.VERSION.SDK_INT);
            pw.println("version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
            pw.flush();
            pw.close();
            CrashReporter.flush(); // 趁早传，别等下次启动又被冲掉
        } catch (Throwable ignored) {
        }
        DebugLog.d("Watchdog", "上次是在播放中没的 engine=" + what + " url=" + text);
        if (!"mpv".equalsIgnoreCase(what)) return;
        Prefers.put("mpv_blocked", true); // 本机以后不再用 MPV，同步也带不回来（见 WebDavData.localOnly）
        Notify.show(R.string.play_engine_fallback);
    }

    private static void write() {
        try {
            File f = file();
            if (f == null) return;
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
            pw.println(seen);
            pw.println(engine);
            pw.println(url);
            pw.println(page);
            pw.flush();
            pw.close();
        } catch (Throwable ignored) {
        }
    }

    private static String[] read(File f) {
        try {
            byte[] data = new byte[(int) Math.min(f.length(), 8192)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(data);
                if (n <= 0) return null;
                return new String(data, 0, n, StandardCharsets.UTF_8).split("\n");
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static File file() {
        try {
            Context ctx = App.get();
            File base = ctx.getExternalFilesDir(null);
            if (base == null) base = ctx.getFilesDir();
            return new File(base, NAME);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String page() {
        try {
            android.app.Activity act = App.activity();
            return act == null ? "none" : act.getClass().getSimpleName();
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /** 这台设备还能不能用 MPV：看门狗判过一次就不让了，除非用户在设置里手动选回 MPV */
    public static boolean mpvBlocked() {
        return Prefers.getBoolean("mpv_blocked");
    }

    /** 用户手动选回 MPV 内核 = 解除封锁 */
    public static void unblockMpv() {
        Prefers.put("mpv_blocked", false);
    }
}
