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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final Random random = new Random();
    private final List<Integer> recentFamilies = new ArrayList<>(); // 最近几部刷过的题材族（新→旧），防一路死磕一族
    private final Set<String> fromType = new HashSet<>(); // 从站点「短剧/漫剧/正剧」分类里捞出来的剧名，剧种有保障
    private volatile boolean typePending; // 分类池子正在路上
    private long typeWaitUntil; // 最多等到这个时候，过了就不再为它等

    private List<String> keywords = new ArrayList<>();
    private String seriesMain = ""; // 当前这部所属的系列：主标题（去掉「第X季」这类后缀）
    private int seriesIndex = -1; // 当前是系列里的第几季；名字里没写就是 -1
    private boolean serialMode; // 名字里明写着第几季：只认这个系列的下一季，绝不换别的剧
    private String region = "";
    private String typeName = ""; // 站点给的分类（如「爱情/都市」），搜同类用得上
    private String actor = ""; // 当前这部的主演（红果那招：看《A》也推同主演的《B》）
    private int kind = BingeTags.KIND_UNKNOWN; // 剧种：一路只刷同一种，不串
    private int keywordIndex;
    private int serial;
    private int failures;
    private boolean active;
    private boolean waitingDetail; // 已选中一部、正在等详情回来校验
    private boolean preparing; // 预加载中：搜索照旧跑，但 pick 只备货不切换
    private Vod pending; // 提前备好的下一部，真播完直接接上

    /** 切过去之前先验货：后台拉一次详情，确认真能播再切，播不了的当场丢掉，不切、不留历史 */
    private static final ExecutorService PROBE = Executors.newSingleThreadExecutor();
    /** 按站点分类拉池子用的：跟验货分开跑，别互相堵着 */
    private static final ExecutorService CATEGORY = Executors.newSingleThreadExecutor();
    /** 分类池子已经在路上时，最多再等这么久再挑——等它一到，挑中的大概率就是正经短剧 */
    private static final long TYPE_GRACE = 1200L;
    /** 等分类池子的总上限：站点分类表头一回拉可能要好几秒，不能让用户一直干等着 */
    private static final long TYPE_WAIT_MAX = 3000L;
    /** 从「短剧分类」里捞出来的候选加这么多分：站点自己分的类，比名字猜靠谱得多 */
    private static final int TYPE_BONUS = 8;
    private int epoch; // 每次作废重来（interrupt/reset/forceNext）都 +1，迟到的异步回调据此自行作废
    private boolean probing; // 正在后台验货
    private Vod probingItem; // 正在验的这一部
    private int probingEpoch;

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
        probing = false; // 验货也停，回来的结果一律作废
        probingItem = null;
        epoch++;
        App.removeCallbacks(timeoutTask, pickTask, probeTimeoutTask);
    }

    /** 没有下一集了：刷剧开着的又是短剧，就去找下一部；返回 true 表示接管了 */
    boolean onNoNext(boolean reversed) {
        if (reversed || !BingeSetting.isEnabled()) return false;
        if (AiGuard.blocked(host.getVodName())) return false; // 碰线的剧不联想、不搜索
        // 正剧不按「同题材换一部」刷，但名字里明写着第几季的要接管：找这个系列的下一季
        if (!isShortDrama() && !BingeTags.hasSerialMark(host.getVodName())) return false;
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
        if (BingeTags.hasSerialMark(host.getVodName())) return true; // 明写着「第X季」：一季接一季往下看，不换别的剧
        if (!state.hasFlags()) return false;
        int episodes = state.getFlag().getEpisodes().size();
        long duration = state.getHistory() == null ? 0 : state.getHistory().getDuration();
        String name = host.getVodName();
        if (episodes >= BingeTags.MIN_PLAYABLE) return BingeTags.canBinge(episodes, duration, name);
        // 集数很少，看看是不是整部短剧压成一个视频的合集版
        int verdict = BingeTags.compileOf(name, "", episodes, duration);
        if (verdict == BingeTags.COMPILE_YES) return true;
        if (verdict == BingeTags.COMPILE_ASK) {
            String key = BingeTags.compileKey(name, "", episodes, duration);
            Boolean hit = AiJudge.peek(key);
            if (hit != null) return hit; // 模型给过结论就按模型的来
            if (AiSetting.isEnabled()) AiJudge.ask(key, name, "", episodes, duration, host.getVodMark(), null); // 先问着，播完就有结论了
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
        App.removeCallbacks(timeoutTask, pickTask, probeTimeoutTask);
        pool.clear();
        active = false;
        waitingDetail = false;
        preparing = false;
        pending = null;
        probing = false;
        probingItem = null;
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
        seriesMain = BingeTags.serialMainOf(host.getVodName());
        seriesIndex = BingeTags.serialOf(host.getVodName());
        serialMode = seriesIndex > 0 && !seriesMain.isEmpty();
        keywords = serialMode ? serialKeywords() : buildKeywords(host.getVodName());
        DebugLog.d("Binge", "开始刷剧，系列=" + (serialMode ? seriesMain + " 第" + seriesIndex + "季" : "无") + "，产地=" + region + "，剧种=" + BingeTags.kindWord(kind) + "，关键词=" + keywords + "，当前=" + host.getVodName());
        active = true;
        keywordIndex = 0;
        tried.add(host.getVodName());
        typePending = false; // 上一轮没回来的分类池别再挡着这一轮
        if (!serialMode) searchByType(); // 先开一路：直接去站点的「短剧」分类里端一盘出来，比关键词搜索准（系列剧不用，它只认下一季）
        search();
    }

    /**
     * 系列剧的搜索词：只搜这个系列本身 —— 主标题，外加「主标题 第X季」（有些站按季分条目，直搜下一季更准）。
     * 季号两种写法都带上：**中文「第三季」和阿拉伯「第3季」都要搜**，站源只认其中一种的很常见，
     * 少了哪一半都可能搜不到下一季。不掺题材词、不掺「短剧/电视剧」这类兜底词：搜出来再靠 filter 挑掉不相关的，纯属浪费一次搜索。
     */
    private List<String> serialKeywords() {
        List<String> keys = new ArrayList<>();
        int next = seriesIndex + 1;
        keys.add(seriesMain); // 主标题：把整个系列都搜出来，靠 filter 挑季号
        keys.add(seriesMain + " 第" + next + "季"); // 阿拉伯写法：「庆余年 第3季」
        keys.add(seriesMain + " 第" + BingeTags.cnNumber(next) + "季"); // 中文写法：「庆余年 第三季」
        keys.add(seriesMain + next); // 站源偷懒写成「庆余年3」的，连着写才搜得到
        return keys;
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
        onSearchResult(result);
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
            } else if (AiSetting.isEnabled()) {
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
            end();
            return;
        }
        host.requestBingeSearch(sites(), keywords.get(keywordIndex++));
        App.post(timeoutTask, BingeSetting.SEARCH_TIMEOUT);
    }

    private List<Site> sites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (site.isSearchable()) sites.add(site);
        return sites;
    }

    /** 搜索是逐站回来的，每次都往池子里补，攒一小会儿再挑最好的 */
    void onSearchResult(Result result) {
        if (!active || result == null) return;
        List<Vod> items = filter(result.getList());
        if (items.isEmpty()) return;
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
            // 系列剧只接下一季：别的剧、当季、更早的季一律不要，别的门槛也不再卡它
            // （续季常被站点分到跟本部不一样的类里，末季集数又少，拿剧种/集数去卡反而接不上）
            if (serialMode) {
                if (!isNextSerial(name)) continue;
                if (watched(name)) continue;
                items.add(item);
                continue;
            }
            if (BingeTags.kindMismatch(name, kind)) continue; // 别串剧种：刷短剧不跳正剧，刷正剧不跳短剧
            if (wrongType(item)) continue; // 站点自己标了分类、又明摆着是别的剧种，一样丢掉
            if (!BingeTags.sameRegion(region, name)) continue; // 别跳到别的国籍去
            int episodes = BingeTags.parseEpisodes(item.getRemarks());
            if (episodes > 0 && episodes < BingeTags.MIN_PLAYABLE) {
                // 集数很少，可能是整部压成一个视频的合集版：是就收，拿不准的先问大模型
                int verdict = BingeTags.compileOf(name, item.getRemarks(), episodes, 0);
                if (verdict == BingeTags.COMPILE_NO) continue;
                if (verdict == BingeTags.COMPILE_ASK && !ask(item, episodes) && !BingeTags.compileFallback(name, item.getRemarks(), episodes, 0)) continue;
            }
            if (watched(name)) continue;
            items.add(item);
        }
        return items;
    }

    /**
     * 系列模式下这个候选能不能接：得是同一个系列，而且必须是更后面的季。
     * ⚠️ 只往后不回头：看的是第二季就只接第三季往后，绝不能跳回第一季（马先生 2026-10-15）。
     * 季号中文「第三季」、阿拉伯「第3季」、英文「S3」、罗马「Ⅲ」写法都由 BingeTags.serialOf 统一成数字再比，
     * 不管站源怎么混写都不会比错。
     * 同名却没写第几季的不要 —— 那多半是「整部合集」或就是当前这部，接过去等于从头再看一遍。
     */
    private boolean isNextSerial(String name) {
        if (!BingeTags.sameSerial(seriesMain, name)) return false;
        return BingeTags.serialOf(name) > seriesIndex; // 写明了第几季，且比现在这一季靠后
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
        if (!AiSetting.isEnabled()) return false;
        String name = item.getName();
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

    private boolean watched(String name) {
        try {
            return !History.findByName(name).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 挑一部最像的出来验货，验过了才切；预加载模式下验过了先存着，等真播完再接 */
    private void pickNow() {
        if (!active || probing || pool.isEmpty()) return; // 已经在验一部了，不许再挑，防止双切
        final int penalty = dynamicPenalty();
        pool.sort((a, b) -> Integer.compare(scoreOf(b, penalty), scoreOf(a, penalty)));
        Vod item = pool.remove(0);
        tried.add(item.getName());
        probe(item);
    }

    /** 候选总分：系列续季 + 关键词命中 + 剧种对不对 + 是不是从站点的分类池里捞的（这个最可信，加成最大） */
    private int scoreOf(Vod item, int penalty) {
        int score = BingeTags.score(item.getName(), item.getRemarks(), keywords, penalty);
        score += BingeTags.kindScore(item.getName(), kind);
        score += serialScore(item.getName());
        if (fromType.contains(item.getName())) score += TYPE_BONUS;
        return score;
    }

    /**
     * 同系列续季顶到最前面：主标题对得上、季数正好是下一季 → 压倒性加分；
     * 跳着来的（搜出第四季而现在是第二季）也认，但往后排，一季一季来更合理；
     * 当季和更早的季重罚（filter 已经挡过了，这是双保险）。
     * 当前这部没写季号时（比如就叫《庆余年》）把「庆余年 第二季」当第一季的下一部，一样顶上来。
     */
    private int serialScore(String name) {
        if (seriesMain.isEmpty() || !BingeTags.sameSerial(seriesMain, name)) return 0;
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
    private void probe(Vod item) {
        probing = true;
        probingItem = item;
        probingEpoch = epoch;
        App.post(probeTimeoutTask, BingeSetting.PROBE_TIMEOUT);
        PROBE.execute(() -> {
            boolean ok = false;
            try {
                Result result = SiteApi.detailContent(item.getSiteKey(), item.getId());
                ok = playable(result == null ? null : result.getVod(), item);
            } catch (Throwable ignored) {
                ok = false; // 拿不到详情的一律当播不了，换一部比跳过去强
            }
            final boolean pass = ok;
            App.post(() -> onProbed(item, pass, probingEpoch));
        });
    }

    private void onProbed(Vod item, boolean ok, int myEpoch) {
        App.removeCallbacks(probeTimeoutTask);
        if (myEpoch != epoch || !probing || probingItem != item) return; // 这一轮已经作废或换了候选
        probing = false;
        probingItem = null;
        if (ok) {
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
            controller.selectSource(item);
            return;
        }
        DebugLog.d("Binge", "验货没过，不切，换一部：" + item.getName());
        next();
    }

    private void onProbeTimeout() {
        if (!probing) return;
        Vod item = probingItem;
        probing = false;
        probingItem = null;
        if (item == null) return;
        DebugLog.d("Binge", "验货超时，不切，换一部：" + item.getName());
        next();
    }

    /** 详情到手：集数够不够一部接一部刷；只有一集的话，得是整部压成合集那种才算数 */
    private boolean playable(Vod detail, Vod item) {
        if (detail == null || item == null) return false;
        String name = item.getName();
        if (BingeTags.isBanned(name)) return false;
        // 系列的下一季：只要有播放列表就接上，剧种/分类/集数门槛一概不挑（同上，续季常被误判）
        if (serialMode && isNextSerial(name) && hasEpisodes(detail)) return true;
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
        String info = String.valueOf(detail.getArea()) + detail.getTypeName() + name;
        if (!BingeTags.sameRegion(region, info)) return false; // 详情里写明是别国的剧，别跳过去
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
        if (!waitingDetail) return;
        waitingDetail = false;
        if (item != null) {
            String info = String.valueOf(item.getArea()) + item.getTypeName() + item.getName();
            if (!BingeTags.sameRegion(region, info)) { // 详情里写明是别的国籍，换下一部
                DebugLog.d("Binge", "跳过 " + item.getName() + "，产地不符（当前" + region + "）");
                tried.add(item.getName());
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
        tried.add(host.getVodName());
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
        else if (keywordIndex >= keywords.size()) end();
        else search();
    }

    private void onTimeout() {
        if (!active || host.isHostFinishing()) return;
        if (keywordIndex >= keywords.size()) end();
        else search();
    }

    private void end() {
        DebugLog.d("Binge", "刷剧结束，没有更多候选");
        active = false;
        probing = false;
        probingItem = null;
        App.removeCallbacks(timeoutTask, pickTask, probeTimeoutTask);
        if (preparing) { // 只是提前备货没备到，别打扰用户，播完时会重新搜
            preparing = false;
            return;
        }
        host.onBingeEnd(serialMode);
    }

    void reset() {
        App.removeCallbacks(timeoutTask, pickTask, probeTimeoutTask);
        pool.clear();
        tried.clear();
        fromType.clear();
        typePending = false;
        keywords.clear();
        recentFamilies.clear();
        region = "";
        seriesMain = "";
        seriesIndex = -1;
        serialMode = false;
        typeName = "";
        actor = "";
        kind = BingeTags.KIND_UNKNOWN;
        keywordIndex = 0;
        serial = 0;
        failures = 0;
        active = false;
        waitingDetail = false;
        preparing = false;
        pending = null;
        probing = false;
        probingItem = null;
        epoch++; // 上一轮没回来的验货结果一律作废
    }
}
