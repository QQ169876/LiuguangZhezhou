package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.cast.CastNet;
import com.github.catvod.net.OkHttp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;

/**
 * 局域网设备扫描：扫本机「所有网卡所在的网段」，找出同样装了本 App 并开着内置服务的设备。
 * 识别方式是请求对方内置服务的 /device 接口，能拿到设备名 / 类型 / 唯一标识。
 *
 * 以前只取 Util.getIp() 一个地址 + 活动网络的那一个前缀，挂 VPN / 开热点 / 双 WiFi / 有线无线
 * 同时连着的时候，取到的网卡往往不是连着家里局域网的那张，网段算偏了就一个设备都搜不到
 * （马先生 2026-11-21）。现在直接复用 CastNet.targets()：每张网卡的 IPv4 各算一段、合起来去重，
 * 大网段自动收窄到 /24，169.254 假 IP 跳过，总量封顶 2048 个，不会把路由器打死。
 */
public class LanScanner {

    private static final String TAG = "LanScan";
    private static final long TIMEOUT = 1200;
    /** 同一地址允许的连续超时次数上限；超过就判定这个地址不在线，别把 21 个端口全等满拖爆扫描 */
    private static final int MAX_TIMEOUTS = 2;
    private static final int[] PORTS = {9978, 9979, 9980, 9981, 9982, 9983, 9984, 9985, 9986, 9987, 9988, 9989, 9990, 9991, 9992, 9993, 9994, 9995, 9996, 9997, 9998};

    private final List<Device> items;
    private final Set<String> hosts;
    private final Set<String> selfIp;
    private final Callback callback;
    private final String self;

    private ExecutorService executor;
    private volatile boolean stop;

    public interface Callback {

        void onFound(Device item);

        void onProgress(int done, int total);

        void onEnd(int count);
    }

    public LanScanner(Callback callback) {
        this.callback = callback;
        this.items = Collections.synchronizedList(new ArrayList<>());
        this.hosts = Collections.synchronizedSet(new HashSet<>());
        this.self = Util.getAndroidId();
        this.selfIp = localIps();
    }

    /** 本机所有网卡的 IPv4：扫描结果里只要碰到就当自己跳过，别把自己列进对端清单 */
    private static Set<String> localIps() {
        Set<String> result = new HashSet<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) result.add(address.getHostAddress());
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String ip = com.github.catvod.utils.Util.getIp();
            if (ip != null && !ip.isEmpty()) result.add(ip);
        } catch (Throwable ignored) {
        }
        return result;
    }

    public List<Device> getItems() {
        return items;
    }

    public void start() {
        stop = false;
        List<String> targets = hosts();
        DebugLog.d(TAG, "开始扫描：本机网段=" + CastNet.subnets() + " 目标地址=" + targets.size() + " 个");
        if (targets.isEmpty()) {
            App.post(() -> callback.onEnd(0));
            return;
        }
        executor = Executors.newFixedThreadPool(48);
        Task.execute(() -> {
            long begin = System.currentTimeMillis();
            scan(targets);
            // 一台都没找到时整体重试一轮：对端服务往往比扫描晚起一两秒，或那一刻网络抖了一下。
            // scan() 里会自动跳过已命中的地址，只有全军覆没才多花这一轮，正常情况不拖慢。
            if (!stop && items.isEmpty()) {
                DebugLog.d(TAG, "首轮 0 台，重试一轮");
                scan(targets);
            }
            DebugLog.d(TAG, "扫描结束：找到 " + items.size() + " 台设备，耗时 " + (System.currentTimeMillis() - begin) + "ms");
            App.post(() -> callback.onEnd(items.size()));
        });
    }

    public void cancel() {
        stop = true;
        if (executor != null) executor.shutdownNow();
    }

    private void scan(List<String> targets) {
        List<String> pending = new ArrayList<>();
        for (String host : targets) if (!hosts.contains(host)) pending.add(host);
        if (pending.isEmpty() || stop) return;
        AtomicInteger done = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(pending.size());
        for (String host : pending) {
            if (stop) break;
            try {
                executor.execute(() -> {
                    try {
                        if (!stop && !hosts.contains(host)) {
                            Device device = probe(host);
                            if (device != null) {
                                hosts.add(host);
                                items.add(device);
                                DebugLog.d(TAG, "发现设备：" + device.getName() + " @ " + device.getIp() + " uuid=" + device.getUuid());
                                App.post(() -> callback.onFound(device));
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        int value = done.incrementAndGet();
                        if (value % 32 == 0 || value == pending.size()) {
                            int total = pending.size();
                            App.post(() -> callback.onProgress(value, total));
                        }
                        latch.countDown();
                    }
                });
            } catch (Throwable ignored) {
                latch.countDown();
            }
        }
        try {
            latch.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一个地址把 9978~9998 全试一遍；对方内置服务落在哪个端口都能撞上。
     * 743 曾改成「第一个端口一超时就整台机放弃」，对端服务晚起一两秒或某个端口偶发抖动就永远漏掉。
     * 现在每个端口独立容错：某端口超时继续试后面的端口，只有连续超时到上限（主机确实不在线）
     * 才收手，避免 283 个空地址把 21×1.2 秒全等满拖爆扫描。
     */
    private Device probe(String host) {
        return stop ? null : request(host);
    }

    private Device request(String host) {
        int timeouts = 0;
        for (int port : PORTS) {
            if (stop) return null;
            try (Response response = OkHttp.newCall(OkHttp.client(TIMEOUT), "http://" + host + ":" + port + "/device").execute()) {
                if (!response.isSuccessful() || response.body() == null) continue;
                Device device = Device.objectFrom(response.body().string());
                if (device == null) continue;
                if (device.getUuid().isEmpty()) continue;
                if (device.getUuid().equals(self)) {
                    DebugLog.d(TAG, "跳过自己 " + host + ":" + port);
                    continue;
                }
                if (selfIp.contains(host)) {
                    DebugLog.d(TAG, "跳过本机 " + host + ":" + port);
                    continue;
                }
                device.setIp("http://" + host + ":" + port);
                return device;
            } catch (java.net.SocketTimeoutException e) {
                // 不再一超时就整台机放弃：继续试后面的端口；连续超时到上限才判定主机不在线
                if (++timeouts >= MAX_TIMEOUTS) return null;
            } catch (Throwable e) {
                // 端口没开 / 连接被拒：接着试下一个端口
            }
        }
        return null;
    }

    /** 本机所有网卡所在网段的全部候选地址，去掉自己、去重，统一交给扫描 */
    private List<String> hosts() {
        return CastNet.targets();
    }
}
