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

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * 局域网设备扫描：扫本机「所有网卡所在的网段」，找出同样装了本 App 并开着内置服务的设备。
 * 识别方式是请求对方内置服务的 /device 接口，能拿到设备名 / 类型 / 唯一标识。
 *
 * 每张网卡各扫各自的 /24 网段（CastNet.groups() 生成目标），请求走系统默认路由，不强制绑定网卡出口。
 * 750 曾用 Network.bindSocket 把请求绑到指定网卡，结果小米手机即使禁用 5G、只剩 Wi-Fi 一个网段，
 * 去往同网段盒子的请求仍全部超时，而浏览器直连同一地址却正常——绑定出口这一步本身是坏的，已删除。
 * （马先生 2026-11-22 小米手机扫不到盒子、荣耀能扫到）
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

    /** 一条待探的地址 + 发它时用的 client */
    private static class Probe {
        final String host;
        final OkHttpClient client;

        Probe(String host, OkHttpClient client) {
            this.host = host;
            this.client = client;
        }
    }

    public void start() {
        stop = false;
        List<CastNet.Subnet> groups = CastNet.groups();
        List<Probe> probes = new ArrayList<>();
        for (CastNet.Subnet group : groups) {
            OkHttpClient client = clientFor(group.local);
            for (String host : group.targets) probes.add(new Probe(host, client));
        }
        DebugLog.d(TAG, "开始扫描：网卡分组=" + groups + " 目标地址=" + probes.size() + " 个");
        if (probes.isEmpty()) {
            App.post(() -> callback.onEnd(0));
            return;
        }
        executor = Executors.newFixedThreadPool(48);
        Task.execute(() -> {
            long begin = System.currentTimeMillis();
            scan(probes);
            // 一台都没找到时整体重试一轮：对端服务往往比扫描晚起一两秒，或那一刻网络抖了一下。
            // scan() 里会自动跳过已命中的地址，只有全军覆没才多花这一轮，正常情况不拖慢。
            if (!stop && items.isEmpty()) {
                DebugLog.d(TAG, "首轮 0 台，重试一轮");
                scan(probes);
            }
            DebugLog.d(TAG, "扫描结束：找到 " + items.size() + " 台设备，耗时 " + (System.currentTimeMillis() - begin) + "ms");
            App.post(() -> callback.onEnd(items.size()));
        });
    }

    public void cancel() {
        stop = true;
        if (executor != null) executor.shutdownNow();
    }

    private void scan(List<Probe> probes) {
        List<Probe> pending = new ArrayList<>();
        for (Probe probe : probes) if (!hosts.contains(probe.host)) pending.add(probe);
        if (pending.isEmpty() || stop) return;
        AtomicInteger done = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(pending.size());
        for (Probe probe : pending) {
            if (stop) break;
            try {
                executor.execute(() -> {
                    try {
                        if (!stop && !hosts.contains(probe.host)) {
                            Device device = probe(probe);
                            if (device != null) {
                                hosts.add(probe.host);
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
     * 手动指定单个 IP 的探测：复用和自动扫描一样的 /device 校验（9978~9998 逐端口试、跳过自己）。
     * 同步调用，请在后台线程跑；命中返回设备，否则 null。
     */
    public Device probeOnce(String host) {
        return request(host, clientFor(host));
    }

    /**
     * 一个地址把 9978~9998 全试一遍；对方内置服务落在哪个端口都能撞上。
     * 743 曾改成「第一个端口一超时就整台机放弃」，对端服务晚起一两秒或某个端口偶发抖动就永远漏掉。
     * 现在每个端口独立容错：某端口超时继续试后面的端口，只有连续超时到上限（主机确实不在线）
     * 才收手，避免空地址把 21×1.2 秒全等满拖爆扫描。
     */
    private Device probe(Probe item) {
        return stop ? null : request(item.host, item.client);
    }

    private Device request(String host, OkHttpClient client) {
        int timeouts = 0;
        for (int port : PORTS) {
            if (stop) return null;
            try (Response response = OkHttp.newCall(client, "http://" + host + ":" + port + "/device").execute()) {
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
                // 一超时不整台机放弃：继续试后面的端口；连续超时到上限才判定主机不在线
                if (++timeouts >= MAX_TIMEOUTS) return null;
            } catch (Throwable e) {
                // 端口没开 / 连接被拒：接着试下一个端口
            }
        }
        return null;
    }

    /**
     * 扫描用普通 client，走系统默认路由。
     * 「各扫各的网段」由 CastNet.groups() 生成目标地址实现；包从哪张网卡发出交给系统默认路由
     * （只有 Wi-Fi 时天然走 Wi-Fi，禁用 5G 后去往 192.168.1.x 的包就能正常到达盒子）。
     */
    private static OkHttpClient clientFor(String local) {
        return OkHttp.client(TIMEOUT);
    }

}
