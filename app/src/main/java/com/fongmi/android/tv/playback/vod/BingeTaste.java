package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户口味：从**播放记录 + 影视收藏 + 关注的演员**里攒出来的「爱看什么 / 不碰什么」。
 *
 * 马先生 2026-11-01 的原话：收藏列表里不可能有爱情剧、情感类、剧情类 —— 这些没出现过的类别
 * 本身就是很强的负信号，推荐的时候要避开。所以这里不光统计「看过什么」，
 * 还把**收藏和历史里一次都没出现过的题材族**挑出来当「几乎不碰」交给大模型和本地筛子。
 *
 * 只读不写，查库一律在后台跑（Room 不许在主线程翻），结果缓存 10 分钟：
 * 刷剧一路换好几部，没必要每一部都去翻一遍库。
 */
public class BingeTaste {

    /** 缓存多久：刷剧一轮里可能连换好几部，没必要每换一部就翻一次库 */
    private static final long TTL = 10 * 60 * 1000L;
    /** 常看的题材词最多报几个 */
    private static final int LIKED_MAX = 8;
    /** 几乎不碰的题材族最多报几个 */
    private static final int AVOID_MAX = 6;
    /** 给模型看的「最近看过」最多几部 */
    private static final int TITLE_MAX = 12;
    /** 关注的演员最多报几个 */
    private static final int STAR_MAX = 5;
    /** 题材词至少出现过几次才算「常看」：只出现一次可能是手滑点进去的 */
    private static final int LIKED_MIN = 2;

    private static volatile Snap snap;
    private static volatile long stamp;
    private static volatile boolean loading;

    /** 一份口味快照：全是只读的普通列表，随便传 */
    public static final class Snap {
        public final List<String> liked; // 常看的题材词（次数多的在前）
        public final List<String> avoided; // 几乎不碰的题材族代表词
        public final List<String> titles; // 最近看过的剧名
        public final List<String> stars; // 关注的演员

        Snap(List<String> liked, List<String> avoided, List<String> titles, List<String> stars) {
            this.liked = liked;
            this.avoided = avoided;
            this.titles = titles;
            this.stars = stars;
        }

        public boolean empty() {
            return liked.isEmpty() && avoided.isEmpty() && titles.isEmpty() && stars.isEmpty();
        }
    }

    private static final Snap EMPTY = new Snap(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    /** 拿一份（可能是空、可能是几分钟前的）快照：绝不查库、绝不卡主线程 */
    public static Snap peek() {
        Snap got = snap;
        return got == null ? EMPTY : got;
    }

    /** 后台翻一遍库，攒出新的快照。间隔太近就不重复翻（刷剧换一部调一次，没事） */
    public static void load() {
        if (loading) return;
        if (snap != null && System.currentTimeMillis() - stamp < TTL) return;
        loading = true;
        Task.executeSerial(() -> {
            Snap built;
            try {
                built = build();
            } catch (Throwable e) {
                built = null;
            }
            if (built != null) {
                snap = built;
                stamp = System.currentTimeMillis();
                DebugLog.d("Binge", "口味：常看" + built.liked + " 不碰" + built.avoided + " 关注演员" + built.stars + " 最近看过" + built.titles.size() + "部");
            }
            loading = false;
        });
    }

    private static Snap build() {
        List<String> titles = new ArrayList<>();
        List<String> stars = new ArrayList<>();
        Map<String, Integer> counted = new HashMap<>();

        try {
            List<History> histories = History.get();
            if (histories != null) {
                // History.get() 出来的一般是新的在前，直接顺序取就够用
                for (History item : histories) {
                    String name = item == null ? null : item.getVodName();
                    if (name == null || name.trim().isEmpty()) continue;
                    name = name.trim();
                    if (titles.size() < TITLE_MAX && !titles.contains(name)) titles.add(name);
                    for (String tag : BingeTags.tagsOf(name)) counted.merge(tag, 1, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            List<Keep> keeps = Keep.getVod(); // 收藏：马先生说这里没有的类别就是不看的类别
            if (keeps != null) {
                for (Keep item : keeps) {
                    String name = item == null ? null : item.getVodName();
                    if (name == null || name.trim().isEmpty()) continue;
                    name = name.trim();
                    if (titles.size() < TITLE_MAX * 2 && !titles.contains(name)) titles.add(name);
                    for (String tag : BingeTags.tagsOf(name)) counted.merge(tag, 2, Integer::sum); // 收藏比看过更算数
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            List<Star> all = Star.getAll();
            if (all != null) {
                for (Star item : all) {
                    if (item == null || item.getDeleted() != 0) continue;
                    String name = item.getName();
                    if (name == null || name.trim().isEmpty()) continue;
                    if (!stars.contains(name)) stars.add(name.trim());
                    if (stars.size() >= STAR_MAX) break;
                }
            }
        } catch (Throwable ignored) {
        }

        List<String> liked = new ArrayList<>();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counted.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> item : sorted) {
            if (liked.size() >= LIKED_MAX) break;
            if (item.getValue() >= LIKED_MIN) liked.add(item.getKey());
        }

        // 「几乎不碰」：整个族在收藏和播放记录里一次都没出现过 —— 收藏里没有的类别，别往人家眼前推
        List<String> avoided = new ArrayList<>();
        for (String[] family : BingeTags.FAMILIES) {
            if (avoided.size() >= AVOID_MAX) break;
            boolean seen = false;
            for (String word : family) if (counted.containsKey(word)) { seen = true; break; }
            if (!seen && family.length > 0) avoided.add(family[0]);
        }
        return new Snap(liked, avoided, titles, stars);
    }
}
