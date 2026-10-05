package com.fongmi.android.tv.music;

/**
 * 『音乐』功能关掉时的占位实现，见同级 MusicSetting 的说明。
 *
 * 罗雪同步在关闭状态下不会有任何网络行为，回调里直接告诉调用方「没开」。
 */
public class LxSync {

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    public static void sync(Callback callback) {
        if (callback != null) callback.onDone(false, "music disabled");
    }
}
