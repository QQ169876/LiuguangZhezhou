package com.fongmi.android.tv.exception;

import android.os.Handler;
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
 * 认人的办法：JarLoader 每造一个 DexClassLoader 都在这儿报个到（弱引用），
 * 崩溃时用它们 loadClass 试着认领堆栈里的类名，认得出来才放行。
 */
public class CrashGuard {

    private static final List<WeakReference<ClassLoader>> LOADERS = new ArrayList<>();
    private static volatile Thread.UncaughtExceptionHandler backup;

    public static synchronized void watch(ClassLoader loader) {
        if (loader == null) return;
        LOADERS.add(new WeakReference<ClassLoader>(loader));
    }

    public static void install() {
        backup = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                if (thirdParty(e)) {
                    report("thread", e);
                    return;
                }
                escape(thread, e);
            }
        });
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Looper.loop();
                    } catch (Throwable e) {
                        if (thirdParty(e)) {
                            report("main", e);
                        } else {
                            escape(Thread.currentThread(), e);
                            return;
                        }
                    }
                }
            }
        });
    }

    /** App 自己的锅，走原来的路，该崩崩 */
    private static void escape(Thread thread, Throwable e) {
        Thread.UncaughtExceptionHandler handler = backup;
        if (handler != null) handler.uncaughtException(thread, e);
        else Process.killProcess(Process.myPid());
    }

    private static void report(String where, Throwable e) {
        e.printStackTrace();
        android.util.Log.w("CrashGuard", "Drop " + where + " crash from spider jar: " + e);
    }

    /** 堆栈里有没有 spider jar 塞进来的类 */
    private static synchronized boolean thirdParty(Throwable e) {
        int depth = 0;
        for (Throwable cause = e; cause != null && depth < 4; cause = cause.getCause(), depth++) {
            StackTraceElement[] stack = cause.getStackTrace();
            if (stack == null) continue;
            int max = Math.min(stack.length, 16);
            for (int i = 0; i < max; i++) {
                String name = stack[i].getClassName();
                if (ours(name)) continue;
                if (claim(name)) return true;
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
}
