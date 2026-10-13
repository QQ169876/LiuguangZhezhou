package com.fongmi.android.tv.ui.custom;

import java.util.stream.Collectors;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.animation.Animation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

public class CustomTitleView extends MaterialTextView {

    /** 换源防抖：手停下来这么久才真的切（毫秒） */
    private static final long SWITCH_DELAY = 500;

    private final Runnable commit = this::doCommit;
    private Listener listener;
    private Animation flicker;
    private boolean coolDown;
    private Site pending;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    public CustomTitleView(@NonNull Context context) {
        super(context);
    }

    public CustomTitleView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        flicker = ResUtil.getAnim(R.anim.flicker);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        setOnClickListener(v -> listener.showDialog());
    }

    private boolean hasEvent(KeyEvent event) {
        return !getHome().isEmpty() && (KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event) || (KeyUtil.isUpKey(event) && !coolDown));
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused) startAnimation(flicker);
        else clearAnimation();
    }

    /**
     * 弹窗（比如站源菜单）盖上来之后，**这个标题其实还占着焦点**——
     * 窗口失焦不会走 onFocusChanged，那个无限循环的闪烁动画就这么一直跑下去，
     * 逼着背后的首页每帧重绘一遍（几十张海报）。弹窗就压在这上面，于是选站源一卡一卡的。
     * 窗口没焦点就先停下，焦点回来再接上。
     */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            clearAnimation();
            // ⚠️ 有预览中的换源就立刻落地：不然打开站源菜单、在里面选了另一个源之后，
            // 这个延迟任务 500ms 后照样执行，会把刚选好的源又顶掉
            if (pending != null) {
                removeCallbacks(commit);
                doCommit();
            }
        } else if (isFocused() && flicker != null) {
            startAnimation(flicker);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!hasEvent(event)) return super.dispatchKeyEvent(event);
        onKeyDown(event);
        return true;
    }

    private void onKeyDown(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) onKeyUp();
        else if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) switchSite(getSite(false));
        else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) switchSite(getSite(true));
    }

    /**
     * 左右键换源：**手停下来才真的切**。
     *
     * 以前是每按一下就换一次源、立刻重新拉一遍首页、还要把整份配置写回数据库。
     * 源多的时候一路按过去，几秒内会堆出十几个加载任务（很多源第一次用还要现加载 jar/Spider），
     * 在 TV 上就是「选片源非常卡」，甚至像卡死。
     * 现在先只把标题改成要去的那个源（按下去有反应），停手 500ms 才落地。
     */
    private void switchSite(Site site) {
        if (site == null || site.isEmpty()) return;
        pending = site;
        setText(site.getName());
        removeCallbacks(commit);
        postDelayed(commit, SWITCH_DELAY);
    }

    private void doCommit() {
        Site site = pending;
        pending = null;
        if (site != null && listener != null) listener.setSite(site);
    }

    private void onKeyUp() {
        App.post(() -> coolDown = false, 3000);
        coolDown = true;
        if (pending != null) { // 刚按了左右还没落地：先落地（它自己会刷一次），别再额外拉一遍
            removeCallbacks(commit);
            doCommit();
            return;
        }
        listener.onRefresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(commit);
        try {
            doCommit(); // 页面要走了，别把这次换源给丢了
        } catch (Throwable ignored) { // 正在销毁就别硬来了
        }
        super.onDetachedFromWindow();
    }

    private Site getSite(boolean next) {
        List<Site> items = getSites();
        if (items.isEmpty()) return new Site();
        Site current = pending != null ? pending : getHome(); // 连着按要能连着跨好几个源，不能每次都从当前源算
        int position = items.indexOf(current);
        if (position < 0) position = 0;
        if (next) position = (position + 1) % items.size();
        else position = (position - 1 + items.size()) % items.size();
        return items.get(position);
    }

    private List<Site> getSites() {
        return VodConfig.get().getSites().stream().filter(site -> !site.isHide()).collect(Collectors.toList());
    }

    public interface Listener extends SiteListener {

        void showDialog();

        void onRefresh();
    }
}
