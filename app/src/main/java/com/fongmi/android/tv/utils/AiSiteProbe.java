package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 站内验货：拿一个片名去各个能搜的站点各搜一页，看看站里到底有没有、真名叫什么、归在哪个分类下。
 *
 * 智能搜索里模型提名的片名必须先过这一关（2026-10-24 马先生定的规矩）：
 * 模型根本没听过《万物生》这种新出的 AI 漫剧，硬凑出来的名字站里十有八九没有，
 * 以前是提名直接上屏，点下去搜个空——现在站里搜不到的一律不上屏。
 *
 * 定位跟 AiJudge/AiSearch 一样：可有可无。任何一个站点超时、报错都当它没这片子，
 * 全部站点都扑空就返回空列表，调用方退回题材词，绝不卡住界面。
 * 只在智能搜索/同类推荐里用，跟正常的关键字搜索各走各的路，互不影响。
 */
public class AiSiteProbe {

    /** 同一个关键词最多跑多久（毫秒）：宁可少验几个站，也不能让人干等 */
    private static final long PER_WORD_BUDGET = 2500L;
    /** 一次最多问几个站：站多的时候（几十个源）全发一遍太吵，前面的一般就够了 */
    private static final int MAX_SITES = 8;

    /**
     * 拿一个片名到站里搜一遍。返回的每条都是站点真实给的条目（名字是站里的写法，点开就能搜到）。
     * 排在前面的跟关键词更像。
     */
    public static List<Vod> find(String keyword) {
        return find(keyword, PER_WORD_BUDGET);
    }

    public static List<Vod> find(String keyword, long budgetMs) {
        List<Vod> hits = Collections.synchronizedList(new ArrayList<>());
        if (keyword == null || keyword.trim().isEmpty()) return new ArrayList<>();
        List<Site> sites = searchable();
        if (sites.isEmpty()) return new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(sites.size());
        for (Site site : sites) {
            Task.submitLarge(() -> {
                try {
                    Result result = SiteApi.searchContent(site, keyword.trim(), true, "1");
                    List<Vod> list = result == null ? null : result.getList();
                    if (list != null) for (Vod vod : list) {
                        if (vod == null || vod.getName() == null || vod.getName().trim().isEmpty()) continue;
                        hits.add(vod);
                    }
                } catch (Throwable e) {
                    DebugLog.d("AiSiteProbe", "验货没搜到：" + keyword + " @ " + site.getName() + " → " + e);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(budgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Vod> copy = new ArrayList<>(hits);
        copy.sort((a, b) -> score(keyword, b) - score(keyword, a)); // 像的排前面
        return copy;
    }

    /** 站里到底有没有这部：挑最像的那条，一条都没有返回 null */
    public static Vod best(String keyword) {
        List<Vod> hits = find(keyword);
        if (hits.isEmpty()) return null;
        Vod top = hits.get(0);
        return score(keyword, top) > 0 ? top : null; // 连沾边的都没有，就当站里没这部
    }

    /** 关键词和站里那条名字像不像：完全命中 > 一边包含另一边 > 有一半以上的字重合 */
    private static int score(String keyword, Vod vod) {
        String a = plain(keyword);
        String b = plain(vod.getName());
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        if (b.contains(a) || a.contains(b)) return 70;
        int same = 0;
        for (char c : a.toCharArray()) if (b.indexOf(c) >= 0) same++;
        return same * 40 / a.length();
    }

    /** 比名字用的写法：去掉空格和标点，「万物生 第2季」跟「万物生第二季」算一部 */
    public static String plain(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s\\pP\\pS]", "");
    }

    private static List<Site> searchable() {
        List<Site> sites = new ArrayList<>();
        try {
            for (Site site : VodConfig.get().getSites()) {
                if (site == null || !site.isSearchable()) continue;
                sites.add(site);
                if (sites.size() >= MAX_SITES) break;
            }
        } catch (Throwable e) {
            return Collections.emptyList();
        }
        return sites;
    }
}
