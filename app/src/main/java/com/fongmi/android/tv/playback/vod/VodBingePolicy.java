package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.AiSetting;
import com.fongmi.android.tv.setting.BingeSetting;
import com.fongmi.android.tv.utils.AiGuard;
import com.fongmi.android.tv.utils.AiJudge;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 刷剧：一部短剧播完后，跨站搜同题材的下一部，一部接一部往下播 */
class VodBingePolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;

    private final List<Vod> pool = new ArrayList<>();
    private final Set<String> tried = new HashSet<>();
    private final Runnable timeoutTask = this::onTimeout;
    private final Runnable pickTask = this::pickNow;
    private final Runnable probeTimeoutTask = this::onProbeTimeout;
    private final Runnable probeGraceTask = this::onProbeGrace;
    private final Runnable aiTimeoutTask = this::onAiTimeout;

    private final Random random = new Random();
    private final List<Integer> recentFamilies = new ArrayList<>(); // 最近几部刷过的题材族（新→旧），防一路死磕一族
    private final Set<String> fromType = new HashSet<>(); // 从站点「短剧/漫剧/正剧」分类里捞出来的剧名，剧种有保障
    private String homeKey = ""; // 当前这部所在的站：找下一季先看自己家，再到外头找（马先生 2026-10-30）
    private boolean searchHome; // 这一轮是不是只在当前站里搜
    private boolean aiAnswered; // 大模型对本轮「下一部」的答复到了没（答了什么 Namen）
    private boolean waitingAi; // 本地季号写法都搜空了，正在等大模型给个说法
    private Boolean seriesHasNext; // 大模型说这个系列还有没有下一季
    private boolean genreFallback; // 系列已经判到头，改刷同类剧了（只降级一次，别无限降级）
    private boolean singlePart; // 当前这部就一个文件（电影这种）：先接它的续作，接不上再推荐同类（马先生 2026-10-31）
    private boolean similarFallback; // 单部的：续作接不上、已经改成推荐同类作品了（同样只放一次机会）
    private boolean similarAsked; // 单部的「同类还有什么」问过没（缓存里有就没有等待过程）
    private AiJudge.Next similarNext; // 大模型给的单部答案：续作写法 + 同类推荐
    private final Set<String> seen = new HashSet<>(); // 播放记录里已经有过的，这一轮别再推出来（马先生 2026-10-31）
    private volatile boolean typePending; // 分类池子正在路上
    private long typeWaitUntil; // 最多等到这个时候，过了就不再为它等

    private final List<String> aiTitles = new ArrayList<>(); // 本轮大模型点名的剧名，搜到了就优先切它
    private List<String> keywords = new ArrayList<>();
    private String seriesMain = ""; // 当前这部所属的系列：主标题（去掉「第X季」这类后缀）
    private int seriesIndex = -1; // 当前是系列里的第几季；名字里没写就是 -1
    private boolean serialMode; // 名字里明写着第几季：只认这个系列的下一季，绝不换别的剧
    private boolean serialMarked; // 名字里明写着第几季/第几部（跟 serialMode 分开记：降级那两步会把它清掉，退路的判断还得认它）
    private String region = "";
    private String typeName = ""; // 站点给的分类（如「爱情/都市」），搜同类用得上
    private String actor = ""; // 当前这部的主演（红果那招：看《A》也推同主演的《B》）
    private int kind = BingeTags.KIND_UNKNOWN; // 剧种：一路只刷同一种，不串
    private int keywordIndex;
    private int serial;
    private int failures;
    private int dropNotNext; // 这一批里「不是这个系列的下一季」丢了多少部
    private int dropWatched; // 这一批里「播放记录里已经有」丢了多少部
    private int dropKind;    // 这一批里「剧种/产地不对」丢了多少部
    private int dropRejected; // 这一批里「以前推过、接不上」丢了多少部
    private int dropTaste; // 这一批里「不合口味」丢了多少部
    private boolean tasteFallback; // 已经走过「按口味问大模型要同类」这条退路
    private List<String> currentTags; // 当前这部命中的题材词：口味判「不碰」时要给它让路
    private int searchSites; // 这一个词一共问了几个站
    private int searchReplies; // 这一个词已经回来了几个站（攒齐了就不用再等）
    private boolean active;
    private boolean waitingDetail; // 已选中一部、正在等详情回来校验
    private boolean preparing; // 预加载中：搜索照旧跑，但 pick 只备货不切换
    private Vod pending; // 提前备好的下一部，真播完直接接上

    /** 一批并行验几部：一部一部串行试的话，验不过就要多等一个 PROBE_TIMEOUT，用户看到的就是干等 */
    private static final int PROBE_PARALLEL = 3;
    /** 切过去之前先验货：后台拉一次详情，确认真能播再切，播不了的当场丢掉，不切、不留历史 */
    private static final ExecutorService PROBE = Executors.newFixedThreadPool(PROBE_PARALLEL);
    /** 排在后面的候选先验过了，给排前面的这么久的机会跟上，跟不上就切先验过的那个 */
    private static final long PROBE_GRACE = 500L;
    /** 站点可靠度的加减分幅度：次次验得过加满，次次验不过扣满 */
    private static final int SITE_BONUS = 10;
    /** 新鲜度加分：今年的加满，去年的少一点，三年前的就不加了（短剧追新） */
    private static final int FRESH_BONUS = 6;
    /** 画像兴趣的加减分上限：看完过这类就往上加，弃过这类就往下扣 */
    private static final int PROFILE_BONUS = 12;
    /** 打散：连着推同一题材族的扣分（轻量 MMR，别让用户连吃三部一模一样的） */
    private static final int REPEAT_PENALTY = 6;
    /** 一个词搜回来是空的、还有站没回时，最多再等这么久就换下一个词（不用干等 SEARCH_TIMEOUT 那 6 秒） */
    private static final long EMPTY_GRACE = 1500L;
    /** 等大模型按口味报同类作品，最多等这么久 */
    private static final long TASTE_AI_GRACE = 6000L;
    /** 口味加分：命中收藏/历史里常看的题材 */
    private static final int TASTE_BONUS = 6;
    /** 口味扣分：命中「收藏和历史里一次都没出现过」的题材 */
    private static final int TASTE_PENALTY = 8;
    /** 按站点分类拉池子用的：跟验货分开跑，别互相堵着 */
    private static final ExecutorService CATEGORY = Executors.newSingleThreadExecutor();
    /** 分类池子已经在路上时，最多再等这么久再挑——等它一到，挑中的大概率就是正经短剧 */
    private static final long TYPE_GRACE = 1200L;
    /** 等分类池子的总上限：站点分类表头一回拉可能要好几秒，不能让用户一直干等着 */
    private static final long TYPE_WAIT_MAX = 3000L;
    /** 从「短剧分类」里捞出来的候选加这么多分：站点自己分的类，比名字猜靠谱得多 */
    private static final int TYPE_BONUS = 8;
    /** 大模型点名的剧加分：它给的是具体剧名，搜出来就是它，比题材词撞运气准得多 */
    private static final int AI_TITLE_BONUS = 15;
    /** 别的站也有同一季时留在当前站源的加分：不至于大过「跳过一季」的落差，不会因为换站而跳季 */
    private static final int SAME_SITE_BONUS = 15;
    /** 系列剧：本地那几种季号写法全搜空了，最多再等大模型这么久——它也许知道下一季在站里叫什么 */
    private static final long SERIES_AI_GRACE = 2500L;
    /** 单部作品：几种部号写法都搜空了，最多再等大模型这么久（它那儿有续作写法和同类推荐） */
    private static final long MOVIE_AI_GRACE = 3000L;
    /** 够一部电影的篇幅：单集又短得多的（短视频片段、MV）不往下接 */
    private static final long MOVIE_MIN_MS = 40 * 60 * 1000L;
    /** 年份：站里写「2025」或「更新至2025」的都认得出来 */
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    /** 一轮里最多拿几个大模型给的剧名去搜：多了万一模型瞎编，白跑好几次搜索 */
    private static final int AI_TITLE_MAX = 3;
    private int epoch; // 每次作废重来（interrupt/reset/forceNext）都 +1，迟到的异步回调据此自行作废
    private boolean probing; // 有一批正在后台验货
    private final List<Vod> probeBatch = new ArrayList<>(); // 这一批按分数排好的候选（新→旧按分数降序）
    /** 验货那趟已经从站内取回的完整详情（含播放列表/URL 参数），切过去时直接落地，不再二次问站 */
    private final java.util.Map<String, Result> probedDetails = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Vod> probingItems = new ArrayList<>(); // 这一批里还在等详情的
    private final List<Vod> probePassed = new ArrayList<>(); // 这一批里已经验过的
    private boolean gracePosted; // 「再给排前面的半秒机会」那个定时任务排了没
    private int probingEpoch;
    private final List<Integer> pickedFamilies = new ArrayList<>(); // 最近挑中的题材族（新→旧），连着同一族就打散

    VodBingePolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host) {
        this.controller = controller;
        this.state = state;
        this.host = host;
    }

    boolean isActive() {
        return active;
    }

    /** 刷剧还在管事（搜索中或等详情）：搜索结果只能归刷剧，绝不许流进换源 */
    boolean isHandling() {
        return active || waitingDetail;
    }

    /** 换源搜索要开始了，刷剧先停，免得两边抢结果 */
    void interrupt() {
        active = false;
        preparing = false;
        pending = null; // 备的货也不要了，免得跟换源抢结果
        endProbe(); // 验货也停，回来的结果一律作废
        waitingAi = false;
        epoch++;
        clearTimers();
    }

    /** 没有下一集了：刷剧开着的又是短剧，就去找下一部；返回 true 表示接管了 */
    boolean onNoNext(boolean reversed) {
        if (reversed || !BingeSetting.isEnabled()) return false;
        if (AiGuard.blocked(host.getVodName())) return false; // 碰线的剧不联想、不搜索
        // 能不能刷全看 canBingeNow：它已经把「单集电影（含续作）不接」的边界算进去了
        if (!isShortDrama()) return false;
        BingeProfile.hit(BingeTags.tagsOf(host.getVodName())); // 看完了：题材记一分，下次更懂你
        if (serial >= BingeSetting.MAX_SERIAL) {
            host.onBingeEnd();
            return true; // 接管掉，别再叠一个「没有下一集」的提示
        }
        Vod item = pending;
        if (item != null) { // 剩最后几集时已经备好的，直接接上，不用再搜
            pending = null;
            DebugLog.d("Binge", "接备好的下一部 " + item.getName() + " 来自 " + item.getSiteName());
            host.showBingeNext(item);
            tried.add(item.getName());
            serial++;
            waitingDetail = true;
            active = false;
            controller.selectSource(item);
            return true;
        }
        if (preparing) preparing = false; // 等不到备好了：正在搜的结果直接拿来接，不再只备货
        if (active || probing) return true; // 搜索或验货正在进行，好了直接接
        begin();
        return true;
    }

    /** 当前这部能不能一部接一部刷下去，决定「下一部」按钮要不要显示 */
    boolean canBingeNow() {
        if (!BingeSetting.isEnabled()) return false;
        if (AiGuard.blocked(host.getVodName())) return false; // 碰线的剧不联想、不搜索
        if (state.getHistory() != null && state.getHistory().isRevPlay()) return false; // 倒序看就别刷
        String name = host.getVodName();
        if (!state.hasFlags()) return BingeTags.hasSerialMark(name); // 还没拿到分集信息：明写「第X季」的才接
        int episodes = state.getFlag().getEpisodes().size();
        long duration = state.getHistory() == null ? 0 : state.getHistory().getDuration();
        // 名字里明写着第几部/第几季的（《持械入宋：第九季》《杀死比尔1》）：一部接一部往下看，
        // 单集的系列电影也照样接（2026-10-21 那条「单集电影一律不接」到此为止，
        // 马先生 2026-10-31：看着《杀死比尔1》就该自动接《杀死比尔2》）
        if (BingeTags.hasSerialMark(name)) return true;
        // 单集、又没有半点剧集相：是一部电影。够一部电影的篇幅才一部接一部往下推荐
        // （《杀死比尔》→《杀死比尔2》；没有第三部了→大模型推荐的同类型电影）
        if (episodes <= 1 && !BingeTags.askable(name, "", episodes)) return duration >= MOVIE_MIN_MS;
        if (episodes >= BingeTags.MIN_PLAYABLE) return BingeTags.canBinge(episodes, duration, name);
        // 集数很少，看看是不是整部短剧压成一个视频的合集版
        int verdict = BingeTags.compileOf(name, "", episodes, duration);
        if (verdict == BingeTags.COMPILE_YES) return true;
        if (verdict == BingeTags.COMPILE_ASK) {
            String key = BingeTags.compileKey(name, "", episodes, duration);
            Boolean hit = AiJudge.peek(key);
            if (hit != null) return hit; // 模型给过结论就按模型的来
            if (AiSetting.ready()) AiJudge.ask(key, name, "", episodes, duration, host.getVodMark(), null); // 先问着，播完就有结论了
            return BingeTags.compileFallback(name, "", episodes, duration); // 模型没结论，本地评分兜底
        }
        return false;
    }

    private boolean isShortDrama() {
        return canBingeNow();
    }

    /** 手动点「下一部」：不等播完，立刻换一部 */
    void forceNext() {
        if (!BingeSetting.isEnabled()) return;
        if (AiGuard.blocked(host.getVodName())) return; // 碰线的剧不联想、不搜索
        BingeProfile.miss(BingeTags.tagsOf(host.getVodName())); // 没看完就换：题材降权，别老推这类
        if (serial >= BingeSetting.MAX_SERIAL) {
            host.onBingeEnd();
            return;
        }
        clearTimers();
        pool.clear();
        active = false;
        waitingDetail = false;
        waitingAi = false;
        preparing = false;
        pending = null;
        endProbe();
        epoch++; // 上一轮没回来的验货结果一律作废
        begin();
    }

    /** 还剩没几集了：提前把下一部搜好备着（只备货不切换），播完直接接上，不用干等搜索 */
    void prepare() {
        if (pending != null || preparing || active || waitingDetail) return;
        if (!BingeSetting.isEnabled()) return;
        if (AiGuard.blocked(host.getVodName())) return; // 碰线的剧不联想、不搜索
        if (serial >= BingeSetting.MAX_SERIAL) return;
        if (!canBingeNow()) return;
        DebugLog.d("Binge", "快播完了，提前备下一部，当前=" + host.getVodName());
        preparing = true;
        begin();
    }

    private void begin() {
        region = BingeTags.regionOf(host.getVodName()); // 当前是哪一国的，后面就一路哪一国
        kind = currentKind(); // 当前是哪一种（短剧/漫剧/正剧），后面就一路哪一种，不串剧种
        singlePart = isSinglePart(); // 就一个文件的（电影这种）：按「续作 → 同类」往下接（马先生 2026-10-31）
        seriesMain = BingeTags.serialMainOf(host.getVodName());
        seriesIndex = BingeTags.serialOf(host.getVodName());
        if (singlePart && seriesIndex <= 0) seriesIndex = 0; // 《杀死比尔》这类没写第几部的：它自己算第一部，下一部自然是第二部
        serialMode = !seriesMain.isEmpty() && (seriesIndex > 0 || singlePart);
        serialMarked = BingeTags.hasSerialMark(host.getVodName());
        keywords = serialMode ? partKeywords() : buildKeywords(host.getVodName());
        // ⚠️ 复位必须写在 applyNext 之前：模型的答案要是已经在缓存里，回调是同步回来的，
        // 写晚了会把刚拿到的答案又抹成「没问过」，后面就白等一轮
        keywordIndex = 0;
        // ⚠️ tried 每轮重新开始：它记的是「这一轮试过没成的」，不该跨轮累积 ——
        // 提前备货那一轮要是验货抽风没过（《镖人》第三季明明在站里），名字就被记进黑名单，
        // 等真播完再搜时反而被自己挡在门外，用户手动搜得到、自动却接不上。
        // 看过的由 watched() 挡，池子里已有的由 contains(pool) 挡，都不会漏。
        tried.clear();
        tried.add(host.getVodName());
        typePending = false; // 上一轮没回来的分类池别再挡着这一轮
        // 系列剧先在自己这部所在的站里找下一季：同一个站源的下一季最省事，搜不到再到外面找
        homeKey = state.getDetailKey();
        searchHome = serialMode && !homeKey.isEmpty();
        similarAsked = false;
        similarNext = null;
        aiAnswered = false;
        waitingAi = false;
        seriesHasNext = null;
        genreFallback = false;
        similarFallback = false;
        tasteFallback = false;
        currentTags = BingeTags.tagsOf(host.getVodName());
        BingeTaste.load(); // 后台翻一遍收藏/历史/关注的演员，等要问模型时口味已经备好了
        loadSeen(); // 播放记录里已经有的，这一轮别再推出来
        applyNext(host.getVodName()); // 让大模型报几部「下一部/下一季」的剧名，比本地题材词准
        DebugLog.d("Binge", "开始刷剧，系列=" + (serialMode ? seriesMain + " 第" + seriesIndex + "部/季" : "无") + "，单部=" + singlePart + "，产地=" + region + "，剧种=" + BingeTags.kindWord(kind) + "，关键词=" + keywords + "，当前=" + host.getVodName());
        active = true;
        if (!serialMode) searchByType(); // 先开一路：直接去站点的「短剧」分类里端一盘出来，比关键词搜索准（系列剧/单部不用，先找续作）
        search();
    }

    /**
     * 这一部该叫「第X季」还是「第X部」：名字里写着季就按季、写着部就按部；
     * 两个都没写（《杀死比尔》）才退回「单部=电影说部、剧说季」的老规矩。
     */
    private String serialUnit() {
        String name = host.getVodName();
        if (name == null) return singlePart ? "部" : "季";
        if (name.contains("季")) return "季";
        if (name.contains("部")) return "部";
        return singlePart ? "部" : "季";
    }

    /**
     * 当前这部是不是「单部的片子」：就一个文件，又没有半点剧集相 —— 就是部电影。
     * ⚠️ 名字带短剧/漫剧/合集字样、或备注写着全几十集的（哪怕同样只有一个文件）不算：
     * 那是整部压成合集版的剧，走原来那条刷剧路子，别在这儿当成电影去找续作。
     */
    private boolean isSinglePart() {
        if (!state.hasFlags()) return false;
        int episodes = state.getFlag().getEpisodes().size();
        return episodes <= 1 && !BingeTags.askable(host.getVodName(), "", episodes);
    }

    /**
     * 「下一部该看什么」交给大模型。
     *
     * 以前是我们从剧名里抠题材词去站里撞（「王妃」「重生」），撞到什么算什么——相关度全看站源
     * 有没有把那个词写进片名，经常搜出一堆八竿子打不着的。现在多问一句模型：它报的是**具体剧名**
     * （系列剧则报下一季的各种写法），我们拿名字去搜，搜到就是它。
     *
     * 时机上有讲究：模型一问要好几秒，播完再问就等于卡一下。所以切到新一部那一刻
     * （{@link #prewarmNext()}）和快播完时（{@link #prepare()} → begin）都会提前问，等真要接下一部时
     * 答案已经在缓存里。缓存没有就一边按本地词表搜、一边在后台问，问到再补进这一轮。
     *
     * 模型报的剧站里未必有：搜不到就当没这回事，下一步照旧换词搜，不会卡住也不会切错。
     */
    private void applyNext(String name) {
        aiTitles.clear();
        if (singlePart) { // 单部作品：那一问是「有没有续作 + 同类推荐」，跟刷剧的两问都不是一回事
            askSimilar(name);
            return;
        }
        AiJudge.Next next = AiJudge.peekNext(name, BingeTags.kindWord(kind), region);
        if (next != null) { // 上几轮问过，答案还在缓存里
            aiAnswered = true;
            seriesHasNext = next.hasNext;
            addNext(next, true);
            return;
        }
        if (!AiSetting.ready()) return;
        final int myEpoch = epoch;
        AiJudge.nextUp(name, typeName, region, BingeTags.kindWord(kind), actor, seriesMain, seriesIndex, got -> {
            if (myEpoch != epoch) return; // 这一轮已经作废，留给下一部
            aiAnswered = true;
            if (got != null) seriesHasNext = got.hasNext;
            if (waitingAi) {
                App.removeCallbacks(aiTimeoutTask);
                waitingAi = false;
            }
            if (got == null || got.useless()) {
                settle(); // 模型没给可用的东西：别悬在那儿干等，当场有个了断
                return;
            }
            if (!isHandling()) return;
            // 模型明说这个系列已经完结：别在本地写法里空转一遍，直接进入「改刷同类」
            if (Boolean.FALSE.equals(got.hasNext)) {
                // ⚠️ 模型说「没有下一季」不等于站里没有：《镖人》第三季站里搜得到，模型却说只到第二季——
                // 它按官方出品的口径答，站源收录什么它不知道。所以这里**不许当场收手**，
                // 本地那几种写法照搜完，真搜不到再走降级（端() 里会转去问同类推荐）。
                DebugLog.d("Binge", "大模型说 " + seriesMain + " 已经没有下一季了，站里有没有还得搜完才知道");
                if (isHandling() && pool.isEmpty() && keywordIndex >= keywords.size()) end();
                return;
            }
            // 这时候本地那几种写法多半已经搜空了，模型给的名字直接顶到最前，马上再搜一轮
            if (addNext(got, true) && keywordIndex >= keywords.size()) keywordIndex = 0;
            if (active && pool.isEmpty()) search();
        });
    }

    /**
     * 单部作品（电影）提前备着模型的答案：本地那几种部号写法先把续作搜一遍，
     * 这一步同时进行——等本地写法都搜空了，模型的答案多半已经回来了，不用再干等。
     * 它还带 has_next：明说没有续作的话，就不用指望后续的 local 写法能撞出来了。
     */
    private void askSimilar(String name) {
        if (!AiSetting.ready()) return;
        final int myEpoch = epoch;
        AiJudge.nextMovie(name, typeName, region, singlePart ? "" : BingeTags.kindWord(kind), actor, seriesMain, seriesIndex, got -> {
            if (myEpoch != epoch) return; // 这一轮已经作废，留给下一部
            similarAsked = true;
            similarNext = got;
            if (got != null && got.hasNext != null) seriesHasNext = got.hasNext;
            if (waitingAi && similarFallback) { // 已经降级到「推荐同类」了，答案一到马上开搜
                App.removeCallbacks(aiTimeoutTask);
                waitingAi = false;
                useSimilar();
                return;
            }
            // 还没降级：本地写法正在搜续作。模型说明没有下一部，就别把剩余写法搜完，直接降级
            if (Boolean.FALSE.equals(got == null ? null : got.hasNext) && isHandling() && pool.isEmpty()) end();
        });
    }

    /** 降级后的开搜：模型给的续作写法和同类片名顶到队首，立刻再来一轮 */
    private void useSimilar() {
        AiJudge.Next next = similarNext;
        if (!isHandling()) return;
        if (next == null || next.useless()) { // 模型也没给出能用的：照原样收尾
            DebugLog.d("Binge", "大模型没给出同类作品，收尾");
            end();
            return;
        }
        DebugLog.d("Binge", "按大模型推荐去找同类：" + next.titles);
        aiTitles.clear();
        boolean added = addNext(next, true);
        if (!added) { // 名字都被上一轮用掉了，没必要再搜一遍
            DebugLog.d("Binge", "推荐的几部都试过了，收尾");
            end();
            return;
        }
        keywordIndex = 0; // ai 给的排到最前了，从头再来
        if (!active) return;
        if (pool.isEmpty()) search();
        else schedulePick(); // 上一轮剩下的候选还没挑完，先把它们挑了
    }

    /**
     * 把模型给的剧名排进搜索词。front=true 插到最前（有缓存，一上来就搜它）；
     * false 追加到队尾——答案来晚了，别打乱正在跑的搜索，只在本地词都搜空时才轮到它。
     * 题材词只当兜底排在后面（万一剧名在站里没有，题材词还能捞一批同类）。
     */
    private boolean addNext(AiJudge.Next next, boolean front) {
        // 系列剧只要下一季的写法，别把队首挤满；单部的多留两个同类位置；
        // 按口味推荐那一步模型最多给 6 部，都收下（这时候队里本来就是空的）
        int max = tasteFallback ? 6 : serialMode ? 2 : singlePart ? 4 : AI_TITLE_MAX;
        List<String> added = new ArrayList<>();
        for (String title : next.titles) {
            if (added.size() >= max) break;
            if (title.length() < 2 || keywords.contains(title)) continue;
            if (title.equals(host.getVodName()) || tried.contains(title)) continue;
            // 已播放过的剧一律不接（马先生 2026-11-08）：模型偶尔会顺着「已看过」名单还是报出来，
            // 光靠搜索结果那道 watched() 挡不够 —— 名字进了搜索词就白跑一趟，降级分支还可能绕过。
            // 系列剧的「下一季写法」是「剧名 第X季」这类，跟历史里的完整名对不上，不会误挡。
            if (watched(title)) continue;
            // 降级推荐时别再报「本系列的下一季」：本地那几种写法刚刚全都搜空了才走到这一步，
            // 再拿同样的名字搜一遍纯属浪费（日志 13:23 那轮又搜了一遍「持械入宋：第十一季」等四个）
            if ((similarFallback || genreFallback || tasteFallback) && laterPart(title)) continue;
            added.add(title);
        }
        if (added.isEmpty() && next.tags.isEmpty()) return false;
        if (front) keywords.addAll(0, added);
        else keywords.addAll(added);
        aiTitles.addAll(added);
        for (String tag : next.tags) {
            if (tag.length() < 2 || keywords.contains(tag)) continue;
            keywords.add(tag);
        }
        DebugLog.d("Binge", "大模型给下一部 " + added + "，题材词 " + next.tags + "，依据=" + next.reason);
        return !added.isEmpty();
    }

    /**
     * 切到新一部时就开始问「再下一部看什么」：等这部播完，答案早就在缓存里了，
     * 接下一部的时候不用干等模型。
     */
    private void prewarmNext() {
        if (!AiSetting.ready() || singlePart) return; // 单部作品有它自己那一问（begin/fallback 里发），别再问一遍刷剧的
        String name = host.getVodName();
        // 违禁词那道闸在 AiJudge.nextUp 里（先抠词、理顺再发，抠光了才拒答），这里不再重复拦
        AiJudge.nextUp(name, typeName, region, BingeTags.kindWord(kind), actor, BingeTags.serialMainOf(name), BingeTags.serialOf(name), null);
    }

    /**
     * 系列/续作的搜索词：只搜这个行列本身 —— 主标题，外加「主标题 第X部/季」的几种写法
     * （有些站按部/季分条目，直接搜下一部更准）。
     * 两种数字写法都带上：**中文「第二部」和阿拉伯「第2部」都要搜**，站源只认其中一种的很常见，
     * 少了哪一半都可能搜不到下一部。部/季两种叫法也各来一遍 —— 电影叫「第二部」，剧叫「第二季」。
     * 当前这部没写第几部时（《杀死比尔》）它自己算第一部，要找的是第二部（seriesIndex 已在 begin 里归 0）。
     * 不掺题材词、不掺兜底词：搜出来再靠 filter 挑掉不相关的，纯属浪费一次搜索。
     */
    private List<String> partKeywords() {
        List<String> keys = new ArrayList<>();
        // ⭐ 部/季谁先搜，看**这部自己名字里写的是哪个**：整部压成一个文件的剧（《持械入宋：第十季》）
        // 会被算成「单部」，照「单部=电影」的老规矩先搜「第11部」，把「第11季」排到第三位——
        // 日志实证：13:19 那一轮先搜「第11部」「第十一部」，最后才轮到「第11季」，白费二十多秒。
        String first = serialUnit();
        String second = "部".equals(first) ? "季" : "部";
        int next = Math.max(seriesIndex, 1) + 1;
        keys.add(seriesMain); // 主标题：把整个系列都搜出来，靠 filter 挑部/季号
        // 马先生 2026-11-09：先搜「和当前片名同一种季号写法」的那一个。
        // 看《持械入宋》第10季，站里第11季多半也写成阿拉伯数字；中文「第十季」的站则先给「第十一季」，
        // 别让另一种写法排在前面白跑一轮、最后报「无资源」。
        String current = host.getVodName();
        boolean cnStyle = current != null && current.matches(".*第[零〇一二三四五六七八九十百两]+[部季].*");
        if (cnStyle) {
            keys.add(seriesMain + " 第" + BingeTags.cnNumber(next) + first);
            keys.add(seriesMain + " 第" + next + first);
        } else {
            keys.add(seriesMain + " 第" + next + first);
            keys.add(seriesMain + " 第" + BingeTags.cnNumber(next) + first);
        }
        keys.add(seriesMain + " 第" + next + second); // 站源叫法换一套的也要搜到：「庆余年 第2部」
        keys.add(seriesMain + next); // 站源偷懒写成「杀死比尔2」的，连着写才搜得到
        return keys;
    }

    /**
     * 把这一轮的「已看过」名单攒出来（马先生 2026-10-31：播放记录里已有的就别再推给用户）。
     * 站源写法会差一两个标点，名字之外再把洗净的版本也记一份，比对时两头都认。
     */
    private void loadSeen() {
        seen.clear();
        try {
            List<History> items = History.get();
            if (items == null) return;
            for (History item : items) {
                String name = item == null ? null : item.getVodName();
                if (name == null || name.isEmpty()) continue;
                name = name.trim();
                seen.add(name);
                String text = BingeTags.clean(name);
                if (text.length() >= 2) seen.add(text);
            }
        } catch (Throwable ignored) {
            // 读不到就当没有，filter 里还有一次按名查库兜底
        }
    }

    /**
     * 按站点分类拉候选（LunaTV 那招）：拉站点的分类表 → 找到「短剧/漫剧/正剧」那一类 → 整类拉一页下来。
     * 分类是站点自己分的，里面的剧种天然统一，不用我们再拿名字去猜、去排除。
     * 只在认得出剧种时才走；分类表拉不到（或这站压根没短剧分类）就什么也不做，照旧靠搜索。
     */
    private void searchByType() {
        if (kind == BingeTags.KIND_UNKNOWN) return;
        final int myEpoch = epoch;
        final List<Site> sites = typeSites();
        if (sites.isEmpty()) return;
        typePending = true;
        typeWaitUntil = System.currentTimeMillis() + TYPE_WAIT_MAX;
        CATEGORY.execute(() -> {
            int got = 0; // 已经端回来多少部：够挑一阵子就收手，别把四个站全翻一遍
            try {
                for (Site site : sites) {
                    if (myEpoch != epoch || host.isHostFinishing()) return;
                    List<Class> types = BingeTypes.load(site);
                    if (types == null) continue;
                    String tid = BingeTypes.tidOf(site.getKey(), kind, random);
                    if (tid == null) continue; // 这站没有本剧种的分类，换下一个站
                    String page = String.valueOf(1 + random.nextInt(3)); // 别每次都翻第一页，不然老是那几部
                    Result result = SiteApi.categoryContent(site.getKey(), tid, page, false, new HashMap<>());
                    List<Vod> list = result == null ? null : result.getList();
                    if (list == null || list.isEmpty()) continue;
                    for (Vod item : list) if (item != null) item.setSite(site); // ⚠️ 分类接口不像搜索那样自带站点，不补上后面详情都拉不到
                    List<String> names = new ArrayList<>();
                    for (Vod item : list) if (item != null && item.getName() != null) names.add(item.getName());
                    DebugLog.d("Binge", "分类池 " + site.getName() + " tid=" + tid + " 第" + page + "页：" + names.size() + " 部");
                    App.post(() -> onTypeResult(result, names, myEpoch));
                    got += names.size();
                    if (got >= BingeSetting.PICK_BATCH * 3) break;
                }
            } catch (Throwable e) {
                DebugLog.d("Binge", "分类池拉取失败：" + e.getMessage());
            } finally {
                App.post(() -> { // 不管拉没拉到，都别再让挑片干等了
                    if (myEpoch != epoch) return;
                    typePending = false;
                    if (active && !probing && !pool.isEmpty()) pickNow();
                });
            }
        });
    }

    /** 分类结果回来：记下这些是从分类里捞的（挑片时加分），再走原来那套过滤 */
    private void onTypeResult(Result result, List<String> names, int myEpoch) {
        if (myEpoch != epoch || !isHandling()) return;
        fromType.addAll(names);
        onSearchResult(result, false);
    }

    /** 去哪些站的分类里捞：能搜的站，最多 4 个，够用了，拉多了反而慢 */
    private List<Site> typeSites() {
        List<Site> sites = new ArrayList<>();
        Site home = VodConfig.get().getHome();
        if (home != null && home.isSearchable()) sites.add(home);
        for (Site site : VodConfig.get().getSites()) {
            if (site != null && site.isSearchable() && !sites.contains(site)) sites.add(site);
            if (sites.size() >= 4) break;
        }
        return sites;
    }

    /** 当前这部是什么剧种：集数、单集时长、名字/分类上的标记一起看 */
    private int currentKind() {
        int episodes = state.hasFlags() ? state.getFlag().getEpisodes().size() : 0;
        long duration = state.getHistory() == null ? 0 : state.getHistory().getDuration();
        return BingeTags.kindOf(host.getVodName(), typeName, episodes, duration);
    }

    /** 详情到手后按详情重判剧种：用详情自己的名字、分类和集数，别拿上一部的名字判这一部 */
    private int kindOf(Vod item) {
        String name = item.getName() == null ? "" : item.getName();
        int episodes = 0;
        List<Flag> flags = item.getFlags();
        if (flags != null) for (Flag flag : flags) {
            if (flag != null && flag.getEpisodes() != null) episodes = Math.max(episodes, flag.getEpisodes().size());
        }
        long duration = state.getHistory() == null ? 0 : state.getHistory().getDuration();
        return BingeTags.kindOf(name, item.getTypeName(), episodes, duration);
    }

    /**
     * 组搜索词：先拿基础词表（产地词→题材词→主标题→分类→兜底），再按「族」重排——
     * 优先换到最近没刷过的题材族（看「穿越王妃」下部可以是「重生王妃」也可以是「穿越XX」，
     * 而不是一路全是「王妃」）；主族里再补一个没搜过的词换换口味。
     * 还有个小比例走探索位：换到画像里比较冷的族，试试新口味，防信息茧房。
     */
    private List<String> buildKeywords(String name) {
        List<String> base = BingeTags.keywords(name, typeName, region, kind);
        List<String> tags = BingeTags.tagsOf(name);
        // 红果那招「同主演推荐」：看《A》也推这个主演的别的剧。只带第一个主演，多了搜索就散了
        if (!actor.isEmpty() && !base.contains(actor)) base.add(Math.min(1, base.size()), actor);
        // 本地抠不出题材词：看大模型有没有给过推荐词；没给过就先问着（下部剧用得上），这部按画像/原词表走
        if (tags.isEmpty()) {
            List<String> suggested = AiJudge.peekSuggest(name);
            if (suggested != null && !suggested.isEmpty()) {
                tags = suggested;
                int insertAt = BingeTags.regionWord(region).isEmpty() ? 0 : Math.min(1, base.size());
                for (int i = suggested.size() - 1; i >= 0; i--) {
                    String word = suggested.get(i);
                    if (!base.contains(word)) base.add(insertAt, word);
                }
            } else if (AiSetting.ready()) {
                AiJudge.suggest(name, typeName, region, null); // 先问着，不用等
            }
        }
        // 动态漫要不要推，看画像：他确实爱看（或这部本身就是动态漫）就照推，
        // 看不出喜好（没基础数据）时才把它压到最后——不是永远最低（马先生 2026-10-11 澄清）。
        // 只管动态漫：漫剧族照常参与选族，不排除（漫剧是漫剧，动态漫是动态漫）。
        boolean dynamicOk = BingeTags.isDynamic(name) || BingeProfile.dynamicPreferred();
        if (dynamicOk && !base.contains("动态漫")) base.add("动态漫"); // 就好这口的话，动态漫也算一路搜索词
        Set<Integer> blocked = new HashSet<>(recentFamilies);
        int family;
        if (random.nextDouble() < epsilon()) {
            family = BingeProfile.coldestFamily(blocked, random); // 探索：试试画像里比较冷的族
            if (family >= 0) DebugLog.d("Binge", "探索新题材 " + BingeTags.familyHead(family));
        } else {
            family = BingeTags.pickFamily(tags, recentFamilies); // 利用：当前剧的族里优先换最近没刷过的
            if (family < 0) family = BingeProfile.hottestFamily(blocked); // 抠不出族：按画像最热的来
        }
        recordFamily(family);
        if (family < 0) return base;
        List<String> keys = new ArrayList<>();
        String regionWord = BingeTags.regionWord(region);
        if (!regionWord.isEmpty() && base.contains(regionWord)) keys.add(regionWord);
        for (String key : base) if (BingeTags.familyOf(key) == family && !keys.contains(key)) keys.add(key);
        String alt = BingeTags.familyAlt(family, keys);
        if (alt != null && keys.size() < 4) keys.add(alt); // 族里再补一个没搜过的词，族内也换口味
        for (String key : base) if (!keys.contains(key)) keys.add(key);
        return keys;
    }

    /**
     * 动态漫扣多少分：正在看动态漫（下一部还是动态漫很正常）或画像里就爱看动态漫 → 一分不扣，正常排队；
     * 看不出喜好（画像里动态漫没数据）→ 扣到队尾，优先给真人短剧。
     * ⚠️ 只针对动态漫：**漫剧、动漫、二次元这些一概不扣**（马先生：漫剧是漫剧，动态漫是动态漫）。
     */
    private int dynamicPenalty() {
        if (BingeTags.isDynamic(host.getVodName())) return 0;
        if (kind == BingeTags.KIND_COMIC) return 0; // 正在刷漫剧，下一部是动态漫很正常，别压
        return BingeProfile.dynamicPreferred() ? 0 : BingeTags.DYNAMIC_PENALTY;
    }

    /** 探索比例：初始 25%，按画像累计完播数指数衰减（0.99^n），地板 5%（工业典型 0.05~0.20，冷启动取偏上） */
    private double epsilon() {
        return Math.max(BingeSetting.EXPLORE_MIN, BingeSetting.EXPLORE_INIT * Math.pow(BingeSetting.EXPLORE_DECAY, BingeProfile.totalPos()));
    }

    private void recordFamily(int family) {
        if (family < 0) return;
        recentFamilies.remove((Integer) family);
        recentFamilies.add(0, family);
        while (recentFamilies.size() > 3) recentFamilies.remove(recentFamilies.size() - 1);
    }

    private void search() {
        App.removeCallbacks(timeoutTask);
        if (host.isHostFinishing()) return; // 页面都退了别再发搜索
        if (keywordIndex >= keywords.size()) {
            advance(); // 这套词搜空了：先换路子（本站→全站→问模型），换不成才是真没戏
            if (keywordIndex >= keywords.size()) {
                // 降级到「推荐同类」了却没词可搜：模型那份东西要么早已用掉，要么压根没给
                if (similarFallback && similarAsked && !waitingAi) end();
                return;
            }
        }
        // 一个词都没有（多半在等模型给名字）：别空转，也别悬在那儿——
        // 以前这里直接 return，active 一直是 true 又没有任何定时任务，用户看到的就是「播完停住」
        if (keywords.isEmpty()) {
            if (!waitingAi) end();
            return;
        }
        List<Site> sites = sites();
        String keyword;
        if (searchHome) {
            // 当前站这一圈只搜主标题：「镖人」一次就把整个系列都端出来，季号写法留到全站那一圈再挨个试。
            // 以前五种写法都在当前站各搜一遍，五种 × 6 秒 = 半分钟，用户看到的就是一直「正在搜索…」
            keyword = keywords.get(0);
            keywordIndex = keywords.size(); // 这一个词搜完就 advance 到全站那一圈
        } else {
            keyword = keywords.get(keywordIndex++);
        }
        searchReplies = 0; // 这一个词问了几个站、回来了几个，攒齐了就不必再等（见 onSearchResult）
        searchSites = sites.size();
        DebugLog.d("Binge", "搜「" + keyword + "」 站数=" + sites.size() + (serialMode ? "（只要 " + seriesMain + " 第" + (Math.max(seriesIndex, 1) + 1) + " 往后）" : similarFallback ? "（降级推荐）" : ""));
        host.requestBingeSearch(sites, keyword);
        App.post(timeoutTask, BingeSetting.SEARCH_TIMEOUT);
    }

    /**
     * 一套关键词搜空之后的路：
     *   1. 系列剧头一轮只在当前站里搜过 —— 放开到所有站再来一圈；
     *   2. 还是空的、又是系列剧：本地那几种季号写法显然不对版，
     *      停一停等大模型（它可能知道下一季在这站叫什么、或者干脆没有下一季）；
     *      等不到看 {@link #onAiTimeout()}，答案到了看 applyNext 的回调。
     * 都不是（普通刷剧）就正常收手。
     */
    private void advance() {
        if (searchHome) { // 自己家这一圈搜完了，再到外面挨个站找
            searchHome = false;
            keywordIndex = 0;
            DebugLog.d("Binge", "当前站没有下一季，放开到所有站再找");
            return;
        }
        // 单部作品（电影）不等这一问：它那一问是「同类推荐」，留给降级那一步用（见 fallbackSimilar）
        if (serialMode && !singlePart && !aiAnswered && !waitingAi && seriesHasNext == null && AiSetting.ready()) {
            waitingAi = true;
            DebugLog.d("Binge", "本地写法都没搜到下一季，等大模型给个说法");
            App.post(aiTimeoutTask, SERIES_AI_GRACE);
        }
    }

    /** 等大模型也没等到东西时收个尾：池子里还有货就挑，没有就按流程结束（系列会转去刷同类） */
    private void settle() {
        if (!isHandling()) return;
        if (!pool.isEmpty()) pickNow();
        else if (keywordIndex >= keywords.size()) end();
    }

    /** 等大模型的耐心用完了：不等，照原来那样收尾 */
    private void onAiTimeout() {
        if (!waitingAi) return;
        waitingAi = false;
        DebugLog.d("Binge", "等大模型超时，不等了");
        if (!isHandling()) return;
        if (pool.isEmpty()) end();
        else pickNow();
    }

    /**
     * 一轮里先搜谁：系列剧头一轮**只搜当前这部所在的站**（马先生 2026-10-30：看着《XX》第九季，
     * 最理想是这家站点自己就有第十季），这家人没有再到外面挨个站找。
     */
    private List<Site> sites() {
        if (searchHome) {
            Site home = null;
            for (Site site : VodConfig.get().getSites()) {
                if (site != null && homeKey.equals(site.getKey())) { home = site; break; }
            }
            if (home != null && home.isSearchable()) {
                List<Site> only = new ArrayList<>();
                only.add(home);
                return only;
            }
            searchHome = false; // 这站已经不在列表里（或不支持搜索），别卡在这一步
        }
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (site.isSearchable()) sites.add(site);
        return sites;
    }

    /**
     * 搜索是逐站回来的，每次都往池子里补，攒一小会儿再挑最好的。
     * ⚠️ 以前搜回来什么、筛掉什么都不记一笔：用户只看到一串「正在找…」的提示、最后什么也没接上，
     * 翻日志却看不出究竟是站里没这部，还是被我们自己的门槛挡掉了。现在全记下来。
     */
    void onSearchResult(Result result) {
        onSearchResult(result, true);
    }

    /** counted=false：分类池那一路的结果不算在「这一个词有几个站」里 */
    private void onSearchResult(Result result, boolean counted) {
        if (!active || result == null) return;
        if (counted) searchReplies++;
        List<Vod> all = result.getList();
        dropNotNext = 0;
        dropWatched = 0;
        dropKind = 0;
        dropRejected = 0;
        dropTaste = 0;
        List<Vod> items = filter(all);
        int total = all == null ? 0 : all.size();
        if (items.isEmpty()) {
            DebugLog.d("Binge", "搜回来 " + total + " 部，一部没留下（不是下一季=" + dropNotNext + " 看过=" + dropWatched + " 剧种/产地不对=" + dropKind + " 以前接过不上=" + dropRejected + " 不合口味=" + dropTaste + "）");
            // 这一个词是空的就别干等满 SEARCH_TIMEOUT。两种情况：
            // 1 问过的站都回来了（攒齐了）→ 立刻换下一个词，一秒都不浪费；
            // 2 还有站没回 → 再给它 EMPTY_GRACE 这么久，到点就换词，别让用户干等 6 秒。
            // ⚠️ 注意：开新搜索会把上一个词还在飞的站掐掉（ViewModelSearchRunner.start 会 cancel），
            // 所以只有「攒齐了」才敢立刻走，其余情况留一点时间。
            if (!waitingAi) {
                boolean replied = searchSites > 0 && searchReplies >= searchSites;
                App.removeCallbacks(timeoutTask);
                App.post(timeoutTask, replied ? 0 : EMPTY_GRACE);
            }
            return;
        }
        DebugLog.d("Binge", "搜回来 " + total + " 部，留下 " + items.size() + " 部");
        pool.addAll(items);
        schedulePick();
    }

    /**
     * 先攒再挑。以前是"谁先回来就切谁"，挑到差的切过去才发现播不了，又换一部——
     * 用户看到的就是连跳两三部，历史里还留下一堆压根没看过的剧。
     */
    private void schedulePick() {
        if (probing || pool.isEmpty()) return; // 正在验货就别插队
        if (pool.size() >= BingeSetting.PICK_BATCH) { // 攒够了不用再等
            App.removeCallbacks(pickTask);
            pickNow();
            return;
        }
        // 分类池子马上到，稍等一下：它里面的剧种是站点自己分的，比搜出来的靠谱。
        // 但只等到 typeWaitUntil，站点分类表头一回拉可能要好几秒，不能让用户干等着不出下一部
        boolean wait = typePending && System.currentTimeMillis() < typeWaitUntil;
        App.post(pickTask, wait ? TYPE_GRACE : BingeSetting.PICK_DELAY);
    }

    private List<Vod> filter(List<Vod> list) {
        List<Vod> items = new ArrayList<>();
        if (list == null || list.isEmpty()) return items;
        String current = host.getVodName();
        for (Vod item : list) {
            String name = item.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.equals(current) || tried.contains(name)) continue;
            if (contains(pool, name) || state.hasFailedId(item.getId())) continue;
            if (BingeTags.isBanned(name)) continue;
            if (BingeProfile.rejected(name)) { // 以前推过去、接不上的，攒够两次就不再推它
                dropRejected++;
                continue;
            }
            // 降级推荐（没有下一季了改推同类）时，不合口味的别推：收藏里没有爱情剧就别推爱情剧
            if ((tasteFallback || genreFallback || similarFallback) && dislikedTag(name)) {
                dropTaste++;
                continue;
            }
            // 系列剧只接下一季：别的剧、当季、更早的季一律不要，别的门槛也不再卡它
            // （续季常被站点分到跟本部不一样的类里，末季集数又少，拿剧种/集数去卡反而接不上）
            if (serialMode) {
                if (!isNextSerial(name)) {
                    dropNotNext++;
                    continue;
                }
                if (watched(name)) {
                    dropWatched++;
                    continue;
                }
                items.add(item);
                continue;
            }
            // 单部作品降级后的推荐：续作、大模型点名的，直接收，别的门槛不卡它；
            // 剩下那些（拿「古装/穿越」这类题材词搜回来的）走下面常规那几道闸，
            // 靠 score 排序挑最像的 —— 以前是一律拒收，结果题材词搜回来的全被丢光，
            // 用户看到的就是一串「正在找…」之后彻底没动静（日志 13:23 那一轮正是这样）。
            if (similarFallback) {
                if (laterPart(name) || matchAi(name)) {
                    if (watched(name)) dropWatched++;
                    else items.add(item);
                    continue;
                }
            }
            if (watched(name)) { // 播放记录里有的，别再推给用户（马先生 2026-10-31）
                dropWatched++;
                continue;
            }
            if (BingeTags.kindMismatch(name, kind)) { // 别串剧种：刷短剧不跳正剧，刷正剧不跳短剧
                dropKind++;
                continue;
            }
            if (wrongType(item)) { // 站点自己标了分类、又明摆着是别的剧种，一样丢掉
                dropKind++;
                continue;
            }
            if (!BingeTags.sameRegion(region, name)) { // 别跳到别的国籍去
                dropKind++;
                continue;
            }
            int episodes = BingeTags.parseEpisodes(item.getRemarks());
            if (episodes > 0 && episodes < BingeTags.MIN_PLAYABLE) {
                // 集数很少，可能是整部压成一个视频的合集版：是就收，拿不准的先问大模型
                int verdict = BingeTags.compileOf(name, item.getRemarks(), episodes, 0);
                if (verdict == BingeTags.COMPILE_NO) continue;
                if (verdict == BingeTags.COMPILE_ASK && !ask(item, episodes) && !BingeTags.compileFallback(name, item.getRemarks(), episodes, 0)) continue;
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 降级推荐里的「同一行列的后续」：《杀死比尔》看完推《杀死比尔2》这类。
     * 跟 {@link #isNextSerial} 一样只往后不回头，但不要求整轮都只接续作。
     */
    private boolean laterPart(String name) {
        if (seriesMain.isEmpty() || !sameSeries(seriesMain, name)) return false;
        return BingeTags.serialOf(name) > Math.max(seriesIndex, 1);
    }

    /**
     * 系列模式下这个候选能不能接：得是同一个系列，而且必须是更后面的季。
     * ⚠️ 只往后不回头：看的是第二季就只接第三季往后，绝不能跳回第一季（马先生 2026-10-15）。
     * 季号中文「第三季」、阿拉伯「第3季」、英文「S3」、罗马「Ⅲ」写法都由 BingeTags.serialOf 统一成数字再比，
     * 不管站源怎么混写都不会比错。
     * 季号一个字都没写的（站源给下一季起了副标题，《镖人3：风起大漠》这种）也不一刀切扔掉：
     * 名字对得上系列、又不是当前这部，先收下交给验货 —— 用户在站里手动搜得到，自动也该接得上。
     */
    private boolean isNextSerial(String name) {
        if (!sameSeries(seriesMain, name)) return false;
        int index = BingeTags.serialOf(name);
        if (index > 0) {
            // 当前这部没写第几部（《杀死比尔》）时 seriesIndex=0，它自己算第一部，所以从第二部起才算「下一部」
            return index > Math.max(seriesIndex, 1);
        }
        // 认不出季号：只要不是当前这部（换站重发、拆成上下部的那种）就先收着，交给验货
        String a = BingeTags.clean(name);
        String b = BingeTags.clean(host.getVodName());
        if (a.isEmpty() || b.isEmpty()) return true;
        return !a.startsWith(b) && !b.startsWith(a);
    }

    /**
     * 是不是同一个系列。站源常给下一季起个副标题、或把季号埋在名字中间（《镖人3：风起大漠》），
     * 老规矩「长度差超过一个字就不算」太苛刻，搜得到却接不上就是这么来的。
     * 主标题够长（≥3 字）才允许「被包在中间」，两字的（《镖人》）只认开头对得上的，免得撞进别的剧。
     */
    private boolean sameSeries(String main, String name) {
        if (BingeTags.sameSerial(main, name)) return true;
        String a = BingeTags.clean(main);
        String b = BingeTags.clean(name);
        if (a.length() < 2 || b.length() <= a.length()) return false;
        if (b.startsWith(a)) return true;
        return a.length() >= 3 && b.contains(a);
    }

    /**
     * 按站点给的分类再筛一道（LunaTV 的用法：搜索结果按分类 ID 过一遍，不在短剧分类里的丢掉）。
     * 只在**站点确实分了剧种**时才动手——这站连"短剧"分类都没有，它那"国产剧"里很可能就混着短剧，
     * 这时候硬筛反而误杀；分了的话，标着"电视剧""国产剧"的就不是我们要的，直接丢。
     */
    private boolean wrongType(Vod item) {
        if (kind == BingeTags.KIND_UNKNOWN) return false;
        String key = item.getSiteKey();
        String type = item.getTypeName();
        if (key == null || key.isEmpty() || type == null || type.isEmpty()) return false; // 没给分类，不猜
        if (!BingeTypes.hasKind(key, kind)) return false; // 这站没按剧种分过类，它的分类不作数
        int other = BingeTypes.kindOfType(type);
        return other != BingeTags.KIND_UNKNOWN && other != kind;
    }

    /**
     * 拿不准是不是合集的条目，去问大模型。有缓存结论的直接回；问完回来如果还轮得到它，就进池子。
     * 返回 false 表示这次先放下（没结论），不是判它死刑——下一轮搜到还会再问一次，但只问一次就记住了。
     */
    private boolean ask(Vod item, int episodes) {
        if (!AiSetting.ready()) return false;
        String name = item.getName();
        // 单集电影不问大模型（2026-10-21 边界）：本地先看有没有剧集相，没有就判「不是」，不烧 token
        if (!BingeTags.askable(name, item.getRemarks(), episodes)) return false;
        String key = BingeTags.compileKey(name, item.getRemarks(), episodes, 0);
        Boolean hit = AiJudge.peek(key);
        if (hit != null) return hit; // 有缓存：同步返回，filter 自己把它放进候选，不走回调
        // 没缓存才发起询问，回调只会异步回来，不会在 filter 迭代中途动 pool
        AiJudge.ask(key, name, item.getRemarks(), episodes, 0, item.getSiteName(), ok -> {
            if (!ok || !isHandling()) return;
            if (tried.contains(name) || contains(pool, name)) return;
            DebugLog.d("Binge", "大模型认下合集 " + name);
            pool.add(item);
            schedulePick();
        });
        return false;
    }

    private boolean contains(List<Vod> list, String name) {
        for (Vod item : list) if (name.equals(item.getName())) return true;
        return false;
    }

    /**
     * 播放记录里有没有：有了就跳过（马先生 2026-10-31：看过的别再推出来）。
     * 先看本轮攒的那份名单（洗净的名字也比对一遍），再按原名查一次库兜底名单漏掉的情况。
     */
    private boolean watched(String name) {
        if (name == null || name.isEmpty()) return false;
        String text = BingeTags.clean(name);
        if (seen.contains(name)) return true;
        if (text.length() >= 2 && seen.contains(text)) return true;
        try {
            return !History.findByName(name).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 挑几部最像的出来验货，验过了才切；预加载模式下验过了先存着，等真播完再接 */
    private void pickNow() {
        if (!active || probing || pool.isEmpty()) return; // 已经有一批在验了，不许再挑，防止双切
        final int penalty = dynamicPenalty();
        pool.sort((a, b) -> Integer.compare(scoreOf(b, penalty), scoreOf(a, penalty)));
        List<Vod> batch = new ArrayList<>();
        for (int i = 0; i < PROBE_PARALLEL && !pool.isEmpty(); i++) batch.add(pool.remove(0));
        probe(batch);
    }

    /** 候选总分：系列续季 + 关键词命中 + 剧种对不对 + 是不是从站点的分类池里捞的（这个最可信，加成最大） */
    private int scoreOf(Vod item, int penalty) {
        int score = BingeTags.score(item.getName(), item.getRemarks(), keywords, penalty);
        score += BingeTags.kindScore(item.getName(), kind);
        score += serialScore(item.getName());
        if (homeKey.equals(item.getSiteKey())) score += SAME_SITE_BONUS; // 同一部别的站也有时，留在当前这个站源
        if (fromType.contains(item.getName())) score += TYPE_BONUS;
        if (matchAi(item.getName())) score += AI_TITLE_BONUS; // 大模型点名的这部：搜到了就先切它
        score += profileScore(item.getName()); // 画像：看完过这类就往前排，弃过这类就往后压
        score += freshScore(item.getYear()); // 新鲜度：短剧追新，今年的优先
        score += diversityPenalty(item.getName()); // 打散：别连着推同一题材族
        score += tasteScore(item.getName()); // 口味：常看的往前排，收藏/历史里从没出现过的往后压
        score += Math.round(BingeProfile.siteWeight(item.getSiteKey()) * SITE_BONUS); // 这个站历次验货靠不靠谱
        return score;
    }

    /**
     * 画像分：候选名字命中的题材词，按「看完过 / 弃过」攒下的权重加减分。
     * 完播是最强的正信号（红果的排序里它占 35%），弃看是最强的负信号，这里两头都认。
     */
    private int profileScore(String name) {
        List<String> tags = BingeTags.tagsOf(name);
        if (tags.isEmpty()) return 0;
        double sum = 0;
        for (String tag : tags) sum += BingeProfile.raw(tag);
        if (sum == 0) return 0;
        return (int) Math.max(-PROFILE_BONUS, Math.min(PROFILE_BONUS, sum * 3));
    }

    /** 新鲜度：今年的加满、去年的少一点、三年前的不加。年份拿不到就不加不减，别瞎猜 */
    private int freshScore(String year) {
        int value = yearOf(year);
        if (value <= 0) return 0;
        int gap = Calendar.getInstance().get(Calendar.YEAR) - value;
        if (gap <= 0) return FRESH_BONUS;
        if (gap == 1) return FRESH_BONUS - 2;
        if (gap == 2) return FRESH_BONUS - 4;
        return 0;
    }

    /** 从「2025」「更新至2025」这类字段里抠年份，抠不出返回 0 */
    private int yearOf(String text) {
        if (text == null || text.isEmpty()) return 0;
        Matcher matcher = YEAR.matcher(text);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 口味分：命中收藏/历史里常看的题材加分，命中「几乎不碰」的题材扣分。
     * 「几乎不碰」= 那一族在收藏和播放记录里一次都没出现过（马先生：收藏里没有的类别就是不看的）。
     */
    private int tasteScore(String name) {
        BingeTaste.Snap taste = BingeTaste.peek();
        if (taste.liked.isEmpty() && taste.avoided.isEmpty()) return 0;
        int score = 0;
        for (String tag : BingeTags.tagsOf(name)) {
            if (disliked(tag)) score -= TASTE_PENALTY;
            else if (taste.liked.contains(tag)) score += TASTE_BONUS;
        }
        return Math.max(-TASTE_PENALTY, Math.min(TASTE_BONUS, score));
    }

    /** 这部是不是整个儿踩在「用户不碰」的题材上 */
    private boolean dislikedTag(String name) {
        for (String tag : BingeTags.tagsOf(name)) if (disliked(tag)) return true;
        return false;
    }

    /**
     * 这个题材词算不算「用户不碰的」。两条例外，免得把自己正在刷的给杀了：
     * ① 就是当前这部自己带的词（既然在看，就不算排斥）；② 当前剧种的形态词（刷漫剧时「漫剧」不算不碰）。
     */
    private boolean disliked(String tag) {
        if (tag == null || tag.isEmpty()) return false;
        if (BingeTaste.peek().avoided.isEmpty()) return false;
        if (currentTags != null && currentTags.contains(tag)) return false;
        if (tag.equals(BingeTags.kindWord(kind))) return false;
        return BingeTaste.peek().avoided.contains(tag);
    }

    /** 打散（轻量 MMR）：刚推过同一题材族的降分，隔一部之后的少降一点 */
    private int diversityPenalty(String name) {
        if (pickedFamilies.isEmpty()) return 0;
        int family = BingeTags.pickFamily(BingeTags.tagsOf(name), null);
        if (family < 0) return 0;
        if (family == pickedFamilies.get(0)) return -REPEAT_PENALTY;
        if (pickedFamilies.size() > 1 && family == pickedFamilies.get(1)) return -REPEAT_PENALTY / 2;
        return 0;
    }

    /** 候选是不是大模型点名的那一部：站里写的名字跟模型给的可能差一两个字，互相包含都算 */
    private boolean matchAi(String name) {
        if (name == null || name.length() < 3 || aiTitles.isEmpty()) return false;
        for (String title : aiTitles) {
            if (title.length() < 2) continue;
            if (name.equals(title) || name.contains(title) || title.contains(name)) return true;
        }
        return false;
    }

    /**
     * 同系列续季顶到最前面：主标题对得上、季数正好是下一季 → 压倒性加分；
     * 跳着来的（搜出第四季而现在是第二季）也认，但往后排，一季一季来更合理；
     * 当季和更早的季重罚（filter 已经挡过了，这是双保险）。
     * 当前这部没写季号时（比如就叫《庆余年》）把「庆余年 第二季」当第一季的下一部，一样顶上来。
     */
    private int serialScore(String name) {
        if (seriesMain.isEmpty() || !sameSeries(seriesMain, name)) return 0;
        int index = BingeTags.serialOf(name);
        if (index < 0) return 0;
        int want = Math.max(1, seriesIndex) + 1;
        if (index == want) return 200;
        if (index > want) return Math.max(120, 200 - (index - want) * 20);
        return -200;
    }

    /**
     * 切过去之前先拉一次详情验货：集数够不够一部接一部刷、产地对不对。
     * 验不过就地丢掉换下一部，一部都不切、历史里一条都不留——
     * 以前是先切过去再看，播不了的再换，用户就看到连跳，历史里还留下没看过的剧。
     */
    private void probe(List<Vod> items) {
        if (items == null || items.isEmpty()) return;
        probing = true;
        probeBatch.clear();
        probedDetails.clear(); // 上一批残留的详情不留，旧 epoch 的结果本来也会被丢掉
        probeBatch.addAll(items);
        probingItems.clear();
        probingItems.addAll(items);
        probePassed.clear();
        gracePosted = false;
        probingEpoch = epoch;
        StringBuilder names = new StringBuilder();
        for (Vod item : items) {
            tried.add(item.getName());
            names.append(item.getName()).append("（").append(item.getSiteName()).append("） ");
        }
        DebugLog.d("Binge", "并行验货 " + items.size() + " 部：" + names);
        App.post(probeTimeoutTask, BingeSetting.PROBE_TIMEOUT);
        for (Vod item : items) {
            final int myEpoch = probingEpoch;
            PROBE.execute(() -> {
                boolean ok = false;
                try {
                    Result result = SiteApi.detailContent(item.getSiteKey(), item.getId());
                    Vod detail = result == null ? null : result.getVod();
                    String key = keyOf(item);
                    ok = playable(detail, item);
                    if (ok) {
                        applyProbeDetail(item, detail); // 验货时已问到站内详情，把播放列表/URL 参数带回搜索结果
                        probedDetails.put(key, result);
                    }
                } catch (Throwable ignored) {
                    ok = false; // 拿不到详情的一律当播不了，换一部比跳过去强
                }
                final boolean pass = ok;
                App.post(() -> onProbed(item, pass, myEpoch));
            });
        }
    }

    private void onProbed(Vod item, boolean ok, int myEpoch) {
        if (myEpoch != epoch || !probing || !removeProbing(item)) return; // 这一轮已经作废、或不是这一批的
        BingeProfile.siteMark(item.getSiteKey(), ok); // 这个站到底能不能拉出能播的详情，攒着给后面排序用
        if (!ok) {
            DebugLog.d("Binge", "验货没过，不切：" + item.getName());
            if (!probingItems.isEmpty()) return; // 同批还有在飞的，等它们
            Vod best = bestPassed(); // 同一批里可能还有别的验过了（在等排最前那个时先出结果）
            endProbe();
            if (best == null) next(); // 一批全军覆没才换路子
            else switchTo(best);
            return;
        }
        probePassed.add(item);
        if (item == probeBatch.get(0)) { // 排最前的那个验过了：立刻切它，不必再等后面的
            endProbe();
            switchTo(item);
            return;
        }
        if (probingItems.isEmpty()) { // 都验完了：这批里挑排最前的那个
            Vod best = bestPassed();
            endProbe();
            switchTo(best);
            return;
        }
        // 排后面的先验过了：给前面的半秒机会，半秒内前面的也验过就切前面的
        if (!gracePosted) {
            gracePosted = true;
            App.post(probeGraceTask, PROBE_GRACE);
        }
    }

    /** 半秒到了：不管排前面的验完没有，切这批里已经验过的、排最前的那个 */
    private void onProbeGrace() {
        gracePosted = false;
        if (!probing || probePassed.isEmpty()) return;
        Vod item = bestPassed();
        endProbe();
        switchTo(item);
    }

    private void onProbeTimeout() {
        if (!probing) return;
        if (!probePassed.isEmpty()) { // 有验过的就切它，别白等
            Vod item = bestPassed();
            endProbe();
            DebugLog.d("Binge", "验货超时，切已经验过的 " + item.getName());
            switchTo(item);
            return;
        }
        endProbe();
        DebugLog.d("Binge", "验货超时，一部没验过，换一批");
        next();
    }

    /** 这批里已经验过的、排最前的那个 */
    private Vod bestPassed() {
        for (Vod item : probeBatch) if (probePassed.contains(item)) return item;
        return probePassed.isEmpty() ? null : probePassed.get(0);
    }

    private boolean removeProbing(Vod item) {
        for (int i = 0; i < probingItems.size(); i++) {
            if (probingItems.get(i) == item) {
                probingItems.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 一批验货收摊：在飞的作废，迟到的结果一律不认。
     * ⚠️ probeBatch / probePassed 留着别清：收摊后还要挑「这批里排最前的那个」，
     * 下一次 probe 开头本来就会重新清一遍。
     */
    private void endProbe() {
        probing = false;
        probingItems.clear();
        gracePosted = false;
        App.removeCallbacks(probeTimeoutTask, probeGraceTask);
    }

    /** 验过了：预加载模式下先存着，等真播完再接；否则直接切过去 */
    private void switchTo(Vod item) {
        if (item == null) {
            next();
            return;
        }
        recordPick(item.getName());
        if (preparing) { // 快播完提前搜的：存起来，onNoNext 直接用
            pending = item;
            active = false;
            preparing = false;
            App.removeCallbacks(timeoutTask);
            DebugLog.d("Binge", "下一部已备好 " + item.getName() + " 来自 " + item.getSiteName());
            return;
        }
        DebugLog.d("Binge", "接下一部 " + item.getName() + " 来自 " + item.getSiteName());
        host.showBingeNext(item);
        serial++;
        waitingDetail = true;
        active = false;
        App.removeCallbacks(timeoutTask);
        Result detail = probedDetails.remove(keyOf(item));
        if (detail != null && !detail.getList().isEmpty()) {
            DebugLog.d("Binge", "接下一部（复用验货详情，免二次问站）：" + item.getName());
            controller.selectSource(item, detail);
        } else controller.selectSource(item);
    }

    private static String keyOf(Vod item) {
        return item.getSiteKey() + "|" + item.getId();
    }

    /** 记下刚挑中的这部属于哪个题材族：连着同一族就打散，别让用户连吃三部一模一样的 */
    private void recordPick(String name) {
        List<String> tags = BingeTags.tagsOf(name);
        int family = BingeTags.pickFamily(tags, null);
        if (family < 0) return;
        pickedFamilies.remove((Integer) family);
        pickedFamilies.add(0, family);
        while (pickedFamilies.size() > 3) pickedFamilies.remove(pickedFamilies.size() - 1);
    }

    /**
     * 当前这一路的产地基准：字段里没有（刷的这部判不出国籍）就拿「剧名 + 分类」再推一次。
     * 以前判不出就无条件放行，外剧的中文译名就是这么混进来的（2026-10-24）。
     */
    private String regionBase() {
        if (!region.isEmpty()) return region;
        String guess = BingeTags.regionOf(host.getVodName() + typeName);
        return guess == null ? "" : guess;
    }

    /** 详情到手：集数够不够一部接一部刷；只有一集的话，得是整部压成合集那种才算数 */
    /**
     * 验货那趟 detailContent 已经把这一部在站里的完整详情（含播放列表和视频源参数）取回来了。
     * 回填给搜索结果后再切过去，避免只带着列表里的 id 二次问站点、拿不到 URL 参数而一直报「无资源」
     * （马先生 2026-11-09：《持械入宋》第 10 季接第 11 季，全站搜得到却播不了）。
     */
    private static void applyProbeDetail(Vod item, Vod detail) {
        if (item == null || detail == null) return;
        if (detail.getFlags() != null && !detail.getFlags().isEmpty()) item.setFlags(detail.getFlags());
        // 不改 id：列表里的 id 本来就是 detailContent 用的那个，换了反而可能让切过去后的二次请求对不上
    }

    private boolean playable(Vod detail, Vod item) {
        if (detail == null || item == null) return false;
        String name = item.getName();
        if (BingeTags.isBanned(name)) return false;
        // 系列的下一季：只要有播放列表就接上，剧种/分类/集数门槛一概不挑（同上，续季常被误判）
        if (serialMode && isNextSerial(name) && hasEpisodes(detail)) return true;
        // 单部作品（电影）以及它降级后推荐的同类：本来就只有一个文件，能播就算接上了。
        // ⚠️ 降级推荐里那些拿题材词搜回来的普通同类，还是得照剧种/产地过一遍，
        // 否则刷着国产古装剧忽然给你接一部别的剧种的（这条口子以前开得太大）
        if (singlePart && hasEpisodes(detail)) {
            if (similarFallback && !laterPart(name) && !matchAi(name)) {
                if (BingeTags.kindMismatch(name, kind)) return false;
                if (!BingeTags.sameRegion(regionBase(), String.valueOf(detail.getArea()) + detail.getTypeName() + name)) return false;
            }
            return true;
        }
        // 验货也看剧种：刷正剧撞上带短剧/漫剧字样的、刷短剧撞上详情写着电视剧/国产剧的，都别切过去
        if (BingeTags.kindMismatch(name, kind)) return false;
        if (kind != BingeTags.KIND_LONG && BingeTags.hasLongMark(detail.getTypeName())) return false;
        if (kind == BingeTags.KIND_LONG && BingeTags.isComic(detail.getTypeName())) return false;
        // 详情里把分类写明白了的，按分类再判一次剧种。只在站点确实分过剧种时才认——
        // 这站压根没"短剧"分类、什么都往"国产剧"里塞，那就不能拿它的分类当证据，不然整站都刷不动
        if (kind != BingeTags.KIND_UNKNOWN && BingeTypes.hasKind(item.getSiteKey(), kind)) {
            int detailKind = BingeTypes.kindOfType(detail.getTypeName());
            if (detailKind != BingeTags.KIND_UNKNOWN && detailKind != kind) return false;
        }
        // 刷漫剧：这站确实分过漫剧这一类时，候选必须也落在漫剧类，看不出剧种的也不要——
        // 2026-10-24 马先生反馈「看国产 AI 漫剧，下一步推出韩剧」，就是分类写着「韩剧」那种漏过来的
        if (kind == BingeTags.KIND_COMIC && BingeTypes.hasKind(item.getSiteKey(), BingeTags.KIND_COMIC)
                && BingeTypes.kindOfType(detail.getTypeName()) != BingeTags.KIND_COMIC) return false;
        String info = String.valueOf(detail.getArea()) + detail.getTypeName() + name;
        if (!BingeTags.sameRegion(regionBase(), info)) return false; // 详情里写明是别国的剧，别跳过去
        List<Flag> flags = detail.getFlags();
        int max = 0;
        if (flags != null) for (Flag flag : flags) {
            if (flag != null && flag.getEpisodes() != null) max = Math.max(max, flag.getEpisodes().size());
        }
        if (max >= BingeTags.MIN_PLAYABLE) return true;
        if (max <= 0) return false; // 连一条播放列表都没有，切过去也是白切
        return BingeTags.hasCompileMark(name) || BingeTags.parseEpisodes(item.getRemarks()) >= BingeTags.MIN_PLAYABLE || BingeTags.isShortMark(name);
    }

    /** 详情里有没有至少一条能播的列表：系列续季只要这个就够了 */
    private boolean hasEpisodes(Vod detail) {
        List<Flag> flags = detail == null ? null : detail.getFlags();
        if (flags == null) return false;
        for (Flag flag : flags) if (flag != null && flag.getEpisodes() != null && !flag.getEpisodes().isEmpty()) return true;
        return false;
    }

    /** 换过去的详情回来了：外剧、电影/单集这类接不下去的，换下一部 */
    void onDetailLoaded(Vod item) {
        if (item != null) {
            String type = item.getTypeName();
            if (type != null && !type.isEmpty()) typeName = type; // 站点给的分类，搜同类用得上
            String cast = item.getActor();
            if (cast != null && !cast.isEmpty()) { // 主演记下来，下一部可以按人找（红果的「同主演推荐」）
                List<String> names = Star.split(cast);
                actor = names.isEmpty() ? "" : names.get(0);
            }
            kind = kindOf(item); // 切过去了：新这部说了算，后面照它的剧种刷
        }
        prewarmNext(); // 刚切过来就替「再下一部」问模型，等这部播完答案已经备好了
        if (!waitingDetail) return;
        waitingDetail = false;
        if (item != null) {
            String info = String.valueOf(item.getArea()) + item.getTypeName() + item.getName();
            if (!BingeTags.sameRegion(regionBase(), info)) { // 详情里写明是别的国籍，换下一部
                DebugLog.d("Binge", "跳过 " + item.getName() + "，产地不符（当前" + region + "）");
                tried.add(item.getName());
                BingeProfile.reject(item.getName()); // 记一笔：这部推过去接不上，攒够两次就不再推
                BingeProfile.siteFail(state.getDetailKey());
                dropHistory(item.getName()); // 别在历史里留一部压根没看过的剧
                next();
                return;
            }
            String next = BingeTags.regionOf(info); // 站里给了准确地区就拿来当新基准
            if (!next.isEmpty()) region = next;
        }
        if (state.hasFlags() && state.getFlag().getEpisodes().size() >= BingeTags.MIN_PLAYABLE) {
            failures = 0; // 接上了，失败计数清零，重新计连续失败
            return;
        }
        if (state.hasFlags() && isCompiled()) {
            failures = 0; // 只有一集但那是整部压成的合集，也算接上了
            return;
        }
        if (singlePart && state.hasFlags() && state.getFlag().getEpisodes().size() <= 1) {
            failures = 0; // 单部作品（电影）本来就只有一个文件，别当成「接不下去」又换一部
            return;
        }
        tried.add(host.getVodName());
        BingeProfile.reject(host.getVodName()); // 切过去才发现接不下去：记一笔，别下次又推它
        BingeProfile.siteFail(state.getDetailKey());
        dropHistory(host.getVodName()); // 接不下去的这部擦掉，历史里只留真看过的
        next();
    }

    /**
     * 兜底：切过去才发现接不下去（跟验货时看到的不一样），把刚写进历史的那条擦掉。
     * 只擦这一部自己的一条，而且它本来就不该在历史里——filter 早把看过的排除掉了。
     */
    private void dropHistory(String name) {
        try {
            List<History> items = History.findByName(name);
            if (items == null || items.isEmpty()) return;
            for (History item : items) {
                int cid = item.getCid();
                String key = item.getKey();
                if (key == null || key.isEmpty()) continue;
                Task.executeSerial(() -> {
                    try {
                        AppDatabase.get().getHistoryDao().delete(cid, key);
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前这部是不是「整部短剧压成一个视频」的合集版：本地判得准就本地定，拿不准看大模型有没有结论 */
    private boolean isCompiled() {
        String name = host.getVodName();
        int episodes = state.getFlag().getEpisodes().size();
        long duration = state.getHistory() == null ? 0 : state.getHistory().getDuration();
        int verdict = BingeTags.compileOf(name, "", episodes, duration);
        if (verdict == BingeTags.COMPILE_YES) return true;
        if (verdict == BingeTags.COMPILE_ASK) {
            Boolean hit = AiJudge.peek(BingeTags.compileKey(name, "", episodes, duration));
            if (hit != null) return hit; // 模型给过结论就按模型的来
            return BingeTags.compileFallback(name, "", episodes, duration); // 模型没结论/不可用，本地评分兜底
        }
        return false;
    }

    /** 这一部接不下去，接着找下一部 */
    private void next() {
        if (++failures >= BingeSetting.MAX_FAILURE) {
            end();
            return;
        }
        active = true;
        if (!pool.isEmpty()) pickNow();
        else if (keywordIndex >= keywords.size()) { advance(); if (keywordIndex < keywords.size()) search(); else if (!waitingAi) end(); }
        else search();
    }

    private void onTimeout() {
        if (!active || host.isHostFinishing()) return;
        if (keywordIndex >= keywords.size()) advance();
        if (keywordIndex >= keywords.size()) { if (!waitingAi) end(); return; }
        search();
    }

    private void end() {
        DebugLog.d("Binge", "这一轮搜到底了，没有更多候选");
        active = false;
        endProbe();
        waitingAi = false;
        clearTimers();
        if (preparing) { // 只是提前备货没备到，别打扰用户，播完时会重新搜
            preparing = false;
            return;
        }
        if (fallbackSimilar()) return; // 单部作品接不上续作：改推同类作品，别撂挑子
        if (fallbackTaste()) return; // 没有下一季了：按口味问大模型要同类，别撂挑子
        if (fallbackGenre()) return; // 模型没给/关着：照题材词改刷同类，别撂挑子
        host.onBingeEnd(serialMode);
    }

    /**
     * 单部作品（电影）的退路：这一部看完，本地那几种部号写法都搜空了 —— 说明它没有下一部了
     * （《杀死比尔》只有一、二两部），那就让大模型推荐几部同类型的接着看（马先生 2026-10-31）。
     * 那一问在 begin 时就发出去了，多半已经回来了；还没回来就再等它一小会儿。
     * 模型不可用老实收尾：与其推一堆不相关的，不如停在这儿。
     */
    private boolean fallbackSimilar() {
        if (!singlePart || similarFallback || !serialMode) return false;
        similarFallback = true;
        serialMode = false;
        searchHome = false;
        keywordIndex = 0;
        failures = 0;
        active = true;
        keywords = new ArrayList<>(); // 只拿模型给的名字去搜，别再用主标题瞎撞
        DebugLog.d("Binge", "《" + seriesMain + "》没有下一部了，改按大模型推荐找同类作品");
        if (similarAsked) useSimilar(); // 答案早到了
        else if (AiSetting.ready() && similarNext == null) {
            waitingAi = true;
            App.post(aiTimeoutTask, MOVIE_AI_GRACE);
        } else return false; // 模型关着，没的可推
        return true;
    }

    /**
     * 没有下一季时的退路（马先生 2026-11-01）：本地几种季号写法都搜空了、大模型也说这个系列到此为止
     * —— 那就**再问一句大模型**：参照这部（动漫 / AI 漫剧 / 短剧），优先同类型的，
     * 或者包含某个主演、他关注过的演员的作品；问的时候把口味一起给它。
     * 口味是从播放记录、影视收藏、关注的演员里攒的（{@link BingeTaste}）：
     * 收藏里压根没有爱情剧、情感类、剧情类的，那些类别就别往人家眼前推。
     * 模型报的是具体剧名，搜到就接上；模型不给力再退回 {@link #fallbackGenre()} 按题材词刷同类。
     * 只放一次机会。
     */
    private boolean fallbackTaste() {
        if (!serialMarked || tasteFallback) return false;
        if (!AiSetting.ready()) return false; // 模型关着就没这一问，老实走题材词那条路
        tasteFallback = true;
        serialMode = false;
        searchHome = false;
        keywordIndex = 0;
        failures = 0;
        active = true;
        keywords = new ArrayList<>(); // 只拿模型给的名字去搜，别再拿主标题瞎撞
        final int myEpoch = epoch;
        BingeTaste.Snap taste = BingeTaste.peek();
        DebugLog.d("Binge", "《" + seriesMain + "》没有下一季了，按口味问大模型要同类（常看" + taste.liked + " 不碰" + taste.avoided + " 演员" + taste.stars + "）");
        waitingAi = true;
        App.post(aiTimeoutTask, TASTE_AI_GRACE);
        AiJudge.recommend(host.getVodName(), typeName, region, BingeTags.kindWord(kind), actor, taste.liked, taste.avoided, taste.stars, got -> {
            if (myEpoch != epoch) return; // 这一轮已经作废
            if (waitingAi) {
                App.removeCallbacks(aiTimeoutTask);
                waitingAi = false;
            }
            if (!isHandling()) return;
            if (got == null || got.useless()) { // 模型没给能用的：交给题材词那条路
                DebugLog.d("Binge", "大模型没按口味给出同类，退回题材词刷同类");
                end();
                return;
            }
            DebugLog.d("Binge", "按口味推荐去找同类：" + got.titles);
            if (addNext(got, true)) search();
            else end();
        });
        return true;
    }

    /**
     * 系列剧的退路：这一季看完、又确实没有下一季（本地四种写法都没搜到、大模型也说没有了），
     * 那就跟普通刷剧一样接着刷同题材的其它剧 —— 以前是直接收手，用户看到的就是「第九季看完就没了」。
     * 只放一次机会：同类再刷不动就老实收尾。
     */
    private boolean fallbackGenre() {
        // ⚠️ 认 serialMarked 不认 serialMode：单部的降级那一步会把 serialMode 清成 false，
        // 照它判断的话这条「改刷同类」的退路就永远走不到了 —— 用户看到的就是播完彻底停住
        if (!serialMarked || genreFallback) return false;
        genreFallback = true;
        serialMode = false;
        searchHome = false;
        keywords = buildKeywords(host.getVodName());
        keywordIndex = 0;
        failures = 0;
        if (keywords.isEmpty()) return false;
        DebugLog.d("Binge", "系列「" + seriesMain + "」接不上下一季，改刷同类：" + BingeTags.kindWord(kind));
        active = true;
        if (kind != BingeTags.KIND_UNKNOWN) searchByType(); // 分类池这时候用得上，端一盘正经短剧出来
        search();
        return true;
    }

    void reset() {
        clearTimers();
        pool.clear();
        tried.clear();
        fromType.clear();
        aiTitles.clear();
        typePending = false;
        keywords.clear();
        recentFamilies.clear();
        region = "";
        seriesMain = "";
        seriesIndex = -1;
        serialMode = false;
        serialMarked = false;
        typeName = "";
        actor = "";
        kind = BingeTags.KIND_UNKNOWN;
        keywordIndex = 0;
        serial = 0;
        failures = 0;
        homeKey = "";
        searchHome = false;
        aiAnswered = false;
        waitingAi = false;
        similarAsked = false;
        similarNext = null;
        similarFallback = false;
        seriesHasNext = null;
        genreFallback = false;
        tasteFallback = false;
        currentTags = null;
        singlePart = false;
        active = false;
        waitingDetail = false;
        preparing = false;
        pending = null;
        endProbe();
        pickedFamilies.clear();
        epoch++; // 上一轮没回来的验货结果一律作废
    }

    /** 到点的定时任务一并撤掉 */
    private void clearTimers() {
        App.removeCallbacks(timeoutTask, pickTask, probeTimeoutTask, probeGraceTask, aiTimeoutTask);
    }
}
