package com.fongmi.android.tv.utils;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 点播源 jar 的登录态仓库。
 *
 * 网盘 / B 站的扫码登录是视频源 jar 自己做的，登录态不走系统 WebView，而是散落在
 * App 私有目录里（2026-10-05 在模拟器上逐字节翻出来的，饭太硬 + 玩偶两套源）：
 *
 *   files/TV/ 下（玩偶系，明文）：
 *     .bilibili（SESSDATA 全套）、.baiducookie、baidu_cookie.txt、.quarkcookie、
 *     quark_cookie.txt、.ucpancookie、.uctvpancookie、.aliyun（OAuth JSON）、
 *     .pan115cookie、.tianyicookie、.YDcookie 等，外加 .bdtime/.qktime/.uctime 时间戳。
 *   files/ 根目录（饭太硬系，加密值原样搬，接收端能不能解开取决于 jar 的 key 是否绑设备）：
 *     aliyundrive_oauth、aliyundrive_user、aliyundrive_info、
 *     quark_ut、quark_fid、quarkDrive_member、quarkdrvie_cookie。
 *   默认 SharedPreferences：baidu_cookie、quark_ck、uc_ck、alishare.*.db。
 *   spUtils：quark_ck、uc_ck、quarkSt、libCk（hide_appgz_android_id 是加固 SDK 缓存的
 *     设备指纹，饭太硬的加密很可能拿它当 key，一并带走赌接收端直接用缓存值）。
 *   404 prefs（com.fongmi.android.tv404_preferences）：值里带 refresh_token 的项
 *     （阿里云盘的完整 token JSON，key 是账号 md5，不固定，按内容认）。
 *
 * 同步 / 推送时把这些打成一份 map 带走，接收端按 key 前缀写回原位，
 * 实现「扫一次码，几台设备都能用」。key 格式：
 *   tv/<文件名>    -> files/TV/<文件名>
 *   file/<文件名>  -> files/<文件名>
 *   pref/<键名>    -> 默认 SharedPreferences
 *   sp/<键名>      -> spUtils
 *   p404/<键名>    -> 404 prefs
 */
public class SpiderVault {

    /** 单文件超过这个大小就不带（正常登录态都是几百字节到两 KB） */
    private static final int MAX_FILE = 256 * 1024;

    private static final String[] TV_FILES = {
            ".bilibili",
            ".baiducookie", "baidu_cookie.txt", ".bdtime",
            ".quarkcookie", "quark_cookie.txt", ".qktime",
            ".ucpancookie", ".uctvpancookie", ".uctime",
            ".aliyun",
            ".pan115cookie", ".115pandata.json", ".115pansafecode",
            ".tianyicookie", ".123panlogin", ".189panlogin",
            ".YDcookie", ".yduserdata",
    };

    private static final String[] ROOT_FILES = {
            "aliyundrive_oauth", "aliyundrive_user", "aliyundrive_info",
            "quark_ut", "quark_fid", "quarkDrive_member", "quarkdrvie_cookie",
    };

    private static final String[] PREF_KEYS = {
            "baidu_cookie", "quark_ck", "uc_ck",
    };

    /** 默认 prefs 里这个前缀的是阿里分享的 token 引用，一并带走 */
    private static final String PREF_PREFIX_ALISHARE = "alishare.";

    private static final String[] SPUTILS_KEYS = {
            "quark_ck", "uc_ck", "quarkSt", "libCk",
            // 加固 SDK 缓存的设备指纹，饭太硬加密 quark_ck/uc_ck 的 key 很可能就是它；
            // 带上赌接收端的 jar 读缓存值而不是重新问系统要
            "hide_appgz_android_id",
    };

    private static final String SP_SPUTILS = "spUtils";
    private static final String SP_404 = "com.fongmi.android.tv404_preferences";
    /** 404 prefs 里凭这个特征认出登录 token（key 是账号 md5，不固定） */
    private static final String TOKEN_MARK = "refresh_token";

    private SpiderVault() {
    }

    /** 把本机散落各处的 jar 登录态收成一份 map（同步上传 / 局域网推送用） */
    public static Map<String, String> collect() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            File files = App.get().getFilesDir();
            File tv = new File(files, "TV");
            for (String name : TV_FILES) readFile(out, "tv/" + name, new File(tv, name));
            for (String name : ROOT_FILES) readFile(out, "file/" + name, new File(files, name));
            for (String key : PREF_KEYS) {
                String value = Prefers.getString(key);
                if (!value.isEmpty()) out.put("pref/" + key, value);
            }
            for (Map.Entry<String, ?> entry : Prefers.getPrefers().getAll().entrySet()) {
                if (entry.getKey() != null && entry.getKey().startsWith(PREF_PREFIX_ALISHARE)
                        && entry.getValue() instanceof String value && !value.isEmpty()) {
                    out.put("pref/" + entry.getKey(), value);
                }
            }
            SharedPreferences sp = App.get().getSharedPreferences(SP_SPUTILS, 0);
            for (String key : SPUTILS_KEYS) {
                String value = sp.getString(key, "");
                if (value != null && !value.isEmpty()) out.put("sp/" + key, value);
            }
            SharedPreferences p404 = App.get().getSharedPreferences(SP_404, 0);
            for (Map.Entry<String, ?> entry : p404.getAll().entrySet()) {
                if (entry.getValue() instanceof String value && value.contains(TOKEN_MARK)) {
                    out.put("p404/" + entry.getKey(), value);
                }
            }
            if (!out.isEmpty()) DebugLog.d("Vault", "收拢登录态 " + out.size() + " 项");
        } catch (Throwable e) {
            DebugLog.d("Vault", "收拢登录态出错 " + e);
        }
        return out;
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
                    if (key.startsWith("tv/")) {
                        writeFile(new File(new File(files, "TV"), key.substring(3)), value);
                    } else if (key.startsWith("file/")) {
                        writeFile(new File(files, key.substring(5)), value);
                    } else if (key.startsWith("pref/")) {
                        Prefers.put(key.substring(5), value);
                    } else if (key.startsWith("sp/")) {
                        App.get().getSharedPreferences(SP_SPUTILS, 0).edit().putString(key.substring(3), value).apply();
                    } else if (key.startsWith("p404/")) {
                        App.get().getSharedPreferences(SP_404, 0).edit().putString(key.substring(5), value).apply();
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
