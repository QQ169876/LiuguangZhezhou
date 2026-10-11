package com.fongmi.android.tv.ui.custom;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.databinding.DialogStarWorksBinding;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Tmdb;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

/**
 * 演员作品列表的内容（两端共用，TV 和手机只是外面那个对话框壳不一样）。
 *
 * 分工：TMDB 只负责把「他演过哪些片」列准，点其中一个片名之后怎么找片源不归它管
 * ——外面传进来的 Action 会拿这个名字走 APP 自己的全站搜索。
 *
 * 万一查不到（断网、TMDB 没这个人、用户关掉了），也不让用户卡住：
 * 提示一句，底下永远留着「直接全站搜『张三』」这条老路。
 */
public class StarWorks {

    private final DialogStarWorksBinding binding;
    private final Action action;

    public interface Action {

        /** 点了某一部作品：拿准片名去搜 */
        void onPick(TmdbWork work);

        /** 直接拿演员名去搜（TMDB 没帮上忙时的退路） */
        void onDirect(String name);
    }

    public StarWorks(DialogStarWorksBinding binding, Action action) {
        this.binding = binding;
        this.action = action;
    }

    /** 弹出来时调：先从本机缓存里看有没有，没有再去问 TMDB */
    public void show(String actor) {
        binding.movieList.removeAllViews();
        binding.tvList.removeAllViews();
        binding.movieTitle.setVisibility(View.GONE);
        binding.tvTitle.setVisibility(View.GONE);
        binding.hint.setVisibility(View.VISIBLE);
        binding.hint.setText(R.string.tmdb_loading);
        binding.direct.setText(binding.getRoot().getResources().getString(R.string.tmdb_direct_search, actor));
        binding.direct.setOnClickListener(view -> {
            if (action != null) action.onDirect(actor);
        });
        List<TmdbWork> cached = Tmdb.peek(actor);
        if (cached != null) render(cached);
        else Tmdb.works(actor, this::render);
    }

    private void render(List<TmdbWork> works) {
        if (binding == null) return;
        if (works == null || works.isEmpty()) {
            binding.hint.setVisibility(View.VISIBLE);
            binding.hint.setText(R.string.tmdb_none);
            return;
        }
        binding.hint.setVisibility(View.GONE);
        for (TmdbWork work : works) {
            if (work.isMovie()) binding.movieList.addView(row(work));
            else binding.tvList.addView(row(work));
        }
        if (binding.movieList.getChildCount() > 0) binding.movieTitle.setVisibility(View.VISIBLE);
        if (binding.tvList.getChildCount() > 0) binding.tvTitle.setVisibility(View.VISIBLE);
    }

    /** 一行一部：第一行「片名（年份）」，第二行「饰 角色」（没有角色就不显示） */
    private View row(TmdbWork work) {
        LinearLayout row = new LinearLayout(binding.getRoot().getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = ResUtil.dp2px(10);
        row.setPadding(pad, pad, pad, pad);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setBackgroundResource(R.drawable.selector_star_work);
        row.setOnClickListener(view -> {
            if (action != null) action.onPick(work);
        });
        row.addView(text(work.getTitle(), R.color.white, R.dimen.star_works_text, true));
        if (!work.getRole().isEmpty()) row.addView(text(work.getRole(), R.color.white_50, R.dimen.star_works_sub, false));
        return row;
    }

    private MaterialTextView text(String value, int color, int sizeRes, boolean bold) {
        MaterialTextView view = new MaterialTextView(binding.getRoot().getContext());
        view.setText(value);
        view.setTextColor(ContextCompat.getColor(view.getContext(), color));
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, view.getResources().getDimensionPixelSize(sizeRes));
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }
}
