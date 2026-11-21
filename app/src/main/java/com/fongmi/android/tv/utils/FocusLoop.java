package com.fongmi.android.tv.utils;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 遥控器左右焦点循环。
 *
 * <p>TV 上焦点停在一行最左 / 最右时，再按左 / 右键，系统找不到下一个可聚焦目标，
 * 表现就是「按了没反应、卡在那儿」。这里把焦点送到同一行的另一端，让这一行的选项
 * 首尾相接、形成循环：最右再向右 → 跳到最左，最左再向左 → 跳到最右。
 *
 * <p>只在焦点确实处于这一行的左右端点、且 {@code focusSearch()} 在这个方向也找不到
 * 下一个焦点时才循环，中间项一律交给系统正常移动。范围限定在「与当前焦点同一个父容器」
 * 的同行可聚焦兄弟，不会串到顶部工具栏、别的列表行这类不相干区域。
 *
 * <p>由 BaseActivity.dispatchKeyEvent 在确认「焦点按下方向键后没有移动」之后调用；
 * 换源（CustomTitleView）、翻页（CustomLeftRightLayout）、搜索键盘、播放快进换台这些
 * 要么自己移动/消费了焦点、要么所在页面已禁用，均不会误触发。
 */
public final class FocusLoop {

    private static final int MAX_ANCESTOR = 3;

    private FocusLoop() {
    }

    /**
     * @param focused 当前焦点（按方向键前后应保持不变，否则说明系统已正常移动，不该调用）
     * @param right   true=按了右键（应跳到这一行最左），false=按了左键（跳到最右）
     * @return 是否已把焦点循环过去（调用方应据此把按键标记为已消费）
     */
    public static boolean wrap(View focused, boolean right) {
        if (focused == null || focused instanceof EditText) return false;
        if (!focused.isShown() || focused.getWidth() <= 0 || focused.getHeight() <= 0) return false;
        // 系统在这个方向还能找到下一个焦点，说明不是边界（含显式 nextFocusLeft/RightId 的循环）
        View next = focused.focusSearch(right ? View.FOCUS_RIGHT : View.FOCUS_LEFT);
        if (next != null && next != focused) return false;

        Rect base = bounds(focused);
        // 从当前焦点的父容器起，向上最多找几层，取第一层「有同行可聚焦兄弟」的容器
        ViewParent parent = focused.getParent();
        for (int level = 0; level < MAX_ANCESTOR && parent instanceof ViewGroup; level++) {
            ViewGroup group = (ViewGroup) parent;
            List<View> row = siblings(group, focused, base);
            if (row.size() >= 2) { // 至少 2 个（含自己）才谈得上循环
                row.sort(Comparator.comparingInt(v -> bounds(v).left));
                int index = row.indexOf(focused);
                if (index < 0) return false;
                boolean atEnd = right ? index == row.size() - 1 : index == 0;
                if (!atEnd) return false; // 还没到这一行的端点，交给系统正常移动
                View target = right ? row.get(0) : row.get(row.size() - 1);
                return target != focused && target.requestFocus();
            }
            parent = parent.getParent();
        }
        return false;
    }

    /** 同一父容器里，与当前焦点同一行、可聚焦的兄弟（含自己），排除祖先/后代关系 */
    private static List<View> siblings(ViewGroup group, View focused, Rect base) {
        List<View> out = new ArrayList<>();
        out.add(focused);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == focused || !child.isShown() || !(child.isFocusable()) || !child.isEnabled()) continue;
            if (child.getWidth() <= 0 || child.getHeight() <= 0 || child instanceof EditText) continue;
            if (related(child, focused)) continue;
            if (sameRow(base, bounds(child))) out.add(child);
        }
        return out;
    }

    /** 祖先 / 后代不是并列的同行选项，排除掉，免得把容器或内部子控件当目标 */
    private static boolean related(View a, View b) {
        return isDescendant(a, b) || isDescendant(b, a);
    }

    private static boolean isDescendant(View child, View parent) {
        for (ViewParent p = child.getParent(); p instanceof View; p = p.getParent()) {
            if (p == parent) return true;
        }
        return false;
    }

    /** 垂直区间重叠超过较矮者的一半即视为同一行，容纳高度不一的按钮 */
    private static boolean sameRow(Rect a, Rect b) {
        int overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        return overlap > 0 && overlap * 2 >= Math.min(a.height(), b.height());
    }

    private static Rect bounds(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
    }
}
