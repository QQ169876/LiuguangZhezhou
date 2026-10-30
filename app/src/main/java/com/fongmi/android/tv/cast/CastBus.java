package com.fongmi.android.tv.cast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 广播总线：一台机器上的**广播端**和**接收端**两个角色都归它管。
 *
 * 关于端口（马先生 2026-10-31 定的规矩）：
 *   广播端从 10086 起逐个试绑，谁被占了试下一个，最多到 10096；
 *   接收端自己开**随机端口**，所以收不到别人往 10086 上扔的包 ——
 *   流程必须是「接收端主动探一嘴，广播端按回包的来源地址答回去」，
 *   这样一来一回就把接收端的随机端口告诉它了，后面心跳就走这条单播通道。
 *
 * 为什么不直接广播到 255.255.255.255：家用路由器普遍拦定向广播，
 * 组播又要看 AP 的脸色。**逐个 IP 探一遍在 /24 里就是 254×11 个包，几百毫秒的事**，
 * 换来的是「在哪儿都能用」，这笔账划算。
 *
 * 这块全是纯 Java，没有一丝 Android 依赖 —— 因为它是整个功能里最容易出错的地方，
 * 能在普通 JVM 上跑单测才敢往上接 UI。
 */
public final class CastBus {

    public interface Listener {

        /** 扫到一个广播端（可能在多网卡上重复回调同一个，按 uuid+IP 去重过了） */
        void onFound(CastHost host);

        /** 收到对方的状态/指令收工包 */
        void onPacket(CastPacket packet);

        /** 广播端连续 {@link #LOST_MS} 没心跳了：接收端该回待机了（只回调一次，回包恢复会自动复位） */
        void onLost();

        /** 一轮扫描结束（超时窗口到点了） */
        void onScanEnd(int count);

        /**
         * 一台接收端报上名来，并且把自己那个收包的端口也一并给了。
         * 「单播」那一档全靠这个端口 —— 广播端得知道往哪儿发货。
         */
        void onPeer(String uuid, InetAddress ip, int port);

        /** 这台接收端走了（主动退出，或者太久没打招呼被除名） */
        void onPeerGone(String uuid);

        /** 接收端对当前链路的评价：0 挺好、1 开始卡了、2 根本播不动 */
        void onQuality(int quality);
    }

    public static final int PORT_FROM = 10086;
    public static final int PORT_TO = 10096;

    /** 广播端心跳间隔：1 秒一次，接收端靠它活着 */
    private static final long TICK_MS = 1000L;
    /** 接收端每隔多久打个招呼，超时会被广播端除名 */
    private static final long PING_MS = 3000L;
    /** 广播端眼里：接收端这么久没消息就当它走了 */
    private static final long PEER_TTL = 12000L;
    /** 接收端眼里：广播端这么久没心跳就算断了 */
    private static final long LOST_MS = 6000L;
    /** 发完探测包再等多久收回应 */
    private static final long SCAN_WAIT_MS = 4000L;
    /** 一次把整个网段的 254 个地址撒出去，24 条线够快又不至于把小路由器打死 */
    private static final int SCAN_THREADS = 24;
    /** 收包线程的醒转间隔：既能响应关闭，又不空转 */
    private static final int SO_TIMEOUT = 1000;

    private final Listener listener;
    private final String uuid;
    private final String name;
    private final Map<String, CastHost> found = new ConcurrentHashMap<>();
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private final Object life = new Object();
    private final AtomicLong sequence = new AtomicLong();

    private volatile String code = "1234";
    private volatile int maxClients = Integer.MAX_VALUE; // 默认不限制
    private volatile boolean closed;

    private DatagramSocket server;   // 广播端（固定端口）
    private DatagramSocket client;   // 接收端（随机端口）
    private ScheduledThreadPoolExecutor jobs;
    private ExecutorService scanPool;

    private volatile int serverPort = -1;
    private volatile String session = "";
    private volatile String nonce = "";
    private volatile String kind = CastPacket.KIND_VIDEO;
    private volatile String title = "";
    private volatile String ep = "";
    private volatile String url = "";
    private volatile Map<String, String> headers = Collections.emptyMap();
    private volatile Map<String, String> subs = Collections.emptyMap();
    private volatile long position;
    private volatile String state = CastPacket.STATE_PAUSE;
    private volatile int mode = CastMode.HTTP;
    private volatile String group = "";
    private volatile int mport;
    private volatile float speed = 1f;
    private volatile boolean dirty = true;

