package com.fongmi.android.tv.music;

import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

/**
 * 音乐功能的设置。
 *
 * 注意：同步服务器的地址和密码一律默认为空，不会预填任何测试值，
 * 用户不填就是没配，不会悄悄连到某个服务器上。
 */
public class MusicSetting {

    /** 音质档位 */
    public static final String[] QUALITYS = {"128k", "320k", "flac", "flac24bit"};

    public static String getUrl() {
        return Prefers.getString("music_url").trim();
    }

    public static void putUrl(String url) {
        Prefers.put("music_url", url == null ? "" : url.trim());
    }

    public static String getPass() {
        return Prefers.getString("music_pass").trim();
    }

    public static void putPass(String pass) {
        Prefers.put("music_pass", pass == null ? "" : pass.trim());
    }

    public static String getQuality() {
        String quality = Prefers.getString("music_quality").trim();
        return quality.isEmpty() ? "320k" : quality;
    }

    public static void putQuality(String quality) {
        Prefers.put("music_quality", quality == null ? "" : quality.trim());
    }

    /** 自建解析服务地址（music_jx 之类），留空就用内置方案 */
    public static String getJx() {
        return Prefers.getString("music_jx").trim();
    }

    public static void putJx(String jx) {
        Prefers.put("music_jx", jx == null ? "" : jx.trim());
    }

    /** 音源脚本地址，留空用内置那一份 */
    public static String getScript() {
        return Prefers.getString("music_script").trim();
    }

    public static void putScript(String script) {
        Prefers.put("music_script", script == null ? "" : script.trim());
    }

    public static boolean isLyric() {
        return Prefers.getBoolean("music_lyric", true);
    }

    public static void putLyric(boolean lyric) {
        Prefers.put("music_lyric", lyric);
    }

    /** 随机播放开关，下次进播放页还是这个状态 */
    public static boolean isShuffle() {
        return Prefers.getBoolean("music_shuffle", false);
    }

    public static void putShuffle(boolean shuffle) {
        Prefers.put("music_shuffle", shuffle);
    }

    /** 服务器地址：把末尾多余的 / 去掉 */
    public static String getBase() {
        String url = getUrl();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static boolean isValid() {
        return getBase().startsWith("http");
    }

    public static String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("url", getUrl());
            object.put("pass", getPass());
            object.put("quality", getQuality());
            object.put("jx", getJx());
            object.put("script", getScript());
            object.put("lyric", isLyric());
        } catch (Exception ignored) {
        }
        return object.toString();
    }

    public static void fromJson(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            JSONObject object = new JSONObject(text);
            putUrl(object.optString("url"));
            putPass(object.optString("pass"));
            putQuality(object.optString("quality"));
            putJx(object.optString("jx"));
            putScript(object.optString("script"));
            putLyric(object.optBoolean("lyric", true));
        } catch (Exception ignored) {
        }
    }
}
