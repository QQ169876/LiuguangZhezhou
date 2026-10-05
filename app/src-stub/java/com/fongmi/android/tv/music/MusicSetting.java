package com.fongmi.android.tv.music;

/**
 * 『音乐』功能关掉时的占位实现。
 *
 * 真正的实现在 app/music-src/java/com/fongmi/android/tv/music/MusicSetting.java，
 * 只有当 rootProject.music = true（写在 local.properties 里）时那个目录才会被加进 sourceSets。
 * 两套源码同名同类、互斥入选，所以项目里其它地方（Nano / Action）不用改一行。
 *
 * toJson() 返回 disabled = true，网页遥控端据此把音乐面板藏起来。
 */
public class MusicSetting {

    public static final String[] QUALITYS = {"128k", "320k", "flac", "flac24bit"};

    public static String getUrl() {
        return "";
    }

    public static void putUrl(String url) {
    }

    public static String getPass() {
        return "";
    }

    public static void putPass(String pass) {
    }

    public static String getQuality() {
        return "128k";
    }

    public static void putQuality(String quality) {
    }

    public static String getJx() {
        return "";
    }

    public static void putJx(String jx) {
    }

    public static String getScript() {
        return "";
    }

    public static void putScript(String script) {
    }

    public static boolean isLyric() {
        return true;
    }

    public static void putLyric(boolean lyric) {
    }

    public static boolean isShuffle() {
        return false;
    }

    public static void putShuffle(boolean shuffle) {
    }

    public static String getBase() {
        return "";
    }

    public static boolean isValid() {
        return false;
    }

    public static String toJson() {
        return "{\"disabled\":true}";
    }

    public static void fromJson(String text) {
    }
}
