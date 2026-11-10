package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型小助手：只干一件事——拿不准的条目问一句「这算不算一部短剧」。
 * 设计上它是可有可无的：关掉、超时、报错、返回乱码，一律当成「没问」，调用方退回本地规则。
 */
public class AiJudge {

    private static final String SYSTEM = "你是影视资源鉴定助手。判断条目是否为「一整部短剧被合成为一个视频」的合集版本，"
            + "例如 70 集每集 1 分钟的短剧被压成一个 70 分钟的单集。只输出 JSON，不要任何解释："
            + "{\"ok\":true或false,\"confidence\":0到100}";

    /** 主题不明确的剧，让模型给几个同类题材搜索词；AI 漫剧/动态漫这类也要会推 */
    private static final String SYSTEM_SUGGEST = "你是短剧推荐助手。根据剧名和类型，给出 3 到 5 个适合用来搜索同类短剧的中文题材关键词"
            + "（每个 2~8 个字，例如：重生、穿越、古装、甜宠、复仇、漫剧、动态漫；AI 漫剧、动态漫、沙雕漫这类题材也要覆盖）。"
            + "只输出 JSON，不要任何解释：{\"tags\":[\"词1\",\"词2\",\"词3\"]}";

    /**
     * 补档案：不少站点（夸克网盘这类站源尤其常见）演员、导演、简介全是空的，详情页看着就剩个片名。
     * 这时候请大模型按剧名回忆一下主演 / 出品方 / 剧情简介，想得出来就填进去
     * （照样能关注、能长按看他的作品；简介填到详情页那块）。
     *
     * 马先生 2026-11-03：一次问全（演员 + 出品方 + 简介），别分三趟烧钱；
     * 问不到就照旧空着，绝不拿不知道的东西糊上去。
     *
     * 2026-10-12 马先生点名要的「按关注的演员筛选检索」：提问时把本机关注的演员名单一起给模型，
     * 让它先判断名单里哪些人参演了这部剧（有的排在最前），再补上它记得的其他主演。
     * 轻度使用：只在演员确实为空时才问，同名只问一次，问不到就算了，绝不拦着你看剧。
     *
     * 2026-10-24 马先生定的分层：站源（网盘那类）不给演员信息时才来问；先让模型报这部是什么形态——
     * 漫剧/动态漫/动画没有真人演员，演员区整块不显示；真人剧才把演员列出来。
     * 形态由模型报、由本地再卡一道，比只指望它自觉返回空数组稳。
     */
    private static final String SYSTEM_ACTORS = "你是影视资料助手。先判断这部作品是什么形态，再回忆它的主要演员和剧情简介。"
            + "只输出 JSON，不要任何解释：{\"form\":\"漫剧|动态漫|动画|真人剧|电影|未知\",\"actors\":[\"名字1\",\"名字2\"],\"studio\":\"出品方\",\"plot\":\"剧情简介\"}。"
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
            + "plot：一段 60 到 200 字的中文剧情简介，讲清楚它讲了个什么故事（不要写「暂无简介」这类废话，"
            + "不要写演员表、导演、评分、播出信息，也不要带剧名前缀）。"
            + "认不出这部作品就给空串——宁可空着，也绝不编一段不存在的剧情。"
            + "真不知道就返回 {\"form\":\"未知\",\"actors\":[],\"studio\":\"\",\"plot\":\"\"}，不要编造。";

    /**
     * 刷剧「下一部」：马先生 2026-11-10 定的问法——**先问有没有新一季/续作，再要同类推荐**，
     * 一句话问齐（他给豆包的原话就是「《XX》有没有新一季或和这个剧题材类型相近的剧，推荐几部」）。
     * 以前这问法明确禁止模型给续作、只要同类，结果《小富即安，不，本公子意在天下》明明有第二部，
     * 模型只报同类，刷剧接出去的就是八竿子打不着的剧。
     *
     * 铁律不变：关掉、断网、超时、返回乱码一律当「没问」，刷剧照旧走本地词表，绝不卡住。
     */
    /**
     * 把「先问清这部剧是什么」和「有没有后续 / 有没有同类型」放在**一次调用**里分两步做完：
     * 先让模型把形态（AI漫剧/真人网剧/…）、题材、主题、主演、地区写成 profile，
     * 再严格照 profile 回答两问（有没有新一季/新一部；有没有同类、按排名）。
     * 马先生 2026-11-10：以前直接问「接下来看什么」，模型凭一个剧名瞎猜题材，
     * 《小富即安，不，本公子意在天下》接出去的竟是《僵尸校园》；题材先定准，模型给的才精确。
     * profile 会落进本机缓存，之后换季、按口味、单部作品那些问法把它当「已核实档案」带上，
     * 不再让模型重新猜一遍（也省一轮 token）。没配大模型时这一整套都不跑，纯本地剧名关键词。
     */
    private static final String SYSTEM_NEXT = "你是影视推荐助手。用户刚看完一部剧/电影，要一部接一部往下刷。"
            + "**第一步：先认剧**——把这部作品是什么写进 profile：形态（AI漫剧、动态漫、沙雕漫、真人短剧、网剧、电视剧、电影、动画，只选一个）、"
            + "题材（古装、穿越、权谋、宫斗、宅斗、玄幻、重生、系统、逆袭、都市、甜宠、复仇、悬疑…挑 2~4 个最贴切的）、"
            + "主题（一句话，20 字以内）、主演（真人作品给 1~4 个人名；AI漫剧/动态漫这类没有真人演员给空数组）、地区（按内容产地，别按剧名语言）。"
            + "题面里若给了【已核实的本剧档案】，一律以它为准、profile 照抄，不许自己另猜。这一步先想清楚，后面所有推荐都必须严格照它来："
            + "档案写 AI漫剧就只找 AI漫剧，写真人网剧就只找真人网剧，写古装穿越权谋就只找这一类，"
            + "不许跨形态也不许跨题材去推不相干的作品（这是硬要求）。"
            + "**第二步：回答两问**——"
            + "① 这部有没有新一季/第二季/第二部/续作/后续？有就把续作在影视站里可能出现的名称写法列出来"
            + "（中文季号「剧名 第二季」、阿拉伯季号「剧名 第2季」、连写「剧名2」、续作另起的名字各给一种），"
            + "放在 picks 最前面，has_next 给 true；确定没有给 false，拿不准给 null。"
            + "② 有没有和它同形态、同题材、同主题气质的作品？要真实存在、站里能搜到的剧名（不是题材词），"
            + "按推荐顺序排在续作后面，**picks 的顺序就是推荐顺序，用户会照着排第一的一路往下看**；"
            + "含续作一共最多 6 项，判不准的宁可少给，绝不编造不存在的剧。"
            + "不要给与它形态不符的作品、综艺、解说、混剪、盘点、番外，不要给当前这部、它的前作，也不要给「已看过」名单里的。"
            + "另外给 2~3 个这类剧的题材词（每个 2~8 字，同一形态，用来在站里搜同类）。"
            + "题面若给了用户常看的题材、几乎不碰的题材、关注过的演员：只用来在同类里排先后、避开不碰的方向，"
            + "**不许因此改掉同形态同题材这条硬要求**，也不许把「已看过」名单里的推出来。"
            + "reason 写一句判断依据（照档案：什么形态题材、有没有续作、据此该接哪几部），40字以内。"
            + "picks 逐项给剧名，每项 title 填剧名、note 填一句「为什么接这部」（20字以内），片名和理由放在同一个对象里，别串位。"
            + "titles/notes 是老版本兼容字段，填了 picks 就留空数组。"
            + "只输出 JSON，不要任何解释：{\"profile\":{\"form\":\"\",\"genres\":[],\"theme\":\"\",\"actors\":[],\"region\":\"\"},"
            + "\"reason\":\"判断依据\",\"picks\":[{\"title\":\"剧名\",\"note\":\"为什么接这部\"}],"
            + "\"titles\":[],\"notes\":[],\"tags\":[\"题材词\"],\"has_next\":null}";

