package com.fongmi.android.tv.utils;

import android.graphics.Color;

/**
 * 文字与背景的对比度工具。
 *
 * 参照 WCAG 2.1 的口径：正文至少要 4.5:1、大号字 3:1，达不到就看不清。
 * 这里不搞花活，只做两件事：
 * 1. 算相对亮度与对比度；
 * 2. 给定背景色，挑一个看得清的字体色（白或近黑，取对比度更高的那个）；
 *    如果连白色都压不住（背景太亮），就返回一层遮罩该用的黑度，把背景压暗再上白字。
 */
public class Contrast {

    /** WCAG 正文最低对比度 */
    public static final float MIN = 4.5f;

    private static final int LIGHT_TEXT = 0xFFF5F7FA;
    private static final int DARK_TEXT = 0xFF0B1220;
    private static final int SCRIM = 0xFF000000;

    private Contrast() {
    }

    /** WCAG 相对亮度，0（全黑）～1（全白） */
    public static double luminance(int color) {
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** 两个颜色的对比度，1～21 */
    public static double ratio(int a, int b) {
        double l1 = luminance(a);
        double l2 = luminance(b);
        double light = Math.max(l1, l2);
        double dark = Math.min(l1, l2);
        return (light + 0.05) / (dark + 0.05);
    }

    /**
     * 这个背景上放什么颜色的字看得清：
     * 先比白色和近黑色谁的对比度高，白色都不够 4.5:1 说明背景太亮，就给近黑色。
     */
    public static int textOn(int background) {
        double white = ratio(background, LIGHT_TEXT);
        double dark = ratio(background, DARK_TEXT);
        if (white >= MIN || white >= dark) return LIGHT_TEXT;
        return DARK_TEXT;
    }

    /**
     * 背景太亮、白字压不住时，需要盖一层多黑的遮罩（0～0.75）。
     * 叠上这层黑之后再放白字，对比度就能到 4.5:1 以上。
     */
    public static float scrim(int background) {
        if (ratio(background, LIGHT_TEXT) >= MIN) return 0f;
        for (float alpha = 0.05f; alpha <= 0.75f; alpha += 0.05f) {
            if (ratio(mix(background, alpha), LIGHT_TEXT) >= MIN) return alpha;
        }
        return 0.75f;
    }

    /** 背景叠一层黑色后的实际颜色 */
    public static int mix(int background, float alpha) {
        int a = Math.round(255 * alpha);
        int r = (Color.red(background) * (255 - a)) / 255;
        int g = (Color.green(background) * (255 - a)) / 255;
        int b = (Color.blue(background) * (255 - a)) / 255;
        return Color.argb(255, r, g, b);
    }

    public static int scrimColor(float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(SCRIM), Color.green(SCRIM), Color.blue(SCRIM));
    }
}
