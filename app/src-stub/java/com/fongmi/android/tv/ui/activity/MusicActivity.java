package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;

/**
 * 『音乐』功能关掉时的占位实现。
 *
 * 真正的实现在 app/music-src/java/com/fongmi/android/tv/ui/activity/MusicActivity.java，
 * 只有当 rootProject.music = true 时那个目录才会被加进 sourceSets。
 *
 * 之所以还要留一个空 Activity：AndroidManifest.xml 里注册了这两个页面，
 * manifest 合并阶段要求类真实存在，所以这里必须撑住。
 *
 * enabled() 给首页入口用 —— 关掉时不显示「音乐」这一格 / 这一个底部导航项。
 */
public class MusicActivity extends Activity {

    public static boolean enabled() {
        return false;
    }

    public static void start(Activity activity) {
    }

    public static void startRandom(Context context) {
    }
}
