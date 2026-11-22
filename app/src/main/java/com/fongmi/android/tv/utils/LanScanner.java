package com.fongmi.android.tv.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.cast.CastNet;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
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

import javax.net.SocketFactory;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * 局域网设备扫描：扫本机「所有网卡所在的网段」，找出同样装了本 App 并开着内置服务的设备。
 * 识别方式是请求对方内置服务的 /device 接口，能拿到设备名 / 类型 / 唯一标识。
 *
 * 每张网卡各扫各自的 /24 网段，并且请求绑定这张网卡的出口：
 * 同时连着 Wi-Fi(192.168.x) 和移动 5G(10.x)、或开了 VPN 时，如果所有目标共用一个默认出口，
 * 去往 192.168.x 的包可能被系统从 10.x 那张网卡丢出去，导致同一个局域网里的设备一个都扫不到
 * （马先生 2026-11-22 小米手机扫不到盒子、荣耀能扫到）。现在去往哪张网卡网段的请求，就绑定哪张网卡发。
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

    /** 一条待探的地址 + 发它时要走的网卡出口 client */
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

    /** 建一个绑定到指定网卡出口的 client：去往这张网卡网段的请求只从这张网卡发出去 */
    private static OkHttpClient clientFor(String local) {
        OkHttpClient base = OkHttp.client(TIMEOUT);
        // 独立连接池：别和全局 client 或其它网卡复用连接，确保每个 socket 都经自己的出口 factory 建立
        return base.newBuilder()
                .connectionPool(new okhttp3.ConnectionPool())
                .socketFactory(new BoundSocketFactory(local, networkFor(local)))
                .build();
    }

    /** 找到持有这个本机 IP 的那张网卡对应的 Android Network，用它强制 socket 出口 */
    private static Network networkFor(String localIp) {
        try {
            ConnectivityManager manager = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            Network[] networks = manager.getAllNetworks();
            for (Network network : networks) {
                LinkProperties props = manager.getLinkProperties(network);
                if (props == null) continue;
                for (LinkAddress address : props.getLinkAddresses()) {
                    InetAddress inet = address.getAddress();
                    if (inet instanceof Inet4Address && inet.getHostAddress().equals(localIp)) return network;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 让 OkHttp 的 socket 强制走指定出口：优先 Android Network.bindSocket（多网卡/Wi-Fi+5G+VPN 下最可靠），
     * 找不到对应 Network 时退回按本机 IP 绑源地址。这样 10.x 的请求走 5G、192.168.x 的请求走 Wi-Fi。
     */
    private static class BoundSocketFactory extends SocketFactory {
        private final SocketFactory delegate = SocketFactory.getDefault();
        private final String local;
        private final Network network;

        BoundSocketFactory(String local, Network network) {
            this.local = local;
            this.network = network;
        }

        private Socket bind(Socket socket) throws IOException {
            if (network != null) network.bindSocket(socket); // 有对应 Network：强制走这张网卡，最可靠
            return socket;
        }

        private void bindLocal(Socket socket) {
            if (network == null && local != null && !local.isEmpty()) { // 没拿到 Network：退回按本机 IP 绑源地址
                try {
                    socket.bind(new InetSocketAddress(local, 0));
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket socket = bind(delegate.createSocket());
            bindLocal(socket);
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = bind(delegate.createSocket());
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = bind(delegate.createSocket());
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = bind(delegate.createSocket());
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = bind(delegate.createSocket());
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }
    }
}