    /**
     * 系列剧「下一季」：站源写法五花八门（第3季 / 第三季 / 剧名3 / S3 / 之某某），
     * 本地正则抠不全会漏，模型知道这个系列的正名和季号排列，让它把可能的写法一次性给齐。
     */
    private static final String SYSTEM_NEXT_SEASON = "你是影视资料助手。用户刚看完一个系列的某一季，想知道后面还有没有，要接着看。"
            + "题面里若给了【已核实的本剧档案】（形态/题材/主题/主演/地区），一律以它为准，别把这部剧认成别的题材或形态。"
            + "先判断这个系列**还有没有下一季**：有就 has_next 给 true，已经完结/确定没有了就给 false，拿不准给 null。"
            + "有可能就把这个系列**下一季**在影视站里可能出现的名称写法都列出来，每种都要："
            + "中文季号（第X季）、阿拉伯季号（第X季）、连写（剧名X）、英文（Season X / SX）、以及续作另起的名字（如「XX之YYY」）。"
            + "只要下一季，最多 4 个；确定没有下一季就返回空数组。"
            + "先用 reason 写一句判断依据：这个系列官方一共到第几季、有没有完结、刚看完的是第几季、下一季该是第几季——先把季号想清楚再给写法，别凭印象乱报；"
            + "季号写法必须照当前剧名原样接着写：当前是阿拉伯数字「第10季」就给「第11季」，当前是中文「第十季」就给「第十一季」，别混着来；给的名字要能直接拿去站内搜到条目并取到播放地址；"
            + "再用 picks 逐项给写法，每项 title 填一种名称写法、note 说明这是哪种写法（中文季号/阿拉伯季号/连写/英文/另起的名字），别串位。"
            + "titles 和 notes 是给老版本用的兼容字段，填了 picks 就都留空数组。"
            + "只输出 JSON，不要任何解释：{\"reason\":\"判断依据\",\"picks\":[{\"title\":\"名称写法\",\"note\":\"哪种写法\"}],"
            + "\"titles\":[],\"notes\":[],\"tags\":[],\"has_next\":true}";

    /**
     * 「这个系列/这部已经没有下一季了，那接着看什么」：拿口味去问（马先生 2026-11-01）。
     * 跟上面几问不一样的是这一问**带着用户自己的数据**：常看的题材、几乎不碰的题材、
     * 关注过的演员、最近看过和收藏过的剧名。模型照这些挑，比只给个剧名瞎猜准得多。
     * 口味里「几乎不碰」那一项是从收藏/播放记录里一次都没出现过的类别反推出来的
     * （收藏里没有爱情剧，就别往人家眼前推爱情剧），模型必须尊重。
     */
    private static final String SYSTEM_NEXT_TASTE = "你是影视推荐助手。用户刚看完一部作品，这个系列（或这部）已经没有下一季/下一部了，他要一部接一部接着看。"
            + "题面里若给了【已核实的本剧档案】（形态/题材/主题/主演/地区），一律以它为准：档案写 AI 漫剧就只推 AI 漫剧，"
            + "写真人网剧就只推真人网剧，写古装穿越权谋就只推这一类，不许跨形态跨题材。"
            + "请按下面给的**用户口味**推荐真实存在、在影视站里能搜到的**剧名**（要剧名，不是题材词）："
            + "1. 优先跟它是同一种形态、同一个地区（按给出的「剧种」「地区」；漫剧就给漫剧，短剧就给真人短剧）；"
            + "2. 优先包含指定主演、或用户关注过的演员的作品 —— 有的人是冲着某个演员看的；"
            + "3. 贴合「常看的题材」，**避开「几乎不碰」的题材**，那条是硬约束，碰了等于白推荐；"
            + "4. 不要给电影、综艺、解说、混剪、盘点、番外，也不要给当前这部、它的前作续作和「已看过」名单里的；"
            + "5. 最像的排在最前，最多 6 个。宁可少给，绝不编造不存在的剧。"
            + "另外给 2~3 个这类剧的题材词（每个 2~8 字，用来在站里搜同类，也要是同一形态的）。"
            + "先用 reason 写一句判断依据（照上面的口味，为什么挑这几部），40字以内；"
            + "再用 picks 逐项给剧名，每项 title 填剧名、note 填一句「为什么符合这个口味」（20字以内），片名和理由放在同一个对象里，别串位。"
            + "titles 和 notes 是给老版本用的兼容字段，填了 picks 就都留空数组。"
            + "只输出 JSON，不要任何解释：{\"reason\":\"判断依据\",\"picks\":[{\"title\":\"剧名\",\"note\":\"为什么符合口味\"}],"
            + "\"titles\":[],\"notes\":[],\"tags\":[\"题材词\"],\"has_next\":false}";

