package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.utils.AliveBeat;
import com.fongmi.android.tv.utils.CrashReporter;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PlayWatchdog;
import com.fongmi.android.tv.utils.SyncStatus;
import com.fongmi.hook.Hook;
import com.fongmi.android.tv.exception.CrashGuard;
import com.fongmi.android.tv.moontv.MoonSync;
import com.fongmi.android.tv.webdav.SyncManager;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;
    private int foreground;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.createChannel();
        registerActivityLifecycleCallbacks(this);
        SyncStatus.install(this); // 同步状态浮层要跟着页面走，先挂上生命周期
        Updater.prewarm(); // 版本检测提前：先悄悄探一次版本文件，等进首页要检查更新时直接出结果
        CrashGuard.install();
        watchMemory(); // 系统内存吃紧/回收时留一条带堆大小的记录，用来分辨是被系统杀还是 native 崩
        PlayWatchdog.check(); // 上次是不是「播着播着就没了」：是的话留一份日志并（MPV 时）自动降级
        CrashReporter.schedule(this); // 上次的崩溃日志后台回传归档网盘，传完即删
        AliveBeat.start(); // 存活心跳：进程无声消失时，日志里最后一行就是死亡时刻
        SyncManager.boot();
        MoonSync.boot();
    }

    /**
     * 内存体检：Android 6 这种小内存盒子，被系统杀（LMK）之前一定会先收到 trim/lowMemory。
     * 记下来，就能把「内存不够被杀」和「解码器 native 崩」区分开——两者表现都是直接重启、没堆栈。
     */
    private void watchMemory() {
        registerComponentCallbacks(new android.content.ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                DebugLog.d("Mem", "系统要回收内存 level=" + level + " " + PlayWatchdog.mem());
            }

            @Override
            public void onLowMemory() {
                DebugLog.d("Mem", "系统内存告急 " + PlayWatchdog.mem());
            }

            @Override
            public void onConfigurationChanged(android.content.res.Configuration config) {
            }
        });
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        CrashGuard.reassert(); // 加固壳可能在任意时刻抢走默认兜底，见 CrashGuard.reassert 注释
        CrashReporter.tick(this); // 有些崩溃不重启进程，借页面恢复把日志补传出去
        if (activity != activity()) this.activity = activity;
        DebugLog.d("Lifecycle", name(activity) + " onResume");
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
        DebugLog.d("Lifecycle", name(activity) + " onPause");
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        DebugLog.d("Lifecycle", name(activity) + " onCreate");
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        DebugLog.d("Lifecycle", name(activity) + " onDestroy");
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        ++foreground;
        DebugLog.d("Lifecycle", name(activity) + " onStart");
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        DebugLog.d("Lifecycle", name(activity) + " onStop");
        if (--foreground > 0) return;
        MoonSync.exit(); // 退到后台时补一次同步
    }

    private static String name(Activity activity) {
        return activity == null ? "null" : activity.getClass().getSimpleName();
    }
}