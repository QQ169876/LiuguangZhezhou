package com.fongmi.android.tv.music;

import com.fongmi.android.tv.utils.DebugLog;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内置直连：不依赖任何第三方转发，自己把平台的算法算完。
 *
 * 网易云走 eapi（AES-128-ECB + md5 摘要，免登录），酷我走 anti.s，
 * 这两个都是实测能出真实音频地址的。音源脚本挂了的时候靠它们兜底，
 * 不至于整个音乐功能开天窗。
 */
public class MusicDirect {

    private static final String WY_KEY = "e82ckenh8dichen8";
    private static final String WY_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154";
    private static final String WY_COOKIE = "os=pc; appver=; osver=; deviceId=pyncm!;";

    public static String level(String quality) {
        return switch (quality) {
            case "320k" -> "exhigh";
            case "flac" -> "lossless";
            case "flac24bit" -> "hires";
            default -> "standard";
        };
    }

    /* ---------- 对外 ---------- */

    public static String url(Music music, String quality) {
        String source = music.getSource();
        if ("wy".equals(source)) return wyUrl(music, quality);
        if ("kw".equals(source)) return kwUrl(music, quality);
        return "";
    }

    /** 网易 eapi：自持算法，不依赖任何第三方服务器，取地址的主路 */
    public static boolean isWy(Music music) {
        return music != null && "wy".equals(music.getSource());
    }

    /**
     * 这首歌能不能不靠第三方取到地址（wy 是可靠的自持算法）。
     *
     * 注意：**酷我不算**。它的 anti.s 官方已限制非客户端调用，多数设备拿回来的是
     * 「下载酷我音乐客户端」的提示页而不是音频（马先生实机确认播不成），
     * 所以只用最后一次兜底，失败跳曲时不该往酷我上跳。
     */
    public static boolean support(Music music) {
        return isWy(music);
    }

    public static String lyric(Music music) {
        String source = music.getSource();
        if ("wy".equals(source)) return wyLyric(music);
        if ("kw".equals(source)) return kwLyric(music);
        return "";
    }

    /* ---------- 网易云 ---------- */

    private static String wyUrl(Music music, String quality) {
        try {
            JSONObject header = wyHeader();
            JSONObject payload = new JSONObject();
            payload.put("ids", new JSONArray().put(Long.parseLong(music.getSongId())));
            payload.put("level", level(quality));
            payload.put("encodeType", "flac");
            payload.put("header", header.toString());
            JSONObject result = eapi("/song/enhance/player/url/v1", payload);
            JSONArray data = result.optJSONArray("data");
            if (data == null) return "";
            for (int i = 0; i < data.length(); i++) {
                String url = data.optJSONObject(i).optString("url");
                if (url.startsWith("http")) return url;
            }
        } catch (Throwable e) {
            DebugLog.d("MusicDirect", "wy 取址失败 " + e);
        }
        return "";
    }