    private static final ConcurrentHashMap<String, Boolean> MEMO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ASKING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<String>> SUGGEST = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> SUGGESTING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Cast> ACTORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ACTOR_ASKING = new ConcurrentHashMap<>();
    /** 同一部剧第二次来问的回调：结果回来一起分发（补简介和补演员常常同时发起） */
    private static final ConcurrentHashMap<String, List<ActorsCallback>> ACTOR_WAIT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Next> NEXT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> NEXT_ASKING = new ConcurrentHashMap<>();
    /** 第一问「这部剧是什么」的画像缓存：同一部剧只问一次，第二问直接拿它当已知条件（省 token、也定得准） */
    private static final ConcurrentHashMap<String, Profile> PROFILE = new ConcurrentHashMap<>();
    private static final int MEMO_LIMIT = 800;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private static volatile boolean loaded;
    private static volatile boolean suggestLoaded;
    private static volatile boolean actorLoaded;
    private static volatile boolean nextLoaded;
    private static volatile boolean profileLoaded;

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
        /** 与 titles 一一对应的「为什么接这部」，片名和理由绑在一起，绝不串位（参照智能搜索） */
        public final List<String> notes;
        /** 模型先给的一句判断依据：是什么形态/系列、有没有下一季、据此该接什么（写进日志，便于排查接得准不准） */
        public final String reason;
        /** 模型说的「这个系列还有没有下一季」：TRUE 有、FALSE 已经完结、null 没说清。普通（非系列）推荐永远是 null */
        public final Boolean hasNext;

        Next(List<String> titles, List<String> tags) {
            this(titles, tags, null);
        }

        Next(List<String> titles, List<String> tags, Boolean hasNext) {
            this(titles, tags, hasNext, null, "");
        }

        Next(List<String> titles, List<String> tags, Boolean hasNext, List<String> notes, String reason) {
            this.titles = titles == null ? new ArrayList<>() : titles;
            this.tags = tags == null ? new ArrayList<>() : tags;
            this.hasNext = hasNext;
            this.notes = notes == null ? new ArrayList<>() : new ArrayList<>(notes);
            while (this.notes.size() < this.titles.size()) this.notes.add(""); // 老缓存/老格式没有理由：补齐空串，别让下标对不上
            this.reason = reason == null ? "" : reason;
        }

        boolean isEmpty() {
            return titles.isEmpty() && tags.isEmpty();
        }

