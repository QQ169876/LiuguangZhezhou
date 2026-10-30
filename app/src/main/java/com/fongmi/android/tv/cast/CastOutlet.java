package com.fongmi.android.tv.cast;

import com.fongmi.android.tv.utils.Util;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;

/**
 * 广播端这一侧的「总控」：一个插座插两桩事 —— 对外亮相（由 {@link CastBus} 负责）和发货（由 {@link CastTx} 负责）。
 *
 * 之所以把它单独拎出来而不是揉进某个界面里：
 * 视频广播和屏幕广播**两个入口都要用同一套逻辑**（挑链路、降级、管接收端名单），
 * 各写一遍的话，迟早会变成「视频这边降级了，屏幕那边还在死等」这种查不出来的毛病。
 *
 * 关于降级 —— 这是整套 UDP 方案的保险丝：
 * 组播省上行，但在家庭 Wi-Fi 上不保证通；一旦有接收端说「卡」或者「播不动」，
 * 就顺着 组播 → 单播 → 中转 往下掉，掉到中转一定能播为止。
 * 降下来之后**不会自己再往上试**：一会儿上一会儿下，用户看到的就是不停地黑一下。
 */
public final class CastOutlet implements CastBus.Listener {

    /** 连续几次「开始卡」才肯降级：偶尔一次抖动不代表这条链路不行 */
    private static final int BAD_LIMIT = 3;

    private final CastBus bus;
    private final CastTx tx;
    private final String kind;

    private volatile String url = "";
    private volatile Map<String, String> headers = Collections.emptyMap();
    private volatile int mode = CastMode.HTTP;
    private volatile String group = "";
    private volatile int mport;
    private volatile boolean tsSource;
    private volatile boolean alive;
    private int bad;

    public CastOutlet(String kind) {
        this.kind = kind == null ? CastPacket.KIND_VIDEO : kind;
        this.bus = new CastBus(this, Util.getAndroidId(), Util.getDeviceName());
        this.tx = new CastTx();
        this.bus.setAuth(CastSetting.getCode(), CastSetting.maxClientsLimit());
    }

    /* ---------------- 开关 ---------------- */

    /**
     * 占端口开广播。
     *
     * @return true 表示占到了；10086~10096 全被占着的话返回 false，让界面好给个说法
     */
    public boolean open(String title, String ep) {
        int port = bus.open(kind, title, ep);
        if (port < 0) {
            close();
            return false;
        }
        alive = true;
        // 屏幕那一路没有「源」这个概念，本身就是能直传的；视频则要等解析出地址才知道
        tsSource = CastPacket.KIND_SCREEN.equals(kind) || CastMode.isTs(url);
        pick();
        return true;
    }

    /** 本机换了 sources（换集、解析出了新的播放地址）：重新判断还能不能直传 */
    public void source(String url, Map<String, String> headers) {
        this.url = url == null ? "" : url;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        if (!alive) return;
        boolean before = tsSource;
        // 屏幕那一档不存在「源」的说法，本身就是能直传的
        tsSource = CastPacket.KIND_SCREEN.equals(kind) || CastMode.isTs(this.url);
        if (before != tsSource) {
            pick();
            return;
        }
        if (CastMode.isUdp(mode)) startTx(); // 换了流就把原来那个拉取线程换掉
    }

    public boolean sending() {
        return alive && bus.sending();
    }

    public int count() {
        return bus.receiverCount();
    }

    public int mode() {
        return mode;
    }

    public String group() {
        return group;
    }

    public int port() {
        return bus.port();
    }

    public void close() {
        alive = false;
        tx.close();
        bus.stopSend();
        bus.close();
    }

    /* ---------------- 往外给 ---------------- */

    public void share(String kind, String title, String ep, String url, Map<String, String> headers, Map<String, String> subs) {
        bus.share(kind, title, ep, url, headers, subs);
    }

    public void tick(long position, String state, float speed) {
        bus.tick(position, state, speed);
    }

    public void command(String act, long position) {
        bus.command(act, position);
    }

    /** 屏幕编码用：把打包好的 TS 字节流推进去 */
    public void write(byte[] data, int len) {
        tx.write(data, len);
    }

    public void flush() {
        tx.flush();
    }

    /* ---------------- 挑链路 ---------------- */

    /**
     * 按「源能不能直传 + 用户偏好」选一档起步线路。
     * 不是 TS（mp4 / mkv 之类）就只有中转一条路：
     * 那些容器得重新封装才能流式发，现阶段没做，也不打算做。
     */
    private void pick() {
        mode = CastMode.initial(CastSetting.getTransport(), tsSource);
        // 屏幕那一路没有「源地址」可中转，万一用户把偏好选成了只走中转，这里得兜回组播
        if (mode == CastMode.HTTP && CastPacket.KIND_SCREEN.equals(kind)) mode = CastMode.MULTICAST;
        bad = 0;
        group = CastMode.group(Util.getAndroidId() + "|" + System.nanoTime());
        apply();
        if (CastMode.isUdp(mode)) startTx();
        else tx.close();
    }

    private void startTx() {
        if (mode == CastMode.MULTICAST && mport <= 0) mport = CastTx.freePort();
        if (mode == CastMode.MULTICAST && mport <= 0) {
            mode = CastMode.UNICAST; // 端口一个都空不出来：别愣着，换单播
            apply();
        }
        if (CastPacket.KIND_SCREEN.equals(kind)) tx.open(mode, group, mport, "", Collections.emptyMap());
        else tx.open(mode, group, mport, url, headers);
    }

    private void apply() {
        bus.transport(mode, mode == CastMode.MULTICAST ? group : "", mport);
    }

    /** 保险丝：有接收端喊卡就往下掉一档 */
    private void down() {
        int next = CastMode.fallback(mode);
        bad = 0;
        if (next == CastMode.NONE) return;
        // 屏幕广播降无可降：单播就是它的底线，再往下降（中转）就没有画面了
        if (next == CastMode.HTTP && CastPacket.KIND_SCREEN.equals(kind)) return;
        mode = next;
        apply();
        if (CastMode.isUdp(mode)) tx.retarget(mode, mode == CastMode.MULTICAST ? group : "", mport);
        else tx.close();
    }

    /* ---------------- 总线回调 ---------------- */

    @Override
    public void onFound(CastHost host) {
    }

    @Override
    public void onPacket(CastPacket packet) {
    }

    @Override
    public void onLost() {
    }

    @Override
    public void onScanEnd(int count) {
    }

    @Override
    public void onPeer(String uuid, InetAddress ip, int port) {
        if (ip != null) tx.peer(uuid, ip.getHostAddress(), port);
    }

    @Override
    public void onPeerGone(String uuid) {
        tx.peerGone(uuid);
    }

    @Override
    public void onQuality(int quality) {
        if (!CastMode.isUdp(mode)) return;
        if (quality >= 2) {
            down(); // 直接播不动了：一刻不等
            return;
        }
        if (++bad >= BAD_LIMIT) down();
    }
}
