package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbWork;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能搜索的执行器：拿到 AiSearch 理解出的意图，分头去办（TMDB 拉作品表/按条件筛选/逐部认领），
 * 办出来的结果通过 View 回调喂给界面。TV 搜索页（页内展示）和手机搜索页（弹窗展示）共用这一套，
 * 界面长什么样各自决定，流程只有这一份。
 *
 * 模型的自由文本永远到不了界面：View 能收到的只有固定文案的资源 id、AI 理解的摘要（限长过闸）、
 * 和一行行「点下去就拿名字全站搜」的候选。
 */
public class AiSearchRunner {

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
        AiSearch.ask(question, plan -> dispatch(plan, view));
    }

    private static void dispatch(AiSearch.Plan plan, View view) {
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
                similar(plan, true, view);
                break;
            case AiSearch.Plan.DISCOVER:
                view.onSummary(plan.summary);
                discover(plan, view);
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
     * 「类似《X》这种」：模型提名的相似作品 + 同类题材词，都列出来可点。
     * 提名的作品逐部送去 TMDB 认领：认出来就换上准名字/海报/评分，认不出来保持原样照样能搜。
     */
    private static void similar(AiSearch.Plan plan, boolean withTags, View view) {
        List<TmdbWork> rows = new ArrayList<>();
        List<Integer> pending = new ArrayList<>(); // 等着 TMDB 认领的行号
        for (String title : plan.titles) {
            pending.add(rows.size());
            rows.add(new TmdbWork(title, "", ResUtil.getString(R.string.ai_pick_guessed), TmdbWork.TYPE_WORD));
        }
        if (withTags) for (String tag : plan.tags) rows.add(new TmdbWork(tag, "", ResUtil.getString(R.string.ai_pick_tag), TmdbWork.TYPE_WORD));
        if (rows.isEmpty()) {
            view.onTip(R.string.ai_search_empty);
            return;
        }
        view.onRows(rows);
        for (int i = 0; i < pending.size(); i++) {
            int position = pending.get(i);
            Tmdb.search(plan.titles.get(i), works -> {
                if (works == null || works.isEmpty()) return;
                view.onEnrich(position, works.get(0));
            });
        }
    }

    /** 「今年新出的/评分高的」：TMDB 按条件筛真实片单；筛不出来再退到模型提名的片名 */
    private static void discover(AiSearch.Plan plan, View view) {
        boolean movie = !"tv".equals(plan.media);
        boolean tv = !"movie".equals(plan.media);
        Tmdb.discover(movie, tv, plan.genre, plan.yearMin, plan.minRating, works -> {
            if (works != null && !works.isEmpty()) {
                view.onRows(works);
            } else if (!plan.titles.isEmpty()) {
                similar(plan, false, view); // TMDB 没筛出来，拿模型提名的片名顶上
            } else {
                view.onTip(R.string.ai_search_empty);
            }
        });
    }
}
