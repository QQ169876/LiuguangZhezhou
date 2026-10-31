package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.PlayerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.cast.CastBus;
import com.fongmi.android.tv.cast.CastHost;
import com.fongmi.android.tv.cast.CastMode;
import com.fongmi.android.tv.cast.CastNet;
import com.fongmi.android.tv.cast.CastPacket;
import com.fongmi.android.tv.cast.CastSetting;
import com.fongmi.android.tv.cast.CastSync;
import com.fongmi.android.tv.cast.CastTx;
import com.fongmi.android.tv.databinding.ActivityReceiveBinding;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.DebugLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接收广播的全屏页。
 *
 * 这一页**没有播放控制**：进度、暂停、换集全听广播端的，界面上只留两个本地操作
 * —— 旋转画面（每台设备各转各的）和退出。
 *
 * 顶栏（左边在播什么 + 右边那两个按钮）走的是普通播放器那一套：点一下屏或者按一下遥控器就浮出来，
 * 几秒没人动自己淡下去，不挡画面；待机等人来的时候它一直挂着，不然只剩一块黑屏没人知道该干嘛。
 *
 * 状态上有三种：扫描中 → 跟播中 → 待机（广播端收工了）
 * 从「待机」回到「跟播」是自动的：广播端一回来，自己就接上并接着播。
 *
 * 取流有三条路（见 {@link CastMode}），由广播端说了算，这边只管照着地址开播：
 * - 中转：http 拉广播端本机的 /cast；
 * - 组播：udp://组地址:端口，加入这个组；
 * - 单播：udp://本机IP:本机端口，报端口给广播端，让它往这儿发。
 * ⚠️ 后两种在部分 AP 上可能收不稳，这时候本页会把质量反馈打回去，由广播端统一往下降一档。
 *
 * 「连上了却只有一块黑屏」是最难猜的一种毛病，所以这里先看住它：
 * 起播之后盯着有没有真的画面出来，没有就把原因写在顶栏（哪条链路 / 缓冲还是出错），
 * 同时催广播端换链路；出错信息也留在屏上，比一闪而过的 toast 好认得多。
 */
public class ReceiveActivity extends BaseActivity implements CastBus.Listener {

    private static final String TAG = "CastRx";

    /** 待机时重扫的间隔 */
    private static final long RETRY_MS = 3000L;
    /** 单播端口的取材范围 */
    private static final int PORT_FROM = 41100;
    private static final int PORT_TO = 41300;
    /** 顶栏多久没人动就淡下去 */
    private static final long MENU_MS = 4000L;
    /** 看门狗多久来看一眼 */
    private static final long WATCH_MS = 5000L;
    /** 超过这个时间还没画面，就算这条链路不行了 */
    private static final long NO_FRAME_MS = 12000L;
    /** 同一条地址最多重开几次 —— UDP 接晚了常常重开一次就好了 */
    private static final int MAX_RETRY = 3;

    private ActivityReceiveBinding binding;
    private CastBus bus;
    private ExoPlayer player;
    private String playUrl = "";
    private String playTitle = "";
    private String playEp = "";
    private String lastUuid = "";
    private boolean standby = true;
    private volatile boolean scanning;
    private int degree;
    private int stalls;
    private int clean;
    private int dataPort;
    private int lastMode = CastMode.HTTP;
    private int retries;
    private int askedMode = CastMode.NONE;
    private long askedAt;
    private long sincePlay;
    private int probed;
    private AlertDialog chooser;
    private String dataUrl = "";
    private WifiManager.MulticastLock lock;
    private final Runnable retry = this::rescan;
    private final Runnable hide = this::fadeOutMenu;
    private final Runnable watchdog = this::checkPlaying;

