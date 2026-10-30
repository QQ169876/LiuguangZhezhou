package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
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
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 接收广播的全屏页。
 *
 * 这一页**没有播放控制**：进度、暂停、换集全听广播端的，界面上只留两个本地操作
 * —— 旋转画面（每台设备各转各的）和退出。
 *
 * 状态上有三种：扫描中 → 跟播中 → 待机（广播端收工了）
 * 从「待机」回到「跟播」是自动的：广播端一回来，自己就接上并接着播。
 *
 * 取流有三条路（见 {@link CastMode}），由广播端说了算，这边只管照着地址开播：
 * - 中转：http 拉广播端本机的 /cast；
 * - 组播：udp://组地址:端口，加入这个组；
 * - 单播：udp://本机IP:本机端口，报端口给广播端，让它往这儿发。
 * ⚠️ 后两种在部分 AP 上可能收不稳，这时候本页会把质量反馈打回去，由广播端统一往下降一档。
 */
public class ReceiveActivity extends BaseActivity implements CastBus.Listener {

    /** 待机时重扫的间隔 */
    private static final long RETRY_MS = 3000L;
    /** 单播端口的取材范围 */
    private static final int PORT_FROM = 41100;
    private static final int PORT_TO = 41300;

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
    private String dataUrl = "";
    private WifiManager.MulticastLock lock;
    private final Runnable retry = this::rescan;

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
        bus = new CastBus(this, Util.getAndroidId(), Util.getDeviceName());
        bus.setAuth(CastSetting.getCode(), 0); // 接收端自身没有人数上限的说法，这里只用来配配对码
        startScan(Collections.emptyList());
    }

    @Override
    protected void initEvent() {
        binding.exit.setOnClickListener(view -> finish());
        binding.rotate.setOnClickListener(view -> rotate());
        binding.standbyManual.setOnClickListener(view -> connectManual());
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
            return;
        }
        scanning = true;
        bus.scan(list);
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
        List<CastHost> items = new ArrayList<>(hosts);
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            CastHost item = items.get(i);
            String playing = item.getPlaying();
            labels[i] = playing.isEmpty() ? item.getLabel() : item.getLabel() + "　" + playing;
        }
        // 取消了也别把这一页卡死：继续按周期重扫，用户改主意时列表会自己回来
        new AlertDialog.Builder(this).setTitle(R.string.cast_select_host)
                .setItems(labels, (dialog, which) -> join(items.get(which)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> scheduleRetry())
                .setOnCancelListener(dialog -> scheduleRetry())
                .show();
    }

    private void join(CastHost host) {
        if (bus == null || host == null) return;
        lastUuid = host.getUuid();
        standby = false;
        pickDataPort(host.getIp()); // 单播那一档要先把自己的收包端口告诉对方，所以得赶在报名之前
        bus.join(host);
        binding.standby.setVisibility(View.GONE);
        binding.status.setText(ResUtil.getString(R.string.cast_receive_connected, host.getLabel()));
    }

    /** 回到待机：广播端收工了 / 联系不上了。不退出，等着它回来 */
    private void idle() {
        standby = true;
        App.removeCallbacks(retry);
        playUrl = "";
        releaseLock();
        if (player != null) player.setPlayWhenReady(false);
        binding.standby.setVisibility(View.VISIBLE);
        binding.standbyText.setText(R.string.cast_receive_wait);
        binding.status.setText("");
        scheduleRetry();
    }

    /* ============================ 跟播 ============================ */

    @Override
    public void onFound(CastHost host) {
        // 广播端又回来了就立刻接上（uuid 相同才算「同一台」）
        App.post(() -> {
            if (standby && host.getUuid().equals(lastUuid)) join(host);
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
        if (url.isEmpty()) return; // 只有位置没有地址（比如广播端还没拿到播放地址）：等下一个包
        if (!url.equals(playUrl)) {
            playUrl = url;
            stalls = 0;
            clean = 0;
            playTitle = packet.title();
            playEp = packet.ep();
            // ⚠️ UDP 那两档是「直播管道」：收到什么播什么，不能 seek —— 底下会自己说清楚
            play(url, CastMode.isUdp(packet.mode()) ? 0 : packet.position(),
                    CastPacket.STATE_PLAY.equals(packet.state()), packet.speed(), packet.subs());
            return;
        }
        follow(packet);
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
        boolean shouldPlay = CastPacket.STATE_PLAY.equals(packet.state());
        if (player.getPlayWhenReady() != shouldPlay) player.setPlayWhenReady(shouldPlay);
        // ⚠️ 组播 / 单播收到的就是广播端此刻发出的那一包，**所有接收端天然同步**，
        // 这里再拿心跳里的 position 去对位毫无意义（对的是广播端从头播的位置，
        // 而本机是从「接进来的那一刻」开始算的），只会在每个心跳上白白 seek 一次、
        // 把画面按在地上摩擦。所以 UDP 这两档只跟播/暂停，位置一概不管。
        if (CastMode.isUdp(mode)) {
            report(mode);
            syncTitle(packet);
            return;
        }
        long local = player.getCurrentPosition();
        CastSync.Result plan = CastSync.plan(System.currentTimeMillis(), packet.time(), packet.position(), local, packet.speed());
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

    private ExoPlayer ensurePlayer() {
        if (player != null) return player;
        ExoPlayer created = new ExoPlayer.Builder(this).build();
        created.setRepeatMode(Player.REPEAT_MODE_OFF);
        created.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) stalls++;
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Notify.show(String.valueOf(error.getMessage()));
                // 播不动：立刻让广播端知道，它会顺着往下掉一档链路
                stalls = 0;
                clean = 0;
                if (bus != null) bus.report(2);
            }
        });
        binding.player.setPlayer(created);
        player = created;
        return created;
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
        releaseLock();
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
