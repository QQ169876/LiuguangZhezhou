package com.fongmi.android.tv.moontv;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * MoonTV / LunaTV 站点 API 客户端。
 * 登录走 POST /api/login，成功后服务端下发 user_auth cookie，后续请求带上即可。
 * 收藏：GET|POST|DELETE /api/favorites，播放记录：/api/playrecords，键格式均为 source+id。
 */
public class MoonApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern COOKIE = Pattern.compile("user_auth=([^;]*)");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

    private static String cookie = "";

    public static void reset() {
        cookie = "";
    }

    private static String api(String path) throws IOException {
        String base = MoonSetting.getBase();
        if (base.isEmpty()) throw new IOException("Address is empty");
        return base.concat(path);
    }

    public static void login() throws Exception {
        cookie = "";
        JSONObject body = new JSONObject();
        body.put("username", MoonSetting.getUser());
        body.put("password", MoonSetting.getPass());
        Request request = new Request.Builder().url(api("/api/login")).post(RequestBody.create(body.toString(), JSON)).build();
        try (Response response = client.newCall(request).execute()) {
            keep(response);
            int code = response.code();
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            if (!response.isSuccessful()) throw new IOException("Login failed (" + code + ")");
            if (cookie.isEmpty()) throw new IOException("Login failed, no cookie");
        }
    }

    private static void keep(Response response) {
        String header = response.header("Set-Cookie");
        if (header == null) return;
        Matcher matcher = COOKIE.matcher(header);
        if (matcher.find()) cookie = matcher.group(1);
    }

    private static Request.Builder auth(String path) throws IOException {
        return new Request.Builder().url(api(path)).addHeader("Cookie", "user_auth=" + cookie).addHeader("Accept", "application/json");
    }

    private static JSONObject call(Request.Builder builder) throws Exception {
        try (Response response = client.newCall(builder.build()).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) return null;
            if (!response.isSuccessful()) throw new IOException("HTTP " + code);
            if (response.body() == null) throw new IOException("Empty response");
            return new JSONObject(response.body().string());
        }
    }

    private static JSONObject get(String path) throws Exception {
        for (int i = 0; i < 2; i++) {
            if (cookie.isEmpty()) login();
            JSONObject result = call(auth(path).get());
            if (result != null) return result;
            cookie = "";
        }
        throw new IOException("Unauthorized");
    }

    private static void post(String path, JSONObject object) throws Exception {
        for (int i = 0; i < 2; i++) {
            if (cookie.isEmpty()) login();
            JSONObject result = call(auth(path).post(RequestBody.create(object.toString(), JSON)));
            if (result != null) return;
            cookie = "";
        }
        throw new IOException("Unauthorized");
    }

    public static JSONObject favorites() throws Exception {
        return get("/api/favorites");
    }

    public static JSONObject playRecords() throws Exception {
        return get("/api/playrecords");
    }

    public static void saveFavorite(String key, JSONObject favorite) throws Exception {
        JSONObject body = new JSONObject();
        body.put("key", key);
        body.put("favorite", favorite);
        post("/api/favorites", body);
    }

    public static void saveRecord(String key, JSONObject record) throws Exception {
        JSONObject body = new JSONObject();
        body.put("key", key);
        body.put("record", record);
        post("/api/playrecords", body);
    }

    public static boolean test() {
        try {
            login();
            favorites();
            playRecords();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
