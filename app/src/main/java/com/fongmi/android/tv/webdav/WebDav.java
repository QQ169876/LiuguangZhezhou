package com.fongmi.android.tv.webdav;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.MediaType;
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
            .build();

    private static String auth() {
        return Credentials.basic(WebDavSetting.getUser(), WebDavSetting.getPass());
    }

    private static Request.Builder prepare(String url) {
        return new Request.Builder().url(url).addHeader("Authorization", auth()).addHeader("Accept", "*/*");
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
            createFolder(builder.toString());
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
        try (Response response = client.newCall(prepare(url).put(RequestBody.create(body, JSON)).build()).execute()) {
            int code = response.code();
            if (code == 201 || code == 200 || code == 204) return;
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            throw new IOException("PUT failed (" + code + ")");
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
