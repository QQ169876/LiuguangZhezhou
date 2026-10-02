package com.fongmi.android.tv.exception;

import android.os.Looper;
import android.os.Process;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃保险，但只保「动态加载的第三方 spider jar」这一种。
 *
 * 那些 jar 有的带加固壳（ftyguard / mergeguard 之类），解密完会把任务 post 到主线程，
 * 一旦它自己空指针，主线程就被带走，整 App 跟着崩 —— 而代码既不是我们写的，也改不了，
 * 服务端随时下发新 jar，躲不开。所以这里是最后一道闸：
 *
 * 1) 主线程：在原有的消息循环外面再套一层 loop，蜘蛛 jar 抛的异常就地记日志吞掉，
 *    队列继续转，App 不倒；App 自己的崩溃照旧交给系统 handler 处理。
 * 2) 子线程：认到是蜘蛛 jar 的锅就放行（那个线程死掉，进程活着），否则原路交给系统。
 *
 * 认人的办法（两道）：
 * ① JarLoader 每造一个 DexClassLoader 都在这儿报个到（弱引用），崩溃时用它们 loadClass
 *    试着认领堆栈里的类名，认得出来算第三方；
 * ② 兜底：宿主 APK 自己都加载不到的类名，只可能是运行时动态塞进来的代码 —— 也算第三方。
 *    这道兜底治两个漏网场景：加固壳 jar 在内部另起 ClassLoader 解密类（我们只登记了最外层），
 *    以及源重载后旧 loader 被 GC、还挂在队列里的老任务没人认领。
 */
public class CrashGuard {

