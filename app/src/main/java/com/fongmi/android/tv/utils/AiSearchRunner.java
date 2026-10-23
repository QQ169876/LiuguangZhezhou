package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 智能搜索的执行器：拿到 AiSearch 理解出的意图，分头去办（TMDB 拉作品表/按条件筛选/站内验货），
 * 办出来的结果通过 View 回调喂给界面。TV 搜索页（页内展示）和手机搜索页（弹窗展示）共用这一套，
 * 界面长什么样各自决定，流程只有这一份。
 *
 * 模型的自由文本永远到不了界面：View 能收到的只有固定文案的资源 id、AI 理解的摘要（限长过闸）、
 * 和一行行「点下去就拿名字全站搜」的候选。
 *
 * 「类似《X》这种」那条路 2026-10-24 改过：以前模型提名什么就直接上屏，而它压根没听过
 * 《万物生》这种新出的 AI 漫剧，硬凑的名字站里搜不到，点开一片空白。现在分三步走：
 *   1. 拿参照作品去站里搜，站里没有就直说没有；
 *   2. 站里有 → 按它所在的分类直取同类（条条都是站里真有、点开就能播的）；
 *   3. 模型提名的片名**逐个回站里验货**，搜不到的一律不上屏。
 * 模型自己也改了活：只管形态和题材，提名必须跟参照同形态（AI 漫剧不许推真人剧）。
 */
public class AiSearchRunner {

    /** 逐个片名验货的整体预算（毫秒）：站多的时候别让人干等 */
    private static final long VERIFY_BUDGET = 4000L;
    /** 同类分类直取最多列几条 */
    private static final int SAME_TYPE_LIMIT = 12;

    public interface View {

        /** 正在问模型（每次 run 一开始就会调） */
        void onThinking();

        /** AI 理解成了一句话（限长过闸后的摘要，可能为空串=没啥可说的） */
        void onSummary(String summary);

        /** 候选出来了：作品行（带海报/评分）或题材词行，点下去就拿名字全站搜 */
        void onRows(List<TmdbWork> rows);

        /** 某一行被 TMDB 认领回来了：换上准名字、海报和评分 */
        void onEnrich(int position, TmdbWork work);

        /** 没办成的固定文案：拒答 / AI 没回应 / 没想到合适的 */
        void onTip(int resId);

        /** 理解成关键词：不用挑，直接拿这个词全站搜 */
        void onDirect(String keyword);
    }

    public static void run(String question, View view) {
        view.onThinking();
        AiSearch.ask(question, plan -> dispatch(plan, question, view));
    }

    private static void dispatch(AiSearch.Plan plan, String question, View view) {
        switch (plan.type) {
            case AiSearch.Plan.REFUSED:
                view.onTip(R.string.ai_search_refused);
                break;
            case AiSearch.Plan.ERROR:
                view.onTip(R.string.ai_search_error);
                break;
            case AiSearch.Plan.KEYWORD:
                view.onDirect(plan.keywords.get(0));
                break;
            case AiSearch.Plan.ACTOR:
                view.onSummary(plan.summary);
                actor(plan, view);
                break;
            case AiSearch.Plan.SIMILAR:
                view.onSummary(plan.summary);
                similar(plan, true, question, view);
                break;
            case AiSearch.Plan.DISCOVER:
                view.onSummary(plan.summary);
                discover(plan, question, view);
                break;
        }
    }

    /** 点名演员：拉他的作品表；TMDB 靠不住就退成「拿人名直接搜」 */
    private static void actor(AiSearch.Plan plan, View view) {
        Tmdb.works(plan.actor, works -> {
            if (works != null && !works.isEmpty()) {
                view.onRows(works.size() > 12 ? new ArrayList<>(works.subList(0, 12)) : works);
            } else {
                List<TmdbWork> fallback = new ArrayList<>();
                fallback.add(new TmdbWork(plan.actor, "", ResUtil.getString(R.string.ai_pick_actor), TmdbWork.TYPE_WORD));
                view.onRows(fallback);
            }
        });
    }

    /**
     * 「类似《X》这种」：
     * ① 站里有没有这部参照作品 → 有就按它的分类直取同类（这一类条条能播）；
     * ② 模型提名的片名逐个回站里验货，站里搜不到的不上屏；
     * ③ 题材词垫在最后，好歹还能拿去站里撞一撞。
     * 前两步一条都没有 = 这一问的结果很差，顺手把缓存清掉，别让它占一天。
     */
    private static void similar(AiSearch.Plan plan, boolean withTags, String question, View view) {
        List<TmdbWork> tags = new ArrayList<>();
        if (withTags) for (String tag : plan.tags) {
            tags.add(new TmdbWork(tag, "", ResUtil.getString(R.string.ai_pick_tag), TmdbWork.TYPE_WORD));
        }
        String ref = plan.similar.isEmpty() ? "" : plan.similar.get(0);
        if (ref.isEmpty() && plan.titles.isEmpty() && tags.isEmpty()) {
            view.onTip(R.string.ai_search_empty);
            return;
        }
        List<String> titles = new ArrayList<>(plan.titles);
        Task.execute(() -> {
            List<TmdbWork> same = sameType(ref);
            List<TmdbWork> verified = verified(titles, ref);
            List<TmdbWork> rows = new ArrayList<>(same);
            rows.addAll(verified);
            rows.addAll(tags);
            if (same.isEmpty() && verified.isEmpty() && question != null && !question.isEmpty()) {
                AiSearch.drop(question); // 一条能播的都没验出来：这个答案别再记着了
            }
            App.post(() -> {
                if (rows.isEmpty()) {
                    view.onTip(R.string.ai_search_empty);
                } else {
                    view.onRows(rows);
                }
            });
        });
    }

