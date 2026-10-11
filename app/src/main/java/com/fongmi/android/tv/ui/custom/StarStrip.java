package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Star;
import com.fongmi.android.tv.databinding.ViewStarStripBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

/**
 * 详情页下方那一块「演员」区域（马先生 2026-10-11 晚要求：别把关注入口藏在那行演员里，单列出来）。
 *
 * 站点给的是一串演员名，拆开一人一张牌：牌上写着名字和当前状态（＋关注 / 已关注），
 * 点一下就关注或取消关注；想搜他的作品用长按（交给外面传进来的 Action，TV 和手机各走各的搜索页）。
 * 没有演员数据整块不显示。
 *
 * 牌子的背景是个 selector：遥控器聚焦上去会亮出白框（TV 详情页把系统默认焦点框关掉了，
 * 不自己配背景的话选中了也看不出来），手机上按下去也有反馈。
 */
public class StarStrip {

    private final ViewStarStripBinding binding;
    private final Action action;

    public interface Action {

        void onSearch(String name);
    }

    public StarStrip(ViewStarStripBinding binding, Action action) {
        this.binding = binding;
        this.action = action;
        this.binding.getRoot().setVisibility(View.GONE);
    }

    /** 换一部剧/详情刷新时调：重新按演员串铺一遍牌 */
    public void set(String actor) {
        binding.starList.removeAllViews();
        List<String> names = Star.split(actor);
        binding.getRoot().setVisibility(names.isEmpty() ? View.GONE : View.VISIBLE);
        for (String name : names) binding.starList.addView(chip(name));
    }

    private View chip(String name) {
        Context context = binding.getRoot().getContext();
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(ResUtil.dp2px(14), ResUtil.dp2px(8), ResUtil.dp2px(14), ResUtil.dp2px(8));
        chip.setBackgroundResource(R.drawable.selector_star_chip);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, ResUtil.dp2px(10), 0);
        chip.setLayoutParams(params);
        MaterialTextView nameView = new MaterialTextView(context);
        nameView.setText(name);
        nameView.setTextColor(ContextCompat.getColor(context, R.color.white));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameView.setSingleLine(true);
        MaterialTextView stateView = new MaterialTextView(context);
        stateView.setTextColor(ContextCompat.getColor(context, R.color.white_50));
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        stateView.setSingleLine(true);
        stateView.setPadding(ResUtil.dp2px(8), 0, 0, 0);
        chip.addView(nameView);
        chip.addView(stateView);
        chip.setOnClickListener(view -> toggle(name, stateView));
        // 长按（含遥控器长按 OK）走 View 内建的长按检测，不要自己再接一层 KeyEvent：
        // View.onKeyDown 对 isLongPress 的 DOWN 会直接 performLongClick，没收到的话 500ms 后
        // 自己的 CheckForLongClick 也会触发，两条路都只会触发一次、且松手时不会再补 click。
        // 之前在 OnKeyListener 里又 performLongClick 一遍，跟内建那次叠在一起会弹两次框。
        chip.setOnLongClickListener(view -> onSearch(name));
        paint(stateView, name);
        return chip;
    }

    private void paint(MaterialTextView view, String name) {
        boolean followed = Star.exist(name);
        view.setText(followed ? R.string.star_followed : R.string.star_follow);
        view.setTextColor(ContextCompat.getColor(view.getContext(), followed ? R.color.white : R.color.white_50));
    }

    private void toggle(String name, MaterialTextView view) {
        boolean followed = Star.toggle(name);
        paint(view, name);
        Notify.show(followed ? R.string.star_add : R.string.star_del);
        RefreshEvent.star();
    }

    private boolean onSearch(String name) {
        if (action != null) action.onSearch(name);
        return true;
    }
}
