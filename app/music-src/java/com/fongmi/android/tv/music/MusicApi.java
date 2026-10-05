package com.fongmi.android.tv.music;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.DebugLog;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 音源脚本引擎：照洛雪手机版那套来，用 QuickJS 跑社区的 lx 音源脚本。
 *
 * 脚本不直接联网，它调 lx.request()，宿主（也就是这里）替它发请求再把结果送回去；
 * 反过来我们要取播放地址时，调脚本注册的 request 处理函数，等它 resolve。
 * 项目里本来就带着 QuickJS（点播源的 JS 爬虫在用），所以这一块不额外增加体积。
 *
 * 引擎跑在单独一个线程上，QuickJS 的上下文不能跨线程。
 */
public class MusicApi {

    private static volatile MusicApi instance;

    private final ExecutorService executor;
    private final Handler handler;
    private final Map<String, Object[]> pending;
    private final Set<String> sources;

    private QuickJSContext ctx;
    private String key;
    private String script;
    private String lastError = "";
    private volatile boolean ready;

    public static MusicApi get() {
        if (instance == null) {
            synchronized (MusicApi.class) {
                if (instance == null) instance = new MusicApi();
            }
        }
        return instance;
    }

    private MusicApi() {
        this.executor = Executors.newSingleThreadExecutor();
        this.handler = new Handler(Looper.getMainLooper());
        this.pending = new ConcurrentHashMap<>();
        this.sources = new java.util.concurrent.CopyOnWriteArraySet<>();
    }

    public boolean isReady() {
        return ready;
    }

    public Set<String> getSources() {
        return sources;
    }

    /** 音源没起来时的原因，用来给用户提示，别让人干瞪眼 */
    public String getLastError() {
        return lastError;
    }

    /** 加载音源脚本；已经加载过就跳过 */
    public synchronized void init() {
        if (ready) return;
        String text = MusicSetting.getScript();
        if (text.startsWith("http")) {
            String fetched = OkHttp.string(text);
            if (fetched.isEmpty()) DebugLog.d("MusicApi", "自定义音源脚本下载失败 " + text);
            else text = fetched;
        }
        if (text.isEmpty()) text = builtIn();
        boot(text);
        DebugLog.d("MusicApi", "音源就绪 " + ready + " 支持 " + sources + (ready ? "" : " 原因 " + lastError));
    }

    /** 把脚本塞进引擎，等它把支持的音源报上来 */
    private boolean boot(String rawScript) {
        if (rawScript == null || rawScript.isEmpty()) return false;
        try {
            submit(() -> create(rawScript)).get(30, TimeUnit.SECONDS);
            for (int i = 0; i < 40 && sources.isEmpty(); i++) Thread.sleep(100);
            ready = !sources.isEmpty();
        } catch (Throwable e) {
            DebugLog.d("MusicApi", "音源加载失败 " + e);
        }
        return ready;
    }

