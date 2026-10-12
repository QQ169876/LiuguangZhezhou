package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Star;
import com.github.catvod.net.interceptor.FailoverInterceptor;
import com.fongmi.android.tv.setting.AiSetting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    /** 主题不明确的剧，让模型给几个同类题材搜索词；AI 漫剧/动态漫这类也要会推 */
    private static final String SYSTEM_SUGGEST = "你是短剧推荐助手。根据剧名和类型，给出 3 到 5 个适合用来搜索同类短剧的中文题材关键词"
            + "（每个 2~8 个字，例如：重生、穿越、古装、甜宠、复仇、漫剧、动态漫；AI 漫剧、动态漫、沙雕漫这类题材也要覆盖）。"
            + "只输出 JSON，不要任何解释：{\"tags\":[\"词1\",\"词2\",\"词3\"]}";

    /**
     * 补演员：不少站点（夸克网盘这类站源尤其常见）压根不给演员信息，演员区就空着。
     * 这时候请大模型按剧名回忆一下主演，想得出来就填进去（照样能关注、能长按看他的作品）。
     *
     * 2026-10-12 马先生点名要的「按关注的演员筛选检索」：提问时把本机关注的演员名单一起给模型，
     * 让它先判断名单里哪些人参演了这部剧（有的排在最前），再补上它记得的其他主演。
     * 轻度使用：只在演员确实为空时才问，同名只问一次，问不到就算了，绝不拦着你看剧。
     */
    private static final String SYSTEM_ACTORS = "你是影视资料助手。根据剧名回忆这部剧的主要演员，"
            + "给出 2 到 6 个主演的人名（只要人名，中文）。"
            + "如果给了「用户关注的演员」名单，先判断其中哪些人参演了这部剧，把他们排在前面；没参演的不要写进去。"
            + "只输出 JSON，不要任何解释：{\"actors\":[\"名字1\",\"名字2\"]}。真不知道就返回 {\"actors\":[]}，不要编造。";

    private static final ConcurrentHashMap<String, Boolean> MEMO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ASKING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<String>> SUGGEST = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> SUGGESTING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<String>> ACTORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ACTOR_ASKING = new ConcurrentHashMap<>();
    private static final int MEMO_LIMIT = 800;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private static volatile OkHttpClient client;
    private static volatile boolean loaded;
    private static volatile boolean suggestLoaded;
    private static volatile boolean actorLoaded;

    public interface Callback {
        void onResult(boolean ok);
    }

    public interface SuggestCallback {
        void onResult(List<String> tags);
    }

    public interface ActorsCallback {
        void onResult(List<String> names);
    }

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (AiJudge.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(8, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .addInterceptor(new FailoverInterceptor()) // 中继域名连不上时换备用域名（169876.xyz ↔ 12356.cool）
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

    /** 大模型给过的题材词，直接给；没问过返回 null。敏感剧名连缓存都不查 */
    public static List<String> peekSuggest(String name) {
        if (AiGuard.blocked(name)) return null;
        loadSuggest();
        return SUGGEST.get(name);
    }

    /**
     * 主题不明确的剧（本地抠不出题材词），让大模型给几个同类题材搜索词。
     * 结果记在本机，同剧不再重复问；任何异常都走 onResult(null)，调用方退回本地规则。
     */
    public static void suggest(String name, String typeName, String region, SuggestCallback callback) {
        // 铁律：涉黄赌毒等违法关键词一律不送模型，一个字节都不许出去
        if (AiGuard.blocked(name, typeName)) {
            DebugLog.d("AiJudge", "命中敏感词，拒绝调用大模型：" + name);
            replySuggest(callback, null);
            return;
        }
        loadSuggest();
        List<String> hit = SUGGEST.get(name);
        if (hit != null) {
            replySuggest(callback, hit);
            return;
        }
        if (!AiSetting.isEnabled()) {
            replySuggest(callback, null);
            return;
        }
        if (SUGGESTING.putIfAbsent(name, Boolean.TRUE) != null) {
            replySuggest(callback, null); // 已经在问了，别重复烧钱
            return;
        }
        POOL.execute(() -> {
            List<String> tags = null; // null＝没问到（断网、超时、报错），不缓存，下次还能再问
            try {
                tags = requestSuggest(name, typeName, region);
            } catch (Throwable e) {
                DebugLog.d("AiJudge", "推荐词模型不可用，退回本地规则：" + name);
            }
            SUGGESTING.remove(name);
            if (tags != null) {
                if (SUGGEST.size() > MEMO_LIMIT) SUGGEST.clear();
                SUGGEST.put(name, tags);
                saveSuggest();
            }
            List<String> result = tags;
            App.post(() -> replySuggest(callback, result));
        });
    }

    private static void replySuggest(SuggestCallback callback, List<String> tags) {
        try {
            if (callback != null) callback.onResult(tags);
        } catch (Throwable ignored) {
        }
    }

    /** 大模型给过的演员，直接给；没问过返回 null。敏感剧名连缓存都不查 */
    public static List<String> peekActors(String name) {
        if (name == null || name.isEmpty()) return null;
        if (AiGuard.blocked(name)) return null;
        loadActors();
        return ACTORS.get(cleanTitle(name)); // 缓存按洗干净的剧名记：同一个片子换个文件名也认得
    }

    /**
     * 站点没给演员时，请大模型按剧名回忆主演。结果记在本机，同剧不再重复问；
     * 问不到（断网/超时/模型说不认识）返回 null，调用方就当没演员，整块不显示。
     */
    public static void actors(String name, String typeName, String site, ActorsCallback callback) {
        if (name == null || name.isEmpty()) {
            replyActors(callback, null);
            return;
        }
        // 铁律：涉黄赌毒等违法关键词一律不送模型，一个字节都不许出去
        if (AiGuard.blocked(name, typeName, site)) {
            DebugLog.d("AiJudge", "命中敏感词，拒绝调用大模型：" + name);
            replyActors(callback, null);
            return;
        }
        String key = cleanTitle(name); // 网盘站源常拿文件名当剧名，洗干净再问，命中率高得多
        loadActors();
        List<String> hit = ACTORS.get(key);
        if (hit != null) {
            replyActors(callback, hit.isEmpty() ? null : hit); // 空列表＝问过但没结果，不再问第二次
            return;
        }
        if (!AiSetting.isEnabled()) {
            replyActors(callback, null);
            return;
        }
        if (ACTOR_ASKING.putIfAbsent(key, Boolean.TRUE) != null) {
            replyActors(callback, null); // 已经在问了，别重复烧钱
            return;
        }
        POOL.execute(() -> {
            List<String> names = null;
            try {
                names = requestActors(key, typeName, site);
            } catch (Throwable e) {
                names = null;
                DebugLog.d("AiJudge", "补演员模型不可用：" + key);
            }
            ACTOR_ASKING.remove(key);
            if (names != null) {
                if (ACTORS.size() > MEMO_LIMIT) ACTORS.clear();
                ACTORS.put(key, names); // 空列表也记：这部剧模型不认识，下次别再问
                saveActors();
            }
            List<String> result = names == null || names.isEmpty() ? null : names;
            App.post(() -> replyActors(callback, result));
        });
    }

    private static void replyActors(ActorsCallback callback, List<String> names) {
        try {
            if (callback != null) callback.onResult(names);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 网盘站源给的「剧名」常常就是文件名：xxx.2024.1080p.国语中字.mp4、带 [4K] 方括号、
     * 带「第01集」……直接问模型它认不出来。先洗干净再问，命中率高一大截。
     * 洗完为空（整串都是噪声）就退回原名，别把问题问成空气。
     */
    private static String cleanTitle(String name) {
        if (name == null) return "";
        String s = name.trim();
        s = s.replaceAll("(?i)\\.(mp4|mkv|avi|mov|rmvb|flv|wmv|ts|m3u8|webm|iso)$", "");
        s = s.replaceAll("\\[[^\\]]*]", " ");
        s = s.replaceAll("[（(【][^）)】]*[）)】]", " ");
        // 分辨率/编码这些必须是「独立的一段」才删：不加边界的话 hd、sd 这类短词会把剧名里的字母也吃掉
        s = s.replaceAll("(?i)(^|[\\s._\\-\\[\\]()（）【】])(4k|2160p|1080p|720p|480p|hd|sd|hdr|bluray|blu-ray|webrip|web-dl|hdrip|x264|x265|h264|h265|hevc|aac|ac3|dts)(?=$|[\\s._\\-\\[\\]()（）【】])", " ");
        s = s.replaceAll("(国语|粤语|中字|高清|超清|蓝光|抢先版|正式版)", " ");
        s = s.replaceAll("第\\s*\\d{1,3}\\s*[集期话]", " ");
        s = s.replaceAll("[._]+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? name.trim() : s;
    }

    /**
     * 本机关注的演员名单（最多 20 个），交给模型去这部剧里筛一遍 —— 马先生要的「按关注的演员检索」。
     * 只在后台线程调（要读库），这儿是 requestActors 里用的，符合。
     */
    private static String followed() {
        try {
            List<Star> all = Star.getAll();
            if (all == null || all.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Star item : all) {
                if (item == null || item.getDeleted() != 0) continue;
                String name = item.getName();
                if (name == null || name.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append("、");
                sb.append(name.trim());
                if (++count >= 20) break;
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static List<String> requestActors(String name, String typeName, String site) throws Exception {
        String stars = followed();
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "剧名：" + name
                + "\n类型：" + (TextUtils.isEmpty(typeName) ? "未知" : typeName)
                + "\n来源站：" + (TextUtils.isEmpty(site) ? "未知" : site)
                + (stars.isEmpty() ? "" : "\n用户关注的演员：" + stars + "（其中参演了这部剧的，请排在最前面）")
                + "\n问：这部剧的主演有哪些？只给人名。");

        JSONObject body = new JSONObject();
        body.put("model", AiSetting.getModel());
        body.put("temperature", 0);
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM_ACTORS)).put(user));

        Request request = new Request.Builder()
                .url(AiSetting.getEndpoint().replaceAll("/+$", "") + "/chat/completions")
                .addHeader("Authorization", "Bearer " + AiSetting.getKey())
                .post(RequestBody.create(JSON, body.toString()))
                .build();

        try (Response response = http().newCall(request).execute()) {
            if (response.body() == null) return null;
            String text = response.body().string();
            JSONObject json = new JSONObject(text);
            String content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            return parseActors(content);
        }
    }

    /** 模型吐的人名逐个过闸：太短太长都不像人名、脏词直接扔；一个能用的都没有就当没问 */
    private static List<String> parseActors(String content) {
        if (content == null) return null;
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            JSONArray array = json.optJSONArray("actors");
            if (array == null) return null;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < array.length() && names.size() < 6; i++) {
                String name = array.optString(i, "").trim();
                if (name.length() < 2 || name.length() > 12) continue;
                if (AiGuard.blocked(name)) continue;
                if (!names.contains(name)) names.add(name);
            }
            DebugLog.d("AiJudge", "补演员 " + names);
            return names;
        } catch (Exception e) {
            return null; // 模型说了听不懂的话，就当没问
        }
    }

    private static void loadActors() {
        if (actorLoaded) return;
        synchronized (AiJudge.class) {
            if (actorLoaded) return;
            try {
                File file = actorFile();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String key = it.next();
                            JSONArray array = json.optJSONArray(key);
                            if (array == null) continue;
                            List<String> names = new ArrayList<>();
                            for (int i = 0; i < array.length(); i++) {
                                String name = array.optString(i, "");
                                if (!name.isEmpty() && !AiGuard.blocked(name)) names.add(name);
                            }
                            ACTORS.put(key, names); // 空的也放进去＝「问过没结果」
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            actorLoaded = true;
        }
    }

    private static synchronized void saveActors() {
        try {
            JSONObject json = new JSONObject();
            for (java.util.Map.Entry<String, List<String>> entry : ACTORS.entrySet()) json.put(entry.getKey(), new JSONArray(entry.getValue()));
            try (FileOutputStream out = new FileOutputStream(actorFile())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File actorFile() {
        return new File(App.get().getFilesDir(), "ai_actors.json");
    }

    private static List<String> requestSuggest(String name, String typeName, String region) throws Exception {
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "剧名：" + name
                + "\n类型：" + (TextUtils.isEmpty(typeName) ? "未知" : typeName)
                + "\n地区：" + (TextUtils.isEmpty(region) ? "未知" : region)
                + "\n问：这部剧是什么题材？给出 3 到 5 个能搜到同类短剧的关键词。");

        JSONObject body = new JSONObject();
        body.put("model", AiSetting.getModel());
        body.put("temperature", 0);
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM_SUGGEST)).put(user));

        Request request = new Request.Builder()
                .url(AiSetting.getEndpoint().replaceAll("/+$", "") + "/chat/completions")
                .addHeader("Authorization", "Bearer " + AiSetting.getKey())
                .post(RequestBody.create(JSON, body.toString()))
                .build();

        try (Response response = http().newCall(request).execute()) {
            if (response.body() == null) return null;
            String text = response.body().string();
            JSONObject json = new JSONObject(text);
            String content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            return parseSuggest(content);
        }
    }

    /** 模型吐的词逐个过闸：太短不像题材、太长怕是瞎编的句子、脏词直接扔；一个能用的都没有就当没问 */
    private static List<String> parseSuggest(String content) {
        if (content == null) return null;
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            JSONArray array = json.optJSONArray("tags");
            if (array == null) return null;
            List<String> tags = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String tag = array.optString(i, "").trim();
                if (tag.length() < 2 || tag.length() > 8) continue;
                if (AiGuard.blocked(tag)) continue;
                if (!tags.contains(tag)) tags.add(tag);
            }
            DebugLog.d("AiJudge", "推荐题材词 " + tags);
            return tags.isEmpty() ? null : tags;
        } catch (Exception e) {
            return null; // 模型说了听不懂的话，就当没问
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

    private static void loadSuggest() {
        if (suggestLoaded) return;
        synchronized (AiJudge.class) {
            if (suggestLoaded) return;
            try {
                File file = suggestFile();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String key = it.next();
                            JSONArray array = json.optJSONArray(key);
                            if (array == null) continue;
                            List<String> tags = new ArrayList<>();
                            for (int i = 0; i < array.length(); i++) {
                                String tag = array.optString(i, "");
                                if (!tag.isEmpty() && !AiGuard.blocked(tag)) tags.add(tag);
                            }
                            if (!tags.isEmpty()) SUGGEST.put(key, tags);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            suggestLoaded = true;
        }
    }

    private static synchronized void saveSuggest() {
        try {
            JSONObject json = new JSONObject();
            for (java.util.Map.Entry<String, List<String>> entry : SUGGEST.entrySet()) json.put(entry.getKey(), new JSONArray(entry.getValue()));
            try (FileOutputStream out = new FileOutputStream(suggestFile())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File suggestFile() {
        return new File(App.get().getFilesDir(), "ai_suggest.json");
    }
}
