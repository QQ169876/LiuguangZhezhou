package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.DebugLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点的「分类」才是判剧种最硬的证据。
 * <p>
 * 之前我们靠剧名猜：名字带"短剧"就算短剧，再拿单集时长、集数补一刀。问题是多数站源既不给时长，
 * 分类字段也经常空着，搜出来的结果里正剧混着短剧，只能凭名字排除——漏网的就切到正剧去了，
 * 这就是"刷着短剧忽然跳一部 45 分钟正剧"的根子。
 * <p>
 * 参照 LunaTV 短剧那套（src/lib/shortdrama.server.ts、api/shortdrama/*）：它根本不猜，而是
 * ① 拉站点的分类表（苹果 CMS 的 `?ac=list`）② 找出「短剧」这一类（还有女频恋爱、反转爽剧这些子标签）
 * ③ 按分类 ID 把整类拉下来当池子（`?ac=detail&t=分类ID`）④ 搜索回来的结果也按分类 ID 过一遍，
 * 不在短剧分类里的一律丢掉。**站点自己分的类，比我们拿名字猜准得多。**
 * 我们这边接口是对得上的：`SiteApi.homeContent()` 拿分类表，`SiteApi.categoryContent()` 按分类 ID 拉列表。
 */
public class BingeTypes {

    /** 强证据：分类名里直接写着短剧那一挂 */
    private static final String[] SHORT_STRONG = {"短剧", "微剧", "微短剧", "竖屏", "小剧场", "迷你剧", "泡面番", "爽剧"};

    /**
     * 子标签：短剧站爱用的二级分类名（头六个是 LunaTV 在用的那组）。
     * 只有站点**没有**强证据分类时才顶上，而且不能沾上 NOT_SUB 那几个词——
     * 综合站也有"都市""古装仙侠"这种分类，那是正剧的分类，认错了就把正剧当短剧拉进来。
     */
    private static final String[] SHORT_SUB = {
            "女频恋爱", "女频", "男频", "反转爽剧", "古装仙侠", "年代穿越", "脑洞悬疑", "现代都市",
            "甜宠", "虐恋", "总裁", "重生", "穿越", "赘婿", "神豪", "逆袭"
    };

    /** 漫剧那一挂 */
    private static final String[] COMIC_MARKS = {"漫剧", "动态漫", "动态漫画", "沙雕漫", "条漫", "动漫", "二次元", "国漫", "日漫", "韩漫", "漫画"};

    /**
     * 正剧那一挂（产地分类也算：日剧/韩剧/美剧下头都是一集四五十分钟的）。
     * ⚠️ 不收「网剧」：**短剧站也爱把短剧归到"网剧"**，收了会把整类短剧当正剧排除掉。
     */
    private static final String[] LONG_MARKS = {"电视剧", "国产剧", "连续剧", "剧集", "剧情片", "港剧", "台剧", "日剧", "韩剧", "美剧", "泰剧", "海外剧"};

    /** 分类名里沾上这些，就不可能是短剧的子标签 */
    private static final String[] NOT_SUB = {"电视剧", "国产剧", "连续剧", "剧集", "电影", "综艺", "纪录", "戏曲", "少儿"};

    /** 分类表缓存多久：站源改了也能慢慢跟上，不至于一辈子用旧表 */
    private static final long CACHE_TTL = 30 * 60 * 1000L;
    /** 拉失败的站点冷落多久：网络抖动一下就永久拉黑，这一整天分类池都用不上了 */
    private static final long FAIL_TTL = 5 * 60 * 1000L;

    /** 站点分类表缓存：刷一部剧可能来回问好几次，别每次都去拉一遍 */
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    /** 拉失败的站点记一笔，别一遍遍重试拖慢刷剧 */
    private static final ConcurrentHashMap<String, Long> FAILED = new ConcurrentHashMap<>();

    private static class Entry {

        private final long time = System.currentTimeMillis();
        private final List<Class> types;

        Entry(List<Class> types) {
            this.types = types;
        }
    }

