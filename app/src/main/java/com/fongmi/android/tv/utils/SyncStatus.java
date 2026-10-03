package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 同步状态小浮层：右下角一小条，告诉用户后台正在同步、同步完了还是没同步成。
 *
 * 两路同步各占一格：WebDAV 那路写「WebDAV同步中… / WebDAV同步完成 / WebDAV同步失败」，
 * 影视站那路写「影视站同步中… / 影视站同步完成 / 影视站同步失败」；
 * 只启用哪路就只显示哪路，两路都启用就各自一格、左右并排，互不顶替也绝不叠在一起
 * （马先生 2026-10-03 定的）。每一路出结果后亮 2.5 秒就收掉自己那一格。
 *
 * 三条规矩：
 * 1. 播放页不显示（正看着片子，右下角冒个框很烦）；
 * 2. 完成后自动消失，不常驻（到期把这一路的状态整个删掉，切页面也不会再冒出来）；
 * 3. 不抢焦点——TV 上焦点一乱，遥控器就不好使了。
 */
public class SyncStatus implements Application.ActivityLifecycleCallbacks {

    private static final long HOLD = 2500; // 出结果后再亮这么久
    private static final long RUNNING_MAX = 3 * 60 * 1000; // 「正在同步」的兜底寿命，防半路死掉留一行常驻

    private static final SyncStatus INSTANCE = new SyncStatus();

    /** 一路同步的一格状态 */
    private static final class Row {
        String text;
        Runnable hide; // 到期收掉这一格
    }

    private final Map<Integer, Row> rows = new LinkedHashMap<>(); // key = 来源的 labelRes，保持先来后到
    private WeakReference<Activity> host;
    private LinearLayout bar; // 装状态格的横条容器，靠右下角；两路都在就左右并排

    public static void install(Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof Application) ((Application) app).registerActivityLifecycleCallbacks(INSTANCE);
    }

    /**
     * 同步开始：labelRes 是来源名（WebDAV / 影视站），浮层写「影视站同步中…」这种。
     * 用格式化字符串拼，中文不空格、英文带空格，各语言自己说了算。
     */
    public static void begin(@StringRes int labelRes) {
        String msg = String.format(ResUtil.getString(R.string.sync_status_running), ResUtil.getString(labelRes));
        App.post(() -> INSTANCE.update(labelRes, msg, true));
    }

    /** 同步结束：成功亮「…同步完成」，失败亮「…同步失败」 */
    public static void finish(@StringRes int labelRes, boolean success) {
        String msg = String.format(ResUtil.getString(success ? R.string.sync_status_done : R.string.sync_status_fail), ResUtil.getString(labelRes));
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
        // 一路一格：按先来后到排出各自的文字，两路都在就并排两格
        LinkedHashSet<String> items = new LinkedHashSet<>();
        for (Row row : rows.values()) {
            if (row.text != null && row.text.length() > 0) items.add(row.text);
        }
        Activity act = App.activity();
        if (act == null) return;
        attach(act, new ArrayList<>(items));
    }

    private void attach(Activity act, List<String> texts) {
        try {
            if (skip(act) || texts.isEmpty()) return;
            Activity old = host == null ? null : host.get();
            if (old != null && old != act) {
                if (bar != null && bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
                bar = null;
            }
            host = new WeakReference<>(act);
            if (bar == null) bar = createBar(act);
            bar.removeAllViews();
            int gap = (int) (8 * act.getResources().getDisplayMetrics().density);
            for (int i = 0; i < texts.size(); i++) {
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) ip.leftMargin = gap; // 格与格之间留缝，绝不叠在一起
                bar.addView(createItem(act, texts.get(i)), ip);
            }
            if (bar.getParent() == null) {
                ViewGroup root = act.findViewById(android.R.id.content);
                if (root == null) return;
                root.addView(bar, params());
            }
            bar.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
            // 状态条只是提示，任何机器上加不上去都不许把 App 带崩
        }
    }

    /** 只摘 view，不动 rows：正在跑的同步切了页面还得接着显示，到期自清 */
    private void detach() {
        if (bar == null) return;
        bar.setVisibility(View.GONE);
        if (bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
        bar = null;
        host = null;
    }

    /** 外层横条：靠右下角，里面一格一路 */
    private LinearLayout createBar(Context context) {
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setFocusable(false);
        ll.setFocusableInTouchMode(false);
        ll.setClickable(true);
        ll.setOnClickListener(v -> {
            rows.clear(); // 不想看就点掉，连状态一起清，别过会儿又冒出来
            detach();
        });
        return ll;
    }

    /** 一格：一个独立的状态条，自带底色和外框 */
    private TextView createItem(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setBackgroundResource(R.drawable.sync_status_bg);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(12);
        tv.setSingleLine(true);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.setClickable(false); // 点击交给外层容器，点哪儿都算点掉整条
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
