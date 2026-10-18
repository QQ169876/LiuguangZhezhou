package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 会自己拐弯的一排按钮：一行塞得下就一行，塞不下就折到下一行，**绝不横向滚动**。
 *
 * 播放页下面那排操作按钮就是被它逼出来的 —— 原来套在 HorizontalScrollView 里，
 * 按钮一多就得拿遥控器往右一下一下摁过去才够得着，电视上这么翻很别扭。
 * 换成这个容器后多出来的直接落到第二行，上下键就能到，一屏之内全看得见。
 * 位置是写死的：谁排在最右边是刻意安排的（「片头」「片尾」就固定在最后两个，好找），
 * 别为了「少摁两下」把它们往前挪。
 *
 * 子项之间的间距统一由 itemSpacing / lineSpacing 给，不用逐个写 margin。
 */
public class CustomFlowLayout extends ViewGroup {

    private int itemSpacing;
    private int lineSpacing;

    public CustomFlowLayout(Context context) {
        this(context, null);
    }

    public CustomFlowLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomFlowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.CustomFlowLayout);
        itemSpacing = typedArray.getDimensionPixelSize(R.styleable.CustomFlowLayout_itemSpacing, 0);
        lineSpacing = typedArray.getDimensionPixelSize(R.styleable.CustomFlowLayout_lineSpacing, 0);
        typedArray.recycle();
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new LayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof LayoutParams;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int limit = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int maxWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : Math.max(0, limit);
        int lineWidth = 0;
        int lineHeight = 0;
        int widest = 0;
        int totalHeight = getPaddingTop() + getPaddingBottom();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int width = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            int height = child.getMeasuredHeight() + params.topMargin + params.bottomMargin;
            // 已经摆了东西且再加上这个就超宽：这一行到此为止，往下另起一行
            if (lineWidth > 0 && lineWidth + itemSpacing + width > maxWidth) {
                widest = Math.max(widest, lineWidth);
                totalHeight += lineHeight + lineSpacing;
                lineWidth = width;
                lineHeight = height;
            } else {
                lineWidth += (lineWidth > 0 ? itemSpacing : 0) + width;
                lineHeight = Math.max(lineHeight, height);
            }
        }
        if (lineWidth > 0) {
            widest = Math.max(widest, lineWidth);
            totalHeight += lineHeight;
        }

        int wantedWidth = widest + getPaddingLeft() + getPaddingRight();
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? wantedWidth : resolveSize(wantedWidth, widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(Math.max(totalHeight, getSuggestedMinimumHeight()), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int limit = right - left - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;

        List<View> views = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        int row = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int span = width + params.leftMargin + params.rightMargin;
            // 这一行已经放不下（且不是行首唯一一个）：换行
            if (x > getPaddingLeft() && x + span > limit) {
                x = getPaddingLeft();
                y += lineHeight + lineSpacing;
                lineHeight = 0;
                row++;
            }
            int childLeft = x + params.leftMargin;
            int childTop = y + params.topMargin + (Math.max(lineHeight, height + params.topMargin + params.bottomMargin) - height) / 2;
            child.layout(childLeft, childTop, childLeft + width, childTop + height);
            x += span + itemSpacing;
            lineHeight = Math.max(lineHeight, height + params.topMargin + params.bottomMargin);
            views.add(child);
            rows.add(row);
        }
        linkFocus(views, rows);
    }

    /**
     * 换行后遥控器靠几何猜焦点常常猜不中（比如从第一行右边按下，够不到折到第二行最左的「片头/片尾」），
     * 2026-10-18 马先生实测「片尾」就是定位不到。所以换行时把焦点链显式串好：
     * 左右同行相邻；上下找相邻行里水平中心最近的那个。
     *
     * 两个配合 Control 样式的细节（这个容器目前只用于播放页控制栏，按钮全是 Control style）：
     * - 第一行的「上」不碰：样式里本来就写死指向进度条（nextFocusUp=@id/exo_progress），那是进出控制栏的门；
     * - 一行放得下时恢复成样式原样：左右下清掉，「上」还给 exo_progress，别把上次换行时设的旧指向留下来。
     * 最后一行的「下」清成未设，交给系统几何查找（下面可能是选集区）。
     * 注意：CustomUpDownView（倍速、片头、片尾）自己会吃掉上下键调数值，焦点链只帮它们把左右打通。
     */
    private void linkFocus(List<View> views, List<Integer> rows) {
        int count = views.size();
        if (count == 0) return;
        int lastRow = rows.get(count - 1);
        for (int i = 0; i < count; i++) {
            View view = views.get(i);
            if (view.getId() == View.NO_ID) continue;
            if (lastRow == 0) { // 一行放下：回到样式原样
                view.setNextFocusLeftId(View.NO_ID);
                view.setNextFocusRightId(View.NO_ID);
                // exo_progress 是 media3 库里进度条的 id（app 的 R 不收库 id，得全限定名）
                view.setNextFocusUpId(androidx.media3.ui.R.id.exo_progress);
                view.setNextFocusDownId(View.NO_ID);
                continue;
            }
            int row = rows.get(i);
            view.setNextFocusLeftId(i > 0 && rows.get(i - 1) == row ? views.get(i - 1).getId() : View.NO_ID);
            view.setNextFocusRightId(i < count - 1 && rows.get(i + 1) == row ? views.get(i + 1).getId() : View.NO_ID);
            if (row > 0) view.setNextFocusUpId(nearest(views, rows, view, row - 1)); // 第一行的「上」保留样式里的进度条
            view.setNextFocusDownId(row < lastRow ? nearest(views, rows, view, row + 1) : View.NO_ID);
        }
    }

    /** 在指定行里找水平中心离它最近的那个，找不到给 NO_ID（等于不设，交给系统几何查找） */
    private int nearest(List<View> views, List<Integer> rows, View from, int row) {
        int center = (from.getLeft() + from.getRight()) / 2;
        int best = View.NO_ID;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < views.size(); i++) {
            if (rows.get(i) != row) continue;
            View item = views.get(i);
            if (item.getId() == View.NO_ID) continue;
            int gap = Math.abs((item.getLeft() + item.getRight()) / 2 - center);
            if (gap < distance) {
                distance = gap;
                best = item.getId();
            }
        }
        return best;
    }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {

        public LayoutParams(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.LayoutParams params) {
            super(params);
        }
    }
}
