package com.fongmi.android.tv.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
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
 * 局域网设备扫描：扫当前网段，找出同样装了本 App 并开着内置服务的设备。
 * 识别方式是请求对方内置服务的 /device 接口，能拿到设备名 / 类型 / 唯一标识。
 */
public class LanScanner {

    private static final long TIMEOUT = 1200;
    private static final int[] PORTS_FIRST = {9978, 9979, 9980, 9981, 9982};
    private static final int[] PORTS_LAST = {9983, 9984, 9985, 9986, 9987, 9988, 9989, 9990, 9991, 9992, 9993, 9994, 9995, 9996, 9997, 9998};

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
        executor = Executors.newFixedThreadPool(48);
        List<String> targets = hosts();
        if (targets.isEmpty()) {
            App.post(() -> callback.onEnd(0));
            return;
        }
        Task.execute(() -> {
            scan(targets, PORTS_FIRST);
            if (!stop) scan(targets, PORTS_LAST);
            App.post(() -> callback.onEnd(items.size()));
        });
    }

    public void cancel() {
        stop = true;
        if (executor != null) executor.shutdownNow();
    }

    private void scan(List<String> targets, int[] ports) {
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
                            Device device = probe(host, ports);
                            if (device != null) {
                                hosts.add(host);
                                items.add(device);
                                App.post(() -> callback.onFound(device));
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        int value = done.incrementAndGet();
                        if (value % 8 == 0 || value == pending.size()) {
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
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Device probe(String host, int[] ports) {
        for (int port : ports) {
            if (stop) return null;
            try {
                Device device = request(host, port);
                if (device != null) return device;
            } catch (HostDownException e) {
                return null;
            }
        }
        return null;
    }

    private Device request(String host, int port) throws HostDownException {
        try (Response response = OkHttp.newCall(OkHttp.client(TIMEOUT), "http://" + host + ":" + port + "/device").execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            Device device = Device.objectFrom(response.body().string());
            if (device == null) return null;
            if (device.getUuid().isEmpty()) return null;
            if (device.getUuid().equals(self)) return null;
            if (selfIp.contains(host)) return null;
            device.setIp("http://" + host + ":" + port);
            return device;
        } catch (java.net.SocketTimeoutException e) {
            // 这个地址根本没人应答（大部分 IP 都是空的），后面的端口不用再试
            throw new HostDownException();
        } catch (Throwable e) {
            return null;
        }
    }

    private static class HostDownException extends Exception {
    }

    private List<String> hosts() {
        List<String> result = new ArrayList<>();
        String ip = com.github.catvod.utils.Util.getIp();
        if (ip == null || !ip.contains(".")) return result;
        long self = toLong(ip);
        if (self <= 0) return result;
        int prefix = getPrefix();
        int size = 1 << (32 - prefix);
        if (size > 512) {
            prefix = 24;
            size = 256;
        }
        long begin = (self & mask(prefix)) + 1;
        for (int i = 0; i < size - 2; i++) {
            long value = begin + i;
            if (selfIp.contains(toIp(value))) continue;
            if (value == self) continue;
            result.add(toIp(value));
        }
        return result;
    }

    private int getPrefix() {
        try {
            ConnectivityManager manager = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return 24;
            Network network = manager.getActiveNetwork();
            if (network == null) return 24;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) return 24;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) return address.getPrefixLength();
            }
        } catch (Throwable ignored) {
        }
        return 24;
    }

    private static long mask(int prefix) {
        return prefix <= 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    private static long toLong(String ip) {
        try {
            String[] p = ip.split("\\.");
            if (p.length != 4) return 0;
            return (Long.parseLong(p[0]) << 24) + (Long.parseLong(p[1]) << 16) + (Long.parseLong(p[2]) << 8) + Long.parseLong(p[3]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String toIp(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
