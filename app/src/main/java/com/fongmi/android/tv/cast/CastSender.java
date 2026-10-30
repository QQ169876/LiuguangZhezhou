package com.fongmi.android.tv.cast;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;

import java.util.Collections;
import java.util.Map;

/**
 * 广播端（设备 A）的主控面。
 *
 * 职责就三件：占端口开广播、每半秒把播放状态喂给 {@link CastOutlet}、收工时把端口吐出来。
 * 它**不碰播放器**，要什么信息都照 {@link CastSource} 问，因此不可能把 bug 带进现有播放流程。
 *
 * 中转地址始终会一起发给接收端：本机代取再转给对方，Referer / UA 一并带上，
 * 解决了「某些设备拉不到源」的老问题，也是组播 / 单播万一不稳时的退路。
 *
 * 源本身是 TS 的话，还会另起一路把字节直接按组播 / 单播抛出去（见 {@link CastTx}），
 * 省掉中转那一层、也省掉发送端的上行 —— 这一档由 {@link CastMode} 按源类型自动决定。
 */
public class CastSender {

    /** 状态同步间隔：比心跳（1 秒）密一点，接收端对位才不至于一跳一跳的 */
    private static final long SYNC_MS = 500L;
    /** 一直拿不到播放地址多久算「这部片子真发不出去」：屏幕广播兜底的判断线 */
    private static final long NO_SOURCE_MS = 5000L;

    private static final CastSender INSTANCE = new CastSender();

    public static CastSender get() {
        return INSTANCE;
    }

    private final Runnable loop = this::beat;

    private CastOutlet outlet;
    private CastSource source;
    private String shareUrl = "";
    private String shareRelay = "";
    private String shareKey = "";
    private long lastPosition = -1L;
    private String lastState = "";
    private long emptySince;
    private boolean hinted;
    private boolean running;

    public boolean sending() {
        return outlet != null && outlet.sending();
    }

    /** 当前挂着几台接收端，界面上显示用 */
    public int count() {
        return outlet == null ? 0 : outlet.count();
    }

    /** 现在实际走的是哪一档链路（界面上显示状态用） */
    public int mode() {
        return outlet == null ? CastMode.HTTP : outlet.mode();
    }

    /**
     * 开广播。
     *
     * @return true 表示占到了端口；已经在播时重复调用只会更新信息，不会开两个
     */
    public boolean start(CastSource source) {
        if (source == null) return false;
        this.source = source;
        if (outlet == null) outlet = new CastOutlet(CastPacket.KIND_VIDEO);
        // 先把当前这部片子的地址递进去：开播那一刻就能判断「能不能直传」，
        // 免得先按中转开起来、两秒后又切到组播，接收端跟着重启一次
        String url = source.getCastUrl();
        if (url != null && !url.isEmpty()) outlet.source(url, source.getCastHeaders());
        if (!outlet.open(source.getCastTitle(), source.getCastEpisode())) {
            stop();
            return false;
        }
        shareUrl = "";
        shareRelay = "";
        shareKey = "";
        lastPosition = -1L;
        lastState = "";
        emptySince = 0L;
        hinted = false;
        running = true;
        beat(); // 立刻喂一次，别让接收端空等第一个心跳
        return true;
    }

    /** 收工：先跟每台接收端打个招呼（它们好回待机），再把端口让出来 */
    public void stop() {
        running = false;
        App.removeCallbacks(loop);
        source = null;
        CastOutlet target = outlet;
        outlet = null;
        if (target == null) return;
        target.close();
    }

    /** 播放器要换集 / 换源时调一下：下一次包就会把新地址带出去 */
    public void notifyChanged() {
        shareUrl = "";
    }

    private void beat() {
        App.removeCallbacks(loop);
        if (!running) return;
        CastOutlet target = outlet;
        CastSource from = source;
        if (target != null && from != null && target.sending()) {
            try {
                sync(target, from);
            } catch (Throwable ignored) {
                // 广播不能影响本机播放：这里出错就跳过这一轮
            }
            if (!from.isCastAlive()) stop(); // 播放页没了就自动收工，省得占着端口
        }
        App.post(loop, SYNC_MS);
    }

    private void sync(CastOutlet target, CastSource from) {
        String title = from.getCastTitle();
        String ep = from.getCastEpisode();
        String url = from.getCastUrl();
        // 地址变了才重新算中转地址：这一步可能要起本机服务 + 拼请求头，没必要每半秒做一次
        if (url != null && !url.isEmpty() && !url.equals(shareUrl)) {
            target.source(url, from.getCastHeaders());
            Map<String, String> headers = from.getCastHeaders();
            shareRelay = relay(title, url, headers == null ? Collections.emptyMap() : headers);
            shareUrl = url;
            emptySince = 0L;
            hinted = false;
        } else if (url == null || url.isEmpty()) {
            noSource();
        }
        String key = title + "\n" + ep + "\n" + shareRelay + "\n" + String.valueOf(from.getCastSubs());
        if (!key.equals(shareKey)) {
            target.share(null, title, ep, shareRelay, Collections.emptyMap(), from.getCastSubs());
            shareKey = key;
        }
        long position = from.getCastPosition();
        if (position < 0) return; // 播放器还没给出位置，别拿负数去扰乱对端
        String state = from.isCastPlaying() ? CastPacket.STATE_PLAY : CastPacket.STATE_PAUSE;
        target.tick(position, state, from.getCastSpeed());
        // 拖动这种要马上生效的就别等心跳：直接补一条指令
        if (lastPosition >= 0 && Math.abs(position - lastPosition) > 2000L) target.command(CastPacket.ACT_SEEK, position);
        if (!state.equals(lastState)) target.command(state.equals(CastPacket.STATE_PLAY) ? CastPacket.ACT_PLAY : CastPacket.ACT_PAUSE, position);
        lastPosition = position;
        lastState = state;
    }

    /**
     * 一直拿不到源怎么办：按用户在设置里的选择给个台阶。
     *
     * 只有选了「总是」才会真的去弹系统的录屏授权 —— 那个授权每次都要点一下，
     * 不经询问就弹出来太突然了；默认那档（仅取不到原流时）这里只提示一句，
     * 让用户自己长按按钮去开启屏幕广播。
     */
    private void noSource() {
        if (emptySince == 0) emptySince = System.currentTimeMillis();
        if (System.currentTimeMillis() - emptySince < NO_SOURCE_MS) return;
        if (hinted) return;
        hinted = true;
        int choice = CastSetting.getScreen();
        if (choice == 2) ScreenCast.request();
        else if (choice == 1) Notify.show(R.string.cast_hint_screen);
    }

    /**
     * 中转地址：默认整条拼进 URL；拼出来太长（超过 UDP 包的 1400 字节还能留出头给别的字段）
     * 就换成短牌 {@code /cast?id=x}，具体地址留在发送端本机。
     */
    private String relay(String title, String url, Map<String, String> headers) {
        String relay = new CastVideo(title, url, 0L, headers).relayUrl();
        return relay.length() > 900 ? Server.get().getAddress() + "/cast?id=" + CastRelay.put(url, headers) : relay;
    }
}
