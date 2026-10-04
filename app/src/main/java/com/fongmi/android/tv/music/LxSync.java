package com.fongmi.android.tv.music;

import android.util.Base64;

import com.fongmi.android.tv.utils.DebugLog;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 洛雪同步协议：先握手拿到 clientId 和密钥，再走 WebSocket 交换歌单。
 * 服务端会反过来调我们这边的方法（message2call），我们按 path 应答。
 */
public class LxSync {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int TIMEOUT = 45;

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    /** 握手结果 */
    private static class Auth {
        String clientId;
        String key;
        String serverName;
    }

    public static void sync(Callback callback) {
        new Thread(() -> {
            String message;
            boolean ok = false;
            try {
                message = doSync();
                ok = true;
            } catch (Exception e) {
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            if (callback != null) callback.onDone(ok, message);
        }, "lx-sync").start();
    }

    private static String doSync() throws Exception {
        String base = MusicSetting.getBase();
        if (base.isEmpty()) throw new Exception("还没填同步服务器地址");
        String authCode = MusicSetting.getPass();
        if (authCode.isEmpty()) throw new Exception("还没填同步密码");
        Auth auth = auth(base, authCode);
        return socket(base, auth);
    }

    /** 第一步：/id 拿服务器标识，第二步：/ah 用 RSA 换回 clientId 和密钥 */
    private static Auth auth(String base, String authCode) throws Exception {
        byte[] key = LxCrypto.key(authCode);
        KeyPair pair = LxCrypto.rsa();
        if (pair == null) throw new Exception("设备不支持 RSA");
        String pub = LxCrypto.rsaPublic(pair);
        String m = LxCrypto.aesEncrypt("lx-music auth::\n" + pub + "\nLiuGuangZheZhou\nlx_music_mobile", key);
        Map<String, String> headers = new HashMap<>();
        headers.put("m", m);
        String enc = get(base + "/ah", headers);
        if (enc.isEmpty()) throw new Exception("服务器没回应，地址或密码不对");
        String json = LxCrypto.rsaDecrypt(enc, pair);
        if (json.isEmpty()) throw new Exception("握手失败，密码可能不对");
        JSONObject object = new JSONObject(json);
        Auth auth = new Auth();
        auth.clientId = object.optString("clientId");
        auth.key = object.optString("key");
        auth.serverName = object.optString("serverName");
        if (auth.clientId.isEmpty() || auth.key.isEmpty()) throw new Exception("握手返回不完整");
        return auth;
    }

    private static String get(String url, Map<String, String> headers) throws Exception {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        try (Response res = OkHttp.client().newCall(builder.build()).execute()) {
            if (!res.isSuccessful()) throw new Exception("服务器返回 " + res.code());
            return res.body() == null ? "" : res.body().string();
        }
    }

    private static String socket(String base, Auth auth) throws Exception {
        String t = LxCrypto.aesEncrypt("lx-music connect", Base64.decode(auth.key, Base64.DEFAULT));
        String host = base.replaceFirst("^https", "wss").replaceFirst("^http", "ws");
        String url = host + "/socket?i=" + java.net.URLEncoder.encode(auth.clientId, "UTF-8") + "&t=" + java.net.URLEncoder.encode(t, "UTF-8");
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(TIMEOUT, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build();
        CountDownLatch latch = new CountDownLatch(1);
        String[] result = new String[]{null, null};
        WebSocket socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {

            @Override
            public void onMessage(@androidx.annotation.NonNull WebSocket webSocket, @androidx.annotation.NonNull String text) {
                handle(webSocket, text, latch, result);
            }

            @Override
            public void onFailure(@androidx.annotation.NonNull WebSocket webSocket, @androidx.annotation.NonNull Throwable t, Response response) {
                result[0] = "连接失败：" + (t.getMessage() == null ? t.toString() : t.getMessage());
                latch.countDown();
            }

            @Override
            public void onClosed(@androidx.annotation.NonNull WebSocket webSocket, int code, @androidx.annotation.NonNull String reason) {
                latch.countDown();
            }
        });
        if (!latch.await(TIMEOUT, TimeUnit.SECONDS)) {
            socket.cancel();
            if (result[0] == null) throw new Exception("同步超时");
        }
        socket.close(1000, "done");
        if (result[0] != null) throw new Exception(result[0]);
        return result[1] == null ? "同步完成" : result[1];
    }

    private static void handle(WebSocket socket, String raw, CountDownLatch latch, String[] result) {
        try {
            String text = raw;
            if ("ping".equals(text)) {
                socket.send("pong");
                return;
            }
            if (text.startsWith("cg_")) text = gunzip(text.substring(3));
            JSONObject msg = new JSONObject(text);
            boolean isCall = msg.optInt("type", -1) == 0 || (!msg.has("type") && msg.has("path"));
            if (!isCall) return;
            String path = join(msg.optJSONArray("path"));
            Object args = msg.has("args") ? msg.opt("args") : msg.opt("data");
            Object data = onCall(path, args);
            JSONObject reply = new JSONObject();
            reply.put("type", 1);
            reply.put("name", msg.optString("name"));
            reply.put("error", JSONObject.NULL);
            reply.put("data", data == null ? JSONObject.NULL : data);
            socket.send(reply.toString());
            if ("finished".equals(path)) {
                result[1] = "同步完成，共 " + MusicStore.get().count() + " 首";
                latch.countDown();
            }
        } catch (Throwable e) {
            result[0] = "同步出错：" + e.getMessage();
            latch.countDown();
        }
    }

    private static String join(JSONArray array) {
        if (array == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) sb.append(".");
            sb.append(array.optString(i));
        }
        return sb.toString();
    }

    /** 服务端调我们的接口：这里只实现歌单同步那几个 */
    private static Object onCall(String path, Object args) throws Exception {
        switch (path) {
            case "getEnabledFeatures": {
                JSONObject list = new JSONObject();
                list.put("skipSnapshot", false);
                JSONObject dislike = new JSONObject();
                dislike.put("skipSnapshot", false);
                JSONObject object = new JSONObject();
                object.put("list", list);
                object.put("dislike", dislike);
                return object;
            }
            case "list_sync_get_md5": {
                return LxCrypto.md5(GSON.toJson(MusicStore.get()));
            }
            case "list_sync_get_sync_mode": {
                return "overwrite_local_remote_full";
            }
            case "list_sync_get_list_data": {
                return new JSONObject(GSON.toJson(MusicStore.get()));
            }
            case "list_sync_set_list_data": {
                JSONObject data = first(args);
                if (data == null) return JSONObject.NULL;
                MusicList list = GSON.fromJson(data.toString(), MusicList.class);
                MusicStore.replace(list);
                return JSONObject.NULL;
            }
            default:
                return JSONObject.NULL;
        }
    }

    private static JSONObject first(Object args) {
        if (args instanceof JSONArray array) return array.optJSONObject(0);
        if (args instanceof JSONObject object) return object;
        return null;
    }

    private static String gunzip(String base64) throws Exception {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            return out.toString("UTF-8");
        }
    }
}
