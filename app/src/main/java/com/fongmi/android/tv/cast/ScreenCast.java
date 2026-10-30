package com.fongmi.android.tv.cast;

import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.App;

/**
 * 屏幕广播的总开关（设备级的，跟播不播视频没关系）。
 *
 * 为什么单独搁这一层：录屏这件事要经过**系统授权**，而且安卓 14 起改成「按次给」的授权，
 * 每次开都还得再点一次 —— 这跟视频广播那种「按钮按下去就开始」的流程不一样，
 * 中间必须垫一个透明的中间页去拿授权结果（见 {@link ScreenRequestActivity}），
 * 这样调用方（播放页、首页）都不必为了这个权限去改自己的生命周期。
 */
public final class ScreenCast {

    private ScreenCast() {
    }

    public static boolean casting() {
        return ScreenCastService.running();
    }

    /** 弹系统的录屏授权；同意之后 {@link ScreenCastService} 会自己起来 */
    public static void request() {
        try {
            Intent intent = new Intent(App.get(), ScreenRequestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            App.get().startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /** 收工 */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, ScreenCastService.class));
        } catch (Throwable ignored) {
        }
    }
}
