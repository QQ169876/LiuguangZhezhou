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

/**
 * 同步状态小浮层：右下角一小条，告诉用户后台正在同步、同步完了还是没同步成。
 *
 * 同步是自动跑的，以前全程静默——数据到底有没有跟上、地址配错没有，用户完全看不见，
 * 只能靠猜。这里给个最小打扰的反馈：开始时亮一下，结束后把结果亮两秒半再自己收掉。
 *
 * 三条规矩：
 * 1. 播放页不显示（正看着片子，右下角冒个框很烦）；
 * 2. 完成后自动消失，不常驻；
 * 3. 不抢焦点——TV 上焦点一乱，遥控器就不好使了。
 */
public class SyncStatus implements Application.ActivityLifecycleCallbacks {

    private static final long HOLD = 2500; // 出结果后再亮这么久

    private static final SyncStatus INSTANCE = new SyncStatus();

    private final Runnable hide = this::detach;
    private WeakReference<Activity> host;
    private TextView view;
    private String text;

    public static void install(Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof Application) ((Application) app).registerActivityLifecycleCallbacks(INSTANCE);
    }

    /** 同步开始：labelRes 是「影视站(webdav)」「影视站(moontv)」这类来源名 */
    public static void begin(@StringRes int labelRes) {
        String label = ResUtil.getString(labelRes);
        String msg = label + ResUtil.getString(R.string.sync_status_running);
        App.post(() -> INSTANCE.show(msg, true));
    }

    /** 同步结束：成功亮「同步完成」，失败亮「同步失败」 */
    public static void finish(@StringRes int labelRes, boolean success) {
        String label = ResUtil.getString(labelRes);
        String msg = label + ResUtil.getString(success ? R.string.sync_status_done : R.string.sync_status_fail);
        App.post(() -> INSTANCE.show(msg, false));
    }

    /** 出结果的那一趟：亮完自动收 */
    private void show(String msg, boolean running) {
        text = msg;
        if (running) App.removeCallbacks(hide);
        else App.post(hide, HOLD);
        Activity act = App.activity();
        if (act == null) return;
        attach(act);
    }

    private void attach(Activity act) {
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
    }

    private void detach() {
        App.removeCallbacks(hide);
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
        tv.setSingleLine(true);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.setClickable(true);
        tv.setOnClickListener(v -> detach()); // 不想看就点掉
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
        // 上一趟同步还在跑的时候从播放页退回来了，把没显示成的那一段补上
        if (text != null) attach(activity);
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
