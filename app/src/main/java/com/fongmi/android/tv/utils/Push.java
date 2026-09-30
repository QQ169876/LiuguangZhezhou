package com.fongmi.android.tv.utils;
import java.util.Arrays;
import java.util.Collections;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.moontv.MoonSetting;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 局域网推送配置：把本机配置或文件直接 POST 给另一台设备内置的服务（端口 9978-9998）。
 * 支持按需勾选：点播 / 直播 / 系统设置 / WebDAV / 影视站 / 历史 / 收藏，也可以全选。
 */
public class Push {

    public static final String VOD = "vod";
    public static final String LIVE = "live";
    public static final String PREF = "pref";
    public static final String WEBDAV = "webdav";
    public static final String COOKIE = "cookie";
    public static final String HISTORY = "history";
    public static final String KEEP = "keep";
    public static final String MOON = "moon";
    public static final String APK = "apk";
    public static final String FILE = "file";

    private static final List<String> DATA = new ArrayList<>(Arrays.asList(VOD, LIVE, PREF, HISTORY, KEEP));
    private static final long TIMEOUT = Constant.TIMEOUT_VOD;
    private static final long TIMEOUT_FILE = Constant.TIMEOUT_VOD * 20;
    private static final int MAX_SIZE = 2 * 1024 * 1024;
    private static final String HOST = "push_host";

    public static String getHost() {
        return Prefers.getString(HOST);
    }

    public static void putHost(String host) {
        Prefers.put(HOST, host == null ? "" : host.trim());
    }

    public static String fix(String host) {
        String text = host == null ? "" : host.trim();
        if (text.isEmpty()) return "";
        if (!text.startsWith("http")) text = "http://" + text;
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        int index = text.indexOf("?", 8);
        return index > 0 ? text.substring(0, index) : text;
    }

    /** 可勾选的推送项：只放配置数据，文件/安装包走单独的「文件推送」菜单 */
    public static List<String> keys(boolean tv) {
        List<String> items = new ArrayList<>();
        items.add(VOD);
        items.add(LIVE);
        items.add(PREF);
        items.add(WEBDAV);
        items.add(COOKIE);
        items.add(HISTORY);
        items.add(KEEP);
        items.add(MOON);
        return items;
    }

    public static int label(String key) {
        return switch (key) {
            case VOD -> R.string.push_item_vod;
            case LIVE -> R.string.push_item_live;
            case PREF -> R.string.push_item_setting;
            case WEBDAV -> R.string.push_item_webdav;
            case COOKIE -> R.string.push_item_cookie;
            case HISTORY -> R.string.push_item_history;
            case KEEP -> R.string.push_item_keep;
            case MOON -> R.string.push_item_moon;
            case APK -> R.string.push_item_apk;
            default -> R.string.push_item_file;
        };
    }

    /** 按勾选的内容逐项推送，某一项失败不影响其它项 */
    public static void run(String host, List<String> keys) throws Exception {
        List<String> data = new ArrayList<>();
        Throwable error = null;
        int count = 0;
        if (keys.contains(WEBDAV)) {
            try {
                if (webdav(host)) count++;
            } catch (Throwable e) {
                error = e;
            }
        }
        if (keys.contains(COOKIE)) {
            try {
                if (cookie(host)) count++;
            } catch (Throwable e) {
                error = e;
            }
        }
        if (keys.contains(MOON)) {
            try {
                if (moontv(host)) count++;
            } catch (Throwable e) {
                error = e;
            }
        }
        for (String key : keys) if (DATA.contains(key)) data.add(key);
        if (!data.isEmpty()) {
            try {
                data(host, data);
                count++;
            } catch (Throwable e) {
                error = e;
            }
        }
        if (count == 0) throw new Exception("empty");
        if (error != null) throw new Exception(error.getMessage());
    }

    public static boolean webdav(String host) throws Exception {
        if (TextUtils.isEmpty(WebDavSetting.getUrl())) return false;
        FormBody.Builder body = new FormBody.Builder();
        body.add("url", WebDavSetting.getUrl());
        body.add("user", WebDavSetting.getUser());
        body.add("pass", WebDavSetting.getPass());
        body.add("folder", WebDavSetting.getFolder());
        post(host, "webdav", body.build());
        return true;
    }

    /** 网盘 / 影视站的扫码登录状态（Cookie）一并推过去，对面就不用再扫一次 */
    public static boolean cookie(String host) throws Exception {
        Map<String, String> cookies = CookieStore.snapshot();
        if (cookies.isEmpty()) return false;
        post(host, "cookie", new FormBody.Builder().add("data", App.gson().toJson(cookies)).build());
        return true;
    }

    public static boolean moontv(String host) throws Exception {
        if (TextUtils.isEmpty(MoonSetting.getUrl())) return false;
        FormBody.Builder body = new FormBody.Builder();
        body.add("url", MoonSetting.getUrl());
        body.add("user", MoonSetting.getUser());
        body.add("pass", MoonSetting.getPass());
        post(host, "moontv", body.build());
        return true;
    }

    /** 配置 / 历史 / 收藏 / 系统设置打包成一个 Backup 一次性推送 */
    public static void data(String host, List<String> keys) throws Exception {
        Backup backup = new Backup();
        if (keys.contains(VOD) || keys.contains(LIVE)) {
            List<Config> configs = new ArrayList<>();
            if (keys.contains(VOD)) configs.addAll(Config.getAll(0));
            if (keys.contains(LIVE)) configs.addAll(Config.getAll(1));
            backup.setConfig(configs);
        }
        if (keys.contains(HISTORY)) backup.setHistory(AppDatabase.get().getHistoryDao().findAll());
        if (keys.contains(KEEP)) backup.setKeep(AppDatabase.get().getKeepDao().findAll());
        if (keys.contains(PREF)) backup.setPrefers(prefers());
        String json = App.gson().toJson(backup);
        if (json.length() > MAX_SIZE) {
            for (Config item : backup.getConfig()) item.setJson("");
            json = App.gson().toJson(backup);
        }
        post(host, "merge", new FormBody.Builder().add("data", json).build());
    }

    private static Map<String, Object> prefers() {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : Prefers.getPrefers().getAll().entrySet()) {
            String key = entry.getKey();
            if (key == null || key.startsWith("webdav_") || key.startsWith("moontv_")) continue;
            if (key.equals(HOST)) continue;
            result.put(key, entry.getValue());
        }
        return result;
    }

    public static void file(String host, File file) throws Exception {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        builder.addFormDataPart("path", "");
        builder.addFormDataPart("push", file.getName(), RequestBody.create(file, MediaType.parse("application/octet-stream")));
        execute(fix(host).concat("/upload"), builder.build(), TIMEOUT_FILE);
    }

    private static void post(String host, String action, RequestBody body) throws Exception {
        execute(fix(host).concat("/action?do=").concat(action), body, TIMEOUT);
    }

    private static void execute(String url, RequestBody body, long timeout) throws Exception {
        try (Response response = OkHttp.newCall(OkHttp.client(timeout), url, body).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
        }
    }
}
