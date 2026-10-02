package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同步状态小浮层：右下角一小条，告诉用户后台正在同步、同步完了还是没同步成。
 *
 * 两路同步（影视站 webdav / 影视站 moontv）各占一行：哪个在跑就显示哪个，
 * 两个都在跑就两行一起亮，互不顶替。每行出结果后亮 2.5 秒就收掉自己那行。
 *
 * 三条规矩：
 * 1. 播放页不显示（正看着片子，右下角冒个框很烦）；
 * 2. 完成后自动消失，不常驻（到期把这一行的状态整个删掉，切页面也不会再冒出来）；
 * 3. 不抢焦点——TV 上焦点一乱，遥控器就不好使了。
 */
public class SyncStatus implements Application.ActivityLifecycleCallbacks {

    private static final long HOLD = 2500; // 出结果后再亮这么久
    private static final long RUNNING_MAX = 3 * 60 * 1000; // 「正在同步」的兜底寿命，防半路死掉留一行常驻

    private static final SyncStatus INSTANCE = new SyncStatus();

    /** 一路同步的一行状态 */
    private static final class Row {
        String text;
        Runnable hide; // 到期收掉这一行
    }

    private final Map<Integer, Row> rows = new LinkedHashMap<>(); // key = 来源的 labelRes，保持先来后到
    private WeakReference<Activity> host;
    private TextView view;

    public static void install(Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof Application) ((Application) app).registerActivityLifecycleCallbacks(INSTANCE);
    }

    /** 同步开始：labelRes 是「影视站(webdav)」「影视站(moontv)」这类来源名 */
    public static void begin(@StringRes int labelRes) {
        String msg = ResUtil.getString(labelRes) + ResUtil.getString(R.string.sync_status_running);
        App.post(() -> INSTANCE.update(labelRes, msg, true));
    }

    /** 同步结束：成功亮「同步完成」，失败亮「同步失败」 */
    public static void finish(@StringRes int labelRes, boolean success) {
        String msg = ResUtil.getString(labelRes) + ResUtil.getString(success ? R.string.sync_status_done : R.string.sync_status_fail);
        App.post(() -> INSTANCE.update(labelRes, msg, false));
    }

    private void update(int labelRes, String msg, boolean running) {
        Row row = rows.get(labelRes);
        if (row == null) {
            row = new Row();
            int key = labelRes;
            row.hide = () -> expire(key);
            rows.put(labelRes, row);
        }
        row.text = msg;
        App.removeCallbacks(row.hide);
        App.post(row.hide, running ? RUNNING_MAX : HOLD); // 跑着的给兜底寿命，出结果的 2.5 秒后收
        render();
    }

    /** 这一行到点收摊：状态一起删掉，之后切页面也不会再把它贴回来 */
    private void expire(int labelRes) {
        rows.remove(labelRes);
        render();
    }

    private void render() {
        if (rows.isEmpty()) {
            detach();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Row row : rows.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(row.text);
        }
        Activity act = App.activity();
        if (act == null) return;
        attach(act, sb.toString());
    }

    private void attach(Activity act, String text) {
        try {
            if (skip(act)) return;
            Activity old = host == null ? null : host.get();
            if (old != null && old != act) {
                if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                view = null;
            }
            host = new WeakReference<>(act);
            if (view == null) view = create(act);
            view.setText(text);
            if (view.getParent() == null) {
                ViewGroup root = act.findViewById(android.R.id.content);
                if (root == null) return;
                root.addView(view, params());
            }
            view.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
            // 状态条只是提示，任何机器上加不上去都不许把 App 带崩
        }
    }

    /** 只摘 view，不动 rows：正在跑的同步切了页面还得接着显示，到期自清 */
    private void detach() {
        if (view == null) return;
        view.setVisibility(View.GONE);
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        view = null;
        host = null;
    }

    private TextView create(Context context) {
        TextView tv = new TextView(context);
        tv.setBackgroundResource(R.drawable.sync_status_bg);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(12);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
            rows.clear(); // 不想看就点掉，连状态一起清，别过会儿又冒出来
            detach();
        });
        int pad = (int) (10 * context.getResources().getDisplayMetrics().density);
        tv.setPadding(pad * 2, pad, pad * 2, pad);
        return tv;
    }

    private FrameLayout.LayoutParams params() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        int m = (int) (16 * App.get().getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    /** 播放页不打扰：TV 和手机的播放页类名都带这几个词 */
    private static boolean skip(Activity act) {
        String name = act.getClass().getSimpleName();
        return name.contains("Video") || name.contains("Live") || name.contains("Playback");
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // 同步还在跑（或结果还没收）的时候从播放页退回来了，把没显示成的那段补上；
        // 已收掉的行状态已删，不会在这里死灰复燃
        if (!rows.isEmpty()) render();
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        Activity old = host == null ? null : host.get();
        if (old == activity) detach();
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable android.os.Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull android.os.Bundle outState) {
    }
}
