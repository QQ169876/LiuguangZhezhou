package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;

import com.github.catvod.utils.Prefers;

/**
 * 播放页「显示设置」：**画面上露什么，这里就有什么开关**（马先生 2026-10-15）。
 *
 * 每条信息各管各的、各占一行，**并列显示不互相覆盖**，打开哪个就多露哪个，可以同时全开：
 *   左上：视频名称（剧名 / 片名）、当前集数、分辨率
 *   右上：年月日、当前时间（精确到秒）、已播时长、剩余时长
 *   最底部：进度条 —— 迷你（一道细线）/ 完整（粗一点的那种）/ 都不显示
 *
 * 老版本存过「时间四选一」（screen_time_mode）的，第一次读的时候自动搬过来，用户设置不丢。
 */
public class ScreenSetting {

    /** 底部进度条的样式：不显示 / 迷你细线 / 完整进度条 */
    public static final int PROGRESS_OFF = 0;
    public static final int PROGRESS_MINI = 1;
    public static final int PROGRESS_FULL = 2;

    public static boolean isShowTitle() {
        migrate();
        return Prefers.getBoolean("screen_show_title", true);
    }

    public static void putShowTitle(boolean on) {
        Prefers.put("screen_show_title", on);
    }

    public static boolean isShowEpisode() {
        migrate();
        return Prefers.getBoolean("screen_show_episode", true);
    }

    public static void putShowEpisode(boolean on) {
        Prefers.put("screen_show_episode", on);
    }

    /** 分辨率，跟名称并排显示在左上角（默认关：马先生要过去掉这项，现在按他说的留成开关） */
    public static boolean isShowSize() {
        migrate();
        return Prefers.getBoolean("screen_show_size", false);
    }

    public static void putShowSize(boolean on) {
        Prefers.put("screen_show_size", on);
    }

    /** 年月日 */
    public static boolean isShowDate() {
        migrate();
        return Prefers.getBoolean("screen_show_date", false);
    }

    public static void putShowDate(boolean on) {
        Prefers.put("screen_show_date", on);
    }

    /** 当前时间（跟着系统时区走，精确到秒） */
    public static boolean isShowClock() {
        migrate();
        return Prefers.getBoolean("screen_show_clock", true);
    }

    public static void putShowClock(boolean on) {
        Prefers.put("screen_show_clock", on);
    }

    /** 这一集已经播了多久 */
    public static boolean isShowPlayed() {
        migrate();
        return Prefers.getBoolean("screen_show_played", false);
    }

    public static void putShowPlayed(boolean on) {
        Prefers.put("screen_show_played", on);
    }

    /** 这一集还剩多久 */
    public static boolean isShowLeft() {
        migrate();
        return Prefers.getBoolean("screen_show_left", false);
    }

    public static void putShowLeft(boolean on) {
        Prefers.put("screen_show_left", on);
    }

    public static int getProgressStyle() {
        migrate();
        int style = Prefers.getInt("screen_progress_style", PROGRESS_MINI);
        return style < PROGRESS_OFF || style > PROGRESS_FULL ? PROGRESS_MINI : style;
    }

    public static void putProgressStyle(int style) {
        Prefers.put("screen_progress_style", style);
    }

    /**
     * 老版本是「时间四选一」（0 不显示 / 1 实时 / 2 已播 / 3 剩余）+「进度条开不开」，
     * 现在是各自独立的开关。第一次用到的时候搬一次，搬完打个标记，别每次都折腾。
     */
    private static void migrate() {
        if (Prefers.getBoolean("screen_migrated", false)) return;
        Prefers.put("screen_migrated", true);
        int mode = Prefers.getInt("screen_time_mode", -1);
        if (mode < 0) return; // 新装的，直接走默认值
        boolean clock = mode == 1;
        boolean played = mode == 2;
        boolean left = mode == 3;
        Prefers.put("screen_show_clock", clock);
        Prefers.put("screen_show_played", played);
        Prefers.put("screen_show_left", left);
        Prefers.put("screen_progress_style", Prefers.getBoolean("screen_show_progress", true) ? PROGRESS_MINI : PROGRESS_OFF);
    }

    /** 左上角那一行：剧名 + 当前集数，各自按开关拼（两个都关就是空，调用方据此整块藏掉） */
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
