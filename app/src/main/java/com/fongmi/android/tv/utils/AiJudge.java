package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.AiSetting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 大模型小助手：只干一件事——拿不准的条目问一句「这算不算一部短剧」。
 * 设计上它是可有可无的：关掉、超时、报错、返回乱码，一律当成「没问」，调用方退回本地规则。
 */
public class AiJudge {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String SYSTEM = "你是影视资源鉴定助手。判断条目是否为「一整部短剧被合成为一个视频」的合集版本，"
            + "例如 70 集每集 1 分钟的短剧被压成一个 70 分钟的单集。只输出 JSON，不要任何解释："
            + "{\"ok\":true或false,\"confidence\":0到100}";

    private static final ConcurrentHashMap<String, Boolean> MEMO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ASKING = new ConcurrentHashMap<>();
    private static final int MEMO_LIMIT = 800;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private static volatile OkHttpClient client;
    private static volatile boolean loaded;

    public interface Callback {
        void onResult(boolean ok);
    }

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (AiJudge.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(8, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return client;
    }

    /** 已经问过并有结论的，直接给；没问过返回 null */
    public static Boolean peek(String cacheKey) {
        if (AiGuard.blocked(cacheKey)) return null; // 敏感条目连缓存都不查
        load();
        return MEMO.get(cacheKey);
    }

    /**
     * 问一句「这算不算一部短剧」。结果会记在本机，同名不再重复问。
     * 任何异常都走 onResult(false)，调用方自己兜底。
     */
    public static void ask(String cacheKey, String name, String remarks, int episodes, long duration, String site, Callback callback) {
        // 铁律：涉黄赌毒等违法关键词一律不送模型，一个字节都不许出去
        if (AiGuard.blocked(name, remarks, site)) {
            DebugLog.d("AiJudge", "命中敏感词，拒绝调用大模型：" + name);
            reply(callback, false);
            return;
        }
        load();
        Boolean hit = MEMO.get(cacheKey);
        if (hit != null) {
            reply(callback, hit);
            return;
        }
        if (!AiSetting.isEnabled()) {
            reply(callback, false);
            return;
        }
        if (ASKING.putIfAbsent(cacheKey, Boolean.TRUE) != null) {
            reply(callback, false); // 已经在问了，别重复烧钱
            return;
        }
        POOL.execute(() -> {
            Boolean ok = null; // null＝没问到（断网、超时、报错），不许当成「模型说不是」记进缓存
            try {
                ok = request(name, remarks, episodes, duration, site);
            } catch (Throwable e) {
                ok = null;
                DebugLog.d("AiJudge", "模型不可用，退回本地规则：" + name);
            }
            ASKING.remove(cacheKey);
            if (ok != null) {
                if (MEMO.size() > MEMO_LIMIT) MEMO.clear();
                MEMO.put(cacheKey, ok);
                save();
            }
            boolean result = ok != null && ok;
            App.post(() -> reply(callback, result));
        });
    }

    /** 回调一律包起来：调用方出什么岔子都不能让播放崩，同步路径也一样 */
    private static void reply(Callback callback, boolean result) {
        try {
            if (callback != null) callback.onResult(result);
        } catch (Throwable ignored) {
        }
    }

    private static boolean request(String name, String remarks, int episodes, long duration, String site) throws Exception {
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "条目名：" + name
                + "\n备注：" + (TextUtils.isEmpty(remarks) ? "无" : remarks)
                + "\n集数：" + episodes
                + "\n单集时长：" + (duration > 0 ? (duration / 60000) + "分钟" : "未知")
                + "\n来源站：" + (TextUtils.isEmpty(site) ? "未知" : site)
                + "\n问：这条是不是一整部短剧被合成一个视频的合集版？");

        JSONObject body = new JSONObject();
        body.put("model", AiSetting.getModel());
        body.put("temperature", 0);
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM)).put(user));

        Request request = new Request.Builder()
                .url(AiSetting.getEndpoint().replaceAll("/+$", "") + "/chat/completions")
                .addHeader("Authorization", "Bearer " + AiSetting.getKey())
                .post(RequestBody.create(JSON, body.toString()))
                .build();

        try (Response response = http().newCall(request).execute()) {
            if (response.body() == null) return false;
            String text = response.body().string();
            JSONObject json = new JSONObject(text);
            String content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            return parse(content);
        }
    }

    /** 模型偶尔会裹一层 ```json，剥掉再解析 */
    private static boolean parse(String content) {
        if (content == null) return false;
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return false;
        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            boolean ok = json.optBoolean("ok", json.optBoolean("is_short_drama", false));
            int confidence = json.optInt("confidence", 0);
            DebugLog.d("AiJudge", "判定 " + (ok ? "是短剧" : "不是") + " 置信度=" + confidence);
            return ok && confidence >= 60;
        } catch (Exception e) {
            return false; // 模型说了听不懂的话，就当没问
        }
    }

    private static void load() {
        if (loaded) return;
        synchronized (AiJudge.class) {
            if (loaded) return;
            try {
                File file = file();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String key = it.next();
                            MEMO.put(key, json.optBoolean(key, false));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            loaded = true;
        }
    }

    private static synchronized void save() {
        try {
            JSONObject json = new JSONObject();
            for (java.util.Map.Entry<String, Boolean> entry : MEMO.entrySet()) json.put(entry.getKey(), entry.getValue());
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        return new File(App.get().getFilesDir(), "ai_judge.json");
    }
}