    private volatile int dataPort;
    private volatile int quality;

    private volatile CastHost current;
    private volatile long lastRecv;
    private volatile long lastPing;
    private volatile boolean lost;
    private volatile boolean beating;
    private volatile int epoch;

    public CastBus(Listener listener, String uuid, String name) {
        this.listener = listener;
        this.uuid = uuid == null ? "" : uuid;
        this.name = name == null ? "" : name;
    }

    /* ============================ 开关与凭据 ============================ */

    /**
     * @param maxClients 最多几台接收端一起看；<b>小于等于 0 表示不限制</b>（家里带宽足就这么设）
     */
    public void setAuth(String code, int maxClients) {
        if (code != null && !code.isEmpty()) this.code = code;
        this.maxClients = maxClients > 0 ? maxClients : Integer.MAX_VALUE;
    }

    /**
     * 配对校验值：sha1(会话随机串 + 配对码)。
     *
     * 为什么要加这个随机串：配对码就这么 4 位数，直接发等于在局域网里广播。
     * 先由广播端在 HI 里给一个会话随机串，接收端拿它跟配对码一起算摘要发回来，
     * 中间人就算抓到了这串摘要，换台机器、换个会话也用不上。
     *
     * ⚠️ 两端都必须走这一个方法自己算，别在两头各写一遍摘要算法 ——
     * 那种写法最常出的错就是一边加冒号一边没加，结果两边都对不上还查不出为什么。
     */
    public static String sign(String nonce, String code) {
        String a = nonce == null ? "" : nonce;
        String b = code == null ? "" : code;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] data = digest.digest((a + "#" + b).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(data.length * 2);
            for (byte item : data) sb.append(String.format("%02x", item));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ============================ 广播端 ============================ */

    /**
     * 打开广播：占住一个端口开始应答别人的探测。已经开着就返回当前端口（重复调用无害）。
     *
     * @return 实际占到的端口；一个都占不到返回 -1（说明这台机器上 10086~10096 全被别人占着）
     */
    public int open(String kind, String title, String ep) {
        synchronized (life) {
            if (closed) return -1;
            if (server != null) return serverPort;
            for (int port = PORT_FROM; port <= PORT_TO; port++) {
                try {
                    DatagramSocket socket = new DatagramSocket(port);
                    socket.setSoTimeout(SO_TIMEOUT);
                    server = socket;
                    serverPort = port;
                    session = newId("s");
                    nonce = newId("n");
                    setVideo(kind, title, ep);
                    startReader(socket, true);
                    beats();
                    return port;
                } catch (SocketException ignored) {
                    // 这个口被占了，下一个
                }
            }
            return -1;
        }
    }

    private void setVideo(String kind, String title, String ep) {
        this.kind = kind == null ? CastPacket.KIND_VIDEO : kind;
        this.title = title == null ? "" : title;
        this.ep = ep == null ? "" : ep;
    }

    /** 收工：先给每台接收端发个「我先撤了」，它们就乖乖回待机，然后才松手这个端口 */
    public void stopSend() {
        DatagramSocket socket;
        List<Peer> peers = new ArrayList<>(this.peers);
        synchronized (life) {
            socket = server;
            server = null;
            serverPort = -1;
            this.peers.clear();
        }
        if (socket == null) return;
        for (Peer peer : peers) {
            byte[] data = CastPacket.of(CastPacket.BYE).uuid(uuid).name(name).sid(session).time().build().bytes();
            if (data != null) write(socket, data, peer.address, peer.port);
        }
        close(socket);
        relax();
    }

    public int port() {
        return serverPort;
    }

    public boolean sending() {
        return server != null && serverPort > 0;
    }

    public int receiverCount() {
        return peers.size();
    }

    /**
     * 当前走哪一档链路（中转 / 单播 / 组播），以及组播要用的地址和端口。
     *
     * 每次心跳和每条指令都会带上它 —— 万一网络不好要往下降一档，
     * 接收端靠这两个字节就能跟着换过来，不用重新报一遍名。
     */
    public void transport(int mode, String group, int port) {
        this.mode = mode;
        this.group = group == null ? "" : group;
        this.mport = port;
    }

    public int mode() {
        return mode;
    }

    /** 接收端给自己挑的收包端口：报名和每次打招呼都带上，单播那一档要用 */
    public void setDataPort(int port) {
        dataPort = port;
    }

    /** 接收端打个招呼说「现在卡了/播不动」：这个值会随下一次打招呼发过去 */
    public void report(int value) {
        quality = value;
        CastHost host = current;
        if (host == null) return;
        byte[] data = CastPacket.of(CastPacket.PING).uuid(uuid).name(name).sid(host.getSid())
                .dport(dataPort).quality(value).build().bytes();
        if (data != null) write(client, data, host.getIp(), host.getPort());
    }

    /**
     * 换片/换集/换了取流地址：下一次心跳附带这些（只带一次，之后就不重复带了，省流量）。
     * 接收端就是靠这个 URL + 请求头去自己去取流 —— 即便有的站点非要 Referer 才能取流，也一并带过去了。
     */
    public void share(String kind, String title, String ep, String url, Map<String, String> headers, Map<String, String> subs) {
        if (kind != null && !kind.isEmpty()) setVideo(kind, title, ep);
        else {
            this.title = title == null ? "" : title;
            this.ep = ep == null ? "" : ep;
        }
        this.url = url == null ? "" : url;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.subs = subs == null ? Collections.emptyMap() : subs;
        this.dirty = true;
    }

    /** 播放状态变了（拖动、暂停、倍速）：记录下来，下一次心跳就带着；不变也无所谓，心跳本身 1 秒一次 */
    public void tick(long position, String state, float speed) {
        this.position = Math.max(0L, position);
        if (state != null && !state.isEmpty()) this.state = state;
        if (speed > 0) this.speed = speed;
    }

    /** 立刻生效的指令（暂停/续播/拖动/换集/收工）：不等下一次心跳，马上发 */
    public void command(String act, long position) {
        if (act == null || act.isEmpty()) return;
        this.position = Math.max(0L, position);
        CastPacket packet = CastPacket.of(CastPacket.CMD).uuid(uuid).name(name).sid(session)
                .kind(kind).title(title).ep(ep).act(act).mode(mode).group(group).mport(mport)
                .position(this.position).state(state).speed(speed).time().build();
        byte[] data = packet.bytes();
        if (data == null) return;
        for (Peer peer : new ArrayList<>(peers)) {
            synchronized (life) {
                if (server != null) write(server, data, peer.address, peer.port);
            }
        }
    }

    /* ============================ 接收端 ============================ */

    /**
     * 扫一圈。传进来的目标 IP 爱是哪儿是哪儿 ——
     * 本机所有网段算出来的（同子网用），也可以是用户在界面上手工填的一个跨网段地址。
     */
    public void scan(Collection<String> targets) {
        ensureClient();
        found.clear();
        final int generation = ++epoch;
        if (targets == null || targets.isEmpty()) {
            listener.onScanEnd(0);
            return;
        }
        ExecutorService pool = scanPool();
        for (String ip : targets) {
            if (ip == null || ip.trim().isEmpty()) continue;
            final String host = ip.trim();
            pool.execute(() -> probe(host, generation));
        }
        schedule(() -> {
            if (generation == epoch) listener.onScanEnd(found.size());
        }, SCAN_WAIT_MS);
    }

    public void cancelScan() {
        epoch++;
    }

    public List<CastHost> hosts() {
        return CastHost.sorted(new ArrayList<>(found.values()));
    }

    public CastHost current() {
        return current;
    }

    /** 认准一个广播端：报个名，之后就听它的心跳 */
    public void join(CastHost host) {
        if (host == null) return;
        ensureClient();
        current = host;
        lastRecv = System.currentTimeMillis();
        lastPing = 0L;
        lost = false;
        byte[] data = CastPacket.of(CastPacket.JOIN).uuid(uuid).name(name)
                .sid(host.getSid()).code(sign(host.getNonce(), code)).dport(dataPort).build().bytes();
        if (data != null) write(client, data, host.getIp(), host.getPort());
        beats();
    }

    /** 接收端主动退出：先打个招呼让对方把自己除名，别让那边干等超时 */
    public void leave() {
        CastHost host = current;
        current = null;
        if (host == null) return;
        byte[] data = CastPacket.of(CastPacket.LEAVE).uuid(uuid).name(name).sid(host.getSid()).build().bytes();
        if (data != null) write(client, data, host.getIp(), host.getPort());
        relax();
    }

    /* ============================ 内部实现 ============================ */

    private void probe(String ip, int generation) {
        InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (Throwable e) {
            return; // 名字解析不了就算了，别在这一棵树上吊死
        }
        byte[] data = CastPacket.of(CastPacket.PROBE).uuid(uuid).name(name).build().bytes();
        if (data == null) return;
        for (int port = PORT_FROM; port <= PORT_TO; port++) {
            if (closed || generation != epoch) return;
            write(client, data, address, port);
        }
    }

    private void ensureClient() {
        synchronized (life) {
            if (closed) return;
            if (client != null && !client.isClosed()) return;
            try {
                DatagramSocket socket = new DatagramSocket(); // 端口交给系统随机分配
                socket.setSoTimeout(SO_TIMEOUT);
                client = socket;
                startReader(socket, false);
            } catch (SocketException ignored) {
                // 起不来就起不来，扫描/收包这一轮没结果，下一轮再试
            }
        }
    }

    private void startReader(final DatagramSocket socket, final boolean serverSide) {
        Thread thread = new Thread(() -> read(socket, serverSide), "cast-read-" + (serverSide ? "s" : "c"));
        thread.setDaemon(true);
        thread.start();
    }

    private void read(DatagramSocket socket, boolean serverSide) {
        byte[] buffer = new byte[CastPacket.MAX_BYTES];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue; // 醒一下看看是不是该退了
            } catch (Throwable e) {
                return; // socket 被关了，收线
            }
            CastPacket payload = CastPacket.parse(packet.getData(), packet.getLength());
            if (payload == null || !payload.supported()) continue;
            if (!uuid.isEmpty() && uuid.equals(payload.uuid())) continue; // 自己的包不当回包处理
            if (serverSide) onServer(socket, payload, packet.getAddress(), packet.getPort());
            else onClient(payload, packet.getAddress(), packet.getPort());
        }
    }

    /* -------------------- 广播端收到什么该怎么办 -------------------- */

    private void onServer(DatagramSocket socket, CastPacket packet, InetAddress from, int port) {
        if (CastPacket.PROBE.equals(packet.type())) {
            byte[] data = CastPacket.of(CastPacket.HI).uuid(uuid).name(name).sid(session).nonce(nonce)
                    .kind(kind).title(title).ep(ep).build().bytes();
            if (data != null) write(socket, data, from, port);
            return;
        }
        if (CastPacket.JOIN.equals(packet.type())) {
            Peer peer = peer(from, port, packet.uuid());
            String expect = sign(nonce, code);
            if (expect.isEmpty() || !expect.equalsIgnoreCase(packet.code())) {
                deny(socket, from, port, "配对码不对");
                return;
            }
            if (peers.size() >= maxClients && peer == null) {
                deny(socket, from, port, "人数满了");
                return;
            }
            if (peer == null) peers.add(new Peer(from, port, packet.uuid(), packet.dport()));
            else {
                peer.seen = System.currentTimeMillis();
                if (packet.dport() > 0) peer.dport = packet.dport();
            }
            welcome(from, port);
            if (packet.dport() > 0) listener.onPeer(packet.uuid(), from, packet.dport());
            return;
        }
        if (CastPacket.PING.equals(packet.type()) || CastPacket.LEAVE.equals(packet.type())) {
            Peer peer = peer(from, port, packet.uuid());
            if (peer == null) return;
            if (CastPacket.LEAVE.equals(packet.type())) {
                peers.remove(peer);
                listener.onPeerGone(peer.uuid);
                return;
            }
            peer.seen = System.currentTimeMillis();
            if (packet.dport() > 0 && packet.dport() != peer.dport) {
                peer.dport = packet.dport();
                listener.onPeer(peer.uuid, from, peer.dport); // 端口换了要重新告诉发货那边
            }
            int report = packet.quality();
            if (report > 0) listener.onQuality(report);
        }
    }

    private void deny(DatagramSocket socket, InetAddress from, int port, String reason) {
        byte[] data = CastPacket.of(CastPacket.DENY).uuid(uuid).name(name).sid(session).msg(reason).build().bytes();
        if (data != null) write(socket, data, from, port);
    }

    /** 刚报上名来的那位：马上给一份完整信息（含播放地址和请求头），别让它空等下一个心跳 */
    private void welcome(InetAddress from, int port) {
        CastPacket packet = CastPacket.of(CastPacket.TICK).uuid(uuid).name(name).sid(session)
                .kind(kind).title(title).ep(ep).url(url).headers(headers).subs(subs)
                .mode(mode).group(group).mport(mport)
                .position(position).state(state).speed(speed).time().build();
        byte[] data = packet.bytes();
        if (data == null) return;
        synchronized (life) {
            if (server != null) write(server, data, from, port);
        }
    }

    private Peer peer(InetAddress from, int port, String uuid) {
        for (Peer peer : peers) {
            if (peer.port == port && peer.address.equals(from) && peer.uuid.equals(uuid)) return peer;
        }
        return null;
    }

    /* -------------------- 接收端收到什么该怎么办 -------------------- */

    private void onClient(CastPacket packet, InetAddress from, int port) {
        String type = packet.type();
        if (CastPacket.HI.equals(type)) {
            String key = packet.uuid() + "@" + from.getHostAddress();
            CastHost host = found.get(key);
            if (host == null) {
                host = new CastHost(packet.uuid(), packet.name(), from.getHostAddress(), port,
                        packet.sid(), packet.nonce(), packet.kind(), packet.title(), packet.ep());
                found.put(key, host);
                CastHost current = this.current;
                // 之前跟着的这台又回来了：把手上这份换成最新的，方便接着用这份最新的信息
                if (current != null && current.getUuid().equals(host.getUuid())) this.current = host;
                listener.onFound(host);
            } else {
                host.refresh(packet);
            }
            return;
        }
        CastHost current = this.current;
        if (current == null) return;
        if (!current.getUuid().equals(packet.uuid())) return; // 别的广播端的心跳一概不听
        lastRecv = System.currentTimeMillis();
        lost = false;
        if (CastPacket.TICK.equals(type) || CastPacket.CMD.equals(type) || CastPacket.BYE.equals(type) || CastPacket.DENY.equals(type)) {
            listener.onPacket(packet);
        }
    }

    /* -------------------- 心跳：一头维系-------------------------------------- */

    private ScheduledExecutorService executor() {
        synchronized (life) {
            if (closed) return Executors.newSingleThreadScheduledExecutor(); // 关了就别再复用，给个空的兜着
            if (jobs == null || jobs.isShutdown()) {
                ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1);
                created.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                created.setRemoveOnCancelPolicy(true);
                jobs = created;
            }
            return jobs;
        }
    }

    /** 延后一会儿干件事；已经收摊的就别再排新的活儿了（避免把自己关了还留下线程） */
    private void schedule(Runnable task, long delayMs) {
        if (closed) return;
        executor().schedule(() -> {
            if (closed) return;
            try {
                task.run();
            } catch (Throwable ignored) {
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** 有活要干的时候才把定时任务拉起来；⚠️ 只能挂一次，挂两次心跳会变成两倍速 */
    private void beats() {
        synchronized (life) {
            if (beating || closed) return;
            beating = true;
            try {
                executor().scheduleAtFixedRate(this::beat, 0, TICK_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                beating = false;
            }
        }
    }

    private void beat() {
        try {
            long now = System.currentTimeMillis();
            if (server != null) {
                // ⚠️ 播放地址只在这一轮里带一次：带着地址的包比空心跳大好几倍，没必要每秒重复
                boolean withSource = dirty;
                for (Peer peer : new ArrayList<>(peers)) {
                    if (now - peer.seen > PEER_TTL) {
                        peers.remove(peer); // 招呼都不打一个的，当它已经走了
                        listener.onPeerGone(peer.uuid);
                        continue;
                    }
                    tickTo(peer, withSource);
                }
                if (withSource) dirty = false;
            }
            CastHost host = current;
            if (host != null) {
                if (now - lastRecv >= LOST_MS && !lost) {
                    lost = true;
                    listener.onLost();
                }
                if (now - lastPing >= PING_MS) {
                    lastPing = now;
                    byte[] data = CastPacket.of(CastPacket.PING).uuid(uuid).name(name).sid(host.getSid())
                            .dport(dataPort).quality(quality).build().bytes();
                    if (data != null) write(client, data, host.getIp(), host.getPort());
                }
            }
        } catch (Throwable ignored) {
            // 心跳这一轮出错绝不能把线程弄死：下一次还会再来一轮
        }
    }

    private void tickTo(Peer peer, boolean withSource) {
        CastPacket.Builder builder = CastPacket.of(CastPacket.TICK).uuid(uuid).name(name).sid(session)
                .kind(kind).title(title).ep(ep).mode(mode).group(group).mport(mport)
                .position(position).state(state).speed(speed).time();
        if (withSource) builder.url(url).headers(headers).subs(subs);
        byte[] data = builder.build().bytes();
        if (data == null) return; // 极少数情况（比如有人塞了个超长地址）宁可不发也别分片
        DatagramSocket socket = server;
        if (socket != null) write(socket, data, peer.address, peer.port);
    }

    /** 没活干了就把定时任务收起来，别白占着一个线程 */
    private void relax() {
        synchronized (life) {
            if (client == null && server == null && current == null) {
                beating = false;
                ScheduledThreadPoolExecutor executor = jobs;
                jobs = null;
                if (executor != null) executor.shutdownNow();
            }
        }
    }

    /* -------------------- 发/关的工具 -------------------- */

    private void write(DatagramSocket socket, byte[] data, InetAddress address, int port) {
        write(socket, data, new InetSocketAddress(address, port));
    }

    private void write(DatagramSocket socket, byte[] data, String host, int port) {
        try {
            write(socket, data, new InetSocketAddress(InetAddress.getByName(host), port));
        } catch (Throwable ignored) {
            // IP 填得不对：这种多半是用户手输错了，别崩，也别刷日志
        }
    }

    private void write(DatagramSocket socket, byte[] data, InetSocketAddress to) {
        if (socket == null || socket.isClosed() || data == null) return;
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (Throwable ignored) {
            // UDP 发出去就算完了，对面收不收得到不是我们操心的事，更不能因此把线程炸了
        }
    }

    private ExecutorService scanPool() {
        synchronized (life) {
            if (scanPool == null || scanPool.isShutdown()) {
                scanPool = Executors.newFixedThreadPool(SCAN_THREADS, r -> {
                    Thread thread = new Thread(r, "cast-scan");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return scanPool;
        }
    }

    private void close(DatagramSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private String newId(String prefix) {
        return prefix + sequence.incrementAndGet() + "-" + Long.toHexString(System.currentTimeMillis());
    }

    /* ============================ 收摊 ============================ */

    /**
     * 全部收摊：先体面地跟两边道个别（该发 BYE 的 BYE、该发 LEAVE 的 LEAVE），再关端口、停线程。
     * 顺序不能反 —— 先关了 socket 再想发客气话就只能对着空气说话了。
     */
    public void close() {
        stopSend();
        leave();
        synchronized (life) {
            closed = true;
            beating = false;
            close(client);
            client = null;
            epoch++;
            ScheduledThreadPoolExecutor executor = jobs;
            jobs = null;
            if (executor != null) executor.shutdownNow();
            ExecutorService pool = scanPool;
            scanPool = null;
            if (pool != null) pool.shutdownNow();
        }
    }

    private static final class Peer {

        private final InetAddress address;
        private final int port;
        private final String uuid;
        private volatile int dport;
        private volatile long seen;

        private Peer(InetAddress address, int port, String uuid, int dport) {
            this.address = address;
            this.port = port;
            this.uuid = uuid;
            this.dport = dport;
            this.seen = System.currentTimeMillis();
        }
    }
}
