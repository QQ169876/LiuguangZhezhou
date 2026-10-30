package com.fongmi.android.tv.cast;

import com.github.catvod.net.OkHttp;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 码流发货的这一侧：把 TS 字节流按组播或者单播发到局域网里。
 *
 * 只有两件事要做：
 *   1. 把一堆 188 字节的 TS 包攒够 7 个（1316 字节）合成一个 UDP 包发出去，别一个包一个小包地撒；
 *   2. 源是直播间那种慢慢来的还好，源要是个静态文件，必须**按 PCR 掐着节奏发**，
 *      不然十几兆一股脑灌出去，接收端只会丢得更凶。
 *
 * ⚠️ 这一路上只有 TS 字节和控制信息无关：连谁、放到第几毫秒、要不要降级都走 {@link CastBus} 那套小包，
 * 两码事分开走，互不拖累。
 */
public final class CastTx {

    /** 拿不到 PCR 时的上限速率（12Mbps）：够 1080p 用了，也不至于把 Wi-Fi 按死 */
    private static final long CAP_BPS = 12L * 1024 * 1024;

    private final byte[] buffer = new byte[TsKit.UNIT];
    private final Map<String, InetSocketAddress> peers = new ConcurrentHashMap<>();

    private volatile MulticastSocket socket;
    private volatile InetSocketAddress group;
    private volatile Thread puller;
    private volatile long pullGen;
    private volatile boolean alive;
    private int filled;

    /**
     * 开张。
     *
     * @param group  组播地址；空表示「挨个发给每台接收端」（单播）
     * @param port   组播端口（单播时不用）
     * @param url    源地址；给了就自己起个线程去拉（视频 TS 这条路），不给则是「别人往里塞」的（屏幕编码）
     */
    public synchronized boolean open(int mode, String group, int port, String url, Map<String, String> headers) {
        boolean udp = CastMode.isUdp(mode);
        if (!udp) {
            close();
            return true;
        }
        close();
        alive = true;
        socket = bindSocket();
        if (socket == null) return false;
        setGroup(mode == CastMode.MULTICAST ? group : "", port);
        if (url != null && !url.isEmpty()) startPull(url, headers);
        return true;
    }

    /** 换发货方式（组播不稳改单播时用）：以身作则把已经连上的接收端一并列进新的发货名单 */
    public synchronized void retarget(int mode, String group, int port) {
        if (!CastMode.isUdp(mode)) {
            stopPull();
            return;
        }
        if (socket == null) {
            socket = bindSocket();
            alive = true;
        }
        setGroup(mode == CastMode.MULTICAST ? group : "", port);
    }

    /** 一台接收端上线（单播要发到它自己那个端口） */
    public void peer(String uuid, String ip, int port) {
        if (uuid == null || ip == null || port <= 0) return;
        try {
            peers.put(uuid, new InetSocketAddress(InetAddress.getByName(ip), port));
        } catch (Throwable ignored) {
        }
    }

    public void peerGone(String uuid) {
        if (uuid != null) peers.remove(uuid);
    }

    /** 塞一段 TS 字节；长度不一定是 188 的整数倍也没关系，这里会攒 */
    public synchronized void write(byte[] data, int len) {
        MulticastSocket socket = this.socket;
        if (socket == null || !alive || data == null || len <= 0) return;
        int pos = 0;
        while (pos < len) {
            int take = Math.min(len - pos, TsKit.UNIT - filled);
            System.arraycopy(data, pos, buffer, filled, take);
            filled += take;
            pos += take;
            if (filled >= TsKit.UNIT) send(socket);
        }
    }

    /** 攒着的零头赶紧发出去（屏幕编码每隔一段时间调一次，避免最后一截卡在手里） */
    public synchronized void flush() {
        MulticastSocket socket = this.socket;
        if (socket == null || !alive || filled <= 0) return;
        send(socket);
    }

