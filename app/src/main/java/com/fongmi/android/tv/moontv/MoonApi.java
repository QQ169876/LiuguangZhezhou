package com.fongmi.android.tv.moontv;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import com.github.catvod.net.interceptor.FailoverInterceptor;
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
            .addInterceptor(new FailoverInterceptor()) // 域名连不上时静默换备用域名再试一次
            .build();

    private static String cookie = "";

    public static void reset() {
        cookie = "";
    }

    /**
     * 同步收工：把这次用的连接全部掐掉。
     * 老设备（MStar 安卓6 32位）在影视站同步做完之后几秒，进程会毫无征兆地死掉——
     * 没有 Java 堆栈、内存也不紧张，死前最后一步又全跑完了，只剩这批还挂在池子里保活的连接
     * （默认存活 5 分钟）。收工就关掉，下次重连慢几十毫秒，换一个可能的活命机会。
     */
    public static void release() {
        try {
            client.connectionPool().evictAll();
        } catch (Throwable ignored) {
        }
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

    /**
     * 请求一律带 no-cache：站点前面常挂着 CDN（Vercel / Cloudflare），
     * 不加的话可能拿到上一秒的旧列表，删掉的记录又会被别的设备拉回来。
     * GET 额外拼一个时间戳参数，双保险。
     */
    private static Request.Builder auth(String path) throws IOException {
        return auth(path, false);
    }

    private static Request.Builder auth(String path, boolean bust) throws IOException {
        String url = api(path);
        if (bust) url = url.concat(url.contains("?") ? "&" : "?").concat("_t=").concat(String.valueOf(System.currentTimeMillis()));
        Request.Builder builder = new Request.Builder().url(url);
        if (!cookie.isEmpty()) builder.addHeader("Cookie", "user_auth=" + cookie);
        return builder
                .addHeader("Accept", "application/json")
                .addHeader("Cache-Control", "no-cache, no-store, max-age=0")
                .addHeader("Pragma", "no-cache");
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

    /** 填了账号密码才去登录拿 cookie；没填的站点当它不要鉴权，直接请求 */
    private static void ensure() throws Exception {
        if (cookie.isEmpty() && MoonSetting.hasAuth()) login();
    }

    private static JSONObject get(String path) throws Exception {
        for (int i = 0; i < 2; i++) {
            ensure();
            JSONObject result = call(auth(path, true).get());
            if (result != null) return result;
            cookie = "";
        }
        throw new IOException("Unauthorized");
    }

    private static void post(String path, JSONObject object) throws Exception {
        for (int i = 0; i < 2; i++) {
            ensure();
            JSONObject result = call(auth(path).post(RequestBody.create(object.toString(), JSON)));
            if (result != null) return;
            cookie = "";
        }
        throw new IOException("Unauthorized");
    }

    private static void del(String path) throws Exception {
        for (int i = 0; i < 2; i++) {
            ensure();
            JSONObject result = call(auth(path).delete());
            if (result != null) return;
            cookie = "";
        }
        throw new IOException("Unauthorized");
    }

    public static void deleteFavorite(String key) throws Exception {
        del("/api/favorites?key=" + encode(key));
    }

    public static void deleteRecord(String key) throws Exception {
        del("/api/playrecords?key=" + encode(key));
    }

    private static String encode(String key) {
        return java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static JSONObject favorites() throws Exception {
        return get("/api/favorites");
    }

    public static JSONObject playRecords() throws Exception {
        return get("/api/playrecords");
    }

    /** 删除之后复查一次：站点写库有延迟，确认真的没了才算删干净 */
    public static boolean hasFavorite(String key) throws Exception {
        return favorites().has(key);
    }

    public static boolean hasRecord(String key) throws Exception {
        return playRecords().has(key);
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
            cookie = "";
            ensure();
            favorites();
            playRecords();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