    /**
     * 参照作品在站里查得到的话，直接把它所在分类下的片子列出来——这才是真正的「同类」：
     * 同一个站、同一个分类，点开就有源，不靠模型记性，也不会跨类别（马先生 2026-10-24：
     * 推荐的剧跨类别跨得太厉害）。
     */
    private static List<TmdbWork> sameType(String ref) {
        List<TmdbWork> rows = new ArrayList<>();
        if (ref == null || ref.trim().isEmpty()) return rows;
        Vod hit;
        try {
            hit = AiSiteProbe.best(ref);
        } catch (Throwable e) {
            return rows;
        }
        if (hit == null || hit.getSite() == null) return rows; // 站里压根没这部
        String tid = resolveTypeId(hit);
        if (tid.isEmpty()) return rows;
        try {
            Result result = SiteApi.categoryContent(hit.getSiteKey(), tid, "1", false, new HashMap<>());
            List<Vod> list = result == null ? null : result.getList();
            if (list == null) return rows;
            String refName = AiSiteProbe.plain(ref);
            for (Vod vod : list) {
                if (vod == null || vod.getName() == null || vod.getName().trim().isEmpty()) continue;
                if (AiSiteProbe.plain(vod.getName()).equals(refName)) continue; // 别把参照作品本身列进来
                TmdbWork work = new TmdbWork(vod.getName().trim(), "", ResUtil.getString(R.string.ai_pick_same_type), TmdbWork.TYPE_WORD);
                rows.add(work.withPic(vod.getPic()));
                if (rows.size() >= SAME_TYPE_LIMIT) break;
            }
        } catch (Throwable e) {
            DebugLog.d("AiSearchRunner", "同类分类直取没取到：" + ref + " → " + e);
        }
        return rows;
    }

    /** 站点给的分类名 → 分类 id：拿首页的分类表对一遍名字，对不上就不直取（宁可少列，不许列错类） */
    private static String resolveTypeId(Vod hit) {
        String typeName = hit.getTypeName();
        if (typeName == null || typeName.trim().isEmpty()) return "";
        try {
            Result home = SiteApi.homeContent(hit.getSite());
            List<Class> types = home == null ? null : home.getTypes();
            if (types == null) return "";
            for (Class type : types) {
                if (type == null || type.getTypeName() == null) continue;
                if (type.getTypeName().trim().equals(typeName.trim())) return type.getTypeId();
            }
        } catch (Throwable e) {
            DebugLog.d("AiSearchRunner", "分类表没取到：" + typeName + " → " + e);
        }
        return "";
    }

    /** 模型提名的片名逐个回站里验货：站里有才上屏，站里那条的真名和海报一并带回来 */
    private static List<TmdbWork> verified(List<String> titles, String ref) {
        List<TmdbWork> rows = new ArrayList<>();
        if (titles.isEmpty()) return rows;
        Vod[] found = new Vod[titles.size()]; // 各验各的，下标不冲突，数组就够了
        CountDownLatch latch = new CountDownLatch(titles.size());
        for (int i = 0; i < titles.size(); i++) {
            int index = i;
            String title = titles.get(i);
            Task.submitLarge(() -> {
                try {
                    found[index] = AiSiteProbe.best(title);
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(VERIFY_BUDGET, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String refName = AiSiteProbe.plain(ref);
        for (Vod vod : found) {
            if (vod == null) continue; // 站里没有：这条提名丢掉
            if (vod.getName() == null || vod.getName().trim().isEmpty()) continue;
            if (!refName.isEmpty() && AiSiteProbe.plain(vod.getName()).equals(refName)) continue; // 就是参照作品本身
            TmdbWork work = new TmdbWork(vod.getName().trim(), "", ResUtil.getString(R.string.ai_pick_verified), TmdbWork.TYPE_WORD);
            rows.add(work.withPic(vod.getPic()));
        }
        return rows;
    }

    /** 「今年新出的/评分高的」：TMDB 按条件筛真实片单；筛不出来再退到模型提名的片名（照样先验货） */
    private static void discover(AiSearch.Plan plan, String question, View view) {
        boolean movie = !"tv".equals(plan.media);
        boolean tv = !"movie".equals(plan.media);
        Tmdb.discover(movie, tv, plan.genre, plan.yearMin, plan.minRating, works -> {
            if (works != null && !works.isEmpty()) {
                view.onRows(works);
            } else if (!plan.titles.isEmpty()) {
                similar(plan, false, question, view); // TMDB 没筛出来，拿模型提名的片名顶上
            } else {
                view.onTip(R.string.ai_search_empty);
            }
        });
    }
}
