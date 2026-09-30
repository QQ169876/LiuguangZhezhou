package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;


import com.fongmi.android.tv.App;

/**
 * 开屏页：盖在主界面上面，等点播源配置加载完再撤，但最少也要停 3 秒，
 * 免得网速快的时候一闪而过。撤的时候淡出，不会生硬地跳一下。
 */
public class SplashOverlay {

    private static final long MIN_SHOW = 3000;

    private final FrameLayout layer;
    private final ViewGroup parent;
    private boolean loaded;
    private boolean gone;

    private SplashOverlay(Activity activity) {
        layer = new FrameLayout(activity);
        layer.setBackgroundColor(0xFF000000);
        SplashLogoView logo = new SplashLogoView(activity, null);
        int size = Math.round(140 * activity.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER;
        layer.addView(logo, params);
        parent = activity.findViewById(android.R.id.content);
    }

    /**
     * 盖到页面上。返回的对象要在配置加载完时调一次 {@link #loaded()}。
     */
    public static SplashOverlay attach(Activity activity) {
        SplashOverlay overlay = new SplashOverlay(activity);
        overlay.show();
        return overlay;
    }

    private void show() {
        if (parent == null) {
            gone = true;
            return;
        }
        App.post(() -> parent.addView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)), 0);
        App.post(this::maybe, MIN_SHOW);
    }

    /** 点播源配置加载完了（成功失败都算完） */
    public void loaded() {
        loaded = true;
        maybe();
    }

    private synchronized void maybe() {
        if (gone || !loaded) return;
        gone = true;
        App.post(this::fade, 0);
    }

    private void fade() {
        if (layer.getParent() == null) return;
        AlphaAnimation animation = new AlphaAnimation(1f, 0f);
        animation.setDuration(320);
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                App.post(() -> {
                    if (layer.getParent() != null) parent.removeView(layer);
                }, 0);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        layer.startAnimation(animation);
    }

}