    private String builtIn() {
        try {
            return com.github.catvod.utils.Asset.read("music/flower.js");
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 照官方移动版 data.ts 的 matchInfo：从脚本头注释解析
     * @name/@description/@version/@author/@homepage，解析不到就留空。
     * 元信息必须跟脚本里写的一致——六音那种带自检的脚本会拿 description 逐字比对，
     * 写错了脚本直接拒绝加载。
     */
    private static String[] scriptInfo(String rawScript) {
        String[] info = {"", "", "", "", ""};
        try {
            java.util.regex.Matcher block = java.util.regex.Pattern.compile("^/\\*[\\S\\s]+?\\*/").matcher(rawScript);
            if (block.find()) {
                java.util.regex.Matcher line = java.util.regex.Pattern.compile("(?m)^\\s?\\*\\s?@(\\w+)\\s(.+)$").matcher(block.group());
                while (line.find()) {
                    String value = line.group(2).trim();
                    switch (line.group(1)) {
                        case "name" -> info[0] = value;
                        case "description" -> info[1] = value;
                        case "version" -> info[2] = value;
                        case "author" -> info[3] = value;
                        case "homepage" -> info[4] = value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (info[0].isEmpty()) info[0] = "user_api_builtin";
        return info;
    }

    private void create(String rawScript) {
        try {
            QuickJSLoader.init();
            if (ctx != null) ctx.destroy();
            ctx = QuickJSContext.create();
            // 必须先给上下文挂上 console 的输出去向：wrapper 的原生 console.log 在没有 stdout 时
            // 会直接 throw（"you should be set a stdout of platform to console.stdout"），
            // 而 user-api-preload.js 结尾就有一句 console.log，不挂的话环境根本建不起来。
            QuickJSLoader.initConsoleLog(ctx, "MusicApi");
            key = UUID.randomUUID().toString();
            bind();
            String preload = com.github.catvod.utils.Asset.read("music/user-api-preload.js");
            ctx.evaluate(preload);
            DebugLog.d("MusicApi", "preload 已执行 " + preload.length() + " 字节");
            String[] meta = scriptInfo(rawScript);
            ctx.getGlobalObject().getJSFunction("lx_setup").call(key, "builtin", meta[0], meta[1], meta[2], meta[3], meta[4], rawScript);
            ctx.evaluate(rawScript);
            DebugLog.d("MusicApi", "脚本已执行 " + rawScript.length() + " 字节");
            script = rawScript;
        } catch (Throwable e) {
            lastError = String.valueOf(e.getMessage() == null ? e : e.getMessage());
            DebugLog.d("MusicApi", "建环境失败 " + e);
        }
    }

    /** 宽容一点的 base64 解码：去掉空白、兼容 urlsafe、补足长度——脚本里各种写法都有 */
    private static byte[] b64(String text) {
        String src = text == null ? "" : text.replaceAll("\\s", "");
        if (src.indexOf('-') >= 0 || src.indexOf('_') >= 0) src = src.replace('-', '+').replace('_', '/');
        int pad = src.length() % 4;
        if (pad > 0) src = src + "==".substring(0, 4 - pad);
        return Base64.decode(src, Base64.NO_WRAP);
    }

    private static String head(String text) {
        if (text == null) return "null";
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    /** 把宿主要给脚本用的几个函数挂到全局上 */
    private void bind() {
        ctx.getGlobalObject().setProperty("__lx_native_call__", args -> {
            if (args.length < 2 || !key.equals(args[0])) return null;
            onNativeCall(String.valueOf(args[1]), args.length > 2 ? String.valueOf(args[2]) : null);
            return null;
        });
        ctx.getGlobalObject().setProperty("__lx_native_call__utils_str2b64", args -> {
            try {
                String out = new String(Base64.encode(String.valueOf(args[0]).getBytes("UTF-8"), Base64.NO_WRAP));
                DebugLog.d("MusicApi", "str2b64 " + head(String.valueOf(args[0])) + " -> " + head(out));
                return out;
            } catch (Throwable e) {
                return "";
            }
        });
        ctx.getGlobalObject().setProperty("__lx_native_call__utils_b642buf", args -> {
            String input = String.valueOf(args[0]);
            try {
                byte[] data = b64(input);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < data.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append((int) data[i]);
                }
                String out = sb.append("]").toString();
                DebugLog.d("MusicApi", "b642buf " + head(input) + " -> " + head(out));
                return out;
            } catch (Throwable e) {
                DebugLog.d("MusicApi", "base64 解码失败 长度=" + input.length() + " 内容=" + head(input) + " " + e);
                return "";
            }
        });
        ctx.getGlobalObject().setProperty("__lx_native_call__utils_str2md5", args -> {
            try {
                String out = LxCrypto.md5(URLDecoder.decode(String.valueOf(args[0]), "UTF-8"));
                DebugLog.d("MusicApi", "str2md5 " + head(String.valueOf(args[0])) + " -> " + head(out));
                return out;
            } catch (Throwable e) {
                return "";
            }
        });
        ctx.getGlobalObject().setProperty("__lx_native_call__utils_aes_encrypt", args -> {
            try {
                return LxCrypto.aesEncryptRaw(String.valueOf(args[0]), String.valueOf(args[1]), String.valueOf(args[2]), String.valueOf(args[3]));
            } catch (Throwable e) {
                return "";
            }
        });
        ctx.getGlobalObject().setProperty("__lx_native_call__utils_rsa_encrypt", args -> {
            try {
                return LxCrypto.rsaEncrypt(String.valueOf(args[0]), String.valueOf(args[1]), String.valueOf(args[2]));
            } catch (Throwable e) {
                return "";
            }
        });
        ctx.getGlobalObject().setProperty("__lx_native_call__set_timeout", args -> {
            try {
                int id = ((Number) args[0]).intValue();
                long delay = ((Number) args[1]).longValue();
                handler.postDelayed(() -> callJs("__set_timeout__", id), delay);
            } catch (Throwable ignored) {
            }
            return null;
        });
    }

    /** 脚本往宿主发消息：init / request / response / showUpdateAlert / log */
    private void onNativeCall(String action, String data) {
        try {
            JSONObject object = new JSONObject(data == null ? "{}" : data);
            switch (action) {
                case "init" -> {
                    JSONObject info = object.optJSONObject("info");
                    if (info != null) {
                        JSONObject list = info.optJSONObject("sources");
                        if (list != null) {
                            for (Iterator<String> it = list.keys(); it.hasNext(); ) sources.add(it.next());
                        }
                    }
                }
                case "request" -> doRequest(object.optString("requestKey"), object.optString("url"), object.optJSONObject("options"));
                case "response" -> onResponse(object);
                case "cancelRequest" -> DebugLog.d("MusicApi", "脚本取消请求");
                default -> DebugLog.d("MusicApi", "脚本消息 " + action + " " + head(data));
            }
        } catch (Throwable e) {
            DebugLog.d("MusicApi", "处理脚本消息出错 " + e);
        }
    }

    /** 替脚本发一个 HTTP 请求，结果送回脚本 */
    private void doRequest(String requestKey, String url, JSONObject options) {
        if (requestKey.isEmpty() || url.isEmpty()) return;
        DebugLog.d("MusicApi", "脚本请求 " + (options == null ? "get" : options.optString("method", "get")) + " " + url);
        try {
            String method = options == null ? "get" : options.optString("method", "get");
            boolean binary = options != null && options.optBoolean("binary");
            long timeout = options == null ? 13000 : Math.min(options.optLong("timeout", 13000), 60000);
            Request.Builder builder = new Request.Builder().url(url);
            if (options != null && options.has("headers")) {
                JSONObject headers = options.optJSONObject("headers");
                if (headers != null) {
                    for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
                        String name = it.next();
                        builder.header(name, headers.optString(name));
                    }
                }
            }
            RequestBody body = null;
            String type = null;
            if (!"get".equalsIgnoreCase(method) && options != null) {
                if (options.has("form")) {
                    type = "application/x-www-form-urlencoded";
                    JSONObject form = options.optJSONObject("form");
                    okhttp3.FormBody.Builder formBody = new okhttp3.FormBody.Builder();
                    if (form != null) for (Iterator<String> it = form.keys(); it.hasNext(); ) {
                        String name = it.next();
                        formBody.add(name, form.optString(name));
                    }
                    body = formBody.build();
                } else if (options.has("body")) {
                    Object raw = options.opt("body");
                    if (raw instanceof JSONObject json) {
                        type = "application/json";
                        body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
                    } else {
                        type = "text/plain";
                        body = RequestBody.create(String.valueOf(raw), MediaType.parse("text/plain"));
                    }
                }
                if (options.has("formData")) {
                    type = "multipart/form-data";
                    body = RequestBody.create(options.optString("formData"), MediaType.parse("multipart/form-data"));
                }
            }
            if (body == null && !"get".equalsIgnoreCase(method)) body = RequestBody.create("", null);
            if (type != null && !"application/x-www-form-urlencoded".equals(type) && !"multipart/form-data".equals(type)) {
                builder.header("Content-Type", type);
            }
            builder.method(method.toUpperCase(), body);
            OkHttp.client(timeout).newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(@androidx.annotation.NonNull Call call, @androidx.annotation.NonNull IOException e) {
                    reply(requestKey, e.getMessage(), null);
                }

                @Override
                public void onResponse(@androidx.annotation.NonNull Call call, @androidx.annotation.NonNull Response response) {
                    try {
                        JSONObject resp = new JSONObject();
                        resp.put("statusCode", response.code());
                        resp.put("statusMessage", response.message());
                        resp.put("url", response.request().url().toString());
                        resp.put("ok", response.isSuccessful());
                        JSONObject headers = new JSONObject();
                        Headers raw = response.headers();
                        for (int i = 0; i < raw.size(); i++) headers.put(raw.name(i), raw.value(i));
                        resp.put("headers", headers);
                        byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
                        if (binary) {
                            StringBuilder sb = new StringBuilder("[");
                            for (int i = 0; i < bytes.length; i++) {
                                if (i > 0) sb.append(",");
                                sb.append((int) bytes[i]);
                            }
                            resp.put("body", new org.json.JSONArray(sb.append("]").toString()));
                        } else {
                            String text = new String(bytes, "UTF-8");
                            try {
                                if (text.trim().startsWith("{") || text.trim().startsWith("[")) resp.put("body", new org.json.JSONTokener(text).nextValue());
                                else resp.put("body", text);
                            } catch (Throwable e) {
                                resp.put("body", text);
                            }
                        }
                        response.close();
                        reply(requestKey, null, resp);
                    } catch (Throwable e) {
                        reply(requestKey, String.valueOf(e.getMessage()), null);
                    }
                }
            });
        } catch (Throwable e) {
            reply(requestKey, String.valueOf(e.getMessage()), null);
        }
    }

    private void reply(String requestKey, String error, JSONObject response) {
        try {
            JSONObject object = new JSONObject();
            object.put("requestKey", requestKey);
            object.put("error", error == null ? JSONObject.NULL : error);
            object.put("response", response == null ? JSONObject.NULL : response);
            callJs("response", object.toString());
        } catch (Throwable ignored) {
        }
    }

    private void callJs(String action, Object data) {
        submit(() -> {
            try {
                if (ctx == null) return;
                if (data == null) ctx.getGlobalObject().getJSFunction("__lx_native__").call(key, action);
                else ctx.getGlobalObject().getJSFunction("__lx_native__").call(key, action, data);
            } catch (Throwable e) {
                DebugLog.d("MusicApi", "调用脚本失败 " + e);
            }
        });
    }

    /**
     * 向脚本要一个播放地址。
     * info 里放 musicInfo（歌曲信息）和 type（音质）。
     */
    public String url(String source, JsonObject musicInfo, String quality) {
        return ask(source, "musicUrl", musicInfo, quality, "url");
    }

    public String lyric(String source, JsonObject musicInfo) {
        return ask(source, "lyric", musicInfo, null, "lyric");
    }

    private String ask(String source, String action, JsonObject musicInfo, String quality, String field) {
        if (!ready) return "";
        String requestKey = "req" + System.nanoTime();
        Object[] slot = new Object[1];
        pending.put(requestKey, slot);
        try {
            JSONObject info = new JSONObject();
            info.put("musicInfo", new JSONObject(musicInfo.toString()));
            info.put("type", quality == null ? "" : quality);
            JSONObject data = new JSONObject();
            data.put("source", source);
            data.put("action", action);
            data.put("info", info);
            JSONObject object = new JSONObject();
            object.put("requestKey", requestKey);
            object.put("data", data);
            callJs("request", object.toString());
            synchronized (slot) {
                if (slot[0] == null) slot.wait(20000);
            }
        } catch (Throwable e) {
            DebugLog.d("MusicApi", "取 " + action + " 出错 " + e);
        } finally {
            pending.remove(requestKey);
        }
        Object result = slot[0];
        if (!(result instanceof JSONObject json)) return "";
        JSONObject payload = json.optJSONObject("data");
        if (payload == null) payload = json;
        return payload.optString(field, "");
    }

    /** 脚本把结果送回来了 */
    private void onResponse(JSONObject object) {
        try {
            String requestKey = object.optString("requestKey");
            Object[] slot = pending.get(requestKey);
            if (slot == null) return;
            boolean status = object.optBoolean("status");
            JSONObject result = status ? object.optJSONObject("result") : null;
            synchronized (slot) {
                slot[0] = result;
                slot.notifyAll();
            }
        } catch (Throwable ignored) {
        }
    }

    private java.util.concurrent.Future<?> submit(Runnable runnable) {
        return executor.submit(runnable);
    }

    public void destroy() {
        try {
            if (ctx != null) ctx.destroy();
        } catch (Throwable ignored) {
        }
        ctx = null;
        ready = false;
        sources.clear();
    }
}