    private static final List<WeakReference<ClassLoader>> LOADERS = new ArrayList<>();
    private static volatile Thread.UncaughtExceptionHandler backup;
    private static final Thread.UncaughtExceptionHandler HANDLER = new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable e) {
            boolean main = thread == Looper.getMainLooper().getThread();
            if (!thirdParty(e)) {
                escape(thread, e, "no-foreign-frame");
                return;
            }
            report(main ? "main" : "thread", e);
            if (main) loop(); // 主线程不能没人转消息，补一个循环接着干
        }
    };

    public static synchronized void watch(ClassLoader loader) {
        if (loader == null) return;
        LOADERS.add(new WeakReference<ClassLoader>(loader));
        reassert();
    }

    /**
     * 加固壳在 Init.init() 里可能偷偷把系统默认兜底换成它自己的，崩溃就绕过我们的吞逻辑、
     * 直接落到更早装上的错误屏。jar 每加载完、每个页面恢复时都查一遍，被换走就抢回来。
     */
    public static synchronized void reassert() {
        if (Thread.getDefaultUncaughtExceptionHandler() != HANDLER) Thread.setDefaultUncaughtExceptionHandler(HANDLER);
    }

    public static void install() {
        backup = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(HANDLER);
    }

    /**
     * 只有真的替蜘蛛 jar 兜了一次之后才跑在这里。
     * 之前是在 App 一启动就把整个主线程套进这个循环，等于常态改写了 main 的消息处理，
     * 老盒子（小米盒子这类）上环境更复杂，没把握，所以改成「出事才接管」。
     */
    private static void loop() {
        while (true) {
            try {
                Looper.loop();
                return; // 队列收了就正常退出，别空转
            } catch (Throwable e) {
                if (!thirdParty(e)) {
                    escape(Thread.currentThread(), e, "no-foreign-frame");
                    return;
                }
                report("main", e);
            }
        }
    }

    /** App 自己的锅，走原来的路，该崩崩。reason 写进堆栈里，错误屏上能看到为什么放行。 */
    private static void escape(Thread thread, Throwable e, String reason) {
        dump(thread, e, "escape:" + reason); // 落盘：老盒子没有 ADB，崩完靠文件定位
        try {
            StackTraceElement[] old = e.getStackTrace();
            StackTraceElement[] neo = new StackTraceElement[old.length + 1];
            neo[0] = new StackTraceElement("CrashGuard", "escaped:" + reason, "CrashGuard.java", 0);
            System.arraycopy(old, 0, neo, 1, old.length);
            e.setStackTrace(neo);
        } catch (Throwable ignored) {
        }
        Thread.UncaughtExceptionHandler handler = backup;
        if (handler != null) handler.uncaughtException(thread, e);
        else Process.killProcess(Process.myPid());
    }

    private static void report(String where, Throwable e) {
        e.printStackTrace();
        android.util.Log.w("CrashGuard", "Drop " + where + " crash from spider jar: " + e);
        dump(Thread.currentThread(), e, "swallow:" + where); // 吞掉的也留一份，第三方 jar 的锅一样要看
    }

    /** 把崩溃堆栈写到私有目录（免存储权限），任何一步失败都静默，绝不影响原有崩溃流程。 */
    private static void dump(Thread thread, Throwable e, String tag) {
        try {
            android.content.Context ctx = com.fongmi.android.tv.App.get();
            java.io.File base = ctx.getExternalFilesDir(null);
            if (base == null) base = ctx.getFilesDir();
            java.io.File dir = new java.io.File(base, "crash");
            if (!dir.exists() && !dir.mkdirs()) return;
            // 最多留 10 份，别让目录无限长
            java.io.File[] olds = dir.listFiles();
            if (olds != null && olds.length >= 10) {
                java.util.Arrays.sort(olds, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < olds.length - 9; i++) olds[i].delete();
            }
            java.io.File f = new java.io.File(dir, "crash_" + System.currentTimeMillis() + ".txt");
            java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8"));
            pw.println("time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
            pw.println("tag: " + tag);
            pw.println("thread: " + thread.getName());
            pw.println("version: " + com.fongmi.android.tv.BuildConfig.VERSION_NAME + " (" + com.fongmi.android.tv.BuildConfig.VERSION_CODE + ")");
            e.printStackTrace(pw);
            pw.flush();
            pw.close();
        } catch (Throwable ignored) {
        }
    }

    /** 堆栈里有没有 spider jar 塞进来的类 */
    private static synchronized boolean thirdParty(Throwable e) {
        int depth = 0;
        for (Throwable cause = e; cause != null && depth < 6; cause = cause.getCause(), depth++) {
            StackTraceElement[] stack = cause.getStackTrace();
            if (stack == null) continue;
            int max = Math.min(stack.length, 32);
            for (int i = 0; i < max; i++) {
                String name = stack[i].getClassName();
                if (ours(name)) continue;
                if (claim(name)) return true;
                // 宿主 APK 里根本没有这个类 → 只能是动态加载的第三方代码
                if (!hostable(name)) return true;
            }
        }
        return false;
    }

    /** 自己人和系统的类，直接跳过 */
    private static boolean ours(String name) {
        return name.startsWith("java.")
                || name.startsWith("kotlin.")
                || name.startsWith("dalvik.")
                || name.startsWith("android.")
                || name.startsWith("androidx.")
                || name.startsWith("com.android.")
                || name.startsWith("com.fongmi.")
                || name.startsWith("com.github.catvod.")
                || name.startsWith("com.google.")
                || name.startsWith("okhttp3.");
    }

    /** 蜘蛛 jar 的 ClassLoader 认不认得这个类 */
    private static boolean claim(String name) {
        for (int i = LOADERS.size() - 1; i >= 0; i--) {
            ClassLoader loader = LOADERS.get(i).get();
            if (loader == null) {
                LOADERS.remove(i);
                continue;
            }
            try {
                // DexClassLoader 的 parent 是 App 的加载器，loadClass 会先问爹；
                // 只有真正长在这份 jar 里的类，ClassLoader 才是它自己
                if (loader.loadClass(name).getClassLoader() == loader) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 宿主 APK 自己能不能加载这个类：能 → 是自己人（混淆短名也算）；不能 → 是动态塞进来的第三方 */
    private static boolean hostable(String name) {
        try {
            Class.forName(name, false, CrashGuard.class.getClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
