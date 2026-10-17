package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.AiSetting;
import com.github.catvod.net.interceptor.FailoverInterceptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
 * 智能搜索：把用户的大白话翻译成「找片指令」（马先生 2026-10-17 定的需求）。
 *
 * 模型只做意图识别，不做聊天——输出只能是规定格式的 JSON，App 按 JSON 里的字段走搜索，
 * 模型的自由文本一个字都不会上屏。三道闸：
 *   1. 进门前 AiGuard 过一遍，碰线的问句本地直接拒答，一个字节都不发出去；
 *   2. system prompt 写死「只处理影视找片，其余拒答」；
 *   3. 回来的 JSON 逐字段校验（长度、数量、数值范围、再过一遍 AiGuard），不合规就当没问。
 *
 * 意图五种：
 *   keyword  给了明确片名/题材关键词 → 直接全站搜；
 *   actor    点名演员/导演 → 走 TMDB 拉他的作品表；
 *   similar  「类似《X》这种」→ 模型给同类题材词 + 提名相似作品，逐部回 App 里搜；
 *   discover 「今年新出的/评分高的推荐」→ 走 TMDB 按类型/年份/评分筛选（真实数据，不靠模型记忆）；
 *   refused  与影视无关的请求 → 固定文案拒答。
 *
 * 跟 AiJudge 一样的定位：可有可无。断网、超时、模型说胡话，一律回调 ERROR，调用方提示一句就完事，
 * 绝不影响正常的关键词搜索。同一句话 24 小时内走本地缓存，不重复烧 token。
 */
public class AiSearch {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String SYSTEM = "你是影视搜索意图识别助手，任务是把用户的话翻译成找片指令。"
            + "只允许处理与电影、电视剧、短剧、漫剧、动画、综艺等影视作品相关的找片或推荐请求；"
            + "其它任何请求（写作、翻译、总结、闲聊、知识问答等）一律拒答。"
            + "只输出 JSON，不要任何解释：\n"
            + "{\"type\":\"keyword|actor|similar|discover|refused\",\"summary\":\"一句话复述理解到的需求，16字以内\","
            + "\"keywords\":[\"直接可搜的关键词\"],\"actor\":\"演员或导演姓名\",\"similar\":[\"用户提到的参照作品名\"],"
            + "\"tags\":[\"同类题材关键词\"],\"titles\":[\"你提名的具体作品名\"],\"genre\":\"类型\","
            + "\"year_min\":0,\"min_rating\":0,\"media\":\"movie|tv|both\"}\n"
            + "规则：\n"
            + "1. 给了明确片名或题材关键词 → keyword，keywords 给 1~3 个最可能搜到的写法（第一个是主关键词）。\n"
            + "2. 点名演员或导演，要找他/她的作品 → actor。\n"
            + "3. 「类似/像《X》这种」→ similar：similar 填参照作品名；tags 填 3~5 个同类题材词"
            + "（AI漫剧、动态漫、沙雕漫、短剧这类形态词要保留）；titles 提名 3~6 部真正类似的作品名，不知道就空数组，不要编造。\n"
            + "4. 「最新的/今年/近几年/评分高/推荐几部」这类筛选推荐 → discover：genre 填类型"
            + "（动作/喜剧/爱情/科幻/奇幻/悬疑/恐怖/惊悚/动画/犯罪/战争/历史/家庭/古装/剧情等），"
            + "year_min 按今天日期折算（今年就填今年的年份，近几年就往前推），有评分要求就填 min_rating（比如「评分高」填 7），"
            + "media：电影=movie，剧集/短剧/漫剧=tv，没说=both。\n"
            + "5. 与影视找片无关 → refused，其它字段留空。\n"
            + "6. summary 始终用中文，拿不准就填用户原话的缩写。";

    /** 同一句话一天内不再重复问 */
    private static final long TTL = 24 * 60 * 60 * 1000L;
    private static final int MEMO_LIMIT = 200;

    private static final ConcurrentHashMap<String, Entry> MEMO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ASKING = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private static volatile OkHttpClient client;
    private static volatile boolean loaded;

