package com.fongmi.android.tv.player.ui;

import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.setting.PlayerSetting;

/**
 * 画面旋转的公共实现：播放页（一般视频播放）和广播接收页都走这一套，不再各写一份。
 * <p>
 * ⭐ 头等大事是**渲染层**：SurfaceView 由 SurfaceFlinger 单独合成，只跟父 View 的缩放、
 * 不跟旋转 —— 转 90° 会看到画面被压扁 / 半屏黑。所以只要角度不是 0，就必须先把渲染层
 * 换成 TextureView（跟着 View 树一起变换），角度回正再换回用户自己选的那种。
 * 这一条对一般视频播放和屏幕广播接收一样成立，故放在这里共用。
 * <p>
 * ⭐ 第二件大事是**缩放什么时候算**：画面尺寸不是一开始就有的。屏幕广播尤其明显 ——
 * 用户常常在待机（还没接上流）时就先把角度转好了，那一刻画面尺寸是 0，
 * 拿窗口尺寸算出来的系数是个错值，等流真的接上、画面尺寸出来了，却没有任何东西再去算一次，
 * 结果就是「比例没错，画面却缩在屏幕中间一小块」（马先生 2026-11-01 指出）。
 * 所以这里挂 {@link #watch} 盯着画面那一层，尺寸一变就重算；算的时候优先用视频自己的比例，
 * 不再依赖 surface 那会儿量出来没有。
 */
public final class PlayerRotate {

    private PlayerRotate() {
    }

    /** 按本机记的播放页角度决定渲染层（播放页用） */
    public static void applyRender(PlayerView view, Player player) {
        applyRender(view, player, PlayerSetting.getVideoRotate());
    }

    /**
     * 换渲染层：角度非 0 → TextureView；回正 → 用户自己选的。
     * 已经是目标类型就不动（换一次要重建 surface，白白黑一帧）。
     */
    public static void applyRender(PlayerView view, Player player, int degree) {
        if (view == null) return;
        int render = degree != 0 ? PlayerSetting.RENDER_TEXTURE : PlayerSetting.getRender();
        View surface = view.getVideoSurfaceView();
        boolean already = render == PlayerSetting.RENDER_TEXTURE ? surface instanceof TextureView : surface instanceof SurfaceView;
        if (already) return;
        try {
            view.setRender(render);
            // 播放中热切换出来的新 surface 这一刻还没就绪（TextureView 的 SurfaceTexture 要等一次布局），
            // 就这样放着会黑屏 —— 马先生 2026-10-14 实测「Surface 模式下按旋转按钮直接黑屏」。
            rebind(view, player);
        } catch (Exception e) {
            // 换不成也不能把播放页搞崩，原样转就是了
        }
    }

