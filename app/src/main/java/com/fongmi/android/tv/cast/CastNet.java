package com.fongmi.android.tv.cast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 算「这个局域网里可能有人在哪些 IP 上」。
 *
 * ⚠️ 一台机器可能有好几个 IP（Wi-Fi + 网线 + 热点 + VPN 虚拟网卡），
 * 只认其中一个的话，接在另一张网卡上的设备就永远扫不到（马先生 2026-10-31）。
 * 所以这里是**每个 IPv4 都算一遍自己那一段，再合起来去重**。
 */
public final class CastNet {

    /** 单个网段最多扫几个地址：/24 正好 254 个；比这还大的网段多半是把整个公司网套进来了 */
    private static final int MAX_PER_SUBNET = 256;
    /** 全部加起来也别失控（真有人给 Diesel 分配了 /8 的） */
    private static final int MAX_TOTAL = 2048;

    private CastNet() {
    }

    /** 本机所有 IPv4（去掉回环） */
    public static Set<String> selfIps() {
        Set<String> result = new LinkedHashSet<>();
        for (NetworkInterface nif : interfaces()) {
            for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) result.add(address.getHostAddress());
            }
        }
        return result;
    }

    /** 本机 IPv4 各自所在的网段，挑出来 {ip, 掩码位数} */
    public static List<String> subnets() {
        List<String> result = new ArrayList<>();
        for (NetworkInterface nif : interfaces()) {
            for (InterfaceAddress item : nif.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                short prefix = item.getNetworkPrefixLength();
                if (prefix < 1 || prefix > 32) continue;
                result.add(address.getHostAddress() + "/" + prefix);
            }
        }
        return result;
    }

    /**
     * 所有候选目标 IP：本机每个网段里的每台主机，去掉自己、去重。
     * 网段太大（比如机房那种 /16）会自动收窄到含本机 IP 的那个 /24，别把路由器打死。
     */
    public static List<String> targets() {
        Set<String> self = selfIps();
        Set<String> result = new LinkedHashSet<>();
        for (NetworkInterface nif : interfaces()) {
            for (InterfaceAddress item : nif.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                result.addAll(range(address, item.getNetworkPrefixLength(), self));
                if (result.size() >= MAX_TOTAL) return new ArrayList<>(result);
            }
        }
        return new ArrayList<>(result);
    }

    private static List<String> range(InetAddress address, short prefix, Set<String> self) {
        List<String> result = new ArrayList<>();
        int bits = prefix < 1 || prefix > 32 ? 24 : prefix;
        long ip = toLong(address);
        if (ip <= 0) return result;
        if (address.getHostAddress().startsWith("169.254.")) return result; // 没网的时候那个「假 IP」，不用扫
        long size = 1L << (32 - bits);
        if (size > MAX_PER_SUBNET) { // 网段太大：收窄成含本机 IP 的 /24，绝大多数家用就是 /24
            bits = 24;
            size = MAX_PER_SUBNET;
        }
        long mask = mask(bits);
        long begin = (ip & mask) + 1;
        long end = (ip & mask) + size - 1;
        for (long value = begin; value <= end && result.size() < MAX_TOTAL; value++) {
            String host = toIp(value);
            if (self.contains(host)) continue; // 别扫自己
            if (host.endsWith(".0") || host.endsWith(".255")) continue; // 网络号/广播地址没意义
            result.add(host);
        }
        return result;
    }

    /**
     * 一张网卡的扫描组：local 是这张网卡自己的 IP（请求要绑定它当出口），targets 是这个 /24 网段里的候选地址。
     *
     * 为什么要按网卡分组而不是混成一张大表：一台机器同时有 Wi-Fi(192.168.x)、移动 5G(10.x)、VPN 时，
     * 若所有目标都用一个默认出口发请求，去往 192.168.x 的包可能被系统从 10.x 那张网卡丢出去，
     * 结果连同一个局域网里的设备都扫不到（小米手机插着移动数据扫不到盒子）。
     * 每张网卡各扫各自的 /24，并且请求绑定本网卡的 local，才能做到「10 扫 10、192 扫 192」（马先生 2026-11-22）。
     */
    public static class Subnet {
        public final String local;
        public final List<String> targets;

        Subnet(String local, List<String> targets) {
            this.local = local;
            this.targets = targets;
        }

        @Override
        public String toString() {
            return local + "/24(" + targets.size() + ")";
        }
    }

    /** 每张 IPv4 网卡一个扫描组：一律按 255.255.255.0(/24) 算自己那一段，互不混用。 */
    public static List<Subnet> groups() {
        Set<String> self = selfIps();
        List<Subnet> result = new ArrayList<>();
        for (NetworkInterface nif : interfaces()) {
            for (InterfaceAddress item : nif.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                String host = address.getHostAddress();
                if (host.startsWith("169.254.")) continue; // 没网时的假 IP
                long ip = toLong(address);
                if (ip <= 0) continue;
                long mask = mask(24);
                long begin = (ip & mask) + 1;
                long end = (ip & mask) + MAX_PER_SUBNET - 1;
                List<String> targets = new ArrayList<>();
                for (long value = begin; value <= end && targets.size() < MAX_PER_SUBNET; value++) {
                    String target = toIp(value);
                    if (self.contains(target)) continue; // 别扫自己
                    if (target.endsWith(".0") || target.endsWith(".255")) continue;
                    targets.add(target);
                    if (targets.size() >= MAX_TOTAL) break;
                }
                if (!targets.isEmpty()) result.add(new Subnet(host, targets));
            }
        }
        return result;
    }

    /**
     * 挑一个**跟对方同一个网段**的本机 IP。
     *
     * 多网卡的情况下这一步不能省：UDP 单播时我们要把自己的接收地址报给对方，
     * 要是报了张根本到不了它那个网段上的网卡地址，对方发过来的包我们一个也收不着。
     */
    public static String localFor(String peerIp) {
        long peer = toLong0(peerIp);
        if (peer > 0) {
            for (NetworkInterface nif : interfaces()) {
                for (InterfaceAddress item : nif.getInterfaceAddresses()) {
                    InetAddress address = item.getAddress();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    short bits = item.getNetworkPrefixLength();
                    if (bits < 1 || bits > 32) continue;
                    long m = mask(bits);
                    if ((toLong(address) & m) == (peer & m)) return address.getHostAddress();
                }
            }
        }
        Set<String> self = selfIps();
        return self.isEmpty() ? "" : self.iterator().next();
    }

    /**
     * 每个网段的定向广播地址（192.168.3.255 那一类的），外加整机的 255.255.255.255。
     *
     * 为什么要额外探这几个：有的路由器把「这台主机 → 那台主机」的单播掐了（组播/广播隔离除外），
     * 却放行发往广播地址的包；广播端照样听得见，而它的回包是单播回到我们自己的端口，通常没事。
     * 一网段的规模就几个包，多撒这一把不亏。
     */
    public static List<String> broadcasts() {
        List<String> result = new ArrayList<>();
        for (NetworkInterface nif : interfaces()) {
            for (InterfaceAddress item : nif.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                short bits = item.getNetworkPrefixLength();
                if (bits < 1 || bits > 30) continue;
                long ip = toLong(address);
                if (ip <= 0) continue;
                if (address.getHostAddress().startsWith("169.254.")) continue;
                long value = (ip & mask(bits)) | ((1L << (32 - bits)) - 1);
                String host = toIp(value);
                if (!result.contains(host)) result.add(host);
            }
        }
        result.add("255.255.255.255");
        return result;
    }

    private static long toLong0(String ip) {
        if (ip == null || ip.isEmpty()) return 0L;
        try {
            long result = 0L;
            for (byte item : InetAddress.getByName(ip).getAddress()) result = (result << 8) | (item & 0xFF);
            return result;
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static List<NetworkInterface> interfaces() {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    private static long mask(int bits) {
        return bits <= 0 ? 0L : (bits >= 32 ? 0xFFFFFFFFL : ((1L << bits) - 1) << (32 - bits));
    }

    private static long toLong(InetAddress address) {
        long result = 0;
        byte[] data = address.getAddress();
        for (byte item : data) result = (result << 8) | (item & 0xFF);
        return result;
    }

    private static String toIp(long value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "." + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