    /**
     * 拉站点分类表（在后台线程调）。拉到就缓存，拉不动就记下来不再试。
     * 有的站首页内容很大，解析要点时间，所以只干一次，之后刷剧都是零成本。
     */
    public static List<Class> load(Site site) {
        if (site == null) return null;
        String key = site.getKey();
        if (key == null || key.isEmpty()) return null;
        Entry hit = CACHE.get(key);
        if (hit != null) {
            if (System.currentTimeMillis() - hit.time < CACHE_TTL) return hit.types;
            CACHE.remove(key); // 放太久了，重新拉一次
        }
        Long failed = FAILED.get(key);
        if (failed != null && System.currentTimeMillis() - failed < FAIL_TTL) return null;
        try {
            List<Class> types = SiteApi.homeContent(site).getTypes();
            if (types == null || types.isEmpty()) {
                FAILED.put(key, System.currentTimeMillis());
                return null;
            }
            CACHE.put(key, new Entry(types));
            FAILED.remove(key);
            DebugLog.d("Binge", "拿到 " + site.getName() + " 的分类表，" + types.size() + " 个分类");
            return types;
        } catch (Throwable e) {
            FAILED.put(key, System.currentTimeMillis()); // 这会儿拉不动就先放一放，过几分钟再给它一次机会
            return null;
        }
    }

    /** 缓存里有这个站的分类表吗 */
    public static List<Class> cached(String key) {
        if (key == null) return null;
        Entry hit = CACHE.get(key);
        return hit == null ? null : hit.types;
    }

    /**
     * 这个站有没有「某一剧种」的专门分类。有，说明它分类分得细，我们才可以拿分类去筛结果；
     * 一个站压根没"短剧"这一类，它那"国产剧"里很可能就混着短剧，这时候硬筛会误杀。
     */
    public static boolean hasKind(String key, int kind) {
        List<Class> types = cached(key);
        if (types == null || kind == BingeTags.KIND_UNKNOWN) return false;
        for (Class item : types) {
            if (item == null) continue;
            if (kindOfType(item.getTypeName()) == kind) return true;
        }
        return false;
    }

    /**
     * 从这个站的分类里挑一个「指定剧种」的分类 ID，挑不到返回 null。
     * 同一剧种有多个分类时**随机挑**（别每次都拉同一类，刷几部就腻了），强证据优先于子标签。
     */
    public static String tidOf(String key, int kind, Random random) {
        List<Class> types = cached(key);
        if (types == null || kind == BingeTags.KIND_UNKNOWN) return null;
        List<String> strong = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        for (Class item : types) {
            if (item == null) continue;
            String id = item.getTypeId();
            String name = item.getTypeName();
            if (id == null || id.isEmpty() || name == null || name.isEmpty()) continue;
            if (kindOfType(name) != kind) continue;
            if (isStrong(name, kind)) strong.add(id);
            else weak.add(id);
        }
        List<String> pick = strong.isEmpty() ? weak : strong;
        if (pick.isEmpty()) return null;
        return pick.get(random.nextInt(pick.size()));
    }

    /** 分类名够不够硬：剧种直接写在名字上（"短剧""电视剧"），还是只是个题材子标签（"反转爽剧""都市"） */
    private static boolean isStrong(String name, int kind) {
        if (kind == BingeTags.KIND_SHORT) return contains(name, SHORT_STRONG);
        if (kind == BingeTags.KIND_LONG) return contains(name, LONG_MARKS);
        if (kind == BingeTags.KIND_COMIC) return contains(name, COMIC_MARKS);
        return false;
    }

    /**
     * 分类名属于哪种剧种：漫剧 → 强证据短剧 → 正剧 → 短剧子标签，都认不出返回 UNKNOWN。
     * 「动漫」这类先判漫剧，免得被后面的规则抢走。
     */
    public static int kindOfType(String name) {
        if (name == null || name.isEmpty()) return BingeTags.KIND_UNKNOWN;
        if (contains(name, COMIC_MARKS)) return BingeTags.KIND_COMIC;
        if (contains(name, SHORT_STRONG)) return BingeTags.KIND_SHORT;
        if (contains(name, LONG_MARKS)) return BingeTags.KIND_LONG;
        if (contains(name, SHORT_SUB) && !contains(name, NOT_SUB)) return BingeTags.KIND_SHORT;
        return BingeTags.KIND_UNKNOWN;
    }

    private static boolean contains(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    /** 站源换过了（改站点、换配置）：旧分类表作废，别拿昨天的表判今天的剧 */
    public static void clear() {
        CACHE.clear();
        FAILED.clear();
    }

    /** 调试用：看看这个站分了哪些类 */
    public static List<String> namesOf(String key) {
        List<Class> types = cached(key);
        if (types == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (Class item : types) if (item != null && item.getTypeName() != null) names.add(item.getTypeName());
        return names;
    }
}
