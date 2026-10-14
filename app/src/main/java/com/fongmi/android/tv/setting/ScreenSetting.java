package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;

import com.github.catvod.utils.Prefers;

/**
 * 播放页「屏幕上显示什么」：剧名、当前集数、当前时间（墙上时钟）、底部那根极细的进度条。
 * 有的片子上下都有黑边，字幕挪到上面之后，标题再压在顶上就会打架 —— 想露什么、不想露什么自己挑。
 */
public class ScreenSetting {

    public static boolean isShowTitle() {
        return Prefers.getBoolean("screen_show_title", true);
    }

    public static void putShowTitle(boolean on) {
        Prefers.put("screen_show_title", on);
    }

    public static boolean isShowEpisode() {
        return Prefers.getBoolean("screen_show_episode", true);
    }

    public static void putShowEpisode(boolean on) {
        Prefers.put("screen_show_episode", on);
    }

    /**
     * 右上角那个时间：**先有开关，开关开着再挑显示什么** —— 实时时间 / 已播了多久 / 还剩多久。
     * 开关和内容是两件事，别混成一个「四选一」（马先生 2026-10-15：几项显示各自独立，
     * 打开哪个就显示哪个，不是选了时间就不显示剧名）。
     */
    public static final int TIME_OFF = 0; // 只用来兼容老版本里存过的「不显示」
    public static final int TIME_NOW = 1;
    public static final int TIME_PLAYED = 2;
    public static final int TIME_LEFT = 3;

    public static boolean isShowClock() {
        if (Prefers.getInt("screen_time_mode", -1) == TIME_OFF) return false; // 老版本里选过「不显示」的，继续不显示
        return Prefers.getBoolean("screen_show_clock", true);
    }

    public static void putShowClock(boolean on) {
        Prefers.put("screen_show_clock", on);
        if (on && Prefers.getInt("screen_time_mode", -1) == TIME_OFF) Prefers.put("screen_time_mode", TIME_NOW); // 把老版本的「不显示」翻过来
    }

    public static int getTimeMode() {
        int mode = Prefers.getInt("screen_time_mode", TIME_NOW);
        return mode < TIME_NOW || mode > TIME_LEFT ? TIME_NOW : mode;
    }

    public static void putTimeMode(int mode) {
        Prefers.put("screen_time_mode", mode);
        Prefers.put("screen_show_clock", true); // 挑了内容就等于要把时间显示出来
    }

    public static boolean isShowProgress() {
        return Prefers.getBoolean("screen_show_progress", true);
    }

    public static void putShowProgress(boolean on) {
        Prefers.put("screen_show_progress", on);
    }

    /** 整块标题：剧名 + 当前集数，各自按开关拼，两个都关就是空（调用方据此把标题整块藏掉） */
    public static String title(@Nullable MediaMetadata metadata) {
        CharSequence name = metadata == null ? null : metadata.title;
        CharSequence episode = metadata == null ? null : metadata.artist;
        StringBuilder text = new StringBuilder();
        if (isShowTitle() && !TextUtils.isEmpty(name)) text.append(name);
        if (isShowEpisode() && !TextUtils.isEmpty(episode)) {
            if (text.length() > 0) text.append(" - ");
            text.append(episode);
        }
        return text.toString();
    }
}
