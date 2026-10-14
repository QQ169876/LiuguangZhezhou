package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.R;

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
            }
            int childLeft = x + params.leftMargin;
            int childTop = y + params.topMargin + (Math.max(lineHeight, height + params.topMargin + params.bottomMargin) - height) / 2;
            child.layout(childLeft, childTop, childLeft + width, childTop + height);
            x += span + itemSpacing;
            lineHeight = Math.max(lineHeight, height + params.topMargin + params.bottomMargin);
        }
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