    public interface Callback {

        void onResult(Plan plan);
    }

    /** 一条找片指令：type 决定走哪条路，其余字段按 type 取用 */
    public static class Plan {

        public static final int ERROR = -1;   // 没问成（断网/超时/模型说胡话），不是拒答
        public static final int REFUSED = 0;  // 与影视无关，拒答
        public static final int KEYWORD = 1;
        public static final int ACTOR = 2;
        public static final int SIMILAR = 3;
        public static final int DISCOVER = 4;

        public int type = ERROR;
        public String summary = "";
        public List<String> keywords = new ArrayList<>();
        public String actor = "";
        public List<String> similar = new ArrayList<>();
        public List<String> tags = new ArrayList<>();
        public List<String> titles = new ArrayList<>();
        public String genre = "";
        public int yearMin;
        public double minRating;
        public String media = "both";

        static Plan error() {
            return new Plan();
        }

        static Plan refused() {
            Plan plan = new Plan();
            plan.type = REFUSED;
            return plan;
        }
    }

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (AiSearch.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .addInterceptor(new FailoverInterceptor()) // 中继域名连不上时换备用域名（169876.xyz ↔ 12356.cool）
                            .build();
                }
            }
        }
        return client;
    }

    /** 已经问过并有结论的，直接给；没问过返回 null */
    public static Plan peek(String question) {
        if (TextUtils.isEmpty(question) || AiGuard.blocked(question)) return null;
        load();
        Entry entry = MEMO.get(key(question));
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.at > TTL) return null;
        return entry.plan;
    }

    /**
     * 问一句「这话想找什么片」。任何异常都回调 ERROR，调用方提示一句就完事。
     * 同一句话正在问的时候重复调用，直接回调 ERROR，别重复烧请求。
     */
    public static void ask(String question, Callback callback) {
        String key = key(question);
        if (key.isEmpty()) {
            reply(callback, Plan.error());
            return;
        }
        // 铁律：涉黄赌毒等违法关键词一律不送模型，本地直接拒答
        if (AiGuard.blocked(key)) {
            DebugLog.d("AiSearch", "命中敏感词，拒绝调用大模型：" + key);
            reply(callback, Plan.refused());
            return;
        }
        load();
        Plan hit = peek(key);
        if (hit != null) {
            reply(callback, hit);
            return;
        }
        if (!AiSetting.isEnabled()) {
            reply(callback, Plan.error());
            return;
        }
        if (ASKING.putIfAbsent(key, Boolean.TRUE) != null) {
            reply(callback, Plan.error());
            return;
        }
        POOL.execute(() -> {
            Plan plan = null; // null＝没问到（断网、超时、报错），不缓存，下次还能再问
            try {
                plan = request(key);
            } catch (Throwable e) {
                DebugLog.d("AiSearch", "智能搜索模型不可用：" + e);
            }
            ASKING.remove(key);
            if (plan != null) {
                if (MEMO.size() > MEMO_LIMIT) MEMO.clear();
                MEMO.put(key, new Entry(plan));
                save();
            }
            Plan result = plan == null ? Plan.error() : plan;
            App.post(() -> reply(callback, result));
        });
    }

    private static void reply(Callback callback, Plan plan) {
        try {
            if (callback != null) callback.onResult(plan);
        } catch (Throwable ignored) {
        }
    }

    private static Plan request(String question) throws Exception {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "今天日期：" + today + "\n用户说：" + question);

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
            if (response.body() == null) return null;
            String text = response.body().string();
            String content = new JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            DebugLog.d("AiSearch", "「" + question + "」→ " + content);
            return parse(content);
        }
    }

    /**
     * 模型吐的 JSON 逐字段过闸：类型不认识的当没问，字符串限长+过 AiGuard，数值限范围。
     * 模型只能填字段，填出格的全丢掉。
     */
    static Plan parse(String content) {
        if (content == null) return null;
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            Plan plan = new Plan();
            switch (json.optString("type")) {
                case "refused":
                    return Plan.refused();
                case "keyword":
                    plan.type = Plan.KEYWORD;
                    break;
                case "actor":
                    plan.type = Plan.ACTOR;
                    break;
                case "similar":
                    plan.type = Plan.SIMILAR;
                    break;
                case "discover":
                    plan.type = Plan.DISCOVER;
                    break;
                default:
                    return null; // 模型说了听不懂的话，就当没问
            }
            plan.summary = clean(json.optString("summary"), 24);
            plan.keywords = cleanList(json.optJSONArray("keywords"), 3, 24);
            plan.actor = clean(json.optString("actor"), 24);
            plan.similar = cleanList(json.optJSONArray("similar"), 4, 30);
            plan.tags = cleanList(json.optJSONArray("tags"), 5, 12);
            plan.titles = cleanList(json.optJSONArray("titles"), 6, 30);
            plan.genre = clean(json.optString("genre"), 8);
            int year = json.optInt("year_min", 0);
            plan.yearMin = year >= 1950 && year <= 2100 ? year : 0; // 离谱的年份（0、99999）就当没限定
            plan.minRating = Math.min(Math.max(json.optDouble("min_rating", 0), 0), 9.5);
            String media = json.optString("media");
            plan.media = "movie".equals(media) || "tv".equals(media) ? media : "both";
            // 掏空了等于没理解：keyword 没关键词、actor 没人名，都当没问
            if (plan.type == Plan.KEYWORD && plan.keywords.isEmpty()) return null;
            if (plan.type == Plan.ACTOR && plan.actor.isEmpty()) return null;
            if (plan.type == Plan.SIMILAR && plan.tags.isEmpty() && plan.titles.isEmpty()) return null;
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        String value = text.trim().replaceAll("\\s+", " ");
        if (value.length() > max) value = value.substring(0, max);
        return AiGuard.blocked(value) ? "" : value;
    }

    private static List<String> cleanList(JSONArray array, int size, int max) {
        List<String> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length() && list.size() < size; i++) {
            String value = clean(array.optString(i, ""), max);
            if (value.length() < 2 || list.contains(value)) continue;
            list.add(value);
        }
        return list;
    }

    private static String key(String question) {
        return question == null ? "" : question.trim().replaceAll("\\s+", " ");
    }

    private static class Entry {

        private final long at;
        private final Plan plan;

        Entry(Plan plan) {
            this(System.currentTimeMillis(), plan);
        }

        Entry(long at, Plan plan) {
            this.at = at;
            this.plan = plan;
        }
    }

    private static void load() {
        if (loaded) return;
        synchronized (AiSearch.class) {
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
                            JSONObject item = new JSONObject(json.optString(key));
                            long at = item.optLong("at", 0);
                            if (System.currentTimeMillis() - at > TTL) continue; // 过期的别再捡回来，不然重启一次就续命一天
                            Plan plan = parse(item.toString());
                            if (plan != null) MEMO.put(key, new Entry(at, plan));
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
            for (java.util.Map.Entry<String, Entry> entry : MEMO.entrySet()) {
                Plan plan = entry.getValue().plan;
                JSONObject item = new JSONObject();
                item.put("at", entry.getValue().at); // 问的时间一并存上，重启后 24 小时照样算数
                item.put("type", nameOf(plan.type));
                item.put("summary", plan.summary);
                item.put("keywords", new JSONArray(plan.keywords));
                item.put("actor", plan.actor);
                item.put("similar", new JSONArray(plan.similar));
                item.put("tags", new JSONArray(plan.tags));
                item.put("titles", new JSONArray(plan.titles));
                item.put("genre", plan.genre);
                item.put("year_min", plan.yearMin);
                item.put("min_rating", plan.minRating);
                item.put("media", plan.media);
                json.put(entry.getKey(), item.toString());
            }
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String nameOf(int type) {
        switch (type) {
            case Plan.REFUSED: return "refused";
            case Plan.KEYWORD: return "keyword";
            case Plan.ACTOR: return "actor";
            case Plan.SIMILAR: return "similar";
            case Plan.DISCOVER: return "discover";
            default: return "";
        }
    }

    private static File file() {
        return new File(App.get().getFilesDir(), "ai_search.json");
    }
}