    public synchronized void close() {
        stopPull();
        alive = false;
        filled = 0;
        group = null;
        peers.clear();
        MulticastSocket socket = this.socket;
        this.socket = null;
        if (socket == null) return;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 内部实现 ---------------- */

    private void setGroup(String address, int port) {
        if (address == null || address.isEmpty() || port <= 0) {
            group = null;
            return;
        }
        try {
            group = new InetSocketAddress(InetAddress.getByName(address), port);
        } catch (Throwable e) {
            group = null;
        }
    }

    private MulticastSocket bindSocket() {
        try {
            MulticastSocket socket = new MulticastSocket(); // 不绑固定端口，让系统随机给
            socket.setTimeToLive(1);                        // 只在本网段打转，别跑到隔壁去了
            NetworkInterface nif = nif();
            if (nif != null) {
                try {
                    socket.setNetworkInterface(nif); // 多网卡时指定走「能到局域网的那张」
                } catch (Throwable ignored) {
                }
            }
            return socket;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 挑一块正在网上的网卡：VirtualBox / VPN 那些虚拟网卡不在局域网上，排除掉 */
    private NetworkInterface nif() {
        List<NetworkInterface> all = interfaces();
        for (NetworkInterface item : all) {
            try {
                if (!item.isUp() || item.isLoopback() || item.isVirtual()) continue;
                if (java.util.Collections.list(item.getInetAddresses()).isEmpty()) continue;
                return item;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** getNetworkInterfaces 会抛 SocketException，单独兜起来：拿不到就当没有，走系统默认出口 */
    private List<NetworkInterface> interfaces() {
        try {
            return java.util.Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (Throwable e) {
            return java.util.Collections.emptyList();
        }
    }

    private void send(MulticastSocket socket) {
        DatagramPacket packet = new DatagramPacket(buffer, filled);
        try {
            InetSocketAddress target = group;
            if (target != null) {
                packet.setSocketAddress(target);
                socket.send(packet);
                return;
            }
            for (InetSocketAddress peer : new ArrayList<>(peers.values())) {
                packet.setSocketAddress(peer);
                socket.send(packet);
            }
        } catch (Throwable ignored) {
            // UDP 发出去就算完了；网络抖动、暂时不可达都不算事，下一包继续
        }
        filled = 0;
    }

    /* ---------------- 自己拉源的那一程（视频 TS） ---------------- */

    private void startPull(String url, Map<String, String> headers) {
        // ⚠️ 换源时旧的拉取线程可能正卡在 socket 读上（interrupt 叫不醒它），
        // 所以给每一代发个号码：号码不对的那个自己悄悄退出，绝不能两路同时往组里写
        final long gen = ++pullGen;
        Thread thread = new Thread(() -> pull(url, headers, gen), "cast-pull");
        thread.setDaemon(true);
        puller = thread;
        thread.start();
    }

    private void stopPull() {
        Thread thread = puller;
        puller = null;
        if (thread != null) thread.interrupt();
    }

    private void pull(String url, Map<String, String> headers, long gen) {
        Response response = null;
        InputStream stream = null;
        try {
            Request request = new Request.Builder().url(url).headers(Headers.of(fill(headers))).get().build();
            response = OkHttp.client(0).newCall(request).execute();
            stream = response.body() == null ? null : response.body().byteStream();
            if (stream == null) return;
            read(stream, gen);
        } catch (Throwable ignored) {
            // 源拉不动就算了：这一路失败不影响接收端 —— 广播端会把解析不到流这件事告诉它们，自动降回中转
        } finally {
            close(stream);
            if (response != null) response.close();
        }
    }

    private void read(InputStream stream, long gen) {
        byte[] raw = new byte[16 * 1024];
        byte[] left = new byte[raw.length + TsKit.PACKET];
        int size = 0;
        boolean aligned = false;
        TsKit.Pacer pacer = new TsKit.Pacer();
        long sent = 0L;
        long stamp = System.currentTimeMillis();
        while (alive && gen == pullGen) {
            int read;
            try {
                read = stream.read(raw);
            } catch (Throwable e) {
                return; // 源断了：交给上面重连/降级
            }
            if (read <= 0) break;
            System.arraycopy(raw, 0, left, size, read);
            size += read;
            if (!aligned) {
                if (size < TsKit.PACKET * 4) continue; // 头几个字节不足以下判断，再攒点
                int head = TsKit.align(left, size);
                if (head > 0) {
                    System.arraycopy(left, head, left, 0, size - head);
                    size -= head;
                }
                aligned = true;
            }
            int whole = size / TsKit.PACKET * TsKit.PACKET;
            if (whole <= 0) continue;
            if (!pacer.ready()) {
                // 拿不到 PCR（多半是个静态文件）：按一个不会给网络添乱的速率走
                sent += whole;
                long cost = (sent * 1000L) / CAP_BPS;
                long spent = System.currentTimeMillis() - stamp;
                sleep(cost - spent);
            }
            emit(left, whole, pacer);
            System.arraycopy(left, whole, left, 0, size - whole);
            size -= whole;
        }
    }

    /** 边发边按 PCR 掐节奏：这一整段该在哪一刻发完，就等到那一刻 */
    private void emit(byte[] data, int len, TsKit.Pacer pacer) {
        for (int i = 0; i < len; i += TsKit.PACKET) {
            long pcr = TsKit.pcr(data, i);
            if (pcr < 0) continue;
            sleep(pacer.waitMs(pcr, System.currentTimeMillis()));
        }
        write(data, len);
        flush();
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(Math.min(ms, 3000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, String> fill(Map<String, String> headers) {
        Map<String, String> result = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                result.put(entry.getKey(), entry.getValue());
            }
        }
        if (!result.containsKey("User-Agent")) result.put("User-Agent", com.fongmi.android.tv.App.get().getString(com.fongmi.android.tv.R.string.app_name));
        return result;
    }

    private void close(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    /** 组播端口从这一段里挑一个没人占的 */
    public static int freePort() {
        for (int port = 41000; port < 41100; port++) {
            if (usable(port)) return port;
        }
        return -1;
    }

    public static boolean usable(int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(port);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (socket != null) socket.close();
        }
    }
}