    /** 下一帧把新 surface 重新绑给播放器（刚建出来那会儿还是空的，绑了等于没绑） */
    private static void rebind(PlayerView view, Player player) {
        view.post(() -> {
            try {
                View surface = view.getVideoSurfaceView();
                if (player == null || surface == null) return;
                if (surface instanceof TextureView) player.setVideoTextureView((TextureView) surface);
                else if (surface instanceof SurfaceView) player.setVideoSurfaceView((SurfaceView) surface);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 一步到位：换渲染层 → 转 → 按画面实际尺寸算缩放。
     * 换过层之后新 surface 还没量出尺寸，隔一会儿按真实尺寸再算一次。
     */
    public static void rotate(PlayerView view, Player player, int degree) {
        if (view == null) return;
        applyRender(view, player, degree);
        view.setRotation(degree);
        view.post(() -> scale(view, player, degree));
        view.postDelayed(() -> scale(view, player, degree), 250);
        view.postDelayed(() -> scale(view, player, degree), 1200);
    }

    /**
     * 盯着尺寸变化，变了就按画面实际大小重算一次缩放。
     * <p>
     * 光盯 PlayerView 自己是不够的：它铺满窗口，尺寸常年不变；真正会变的是**画面那一层**
     * （AspectRatioFrameLayout，随片源比例伸缩）。屏幕广播接上流、切分辨率、换渲染层之后，
     * 都是这一层的尺寸在变，不盯着它就永远算不出正确的缩放。
     * <p>
     * 0° 时一概不插手：手机版有双指手势缩放，也在写 scaleX/scaleY，别把人家的放大清回去。
     *
     * @param degree 取当前角度（会随用户按键变，所以传取值的方法而不是固定值）
     */
    public static void watch(PlayerView view, Degree degree) {
        if (view == null) return;
        OnScale listener = () -> {
            int value = degree.get();
            if (value != 0) scale(view, value);
        };
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> listener.run());
        View frame = frame(view);
        if (frame != null && frame != view) frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> listener.run());
    }

    /** 画面那一层（surface 的父层）：它的尺寸才是片源比例说了算的那个 */
    private static View frame(PlayerView view) {
        View surface = view.getVideoSurfaceView();
        if (surface == null) return null;
        return surface.getParent() instanceof View ? (View) surface.getParent() : surface;
    }

    public interface Degree {
        int get();
    }

    private interface OnScale {
        void run();
    }

    public static void scale(PlayerView view, int degree) {
        scale(view, view == null ? null : view.getPlayer(), degree);
    }

    /**
     * 按**画面在窗口里的实际大小**算缩放，不能拿窗口尺寸算：竖屏片源在横屏窗口里只占中间一条
     * （720×1280 的短剧在 1600×900 的窗口里只有 506×900），拿窗口尺寸算出来的系数只有 0.56，
     * 转完就成巴掌大一小块。
     * 正确做法：转 90°/270° 后画面变成 (vh,vw)，再取「能把它整体塞进窗口的最大等比系数」
     * min(窗口宽/vh, 窗口高/vw) —— 这样转完一定是贴着屏幕的某一边铺满，另一边留黑边（不拉伸、不裁掉）。
     * <p>
     * 180° 转完跟窗口一样大，缩了反而把画面弄小，所以只有 90/270 才缩。
     * 旋转必须绕中心：手势缩放会把 pivot 挪到手指点，不掰回来画面会转出去。
     */
    public static void scale(PlayerView view, Player player, int degree) {
        if (view == null) return;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return;
        float[] box = box(view, player, width, height);
        float bw = box[0];
        float bh = box[1];
        if (bw <= 0 || bh <= 0) return;
        float scale = degree == 90 || degree == 270 ? Math.min(width / bh, height / bw) : 1f;
        view.setPivotX(width / 2f);
        view.setPivotY(height / 2f);
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    /**
     * 画面在窗口里实际占的那块（还没转的）。
     * <p>
     * 优先用**视频自己的比例**算：直播刚接上、或者用户先转了再等流的时候，
     * surface 压根还没量出尺寸（量出来的是 0，量晚了就是窗口大小），拿它算必错。
     * 视频比例是解出轨道就有的，比 surface 靠得住得多。
     */
    private static float[] box(PlayerView view, Player player, int width, int height) {
        float aspect = aspect(player);
        int mode = view.getResizeMode();
        // 填充 / 裁切这两种模式是铺满窗口的，画面那一块就是窗口大小，跟片源比例无关
        boolean fill = mode == AspectRatioFrameLayout.RESIZE_MODE_FILL || mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        if (!fill && aspect > 0) {
            float bw = aspect >= (float) width / height ? width : height * aspect;
            float bh = aspect >= (float) width / height ? width / aspect : height;
            return new float[]{bw, bh};
        }
        View surface = view.getVideoSurfaceView();
        if (surface != null && surface.getWidth() > 0 && surface.getHeight() > 0) {
            return new float[]{surface.getWidth(), surface.getHeight()};
        }
        return new float[]{width, height};
    }

    /** 视频自己的宽高比（算上像素宽高比），没有就给 0 */
    private static float aspect(Player player) {
        if (player == null) return 0;
        VideoSize size = player.getVideoSize();
        if (size == null || size.width <= 0 || size.height <= 0) return 0;
        float ratio = size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f;
        return size.width * ratio / size.height;
    }
}