    private static String wyLyric(Music music) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("id", Long.parseLong(music.getSongId()));
            payload.put("cp", false);
            payload.put("tv", 0);
            payload.put("lv", 0);
            payload.put("rv", 0);
            payload.put("yv", 0);
            payload.put("ytv", 0);
            payload.put("yrv", 0);
            payload.put("header", wyHeader().toString());
            JSONObject result = eapi("/song/lyric", payload);
            // lrc 是个对象 {"version":2,"lyric":"..."}，直接 optString 会把整个 JSON 文本拿出来
            JSONObject lrc = result.optJSONObject("lrc");
            return lrc == null ? "" : lrc.optString("lyric", "");
        } catch (Throwable e) {
            return "";
        }
    }

    private static JSONObject wyHeader() {
        JSONObject header = new JSONObject();
        try {
            header.put("os", "pc");
            header.put("appver", "");
            header.put("osver", "");
            header.put("deviceId", "pyncm!");
            header.put("requestId", String.valueOf(20000000 + (int) (Math.random() * 10000000)));
        } catch (Exception ignored) {
        }
        return header;
    }

    private static JSONObject eapi(String path, JSONObject payload) throws Exception {
        String url2 = "/api" + path;
        String digest = LxCrypto.md5("nobody" + url2 + "use" + payload + "md5forencrypt");
        String params = url2 + "-36cd479b6b5-" + payload + "-36cd479b6b5-" + digest;
        String body = "params=" + aesHex(params);
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", WY_UA);
        headers.put("Referer", "");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Cookie", WY_COOKIE);
        String text = post("https://interface3.music.163.com/eapi" + path, headers, body);
        return new JSONObject(text);
    }

    private static String aesHex(String text) throws Exception {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int pad = 16 - (raw.length % 16);
        byte[] input = new byte[raw.length + pad];
        System.arraycopy(raw, 0, input, 0, raw.length);
        for (int i = raw.length; i < input.length; i++) input[i] = (byte) pad;
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(WY_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] out = cipher.doFinal(input);
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /* ---------- 酷我 ---------- */

    /**
     * 酷我 anti.s。这条路已经半废：官方只对客户端放行，非客户端设备常被回一个
     * 「下载酷我音乐客户端」的提示页（HTML）而不是音频 URL（马先生实机确认）。
     * 所以 {@link MusicSource} 把它排在最后兜底，主路走脚本音源。
     */
    /**
     * 先把 anti.s 的 302 目标解出来再交给播放器：一是省一次跳转，二是这样
     * MusicGuard 拿到的是 CDN 上真实的文件名，才知道两首歌是不是同一个音频
     * （anti.s?rid=xxx 那一层，每首歌的 URL 看着都是 anti.s，没法比对）。
     */
    private static String kwUrl(Music music, String quality) {
        String format = "flac".equals(quality) || "flac24bit".equals(quality) ? "flac" : "mp3";
        String br = "320k".equals(quality) ? "&br=320kmp3" : "";
        String location = follow("http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=" + music.getSongId() + "&format=" + format + "&response=res" + br);
        return location.startsWith("http") ? location : "";
    }

    /** 只取跳到哪去，不下载内容 */
    private static String follow(String url) {
        try {
            okhttp3.OkHttpClient client = OkHttp.client().newBuilder().followRedirects(false).followSslRedirects(false).build();
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).head().header("User-Agent", "okhttp/3.10.0").build();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                String location = response.header("Location");
                if (location == null || location.isEmpty()) return "";
                if (location.startsWith("/")) {
                    okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(url);
                    if (base == null) return "";
                    okhttp3.HttpUrl resolved = base.resolve(location);
                    return resolved == null ? "" : resolved.toString();
                }
                return location;
            }
        } catch (Throwable e) {
            DebugLog.d("MusicDirect", "kw 跳转解析失败 " + e);
            return "";
        }
    }

    private static String kwLyric(Music music) {
        try {
            String text = OkHttp.string("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" + music.getSongId());
            JSONObject data = new JSONObject(text).optJSONObject("data");
            if (data == null) return "";
            JSONArray list = data.optJSONArray("lrclist");
            if (list == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                sb.append(time(Double.parseDouble(item.optString("time", "0"))));
                sb.append(item.optString("lineLyric", "")).append("\n");
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String time(double second) {
        int total = (int) Math.floor(second);
        int mm = total / 60;
        int ss = total % 60;
        int xx = (int) ((second - total) * 100);
        return String.format("[%02d:%02d.%02d]", mm, ss, xx);
    }

    /* ---------- 自建解析服务 ---------- */

    /** music_jx 之类的自建服务，填了就用它先试 */
    public static String jxUrl(Music music, String quality) {
        String base = MusicSetting.getJx();
        if (base.isEmpty() || !"wy".equals(music.getSource())) return "";
        try {
            String url = base + "/163music.php?type=url&id=" + music.getSongId() + "&level=" + level(quality);
            JSONObject result = new JSONObject(OkHttp.string(url));
            JSONArray data = result.optJSONArray("data");
            if (data != null && data.length() > 0) return data.optJSONObject(0).optString("url");
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String jxLyric(Music music) {
        String base = MusicSetting.getJx();
        if (base.isEmpty() || !"wy".equals(music.getSource())) return "";
        try {
            JSONObject result = new JSONObject(OkHttp.string(base + "/163music.php?type=lyric&id=" + music.getSongId()));
            JSONObject data = result.optJSONObject("data");
            if (data == null) return "";
            return data.optString("lyric", data.optString("lrc", ""));
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String post(String url, Map<String, String> headers, String body) throws Exception {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
        for (Map.Entry<String, String> entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        builder.post(okhttp3.RequestBody.create(body, okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
        try (okhttp3.Response response = OkHttp.client().newCall(builder.build()).execute()) {
            return response.body() == null ? "" : response.body().string();
        }
    }
}
