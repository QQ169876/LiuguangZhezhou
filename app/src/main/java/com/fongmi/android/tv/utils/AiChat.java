package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.AiSetting;
import com.github.catvod.net.interceptor.FailoverInterceptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 大模型统一出口：**豆包优先、千问兜底**（马先生 2026-11-01 定）。
 *
 * 为什么要两条：豆包（火山 Ark）是**限额**的——免费额度用完就一个字都不回了；
 * 千问走自建中继**按量计费**，什么时候问什么时候有。所以豆包排最前省千问的钱，
 * 限额撞墙自动退到千问，冷却一阵再回来试豆包（限额恢复了就又省回去了）。
 *
 * APP 里所有问模型的地方（AiJudge 的合集判定/题材词/补演员/下一部推荐、AiSearch 的
 * 智能搜索）都从这儿走：一条链一种行为，谁也不用自己拼请求、自己挑模型。
 * 模型挑的 doubao-seed-2-1-lite：最便宜的一档，问的都是「这是不是合集」「下一季叫什么」
 * 这类简单问题，够用；seed 系列默认带深度思考，不关掉要想半分钟才回话，必须关。
 *
 * 铁律照旧：全链失败把最后一个异常抛上去，上层记 reasonOf 留痕、当「没问」兜底，绝不卡播放。
 */
public class AiChat {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 通道数：0=豆包（Ark 直连），1=千问（自建中继） */
    private static final int DOUBAO = 0;
    private static final int QWEN = 1;

    /** 豆包限额撞墙后的冷却：限额是按天/按月恢复的，冷太久回不来，取半小时 */
    private static final long QUOTA_COOLDOWN = 30 * 60 * 1000L;
    /** 豆包别的毛病（网络抖一下之类）的短冷却 */
    private static final long SOFT_COOLDOWN = 5 * 60 * 1000L;

    private static volatile OkHttpClient client;
    /** 豆包冷却到什么时候（这之前直奔千问，别每问一句都先撞一次墙） */
    private static volatile long doubaoDownUntil;

    public static String chat(String system, String user) throws Exception {
        Exception last = null;
        // 豆包能用就先问豆包（限额省钱），撞墙了/在冷却期就只剩千问这一条路
        int first = System.currentTimeMillis() < doubaoDownUntil ? QWEN : DOUBAO;
        for (int idx = first; idx <= QWEN; idx++) {
            try {
                String content = call(idx, system, user);
                if (idx != first) DebugLog.d("AiChat", name(idx) + " 接住了这单");
                return content;
            } catch (Exception e) {
                last = e;
                fail(idx, e);
            }
        }
        throw last == null ? new IOException("没有可用的模型通道") : last;
    }

    private static String call(int idx, String system, String user) throws Exception {
        boolean ark = idx == DOUBAO;
        JSONObject body = new JSONObject();
        body.put("model", ark ? AiSetting.ARK_MODEL : AiSetting.getModel());
        body.put("temperature", 0);
        if (ark) body.put("thinking", new JSONObject().put("type", "disabled")); // 不关会先想半分钟才回话
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user)));
        Request request = new Request.Builder()
                .url((ark ? AiSetting.ARK_ENDPOINT : AiSetting.getEndpoint()).replaceAll("/+$", "") + "/chat/completions")
                .addHeader("Authorization", "Bearer " + (ark ? AiSetting.getArkKey() : AiSetting.getKey()))
                .post(RequestBody.create(JSON, body.toString()))
                .build();
        try (Response response = http().newCall(request).execute()) {
            if (response.body() == null) throw new IOException(name(idx) + " 没给响应体");
            String text = response.body().string();
            if (!response.isSuccessful()) throw new HttpError(name(idx) + " HTTP " + response.code(), response.code());
            JSONObject json = new JSONObject(text);
            if (!json.has("choices")) throw new JSONException(name(idx) + " 没给 choices：" + clip(text));
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        }
    }

    /** 谁挂了记一笔：豆包进冷却（限额长冷、别的毛病短冷），千问不冷（它是最后一条路，没有退路） */
    private static void fail(int idx, Exception e) {
        if (idx != DOUBAO) return;
        boolean quota = e instanceof HttpError && ((HttpError) e).quota();
        long until = System.currentTimeMillis() + (quota ? QUOTA_COOLDOWN : SOFT_COOLDOWN);
        if (until > doubaoDownUntil) doubaoDownUntil = until;
        DebugLog.d("AiChat", "豆包不可用（" + reasonOf(e) + "），" + (quota ? "限额了，半小时后再试，" : "这会儿不通，") + "这单改走千问");
    }

    private static String name(int idx) {
        return idx == DOUBAO ? "豆包" : "千问";
    }

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (AiChat.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(8, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .addInterceptor(new FailoverInterceptor()) // 千问中继域名连不上时换备用域名；豆包的域名不在名单里，直接过
                            .build();
                }
            }
        }
        return client;
    }

    /** 带 HTTP 状态码的失败：429/402/403 一类是限额/欠费/禁用，冷却要长 */
    private static final class HttpError extends IOException {
        final int code;

        HttpError(String message, int code) {
            super(message);
            this.code = code;
        }

        boolean quota() {
            return code == 429 || code == 402 || code == 403;
        }
    }

    /** 失败原因留痕：类名 + 消息，超长掐头，免得整段响应刷屏（各家排查就靠这一句） */
    public static String reasonOf(Throwable e) {
        if (e == null) return "未知";
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        return e.getClass().getSimpleName() + ": " + (msg.length() > 120 ? msg.substring(0, 120) + "…" : msg);
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