    public static void start(Context context) {
        context.startActivity(new Intent(context, ReceiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @NonNull
    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityReceiveBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.standbyText.setText(R.string.cast_receive_scanning);
        binding.status.setText("");
        binding.hint.setText("");
        bus = new CastBus(this, Util.getAndroidId(), Util.getDeviceName());
        bus.setAuth(CastSetting.getCode(), 0); // 接收端自身没有人数上限的说法，这里只用来配配对码
        showMenu(); // 待机页得一直挂着那两个按钮，别让人对着黑屏发愣
        startScan(Collections.emptyList());
    }

    @Override
    protected void initEvent() {
        binding.exit.setOnClickListener(view -> finish());
        binding.rotate.setOnClickListener(view -> rotate());
        binding.standbyManual.setOnClickListener(view -> connectManual());
        binding.receiveLayout.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) pokeMenu();
            return false; // 别把点击吃掉：按钮自己还得响应
        });
    }

    /* ============================ 顶栏显隐 ============================ */

    /** 屏幕上有动静：没影的就浮出来，已经在屏上的重新开始倒计时 */
    private void pokeMenu() {
        if (binding.topBar.getVisibility() != View.VISIBLE) {
            showMenu();
            return;
        }
        App.removeCallbacks(hide);
        App.post(hide, MENU_MS);
    }

    private void showMenu() {
        App.removeCallbacks(hide);
        if (binding.topBar.getVisibility() != View.VISIBLE) {
            binding.topBar.setVisibility(View.VISIBLE);
            binding.topBar.setAlpha(0f);
        }
        binding.topBar.animate().alpha(1f).setDuration(180).start();
        App.post(hide, MENU_MS);
    }

    private void fadeOutMenu() {
        App.removeCallbacks(hide);
        // 到点就收，不因为「焦点正好停在按钮上」而一直续命 ——
        // 遥控器一进来焦点默认就落在「旋转画面」上，那样一辈子也收不掉（马先生 2026-10-31 指出）。
        // 焦点先还给容器，按任意键唤出来时再自动落回按钮。
        if (binding.rotate.hasFocus() || binding.exit.hasFocus()) {
            binding.rotate.clearFocus();
            binding.exit.clearFocus();
        }
        binding.topBar.animate().alpha(0f).setDuration(220).withEndAction(() -> binding.topBar.setVisibility(View.INVISIBLE)).start();
    }

    /** 遥控器：按一下先把顶栏叫出来（退出键除外，该让人出去的还是得出去） */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (binding.topBar.getVisibility() != View.VISIBLE) {
                showMenu();
                // 刚收掉的顶栏，焦点跟着一起交还过：唤出来时得把它放回按钮上，不然方向键按不出动静
                if (!binding.rotate.hasFocus() && !binding.exit.hasFocus()) binding.rotate.requestFocus();
                return true;
            }
            App.removeCallbacks(hide);
            App.post(hide, MENU_MS);
        }
        return super.dispatchKeyEvent(event);
    }

    /** 顶栏第二行：把「现在到底怎么了」说清楚，别让用户对着一块黑屏自己猜 */
    private void setHint(String text) {
        if (binding.hint.getText().toString().equals(text)) return;
        binding.hint.setText(text);
        if (!text.isEmpty()) showMenu(); // 有话要说就把顶栏浮出来，说完自然会收
    }

    private String linkText(int mode) {
        return ResUtil.getString(R.string.cast_receive_link, CastMode.text(mode));
    }

    /* ============================ 扫描与选择 ============================ */

    /**
     * @param targets 空 = 扫本机所有网段；不为空 = 只扫这几个 IP（用户在待机页手填的地址）
     */
    private void startScan(List<String> targets) {
        if (bus == null) return;
        if (targets != null && !targets.isEmpty()) {
            scan(targets);
            return;
        }
        // 枚举本机所有网卡和网段是个费时的活儿，丢到后台去算，算完再回主线程发探测包
        new Thread(() -> {
            List<String> list = CastNet.targets();
            App.post(() -> scan(list));
        }).start();
    }

    private void scan(List<String> list) {
        if (bus == null) return;
        if (list.isEmpty()) {
            binding.standbyText.setText(R.string.cast_receive_wait);
            diag(0, 0);
            return;
        }
        scanning = true;
        probed = list.size();
        diag(probed, bus.hosts().size());
        bus.scan(list);
    }

    /**
     * 待机页上的自检数据：本机 IP、这一轮撒了多少个地址、回来几个包。
     *
     * 为什么写在屏上而不是只在日志里：老盒子（这次的小米盒子就是 Android 6）那类设备
     * 不一定开着调试日志，而这种「这台机器看不见那台机器」的毛病，
     * 一眼看到「本机 IP 多少、几个回包」，比翻日志快得多 —— 「0 个回包」和「根本没拿到 IP」完全是两种修法。
     */
    private void diag(int probedCount, int replies) {
        Set<String> ips = CastNet.selfIps();
        String head = ips.isEmpty() ? getString(R.string.cast_receive_diag_noip)
                : ResUtil.getString(R.string.cast_receive_diag_self, TextUtils.join(" / ", ips));
        String text = head + "\n" + ResUtil.getString(R.string.cast_receive_diag_scan, probedCount, replies);
        if (replies == 0 && !ips.isEmpty()) text += "\n" + getString(R.string.cast_receive_diag_tip);
        binding.standbyInfo.setText(text);
        DebugLog.d(TAG, "自检 IP=" + TextUtils.join(",", ips) + " 探了 " + probedCount + " 个 / 回包 " + replies + " 个");
    }

    private void dismissChooser() {
        if (chooser != null && chooser.isShowing()) chooser.dismiss();
        chooser = null;
    }

    private void connectManual() {
        String text = binding.standbyInput.getText().toString().trim();
        if (text.isEmpty()) return;
        startScan(Collections.singletonList(text));
    }

    /** 待机时反复扫，广播端一回来就能自动接上 */
    private void rescan() {
        if (!standby || bus == null) return;
        if (scanning) return; // 上一轮还没扫完就不叠加，免得把路由器按住不放
        // 重扫的时候回到整网扫描，别老盯着上一次填的地址
        startScan(Collections.emptyList());
    }

    private void scheduleRetry() {
        App.removeCallbacks(retry);
        App.post(retry, RETRY_MS);
    }

    private void pick(List<CastHost> hosts) {
        if (hosts.isEmpty()) {
            binding.standbyText.setText(R.string.cast_receive_wait);
            scheduleRetry(); // 一台都没有：过几秒再扫一次，人家可能还没开广播
            return;
        }
        if (CastSetting.isAutoJoin() && hosts.size() == 1) {
            join(hosts.get(0));
            return;
        }
        if (chooser != null && chooser.isShowing()) return; // 清单还在屏上就别再叠一层
        List<CastHost> items = new ArrayList<>(hosts);
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            CastHost item = items.get(i);
            String playing = item.getPlaying();
            labels[i] = playing.isEmpty() ? item.getLabel() : item.getLabel() + "　" + playing;
        }
        // 取消了也别把这一页卡死：继续按周期重扫，用户改主意时列表会自己回来
        chooser = new AlertDialog.Builder(this).setTitle(R.string.cast_select_host)
                .setItems(labels, (dialog, which) -> join(items.get(which)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> scheduleRetry())
                .setOnCancelListener(dialog -> scheduleRetry())
                .show();
    }

    private void join(CastHost host) {
        if (bus == null || host == null) return;
        dismissChooser();
        lastUuid = host.getUuid();
        standby = false;
        pickDataPort(host.getIp()); // 单播那一档要先把自己的收包端口告诉对方，所以得赶在报名之前
        bus.join(host);
        binding.standby.setVisibility(View.GONE);
        binding.status.setText(ResUtil.getString(R.string.cast_receive_connected, host.getLabel()));
        setHint("");
        DebugLog.d(TAG, "接入广播端 " + host.getLabel() + " " + host.getIp());
        showMenu();
    }

    /** 回到待机：广播端收工了 / 联系不上了。不退出，等着它回来 */
    private void idle() {
        standby = true;
        App.removeCallbacks(retry);
        App.removeCallbacks(watchdog);
        playUrl = "";
        releaseLock();
        dismissChooser();
        if (player != null) player.setPlayWhenReady(false);
        binding.standby.setVisibility(View.VISIBLE);
        binding.standbyText.setText(R.string.cast_receive_wait);
        binding.status.setText("");
        setHint("");
        DebugLog.d(TAG, "回到待机，等广播端回来");
        showMenu();
        scheduleRetry();
    }

    /* ============================ 跟播 ============================ */

    @Override
    public void onFound(CastHost host) {
        App.post(() -> {
            if (!standby || host == null) return;
            // ⚠️ 慢机器（老盒子）上，回包常常迟到若扫描窗口之后：它肯出现就是一次机会，别放过
            diag(probed, bus == null ? 0 : bus.hosts().size());
            DebugLog.d(TAG, "扫到 " + host.getLabel() + " " + host.getIp() + " 正在放：" + host.getPlaying());
            if (bus != null && CastSetting.isAutoJoin() && bus.hosts().size() == 1) join(host);
            else if (host.getUuid().equals(lastUuid)) join(host);
        });
    }

    @Override
    public void onScanEnd(int count) {
        scanning = false;
        App.post(() -> {
            if (standby) pick(bus == null ? Collections.emptyList() : bus.hosts());
        });
    }

    @Override
    public void onPacket(CastPacket packet) {
        // ⚠️ 收包是在总线自己的线程里，播放器和界面都只能主线程碰
        App.post(() -> consume(packet));
    }

    @Override
    public void onLost() {
        App.post(this::idle);
    }

    @Override
    public void onPeer(String uuid, java.net.InetAddress ip, int port) {
    }

    @Override
    public void onPeerGone(String uuid) {
    }

    @Override
    public void onQuality(int quality) {
    }

    private void consume(CastPacket packet) {
        if (packet.is(CastPacket.TICK)) tick(packet);
        else if (packet.is(CastPacket.CMD)) command(packet);
        else if (packet.is(CastPacket.BYE)) idle();
        else if (packet.is(CastPacket.DENY)) Notify.show(packet.msg());
    }

    private void tick(CastPacket packet) {
        String url = resolve(packet);
        lastMode = packet.mode();
        DebugLog.d(TAG, "心跳 mode=" + CastMode.text(packet.mode()) + " state=" + packet.state() + " pos=" + packet.position() + " url=" + mask(url));
        if (url.isEmpty()) {
            // 只有位置没有地址：要么广播端还没拿到播放地址（等下一个包就行），
            // 要么是本机的收包通道没开出来（UDP 那两档要占端口 / 认自己的 IP），后者得让广播端改走中转
            if (!CastMode.isUdp(packet.mode())) return;
            setHint(getString(R.string.cast_receive_no_link));
            DebugLog.d(TAG, "本机没算出收包地址（udp）mode=" + CastMode.text(packet.mode()));
            askDowngrade();
            return;
        }
        if (!url.equals(playUrl)) {
            playUrl = url;
            stalls = 0;
            clean = 0;
            retries = 0;
            playTitle = packet.title();
            playEp = packet.ep();
            // ⚠️ UDP 那两档是「直播管道」：收到什么播什么，不能 seek —— 底下会自己说清楚。
            // 而且**必须张嘴就播**：广播端报上来的 state 是它自己那台设备的播放状态，
            // 屏幕广播根本没有「播放器」，那字段一直是 pause —— 照着它把 playWhenReady 设成 false，
            // 播放器就连包都不去收，界面上就是一块永远「缓冲中」的黑屏（这次日志里就是这么黑的）。
            boolean udp = CastMode.isUdp(packet.mode());
            // 屏幕广播（含 HTTP 主动拉流）也是直播管道：张嘴就播、从头播、不 seek
            boolean live = udp || CastPacket.KIND_SCREEN.equals(packet.kind());
            play(url, live ? 0 : packet.position(), live || CastPacket.STATE_PLAY.equals(packet.state()), packet.speed(), packet.subs());
            return;
        }
        follow(packet);
    }

    /** 地址又长又带 token，日志里留个头尾就够了 */
    private String mask(String url) {
        if (url.isEmpty()) return "(空)";
        if (url.length() <= 48) return url;
        return url.substring(0, 32) + "…" + url.substring(url.length() - 12);
    }

    /**
     * 按广播端说的链路，算出本机该从哪儿拉流。
     *
     * UDP 那两档地址是收发双方各算一半的：组播那边给地址和端口，单播这边报自己的端口，
     * 接收端只是把它们拼成一个 {@code udp://} 的地址交给播放器 ——
     * media3 自带的 UdpDataSource 认得这个协议，也会自己去 join 组播组。
     */
    private String resolve(CastPacket packet) {
        int mode = packet.mode();
        if (mode == CastMode.MULTICAST) {
            String group = packet.group();
            int port = packet.mport();
            return group.isEmpty() || port <= 0 ? "" : "udp://" + group + ":" + port;
        }
        if (mode == CastMode.UNICAST) return dataUrl;
        return packet.url();
    }

    private void command(CastPacket packet) {
        if (player == null) return;
        String act = packet.act();
        if (CastPacket.ACT_PAUSE.equals(act)) player.setPlayWhenReady(false);
        else if (CastPacket.ACT_PLAY.equals(act)) player.setPlayWhenReady(true);
        else if (CastPacket.ACT_SEEK.equals(act)) seek(packet.position());
        else if (CastPacket.ACT_STOP.equals(act)) idle();
        else if (CastPacket.STATE_PLAY.equals(packet.state())) player.setPlayWhenReady(true);
        else if (CastPacket.STATE_PAUSE.equals(packet.state())) player.setPlayWhenReady(false);
    }

    private void seek(long position) {
        try {
            if (player != null) player.seekTo(position);
        } catch (Throwable ignored) {
            // 组播 / 单播是直播流，不支持拖动，忽略就好
        }
    }

    /** 按心跳对位：差得少不动，差几秒悄悄变速追，差太多直接 seek */
    private void follow(CastPacket packet) {
        if (player == null) return;
        int mode = packet.mode();
        boolean udp = CastMode.isUdp(mode);
        // 屏幕广播（含 HTTP 拉流）跟 UDP 一样是直播管道：只跟播/暂停，不对位、不 seek
        boolean live = udp || CastPacket.KIND_SCREEN.equals(packet.kind());
        // 直播管道同理：state 只管视频播放器那一档，屏幕/ UDP 一律当作「一直在播」，
        // 否则广播端一句 pause 就能把接收端按死成黑屏
        boolean shouldPlay = live || CastPacket.STATE_PLAY.equals(packet.state());
        if (player.getPlayWhenReady() != shouldPlay) player.setPlayWhenReady(shouldPlay);
        // ⚠️ 直播流收到的就是广播端此刻发出的那一包，**所有接收端天然同步**，
        // 这里再拿心跳里的 position 去对位毫无意义，只会把画面按在地上摩擦。
        if (live) {
            report(mode);
            syncTitle(packet);
            return;
        }
        long local = player.getCurrentPosition();        CastSync.Result plan = CastSync.plan(System.currentTimeMillis(), packet.time(), packet.position(), local, packet.speed());
        switch (plan.action) {
            case SEEK -> seek(plan.target);
            case FASTER, SLOWER -> {
                if (player.getPlaybackParameters().speed != plan.speed) player.setPlaybackParameters(new PlaybackParameters(plan.speed));
            }
            case NONE -> {
                float base = packet.speed() > 0 ? packet.speed() : 1f;
                if (player.getPlaybackParameters().speed != base) player.setPlaybackParameters(new PlaybackParameters(base));
            }
        }
        report(mode);
        syncTitle(packet);
    }

    /** 换集了就更新一下标题，别每次心跳都刷一次界面 */
    private void syncTitle(CastPacket packet) {
        if (playTitle.equals(packet.title()) && playEp.equals(packet.ep())) return;
        playTitle = packet.title();
        playEp = packet.ep();
        binding.status.setText(ResUtil.getString(R.string.cast_receive_connected, playTitle.isEmpty() ? "..." : playTitle + "　" + playEp));
    }

    /**
     * 把「这条链路到底行不行」打回给广播端，让它决定要不要降级。
     *
     * ⚠️ 只有 UDP 那两档才需要反馈 —— 中转那条路本来就是最终的兜底，再报也没地方可降了。
     */
    private void report(int mode) {
        if (bus == null || !CastMode.isUdp(mode)) return;
        if (stalls >= 2) {
            stalls = 0;
            clean = 0;
            bus.report(1); // 开始卡了
            return;
        }
        // 连续十几秒没再卡：把之前的负面印象抹掉，不然一次偶发卡顿会把链路一路降到中转
        if (++clean >= 15) {
            clean = 0;
            bus.report(0);
        }
    }

    private void play(String url, long position, boolean playWhenReady, float speed, Map<String, String> subs) {
        ExoPlayer target = ensurePlayer();
        if (target == null) return;
        target.setMediaItem(media(url, subs));
        target.setPlaybackParameters(new PlaybackParameters(speed > 0 ? speed : 1f));
        grabLock(url);
        target.prepare();
        if (position > 0) seek(position); // 0 = 从头播（直播管道也走这条），不用多此一举去 seek
        target.setPlayWhenReady(playWhenReady);
        binding.status.setText(ResUtil.getString(R.string.cast_receive_connected, playTitle.isEmpty() ? playEp : playTitle + "　" + playEp));
        setHint(linkText(lastMode));
        DebugLog.d(TAG, "起播 " + CastMode.text(lastMode) + " " + mask(url));
        App.removeCallbacks(watchdog);
        sincePlay = System.currentTimeMillis();
        App.post(watchdog, WATCH_MS);
    }

    /**
     * 看门狗：地址给了、流也起了，可画面迟迟不来 —— 这种最容易变成「黑屏干等」。
     *
     * 到点问一句：还没就绪就是在拉流（写清楚是哪条链路）；就绪了却没有视频轨，
     * 说明这条路上压根没画面；超过 {@link #NO_FRAME_MS} 就催广播端换一档链路（降级顺序见 {@link CastMode}）。
     */
    private void checkPlaying() {
        if (standby || player == null) return;
        int state = player.getPlaybackState();
        boolean video = hasVideoTrack();
        if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
            setHint(video ? "" : getString(R.string.cast_receive_no_video));
            if (!video) App.post(watchdog, WATCH_MS); // 只有声音的话再等等，别急着下结论
            return;
        }
        long waited = System.currentTimeMillis() - sincePlay;
        DebugLog.d(TAG, "起播 " + waited + "ms 仍未出画 state=" + name(state) + " mode=" + CastMode.text(lastMode) + " 视频轨=" + video);
        if (waited > NO_FRAME_MS) {
            setHint(linkText(lastMode) + " · " + getString(R.string.cast_receive_stall));
            askDowngrade(); // 直接催它降一档，省得在这儿干等
        } else {
            setHint(linkText(lastMode) + " · " + getString(R.string.cast_receive_opening));
        }
        App.post(watchdog, WATCH_MS);
    }

    /**
     * 催广播端降一档链路。
     *
     * 之所以要掐着节奏：接收端每次打招呼都会捎上「我这儿不行」，广播端那边见一次就降一档，
     * 要是每秒都喊，组播 → 单播 → 中转会在几秒内一口气降到底，等于没给中间那档机会。
     * 所以同一条链路只催一次，换过链路（或隔了一个窗口还没动静）才再开口。
     */
    private void askDowngrade() {
        long now = System.currentTimeMillis();
        if (bus == null) return;
        if (lastMode == askedMode && now - askedAt < NO_FRAME_MS) return;
        askedMode = lastMode;
        askedAt = now;
        DebugLog.d(TAG, "催广播端降链路，当前 " + CastMode.text(lastMode));
        bus.report(2);
    }

    /** 现在有没有真正的画面轨：那种「只有声音没有图像」的黑屏，靠它认出来 */
    private boolean hasVideoTrack() {
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return true;
        }
        return false;
    }

    /** UDP 是断一下就接不回来的直播管道：出错时重新开一次，接晚了常常一次就救回来 */
    private void retryOnce() {
        if (retries++ >= MAX_RETRY) return;
        App.post(() -> {
            if (standby || player == null) return;
            DebugLog.d(TAG, "重新起播一次 mode=" + CastMode.text(lastMode));
            setHint(linkText(lastMode) + " · " + getString(R.string.cast_receive_retry));
            player.prepare();
            sincePlay = System.currentTimeMillis();
            App.post(watchdog, WATCH_MS);
        }, 1500L);
    }

    /** 外挂字幕跟着过去：广播端挂了什么就列什么，一个 Baltics 都不用改 */
    private MediaItem media(String url, Map<String, String> subs) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(Uri.parse(url));
        List<MediaItem.SubtitleConfiguration> list = new ArrayList<>();
        if (subs != null) {
            for (Map.Entry<String, String> entry : subs.entrySet()) {
                String target = entry.getValue();
                if (target == null || !target.startsWith("http")) continue; // 本机文件路径在别的机器上不存在
                String label = entry.getKey();
                list.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(target))
                        .setLabel(TextUtils.isEmpty(label) ? target : label)
                        .setMimeType(TrackUtil.getSubtitleMimeType(target))
                        .setLanguage("zh")
                        .build());
            }
        }
        if (!list.isEmpty()) builder.setSubtitleConfigurations(list);
        return builder.build();
    }

    /**
     * 组播必须拿这把锁：安卓默认会把组播包滤掉，
     * 不拿锁的话「有的机器收得到有的收不到」，查一整天也查不出为什么。
     */
    private void grabLock(String url) {
        if (!url.startsWith("udp://")) return;
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return;
            if (lock == null) lock = manager.createMulticastLock("oktv-cast");
            if (!lock.isHeld()) lock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseLock() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 单播端口：挑一个没人占的，交给播放器去绑，并把端口号报给广播端。
     *
     * 为什么要在报名之前定下来：广播端那边一见到 JOIN 就开始往这个端口发货，
     * 晚一步报，前面几个包就落在没人听的端口上了 —— TS 本身能从中间接起来，丢几个包不致命，但没必要白丢。
     */
    private void pickDataPort(String hostIp) {
        if (dataPort > 0) return;
        for (int port = PORT_FROM; port < PORT_TO; port++) {
            if (CastTx.usable(port)) {
                dataPort = port;
                break;
            }
        }
        if (dataPort <= 0) return;
        String local = CastNet.localFor(hostIp);
        if (local.isEmpty()) return; // 连自己的 IP 都认不出来，单播这条路走不通，交给广播端降级
        dataUrl = "udp://" + local + ":" + dataPort;
        if (bus != null) bus.setDataPort(dataPort);
    }

    /**
     * 起播放器。
     *
     * ⚠️ 这里刻意照着本机播片那套参数配：解码失败允许回落、挂上 FFmpeg 那几个扩展解码、
     * TS 的时间戳搜索范围放大到默认的十倍、取流走本机那套 OkHttp。
     * 早先为省事用 {@code new ExoPlayer.Builder(this)} 的默认值，结果不少机器上就是
     * 「连上了但只有一块黑屏」——缺的不是地址，是把这条流接下来扯到底的那点配置。
     */
    private ExoPlayer ensurePlayer() {
        if (player != null) return player;
        RenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        ExoPlayer created = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sourceFactory()).build();
        created.setAudioAttributes(AudioAttributes.DEFAULT, true);
        created.setHandleAudioBecomingNoisy(true);
        created.setRepeatMode(Player.REPEAT_MODE_OFF);
        created.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                DebugLog.d(TAG, "播放器状态 " + name(playbackState) + " mode=" + CastMode.text(lastMode) + " 视频轨=" + hasVideoTrack());
                if (playbackState == Player.STATE_BUFFERING) {
                    stalls++;
                    setHint(linkText(lastMode) + " · " + getString(R.string.cast_receive_opening));
                } else if (playbackState == Player.STATE_READY) {
                    setHint(hasVideoTrack() ? "" : getString(R.string.cast_receive_no_video));
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                String msg = String.valueOf(error.getMessage());
                Notify.show(msg);
                // 出错原因留在屏幕上：toast 一闪而过，事后没人说得清刚才那块黑屏是怎么来的
                setHint(linkText(lastMode) + " · " + msg);
                DebugLog.d(TAG, "播放出错 " + msg + " mode=" + CastMode.text(lastMode) + " url=" + mask(playUrl));
                // 播不动：立刻让广播端知道，它会顺着往下掉一档链路
                stalls = 0;
                clean = 0;
                askedAt = 0L; // 出错跟干等是两回事，这里的催额度重新算
                askDowngrade();
                retryOnce(); // UDP 接晚了不一定是真出错，重新起一次常常就好了
            }
        });
        binding.player.setPlayer(created);
        player = created;
        return created;
    }

    private MediaSource.Factory sourceFactory() {
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(OkHttp.player());
        DataSource.Factory data = new DefaultDataSource.Factory(App.get(), http);
        ExtractorsFactory extractors = new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
        return new DefaultMediaSourceFactory(data, extractors);
    }

    private static String name(int state) {
        if (state == Player.STATE_IDLE) return "idle";
        if (state == Player.STATE_BUFFERING) return "buffering";
        if (state == Player.STATE_READY) return "ready";
        return "ended";
    }

    /* ============================ 旋转 ============================ */

    /** 旋转画面：每台设备自己转自己的，不影响别的屏，也不关广播端的事 */
    private void rotate() {
        degree = (degree + 90) % 360;
        PlayerView view = binding.player;
        view.setRotation(degree);
        view.post(this::applyScale);
    }

    /** 转 90/270 度后画面会变窄，按屏幕长短边比拉回来才满屏 */
    private void applyScale() {
        PlayerView view = binding.player;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return;
        float scale = degree % 180 == 0 ? 1f : Math.max(width, height) / (float) Math.max(1, Math.min(width, height));
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    /* ============================ 生命周期 ============================ */

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && !standby) player.setPlayWhenReady(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.removeCallbacks(retry);
        App.removeCallbacks(hide);
        App.removeCallbacks(watchdog);
        releaseLock();
        dismissChooser();
        if (bus != null) {
            bus.leave();
            bus.close();
            bus = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    /** TV 上用遥控器：退出键直接退不商量，别让人困在这一页 */
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }

    @Override
    protected boolean customWall() {
        return false;
    }
}
