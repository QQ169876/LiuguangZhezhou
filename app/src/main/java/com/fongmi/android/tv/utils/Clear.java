package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.moontv.MoonSync;
import com.github.catvod.utils.Path;

import java.io.File;

/**
 * 设置页最下面那个「清除数据」：清观看记录收藏，或者整个回到刚安装的状态。
 * 数据库操作都放后台线程；清完全部数据会重启 App，避免内存里还留着旧状态。
 */
public class Clear {

    private Clear() {
    }

    /** 只清播放历史和收藏（toast 提示按沿用调用方处理） */
    public static void history(Callback callback) {
        Task.execute(() -> {
            MoonSync.tombstoneLocal(); // 先记墓碑 + 通知站点删除，不然下次同步又拉回来
            AppDatabase.get().getHistoryDao().delete();
            AppDatabase.get().getKeepDao().delete();
            App.post(callback::success);
        });
    }

    /** 清全部：点播源 / 直播源 / 收藏 / 历史 / 轨道 / 设备 + 所有配置 + 缓存 */
    public static void all(Callback callback) {
        Task.execute(() -> {
            MoonSync.tombstoneLocal(); // 同上：清库之前先把站点上的对应条目删掉
            AppDatabase.get().getKeepDao().delete();
            AppDatabase.get().getHistoryDao().delete();
            AppDatabase.get().getTrackDao().delete();
            AppDatabase.get().getSiteDao().delete();
            AppDatabase.get().getLiveDao().delete();
            AppDatabase.get().getConfigDao().delete();
            AppDatabase.get().getDeviceDao().delete();
            Path.clear(Path.cache());
            CookieStore.clear();
            clearPrefs();
            App.post(callback::success);
        });
    }

    /**
     * 把所有 SharedPreferences 清掉，等于 Preferences 层回到出厂。
     * 直接扫 shared_prefs 目录，不用去猜各个库自己用了哪个文件名。
     */
    private static void clearPrefs() {
        File dir = new File(App.get().getApplicationInfo().dataDir, "shared_prefs");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".xml")) continue;
            String key = name.substring(0, name.length() - 4);
            App.get().getSharedPreferences(key, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    /**
     * 重启到首屏：先排一个 300 毫秒后拉起首页的闹钟，再把当前进程结束，
     * 这样起来的是干净进程，内存里的旧配置不会又写回去。
     */
    public static void restart(Activity activity) {
        if (activity == null) return;
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_ONE_SHOT | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pending = PendingIntent.getActivity(activity, 0, intent, flags);
        AlarmManager manager = (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.set(AlarmManager.RTC, System.currentTimeMillis() + 300, pending);
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
