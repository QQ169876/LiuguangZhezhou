package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import java.io.File;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 局域网推送：把本机配置或文件直接 POST 给另一台设备内置的服务（端口 9978-9998）。
 */
public class Push {

    private static final long TIMEOUT = Constant.TIMEOUT_VOD;
    private static final long TIMEOUT_FILE = Constant.TIMEOUT_VOD * 20;
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

    public static void webdav(String host) throws Exception {
        FormBody.Builder body = new FormBody.Builder();
        body.add("url", WebDavSetting.getUrl());
        body.add("user", WebDavSetting.getUser());
        body.add("pass", WebDavSetting.getPass());
        body.add("folder", WebDavSetting.getFolder());
        post(host, "webdav", body.build());
    }

    public static void config(String host, String name, String text) throws Exception {
        FormBody.Builder body = new FormBody.Builder();
        body.add("name", name);
        body.add("text", text);
        post(host, "setting", body.build());
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