        /** 名字一个都没给、也不肯说有没有下一季 —— 这条推荐等于没问 */
        public boolean useless() {
            return isEmpty() && hasNext == null;
        }
    }

    /**
     * 第一问「这部剧到底是什么」的画像：形态、题材、主题、主演、地区。
     * 用户 2026-11-10 的意思：先把题材问清楚、把形态定准（AI 漫剧就当 AI 漫剧、真人网剧就当真人网剧），
     * 第二问再拿这份档案当**已知条件**去问「有没有后续 / 有没有同类型」，模型给的才精确。
     * 全部字段都过了脱敏闸才存；空了就当没问到，第二问照旧发，绝不拦着刷剧。
     */
    public static final class Profile {
        public final String form;
        public final List<String> genres;
        public final String theme;
        public final List<String> actors;
        public final String region;

        Profile(String form, List<String> genres, String theme, List<String> actors, String region) {
            this.form = form == null ? "" : form;
            this.genres = genres == null ? new ArrayList<>() : genres;
            this.theme = theme == null ? "" : theme;
            this.actors = actors == null ? new ArrayList<>() : actors;
            this.region = region == null ? "" : region;
        }

        public boolean empty() {
            return form.isEmpty() && genres.isEmpty() && theme.isEmpty() && actors.isEmpty() && region.isEmpty();
        }

        /** 拼成第二问里的一段「已核实档案」，每行一项，别让模型看串了 */
        String brief() {
            StringBuilder sb = new StringBuilder("【已核实的本剧档案】（请严格照此回答，不要另猜）");
            if (!form.isEmpty()) sb.append("\n形态：").append(form);
            if (!genres.isEmpty()) sb.append("\n题材：").append(TextUtils.join("、", genres));
            if (!theme.isEmpty()) sb.append("\n主题：").append(theme);
            if (!actors.isEmpty()) sb.append("\n主演：").append(TextUtils.join("、", actors));
            if (!region.isEmpty()) sb.append("\n地区：").append(region);
            return sb.toString();
        }
    }

    /** 已经问过并有结论的，直接给；没问过返回 null */
    public static Boolean peek(String cacheKey) {
        if (!AiSetting.ready()) return null; // 铁律：开关没开，连上次问出来的缓存也不许用
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
        if (!AiSetting.ready()) {
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
                DebugLog.d("AiJudge", "模型不可用，退回本地规则：" + name + "（" + reasonOf(e) + "）");
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
        if (!AiSetting.ready()) return null; // 铁律：开关没开，缓存里的模型答案一律不翻
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
        if (!AiSetting.ready()) {
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
                DebugLog.d("AiJudge", "推荐词模型不可用，退回本地规则：" + name + "（" + reasonOf(e) + "）");
            }
            SUGGESTING.remove(name);
            if (tags != null) {
                if (SUGGEST.size() > MEMO_LIMIT) SUGGEST.clear();
                SUGGEST.put(name, tags);
                BingeWords.learn("题材词", tags); // 模型给的同类搜索词也进共享词库
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
        if (!AiSetting.ready()) return null; // 铁律：开关没开，缓存里的演员名单也不许翻出来
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
        /** 剧情简介（网盘站源连一段简介都不给，补出来填在详情页那块） */
        public final String plot;

        Cast(List<String> names, String studio) {
            this(names, studio, "");
        }

        Cast(List<String> names, String studio, String plot) {
            this.names = names == null ? new ArrayList<>() : names;
            this.studio = studio == null ? "" : studio;
            this.plot = plot == null ? "" : plot;
        }

        boolean empty() {
            return names.isEmpty() && studio.isEmpty() && plot.isEmpty();
        }
    }

    /**
     * 站点没给演员 / 没给简介（或给的演员串拆不开）时，请大模型按剧名+年份回忆主演、出品方和简介。
     * 结果记在本机，同剧不再重复问；问不到（断网/超时/模型说不认识）返回 null，
     * 调用方就当没问出来，该空着的还是空着。
     * 漫剧、动画不问演员，但仍要问出品方（actor 那一段就交白卷，studio 才是要的）；
     * 简介不分形态，问得到就填。
     *
     * 一部剧可能同时被两处问（详情页补简介、演员区补人）——同一个 key 只发一次请求，
     * 后到的挂在 {@link #ACTOR_WAIT} 上，结果回来一起分发，不重复烧钱也不漏给谁。
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
        if (!AiSetting.ready()) {
            replyActors(callback, null);
            return;
        }
        if (ACTOR_ASKING.putIfAbsent(key, Boolean.TRUE) != null) {
            // 已经在问了：挂个号，回来一起给（以前是直接给 null，第二个来问的就白等了）
            ACTOR_WAIT.computeIfAbsent(key, k -> new ArrayList<>()).add(callback);
            return;
        }
        POOL.execute(() -> {
            Cast cast = null;
            try {
                cast = requestActors(key, cleanTitle(safe[0]), safe[1], safe[2], year);
            } catch (Throwable e) {
                cast = null;
                DebugLog.d("AiJudge", "补演员模型不可用：" + key + "（" + reasonOf(e) + "）");
            }
            ACTOR_ASKING.remove(key);
            if (cast != null) {
                if (ACTORS.size() > MEMO_LIMIT) ACTORS.clear();
                ACTORS.put(key, cast); // 空的也记：这部剧模型不认识，下次别再问
                saveActors();
            }
            Cast result = cast == null || cast.empty() ? null : cast;
            App.post(() -> {
                replyActors(callback, result);
                List<ActorsCallback> waiters = ACTOR_WAIT.remove(key); // 同剧第二次来问的，一并给
                if (waiters != null) for (ActorsCallback waiter : waiters) replyActors(waiter, result);
            });
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
        if (!AiSetting.ready()) return null; // 铁律：开关没开，缓存里的推荐也不许用
        if (AiGuard.blocked(name)) return null;
        loadNext();
        return NEXT.get(nextKey(name, kindWord, region, MODE_GENERIC));
    }

    /**
     * 缓存键：剧名 + 剧种（形态）+ 产地。
     * 2026-10-24 马先生反馈「看国产 AI 漫剧，下一步推出韩剧」——以前只按剧名记，
     * 同一个剧名换形态/产地后还拿旧答案，跨类别就是这么来的。现在形态一变旧答案自动作废。
     */
    private static String nextKey(String name, String kindWord, String region, int mode) {
        String head = mode == MODE_MOVIE ? "movie·" : mode == MODE_SERIAL ? "serial·" : mode == MODE_TASTE ? "taste·" : "";
        // 版本必须和 loadNext() 认的前缀一致：2026-11-10 提示词升级成「先画像、再按档案问后续/同类」，
        // 旧答案不带档案不复用。上一版曾把这里升到 v3 却忘了改读缓存的 v2，答案存了读不回来、每部剧重复问模型。
        return "v4·" + head + name + "·" + (TextUtils.isEmpty(kindWord) ? "" : kindWord) + "·" + (TextUtils.isEmpty(region) ? "" : region);
    }

    /** 画像缓存键：剧名 + 剧种 + 地区（形态/产地一变，旧画像作废） */
    private static String profileKey(String name, String kindWord, String region) {
        return "v1·" + name + "·" + (TextUtils.isEmpty(kindWord) ? "" : kindWord) + "·" + (TextUtils.isEmpty(region) ? "" : region);
    }

    /**
     * 上一次问「下一部」时模型写出的本剧档案，直接拿来当这一问的已知条件。
     * 只读缓存、绝不为此单独发一次请求（铁律：别为画像多烧一轮 token）。
     * 开关没开一律返回 null：没配大模型时连缓存里的模型结论都不许用，纯走本地剧名关键词。
     */
    private static Profile peekProfile(String name, String kindWord, String region) {
        if (!AiSetting.ready() || name == null || name.isEmpty()) return null;
        if (AiGuard.blocked(name)) return null;
        loadProfile();
        Profile hit = PROFILE.get(profileKey(name, kindWord, region));
        return hit == null || hit.empty() ? null : hit;
    }

    /**
     * 从「下一部」那次回答里把 profile 字段收下来、落进本机缓存。
     * 下一次换季/按口味/单部作品再问时，这份「已核实的本剧档案」就会作为已知条件带上，
     * 模型不用重新猜这部剧是什么形态、什么题材（马先生 2026-11-10：题材先弄清楚，给的才精确）。
     * 逐字段过脱敏闸、限长、限个数；形态必须是那几个定值之一，乱写的一律不认，免得把后面带偏。
     */
    private static void rememberProfile(JSONObject json, String name, String kindWord, String region) {
        try {
            JSONObject obj = json == null ? null : json.optJSONObject("profile");
            if (obj == null) return;
            String form = cleanText(obj.optString("form", ""), 12).trim();
            if (form.isEmpty() || !"AI漫剧,动态漫,沙雕漫,真人短剧,网剧,电视剧,电影,动画,未知".contains(form)) form = "";
            List<String> genres = new ArrayList<>();
            JSONArray genreArray = obj.optJSONArray("genres");
            if (genreArray != null) for (int i = 0; i < genreArray.length() && genres.size() < 4; i++) {
                String tag = cleanText(genreArray.optString(i, ""), 8).trim();
                if (tag.length() < 2 || tag.isEmpty() || AiGuard.blocked(tag) || genres.contains(tag)) continue;
                genres.add(tag);
            }
            String theme = cleanText(obj.optString("theme", ""), 40).trim();
            List<String> actors = new ArrayList<>();
            JSONArray actorArray = obj.optJSONArray("actors");
            if (actorArray != null) for (int i = 0; i < actorArray.length() && actors.size() < 4; i++) {
                String star = cleanText(actorArray.optString(i, ""), 12).trim();
                if (star.length() < 2 || star.isEmpty() || AiGuard.blocked(star) || actors.contains(star)) continue;
                actors.add(star);
            }
            String area = cleanText(obj.optString("region", ""), 12).trim();
            Profile profile = new Profile(form, genres, theme, actors, area);
            if (profile.empty()) return; // 模型没给出可用档案：这次就不记，下次让它重新认
            // 档案里的题材/形态也是提示词：一并攒进共享文档（马先生 2026-11-11）
            BingeWords.learn("题材", genres);
            BingeWords.learn("形态", form);
            String key = profileKey(name, kindWord, region);
            loadProfile();
            if (PROFILE.size() > MEMO_LIMIT) PROFILE.clear();
            PROFILE.put(key, profile);
            saveProfile();
            DebugLog.d("AiJudge", "本剧档案 " + profile.brief().replace('\n', ' '));
        } catch (Throwable ignored) {
        }
    }

    private static void loadProfile() {
        if (profileLoaded) return;
        synchronized (AiJudge.class) {
            if (profileLoaded) return;
            try {
                File file = profileFile();
                if (file.exists()) {
                    byte[] data = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read = in.read(data);
                        JSONObject json = new JSONObject(new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8));
                        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                            String key = it.next();
                            if (!key.startsWith("v1·")) continue;
                            JSONObject item = json.optJSONObject(key);
                            if (item == null) continue;
                            List<String> genres = new ArrayList<>();
                            JSONArray g = item.optJSONArray("genres");
                            if (g != null) for (int i = 0; i < g.length(); i++) {
                                String t = g.optString(i, "");
                                if (!t.isEmpty() && !AiGuard.blocked(t)) genres.add(t);
                            }
                            List<String> actors = new ArrayList<>();
                            JSONArray a = item.optJSONArray("actors");
                            if (a != null) for (int i = 0; i < a.length(); i++) {
                                String t = a.optString(i, "");
                                if (!t.isEmpty() && !AiGuard.blocked(t)) actors.add(t);
                            }
                            PROFILE.put(key, new Profile(
                                    cleanText(item.optString("form", ""), 12), genres,
                                    cleanText(item.optString("theme", ""), 40), actors,
                                    cleanText(item.optString("region", ""), 12)));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            profileLoaded = true;
        }
    }

    private static synchronized void saveProfile() {
        try {
            JSONObject json = new JSONObject();
            for (java.util.Map.Entry<String, Profile> entry : PROFILE.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("form", entry.getValue().form);
                item.put("genres", new JSONArray(entry.getValue().genres));
                item.put("theme", entry.getValue().theme);
                item.put("actors", new JSONArray(entry.getValue().actors));
                item.put("region", entry.getValue().region);
                json.put(entry.getKey(), item);
            }
            try (FileOutputStream out = new FileOutputStream(profileFile())) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File profileFile() {
        return new File(App.get().getFilesDir(), "ai_profile.json");
    }

    /**
     * 单部作品（电影/一部到头的视频）：先看它有没有续作（《杀死比尔》→《杀死比尔2》），
     * 没有就把 has_next 给 false 并推荐几部同类型的——这是它的最后一步，别再返回一堆不相关的剧。
     * 跟上面两个分开：给短剧的那一版明写着「不要给电影」，给系列的那一版只问季号，口径都不一样。
     */
    private static final String SYSTEM_NEXT_MOVIE = "你是影视资料助手。用户刚看完一部电影/单部作品，要接着往下看。"
            + "题面里若给了【已核实的本剧档案】（形态/题材/主题/主演/地区），一律以它为准，同类型就照档案里的题材和形态找，别跨题材。"
            + "第一步先判断这部作品有没有**续作/下一部**（同系列的后一部，可能是「第X部」「X」「II」或另起的名字）："
            + "有就 has_next 给 true，并把续作在影视站里可能出现的名称写法都列出来（中文第X部、阿拉伯第X部、连写、英文名、另起的名字都给）；"
            + "确定没有了（或已经是最后一部）就 has_next 给 false。"
            + "第二步：不管有没有续作，再推荐 2~3 部**同类型**的真实作品（题材、地区要跟它接近，站里能搜到的），"
            + "给全称，不要给解说、混剪、盘点、番外、综艺，也不要给已经看过的。"
            + "先用 reason 写一句判断依据：这部是什么、是不是系列、有没有续作、凭什么这么判断，40字以内；"
            + "再用 picks 逐项给名称，每项 title 填续作写法或同类作品全称、note 说明「为什么是它」（20字以内），别串位。"
            + "titles 和 notes 是给老版本用的兼容字段，填了 picks 就都留空数组。"
            + "只输出 JSON，不要任何解释：{\"reason\":\"判断依据\",\"picks\":[{\"title\":\"名称\",\"note\":\"为什么是它\"}],"
            + "\"titles\":[],\"notes\":[],\"tags\":[\"题材词\"],\"has_next\":true}";

    /** 问「下一部」的四种口径：同类剧 / 系列下一季 / 单部作品（续作优先，没有再给同类）/ 没下一季了按口味推荐 */
    private static final int MODE_GENERIC = 0;
    private static final int MODE_SERIAL = 1;
    private static final int MODE_MOVIE = 2;
    private static final int MODE_TASTE = 3;

    /**
     * 问一句「下一部该看什么」：让模型报出同类剧名（系列剧则报下一季的各种写法）。
     * 同名只问一次，结果记在本机；任何异常都走 onResult(null)，刷剧退回本地题材词，不受影响。
     *
     * ⚠️ 这是**纯建议**：模型报的剧名站里未必有，搜不到就当没这回事，下一步照旧换词搜。
     */
    public static void nextUp(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex, NextCallback callback) {
        nextUp(name, typeName, region, kindWord, actor, seriesMain, seriesIndex, callback, null, null, null);
    }

    /**
     * 带观影口味的「下一部」：口味（常看题材 / 几乎不碰的题材 / 关注过的演员）从播放历史和收藏里攒，
     * 只用来给同类推荐排排序，**不许因此改掉「同形态同题材」这条硬要求**（马先生 2026-11-10）。
     * 口味不进缓存键：答案里有哪些剧不因口味而变，别为口味变一次就重问一遍模型。
     */
    public static void nextUp(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex,
                              NextCallback callback, List<String> liked, List<String> avoided, List<String> stars) {
        int mode = seriesIndex > 0 && seriesMain != null && !seriesMain.isEmpty() ? MODE_SERIAL : MODE_GENERIC;
        askNext(name, typeName, region, kindWord, actor, seriesMain, seriesIndex, mode, callback, liked, avoided, stars);
    }

    /**
     * 单部作品（电影）的「下一部」：答案里既有续作的名字写法，也有同类型推荐（马先生 2026-10-31：
     * 看着《杀死比尔》就接《杀死比尔2》，没有第三部了再推《极速追杀》这类同类型的）。
     * 问法跟刷剧不一样，缓存也按问法分开记，两路的答案不会互相顶掉。
     */
    public static void nextMovie(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex, NextCallback callback) {
        askNext(name, typeName, region, kindWord, actor, seriesMain, seriesIndex, MODE_MOVIE, callback);
    }

    /**
     * 没下一季/下一部时的「接着看什么」：带口味去问（马先生 2026-11-01 —— 《镖人》只到第二季，
     * 那就问模型：参照它（动漫/AI漫剧/短剧），优先同类型的、或者含某个主演/他关注演员的作品）。
     * 口味由调用方从播放记录、收藏、关注的演员里攒（BingeTaste），这里只负责组织成话。
     */
    public static void recommend(String name, String typeName, String region, String kindWord, String actor,
                                 List<String> liked, List<String> avoided, List<String> stars, NextCallback callback) {
        askNext(name, typeName, region, kindWord, actor, "", 0, MODE_TASTE, callback, liked, avoided, stars);
    }

    private static void askNext(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex, int mode, NextCallback callback) {
        askNext(name, typeName, region, kindWord, actor, seriesMain, seriesIndex, mode, callback, null, null, null);
    }

    private static void askNext(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex, int mode, NextCallback callback, List<String> liked, List<String> avoided, List<String> stars) {
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
        String key = nextKey(name, kindWord, region, mode); // 形态/产地/问法变了旧答案作废
        loadNext();
        Next hit = NEXT.get(key);
        if (hit != null) {
            replyNext(callback, hit.useless() ? null : hit); // 空＝问过没结果，别再问第二次（明确答复「没有下一季」的不算空）
            return;
        }
        if (!AiSetting.ready()) {
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
                // 上一轮模型写出的本剧档案，这一轮直接当已知条件带上；
                // 没有就让它在本轮回答里先认剧（profile 字段），答完存下来给下一轮用。
                // 全程只有一次模型调用，不为画像多烧一轮，也绝不拖住刷剧。
                Profile profile = peekProfile(safe[0], kindWord, region);
                next = requestNext(safe[0], safe[1], region, kindWord, safe[2], seriesMain, seriesIndex, mode, liked, avoided, stars, profile);
            } catch (Throwable e) {
                // 为什么不可用要留痕：以前只记一句「不可用」，模型换了地址、令牌过期、设备断网看起来一模一样，事后无从分辨
                next = null;
                DebugLog.d("AiJudge", "下一部推荐模型不可用，退回本地词表：" + name + "（" + reasonOf(e) + "）");
            }
            NEXT_ASKING.remove(key);
            if (next != null) { // 空的也记：这部剧模型不认识/没有下一季，下次别再问
                if (NEXT.size() > MEMO_LIMIT) NEXT.clear();
                NEXT.put(key, next);
                saveNext();
            }
            Next result = next == null || next.useless() ? null : next;
            App.post(() -> replyNext(callback, result));
        });
    }

    /** 一句能认出病因的话：类名 + 消息，都取不到就退类名，绝不返回空（日志里要能一眼看出是超时还是令牌过期） */
    private static String reasonOf(Throwable e) {
        if (e == null) return "未知";
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return name;
        // 有些异常把整段响应塞进 message（超长、还带换行），日志里留个头就够了
        if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
        return name + ": " + msg.replaceAll("\\s+", " ");
    }

    private static void replyNext(NextCallback callback, Next next) {
        try {
            if (callback != null) callback.onResult(next);
        } catch (Throwable ignored) {
        }
    }

    private static Next requestNext(String name, String typeName, String region, String kindWord, String actor, String seriesMain, int seriesIndex, int mode, List<String> liked, List<String> avoided, List<String> stars, Profile profile) throws Exception {
        boolean serial = mode == MODE_SERIAL;
        boolean movie = mode == MODE_MOVIE;
        boolean taste = mode == MODE_TASTE;
        StringBuilder sb = new StringBuilder();
        sb.append(movie ? "作品名：" : "剧名：").append(name);
        sb.append("\n类型：").append(TextUtils.isEmpty(typeName) ? "未知" : typeName);
        sb.append("\n地区：").append(TextUtils.isEmpty(region) ? "未知" : region);
        sb.append(movie ? "\n形态：单部作品（电影/一部到头的那种）" : "\n剧种（形态）：" + (TextUtils.isEmpty(kindWord) ? "未知" : kindWord));
        if (!TextUtils.isEmpty(kindWord) && !movie) sb.append("\n只给同一种形态、同一个地区的，跨形态跨地区的不要。");
        if (!TextUtils.isEmpty(actor)) sb.append("\n主演：").append(actor);
        if (serial) sb.append("\n这是系列剧，系列名：").append(seriesMain).append("，刚看完第").append(seriesIndex).append("季");
        if (movie && seriesIndex > 0) sb.append("\n这是系列里的第").append(seriesIndex).append("部，系列名：").append(seriesMain);
        String watched = watched(name);
        if (!watched.isEmpty()) sb.append("\n已看过（不要推荐）：").append(watched);
        // 观影口味（播放历史 + 收藏 + 关注演员攒出来的）：主问和兜底问都带上，
        // 只用于同类推荐排序，不许因此跨形态跨题材；每一项也过一遍脱敏闸
        if (liked != null && !liked.isEmpty()) {
            List<String> clean = new ArrayList<>();
            for (String tag : liked) if (tag != null && !tag.isEmpty() && !AiGuard.blocked(tag)) clean.add(tag);
            if (!clean.isEmpty()) sb.append("\n用户常看的题材（同类里优先这个方向）：").append(TextUtils.join("、", clean));
        }
        if (avoided != null && !avoided.isEmpty()) {
            List<String> clean = new ArrayList<>();
            for (String tag : avoided) if (tag != null && !tag.isEmpty() && !AiGuard.blocked(tag)) clean.add(tag);
            if (!clean.isEmpty()) sb.append("\n用户几乎不碰的题材（同类里尽量避开，但仍不许跨形态跨题材）：").append(TextUtils.join("、", clean));
        }
        if (stars != null && !stars.isEmpty()) {
            List<String> clean = new ArrayList<>();
            for (String star : stars) if (star != null && !star.isEmpty() && !AiGuard.blocked(star)) clean.add(star);
            if (!clean.isEmpty()) sb.append("\n用户关注过的演员（同类里优先考虑他们的作品）：").append(TextUtils.join("、", clean));
        }
        // 上一次模型写出的「已核实档案」放在所有线索之后、问题之前：这是定过性的，优先级高于站源标注和模型自己的印象
        if (profile != null && !profile.empty()) sb.append("\n").append(profile.brief());
        sb.append(serial
                ? "\n问：这个系列最新到第几季？第" + seriesIndex + "季之后还有没有下一季（有没有完结）？有就把下一季的名称写法都列出来，已经完结就直说没有。站源可能收录了你不知道的版本，没把握的 has_next 给 null 别把话说死，我们本地还会再搜。"
                : movie
                ? "\n问：这部作品有没有下一部/续作？有就把续作可能的名称写法都列出来，顺便推荐几部同类型的；确定没有续作就 has_next 给 false，没把握给 null。"
                : taste
                ? "\n问：它已经没有下一季/下一部了。参照上面的口味" + (profile == null || profile.empty() ? "" : "和本剧档案") + "，接下来该看哪几部？优先同形态同地区、优先含指定主演或关注演员的作品，避开不碰的题材，给出剧名。"
                : "\n问：" + (profile == null || profile.empty()
                    ? "先确认它是什么剧，把形态、题材、主题、主演、地区写进 profile，"
                    : "照上面的【已核实的本剧档案】确认它是什么剧，")
                    + "再回答两件事：①这部有没有新一季/第二季/第二部/后续？有就先给续作的名称写法；②有没有和它同类型的剧？同类型严格按档案里的形态、题材、主题，按推荐顺序排好，排第一的是最该接着看的。");

        // 模型链统一从 AiChat 走：豆包优先（限额省钱），千问兜底（按量计费总有答）
        String content = AiChat.chat(serial ? SYSTEM_NEXT_SEASON : movie ? SYSTEM_NEXT_MOVIE : taste ? SYSTEM_NEXT_TASTE : SYSTEM_NEXT, sb.toString());
        // requestNext 的 name 已是 AiGuard.pass 脱敏后的，档案缓存与 peekProfile 用同一个键
        return parseNext(content, name, kindWord, region);
    }

    /**
     * 模型报的剧名逐个过闸：太短太长都不像剧名、跟当前这部一样的不收、脏词直接扔。
     * 一个都不剩就当没问（返回空 Next，调用方退回本地词表）。
     */
    private static Next parseNext(String content, String current, String kindWord, String region) {
        if (content == null) return null;
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            rememberProfile(json, current, kindWord, region); // 把「这部剧是什么」的定性收进缓存，下一问直接带上
            List<String> titles = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            // 结构化 picks 优先：片名和「为什么接这部」绑在同一个对象里，绝不串位（参照智能搜索）
            JSONArray picks = json.optJSONArray("picks");
            if (picks != null) {
                for (int i = 0; i < picks.length() && titles.size() < 6; i++) {
                    JSONObject item = picks.optJSONObject(i);
                    if (item == null) continue;
                    String title = cleanNextTitle(item.optString("title", ""), current, titles);
                    if (title.isEmpty()) continue;
                    titles.add(title);
                    notes.add(cleanText(item.optString("note", ""), 60));
                }
            }
            if (titles.isEmpty()) {
                // 老格式 / 老缓存：titles + notes 两个数组按位配对，被闸掉的那条留空串占位，保证下标一一对齐
                JSONArray array = json.optJSONArray("titles");
                JSONArray noteArray = json.optJSONArray("notes");
                if (array != null) {
                    for (int i = 0; i < array.length() && titles.size() < 6; i++) {
                        String title = cleanNextTitle(array.optString(i, ""), current, titles);
                        if (title.isEmpty()) continue;
                        titles.add(title);
                        notes.add(noteArray == null ? "" : cleanText(noteArray.optString(i, ""), 60));
                    }
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
            // 「有没有下一季」是后面要不要接着搜的判断依据，模型没写就当没说（null），不当「没有」
            Boolean hasNext = json.has("has_next") && !json.isNull("has_next") ? json.optBoolean("has_next") : null;
            String reason = cleanText(json.optString("reason", ""), 60);
            // 配了大模型的客户端把模型给出的题材词攒进共享文档，没配大模型的客户端启动时拉走就能用
            BingeWords.learn("题材词", tags);
            DebugLog.d("AiJudge", "下一部推荐 " + titles + " 题材词 " + tags + " 还有下一季=" + hasNext + " 依据=" + reason);
            return new Next(titles, tags, hasNext, notes, reason);
        } catch (Exception e) {
            return null; // 模型说了听不懂的话，就当没问
        }
    }

    /** 剧名洗一遍：去序号、去书名号、限长、排除当前这部和重复的、剔占位，再过脱敏闸；不合格返回空串 */
    private static String cleanNextTitle(String raw, String current, List<String> picked) {
        String title = raw == null ? "" : raw.trim();
        title = title.replaceAll("^[\\d]+[.、)．]\\s*", "").trim(); // 模型偶尔带个「1. 」序号
        title = title.replaceAll("[《》]", "").trim(); // 模型爱给书名号
        if (title.length() < 2 || title.length() > 24) return "";
        if (current != null && title.equals(current)) return ""; // 就是当前这部，等于没说
        if (picked.contains(title)) return "";
        // 提示词模板里的示例占位，模型偶尔会照抄
        if (title.equals("剧名") || title.equals("名称") || title.equals("名称写法") || title.equals("作品名")
                || title.contains("兼容字段") || title.contains("用了picks")) return "";
        return AiGuard.blocked(title) ? "" : title;
    }

    /** 理由/判断依据：压空白、限长、过脱敏闸，模型文本一个字都不许漏过这道闸 */
    private static String cleanText(String text, int max) {
        if (text == null) return "";
        String value = text.trim().replaceAll("\\s+", " ");
        if (value.length() > max) value = value.substring(0, max);
        return AiGuard.blocked(value) ? "" : value;
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
                if (AiGuard.blocked(name)) continue; // 铁律：命中关键字的历史片名一律不发大模型
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
                            if (!key.startsWith("v4·")) continue; // 提示词升级前的旧答案不复用，也别让它在缓存文件里常驻（版本须与 nextKey 一致）
                            JSONObject item = json.optJSONObject(key);
                            if (item == null) continue;
                            List<String> titles = new ArrayList<>();
                            List<String> notes = new ArrayList<>();
                            // titles 会跳过敏感项，notes 必须在同一个循环里按原下标一起取，否则理由会串到别的片名下
                            JSONArray array = item.optJSONArray("titles");
                            JSONArray noteArray = item.optJSONArray("notes");
                            if (array != null) for (int i = 0; i < array.length(); i++) {
                                String title = array.optString(i, "");
                                if (title.isEmpty() || AiGuard.blocked(title)) continue;
                                titles.add(title);
                                String note = noteArray == null ? "" : noteArray.optString(i, "");
                                notes.add(note.isEmpty() || AiGuard.blocked(note) ? "" : note);
                            }
                            List<String> tags = new ArrayList<>();
                            JSONArray tagArray = item.optJSONArray("tags");
                            if (tagArray != null) for (int i = 0; i < tagArray.length(); i++) {
                                String tag = tagArray.optString(i, "");
                                if (!tag.isEmpty() && !AiGuard.blocked(tag)) tags.add(tag);
                            }
                            String reason = item.optString("reason", "");
                            if (AiGuard.blocked(reason)) reason = "";
                            Boolean hasNext = item.has("has_next") && !item.isNull("has_next") ? item.optBoolean("has_next") : null;
                            NEXT.put(key, new Next(titles, tags, hasNext, notes, reason)); // 空的也放进去＝「问过没结果」
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
                item.put("notes", new JSONArray(entry.getValue().notes));
                item.put("reason", entry.getValue().reason);
                if (entry.getValue().hasNext != null) item.put("has_next", entry.getValue().hasNext.booleanValue());
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
        String question = "剧名：" + name
                + (y.isEmpty() ? "" : "\n年份：" + y + "（同名剧、翻拍版要靠这个年份区分，认准这个年份的版本）")
                + "\n类型：" + (TextUtils.isEmpty(typeName) ? "未知" : typeName)
                + "\n来源站：" + (TextUtils.isEmpty(site) ? "未知" : site)
                + "\n问：这部剧的主演有哪些、剧情讲了什么？没有真人演员的（漫剧/动画）就给出品方（导演、工作室、版权方）。"
                + "剧名、年份、类型这些已经给过你了，plot 里不要再重复一遍；认不出这部剧就把对应字段留空，不要编。";

        // 模型链统一从 AiChat 走：豆包优先（限额省钱），千问兜底（按量计费总有答）
        return parseCast(AiChat.chat(SYSTEM_ACTORS, question));
    }

    /** 出品方：太长太短都不像「某某工作室」这类名字，脏词直接扔 */
    private static String cleanStudio(String studio) {
        if (studio == null) return "";
        String name = studio.trim().replaceAll("^[\\d]+[.、)．]\\s*", "").trim();
        if (name.length() < 2 || name.length() > 30) return "";
        return AiGuard.blocked(name) ? "" : name;
    }

    /**
     * 剧情简介：太短（「暂无简介」这类废话）、太长（模型啰嗦起来没完，详情页也放不下）都不要；
     * 碰线的词按老规矩抠掉再留，抠光了就当它没给。
     */
    private static String cleanPlot(String plot) {
        if (plot == null) return "";
        String text = plot.trim().replaceAll("(?i)^\\s*(剧情简介|剧情|简介|plot)\\s*[:：]?\\s*", "").trim();
        text = text.replaceAll("\\s+", " ");
        if (text.length() < 10 || text.length() > 600) return "";
        if (text.contains("暂无简介") || text.contains("暂无介绍")) return "";
        String safe = AiGuard.scrub(text);
        return safe.isEmpty() ? "" : safe;
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
            String plot = cleanPlot(json.optString("plot"));
            // 形态这道闸本地再卡一遍：模型报了漫剧/动画却还列了一串人名，那串也不能信（2026-10-24）
            if (noCastForm(json.optString("form"))) {
                DebugLog.d("AiJudge", "模型报的形态没有真人演员，只显示出品方：" + studio);
                return new Cast(new ArrayList<>(), studio, plot);
            }
            JSONArray array = json.optJSONArray("actors");
            if (array == null) return new Cast(new ArrayList<>(), studio, plot);
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
            DebugLog.d("AiJudge", "补档案 演员" + names + " 出品方" + studio + " 简介" + (plot.isEmpty() ? "（空）" : plot.length() + "字"));
            return new Cast(names, studio, plot);
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
                            String plot = object.optString("plot", "");
                            if (!plot.isEmpty() && AiGuard.blocked(plot)) plot = "";
                            ACTORS.put(key, new Cast(read(object.optJSONArray("actors")), AiGuard.blocked(studio) ? "" : studio, plot));
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
                item.put("plot", entry.getValue().plot);
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
        String question = "剧名：" + name
                + "\n类型：" + (TextUtils.isEmpty(typeName) ? "未知" : typeName)
                + "\n地区：" + (TextUtils.isEmpty(region) ? "未知" : region)
                + "\n问：这部剧是什么题材？给出 3 到 5 个能搜到同类短剧的关键词。";

        // 模型链统一从 AiChat 走：豆包优先（限额省钱），千问兜底（按量计费总有答）
        return parseSuggest(AiChat.chat(SYSTEM_SUGGEST, question));
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
        String question = "条目名：" + name
                + "\n备注：" + (TextUtils.isEmpty(remarks) ? "无" : remarks)
                + "\n集数：" + episodes
                + "\n单集时长：" + (duration > 0 ? (duration / 60000) + "分钟" : "未知")
                + "\n来源站：" + (TextUtils.isEmpty(site) ? "未知" : site)
                + "\n问：这条是不是一整部短剧被合成一个视频的合集版？";

        // 模型链统一从 AiChat 走：豆包优先（限额省钱），千问兜底（按量计费总有答）
        return parse(AiChat.chat(SYSTEM, question));
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
