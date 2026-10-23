package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     *
     * 2026-10-24 马先生定的分层：站源（网盘那类）不给演员信息时才来问；先让模型报这部是什么形态——
     * 漫剧/动态漫/动画没有真人演员，演员区整块不显示；真人剧才把演员列出来。
     * 形态由模型报、由本地再卡一道，比只指望它自觉返回空数组稳。
     */
    private static final String SYSTEM_ACTORS = "你是影视资料助手。先判断这部作品是什么形态，再回忆它的主要演员。"
            + "只输出 JSON，不要任何解释：{\"form\":\"漫剧|动态漫|动画|真人剧|电影|未知\",\"actors\":[\"名字1\",\"名字2\"],\"studio\":\"出品方\"}。"
            + "form：AI漫剧、动态漫、沙雕漫填「漫剧」；动漫、动画、卡通填「动画」；真人出演的剧集或短剧填「真人剧」；"
            + "电影填「电影」；拿不准填「未知」。"
            + "actors：2 到 6 个主演的人名（只要人名，中文）。"
            + "如果给了年份，同名作品或翻拍版要以该年份的版本为准，别答成另一部同名剧。"
            + "外国演员给中文译名，人名中间的间隔号要保留（如「小罗伯特·唐尼」），"
            + "一个人的姓和名必须放在同一个字符串里，绝不能拆成两个人。"
            + "只报你确信参演了这部剧的演员，不确定的不要报，更不要把某个常演短剧的演员往这部剧上硬套。"
            + "漫剧、动态漫、动漫、动画、卡通这类作品没有真人演员，actors 一律给空数组；"
            + "但 studio 要填它的出品方——导演、工作室、版权方、出品公司都行（比如「某某漫剧工作室」），"
            + "用户要的就是关注这个出品方，好接着看它出的其它漫剧；确实查不到就填空串。"
            + "真人剧的 studio 填空串即可（那类看演员就够了）。"
            + "真不知道就返回 {\"form\":\"未知\",\"actors\":[],\"studio\":\"\"}，不要编造。";

    /**
     * 刷剧「下一部」：以前是我们拿题材词去站里撞，撞到什么算什么（相关度全靠名字里有没有那个词）。
     * 现在多一条路——让模型直接报出**真实存在的同类剧名**，我们拿这些名字去站里搜，
     * 搜到就是它，命中率比题材词高一大截。2026-10-19 马先生要求。
     *
     * 铁律不变：关掉、断网、超时、返回乱码一律当「没问」，刷剧照旧走本地词表，绝不卡住。
     */
    private static final String SYSTEM_NEXT = "你是短剧推荐助手。用户刚看完一部剧，要一部接一部往下刷。"
            + "请推荐真实存在、在影视站里能搜到的同类作品**剧名**（要剧名，不是题材词）："
            + "1. 必须跟它是**同一种形态**（按给出的「剧种」：漫剧就只给漫剧/动态漫/AI漫剧，"
            + "短剧就只给真人短剧，电视剧就只给电视剧；不许跨到真人剧、电影、解说去）"
            + "和**同一个地区**（按给出的「地区」；地区写「未知」时按剧名语言判断，中文剧名就只给中文剧）；"
            + "2. 不要给电影、综艺、解说、混剪、盘点、番外；"
            + "3. 不要给当前这部本身、它的续作或前作，也不要给「已看过」名单里的；"
            + "4. 最像的排在最前，最多 5 个。判不出同形态同地区的宁可少给，绝不编造不存在的剧。"
            + "另外给 2~3 个这类剧的题材词（每个 2~8 字，用来在站里搜同类，题材词也要是同一形态的）。"
            + "只输出 JSON，不要任何解释：{\"titles\":[\"剧名1\",\"剧名2\"],\"tags\":[\"题材词\"]}";

    /**
     * 系列剧「下一季」：站源写法五花八门（第3季 / 第三季 / 剧名3 / S3 / 之某某），
     * 本地正则抠不全会漏，模型知道这个系列的正名和季号排列，让它把可能的写法一次性给齐。
     */
    private static final String SYSTEM_NEXT_SEASON = "你是影视资料助手。用户刚看完一个系列的某一季，要接着看它的下一季。"
            + "请给出这个系列**下一季**在影视站里可能出现的名称写法，每种写法都要："
            + "中文季号（第X季）、阿拉伯季号（第X季）、连写（剧名X）、英文（Season X / SX）、以及续作另起的名字（如「XX之YYY」）。"
            + "只要下一季，最多 4 个；这个系列确定没有下一季就返回空数组。"
            + "只输出 JSON，不要任何解释：{\"titles\":[\"名称1\"],\"tags\":[]}";

    private static final ConcurrentHashMap<String, Boolean> MEMO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ASKING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<String>> SUGGEST = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> SUGGESTING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Cast> ACTORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ACTOR_ASKING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Next> NEXT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> NEXT_ASKING = new ConcurrentHashMap<>();
    private static final int MEMO_LIMIT = 800;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private static volatile OkHttpClient client;
    private static volatile boolean loaded;
    private static volatile boolean suggestLoaded;
    private static volatile boolean actorLoaded;
    private static volatile boolean nextLoaded;

    public interface Callback {
        void onResult(boolean ok);
    }

    public interface SuggestCallback {
        void onResult(List<String> tags);
    }

    public interface ActorsCallback {
        void onResult(Cast cast);
    }

    public interface NextCallback {
        void onResult(Next next);
    }

    /** 刷剧「下一部/下一季」的答案：具体剧名（拿去站里搜）＋ 几个兜底题材词 */
    public static final class Next {
        public final List<String> titles;
        public final List<String> tags;

        Next(List<String> titles, List<String> tags) {
            this.titles = titles == null ? new ArrayList<>() : titles;
            this.tags = tags == null ? new ArrayList<>() : tags;
        }

        boolean isEmpty() {
            return titles.isEmpty() && tags.isEmpty();
        }
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
        if (!AiSetting.isEnabled()) return null; // 铁律：开关没开，连上次问出来的缓存也不许用
        if (AiGuard.blocked(cacheKey)) return null; // 敏感条目连缓存都不查
        load();
        return MEMO.get(cacheKey);
    }

    /**
     * 问一句「这算不算一部短剧」。结果会记在本机，同名不再重复问。
     * 任何异常都走 onResult(false)，调用方自己兜底。
     */
    public static void ask(String cacheKey, String name, String remarks, int episodes, long duration, String site, Callback callback) {
        // 二级闸门：本地先把违法/18禁关键词抠掉、句子理顺，剩下的才送模型；抠光了才拒答
        String[] safe = AiGuard.pass(name, remarks, site);
        if (safe == null) {
            DebugLog.d("AiJudge", "整句都是违禁词，拒绝调用大模型：" + name);
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
                ok = request(safe[0], safe[1], episodes, duration, safe[2]);
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
        if (!AiSetting.isEnabled()) return null; // 铁律：开关没开，缓存里的模型答案一律不翻
        if (AiGuard.blocked(name)) return null;
        loadSuggest();
        return SUGGEST.get(name);
    }

    /**
     * 主题不明确的剧（本地抠不出题材词），让大模型给几个同类题材搜索词。
     * 结果记在本机，同剧不再重复问；任何异常都走 onResult(null)，调用方退回本地规则。
     */
    public static void suggest(String name, String typeName, String region, SuggestCallback callback) {
        // 二级闸门：先抠违禁词、理顺，剩下的才送模型；抠光了才拒答
        String[] safe = AiGuard.pass(name, typeName, region);
        if (safe == null) {
            DebugLog.d("AiJudge", "整句都是违禁词，拒绝调用大模型：" + name);
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
                tags = requestSuggest(safe[0], safe[1], safe[2]);
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

    /**
     * 这类片子压根没有「真人演员」这回事：漫剧、动态漫、沙雕漫、动漫、动画、卡通。
     * 名字或类型里写了就不问模型、也不读缓存 —— 2026-10-18 实测：给模型带「用户关注的演员」名单时，
     * 碰上 AI 漫剧它会顺着名单瞎认领（明明没这人，也把姜十七贴上来），本地闸比指望它自觉可靠。
     */
    private static boolean noCast(String name, String typeName) {
        String s = (name == null ? "" : name) + (typeName == null ? "" : typeName);
        return s.matches("(?i).*(漫剧|动态漫|沙雕漫|动漫|动画|卡通|ai漫).*");
    }

    /** 模型报的形态有没有真人演员这回事：漫剧、动态漫、沙雕漫、动漫、动画、卡通都没有 */
    private static boolean noCastForm(String form) {
        if (form == null) return false;
        return form.matches("(?i).*(漫|动漫|动画|卡通).*");
    }

    /** 大模型给过的「人」（主演名单 + 出品方），直接给；没问过返回 null。敏感剧名连缓存都不查 */
    public static Cast peekActors(String name, String typeName, String year) {
        if (name == null || name.isEmpty()) return null;
        if (!AiSetting.isEnabled()) return null; // 铁律：开关没开，缓存里的演员名单也不许翻出来
        if (AiGuard.blocked(name)) return null;
        loadActors();
        return ACTORS.get(actorKey(name, year)); // 缓存按「洗干净的剧名+年份」记：换文件名认得，同名不同版也不串
    }

    /** 缓存键：洗干净的剧名 + 年份（2026-10-20 马先生点名要带年份——同名剧、翻拍版靠它区分） */
    private static String actorKey(String name, String year) {
        String y = plainYear(year);
        return cleanTitle(name) + (y.isEmpty() ? "" : "·" + y);
    }

    /** 站点给的年份五花八门（2023、2023-01、2023年）：只要那 4 位数字，没有就算没给 */
    private static String plainYear(String year) {
        if (year == null) return "";
        Matcher m = Pattern.compile("\\d{4}").matcher(year);
        return m.find() ? m.group() : "";
    }

    /**
     * 一部剧的「人」：主演名单，外加没有演员时的出品方（导演 / 工作室 / 版权方）。
     *
     * 2026-10-24 马先生定：漫剧这类没有真人演员的作品，演员区要么不显示、要么显示出品方
     * （比如「某某漫剧工作室」），关注它以后它出的漫剧就能优先推。
     */
    public static final class Cast {

        public final List<String> names;
        public final String studio;

        Cast(List<String> names, String studio) {
            this.names = names == null ? new ArrayList<>() : names;
            this.studio = studio == null ? "" : studio;
        }

        boolean empty() {
            return names.isEmpty() && studio.isEmpty();
        }
    }

    /**
     * 站点没给演员（或给的演员串拆不开）时，请大模型按剧名+年份回忆主演。结果记在本机，同剧不再重复问；
     * 问不到（断网/超时/模型说不认识）返回 null，调用方就当没演员，整块不显示。
     * 漫剧、动画不问演员，但仍要问出品方（actor 那一段就交白卷，studio 才是要的）。
     */
    public static void actors(String name, String typeName, String site, String year, ActorsCallback callback) {
        if (name == null || name.isEmpty()) {
            replyActors(callback, null);
            return;
        }
        // 二级闸门：先抠违禁词、理顺，剩下的才送模型；抠光了才拒答
        String[] safe = AiGuard.pass(name, typeName, site);
        if (safe == null) {
            DebugLog.d("AiJudge", "整句都是违禁词，拒绝调用大模型：" + name);
            replyActors(callback, null);
            return;
        }
        String key = actorKey(name, year); // 网盘站源常拿文件名当剧名，洗干净再问；年份一并入键，同名剧不串
        loadActors();
        Cast hit = ACTORS.get(key);
        if (hit != null) {
            replyActors(callback, hit.empty() ? null : hit); // 空＝问过但没结果，不再问第二次
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
            Cast cast = null;
            try {
                cast = requestActors(key, cleanTitle(safe[0]), safe[1], safe[2], year);
            } catch (Throwable e) {
                cast = null;
                DebugLog.d("AiJudge", "补演员模型不可用：" + key);
            }
            ACTOR_ASKING.remove(key);
            if (cast != null) {
                if (ACTORS.size() > MEMO_LIMIT) ACTORS.clear();
                ACTORS.put(key, cast); // 空的也记：这部剧模型不认识，下次别再问
                saveActors();
            }
            Cast result = cast == null || cast.empty() ? null : cast;
            App.post(() -> replyActors(callback, result));
        });
    }

    private static void replyActors(ActorsCallback callback, Cast cast) {
        try {
            if (callback != null) callback.onResult(cast);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 刷剧要用「下一部/下一季」时先问缓存：问过就有结果（可能是空＝模型说不认识这部剧），
     * 没问过返回 null，调用方先按本地词表走，同时可以在后台发问留给下一次。
     */
    public static Next peekNext(String name, String kindWord, String region) {
        if (name == null || name.isEmpty()) return null;
        if (!AiSetting.isEnabled()) return null; // 铁律：开关没开，缓存里的推荐也不许用
        if (AiGuard.blocked(name)) return null;
        loadNext();
        return NEXT.get(nextKey(name, kindWord, region));
    }

    /**
     * 缓存键：剧名 + 剧种（形态）+ 产地。
     * 2026-10-24 马先生反馈「看国产 AI 漫剧，下一步推出韩剧」——以前只按剧名记，
     * 同一个剧名换形态/产地后还拿旧答案，跨类别就是这么来的。现在形态一变旧答案自动作废。
     */
    private static String nextKey(String name, String kindWord, String region) {
        return name + "·" + (TextUtils.isEmpty(kindWord) ? "" : kindWord) + "·" + (TextUtils.isEmpty(region) ? "" : region);
    }

    /**
     * 问一句「下一部该看什么」：让模型报出同类剧名（系列剧则报下一季的各种写法）。
     * 同名只问一次，结果记在本机；任何异常都走 onResult(null)，刷剧退回本地题材词，不受影响。
     *
     * ⚠️ 这是**纯建议**：模型报的剧名站里未必有，搜不到就当没这回事，下一步照旧换词搜。
     */
    public static void nextUp(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex, NextCallback callback) {
        if (name == null || name.isEmpty()) {
            replyNext(callback, null);
            return;
        }
        // 二级闸门：先抠违禁词、理顺，剩下的才送模型；抠光了才拒答
        String[] safe = AiGuard.pass(name, typeName, actor);
        if (safe == null) {
            DebugLog.d("AiJudge", "整句都是违禁词，拒绝调用大模型：" + name);
            replyNext(callback, null);
            return;
        }
        String key = nextKey(name, kindWord, region); // 形态/产地变了旧答案作废
        loadNext();
        Next hit = NEXT.get(key);
        if (hit != null) {
            replyNext(callback, hit.isEmpty() ? null : hit); // 空＝问过没结果，别再问第二次
            return;
        }
        if (!AiSetting.isEnabled()) {
            replyNext(callback, null);
            return;
        }
        if (NEXT_ASKING.putIfAbsent(key, Boolean.TRUE) != null) {
            replyNext(callback, null); // 已经在问了，别重复烧钱
            return;
        }
        POOL.execute(() -> {
            Next next = null;
            try {
                next = requestNext(safe[0], safe[1], region, kindWord, safe[2], seriesMain, seriesIndex);
            } catch (Throwable e) {
                next = null;
                DebugLog.d("AiJudge", "下一部推荐模型不可用，退回本地词表：" + name);
            }
            NEXT_ASKING.remove(key);
            if (next != null) { // 空的也记：这部剧模型不认识/没有下一季，下次别再问
                if (NEXT.size() > MEMO_LIMIT) NEXT.clear();
                NEXT.put(key, next);
                saveNext();
            }
            Next result = next == null || next.isEmpty() ? null : next;
            App.post(() -> replyNext(callback, result));
        });
    }

    private static void replyNext(NextCallback callback, Next next) {
        try {
            if (callback != null) callback.onResult(next);
        } catch (Throwable ignored) {
        }
    }

    private static Next requestNext(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex) throws Exception {
        boolean serial = seriesIndex > 0 && seriesMain != null && !seriesMain.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("剧名：").append(name);
        sb.append("\n类型：").append(TextUtils.isEmpty(typeName) ? "未知" : typeName);
        sb.append("\n地区：").append(TextUtils.isEmpty(region) ? "未知" : region);
        sb.append("\n剧种（形态）：").append(TextUtils.isEmpty(kindWord) ? "未知" : kindWord);
        if (!TextUtils.isEmpty(kindWord)) sb.append("\n只给同一种形态、同一个地区的，跨形态跨地区的不要。");
        if (!TextUtils.isEmpty(actor)) sb.append("\n主演：").append(actor);
        if (serial) sb.append("\n这是系列剧，系列名：").append(seriesMain).append("，刚看完第").append(seriesIndex).append("季");
        String watched = watched(name);
        if (!watched.isEmpty()) sb.append("\n已看过（不要推荐）：").append(watched);
        sb.append(serial ? "\n问：这个系列的下一季叫什么？把可能的写法都列出来。" : "\n问：接下来该看哪几部同类的剧？给出剧名。");

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", sb.toString());

        JSONObject body = new JSONObject();
        body.put("model", AiSetting.getModel());
        body.put("temperature", 0);
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", serial ? SYSTEM_NEXT_SEASON : SYSTEM_NEXT)).put(user));

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
            return parseNext(content, name);
        }
    }

    /**
     * 模型报的剧名逐个过闸：太短太长都不像剧名、跟当前这部一样的不收、脏词直接扔。
     * 一个都不剩就当没问（返回空 Next，调用方退回本地词表）。
     */
    private static Next parseNext(String content, String current) {
        if (content == null) return null;
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            List<String> titles = new ArrayList<>();
            JSONArray array = json.optJSONArray("titles");
            if (array != null) {
                for (int i = 0; i < array.length() && titles.size() < 6; i++) {
                    String title = array.optString(i, "").trim();
                    title = title.replaceAll("^[\\d]+[.、)．]\\s*", "").trim(); // 模型偶尔带个「1. 」序号
                    title = title.replaceAll("[《》]", "").trim(); // 模型爱给书名号
                    if (title.length() < 2 || title.length() > 24) continue;
                    if (title.equals(current)) continue; // 就是当前这部，等于没说
                    if (AiGuard.blocked(title)) continue;
                    if (!titles.contains(title)) titles.add(title);
                }
            }
            List<String> tags = new ArrayList<>();
            JSONArray tagArray = json.optJSONArray("tags");
            if (tagArray != null) {
                for (int i = 0; i < tagArray.length() && tags.size() < 4; i++) {
                    String tag = tagArray.optString(i, "").trim();
                    if (tag.length() < 2 || tag.length() > 8) continue;
                    if (AiGuard.blocked(tag)) continue;
                    if (!tags.contains(tag)) tags.add(tag);
                }
            }
            DebugLog.d("AiJudge", "下一部推荐 " + titles + " 题材词 " + tags);
            return new Next(titles, tags);
        } catch (Exception e) {
            return null; // 模型说了听不懂的话，就当没问
        }
    }

    /**
     * 最近看过的剧名（最多 30 个），塞给模型让它别推重复的。
     * 只在后台线程调（要读库），requestNext 里用，符合。
     */
    private static String watched(String exclude) {
        try {
            List<History> items = History.get();
            if (items == null || items.isEmpty()) return "";
            List<String> names = new ArrayList<>();
            for (History item : items) {
                String name = item == null ? null : item.getVodName();
                if (name == null || name.trim().isEmpty()) continue;
                name = name.trim();
                if (name.equals(exclude) || names.contains(name)) continue;
                names.add(name);
                if (names.size() >= 30) break;
            }
            return TextUtils.join("、", names);
        } catch (Throwable e) {
            return "";
        }
    }

    private static void loadNext() {
        if (nextLoaded) return;
        synchronized (AiJudge.class) {
            if (nextLoaded) return;
            try {
                File file = nextFile();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String key = it.next();
                            JSONObject item = json.optJSONObject(key);
                            if (item == null) continue;
                            List<String> titles = new ArrayList<>();
                            JSONArray array = item.optJSONArray("titles");
                            if (array != null) for (int i = 0; i < array.length(); i++) {
                                String title = array.optString(i, "");
                                if (!title.isEmpty() && !AiGuard.blocked(title)) titles.add(title);
                            }
                            List<String> tags = new ArrayList<>();
                            JSONArray tagArray = item.optJSONArray("tags");
                            if (tagArray != null) for (int i = 0; i < tagArray.length(); i++) {
                                String tag = tagArray.optString(i, "");
                                if (!tag.isEmpty() && !AiGuard.blocked(tag)) tags.add(tag);
                            }
                            NEXT.put(key, new Next(titles, tags)); // 空的也放进去＝「问过没结果」
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            nextLoaded = true;
        }
    }

    private static synchronized void saveNext() {
        try {
            JSONObject json = new JSONObject();
            for (java.util.Map.Entry<String, Next> entry : NEXT.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("titles", new JSONArray(entry.getValue().titles));
                item.put("tags", new JSONArray(entry.getValue().tags));
                json.put(entry.getKey(), item);
            }
            try (FileOutputStream out = new FileOutputStream(nextFile())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File nextFile() {
        return new File(App.get().getFilesDir(), "ai_next.json");
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

    private static Cast requestActors(String key, String name, String typeName, String site, String year) throws Exception {
        // ⚠️ 不带「用户关注的演员」名单了（2026-10-20 马先生发火）：带名单时模型会顺着名单瞎认领，
        // 没演员的剧十有八九给杳出个姜十七（短剧常客，模型最容易往她身上套），token 白烧。
        // 关注标记是本地比对出来的，名单对模型没半点用处，纯粹是污染。
        String y = plainYear(year);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "剧名：" + name
                + (y.isEmpty() ? "" : "\n年份：" + y + "（同名剧、翻拍版要靠这个年份区分，认准这个年份的版本）")
                + "\n类型：" + (TextUtils.isEmpty(typeName) ? "未知" : typeName)
                + "\n来源站：" + (TextUtils.isEmpty(site) ? "未知" : site)
                + "\n问：这部剧的主演有哪些？没有真人演员的（漫剧/动画）就给出品方（导演、工作室、版权方）。");

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
            return parseCast(content);
        }
    }

    /** 出品方：太长太短都不像「某某工作室」这类名字，脏词直接扔 */
    private static String cleanStudio(String studio) {
        if (studio == null) return "";
        String name = studio.trim().replaceAll("^[\\d]+[.、)．]\\s*", "").trim();
        if (name.length() < 2 || name.length() > 30) return "";
        return AiGuard.blocked(name) ? "" : name;
    }

    /** 模型吐的人名逐个过闸：太短太长都不像人名、脏词直接扔；一个能用的都没有就当没问 */
    private static Cast parseCast(String content) {
        if (content == null) return null;
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            String studio = cleanStudio(json.optString("studio"));
            // 形态这道闸本地再卡一遍：模型报了漫剧/动画却还列了一串人名，那串也不能信（2026-10-24）
            if (noCastForm(json.optString("form"))) {
                DebugLog.d("AiJudge", "模型报的形态没有真人演员，只显示出品方：" + studio);
                return new Cast(new ArrayList<>(), studio);
            }
            JSONArray array = json.optJSONArray("actors");
            if (array == null) return new Cast(new ArrayList<>(), studio);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < array.length() && names.size() < 6; i++) {
                String name = array.optString(i, "").trim();
                name = name.replaceAll("^[\\d]+[.、)．]\\s*", "").trim(); // 模型偶尔带个「1. 」序号
                // 上限放到 24：外国演员的中文译名带间隔号能到十来字，写原名的更长
                // （「Samuel L. Jackson」17 个字符），按 12 卡会把这些人整个丢掉。
                if (name.length() < 2 || name.length() > 24) continue;
                if (AiGuard.blocked(name)) continue;
                if (!names.contains(name)) names.add(name);
            }
            DebugLog.d("AiJudge", "补演员 " + names + "，出品方 " + studio);
            return new Cast(names, studio);
        } catch (Exception e) {
            return null; // 模型说了听不懂的话，就当没问
        }
    }

    private static void loadActors() {
        if (actorLoaded) return;
        synchronized (AiJudge.class) {
            if (actorLoaded) return;
            try {
                wipeLegacyActors(); // 旧版答案是被关注名单污染过的（没演员的剧硬杳姜十七那批），一次性作废
                File file = actorFile();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String key = it.next();
                            JSONArray legacy = json.optJSONArray(key); // 老格式：只记了名单
                            if (legacy != null) {
                                ACTORS.put(key, new Cast(read(legacy), "")); // 空的也放进去＝「问过没结果」
                                continue;
                            }
                            JSONObject object = json.optJSONObject(key);
                            if (object == null) continue;
                            String studio = object.optString("studio", "");
                            ACTORS.put(key, new Cast(read(object.optJSONArray("actors")), AiGuard.blocked(studio) ? "" : studio));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            actorLoaded = true;
        }
    }

    /** 读一个 JSON 数组里的人名（脏词、空串丢掉） */
    private static List<String> read(JSONArray array) {
        List<String> names = new ArrayList<>();
        if (array == null) return names;
        for (int i = 0; i < array.length(); i++) {
            String name = array.optString(i, "");
            if (!name.isEmpty() && !AiGuard.blocked(name)) names.add(name);
        }
        return names;
    }

    private static synchronized void saveActors() {
        try {
            JSONObject json = new JSONObject();
            for (java.util.Map.Entry<String, Cast> entry : ACTORS.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("actors", new JSONArray(entry.getValue().names));
                item.put("studio", entry.getValue().studio);
                json.put(entry.getKey(), item);
            }
            try (FileOutputStream out = new FileOutputStream(actorFile())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File actorFile() {
        return new File(App.get().getFilesDir(), "ai_actors.json");
    }

    /**
     * 旧版（≤5.6.122）的演员答案都是带着关注名单问出来的，里面躺着不少瞎认领的“姜十七”，
     * 升级后第一次加载数据库时把整个文件删掉：宁可每部剧重问一次（结果会重新缓存），
     * 也不能让污染过的旧答案继续挂在演员区里骗人。
     */
    private static void wipeLegacyActors() {
        File marker = new File(App.get().getFilesDir(), "ai_actors_v2.marker");
        if (marker.exists()) return; // 已经清过一次了
        File old = actorFile();
        if (old.exists()) old.delete();
        try {
            marker.createNewFile();
        } catch (Throwable ignored) {
        }
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
