package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.AiSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.CacheControl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 刷剧题材「提示词」共享词库（马先生 2026-11-11）。
 *
 * 内置的题材词是有限的（王爷、重生、穿越、宅斗…），像《小富即安，不，本公子意在天下》
 * 这种「公子／权谋／朝堂」写法，本地一个词都抠不出来，没配大模型时就掉到画像最热族乱撞。
 * 所以改成一台机器学到、所有机器都能用：
 *
 *   文档：/dav/Other/tishici.json（和快捷键 kuaijie.json 同一个目录、同一套 Basic 凭据，见 SelfHost）
 *   结构：{"version":1,"updated":...,"types":[{"name":"古装权谋","words":["公子","权谋"]}]}
 *   ① 没配大模型的客户端：App 启动后台拉一次，拉到的词并进 BingeTags 的词表，匹配时优先用；
 *   ② 配了大模型的客户端：模型每次给出的题材词（tags / profile.genres / 形态 / suggest 词）
 *      先过 AiGuard 脱敏闸，攒起来合并写回文档（读-合并-写，不覆盖别的机器学到的词）；
 *   ③ 服务器不通、文档不存在、解析失败：一律静默回退，用内置默认词表（BingeTags.FAMILIES），
 *      刷剧一个字都不少 —— 远程只是增量，永远不替代内置。
 *
 * 铁律：不因为这份文档多问一次大模型（没配大模型时一个 token 都不烧）；
 * 不把剧名、演员、观看历史写进去（只存题材词，且逐个过脱敏闸）；
 * 全程后台、失败静默、绝不阻塞启动或播放。
 */
public class BingeWords {

    private static final String TAG = "BingeWords";
    public static final String PATH = "/dav/Other/tishici.json";
    private static final String KEY_CACHE = "binge_words_json";
    private static final String KEY_HOST = "binge_words_host";
    private static final String KEY_DIRTY = "binge_words_dirty"; // 本机缓存比服务器新（上次写回没成功）
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int TIMEOUT = 6000;
    private static final int ALT_TIMEOUT = 12000; // 备用域走 CDN，冷启动握手慢
    private static final long MIN_WRITE_GAP = 60000L; // 攒够一分钟才写一次，别每次模型回答都 PUT
    private static final int MAX_WORDS = 400;

    private static volatile List<String> words = Collections.emptyList();
    private static volatile Map<String, List<String>> groups = Collections.emptyMap();
    private static volatile String alive = ShortcutConfig.DOMAINS[0];
    private static final Set<String> builtin = new HashSet<>();
    private static final Object LOCK = new Object();
    private static final Map<String, Set<String>> pending = new LinkedHashMap<>();
    private static final AtomicBoolean writing = new AtomicBoolean(false);
    private static final AtomicBoolean retry = new AtomicBoolean(false);
    private static final AtomicLong lastWrite = new AtomicLong(0);

    private BingeWords() {
    }

    /** BingeTags 静态初始化时把内置词表登记进来：写文档时不必重复塞内置词 */
    public static void builtin(List<String> tags) {
        if (tags == null) return;
        synchronized (LOCK) {
            builtin.clear();
            for (String tag : tags) if (tag != null && !tag.isEmpty()) builtin.add(tag);
        }
    }

    /** App 启动调一次：先让上次的缓存立刻生效，再后台拉最新 */
    public static void boot() {
        try {
            String host = Prefers.getString(KEY_HOST);
            for (String domain : ShortcutConfig.DOMAINS) if (domain.equals(host)) alive = domain;
            apply(Prefers.getString(KEY_CACHE));
        } catch (Throwable ignored) {
        }
        Task.execute(BingeWords::refresh);
    }

    /** 当前生效的远程词（长词优先）：BingeTags 每次合并时拿引用比较，变了才重建 */
    public static List<String> words() {
        return words;
    }

