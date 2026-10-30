package com.fongmi.android.tv.cast;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫到的一个广播端（也就是「谁在这儿广播」清单里的一行）。
 *
 * 列表要给用户看的是：**这台是什么设备、IP 最后一段、正在放什么**。
 * 完整的 IP 留着连的时候用，界面上只露尾巴（马先生 2026-10-31 定的样式）。
 */
public final class CastHost {

    private final String uuid;
    private final String name;
    private final String ip;
    private final int port;
    private final String sid;
    private final String nonce;
    private String kind;
    private String title;
    private String ep;
    private volatile long seen;

    public CastHost(String uuid, String name, String ip, int port, String sid, String nonce, String kind, String title, String ep) {
        this.uuid = uuid == null ? "" : uuid;
        this.name = name == null ? "" : name;
        this.ip = ip == null ? "" : ip;
        this.port = port;
        this.sid = sid == null ? "" : sid;
        this.nonce = nonce == null ? "" : nonce;
        this.kind = kind == null ? CastPacket.KIND_VIDEO : kind;
        this.title = title == null ? "" : title;
        this.ep = ep == null ? "" : ep;
        this.seen = System.currentTimeMillis();
    }

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public String getSid() {
        return sid;
    }

    public String getNonce() {
        return nonce;
    }

    public String getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getEp() {
        return ep;
    }

    public long getSeen() {
        return seen;
    }

    /** 同一个广播端又回了一次 HI：内容可能换了戏，只更新这几项和最后露面时间 */
    void refresh(CastPacket packet) {
        this.kind = packet.kind();
        this.title = packet.title();
        this.ep = packet.ep();
        this.seen = System.currentTimeMillis();
    }

    /** IP 最后一段：192.168.3.20 → 20，列表上就用它区分同屋的几台机器 */
    public String getTail() {
        int index = ip.lastIndexOf('.');
        return index < 0 ? ip : ip.substring(index + 1);
    }

    /** 列表标题：「客厅索尼 · .20」（设备名 + IP 尾巴） */
    public String getLabel() {
        if (name.isEmpty()) return "." + getTail();
        return name + " · ." + getTail();
    }

    /** 列表副标题：正在放什么。屏幕广播没有片名，就写「屏幕」 */
    public String getPlaying() {
        if (CastPacket.KIND_SCREEN.equals(kind)) return "屏幕";
        if (title.isEmpty()) return "";
        return ep.isEmpty() ? title : title + "　" + ep;
    }

    public static List<CastHost> sorted(List<CastHost> items) {
        List<CastHost> result = new ArrayList<>(items);
        result.sort((a, b) -> {
            int c = compareIp(a.getIp(), b.getIp());
            if (c != 0) return c;
            return a.getName().compareTo(b.getName());
        });
        return result;
    }

    /** 按 IP 的数字大小排（不能按字符串，不然 192.168.3.9 会排在 .20 后面） */
    private static int compareIp(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            try {
                int p = Integer.parseInt(x[i]);
                int q = Integer.parseInt(y[i]);
                if (p != q) return Integer.compare(p, q);
            } catch (Exception e) {
                return x[i].compareTo(y[i]);
            }
        }
        return Integer.compare(x.length, y.length);
    }
}
