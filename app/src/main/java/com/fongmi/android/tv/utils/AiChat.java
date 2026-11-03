package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.AiModel;
import com.fongmi.android.tv.setting.AiSetting;
import com.github.catvod.net.interceptor.FailoverInterceptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 大模型统一出口：**自建的最优先，然后是豆包，最后千问**（马先生 2026-11-03 定：
 * 用户自己加的模型优先级最高；2026-11-01 定的「豆包优先、千问兜底」排在自建后面）。
 *
 * 为什么要两条内置的：豆包（火山 Ark）是**限额**的——免费额度用完就一个字都不回了；
 * 千问走自建中继**按量计费**，什么时候问什么时候有。所以豆包排最前省千问的钱，
 * 限额撞墙自动退到千问，冷却一阵再回来试豆包（限额恢复了就又省回去了）。
 * 内置这两条只有「自己人」（快捷码录过源）才有，见 AiSetting.channels()。
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

    /** 豆包限额撞墙后的冷却：限额是按天/按月恢复的，冷太久回不来，取半小时 */
    private static final long QUOTA_COOLDOWN = 30 * 60 * 1000L;
    /** 豆包别的毛病（网络抖一下之类）的短冷却 */
    private static final long SOFT_COOLDOWN = 5 * 60 * 1000L;
    /** 自建模型撞到限额/欠费这类，冷个五分钟再来，别每问一句都先撞一次墙 */
    private static final long USER_COOLDOWN = 5 * 60 * 1000L;

    private static volatile OkHttpClient client;
    /** 每条通道冷却到什么时候（key 是模型 id） */
    private static final Map<String, Long> downUntil = new ConcurrentHashMap<>();

    /**
     * 按顺序问：**用户自建的排最前**（多条时按他在列表里排的顺序），后面才是内置的豆包、千问。
     * 一条挂了就往下一条走，全挂就抛上去——上层当「没问」处理，绝不卡播放。
     *
     * ⚠️ 铁律（马先生 2026-11-03）：**任何内容出网前一律先脱敏，命中过的关键字一个都不带出去。**
     * 各调用方自己那道 AiGuard.pass() 只管得住它亲手拼的那几个字段（剧名、类型、站名），
     * 谁再往里塞点别的就漏了，所以**最后一道闸设在这个唯一出口上**：整段内容再抠一遍违禁词，
     * 抠完还剩什么就发什么；整句都是违禁词的直接拒发，一个字节都不外发，上层当「没问」。
     */
    public static String chat(String system, String user) throws Exception {
        String safe = user == null ? "" : AiGuard.scrub(user);
        if (safe.isEmpty()) { // 抠光了＝整句都是违禁词：拒答，不外发
            DebugLog.d("AiChat", "整句都是违禁词，拒发大模型");
            return null;
        }
        if (!safe.equals(user)) DebugLog.d("AiChat", "脱敏后外发：" + safe);
        user = safe;
        List<AiModel> list = AiSetting.channels();
        if (list.isEmpty()) throw new IOException("没有可用的模型通道");
        int first = firstAlive(list);
        Exception last = null;
        for (int idx = first; idx < list.size(); idx++) {
            AiModel model = list.get(idx);
            try {
                String content = call(model, system, user);
                if (idx != first) DebugLog.d("AiChat", name(model) + " 接住了这单");
                return content;
            } catch (Exception e) {
                last = e;
                fail(model, e);
            }
        }
        throw last == null ? new IOException("没有可用的模型通道") : last;
    }

    /**
     * 单点试一条通道（界面「测一下」用）：通了返回 null，不通返回原因。
     * 不进冷却、不影响正常排队，纯粹是替用户先探一句。
     */
    public static String probe(AiModel model) {
        try {
            call(model, "只回答两个字：收到", "收到");
            return null;
        } catch (Throwable e) {
            return reasonOf(e);
        }
    }

    /** 从哪条开始问：跳过还在冷却期里的；万一全在冷却，就从第一条硬着头皮试 */
    private static int firstAlive(List<AiModel> list) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            Long until = downUntil.get(list.get(i).getId());
            if (until == null || until <= now) return i;
        }
        return 0;
    }

    private static String call(AiModel model, String system, String user) throws Exception {
        boolean ark = AiModel.BUILTIN_DOUBAO.equals(model.getId());
        JSONObject body = new JSONObject();
        body.put("model", model.getModel());
        body.put("temperature", 0);
        if (ark) body.put("thinking", new JSONObject().put("type", "disabled")); // 不关会先想半分钟才回话
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user)));
        Request request = new Request.Builder()
                .url(model.getEndpoint().trim().replaceAll("/+$", "") + "/chat/completions")
                .addHeader("Authorization", "Bearer " + model.getKey())
                .post(RequestBody.create(JSON, body.toString()))
                .build();
        try (Response response = http().newCall(request).execute()) {
            if (response.body() == null) throw new IOException(name(model) + " 没给响应体");
            String text = response.body().string();
            if (!response.isSuccessful()) throw new HttpError(name(model) + " HTTP " + response.code(), response.code());
            JSONObject json = new JSONObject(text);
            if (!json.has("choices")) throw new JSONException(name(model) + " 没给 choices：" + clip(text));
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        }
    }

    /** 谁挂了记一笔：豆包进冷却（限额长冷、别的毛病短冷），千问是最后一条路不冷，自建的只冷限额那类 */
    private static void fail(AiModel model, Exception e) {
        boolean quota = e instanceof HttpError && ((HttpError) e).quota();
        boolean doubao = AiModel.BUILTIN_DOUBAO.equals(model.getId());
        long until = 0;
        if (doubao) until = System.currentTimeMillis() + (quota ? QUOTA_COOLDOWN : SOFT_COOLDOWN);
        else if (quota) until = System.currentTimeMillis() + USER_COOLDOWN;
        if (until == 0) return;
        Long old = downUntil.get(model.getId());
        if (old == null || until > old) downUntil.put(model.getId(), until);
        DebugLog.d("AiChat", name(model) + "不可用（" + reasonOf(e) + "），" + (quota ? "限额/被拒，先冷一阵，" : "这会儿不通，") + "这单改走下一条");
    }

    private static String name(AiModel model) {
        String name = model.getName().trim();
        return name.isEmpty() ? model.getModel() : name;
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