    /**
     * 没配大模型、剧名又抠不出题材词时，从共享文档里挑几个提示词当搜索词。
     * 按「类型」分组挑：组名/组内词跟当前线索（剧名、类型、剧种）沾边的优先，
     * 一个都不沾就退到文档最前面那组（一般是内置默认那批古装权谋/穿越逆袭），
     * 绝不把末世、丧尸这种别的类型的词硬塞给一部古装剧。
     */
    public static List<String> picks(String hint, int limit) {
        List<String> out = new ArrayList<>();
        Map<String, List<String>> snapshot = groups;
        if (snapshot.isEmpty() || limit <= 0) return out;
        String clue = hint == null ? "" : hint;
        List<List<String>> ordered = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
            boolean hit = !clue.isEmpty() && (clue.contains(entry.getKey()) || entry.getKey().contains(clue));
            if (!hit && !clue.isEmpty()) for (String word : entry.getValue()) if (clue.contains(word)) { hit = true; break; }
            if (hit) ordered.add(0, entry.getValue());
            else ordered.add(entry.getValue());
        }
        for (List<String> list : ordered) {
            for (String word : list) {
                if (out.size() >= limit) return out;
                if (!out.contains(word)) out.add(word);
            }
        }
        return out;
    }

    private static void refresh() {
        int start = 0;
        for (int i = 0; i < ShortcutConfig.DOMAINS.length; i++) {
            if (ShortcutConfig.DOMAINS[i].equals(alive)) start = i;
        }
        for (int i = 0; i < ShortcutConfig.DOMAINS.length; i++) {
            String domain = ShortcutConfig.DOMAINS[(start + i) % ShortcutConfig.DOMAINS.length];
            String body = fetch(domain);
            if (body == null) continue;
            // 只有「本机确实比服务器新」这个标记在时才保护：上次写回没成功（只读/断网）时
            // 别让旧文档把学到的词冲掉；平时您在服务器上手工改了文档，照样能拉下来生效
            int local = versionOf(Prefers.getString(KEY_CACHE));
            if (Prefers.getBoolean(KEY_DIRTY, false) && local > 0 && versionOf(body) < local) {
                DebugLog.d(TAG, "服务器文档比本机旧，沿用本机缓存（上次写回未成功）");
                return;
            }
            if (!apply(body)) continue;
            Prefers.put(KEY_DIRTY, false);
            alive = domain;
            Prefers.put(KEY_HOST, domain);
            DebugLog.d(TAG, "提示词已拉取（" + domain + "）共 " + words.size() + " 个词");
            return;
        }
        DebugLog.d(TAG, "提示词三个域名都没取到，按内置默认词表匹配");
    }

    private static String fetch(String domain) {
        try {
            Request request = new Request.Builder().url("https://www." + domain + PATH)
                    .header("Authorization", SelfHost.auth())
                    .header("Cache-Control", "no-cache")
                    .cacheControl(CacheControl.FORCE_NETWORK).get().build();
            int timeout = domain.equals(ShortcutConfig.DOMAINS[0]) ? TIMEOUT : ALT_TIMEOUT;
            try (Response res = OkHttp.client(timeout).newCall(request).execute()) {
                if (!res.isSuccessful() || res.body() == null) return null;
                String body = res.body().string();
                return body == null || body.trim().isEmpty() ? null : sanitize(body);
            }
        } catch (Throwable e) {
            DebugLog.d(TAG, "提示词取不到 " + domain);
            return null;
        }
    }

    /**
     * 这台 WebDAV 覆盖写不截断：新内容比旧的短时，文件会变成「一份新 JSON + 上一版的尾巴」，
     * JSONObject 整串解直接失败，三个域名就都当成拉不到，全线退回内置词表（2026-11-11 实测）。
     * 这里截到第一个完整 JSON 对象再交给下游；只采信主内容，尾巴是旧版本，不参与合并。
     */
    private static String sanitize(String body) {
        String text = body.startsWith("\uFEFF") ? body.substring(1) : body;
        try {
            new JSONObject(text);
            return text;
        } catch (Throwable ignored) {
        }
        String head = firstObject(text);
        if (head == null || head.length() >= text.length()) return null;
        try {
            new JSONObject(head);
            DebugLog.d(TAG, "提示词文档带旧尾巴，截断 " + (text.length() - head.length()) + " 字符后解析成功");
            return head;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 第一个完整 JSON 对象的结束位置：跳过字符串里的括号和转义符 */
    private static String firstObject(String json) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                if (--depth == 0) return json.substring(0, i + 1);
            }
        }
        return null;
    }

    /** 解析文档 → 扁平词表（长词优先、去重）；解析不出来返回 false，缓存不动 */
    private static boolean apply(String body) {
        if (body == null || body.trim().isEmpty()) return false;
        try {
            new JSONObject(body); // 先确认是合法 JSON，再往下解析
            Map<String, Set<String>> parsed = parseGroups(body);
            LinkedHashSet<String> all = new LinkedHashSet<>();
            Map<String, List<String>> byType = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : parsed.entrySet()) {
                List<String> list = new ArrayList<>();
                for (String word : entry.getValue()) {
                    if (!valid(word)) continue;
                    String value = word.trim();
                    if (!list.contains(value)) list.add(value);
                    all.add(value);
                }
                if (!list.isEmpty()) byType.put(entry.getKey(), Collections.unmodifiableList(list));
            }
            if (all.isEmpty()) return false; // 空文档不算数，别把缓存冲掉
            List<String> list = new ArrayList<>(all);
            list.sort((a, b) -> Integer.compare(b.length(), a.length()));
            groups = Collections.unmodifiableMap(byType);
            words = Collections.unmodifiableList(list);
            Prefers.put(KEY_CACHE, body);
            return true;
        } catch (Throwable e) {
            DebugLog.d(TAG, "提示词解析失败，按内置默认词表匹配");
            return false;
        }
    }

    private static Map<String, Set<String>> parseGroups(String body) {
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        if (body == null || body.trim().isEmpty()) return groups;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray types = root.optJSONArray("types");
            if (types != null) for (int i = 0; i < types.length(); i++) {
                JSONObject obj = types.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "").trim();
                if (name.isEmpty() || name.length() > 12) name = "模型补充";
                JSONArray array = obj.optJSONArray("words");
                if (array == null) continue;
                Set<String> set = groups.computeIfAbsent(name, k -> new LinkedHashSet<>());
                for (int j = 0; j < array.length(); j++) {
                    String word = array.optString(j, "").trim();
                    if (valid(word)) set.add(word);
                }
            }
            // 也认平铺的 {"words":[...]}，方便手工编辑
            JSONArray flat = root.optJSONArray("words");
            if (flat != null) {
                Set<String> set = groups.computeIfAbsent("模型补充", k -> new LinkedHashSet<>());
                for (int i = 0; i < flat.length(); i++) {
                    String word = flat.optString(i, "").trim();
                    if (valid(word)) set.add(word);
                }
            }
        } catch (Throwable ignored) {
        }
        return groups;
    }

    private static boolean valid(String word) {
        return word != null && word.trim().length() >= 2 && word.trim().length() <= 8 && !AiGuard.blocked(word);
    }

    private static String clean(String word) {
        if (word == null) return "";
        String text = word.trim().replaceAll("[《》\"'“”‘’\\s]", "");
        if (text.length() < 2 || text.length() > 8 || AiGuard.blocked(text)) return "";
        synchronized (LOCK) {
            if (builtin.contains(text)) return ""; // 内置已经有了，文档里不用再存一份
        }
        return text;
    }

    /**
     * 配了大模型的客户端把模型给出的题材词攒起来（马先生 2026-11-11）。
     * type 是分组名（题材/形态），words 是模型回答里的 tags / genres / 形态词。
     * 只攒不立刻生效，等写回成功才 apply；没配大模型时这里压根不会被调到。
     */
    public static void learn(String type, List<String> wordsIn) {
        if (!AiSetting.ready() || wordsIn == null || wordsIn.isEmpty()) return;
        String name = type == null || type.trim().isEmpty() ? "模型补充" : type.trim();
        if (name.length() > 12) name = name.substring(0, 12);
        boolean added = false;
        synchronized (LOCK) {
            for (String raw : wordsIn) {
                String word = clean(raw);
                if (word.isEmpty()) continue;
                if (words.contains(word) || pendingContains(word)) continue;
                pending.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(word);
                added = true;
            }
        }
        if (added) Task.execute(BingeWords::flush);
    }

    public static void learn(String type, String word) {
        if (word == null || word.isEmpty()) return;
        List<String> one = new ArrayList<>(1);
        one.add(word);
        learn(type, one);
    }

    private static boolean pendingContains(String word) {
        for (Set<String> set : pending.values()) if (set.contains(word)) return true;
        return false;
    }

    /** 读最新 → 合并 → 写回：不覆盖别的客户端刚学到的词；失败静默，下次再试 */
    private static void flush() {
        // 不检查 AiSetting.ready()：pending 里的词只可能是配着大模型时攒下的，
        // 用户中途把模型关了也该把已学到的词写回去，不然白学
        if (!writing.compareAndSet(false, true)) return;
        try {
            long wait = MIN_WRITE_GAP - (System.currentTimeMillis() - lastWrite.get());
            if (wait > 0) {
                // 别把这一批词丢在内存里：到期自动重试一次，多个 learn 只排一个重试任务
                if (retry.compareAndSet(false, true)) {
                    Task.schedule(() -> {
                        retry.set(false);
                        flush();
                    }, wait, TimeUnit.MILLISECONDS);
                }
                return;
            }
            Map<String, Set<String>> batch;
            synchronized (LOCK) {
                if (pending.isEmpty()) return;
                batch = new LinkedHashMap<>();
                for (Map.Entry<String, Set<String>> entry : pending.entrySet()) {
                    batch.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
                }
            }
            String domain = alive;
            String body = fetch(domain);
            if (body == null) {
                for (String alt : ShortcutConfig.DOMAINS) {
                    if (alt.equals(domain)) continue;
                    body = fetch(alt);
                    if (body != null) {
                        domain = alt;
                        break;
                    }
                }
            }
            Map<String, Set<String>> groups = parseGroups(body);
            if (groups.isEmpty()) groups = seed();
            int total = 0;
            for (Set<String> set : groups.values()) total += set.size();
            for (Map.Entry<String, Set<String>> entry : batch.entrySet()) {
                Set<String> set = groups.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>());
                for (String word : entry.getValue()) {
                    if (total >= MAX_WORDS) break;
                    if (set.add(word)) total++;
                }
            }
            int version = versionOf(body) + 1;
            String json = toJson(groups, version);
            Prefers.put(KEY_DIRTY, true); // 本机缓存已比服务器新，写回成功前不许旧文档冲掉
            apply(json); // 本机先立即生效：服务器只读/断网时学到的词照样能用（马先生 2026-11-11）
            boolean pushed = put(domain, json);
            if (!pushed) for (String alt : ShortcutConfig.DOMAINS) { // 主域 PUT 不通就换备用域
                if (alt.equals(domain)) continue;
                if (put(alt, json)) { pushed = true; domain = alt; break; }
            }
            if (!pushed) {
                DebugLog.d(TAG, "提示词写回失败（服务器只读或网络不通），本机已生效，下次再试");
                return; // pending 不清，有新词时再试；本机 words 已经更新
            }
            Prefers.put(KEY_DIRTY, false);
            lastWrite.set(System.currentTimeMillis());
            synchronized (LOCK) {
                for (Map.Entry<String, Set<String>> entry : batch.entrySet()) {
                    Set<String> set = pending.get(entry.getKey());
                    if (set != null) set.removeAll(entry.getValue());
                    if (set != null && set.isEmpty()) pending.remove(entry.getKey());
                }
            }
            DebugLog.d(TAG, "提示词已写回 v" + version + "，共 " + total + " 个词");
        } catch (Throwable e) {
            DebugLog.d(TAG, "提示词写回异常，下次再试");
        } finally {
            writing.set(false);
        }
    }

    /**
     * 整份写回并回读校验：服务器 PUT 覆盖不截断时，写完 GET 会发现尾部挂着上一版，
     * 这时删掉重传一次再校验。不先删是为了避免 DELETE 成功、PUT 失败把文档弄丢；
     * 真失败了也尽力把原文放回去，别让云端比写之前更糟。
     */
    private static boolean put(String domain, String body) {
        String url = "https://www." + domain + PATH;
        try {
            if (!call(url, "PUT", body)) return false;
            String echo = get(url);
            if (same(body, echo)) return true;
            call(url, "DELETE", null);
            if (!call(url, "PUT", body)) {
                restore(url, echo);
                return false;
            }
            String again = get(url);
            if (same(body, again)) return true;
            // 二次校验没过也不能把上一版写回去：文件里很可能已经是新内容，
            // 回写反而把刚学到的词冲掉。判失败让下次重试，云端保持现状。
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean call(String url, String method, String body) {
        Request.Builder builder = new Request.Builder().url(url).header("Authorization", SelfHost.auth());
        if ("DELETE".equals(method)) builder.delete();
        else if (body == null) builder.get();
        else builder.put(RequestBody.create(body, JSON));
        try (Response res = OkHttp.client(TIMEOUT).newCall(builder.build()).execute()) {
            return res.isSuccessful();
        } catch (Throwable e) {
            return false;
        }
    }

    private static String get(String url) {
        Request request = new Request.Builder().url(url)
                .header("Authorization", SelfHost.auth())
                .header("Cache-Control", "no-cache")
                .cacheControl(CacheControl.FORCE_NETWORK).get().build();
        try (Response res = OkHttp.client(TIMEOUT).newCall(request).execute()) {
            if (!res.isSuccessful() || res.body() == null) return null;
            return res.body().string();
        } catch (Throwable e) {
            return null;
        }
    }

    /** DELETE 成功、随后 PUT 却失败时把上一版内容尽力放回去 */
    private static void restore(String url, String previous) {
        if (previous == null) return;
        call(url, "PUT", previous);
    }

    private static boolean same(String expect, String actual) {
        if (actual == null) return false;
        String text = actual.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        return expect.trim().equals(text);
    }

    private static int versionOf(String body) {
        if (body == null) return 0;
        try {
            return new JSONObject(body).optInt("version", 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String toJson(Map<String, Set<String>> groups, int version) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", version);
            root.put("updated", System.currentTimeMillis());
            JSONArray types = new JSONArray();
            for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                JSONObject obj = new JSONObject();
                obj.put("name", entry.getKey());
                obj.put("words", new JSONArray(entry.getValue()));
                types.put(obj);
            }
            root.put("types", types);
            return root.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 文档还不存在时用它起步：就是上一轮建议补进内置的那批词，按类型分好，方便在服务器上直接看/改 */
    private static Map<String, Set<String>> seed() {
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        groups.put("古装权谋", set("公子", "本公子", "权谋", "朝堂", "夺嫡", "首辅", "科举", "入仕"));
        groups.put("穿越逆袭", set("穿越", "逆袭", "扮猪吃虎", "穿书", "系统流"));
        groups.put("形态", set("AI漫剧", "动态漫", "真人短剧", "网剧", "沙雕漫"));
        return groups;
    }

    private static Set<String> set(String... values) {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, values);
        return set;
    }
}
