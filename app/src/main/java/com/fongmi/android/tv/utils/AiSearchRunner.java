package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.AiSetting;

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
    /** 翻译关键词时验货的预算：这一路是要马上出结果的，等太久不如按原话搜 */
    private static final long WORD_BUDGET = 2500L;
    /** 同类分类直取最多列几条 */
    private static final int SAME_TYPE_LIMIT = 12;

    /**
     * 一句话里掺了这些说法，说明它不是现成的片名，直接拿去搜站里必定扑空。
     * 2026-11-03 马先生：搜「持械入宋最新」，站里叫「持械入宋 第十一季」，按原话一个字也搜不到。
     */
    private static final String[] HINTS = {
            "最新", "最近", "新出的", "新出", "更新到", "更新完", "在更", "完结",
            "第几季", "第几部", "下一季", "下一部", "续集", "前传", "番外",
            "第二季", "第三季", "第四季", "第五季", "第六季", "第七季", "第八季",
            "第2季", "第3季", "第4季", "第5季", "第6季", "第7季", "第8季", "第9季", "第10季",
            "推荐", "好看", "类似", "差不多", "这种", "一样的", "同款", "同类型",
            "谁演", "主演", "演的", "导演", "演员", "出品",
            "评分", "豆瓣", "高分", "口碑",
            "今年", "去年", "近几年", "最近几年", "这两年", "这几年",
            "多少部", "一共", "几部", "全集", "全部", "完整版",
            "排行", "榜单", "热播", "热门", "免费", "在线观看",
            "国语", "粤语", "中字", "高清",
            "？", "?", "吗", "呢", "什么", "怎么"
    };

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

    /** 普通搜索键走这一路时的回调：要么拿到一个可以直接搜的词，要么得挑一挑（交给智能搜索界面） */
    public interface Direct {

        void onKeyword(String keyword);

        void onPick(AiSearch.Plan plan);
    }

    /**
     * 普通搜索键也先过一遍大模型（2026-11-03 马先生定的）：
     * 搜「持械入宋最新」这种话，按原话搜站里一条都没有——站里的名字是「持械入宋 第十一季」。
     * 所以先把这句话交给模型翻译成站里真有的写法，再拿翻译出来的词全站搜，这才是正常逻辑。
     *
     * 三道收口，绝不让用户干等：
     *   1. 看着就是现成片名的（没掺「最新/第几季/推荐…」这类说法）→ 不问模型，照常搜；
     *   2. 开关没开、没有能用的模型 → 不问，照常搜；
     *   3. 模型没搭理/超时/理解不出来 → 按原话搜。
     * 返回 true 表示已经接管（结果异步回调），false 表示这一路没用上模型，调用方立刻按原话搜。
     */
    public static boolean interpret(String question, Direct callback) {
        if (question == null || question.trim().isEmpty()) return false;
        if (!needs(question) || !AiSetting.ready()) return false;
        DebugLog.d("AiSearchRunner", "普通搜索先请大模型翻译一句：" + question);
        AiSearch.ask(question, plan -> {
            if (plan.type == AiSearch.Plan.KEYWORD && !plan.keywords.isEmpty()) {
                final String asked = question;
                Direct target = callback; // 进 lambda 得是最终的
                Task.execute(() -> {
                    final String word = stationWord(plan.keywords, asked);
                    App.post(() -> target.onKeyword(word));
                });
                return;
            }
            if (plan.type == AiSearch.Plan.ERROR || plan.type == AiSearch.Plan.REFUSED) {
                DebugLog.d("AiSearchRunner", "模型没搭理，按原话搜：" + question);
                callback.onKeyword(question);
                return;
            }
            callback.onPick(plan); // 演员作品表/同类推荐/按条件筛选：得挑一挑，交给智能搜索界面
        });
        return true;
    }

    /** 这句话像不像大白话：掺了意图词才值得请模型，纯片名别白烧一次调用（也别让人多等一秒） */
    private static boolean needs(String question) {
        String text = question.trim();
        if (text.length() < 2) return false;
        for (String hint : HINTS) if (text.contains(hint)) return true;
        return text.matches(".*第\\d+[季部集].*"); // 「第11季」这种写法列不全，用式子兜住
    }

    /**
     * 模型给了好几种写法（「持械入宋 第十一季」「持械入宋第十一季」「持械入宋 第11季」…）：
     * 按它的顺序回站里挨个对，站里真有这个名（完全对上、或站里的名字更长）就用**站里的写法**去搜——
     * 命中才稳。一个都对不上就退成模型的主关键词。
     *
     * 卡得严是有原因的：只沾点边的（「第十一季」对上「第十季」）绝不能凑，
     * 否则用户搜「持械入宋」想看全部季，会被替换成某一季，反而搜不全。
     */
    private static String stationWord(List<String> keywords, String question) {
        String plain = AiSiteProbe.plain(question);
        for (String word : keywords) {
            if (AiSiteProbe.plain(word).equals(plain)) return question; // 模型没改写（只是换了个写法），原话就是最好的词
        }
        Vod[] found = new Vod[keywords.size()];
        CountDownLatch latch = new CountDownLatch(keywords.size());
        for (int i = 0; i < keywords.size(); i++) {
            int index = i;
            String word = keywords.get(i);
            Task.submitLarge(() -> {
                try {
                    found[index] = sameWord(word);
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(WORD_BUDGET, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < found.length; i++) {
            Vod vod = found[i];
            if (vod == null || vod.getName() == null || vod.getName().trim().isEmpty()) continue;
            DebugLog.d("AiSearchRunner", "模型给的「" + keywords.get(i) + "」站里写作「" + vod.getName().trim() + "」，按站里的名字搜");
            return vod.getName().trim();
        }
        String first = keywords.get(0);
        String base = baseName(first); // 带季数的写法站里都没有：退到系列名，好歹能把整季列表搜出来给他挑
        if (!base.isEmpty() && !base.equals(first)) {
            DebugLog.d("AiSearchRunner", "站里都没对上，退回系列名搜：" + base);
            return base;
        }
        DebugLog.d("AiSearchRunner", "站里都没对上，按模型的主关键词搜：" + first);
        return first;
    }

    /** 「持械入宋 第十一季」→「持械入宋」：季数/部数/集数那截去掉 */
    private static String baseName(String word) {
        if (word == null) return "";
        String text = word.trim().replaceAll("[\\s]*(第)?[0-9０-９一二三四五六七八九十百]+[季部集]$", "");
        text = text.trim().replaceAll("(?i)[\\s]*(season[\\s]*[0-9]+|[Ss][0-9]{1,2})$", "");
        return text.trim().length() >= 2 ? text.trim() : "";
    }

    /** 站里有没有这个名：完全对上、或站里的名字更长（多了前缀后缀），才算数 */
    private static Vod sameWord(String keyword) {
        String want = AiSiteProbe.plain(keyword);
        if (want.isEmpty()) return null;
        for (Vod vod : AiSiteProbe.find(keyword, WORD_BUDGET)) {
            if (vod == null || vod.getName() == null) continue;
            String name = AiSiteProbe.plain(vod.getName());
            if (name.equals(want) || name.contains(want)) return vod;
        }
        return null;
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
                // 模型改写的词照样回站里对一遍：拿到站里真名再全站搜，命中才稳
                final String asked = question == null ? "" : question;
                Task.execute(() -> {
                    final String word = stationWord(plan.keywords, asked);
                    App.post(() -> view.onDirect(word));
                });
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
            } else if (!plan.keywords.isEmpty()) {
                // 模型把点名作品的「最新/第几季」误判成了泛推荐：别走 TMDB 空转，照关键词回站里搜
                String asked = question == null ? "" : question;
                Task.execute(() -> {
                    final String word = stationWord(plan.keywords, asked);
                    App.post(() -> view.onDirect(word));
                });
            } else {
                view.onTip(R.string.ai_search_empty);
            }
        });
    }
}
