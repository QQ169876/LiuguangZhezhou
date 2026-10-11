package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class PreloadSetting {

    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 10;
    public static final int MIN_SIZE_MB = 128;
    public static final int MAX_SIZE_MB = 4096;
    public static final int STEP_SIZE_MB = 128;
    public static final int MIN_TIME_SECONDS = 20;
    public static final int MAX_TIME_SECONDS = 120;
    public static final int STEP_TIME_SECONDS = 10;

    public static boolean isEnabled() {
        return Prefers.getBoolean("preload");
    }

    public static void putEnabled(boolean preload) {
        Prefers.put("preload", preload);
    }

    /**
     * 下一集预读：默认开。
     *
     * 这两件事要分开看：
     * - 「预加载」总开关管的是**边播边把整片往磁盘缓存**（ExoDiskPreload），费磁盘、费带宽，所以默认关；
     * - 「预读下一集」管的是**提前把下一集的播放地址问出来**（能预取流数据的就顺手预取一点），
     *   只是多一趟站点请求，换来的是切集不再转圈，这是默认就该有的体验。
     * 以前这两件事被捆在一个开关上，总开关一关，下一集预读跟着一起没了——表现出来就是
     * 「调下一集要等一会儿」。现在解耦：预读只看自己这个开关。
     */
    public static boolean isNextEpisodeEnabled() {
        return Prefers.getBoolean("preload_next_episode", true);
    }

    public static void putNextEpisodeEnabled(boolean enabled) {
        Prefers.put("preload_next_episode", enabled);
    }

    public static int getThreads() {
        return Math.clamp(Prefers.getInt("preload_threads", MIN_THREADS), MIN_THREADS, MAX_THREADS);
    }

    public static void putThreads(int threads) {
        Prefers.put("preload_threads", Math.clamp(threads, MIN_THREADS, MAX_THREADS));
    }

    public static int getSizeMb() {
        int size = Math.clamp(Prefers.getInt("preload_size", MIN_SIZE_MB), MIN_SIZE_MB, MAX_SIZE_MB);
        return Math.clamp(MIN_SIZE_MB + (long) Math.round((float) (size - MIN_SIZE_MB) / STEP_SIZE_MB) * STEP_SIZE_MB, MIN_SIZE_MB, MAX_SIZE_MB);
    }

    public static void putSizeMb(int size) {
        Prefers.put("preload_size", Math.clamp(size, MIN_SIZE_MB, MAX_SIZE_MB));
    }

    public static long getSizeBytes() {
        return getSizeMb() * 1024L * 1024L;
    }

    public static int getTimeSeconds() {
        int seconds = Math.clamp(Prefers.getInt("preload_time", MAX_TIME_SECONDS), MIN_TIME_SECONDS, MAX_TIME_SECONDS);
        return Math.clamp(MIN_TIME_SECONDS + (long) Math.round((float) (seconds - MIN_TIME_SECONDS) / STEP_TIME_SECONDS) * STEP_TIME_SECONDS, MIN_TIME_SECONDS, MAX_TIME_SECONDS);
    }

    public static void putTimeSeconds(int seconds) {
        Prefers.put("preload_time", Math.clamp(seconds, MIN_TIME_SECONDS, MAX_TIME_SECONDS));
    }

    public static long getDurationMs() {
        return getTimeSeconds() * 1000L;
    }
}
