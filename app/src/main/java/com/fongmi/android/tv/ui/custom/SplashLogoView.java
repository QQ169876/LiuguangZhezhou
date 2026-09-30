package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * 开屏那个大写 M：底色是暗的，一道白色高光从左往右循环扫过，
 * 高光扫到哪哪就亮起来，来回不断，除了 M 之外不加任何别的东西。
 */
public class SplashLogoView extends View {

    private static final String TEXT = "M";
    private static final long DURATION = 1900;

    private final TextPaint paint;
    private final ValueAnimator animator;
    private float phase;

    public SplashLogoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) * 0.86f;
        paint.setTextSize(size);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float base = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;

        // 底字：暗一点的白，先把字形摆在那儿
        paint.setShader(null);
        paint.clearShadowLayer();
        paint.setColor(0x2EFFFFFF);
        canvas.drawText(TEXT, getWidth() / 2f, base, paint);

        // 高光：一道白色亮带扫过字形内部，带一点外发光
        float width = getWidth();
        float center = -width * 0.6f + phase * (width * 2.2f);
        float band = width * 0.45f;
        paint.setShader(new LinearGradient(center - band, 0, center + band, getHeight(),
                new int[]{0x00FFFFFF, 0x66FFFFFF, 0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.34f, 0.5f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(size * 0.18f, 0, 0, 0xB3FFFFFF);
        canvas.drawText(TEXT, getWidth() / 2f, base, paint);
        paint.clearShadowLayer();
        paint.setShader(null);
    }
}
