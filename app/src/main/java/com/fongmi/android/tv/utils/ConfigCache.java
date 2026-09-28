package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 点播 / 直播在线配置的本地快照。
 * 在线源挂掉时用它兜底，下次进 App 先出画面，联网成功后再整体替换。
 */
public class ConfigCache {

    private static final String TAG = ConfigCache.class.getSimpleName();
    private static final String KEY = "config_cache_url_";
    private static final int LIMIT = 3 * 1024 * 1024;
    private static final int[] TYPES = {0, 1};

    private static File file(int type) {
        return new File(App.get().getFilesDir(), "config_cache_" + type + ".txt");
    }

    public static void put(int type, String url, String text) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(text)) return;
        if (text.length() > LIMIT) return;
        try (FileOutputStream out = new FileOutputStream(file(type))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            Prefers.put(KEY + type, url);
            Log.d(TAG, "saved type=" + type + " size=" + text.length());
        } catch (Exception ignored) {
        }
    }

    public static String get(int type, String url) {
        if (TextUtils.isEmpty(url)) return "";
        if (!url.equals(Prefers.getString(KEY + type, ""))) return "";
        File file = file(type);
        if (!file.exists()) return "";
        Log.d(TAG, "hit type=" + type);
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = in.read(buffer);
            return new String(buffer, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static void clear(int type) {
        try {
            File file = file(type);
            if (file.exists()) file.delete();
            Prefers.remove(KEY + type);
        } catch (Exception ignored) {
        }
    }

    public static Map<String, String> export() {
        Map<String, String> map = new HashMap<>();
        for (int type : TYPES) {
            String url = Prefers.getString(KEY + type, "");
            String text = get(type, url);
            if (url.isEmpty() || text.isEmpty()) continue;
            map.put("url" + type, url);
            map.put("text" + type, text);
        }
        return map;
    }

    public static void apply(Map<String, String> map) {
        if (map == null || map.isEmpty()) return;
        for (int type : TYPES) {
            String url = map.get("url" + type);
            String text = map.get("text" + type);
            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(text)) continue;
            if (url.equals(Prefers.getString(KEY + type, "")) && !get(type, url).isEmpty()) continue;
            put(type, url, text);
        }
    }
}
