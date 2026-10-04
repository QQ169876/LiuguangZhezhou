package com.fongmi.android.tv.utils;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点播源 jar 的登录态仓库。
 *
 * 网盘 / B 站的扫码登录是视频源 jar 自己做的，登录态不走系统 WebView，而是散落在
 * App 私有目录里。2026-10-05 在模拟器上把已登录的四盘 + B 站逐字节翻了一遍，
 * 实际落点是这些（文件名跟源的版本走，会变，所以这里不写死清单）：
 *
 *   files/ 根目录：
 *     bili（SESSDATA 全套，明文）、bili_refresh_token、
 *     ali_info（明文 JSON）、ali_oauth（rfO 加密）、ali_user（rfO 加密）、
 *     mybd_cookie（rfO 加密）、uc_fid（明文 JSON）、uc_ut（短加密串）
 *   spUtils：
 *     quark_ck / uc_ck（sbaP 加密）、quarkSt、ucSt、libCk，
 *     以及 hide_appgz_android_id、hide_appgz_new_key、hide_appgz_utdid、hide_appgz_token
 *     —— 后四个是加固 SDK 缓存的设备指纹，饭太硬的加密值就是拿它当钥匙，
 *       必须整套一起搬，少一个接收端就解不开。
 *
 * 所以这里改成"泛化扫描"：files 目录下像登录态的小文件全带走，spUtils 整个搬，
 * 不再依赖写死的文件名，换源、换版本都不用跟着改。
 *
 * key 格式：
 *   f/<相对路径>          -> files/<相对路径>
 *   s/<sp名>/<键名>       -> 该 SharedPreferences 文件下的键
 *   p/<键名>              -> 默认 SharedPreferences
 */
public class SpiderVault {

    /** 单文件超过这个大小就不带（正常登录态都是几十字节到几 KB） */
    private static final int MAX_FILE = 256 * 1024;
    /** 最多扫两层，够用了也免得把缓存目录翻个底朝天 */
    private static final int MAX_DEPTH = 2;

    /** 这些是 App 自己的东西，跟登录态无关 */
    private static final List<String> SKIP_FILE = Arrays.asList(
            "config_cache", "wallpaper", "webdav-baseline", "profileInstalled",
            "profileinstaller_", "profileinstaller", "splitcompat", "baseline"
    );

    private static final List<String> SKIP_DIR = Arrays.asList(
            "so", "js", "py", "jar", "exo", "mpv", "epg", "jpa", "thunder", "music", "cache"
    );

    /** spUtils 整个搬，这里面既有加密 cookie 也有解它要用的设备指纹 */
    private static final String SP_SPUTILS = "spUtils";

    /** 默认 prefs 里这些前缀 / 关键词的才带走（整个 prefs 太大，且大半是本机设置） */
    private static final List<String> PREF_KEYS = Arrays.asList("baidu_cookie", "quark_ck", "uc_ck");
    private static final List<String> PREF_PREFIX = Arrays.asList("alishare.", "hide_appgz_");
    private static final List<String> PREF_MARK = Arrays.asList("SESSDATA", "refresh_token", "bili_jct");

    private SpiderVault() {
    }

    /** 把本机散落各处的 jar 登录态收成一份 map（同步上传 / 局域网推送用） */
    public static Map<String, String> collect() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            scanDir(out, App.get().getFilesDir(), "", 0);
            collectPrefs(out, SP_SPUTILS);
            collectPrefs(out, null);
            for (String key : PREF_KEYS) {
                String value = Prefers.getString(key);
                if (!value.isEmpty()) out.put("p/" + key, value);
            }
            if (!out.isEmpty()) DebugLog.d("Vault", "收拢登录态 " + out.size() + " 项");
        } catch (Throwable e) {
            DebugLog.d("Vault", "收拢登录态出错 " + e);
        }
        return out;
    }

    private static void scanDir(Map<String, String> out, File dir, String path, int depth) {
        if (dir == null || !dir.isDirectory() || depth > MAX_DEPTH) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                if (!SKIP_DIR.contains(name) && !name.startsWith(".")) scanDir(out, file, path + name + "/", depth + 1);
                continue;
            }
            if (skip(name)) continue;
            readFile(out, "f/" + path + name, file);
        }
    }

    private static boolean skip(String name) {
        if (name.endsWith(".dat") || name.endsWith(".gz") || name.endsWith(".bk")) return true;
        for (String item : SKIP_FILE) if (name.startsWith(item)) return true;
        return false;
    }

    /** 把一份 sp 文件里看着像登录态的键带走；keys 为空表示整个文件都搬 */
    private static void collectPrefs(Map<String, String> out, String spName) {
        try {
            SharedPreferences sp = spName == null ? Prefers.getPrefers() : App.get().getSharedPreferences(spName, 0);
            String tag = spName == null ? "p/" : "s/" + spName + "/";
            for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                String key = entry.getKey();
                if (!(entry.getValue() instanceof String value) || value.isEmpty()) continue;
                if (spName == null) {
                    boolean hit = false;
                    for (String item : PREF_KEYS) if (item.equals(key)) hit = true;
                    for (String item : PREF_PREFIX) if (key.startsWith(item)) hit = true;
                    for (String item : PREF_MARK) if (value.contains(item)) hit = true;
                    if (!hit) continue;
                }
                out.put(tag + key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把收来的登录态写回本机对应位置（同步恢复 / 收到推送用） */
    public static void apply(Map<String, String> data) {
        if (data == null || data.isEmpty()) return;
        int count = 0;
        try {
            File files = App.get().getFilesDir();
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null || value.isEmpty()) continue;
                try {
                    if (key.startsWith("f/")) {
                        writeFile(new File(files, key.substring(2)), value);
                    } else if (key.startsWith("s/")) {
                        int split = key.indexOf('/', 2);
                        if (split < 0) continue;
                        String spName = key.substring(2, split);
                        String spKey = key.substring(split + 1);
                        App.get().getSharedPreferences(spName, 0).edit().putString(spKey, value).commit();
                    } else if (key.startsWith("p/")) {
                        Prefers.put(key.substring(2), value);
                    } else {
                        continue;
                    }
                    count++;
                } catch (Throwable ignored) {
                    // 单项写失败不拖垮整批
                }
            }
            DebugLog.d("Vault", "写回登录态 " + count + "/" + data.size() + " 项");
        } catch (Throwable e) {
            DebugLog.d("Vault", "写回登录态出错 " + e);
        }
    }

    private static void readFile(Map<String, String> out, String key, File file) {
        try {
            if (!file.exists() || !file.isFile()) return;
            long len = file.length();
            if (len <= 0 || len > MAX_FILE) return;
            byte[] buffer = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = in.read(buffer);
                if (read <= 0) return;
                String value = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) out.put(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeFile(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
