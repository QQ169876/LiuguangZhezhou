package com.fongmi.android.tv.cast;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.ReceiveActivity;

/**
 * 首页那个「多机同步」入口弹出的小菜单。
 *
 * 三件事放在一处：接收别人的广播、把本机屏幕广播出去、改选项。
 * 之所以要这个菜单而不是三个按钮：屏幕广播**必须先过系统授权**，
 * 而且安卓 14 起是「按次授权」（每次开都要点一次），跟另外两个不同，
 * 搁在一起用户点一下就知道发生了什么，不会莫名其妙跳一个系统弹窗。
 */
public final class CastMenu {

    private CastMenu() {
    }

    public static void show(Context context) {
        boolean casting = ScreenCast.casting();
        CharSequence[] items = {
                context.getString(R.string.cast_receive),
                context.getString(casting ? R.string.cast_screen_off : R.string.cast_screen_on),
                context.getString(R.string.cast_setting)
        };
        new AlertDialog.Builder(context).setTitle(R.string.cast_pick).setItems(items, (dialog, which) -> {
            if (which == 0) ReceiveActivity.start(context);
            else if (which == 1) toggle(context, casting);
            else CastOption.show(context);
        }).show();
    }

    private static void toggle(Context context, boolean casting) {
        if (casting) ScreenCast.stop(context);
        else ScreenCast.request();
    }
}
