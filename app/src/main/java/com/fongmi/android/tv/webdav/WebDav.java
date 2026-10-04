package com.fongmi.android.tv.webdav;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.MediaType;
import com.github.catvod.net.interceptor.FailoverInterceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WebDav {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(new FailoverInterceptor()) // 域名连不上时静默换备用域名再试一次
            .build();

    private static String auth() {
        return Credentials.basic(WebDavSetting.getUser(), WebDavSetting.getPass());
    }

    private static Request.Builder prepare(String url) {
        Request.Builder builder = new Request.Builder().url(url).addHeader("Accept", "*/*");
        // 有些 WebDAV 根本不要账号密码，这时候空账号也要带上 Authorization 反而会被拒，干脆不带
        if (WebDavSetting.hasAuth()) builder.addHeader("Authorization", auth());
        return builder;
    }

    /**
     * 目录/文件是否存在（PROPFIND Depth:0）。
     * 207/200 = 存在；404 = 不存在；其他按异常抛出，方便把真实原因报给用户。
     */
    public static boolean exists(String url) throws IOException {
        try (Response response = client.newCall(prepare(url).method("PROPFIND", null).addHeader("Depth", "0").build()).execute()) {
            int code = response.code();
            if (code == 207 || code == 200) return true;
            if (code == 404) return false;
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            throw new IOException("PROPFIND failed (" + code + ")");
        }
    }

    public static void createFolder(String url) throws IOException {
        try (Response response = client.newCall(prepare(url).method("MKCOL", null).build()).execute()) {
            int code = response.code();
            if (code == 201 || code == 200 || code == 204 || code == 301 || code == 302) return;
            if (code == 405 || code == 409) return;
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            throw new IOException("MKCOL failed (" + code + ")");
        }
    }

    /**
     * 同步前调用：逐级确认目录存在，缺了才 MKCOL。
     * 之前的问题是只在部分路径检查过，远端目录不存在时 PUT 会直接 409/404 报错。
     */
    public static void createFolders() throws IOException {
        String base = WebDavSetting.getFolderUrl();
        if (base.isEmpty()) throw new IOException("Address is empty");
        int index = base.indexOf("//");
        if (index < 0) return;
        String prefix = base.substring(0, base.indexOf("/", index + 2) + 1);
        StringBuilder builder = new StringBuilder(prefix);
        for (String part : base.substring(prefix.length()).split("/")) {
            if (part.isEmpty()) continue;
            builder.append(part).append("/");
            String current = builder.toString();
            if (exists(current)) continue;
            createFolder(current);
            if (!exists(current)) throw new IOException("MKCOL failed, folder missing: " + current);
        }
    }

    @Nullable
    public static String get(String url) throws IOException {
        try (Response response = client.newCall(prepare(url).get().build()).execute()) {
            int code = response.code();
            if (code == 404) return null;
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            if (!response.isSuccessful()) throw new IOException("GET failed (" + code + ")");
            if (response.body() == null) return null;
            return response.body().string();
        }
    }

    public static void put(String url, String body) throws IOException {
        int code = putOnce(url, body);
        // 目录被第三方删掉/未建好时，坚果云等会返回 404/409，重建目录后重试一次
        if (code == 404 || code == 409) {
            createFolders();
            code = putOnce(url, body);
        }
        if (code == 201 || code == 200 || code == 204) return;
        if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
        throw new IOException("PUT failed (" + code + ")");
    }

    private static int putOnce(String url, String body) throws IOException {
        try (Response response = client.newCall(prepare(url).put(RequestBody.create(body, JSON)).build()).execute()) {
            return response.code();
        }
    }

    public static boolean test() {
        try {
            createFolders();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Throwable root(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
        return cause;
    }
}
